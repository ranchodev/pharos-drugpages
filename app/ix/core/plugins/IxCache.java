package ix.core.plugins;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.security.DigestOutputStream;

import play.Logger;
import play.Plugin;
import play.Application;
import play.mvc.Result;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.config.CacheWriterConfiguration;
import net.sf.ehcache.Statistics;
import net.sf.ehcache.Status;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.CacheEntry;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.writer.CacheWriter;
import net.sf.ehcache.writer.AbstractCacheWriter;
import net.sf.ehcache.loader.CacheLoader;
import net.sf.ehcache.constructs.blocking.CacheEntryFactory;
import net.sf.ehcache.constructs.blocking.SelfPopulatingCache;
import net.sf.ehcache.writer.writebehind.operations.SingleOperationType;
import net.sf.ehcache.event.CacheEventListenerAdapter;

import com.sleepycat.je.*;
import ix.utils.Util;

public class IxCache extends Plugin
    implements CacheWriter, CacheEntryFactory {
    public static final String CACHE_NAME = "IxCache";
    
    static final int MAX_ELEMENTS = 10000;
    static final int TIME_TO_LIVE = 60*60; // 1hr
    static final int TIME_TO_IDLE = 60*60; // 1hr
    
    public static final String CACHE_MAX_OBJECT_SIZE =
        "ix.cache.maxCacheObjectSize";
    public static final String CACHE_MAX_ELEMENTS = "ix.cache.maxElements";
    public static final String CACHE_TIME_TO_LIVE = "ix.cache.timeToLive";
    public static final String CACHE_TIME_TO_IDLE = "ix.cache.timeToIdle";
    public static final String CACHE_QUEUE_SIZE = "ix.cache.queueSize";

    private ExecutorService persistencePool;
    private final Application app;
    private Ehcache cache;
    private IxContext ctx;
    
    private File payload; // payload for cache that's too big (>5MB)
    private long maxCacheObjectSize;
    private AtomicBoolean shuttingDown = new AtomicBoolean (false);
    protected Database db;
    protected Environment env;

    static private IxCache _instance;

    // a simple wrapper to store the file pointer to where the actual payload
    // resides
    static class CacheFilePointer implements Serializable {
        public final File file;
        CacheFilePointer (File file) {
            this.file = file;
        }

        public Object deserialize () throws Exception {
            if (!file.exists())
                throw new RuntimeException
                    ("Cache file "+file+" is not available!");
            
            ObjectInputStream ois = new ObjectInputStream
                (new GZIPInputStream (Files.newInputStream
                                      (file.toPath(), 
                                       StandardOpenOption.READ)));
            Object obj = ois.readObject();
            ois.close();
            return obj;
        }
    }

    static class CacheObject implements Serializable {
        static final long serialVersionUID = 0x123456l;
        public long created;
        public long lastUpdated;
        public Object data;

        CacheObject (long created, long lastUpdated, Object data) {
            this.created = created;
            this.lastUpdated = lastUpdated;
            this.data = data;
        }

        CacheObject (Object data) {
            if (data instanceof Element) {
                Element elm = (Element)data;
                this.created = elm.getCreationTime();
                this.lastUpdated = elm.getLastUpdateTime();
                this.data = elm.getValue();
            }
            else {
                this.data = data;
                this.created = this.lastUpdated = System.currentTimeMillis();
            }
        }
    }

    static class CacheAlias implements Serializable {
        static final long serialVersionUID = 0x121212l;
        public String key;
        CacheAlias (String key) {
            this.key = key;
        }
    }

    class SerializePayload {
        final Element elm;
        
        SerializePayload () {
            elm = null;
        }
        SerializePayload (Element elm) {
            this.elm = elm;
        }

        public void persists () throws Exception {
            int tries = 0;
            byte[] buf = null;
            
            do {
                buf = serialize (elm);
                if (buf != null) {
                    DatabaseEntry data = new DatabaseEntry (buf);
                    DatabaseEntry dkey = getKeyEntry (elm.getKey());
                    Transaction tx = env.beginTransaction(null, null);
                    try {
                        OperationStatus status = db.put(tx, dkey, data);
                        if (status != OperationStatus.SUCCESS)
                            Logger.warn
                                ("** PUT for key "+elm.getKey()
                                 +" returns status "+status);
                        //Logger.debug(Thread.currentThread().getName()+" >>> cached key: '"+elm.getKey()+"' ["+elm.getObjectValue().getClass()+"] = "+buf.length);
                    }
                    finally {
                        tx.commit();
                    }
                }
                else {
                    // this is just a bad way but is needed because there
                    // isn't a good way for us to know when an entity bean
                    // is done with lazy loading..
                    Thread.sleep(100);
                }
                ++tries;
            }
            while (buf == null && tries < 10);
            
            if (buf == null) {
                Logger.error("Serialization of "
                             +elm.getKey()+" failed after "+tries+" attempts!");
            }
        }
    }

    final SerializePayload POISON_PAYLOAD = new SerializePayload ();

    private ArrayBlockingQueue<SerializePayload> queue;
    class PersistenceQueue implements Runnable {
        public void run () {
            String name = Thread.currentThread().getName();
            try {
                for (SerializePayload p;
                     (p = queue.take()) != POISON_PAYLOAD;) {
                    try {
                        p.persists();
                    }
                    catch (Exception ex) {
                        Logger.error
                            (name+": can't persist cache payload: "
                             +p.elm.getKey(), ex);
                    }
                }
                Logger.debug(name+": complete...shutting down");
            }
            catch (Exception ex) {
                Logger.error(name+": shutting down ", ex);
            }
        }
    }
    
    public IxCache (Application app) {
        this.app = app;
    }

    @Override
    public void onStart () {
        Logger.info("Loading plugin "+getClass().getName()+"...");
        ctx = app.plugin(IxContext.class);

        int maxElements = app.configuration()
            .getInt(CACHE_MAX_ELEMENTS, MAX_ELEMENTS);

        cache = new SelfPopulatingCache
            (CacheManager.getInstance().addCacheIfAbsent(CACHE_NAME), this);
        //cache = CacheManager.getInstance().addCacheIfAbsent(CACHE_NAME);
        
        cache.registerCacheWriter(this);
        CacheWriterConfiguration wconf = new CacheWriterConfiguration ();
        wconf.maxWriteDelay(5)
            .minWriteDelay(1)
            .writeMode(CacheWriterConfiguration.WriteMode.WRITE_BEHIND)
            .rateLimitPerSecond(100)
            .writeBehindMaxQueueSize(app.configuration()
                                     .getInt(CACHE_QUEUE_SIZE, 1000))
            .writeBatching(true)
            .writeBatchSize(100)
            .writeCoalescing(true)
            .retryAttempts(20)
            .retryAttemptDelaySeconds(2)
            .notifyListenersOnException(true)
            ;
        
        cache.getCacheConfiguration()
            .cacheWriter(wconf)
            .eternal(true)
            .overflowToOffHeap(true)
            .overflowToDisk(true)
            .maxElementsOnDisk(0)
            .diskPersistent(true)
            .maxEntriesLocalHeap(maxElements)            
            ;
        /*
        cache.getCacheEventNotificationService()
            .registerListener(new CacheEventListenerAdapter () {
                    @Override
                    public void notifyElementPut (Ehcache cache, Element elm) {
                        Logger.debug("PUT: element="+elm);
                    }
                });
        */

        queue = new ArrayBlockingQueue<SerializePayload>
            (app.configuration().getInt(CACHE_QUEUE_SIZE, 10000));
        
        maxCacheObjectSize = app.configuration()
            .getLong(CACHE_MAX_OBJECT_SIZE, 1024*1024*5l);

        ExecutorService es = Executors.newSingleThreadExecutor();
        es.submit(new PersistenceQueue ());
        
        //CacheManager.getInstance().addCache(cache);     
        cache.setSampledStatisticsEnabled(true);
        _instance = this;
    }

    @Override
    public void onStop () {
        Logger.info("Stopping plugin "+getClass().getName());   
        try {
            shuttingDown.set(true);
            queue.put(POISON_PAYLOAD);
            cache.dispose();
            CacheManager.getInstance().removeCache(cache.getName());
        }
        catch (Exception ex) {
            Logger.trace("Disposing cache", ex);
        }
    }

    static protected void put (Element elm) {
        if (elm.isSerializable() /*&& _instance.queue.remainingCapacity() > 0*/) {
            _instance.cache.putWithWriter(elm);
            if (false) {
                Logger.debug("caching key="+elm.getKey()
                             +" value="+elm.getObjectValue());
                for (StackTraceElement st :
                         Thread.currentThread().getStackTrace()) {
                    Logger.debug(st.getFileName()+":"+st.getLineNumber()+":"
                                 +":"+st.getClassName()+":"+st.getMethodName());
                }
            }
        }
        else {
            // not serializable
            _instance.cache.put(elm);
        }
    }

    public static Element getElm (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        
        Element elm = _instance.cache.get(key);
        if (elm != null) {
            Object obj = elm.getObjectValue();
            while (obj instanceof CacheAlias) {
                elm = _instance.cache.get(((CacheAlias)obj).key);
                obj = elm != null ? elm.getObjectValue() : null;
            }
        }
        return elm;
    }
    
    public static Object get (String key) {
        Element elm = getElm (key);
        return elm != null ? elm.getObjectValue() : null;
    }

    public static boolean alias (String key, String oldKey) {
        boolean ok = false;
        if (!key.equals(oldKey)) {
            Logger.debug("creating alias "+key+" => "+oldKey);
            put (new Element (key, new CacheAlias (oldKey)));
        }
        return ok;
    }

    public static long getLastAccessTime (String key) {
        Element elm = getElm (key);
        return elm != null ? elm.getLastAccessTime() : 0l;
    }

    public static long getExpirationTime (String key) {
        Element elm = getElm (key);
        return elm != null ? elm.getExpirationTime() : 0l;
    }

    public static boolean isExpired (String key) {
        Element elm = getElm (key);
        return elm != null ? elm.isExpired() : false;
    }

    /**
     * apply generator if the cache was created before epoch
     */
    public static <T> T getOrElse (long epoch,
                                   String key, Callable<T> generator)
        throws Exception {
        Element elm = getElm (key);
        if (elm == null || elm.getObjectValue() == null
            || elm.getCreationTime() < epoch) {
            T v = generator.call();
            if (v != null || elm == null) {
                elm = new Element (key, v);
                IxCache.put(elm);
            }
        }
        return (T)elm.getObjectValue();
    }
    
    public static <T> T getOrElse (String key, Callable<T> generator)
        throws Exception {
        Object value = get (key);
        if (value == null) {
            if (_instance.ctx.debug(2))
                Logger.debug("IxCache missed: "+key);
            T v = generator.call();
            IxCache.put(new Element (key, v));
            return v;
        }
        return (T)value;
    }

    // mimic play.Cache 
    public static <T> T getOrElse (String key, Callable<T> generator,
                                   int seconds) throws Exception {
        Object value = get (key);
        if (value == null) {
            if (_instance.ctx.debug(2))
                Logger.debug("IxCache missed: "+key);
            T v = generator.call();
            IxCache.put(new Element (key, v,
                                     seconds <= 0, seconds, seconds));
            return v;
        }
        return (T)value;
    }

    public static List getKeys () {
        try {
            return new ArrayList (_instance.cache.getKeys());
        }
        catch (Exception ex) {
            Logger.trace("Can't get cache keys", ex);
        }
        return null;
    }
    
    public static List getKeys (int top, int skip) {
        List keys = getKeys ();
        if (keys != null) {
            keys = keys.subList(skip, Math.min(skip+top, keys.size()));
        }
        return keys;
    }
    
    public static void set (String key, Object value) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        IxCache.put(new Element (key, value));
    }

    public static boolean setIfNewer (String key, Object value, long time) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        boolean set = true;
        Element elm = getElm (key);
        if (elm != null) {
            if (time > elm.getLastUpdateTime())
                IxCache.put(new Element (key, value));
            else
                set = false;
        }
        else {
            IxCache.put(new Element (key, value));
        }
        return set;
    }
    
    public static void set (String key, Object value, int expiration) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        IxCache.put(new Element (key, value,
                                 expiration <= 0, expiration, expiration));
    }

    public static boolean remove (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        return _instance.cache.removeWithWriter(key);
    }
    
    public static Statistics getStatistics () {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        return _instance.cache.getStatistics();
    }

    public static boolean contains (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        boolean found = _instance.cache.isKeyInCache(key);
        if (!found && _instance.db != null) {
            // try persistence
            try {
                DatabaseEntry dkey = _instance.getKeyEntry(key);
                DatabaseEntry data = new DatabaseEntry ();
                data.setPartial(0, 0, true); // don't return the data
                Transaction tx = _instance.env.beginTransaction(null, null);
                try {
                    found = OperationStatus.SUCCESS ==
                        _instance.db.get(tx, dkey, data, null);
                }
                finally {
                    tx.commit();
                }
            }
            catch (Exception ex) {
                Logger.error("Can't search persistence database for "+key, ex);
            }
        }
        return found;
    }

    DatabaseEntry getKeyEntry (Object value) throws Exception {
        return new DatabaseEntry (value.toString().getBytes("utf-8"));
    }
    
    /**
     * CacheEntryFactory interface
     */
    public Object createEntry (Object key) throws Exception {
        if (key == null) 
            return null;
        
        if (!(key instanceof Serializable)) {
            throw new IllegalArgumentException
                ("Cache key "+key+" is not serliazable!");
        }
        
        Element elm = null;
        DatabaseEntry dkey = getKeyEntry (key); 
        try {
            DatabaseEntry data = new DatabaseEntry ();
            Transaction tx = env.beginTransaction(null, null);
            try {
                OperationStatus status = db.get(tx, dkey, data, null);
                switch (status) {
                case SUCCESS:
                    {   CacheObject obj = (CacheObject) deserialize (data);
                        elm = new Element (key, obj.data, 0l, obj.created,
                                           System.currentTimeMillis(),
                                           obj.lastUpdated, 1l);
                    }
                    break;
                    
                case NOTFOUND: 
                    //Logger.warn("Can't find cache entry: '"+key+"'");
                    break;
                    
                default:
                    Logger.warn("Unknown status for key "+key+": "+status);
                }
            }
            finally {
                tx.commit();
            }
        }
        catch (Exception ex) {
            Logger.warn("Can't recreate entry for "+key
                        +"; removing this entry from cache!", ex);
            Transaction tx = env.beginTransaction(null, null);
            try {
                db.delete(tx, dkey);
            }
            catch (Exception exx) {
            }
            finally {
                tx.commit();
            }
        }
        return elm;
    }

    protected byte[] serialize (Object obj) throws IOException {
        return serialize (null, null, obj);
    }
    
    protected byte[] serialize (String prefix, String suffix, Object obj)
        throws IOException {
        Path path = Files.createTempFile(payload.toPath(), prefix, suffix);
        File file = path.toFile();
        
        byte[] ret = null;      
        try {
            MessageDigest md = MessageDigest.getInstance("sha1");
            ObjectOutputStream oos = new ObjectOutputStream
                (new DigestOutputStream 
                 (new GZIPOutputStream (new FileOutputStream (file)), md));

            CacheObject entry = new CacheObject (obj);
            oos.writeObject(entry);
            oos.close();

            if (file.length() > maxCacheObjectSize) {
                // rename this file to something permanent
                File sha1 = new File (payload, Util.toHex(md.digest()));
                if (!sha1.exists()) {
                    if (file.renameTo(sha1)) {
                        file = sha1;
                    }
                    else {
                        Logger.warn("Can't rename file "+file+" to "+sha1);
                    }
                    Logger.debug(Thread.currentThread().getName()+": large ("
                                 +file.length()+") cache "+file.getName()
                                 +" saved; "+queue.size()
                                 +" remains in queue!");
                }
                else {
                    file.delete();
                    file = sha1;
                }
                
                ByteArrayOutputStream bytes = new ByteArrayOutputStream ();
                oos = new ObjectOutputStream (bytes);
                oos.writeObject(new CacheFilePointer (file));
                oos.close();
                ret = bytes.toByteArray();
                file = null;
            }
            else {
                ret = Files.readAllBytes(path);
            }
        }
        catch (ConcurrentModificationException ex) {
            // bean is still lazy loading.. ignore for now
        }
        catch (Exception ex) {
            Logger.error("Can't serialize object "+obj, ex);
        }
        finally {
            if (file != null)
                file.delete();
        }
        return ret;
    }

    protected Object deserialize (DatabaseEntry e) throws Exception {
        return deserialize (e.getData(), e.getOffset(), e.getSize());
    }
    
    protected Object deserialize (byte[] data, int offset, int size)
        throws Exception {
        int magic = ((data[1] & 0xff) << 8) | (data[0] & 0xff);
        InputStream is = new ByteArrayInputStream (data, offset, size);
        ObjectInputStream ois = new ObjectInputStream
             (magic == GZIPInputStream.GZIP_MAGIC 
              ? new GZIPInputStream (is) : is);
        Object obj = ois.readObject();
        if (obj instanceof CacheFilePointer) {
            obj = ((CacheFilePointer)obj).deserialize();
        }
        return obj;
    }
    
    /**
     * CacheWriter interface
     */
    @Override
    public void init () {
        try {
            payload = new File (ctx.cache(), "payload");
            payload.mkdirs();

            File dir = new File (ctx.cache(), "ix");
            dir.mkdirs();
            EnvironmentConfig envconf = new EnvironmentConfig ();
            envconf.setAllowCreate(true);
            envconf.setTransactional(true);
            envconf.setTxnTimeout(5, TimeUnit.SECONDS);
            envconf.setLockTimeout(5, TimeUnit.SECONDS);
            env = new Environment (dir, envconf);
            Transaction tx = env.beginTransaction(null, null);
            try {
                DatabaseConfig dbconf = new DatabaseConfig ();
                dbconf.setAllowCreate(true);
                dbconf.setTransactional(true);
                db = env.openDatabase(tx, CACHE_NAME, dbconf);
                Logger.debug("## persistence cache "+dir
                             +" contains "+db.count()+" entries!");
            }
            finally {
                tx.commit();
            }
        }
        catch (Exception ex) {
            Logger.error("Can't initialize lucene for "+ctx.cache(), ex);
        }
    }
    
    @Override
    public void dispose () {
        if (!shuttingDown.get()) {
            Logger.error
                ("!!! Cache writer isn't being disposed in shutdown !!!");
            for (StackTraceElement st :
                     Thread.currentThread().getStackTrace()) {
                Logger.debug(st.getFileName()+":"+st.getLineNumber()+":"
                             +":"+st.getClassName()+":"+st.getMethodName());
            }
        }
        else if (db != null) {       
            try {
                Logger.debug("#### closing cache writer "+cache.getName()
                             +"; "+db.count()+" entries #####");
                db.close();
                env.close();
            }
            catch (Exception ex) {
                Logger.error("Can't close cache database!", ex);
            }
        }
    }
    
    @Override
    public void delete (CacheEntry entry) {
        Object key = entry.getKey();
        if (!(key instanceof Serializable))
            return;

        try {
            DatabaseEntry dkey = getKeyEntry (key);
            Transaction tx = env.beginTransaction(null, null);
            try {
                OperationStatus status = db.delete(tx, dkey);
                if (status != OperationStatus.SUCCESS)
                    Logger.warn("Delete cache key '"
                                +key+"' returns status "+status);
            }
            finally {
                tx.commit();
            }
        }
        catch (Exception ex) {
            Logger.error("Deleting cache "+key+" from persistence!", ex);
        }
    }
    
    @Override
    public void write (Element elm) {
        Serializable key = elm.getKey();
        if (key != null) {
            try {
                /*
                if (!queue.offer(new SerializePayload (elm),
                                 1000, TimeUnit.MILLISECONDS)) {
                    Logger.warn("Persistence queue is full; cache "+key
                                +" not persisted within alotted time!");
                }
                */
                new SerializePayload(elm).persists();
            }
            catch (Exception ex) {
                Logger.error("Can't queue cache element: key="
                             +key+" value="+elm.getObjectValue(), ex);
            }
        }
        else {
            Logger.warn("Key "+elm.getObjectKey()+" isn't serializable!");
        }
    }

    @Override
    public void deleteAll (Collection<CacheEntry> entries) {
        for (CacheEntry e : entries)
            delete (e);
    }
    
    @Override
    public void writeAll (Collection<Element> entries) {
        for (Element elm : entries)
            write (elm);
    }

    @Override
    public void throwAway (Element elm,
                           SingleOperationType op, RuntimeException ex) {
        Logger.error("Throwing away cache element "+elm.getKey(), ex);
    }

    @Override
    public CacheWriter clone (Ehcache cache) {
        throw new UnsupportedOperationException
            ("This implementation doesn't support clone operation!");
    }
}
