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
import ix.utils.Global;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.mvc.*;
import play.*;

public class TargetVectorFactory extends Controller implements Commons {
    static final int DIM = 10;
    
    static public class TargetVector {
        final public Target target;
        final public Map<String, Number> descriptor;
        final public Map<String, Number> vector;
        
        TargetVector (Long id) throws Exception {
            target = (Target) ObjectFactory.get(Target.class, id);
            descriptor = EntityDescriptor.get(Target.class, id);
            if (descriptor == null)
                throw new IllegalArgumentException ("Unknown target "+id);
            Map<String, Histogram> histogram =
                EntityDescriptor.getDescriptorHistograms(Target.class, DIM);
            
            vector = new TreeMap<String, Number>();
            for (Map.Entry<String, Number> me : descriptor.entrySet()) {
                Histogram hist = histogram.get(me.getKey());
                if (hist != null) {
                    double mass = hist.eval(me.getValue().doubleValue());
                    vector.put(me.getKey(), mass/hist.getWeight());
                }
            }
        }
    }

    protected TargetVectorFactory () {
        
    }
    
    public static Result targetVector (Long id) {
        try {
            TargetVector tv = new TargetVector (id);
            ObjectMapper mapper = new ObjectMapper ();
            return ok (mapper.valueToTree(tv));
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }

    public static Result descriptorVectors () {
        ObjectMapper mapper = new ObjectMapper ();
        Map<String, Histogram> histograms =
            EntityDescriptor.getDescriptorHistograms(Target.class, DIM);
        ArrayNode json = mapper.createArrayNode();
        for (Map.Entry<String, Histogram> me : histograms.entrySet()) {
            ObjectNode n = mapper.createObjectNode();
            n.put("name", me.getKey());
            n.put("histogram", mapper.valueToTree(me.getValue()));
            json.add(n);
        }
        return ok (json);
    }

    public static double tanimoto (Map<String, Number> v1,
                                   Map<String, Number> v2) {
        double a = 0., b = 0., c = 0.;
        for (Map.Entry<String, Number> me : v1.entrySet()) {
            double x = me.getValue().doubleValue();
            Number z = v2.get(me.getKey());
            if (z != null) {
                double y = z.doubleValue();
                c += x*y;
            }
            a += x*x;       
        }
        
        for (Map.Entry<String, Number> me : v2.entrySet()) {
            double y = me.getValue().doubleValue();
            b += y*y;
        }
            
        return c / (a+b-c);
    }

    public static Result targetSimilarity (String ids) {
        try {
            List<Long> targets = new ArrayList<Long>();
            for (String s : ids.split(",")) {
                try {
                    targets.add(Long.parseLong(s));
                }
                catch (NumberFormatException ex) {
                    Logger.debug("Bogus target id: "+s);
                }
            }

            if (targets.isEmpty())
                return badRequest ("Not a valid target id list: "+ids);

            ObjectMapper mapper = new ObjectMapper ();
            ArrayNode json = mapper.createArrayNode();
            for (int i = 0; i < targets.size(); ++i) {
                TargetVector ti = new TargetVector (targets.get(i));
                for (int j = i+1; j < targets.size(); ++j) {
                    TargetVector tj = new TargetVector (targets.get(j));
                    double tan = tanimoto (ti.vector, tj.vector);
                    ObjectNode node = mapper.createObjectNode();
                    node.put("target_1", Global.getRef(ti.target));
                    node.put("target_2", Global.getRef(tj.target));
                    node.put("tanimoto", tan);
                    json.add(node);
                }
            }
            
            return ok (json);
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }
}
