package ix.core.stats;

import java.util.Arrays;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.IOException;
import java.io.Serializable;

public class Histogram implements Serializable {
    private static final long serialVersionUID = 12242007L;

    private static final double LN2 = Math.log(2.);

    // bin[i] corresponds to range[i] <= x < range[i+1]
    protected double[] range;
    protected double[] bin;
    protected double negInfBin = 0.; // the left bin: (-inf, range[0])
    protected double posInfBin = 0.; // the right bin: [range[nbins], inf)
    protected double weight = 0.; // total weight

    public Histogram (int nbins) {
        this.range = new double[nbins+1];
        this.bin = new double[nbins];
    }

    public Histogram (int nbins, double min, double max) {
        this.range = new double[nbins+1];
        this.bin = new double[nbins];
        setRangeUniform (min, max);
    }

    public void setRangeUniform (double min, double max) {
        if (min < max) {
            double d = (max - min)/bin.length;
            range[0] = min;
            //System.out.printf("%1$3d:%2$.5f\n", 0, min);
            for (int i = 1; i <= bin.length; ++i) {
                range[i] = range[i-1] + d;
                //System.out.printf("%1$3d:%2$.5f\n", i, range[i]);
            }
        }
        else {
            range = new double[1];
            bin = new double[1];
            range[0] = min;
        }
    }

    public void setRanges (double[] range) {
        for (int i = 0; i < range.length-1; ++i) {
            // ensure that the range is monotonically increasing...
            if (range[i+1] < range[i]) {
                throw new IllegalArgumentException ("Bad range specified");
            }
        }

        if (range.length != bin.length+1) {
            bin = new double[range.length-1];
        }
        clear ();
    }

    public void clear () {
        // reset the bins...
        for (int i = 0; i < bin.length; ++i) {
            bin[i] = 0;
        }
        negInfBin = 0.;
        posInfBin = 0.;
        weight = 0.;
    }

    public double[] getRanges () { return range; }
    public double[] getBins () { return bin; }
    public double getMin () { return range[0]; }
    public double getMax () { return range[range.length-1]; }

    public void increment (double x) {
        increment (x, 1.);
    }

    public void increment (double x, double w) {
        if (x < range[0]) {
            negInfBin += w;
        }
        else if (x >= range[range.length-1]) {
            posInfBin += w;
        }
        else {
            int index = Arrays.binarySearch(range, x);
            if (index < 0) {
                index = -index - 2;
            }
            bin[index] += w;
        }
        weight += w;
    }

    // calculate scaled shanon entropy
    public double sse () {
        double sse = 0.;
        if (weight > 0.) {
            for (int i = 0; i < bin.length; ++i) {
                if (bin[i] != 0.) {
                    double p = bin[i]/weight;
                    sse += p*Math.log(p)/LN2;
                }
            }
            sse /= -Math.log(bin.length)/LN2;
        }
        return sse;
    }

    public double getWeight () { return weight; }

    public int find (double x) {
        int index;
        if (x < range[0]) {
            index = -1;
        }
        else if (x >= range[range.length-1]) {
            index = bin.length;
        }
        else {
            index = Arrays.binarySearch(range, x);
            if (index < 0) {
                index = -index - 2;
            }
        }
        return index;
    }

    public int size () { return bin.length; }
    public double getNegInfBin () { return negInfBin; }
    public double getPosInfBin () { return posInfBin; }
    public double get (int n) { 
        if (n < 0) return negInfBin;
        if (n >= bin.length) return posInfBin;
        return bin[n]; 
    }
    public double eval (double x) {
        return get (find (x));
    }

    public String toString () { 
        StringBuffer sb = new StringBuffer();
        sb.append(String.format("(-inf, %1$.1f) => %2$.1f\n", range[0], 
                                negInfBin));
        for (int i = 0; i < bin.length; ++i) {
            sb.append(String.format("[%1$.1f,%2$.1f) => %3$.1f\n", 
                                    range[i], range[i+1], bin[i]));
        }
        sb.append(String.format("[%1$.1f, +inf) => %2$.1f\n", 
                                range[range.length-1], posInfBin));

        return sb.toString();
    }

    private void writeObject (ObjectOutputStream out) throws IOException {
        out.writeObject(range);
        out.writeObject(bin);
        out.writeDouble(negInfBin);
        out.writeDouble(posInfBin);
    }

    private void readObject (ObjectInputStream in) 
        throws IOException, ClassNotFoundException {
        range = (double[])in.readObject();
        bin = (double[])in.readObject();
        negInfBin = in.readDouble();
        posInfBin = in.readDouble();
    }

    public static void main (String[] argv) throws Exception {
        Histogram h = new Histogram (10, 0., 1.);
        h.increment(-1.);
        h.increment(0.);
        h.increment(.09);
        h.increment(0.1);
        h.increment(.9);
        h.increment(1.);
        h.increment(1.01);
        System.out.println(h);
    }
}

