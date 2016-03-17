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

    static final Random rand = new Random ();
    static void assignRandomSize (DTOParser.Node node) {
        if (node.children.isEmpty()) {
            node.size = rand.nextInt(100);
        }
        else {
            for (DTOParser.Node child : node.children) {
                assignRandomSize (child);
            }
        }
    }
    
    public static DTOParser getDTO () {
        String param = Play.application()
            .configuration().getString("ix.idg.dto");
        if (param == null) {
            throw new RuntimeException ("No dto file specified!");
        }

        try {
            DTOParser parser = new DTOParser ();
            DTOParser.Node root = parser.parse(new File (param));
            assignRandomSize (root);
            Logger.debug("## DTO parsed!");
            
            return parser;
        }
        catch (IOException ex) {
            throw new RuntimeException ("Can't parse DTO file "+param);
        }
    }

    @Cached(key="idg/DTO", duration = Integer.MAX_VALUE)    
    public static Result dto (String ctx) {
        return ok (ix.idg.views.html.dto.render(ctx));
    }

    public static Result dtoViz () {
        return ok (ix.idg.views.html.dtoviz.render());
    }
    
    public static Result dtoNode (final String label) {
        final String key = "idg/dto/"+label;
        try {
            return getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        ObjectMapper mapper = new ObjectMapper ();
                        final DTOParser dto = getDTO ();
                        if (label != null) {
                            return ok (mapper.valueToTree(dto.get(label)));
                        }
                        // return the top-level gene
                        return ok (mapper.valueToTree(dto.get("DTO_00200000")));
                    }
                });
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    @Cached(key="idg/DTO/GeneRoot", duration = Integer.MAX_VALUE)
    public static Result dtoGeneRoot () {
        ObjectMapper mapper = new ObjectMapper ();
        return ok (mapper.valueToTree(getDTO().get("DTO_00200000")));
    }
}
