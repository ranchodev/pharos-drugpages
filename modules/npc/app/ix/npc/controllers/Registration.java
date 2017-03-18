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

public class Registration extends App  implements ix.npc.models.Properties {
    public static final ThreadPoolPlugin THREAD_POOL =
        Play.application().plugin(ThreadPoolPlugin.class);
    public static final PayloadPlugin PAYLOAD =
        Play.application().plugin(PayloadPlugin.class);
    public static final PersistenceQueue PQ =
        Play.application().plugin(PersistenceQueue.class);
    static final StructureIndexer MOLIDX = Play.application()
        .plugin(StructureIndexerPlugin.class).getIndexer();

    static class MolJobPersistence
        extends PersistenceQueue.AbstractPersistenceContext {

        final Job job;
        MolJobPersistence (Job job) {
            this.job = job;
        }

        public void persists () throws Exception {
            int count = 0;          
            try {
                job.status = Job.Status.RUNNING;

                Keyword ds = KeywordFactory.registerIfAbsent
                    (DATASET, job.payload.name, null);
                
                MolImporter mi = new MolImporter
                    (PayloadFactory.getStream(job.payload));
                mi.setGrabbingEnabled(true);
                
                job.processed = job.failed = 0;
                for (Molecule mol = new Molecule (); mi.read(mol); ) {
                    Text input = new Text
                        (ORIGINAL_INPUT, mi.getGrabbedMoleculeString());
                    
                    try {
                        Entity ent = instrument (mol);
                        ent.properties.add(input);
                        
                        XRef xref = new XRef (job.payload);
                        xref.properties.add(ds);
                        ent.links.add(xref);
                        
                        ent.save();
                        
                        if (job.processed++ % 100 == 0) {
                            job.update();
                            Logger.debug(job.payload.name+": "+job.processed);
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
                mi.close();
                job.status = Job.Status.COMPLETE;
                job.message = null;
            }
            catch (Exception ex) {
                job.message = ex.getMessage();
                job.status = Job.Status.FAILED;
                Logger.error("Job "+job.id+" for payload "+job.payload.name
                             +" failed!", ex);
            }
            job.update();
            Logger.debug("Job "+job.id+"/"+job.payload.name
                         +" finished processing "+job.processed+" entities!");
        }
    }

    static Entity instrument (Molecule mol) throws Exception {
        Entity ent = new Entity (Entity.Type.Compound, mol.getName());
        
        List<Structure> moieties = new ArrayList<>();
        Structure struc = StructureProcessor.instrument(mol, moieties, false);
        struc.save();
        MOLIDX.add(null, struc.id.toString(), struc.molfile);
        
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

        struc = StructureProcessor.instrument(mol); // standardized
        struc.save();
        
        xref = new XRef (struc);
        xref.properties.addAll(struc.properties);
        xref.properties.add
            (KeywordFactory.registerIfAbsent
             (STRUCTURE_TYPE, STRUCTURE_STANDARDIZED, null));
        xref.save();
        ent.links.add(xref);

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
            return badRequest ("Upload is too large!");
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
        
        return internalServerError
            ("Unable to create payload from multipart request!");
    }
}
