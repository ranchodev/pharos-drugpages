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

    static final String[] ENTITY_FACETS = {
        "Dataset",
        "Entity Type",
        "StereoChemistry",
        "Stereocenters",
        "Defined Stereocenters",
        "LyChI_L4",
        "LyChI_L3"
    };
    
    static class MolJobPersistence
        extends PersistenceQueue.AbstractPersistenceContext {

        final ProcessingJob job;
        MolJobPersistence (ProcessingJob job) {
            this.job = job;
        }

        public void persists () throws Exception {
            int count = 0;          
            try {
                job.start = System.currentTimeMillis();
                job.status = ProcessingJob.Status.RUNNING;

                Keyword ds = KeywordFactory.registerIfAbsent
                    (DATASET, job.payload.name, null);
                
                MolImporter mi = new MolImporter
                    (PayloadFactory.getStream(job.payload));
                mi.setGrabbingEnabled(true);
                for (Molecule mol = new Molecule (); mi.read(mol); ) {
                    Entity ent = instrument (mol);
                    ent.properties.add
                        (new Text (ORIGINAL_INPUT,
                                   mi.getGrabbedMoleculeString()));
                    
                    XRef xref = new XRef (job.payload);
                    xref.properties.add(ds);
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
                Logger.error("Job "+job.id+" for payload "+job.payload.name
                             +" failed!", ex);
            }
            job.stop = System.currentTimeMillis();
            job.update();
            Logger.debug("Job "+job.id+"/"+job.payload.name
                         +" finished processing "+count+" entities!");
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

    static public FacetDecorator[] decorate (Facet... facets) {
        List<FacetDecorator> decors = new ArrayList<FacetDecorator>();
        // override decorator as needed here
        for (int i = 0; i < facets.length; ++i) {
            decors.add(new FacetDecorator (facets[i], false, 100));
        }
        
        return decors.toArray(new FacetDecorator[0]);
    }

    public static Result index () {
        return redirect (routes.DrugApp.entities(null, 15, 1));
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

            return redirect (routes.DrugApp.index());
        }
        
        return internalServerError
            ("Unable to create payload from multipart request!");
    }

    static Result _entities (final String q, final int rows, final int page)
        throws Exception {
        final String key = "entities/"+Util.sha1(request ());
        Logger.debug("entities: q="+q+" rows="+rows+" page="+page+" key="+key);
        
        final int total = EntityFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            final SearchResult result =
                getSearchResult (Entity.class, q, total, getRequestQuery ());
            

            return createEntityResult (result, rows, page);
        }
        else {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        Facet[] facets =
                            filter (getFacets (Entity.class, FACET_DIM),
                                    ENTITY_FACETS);
            
                        int _rows = Math.max(1, Math.min(total, rows));
                        int[] pages = paging (_rows, page, total);
            
                        List<Entity> entities = EntityFactory.getEntities
                            (_rows, (page-1)*_rows, null);
            
                        return ok (ix.drug.views.html.entities.render
                                   (page, _rows, total, pages,
                                    decorate (facets), entities));
                    }
                });
        }
    }

    static Result createEntityResult
        (final SearchResult result, final int rows, final int page) {
        try {
            if (result.finished()) {
                final String key = "createEntityResult/"+Util.sha1(request());
                return ok (getOrElse (key, new Callable<Content>() {
                        public Content call () throws Exception {
                            return CachableContent.wrap
                                (createEntityContent (result, rows, page));
                        }
                    }));
            }
            return ok (createEntityContent (result, rows, page));
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    static Content createEntityContent
        (SearchResult result, int rows, int page) {
        Facet[] facets = filter (result.getFacets(), ENTITY_FACETS);

        List<Entity> entities = new ArrayList<Entity>();
        int[] pages = new int[0];
        if (result.count() > 0) {
            rows = Math.min(result.count(), Math.max(1, rows));
            pages = paging (rows, page, result.count());
            result.copyTo(entities, (page-1)*rows, rows);
        }

        return ix.drug.views.html.entities.render
            (page, rows, result.count(),
             pages, decorate (facets), entities);
    }

    static final GetResult<Entity> EntityResult =
        new GetResult<Entity>(Entity.class, EntityFactory.finder) {
            @Override
            public Content getContent (List<Entity> entities) throws Exception {
                return getEntityContent (entities);
            }
        };

    static Content getEntityContent (List<Entity> entities) throws Exception {
        Entity e = entities.get(0);
        return ix.drug.views.html.entitydetails.render(e);
    }
    
    public static Result entities (String q, final int rows, final int page) {
        String type = request().getQueryString("type");
        if (q != null && q.trim().length() == 0)
            q = null;
        
        long start = System.currentTimeMillis();
        try {
            if (type != null) {
            }

            return _entities (q, rows, page);
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    public static Result entity (String name) {
        return EntityResult.get(name);
    }

    public static Entity[][] toMatrix (int column, List<Entity> entities) {
        int nr = (entities.size()+column-1)/column;
        Entity[][] m = new Entity[nr][column];
        for (int i = 0; i < entities.size(); ++i)
            m[i/column][i%column] = entities.get(i);
        return m;
    }

    static final Keyword STRUCTURE_KW =
        new Keyword (STRUCTURE_TYPE, STRUCTURE_ORIGINAL);
    public static Structure getStructure (Entity e) {
        return e.getLinkedObject(Structure.class, STRUCTURE_KW);
    }
}
