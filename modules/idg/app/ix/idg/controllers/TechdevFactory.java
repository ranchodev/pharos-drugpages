package ix.idg.controllers;

import java.util.*;
import play.db.ebean.*;
import play.mvc.Result;

import ix.core.NamedResource;
import ix.idg.models.Techdev;
import ix.core.controllers.EntityFactory;

@NamedResource(name="techdev",type=Techdev.class)
public class TechdevFactory extends EntityFactory {
    static final public Model.Finder<Long, Techdev> finder = 
        new Model.Finder(Long.class, Techdev.class);

    public static Techdev getTechdev (Long id) {
        return getEntity (id, finder);
    }

    public static List<Techdev> getTechdevs (int top, int skip, String filter) {
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
        return edits (id, Techdev.class);
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
        return create (Techdev.class, finder);
    }

    public static Result delete (Long id) {
        return delete (id, finder);
    }

    public static Result update (Long id, String field) {
        return update (id, field, Techdev.class, finder);
    }
}
