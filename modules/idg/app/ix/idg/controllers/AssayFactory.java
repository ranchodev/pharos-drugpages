package ix.idg.controllers;

import java.util.*;

import play.db.ebean.*;
import play.mvc.Result;

import ix.core.NamedResource;
import ix.idg.models.Assay;
import ix.core.controllers.EntityFactory;

@NamedResource(name="assays",type=Assay.class)
public class AssayFactory extends EntityFactory {
    static final public Model.Finder<Long, Assay> finder = 
        new Model.Finder(Long.class, Assay.class);

    public static Assay getAssay (Long id) {
        return getEntity (id, finder);
    }

    public static List<Assay> getAssays (int top, int skip, String filter) {
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
        return edits (id, Assay.class);
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
        return create (Assay.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Assay.class, finder);
    }
}
