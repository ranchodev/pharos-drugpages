package ix.idg.controllers;

import java.io.Serializable;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import ix.core.stats.Histogram;

public class Vector implements Serializable, Comparable<Vector>, Commons {
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

    public Histogram createHistogram (int nbins) {
        if (min == null || max == null || values.isEmpty())
            throw new IllegalStateException ("Vector '"+field+"' is empty!");
        
        Histogram hist = new Histogram
            (nbins, min.doubleValue(), max.doubleValue());
        for (Number v : values)
            hist.increment(v.doubleValue());
        
        return hist;
    }    
}
