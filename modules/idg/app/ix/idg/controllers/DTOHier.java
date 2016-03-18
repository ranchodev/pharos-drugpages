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

import ix.ncats.controllers.App;

public class DTOHier extends App {
    static final String DTOROOT = "DTO_00200000";

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
        final String key = "idg/DTO/"+node;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return ok (ix.idg.views.html.dto.render(node));
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return IDGApp._internalServerError(ex);
        }
    }

    public static Result dtoViz (final String node) {
        final String key = "idg/DTO/viz/"+node;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        return ok (ix.idg.views.html.dtoviz.render
                                   (node != null ? node : DTOROOT));
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return IDGApp._internalServerError (ex);
        }
    }
    
    public static Result dtoNode (final String label) {
        final String key = "idg/DTO/json/"+label;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        ObjectMapper mapper = new ObjectMapper ();
                        final DTOParser dto = getDTO ();
                        if (label != null) {
                            return ok (mapper.valueToTree(dto.get(label)));
                        }
                        // return the top-level gene
                        return ok (mapper.valueToTree(dto.get(DTOROOT)));
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

    @Cached(key="idg/DTO/GeneRoot", duration = Integer.MAX_VALUE)
    public static Result dtoGeneRoot () {
        ObjectMapper mapper = new ObjectMapper ();
        return ok (mapper.valueToTree(getDTO().get(DTOROOT)));
    }
}
