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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnore;

import play.mvc.*;
import play.*;

public class TargetVectorFactory extends Controller implements Commons {
    static final int DIM = 10;
    
    static public class TargetVector {
        @JsonIgnore
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
                else {
                    // pass through
                    vector.put(me.getKey(), me.getValue());
                }
            }
        }

        @JsonProperty("target")
        public String getTargetLink () {
            return Global.getRef(target);
        }
    }

    static public class TanimotoSimilarity {
        @JsonIgnore
        final public TargetVector target1;
        @JsonIgnore
        final public TargetVector target2;
        
        public final double similarity;
        public final Map<String, Double> contribution;
        
        TanimotoSimilarity (TargetVector target1, TargetVector target2) {
            this.target1 = target1;
            this.target2 = target2;

            double a = 0., b = 0., c = 0.;
            final Map<String, Double> contrib = new HashMap<String, Double>();
            for (Map.Entry<String, Number> me : target1.vector.entrySet()) {
                String name = me.getKey();
                double x = me.getValue().doubleValue();
                Number z = target2.vector.get(name);
                if (z != null && name.indexOf("Protein Class") < 0) {
                    double y = z.doubleValue();
                    c += x*y;
                    contrib.put(name, x*y);
                }
                a += x*x;       
            }
            
            for (Map.Entry<String, Number> me : target2.vector.entrySet()) {
                double y = me.getValue().doubleValue();
                b += y*y;
            }

            double z = a+b-c;
            for (Map.Entry<String, Double> me : contrib.entrySet()) {
                me.setValue(me.getValue()/z);
            }
            similarity = c / z;

            contribution = new TreeMap<String, Double>
                (new Comparator<String>() {
                        public int compare (String k1, String k2) {
                            Double v1 = contrib.get(k1);
                            Double v2 = contrib.get(k2);
                            if (v2 > v1) return 1;
                            if (v2 < v1) return -1;
                            return k1.compareTo(k2);
                        }
                    });
            contribution.putAll(contrib);
        }

        @JsonProperty("target_1")
        public String getTarget1 () {
            return Global.getRef(target1.target);
        }
        
        @JsonProperty("target_2")
        public String getTarget2 () {
            return Global.getRef(target2.target);
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
        try {
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
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
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
                    TanimotoSimilarity sim = new TanimotoSimilarity (ti, tj);
                    json.add(mapper.valueToTree(sim));
                }
            }
            
            return ok (json);
        }
        catch (Exception ex) {
            Logger.error("Can't calculate similarity", ex);
            return internalServerError (ex.getMessage());
        }
    }
}
