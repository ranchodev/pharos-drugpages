package ix.core.plugins;

import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import java.io.InputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.Serializable;

import play.Logger;
import play.Plugin;
import play.Application;
import play.cache.Cache;
import play.libs.Akka;
import play.db.ebean.Model;
import play.db.ebean.Transactional;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.actor.Inbox;
import akka.actor.Terminated;
import akka.routing.Broadcast;
import akka.routing.RouterConfig;
import akka.routing.FromConfig;
import akka.routing.RoundRobinRouter;
import akka.routing.SmallestMailboxRouter;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.collection.immutable.Iterable;
import scala.collection.JavaConverters;

import chemaxon.formats.MolImporter;
import chemaxon.struc.Molecule;
import chemaxon.util.MolHandler;

import com.avaje.ebean.Ebean;
import com.avaje.ebean.Transaction;

import ix.core.plugins.IxContext;
import ix.core.plugins.PersistenceQueue;
import ix.core.plugins.StructureIndexerPlugin;
import ix.core.models.XRef;
import ix.core.models.Keyword;
import ix.core.models.Payload;
import ix.core.models.Job;
import ix.core.models.Record;
import ix.core.models.Structure;
import ix.core.chem.StructureProcessor;
import ix.core.controllers.JobFactory;
import ix.core.controllers.PayloadFactory;
import ix.utils.Util;

import tripod.chem.indexer.StructureIndexer;

public class StructureProcessorPlugin extends Plugin {
    private static final int AKKA_TIMEOUT = 60000;
        private final Application app;
    private StructureIndexer indexer;
    private PersistenceQueue PQ;
    private IxContext ctx;
    private ActorSystem system;
    private ActorRef processor;
    private Inbox inbox;
    static final Random rand = new Random ();

    static String randomKey (int size) {
        byte[] b = new byte[size];
        rand.nextBytes(b);
        return Util.toHex(b);
    }
    
    public static class PayloadProcessor implements Serializable {
        public final Payload payload;
        public final String id;
        public final String key;
        
        public PayloadProcessor (Payload payload) {
            this.payload = payload;
            this.key = randomKey (10);
            this.id = payload.id + ":" +this.key;
        }
    }

    /**
     * Instead of creating seperate classes for performing the different
     * stages, we're going to do a bad thing by following the convention 
     * that each actor knows which method to call. Effectively we have 
     * the same instance that get passed through the actor pipeline. This
     * goes against the recommendation that a message shouldn't have
     * any state information!
     */
    public static class ReceiverProcessor implements Serializable {
        enum Stage {
            Routing,
            Instrumentation,
            Persisting,
            Done
        }
        
        final StructureReceiver receiver;
        final Molecule mol;
        final String key;
        final StructureIndexer indexer;

        Stage stage = Stage.Routing;
        
        Structure struc;
        StructureReceiver.Status status = StructureReceiver.Status.OK;
        String mesg;
        
        public ReceiverProcessor (Molecule mol, StructureReceiver receiver,
                                  StructureIndexer indexer) {
            this.mol = mol;
            this.receiver = receiver;
            this.indexer = indexer;
            this.key = randomKey (10);
        }

        Stage stage () { return stage; }

        void routes () {
            assert stage == Stage.Routing
                : "Not a valid stage ("+stage+") for routing!";
            // next stage
            stage = Stage.Instrumentation;
        }
        
        void instruments () {
            assert stage == Stage.Instrumentation
                : "Not a valid stage ("+stage+") for instrumentation!";
            
            try {
                if (mol != null) {
                    struc = StructureProcessor.instrument(mol);
                }
                stage = Stage.Persisting;
            }
            catch (Exception ex) {
                error (ex);
            }
        }

        void persists () {
            assert stage == Stage.Persisting
                : "Not a valid stage ("+stage+") for persisting!";
            try {
                if (struc != null) {
                    struc.save();
                    indexer.add(receiver.getSource(), struc.id.toString(), mol);
                }
                stage = Stage.Done;
            }
            catch (Exception ex) {
                ex.printStackTrace();
                error (ex);
            }
        }

        void done () {
            receiver.receive(status, mesg, struc);
        }

        void error (Throwable t) {
            status = StructureReceiver.Status.FAILED;
            mesg = t.getMessage();
            stage = Stage.Done;
        }
    }

    public static class PayloadRecord implements Serializable {
        public final Job job;
        public final Molecule mol;

        public PayloadRecord (Job job, Molecule mol) {
            this.job = job;
            this.mol = mol;
        }
    }

    public static class PersistModel implements Serializable {
        public enum Op { SAVE, UPDATE, DELETE };
        public final Op oper;
        public final Model[] models;

        public PersistModel (Op oper, Model... models) {
            this.oper = oper;
            this.models = models;
        }

        @Transactional
        public void persists () {
            try {
                switch (oper) {
                case SAVE: 
                    for (Model m : models) {
                        m.save();
                    }
                    break;
                case UPDATE:
                    for (Model m : models) {
                        m.update();
                    }
                    break;
                case DELETE: 
                    for (Model m : models) {
                        m.delete();
                    }
                    break;
                }
            }
            catch (Throwable t) {
                t.printStackTrace();
            }
        }
        
        public static PersistModel Update (Model... models) {
            return new PersistModel (Op.UPDATE, models);
        }
        public static PersistModel Save (Model... models) {
            return new PersistModel (Op.SAVE, models);
        }
        public static PersistModel Delete (Model... models) {
            return new PersistModel (Op.DELETE, models);
        }
    }

    public static class PersistRecord implements Serializable {
        public final Structure struc;
        public final Record rec;
        final Molecule mol;
        final StructureIndexer indexer;
        
        public PersistRecord (Structure struc, Molecule mol,
                              Record rec, StructureIndexer indexer) {
            this.struc = struc;
            this.rec = rec;
            this.mol = mol;
            this.indexer = indexer;
        }
        
        @Transactional
        public void persists () {
            try {
                if (struc != null) {
                    struc.save();
                    indexer.add(rec.job.payload.name,
                                struc.id.toString(), mol);
                    rec.xref = new XRef (struc);
                    rec.xref.save();
                }
                rec.save();
                Logger.debug("Saved struc "+(struc != null ? struc.id:null)
                             +" record "+rec.id);
            }
            catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }

    /**
     * This actor runs in a bounded queue to ensure we don't have issues
     * with locking due to database persistence
     */
    public static class Reporter extends UntypedActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);
        final PersistenceQueue PQ;

        public Reporter (PersistenceQueue PQ) {
            this.PQ = PQ;
        }

        public void onReceive (Object mesg) {
            if (mesg instanceof PersistModel) {
                PQ.submit(new PersistModelWorker ((PersistModel)mesg));
            }
            else if (mesg instanceof PersistRecord) {
                PQ.submit(new PersistRecordWorker ((PersistRecord)mesg));
            }
            else if (mesg instanceof ReceiverProcessor) {
                PQ.submit
                    (new ReceiverProcessorWorker ((ReceiverProcessor)mesg));
            }
            else {
                log.info("unhandled mesg: sender="+sender()+" mesg="+mesg);
                unhandled (mesg);
            }
        }
    }

    static class PersistModelWorker
        implements PersistenceQueue.PersistenceContext {
        PersistModel model;
        PersistModelWorker (PersistModel model) {
            this.model = model;
        }
        public void persists () throws Exception {
            model.persists();
        }
        public Priority priority () { return Priority.MEDIUM; }
    }
    
    static class PersistRecordWorker
        implements PersistenceQueue.PersistenceContext {
        PersistRecord record;
        PersistRecordWorker (PersistRecord record) {
            this.record = record;
        }
        public void persists () throws Exception {
            record.persists();
        }
        public Priority priority () { return Priority.MEDIUM; }
    }
    
    static class ReceiverProcessorWorker
        implements PersistenceQueue.PersistenceContext {
        ReceiverProcessor receiver;
        ReceiverProcessorWorker (ReceiverProcessor receiver) {
            this.receiver = receiver;
        }
        public void persists () throws Exception {
            switch (receiver.stage()) {
            case Persisting:
                receiver.persists();
                // fall through
                
            case Done:
                receiver.done();
                break;
                
            default:
                assert false: "Stage "+receiver.stage()+" shouldn't be "
                    +"run in "+getClass().getName()+"!";
            }
        }
        public Priority priority () { return Priority.MEDIUM; }
    }
    
    public static class Processor extends UntypedActor {
        LoggingAdapter log = Logging.getLogger(getContext().system(), this);
        ActorRef reporter = context().actorFor("/user/reporter");
        StructureIndexer indexer;

        public Processor (StructureIndexer indexer) {
            this.indexer = indexer;
        }
        
        public void onReceive (Object mesg) {
            if (mesg instanceof PayloadProcessor) {
                PayloadProcessor payload = (PayloadProcessor)mesg;
                log.info("Received payload "+payload.id);

                // now spawn child processor to proces the payload stream
                ActorRef child = null;
                Collection<ActorRef> children = JavaConverters
                    .asJavaCollectionConverter(context().children())
                    .asJavaCollection();
                for (ActorRef ref : children) {
                    if (ref.path().name()
                        .indexOf(payload.payload.id.toString()) >= 0) {
                        // this child is current running
                        child = ref;
                    }
                }
                
                if (child != null) {
                    // the given payload is currently processing
                    // at the moment!
                    Job job = new Job ();
                    job.keys.add(new Keyword
                                 (StructureProcessorPlugin.class.getName(),
                                  payload.key));
                    job.status = Job.Status.NOT_RUN;
                    job.message = "Payload "+payload.payload.id+" is "
                        +"currently being processed.";
                    job.payload = payload.payload;
                    reporter.tell(PersistModel.Save(job), self ());
                }
                else {
                    child = context().actorOf
                        (Props.create(Processor.class, indexer).withRouter
                         (new FromConfig().withFallback
                          (new SmallestMailboxRouter (2))), payload.id);
                    context().watch(child);

                    try {
                        Job job = process
                            (reporter, child, self (), payload);
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    child.tell(new Broadcast (PoisonPill.getInstance()),
                               self ());
                }
            }
            else if (mesg instanceof ReceiverProcessor) {
                ReceiverProcessor receiver = (ReceiverProcessor)mesg;
                switch (receiver.stage()) {
                case Routing:
                    {
                        ActorRef actor = context().actorOf
                            (Props.create(Processor.class, indexer).withRouter
                             (new FromConfig().withFallback
                              (new SmallestMailboxRouter (1))), receiver.key);
                        context().watch(actor);
                        try {
                            // submit for
                            receiver.routes();
                            actor.tell(mesg, self ()); // forward to next stage
                        }
                        catch (Exception ex) {
                            ex.printStackTrace();
                            // notify the receiver we can't process the input
                            receiver.error(ex);
                            reporter.tell(mesg, self ());
                        }
                        actor.tell(new Broadcast
                                   (PoisonPill.getInstance()), self ());
                    }
                    break;
                    
                case Instrumentation:
                    receiver.instruments();
                    reporter.tell(mesg, self ()); 
                    break;

                default:
                    assert false : "Stage "+receiver.stage()+" shouldn't "
                        +"be running in "+getClass().getName()+"!";
                }
            }
            else if (mesg instanceof PayloadRecord) {
                PayloadRecord pr = (PayloadRecord)mesg;
                //log.info("processing "+pr.record.getName());
                Record rec = new Record ();
                rec.name = pr.mol.getName();
                rec.job = pr.job;
                Structure struc = null;
                try {
                    struc = StructureProcessor.instrument(pr.mol);
                    rec.status = Record.Status.OK;
                }
                catch (Throwable t) {
                    rec.status = Record.Status.FAILED;
                    rec.message = t.getMessage();
                    t.printStackTrace();
                }
                
                reporter.tell
                    (new PersistRecord (struc, pr.mol, rec, indexer), self ());
            }
            else if (mesg instanceof Terminated) {
                ActorRef actor = ((Terminated)mesg).actor();
                
                String id = actor.path().name();
                int pos = id.indexOf(':');
                if (pos > 0) {
                    String jid = id.substring(pos+1);
                    try {                   
                        Job job = JobFactory.getJob(jid);
                        if (job != null) {
                            job.status = Job.Status.COMPLETE;
                            reporter.tell
                                (PersistModel.Update(job), self ());
                            log.info("done processing job {}!", jid);
                        }
                        else {
                            log.error("Failed to retrieve job "+jid);
                        }
                    }
                    catch (Exception ex) {
                        ex.printStackTrace();
                        log.error("Failed to retrieve job "
                                  +jid+"; "+ex.getMessage());
                    }
                }
                else {
                    // receiver job
                    //log.error("Invalid job id: "+id);
                }
                context().unwatch(actor);               
            }
            else {
                unhandled (mesg);
            }
        }
    }

    public StructureProcessorPlugin (Application app) {
        this.app = app;
    }

    @Override
    public void onStart () {
        ctx = app.plugin(IxContext.class);
        if (ctx == null)
            throw new IllegalStateException
                ("IxContext plugin is not loaded!");
        
        StructureIndexerPlugin plugin =
            app.plugin(StructureIndexerPlugin.class);
        if (plugin == null)
            throw new IllegalStateException
                ("StructureIndexerPlugin is not loaded!");
        indexer = plugin.getIndexer();

        PQ = app.plugin(PersistenceQueue.class);
        if (PQ == null)
            throw new IllegalStateException
                ("Plugin PersistenceQueue is not laoded!");
        
        system = ActorSystem.create("StructureProcessor");
        Logger.info("Plugin "+getClass().getName()
                    +" initialized; Akka version "+system.Version());
        RouterConfig config = new SmallestMailboxRouter (2);
        processor = system.actorOf
            (Props.create(Processor.class, indexer).withRouter
             (new FromConfig().withFallback(config)), "processor");
        system.actorOf(Props.create(Reporter.class, PQ), "reporter");
        
        long start= System.currentTimeMillis();
        while(true){
            try{
                inbox = Inbox.create(system);
                break;
            }catch(Exception e){
                Logger.error(e.getMessage() + " retrying");
            }
            if(System.currentTimeMillis()>start+StructureProcessorPlugin.AKKA_TIMEOUT){
                throw new IllegalStateException("Couldn't start akka");
            }
            try{
                Thread.sleep(10);
            }catch(Exception e){
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onStop () {
        if (system != null)
            system.shutdown();
        Logger.info("Plugin "+getClass().getName()+" stopped!");
    }

    public boolean enabled () { return true; }
    public String submit (Payload payload) {
        // first see if this payload has already processed..
        PayloadProcessor pp = new PayloadProcessor (payload);
        inbox.send(processor, pp);
        return pp.key;
    }

    public void submit (String struc, StructureReceiver receiver) {
        try {
            MolHandler mh = new MolHandler (struc);
            submit (mh.getMolecule(), receiver);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            throw new IllegalArgumentException
                ("Unable to parse input structure: "+struc);
        }
    }

    public void submit (Molecule mol, StructureReceiver receiver) {
        inbox.send(processor, new ReceiverProcessor (mol, receiver, indexer));
    }

    /**
     * This is so as to give the user access to the same persistence 
     * queue as the processor to prevent deadlocks
     */
    public void submit (StructureReceiver receiver) {
        inbox.send(processor, new ReceiverProcessor (null, receiver, indexer));
    }

    /**
     * batch processing
     */
    static Job process (ActorRef reporter,
                        ActorRef proc, ActorRef sender,
                        PayloadProcessor pp) throws Exception {
        List<Job> jobs = JobFactory.getJobsByPayload
            (pp.payload.id.toString());
        Job job = null;       
        if (jobs.isEmpty()) {
            job = new Job ();
            job.keys.add(new Keyword
                         (StructureProcessorPlugin.class.getName(), pp.key));
            job.status = Job.Status.RUNNING;
            job.payload = pp.payload;
            try {
                InputStream is = PayloadFactory.getStream(pp.payload);      
                MolImporter mi = new MolImporter (is);
                int total = 0;
                for (Molecule m; (m = mi.read()) != null; ++total) {
                    proc.tell(new PayloadRecord (job, m), sender);
                }
                mi.close();
            }
            catch (Throwable t) {
                job.message = t.getMessage();
                job.status = Job.Status.FAILED;
                Logger.trace("Failed to process payload "+pp.payload.id, t);
            }
            finally {
                reporter.tell(PersistModel.Save(job), sender);
            }
        }
        else {
            job = jobs.iterator().next();
            job.keys.add(new Keyword
                         (StructureProcessorPlugin.class.getName(), pp.key));
            reporter.tell(PersistModel.Update(job), sender);
        }
        return job;
    }
}
