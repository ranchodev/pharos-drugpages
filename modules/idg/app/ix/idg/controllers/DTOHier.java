package ix.idg.controllers;

import java.io.*;
import java.util.*;
import java.util.concurrent.Callable;

import play.*;
import play.cache.Cached;
import play.mvc.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnore;

import ix.utils.Util;
import ix.ncats.controllers.App;
import ix.idg.models.Target;
import ix.core.models.Keyword;
import ix.core.plugins.IxCache;

import static ix.core.search.TextIndexer.Facet;
import static ix.core.search.TextIndexer.SearchResult;

public class DTOHier extends IDGApp {
    static final String DTOROOT = "DTO_00200000";
    
    public static final String[] DTO_FACETS = {
        //IDG_DEVELOPMENT,
        IDG_DISEASE,
        IDG_LIGAND,
        IDG_TISSUE
    };
    
    public static DTOParser getDTO () {
        String param = Play.application()
            .configuration().getString("ix.idg.dto.enhanced");
        if (param == null) {
            throw new RuntimeException ("No dto file specified!");
        }
        
        File file = new File (param);
        if (!file.exists()) {
            // now let's treat it as a resource..
            try {
                InputStream is = Play.application().resourceAsStream(param);
                DTOParser dto = DTOParser.readJson(is);
                Logger.debug("## DTO parsed from resource "+param);
                return dto;
            }
            catch (Exception ex) {
                ex.printStackTrace();
                throw new RuntimeException (ex);
            }
        }
        
        try {
            DTOParser dto = DTOParser.readJson(file);
            Logger.debug("## DTO parsed from file "+file);
            return dto;
        }
        catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException ("Can't parse DTO file "+param);
        }
    }

    public static Result dto (final String node) {
        final String key = "idg/DTO/"+node+"/"+Util.sha1(request ());
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () {
                        return _dto (node);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    static Result _dto (final String node) {
        SearchResult result;
        Map<String, String[]> query = request().queryString();

        DTOParser dto = getDTO ();      
        if (query.containsKey("facet")) {
            int total = TargetFactory.finder.findRowCount();
            result = getSearchResult (Target.class, null, total, query);
            List matches = result.getMatches();
            Set<DTOParser.Node> keep = new HashSet<DTOParser.Node>();
            for (int i = 0; i < matches.size(); ++i) {
                Target t = (Target)matches.get(i);
                
                DTOParser.Node n = null;
                for (Keyword kw : t.getSynonyms()) {
                    n = dto.get(kw.term);
                    if (n != null) break;
                }
                
                if (n == null) {
                    Logger.warn
                        ("Can't lookup target \""+getId (t)+"\" in DTO!");
                }
                else {
                    keep.add(n);
                }
            }
            
            Set<DTOParser.Node> all =
                new HashSet<DTOParser.Node>(dto.nodes());
            all.removeAll(keep);
            // remove the tdl so that it doesn't hightlight
            for (DTOParser.Node n : all) {
                n.tdl = null;
            }
            Logger.debug(result.getKey()+": "+matches.size()
                         +" matches filtered!");
        }
        else {
            result = getSearchFacets(Target.class);
        }
        IxCache.set("DTO/"+result.getKey(), dto);
                    
        return ok (ix.idg.views.html.dto.render
                   (result.getKey(), decorate (filter (result.getFacets(),
                                                       DTO_FACETS))));
    }

    public static Result dtoViz (final String node) {
        final String key = "idg/DTO/viz/"+node;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return ok (ix.idg.views.html.dtoviz.render(node));
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }
    
    public static Result dtoNode (final String label) {
        final String key = "idg/DTO/json/"+label;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        final DTOParser dto = (DTOParser)IxCache.get
                            ("DTO/"+label);
                        // return the top-level gene
                        if (dto != null) {
                            ObjectMapper mapper = new ObjectMapper ();
                            return ok (mapper.valueToTree(dto.get(DTOROOT)));
                        }
                        
                        Logger.warn("No DTO in cache for "+label);
                        return internalServerError ("");
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return _internalServerError (ex);
        }
    }

    @Cached(key="idg/DTO/GeneRoot", duration = Integer.MAX_VALUE)
    public static Result dtoGeneRoot () {
        ObjectMapper mapper = new ObjectMapper ();
        return ok (mapper.valueToTree(getDTO().get(DTOROOT)));
    }
}
