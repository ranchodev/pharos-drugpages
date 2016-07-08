package ix.idg.controllers;

import com.avaje.ebean.Expr;
import com.jolbox.bonecp.BoneCPDataSource;
import ix.core.chem.StructureProcessor;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.controllers.PublicationFactory;
import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.idg.models.*;
import ix.seqaln.SequenceIndexer;
import tripod.chem.indexer.StructureIndexer;

import play.Logger;
import play.Play;
import play.cache.Cache;
import play.data.DynamicForm;
import play.data.Form;
import play.db.DB;
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
    
public class TcrdRegistry extends Controller implements Commons {

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
    static final SequenceIndexer SEQIDX = Play.application()
        .plugin(SequenceIndexerPlugin.class).getIndexer();
    static final StructureIndexer MOLIDX = Play.application()
        .plugin(StructureIndexerPlugin.class).getIndexer();
    
    static final ConcurrentMap<String, Disease> DISEASES =
        new ConcurrentHashMap<String, Disease>();
    static final ConcurrentMap<Long, Target> TARGETS =
        new ConcurrentHashMap<Long, Target>();
    static final ConcurrentMap<String, Ligand> LIGS =
        new ConcurrentHashMap<String, Ligand>();
    
    static final ConcurrentMap<Long, Ligand> LIGANDS =
        new ConcurrentHashMap<Long, Ligand>();

    static final DrugTargetOntology dto = new DrugTargetOntology();

    public static Namespace namespace;
    static public class LigandStructureReceiver implements StructureReceiver {
        final Ligand ligand;
        final Keyword source;
        
        public LigandStructureReceiver (Keyword source, Ligand ligand) {
            this.ligand = ligand;
            this.source = source;
        }

        public String getSource () { return source.term; }
        public void receive (Status status, String mesg, Structure struc) {
            //Logger.debug(status+": ligand "+ligand.getName()+" struc "+struc);
            if (status == Status.OK) {
                try {
                    if (struc != null) {
                        struc.properties.add(source);
                        //struc.save();
                        
                        XRef xref = new XRef (struc);
                        xref.properties.add(source);
                        xref.save();
                        ligand.links.add(xref);
                        ligand.update();
                        INDEXER.update(ligand);
                    }
                    Logger.debug
                        (status+": Ligand "+ligand.id+" "+ligand.getName());
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            else {
                Logger.error(status+": "+ligand.getName()+": "+mesg);
            }
        }
    }
    
    static class TcrdTarget implements Comparable<TcrdTarget> {
        String acc;
        String family;
        String tdl;
        Long id;
        Long protein;
        Double novelty;
        Keyword source;
        DTOParser.Node dtoNode;
        DTOParser dto;

        TcrdTarget () {}
        TcrdTarget (String acc, String family, String tdl,
                    Long id, Long protein, Double novelty,
                    Keyword source) {
            if (family == null || family.equals("")) 
                family = "Unknown";
            if (tdl == null || tdl.equals(""))
                tdl = "Other";

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
            this.novelty = novelty;
            this.source = source;
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
        PreparedStatement pstm, pstm2, pstm3, pstm4,
            pstm5, pstm6, pstm7, pstm8, pstm9, pstm10,
            pstm11, pstm12, pstm13, pstm14, pstm15,
            pstm16, pstm17, pstm18, pstm19, pstm20,
            pstm21, pstm22, pstm23, pstm24, pstm25,
            pstm26, pstm27, pstm28;
        Map<String, Keyword> datasources = new HashMap<String, Keyword>();

        // xrefs for the current target
        Map<String, List<String>> xrefs =
            new HashMap<String, List<String>>();
        Map<String, Keyword> phenotypeSource = new HashMap<String, Keyword>();

        Map<Target.TDL, Keyword> tdlKeywords =
            new EnumMap<Target.TDL,Keyword>(Target.TDL.class);
        Map<String, Keyword> famKeywords = new HashMap<String, Keyword>();
        Map<String, Map<String, Keyword>> keywords =
            new HashMap<String, Map<String, Keyword>>();
        
        PersistRegistration (Connection con, Http.Context ctx,
                             Collection<TcrdTarget> targets,
                             ChemblRegistry chembl)
            throws SQLException {
            this.con = con;
            this.ctx = ctx;
            this.targets = targets;


            // For some reason it is possible that a target has entries in target2disease and tinx_novelty
            // but no entries in tinx_importance. Example is Q8WXS5. As a result the SQL below would not
            // return anything
            pstm = con.prepareStatement
                    ("select distinct " +
                     "a.target_id, d.doid, d.name, d.score as diseaseNovelty, c.score as importance,  " +
                     "e.uniprot, f.score as targetNovelty " +
                     "from target2disease a, tinx_disease d, tinx_importance c, protein e, tinx_novelty f " +
                     "where a.target_id = ? " +
                     "and a.doid = d.doid " +
                     "and c.protein_id = a.target_id " +
                     "and c.disease_id = d.id " +
                     "and e.id = a.target_id " +
                     "and f.protein_id = a.target_id");
            pstm2 = con.prepareStatement
                ("select * from chembl_activity where target_id = ?");
            pstm3 = con.prepareStatement
                ("select * from drug_activity where target_id = ?");
            pstm4 = con.prepareStatement
                ("select * from generif where protein_id = ?");
            pstm5 = con.prepareStatement
                ("select * from dto_classification "
                 +"where protein_id = ? order by id");
            pstm6 = con.prepareStatement
                ("select * from tdl_info where protein_id = ?");
            pstm7 = con.prepareStatement
                ("select * from phenotype where protein_id = ?");
            pstm8 = con.prepareStatement
                ("select * from expression where protein_id = ? ");
            pstm9 = con.prepareStatement
                ("select a.*, uniprot from goa a, protein b where a.protein_id = ? and a.protein_id = b.id");
            pstm10 = con.prepareStatement
                ("select * from panther_class a, p2pc b "
                 +"where a.id = b.panther_class_id and b.protein_id = ?");
            pstm11 = con.prepareStatement
                ("select * from pathway where protein_id = ?");
            pstm12 = con.prepareStatement
                ("select * from xref where protein_id = ?");
            pstm13 = con.prepareStatement
                ("select * from patent_count where "
                 +"protein_id = ? order by year");
            pstm14 = con.prepareStatement
                ("select * from protein where id = ?");
            pstm15 = con.prepareStatement
                ("select * from alias where protein_id = ?");
            pstm16 = con.prepareStatement
                ("select * from chembl_activity where target_id = ?");
            pstm17 = con.prepareStatement
                ("select * from drug_activity where target_id = ?");
            pstm18 = con.prepareStatement("select p.sym, p.uniprot, hg.*, gat.* " +
                    "from target t, t2tc, protein p, hgram_cdf hg, gene_attribute_type gat " +
                    "WHERE t.id = t2tc.target_id AND t2tc.protein_id = p.id AND p.id = hg.protein_id " +
                    "AND gat.name = hg.type and hg.protein_id = ?");

            pstm19 = con.prepareStatement
                ("select * from target2grant where target_id = ?");

            pstm20 = con.prepareStatement
                ("select * from target2disease where target_id = ? "
                 +"order by doid desc");

            pstm21 = con.prepareStatement
                ("select * from mlp_assay_info where protein_id = ? order by aid");

            pstm22 = con.prepareStatement
                ("select * from protein2pubmed a, pubmed b "
                 +"where a.pubmed_id = b.id and a.protein_id = ?");

            pstm23 = con.prepareStatement
                ("select distinct cmpd_chemblid "
                 +"from drug_activity where drug = ?");

            pstm24 = con.prepareStatement
                ("select distinct cmpd_name_in_ref "
                 +"from chembl_activity where cmpd_chemblid = ?");

            pstm25 = con.prepareStatement
                ("select * from pmscore where protein_id = ? order by year");

            pstm26 = con.prepareStatement
                ("select * from ppi where protein1_id = ?");

            pstm27 = con.prepareStatement
                ("select * from compartment where protein_id = ?");

            pstm28 = con.prepareStatement
                ("select * from techdev_contact a, techdev_info b where "
                 +"a.id = b.contact_id and b.protein_id = ?");

            this.chembl = chembl;
        }

        Keyword getTdlKw (Target.TDL tdl) {
            Keyword kw = tdlKeywords.get(tdl);
            if (kw == null) {
                kw = KeywordFactory.registerIfAbsent(IDG_DEVELOPMENT, tdl.name, null);
                tdlKeywords.put(tdl, kw);
            }
            return kw;
        }

        Keyword getFamKw (String fam) {
            Keyword kw = famKeywords.get(fam);
            if (kw == null) {
                kw = KeywordFactory.registerIfAbsent(IDG_FAMILY, fam, null);
                famKeywords.put(fam, kw);
            }
            return kw;
        }
        
        Keyword getKeyword (String label, String value) {
            return getKeyword (label, value, null);
        }
        
        Keyword getKeyword (String label, String value, String href) {
            Map<String, Keyword> keys = keywords.get(label);
            if (keys == null) {
                keywords.put(label, keys = new HashMap<String, Keyword>());
            }
            
            Keyword kw = keys.get(value);
            if (kw == null) {
                keys.put(value, kw = KeywordFactory.registerIfAbsent(label, value, href));
            }
            return kw;
        }

        public void persists () throws Exception {
            java.util.Date start = new java.util.Date();
            for (TcrdTarget t : targets) {
                persists (t);
            }
            
            for (Map.Entry<Long, Target> me : TARGETS.entrySet()) {
                try {
                    long protein = me.getKey();
                    addPPI (me.getValue(), protein);
                    INDEXER.update(me.getValue());
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            
            for (Ligand l : LIGS.values()) {
                try {
                    //l.update();
                    INDEXER.update(l);              
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            for (Disease d : DISEASES.values()) {
                try {
                    Value drug = d.getProperty(IDG_DRUG);
                    if (drug != null) {
                        Keyword kw = (Keyword)drug;
                        Ligand lig = LIGS.get(kw.term);
                        if (lig != null) {
                            XRef ref = d.addIfAbsent(new XRef (lig));
                            if (ref.id == null)
                                ref.save();
                            d.update();
                        }
                        else {
                            Logger.warn("Can't find drug \""
                                        +kw.term+"\" for disease "
                                        +d.id+" "+d.name);
                        }
                    }
                    //d.update();             
                    INDEXER.update(d);              
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            Logger.debug("\n#### PERSISTENCE STARTED ON "+start+" ####"
                         +"\n#### AND COMPLETE AT "
                         +new java.util.Date()+"! #####");
        }

        public void shutdown () throws SQLException {
            pstm.close();
            pstm2.close();
            pstm3.close();
            pstm4.close();
            pstm5.close();
            pstm6.close();
            pstm7.close();
            pstm8.close();
            pstm9.close();
            pstm10.close();
            pstm11.close();
            pstm12.close();
            pstm13.close();
            pstm14.close();
            pstm15.close();
            pstm16.close();
            pstm17.close();
            pstm18.close();
            pstm19.close();
            pstm20.close();
            pstm21.close();
            pstm22.close();
            pstm23.close();
            pstm24.close();
            pstm25.close();
            pstm26.close();
            pstm27.close();
            pstm28.close();
            chembl.shutdown();
        }

        void instrument (Target target, TcrdTarget t) throws Exception {
            //Logger.debug("... instrumenting target " +t.id);
            target.synonyms.add(new Keyword (IDG_TARGET, "TCRD:"+t.id));
            target.properties.add(t.source);
            
            if (t.novelty != null) {
                // log10
                target.novelty = Math.log10(t.novelty);
            }
            
            String value;
            
            xrefs.clear();
            pstm12.setLong(1, t.protein);
            ResultSet rset = pstm12.executeQuery();
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            while (rset.next()) {
                String xtype = rset.getString("xtype");
                value = rset.getString("value");
                //Logger.info("  + "+xtype+": "+value);
                Integer c = counts.get(xtype);
                counts.put(xtype, c==null?1:(c+1));
                if ("uniprot keyword".equalsIgnoreCase(xtype)) {
                    String term = rset.getString("xtra");
                    if (term != null) {
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (UNIPROT_KEYWORD, term.replaceAll("/","-"),
                             "http://www.uniprot.org/keywords/"+value);

                        target.addIfAbsent((Value)kw);
                    }
                }
                else if ("pdb".equalsIgnoreCase(xtype)) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (PDB_ID, value,
                         "http://www.rcsb.org/pdb/explore/explore.do?structureId="
                         +value);
                    target.addIfAbsent(kw);
                }
                else if ("pubmed".equalsIgnoreCase(xtype)) {
                    /*
                    Publication pub = PublicationFactory.registerIfAbsent
                        (Long.parseLong(value));
                    if (pub != null)
                        target.addIfAbsent(pub);
                    */
                    target.properties.add
                        (new VInt (PUBMED_ID, Long.parseLong(value)));
                }
                else {
                    List<String> names = xrefs.get(xtype);
                    if (names == null) {
                        xrefs.put(xtype, names = new ArrayList<String>());
                    }
                    names.add(value);
                }
            }
            rset.close();

            Keyword source =  KeywordFactory.registerIfAbsent
                (SOURCE, "UniProt", "http://www.uniprot.org");
            datasources.put("UniProt", source);

            pstm14.setLong(1, t.protein);
            rset = pstm14.executeQuery();
            while (rset.next()) {
                target.name = rset.getString("description");
                value = rset.getString("uniprot");
                
                Keyword kw = new Keyword (UNIPROT_ACCESSION, value);
                kw.href = "http://www.uniprot.org/uniprot/"+value;
                Keyword _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();

                kw = new Keyword (STRING_ID, rset.getString("stringid"));
                _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();

                kw = new Keyword (UNIPROT_NAME, rset.getString("name"));
                _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();

                //Gene gene = GeneFactory.registerIfAbsent(rset.getString("sym"));
                //target.links.add(new XRef (gene));
                value = rset.getString("sym");
                kw = new Keyword (UNIPROT_GENE, value);
                kw.href = 
                    "http://www.genenames.org/cgi-bin/gene_symbol_report?match="+value;
                _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();
                
                value = String.valueOf(rset.getLong("geneid"));
                kw = new Keyword (ENTREZ_GENE, value);
                kw.href = "http://www.ncbi.nlm.nih.gov/gene/"+value;
                _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();

                value = rset.getString("dtoid");
                kw = new Keyword (DTO_ID, value);
                kw.href = "http://drugtargetontology.org/"+value;
                _kw = target.addIfAbsent(kw);
                if (_kw == kw)
                    kw.save();

                Text seq = new Text (UNIPROT_SEQUENCE, rset.getString("seq"));
                seq.save();
                
                SEQIDX.add(String.valueOf(seq.id), seq.text);
                target.properties.add(seq);
            }
            rset.close();

            pstm15.setLong(1, t.protein);
            rset = pstm15.executeQuery();
            while (rset.next()) {
                String type = rset.getString("type");
                value = rset.getString("value");
                if ("uniprot".equalsIgnoreCase(type)) {
                    Keyword kw = new Keyword (UNIPROT_ACCESSION, value);
                    kw.href = "http://www.uniprot.org/uniprot/"+value;
                    Keyword _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();
                }
                else if ("symbol".equalsIgnoreCase(type)) {
                    Keyword kw = new Keyword (UNIPROT_SHORTNAME, value);
                    Keyword _kw = target.addIfAbsent(kw);
                    if (_kw == kw)
                        kw.save();
                }
                Logger.info("  + "+type+": "+value);
            }
            rset.close();
            
            target.properties.add(source);
            Logger.debug("Target "+target.id+" xtype: "+counts);
        }
                         
        void addPatent (Target target, long protein) throws Exception {
            pstm13.setLong(1, protein);
            ResultSet rset = pstm13.executeQuery();
            Timeline timeline = null;
            while (rset.next()) {
                long year = rset.getLong("year");
                long count = rset.getLong("count");
                if (timeline == null) {
                    timeline = new Timeline ("Patent Count");
                }
                Event event = new Event ();
                event.start = year;
                event.end = count; // abusing notation
                event.unit = Event.Resolution.YEARS;
                timeline.events.add(event);
            }
            rset.close();
            
            if (timeline != null) {
                timeline.save();
                target.links.add(new XRef (timeline));
            }
        }

        void addPMScore (Target target, long protein) throws Exception {
            pstm25.setLong(1, protein);
            ResultSet rset = pstm25.executeQuery();
            Timeline timeline = null;
            while (rset.next()) {
                long year = rset.getLong("year");
                double score = rset.getDouble("score");
                if (timeline == null) {
                    timeline = new Timeline ("PubMed Score");
                }
                Event event = new Event ();
                event.start = year;
                event.end = (long)(score * 1000 + 0.5); // abusing notation
                event.unit = Event.Resolution.YEARS;
                timeline.events.add(event);
            }
            rset.close();
            
            if (timeline != null) {
                timeline.save();
                target.links.add(new XRef (timeline));
            }
        }

        void addAssay (Target target, long protein) throws Exception {
            pstm21.setLong(1, protein);
            ResultSet rset = pstm21.executeQuery();
            int count = 0;
            while (rset.next()) {
                Assay assay = new Assay (rset.getString("assay_name"));
                assay.type = rset.getString("method");
                assay.properties.add
                    (new VInt (MLP_ASSAY_ACTIVE, rset.getLong("active_sids")));
                assay.properties.add
                    (new VInt (MLP_ASSAY_INACTIVE, rset.getLong("inactive_sids")));
                assay.properties.add
                    (new VInt (MLP_ASSAY_INCONCLUSIVE, rset.getLong("iconclusive_sids")));
                assay.properties.add
                    (new VInt (MLP_ASSAY_AID, rset.getLong("aid")));
                assay.save();
                
                XRef tref = assay.addIfAbsent(new XRef (target));
                tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                
                XRef aref = target.addIfAbsent(new XRef (assay));
                aref.addIfAbsent((Value)KeywordFactory.registerIfAbsent
                                 (MLP_ASSAY_TYPE, assay.type, null));
                // 
                aref.properties.add(new Text (MLP_ASSAY, assay.name));

                try {
                    tref.save();
                    aref.save();
                    assay.update();
                    target.update();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
                ++count;
            }
            rset.close();
            Logger.debug("Target " + target.id + " has " + count +" assay(s)!");    
        }

        void addGrant (Target target, long tid) throws Exception {
            pstm19.setLong(1, tid);
            ResultSet rset = pstm19.executeQuery();
            Set<String> fundingICs = new HashSet<String>();
            Map<String, Integer> activity = new HashMap<String, Integer>();
            int count = 0;
            double cost = 0;
            while (rset.next()) {
                String act = rset.getString("activity");
                if (act != null) {
                    Integer c = activity.get(act);
                    activity.put(act, c != null ? (c+1) : 1);
                }
                String ics = rset.getString("funding_ics");
                if (ics != null) {
                    for (int pos, p = 0; (pos = ics.indexOf('\\', p)) > p; ) {
                        String s = ics.substring(p, pos);
                        String[] toks = s.split(":");
                        if (toks.length == 2) {
                            fundingICs.add(toks[0]);
                        }
                        p = pos;
                    }
                }
                cost += rset.getDouble("cost");
                long appid = rset.getLong("appid");
                if (!rset.wasNull()) {
                    Keyword grant = new Keyword 
                        (GRANT_APPLICATION, rset.getString("full_project_num"));
                    grant.href = "https://projectreporter.nih.gov/project_info_description.cfm?aid="+appid;
                    target.properties.add(grant);
                }
                ++count;
            }
            rset.close();

            for (String a : activity.keySet()) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (GRANT_ACTIVITY, a, null);
                target.properties.add(kw);
            }
            target.r01Count = activity.get("R01");

            for (String ic : fundingICs) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (GRANT_FUNDING_IC, ic, null);
                target.properties.add(kw);
            }
            
            if (count > 0)
                target.grantCount = count;
            
            if (cost > 0.)
                target.grantTotalCost = cost;
        }
        
        void addPathway (Target target, long protein) throws Exception {
            pstm11.setLong(1, protein);
            ResultSet rset = pstm11.executeQuery();
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            while (rset.next()) {
                String source = rset.getString("pwtype");
                String name = rset.getString("name");
                Keyword term = KeywordFactory.registerIfAbsent
                    (source+" Pathway", name, rset.getString("url"));
                target.addIfAbsent((Value)term);
                /*
                Logger.debug("Target "+target.id
                             +" pathway ("+source+"): "+term.term);
                */
                
                if ("Reactome".equals(source)) {
                    List<String> refs = xrefs.get("Reactome");
                    if (refs != null) {
                        String id = refs.iterator().next();
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (REACTOME_REF, id,
                             "http://www.reactome.org/content/query?cluster=true&q="+id);
                        target.addIfAbsent((Value)kw);
                    }
                }
                Integer c = counts.get(source);
                counts.put(source, c==null?1:(c+1));
            }
            rset.close();
            Logger.debug("Target "+target.id+" pathway: "+counts);
        }

        void addHarmonogram(Target target, long protein) throws Exception {
            pstm18.setLong(1, protein);
            ResultSet rset = pstm18.executeQuery();
            int n = 0;
            double cdfSum = 0;
            while (rset.next()) {
                HarmonogramCDF hg = new HarmonogramCDF();
                hg.attrGroup = rset.getString("attribute_group");
                hg.attrType = rset.getString("attribute_type");
                hg.cdf = rset.getDouble("attr_cdf");
                cdfSum += hg.cdf;
                hg.dataSource = rset.getString("name");
                hg.dataSourceUrl = rset.getString("url");
                //hg.dataSourceDescription = rset.getString("description");
                hg.dataType  =rset.getString("resource_group");
                hg.IDGFamily = target.idgFamily;
                hg.TDL = target.idgTDL.name;
                hg.uniprotId = rset.getString("uniprot");
                hg.symbol = rset.getString("sym");
                hg.save();
                n++;
            }
            target.knowledgeAvailability = cdfSum;

            rset.close();
            Logger.debug("Target "+target.id+" has "+n+" harmonogram entries!");
        }

        void addGO (Target target, long protein) throws Exception {
            pstm9.setLong(1, protein);
            ResultSet rset = pstm9.executeQuery();
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            while (rset.next()) {
                String term = rset.getString("go_term");
                String id = rset.getString("go_id");
                String uniprot = rset.getString("uniprot");
                
                Keyword go = null;
                char kind = term.charAt(0);
                term = term.substring(term.indexOf(':')+1)
                        .replaceAll("/","-");
                String href = "http://amigo.geneontology.org/amigo/search/annotation?q=*:*&fq=bioentity:\"UniProtKB:"+uniprot+"\"&sfq=document_category:\"annotation\"&fq=regulates_closure:\""+id+"\"";
                
                switch (kind) {
                case 'C': // component
                    go = KeywordFactory.registerIfAbsent
                        (GO_COMPONENT, term, href);
                    break;
                    
                case 'F': // function
                    go = KeywordFactory.registerIfAbsent
                        (GO_FUNCTION, term, href);
                    break;
                    
                case 'P': // process
                    go = KeywordFactory.registerIfAbsent
                        (GO_PROCESS, term, href);
                    break;
                    
                default:
                    Logger.warn("Unknown GO term \""+term+"\"!");
                }

                if (go != null && !target.properties.contains(go)) {
                    target.properties.add(go);
                    //Logger.debug("Target "+target.id+" GO: "+term);
                }
                Integer c = counts.get(""+kind);
                counts.put(""+kind,c==null?1:(c+1));
            }
            rset.close();
            Logger.debug("Target "+target.id+" GO: "+counts);
        }
        
        void addExpression (Target target, long protein) throws Exception {
            pstm8.setLong(1, protein);
            ResultSet rset = pstm8.executeQuery();
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            Map<String, Keyword> sources = new HashMap<String, Keyword>();
            while (rset.next()) {
                String qv = rset.getString("qual_value");
                if ("Not detected".equalsIgnoreCase(qv))
                    continue;

                Expression expr = new Expression();
                expr.proteinId = protein;
                expr.source = rset.getString("etype");
                expr.tissue = rset.getString("tissue");
                expr.confidence = rset.getDouble("conf");
                expr.qualValue = qv;
                expr.numberValue = rset.getDouble("number_value");
                expr.evidence = rset.getString("evidence");

                String sourceUrl = "";
                Keyword tissue = null;
                if (expr.source.startsWith("GTEx")) {
                    sourceUrl = "http://www.gtexportal.org/";
                    expr.sourceid = GTEx_EXPR;
                    tissue = KeywordFactory.registerIfAbsent
                        (GTEx_TISSUE, expr.tissue, null);
                    target.addIfAbsent((Value)tissue);
                }
                else if (expr.source.startsWith("Consensus")) {
                    sourceUrl = "http://targetcentral.ws";
                    expr.sourceid = IDG_EXPR;
                    tissue = KeywordFactory.registerIfAbsent
                            (IDG_TISSUE, expr.tissue, null);
                    target.addIfAbsent((Value)tissue);
                }
                else if (expr.source.startsWith("HPM Gene")) {
                }
                else if (expr.source.startsWith("HPM Protein")) {
                    sourceUrl = "http://www.humanproteomemap.org";
                    expr.sourceid = HPM_EXPR;
                    tissue = KeywordFactory.registerIfAbsent
                        (HPM_TISSUE, expr.tissue, null);
                    target.addIfAbsent((Value)tissue);
                }
                else if (expr.source.startsWith("JensenLab Text Mining")) {
                    sourceUrl = "http://tissues.jensenlab.org";
                    expr.sourceid = JENSEN_TM_EXPR;
                    // this is specific to each protein
                    tissue = new Keyword (JENSEN_TM_TISSUE, expr.tissue);
                    tissue.href = rset.getString("url");
                    tissue.save();
                    target.properties.add(tissue);
                }
                else if (expr.source.startsWith
                           ("JensenLab Knowledge UniProtKB-RC")) {
                    sourceUrl = "http://tissues.jensenlab.org";
                    expr.sourceid = JENSEN_KB_EXPR;
                    tissue = KeywordFactory.registerIfAbsent
                        (JENSEN_KB_TISSUE, expr.tissue, null);
                    target.addIfAbsent((Value)tissue);
                }
                else if (expr.source.equals("UniProt Tissue")) {
                    tissue = KeywordFactory.registerIfAbsent
                        (expr.source, expr.tissue, null);
                    expr.sourceid = UNIPROT_EXPR;
                    target.addIfAbsent((Value)tissue);              
                }
                else if (expr.source.startsWith("HPA")) {
                    sourceUrl = "http://tissues.jensenlab.org";
                    expr.sourceid = expr.source+" Expression";
                    tissue = KeywordFactory.registerIfAbsent
                        (expr.source+" Tissue", expr.tissue, null);
                    target.addIfAbsent((Value)tissue);
                }
                else if (expr.source.startsWith("JensenLab Experiment")) {
                    sourceUrl = "http://tissues.jensenlab.org";
                    String t = expr.source
                        .substring("JensenLab Experiment".length()+1).trim();
                    expr.sourceid = t+" Expression";
                }
                else
                    Logger.warn("Unknown expression \""+expr.source
                            +"\" for target "+target.id);

                expr.save();
                XRef ref = target.addIfAbsent(new XRef(expr));
                if (tissue != null)
                    ref.properties.add(tissue);
                
                if (ref.id == null)
                    ref.save();
                
                //Logger.debug("Target "+target.id+" "+expr.source+": "+expr.tissue);
                Integer c = counts.get(expr.source);
                counts.put(expr.source, c==null?1:(c+1));

                Keyword source = datasources.get(expr.source);
                if (source == null) {
                    source = KeywordFactory.registerIfAbsent
                            (SOURCE, expr.source, sourceUrl);
                    datasources.put(expr.source, source);
                }
                sources.put(expr.source, source);
            }
            rset.close();

            for (Keyword source: sources.values()) {
                target.addIfAbsent((Value)source);
            }

            List<String> ids = xrefs.get("STRING");
            if (ids != null && !ids.isEmpty()) {
                // 9606.ENSP00000000442
                String id = ids.iterator().next();
                if (id.startsWith("9606.")) {
                    id = id.substring(5);
                    Keyword kw = new Keyword (IDG_TISSUE_REF, id);
                    kw.href = "http://tissues.jensenlab.org/Entity?figures=tissues_body_human&knowledge=10&experiments=10&textmining=10&type1=9606&type2=-25&id1="+id;
                    target.properties.add(kw);
                }
            }
            Logger.debug("Target "+target.id+" expression: "+counts);
        }

        static Pattern OmimRegex = Pattern.compile("([^\\s]+)\\s\\(([1-4])\\)");
        static void parseOMIMPhenotype (String trait, Target target) {
            String[] tokens  = trait.split(";");
            int pos = tokens[0].indexOf(':');
            if (pos > 0) {
                String mim = tokens[0].substring(pos+1).trim();
                Keyword kw = KeywordFactory.registerIfAbsent
                    (OMIM_GENE, "MIM:"+mim,"http://omim.org/entry/"+mim);
                target.addIfAbsent(kw);
            }

            /*
             * MIM Number: 114208; Disorder: Hypokalemic periodic paralysis, type 1, 170400 (3); {Malignant hyperthermia susceptibility 5}, 601887 (3); {Thyrotoxic periodic paralysis, susceptibility to, 1}, 188580 (3); Comments: in mouse, mutation causes muscular dysgenesis
             */
            
            for (int i = 1; i < tokens.length; ++i) {
                String disorder = tokens[i].trim();
                if (disorder.startsWith("Comments:")) {
                    // do nothing..
                }
                else {
                    if (disorder.startsWith("Disorder:")) {
                        disorder = disorder.substring(9);
                    }
                    pos = disorder.lastIndexOf(',');
                    if (pos > 0) {
                        String pheno = disorder.substring(pos+1);
                        if (disorder.charAt(0) == '{') {
                            pos = disorder.indexOf('}');
                            disorder = pos < 0
                                ? disorder.substring(1)
                                : disorder.substring(1, pos);
                        }
                        else {
                            disorder = disorder.substring(0, pos);
                        }
                        
                        Matcher m = OmimRegex.matcher(pheno);
                        if (m.find()) {
                            String id = m.group(1);
                            String key = m.group(2);
                            Logger.debug
                                ("OMIM: "+disorder+" ["+id+"] ("+key+")");
                            if (key.charAt(0) == '3') {
                                disorder = disorder.replaceAll("/", "-");
                                Keyword kw = KeywordFactory.registerIfAbsent
                                    (OMIM_TERM, disorder,
                                     "http://omim.org/entry/"+id);
                                target.properties.add(kw);
                            }
                        }
                    }
                }
            }
        }
        
        void addPhenotype (Target target, long protein) throws Exception {
            pstm7.setLong(1, protein);
            ResultSet rset = pstm7.executeQuery();
            Set<Keyword> terms = new HashSet<>();
            Map<String, Keyword> sources = new TreeMap<String, Keyword>();
            int phenoCount = 0;
            while (rset.next()) {
                String type = rset.getString("ptype");
                if ("impc".equalsIgnoreCase(type)) {
                    Keyword source = datasources.get(type);
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, type,
                             "http://www.mousephenotype.org/data/secondaryproject/idg");
                        datasources.put(type, source);
                    }
                    sources.put(type, source);
                    String term = rset.getString("term_name");
                    String termId = rset.getString("term_id");
                    if (term != null) {
                        Keyword kw = KeywordFactory.registerIfAbsent
                                (IMPC_TERM, term.replaceAll("/","-"),
                                        "http://www.informatics.jax.org/searches/Phat.cgi?id=" + termId);
                        terms.add(kw);
                    }
                }
                else if ("gwas catalog".equalsIgnoreCase(type)) {
                    Keyword source = datasources.get(type);
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, type,
                             "https://www.genome.gov/26525384");
                        datasources.put(type, source);
                    }
                    String trait = rset.getString("trait");
                    if (trait != null) {
                        sources.put(type, source);                      
                        trait = trait.replaceAll("/", "-");
                        Keyword gwas = KeywordFactory.registerIfAbsent
                                (GWAS_TRAIT, trait, null);
                        XRef ref = target.addIfAbsent(new XRef (gwas));
                        ref.addIfAbsent(source);
                        
                        long pmid = rset.getLong("pmid");
                        if (!rset.wasNull()) {
                            ref.properties.add(new VInt (PUBMED_ID, pmid));
                            /*
                            Publication pub =
                                PublicationFactory.registerIfAbsent(pmid);
                            if (pub != null) {
                                XRef ref = target.getLink(pub);
                                Keyword t = KeywordFactory.registerIfAbsent
                                    (GWAS_TRAIT, trait, null);
                                if (ref == null) {
                                    ref = new XRef (pub);
                                    ref.properties.add(source);
                                    ref.properties.add(t);
                                    ref.save();
                                    target.links.add(ref);
                                }
                                else {
                                    boolean add = true;
                                    if (!ref.properties.contains(t)) {
                                        ref.properties.add(t);
                                        ref.update();
                                    }
                                }
                                target.addIfAbsent(pub);
                            }
                            else {
                                Logger.warn("Can't retrieve publication "+pmid+
                                            "for target "+target.id);
                            }
                            */
                        }
                        
                        if (ref.id == null) {
                            ref.save();
                        }
                        
                        // also add this as a property
                        target.addIfAbsent((Value)gwas);
                    }
                }
                else if ("JAX/MGI Human Ortholog Phenotype"
                         .equalsIgnoreCase(type)) {
                    Keyword source = datasources.get(type);             
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, type,
                             "http://www.informatics.jax.org/");
                        datasources.put(type, source);
                    }
                    String pheno = rset.getString("term_name");
                    String termId = rset.getString("term_id");
                    if (pheno != null) {
                        pheno = pheno.replaceAll("/", "-");
                        sources.put(type, source);
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (MGI_TERM, pheno,
                             "http://www.informatics.jax.org/searches/Phat.cgi?id="+termId);
                        target.addIfAbsent((Value)kw);
                        ++phenoCount;
                    }
                }
                else if ("OMIM".equalsIgnoreCase(type)) {
                    Keyword source = datasources.get(type);             
                    if (source == null) {
                        source = KeywordFactory.registerIfAbsent
                            (SOURCE, type, "http://omim.org/");
                        datasources.put(type, source);
                    }
                    String trait = rset.getString("trait");
                    if (trait != null) {
                        sources.put(type, source);
                        parseOMIMPhenotype (trait, target);
                    }
                }
                else {
                    Logger.warn("Unknown phenotype \""+type
                                +"\" for target "+target.id);
                }
            }
            rset.close();

            if (!terms.isEmpty()) {
                for (Keyword term : terms) {
                    target.properties.add(term);
                }
                Logger.debug("Target "+target.id+" has "+terms.size() +" phenotype(s)!");
            }
            
            for (Keyword source: sources.values()) {
                target.addIfAbsent((Value)source);
            }

            if (!terms.isEmpty() || phenoCount > 0) {
                Keyword pheno = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_PHENOTYPES, null);
                target.addIfAbsent((Value)pheno);
            }
        }
        
        void addTDL (Target target, long protein) throws Exception {
            pstm6.setLong(1, protein);
            ResultSet rset = pstm6.executeQuery();
            int selective = 0;
            Keyword mice = KeywordFactory.registerIfAbsent
                ("IMPC Mice Produced", "YES", null);
            Map<String, Integer> counts = new TreeMap<String, Integer>();
            while (rset.next()) {
                String type = rset.getString("itype");
                
                Value val = null;
                if (type.equals("IMPC Mice Produced")) {
                    int bv = rset.getInt("boolean_value");
                    if (bv != 0)
                        val = mice;
                }
                else {
                    for (String field : new String[]{"string_value",
                                                     "number_value",
                                                     "integer_value",
                                                     "date_value",
                                                     "boolean_value"}) {
                        if (field.equals("string_value")) {
                            String str = rset.getString(field);
                            if (str != null)
                                val = new Text (type, str);
                        }
                        else if (field.equals("number_value")) {
                            double num = rset.getDouble(field);
                            if (!rset.wasNull())
                                val = new VNum (type, num);
                        }
                        else if (field.equals("boolean_value")) {
                            
                        }
                        else {
                            long num = rset.getLong(field);
                            if (!rset.wasNull())
                                val = new VInt (type, num);
                        }
                    }
                }
                
                if (val != null) {
                    //Logger.debug("Target "+target.id+": "+type);
                    Integer c = counts.get(type);
                    counts.put(type, c==null?1:(c+1));
                    if (type.equalsIgnoreCase("UniProt Function")) {
                        target.description = (String)val.getValue();
                    }
                    else {
                        if (type.equalsIgnoreCase("Ab Count")) {
                            target.antibodyCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase("MAb Count")) {
                            target.monoclonalCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase("PubMed Count")) {
                            target.pubmedCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase("JensenLab PubMed Score")) {
                            target.jensenScore = ((Number)val.getValue()).doubleValue();
                        }
                        else if (type.equalsIgnoreCase
                                 ("EBI Total Patent Count")) {
                            target.patentCount =
                                ((Number)val.getValue()).intValue();
                        }
                        else if (type.equalsIgnoreCase
                                 ("ChEMBL Selective Compound")) {
                            
                            ++selective;
                        }

                        target.properties.add(val);
                    }
                }
                else {
                    Logger.warn("TDL info \""+type+"\" for target "+target.id
                                +" is null!");
                }
            }
            rset.close();

            if (selective > 0) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_SELECTIVE_COMPOUNDS, null);
                target.properties.add(kw);
            }
            
            if ((target.antibodyCount != null && target.antibodyCount > 0)
                || (target.monoclonalCount != null && target.monoclonalCount > 0)) {
                Keyword kw = KeywordFactory.registerIfAbsent
                    (IDG_TOOLS, IDG_TOOLS_ANTIBODIES, null);
                target.properties.add(kw);
            }

            Logger.debug("Target "+target.id+" tdl info: "+counts);
        }

        void addPanther (Target target,long protein) throws Exception {
            pstm10.setLong(1, protein);
            Map<String, String> parents = new HashMap<String, String>();
            Map<String, String> panther = new HashMap<String, String>();
            ResultSet rset = pstm10.executeQuery();
            while (rset.next()) {
                String pcid = rset.getString("pcid");
                String name = rset.getString("name");
                String ancestor = rset.getString("parent_pcids");
                for (String p : ancestor.split("\\|")) 
                    if (!"PC00000".equals(p)) {
                        String old = parents.put(pcid, p);
                        if (old != null && !old.equals(p)) {
                            Logger.warn("Target "+target.id
                                        +" has two Panther parents: "
                                        +p+" and "+old+"; keeping "+p+"!");
                        }
                    }
                panther.put(pcid, name);
            }
            rset.close();

            Keyword[] path = new Keyword[panther.size()];
            for (Map.Entry<String, String> me : panther.entrySet()) {
                int d = 0;
                for (String p = parents.get(me.getKey()); p != null; ++d)
                    p = parents.get(p);
                //Logger.debug("PANTHER "+me.getKey()+" "+d);

                if (path[d] != null) {
                    // ignore this lineage!
                }
                else {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (PANTHER_PROTEIN_CLASS + " ("+d+")",
                         me.getValue(), "http://pantherdb.org/panther/category.do?categoryAcc="+me.getKey());
                    target.properties.add(kw);
                    path[d] = kw;
                }
            }
            
            for (int k = path.length; --k >= 0; ) {
                Keyword node = path[k];
                if (node != null) {
                    List<Predicate> predicates = PredicateFactory.finder.where
                        (Expr.and(Expr.eq("subject.refid", node.id.toString()),
                                  Expr.eq("predicate",
                                          PANTHER_PROTEIN_ANCESTRY)))
                        .findList();
                    if (predicates.isEmpty()) {
                        try {
                            Predicate pred = new Predicate
                                (PANTHER_PROTEIN_ANCESTRY);
                            pred.subject = new XRef (node);
                            for (int j = k; --j >= 0; ) {
                                pred.objects.add(new XRef (path[j]));
                            }
                            pred.save();
                        }
                        catch (Throwable t) {
                            t.printStackTrace();
                        }
                    }
                }
            }
        }
        
        void addDTO (Target target, long protein, DTOParser.Node dtoNode)
            throws Exception {
            List<Keyword> path = new ArrayList<Keyword>();
            Logger.debug("Target "+IDGApp.getId(target)+" "
                         +target.idgFamily+" DTO");

            if (dtoNode != null) {
                dtoNode.url = routes.IDGApp.target
                    (IDGApp.getId(target)).url();
                dtoNode.tdl = target.idgTDL.toString();
                dtoNode.size = 100;
                if (target.novelty != null) {
                    // this assumes novelty is expressed as log10, so that
                    //  "novel" targets are bigger
                    dtoNode.size += (int)(10*target.novelty + 0.5);
                }
                dtoNode.fullname = target.name;
                
                List<DTOParser.Node> nodes = new ArrayList<DTOParser.Node>();
                for (DTOParser.Node node = dtoNode.parent;
                     node != null
                         && !node.id.equals("DTO_00200000") // Gene
                         && !node.id.equals("DTO_00100000") // Protein
                         ; node = node.parent) {
                    nodes.add(node);
                }
                
                Collections.reverse(nodes);
                String enhanced = Play.application()
                    .configuration().getString("ix.idg.dto.enhanced");
                if (enhanced != null) {
                    File file = new File (enhanced);
                    DTOParser.Node node = nodes.iterator().next();
                    DTOParser.writeJson(file, node.parent);
                }
                
                for (DTOParser.Node n : nodes) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (DTO_PROTEIN_CLASS+" ("+path.size()+")",
                         n.name.replaceAll("/", "-"),
                         // not a real url.. 
                         "http://drugtargetontology.org/"+n.id);

                    StringBuilder sb = new StringBuilder ();
                    for (int i = 0; i < path.size(); ++i)
                        sb.append("\t");
                    Logger.debug(kw.id+" "+kw.label+" \""+kw.term+"\" "+kw.href);
                    if (n.url == null)
                        n.url = routes.IDGApp.targets(null, 10, 1).url()
                            +"?facet="+kw.label+"/"+kw.term;
                    
                    target.properties.add(kw);
                    path.add(kw);
                }
            }
            else if (false) { // this is the older version
                Keyword kw = KeywordFactory.registerIfAbsent
                    (DTO_PROTEIN_CLASS + " (0)", target.idgFamily, null);
                target.properties.add(kw);
                path.add(kw);
                
                pstm5.setLong(1, protein);
                ResultSet rset = pstm5.executeQuery();
                while (rset.next()) {
                    String label = rset.getString("name").trim();
                    if (target.idgFamily.equals("GPCR")) {
                        if (label.equalsIgnoreCase("Ligand Type"))
                            break; // we're done
                    }
                    else if (target.idgFamily.equals("Ion Channel")) {
                        if (label.equalsIgnoreCase("Transporter Protein Type"))
                            break;
                    }
                    else if (target.idgFamily.equals("Kinase")) {
                        if (label.equalsIgnoreCase("Pseudokinase"))
                            break;
                    }
                    else if (target.idgFamily.equals("Nuclear Receptor")) {
                        // nothing to check
                    }
                    
                    String value = rset.getString("value");
                    if (value.equals(""))
                        break; // we're done
                    value = value.replaceAll("/", "-");
                    Logger.debug("  name=\""+label+"\" value="+value);
                    
                    kw = KeywordFactory.registerIfAbsent
                        (DTO_PROTEIN_CLASS+" ("+path.size()+")", value, null);
                    target.properties.add(kw);
                    path.add(kw);
                }
                rset.close();
            }
            else {
                
            }

            if (path.isEmpty()) {
                Logger.warn("!!! Target "+protein+"/"
                            +IDGApp.getId(target)+" has no DTO entry !!!");
            }
            
            for (int k = path.size(); --k >= 0; ) {
                Keyword node = path.get(k);
                List<Predicate> predicates = PredicateFactory.finder.where
                    (Expr.and(Expr.eq("subject.refid", node.id.toString()),
                              Expr.eq("predicate",
                                      DTO_PROTEIN_ANCESTRY)))
                    .findList();
                if (predicates.isEmpty()) {
                    try {
                        Predicate pred = new Predicate
                            (DTO_PROTEIN_ANCESTRY);
                        pred.subject = new XRef (node);
                        for (int j = k; --j >= 0; ) {
                            pred.objects.add(new XRef (path.get(j)));
                        }
                        pred.save();
                    }
                    catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }
        }

        void addDrugs (Target target, long tid, Keyword tcrd)
            throws Exception {
            pstm17.setLong(1, tid);
            ResultSet rset = pstm17.executeQuery();
            int count = 0;
            while (rset.next()) {
                String chemblId = rset.getString("cmpd_chemblid");
                String drug = rset.getString("drug");

                /*
                List<Ligand> ligands = LigandFactory.finder.where()
                    .eq("synonyms.term", drug).findList();
                Ligand ligand = ligands.isEmpty() ? null
                    : ligands.iterator().next();
                */
                Ligand ligand = LIGS.get(drug);
                if (ligand == null && chemblId != null) {
                    // try chembl
                    ligand = LIGS.get(chemblId);
                    if (ligand != null) {
                        ligand.name = drug;
                        ligand.description = rset.getString("nlm_drug_info");
                    }
                }

                if (ligand == null) {
                    // new ligand
                    String smiles = rset.getString("smiles");
                    String ref = rset.getString("reference");
                    String source = rset.getString("source");
                    
                    ligand = new Ligand (drug);
                    ligand.addIfAbsent(KeywordFactory.registerIfAbsent
                                       (IDG_DRUG, drug, ref));
                    ligand.properties.add(tcrd);
                    if (source != null) {
                        if (chemblId == null) {
                            Keyword ds = datasources.get(source);
                            if (ds == null) {
                                ds = KeywordFactory.registerIfAbsent
                                    (SOURCE, source, source.equalsIgnoreCase
                                     (ChEMBL) ? "https://www.ebi.ac.uk/chembl"
                                     : null);
                                datasources.put(source, ds);
                            }
                            // property
                            ligand.addIfAbsent((Value)ds);
                        }

                        // add as property
                        Keyword kw = new Keyword (LIGAND_SOURCE, source);
                        kw.href = ref;
                        ligand.properties.add(kw);
                    }

                    ligand.description = rset.getString("nlm_drug_info");
                    if (smiles != null) {
                        ligand.properties.add
                            (new Text (ChEMBL_SMILES, smiles));
                        Structure struc = StructureProcessor.instrument
                            (smiles, null, false);
                        struc.save();
                        XRef xref = new XRef (struc);
                        ligand.links.add(xref);
                        MOLIDX.add(null, struc.id.toString(), struc.molfile);
                    }

                    pstm23.setString(1, drug);
                    ResultSet rs = pstm23.executeQuery();
                    while (rs.next()) {
                        String syn = rs.getString(1);
                        if (syn != null) {
                            if (syn.startsWith("CHEMBL")) {
                                ligand.addIfAbsent
                                    (KeywordFactory.registerIfAbsent
                                     (ChEMBL_ID, syn,
                                      "https://www.ebi.ac.uk/chembl/compound/inspect/" +syn));
                            }
                            else {
                                ligand.addIfAbsent
                                    (KeywordFactory.registerIfAbsent
                                     (ChEMBL_SYNONYM, syn, null));
                            }
                            LIGS.put(syn, ligand);
                        }
                    }
                    rs.close();
                    
                    ligand.save();
                    LIGS.put(drug, ligand);
                    
                    Logger.debug("New ligand "+ligand.id+" "
                                 +ligand.getName()+" added!");
                }
                else if (ligand.name.startsWith("CHEMBL")) {
                    ligand.description = rset.getString("nlm_drug_info");
                    ligand.name = drug;
                    ligand.addIfAbsent
                        (KeywordFactory.registerIfAbsent
                         (IDG_DRUG, drug, rset.getString("reference")));
                }
                
                if (chemblId != null) {
                    LIGS.put(chemblId, ligand);

                    Keyword kw = KeywordFactory.registerIfAbsent
                        (ChEMBL_ID, chemblId,
                         "https://www.ebi.ac.uk/chembl/compound/inspect/"
                         +chemblId);
                    ligand.addIfAbsent(kw);

                    Keyword ds = datasources.get(ChEMBL);
                    if (ds == null) {
                        ds = KeywordFactory.registerIfAbsent
                            (SOURCE, ChEMBL, "https://www.ebi.ac.uk/chembl");
                        datasources.put(ChEMBL, ds);
                    }
                    ligand.addIfAbsent((Value)ds);
                }

                XRef tref = ligand.addIfAbsent(new XRef (target));
                tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                Keyword acc = target.getSynonym(UNIPROT_GENE);
                if (acc != null)
                    tref.addIfAbsent((Value)getKeyword
                                     (IDG_TARGET, acc.term, acc.href));
                
                XRef lref = target.addIfAbsent(new XRef (ligand));
                lref.addIfAbsent((Value)getKeyword (IDG_LIGAND, ligand.getName()));

                String actType = rset.getString("act_type");
                if (actType != null) {
                    double act = rset.getDouble("act_value");
                    VNum val = new VNum (actType, act);
                    tref.properties.add(val);
                    lref.properties.add(val);
                }

                String action = rset.getString("action_type");
                if (action != null) {
                    String source = rset.getString("source");
                    Keyword kw = new Keyword (PHARMALOGICAL_ACTION, action);
                    kw.href = rset.getString("reference");
                    kw.save();
                    
                    tref.addIfAbsent((Value)kw);
                    lref.addIfAbsent((Value)kw);
                }

                try {
                    tref.save();
                    lref.save();
                    ligand.update();
                    target.update();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }

                ++count;
            }
            rset.close();
            Logger.debug("Target " + target.id + " has " + count +" drug(s)!");
        }

        void addTINX(Target target, long tid) throws Exception {
            pstm.setLong(1, tid);
            ResultSet rs = pstm.executeQuery();
            int n = 0;
            while (rs.next()) {
                String doid = rs.getString("doid");
                double tinx = rs.getDouble("importance");
                double diseaseNovelty = rs.getDouble("diseaseNovelty");
                String uniprot = rs.getString("uniprot");
                double targetNovelty = rs.getDouble("targetNovelty");
                TINX tinxe = new TINX
                        (uniprot, doid, targetNovelty, tinx, diseaseNovelty);
                tinxe.save();
                n++;
            }
            rs.close();
            Logger.debug("Target " + target.id + " has " + n +" TINX entries!");
        }
        
        void addChembl (Target target, long tid, Keyword tcrd)
            throws Exception {
            Keyword source = datasources.get(ChEMBL);
            if (source == null) {
                source = KeywordFactory.registerIfAbsent
                    (SOURCE, ChEMBL, "https://www.ebi.ac.uk/chembl");
                datasources.put(ChEMBL, source);
            }
            
            pstm16.setLong(1, tid);
            ResultSet rset = pstm16.executeQuery();
            int count = 0;
            Set<String> seen = new HashSet<String>();
            long start = System.currentTimeMillis();        
            while (rset.next()) {
                String chemblId = rset.getString("cmpd_chemblid");
                if (seen.contains(chemblId))
                    continue;

                seen.add(chemblId);
                String syn = rset.getString("cmpd_name_in_ref");
                
                /*
                List<Ligand> ligands = LigandFactory.finder.where()
                    .eq("synonyms.term", chemblId).findList();
                Ligand ligand = null;
                if (!ligands.isEmpty()) {
                    ligand = ligands.iterator().next();
                }
                else*/
                Ligand ligand = LIGS.get(chemblId);
                if (ligand == null && syn != null) {
                    ligand = LIGS.get(syn.toLowerCase());
                }
                
                if (ligand == null) {
                    ligand = new Ligand (chemblId);
                    ligand.properties.add(source);
                    ligand.properties.add(tcrd);
                    ligand.synonyms.add
                        (KeywordFactory.registerIfAbsent
                         (ChEMBL_SYNONYM, chemblId,
                          "https://www.ebi.ac.uk/chembl/compound/inspect/"
                          +chemblId));
                    
                    Keyword kw = new Keyword (LIGAND_SOURCE, source.term);
                    kw.href = source.href;
                    ligand.properties.add(kw);
                    
                    String smiles = rset.getString("smiles");
                    /*
                    if (smiles == null) {
                        // grab from chembl schema.. sigh
                        smiles = chembl.getMolfile(chemblId);
                        if (smiles == null) {
                            Logger.error
                                ("Can't retrieve molfile from chembl: "
                                 +chemblId);
                        }
                    }
                    */
                    
                    if (smiles != null && smiles.length() > 0) {
                        long t0 = System.currentTimeMillis();
                        ligand.properties.add
                            (new Text (ChEMBL_SMILES, smiles));
                        Structure struc = StructureProcessor.instrument
                            (smiles, null, false);
                        struc.save();
                        XRef xref = new XRef (struc);
                        ligand.links.add(xref);
                        // now index the structure for searching

                        try {
                            MOLIDX.add(null, struc.id.toString(), struc.molfile);
                        } catch (IllegalArgumentException e) {
                            Logger.debug(e.toString());
                        }
                        /*
                        Logger.debug("... "+chemblId+": structure "
                        +struc.id+" indexed in "
                                     +(System.currentTimeMillis()-t0)+"ms");
                        */
                    }

                    pstm24.setString(1, chemblId);
                    ResultSet rs = pstm24.executeQuery();
                    while (rs.next()) {
                        String s = rs.getString(1);
                        if (s != null && s.length() <= 255) {
                            ligand.addIfAbsent
                                (KeywordFactory.registerIfAbsent
                                 (ChEMBL_SYNONYM, s, "https://www.ebi.ac.uk/chembl/compound/inspect/"+chemblId));
                            LIGS.put(s.toLowerCase(), ligand);
                        }
                    }
                    rs.close();
                    
                    ligand.save();
                    LIGS.put(chemblId, ligand);
                    if (syn != null)
                        LIGS.put(syn.toLowerCase(), ligand);
                }

                if (syn != null && syn.length() <= 255) {
                    Keyword found = null;
                    for (Keyword kw : ligand.getSynonyms())
                        if (syn.equalsIgnoreCase(kw.term)) {
                            found = kw;
                            break;
                        }
                    
                    if (found == null) {
                        Keyword kw = getKeyword 
                            (ChEMBL_SYNONYM, syn,
                             "https://www.ebi.ac.uk/chembl/compound/inspect/"
                             +chemblId);
                        ligand.addIfAbsent(kw);
                    }
                }

                VNum act = new VNum (rset.getString("act_type"),
                                     rset.getDouble("act_value"));
                act.save();

                long pmid = rset.getLong("pubmed_id");
                if (pmid != 0) {
                    /*
                    Publication pub = PublicationFactory.registerIfAbsent(pmid);
                    XRef ref = new XRef (pub);
                    ref.properties.add(act);
                    ligand.addIfAbsent(ref);
                    ligand.addIfAbsent(pub);
                    */
                    ligand.properties.add(new VInt (PUBMED_ID, pmid));
                }

                Keyword endpoint = getKeyword (LIGAND_ACTIVITY, act.label);
                XRef tref = ligand.addIfAbsent(new XRef (target));
                tref.addIfAbsent((Value)getTdlKw (target.idgTDL));
                tref.addIfAbsent((Value)getFamKw (target.idgFamily));
                Keyword acc = target.getSynonym(UNIPROT_GENE);
                if (acc != null)
                    tref.addIfAbsent((Value)getKeyword
                                     (IDG_TARGET, acc.term, acc.href));
                tref.addIfAbsent(endpoint);
                
                XRef lref = target.addIfAbsent(new XRef (ligand));
                lref.addIfAbsent(getKeyword (IDG_LIGAND, ligand.getName()));
                lref.addIfAbsent(endpoint);
                
                tref.properties.add(act);
                lref.properties.add(act);
                
                try {
                    if (tref.id == null)
                        tref.save();
                    else
                        tref.update();
                    ligand.update();
                    
                    if (lref.id == null) {
                        lref.save();
                        if (((count+1) % 100) == 0)
                            target.update();
                    }
                    else
                        lref.update();
                    
                    Logger.debug("..."+count+" ligand "
                                 +ligand.name+" "+act.label+"="+act.numval);
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
                ++count;
            }
            rset.close();

            /*
            if (count > 0) {
                try {
                    target.update();
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            */
            Logger.debug("Target "+target.id+" has "+count+" ligand(s)... "
                         +(System.currentTimeMillis()-start)+"ms!");
        }

        Disease registerDiseaseIfAbsent
            (String name, String type, Keyword tcrd, ResultSet rset)
            throws Exception {
            List<Disease> diseases = DiseaseFactory
                .finder.where().eq("name", name).findList();
            Disease d = null;
            if (diseases.isEmpty()) {
                d = new Disease();
                d.name = name;
                d.description = rset.getString("description");
                d.properties.add(datasources.get(type));                
                d.properties.add(tcrd);
                String doid = rset.getString("doid");
                if (doid != null)
                    doid = doid.trim();
                
                String drugName = rset.getString("drug_name");
                if (drugName != null) {
                    // add this temporary for now and we
                    //  resolve it later..
                    d.properties.add
                        (KeywordFactory.registerIfAbsent
                         (IDG_DRUG, drugName, null));
                }
                
                if (doid != null && doid.length() > 0) {
                    d.synonyms.add
                        (KeywordFactory.registerIfAbsent
                         ("DOID", doid,
                          "http://www.disease-ontology.org/term/" + doid));
                }
                else {
                    String ref = rset.getString("reference");
                    if (ref != null) {
                        // UniProt Disease
                        doid = ref.replaceAll("[\\s]+", "");
                        d.synonyms.add
                            (KeywordFactory.registerIfAbsent
                             (UNIPROT_DISEASE, doid,
                              "http://omim.org/entry/"
                              + doid.substring(doid.indexOf(':') + 1)));
                        Keyword kw = datasources.get("OMIM");
                        if (kw != null)
                            d.addIfAbsent((Value) kw);
                        kw = datasources.get("UniProt");
                        if (kw != null)
                            d.addIfAbsent((Value) kw);
                    }
                    else { // DrugCentral
                        doid = null;
                        Keyword kw =
                            datasources.get("DrugCentral");
                        if (kw == null) {
                            kw = KeywordFactory.registerIfAbsent
                                (SOURCE, "DrugCentral", null);
                            datasources.put("DrugCentral", kw);
                        }
                        d.addIfAbsent((Value)kw);
                    }
                }
                d.save();
                if (doid == null) {
                    doid = "IDG:D"+d.id;
                }
                DISEASES.put(doid, d);
            }
            else {
                d = diseases.iterator().next();
            }

            return d;
        }
        
        void addDisease (Target target, long tid, final Keyword tcrd)
            throws Exception {
            final String type = "DiseaseOntology";
            Keyword ds = datasources.get(type);
            if (ds == null) {
                ds = KeywordFactory.registerIfAbsent
                    (SOURCE, type, "http://www.disease-ontology.org");
                datasources.put(type, ds);
            }
            
            pstm20.setLong(1, tid);
            final ResultSet rset = pstm20.executeQuery();
            try {
                int cnt = 0;
                while (rset.next()) {
                    final String name = rset.getString("name");
                    Disease d = IxCache.getOrElse
                        (name, new Callable<Disease>() {
                                public Disease call() throws Exception {
                                    return registerDiseaseIfAbsent
                                    (name, type, tcrd, rset);
                                }
                            });
                    
                    XRef xref = target.addIfAbsent(new XRef (d));                    
                    String dtype = rset.getString("datype");
                    Keyword source = datasources.get(dtype);
                    if (source == null) {
                        String url = null;
                        if (dtype.equalsIgnoreCase("JensenLab Experiment DistiLD")) {
                            url = "http://distild.jensenlab.org";
                        }
                        else if ("JensenLab Knowledge UniProtKB-KW"
                                 .equalsIgnoreCase(dtype)) {
                            url = "http://diseases.jensenlab.org";
                        }
                        else if ("JensenLab Text Mining".equalsIgnoreCase(dtype)) {
                            url = "http://diseases.jensenlab.org";
                        }

                        source = KeywordFactory.registerIfAbsent(SOURCE, dtype, url);
                        datasources.put(dtype, source);
                    }
                    d.addIfAbsent((Value)source);
                    
                    if ("JensenLab Knowledge UniProtKB-KW"
                        .equalsIgnoreCase(dtype)) {
                        xref.addIfAbsent(KeywordFactory.registerIfAbsent
                                             (IDG_DISEASE, d.name, null));
                    }
                    
                    xref.addIfAbsent(source);
                    double zscore = rset.getDouble("zscore");
                    if (!rset.wasNull()) {
                        xref.properties.add(new VNum (IDG_ZSCORE, zscore));
                    }
                    double conf = rset.getDouble("conf");
                    if (!rset.wasNull()) {
                        xref.properties.add(new VNum (IDG_CONF, conf));
                    }
                    String evidence = rset.getString("evidence");
                    if (evidence != null) {
                        xref.properties.add(new Text (IDG_EVIDENCE, evidence));
                    }

                    Keyword kw = KeywordFactory.registerIfAbsent
                        (IDG_DISEASE, d.name, xref.getHRef());
                    xref.addIfAbsent(kw);
                    
                    try {
                        if (xref.id == null) {
                            xref.save();
                            target.update();
                        }
                        else {
                            xref.update();
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }

                    xref = d.addIfAbsent(new XRef (target));
                    xref.addIfAbsent(getTdlKw (target.idgTDL));
                    xref.addIfAbsent(getFamKw(target.idgFamily));
                    xref.addIfAbsent(source);

                    try {
                        if (xref.id == null) {
                            xref.save();
                            d.update();
                        }
                        else {
                            xref.update();
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    ++cnt;
                }

                Logger.debug("Target "+target.id+" has "+cnt+" disease(s)!");
            }
            finally {
                rset.close();
            }
        }

        void addGeneRIF (Target target, long protein) throws Exception {
            pstm4.setLong(1, protein);
            ResultSet rset = pstm4.executeQuery();
            try {
                while (rset.next()) {
                    String desc = rset.getString("text");
                    Text text = new Text (IDG_GENERIF, desc);
                    text.save();
                    XRef ref = new XRef (text);
                    String pmids = rset.getString("pubmed_ids");
                    if (pmids != null) {
                        for (String t : pmids.split("\\|")) {
                            ref.properties.add(new VInt
                                               (PUBMED_ID, Long.parseLong(t)));
                        }
                    }
                    target.links.add(ref);
                }
            }
            finally {
                rset.close();
            }
        }

        void addCompartment (Target target, long protein) throws Exception {
            pstm27.setLong(1, protein);
            ResultSet rset = pstm27.executeQuery();
            try {
                int count = 0;
                long start = System.currentTimeMillis();
                while (rset.next()) {
                    Compartment comp = new Compartment ();
                    comp.type = rset.getString("ctype");
                    comp.goId = rset.getString("go_id");
                    comp.goTerm = rset.getString("go_term");
                    comp.zscore = rset.getDouble("zscore");
                    if (rset.wasNull())
                        comp.zscore = null;
                    comp.evidence = rset.getString("evidence");
                    comp.conf = rset.getDouble("conf");
                    if (rset.wasNull())
                        comp.conf = null;
                    comp.url = rset.getString("url");
                    try {
                        comp.save();
                        XRef ref = new XRef (comp);
                        Keyword kw = getKeyword
                            (COMPARTMENT_GOTERM, comp.goTerm,
                             "http://amigo.geneontology.org/amigo/term/"
                             +comp.goId);
                        ref.properties.add(kw);
                              
                        kw = getKeyword (COMPARTMENT_EVIDENCE, 
                                         comp.evidence, comp.url);
                        ref.properties.add(kw);

                        kw = getKeyword (COMPARTMENT_TYPE, comp.type, null);
                        ref.properties.add(kw);

                        ref.save();
                        target.links.add(ref);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist compartment for protein "
                                     +protein, ex);
                    }
                    ++count;
                }
                Logger.debug("Target "+target.id+" has "
                             +count+" compartments..."+String.format
                             ("%1$dms!", System.currentTimeMillis()-start));
            }
            finally {
                rset.close();
            }
        }

        void addPublication (Target target, long protein) throws Exception {
            pstm22.setLong(1, protein);
            ResultSet rset = pstm22.executeQuery();
            try {
                int count = 0;
                long start = System.currentTimeMillis();
                while (rset.next()) {
                    long pmid = rset.getLong("id");
                    Publication pub = PublicationFactory.byPMID(pmid);
                    if (pub == null) {
                        pub = new Publication ();
                        pub.pmid = pmid;
                        pub.title = rset.getString("title");
                        pub.abstractText = rset.getString("abstract");

                        String date = rset.getString("date");
                        Integer year = null;
                        if (date != null) {
                            year = Integer.parseInt(date.split("-")[0]);
                        }
                        pub.year = year;
                        pub.save();
                    }

                    target.addIfAbsent(pub);
                    ++count;
                }
                
                Logger.debug("Target "+target.id+" has "
                             +count+" publications..."
                             +String.format("%1$dms!",
                                            System.currentTimeMillis()-start));
            }
            finally {
                rset.close();
            }
        }

        void addPPI (Target target, long protein) throws Exception {
            pstm26.setLong(1, protein);
            ResultSet rset = pstm26.executeQuery();
            try {
                Predicate pred = new Predicate (TARGET_PPI);
                pred.subject = new XRef (target);
                while (rset.next()) {
                    long p2 = rset.getLong("protein2_id");
                    if (p2 != protein) {
                        // don't store self-link
                        Target t = TARGETS.get(p2);
                        if (t == null) {
                            Logger.error
                                ("Can't find target with protein id="+p2);
                        }
                        else {
                            String type = rset.getString("ppitype");
                            Keyword source = datasources.get(type);
                            if (source == null) {
                                source = KeywordFactory.registerIfAbsent
                                    (SOURCE, type, null);
                                datasources.put(type, source);
                            }
                            target.addIfAbsent((Value)source);

                            XRef ref = new XRef (t);
                            ref.properties.add
                                (new VNum ("p_int", rset.getDouble("p_int")));
                            ref.properties.add
                                (new VNum ("p_ni", rset.getDouble("p_ni")));
                            ref.properties.add
                                (new VNum ("p_wrong",
                                           rset.getDouble("p_wrong")));
                            ref.save();
                            pred.addIfAbsent(ref);
                        }
                    }
                }
                
                if (!pred.objects.isEmpty()) {
                    pred.save();
                    Logger.debug("Target "+target.id+" has "
                                 +pred.objects.size()
                                 +" protein-protein interactions!");
                }
            }
            finally {
                rset.close();
            }
        }

        void addTechdev (Target target, long protein) throws Exception {
            pstm28.setLong(1, protein);
            ResultSet rset = pstm28.executeQuery();
            try {
                while (rset.next()) {
                    Techdev dev = new Techdev ();
                    dev.pi = rset.getString("name");
                    dev.grantNum = rset.getString("grant_number");
                    dev.comment = rset.getString("comment");
                    dev.pmcid = rset.getString("publication_pcmid");
                    dev.pmid = rset.getLong("publication_pmid");
                    if (rset.wasNull())
                        dev.pmid = null;
                    dev.resourceUrl = rset.getString("resource_url");
                    dev.dataUrl = rset.getString("data_url");
                    dev.target = target;
                    try {
                        dev.save();

                        XRef ref = new XRef (dev);
                        // add these for target facets
                        ref.properties.add(KeywordFactory.registerIfAbsent
                                           (TECHDEV_PI, dev.pi, null));
                        ref.properties.add(KeywordFactory.registerIfAbsent
                                           (TECHDEV_GRANT, dev.grantNum, null));
                        ref.save();
                        target.links.add(ref);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't persist Techdev "
                                     +rset.getLong("a.id")+" for protein "+protein, ex);
                    }
                }
            }
            finally {
                rset.close();
            }
        }

        void persists (TcrdTarget t) throws Exception {
            long start = System.currentTimeMillis();

            Http.Context.current.set(ctx);
            Logger.debug("####### Processing target... "+t.acc+" "+t.id
                         +" \""+t.family+"\' "+t.tdl+" #######");
            
            final Target target = new Target ();
            target.idgFamily = t.family;
            for (Target.TDL tdl : EnumSet.allOf(Target.TDL.class)) {
                if (t.tdl.equals(tdl.name)) 
                    target.idgTDL = tdl;
            }
            assert target.idgTDL != null
                : "Unknown TDL "+t.tdl;
            
            //Logger.debug("...uniprot registration");
            //UniprotRegistry uni = new UniprotRegistry ();
            //uni.register(target, t.acc);
            try {
                instrument (target, t);         
                target.save();
            }
            catch (Exception ex) {
                ex.printStackTrace();
                Logger.debug("Can't persist target "+t.id
                             +" (protein: "+t.protein+")");
            }

            if (t.dtoNode == null) {
                for (Keyword kw : target.getSynonyms()) {
                    if (kw.term != null) {
                        t.dtoNode = t.dto.get(kw.term);
                        if (t.dtoNode != null)
                            break;
                    }
                }
            }

            try {
                addDTO (target, t.protein, t.dtoNode);
                addTDL (target, t.protein);
                addPhenotype (target, t.protein);
                addExpression (target, t.protein);
                addGO (target, t.protein);
                addPathway (target, t.protein);
                addPanther (target, t.protein);
                addPatent (target, t.protein);
                addPMScore (target, t.protein);
                addGrant (target, t.id);
                addDrugs (target, t.id, t.source);
                addAssay (target, t.protein);
                addChembl (target, t.id, t.source);
                addDisease (target, t.id, t.source);
                addHarmonogram (target, t.protein);
                addGeneRIF (target, t.protein);
                addTINX (target, t.id);
                addPublication (target, t.protein);
                addCompartment (target, t.protein);
                addTechdev (target, t.protein);
            }
            catch (Exception ex) {
                Logger.error("Can't parse target "+IDGApp.getId(target)+"!");
                ex.printStackTrace();
            }

            try {
                target.update();
            }
            catch (Exception ex) {
                Logger.error("Can't update target "+target.id+" ("
                             +IDGApp.getId(target)+")", ex);
                ex.printStackTrace();
            }

            TARGETS.put(t.protein, target);

            /*
            Logger.debug("...disease linking");
            pstm.setLong(1, t.id);
            long start = System.currentTimeMillis();
            new RegisterDiseaseRefs (target, t, pstm).persists();
            long end = System.currentTimeMillis();
            Logger.debug("..."+(end-start)+"ms to resolve diseases");
            */

            //Logger.debug("...gene RIF linking");
            //pstm4.setLong(1, t.protein);
            //new RegisterGeneRIFs (target, pstm4).persists();

            /*
            Logger.debug("...ligand linking");
            pstm2.setLong(1, t.id);
            pstm3.setLong(1, t.id);
            RegisterLigands reglig = new RegisterLigands
                (chembl, target, pstm2, pstm3, t.source);
            reglig.persists();

            for (Ligand lig : reglig.getLigands())
                if (lig.id != null)
                    LIGANDS.put(lig.id, lig);
            */

            Logger.debug("####### Target "+t.acc+" processed in "
                         +String.format("%1$dms!", 
                                        System.currentTimeMillis()-start)
                         +" ########");
        }
    }

    /**
     * @deprecated
     */
    static class RegisterDiseaseRefs
        extends PersistenceQueue.AbstractPersistenceContext {
        final Target target;
        final TcrdTarget tcrdTarget;
        final Keyword source;
        final PreparedStatement pstm;

        RegisterDiseaseRefs (Target target, TcrdTarget tcrdTarget,
                             PreparedStatement pstm) {
            this.target = target;
            this.source = tcrdTarget.source;
            this.pstm = pstm;
            this.tcrdTarget = tcrdTarget;
        }

        public void persists () throws Exception {
            ResultSet rs = pstm.executeQuery();
            try {               
                Keyword family = KeywordFactory.registerIfAbsent
                    (IDG_FAMILY, target.idgFamily, null);
                Keyword clazz = KeywordFactory.registerIfAbsent
                    (IDG_DEVELOPMENT, target.idgTDL.name, null);
                Keyword name = KeywordFactory.registerIfAbsent
                    (UNIPROT_TARGET, target.name, target.getSelf());

                XRef self = new XRef (target);
                self.properties.add(family);
                self.properties.add(clazz);
                self.properties.add(name);
                self.save();

                int count = 0;
                Map<Long, Disease> neighbors = new HashMap<Long, Disease>();
                List<Disease> updates = new ArrayList<Disease>();
                while (rs.next()) {
                    String doid = rs.getString("doid");
                    Disease disease = DISEASES.get(doid);
                    long start = System.currentTimeMillis();
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
                        DISEASES.putIfAbsent(doid, disease);
                    }

                    { Value val = null;
                        for (Value v : disease.properties) {
                            if (v == source) {
                                val = v;
                                break;
                            }
                            else if (v instanceof Keyword) {
                                Keyword kw = (Keyword)v;
                                if (kw.label.equals(source.label)
                                    && kw.term.equals(source.term)) {
                                    val = kw;
                                    break;
                                }
                            }
                        }
                        
                        if (val == null) {
                            disease.properties.add(source);
                            disease.update();
                        }
                    }
                    
                    double zscore = rs.getDouble("zscore");
                    double conf = rs.getDouble("conf");
                    double tinx = rs.getDouble("score");
                    double diseaseNovelty = rs.getDouble("diseaseNovelty");

                    /**
                     * TODO: tinx should reference disease and target directly instead of just the uniprot and doid!
                     */
                    TINX tinxe = new TINX
                        (tcrdTarget.acc, doid, tcrdTarget.novelty, tinx, diseaseNovelty);
                    tinxe.save();

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
                        Keyword kw = KeywordFactory.registerIfAbsent
                            (IDG_DISEASE, disease.name, xref.getHRef());
                        xref.properties.add(kw);
                        xref.properties.add(new VNum (IDG_ZSCORE, zscore));
                        xref.properties.add(new VNum (IDG_CONF, conf));
                        xref.properties.add(new VNum (TINX_IMPORTANCE, tinx));
                        xref.properties.add(new VNum (TINX_DISEASE_NOVELTY, diseaseNovelty));
                        xref.save();
                        target.links.add(xref);

                        // now add all the unique parents of this disease node
                        getNeighbors(neighbors, disease.links);
                    
                        // link the other way
                        try {
                            disease.links.add(self);
                            updates.add(disease);
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
                    long end = System.currentTimeMillis();
                    Logger.debug("......."+(end-start)+"ms linking target "
                                 +target.id+" and disease "+disease.id
                                 +" ("+doid+")");  
                }
                Logger.debug(".....updating "+updates.size()+" diseases");
                for (Disease d : updates) {
                    try {
                        d.update();
                        INDEXER.update(d);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't update disease "
                                     +d.id+" "+d.name, ex);
                        ex.printStackTrace();
                    }
                }
                Logger.debug("....."+count+" disease xref(s) added!");
            }
            finally {
                rs.close();
            }
        }

        void getNeighbors (Map<Long, Disease> neighbors, List<XRef> links) {
            for (XRef xr : links) {
                if (Disease.class.getName().equals(xr.kind)) {
                    final XRef ref = xr;
                    try {
                        Disease neighbor =
                            Cache.getOrElse
                            (Disease.class.getName()+"."+xr.refid,
                             new Callable<Disease> () {
                                 public Disease call () {
                                     return (Disease)ref.deRef();
                                 }
                             }, Integer.MAX_VALUE);
                        neighbors.put(neighbor.id, neighbor);
                        // recurse
                        getNeighbors (neighbors, neighbor.links);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't retrieve neighbor for XRef "
                                     +ref.kind+" "+ref.refid, ex);
                        ex.printStackTrace();
                    }
                }
            }
        }
    } // RegisterDiseaseRefs ()

    /**
     * @deprecated
     */
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
                Map<Long, String> generifs = new HashMap<Long, String>();
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
                                xref.properties.add
                                    (new Text (IDG_GENERIF, text));
                                xref.update();
                                ++updates;
                            }
                        }
                    }
                    
                    if (updates > 0) {
                        ++count;
                    }
                    else {
                        generifs.put(pmid, text);
                    }
                }

                for (Map.Entry<Long, String> me : generifs.entrySet()) {
                    long pmid = me.getKey();
                    Publication pub = PublicationFactory.registerIfAbsent(pmid);
                    if (pub != null) {
                        try {
                            target.addIfAbsent(pub);
                            XRef xref = target.addIfAbsent(new XRef (pub));
                            xref.properties.add
                                (new Text (IDG_GENERIF, me.getValue()));
                            if (xref.id == null) {
                                xref.save();
                                target.update();
                            }
                            else {
                                xref.update();
                            }
                            
                            ++count;
                        }
                        catch (Exception ex) {
                            ex.printStackTrace();
                            Logger.error("Can't save XRef for publication "
                                         +pub.id, ex);
                        }
                    }
                    else {
                        Logger.warn("Bogus PMID: "+pmid+"!");
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

    /**
     * @deprecated
     */
    static class RegisterLigands
        extends PersistenceQueue.AbstractPersistenceContext {
        final ChemblRegistry registry;
        final Target target;
        final PreparedStatement chembl;
        final PreparedStatement drug;
        final List<Ligand> allligands = new ArrayList<Ligand>();
        final Keyword source;

        RegisterLigands (ChemblRegistry registry,
                         Target target, PreparedStatement chembl,
                         PreparedStatement drug, Keyword source)
            throws SQLException {
            this.registry = registry;
            this.target = target;
            this.chembl = chembl;
            this.drug = drug;
            this.source = source;
        }

        List<Ligand> loadChembl () throws SQLException {
            final List<Ligand> ligands = new ArrayList<Ligand>();
            ResultSet rs = chembl.executeQuery();
            while (rs.next()) {
                String chemblId = rs.getString("cmpd_chemblid");
                List<Ligand> ligs = LigandFactory.finder
                    .where(Expr.and
                           (Expr.eq("synonyms.label", ChEMBL_ID),
                            Expr.eq("synonyms.term", chemblId)))
                    .findList();
                if (ligs.isEmpty()) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (ChEMBL_ID, chemblId,
                         "https://www.ebi.ac.uk/chembl/compound/inspect/"
                         +chemblId);
                    Ligand ligand = new Ligand (chemblId);
                    ligand.synonyms.add(kw);
                    ligand.properties.add(source);
                    ligands.add(ligand);
                }
                else {
                    for (Ligand l : ligs) {
                        Ligand lig = LIGANDS.get(l.id);
                        if (lig == null) {
                            Logger.warn("Ligand "+l.id+" ("+l.getName()
                                        +") isn't cached!");
                        }
                        else {
                            ligands.add(lig);
                        }
                    }
                }
            }
            rs.close();
            return ligands;
        }

        List<Ligand> loadDrugs () throws SQLException {
            List<Ligand> ligands = new ArrayList<Ligand>();
            ResultSet rs = drug.executeQuery();
            while (rs.next()) {
                String drug = rs.getString("drug");
                String ref = rs.getString("reference");
                List<Ligand> ligs;
                if (ref != null && ref.indexOf("CHEMBL") > 0) {
                    String chemblId = ref.substring(ref.indexOf("CHEMBL"));
                    ligs = LigandFactory.finder
                        .where(Expr.and
                               (Expr.eq("synonyms.label", ChEMBL_ID),
                                Expr.eq("synonyms.term", chemblId)))
                        .findList();
                }
                else {
                    ligs = LigandFactory.finder
                        .where(Expr.and
                               (Expr.eq("synonyms.label", ChEMBL_SYNONYM),
                                Expr.eq("synonyms.term", drug)))
                        .findList();
                }
                if (ligs.isEmpty()) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (IDG_DRUG, drug, ref != null
                         && ref.startsWith("http") ? ref : null);
                    Ligand ligand = new Ligand (drug);
                    String desc = rs.getString("nlm_drug_info");
                    if (desc != null) {
                        int pos = desc.lastIndexOf("Check for");
                        if (pos > 0)
                            desc = desc.substring(0, pos);
                    }
                    ligand.description = desc;
                    ligand.synonyms.add(kw);
                    String src = rs.getString("source");
                    if (!src.equalsIgnoreCase("chembl")) {
                        kw = KeywordFactory.registerIfAbsent
                            (SOURCE, src, ref);
                        ligand.properties.add(kw);
                    }
                    ligand.properties.add(source);
                    ligands.add(ligand);
                }
                else {
                    for (Ligand l : ligs) {
                        Ligand lig = LIGANDS.get(l.id);
                        if (lig == null) {
                            Logger.warn("Ligand "+l.id+" ("+l.getName()
                                        +") isn't cached!");
                        }
                        else {
                            ligands.add(lig);
                        }
                    }
                }
            }
            rs.close();
            return ligands;
        }
        
        /**
         * This is to register the ligands direct instead of going through
         * chembl. This is for those ligands/targets that can't be resolved
         * through chembl.
         */
        List<Ligand> registerDrugLigands () throws SQLException {
            List<Ligand> ligands = new ArrayList<Ligand>();
            ResultSet rs = drug.executeQuery();
            while (rs.next()) {
                String drug = rs.getString("drug");
                String ref = rs.getString("reference");
                String src = rs.getString("source");
                
                List<Ligand> ligs = LigandFactory.finder
                    .where(Expr.and
                           (Expr.eq("synonyms.label", ChEMBL_SYNONYM),
                            Expr.eq("synonyms.term", drug)))
                    .findList();
                
                if (ligs.isEmpty()) {
                    Keyword kw = KeywordFactory.registerIfAbsent
                        (IDG_DRUG, drug, ref != null
                         && ref.startsWith("http") ? ref : null);
                    Ligand ligand = new Ligand (drug);
                    String desc = rs.getString("nlm_drug_info");
                    if (desc != null) {
                        int pos = desc.lastIndexOf("Check for");
                        if (pos > 0)
                            desc = desc.substring(0, pos);
                    }
                    ligand.description = desc;
                    ligand.synonyms.add(kw);
                    
                    if (!src.equalsIgnoreCase("chembl")) {
                        kw = KeywordFactory.registerIfAbsent(SOURCE, src, ref);
                        ligand.properties.add(kw);
                    }
                    else if (src.equalsIgnoreCase("iuphar")) {
                        kw = KeywordFactory.registerIfAbsent
                            (SOURCE, src, "http://www.guidetopharmacology.org/");
                        ligand.properties.add(kw);
                    }
                    ligand.properties.add(source);

                    if (ref != null && ref.indexOf("CHEMBL") > 0) {
                        String chemblId = ref.substring(ref.indexOf("CHEMBL"));
                        kw = KeywordFactory.registerIfAbsent
                            (ChEMBL_ID, chemblId,
                             "https://www.ebi.ac.uk/chembl/compound/inspect/"
                             +chemblId);
                        ligand.synonyms.add(kw);
                    }
                    
                    if (!registry.instrument(ligand)) {
                        // if can't resolve ligand via chembl, then use
                        // whatever information available
                        String smiles = rs.getString("smiles");
                        if (smiles != null) {
                            ligand.properties.add
                                (new Text (IDG_SMILES, smiles));
                            Logger.debug("submitting "+drug
                                         +" for processing...");
                            StructureReceiver receiver =
                                new LigandStructureReceiver (source, ligand);
                            PROCESSOR.submit(smiles, receiver);
                        }
                        ligand.save();
                    }
                    
                    ligs.add(ligand);
                }
                else {
                    List<Ligand> temp = new ArrayList<Ligand>();
                    for (Ligand l : ligs) {
                        Ligand lig = LIGANDS.get(l.id);
                        if (lig == null) {
                            Logger.warn("Ligand "+l.id+" ("+l.getName()
                                        +") isn't cached!");
                        }
                        else {
                            //ligands.add(lig);
                            temp.add(lig);
                        }
                    }
                    ligs = temp;
                }
                
                if (ref != null && ref.indexOf("CHEMBL") > 0) {
                    Logger.warn("Skipping ChEMBL reference "
                                +ref+" for drug "+drug);
                }
                else {
                    String type = rs.getString("act_type");
                    Double value = rs.getDouble("act_value");
                    if (rs.wasNull())
                        value = null;
                    
                    VNum act = new VNum (type, value);
                    act.save();
                    for (Ligand l : ligs) {
                        XRef tref = new XRef (target);
                        tref.properties.add(source);
                        tref.properties.add
                            (KeywordFactory.registerIfAbsent
                             (Target.IDG_FAMILY, target.idgFamily, null));
                        tref.properties.add
                            (KeywordFactory.registerIfAbsent
                             (Target.IDG_DEVELOPMENT,
                              target.idgTDL.name, null));
                        tref.properties.add(act);
                        
                        XRef lref = new XRef (l);
                        lref.properties.add(source);
                        lref.properties.add
                            (KeywordFactory.registerIfAbsent
                             ("Ligand", l.getName(), null));
                        lref.properties.add(act);
                        
                        tref.save();
                        lref.save();
                        l.links.add(tref);
                        target.links.add(lref);
                    }
                }
                ligands.addAll(ligs);
            }
            rs.close();
            
            return ligands;
        }

        public void persists () throws Exception {
            Set<Long> tids = registry.instruments(target);
            if (tids == null || tids.isEmpty()) {
                // not in chembl, so we only have to look at drug
                List<Ligand> ligands = registerDrugLigands ();
                Logger.debug("Registering "+ligands.size()+" drug ligand(s) "
                             +"for target "+target.id+": "+target.name);
                allligands.addAll(ligands);
            }
            else {
                List<Ligand> ligands = loadChembl();
                Logger.debug("Registering "+ligands.size()+" Chembl ligand(s) "
                             +"for target "+target.id+": "+target.name);
                registry.instruments(tids, target, ligands);
                allligands.addAll(ligands);
                
                //ligands = loadDrugs ();
                ligands = registerDrugLigands ();
                Logger.debug("Registering "+ligands.size()+" drug ligand(s) "
                             +"for target "+target.id+": "+target.name);
                registry.instruments(tids, target, ligands);
                allligands.addAll(ligands);
            }
        }

        public List<Ligand> getLigands () { return allligands; }
    } // RegisterLigands

    static void loadChemblUniprotMapping
        (Map<String, Set<String>> uniprotMap, File file) {
        try {
            BufferedReader br = new BufferedReader (new FileReader (file));
            for (String line; (line = br.readLine()) != null; ) {
                if (line.charAt(0) == '#')
                    continue;
                String[] toks = line.split("[\\s\t]+");
                if (2 == toks.length) {
                    Set<String> set = uniprotMap.get(toks[0]);
                    if (set == null) {
                        uniprotMap.put
                            (toks[0], set = new TreeSet<String>());
                    }
                    set.add(toks[1]);
                }
            }
            br.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't load uniprot mapping file: "+file, ex);
        }
    }
    
    public static Result load () {
        DynamicForm requestData = Form.form().bindFromRequest();
        if (Play.isProd()) { // production..
            String secret = requestData.get("secret-code");
            if (secret == null || secret.length() == 0
                || !secret.equals(Play.application()
                                  .configuration().getString("ix.idg.secret"))) {
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
                DISEASES.putAll(obo.register(new FileInputStream (file)));
            }
            catch (IOException ex) {
                Logger.trace("Can't load obo file: "+file, ex);
            }
        }

        Map<String, Set<String>> uniprotMap =
            new HashMap<String, Set<String>>();
        part = body.getFile("uniprot-map");
        if (part != null) {
            String name = part.getFilename();
            String content = part.getContentType();
            File file = part.getFile();
            loadChemblUniprotMapping (uniprotMap, file);
            Logger.debug("uniprot-map: file="+name+" content="
                         +content+" count="+uniprotMap.size());
        }
        else {
            // check the config
            String file = Play.application()
                .configuration().getString("ix.pharos.chembl_uniprot_mapping");
            if (file != null) {
                loadChemblUniprotMapping (uniprotMap, new File (file));
            }
            else {
                Logger.warn("No Chembl to UniProt mapping file provided!");
            }
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
                     Map<String, Set<String>> uniprotMap) throws Exception {

        Set<TcrdTarget> targets = new HashSet<TcrdTarget>();    
        Keyword source = null;
        Connection con = ds.getConnection();
        Statement stm = con.createStatement();
        int count = 0;
        try {
            ResultSet rset = stm.executeQuery("select * from dbinfo");
            if (rset.next()) {
                source = KeywordFactory.registerIfAbsent
                    (SOURCE, "TCRDv"+rset.getString("data_ver"),
                     "http://habanero.health.unm.edu");
            }
            rset.close();

            String sql = 
                ("select *\n"
                 +"from t2tc a "
                 +"     join (target b, protein c)\n"
                 +"on (a.target_id = b.id and a.protein_id = c.id)\n"
                 +"left join tinx_novelty d\n"
                 +"    on d.protein_id = a.protein_id \n"
                 //+"where d.protein_id in (9671,9849,11534,15077,658,11534)"
                 //+"where d.protein_id in (137,734,6845)\n"
                 //+"where c.id in (11521)\n"
                 //+"where a.target_id in (12241)\n"
                 //+"where c.uniprot = 'Q9H3Y6'\n"
                 //+"where b.tdl in ('Tclin','Tchem')\n"
                 //+"where b.idgfam = 'kinase'\n"
                 //+" where c.uniprot = 'Q8N568'\n"
                 //+"where c.uniprot = 'Q9Y5X4'\n"
                 //+"where c.uniprot = 'Q9Y691'\n"
                 //+" where c.uniprot = 'Q6NV75'\n"
                 //+"where c.uniprot = 'P31749'\n"
                 //+"where c.uniprot in ('O00444', 'P07333')\n"
                 //+"where c.uniprot in ('Q8NE63','O14976')\n"
                 //+"where c.uniprot in ('O14976','O43293','O75385','O75582','P07949','P09619')\n"
                 //+"where c.uniprot in ('P35968')\n"
                 //+"where c.uniprot in ('Q15743')\n"
                 //+"where c.uniprot in ('P42685')\n"
                 //+"where c.uniprot in ('Q6PIU1')\n"
                 //+"where c.uniprot in ('A5X5Y0')\n"
                 //+"where c.uniprot in ('Q7RTX7')\n"
                 //+"where c.uniprot in ('Q00537','Q8WXA8')\n"
                 //+"where c.uniprot in ('Q401N2')\n"
                 //+"where c.uniprot in ('O94921','Q96Q40','Q00536','Q00537','Q00526','P50613','P49761','P20794')\n"
                 //+"where c.uniprot in ('Q8WXA8')\n"
                 //+"where c.uniprot in ('Q7RTX7','Q86YV6','P07333','P07949')\n"
//                 +"where c.uniprot in ('A5X5Y0','O00329','O00329', 'O95069')\n"
//                                +"where c.uniprot in ('O00141','O00168','O00299')\n"
                 +"order by d.score desc, c.id\n"
                 +(rows > 0 ? ("limit "+rows) : "")
                 );

            Logger.debug("Executing sql..."+sql);
            rset = stm.executeQuery(sql);           

            DTOParser dto = null;
            String enhanced = Play.application()
                .configuration().getString("ix.idg.dto.enhanced");
            if (enhanced != null) {
                File file = new File (enhanced);
                if (file.exists()) {
                    // load the dto from this file
                    Logger.debug("Loading enhanced DTO file..."+file);
                    dto = DTOParser.readJson(file);
                }
            }
                            
            if (dto == null) {
                String dtofile = Play.application()
                    .configuration().getString("ix.idg.dto.basic");
                File file = new File (dtofile);
                if (file.exists()) {
                    Logger.debug("Loading basic DTO file..."+dtofile);
                    try {
                        dto = new DTOParser ();
                        dto.parse(file);
                    }
                    catch (IOException ex) {
                        ex.printStackTrace();
                    }
                }
                else {
                    Logger.warn("!!!! NO DTO AVAILABLE !!!!");
                }
            }
                 
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
                String sym = rset.getString("c.sym");
                Double novelty = rset.getDouble("d.score");
                if (rset.wasNull())
                    novelty = null;
                List<Target> tlist = targetDb
                    .where().eq("synonyms.term", acc).findList();
                
                if (tlist.isEmpty()) {
                    //Logger.debug("Adding "+acc);
                    TcrdTarget t =
                        new TcrdTarget (acc, fam, tdl, id, protId,
                                        novelty, source);
                    t.dto = dto;
                    t.dtoNode = dto.get(sym);
                    targets.add(t);
                }
                else {
                    Logger.debug("Skipping "+acc);
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

        Logger.debug("Preparing to process "+targets.size()+" targets...");
        ChemblRegistry chembl = new ChemblRegistry (uniprotMap);
        PersistRegistration regis = new PersistRegistration
            (ds.getConnection(), Http.Context.current(),
             targets, chembl);
        PQ.submit(regis);
        //regis.persists();
        
        return count;
    }
    
    public static Result index () {
        if (Play.isProd()) {
            return redirect (routes.IDGApp.index());
        }
        return ok (ix.idg.views.html.tcrd.render("IDG TCRD Loader"));
    }
}
