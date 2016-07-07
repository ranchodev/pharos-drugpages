package ix.core.plugins;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import play.Logger;
import play.Plugin;
import play.Application;
import play.mvc.Result;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
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

    public static final String CACHE_MAX_ELEMENTS = "ix.cache.maxElements";
    public static final String CACHE_TIME_TO_LIVE = "ix.cache.timeToLive";
    public static final String CACHE_TIME_TO_IDLE = "ix.cache.timeToIdle";

    private final Application app;
    private Ehcache cache;
    private IxContext ctx;
    protected Database db;

    static private IxCache _instance;
    
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
        cache.getCacheConfiguration()
            .overflowToOffHeap(true)
            .maxElementsOnDisk(0)
            .maxEntriesLocalHeap(maxElements)
            .timeToLiveSeconds(app.configuration()
                               .getInt(CACHE_TIME_TO_LIVE, TIME_TO_LIVE))
            .timeToIdleSeconds(app.configuration()
                               .getInt(CACHE_TIME_TO_IDLE, TIME_TO_IDLE));
        /*
        cache.getCacheEventNotificationService()
            .registerListener(new CacheEventListenerAdapter () {
                    @Override
                    public void notifyElementPut (Ehcache cache, Element elm) {
                        Logger.debug("PUT: element="+elm);
                    }
                });
        */
        cache.registerCacheWriter(this);
        
        //CacheManager.getInstance().addCache(cache);     
        cache.setSampledStatisticsEnabled(true);
        _instance = this;
    }

    @Override
    public void onStop () {
        Logger.info("Stopping plugin "+getClass().getName());   
        try {
            cache.dispose();
            CacheManager.getInstance().removeCache(cache.getName());
        }
        catch (Exception ex) {
            Logger.trace("Disposing cache", ex);
        }
    }

    static protected void put (Element elm) {
        if (elm.isSerializable()) {
            _instance.cache.putWithWriter(elm);
        }
        else {
            // not serializable
            _instance.cache.put(elm);
        }
    }

    public static Element getElm (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        return _instance.cache.get(key);
    }
    
    public static Object get (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        Element elm = _instance.cache.get(key);
        return elm != null ? elm.getObjectValue() : null;
    }

    public static long getLastAccessTime (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        Element elm = _instance.cache.get(key);
        return elm != null ? elm.getLastAccessTime() : 0l;
    }

    public static long getExpirationTime (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        Element elm = _instance.cache.get(key);
        return elm != null ? elm.getExpirationTime() : 0l;
    }

    public static boolean isExpired (String key) {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        Element elm = _instance.cache.get(key);
        return elm != null ? elm.isExpired() : false;
    }

    /**
     * apply generator if the cache was created before epoch
     */
    public static <T> T getOrElse (long epoch,
                                   String key, Callable<T> generator)
        throws Exception {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");

        Element elm = _instance.cache.get(key);
        if (elm == null || elm.getObjectValue() == null
            || elm.getCreationTime() < epoch) {
            T v = generator.call();
            if (v != null || elm == null) {
                elm = new Element (key, v);
                IxCache.put (elm);
            }
        }
        return (T)elm.getObjectValue();
    }
    
    public static <T> T getOrElse (String key, Callable<T> generator)
        throws Exception {
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        
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
        if (_instance == null)
            throw new IllegalStateException ("Cache hasn't been initialized!");
        
        Object value = get (key);
        if (value == null) {
            if (_instance.ctx.debug(2))
                Logger.debug("IxCache missed: "+key);
            T v = generator.call();
            IxCache.put(new Element (key, v, seconds <= 0, seconds, seconds));
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
        return _instance.cache.isKeyInCache(key);
    }

    static DatabaseEntry getKeyEntry (Object value) {
        return new DatabaseEntry (value.toString().getBytes());
    }
    
    /**
     * CacheEntryFactory interface
     */
    public Object createEntry (Object key) throws Exception {
        if (!(key instanceof Serializable)) {
            throw new IllegalArgumentException
                ("Cache key "+key+" is not serliazable!");
        }

        Element elm = null;
        try {
            DatabaseEntry dkey = getKeyEntry (key);
            DatabaseEntry data = new DatabaseEntry ();
            OperationStatus status = db.get(null, dkey, data, null);
            if (status == OperationStatus.SUCCESS) {
                ObjectInputStream ois = new ObjectInputStream
                    (new ByteArrayInputStream
                     (data.getData(), data.getOffset(), data.getSize()));
                elm = new Element (key, ois.readObject());
                ois.close();
            }
            else if (status == OperationStatus.NOTFOUND) {
                // 
            }
            else {
                Logger.warn("Unknown status for key "+key+": "+status);
            }
        }
        catch (Exception ex) {
            Logger.error("Can't recreate entry for "+key, ex);
        }
        return elm;
    }
    
    /**
     * CacheWriter interface
     */
    @Override
    public void init () {
        try {
            File dir = new File (ctx.cache(), "ix");
            dir.mkdirs();
            EnvironmentConfig envconf = new EnvironmentConfig ();
            envconf.setAllowCreate(true);
            Environment env = new Environment (dir, envconf);
            DatabaseConfig dbconf = new DatabaseConfig ();
            dbconf.setAllowCreate(true);
            db = env.openDatabase(null, CACHE_NAME, dbconf);
        }
        catch (Exception ex) {
            Logger.error("Can't initialize lucene for "+ctx.cache(), ex);
        }
    }
    
    @Override
    public void dispose () {
        if (db != null) {       
            try {
                Logger.debug("#### closing cache writer "+cache.getName()
                             +"; "+db.count()+" entries #####");
                db.close();
            }
            catch (Exception ex) {
                Logger.error("Can't close lucene index!", ex);
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
            OperationStatus status = db.delete(null, dkey);
            if (status != OperationStatus.SUCCESS)
                Logger.warn("Delete cache key '"
                            +key+"' returns status "+status);
        }
        catch (Exception ex) {
            Logger.error("Deleting cache "+key+" from persistence!", ex);
        }
    }
    
    @Override
    public void write (Element elm) {
        Serializable key = elm.getKey();
        if (key != null) {
            //Logger.debug("Persisting cache key="+key+" value="+elm.getObjectValue());
            try {
                DatabaseEntry dkey = getKeyEntry (key);
                DatabaseEntry data = new DatabaseEntry
                    (Util.serialize(elm.getObjectValue()));
                OperationStatus status = db.put(null, dkey, data);
                if (status != OperationStatus.SUCCESS)
                    Logger.warn
                        ("** PUT for key "+key+" returns status "+status);
            }
            catch (Exception ex) {
                Logger.error("Can't write cache element: key="
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
