package ix.core.controllers;

import java.io.*;
import java.util.*;

import play.*;
import play.db.ebean.*;
import play.data.*;
import play.mvc.*;

import ix.core.models.Payload;
import ix.core.models.Structure;
import ix.core.NamedResource;


@NamedResource(name="structures",
               type=Structure.class,
               description="Resource for handling chemical structures")
public class StructureFactory extends EntityFactory {
    
    public static final Model.Finder<UUID, Structure> finder = 
        new Model.Finder(UUID.class, Structure.class);

    public static Structure getStructure (UUID id) {
        return getEntity (id, finder);
    }

    public static Structure getStructure (String id) {
        return getEntity (toUUID (id), finder);
    }
    
    public static Result count () { return count (finder); }
    public static Result page (int top, int skip, String filter) {
        return page (top, skip, filter, finder);
    }

    public static Result get (UUID id, String select) {
        return get (id, select, finder);
    }

    public static Result field (UUID id, String path) {
        return field (id, path, finder);
    }

    public static Result create () {
        return create (Structure.class, finder);
    }

    public static Result delete (UUID id) {
        return delete (id, finder);
    }

    public static Result edits (UUID id) {
        return edits (id, Structure.class);
    }

    public static Result update (UUID id, String field) {
        return update (id, field, Structure.class, finder);
    }
}
