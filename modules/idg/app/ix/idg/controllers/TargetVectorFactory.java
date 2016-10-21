package ix.idg.controllers;

import java.util.*;

import ix.idg.models.Target;
import ix.core.controllers.search.SearchFactory;
import ix.core.plugins.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.mvc.*;
import play.*;

public class TargetVectorFactory extends Controller {
    public static Result targetVectors () {
        DictVector[] dictVectors = DictVector.getInstances(Target.class);
        Vector[] vectors = Vector.getVectors(Target.class);

        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode json = mapper.createArrayNode();
        for (DictVector dv : dictVectors) {
            ObjectNode node = mapper.createObjectNode();
            node.put("dim", dv.field);
            ArrayNode n = mapper.createArrayNode();
            for (int i = 0; i < dv.termCountProfile.length; ++i)
                n.add(dv.termCountProfile[i]);
            node.put("profile", n);
            json.add(node);
        }
        
        return ok (json);
    }
}
