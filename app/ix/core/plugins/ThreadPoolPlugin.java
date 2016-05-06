package ix.core.plugins;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import play.Logger;
import play.Plugin;
import play.Application;


public class ThreadPoolPlugin extends Plugin {
    private final Application app;
    private ExecutorService threadPool;

    public ThreadPoolPlugin (Application app) {
        this.app = app;
    }

    public void onStart () {
        Logger.info("Loading plugin "+getClass().getName()+"...");
        threadPool = Executors.newFixedThreadPool
            (app.configuration().getInt("ix.threads", 2));
    }

    public void onStop () {
        Logger.info("Plugin "+getClass().getName()+" stopped!");
        threadPool.shutdown();
    }

    public boolean enabled () { return true; }
    
    public <T> Future<T> submit (Callable<T> callable) {
        return threadPool.submit(callable);
    }
    
    public void submit (Runnable task) {
        threadPool.submit(task);
    }
}
