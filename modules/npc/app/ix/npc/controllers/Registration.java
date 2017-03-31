package ix.npc.controllers;

import chemaxon.struc.MolAtom;
import chemaxon.struc.Molecule;
import chemaxon.formats.MolImporter;

import com.avaje.ebean.Expr;
import com.avaje.ebean.QueryIterator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import ix.core.controllers.*;
import ix.core.controllers.search.SearchFactory;
import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.*;
import ix.core.chem.StructureProcessor;
import ix.ncats.controllers.App;
import ix.utils.Global;
import ix.utils.Util;
import tripod.chem.MolecularFramework;

import play.*;
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.cache.Cached;
import play.db.ebean.Model;
import play.libs.Akka;
import play.mvc.*;
import play.twirl.api.Content;

import tripod.chem.indexer.StructureIndexer;

import java.io.*;
import java.nio.file.Files;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static ix.core.search.TextIndexer.Facet;
import static ix.core.search.TextIndexer.SearchResult;
import static ix.core.search.TextIndexer.TermVectors;
import static play.mvc.Http.MultipartFormData;

import ix.npc.models.*;

public class Registration extends NPCApp {
    public static final PayloadPlugin PAYLOAD =
        Play.application().plugin(PayloadPlugin.class);
    public static final PersistenceQueue PQ =
        Play.application().plugin(PersistenceQueue.class);
    public static final StructureIndexer MOLIDX = Play.application()
        .plugin(StructureIndexerPlugin.class).getIndexer();
    static final ObjectMapper JSON = new ObjectMapper ();
    
    static class MolJobPersistence
        extends PersistenceQueue.AbstractPersistenceContext {

        final Job job;
        final JsonNode config;
        final Keyword ds;
        final String source;
        final String sep;
        final Map<String, JsonNode> props = new HashMap<>();
        final MolecularFramework mf;
        
        MolJobPersistence (Job job, JsonNode config) {
            this.job = job;
            this.config = config;
            ds = KeywordFactory.registerIfAbsent
                (DATASET, job.payload.name, null);
            source = job.payload.sha1.substring(0,9);
            sep = config != null && config.hasNonNull("separator")
                ? config.get("separator").asText() : "\n";
            if (config != null) {
                JsonNode pn = config.get("properties");
                Logger.debug("CONFIGURATION: props="
                             +pn.size() +" sep='"+sep+"'");          
                for (int i = 0; i < pn.size(); ++i) {
                    JsonNode n = pn.get(i);
                    if (n.hasNonNull("property")) {
                        Logger.debug("..."+n.get("property").asText());
                        props.put(n.get("property").asText(), n);
                    }
                }
            }
            
            mf = MolecularFramework.createMurckoInstance();
            mf.setGenerateAtomMapping(true);
            mf.setAllowBenzene(false);      
        }

        public void persists () throws Exception {
            int count = 0;          
            try {
                MolImporter mi = new MolImporter
                    (PayloadFactory.getStream(job.payload));
                mi.setGrabbingEnabled(true);
                job.status = Job.Status.RUNNING;        
                job.processed = job.failed = 0;
                do {
                    try {
                        process (mi);
                    }
                    catch (Exception ex) {
                        ++job.failed;
                        Logger.error("Can't process payload: "
                                     +job.payload.name, ex);
                        ex.printStackTrace();
                    }
                }
                while (mi.skipToNext());

                mi.close();
                job.status = Job.Status.COMPLETE;
                job.message = null;
            }
            catch (Exception ex) {
                job.message = ex.getMessage();
                job.status = Job.Status.FAILED;
                Logger.error("Job "+job.id+" for payload "+job.payload.name
                             +" failed!", ex);
                ex.printStackTrace();
            }
            
            if (job.id != null) {
                job.update();
                Logger.debug("Job "+job.id+"/"+job.payload.name
                             +" finished processing "+job.processed
                             +" entities!");
            }
            else {
                // job deleted, we remove any leftover from this job
                MOLIDX.remove(source);          
                deleteDataset (job.payload);
            }
            
            INDEXER.flush();
        }

        void process (MolImporter mi) throws Exception {
            for (Molecule mol = new Molecule (); mi.read(mol); ) {
                Logger.debug("processing "
                             +job.processed+"..."+mol.getName());
                Text input = new Text
                    (ORIGINAL_INPUT, mi.getGrabbedMoleculeString());
                
                try {
                    Entity ent = instrument (source, mol);
                    input.save();
                    ent.properties.add(input);
                    
                    XRef xref = new XRef (job.payload);
                    xref.properties.add(ds);
                    xref.save();
                    
                    ent.links.add(xref);
                    ent.save();
                    /*
                     * a job could be delete while we're still processing;
                     * this checkpointing mechanism allows us to sync with
                     * the database and act accordingly if the job has been
                     * deleted.
                     */
                    if (job.processed++ % 100 == 0) {
                        try {
                            IxCache.clearCache();
                        }
                        catch (Exception ex) {
                            Logger.error("Can't clear cache", ex);
                        }
                        
                        if (null != JobFactory.getJob(job.id)) {
                            job.update();
                            Logger.debug(job.payload.name+": "+job.processed);
                        }
                        else {
                            Logger.warn("Job "+job.id+" no longer available!");
                            job.id = null;
                            mi.close();
                            break;
                        }
                    }
                }
                catch (Exception ex) {
                    ++job.failed;
                    Record rec = new Record ();
                    rec.status = Record.Status.FAILED;
                    rec.message = ex.getMessage();
                    rec.properties.add(input);
                    rec.job = job;
                    rec.save();
                    
                    Logger.warn("Job "+job.id+": record "+rec.id
                                +" failed: "+ex.getMessage());
                    ex.printStackTrace();
                }
            }
        }

        Entity instrument (String source, Molecule mol) throws Exception {
            Entity ent = new Entity (Entity.Type.Compound, mol.getName());
        
            List<Structure> moieties = new ArrayList<>();
            Structure struc =
                StructureProcessor.instrument(mol, moieties, false);
            struc.save();

            if (mol.getAtomCount() > 0) {
                Map<String, Structure> fragments = generateFragments (struc);
                for (Map.Entry<String, Structure> me : fragments.entrySet()) {
                    addxref (ent, me.getValue(),
                             KeywordFactory.registerIfAbsent
                             (STRUCTURE_SCAFFOLD, me.getKey(), null));
                    createScaffoldIfAbsent (me.getKey(), me.getValue());
                }
                
                MOLIDX.add(source, struc.id.toString(), struc.molfile);
            }           

            addxref (ent, struc, KeywordFactory.registerIfAbsent
                     (STRUCTURE_TYPE, STRUCTURE_ORIGINAL, null),
                     new VInt (MOIETY_COUNT,
                               (long)(moieties.isEmpty()
                                  ? 1 : moieties.size())));

            if (mol.getAtomCount() < 500) {
                // only standardize if we a small molecule
                struc = StructureProcessor.instrument(mol);
                struc.save();
                addxref (ent, struc, 
                         KeywordFactory.registerIfAbsent
                         (STRUCTURE_TYPE, STRUCTURE_STANDARDIZED, null));
            }

            properties (ent, mol);

            return ent;
        } // instrument

        XRef addxref (Entity ent, Structure struc, Value... props) {
            XRef xref = new XRef (struc);
            xref.properties.addAll(struc.properties);
            for (Value p : props)
                xref.properties.add(p);
            xref.save();
            ent.links.add(xref);
            return xref;
        }

        Entity createScaffoldIfAbsent (String key, Structure struc) {
            List<Entity> entities = EntityFactory
                .finder.where().eq("name", key).findList();
            Entity ent;
            if (entities.isEmpty()) {
                ent = new Entity (Entity.Type.Scaffold, key);
                struc = StructureProcessor.clone(struc);
                struc.save();
                addxref (ent, struc, 
                         KeywordFactory.registerIfAbsent
                         (STRUCTURE_TYPE, STRUCTURE_ORIGINAL, null));
                ent.properties.add(ds);
                ent.save();
            }
            else {
                ent = entities.get(0);
                ent.addIfAbsent((Value)ds);
                ent.update();
            }
            return ent;
        }
        
        void properties (Entity ent, Molecule mol) {
            for (int i = 0; i < mol.getPropertyCount(); ++i) {
                String prop = mol.getPropertyKey(i);
                String pval = mol.getProperty(prop);
                String[] values = pval.split(sep);
                if ("name".equalsIgnoreCase(prop)) {
                    ent.name = values.length > 0 ? values[0] : null;
                }
                else if (props.containsKey(prop)) {
                    parse (ent, props.get(prop), values);
                }
                else {
                    Logger.warn(mol.getName()
                                +": No config for property \""+prop+"\"...");
                    for (String v : values)
                        ent.properties.add(new Text (prop, v));
                }
            }

            if (ent.name == null)
                ent.name = mol.getName();
        }

        void parse (Entity ent, JsonNode node, String... values) {
            if (node.hasNonNull("ignore")
                && node.get("ignore").booleanValue())
                return;
            
            String name = node.hasNonNull("name")
                ? node.get("name").asText() : node.get("property").asText();

            if (node.hasNonNull("transform")) {
                JsonNode n = node.get("transform");
                String p = n.get("pattern").asText();
                String v = n.hasNonNull("replace")
                    ? n.get("replace").asText() : "";
                for (int i = 0; i < values.length; ++i)
                    values[i] = values[i].replaceAll(p, v);
            }
            
            if (node.hasNonNull("type")) {
                switch (node.get("type").asText()) {
                case "synonym":
                    for (String v : values) {
                        Logger.debug("Adding synonym \""+v+"\"");
                        if (v.length() < 64) {
                            ent.addIfAbsent(KeywordFactory.registerIfAbsent
                                            (name, v, null));
                        }
                        else
                            ent.addIfAbsent(new Text (name, v));
                    }
                    break;
                
                case "date":
                    Logger.warn("I don't know how to handle date yet!");
                    break;
                            
                case "long": case "integer": case "int":
                    for (String v : values) {
                        try {
                            ent.addIfAbsent(new VInt
                                            (name, Long.parseLong(v)));
                        }
                        catch (NumberFormatException ex) {
                            Logger.error("Not a valid long: "+v, ex);
                        }
                    }
                    break;
                            
                case "number": case "float": case "double":
                    for (String v : values) {
                        try {
                            ent.addIfAbsent
                                (new VNum (name,
                                           Double.parseDouble(v)));
                        }
                        catch (NumberFormatException ex) {
                            Logger.error("Not a valid number: "+v, ex);
                        }
                    }
                    break;
                
                case "text":
                    for (String v : values)
                        ent.addIfAbsent(new Text (name, v));
                    break;

                case "uri": case "url":
                    for (String v : values)
                        ent.addIfAbsent(new Text (name, v));
                    break;
                    
                default:
                    for (String v : values)
                        ent.addIfAbsent(new Text (name, v));                
                }
            }
            else {
                for (String v : values)
                    ent.addIfAbsent(new Text (name, v));
            }
        } // parse ()

        Map<String, Structure> generateFragments (Structure parent) {
            Map<String, Structure> fragments = new HashMap<>();

            mf.setMolecule(parent.molfile);
            mf.run();
            for (Enumeration<Molecule> en = mf.getFragments();
                 en.hasMoreElements(); ) {
                Molecule f = en.nextElement();
                if (!fragments.containsKey(f.getName())) {
                    int[] amap = new int[mf.getMolecule().getAtomCount()];
                    for (int i = 0; i < f.getAtomCount(); ++i) {
                        int m = f.getAtom(i).getAtomMap();
                        if (m > 0) 
                            amap[m-1] = i+1;
                    }
                    StringBuilder sb = new StringBuilder ();
                    sb.append(amap[0]);
                    for (int i = 1; i < amap.length; ++i) {
                        sb.append(",");
                        sb.append(amap[i]);
                    }
                        
                    XRef ref = new XRef (parent);
                    ref.properties.add
                        (new Text (STRUCTURE_PARENT, sb.toString()));
                    ref.save();
                    
                    Structure struc =
                        StructureProcessor.instrument(f, null, false);
                    struc.links.add(ref);
                    struc.save();
                    
                    fragments.put(f.getName(), struc);
                }
            }
            
            return fragments;
        } // generateFragments ()
    } // MolJobPersistence


    public static Result registerForm () {
        return ok (ix.npc.views.html.register.render());
    }

    @BodyParser.Of(value = BodyParser.MultipartFormData.class,
                   maxLength = 200*1024*1024)
    public static Result register () {
        if (request().body().isMaxSizeExceeded()) {
            return _badRequest ("Upload is too large!");
        }
        
        MultipartFormData form = request().body().asMultipartFormData();
        Map<String, String[]> params = form.asFormUrlEncoded();

        JsonNode config = null;
        MultipartFormData.FilePart part;
        part = form.getFile("config");
        if (part != null) {
            File file = part.getFile();
            try {
                config = JSON.readTree(new FileInputStream (file));
            }
            catch (Exception ex) {
                Logger.error("Can't parse JSON configuration: "+file, ex);
            }
        }

        part = form.getFile("dataset");
        Payload py = null;
        if (part != null) {
            File file = part.getFile();
            try {
                String mime = Files.probeContentType(file.toPath());
                Logger.debug("register: file="
                             +part.getFilename()+" mime="+mime);
                
                String name = null;
                if (config != null) {
                    name = config.has("dataset")
                        ? config.get("dataset").asText() : part.getFilename();
                }
                else 
                    name = part.getFilename();
                
                if (params.containsKey("name")) {
                    String[] ns = params.get("name");
                    if (ns.length > 0 && ns[0].length() > 0)
                        name = ns[0];
                }

                py = PAYLOAD.createPayload
                    (name, mime, new FileInputStream (file));
                py.filename = part.getFilename();
                py.update();
            }
            catch (Exception ex) {
                Logger.error("Can't create payload for file: "+file, ex);
                ex.printStackTrace();
            }
        }

        if (py != null) {
            List<Job> jobs = JobFactory
                .getJobsByPayload(py.id.toString());
            
            Job job;
            if (jobs.isEmpty()) {
                job = new Job ();
                job.payload = py;
                if (config != null) {
                    try {
                        job.configuration = JSON.writer
                            (new DefaultPrettyPrinter ())
                            .writeValueAsString(config);
                    }
                    catch (Exception ex) {
                        Logger.error("Can't write configuration JSON", ex);
                    }
                }
                job.save();
                PQ.submit(new MolJobPersistence (job, config));
            }
            else {
                job = jobs.iterator().next();
            }

            return redirect (routes.NPCApp.index());
        }
        
        return _internalServerError
            ("Unable to create payload from multipart request!");
    }

    static int deleteDataset (Payload py) throws Exception {
        int count = 0;
        QueryIterator<Entity> it = EntityFactory.finder.where()
            .eq("links.refid", py.id).findIterate();
        try {
            while (it.hasNext()) {
                Entity e = it.next();
                e.delete();
                ++count;
            }
        }
        finally {
            it.close();
        }
            
        QueryIterator<Job> jit = JobFactory.finder.where()
            .eq("payload.id", py.id).findIterate();
        try {
            while (jit.hasNext()) {
                Job job = jit.next();
                Logger.debug("deleting job "+job.id);
                job.delete();
            }
        }
        finally {
            jit.close();
        }

        MOLIDX.remove(py.sha1.substring(0,9));
        
        return count;
    }
    
    public static Result deleteDataset (String id) {
        try {
            Payload py = PayloadFactory.getPayload(UUID.fromString(id));
            if (py == null)
                return notFound ("Unknown payload: "+id);

            int count = deleteDataset (py);

            //py.delete();            
            //Logger.debug("deleting payload "+id);

            return ok (count+" entities deleted for dataset "+id);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    public static Result admin () {
        return ok (ix.npc.views.html.admin.render());
    }
}
