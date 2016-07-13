package ix.idg.controllers;

import java.util.*;

import play.db.ebean.*;
import play.mvc.Result;

import ix.core.NamedResource;
import ix.idg.models.Compartment;
import ix.core.controllers.EntityFactory;

@NamedResource(name="compartments",type=Compartment.class)
public class CompartmentFactory extends EntityFactory {
    static final public Model.Finder<Long, Compartment> finder = 
        new Model.Finder(Long.class, Compartment.class);

    public static Compartment getCompartment (Long id) {
        return getEntity (id, finder);
    }

    public static List<Compartment> getCompartments (int top, int skip, String filter) {
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

    public static Result edits (Long id) {
        return edits (id, Compartment.class);
    }

    public static Result doc (Long id) {
        return doc (id, finder);
    }

    public static Result get (Long id, String expand) {
        return get (id, expand, finder);
    }

    public static Result field (Long id, String path) {
        return field (id, path, finder);
    }

    public static Result create () {
        return create (Compartment.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Compartment.class, finder);
    }
}
