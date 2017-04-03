package ix.npc.controllers;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import ix.utils.Util;
import ix.core.NamedResource;
import ix.core.controllers.EntityFactory.FetchOptions;
import ix.npc.models.Entity;
import static ix.ncats.controllers.auth.Authentication.Secured;
import ix.core.plugins.TextIndexerPlugin;
import ix.core.search.TextIndexer;

import java.util.List;

import com.avaje.ebean.FutureRowCount;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import play.Logger;
import play.db.ebean.Model;
import play.mvc.Result;
import play.mvc.Security;

@NamedResource(name="entities",
               type=Entity.class,
               description="Resource for entities")
public class EntityFactory extends ix.core.controllers.EntityFactory {
    public static final Model.Finder<Long, Entity> finder =
        new Model.Finder(Long.class, Entity.class);

    public static Entity getEntity (Long id) {
        return getEntity (id, finder);
    }
    
    public static List<Entity> getEntities (int top, int skip, String filter) {
        return filter (new FetchOptions (top, skip, filter), finder);
    }
    
    public static Result count () { return count (finder); }
    public static Result page (int top, int skip, String filter) {
        return page (top, skip, filter, finder);
    }

    public static Result get (Long id, String select) {
        return get (id, select, finder);
    }

    public static Result field (Long id, String path) {
        return field (id, path, finder);
    }

    public static Result edits (Long id) {
        return edits (id, Entity.class);
    }

    @Security.Authenticated(Secured.class)    
    public static Result create () {
        return create (Entity.class, finder);
    }

    @Security.Authenticated(Secured.class)    
    public static Result delete (Long id) {
        return delete (id, finder);
    }

    @Security.Authenticated(Secured.class)    
    public static Result update (Long id, String field) {
        return update (id, field, Entity.class, finder);
    }

    //@Security.Authenticated(Secured.class)
    public static Result reindex () {
        return ix.core.controllers.EntityFactory.reindex(finder);
    }
}
