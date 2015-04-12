package ix.ginas.controllers.v1;

import java.util.*;
import java.io.*;

import play.libs.Json;
import play.*;
import play.db.ebean.*;
import play.data.*;
import play.mvc.*;
import com.avaje.ebean.*;

import ix.core.controllers.EntityFactory;
import ix.ginas.models.*;
import ix.ginas.models.v1.*;
import ix.core.NamedResource;

@NamedResource(name="references",
               type=Reference.class,
               description="Resource for handling of GInAS references")
public class ReferenceFactory extends EntityFactory {
    static public final Model.Finder<UUID, Reference> finder =
        new Model.Finder(UUID.class, Reference.class);

    public static Reference getReference (String id) {
        return getEntity (UUID.fromString(id), finder);
    }

    public static List<Reference> getReferences
        (int top, int skip, String filter) {
        return filter (new FetchOptions (top, skip, filter), finder);
    }

    public static Result count () {
        return count (finder);
    }

    public static Result page (int top, int skip) {
        return page (top, skip, null);
    }

    public static Result page (int top, int skip, String filter) {
        return page (top, skip, filter, finder);
    }

    public static Result edits (String id) {
        return edits (UUID.fromString(id), Reference.class);
    }

    public static Result get (String id, String expand) {
        return get (UUID.fromString(id), expand, finder);
    }

    public static Result field (String id, String path) {
        return field (UUID.fromString(id), path, finder);
    }

    public static Result create () {
        return create (Reference.class, finder);
    }

    public static Result delete (String id) {
        return delete (UUID.fromString(id), finder);
    }

    public static Result update (String id, String field) {
        return update (UUID.fromString(id), field, Reference.class, finder);
    }
}
