package ix.npc.controllers;

import ix.core.NamedResource;
import ix.core.controllers.EntityFactory.FetchOptions;
import ix.npc.models.Entity;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.avaje.ebean.FutureRowCount;
import com.fasterxml.jackson.databind.JsonNode;
import play.db.ebean.Model;
import play.mvc.Result;

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

    public static Result create () {
        return create (Entity.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Entity.class, finder);
    }
}
