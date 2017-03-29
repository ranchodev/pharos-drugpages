package tripod.util;

import java.io.InputStream;
import java.io.FileInputStream;

import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;

import chemaxon.formats.MolImporter;
import chemaxon.struc.MolAtom;
import chemaxon.struc.MolBond;
import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;
import chemaxon.sss.search.MolSearch;

import lychi.util.ChemUtil;

/*
 * A specialized version of all pair-wise shortest path fingerprint
 */
public class MolShortestPath implements SparseDescriptor {
    static final int MAX_DEPTH = 16;

    private static final Logger logger = 
        Logger.getLogger(MolShortestPath.class.getName());

    public interface AtomAnnotator {
        public String getLabel (MolAtom atom);
    }

    public class DefaultAtomAnnotator implements AtomAnnotator {
        public String getLabel (MolAtom atom) {
            return atom.getSymbol() + (atom.hasAromaticBond() ? "ar" 
                                       : "sp"+atom.getHybridizationState());
        }
    }

    protected Map<String, Integer> sparseVector = null;
    private Molecule mol;
    private int[][] btab;
    private int[][] dist;
    private MolAtom[] atoms;
    private String[] labels;
    private int[][][][] paths;
    private int maxDist = -1;

    private AtomAnnotator annotator = new DefaultAtomAnnotator ();
    
    public MolShortestPath () {
    }

    public MolShortestPath (AtomAnnotator annotator) {
        setAnnotator (annotator);
    }

    public MolShortestPath (Molecule mol) {
        setMolecule (mol);
    }

    public MolShortestPath (AtomAnnotator annotator, Molecule mol) {
        setAnnotator (annotator);
        setMolecule (mol);
    }


    public void setAnnotator (AtomAnnotator annotator) {
        if (annotator == null) {
            annotator = new DefaultAtomAnnotator ();
        }

        this.annotator = annotator;
        if (mol != null) {
            // calculate new sparse vector
            labels = new String[atoms.length];
            for (int i = 0; i < atoms.length; ++i) {
                labels[i] = annotator.getLabel(atoms[i]);
            }
            calcSparseVector ();
        }
    }
    public AtomAnnotator getAnnotator () { return annotator; }

    public int getMaxDist () { return maxDist; }
    public void setMaxDist (int maxDist) {
        this.maxDist = maxDist;
        if (sparseVector != null) {
            calcSparseVector ();
        }
    }

    public void setMolecule (Molecule mol) {
        this.mol = mol;
        this.btab = mol.getBtab();

        this.atoms = mol.getAtomArray();
        int acount = mol.getAtomCount();

        this.labels = new String[acount];
        if (annotator != null) {
            for (int i = 0; i < acount; ++i) {
                labels[i] = annotator.getLabel(atoms[i]);
            }
        }

        // init the path cost...
        dist = new int[acount][acount];
        for (int i = 0; i < acount; ++i) {
            dist[i][i] = 0;
            for (int j = i+1; j < acount; ++j) {
                dist[i][j] = dist[j][i] = btab[i][j] < 0 ? acount : 1;
            }
        }

        // now perform floyd-warshall's all pairs shortest path
        for (int k = 0; k < acount; ++k) 
            for (int i = 0; i < acount; ++i) 
                for (int j = 0; j < acount; ++j)
                    dist[i][j] = Math.min(dist[i][j],dist[i][k] + dist[k][j]);

        calcSparseVector ();
    }

    protected void calcSparseVector () {
        //sparseVector = new TreeMap<String, Integer>();
        sparseVector = new HashMap<String, Integer>();

        paths = new int[atoms.length][atoms.length][][];
        for (int i = 0; i < atoms.length; ++i) 
            for (int j = i+1; j < atoms.length; ++j) {
                if (maxDist < 0 || dist[i][j] <= maxDist) {
                    paths[i][j] = paths[j][i] = generatePaths (i, j);
                }
            }
    }

    protected void dfs (List<Integer[]> paths, Stack<Integer> path, 
                        int start, int a, int end, int depth) {

        path.push(a);

        if (a == end) {
            // process this path...
            paths.add(path.toArray(new Integer[0]));
        }
        else if (depth + 1 <= MAX_DEPTH) {
            int nb = mol.getNeighborCount(a);
            for (int b = 0; b < nb; ++b) {
                int xa = mol.getNeighbor(a, b);
                
                if ((dist[start][xa] + dist[xa][end] <= dist[start][end])
                    && path.indexOf(xa) < 0) {
                    dfs (paths, path, start, xa, end, depth+1);
                }
            }
        }
        else {
            //logger.warning("** Max recursion depth ("+depth+") reached!");
        }

        path.pop();
    }

    String toString (Integer[] p) {
        StringBuilder svec = new StringBuilder ();

        int dir = labels[p[0]].compareTo(labels[p[p.length-1]]);
        for (int i = 0, j = p.length-1; i < j && dir == 0;) {
            dir = labels[p[++i]].compareTo(labels[p[--j]]);
        }

        if (dir > 0) {
            // reverse p
            for (int i = 0, j = p.length-1; i < j; ++i, --j) {
                Integer t = p[i];
                p[i] = p[j];
                p[j] = t;
            }
        }
        
        for (int i = 0; i < p.length; ++i) {
            /*
            int atno = atoms[p[i]].getAtno();
            switch (atno) {
            case MolAtom.RGROUP:
            case MolAtom.ANY:
            case MolAtom.LIST:
            case MolAtom.NOTLIST:
            case MolAtom.HETERO:
                return null;
            }
            */
            MolAtom a = atoms[p[i]];
            if (a.isQuery() || a.isPseudo()) {
                // don't do this path, since it contains query atom
                return null;
            }

            svec.append(labels[p[i]]);
        }

        return svec.toString();
    }

    protected int[][] generatePaths (int start, int end) {
        ArrayList<Integer[]> paths = new ArrayList<Integer[]>();
        dfs (paths, new Stack<Integer>(), start, start, end, 0);

        if (paths.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder ();
        {
            String[] vec = new String[paths.size()];
            for (int i = 0; i < vec.length; ++i) {
                String p = toString (paths.get(i));
                vec[i] = p == null ? "" : p;
            }
            Arrays.sort(vec);
            for (String s : vec) {
                if (s != null) {
                    if (sb.length() > 0) {
                        sb.append('.');
                    }
                    sb.append(s);
                }
            }
        }

        String sv = sb.toString();
        Integer c = sparseVector.get(sv);
        sparseVector.put(sv, c != null ? c+1 : 1);

        int[][] gp = new int[paths.size()][];
        for (int i = 0; i < gp.length; ++i) {
            Integer[] p = paths.get(i);
            gp[i] = new int[p.length];
            for (int j = 0; j < p.length; ++j) {
                gp[i][j] = p[j];
            }
        }

        return gp;
    }

    public int size () { 
        return sparseVector != null ? sparseVector.size(): 0;
    }
    public Map<String, Integer> getSparseVector () { return sparseVector; }
    public Molecule getMolecule () { return mol; }
    public int[][] getPaths (int i, int j) { return paths[i][j]; }
    public int getDist (int i, int j) { return dist[i][j]; }

    /*
     * Generate a fingerprint where numInts is the length of the 
     *  fingerprint in 32-bit units.  That is, if an 1024-bit fingerprint
     *  is desired, then numInts is 32.
     */
    public int[] generateFingerprint (int numInts) {
        return generateFingerprint (new int[numInts], 0, numInts);
    }

    public int[] generateFingerprint (int[] fp, int offset, int length) {
        for (int i = 0; i < length; ++i) {
            fp[offset+i] = 0;
        }

        Map<String, Integer> sv = getSparseVector ();
        int nbits = length * 32;

        Random rand = new Random ();
        for (String s : sv.keySet()) {
            long hash = (long)s.hashCode() & 0xffffffffl;
            int bit =  (int)(hash % nbits); 
            //System.out.println(s + " => " + bit);
            fp[offset+bit/32] |= 1 << (31 - bit%32);
            /*
            if (s.indexOf('.') > 0) {
                // multiple path, then turn on additional bit
                rand.setSeed(hash);
                bit = rand.nextInt(nbits);
                fp[offset+bit/32] |= 1 << (31 - bit%32);
            }
            */
            int nb = (int)Math.log(s.length()) - 1;
            if (nb > 0) {
                rand.setSeed(hash);
                for (int i = 0; i < nb; ++i) {
                    bit = rand.nextInt(nbits);
                    fp[offset+bit/32] |= 1 << (31 - bit%32);
                }
            }
        }
        return fp;
    }


    static void doTiming (InputStream is) throws Exception {
        MolImporter mi = new MolImporter (is);

        MolHandler mh = new MolHandler ();

        MolShortestPath msp = new MolShortestPath ();
        int maxDist = Integer.getInteger("maxDist", -1);
        System.out.println("MaxDist: "+maxDist);
        msp.setMaxDist(maxDist);
        int size = Integer.getInteger("size", 16);
        System.out.println("FpSize: " +size);

        int count = 0;
        long start = System.currentTimeMillis();
        for (Molecule mol = new Molecule (); mi.read(mol); ++count) {
            mol.aromatize();
            mol.calcHybridization();

            msp.setMolecule(mol);
            Map<String, Integer> sv = msp.getSparseVector();
            int[] fp = msp.generateFingerprint(16);

            /*
            mh.setMolecule(mol);
            int[] fp = mh.generateFingerprintInInts(16, 2, 6);
            */

            //System.out.println(mol.getName() + " " + sv);
            //System.exit(1);
        }
        double time = (System.currentTimeMillis() - start)*1e-3;
        System.out.println("Total time to generate fp: "
                           +String.format("%1$.3fs", time));
        System.out.println("Average time per structure: "+
                           String.format("%1$.0fms", time*1e3/count));
    }

    static void doQuery (InputStream is) throws Exception {
        MolShortestPath mp = new MolShortestPath ();
        MolImporter mi = new MolImporter (is);

        String q = System.getProperty("query", "c1ccccc1");
        int maxDist = Integer.getInteger("maxDist", -1);
        System.out.println("Query: " + q);
        System.out.println("MaxDist: "+maxDist);
        mp.setMaxDist(maxDist);

        Molecule query = new MolHandler (q).getMolecule();
        query.aromatize();
        query.calcHybridization();

        int fpsize = 16;

        mp.setMolecule(query);
        int[] qFp1 = mp.generateFingerprint(fpsize);

        MolHandler mh = new MolHandler (query);
        int[] qFp2 = mh.generateFingerprintInInts(fpsize, 2, 6);

        MolSearch ms = new MolSearch ();
        ms.setQuery(query);
        { int n1 = 0, n2 = 0;
            for (int i = 0; i < fpsize; ++i) {
                n1 += Integer.bitCount(qFp1[i]);
                n2 += Integer.bitCount(qFp2[i]);
            }
            System.out.println("Query bit count: "+n1 + " " +n2);
            System.out.println("Query paths");
            for (Map.Entry<String, Integer> e : 
                     mp.getSparseVector().entrySet()) {
                System.out.println(e.getKey() + " " + e.getValue());
            }
        }

        int[] fp1 = new int[fpsize];
        double[] avgBits1 = new double[fpsize];
        double[] avgBits2 = new double[fpsize]; 
        double avg1 = 0., avg2 = 0.;
        int total = 0, nmatches = 0;
        int fpc1 = 0, fpc2 = 0; // false positive count
        for (Molecule mol = new Molecule (); mi.read(mol); ) {
            mol.calcHybridization();
            mol.aromatize();

            ms.setTarget(mol);
            boolean matched = ms.isMatching();

            mp.setMolecule(mol);
            mp.generateFingerprint(fp1, 0, fp1.length);

            mh.setMolecule(mol);
            int[] fp2 = mh.generateFingerprintInInts(fpsize, 2, 6);

            int c1 = 0, c2 = 0;
            for (int i = 0; i < fpsize; ++i) {
                if ((fp1[i] & qFp1[i]) == qFp1[i]) {
                    ++c1;
                }
                if ((fp2[i] & qFp2[i]) == qFp2[i]) {
                    ++c2;
                }

                int a = Integer.bitCount(fp1[i]);
                int b = Integer.bitCount(fp2[i]);
                avgBits1[i] += a;
                avgBits2[i] += b;
                avg1 += a;
                avg2 += b;
            }

            if (c1 == fp1.length) {
                if (!matched /*&& c2 != fp2.length*/) {
                    /*
                    System.err.println
                        ("** false positive found for " 
                         + mol.toFormat("smiles:q") + " " +mol.getName());
                    Map<String, Integer> target = mp.getSparseVector();
                    mp.setMolecule(query);
                    Map<String, Integer> qq = mp.getSparseVector();
                    target.keySet().retainAll(qq.keySet());
                    System.err.println("overlap paths");
                    for (Map.Entry<String, Integer> e : target.entrySet()) {
                        System.err.println("  "+e.getKey() + " " + e.getValue());
                    }
                    */
                    
                    ++fpc1;
                }
            }
            else if (matched) { // false negative
                System.err.println
                    ("** fatal error: false negative found for " 
                     + mol.toFormat("smiles:q") + " " +mol.getName());
                Map<String, Integer> target = mp.getSparseVector();
                for (Map.Entry<String, Integer> e : target.entrySet()) {
                    System.err.println("  "+e.getKey() + " " + e.getValue());
                }
                
                mp.setMolecule(query);
                Map<String, Integer> qq = mp.getSparseVector();
                qq.keySet().removeAll(target.keySet());
                System.err.println("missing paths");
                for (Map.Entry<String, Integer> e : qq.entrySet()) {
                    System.err.println("  "+e.getKey() + " " + e.getValue());
                }
                System.exit(1);
            }

            if (c2 == fp2.length) {
                if (!matched) ++fpc2;
            }

            if (matched) {
                ++nmatches;
            }
            ++total;
        }
        System.out.println("Average bits per bin for "+total);
        for (int i = 0; i < fp1.length; ++i) {
            System.out.printf("%1$2d: %2$.3f %3$.3f\n", 
                              i+1, avgBits1[i]/total, avgBits2[i]/total);
        }
        System.out.println("Average bit per molecule: " 
                           + String.format("%1$.3f", avg1/total)
                           + " " + String.format("%1$.3f", avg2/total));
        System.out.println("False positive rate: "
                           +String.format("%1$.3f", (double)fpc1/total)
                           +"/" +fpc1 + " " 
                           + String.format("%1$.3f", (double)fpc2/total)
                           + "/" + fpc2);
        System.out.println("Num matches: "+nmatches+"/"+total);
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            logger.info("Reading from STDIN...");
            doQuery (System.in);
        }
        else {
            for (String file : argv) {
                FileInputStream fis = new FileInputStream (file);
                System.out.println("## "+file);
                //doTiming (fis);
                doQuery (fis);
                fis.close();
            }
        }       
    }
}
