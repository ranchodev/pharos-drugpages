package ix.idg.controllers;

import java.io.Serializable;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import ix.core.stats.Histogram;

public class Vector implements Serializable, Comparable<Vector>, Commons {
    private static final long serialVersionUID = 1L; //
    static final double EPSILON = 0.01;

    class AdaptiveHistogram {
        List<Double> range = new ArrayList<>();
        int index;
        final int nbins;
        final int max; // max number of bins

        AdaptiveHistogram (int nbins) {
            this (nbins, 50);
        }
        
        AdaptiveHistogram (int nbins, int max) {
            this.nbins = nbins;
            this.max = max;
        }

        public void iterate (double min, double max) {
            if (range.size() + nbins >= max)
                return; // stop
            
            Histogram hist = new Histogram (nbins, min, max);
            for (Number v : values)
                hist.increment(v.doubleValue());

            double threshold = Math.sqrt(hist.getWeight());
            double[] r = hist.getRanges();            
            double[] bins = hist.getBins();
            for (int i = 0; i < bins.length; ++i) {
                double x = bins[i];
                double delta = r[i+1] - r[i];
                if (x > threshold && delta > EPSILON) {
                    // expand this range: ranges[i] and ranges[i+1]
                    //System.err.println("** expanding range: ["+r[i]+","
                    //+r[i+1]+")");
                    iterate (r[i], r[i+1]);
                }
                else {
                    range.add(r[i]);
                }
            }
        }

        public Histogram getHistogram () {
            
            StringBuilder sb = new StringBuilder ();
            sb.append("range \""+field+"\" <");
            for (int i = 0; i < range.size(); ++i) {
                sb.append(range.get(i));
                if (i+1 < range.size()) sb.append(" ");
            }
            sb.append(">");
            System.err.println(sb.toString());

            Histogram h;
            if (!range.isEmpty()) {
                double[] r = new double[range.size()];
                for (int i = 0; i < r.length; ++i)
                    r[i] = range.get(i);
                
                h = new Histogram (r);
                for (Number v : values)
                    h.increment(v.doubleValue());
            }
            else
                h = new Histogram (0);
            
            return h;
        }
    }
    
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
        
        //System.err.println("[****** "+field+" *******]");
        Histogram h = new Histogram
            (nbins, min.doubleValue(), max.doubleValue());
        for (Number v : values)
            h.increment(v.doubleValue());

        // FIXME: can't use adaptive histogram without padding so that
        // we have the same number of bins!
        /*
        if (Math.abs(max.doubleValue() - min.doubleValue()) <= EPSILON) {
            h = new Histogram (nbins, min.doubleValue(), max.doubleValue());
        }
        else {
            AdaptiveHistogram ah = new AdaptiveHistogram (nbins);
            ah.iterate(min.doubleValue(), max.doubleValue());
            h = ah.getHistogram();
        }
        */
        return h;
    }
}
