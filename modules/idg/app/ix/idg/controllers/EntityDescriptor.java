package ix.idg.controllers;

import java.util.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

import ix.core.models.*;
import ix.idg.models.*;
import ix.core.stats.Histogram;
import ix.core.plugins.IxCache;
import ix.core.plugins.ThreadPoolPlugin;
import ix.core.ObjectFactory;
import ix.core.controllers.PredicateFactory;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.TermVectors;
import static ix.core.search.TextIndexer.Facet;
import ix.core.plugins.SleepycatStore;

import com.sleepycat.je.*;
import com.sleepycat.bind.ByteArrayBinding;
import com.sleepycat.bind.serial.StoredClassCatalog;
import com.sleepycat.bind.serial.ClassCatalog;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.bind.tuple.StringBinding;
import com.sleepycat.bind.tuple.LongBinding;

import play.Logger;
import play.Play;
import com.avaje.ebean.Expr;
import com.avaje.ebean.Query;
import com.avaje.ebean.QueryIterator;
import play.db.ebean.Model;

public class EntityDescriptor<T extends EntityModel> implements Commons {
    static final int NUM_SIM_WORKERS = 4;
    static final public int DIM = 50;
    
    static final SleepycatStore STORE =
        Play.application().plugin(SleepycatStore.class);
    static final ThreadPoolPlugin THREAD =
        Play.application().plugin(ThreadPoolPlugin.class);
    
    static final String BASE = EntityDescriptor.class.getName();
    static final String MODIFIED = "LastModified";
    
    static ConcurrentMap<Class, EntityDescriptor> _instances =
        new ConcurrentHashMap<Class, EntityDescriptor>();

    public static class Similarity implements Serializable,
                                              Comparable<Similarity> {
        private static final long serialVersionUID = 0x1l;

        final public double similarity;
        final public Map<String, Double> contrib = new TreeMap<>();

        Similarity (Map d1, Map d2) {
            similarity = tanimoto (d1, d2, contrib);
        }

        public int compareTo (Similarity s) {
            return Double.compare(s.similarity, similarity);
        }
    }

    public static class SimKey {
        byte[] key = new byte[16];

        public SimKey () {}
        public SimKey (byte[] key) {
            this.key = key;
        }
        public SimKey (long k1, long k2) {
            encode (k1, k2);
        }

        public void encode (long k1, long k2) {
            if (k1 > k2) {
                long t = k1;
                k1 = k2;
                k2 = t;
            }

            encode (0, key, k1);
            encode (8, key, k2);
        }

        public long key1 () { return decode (0, key); }
        public long key2 () { return decode (8, key); }

        public void encode (DatabaseEntry entry) {
            new ByteArrayBinding().objectToEntry(key, entry);
        }
        
        public static SimKey decode (DatabaseEntry entry) {
            return new SimKey (new ByteArrayBinding().entryToObject(entry));
        }
        
        static void encode (int i, byte[] b, long x) {
            b[i++] = (byte)((x >> 56) & 0xff);
            b[i++] = (byte)((x >> 48) & 0xff);
            b[i++] = (byte)((x >> 40) & 0xff);
            b[i++] = (byte)((x >> 32) & 0xff);
            b[i++] = (byte)((x >> 24) & 0xff);
            b[i++] = (byte)((x >> 16) & 0xff);
            b[i++] = (byte)((x >> 8) & 0xff);
            b[i++] = (byte)(x & 0xff);        
        }
        
        static long decode (int i, byte[] b) {
            return ((b[i++] & 0xff) << 56l)
                | ((b[i++] & 0xff) << 48l)
                | ((b[i++] & 0xff) << 40l)
                | ((b[i++] & 0xff) << 32l)
                | ((b[i++] & 0xff) << 24l)
                | ((b[i++] & 0xff) << 16l)
                | ((b[i++] & 0xff) << 8l)
                | (b[i++] & 0xff);
        }

        public void createSecondaryKeys (SecondaryDatabase secondary,
                                         DatabaseEntry key,
                                         DatabaseEntry data,
                                         Set<DatabaseEntry> results) {
            SimKey sk = SimKey.decode(key);
            DatabaseEntry key1 = new DatabaseEntry ();
            DatabaseEntry key2 = new DatabaseEntry ();
            LongBinding.longToEntry(sk.key1(), key1);
            LongBinding.longToEntry(sk.key2(), key2);
            results.add(key1);
            results.add(key2);
        }

        public String toString () {
            return "("+key1()+","+key2()+")";
        }
    }

    static class SimPayload {
        public final long ki, kj;
        public final Map di, dj;

        SimPayload () {
            this (0, null, 0, null);
        }
        SimPayload (long ki, Map di, long kj, Map dj) {
            this.ki = ki;
            this.kj = kj;
            this.di = di;
            this.dj = dj;
        }
    }
    
    static final SimPayload POISON = new SimPayload ();

    class InitDbs implements Runnable {
        public void run () {
            String name = Thread.currentThread().getName();
            lock.lock();
            try {
                generateDescriptors ();
                Logger.debug(name+": ## "+vectors.size()
                             +" descriptor(s) calculated for "
                             +kind.getName()+"; persisting vectors...");
                
                SerialBinding sb = STORE.getSerialBinding(Vector.class);
                DatabaseEntry key = new DatabaseEntry ();
                DatabaseEntry val = new DatabaseEntry ();
                
                Transaction tx = STORE.createTx();
                for (Map.Entry<String, Vector> me : vectors.entrySet()) {
                    StringBinding.stringToEntry(me.getKey(), key);
                    sb.objectToEntry(me.getValue(), val);
                    OperationStatus status = vecDb.put(tx, key, val);
                    if (status != OperationStatus.SUCCESS) {
                        Logger.warn("** PUT KEY '"+me.getKey()
                                    +"' return non-success status "+status);
                    }
                }
                tx.commit();
                Logger.debug(name+": DONE!");
            }
            catch (Exception ex) {
                Logger.error(Thread.currentThread().getName()
                             +": Can't generate descriptor vectors!", ex);
            }
            finally {
                lock.unlock();
            }
        }

        void generateDescriptors () throws Exception {
            Logger.debug(Thread.currentThread().getName()
                         +": ## generating descriptor vectors for "
                         +kind.getName());
        
            Model.Finder finder = ObjectFactory.finder(kind);
            int count = 0;
            DatabaseEntry key = new DatabaseEntry ();
            DatabaseEntry val = new DatabaseEntry ();
            SerialBinding sb = STORE.getSerialBinding(Map.class);
            
            for (Object obj : finder.all()) {
                EntityModel model = (EntityModel) obj;
                
                Map<String, Number> desc = instrument (model);
                LongBinding.longToEntry(model.id, key);
                sb.objectToEntry(desc, val);
                
                Transaction tx = STORE.createTx();
                try {
                    OperationStatus status = descDb.put(tx, key, val);
                    if (status != OperationStatus.SUCCESS) {
                        Logger.warn("** PUT KEY "+model.getClass()+"/"
                                    +model.id+" return non-success "
                                    +"status "+status);
                    }
                    else {
                        for (Map.Entry<String, Number> me :
                                 desc.entrySet()) {
                            String name = me.getKey();
                            if (name.charAt(0) != '@') {
                                Vector vec = vectors.get(name);
                                if (vec == null) {
                                    vectors.put
                                        (name,
                                         vec = new Vector (kind, name));
                                }
                                
                                Number nv = me.getValue();
                                if (vec.min == null
                                    || (vec.min.doubleValue()
                                        > nv.doubleValue()))
                                    vec.min = nv;
                                if (vec.max == null
                                    || (vec.max.doubleValue()
                                        < nv.doubleValue()))
                                    vec.max = nv;
                                vec.values.add(nv);
                            }
                        }
                        ++count;
                        Logger.debug(String.format("%1$10d: ", count)
                                     +kind.getName()+"/"+model.getName());
                    }
                }
                finally {
                    tx.commit();
                }
            }
        }       
    } // InitDbs

    class CalculatePairwiseSimilarity implements Runnable {
        public void run () {
            lock.lock();
            try {
                long start = System.currentTimeMillis();
                calcPairwise ();
                Logger.debug("## calc "+simDb.count()
                             +" pairwise similarity took "
                             +String.format("%1$.1fs",
                                            1e-3*(System.currentTimeMillis()
                                                  -start)));
            }
            catch (Exception ex) {
                Logger.error("Can't generate pairwise similarity matrix!", ex);
            }
            finally {
                lock.unlock();
            }
        }

        void calcPairwise () throws Exception {
            String name = Thread.currentThread().getName();
            Logger.debug(name+": ## calculating all pairwise "
                         +"similarity matrix for "+descDb.count()
                         +" descriptors!");
            
            DatabaseEntry key = new DatabaseEntry ();
            
            List<Long> keys = new ArrayList<>(); // load all keys in memory
            Transaction tx = STORE.createTx();
            try {
                DatabaseEntry partial = new DatabaseEntry ();
                partial.setPartial(0, 0, true);
                Cursor cursor = descDb.openCursor(tx, null);
                for (OperationStatus status =
                         cursor.getFirst(key, partial, null);
                     status == OperationStatus.SUCCESS; ) {
                    long id = LongBinding.entryToLong(key);
                    keys.add(id);
                    status = cursor.getNext(key, partial, null);
                }
                cursor.close();
                
                Logger.debug(keys.size()+" keys loaded; preparing "
                             +"to calculate "+(keys.size()*(keys.size()-1)/2)
                             +" pairwise similarity!");
                
                DatabaseEntry val = new DatabaseEntry ();
                SerialBinding<Map> serial = STORE.getSerialBinding(Map.class);
                SerialBinding<Similarity> simserial =
                    STORE.getSerialBinding(Similarity.class);
                
                OperationStatus status;
                for (int i = 0; i < keys.size(); ++i) {
                    LongBinding.longToEntry(keys.get(i), key);
                    status = descDb.get(tx, key, val, null);
                    if (status == OperationStatus.SUCCESS) {
                        Map di = serial.entryToObject(val);
                        for (int j = i+1; j < keys.size(); ++j) {
                            LongBinding.longToEntry(keys.get(j), key);
                            status = descDb.get(tx, key, val, null);
                            if (status == OperationStatus.SUCCESS) {
                                Map dj = serial.entryToObject(val);
                                queue.put(new SimPayload (keys.get(i), di,
                                                          keys.get(j), dj));
                            }
                            else {
                                Logger.warn
                                    ("Retrieving descriptors for "+keys.get(j)
                                     +" returns status="+status);
                            }
                        }
                    }
                    else {
                        Logger.warn("Retrieving descriptors for "+keys.get(i)
                                    +" returns status="+status);
                    }
                }

                // stop the sim worker
                for (int i = 0; i < NUM_SIM_WORKERS; ++i)
                    queue.put(POISON);
            }
            catch (Exception ex) {
                Logger.error("Calculating pairwise similarity", ex);
            }
            finally {
                tx.commit();
            }
        } // calcPairwise
    } // CalculatePairwiseSimilarity

    class SimWorker implements Runnable {
        SerialBinding<Similarity> simserial =
            STORE.getSerialBinding(Similarity.class);
        DatabaseEntry key = new DatabaseEntry ();
        DatabaseEntry val = new DatabaseEntry ();
        Transaction tx;
        Map<String, Histogram> histogram;
        
        public void run () {
            String name = Thread.currentThread().getName();
            try {
                long count = 0;
                for (SimPayload p; (p = queue.take()) != POISON; ) {
                    if (tx == null)
                        tx = STORE.createTx();

                    process (p);
                    if (++count % 1000 == 0) {
                        Logger.debug(name+": "+count);
                        tx.commit();
                        tx = STORE.createTx();
                    }
                }
                
                if (tx != null)
                    tx.commit();
                Logger.debug(name+": processed "+count);
            }
            catch (Exception ex) {
                Logger.error(name+" interrupted", ex);
            }
        }

        Map<String, Number> toVector (Map<String, Number> descriptor) {
            if (histogram == null) {
                if (vectors.isEmpty()) {
                    throw new RuntimeException
                        ("Descriptors haven't been extracted!");
                }
                
                histogram = new TreeMap<String, Histogram>();           
                for (Map.Entry<String, Vector> me : vectors.entrySet()) {
                    Histogram h = me.getValue().createHistogram(DIM);
                    histogram.put(me.getKey(), h);
                }
            }
            
            Map<String, Number> vector = new TreeMap<String, Number>();
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
            return vector;
        }

        void process (SimPayload p) throws Exception {      
            Similarity sim = new Similarity (toVector (p.di), toVector (p.dj));
            SimKey simkey = new SimKey (p.ki, p.kj);
            simkey.encode(key);
            simserial.objectToEntry(sim, val);
            
            OperationStatus status = simDb.put(tx, key, val);
            if (status != OperationStatus.SUCCESS)
                Logger.warn("Putting similarity value "
                            +"for keypair "+simkey
                            +" yields status="+status);
            else
                Logger.debug(String.format("%1$10d", simDb.count())
                             + ": similarity "+simkey+" = "
                             +sim.similarity);
        }
    }

    final Class<T> kind;
    final Map<String, Vector> vectors = new TreeMap<String, Vector>();
    Database vecDb, descDb, simDb;
    // upper and lower (respectively) triangle of the sim matrix
    SecondaryDatabase simIndex1Db, simIndex2Db;

    final BlockingQueue<SimPayload> queue = new ArrayBlockingQueue<>(10000);
    final ReentrantLock lock = new ReentrantLock ();
    
    protected EntityDescriptor (Class<T> kind) throws IOException {
        this.kind = kind;
        
        vecDb = STORE.createDbIfAbsent(BASE+"$"+kind.getName()+"$Vectors");
        Logger.debug("Database "+vecDb.getDatabaseName()+" initialized; "
                     +vecDb.count()+" entries...");
        
        descDb = STORE.createDbIfAbsent(BASE+"$"+kind.getName()+"$Descriptors");
        Logger.debug("Database "+descDb.getDatabaseName()+" initialized; "
                     +descDb.count()+" entries...");

        simDb = STORE.createDbIfAbsent(BASE+"$"+kind.getName()+"$Similarity");
        simIndex1Db = createSecondaryIndex
            (simDb, "Index1", new SecondaryKeyCreator () {
                    public boolean createSecondaryKey
                        (SecondaryDatabase db, DatabaseEntry key,
                         DatabaseEntry data, DatabaseEntry result) {
                        SimKey skey = SimKey.decode(key);
                        LongBinding.longToEntry(skey.key1(), result);
                        return true;
                    }
                });
        simIndex2Db = createSecondaryIndex
            (simDb, "Index2", new SecondaryKeyCreator () {
                    public boolean createSecondaryKey
                        (SecondaryDatabase db, DatabaseEntry key,
                         DatabaseEntry data, DatabaseEntry result) {
                        SimKey skey = SimKey.decode(key);
                        LongBinding.longToEntry(skey.key2(), result);
                        return true;
                    }
                });
        
        for (int i = 0; i < NUM_SIM_WORKERS; ++i)
            THREAD.submit(new SimWorker ());
        
        Logger.debug("Database "+simDb.getDatabaseName()+" initialized; "
                     +simDb.count()+" entries...");
        
        if (vecDb.count() == 0l || descDb.count() == 0l) {
            // initialize this in the background..
            THREAD.submit(new InitDbs ());
        }
        else {
            Transaction tx = STORE.createTx();
            try {
                Cursor cursor = vecDb.openCursor(tx, null);
                DatabaseEntry key = new DatabaseEntry ();
                DatabaseEntry val = new DatabaseEntry ();
                
                SerialBinding<Vector> sb = STORE.getSerialBinding(Vector.class);
                OperationStatus status = cursor.getFirst(key, val, null);
                while (status == OperationStatus.SUCCESS) {
                    String k = StringBinding.entryToString(key);
                    Vector v = sb.entryToObject(val);
                    vectors.put(k, v);
                    status = cursor.getNextNoDup(key, val, null);
                }
                cursor.close();
                Logger.debug(kind.getName()+": "+vectors.size()
                             +" descriptors loaded!");
            }
            finally {
                tx.commit();
            }
        }
    }

    static SecondaryDatabase createSecondaryIndex
        (Database db, String name, SecondaryKeyCreator keyCreator)
        throws IOException {
        Transaction tx = STORE.createTx();
        try {
            SecondaryConfig config = new SecondaryConfig();
            config.setAllowCreate(true);
            config.setTransactional(true);
            config.setSortedDuplicates(true);
            config.setKeyCreator(keyCreator);
            return db.getEnvironment()
                .openSecondaryDatabase(tx, name, db, config);
        }
        catch (Exception ex) {
            Logger.error("Can't create secondary database for "
                         +db.getDatabaseName(), ex);
            return null;
        }
        finally {
            tx.commit();
        }
    }

    public synchronized static <T extends EntityModel> EntityDescriptor<T>
        getInstance (Class<T> kind) {
        EntityDescriptor<T> inst = _instances.get(kind);
        if (inst == null) {
            try {
                EntityDescriptor prev = _instances.putIfAbsent
                    (kind, inst = new EntityDescriptor (kind));
                if (prev != null)
                    inst = prev;
            }
            catch (Exception ex) {
                Logger.error("Can't initialize "
                             +EntityDescriptor.class.getName()
                             +" instance for "+kind.getName()+"!", ex);
            }
        }
        return inst;
    }

    public Map<String, Number> get (final Long id) throws IOException {
        Map<String, Number> desc = null;
        Transaction tx = STORE.createTx();
        try {
            DatabaseEntry key = new DatabaseEntry ();
            DatabaseEntry val = new DatabaseEntry ();
            SerialBinding sb = STORE.getSerialBinding(Map.class);
            LongBinding.longToEntry(id, key);
            OperationStatus status = descDb.get(tx, key, val, null);
            if (status == OperationStatus.SUCCESS)
                desc = (Map)sb.entryToObject(val);

            return desc;
        }
        finally {
            tx.commit();
        }
    }

    public static <T extends EntityModel> Map<String, Number>
        get (final Class<T> kind, final Long id) {
        try {
            return getInstance(kind).get(id);
        }
        catch (Exception ex) {
            Logger.error("Can't generate entity descriptor for "
                         +kind+"/"+id, ex);
        }
        return null;
    }
    
    public static Map<String, Number> instrument (EntityModel model)
        throws Exception {
        Map<String, Number> descriptor = new TreeMap<String, Number>();
        instrument (descriptor, model);
        return descriptor;
    }

    static double weight (int count, int total) {
        double x = (double)count/total;
        //return Math.exp(-2.0*x);
        return Math.exp(-2.0*x*x);
    }
    
    public static void instrument
        (Map<String, Number> props, EntityModel model) throws Exception {
        
        for (Field f : model.getClass().getFields()) {
            Indexable idx = f.getAnnotation(Indexable.class);
            if (idx != null && Number.class.isAssignableFrom(f.getType())) {
                Object val = f.get(model);
                if (val != null) {
                    props.put(idx.name().equals("")
                              ? f.getName():idx.name(), (Number)val);
                }
            }
        }

        Map<String, Set> map = new HashMap<String, Set>();
        instrument (map, model.getProperties());
        
        for (XRef xref : model.getLinks()) {
            if (xref.kind.equals(Text.class.getName())) {
                try {
                    Text txt = (Text)xref.deRef();
                    Set set = map.get(txt.label);
                    if (set == null)
                        map.put(txt.label, set = new HashSet());
                    set.add(txt.text);
                }
                catch (Exception ex) {
                    Logger.error("Can't resolve xref "
                                 +xref.kind+":"+xref.refid, ex);
                }
            }
            instrument (map, xref.properties);      
        }
        
        for (Map.Entry<String, Set> me : map.entrySet()) {
            String name = me.getKey();
            Set set = me.getValue();
            if (!set.isEmpty()) {
                props.put(name, set.size());
                DictVector dv = DictVector.getInstance(model.getClass(), name);
                if (dv != null) {
                    for (Object term : set) {
                        if (term != null) {
                            Integer count = dv.getTermCount(term.toString());
                            if (count != null && count > 0) {
                                // special annotated term
                                props.put("@"+name+"/"+term,
                                          //1.-(double)count/dv.getNumDocs()
                                          weight (count, dv.getNumDocs())
                                          );
                            }
                        }
                    }
                }
            }
        }
        
        if (!model.getPublications().isEmpty())
            props.put(IDG_PUBLICATIONS, model.getPublications().size());

        Value val = model.getProperty(UNIPROT_SEQUENCE);
        if (val != null) {
            Text seq = (Text)val;
            Set<String> aaset = new HashSet<String>();
            for (int i = 0; i < seq.text.length(); ++i) {
                String aa = "AA_"+seq.text.charAt(i);
                Number n = props.get(aa);
                props.put(aa, n==null ? 1 : n.intValue()+1);
                aaset.add(aa);
            }

            /*
            for (String aa : aaset) {
                Number nv = props.get(aa);
                props.put(aa, nv.doubleValue()/seq.text.length());
            }
            */
            props.put("AA Length", seq.text.length());
        }

        List<Keyword> syns = model.getSynonyms(PDB_ID);
        if (!syns.isEmpty())
            props.put(PDB_ID, syns.size());

        for (Class cls : new Class[]{
                Target.class, Ligand.class, Disease.class}) {
            List<XRef> xrefs = IDGApp.getLinks(model, cls);
            if (!xrefs.isEmpty()) {
                props.put("Link/"+cls.getName(), xrefs.size());
            }
        }

        List<Predicate> preds = PredicateFactory.finder
            .where(Expr.and
                   (Expr.eq("subject.refid", model.id),
                    Expr.eq("subject.kind", model.getClass().getName())))
            .findList();
        for (Predicate p : preds) {
            props.put("Predicate/"+p.predicate, p.objects.size());
        }
    }

    static void instrument (Map<String, Set> map, List<Value> values) {
        for (Value v : values) {
            if (v instanceof Keyword) {
                Keyword kw = (Keyword)v;
                Set uv = map.get(kw.label);
                if (uv == null) {
                    map.put(kw.label, uv = new HashSet());
                }
                uv.add(kw.term);
                //Logger.debug("label='"+kw.label+"' term='"+kw.term+"'");
            }
        }
    }

    public static <T extends EntityModel> Map<String, Histogram>
        getDescriptorHistograms (final Class<T> kind) throws Exception {
        return getDescriptorHistograms (kind, DIM);
    }
    
    public static <T extends EntityModel> Map<String, Histogram>
        getDescriptorHistograms (final Class<T> kind, final int dim)
        throws Exception {
        Map<String, Vector> vectors =  getDescriptorVectors (kind);
        Map<String, Histogram> hist = new TreeMap<String, Histogram>();
        for (Map.Entry<String, Vector> me : vectors.entrySet()) {
            Histogram h = me.getValue().createHistogram(dim);
            hist.put(me.getKey(), h);
        }
        return hist;
    }

    public Map<String, Vector> getVectors () { return vectors; }
    public long allPairwiseSimilarity () {
        try {
            if (!lock.isLocked() && simDb.count() == 0) {
                THREAD.submit(new CalculatePairwiseSimilarity ());
            }
            return simDb.count();
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve count for "
                         +simDb.getDatabaseName(), ex);
            return -1;
        }
    }

    public void dumpDescriptorSparse (int dim) throws IOException {
        if (lock.isLocked())
            throw new RuntimeException
                ("Descriptor generation still not complete!");
        
        Transaction tx = STORE.createTx();
        try {
            // mapping of descriptor name to index
            final Map<String, Integer> dict = new TreeMap<>();
            Map<String, Histogram> histogram = new TreeMap<>();
            int map = 0;
            for (Map.Entry<String, Vector> me : vectors.entrySet()) {
                dict.put(me.getKey(), map++);
                histogram.put(me.getKey(), me.getValue().createHistogram(dim));
            }

            PrintWriter pw = new PrintWriter
                (new FileWriter ("descriptors_"+dim+".txt"));
            DatabaseEntry key = new DatabaseEntry ();
            DatabaseEntry data = new DatabaseEntry ();

            SerialBinding<Map> serial = STORE.getSerialBinding(Map.class);
            Cursor cursor = descDb.openCursor(tx, null);
            for (OperationStatus status = cursor.getFirst(key, data, null);
                 status == OperationStatus.SUCCESS;) {
                long id = LongBinding.entryToLong(key);
                
                Map<String, Number> dv = serial.entryToObject(data);
                Map<Integer, Number> nv = new TreeMap<>();
                int max = 0;
                for (Map.Entry<String, Number> me : dv.entrySet()) {
                    Histogram hist = histogram.get(me.getKey());

                    int pos;
                    Number x;
                    if (hist != null) {
                        double mass = hist.eval(me.getValue().doubleValue());
                        x = mass / hist.getWeight();
                        pos = dict.get(me.getKey());
                    }
                    else { // pass through
                        if (dict.containsKey(me.getKey()))
                            pos = dict.get(me.getKey());
                        else {
                            pos = map;
                            dict.put(me.getKey(), map++);
                        }
                        x =  me.getValue();
                    }
                    nv.put(pos, x);
                    if (pos > max)
                        max = pos;
                }
                
                pw.print(id+" "+nv.size()+" "+max);
                for (Map.Entry<Integer, Number> me : nv.entrySet())
                    pw.print(" "+me.getKey()+":"+me.getValue());
                pw.println();
                status = cursor.getNext(key, data, null);
            }
            pw.close();
            cursor.close();
            
            pw = new PrintWriter (new FileWriter ("dict.txt"));
            Map<String, Integer> ordered = new TreeMap<>
                (new Comparator<String>() {
                        public int compare (String s1, String s2) {
                            return dict.get(s1).compareTo(dict.get(s2));
                        }
                    });
            ordered.putAll(dict);
            for (Map.Entry<String, Integer> me : ordered.entrySet())
                pw.println(me.getValue()+":"+me.getKey());
            pw.close();
        }
        finally {
            tx.commit();
        }
    }

    public Map<Long, Similarity> similarity (Long id, int topK)
        throws IOException {
        Transaction tx = STORE.createTx();
        try {
            DatabaseEntry key = new DatabaseEntry ();
            DatabaseEntry pkey = new DatabaseEntry ();
            DatabaseEntry data = new DatabaseEntry ();

            SerialBinding<Similarity> serial =
                STORE.getSerialBinding(Similarity.class);

            Map<Long, Similarity> results = new TreeMap<>();
            LongBinding.longToEntry(id, key);

            // upper triangle
            SecondaryCursor cursor = simIndex2Db.openCursor(tx, null);      
            OperationStatus status =
                cursor.getSearchKey(key, pkey, data, null);
            Logger.debug("similarity for "+id+"...");
            while (status == OperationStatus.SUCCESS) {
                Similarity sim = serial.entryToObject(data);
                SimKey skey = SimKey.decode(pkey);
                if (skey.key2() == id) {
                    Logger.debug(".."+skey+" "+sim.similarity);
                    results.put(skey.key1(), sim);
                }
                else 
                    break;
                status = cursor.getNext(key, pkey, data, null);
            }
            cursor.close();

            // lower triangle
            LongBinding.longToEntry(id, key);
            cursor = simIndex1Db.openCursor(tx, null);
            status = cursor.getSearchKey(key, pkey, data, null);
            while (status == OperationStatus.SUCCESS) {
                Similarity sim = serial.entryToObject(data);
                SimKey skey = SimKey.decode(pkey);
                if (skey.key1() == id) {
                    Logger.debug(".."+skey+" "+sim.similarity);
                    results.put(skey.key2(), sim);
                }
                else 
                    break;
                status = cursor.getNext(key, pkey, data, null);         
            }
            cursor.close();
            
            return results;
        }
        finally {
            tx.commit();
        }
    }
    
    public static <T extends EntityModel> Map<String, Vector>
        getDescriptorVectors (final Class<T> kind) throws Exception {
        return getInstance(kind).getVectors();
    }

    public static double tanimoto (Map<String, Number> vec1,
                                   Map<String, Number> vec2) {
        return tanimoto (vec1, vec2, null);
    }

    static final String[] IGNORE = new String[]{
        "IDG Tissue Ref",
        "Reactome Pathway Ref",
        "Protein Class"
    };

    static boolean blacklist (String name) {
        /*
        for (String s : IGNORE) {
            if (name.indexOf(s) >= 0)
                return true;
        }
        */
        return false;
    }
    
    public static double tanimoto (Map<String, Number> vec1,
                                   Map<String, Number> vec2,
                                   Map<String, Double> contribution) {
        double a = 0., b = 0., c = 0.;
        
        Map<String, Double> contrib = new HashMap<String, Double>();
        for (Map.Entry<String, Number> me : vec1.entrySet()) {
            String name = me.getKey();
            double x = me.getValue().doubleValue();
            Number z = vec2.get(name);
            if (!blacklist (name)) {
                if (z != null) {
                    double y = z.doubleValue();
                    c += x*y;
                    contrib.put(name, x*y);
                }
                a += x*x;
            }
        }
        
        for (Map.Entry<String, Number> me : vec2.entrySet()) {
            if (!blacklist (me.getKey())) {
                double y = me.getValue().doubleValue();
                b += y*y;
            }
        }
        
        double z = a+b-c;
        for (Map.Entry<String, Double> me : contrib.entrySet()) {
            me.setValue(me.getValue()/z);
        }
        
        if (contribution != null)
            contribution.putAll(contrib);

        return c / z;
    }
}
