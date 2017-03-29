package tripod.chem;

import java.io.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;


import chemaxon.struc.MolAtom;
import chemaxon.struc.MolBond;
import chemaxon.struc.Molecule;
import chemaxon.struc.RxnMolecule;
import chemaxon.formats.MolImporter;
import chemaxon.sss.screen.HashCode;
import chemaxon.util.MolHandler;

import lychi.SMIRKS;
import lychi.LyChIStandardizer;
import lychi.util.UnionFind;
import lychi.util.GrayCode;
import lychi.util.ChemUtil;

import tripod.util.MolShortestPath;

public class MolecularFramework {
    static private boolean debug = false;
    static {
        try {
            debug = Boolean.getBoolean("tripod.framework.debug");
        }
        catch (Exception ex) {
        }
    }

    static private Logger logger = Logger.getLogger
        (MolecularFramework.class.getName());

    public static enum FragmentType {
        FRAG_MURCKO,
            FRAG_CARBON,
            FRAG_RECAP
            }

    static final String[] RECAP_REACTIONS = {
        "[O:3]=[C!$(C([#7])(=O)[!#1!#6]):2]-[#7!$([#7][!#1!#6]):1]>>[O:3]=[C:2].[#7:1]", // amide
        "[#6!$([#6](O)~[!#1!#6])][O:2][C:1]=O>>[C:1]=O.[#6][O:2]", // ester
        "[#6:2]-[N!$(N[#6]=[!#6])!$(N~[!#1!#6])!X4:1]>>[N:1].[#6:2]", // amine
        "N[C:1]([N:2])=O>>N[C:1]=O.[N:2]", // urea
        "[#6]-[O!$(O[#6]~[!#1!#6]):1]-[#6:2]>>[#6:2].[O:1]-[#6]", // ether
        "[C:1]=[C:1]>>[C:1].[C:1]", // olefin
        "[#6:1]-[N$(N([#6])([#6])([#6])[#6])!$(NC=[!#6]):2]>>[#6:1].[N:2]", // quatN
        "[n:1]-[#6!$([#6]=[!#6]):2]>>[n:1].[#6:2]", // aromN-carbon
        "[C:3](=[O:4])@-[N:1]!@-[#6!$([#6]=[!#6]):2]>>[C:3](=[O:4])[N:1].[#6:2]", // lactamN-carbon
        "[c:1]-[c:1]>>[c:1].[c:1]", // aromc-aromc
        "[#7:1][S:2](=O)=O>>[#7:1].[S:2](=O)=O" // sulphonamide
    };

    static final String[] FIX_CHARGES = {
        "[*H4+]>>*",
        "[*H3+]>>*",
        "[*H2+]>>*",
        "[*H+]>>*",
    };

    static final String[] RECAP_NOTLIST = {
        "CCCC", // butyl
        "CC(C)C", // ibutyl
        "C(C)(C)C", // tbutyl
        "C1=CC=CC=C1" // benzol
    };

    private static final ThreadLocal<SMIRKS[]> NEUTRALIZER =
        new ThreadLocal<SMIRKS[]> () {
        @Override
        protected SMIRKS[] initialValue () {
            try {
                SMIRKS[] neutralizer = new SMIRKS[FIX_CHARGES.length];
                for (int i = 0; i < FIX_CHARGES.length; ++i) {
                    neutralizer[i] = new SMIRKS (FIX_CHARGES[i]);
                }
                return neutralizer;
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE, 
                           "Can't create neutralized transforms", ex);
            }
            return null;
        }
    };


    private Molecule mol;
    private FragmentType type;
    private volatile int[] fingerprint; // fingerprint of the input molecule

    private HashCode hash = new HashCode ();
    private boolean generateAtomMapping = false;
    private boolean keepFusedRings = false; // should fused rings be broken?
    private Set<String> notList = new HashSet<String>();

    // parameters relevant to FRAG_MURCKO & FRAG_CABRON
    private int maxNumRings = 10;
    private int minFragSize = 3; // minimum fragment size
    private boolean doLinker = false;
    private boolean keepStereo = true;

    // output fragments
    private volatile ConcurrentMap<String, Molecule> fragments = 
        new ConcurrentHashMap<String, Molecule>();
    // output linkers; only valid when type = FRAG_MURCKO
    private volatile ConcurrentMap<String, Molecule> linkers = 
        new ConcurrentHashMap<String, Molecule>();

    // number of threads to use
    private int nthreads = 1;
    private final ReentrantLock lock = new ReentrantLock ();

    
    public MolecularFramework () {
        this (FragmentType.FRAG_MURCKO);
    }

    public MolecularFramework (FragmentType type) {
        switch (type) {
        case FRAG_RECAP:
            throw new IllegalArgumentException
                ("Recap fragments not supported!");
            
        case FRAG_MURCKO:
            try {
                initMurcko ();
            }
            catch (Exception ex) {
                throw new RuntimeException (ex);
            }
            break;
            
        case FRAG_CARBON:
            try {
                initCarbon ();
            }
            catch (Exception ex) {
                throw new RuntimeException (ex);
            }
            break;
        }
        this.type = type;       
    }

    protected void initRecap () throws Exception {
        RxnMolecule[] recapRxn = 
            new RxnMolecule[RECAP_REACTIONS.length];

        MolHandler mh = new MolHandler ();
        for (int i = 0; i < RECAP_REACTIONS.length; ++i) {
            mh.setMolecule(RECAP_REACTIONS[i]);
            recapRxn[i] = (RxnMolecule)mh.getMolecule();
        }
                
        Molecule[] notlist = new Molecule[RECAP_NOTLIST.length];
        for (int i = 0; i < RECAP_NOTLIST.length; ++i) {
            mh.setMolecule(RECAP_NOTLIST[i]);
            notlist[i] = mh.getMolecule();
        }
    }

    protected void initMurcko () {
        setAllowBenzene (true);
    }

    protected void initCarbon () {
    }

    public void generateLinkers (boolean doLinker) {
        this.doLinker = doLinker;
    }
    public boolean generateLinkers () { return doLinker; }

    public void setKeepFusedRings (boolean keep) { keepFusedRings = keep; }
    public boolean getKeepFusedRings () { return keepFusedRings; }

    public void setKeepStereo (boolean keep) { keepStereo = keep; }
    public boolean getKeepStereo () { return keepStereo; }

    public void setMolecule (Molecule mol) {
        setMolecule (mol, false);
    }

    public void setNumThreads (int nthreads) { this.nthreads = nthreads; }
    public int getNumThreads () { return nthreads; }

    public void setMolecule (String mol) {
        setMolecule (mol, false);
    }
    
    public void setMolecule (String mol, boolean standardize) {
        try {
            MolHandler mh = new MolHandler (mol);
            setMolecule (mh.getMolecule(), standardize);
        }
        catch (Exception ex) {
            throw new IllegalArgumentException ("Invalid mol format: "+mol, ex);
        }
    }
    
    public void setMolecule (Molecule mol, boolean standardize) {
        mol.hydrogenize(false);
        mol.expandSgroups();

        MolAtom[] atoms = mol.getAtomArray();

        int[] maps = new int[atoms.length];
        for (int i = 0; i < atoms.length; ++i) {
            maps[i] = atoms[i].getAtomMap();
            atoms[i].setAtomMap(i+1); // reset the mapping
        }

        this.mol =  mol.cloneMolecule();
        if (standardize) {
            try {
                LyChIStandardizer standardizer = new LyChIStandardizer ();
                if (!standardizer.standardize(this.mol)) {
                    /*
                      logger.log(Level.WARNING, "Can't standardize " 
                      + mol.getName() 
                      + "; no fragments generated!");
                      // fails standardization, so don't bother...
                      this.mol = null;
                        */
                }
            }
            catch (Exception ex) { 
                throw new IllegalArgumentException (ex);
            }
        }

        this.mol.aromatize();
        if (debug) {
            logger.info(mol.getName() +"\t"+mol.toFormat("smiles:q")
                        +"\t"+this.mol.toFormat("smiles:q"));
        }

        MolHandler mh = new MolHandler (this.mol);
        this.fingerprint =  mh.generateFingerprintInInts(16, 2, 6);

        for (int i = 0; i < atoms.length; ++i) {
            atoms[i].setAtomMap(maps[i]); // restore the mapping
        }

        fragments.clear();
        linkers.clear();
    }

    static int[] generateFingerprint (Molecule mol) {
        MolHandler mh = new MolHandler (mol.cloneMolecule());
        mh.aromatize();
        return mh.generateFingerprintInInts(16, 2, 6);
    }

    public void addNotList (String fragment) {
        notList.add(fragment);
    }
    public void clearNotList () { 
        notList.clear();
    }

    public void setAllowBenzene (boolean allowed) {
        if (allowed) {
            notList.remove("c1ccccc1");
            notList.remove("C1=CC=CC=C1");
        }
        else {
            notList.add("c1ccccc1"); // ignore benzene... 
            notList.add("C1=CC=CC=C1");
        }
    }

    public Molecule getMolecule () { return mol; }
    public int getHashCode () { return hash.getHashCode(mol); }

    /*
     * Note that the atom mapping is always with respect to
     * the reference molecule as returned by getMolecule() above
     * and not the input to setMolecule()!
     */
    public void setGenerateAtomMapping (boolean mapping) {
        this.generateAtomMapping = mapping;
    }
    public boolean getGenerateAtomMapping () { 
        return generateAtomMapping; 
    }

    public FragmentType getFragmentType () { return type; }
    public void setMaxNumRings (int max) {
        maxNumRings = max;
    }
    public int getMaxNumRings () { return maxNumRings; }
    public void setMinFragmentSize (int size) { minFragSize = size; }
    public int getMinFragmentSize () { return minFragSize; }
    public int getFragmentCount () { return fragments.size(); }
    public Enumeration<Molecule> getFragments () { 
        return Collections.enumeration(fragments.values());
    }
    public Enumeration<String> getFragmentAsSmiles () {
        return Collections.enumeration(fragments.keySet());
    }
    public Enumeration<Molecule> getLinkers () {
        return Collections.enumeration(linkers.values());
    }
    public Enumeration<String> getLinkerAsSmiles () {
        return Collections.enumeration(linkers.keySet());
    }
    public int getLinkerCount () { return linkers.size(); }
    
    public void run () {
        if (mol == null || mol.getAtomCount() < 3) {
            return;
        }

        switch (type) {
        case FRAG_CARBON:
            for (MolBond b : mol.getBondArray()) {
                //b.setFlags(MolBond.TYPE_MASK, 1);
                b.setType(1);
                b.setFlags(MolBond.STEREO_MASK, 0);
                MolAtom a1 = b.getAtom1(), a2 = b.getAtom2();
                a1.setCharge(0);
                a2.setCharge(0);
                a1.setAtno(6);
                a2.setAtno(6);
            }
            // fall-thru
            
        case FRAG_MURCKO:
            generateFrameworkFragments (mol);
            break;
            
        default:
            throw new IllegalArgumentException
                ("Unknown fragment type: " + type + " specified!");
        }
    }

    protected void generateFrameworkFragments (Molecule mol) {
        int[][] sssr = mol.getSSSR();

        int[][] rings = keepFusedRings 
            ? ChemUtil.getRingSystems(sssr) : sssr;

        if (debug) {
            logger.info("## Ring sets");
            for (int j = 0; j < rings.length; ++j) {
                int[] r = rings[j];
                System.out.print(j+":");
                for (int i = 0; i < r.length; ++i) {
                    System.out.print(" " + (r[i]+1));
                }
                System.out.println();
            }
        }

        if (rings.length > maxNumRings) {
            logger.log(Level.WARNING,"Number of rings ("+rings.length
                       +") > max value (" + maxNumRings + "); "
                       + "truncating ring enumeration...");

            // generate a fragment that contains all the rings
            List<int[]> allrings = new ArrayList<int[]>();
            for (int i = 0; i < rings.length; ++i) {
                allrings.add(rings[i]);
            }
            Molecule f = generateSubgraph (mol, allrings);
            if (f != null && f.getAtomCount() >= minFragSize) {
                addFragment (f);
            }
        }

        MolShortestPath msp = new MolShortestPath (mol);
        mol.setPropertyObject("MSP", msp);
        if (debug || nthreads < 2) {
            generateFragments (mol, rings);
        }
        else {
            generateFragmentsThreaded (mol, rings);
        }
        mol.setPropertyObject("MSP", null);

        if (type == FragmentType.FRAG_MURCKO && doLinker) {
            generateLinkers (mol);
        }
    }

    protected void generateFragments (final Molecule mol, final int[][] rings) {
        GrayCode g = GrayCode.createBinaryGrayCode
            (Math.min(rings.length, maxNumRings));

        g.addObserver(new Observer () {
                public void update (Observable obs, Object arg) {
                    int[] g = (int[])arg;

                    final List<int[]> comps = new ArrayList<int[]>();
                    BitSet set = new BitSet ();
                    for (int i = 0; i < g.length; ++i) {
                        if (g[i] != 0) {
                            comps.add(rings[i]);
                            set.set(i);
                        }
                    }

                    if (!comps.isEmpty()) {
                        if (debug) {
                            logger.info("Ring set "+set);
                        }

                        generateFragment (mol, comps);
                    }
                }
            });
        g.generate();
    }

    protected void generateFragmentsThreaded (final Molecule mol, 
                                              final int[][] rings) {
        GrayCode g = GrayCode.createBinaryGrayCode
            (Math.min(rings.length, maxNumRings));

        final ExecutorService threadPool = 
            Executors.newFixedThreadPool(nthreads);
        final List<Future> tasks = new ArrayList<Future>();

        g.addObserver(new Observer () {
                public void update (Observable obs, Object arg) {
                    int[] g = (int[])arg;

                    final List<int[]> comps = new ArrayList<int[]>();
                    BitSet set = new BitSet ();
                    for (int i = 0; i < g.length; ++i) {
                        if (g[i] != 0) {
                            comps.add(rings[i]);
                            set.set(i);
                        }
                    }

                    if (!comps.isEmpty()) {
                        if (debug) {
                            logger.info("Ring set "+set);
                        }

                        Future f = threadPool.submit
                            (new Runnable () {
                                    public void run () {
                                        generateFragment (mol, comps);
                                    }
                                });
                        tasks.add(f);
                    }
                }
            });
        g.generate();

        // wait for threads to finish
        for (Future f : tasks) {
            try {
                if (!f.isDone()) {
                    f.get();
                }
            }
            catch (Exception ex) {
                logger.log(Level.SEVERE, "Thread exception", ex);
            }
        }

        try {
            threadPool.shutdown();
        }
        catch (SecurityException ex) { // ignore if we can't shutdown
        }
    }

    protected void generateFragment (Molecule mol, List<int[]> comps) {
        if (debug) {
            System.err.print("** generating fragment for");
            for (int[] r : comps) {
                System.err.print(" {"+(r[0]+1));
                for (int i = 1; i < r.length; ++i) {
                    System.err.print(" "+(r[i]+1));
                }
                System.err.print("}");
            }
            System.err.println();
        }

        Molecule f = generateSubgraph (mol, comps);

        if (f != null && f.getAtomCount() >= minFragSize) {
            f = addFragment (f);
            if (f != null) {
                addNonTerminalFragment (f);
            }
        }
        
        if (debug) {
            System.err.print("**");
            for (int[] r : comps) {
                System.err.print(" {"+(r[0]+1));
                for (int i = 1; i < r.length; ++i) {
                    System.err.print(" "+(r[i]+1));
                }
                System.err.print("}");
            }
            System.err.print(" => ");
            if (f != null) {
                System.err.print
                    (ChemUtil.canonicalSMILES(f, false) 
                     + " " + f.toFormat("smiles:q"));
            }
            else {
                System.err.print("None");
            }
            System.err.println();
        }
    }

    protected Molecule addFragment (Molecule f) {
        f.aromatize();
        String frag = ChemUtil.canonicalSMILES(f, false);

        if (!notList.contains(frag)) {
            /*
             * if generateAtomMapping flag is set, we 
             * also consider the same fragment with 
             * different atom mapping
             */
            if (generateAtomMapping) {
                frag = f.toFormat("smiles:q"+(keepStereo?"":"0"));
            }
            try {
                MolHandler mh = new MolHandler (frag);
                // this ensure the Molecule instance is in the same order
                //  as its smiles
                fragments.put(frag, f = mh.getMolecule());
            }
            catch (Exception ex) {
                logger.warning("Can't parse fragment: "+frag);
                fragments.put(frag, f);
            }
        }

        f.dearomatize();
        for (MolBond b : f.getBondArray()) {
            if (b.getType() == MolBond.AROMATIC) {
                fragments.remove(frag);
                return null;
            }
        }
        String[] hk = LyChIStandardizer.hashKeyArray(f);
        f.setName(hk[hk.length-1]);

        return f;
    }

    protected void addNonTerminalFragment (Molecule f) {
        // now see if f contains terminal =X... we keep a version
        //  with all =X removed
        BitSet terminal = new BitSet (f.getAtomCount());
        for (int i = 0; i < f.getAtomCount(); ++i) {
            MolAtom a = f.getAtom(i);
            if (a.isTerminalAtom() && a.getBondCount() > 0
                && a.getBond(0).getOtherAtom(a).getAtno() != 16) {
                // only consider terminal atoms that aren't connected
                //  to S, since deleting such atoms will result in bogus
                //  valence
                terminal.set(i);
            }
        }
        
        if (terminal.isEmpty()) {
            return;
        }

        Molecule c = f.cloneMolecule();
        c.aromatize();

        List<MolAtom> remove = new ArrayList<MolAtom>();
        for (int i = terminal.nextSetBit(0); 
             i >= 0; i = terminal.nextSetBit(i+1)) {
            remove.add(c.getAtom(i));
        }
        for (MolAtom a : remove) {
            c.removeNode(a);
        }
        c.dearomatize();
        
        boolean bogus = false;
        for (MolBond b : c.getBondArray()) {
            if (b.getType() == MolBond.AROMATIC) {
                bogus = true;
                break;
            }
        }
        
        if (!bogus) {
            addFragment (c);
        }
    }

    protected BitSet[] getRingMemberships (Molecule mol) {
        return getRingMemberships (mol, mol.getSSSR());
    }

    protected BitSet[] getRingMemberships (Molecule mol, int[][] sssr) {
        BitSet[] rings = new BitSet[mol.getAtomCount()];
        for (int i = 0; i < sssr.length; ++i) {
            for (int j = 0; j < sssr[i].length; ++j) {
                int a = sssr[i][j];
                BitSet bs = rings[a];
                if (bs == null) {
                    rings[a] = bs = new BitSet (sssr.length);
                }
                bs.set(i);
            }
        }
        return rings;
    }

    protected Molecule generateSubgraph (Molecule mol, List<int[]> comps) {

        // generate subgraph corresponds to the given list of components
        Molecule m = mol.cloneMolecule();

        // make note of terminal =X atoms that aren't directly attached
        //  to rings
        int[] rsizes = m.getSmallestRingSizeForIdx();
        MolAtom[] atoms = m.getAtomArray();

        // store mapping of terminal *= attachment 
        Map<MolAtom, Integer> maps = new HashMap<MolAtom, Integer>();

        for (int i = 0; i < atoms.length; ++i) {
            MolAtom a = atoms[i];
            if (a.isTerminalAtom() 
                && a.getAtno() != 6 // just in case...
                && a.getBondCount() > 0 // could be single atom molecule
                && a.getBond(0).getType() == 2) {
                MolAtom xa = a.getBond(0).getOtherAtom(a);
                // save this attachment point
                int pos = m.indexOf(xa);
                if (rsizes[pos] == 0) { // only annotate non-ring 
                    maps.put(xa, pos);
                }
            }
        }

        MolBond[] bonds = m.getBondArray();
        BitSet keepAtoms = new BitSet ();
        for (int[] c : comps) {
            for (int i = 0; i < c.length; ++i) {
                keepAtoms.set(c[i]);
            }
        }

        // We also keep any atom along the shortest path between components
        //  Note that components are now ring systems regardless comps is
        //  made up of sssr or ring systems
        MolShortestPath msp = (MolShortestPath)mol.getPropertyObject("MSP");
        if (msp != null) {
            int[][] rsys = ChemUtil.getRingSystems(comps.toArray(new int[0][]));
            BitSet[] eqv = new BitSet[rsys.length];
            int[] rank = new int[atoms.length];
            
            // now just pick any two atoms between components i & k  and
            //  any atoms along its shortest path are kept
            for (int i = 0; i < rsys.length; ++i) {
                int[] c = rsys[i];
                for (int k = i+1; k < rsys.length; ++k) {
                    int[] r = rsys[k];
                    
                    // find the shortest path between to ring systems
                    Map<int[], Integer> minpaths = 
                        new HashMap<int[], Integer>();

                    int min = Integer.MAX_VALUE; 
                    int minnc = Integer.MAX_VALUE;
                    for (int n = 0; n < c.length; ++n) {
                        for (int p = 0; p < r.length; ++p) {
                            int dist = msp.getDist(c[n], r[p]);
                            if (dist <= min) {
                                int[][] path = msp.getPaths(c[n], r[p]);
                                if (path != null) {
                                    if (dist < min) {
                                        min = dist;
                                        minnc = Integer.MAX_VALUE;
                                        minpaths.clear();
                                    }

                                    for (int[] pp : path) {
                                        int nc = 0;
                                        for (int j = 0; j < pp.length; ++j) {
                                            if (!keepAtoms.get(pp[j]))
                                                ++nc;
                                        }
                                        if (nc < minnc) {
                                            minnc = nc;
                                        }
                                        minpaths.put(pp, nc);
                                    }

                                    // minimize adding new atoms
                                    List<int[]> remove = 
                                        new ArrayList<int[]>();
                                    for (Map.Entry<int[], Integer> me 
                                             : minpaths.entrySet()) {
                                        if (me.getValue() > minnc) {
                                            remove.add(me.getKey());
                                        }
                                    }

                                    for (int[] pp : remove) {
                                        minpaths.remove(pp);
                                    }
                                }
                            }
                        }
                    }
                    
                    if (debug) {
                        logger.info("Min dist = "+min);
                        System.err.print("{"+(c[0]+1));
                        for (int j = 1; j < c.length; ++j) {
                            System.err.print(","+(c[j]+1));
                        }
                        System.err.print("} vs {"+(r[0]+1));
                        for (int j = 1; j < r.length; ++j) {
                            System.err.print(","+(r[j]+1));
                        }
                        System.err.println("}");
                    }
                    
                    if (!minpaths.isEmpty()) {
                        if (debug) {
                            System.err.print(" =>");
                            for (int[] p : minpaths.keySet()) {
                                System.err.print(" {"+p[0]);
                                for (int j = 1; j < p.length; ++j) {
                                    System.err.print(","+(p[j]+1));
                                }
                                System.err.print("}="+minpaths.get(p));
                            }
                            System.err.println();
                        }

                        /*
                        if (minpaths.size() > 1) {
                            StringBuilder mesg = new StringBuilder
                                ("{"+(c[0]+1));
                            for (int j = 1; j < c.length; ++j) {
                                mesg.append(","+(c[j]+1));
                            }
                            mesg.append("} and {"+(r[0]+1));
                            for (int j = 1; j < r.length; ++j) {
                                mesg.append(","+(r[j]+1));
                            }
                            mesg.append("}");

                            logger.warning(minpaths.size()
                                           +" unique shortest paths possible"
                                           +" between "+mesg);
                        }
                        */

                        // more than one shortest path, so just pick one?
                        for (int[] p : minpaths.keySet()) {
                            for (int j = 0; j < p.length; ++j)
                                // only keep track of new atoms
                                ++rank[p[j]];
                            break;
                        }
                    }
                }
                eqv[i] = new BitSet ();
                for (int j = 0; j < c.length; ++j) {
                    eqv[i].set(c[j]);
                }
            }

            // now update keepAtoms with the max rank atom
            if (debug) {
                logger.info("Adding new atoms (if any)...");
                System.err.print("++");
            }
            // keep all atom with rank = max
            for (int i = 0; i < rank.length; ++i)
                if (rank[i] > 0) {
                    keepAtoms.set(i);
                    if (debug) System.err.print(" "+(i+1)+"["+rank[i]+"]");
                }

            if (debug) System.err.println();
        } // msp != null

        if (debug) {
            logger.info("Keeping atoms...");
            System.err.print("++ ");
            for (int i = keepAtoms.nextSetBit(0); 
                 i>=0; i = keepAtoms.nextSetBit(i+1)) {
                System.err.print(" "+(i+1));
            }
            System.err.println();
        }

        if (false) {
            // now remove all rings that are not in comps...
            Set<MolBond> removeBonds = new HashSet<MolBond>();
            
            for (int i = 0; i < bonds.length; ++i) {
                MolBond b = bonds[i];
                if (m.isRingBond(i)) {
                    int a1 = m.indexOf(b.getAtom1());
                    int a2 = m.indexOf(b.getAtom2());
                    if (keepAtoms.get(a1) || keepAtoms.get(a2)) {
                        /*
                          int c1 = -1, c2 = -1;
                          for (int c = 0; c < eqv.length; ++c) {
                          if (c1 < 0 && eqv[c].get(a1)) {
                          c1 = c;
                          }
                          if (c2 < 0 && eqv[c].get(a2)) {
                          c2 = c;
                          }
                          }
                          
                          if (c1 < 0 || c2 < 0) {
                          }
                          else if (c1 != c2) {
                          removeBonds.add(b);
                          }
                        */
                    }
                    else {
                        removeBonds.add(b);
                    }
                }
            }
            
            // remove all bonds
            for (MolBond b : removeBonds) {
                m.removeEdge(b);
            }
        }
        else {
            Set<MolAtom> removeAtoms = new HashSet<MolAtom>();
            for (int i = 0; i < atoms.length; ++i) {
                if (!keepAtoms.get(i) && !atoms[i].isTerminalAtom()) {
                    removeAtoms.add(atoms[i]);
                }
            }
            
            for (MolAtom a : removeAtoms) {
                m.removeNode(a);
            }
        }

        // now erode
        erode (m, false);
        if (debug) {
            logger.info("Eroded: "+m.toFormat("smiles:q"));
        }

        // return the largest fragment...
        Molecule[] frags = m.convertToFrags();
        Molecule best = frags[0];
        for (int i = 1; i < frags.length; ++i) {
            if (frags[i].getAtomCount() > best.getAtomCount()) {
                best = frags[i];
            }
        }
        best.aromatize();
        best.dearomatize();

        // now check if we have bogus aromatic bonds; this can happen
        //  if we remove aromatic rings that are fused to non-aromatic
        //  rings.
        bonds = best.getBondArray();
        for (int i = 0; i < bonds.length; ++i) {
            if (bonds[i].getType() == MolBond.AROMATIC) {
                return null; // bail out early
            }
        }

        /*
                MolBond b = bonds[i];
                if (b.getType() == 2 || b.getType() == MolBond.AROMATIC) {
                    MolAtom a1 = b.getCTAtom1();
                    MolAtom a4 = b.getCTAtom4();
                    if (a1 != null && a4 != null) {
                        b.setStereo2Flags(a1, a4, MolBond.TRANS|MolBond.CIS);
                    }
                }
        */


        if (best != null) {

            // now reattach =X atoms (if any)
            List<MolBond> newBonds = new ArrayList<MolBond>();
            List<MolAtom> newAtoms = new ArrayList<MolAtom>();
            for (MolAtom a : best.getAtomArray()) {
                Integer map = maps.get(a);
                if (map != null) {
                    // there should be a terminal atom attached at 
                    //   this position
                    MolAtom x = mol.getAtom(map);
                    for (int i = 0; i < x.getBondCount(); ++i) {
                        MolBond b = x.getBond(i);
                        MolAtom xa = b.getOtherAtom(x);
                        if (xa.isTerminalAtom()) {
                            // found it...
                            int xm = xa.getAtomMap();
                            xa = new MolAtom (xa.getAtno());
                            xa.setAtomMap(xm);
                            MolBond bnd = new MolBond (xa, a);
                            bnd.setType(b.getType());
                            newAtoms.add(xa);
                            newBonds.add(bnd);
                        }
                    }
                }
            }

            if (!newAtoms.isEmpty()) {
                // first add all new atoms
                for (MolAtom a : newAtoms) {
                    best.add(a);
                }
                // then the bonds
                for (MolBond b : newBonds) {
                    best.add(b);
                }
            }

            best.valenceCheck();
            if (best.hasValenceError()) {
                if (debug) {
                    logger.info("Not a proper substructure "
                                +best.toFormat("smiles:q") +" (bad valence); "
                                +"fragment ignored!");
                }

                // bogus fragment generated
                return null;
            }

            if (debug) {
                logger.info("Fragment "+best.toFormat("smiles:q") 
                            + "\t" + getMolecule().toFormat("smiles:q"));
            }

            if (type == FragmentType.FRAG_MURCKO) {
                try {
                    String smiles = best.toFormat
                        ("smiles:u"+(keepStereo?"":"0"));
                    //MolImporter.importMol(smiles, best);

                    MolHandler mh = new MolHandler (smiles);
                    best = mh.getMolecule();
                    /*
                     * now if this fragment is a valid substructure of the 
                     * parent molecule, then we keep it.  otherwise, we ignore
                     * it!
                     */
                    int[] fp = mh.generateFingerprintInInts(16, 2, 6);

                    for (int i = 0; i < fp.length; ++i) {
                        if ((fp[i] & fingerprint[i]) != fp[i]) {
                            if (debug) {
                                logger.info("Not a proper substructure "
                                            +smiles +" (fp mismatch); "
                                            +"fragment ignored!");
                            }

                            // bail out.. not a proper fragment
                            return null;
                        }
                    }

                    // now the last test in case the fingerprint didn't 
                    //  catch bogus aromatic bonds
                    for (MolBond b : best.getBondArray()) {
                        int a1 = b.getAtom1().getAtomMap();
                        int a2 = b.getAtom2().getAtomMap();
                        if (!checkBondType (a1, a2, b.getType())) {
                            if (debug) {
                                logger.info
                                    ("Not a proper substructure " +smiles 
                                     +" (bond type mismatch "+(a1+1)
                                     +"."+b.getType()+"."+(a2+1)+"); "
                                     +"fragment ignored!");
                            }
                            return null; // bail out
                        }
                    }

                    if (best != null) {
                        best.setName(mol.getName());
                        best.dearomatize();

                        // make sure the fragment is standardized
                        //LyChIStandardizer mstd = new LyChIStandardizer ();
                        //mstd.standardize(best);
                    }
                }
                catch (Exception ex) {
                    logger.log
                        (Level.WARNING, "Failed to clean fragment", ex);
                    best = null;
                }
            }
        }

        return best;
    }

    boolean checkBondType (int a1, int a2, int type) {
        MolAtom atom1 = null, atom2 = null;
        for (MolAtom a : mol.getAtomArray()) {
            int map = a.getAtomMap();
            if (map == a1) {
                atom1 = a;
            }
            if (map == a2) {
                atom2 = a;
            }

            if (atom1 != null && atom2 != null) {
                break;
            }
        }

        if (atom1 == null || atom2 == null) {
            return false;
        }

        for (int i = 0; i < atom1.getBondCount(); ++i) {
            MolBond b = atom1.getBond(i);
            if (atom2 == b.getOtherAtom(atom1)) {
                return b.getType() == type;
            }
        }

        return false;
    }

    protected void generateLinkers (Molecule mol) {
        Molecule m = mol.cloneMolecule();
        MolBond[] bonds = m.getBondArray();

        List<MolBond> ringBonds = new ArrayList<MolBond>();
        int[] rings = m.getSmallestRingSizeForIdx();

        for (int i = 0; i < bonds.length; ++i) {
            MolBond b = bonds[i];
            if (m.isRingBond(i)) {
                ringBonds.add(b);
            }
            else {
                MolAtom a1 = b.getAtom1();
                MolAtom a2 = b.getAtom2();
                int ix1 = m.indexOf(a1), ix2 = m.indexOf(a2);

                if (rings[ix1] > 0 && rings[ix2] > 0) {
                    //ringBonds.add(b);
                    a1.setAtno(MolAtom.ANY);
                    a2.setAtno(MolAtom.ANY);
                }
                else if (rings[ix1] > 0) {
                    a1.setAtno(MolAtom.ANY);
                }
                else if (rings[ix2] > 0) {
                    a2.setAtno(MolAtom.ANY);
                }
            }
        }

        for (MolBond b : ringBonds) {
            m.removeEdge(b);
        }

        for (Molecule f : m.convertToFrags()) {
            int nb = f.getBondCount();
            if (nb > 0) {
                linkers.put(f.toFormat("smarts:q0"), f);
            }
        }
    }

    protected static void erode (Molecule mol, boolean simple) {
        for (MolAtom atom; (atom = getNextTerminalAtom 
                            (mol, simple)) != null; ) {
            mol.removeNode(atom);
        }
        mol.valenceCheck();
    }

    protected static MolAtom getNextTerminalAtom 
        (Molecule m, boolean simple) {
        int na = m.getAtomCount();
        int[] rsizes = m.getSmallestRingSizeForIdx();
        for (int i = 0; i < na; ++i) {
            MolAtom a = m.getAtom(i);
            if (a.isTerminalAtom()) {
                if (simple) {
                    return a;
                }
                else if (a.getBondCount() == 1) {
                    MolBond bond = a.getBond(0);
                    MolAtom xa = bond.getOtherAtom(a);
                    int xi = m.indexOf(xa);
                    if ((a.getAtno() == 8 || a.getAtno() == 7 
                         || a.getAtno() == 16) && 
                        bond.getType() == 2 && rsizes[xi] > 0) {
                        // keep double-bond O,N,S connecting to a 
                        //   ring in tact
                    }
                    else if (xa.getAtno() == 16 && rsizes[xi] > 0) {
                        // keep terminal attachments to S intach to preserve
                        //  valence
                    }
                    /*
                      else if (rsizes[xi] > 0 && xa.getAtno() != 6
                      && a.getAtno() != 6) {
                      // terminal atom connecting to a non-carbon 
                      //  atom in a ring....
                      }
                    */
                    else {
                        return a;
                    }
                }
            }
        }
        return null;
    }

    public static MolecularFramework createMurckoInstance () {
        return new MolecularFramework (FragmentType.FRAG_MURCKO);
    }

    public static MolecularFramework createCarbonInstance () {
        return new MolecularFramework (FragmentType.FRAG_CARBON);
    }

    public static void main (String argv[]) throws Exception {
        if (argv.length < 2) {
            logger.info
                ("Usage: MolecularFramework [murcko|carbon] FILES...");
            System.exit(1);
        }

        MolecularFramework.FragmentType type = 
            MolecularFramework.FragmentType.FRAG_MURCKO;

        String which = argv[0];
        if (which.equalsIgnoreCase("carbon")) {
            type = MolecularFramework.FragmentType.FRAG_CARBON;
        }
        else if (which.equalsIgnoreCase("murcko")) {
            type = MolecularFramework.FragmentType.FRAG_MURCKO;
        }
        else {
            logger.log(Level.SEVERE, "Unknown framework: "+which);
            System.exit(1);
        }

        MolecularFramework mf = new MolecularFramework (type);
        mf.setGenerateAtomMapping(true);
        //mf.setKeepFusedRings(true);
        mf.setAllowBenzene(false);

        PrintStream fragments = new PrintStream
            (new FileOutputStream ("fragments.sdf"));

        Map<String, Molecule> frameworks = new HashMap<String, Molecule> ();

        LyChIStandardizer standardizer = new LyChIStandardizer ();
        MolHandler mh = new MolHandler ();
        for (int i = 1; i < argv.length; ++i) {
            MolImporter molimp = new MolImporter (argv[i]);
            try {
                for (Molecule mol = new Molecule (); molimp.read(mol); ) {
                    String name = mol.getName();
                    if (name == null || name.equals("")) {
                        for (int j = 0; j < mol.getPropertyCount(); ++j) {
                            name = mol.getProperty(mol.getPropertyKey(j));
                            if (name != null && !name.equals("")) {
                                break;
                            }
                        }
                        mol.setName(name);
                    }
                
                    mol.valenceCheck();
                    if (mol.hasValenceError()) {
                        System.err.println
                            ("** warning: " + name + " has valence error");
                    }
                    
                    standardizer.standardize(mol);
                    try {
                        mf.setMolecule(mol, false);
                        mf.run();
                    }
                    catch (Exception ex) {
                        logger.warning("Can't process "
                                       +mol.getName()+": "+ex.getMessage());
                        continue;
                    }

                    mh.setMolecule(mol.cloneMolecule());
                    mh.aromatize();
                    int[] fp0 = mh.generateFingerprintInInts(16, 2, 6);
                    int a = 0;
                    for (int k = 0; k < fp0.length; ++k)
                        a += Integer.bitCount(fp0[k]);

                    /*
                    for (int j = 0; j < mol.getAtomCount(); ++j) {
                        mol.getAtom(j).setAtomMap(j+1);
                    }
                    */
                    /*
                      System.out.println
                      (frag.toFormat("smiles:q")+"\t"+mol.getName());
                    */
                    //System.out.print(mol.toFormat("sdf"));

                    int j =1;
                    StringBuilder sb = new StringBuilder ();
                    for (Enumeration<Molecule> en = mf.getFragments();
                         en.hasMoreElements(); ++j) {
                        Molecule f = en.nextElement();

                        mh.setMolecule(f.cloneMolecule());
                        mh.aromatize();
                        int[] fp1 = mh.generateFingerprintInInts(16, 2, 6);
                        int b = 0, c = 0, d = 0;
                        for (int k = 0; k < fp1.length; ++k) {
                            b += Integer.bitCount(fp1[k]);
                            c += Integer.bitCount(fp0[k] & fp1[k]);
                            d += Integer.bitCount(fp0[k] ^ fp1[k]);
                        }

                        double lamda = 1./(1+2*c);
                        double m = Math.pow(1-lamda, d);
                        double t = c / (double)(a+b -c);
                        
                        /*
                          System.out.println
                          (f.toFormat("smiles:q")+"\t"+j
                          +"\t"+LyChIStandardizer.hashKey(f));
                        */
                        //f.setName(mol.getName()+"."+j);
                        String hashkey = f.getName();
                        f.setProperty("HashKey", hashkey);
                        f.setProperty
                            ("SMILES", ChemUtil.canonicalSMILES(f));
                        f.setProperty("MAP", f.toFormat("smiles:q0"));
                        f.setProperty("PARENT", mol.getName());
                        f.setProperty("SCORE", String.format("%1$.3f", m));
                        sb.append(f.toFormat("smiles:q0") +"\t"+hashkey+"\t"
                                  +String.format("%1$.3f", m) +"\t"
                                  +String.format("%1$.3f", t) +"\n");
                        if (!frameworks.containsKey(hashkey)) {
                            Molecule z = f.cloneMolecule();
                            for (MolAtom ma : z.getAtomArray())
                                ma.setAtomMap(0);
                            frameworks.put(hashkey, z);
                        }
                    }

                    if (sb.length() > 0) {
                        sb.deleteCharAt(sb.length()-1);
                        mol.setProperty("FRAGMENTS", sb.toString());
                    }
                    fragments.print(mol.toFormat("sdf"));

                    /*
                      for (Enumeration<String> linker = mf.getLinkerAsSmiles();
                      linker.hasMoreElements(); ) {
                      String l = linker.nextElement();
                      System.out.println(l + "\t" + name);
                      }
                    */
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
            molimp.close();
        }

        PrintStream ps = new PrintStream
            (new FileOutputStream ("frameworks.smi"));
        List<Molecule> order = new ArrayList<Molecule>(frameworks.values());
        Collections.sort(order, new Comparator<Molecule>() {
                public int compare (Molecule m1, Molecule m2) {
                    return m2.getAtomCount() - m1.getAtomCount();
                }
            });
        for (Molecule m : order) {
            ps.println(m.toFormat("smiles:q")+"\t"+m.getName());
        }
        ps.close();
    }
}
