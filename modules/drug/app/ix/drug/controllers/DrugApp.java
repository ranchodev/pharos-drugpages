package ix.drug.controllers;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;

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

import ix.drug.models.*;

public class DrugApp extends App implements ix.drug.models.Properties {
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

        final ProcessingJob job;
        MolJobPersistence (ProcessingJob job) {
            this.job = job;
        }

        public void persists () throws Exception {
            try {
                job.start = System.currentTimeMillis();
                job.status = ProcessingJob.Status.RUNNING;
                
                MolImporter mi = new MolImporter
                    (PayloadFactory.getStream(job.payload));
                mi.setGrabbingEnabled(true);
                int count = 0;
                for (Molecule mol = new Molecule (); mi.read(mol); ) {
                    Entity ent = new Entity
                        (Entity.Type.Compound, mol.getName());
                    ent.properties.add
                        (new Text (MolInput, mi.getGrabbedMoleculeString()));
                    
                    Structure struc = StructureProcessor.instrument(mol);
                    struc.save();
                    MOLIDX.add(null, struc.id.toString(), struc.molfile);
                    
                    XRef xref = new XRef (struc);
                    ent.links.add(xref);
                    ent.save();
                    
                    if (count++ % 100 == 0) {
                        job.message = "Processing structure "+count;
                        job.update();
                        Logger.debug(job.payload.name+": "+count);
                    }
                }
                mi.close();
                job.status = ProcessingJob.Status.COMPLETE;
                job.message = count+" structures processed!";
            }
            catch (Exception ex) {
                job.message = ex.getMessage();
                job.status = ProcessingJob.Status.FAILED;
            }
            job.stop = System.currentTimeMillis();
            job.update();
            Logger.debug("Job "+job.id+"/"+job.payload.name+" finished!");
        }
    }
    
    public static Result registerForm () {
        return ok (ix.drug.views.html.register.render());
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
            List<ProcessingJob> jobs = ProcessingJobFactory
                .getJobsByPayload(py.id.toString());
            
            ProcessingJob job;
            if (jobs.isEmpty()) {
                job = new ProcessingJob ();
                job.payload = py;
                job.save();
                PQ.submit(new MolJobPersistence (job));
            }
            else {
                job = jobs.iterator().next();
            }
            
            ObjectMapper mapper = new ObjectMapper ();
            return ok ((JsonNode)mapper.valueToTree(job));
        }
        
        return internalServerError
            ("Unable to create payload from multipart request!");
    }

}
