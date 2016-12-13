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
                EntityDescriptor.getDescriptorHistograms(Target.class);
            
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

            final Map<String, Double> contrib = new HashMap<>();
            similarity = EntityDescriptor.tanimoto
                (target1.vector, target2.vector, contrib);

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

        @JsonProperty("size_1")
        public int getSize1 () { return target1.vector.size(); }
        @JsonProperty("size_2")
        public int getSize2 () { return target2.vector.size(); } 
        public int getSize () { return contribution.size(); }

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
                EntityDescriptor.getDescriptorHistograms(Target.class);
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

    public static Result allPairwiseSimilarity () {
        if (Play.isDev()) {
            try {
                EntityDescriptor<Target> edt =
                    EntityDescriptor.getInstance(Target.class);
                return ok ("size = "+edt.allPairwiseSimilarity());
            }
            catch (Exception ex) {
                Logger.error("Can't calculate all pairwise similarity", ex);
                return internalServerError (ex.getMessage());
            }
        }
        
        return IDGApp._notFound("Unknown resource: "+request().uri());
    }

    public static Result targetPairwiseSimilarity (String ids) {
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

    public static Result targetSimilarity (Long id) {
        try {
            ObjectMapper mapper = new ObjectMapper ();
            EntityDescriptor<Target> edt =
                EntityDescriptor.getInstance(Target.class);
            return ok (mapper.valueToTree(edt.similarity(id, 10)));
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve similarity for "+id, ex);
            return internalServerError (ex.getMessage());
        }
    }

    public static Result dumpDescriptorSparse (Integer dim) {
        if (Play.isDev()) {
            try {            
                if (!EntityDescriptor.getDescriptorVectors
                    (Target.class).isEmpty()) {
                    EntityDescriptor<Target> edt =
                        EntityDescriptor.getInstance(Target.class);
                    edt.dumpDescriptorSparse(dim);
                    return ok ("Descriptors have been dumpped!");
                
                }
                else {
                    return badRequest
                        ("Descriptor vectors are being generated; retry "
                         +"when descriptors have been generated!");
                }
            }
            catch (Exception ex) {
                Logger.error("Can't dump descriptors in sparse format!", ex);
                return internalServerError (ex.getMessage());
            }
        }
        
        return notFound ("Bad resource: "+request().uri());
    }
}
