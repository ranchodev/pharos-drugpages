package ix.idg.controllers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.*;

import ix.core.stats.Histogram;
import ix.core.models.Indexable;
import ix.core.models.Value;
import ix.core.models.Keyword;
import ix.core.models.XRef;
import ix.idg.models.Target;
import ix.core.controllers.search.SearchFactory;
import ix.core.plugins.*;
import ix.core.ObjectFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.mvc.*;
import play.*;

public class TargetVectorFactory extends Controller implements Commons {
    static final int DIM = 10;

    protected TargetVectorFactory () {
    }

        
    static JsonNode createNode (ObjectMapper mapper, Vector v) {
        ObjectNode node = mapper.createObjectNode();
        node.put("dim", v.field);
        Histogram hist = v.createHistogram(10);
        node.put("histogram", mapper.valueToTree(hist));
        return node;
    }
    
    public static Result targetVectors () {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode json = mapper.createArrayNode();
        
        return ok (json);
    }

    public static Result targetDescriptor (Long id) {
        Map<String, Number> descriptor =
            EntityDescriptor.get(Target.class, id);
        if (descriptor != null) {
            Map<String, Histogram> histogram =
                EntityDescriptor.getDescriptorHistograms(Target.class, DIM);
            
            Map<String, Double> dvec = new TreeMap<String, Double>();
            for (Map.Entry<String, Number> me : descriptor.entrySet()) {
                Histogram hist = histogram.get(me.getKey());
                if (hist != null) {
                    double mass = hist.eval(me.getValue().doubleValue());
                    dvec.put(me.getKey(), mass/hist.getWeight());
                }
            }
            
            ObjectMapper mapper = new ObjectMapper ();
            ObjectNode json = mapper.createObjectNode();
            json.put("descriptor", mapper.valueToTree(descriptor));
            json.put("dvector", mapper.valueToTree(dvec));
            return ok (json);
        }
        
        return internalServerError
            ("Can't generate target descriptor for "+id);
    }

    public static Result descriptorVectors () {
        ObjectMapper mapper = new ObjectMapper ();
        Map<String, Histogram> histograms =
            EntityDescriptor.getDescriptorHistograms(Target.class, 10);
        ArrayNode json = mapper.createArrayNode();
        for (Map.Entry<String, Histogram> me : histograms.entrySet()) {
            ObjectNode n = mapper.createObjectNode();
            n.put("name", me.getKey());
            n.put("histogram", mapper.valueToTree(me.getValue()));
            json.add(n);
        }
        return ok (json);
    }
}
