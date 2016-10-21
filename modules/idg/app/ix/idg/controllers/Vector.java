package ix.idg.controllers;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;

import com.avaje.ebean.Query;
import com.avaje.ebean.QueryIterator;
import play.db.ebean.Model;
import play.Logger;

import ix.core.models.Indexable;
import ix.core.plugins.IxCache;
import ix.core.ObjectFactory;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.Facet;

public class Vector implements Serializable, Comparable<Vector> {
    private static final long serialVersionUID = 1L; //
    
    public final Class kind;
    public final String field;
    
    public Number min;
    public Number max;
    public List<Number> values = new ArrayList<Number>();

    protected Vector (Class kind, String field) {
        this.kind = kind;
        this.field = field;
    }

    public int size () { return values.size(); }
    public int compareTo (Vector vec) {
        int d = kind.getName().compareTo(vec.kind.getName());
        if (d == 0)
            d = field.compareTo(vec.field);
        return d;
    }

    @Override
    public boolean equals (Object obj) {
        if (obj == this)
            return true;
        
        if (obj instanceof Vector) {
            Vector vec = (Vector)obj;
            return compareTo (vec) == 0;
        }
        
        return false;
    }

    @Override
    public int hashCode () {
        return kind.hashCode() ^ field.hashCode();
    }

    public static Vector[] getVectors (final Class kind) {
        final String key = Vector.class.getName()
            +"/vectors/"+kind.getName();
        try {
            return IxCache.getOrElse
                (key, new Callable<Vector[]> () {
                        public Vector[] call () throws Exception {
                            return _getVectors (kind);
                        }
                    });
        }
        catch (Exception ex) {
            Logger.trace("Can't generate term vector summaries for "+kind, ex);
        }
        return null;
    }
    
    public static Vector[] _getVectors (Class kind) throws Exception {
        Map<String, Field> fields = new HashMap<String, Field>();
        // see if this field is numerical
        for (Field f : kind.getFields()) {
            Indexable idx = f.getAnnotation(Indexable.class);
            if (idx != null && Number.class.isAssignableFrom(f.getType()))
                fields.put(f.getName(), f);
        }

        Logger.debug("vector fields: "+fields);

        Map<String, Vector> vectors = new HashMap<String, Vector>();    
        if (!fields.isEmpty()) {
            Model.Finder finder = ObjectFactory.finder(kind);
            QueryIterator qiter = finder.findIterate();
            try {
                while (qiter.hasNext()) {
                    Object obj = qiter.next();
                    for (Map.Entry<String, Field> me : fields.entrySet()) {
                        Field f = me.getValue();
                        Object v = f.get(obj);
                        if (v != null) {
                            Vector vec = vectors.get(me.getKey());
                            if (vec == null) {
                                vectors.put
                                    (me.getKey(),
                                     vec = new Vector (kind, me.getKey()));
                            }
                            
                            Number nv = (Number)v;
                            vec.values.add(nv);
                            if (vec.min == null
                                || vec.min.doubleValue() > nv.doubleValue())
                                vec.min = nv;
                            
                            if (vec.max == null
                                || vec.max.doubleValue() < nv.doubleValue())
                                vec.max = nv;                       
                        }
                    }
                }

                for (Map.Entry<String, Vector> me : vectors.entrySet()) {
                    Vector v = me.getValue();
                    Logger.debug(me.getKey()+": min="+v.min+" max="
                                 +v.max+" size="+v.values.size());
                }
            }
            finally {
                qiter.close();
            }
        }
        
        return vectors.values().toArray(new Vector[0]);
    }
}
