package ix.idg.controllers;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Transaction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jolbox.bonecp.BoneCPDataSource;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.NamespaceFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.models.Keyword;
import ix.core.models.Namespace;
import ix.core.models.Predicate;
import ix.core.models.Publication;
import ix.core.models.Text;
import ix.core.models.VNum;
import ix.core.models.XRef;
import ix.core.models.Structure;
import ix.core.plugins.TextIndexerPlugin;
import ix.core.plugins.StructureReceiver;
import ix.core.plugins.StructureProcessorPlugin;
import ix.core.plugins.PersistenceQueue;
import ix.core.search.TextIndexer;
import ix.idg.models.Disease;
import ix.idg.models.Target;
import ix.idg.models.Ligand;
import play.Logger;
import play.Play;
import play.data.DynamicForm;
import play.data.Form;
import play.db.DB;
import play.db.ebean.Model;
import play.db.ebean.Transactional;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumSet;
import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
    
public class TcrdRegistry extends Controller {
    public static final String DISEASE = "IDG Disease";
    public static final String DEVELOPMENT = Target.IDG_DEVELOPMENT;
    public static final String FAMILY = Target.IDG_FAMILY;
    public static final String DRUG = "IDG Drug";
    public static final String ZSCORE = "IDG Z-score";
    public static final String CONF = "IDG Confidence";
    public static final String GENERIF = "IDG GeneRIF";
    public static final String TARGET = "IDG Target";

    static final Model.Finder<Long, Target> targetDb = 
        new Model.Finder(Long.class, Target.class);
    static final Model.Finder<Long, Disease> diseaseDb = 
        new Model.Finder(Long.class, Disease.class);
    
    static final TextIndexer INDEXER = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();
    static final StructureProcessorPlugin PROCESSOR =
        Play.application().plugin(StructureProcessorPlugin.class);
    static final PersistenceQueue PQ =
        Play.application().plugin(PersistenceQueue.class);

    static final ConcurrentMap<String, Disease> diseases =
        new ConcurrentHashMap<String, Disease>();

    static final DrugTargetOntology dto = new DrugTargetOntology();

    public static final Namespace namespace = NamespaceFactory.registerIfAbsent
        ("TCRDv096", "http://habanero.health.unm.edu");

    static class TcrdTarget implements Comparable<TcrdTarget> {
        String acc;
        String family;
        String tdl;
        Long id;
        Long protein;

        TcrdTarget () {}
        TcrdTarget (String acc, String family, String tdl,
                    Long id, Long protein) {
            this.acc = acc;
            if ("nr".equalsIgnoreCase(family))
                this.family = "Nuclear Receptor";
            else if ("ic".equalsIgnoreCase(family))
                this.family = "Ion Channel";
            else 
                this.family = family;
            this.tdl = tdl;
            this.id = id;
            this.protein = protein;
        }

        public int hashCode () {
            return acc == null ? 1 : acc.hashCode();
        }
        public boolean equals (Object obj) {
            if (obj instanceof TcrdTarget) {
                return acc.equals(((TcrdTarget)obj).acc);
            }
            return false;
        }
        public int compareTo (TcrdTarget t) {
            return acc.compareTo(t.acc);
        }
    }


    static class PersistRegistration
        extends PersistenceQueue.AbstractPersistenceContext {
        final Connection con;
        final Http.Context ctx;
        final ChemblRegistry chembl;
        final Collection<TcrdTarget> targets;
        PreparedStatement pstm, pstm2, pstm3, pstm4, pstm5, pstm6;
        
        PersistRegistration (Connection con, Http.Context ctx,
                             Collection<TcrdTarget> targets,
                             ChemblRegistry chembl)
            throws SQLException {
            this.con = con;
            this.ctx = ctx;
            this.targets = targets;
            pstm = con.prepareStatement
                ("select * from target2disease where target_id = ?");
            pstm2 = con.prepareStatement
                ("select * from chembl_activity where target_id = ?");
            pstm3 = con.prepareStatement
                ("select * from drugdb_activity where target_id = ?");
            pstm4 = con.prepareStatement
                ("select * from generif where protein_id = ?");
            this.chembl = chembl;
        }

        public void persists () throws Exception {
            for (TcrdTarget t : targets)
                persists (t);
        }

        public void shutdown () throws SQLException {
            pstm.close();
            pstm2.close();
            pstm3.close();
            pstm4.close();
            chembl.shutdown();
        }

        void persists (TcrdTarget t) throws Exception {
            Http.Context.current.set(ctx);
            Logger.debug(Thread.currentThread().getName()
                         +": "+t.family+" "+t.tdl+" "+t.acc+" "+t.id);
            
            final Target target = new Target ();
            target.idgFamily = t.family;
            for (Target.TDL tdl : EnumSet.allOf(Target.TDL.class)) {
                if (t.tdl.equals(tdl.name)) 
                    target.idgTDL = tdl;
            }
            assert target.idgTDL != null
                : "Unknown TDL "+t.tdl;
            
            target.synonyms.add
                (new Keyword (TARGET, String.valueOf(t.id)));

            Logger.debug("...uniprot registration");
            UniprotRegistry uni = new UniprotRegistry ();
            uni.register(target, t.acc);

            Logger.debug("...disease linking");
            pstm.setLong(1, t.id);
            new RegisterDiseaseRefs (target, namespace, pstm).persists();

            Logger.debug("...gene RIF linking");
            pstm4.setLong(1, t.protein);
            new RegisterGeneRIFs (target, pstm4).persists();

            Logger.debug("...ligand linking");
            pstm2.setLong(1, t.id);
            pstm3.setLong(1, t.id);
            new RegisterLigands (chembl, target, pstm2, pstm3).persists();

            // make sure all changes are update
            target.update();
            INDEXER.update(target);
        }
    }

    static class RegisterDiseaseRefs
        extends PersistenceQueue.AbstractPersistenceContext {
        final Target target;
        final Namespace namespace;
        final PreparedStatement pstm;

        RegisterDiseaseRefs (Target target,
                         Namespace namespace, PreparedStatement pstm) {
            this.target = target;
            this.namespace = namespace;
            this.pstm = pstm;
        }

        public void persists () throws Exception {
            ResultSet rs = pstm.executeQuery();
            try {               
                Keyword family = KeywordFactory.registerIfAbsent
                    (Target.IDG_FAMILY, target.idgFamily, null);
                Keyword clazz = KeywordFactory.registerIfAbsent
                    (Target.IDG_DEVELOPMENT, target.idgTDL.name, null);
                Keyword name = KeywordFactory.registerIfAbsent
                    (UniprotRegistry.TARGET, target.name, target.getSelf());

                XRef self = new XRef (target);
                self.namespace = namespace;
                self.properties.add(family);
                self.properties.add(clazz);
                self.properties.add(name);
                self.save();

                int count = 0;
                Map<Long, Disease> neighbors = new HashMap<Long, Disease>();
                while (rs.next()) {
                    String doid = rs.getString("doid");
                    Disease disease = diseases.get(doid);
                    if (disease == null) {
                        List<Disease> dl = DiseaseFactory.finder
                            .where(Expr.and
                                   (Expr.eq("synonyms.label",
                                            DiseaseOntologyRegistry.DOID),
                                    Expr.eq("synonyms.term", doid)))
                            .findList();
                    
                        if (dl.isEmpty()) {
                            Logger.warn("Target "+target.id+" references "
                                        +"unknown disease "+doid);
                            continue;
                        }
                        else if (dl.size() > 1) {
                            Logger.warn("Disease "+doid+" maps to "+dl.size()
                                        +" entries!");
                            for (Disease d : dl)
                                Logger.warn("..."+d.id+" "+d.name);
                            
                        }
                        disease = dl.iterator().next();
                        diseases.putIfAbsent(doid, disease);
                    }
                    
                    double zscore = rs.getDouble("zscore");
                    double conf = rs.getDouble("conf");
                    
                    XRef xref = null;
                    for (XRef ref : target.links) {
                        if (ref.referenceOf(disease)) {
                            xref = ref;
                            break;
                        }
                    }
                    
                    if (xref != null) {
                        Logger.warn("Disease "+disease.id+" ("
                                    +disease.name+") is "
                                    +"already linked with target "
                                    +target.id+" ("+target.name+")");
                    }
                    else {
                        xref = new XRef (disease);
                        xref.namespace = namespace;
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (DISEASE, disease.name, xref.getHRef());
                        xref.properties.add(kw);
                        xref.properties.add(new VNum (ZSCORE, zscore));
                        xref.properties.add(new VNum (CONF, conf));
                        xref.save();
                        target.links.add(xref);
                        target.update();
                        INDEXER.update(target);
                        
                        // now add all the unique parents of this disease node
                        getNeighbors (neighbors, disease.links);
                    
                        // link the other way
                        try {
                            disease.links.add(self);
                            disease.update();
                            INDEXER.update(disease);
                            ++count;
                        }
                        catch (Exception ex) {
                            Logger.warn("Disease "+disease.id+" ("
                                        +disease.name+")"
                                        +" is already link with target "
                                        +target.id+" ("+target.name+"): "
                                        +ex.getMessage());
                        }
                    }
                }
                Logger.debug("...."+count+" disease xref(s) added!");
            }
            finally {
                rs.close();
            }
        }

        void getNeighbors (Map<Long, Disease> neighbors, List<XRef> links) {
            for (XRef xr : links) {
                if (Disease.class.getName().equals(xr.kind)) {
                    Disease neighbor = (Disease)xr.deRef();
                    neighbors.put(neighbor.id, neighbor);
                    // recurse
                    getNeighbors (neighbors, neighbor.links);
                }
            }
        }
    } // RegisterDiseaseRefs ()

    static class RegisterGeneRIFs 
        extends PersistenceQueue.AbstractPersistenceContext {
        final Target target;
        final PreparedStatement pstm;
        
        RegisterGeneRIFs (Target target, PreparedStatement pstm) {
            this.target = target;
            this.pstm = pstm;
        }

        public void persists () throws Exception {
            ResultSet rs = pstm.executeQuery();
            try {
                int count = 0;
                while (rs.next()) {
                    long pmid = rs.getLong("pubmed_ids");
                    String text = rs.getString("text");
                    int updates = 0;
                    for (XRef xref : target.links) {
                        if (Publication.class.getName().equals(xref.kind)) {
                            Publication pub = (Publication)xref.deRef();
                            if (pub == null) {
                                Logger.error("XRef "+xref.id+" reference a "
                                             +"bogus publication!");
                            }
                            else if (pmid == pub.pmid) {
                                xref.properties.add(new Text (GENERIF, text));
                                xref.update();
                                ++updates;
                            }
                        }
                    }
                    
                    if (updates > 0) {
                        ++count;
                    }
                }
                
                if (count > 0) {
                    Logger.debug("Updated target "+target.id+": "+target.name
                                 +" with "+count+" GeneRIF references!");
                }
            }
            finally {
                rs.close();
            }
        }
    } // RegisterGeneRIFs

    static class RegisterLigands
        extends PersistenceQueue.AbstractPersistenceContext {
        final ChemblRegistry registry;
        final Target target;
        final PreparedStatement chembl;
        final PreparedStatement drug;
        List<Ligand> ligands = new ArrayList<Ligand>();

        RegisterLigands (ChemblRegistry registry,
                         Target target, PreparedStatement chembl,
                         PreparedStatement drug) throws SQLException {
            this.registry = registry;
            this.target = target;
            this.chembl = chembl;
            this.drug = drug;
        }

        void loadChembl () throws SQLException {
            ResultSet rs = chembl.executeQuery();
            while (rs.next()) {
                String chemblId = rs.getString("cmpd_chemblid");
                Keyword kw = KeywordFactory.registerIfAbsent
                    (ChemblRegistry.ChEMBL_ID, chemblId,
                     "https://www.ebi.ac.uk/chembl/compound/inspect/"
                     +chemblId);
                Ligand ligand = new Ligand
                    (rs.getString("cmpd_name_in_ref"));
                ligand.synonyms.add(kw);
                ligands.add(ligand);
            }
            rs.close();
        }

        void loadDrugs () throws SQLException {
            ResultSet rs = drug.executeQuery();
            while (rs.next()) {
                String drug = rs.getString("drug");
                String ref = rs.getString("reference");
                Keyword kw = KeywordFactory.registerIfAbsent
                    (DRUG, drug, ref != null
                     && ref.startsWith("http") ? ref : null);
                Ligand ligand = new Ligand (drug);
                ligand.synonyms.add(kw);
                ligands.add(ligand);            
            }
            rs.close();
        }

        public void persists () throws Exception {
            loadChembl ();
            loadDrugs ();
            registry.instruments(target, ligands);
            Logger.debug("Target "+target.id+": "+target.name
                         +" has "+ligands.size()+" ligands!");
        }
    } // RegisterLigands
    
    public static Result load () {
        DynamicForm requestData = Form.form().bindFromRequest();
        if (Play.isProd()) { // production..
            String secret = requestData.get("secret-code");
            if (secret == null || secret.length() == 0
                || !secret.equals(Play.application()
                                  .configuration().getString("ix.secret"))) {
                return unauthorized
                    ("You do not have permission to access resource!");
            }
        }
        
        String jdbcUrl = requestData.get("jdbcUrl");
        String jdbcUsername = requestData.get("jdbc-username");
        String jdbcPassword = requestData.get("jdbc-password");
        Logger.debug("JDBC: "+jdbcUrl);

        DataSource ds = null;
        if (jdbcUrl == null || jdbcUrl.equals("")) {
            //return badRequest ("No JDBC URL specified!");
            ds = DB.getDataSource("tcrd");
        }
        else {
            BoneCPDataSource bone = new BoneCPDataSource ();
            bone.setJdbcUrl(jdbcUrl);
            bone.setUsername(jdbcUsername);
            bone.setPassword(jdbcPassword);
            ds = bone;
        }

        if (ds == null) {
            return badRequest ("Neither DataSource \"tcrd\" found "
                               +"nor jdbc url is specified!");
        }

        String maxRows = requestData.get("max-rows");
        Logger.debug("Max Rows: "+maxRows);

        Http.MultipartFormData body = request().body().asMultipartFormData();
        Http.MultipartFormData.FilePart part = body.getFile("load-do-obo");
        if (part != null) {
            String name = part.getFilename();
            String content = part.getContentType();
            Logger.debug("file="+name+" content="+content);
            File file = part.getFile();
            DiseaseOntologyRegistry obo = new DiseaseOntologyRegistry ();
            try {
                diseases.putAll(obo.register(new FileInputStream (file)));
            }
            catch (IOException ex) {
                Logger.trace("Can't load obo file: "+file, ex);
            }
        }

        part = body.getFile("load-dto");
        if (part != null) {
            ObjectMapper mapper = new ObjectMapper();
            try {
                dto.setRoot(mapper.readTree(part.getFile()));
                Logger.debug("Loaded DTO from "+part.getFilename());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Map<String, String> uniprotMap = new HashMap<String, String>();
        part = body.getFile("uniprot-map");
        if (part != null) {
            String name = part.getFilename();
            String content = part.getContentType();
            File file = part.getFile();
            try {
                BufferedReader br = new BufferedReader (new FileReader (file));
                for (String line; (line = br.readLine()) != null; ) {
                    if (line.charAt(0) == '#')
                        continue;
                    String[] toks = line.split("[\\s\t]+");
                    if (2 == toks.length) {
                        uniprotMap.put(toks[0], toks[1]);
                    }
                }
                br.close();
            }
            catch (IOException ex) {
                Logger.trace("Can't load uniprot mapping file: "+file, ex);
            }
            Logger.debug("uniprot-map: file="+name+" content="
                         +content+" count="+uniprotMap.size());
        }

        int count = 0;
        try {
            int rows = 0;
            if (maxRows != null && maxRows.length() > 0) {
                try {
                    rows = Integer.parseInt(maxRows);
                }
                catch (NumberFormatException ex) {
                    Logger.warn("Bogus maxRows \""+maxRows+"\"; default to 0!");
                }
            }
            count = load (ds, 1, rows, uniprotMap);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }

        return redirect (routes.IDGApp.index());
    }

    static int load (DataSource ds, int threads, int rows,
                     Map<String, String> uniprotMap) throws Exception {

        Set<TcrdTarget> targets = new HashSet<TcrdTarget>();    

        Connection con = ds.getConnection();
        Statement stm = con.createStatement();
        int count = 0;
        try {
            ResultSet rset = stm.executeQuery
                ("select * from t2tc a, target b, protein c\n"+
                 "where a.target_id = b.id\n"+
                 "and a.protein_id = c.id "
                 +" order by c.id, c.uniprot "           
                 +(rows > 0 ? ("limit "+rows) : "")
                 );
            while (rset.next()) {
                long protId = rset.getLong("protein_id");
                if (rset.wasNull()) {
                    Logger.warn("Not a protein target: "
                                +rset.getLong("target_id"));
                    continue;
                }
                
                long id = rset.getLong("target_id");
                String fam = rset.getString("idgfam");
                String tdl = rset.getString("tdl");
                String acc = rset.getString("uniprot");
                List<Target> tlist = targetDb
                    .where().eq("synonyms.term", acc).findList();
                
                if (tlist.isEmpty()) {
                    TcrdTarget t =
                        new TcrdTarget (acc, fam, tdl, id, protId);
                    targets.add(t);
                }
            }
            rset.close();
            stm.close();
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        finally {
            con.close();
        }

        ChemblRegistry chembl = new ChemblRegistry (uniprotMap);
        PersistRegistration regis = new PersistRegistration
            (ds.getConnection(), Http.Context.current(),
             targets, chembl);
        PQ.submit(regis);
        
        return count;
    }
    
    public static Result index () {
        return ok (ix.idg.views.html.tcrd.render("IDG TCRD Loader"));
    }
}
