package ix.npc.controllers;

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

import ix.npc.models.*;

public class NPCApp extends App implements ix.npc.models.Properties {
    public static final ThreadPoolPlugin THREAD_POOL =
        Play.application().plugin(ThreadPoolPlugin.class);
    public static final TextIndexer INDEXER =
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();

    static final String[] ENTITY_FACETS = {
        "Dataset",
        ENTITY_TYPE,
        STRUCTURE_SCAFFOLD,
        "modified"
    };

    static class NPCFacetDecorator extends FacetDecorator {
        // don't show Scaffold facet value
        NPCFacetDecorator (Facet facet) {
            super (facet.filter(fv -> {
                        return !facet.getName().equals(ENTITY_TYPE)
                            || (facet.getName().equals(ENTITY_TYPE)
                                && !fv.getLabel().equals(STRUCTURE_SCAFFOLD));
                    }), true, FACET_DIM);
        }
        NPCFacetDecorator (Facet facet, boolean raw, int dim) {
            super (facet.filter(fv -> {
                        return !facet.getName().equals(ENTITY_TYPE)
                            || (facet.getName().equals(ENTITY_TYPE)
                                && !fv.getLabel().equals(STRUCTURE_SCAFFOLD));
                    }), raw, dim);
        }

        @Override
        public String label (final int i) {
            final String label = super.label(i);
            final String name = super.name();

            if (name.equals(STRUCTURE_SCAFFOLD)
                || name.equals(Structure.H_LyChI_L4)) {
                StringBuilder url = new StringBuilder
                    ("<a href='"+routes.NPCApp.entity(label)+"'");
                try {
                    List<Entity> entities = EntityResult.find(label);
                    if (!entities.isEmpty()) {
                        Structure struc = getStructure (entities.get(0));
                        if (struc != null) {
                            url.append(" tabindex='-1'");
                            url.append(" data-toggle='popover'");
                            url.append(" data-animation='true'");
                            url.append(" data-placement='top'");
                            url.append(" data-trigger='hover'");
                            url.append(" data-html='true'");
                            url.append(" data-content=\"<img src='");
                            url.append
                                (ix.ncats.controllers.routes.App.structure
                                 (struc.getId(),"svg",200,null).toString());
                            url.append("'>\"");
                        }
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.warn("Can't retrieve structure for ligand \""
                                +label+"\"!");
                }
                url.append("><span style='font-family:monospace'>"
                           +label+"</span></a>");
                
                return url.toString();
            }
            return label;
        }  // label()
    } // NPCFacetDecorator
    
    static protected class EntityStructureSearchResultProcessor
        extends SearchResultProcessor<StructureIndexer.Result> {

        protected EntityStructureSearchResultProcessor () throws IOException {
        }

        @Override
        protected Object instrument (final StructureIndexer.Result r)
            throws Exception {
            List<Entity> entities = getOrElse
                (getClass().getName()+"/structure/"+r.getId(),
                 new Callable<List<Entity>> () {
                     public List<Entity> call () {
                         return EntityFactory.finder
                         .where().eq("links.refid", r.getId()).findList();
                     }
                 });

            Entity e = null;
            if (!entities.isEmpty()) {
                e = entities.get(0);
                
                int[] amap = new int[r.getMol().getAtomCount()];
                int i = 0, nmaps = 0;
                for (MolAtom ma : r.getMol().getAtomArray()) {
                    amap[i] = ma.getAtomMap();
                    if (amap[i] > 0)
                        ++nmaps;
                    ++i;
                }
                
                if (nmaps > 0) {
                    IxCache.set("AtomMaps/"+getContext().getId()+"/"
                                +r.getId(), amap);
                }
            }
            
            return e;
        }
    }
    
    static public FacetDecorator[] decorate (Facet... facets) {
        List<FacetDecorator> decors = new ArrayList<FacetDecorator>();
        // override decorator as needed here
        for (int i = 0; i < facets.length; ++i) {
            NPCFacetDecorator decor = new NPCFacetDecorator (facets[i]);
            decors.add(decor);
        }

        for (FacetDecorator f : decors) {
            if (!f.hidden) {
                TermVectors tvs = SearchFactory.getTermVectors
                    (Entity.class, f.facet.getName());
                
                if (Global.DEBUG(2))
                    Logger.debug("Facet "+f.facet.getName());
                for (int i = 0; i < f.facet.size(); ++i) {
                    TextIndexer.FV fv = f.facet.getValue(i);
                    f.total[i] = tvs.getTermCount(fv.getLabel());
                    if (Global.DEBUG(2))
                        Logger.debug("  + "+fv.getLabel()+" "
                                     +fv.getCount()+"/"+f.total[i]);
                }
            }
        }
        
        return decors.toArray(new FacetDecorator[0]);
    }

    public static Result index () {
        return redirect (routes.NPCApp.entities(null, 15, 1));
    }

    static Result _entities (final String q, final int rows, final int page)
        throws Exception {
        final String key = "entities/"+Util.sha1(request ());
        Logger.debug("entities: q="+q+" rows="+rows+" page="+page+" key="+key);
        
        final int total = EntityFactory.finder.findRowCount();
        if (request().queryString().containsKey("facet") || q != null) {
            final SearchResult result =
                getSearchResult (Entity.class, q, total, getRequestQuery (),
                                 createDateFacetRange ("modified"));

            return createEntityResult (result, rows, page);
        }
        else {
            return getOrElse_ (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        Facet[] facets = filter
                            (getFacets
                             (Entity.class, FACET_DIM,
                              createDateFacetRange ("modified")),
                             ENTITY_FACETS);
                                                    
                        int _rows = Math.max(1, Math.min(total, rows));
                        int[] pages = paging (_rows, page, total);
            
                        List<Entity> entities = EntityFactory.getEntities
                            (_rows, (page-1)*_rows, null);
            
                        return ok (ix.npc.views.html.entities.render
                                   (page, _rows, total, pages,
                                    decorate (facets), entities, null));
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
            return _internalServerError (ex);
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

        return ix.npc.views.html.entities.render
            (page, rows, result.count(),
             pages, decorate (facets), entities, null);
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
        return ix.npc.views.html.entitydetails.render(e);
    }
    
    public static Result entities (String q, final int rows, final int page) {
        String type = request().getQueryString("type");
        if (q != null && q.trim().length() == 0)
            q = null;
        
        long start = System.currentTimeMillis();
        try {
            if (type != null && (type.equalsIgnoreCase("substructure")
                                 || type.equalsIgnoreCase("similarity"))) {
                // structure search
                String cutoff = request().getQueryString("cutoff");
                Logger.debug("Search: q="+q+" type="+type+" cutoff="+cutoff);
                if (type.equalsIgnoreCase("substructure")) {
                    return substructure (q, rows, page);
                }
                else {
                    return similarity
                        (q, Double.parseDouble(cutoff), rows, page);
                }
            }

            return _entities (q, rows, page);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
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
        if (request() != null) {
            String []facets = request().queryString().get("facet");
            if (facets != null) {
                for (String f : facets) {
                    int pos = f.indexOf('/');
                    if (pos > 0) {
                        String fn = f.substring(0, pos);
                        if (Structure.H_LyChI_L4.equals(fn)
                            || STRUCTURE_SCAFFOLD.equals(fn)) {
                            String fv = f.substring(pos+1);
                            for (XRef ref : e.getLinks()) {
                                try {
                                    if (Structure.class
                                        .isAssignableFrom
                                        (Class.forName(ref.kind))) {
                                        for (Value v : ref.properties) {
                                            if (v.label.equals(STRUCTURE_SCAFFOLD)
                                                && fv.equals(v.getValue()))
                                                return (Structure)ref.deRef();
                                        }
                                    }
                                }
                                catch (Exception ex) {
                                    Logger.error
                                        ("Unknown class["+ref.kind
                                         +"] in xref "+ref.id, ex);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return e.getLinkedObject(Structure.class, STRUCTURE_KW);
    }

    public static Result error (int code, String mesg) {
        return ok (ix.npc.views.html.error.render(code, mesg));
    }

    public static Result _notFound (String mesg) {
        return notFound (ix.npc.views.html.error.render(404, mesg));
    }

    public static Result _badRequest (String mesg) {
        return badRequest (ix.npc.views.html.error.render(400, mesg));
    }
    
    public static Result _internalServerError (String mesg) {
        return internalServerError
            (ix.npc.views.html.error.render
             (500, "Internal server error: "+mesg));    
    }
    
    public static Result _internalServerError (Throwable t) {
        t.printStackTrace();
        return _internalServerError (t.getMessage());
    }

    public static Result sketcher (String s) {
        return ok (ix.npc.views.html.sketcher.render(s));
    }

    public static Result substructure
        (final String query, final int rows, int page) {
        try {
            SearchResultContext context =
                substructure (query, rows, page,
                              new EntityStructureSearchResultProcessor ());
            
            if (context != null) {
                return fetchResult (context, rows, page);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform substructure search", ex);
        }
        
        return _internalServerError
            ("Unable to perform substructure search: "+query);
    }

    public static Result similarity (final String query,
                                     final double threshold,
                                     final int rows,
                                     final int page) {
        try {
            SearchResultContext context = similarity
                (query, threshold, rows, page,
                 new EntityStructureSearchResultProcessor ());
            
            if (context != null) {
                return fetchResult (context, rows, page);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform similarity search", ex);
        }
        
        return _internalServerError
            ("Unable to perform similarity search: "+query);
    }

    public static Result fetchResult
        (final SearchResultContext context, int rows, int page)
        throws Exception {
        return App.fetchResult
            (context, rows, page, new DefaultResultRenderer<Entity> () {
                    public Content getContent
                        (SearchResultContext context,
                         int page, int rows,
                         int total, int[] pages,
                         List<Facet> facets, List<Entity> entities) {
                        return ix.npc.views.html.entities.render
                            (page, rows, total,
                             pages, decorate (filter
                                              (facets, ENTITY_FACETS)),
                             entities, context.getId());
                    }
                    
                    public SearchOptions.FacetRange[] getRangeFacets () {
                        return new SearchOptions.FacetRange[]{
                            createDateFacetRange ("modified")
                        };
                    }
                });
    }

    /*
     * with structure highlighting
     */
    public static Result structure (final String id,
                                    final String format, final int size,
                                    final String context) {

        Structure struc = App.structure(id);
        if (struc == null) {
            return notFound ("Unknown structure "+id);
        }

        int[] amap = null;
        if (context != null) {
            amap = (int[])IxCache.get("AtomMaps/"+context+"/"+id);
        }

        if (amap == null) {
            for (XRef ref : struc.links) {
                for (Value v : ref.properties) {
                    if (STRUCTURE_PARENT.equals(v.label)) {
                        String sv = (String)v.getValue();
                        if (sv != null) {
                            String[] av = sv.split(",");
                            amap = new int[av.length];
                            for (int i = 0; i < av.length; ++i)
                                amap[i] = Integer.parseInt(av[i]);
                            
                            struc = (Structure)ref.deRef();
                            break;
                        }
                    }
                }
            }
        }
        else if (context != null) {
            //Logger.warn("Can't find atom mapping for context "+context);
        }

        String atomMap = "";
        if (amap != null && amap.length > 0) {
            //Logger.debug("AtomMaps/"+context+" => "+amap);
            StringBuilder sb = new StringBuilder ();
            sb.append(amap[0]);
            for (int i = 1; i < amap.length; ++i)
                sb.append(","+amap[i]);
            atomMap = sb.toString();
        }
        else {
            atomMap = context;
        }
        
        return App.structure(struc, format, size, atomMap);
    }

    public static TermVectors getTermVectors
        (String field, String facet, String value) {
        Map<String, String> params = new HashMap<>();
        params.put(facet, value);
        return SearchFactory.getConditionalTermVectors
            (Entity.class, field, params);
    }

    public static List<Entity> getScaffolds (Entity e) {
        List<Entity> scafs = new ArrayList<>();
        for (XRef xref : e.getLinks()) {
            try {
                if (Structure.class.isAssignableFrom
                    (Class.forName(xref.kind))) {
                    for (Value v : xref.properties) {
                        if (STRUCTURE_SCAFFOLD.equals(v.label)) {
                            List<Entity> ents =
                                EntityResult.find((String)v.getValue());
                            if (!ents.isEmpty()) {
                                scafs.addAll(ents);
                            }
                        }
                    }
                }
            }
            catch (Exception ex) {
                Logger.error("Can't resolve xref type: "+xref.kind, ex);
            }
        }
        return scafs;
    }
}
