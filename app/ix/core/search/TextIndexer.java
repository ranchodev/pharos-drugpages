package ix.core.search;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ix.core.models.DynamicFacet;
import ix.core.models.Indexable;
import ix.core.plugins.IxCache;
import ix.utils.Global;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.facet.*;
import org.apache.lucene.facet.taxonomy.FastTaxonomyFacetCounts;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyReader;
import org.apache.lucene.facet.taxonomy.directory.DirectoryTaxonomyWriter;
import org.apache.lucene.index.*;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.queries.TermFilter;
import org.apache.lucene.queries.TermsFilter;
import org.apache.lucene.queries.ChainedFilter;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.suggest.DocumentDictionary;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.store.NoLockFactory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.reflections.Reflections;
import play.Logger;
import play.Play;
import play.db.ebean.Model;

import javax.persistence.Entity;
import javax.persistence.Id;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.*;

import static org.apache.lucene.document.Field.Store.NO;
import static org.apache.lucene.document.Field.Store.YES;

/**
 * Singleton class that responsible for all entity indexing
 */
public class TextIndexer {
    protected static final String STOP_WORD = " THE_STOP";
    protected static final String START_WORD = "THE_START ";
    protected static final String GIVEN_STOP_WORD = "$";
    protected static final String GIVEN_START_WORD = "^";

    @Indexable
    static final class DefaultIndexable {}
    static final Indexable defaultIndexable = 
        (Indexable)DefaultIndexable.class.getAnnotation(Indexable.class);

    /**
     * well known fields
     */
    public static final String FIELD_KIND = "__kind";
    public static final String FIELD_ID = "id";

    /**
     * these default parameters should be configurable!
     */
    public static final int CACHE_TIMEOUT = 60*60*24; // 24 hours
    public static final int FETCH_WORKERS = 4; // number of fetch workers

    /**
     * Make sure to properly update the code when upgrading version
     */
    static final Version LUCENE_VERSION = Version.LATEST;
    static final String FACETS_CONFIG_FILE = "facet_conf.json";
    static final String SUGGEST_CONFIG_FILE = "suggest_conf.json";
    static final String SORTER_CONFIG_FILE = "sorter_conf.json";
    static final String DIM_CLASS = "ix.Class";

    static final DateFormat YEAR_DATE_FORMAT = new SimpleDateFormat ("yyyy");
    static final SearchResultPayload POISON_PAYLOAD = new SearchResultPayload ();
    static final Object POISON_PILL = new Object ();

    static final FieldType TermVectorFieldType = new FieldType ();
    static {
        TermVectorFieldType.setIndexed(true);
        TermVectorFieldType.setTokenized(false);
        TermVectorFieldType.setStoreTermVectors(true);
        TermVectorFieldType.setStoreTermVectorPositions(false);
        TermVectorFieldType.freeze();
    }

    static class TermVectorField extends org.apache.lucene.document.Field {
        TermVectorField (String field, String value) {
            super (field, value, TermVectorFieldType);
        }
    }

    public static class TermVectors implements java.io.Serializable {
        static private final long serialVersionUID = 0x192464a5d08ea528l;
        
        Class kind;     
        String field;
        int numDocs;
        List<Map> docs = new ArrayList<Map>();
        Map<String, Map> terms = new TreeMap<String,Map>();
        Map<String, String> filters = new TreeMap<String, String>();
        
        TermVectors (Class kind, String field) {
            this.kind = kind;
            this.field = field;
        }

        public Class getKind () { return kind; }
        public String getField () { return field; }

        public Map<String, String> getFilters () { return filters; }
        public Map<String, Map> getTerms () { return terms; }
        public List<Map> getDocs () { return docs; }    
        public int getNumDocs () { return numDocs; }
        public int getNumDocsWithTerms () { return docs.size(); }
        public int getNumTerms () { return terms.size(); }
        public Integer getTermCount (String term) {
            Map map = terms.get(term);
            Integer count = null;
            if (map != null) {
                count = (Integer)map.get("nDocs");
            }
            return count;
        }
    }

    class TermVectorsCollector extends Collector {
        private int docBase;
        private TermsEnum termsEnum;
        private IndexReader reader;     
        private Class idType;
        private TermVectors tvec;
        private Map<String, Set> counts;
        
        TermVectorsCollector (Class kind, String field) throws IOException {
            this (kind, field, new Term[0]);
        }

        TermVectorsCollector (Class kind, String field, Term... terms)
            throws IOException {
            tvec = new TermVectors (kind, field);
            counts = new TreeMap<String, Set>();
            
            idType = String.class;
            for (Field f : kind.getFields()) {
                if (null != f.getAnnotation(Id.class)) {
                    idType = f.getType();
                    break;
                }
            }
            
            IndexSearcher searcher = getSearcher ();        
            this.reader = searcher.getIndexReader();

            TermQuery tq = new TermQuery
                (new Term (FIELD_KIND, kind.getName()));
            
            Filter filter = null;
            if (terms != null && terms.length > 0) {
                if (terms.length == 1) {
                    filter = new TermFilter (terms[0]);
                    tvec.filters.put(terms[0].field(), terms[0].text());
                }
                else {
                    Filter[] filters = new TermFilter[terms.length];
                    for (int i = 0; i < terms.length; ++i) {
                        filters[i] = new TermFilter (terms[i]);
                        tvec.filters.put(terms[i].field(), terms[i].text());
                    }
                    filter = new ChainedFilter (filters, ChainedFilter.AND);
                }
            }
            searcher.search(tq, filter, this);

            Collections.sort(tvec.docs, new Comparator<Map>() {
                    public int compare (Map m1, Map m2) {
                        Integer c1 = (Integer)m1.get("nTerms");
                        Integer c2 = (Integer)m2.get("nTerms");
                        return c2 - c1;
                    }
                });

            for (Map.Entry<String, Set> me : counts.entrySet()) {
                Map map = new HashMap ();
                map.put("docs", me.getValue().toArray(new Object[0]));
                map.put("nDocs", me.getValue().size());
                tvec.terms.put(me.getKey(), map);
            }
            counts = null;
        }
        
        public void setScorer (Scorer scorer) {
        }
        
        public boolean acceptsDocsOutOfOrder () { return true; }
        public void collect (int doc) {
            int docId = docBase + doc;
            try {
                Terms docterms = reader.getTermVector(docId, tvec.field);
                if (docterms != null) {
                    Document d = reader.document(docId);                    
                    Object id = d.get(tvec.kind.getName()+"._id");
                    if (Long.class.isAssignableFrom(idType)) {
                        try {
                            id = Long.parseLong(id.toString());
                        }
                        catch (NumberFormatException ex) {
                            Logger.error("Bogus id: "+id, ex);
                        }
                    }
                    
                    //Logger.debug("+++++ doc "+docId+" +++++");
                    List<String> terms = new ArrayList<String>();
                    for (TermsEnum en = docterms.iterator(termsEnum);
                         en.next() != null;) {
                        String term = en.term().utf8ToString();
                        Set docs = counts.get(term);
                        if (docs == null) {
                            counts.put(term, docs = new HashSet ());
                        }
                        docs.add(id);
                        terms.add(term);
                    }
                    
                    Map map = new HashMap ();
                    map.put("doc", id);
                    map.put("terms", terms.toArray(new String[0]));
                    map.put("nTerms", terms.size());
                    tvec.docs.add(map);
                }
                else {
                    //Logger.debug("No term vector for field \""+field+"\"!");
                }
                ++tvec.numDocs;
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        
        public void setNextReader (AtomicReaderContext ctx) {
            docBase = ctx.docBase;
        }

        public TermVectors termVectors () { return tvec; }
    }
    
    public static class FV implements java.io.Serializable {
        String label;
        Integer count;

        FV (String label, Integer count) {
            this.label = label;
            this.count = count;
        }
        public String getLabel () { return label; }
        public Integer getCount () { return count; }
    }

    public interface FacetFilter {
        boolean accepted (FV fv);
    }

    public static class Facet implements java.io.Serializable {
        String name;
        List<FV> values = new ArrayList<FV>();

        public Facet (String name) { this.name = name; }
        public String getName () { return name; }
        public List<FV> getValues () {
            return values; 
        }
        public int size () { return values.size(); }

        public FV getValue (int index) { return values.get(index); }
        public String getLabel (int index) {
            return values.get(index).getLabel();
        }
        public Integer getCount (int index) {
            return values.get(index).getCount();
        }
        public Integer getCount (String label) {
            for (FV fv : values)
                if (fv.label.equalsIgnoreCase(label))
                    return fv.count;
            return null;
        }
        
        public void sort () {
            sortCounts (true);
        }

        public Facet filter (FacetFilter filter) {
            Facet filtered = new Facet (name);
            for (FV fv : values)
                if (filter.accepted(fv))
                    filtered.values.add(fv);
            return filtered;
        }

        public void sortLabels (final boolean desc) {
            Collections.sort(values, new Comparator<FV>() {
                    public int compare (FV v1, FV v2) {
                        return desc ? v2.label.compareTo(v1.label)
                            : v1.label.compareTo(v2.label);
                    }
                });
        }
        
        public void sortCounts (final boolean desc) {
            Collections.sort(values, new Comparator<FV> () {
                    public int compare (FV v1, FV v2) {
                        int d = desc ? (v2.count - v1.count)
                            : (v1.count-v2.count);
                        if (d == 0)
                            d = v1.label.compareTo(v2.label);
                        return d;
                    }
                });
        }

        @JsonIgnore
        public ArrayList<String> getLabelString () {
            ArrayList<String> strings = new ArrayList<String>();
            for (int i = 0; i<values.size(); i++) {
                String label = values.get(i).getLabel();
                strings.add(label);
            }
            return strings;
        }
        
        @JsonIgnore
        public ArrayList <Integer> getLabelCount () {
            ArrayList<Integer> counts = new ArrayList<Integer>();
            for(int i = 0; i<values.size(); i++){
                int count = values.get(i).getCount();
                counts.add(count);
            }
            return counts;
        }
    }

    public static class SearchResult /*implements java.io.Serializable*/ {

        String key;
        String query;
        List<Facet> facets = new ArrayList<Facet>();
        //final List matches = new CopyOnWriteArrayList ();
        final BlockingQueue matches = new LinkedBlockingQueue ();
        int count;
        SearchOptions options;
        final long timestamp = System.currentTimeMillis();
        AtomicLong stop = new AtomicLong ();

        transient ReentrantLock lock = new ReentrantLock ();
        transient ReentrantLock wlck = new ReentrantLock (); // write lock
        transient Condition finished = lock.newCondition();
        transient Set<String> keys = new HashSet<String>();

        SearchResult () {}
        SearchResult (SearchOptions options, String query) {
            this.options = options;
            this.query = query;            
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getQuery () { return query; }
        public SearchOptions getOptions () { return options; }
        public List<Facet> getFacets () { return facets; }
        public Facet getFacet (String name) {
            for (Facet f : facets) {
                if (name.equalsIgnoreCase(f.getName()))
                    return f;
            }
            return null;
        }
        public int size () { return matches.size(); }
        public Object get (int index) {
            throw new UnsupportedOperationException
                ("get(index) is no longer supported; please use copyTo()");
        }
        // fill the given list with value starting at start up to start+count
        public int copyTo (List list, int start, int count) {
            wlck.lock();
            try {
                if (start >= matches.size()) {
                    return 0;
                }
                
                Iterator it = matches.iterator();
                int i = 0;
                for (; i < start && it.hasNext(); ++i)
                    it.next(); // skip
                
                for (i = 0; i < count && it.hasNext(); ++i) {
                    list.add(it.next());
                }
                return i;
            }
            finally {
                wlck.unlock();
            }
        }
        
        public List getMatches () {
            wlck.lock();
            try {
                return new ArrayList (matches);
            }
            finally {
                wlck.unlock();
            }
        }
        public List getMatchesAndWaitIfNotFinished () {
            lock.lock();
            try {
                while (!finished())
                    finished.await();
                return getMatches ();
            }
            catch (InterruptedException ex) {
                ex.printStackTrace();
                return getMatches ();
            }
            finally {
                lock.unlock();
            }
        }

        public void updateCacheWhenComplete (String... keys) {
            for (String key : keys) {
                this.keys.add(key);
            }
        }
        
        public boolean isEmpty () { return matches.isEmpty(); }
        public int count () { return count; }
        public long getTimestamp () { return timestamp; }
        public long elapsed () { return stop.get() - timestamp; }
        public long getStopTime () { return stop.get(); }
        public boolean finished () { return stop.get() >= timestamp; }
        
        public SearchContextAnalyzer getSearchContextAnalyzer(){
            return null;
        }

        protected void add (Object obj) {
            wlck.lock();
            try {
                matches.add(obj);
            }
            finally {
                wlck.unlock();
            }
        }
        
        protected void done () {
            lock.lock();
            try {
                stop.set(System.currentTimeMillis());
                for (String key : keys) {
                    IxCache.set(key, this);
                }
                finished.signal();
            }
            finally {
                lock.unlock();
            }
        }

        private void writeObject(java.io.ObjectOutputStream out)
            throws IOException {
            wlck.lock();
            try {
                out.defaultWriteObject();
            }
            finally {
                wlck.unlock();
            }
        }
        
        private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
            if (wlck == null) {
                lock = new ReentrantLock ();
                wlck = new ReentrantLock ();
                finished = lock.newCondition();
                keys = new HashSet<String>();
            }
            
            wlck.lock();
            try {
                in.defaultReadObject();
            }
            finally {
                wlck.unlock();
            }
        }
        
        private void readObjectNoData() throws ObjectStreamException {
        }
    }

    public static class SuggestResult {
        CharSequence key, highlight;
        SuggestResult (CharSequence key, CharSequence highlight) {
            this.key = key;
            this.highlight = highlight;
        }

        public CharSequence getKey () { return key; }
        public CharSequence getHighlight () { return highlight; }
    }

    class SuggestLookup {
        String name;
        File dir;
        AtomicInteger dirty = new AtomicInteger ();
        AnalyzingInfixSuggester lookup;
        long lastRefresh;

        SuggestLookup (File dir) throws IOException {
            boolean isNew = false;
            if (!dir.exists()) {
                dir.mkdirs();
                isNew = true;
            }
            else if (!dir.isDirectory()) 
                throw new IllegalArgumentException ("Not a directory: "+dir);

            lookup = new AnalyzingInfixSuggester 
                (LUCENE_VERSION, new NIOFSDirectory 
                 (dir, NoLockFactory.getNoLockFactory()), indexAnalyzer);
            
            //If there's an error getting the index count, it probably wasn't 
            //saved properly. Treat it as new if an error is thrown.
            if (!isNew) {
                try{
                    lookup.getCount();
                }
                catch (Exception e) {
                    isNew=true;
                    Logger.warn("Error building lookup " + dir.getName()
                                + " will reinitialize");
                }
            }
            
            if (isNew) {
                Logger.debug("Initializing lookup "+dir.getName());
                build ();
            }
            else {
                Logger.debug(lookup.getCount()
                             +" entries loaded for "+dir.getName());
            }

            this.dir = dir;
            this.name = dir.getName();
        }

        SuggestLookup (String name) throws IOException {
            this (new File (suggestDir, name));
        }

        void add (BytesRef text, Set<BytesRef> contexts, 
                  long weight, BytesRef payload) throws IOException { 
            lookup.update(text, contexts, weight, payload);
            incr ();
        }

        void add (String text) throws IOException {
            BytesRef ref = new BytesRef (text);
            lookup.update(ref, null, 0, ref);
            incr ();
        }

        void incr ()  {
            dirty.incrementAndGet();
        }

        synchronized void refresh () throws IOException {
            long start = System.currentTimeMillis();
            lookup.refresh();
            lastRefresh = System.currentTimeMillis();
            Logger.debug(lookup.getClass().getName()
                         +" refreshs "+lookup.getCount()+" entries in "
                         +String.format("%1$.2fs", 
                                        1e-3*(lastRefresh - start)));
            dirty.set(0);
        }

        void close () throws IOException {
            lookup.close();
        }

        long build () throws IOException {
            IndexReader reader = DirectoryReader.open(indexWriter, true);
            // now weight field
            long start = System.currentTimeMillis();
            lookup.build(new DocumentDictionary (reader, name, null));
            long count = lookup.getCount();
            Logger.debug(lookup.getClass().getName()
                         +" builds "+count+" entries in "
                         +String.format("%1$.2fs", 
                                        1e-3*(System.currentTimeMillis()
                                              - start)));
            return count;
        }

        List<SuggestResult> suggest (CharSequence key, int max)
            throws IOException {
            if (dirty.get() > 0)
                refresh ();

            List<Lookup.LookupResult> results = lookup.lookup
                (key, null, false, max);

            List<SuggestResult> m = new ArrayList<SuggestResult>();
            for (Lookup.LookupResult r : results) {
                m.add(new SuggestResult (r.payload.utf8ToString(), r.key));
            }

            return m;
        }
    }

    static class SearchResultPayload {
        SearchResult result;
        TopDocs hits;
        IndexSearcher searcher;
        Map<String, Model.Finder> finders =
            new HashMap<String, Model.Finder>();
        SearchOptions options;
        int total, offset;
        
        SearchResultPayload () {}
        SearchResultPayload (SearchResult result, TopDocs hits,
                             IndexSearcher searcher) {
            this.result = result;
            this.hits = hits;
            this.searcher = searcher;
            this.options = result.options;
            result.count = hits.totalHits; 
            total = Math.max(0, Math.min(options.max(), result.count));
            offset = Math.min(options.skip, total);
        }

        void fetch () throws IOException {
            try {
                fetch (total);
            }
            finally {
                result.done();
            }
        }

        Object findObject (IndexableField kind, IndexableField id)
            throws Exception {
            
            Number n = id.numericValue();
            Object value = null;
            
            Model.Finder finder = finders.get(kind.stringValue());
            if (finder == null) {
                Class c = n != null ? Long.class : String.class;
                finder = new Model.Finder
                    (c, Class.forName(kind.stringValue()));
                finders.put(kind.stringValue(), finder);
            }
            
            if (options.expand.isEmpty()) {
                value = finder.byId(n != null
                                    ? n.longValue() : id.stringValue());
            }
            else {
                com.avaje.ebean.Query ebean = finder.setId
                    (n != null ? n.longValue() : id.stringValue());
                for (String path : options.expand)
                    ebean = ebean.fetch(path);
                value = ebean.findUnique();
            }
                    
            if (value == null) {
                Logger.warn
                    (kind.stringValue()+":"+id
                     +" not available in persistence store!");
            }

            return value;
        }
        
            
        void fetch (int size)  throws IOException {
            size = Math.min(options.top, Math.min(total - offset, size));
            for (int i = result.size(); i < size; ++i) {
                Document doc = searcher.doc(hits.scoreDocs[i+offset].doc);
                final IndexableField kind = doc.getField(FIELD_KIND);
                if (kind != null) {
                    String field = kind.stringValue()+"._id";
                    final IndexableField id = doc.getField(field);
                    if (id != null) {
                        if (DEBUG (2)) {
                            Logger.debug("++ matched doc "
                                         +field+"="+id.stringValue());
                        }
                        
                        try {
                            /*
                            Object value = IxCache.getOrElse
                                (field+":"+id.stringValue(), new Callable () {
                                        public Object call () throws Exception {
                                            return findObject (kind, id);
                                        }
                                    });
                            */
                            Object value = findObject (kind, id);
                            if (value != null)
                                result.add(value);
                        }
                        catch (Exception ex) {
                            Logger.trace("Can't locate object "
                                         +field+":"+id.stringValue(), ex);
                        }
                    }
                    else {
                        Logger.error("Index corrupted; document "
                                     +"doesn't have field "+field);
                    }
                }
            }
        }
    }
        
    class FetchWorker implements Runnable {
        FetchWorker () {
        }

        public void run () {
            Logger.debug(Thread.currentThread()
                         +": FetchWorker started at "+new Date ());
            try {
                for (SearchResultPayload payload; (payload = fetchQueue.take())
                         != POISON_PAYLOAD
                         && !Thread.currentThread().isInterrupted(); ) {
                    try {
                        long start = System.currentTimeMillis();
                        Logger.debug(Thread.currentThread()
                                     +": fetching payload "
                                     +payload.hits.totalHits
                                     +" for "+payload.result);
                        payload.fetch();
                        Logger.debug(Thread.currentThread()+": ## fetched "
                                     +payload.result.size()
                                     +" for result "+payload.result
                                     +" in "+String.format
                                     ("%1$dms", 
                                      System.currentTimeMillis()-start));
                    }
                    catch (IOException ex) {
                        Logger.error("Error in processing payload", ex);
                    }
                }
                Logger.debug(Thread.currentThread()
                             +": FetchWorker stopped at "+new Date());
            }
            catch (Exception ex) {
                //ex.printStackTrace();
                Logger.trace(Thread.currentThread()+" stopped", ex);
            }
        }
    }
    
    private File baseDir;
    private File suggestDir;
    private Directory indexDir;
    private Directory taxonDir;
    private IndexWriter indexWriter;
    private DirectoryReader indexReader;
    private Analyzer indexAnalyzer;
    private DirectoryTaxonomyWriter taxonWriter;
    private FacetsConfig facetsConfig;
    private ConcurrentMap<String, SuggestLookup> lookups;
    private ConcurrentMap<String, SortField.Type> sorters;
    private AtomicLong lastModified = new AtomicLong ();
    
    private ExecutorService threadPool = Executors.newCachedThreadPool();
    private Future[] fetchWorkers;
    private BlockingQueue<SearchResultPayload> fetchQueue =
        new LinkedBlockingQueue<SearchResultPayload>();
        
    static ConcurrentMap<File, TextIndexer> indexers = 
        new ConcurrentHashMap<File, TextIndexer>();


    public IndexReader getIndexReader() {
        return indexReader;
    }

    public static TextIndexer getInstance (File baseDir) throws IOException {
        if (indexers.containsKey(baseDir)) 
            return indexers.get(baseDir);

        try {
            TextIndexer indexer = new TextIndexer (baseDir);
            TextIndexer old = indexers.putIfAbsent(baseDir, indexer);
            return old == null ? indexer : old;
        }
        catch (IOException ex) {
            return indexers.get(baseDir);
        }
    }

    private TextIndexer () {
        setFetchWorkers (FETCH_WORKERS);
    }
    
    public TextIndexer (File dir) throws IOException {
        if (!dir.isDirectory())
            throw new IllegalArgumentException ("Not a directory: "+dir);

        File index = new File (dir, "index");
        if (!index.exists())
            index.mkdirs();
        indexDir = new NIOFSDirectory 
            (index, NoLockFactory.getNoLockFactory());

        File taxon = new File (dir, "facet");
        if (!taxon.exists())
            taxon.mkdirs();
        taxonDir = new NIOFSDirectory
            (taxon, NoLockFactory.getNoLockFactory());

        indexAnalyzer = createIndexAnalyzer ();
        IndexWriterConfig conf = new IndexWriterConfig 
            (LUCENE_VERSION, indexAnalyzer);
        indexWriter = new IndexWriter (indexDir, conf);
        indexReader = DirectoryReader.open(indexWriter, true);  
        taxonWriter = new DirectoryTaxonomyWriter (taxonDir);

        facetsConfig = loadFacetsConfig (new File (dir, FACETS_CONFIG_FILE));
        if (facetsConfig == null) {
            int size = taxonWriter.getSize();
            if (size > 0) {
                Logger.warn("There are "+size+" dimensions in "
                            +"taxonomy but no facet\nconfiguration found; "
                            +"facet searching might not work properly!");
            }
            facetsConfig = new FacetsConfig ();
            facetsConfig.setMultiValued(DIM_CLASS, true);
            facetsConfig.setRequireDimCount(DIM_CLASS, true);
        }

        suggestDir = new File (dir, "suggest");
        if (!suggestDir.exists())
            suggestDir.mkdirs();

        // load saved lookups
        lookups = new ConcurrentHashMap<String, SuggestLookup>();
        for (File f : suggestDir.listFiles()) {
            if (f.isDirectory()) {
                try {
                    lookups.put(f.getName(), new SuggestLookup (f));
                }
                catch (IOException ex) {
                    Logger.error("Unable to load lookup from "+f, ex);
                }
            }
        }
        Logger.info("## "+suggestDir+": "
                    +lookups.size()+" lookups loaded!");

        sorters = loadSorters (new File (dir, SORTER_CONFIG_FILE));
        Logger.info("## "+sorters.size()+" sort fields defined!");

        this.baseDir = dir;
        setFetchWorkers (FETCH_WORKERS);
    }

    public void setFetchWorkers (int n) {
        if (fetchWorkers != null) {
            for (Future f : fetchWorkers)
                if (f != null)
                    f.cancel(true);
        }
        
        fetchWorkers = new Future[n];
        for (int i = 0; i < fetchWorkers.length; ++i)
            fetchWorkers[i] = threadPool.submit(new FetchWorker ());
    }
    
    protected synchronized DirectoryReader getReader () throws IOException {
        DirectoryReader reader = DirectoryReader.openIfChanged(indexReader);
        if (reader != null) {
            indexReader.close();
            indexReader = reader;
        }
        return indexReader;
    }

    protected IndexSearcher getSearcher () throws IOException {
        return new IndexSearcher (getReader ());
    }

    static boolean DEBUG (int level) {
        Global g = Global.getInstance();
        if (g != null)
            return g.debug(level);
        return false;
    }

    static Analyzer createIndexAnalyzer () {
        Map<String, Analyzer> fields = new HashMap<String, Analyzer>();
        fields.put(FIELD_ID, new KeywordAnalyzer ());
        fields.put(FIELD_KIND, new KeywordAnalyzer ());
        return  new PerFieldAnalyzerWrapper 
            (new StandardAnalyzer (LUCENE_VERSION), fields);
    }

    /**
     * Create a empty RAM instance. This is useful for searching/filtering
     * of a subset of the documents stored.
     */
    public TextIndexer createEmptyInstance () throws IOException {
        TextIndexer indexer = new TextIndexer ();
        indexer.indexDir = new RAMDirectory ();
        indexer.taxonDir = new RAMDirectory ();
        return config (indexer);
    }

    public TextIndexer createEmptyInstance (File dir) throws IOException {
        TextIndexer indexer = new TextIndexer ();

        File index = new File (dir, "index");
        if (!index.exists())
            index.mkdirs();
        indexer.indexDir = new NIOFSDirectory
            (index, NoLockFactory.getNoLockFactory());
        
        File taxon = new File (dir, "facet");
        if (!taxon.exists())
            taxon.mkdirs();
        indexer.taxonDir = new NIOFSDirectory
            (taxon, NoLockFactory.getNoLockFactory());
        
        return config (indexer);
    }

    public Map<String,Integer> getFacetLabelCounts(String facetName) throws IOException {
        IndexSearcher searcher = new IndexSearcher(indexReader);
        TaxonomyReader taxoReader = new DirectoryTaxonomyReader(taxonDir);
        FacetsCollector fc = new FacetsCollector();
        FacetsCollector.search(searcher, new MatchAllDocsQuery(), 1000000, fc);
        Facets facets = new FastTaxonomyFacetCounts(taxoReader, facetsConfig, fc);
        List<FacetResult> frs = facets.getAllDims(999999);
        Map<String,Integer> facetValues = new HashMap<>();
        for (FacetResult fr : frs) {
            if (fr.dim.equals(facetName)) {
                LabelAndValue[] lavs = fr.labelValues;
                for (LabelAndValue lav : lavs)
                    facetValues.put(lav.label, lav.value.intValue());
            }
        }
        return facetValues;
    }

    protected TextIndexer config (TextIndexer indexer) throws IOException {
        indexer.indexAnalyzer = createIndexAnalyzer ();
        IndexWriterConfig conf = new IndexWriterConfig 
            (LUCENE_VERSION, indexer.indexAnalyzer);
        indexer.indexWriter = new IndexWriter (indexer.indexDir, conf);
        indexer.indexReader = DirectoryReader.open(indexer.indexWriter, true);
        indexer.taxonWriter = new DirectoryTaxonomyWriter (indexer.taxonDir);
        indexer.facetsConfig = new FacetsConfig ();
        for (Map.Entry<String, FacetsConfig.DimConfig> me
                 : facetsConfig.getDimConfigs().entrySet()) {
            String dim = me.getKey();
            FacetsConfig.DimConfig dconf = me.getValue();
            indexer.facetsConfig.setHierarchical(dim, dconf.hierarchical);
            indexer.facetsConfig.setMultiValued(dim, dconf.multiValued);
            indexer.facetsConfig.setRequireDimCount
                (dim, dconf.requireDimCount);
        }
        // shouldn't be using for any 
        indexer.lookups = new ConcurrentHashMap<String, SuggestLookup>();
        indexer.sorters = new ConcurrentHashMap<String, SortField.Type>();
        indexer.sorters.putAll(sorters);
        return indexer;
    }

    public List<SuggestResult> suggest 
        (String field, CharSequence key, int max) throws IOException {
        SuggestLookup lookup = lookups.get(field);
        if (lookup == null) {
            Logger.debug("Unknown suggest field \""+field+"\"");
            return new ArrayList ();
        }
        
        return lookup.suggest(key, max);
    }

    public Collection<String> getSuggestFields () {
        return Collections.unmodifiableCollection(lookups.keySet());
    }

    public int size () {
        try {
            return getReader().numDocs();
        }
        catch (IOException ex) {
            Logger.trace("Can't retrieve NumDocs", ex);
        }
        return -1;
    }

    public SearchResult search (String text, int size) throws IOException {
        return search (new SearchOptions (null, size, 0, 10), text);
    }

    public SearchResult search 
        (SearchOptions options, String text) throws IOException {
        return search (options, text, null);
    }
    
    public SearchResult search 
        (SearchOptions options, String text, Collection subset)
        throws IOException {
        //this is a quick and dirty way to have a cleaner-looking
        //query for display
        String qtext =text;
        if (qtext!=null){
            qtext= text.replace(TextIndexer.GIVEN_START_WORD,
                                TextIndexer.START_WORD);
            qtext = qtext.replace(TextIndexer.GIVEN_STOP_WORD,
                                  TextIndexer.STOP_WORD);
        }
        SearchResult searchResult = new SearchResult (options, text);

        Query query = null;
        if (text == null) {
            query = new MatchAllDocsQuery ();
        }
        else {
            try {
                QueryParser parser = new QueryParser
                    ("text", indexAnalyzer);
                query = parser.parse(qtext);
            }
            catch (ParseException ex) {
                Logger.warn("Can't parse query expression: "+qtext, ex);
            }
        }

        if (query != null) {
            Filter f = null;
            if (subset != null) {
                List<Term> terms = getTerms (subset);
                //Logger.debug("Filter terms "+subset.size());
                if (!terms.isEmpty())
                    f = new TermsFilter (terms);
            }
            else if (options.kind != null) {
                Set<String> kinds = new TreeSet<String>();
                kinds.add(options.kind.getName());
                Reflections reflections = new Reflections("ix");
                for (Class c : reflections.getSubTypesOf(options.kind)) {
                    kinds.add(c.getName());
                }
                f = new FieldCacheTermsFilter 
                    (FIELD_KIND, kinds.toArray(new String[0]));
            }
            search (searchResult, query, f);
        }
        
        return searchResult;
    }

    public SearchResult filter (Collection subset)  throws IOException {
        SearchOptions options = new SearchOptions
            (null, subset.size(), 0, subset.size()/2);
        return filter (options, subset);
    }

    protected List<Term> getTerms (Collection subset) {
        List<Term> terms = new ArrayList<Term>();
        for (Iterator it = subset.iterator(); it.hasNext(); ) {
            Object obj = it.next();
            Term term = getTerm (obj);
            if (term != null) {
                terms.add(term);
            }
        }
        return terms;
    }
    
    protected TermsFilter getTermsFilter (Collection subset) {
        return new TermsFilter (getTerms (subset));
    }
    
    public SearchResult filter (SearchOptions options, Collection subset)
        throws IOException {
        return filter (options, getTermsFilter (subset));
    }

    public TermVectors getTermVectors (Class kind, String field)
        throws IOException {
        return new TermVectorsCollector (kind, field).termVectors();
    }
    
    public TermVectors getTermVectors (Class kind, String field,
                                       Map<String, String> filters)
        throws IOException {
        Term[] terms = new Term[0];
        if (filters != null && !filters.isEmpty()) {
            terms = new Term[filters.size()];
            int i = 0;
            for (Map.Entry<String, String> me : filters.entrySet()) {
                terms[i++] = new Term (me.getKey(), me.getValue());
            }
        }
        return new TermVectorsCollector(kind, field, terms).termVectors();
    }
    
    public SearchResult range (SearchOptions options, String field,
                               Integer min, Integer max)
        throws IOException {
        Query query = NumericRangeQuery.newIntRange
            (field, min, max, true /* minInclusive?*/, true/*maxInclusive?*/);
        
        return search (getSearcher (), new SearchResult (options, null),
                       query, null);
    }
    
    protected SearchResult filter (SearchOptions options, Filter filter)
        throws IOException {
        return search (getSearcher (), new SearchResult (options, null),
                       new MatchAllDocsQuery (), filter);
    }

    protected SearchResult search (SearchResult searchResult, 
                                   Query query, Filter filter)
        throws IOException {
        return search (getSearcher (), searchResult, query, filter);
    }
    
    protected SearchResult search (IndexSearcher searcher,
                                   SearchResult searchResult, 
                                   Query query, Filter filter)
        throws IOException {
        SearchOptions options = searchResult.getOptions();
        
        if (DEBUG (1)) {
            Logger.debug("## Query: "
                         +query+" Filter: "
                         +(filter!=null?filter.getClass():"none")
                         +" Options:"+options);
        }
        
        long start = System.currentTimeMillis();
            
        FacetsCollector fc = new FacetsCollector ();
        TaxonomyReader taxon = new DirectoryTaxonomyReader (taxonWriter);
        TopDocs hits = null;
        
        try {
            Sort sorter = null;
            if (!options.order.isEmpty()) {
                List<SortField> fields = new ArrayList<SortField>();
                for (String f : options.order) {
                    boolean rev = false;
                    if (f.charAt(0) == '^') {
                        // sort in reverse
                        f = f.substring(1);
                    }
                    else if (f.charAt(0) == '$') {
                        f = f.substring(1);
                        rev = true;
                    }
                    
                    SortField.Type type = sorters.get(f);
                    if (type != null) {
                        SortField sf = new SortField (f, type, rev);
                        Logger.debug("Sort field (rev="+rev+"): "+sf);
                        fields.add(sf);
                    }
                    else {
                        Logger.warn("Unknown sort field: \""+f+"\"");
                    }
                }
                
                if (!fields.isEmpty())
                    sorter = new Sort (fields.toArray(new SortField[0]));
            }
            
            List<String> drills = options.facets;
            if (drills.isEmpty()) {
                hits = sorter != null 
                    ? (FacetsCollector.search
                       (searcher, query, filter, options.max(), sorter, fc))
                    : (FacetsCollector.search
                       (searcher, query, filter, options.max(), fc));
                
                Facets facets = new FastTaxonomyFacetCounts
                    (taxon, facetsConfig, fc);
                
                List<FacetResult> facetResults =
                    facets.getAllDims(options.fdim);
                if (DEBUG (1)) {
                    Logger.info("## "+facetResults.size()
                                +" facet dimension(s)");
                }
                
                for (FacetResult result : facetResults) {
                    Facet f = new Facet (result.dim);
                    if (DEBUG (2)) {
                        Logger.info(" + ["+result.dim+"]");
                    }
                    for (int i = 0; i < result.labelValues.length; ++i) {
                        LabelAndValue lv = result.labelValues[i];
                        if (DEBUG (2)) {
                            Logger.info("     \""+lv.label+"\": "+lv.value);
                        }
                        f.values.add(new FV (lv.label, lv.value.intValue()));
                    }
                    searchResult.facets.add(f);
                }
            }
            else {
                DrillDownQuery ddq = new DrillDownQuery (facetsConfig, query);
                // the first term is the drilldown dimension
                for (String dd : options.facets) {
                    int pos = dd.indexOf('/');
                    if (pos > 0) {
                        String facet = dd.substring(0, pos);
                        String value = dd.substring(pos+1);
                        ddq.add(facet, value/*.split("/")*/);
                    }
                    else {
                        Logger.warn("Bogus drilldown syntax: "+dd);
                    }
                }
                
                Facets facets;
                if (options.sideway) {
                    DrillSideways sideway = new DrillSideways 
                        (searcher, facetsConfig, taxon);
                    DrillSideways.DrillSidewaysResult swResult = 
                        sideway.search(ddq, filter, null, 
                                       options.max(), sorter, false, false);
                    
                    facets = swResult.facets;
                    hits = swResult.hits;
                }
                else { // drilldown
                    hits = sorter != null 
                        ? (FacetsCollector.search
                           (searcher, ddq, filter, options.max(), sorter, fc))
                        : (FacetsCollector.search
                           (searcher, ddq, filter, options.max(), fc));
                    
                    facets = new FastTaxonomyFacetCounts
                        (taxon, facetsConfig, fc);
                }
                
                List<FacetResult> facetResults =
                    facets.getAllDims(options.fdim);
                if (DEBUG (1)) {
                    Logger.info("## Drilled "
                                +(options.sideway ? "sideway" : "down")
                                +" "+facetResults.size()
                                +" facets and "+hits.totalHits
                                +" hits");
                }
                
                for (FacetResult result : facetResults) {
                    if (result != null) {
                        if (DEBUG (2)) {
                            Logger.info(" + ["+result.dim+"]");
                        }
                        Facet f = new Facet (result.dim);
                        
                        // make sure the facet value is returned                
                        String label = null; 
                        for (String d : drills) {
                            int pos = d.indexOf('/');
                            if (pos > 0) {
                                if (result.dim.equals(d.substring(0, pos)))
                                    label = d.substring(pos+1);
                            }
                        }
                        
                        for (int i = 0; i < result.labelValues.length; ++i) {
                            LabelAndValue lv = result.labelValues[i];
                            if (DEBUG (2)) {
                                Logger.info
                                    ("     \""+lv.label+"\": "+lv.value);
                            }
                            if (lv.label.equals(label)) {
                                // got it
                                f.values.add(0, new FV (lv.label, 
                                                        lv.value.intValue()));
                                label = null;
                            }
                            else {
                                f.values.add(new FV (lv.label, 
                                                     lv.value.intValue()));
                            }
                        }
                        
                        if (label != null) {
                            Number value =
                                facets.getSpecificValue(result.dim, label);
                            if (value != null) {
                                f.values.add(0, new FV
                                             (label, value.intValue()));
                            }
                            else {
                                Logger.warn
                                    ("Facet \""+result.dim+"\" doesn't any "
                                     +"value for label \""+label+"\"!");
                            }
                        }
                        
                        f.sort();
                        searchResult.facets.add(f);
                    }
                }
            }
        }
        finally {
            taxon.close();
        }

        if (DEBUG (1)) {
            Logger.debug("## Query executes in "
                         +String.format
                         ("%1$.3fs", 
                          (System.currentTimeMillis()-start)*1e-3)
                         +"..."+hits.totalHits+" hit(s) found!");
        }

        try {
            SearchResultPayload payload = new SearchResultPayload
                (searchResult, hits, searcher);
            if (options.fetch <= 0) {
                payload.fetch();
            }
            else {
                // we first block until we have enough result to show; simulate
                //  with a random number of fetch
                int fetch = 20 + new Random().nextInt(options.fetch);
                payload.fetch(fetch);

                if (hits.totalHits > fetch) {
                    // now queue the payload so the remainder is fetched in
                    // the background
                    fetchQueue.put(payload);
                }
                else {
                    payload.fetch();
                }
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.trace("Can't queue fetch results!", ex);
        }
        
        return searchResult;
    }


    protected Term getTerm (Object entity) {
        if (entity == null)
            return null;

        Class cls = entity.getClass();
        Object id = null;
        for (Field f : cls.getFields()) {
            if (null != f.getAnnotation(Id.class)) {
                try {
                    id = f.get(entity);
                    break;
                }
                catch (Exception ex) {
                    Logger.error("Can't retrieve entity Id", ex);
                }
            }
        }

        if (id == null) {
            Logger.warn("Entity "+entity+"["
                        +entity.getClass()+"] has no Id field!");
            return null;
        }

        return new Term (cls.getName()+".id", id.toString());
    }
    
    public Document getDoc (Object entity) throws Exception {
        Term term = getTerm (entity);
        if (term != null) {
            IndexSearcher searcher = getSearcher ();
            TopDocs docs = searcher.search(new TermQuery (term), 1);
            //Logger.debug("TermQuery: term="+term+" => "+docs.totalHits);
            if (docs.totalHits > 0)
                return searcher.doc(docs.scoreDocs[0].doc);
        }
        return null;
    }

    public JsonNode getDocJson (Object entity) throws Exception {
        Document _doc = getDoc (entity);
        if (_doc == null) {
            return null;
        }
        List<IndexableField> _fields = _doc.getFields();
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode fields = mapper.createArrayNode();
        for (IndexableField f : _fields) {
            ObjectNode node = mapper.createObjectNode();
            node.put("name", f.name());
            if (null != f.numericValue()) {
                node.put("value", f.numericValue().doubleValue());
            }
            else {
                node.put("value", f.stringValue());
            }

            ObjectNode n = mapper.createObjectNode();
            IndexableFieldType type = f.fieldType();
            if (type.docValueType() != null)
                n.put("docValueType", type.docValueType().toString());
            n.put("indexed", type.indexed());
            n.put("indexOptions", type.indexOptions().toString());
            n.put("omitNorms", type.omitNorms());
            n.put("stored", type.stored());
            n.put("storeTermVectorOffsets", type.storeTermVectorOffsets());
            n.put("storeTermVectorPayloads", type.storeTermVectorPayloads());
            n.put("storeTermVectorPositions", type.storeTermVectorPositions());
            n.put("storeTermVectors", type.storeTermVectors());
            n.put("tokenized", type.tokenized());
            
            node.put("options", n);
            fields.add(node);
        }
        
        ObjectNode doc = mapper.createObjectNode();
        doc.put("num_fields", _fields.size());
        doc.put("fields", fields);
        return doc;
    }
    
    /**
     * recursively index any object annotated with Entity
     */
    public void add (Object entity) throws IOException {
        if (entity == null 
            || !entity.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }

        Indexable indexable = 
            (Indexable)entity.getClass().getAnnotation(Indexable.class);

        if (indexable != null && !indexable.indexed()) {
            if (DEBUG (2)) {
                Logger.debug(">>> Not indexable "+entity);
            }

            return;
        }

        if (DEBUG (2))
            Logger.debug(">>> Indexing "+entity+"...");
        
        List<IndexableField> fields = new ArrayList<IndexableField>();
        fields.add(new StringField
                   (FIELD_KIND, entity.getClass().getName(), YES));

        instrument (new LinkedList<String>(), entity, fields);

        Document doc = new Document ();
        for (IndexableField f : fields) {
            String text = f.stringValue();
            if (text != null) {
                if (DEBUG (2))
                    Logger.debug(".."+f.name()+":"
                                 +text+" ["+f.getClass().getName()+"]");
                
                doc.add(new TextField ("text", text, NO));
            }
            doc.add(f);
        }
        
        // now index
        addDoc (doc);
        if (DEBUG (2))
            Logger.debug("<<< "+entity);
    }

    public void addDoc (Document doc) throws IOException {
        doc = facetsConfig.build(taxonWriter, doc);
        if (DEBUG (2))
            Logger.debug("++ adding document "+doc);
        
        indexWriter.addDocument(doc);
        lastModified.set(System.currentTimeMillis());
    }

    public long lastModified () { return lastModified.get(); }

    public void update (Object entity) throws IOException {
        if (!entity.getClass().isAnnotationPresent(Entity.class)) {
            return;
        }

        if (DEBUG (2))
            Logger.debug(">>> Updating "+entity+"...");

        try {
            Object id = null;
            for (Field f : entity.getClass().getFields()) {
                if (f.getAnnotation(Id.class) != null) {
                    id = f.get(entity);
                }
            }

            if (id != null) {
                String field = entity.getClass().getName()+".id";
                indexWriter.deleteDocuments
                    (new Term (field, id.toString()));
                
                if (DEBUG (2))
                    Logger.debug("++ Updating "+field+"="+id);
                
                // now reindex .. there isn't an IndexWriter.update 
                // that takes a Query
                add (entity);
            }
        }
        catch (Exception ex) {
            Logger.trace("Unable to update index for "+entity, ex);
        }

        if (DEBUG (2))
            Logger.debug("<<< "+entity);
    }

    public void remove (Object entity) throws Exception {
        Class cls = entity.getClass();
        if (cls.isAnnotationPresent(Entity.class)) {
            Field[] fields = cls.getDeclaredFields();
            Object id = null;       
            for (Field f : fields) {
                if (f.getAnnotation(Id.class) != null) {
                    id = f.get(entity);
                }
            }
            if (id != null) {
                String field = entity.getClass().getName()+".id";
                if (DEBUG (2))
                    Logger.debug("Deleting document "+field+"="+id+"...");
                indexWriter.deleteDocuments
                    (new Term (field, id.toString()));
            }
            else {
                Logger.warn("Entity "+cls+"'s Id field is null!");
            }
        }
        else {
            throw new IllegalArgumentException
                ("Object is not of type Entity");
        }
    }

    public void remove (String text) throws Exception {
        try {
            QueryParser parser = new QueryParser 
                (LUCENE_VERSION, "text", indexAnalyzer);
            Query query = parser.parse(text);
            Logger.debug("## removing documents: "+query);
            indexWriter.deleteDocuments(query);
        }
        catch (ParseException ex) {
            Logger.warn("Can't parse query expression: "+text, ex);
            throw new IllegalArgumentException
                ("Can't parse query: "+text, ex);
        }
    }

    protected void instrument (LinkedList<String> path,
                               Object entity, 
                               List<IndexableField> ixFields) {
        try {
            Class cls = entity.getClass();
            ixFields.add(new FacetField (DIM_CLASS, cls.getName()));

            DynamicFacet dyna = 
                (DynamicFacet)cls.getAnnotation(DynamicFacet.class);
            String facetLabel = null;
            String facetValue = null;

            Field[] fields = cls.getFields();
            for (Field f : fields) {
                Indexable indexable = 
                    (Indexable)f.getAnnotation(Indexable.class);
                if (indexable == null) {
                    indexable = defaultIndexable;
                }

                int mods = f.getModifiers();
                if (!indexable.indexed()
                    || Modifier.isStatic(mods)
                    || Modifier.isTransient(mods)) {
                    //Logger.debug("** skipping field "+f.getName()+"["+cls.getName()+"]");
                    continue;
                }

                path.push(f.getName());
                try {
                    Class type = f.getType();
                    Object value = f.get(entity);

                    if (DEBUG (2)) {
                        Logger.debug
                            ("++ "+toPath (path)+": type="+type
                             +" value="+value);
                    }

                    if (f.getAnnotation(Id.class) != null) {
                        //Logger.debug("+ Id: "+value);
                        if (value != null) {
                            // the hidden _id field stores the field's value
                            // in its native type whereas the display field id
                            // is used for indexing purposes and as such is
                            // represented as a string
                            String kind = entity.getClass().getName();
                            if (value instanceof Long) {
                                ixFields.add(new LongField 
                                             (kind+"._id", 
                                              (Long)value, YES));
                            }
                            else {
                                ixFields.add(new StringField 
                                             (kind+"._id", 
                                              value.toString(), YES));
                            }
                            ixFields.add
                                (new StringField (kind+".id", 
                                                  value.toString(), NO));
                        }
                        else {
                            if (DEBUG (2))
                                Logger.warn("Id field "+f+" is null");
                        }
                    }
                    else if (value == null) {
                        // do nothing
                    }
                    else if (dyna != null 
                             && f.getName().equals(dyna.label())) {
                        facetLabel = value.toString();
                    }
                    else if (dyna != null
                             && f.getName().equals(dyna.value())) {
                        facetValue = value.toString();
                    }
                    else if (type.isPrimitive()) {
                        indexField (ixFields, indexable, path, value);
                    }
                    else if (type.isArray()) {
                        int len = Array.getLength(value);
                        // recursively evaluate each element in the array
                        for (int i = 0; i < len; ++i) {
                            path.push(String.valueOf(i));
                            instrument (path, Array.get(value, i), ixFields); 
                            path.pop();
                        }
                    }
                    else if (Collection.class.isAssignableFrom(type)) {
                        Iterator it = ((Collection)value).iterator();
                        for (int i = 0; it.hasNext(); ++i) {
                            path.push(String.valueOf(i));
                            instrument (path, it.next(), ixFields);
                            path.pop();
                        }
                    }
                    // why isn't this the same as using type?
                    else if (value.getClass()
                             .isAnnotationPresent(Entity.class)) {
                        // composite type; recurse
                        instrument (path, value, ixFields);
                    }
                    else { // treat as string
                        indexField (ixFields, indexable, path, value);
                    }
                }
                catch (Exception ex) {
                    if (DEBUG (3)) {
                        Logger.warn(entity.getClass()
                                    +": Field "+f+" is not indexable due to "
                                    +ex.getMessage());
                    }
                }
                path.pop();
            } // foreach field

            // dynamic facet if available
            if (facetLabel != null && facetValue != null) {
                facetsConfig.setMultiValued(facetLabel, true);
                facetsConfig.setRequireDimCount(facetLabel, true);
                ixFields.add(new FacetField (facetLabel, facetValue));
                // allow searching of this field
                //ixFields.add(new TextField (facetLabel, facetValue, NO));
                ixFields.add(new TermVectorField (facetLabel, facetValue));
                // all dynamic facets are suggestable???
                suggestField (facetLabel, facetValue);
            }
            
            Method[] methods = entity.getClass().getMethods();
            for (Method m: methods) {
                Indexable indexable = 
                    (Indexable)m.getAnnotation(Indexable.class);
                if (indexable != null && indexable.indexed()) {
                    // we only index no arguments methods
                    Class[] args = m.getParameterTypes();
                    if (args.length == 0) {
                        Object value = m.invoke(entity);
                        if (value != null) {
                            String name = m.getName();
                            if (name.startsWith("get"))
                                name = name.substring(3);
                            indexField (ixFields, indexable, 
                                        Arrays.asList(name), value);
                        }
                    }
                    else {
                        Logger.warn("Indexable is annotated for non-zero "
                                    +"arguments method \""+m.getName()+"\""); 
                    }
                }
            }
        }
        catch (Exception ex) {
            Logger.trace("Fetching entity fields", ex);
        }
    }

    void suggestField (String name, String value) {
        try {
            name = name.replaceAll("[\\s/]","_");
            SuggestLookup lookup = lookups.get(name);
            if (lookup == null) {
                lookups.put(name, lookup = new SuggestLookup (name));
            }
            lookup.add(value);
        }
        catch (Exception ex) { // 
            Logger.trace("Can't create Lookup!", ex);
        }
    }
    
    void indexField (List<IndexableField> fields, 
                     Collection<String> path, Object value) {
        indexField (fields, null, path, value, NO);
    }

    void indexField (List<IndexableField> fields, Indexable indexable, 
                     Collection<String> path, Object value) {
        indexField (fields, indexable, path, value, NO);
    }

    void indexField (List<IndexableField> fields, Indexable indexable, 
                     Collection<String> path, Object value, 
                     org.apache.lucene.document.Field.Store store) {
        String name = path.iterator().next();
        String full = toPath (path);
        String fname =
            "".equals(indexable.name()) ? name : indexable.name();
        
        boolean asText = true;

        if (value instanceof Long) {
            //fields.add(new NumericDocValuesField (full, (Long)value));
            Long lval = (Long)value;
            fields.add(new LongField (full, lval, NO));
            asText = indexable.facet();
            if (!asText && !name.equals(full)) 
                fields.add(new LongField (name, lval, store));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.LONG);

            FacetField ff = getRangeFacet (fname, indexable.ranges(), lval);
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
                asText = false;
            }
        }
        else if (value instanceof Integer) {
            //fields.add(new IntDocValuesField (full, (Integer)value));
            Integer ival = (Integer)value;
            fields.add(new IntField (full, ival, NO));
            asText = indexable.facet();
            if (!asText && !name.equals(full))
                fields.add(new IntField (name, ival, store));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.INT);

            FacetField ff = getRangeFacet 
                (fname, indexable.ranges(), ival);
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
                asText = false;
            }
        }
        else if (value instanceof Float) {
            //fields.add(new FloatDocValuesField (full, (Float)value));
            Float fval = (Float)value;
            fields.add(new FloatField (name, fval, store));
            if (!full.equals(name))
                fields.add(new FloatField (full, fval, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.FLOAT);
            
            FacetField ff = getRangeFacet 
                (fname, indexable.dranges(), fval, indexable.format());
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
            asText = false;
        }
        else if (value instanceof Double) {
            //fields.add(new DoubleDocValuesField (full, (Double)value));
            Double dval = (Double)value;
            fields.add(new DoubleField (name, dval, store));
            if (!full.equals(name))
                fields.add(new DoubleField (full, dval, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.DOUBLE);

            FacetField ff = getRangeFacet 
                (fname, indexable.dranges(), dval, indexable.format());
            if (ff != null) {
                facetsConfig.setMultiValued(fname, true);
                facetsConfig.setRequireDimCount(fname, true);
                fields.add(ff);
            }
            asText = false;
        }
        else if (value instanceof java.util.Date) {
            long date = ((Date)value).getTime();
            fields.add(new LongField (name, date, store));
            if (!full.equals(name))
                fields.add(new LongField (full, date, NO));
            if (indexable.sortable())
                sorters.put(full, SortField.Type.LONG);
            asText = indexable.facet();
            if (asText) {
                value = YEAR_DATE_FORMAT.format(date);
            }
        }

        if (asText) {
            String text = value.toString();
            String dim = indexable.name();
            if ("".equals(dim))
                dim = toPath (path, true);

            if (indexable.facet() || indexable.taxonomy()) {
                facetsConfig.setMultiValued(dim, true);
                facetsConfig.setRequireDimCount(dim, true);
                
                if (indexable.taxonomy()) {
                    facetsConfig.setHierarchical(dim, true);
                    fields.add
                        (new FacetField
                         (dim, text.split(indexable.pathsep())));
                }
                else {
                    fields.add(new FacetField (dim, text));
                }
                fields.add(new TermVectorField (dim, text));
            }

            if (indexable.suggest()) {
                // also index the corresponding text field with the 
                //   dimension name
                //fields.add(new TextField (dim, text, NO));
                if (indexable.facet() || indexable.taxonomy()) {
                }
                else {
                    fields.add(new TermVectorField (dim, text));
                }
                suggestField (dim, text);
            }

            if (!(value instanceof Number)) {
                if (!name.equals(full))
                    fields.add(new TextField
                               (full, TextIndexer.START_WORD
                                + text + TextIndexer.STOP_WORD, NO));
            }

            if (indexable.sortable() && !sorters.containsKey(name))
                sorters.put(name, SortField.Type.STRING);
            fields.add(new TextField
                       (name, TextIndexer.START_WORD
                        + text + TextIndexer.STOP_WORD, store));
        }
    }

    static FacetField getRangeFacet (String name, long[] ranges, long value) {
        if (ranges.length == 0) 
            return null;

        if (value < ranges[0]) {
            return new FacetField (name, "<"+ranges[0]);
        }

        int i = 1;
        for (; i < ranges.length; ++i) {
            if (value < ranges[i])
                break;
        }

        if (i == ranges.length) {
            return new FacetField (name, ">"+ranges[i-1]);
        }

        return new FacetField (name, ranges[i-1]+":"+ranges[i]);
    }

    static FacetField getRangeFacet
        (String name, double[] ranges, double value, String format) {
        if (ranges.length == 0) 
            return null;

        if (value < ranges[0]) {
            return new FacetField (name, "<"+String.format(format, ranges[0]));
        }

        int i = 1;
        for (; i < ranges.length; ++i) {
            if (value < ranges[i])
                break;
        }

        if (i == ranges.length) {
            return new FacetField (name, ">"+String.format(format,ranges[i-1]));
        }

        return new FacetField (name, String.format(format, ranges[i-1])
                               +":"+String.format(format, ranges[i]));
    }
    
    static void setFieldType (FieldType ftype) {
        ftype.setIndexed(true);
        ftype.setTokenized(true);
        ftype.setStoreTermVectors(true);
        ftype.setIndexOptions
            (IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS);
    }

    static String toPath (Collection<String> path) {
        return toPath (path, /*false*/true);
    }

    static String toPath (Collection<String> path, boolean noindex) {
        StringBuilder sb = new StringBuilder ();
        List<String> rev = new ArrayList<String>(path);
        Collections.reverse(rev);

        for (Iterator<String> it = rev.iterator(); it.hasNext(); ) {
            String p = it.next();

            boolean append = true;
            if (noindex) {
                try {
                    Integer.parseInt(p);
                    append = false;
                }
                catch (NumberFormatException ex) {
                }
            }

            if (append) {
                sb.append(p);
                if (it.hasNext())
                    sb.append('_');
            }
        }
        return sb.toString();
    }

    public JsonNode getFacetsConfig () {
        return setFacetsConfig (facetsConfig);
    }

    static FacetsConfig getFacetsConfig (JsonNode node) {
        if (!node.isContainerNode())
            throw new IllegalArgumentException
                ("Not a valid json node for FacetsConfig!");

        String text = node.get("version").asText();
        Version ver = Version.parseLeniently(text);
        if (!ver.equals(LUCENE_VERSION)) {
            Logger.warn("Facets configuration version ("+ver+") doesn't "
                        +"match index version ("+LUCENE_VERSION+")");
        }

        FacetsConfig config = null;
        ArrayNode array = (ArrayNode)node.get("dims");
        if (array != null) {
            config = new FacetsConfig ();
            for (int i = 0; i < array.size(); ++i) {
                ObjectNode n = (ObjectNode)array.get(i);
                String dim = n.get("dim").asText();
                config.setHierarchical
                    (dim, n.get("hierarchical").asBoolean());
                config.setIndexFieldName
                    (dim, n.get("indexFieldName").asText());
                config.setMultiValued(dim, n.get("multiValued").asBoolean());
                config.setRequireDimCount
                    (dim, n.get("requireDimCount").asBoolean());
            }
        }

        return config;
    }

    static JsonNode setFacetsConfig (FacetsConfig config) {
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode node = mapper.createObjectNode();
        node.put("created", new java.util.Date().getTime());
        node.put("version", LUCENE_VERSION.toString());
        node.put("warning", "AUTOMATICALLY GENERATED FILE; DO NOT EDIT");
        Map<String, FacetsConfig.DimConfig> dims = config.getDimConfigs();
        node.put("size", dims.size());
        ArrayNode array = node.putArray("dims");
        for (Map.Entry<String, FacetsConfig.DimConfig> me : dims.entrySet()) {
            FacetsConfig.DimConfig c = me.getValue();
            ObjectNode n = mapper.createObjectNode();
            n.put("dim", me.getKey());
            n.put("hierarchical", c.hierarchical);
            n.put("indexFieldName", c.indexFieldName);
            n.put("multiValued", c.multiValued);
            n.put("requireDimCount", c.requireDimCount);
            array.add(n);
        }
        return node;
    }

    static void saveFacetsConfig (File file, FacetsConfig facetsConfig) {
        JsonNode node = setFacetsConfig (facetsConfig);
        ObjectMapper mapper = new ObjectMapper ();
        try {
            FileOutputStream out = new FileOutputStream (file);
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, node);
            out.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't persist facets config!", ex);
        }
    }

    static FacetsConfig loadFacetsConfig (File file) {
        FacetsConfig config = null;
        if (file.exists()) {
            ObjectMapper mapper = new ObjectMapper ();
            try {
                JsonNode conf = mapper.readTree(new FileInputStream (file));
                config = getFacetsConfig (conf);
                Logger.info("## FacetsConfig loaded with "
                            +config.getDimConfigs().size()
                            +" dimensions!");
            }
            catch (Exception ex) {
                Logger.trace("Can't read file "+file, ex);
            }
        }
        return config;
    }

    static ConcurrentMap<String, SortField.Type> loadSorters (File file) {
        ConcurrentMap<String, SortField.Type> sorters = 
            new ConcurrentHashMap<String, SortField.Type>();
        if (file.exists()) {
            ObjectMapper mapper = new ObjectMapper ();
            try {
                JsonNode conf = mapper.readTree(new FileInputStream (file));
                ArrayNode array = (ArrayNode)conf.get("sorters");
                if (array != null) {
                    for (int i = 0; i < array.size(); ++i) {
                        ObjectNode node = (ObjectNode)array.get(i);
                        String field = node.get("field").asText();
                        String type = node.get("type").asText();
                        sorters.put(field, SortField.Type.valueOf
                                    (SortField.Type.class, type));
                    }
                }
            }
            catch (Exception ex) {
                Logger.trace("Can't read file "+file, ex);
            }
        }
        return sorters;
    }

    static void saveSorters (File file, Map<String, SortField.Type> sorters) {
        ObjectMapper mapper = new ObjectMapper ();

        ObjectNode conf = mapper.createObjectNode();
        conf.put("created", new java.util.Date().getTime());
        ArrayNode node = mapper.createArrayNode();
        for (Map.Entry<String, SortField.Type> me : sorters.entrySet()) {
            ObjectNode obj = mapper.createObjectNode();
            obj.put("field", me.getKey());
            obj.put("type", me.getValue().toString());
            node.add(obj);
        }
        conf.put("sorters", node);

        try {
            FileOutputStream fos = new FileOutputStream (file);
            mapper.writerWithDefaultPrettyPrinter().writeValue(fos, conf);
            fos.close();
        }
        catch (IOException ex) {
            Logger.trace("Can't persist sorter config!", ex);
        }
    }

    public void shutdown () {
        try {
            fetchQueue.put(POISON_PAYLOAD);
            
            for (SuggestLookup look : lookups.values()) {
                look.close();
            }

            if (indexReader != null)
                indexReader.close();
            if (indexWriter != null)
                indexWriter.close();
            if (taxonWriter != null)
                taxonWriter.close();
            indexDir.close();
            taxonDir.close();

            saveFacetsConfig (new File (baseDir, FACETS_CONFIG_FILE), 
                              facetsConfig);
            saveSorters (new File (baseDir, SORTER_CONFIG_FILE), sorters);
        }
        catch (Exception ex) {
            //ex.printStackTrace();
            Logger.trace("Closing index", ex);
        }
        finally {
            indexers.remove(baseDir);
            threadPool.shutdown();
        }
    }
}
