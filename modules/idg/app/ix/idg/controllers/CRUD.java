package ix.idg.controllers;

import com.avaje.ebean.*;
import com.avaje.ebean.annotation.Transactional;

import ix.core.models.*;
import ix.core.plugins.*;
import ix.core.search.TextIndexer;
import ix.idg.models.*;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.search.SearchFactory;

import play.Logger;
import play.Play;
import play.cache.Cache;
import play.data.DynamicForm;
import play.data.Form;
import play.db.DB;
import play.db.ebean.Model;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.sql.DataSource;
import java.io.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CRUD implements Commons {
    static final TextIndexer INDEXER = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();

    static final Model.Finder<Long, Target> TARGET = 
        new Model.Finder(Long.class, Target.class);

    public static void addCollection (JsonNode json) throws Exception {
        JsonNode n;

        n = json.get("name");
        if (n == null || n.isNull())
            throw new IllegalArgumentException
                ("Not a valid collection json; no field 'name' found!");
        String name = n.asText();

        n = json.get("description");
        String desc = "";
        if (n != null && !n.isNull())
            desc = n.asText();
        
        Logger.debug("name="+name+"\ndesc="+desc);
        JsonNode list = json.get("targets");
        if (list.isArray()) {
            Logger.debug("targets="+list.size());
            for (int i = 0; i < list.size(); ++i) {
                n = list.get(i);
                List<Target> targets = TARGET
                    .where().eq("synonyms.term", n.asText())
                    .findList();
                if (!targets.isEmpty()) {
                    Target t = targets.get(0);
                    Logger.debug(n.asText()+" => "+t.id+" "+t.name);
                    addCollection (name, desc, t);
                }
                else {
                    Logger.warn("Unknown target '"+n.asText()+"'");
                }
            }
            
            clearCaches ();
        }
    }

    public static void addCollection (String name, String desc, Target t)
        throws Exception {
        // abuse the href for description
        Keyword kw = KeywordFactory.registerIfAbsent(COLLECTION, name, desc);
        Transaction tx = Ebean.beginTransaction();
        try {
            Value v = t.addIfAbsent((Value)kw);
            if (v == kw) {
                t.update();
                tx.commit();
                
                INDEXER.update(t);
                Logger.debug("Target "+t.id+" added to collection '"
                             +name+"' ("+kw.id+")");
            }
            else {
                Logger.warn("Target "+t.id+" is already part of collection '"
                            +name+"' ("+v.id+")");
            }
        }
        catch (Exception ex) {
            Logger.trace("Can't add target "+t.id+" to collection '"
                         +name+"' ("+kw.id+")", ex);
        }
        finally {
            Ebean.endTransaction();
        }
    }

    public static boolean delCollection (String name) throws Exception {
        boolean ok = false;
        Transaction tx = Ebean.beginTransaction();
        try {
            List<Target> targets = TARGET.where
                (Expr.and(Expr.eq("properties.label", COLLECTION),
                          Expr.eq("properties.term", name))).findList();
            if (targets.isEmpty()) {
                Logger.warn("No targets with collection named '"
                            +name+"' found!");
            }
            else {
                Logger.debug(targets.size()
                             +" targets(s) found for collection '"+name+"'!");
                for (Target t : targets) {
                    List<Value> remove = new ArrayList<Value>();
                    for (Value v : t.getProperties()) {
                        if (COLLECTION.equals(v.label)
                            && name.equals(((Keyword)v).term)) {
                            remove.add(v);
                        }
                    }
                    
                    for (Value v : remove) {
                        t.getProperties().remove(v);
                    }
                    t.update();
                }

                /*
                List<Keyword> keywords = KeywordFactory.finder
                    .where(Expr.and(Expr.eq("label", COLLECTION),
                                    Expr.eq("term", name)))
                    .findList();
                for (Keyword kw : keywords)
                    kw.delete();
                */
                
                tx.commit();

                for (Target t : targets)
                    INDEXER.update(t);
                clearCaches ();
                ok = true;
            }
        }
        finally {
            Ebean.endTransaction();
        }
        return ok;
    }

    public static void clearCaches () {
        SearchFactory.clearCaches(Target.class, COLLECTION, IDG_DEVELOPMENT);
    }
}
