package ix.idg.controllers;

import java.util.*;
import java.io.*;
import java.lang.reflect.Field;
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
    static final SleepycatStore STORE =
        Play.application().plugin(SleepycatStore.class);
    static final ThreadPoolPlugin THREAD =
        Play.application().plugin(ThreadPoolPlugin.class);
    
    static final String BASE = EntityDescriptor.class.getName();
    static final String MODIFIED = "LastModified";
    static final ReentrantLock LOCK = new ReentrantLock ();
    
    static ConcurrentMap<Class, EntityDescriptor> _instances =
        new ConcurrentHashMap<Class, EntityDescriptor>();

    class InitDbs implements Runnable {
        public void run () {
            String name = Thread.currentThread().getName();
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
    

    final Class<T> kind;
    final Map<String, Vector> vectors = new TreeMap<String, Vector>();
    Database vecDb, descDb;
    
    protected EntityDescriptor (Class<T> kind) throws IOException {
        vecDb = STORE.createDbIfAbsent(BASE+"$"+kind.getName()+"$Vectors");
        Logger.debug("Database "+vecDb.getDatabaseName()+" initialized; "
                     +vecDb.count()+" entries...");
        
        descDb = STORE.createDbIfAbsent(BASE+"$"+kind.getName()+"$Descriptors");
        Logger.debug("Database "+descDb.getDatabaseName()+" initialized; "
                     +descDb.count()+" entries...");
        
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
        this.kind = kind;       
    }

    public static <T extends EntityModel> EntityDescriptor<T>
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
                            if (count != null)
                                // special annotated term
                                props.put("@"+name+"/"+term,
                                          1.-(double)count/dv.getNumDocs());
                        }
                    }
                }
            }
        }
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
        for (String s : IGNORE) {
            if (name.indexOf(s) >= 0)
                return true;
        }
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
            if (z != null && !blacklist (name)) {
                double y = z.doubleValue();
                c += x*y;
                contrib.put(name, x*y);
            }
            a += x*x;       
        }
        
        for (Map.Entry<String, Number> me : vec2.entrySet()) {
            double y = me.getValue().doubleValue();
            b += y*y;
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
