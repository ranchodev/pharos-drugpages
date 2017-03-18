package ix.core.controllers;

import ix.core.NamedResource;
import ix.core.controllers.EntityFactory.FetchOptions;
import ix.core.models.Job;
import ix.core.models.Record;

import java.util.List;
import java.util.concurrent.ExecutionException;

import com.avaje.ebean.FutureRowCount;
import com.fasterxml.jackson.databind.JsonNode;
import play.db.ebean.Model;
import play.mvc.Result;

@NamedResource(name="jobs",
               type=Job.class,
               description="Resource for handling processing jobs")
public class JobFactory extends EntityFactory {
    public static final Model.Finder<Long, Job> finder =
        new Model.Finder(Long.class, Job.class);
    public static final Model.Finder<Long, Record> recordFinder =
        new Model.Finder(Long.class, Record.class);

    public static Job getJob (Long id) {
        return getEntity (id, finder);
    }
    
    public static List<Record> getJobRecords (Long id) {      
        return recordFinder.where().eq("job.id", id).findList();
    }

    public static List<Job> getJobsByPayload (String uuid) {
        return finder.setDistinct(false)
            .where().eq("payload.id", uuid)
            .orderBy("created desc").findList();
    }
    public static List<Job> getJobs
        (int top, int skip, String filter) {
        return filter (new FetchOptions (top, skip, filter), finder);
    }

    public static Job getJob (String key) {
        //finder.setDistinct(false).where().eq("keys.term", key).findUnique();
        
        // This is because the built SQL for oracle includes a "DISTINCT"
        // statement, which doesn't appear to be extractable.
        List<Job> gotJobsv= finder.findList();
        for (Job pj : gotJobsv) {
            if(pj.hasKey(key)) return pj;
        }
        return null;
    }
    
    public static Integer getCount () 
        throws InterruptedException, ExecutionException {
        return JobFactory.getCount(finder);
    }
    
    public static Result count () { return count (finder); }
    
    public static Result page (int top, int skip, String filter) {
        return page (top, skip, filter, finder);
    }

    public static Result get (Long id, String select) {
        return get (id, select, finder);
    }

    public static Result field (Long id, String path) {
        if (path.equals("records")) {
            return ok ((JsonNode)getEntityMapper()
                       .valueToTree(getJobRecords (id)));
        }
        return field (id, path, finder);
    }

    public static Result create () {
        throw new UnsupportedOperationException
            ("create operation not supported!");
    }

    public static Result delete (Long id) {
        throw new UnsupportedOperationException
            ("delete operation not supported!");
    }
}

