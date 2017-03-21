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

import ix.core.controllers.*;
import ix.core.controllers.search.SearchFactory;
import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.*;
import ix.core.chem.StructureProcessor;
import ix.ncats.controllers.App;
import ix.utils.Global;
import ix.utils.Util;

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

    static class MolJobPersistence
        extends PersistenceQueue.AbstractPersistenceContext {

        final Job job;
        final Keyword ds;
        final String source;
        
        MolJobPersistence (Job job) {
            this.job = job;
            ds = KeywordFactory.registerIfAbsent
                (DATASET, job.payload.name, null);
            source = job.payload.sha1.substring(0,9);
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
                    ent.properties.add(input);
                    
                    XRef xref = new XRef (job.payload);
                    xref.properties.add(ds);
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
                }
            }
        }
    }

    static Entity instrument (String ds, Molecule mol) throws Exception {
        Entity ent = new Entity (Entity.Type.Compound, mol.getName());
        
        List<Structure> moieties = new ArrayList<>();
        Structure struc = StructureProcessor.instrument(mol, moieties, false);
        struc.save();

        MOLIDX.add(ds, struc.id.toString(), struc.molfile);
        
        XRef xref = new XRef (struc);
        xref.properties.addAll(struc.properties);
        xref.properties.add
            (KeywordFactory.registerIfAbsent
             (STRUCTURE_TYPE, STRUCTURE_ORIGINAL, null));
        xref.properties.add
            (new VInt (MOIETY_COUNT,
                       (long)(moieties.isEmpty()
                              ? 1 : moieties.size())));
        xref.save();
        ent.links.add(xref);

        if (mol.getAtomCount() < 500) {
            // only standardize if we a small molecule
            struc = StructureProcessor.instrument(mol);
            struc.save();
            
            xref = new XRef (struc);
            xref.properties.addAll(struc.properties);
            xref.properties.add
                (KeywordFactory.registerIfAbsent
                 (STRUCTURE_TYPE, STRUCTURE_STANDARDIZED, null));
            xref.save();
            ent.links.add(xref);
        }

        /*
         * TODO: we need to take a configuration and specify the
         * property type and whether a property should be indexed or not
         */  
        for (int i = 0; i < mol.getPropertyCount(); ++i) {
            String prop = mol.getPropertyKey(i);
            String[] values = mol.getProperty(prop).split("\n");
            for (String v : values) {
                if (v.length() < 255)
                    ent.addIfAbsent
                        ((Value)KeywordFactory.registerIfAbsent(prop, v, null));
                else
                    ent.properties.add(new Text (prop, v));
            }
        }

        return ent;
    }

    public static Result registerForm () {
        return ok (ix.npc.views.html.register.render());
    }

    @BodyParser.Of(value = BodyParser.MultipartFormData.class,
                   maxLength = 100*1024 * 1024)
    public static Result register () {
        if (request().body().isMaxSizeExceeded()) {
            return _badRequest ("Upload is too large!");
        }
        
        MultipartFormData form = request().body().asMultipartFormData();
        Map<String, String[]> params = form.asFormUrlEncoded();
        
        MultipartFormData.FilePart part = form.getFile("dataset");
        Payload py = null;
        if (part != null) {
            File file = part.getFile();
            try {
                String mime = Files.probeContentType(file.toPath());
                Logger.debug("register: file="
                             +part.getFilename()+" mime="+mime);
                
                String name = part.getFilename();
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
                job.save();
                PQ.submit(new MolJobPersistence (job));
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
