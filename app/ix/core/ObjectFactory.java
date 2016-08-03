package ix.core;

import java.util.Arrays;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentHashMap;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

import javax.persistence.*;
import play.db.ebean.Model;
import com.avaje.ebean.Query;

import ix.utils.Util;
import ix.core.plugins.IxCache;

public class ObjectFactory {
    static protected final ConcurrentMap<Class, Model.Finder> finders =
        new ConcurrentHashMap<Class, Model.Finder>();
    
    protected ObjectFactory () {}

    public static Object find (Class kind, Object id, String... fields)
        throws Exception {
        Model.Finder finder = finders.get(kind);
        if (finder == null) {
            finders.putIfAbsent
                (kind, finder = new Model.Finder(id.getClass(), kind));
        }

        Object obj = null;
        if (fields == null || fields.length == 0)
            obj = finder.byId(id);
        else {
            Query q = finder.setId(id);
            for (String f : fields)
                q = q.fetch(f);
            obj = q.findUnique();
        }
        
        finder = null;
        return obj;
    }
    
    public static Object get (final Class kind, final Object id,
                              final String... fields)
        throws Exception {
        final StringBuilder key = new StringBuilder (kind.getName()+":"+id);
        if (fields != null && fields.length > 0) {
            Arrays.sort(fields);
            key.append(":"+Util.sha1(fields));
        }
        
        return IxCache.getOrElse(key.toString(), new Callable () {
                    public Object call () throws Exception {
                        return find (kind, id, fields);
                    }
                });
    }
}
