package ix.core.plugins;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import com.sleepycat.je.*;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.serial.StoredClassCatalog;

import play.Logger;
import play.Plugin;
import play.Application;

public class SleepycatStore extends Plugin {
    static final String CATALOG_DB = "CATALOG";
    
    private final Application app;
    protected Environment env;
    protected StoredClassCatalog catalog;
    
    Map<String, Database> databases = new HashMap<String, Database>();
    ConcurrentMap<Class, SerialBinding> bindings =
        new ConcurrentHashMap<Class, SerialBinding>();
    
    public SleepycatStore (Application app) {
        this.app = app;
    }

    public void onStart () {
        Logger.info("Loading plugin "+getClass().getName()+"...");
        IxContext ctx = app.plugin(IxContext.class);
        try {
            File dir = new File (ctx.home(), "store");
            dir.mkdirs();
            
            EnvironmentConfig envconf = new EnvironmentConfig ();
            envconf.setAllowCreate(true);
            envconf.setTransactional(true);
            envconf.setTxnTimeout(5, TimeUnit.SECONDS);
            envconf.setLockTimeout(5, TimeUnit.SECONDS);
            env = new Environment (dir, envconf);
            catalog = new StoredClassCatalog (createDbIfAbsent (CATALOG_DB));
        }
        catch (Exception ex) {
            throw new RuntimeException (ex);
        }
    }

    public <T> SerialBinding<T> getSerialBinding (Class<T> cls) {
        SerialBinding sb = new SerialBinding (catalog, cls);
        SerialBinding binder = bindings.putIfAbsent(cls, sb);
        if (binder == null)
            binder = sb;
        return binder;
    }
    
    public void onStop () {
        if (env != null) {
            try {
                for (Database db : databases.values()) {
                    for (SecondaryDatabase sdb : db.getSecondaryDatabases())
                        sdb.close();
                    db.close();
                }
                env.close();
            }
            catch (Exception ex) {
                Logger.error("Can't close database environment!", ex);
            }
        }
        Logger.info("Plugin "+getClass().getName()+" stopped!");        
    }

    public void delete (Database db) throws IOException {
        delete (db.getDatabaseName());
    }
    
    public void delete (String name) throws IOException {
        databases.remove(name);
        Transaction tx = env.beginTransaction(null, null);
        try {
            env.removeDatabase(tx, name);
        }
        finally {
            tx.commit();
        }
    }

    public synchronized Database createDbIfAbsent (String name)
        throws IOException {
        Database db = databases.get(name);
        if (db == null) {
            Transaction tx = env.beginTransaction(null, null);
            try {
                DatabaseConfig dbconf = new DatabaseConfig ();
                dbconf.setAllowCreate(true);
                dbconf.setTransactional(true);
                db = env.openDatabase(tx, name, dbconf);
                databases.put(name, db);
            }
            catch (Exception ex) {
                Logger.error("Can't create database \""+name+"\"", ex);
            }
            finally {
                tx.commit();
            }
        }
        return db;
    }

    public Transaction createTx () throws IOException {
        Transaction tx = env.beginTransaction(null, null);
        /*Logger.debug("Transaction created "+tx.getId());
        for (StackTraceElement st : Thread.currentThread().getStackTrace()) {
            Logger.debug(st.toString());
            }*/
        return tx;
    }

    public Environment getEnv () { return env; }
}
