package ix.ncats.controllers;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.*;

import controllers.AssetsBuilder;
import play.Play;
import play.Logger;
import play.twirl.api.Content;
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.mvc.Controller;
import play.mvc.Security;
import play.mvc.Result;
import play.mvc.Results;
import play.mvc.Call;
import play.mvc.BodyParser;
import play.db.ebean.Model;
import play.libs.ws.*;
import play.libs.F;
import play.libs.Akka;
import play.mvc.Http;
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
import ix.core.search.TextIndexer;
import ix.seqaln.SequenceIndexer;
import static ix.core.search.TextIndexer.*;
import tripod.chem.indexer.StructureIndexer;
import static tripod.chem.indexer.StructureIndexer.*;
import ix.core.plugins.TextIndexerPlugin;
import ix.core.plugins.StructureIndexerPlugin;
import ix.core.plugins.SequenceIndexerPlugin;
import ix.core.plugins.IxContext;
import ix.core.plugins.IxCache;
import ix.core.plugins.PersistenceQueue;
import ix.core.plugins.PayloadPlugin;
import ix.core.controllers.search.SearchFactory;
import ix.core.chem.StructureProcessor;
import ix.core.models.EntityModel;
import ix.core.models.Structure;
import ix.core.models.VInt;
import ix.core.models.Keyword;
import ix.core.search.SearchOptions;
import ix.core.controllers.StructureFactory;
import ix.core.controllers.EntityFactory;
import ix.core.controllers.PayloadFactory;
import ix.utils.Util;
import ix.utils.Global;
import chemaxon.formats.MolImporter;
import chemaxon.struc.Molecule;
import chemaxon.struc.MolAtom;
import chemaxon.struc.MolBond;
import chemaxon.util.MolHandler;

import java.awt.Dimension;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import org.freehep.graphicsio.svg.SVGGraphics2D;

import gov.nih.ncgc.chemical.Chemical;
import gov.nih.ncgc.chemical.ChemicalAtom;
import gov.nih.ncgc.chemical.ChemicalFactory;
import gov.nih.ncgc.chemical.ChemicalRenderer;
import gov.nih.ncgc.chemical.DisplayParams;
import gov.nih.ncgc.nchemical.NchemicalRenderer;
import gov.nih.ncgc.jchemical.Jchemical;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sf.ehcache.Element;
import ix.ncats.controllers.auth.*;

import static ix.core.search.TextIndexer.TermVectors;

/**
 * Basic plumbing for an App
 */
public class App extends Authentication {
    private static final String KEY_DISPLAY_CD = "DISPLAY_CD";

        private static final String DISPLAY_CD_VALUE_RELATIVE = "RELATIVE";

        static final String APP_CACHE = App.class.getName();

    private static AssetsBuilder delegate = new AssetsBuilder();
    public static Action<AnyContent> asset(String path, String file) {
        return delegate.at(path, file, false);
    }

    static final String RENDERER_URL =
        play.Play.application()
        .configuration().getString("ix.structure.renderer.url");
    
    static final String RENDERER_FORMAT =
        play.Play.application()
        .configuration().getString("ix.structure.renderer.format");
    
    public static final int FACET_DIM = Play.application()
        .configuration().getInt("ix.text.facet.dim", 100);
    public static final int MAX_SEARCH_RESULTS = 1000;

    public static final TextIndexer _textIndexer = 
        Play.application().plugin(TextIndexerPlugin.class).getIndexer();
    public static final StructureIndexer _strucIndexer =
        Play.application().plugin(StructureIndexerPlugin.class).getIndexer();
    public static final SequenceIndexer _seqIndexer =
        Play.application().plugin(SequenceIndexerPlugin.class).getIndexer();
    public static final PayloadPlugin _payloader =
        Play.application().plugin(PayloadPlugin.class);
        
    public static final IxContext _ix =
        Play.application().plugin(IxContext.class);

    public static final PersistenceQueue _pq =
        Play.application().plugin(PersistenceQueue.class);

    /**
     * interface for rendering a result page
     */
    public interface ResultRenderer<T> {
        Content getContent
            (SearchResultContext context,
             int page, int rows, int total, int[] pages,
             List<TextIndexer.Facet> facets, List<T> results);
        int getFacetDim ();
        default SearchOptions.FacetRange[] getRangeFacets () { return null; }
    }

    public static abstract class DefaultResultRenderer<T>
        implements ResultRenderer<T> {
        public int getFacetDim () { return FACET_DIM; }
    }

    public interface Tokenizer {
        public Enumeration<String> tokenize (String input);
    }

    public static class DefaultTokenizer implements Tokenizer {
        final protected String pattern;
        public DefaultTokenizer () {
            this ("[\\s;,\n\t]");
        }
        public DefaultTokenizer (String pattern) {
            this.pattern = pattern;
        }

        public Enumeration<String> tokenize (String input) {
            String[] tokens = input.split(pattern);
            return Collections.enumeration(Arrays.asList(tokens));
        }
    }

    static protected abstract class GetResult<T extends EntityModel> {
        final Model.Finder<Long, T> finder;
        final Class<T> cls;
        public GetResult (Class<T> cls, Model.Finder<Long, T> finder) {
            this.cls = cls;
            this.finder = finder;
        }

        public List<T> find (final String name) throws Exception {
            long start = System.currentTimeMillis();
            final String key = cls.getName()+"/"+name;
            List<T> e = getOrElse_
                (key, new Callable<List<T>> () {
                        public List<T> call () throws Exception {
                            return _find (key, name);
                        }
                    });
            double elapsed = (System.currentTimeMillis()-start)*1e-3;
            Logger.debug("Elapsed time "+String.format("%1$.3fs", elapsed)
                         +" to retrieve "+(e!=null?e.size():-1)
                         +" matches for "+name);
            return e;
        }

        /*
         * perform basic validation; subclass should override to provide
         * more control.
         */
        protected boolean validation (String name) {
            int len = name.length();
            if (name == null || len == 0 || len > 128)
                return false;

            for (int i = 0; i < len; ++i) {
                char ch = name.charAt(i);
                if (Character.isLetterOrDigit(ch) 
                    || Character.isWhitespace(ch)
                    || ch == '-' || ch == '+' || ch == ':'
                    || ch == ',' || ch == '.')
                    ;
                else
                    return false;
            }

            return true;
        }

        protected void cacheAlias (Keyword kw, String name, String key) {
            Set<String> aliases = new HashSet<String>();
            if (!kw.term.equals(name))
                aliases.add(cls.getName()+"/"+kw.term);
            if (!kw.term.toUpperCase().equals(name))
                aliases.add(cls.getName()+"/"+kw.term.toUpperCase());
            if (!kw.term.toLowerCase().equals(name))
                aliases.add(cls.getName()+"/"+kw.term.toLowerCase());
            for (String a : aliases)
                IxCache.alias(a, key);
        }

        protected List<T> _find (String key, String name) throws Exception {
            List<T> values = finder.where()
                .eq("synonyms.term", name).findList();
            if (values.isEmpty()) {
                // let try name directly
                values = finder.where()
                    .eq("name", name).findList();
            }

            if (values.isEmpty()) {
                try {
                    long id = Long.parseLong(name);
                    values = new ArrayList<>();
                    values.add(finder.byId(id));
                }
                catch (NumberFormatException ex) {
                }
            }
                                                        
            // also cache all the synonyms
            T best = null;
            int rank = 0;
            for (T v : values) {
                Set<String> labels = new HashSet<String>();
                for (Keyword kw : v.getSynonyms()) {
                    if (kw.term == null) {
                        Logger.warn("NULL term for synonym"
                                    +" keyword label: "
                                    +kw.label);
                    }
                    else if (kw.term.equalsIgnoreCase(name)) {
                        labels.add(kw.label);
                    }
                }

                int r = 0;
                for (String l : labels)
                    r += getLabelRank (l);

                if (best == null || r > rank) {
                    rank = r;
                    best = v;
                }
            }

            List<T> matches = new ArrayList<T>();
            if (best != null) {
                for (Keyword kw : best.getSynonyms()) {
                    if (kw.term != null && getLabelRank (kw.label) > 0)
                        cacheAlias (kw, name, key);
                }
                matches.add(best);
            }
                            
            return matches;
        }

        // override by subclass
        protected int getLabelRank (String label) {
            return 1;
        }

        public Result get (final String name) {
            if (!validation (name)) {
                return notFound ("Not a valid name: '"+name+"'");
            }

            try {
                String view = request().getQueryString("view");
                final String key = getClass().getName()
                    +"/"+cls.getName()+"/"+name+"/result/"
                    +(view != null?view:"");
                
                CachableContent content =
                    getOrElse_ (key, new Callable<CachableContent> () {
                        public CachableContent call () throws Exception {
                            List<T> e = find (name);
                            if (!e.isEmpty()) {
                                return CachableContent.wrap(getContent (e));
                            }
                            return null;
                        }
                    });

                return content != null ? content.ok()
                    :  notFound ("Unknown name: "+name);
            }
            catch (Exception ex) {
                Logger.error("Unable to generate Result for \""+name+"\"", ex);
                return error (ex);
            }
        }

        protected Result notFound (String mesg) {
            return notFound (mesg);
        }
        
        protected Result error (Exception ex) {
            return internalServerError (ex.getMessage());
        }
        
        abstract public Content getContent (List<T> e) throws Exception;
    }
    
    public static class FacetDecorator {
        final public Facet facet;
        public int max;
        public boolean raw;
        public boolean hidden;
        public Integer[] total;
        public boolean[] selection;
        
        public FacetDecorator (Facet facet) {
            this (facet, false, 6);
        }
        public FacetDecorator (Facet facet, boolean raw, int max) {
            this.facet = facet;
            this.raw = raw;
            this.max = max;
            total = new Integer[facet.size()];
            selection = new boolean[facet.size()];
        }

        public String name () { return facet.getName(); }
        public int size () { return facet.getValues().size(); }
        public String label (int i) {
            return facet.getLabel(i);
        }
        public String url () { return null; }
        public String value (int i) {
            Integer total = this.total[i];
            Integer count = facet.getCount(i);
            if (total != null) {
                return Util.format(count)+" | "+Util.format(total);
            }
            return Util.format(count);
        }
        public Integer percent (int i) {
            Integer total = this.total[i];
            if (total != null) {
                double p = (double)facet.getCount(i)/total;
                return (int)(100*p+0.5);
            }
            return null;
        }
    }
    /**
     * This returns links to up to 10 pages of interest.
     * 
     * The first few are always 1-3
     * 
     * The last 2 pages are always the last 2 possible
     * 
     * The middle pages are the pages around the current page
     * 
     * @param rowsPerPage
     * @param page
     * @param total
     * @return
     */
    public static int[] paging (int rowsPerPage, int page, int total) {
        
        //last page
        int max = (total+ rowsPerPage-1)/rowsPerPage;
        if (page < 0 || page > max) {
            //throw new IllegalArgumentException ("Bogus page "+page);
            return new int[0];
        }
        
        int[] pages;
        if (max <= 10) {
            pages = new int[max];
            for (int i = 0; i < pages.length; ++i)
                pages[i] = i+1;
        }
        else if (page >= max-3) {
            pages = new int[10];
            pages[0] = 1;
            pages[1] = 2;
            pages[2] = 0;
            for (int i = pages.length; --i > 2; )
                pages[i] = max--;
        }
        else {
            pages = new int[10];
            int i = 0;
            //0-7 set to +1
            for (; i < 7; ++i)
                pages[i] = i+1;
            //if the page is larger than 7 (last 3 page)
            //
            if (page >= pages[i-1]) {
                // now shift
                pages[--i] = page;
                while (i-- > 0)
                    pages[i] = pages[i+1]-1;
                pages[0] = 1;
                pages[1] = 2;
                pages[2] = 0;
            }
            pages[8] = max-1;
            pages[9] = max;
        }
        return pages;
    }

    public static String sha1 (Facet facet, int value) {
        return Util.sha1(facet.getName(),
                         facet.getValues().get(value).getLabel());
    }

    /**
     * make sure if the argument doesn't have quote then add them
     */
    static Pattern regex = Pattern.compile("\"([^\"]+)");
    public static String quote (String s) {
        try {
            Matcher m = regex.matcher(s);
            if (m.find())
                return s; // nothing to do.. already have quote
            return "\""+URLEncoder.encode(s, "utf8")+"\"";
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        return s;
    }
    
    public static String decode (String s) {
        try {
            return URLDecoder.decode(s, "utf8");
        }
        catch (Exception ex) {
            Logger.trace("Can't decode string "+s, ex);
        }
        return s;
    }

    public static String encode (String s) {
        try {
            return URLEncoder.encode(s, "utf8");
        }
        catch (Exception ex) {
            Logger.trace("Can't encode string "+s, ex);
        }
        return s;
    }

    public static String encode (Facet facet) {
        try {
            return URLEncoder.encode(facet.getName(), "utf8");
        }
        catch (Exception ex) {
            Logger.trace("Can't encode string "+facet.getName(), ex);
        }
        return facet.getName();
    }
    
    public static String encode (Facet facet, int i) {
        String value = facet.getValues().get(i).getLabel();
        try {
            return URLEncoder.encode(value, "utf8");
        }
        catch (Exception ex) {
            Logger.trace("Can't encode string "+value, ex);
        }
        return value;
    }

    public static String page (int rows, int page) {
        //Logger.debug(">> page(rows="+rows+",page="+page+") uri: "+request().uri());

        Map<String, Collection<String>> params = getQueryParameters ();
        
        // remove these
        //params.remove("rows");
        params.remove("page");
        StringBuilder uri = new StringBuilder (request().path()+"?page="+page);
        for (Map.Entry<String, Collection<String>> me : params.entrySet()) {
            for (String v : me.getValue()) {
                //Logger.debug(v+" => "+decode(v));
                uri.append("&"+me.getKey()+"="+v);
            }
        }

        //Logger.debug("<< "+uri);
        
        return uri.toString();
    }

    public static String truncate (String str, int size) {
        if (str.length() <= size) return str;
        return str.substring(0, size)+"...";
    }

    public static String url (String... remove) {
        return url (true, remove);
    }
    
    public static String url (boolean exact, String... remove) {
        //Logger.debug(">> uri="+request().uri());

        StringBuilder uri = new StringBuilder (request().path()+"?");
        Map<String, Collection<String>> params = getQueryParameters ();
        for (Map.Entry<String, Collection<String>> me : params.entrySet()) {
            boolean matched = false;
            for (String s : remove)
                if ((exact && s.equals(me.getKey()))
                    || me.getKey().startsWith(s)) {
                    matched = true;
                    break;
                }
            
            if (!matched) {
                for (String v : me.getValue())
                    if (v != null)
                        uri.append(me.getKey()+"="+v+"&");
            }
        }
        //Logger.debug("<< "+uri);
        
        return uri.substring(0, uri.length()-1);
    }

    public static Map<String, Collection<String>> removeIfMatch
        (Map<String, Collection<String>> params, String key, String value) {
        Collection<String> values = params.get(key);
        //Logger.debug("removeIfMatch: key="+key+" value="+value+" "+values);
        if (values != null) {
            List<String> keep = new ArrayList<String>();
            for (String v : values) {
                try {
                    String dv = URLDecoder.decode(v, "utf8");
                    //Logger.debug(v+" => "+dv);
                    if (!dv.startsWith(value)) {
                        keep.add(v);
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            if (!keep.isEmpty()) {
                params.put(key, keep);
            }
            else {
                params.remove(key);
            }
        }
        return params;
    }

    public static Map<String, Collection<String>> removeIfMatch
        (String key, String value) {
        Map<String, Collection<String>> params = getQueryParameters ();
        return removeIfMatch (params, key, value);
    }

    public static String url (Map<String, Collection<String>> params,
                              String... remove) {
        for (String p : remove)
            params.remove(p);
        
        StringBuilder uri = new StringBuilder (request().path()+"?");
        for (Map.Entry<String, Collection<String>> me : params.entrySet())
            for (String v : me.getValue())
                if (v != null)
                    uri.append(me.getKey()+"="+v+"&");
        
        return uri.substring(0, uri.length()-1);
    }
    

    static Map<String, Collection<String>> getQueryParameters () {
        Map<String, Collection<String>> params =
            new TreeMap<String, Collection<String>>();
        String uri = request().uri();
        int pos = uri.indexOf('?');
        if (pos >= 0) {
            for (String p : uri.substring(pos+1).split("&")) {
                pos = p.indexOf('=');
                if (pos > 0) {
                    String key = p.substring(0, pos);
                    String value = p.substring(pos+1);
                    Collection<String> values = params.get(key);
                    if (values == null) {
                        params.put(key, values = new ArrayList<String>());
                    }
                    values.add(value);
                }
                else {
                    Logger.error("Bad parameter: "+p);
                }
            }
        }
        return params;
    }

    /**
     * more specific version that only remove parameters based on 
     * given facets
     */
    public static String url (FacetDecorator[] facets, String... others) {
        Logger.debug(">> uri="+request().uri());

        StringBuilder uri = new StringBuilder (request().path()+"?");
        Map<String, Collection<String>> params = getQueryParameters ();
        for (Map.Entry<String, Collection<String>> me : params.entrySet()) {
            if (me.getKey().equals("facet")) {
                for (String v : me.getValue())
                    if (v != null) {
                        String s = decode (v);
                        boolean matched = false;
                        for (FacetDecorator f : facets) {
                            // use the real name.. f.name() is a decoration
                            // that might not be the same as the actual
                            // facet name
                            if (!f.hidden && s.startsWith(f.facet.getName())) {
                                matched = true;
                                break;
                            }
                        }
                        
                        if (!matched) {
                            uri.append(me.getKey()+"="+v+"&");
                        }
                    }
            }
            else {
                boolean matched = false;
                for (String s : others) {
                    if (s.equals(me.getKey())) {
                        matched = true;
                        break;
                    }
                }
                
                if (!matched)
                    for (String v : me.getValue())
                        if (v != null)
                            uri.append(me.getKey()+"="+v+"&");
            }
        }
        
        Logger.debug("<< uri="+uri);
        return uri.substring(0, uri.length()-1);
    }

    public static String queryString (String... params) {
        Map<String, String[]> query = new HashMap<String, String[]>();
        for (String p : params) {
            String[] values = request().queryString().get(p);
            if (values != null)
                query.put(p, values);
        }
        
        return query.isEmpty() ? "" : "?"+queryString (query);
    }
    
    public static String queryString (Map<String, String[]> queryString) {
        //Logger.debug("QueryString: "+queryString);
        StringBuilder q = new StringBuilder ();
        for (Map.Entry<String, String[]> me : queryString.entrySet()) {
            for (String s : me.getValue()) {
                if (q.length() > 0)
                    q.append('&');
                q.append(me.getKey()+"="+encode (s));
                //+ ("q".equals(me.getKey()) ? encode (s) : s));
            }
        }
        return q.toString();
    }

    public static boolean hasFacet (Facet facet, int i) {
        String[] facets = request().queryString().get("facet");
        if (facets != null) {
            for (String f : facets) {
                int pos = f.indexOf('/');
                if (pos > 0) {
                    try {
                        String name = f.substring(0, pos);
                        String value = f.substring(pos+1);
                        /*
                        Logger.debug("Searching facet "+name+"/"+value+"..."
                                     +facet.getName()+"/"
                                     +facet.getValues().get(i).getLabel());
                        */
                        boolean matched = name.equals(facet.getName())
                            && value.equals(facet.getValues()
                                            .get(i).getLabel());
                        
                        if (matched)
                            return matched;
                    }
                    catch (Exception ex) {
                        Logger.trace("Can't URL decode string", ex);
                    }
                }
            }
        }
        
        return false;
    }

    public static List<Facet> getFacets (final Class kind, final int fdim,
                                         SearchOptions.FacetRange... facets) {
        try {
            SearchResult result =
                SearchFactory.search(kind, null, 0, 0, fdim, null, facets);
            return result.getFacets();
        }
        catch (IOException ex) {
            Logger.trace("Can't retrieve facets for "+kind, ex);
        }
        return new ArrayList<Facet>();
    }

    public static List<String> getUnspecifiedFacets
        (final FacetDecorator[] decors) {
        String[] facets = request().queryString().get("facet");
        List<String> unspec = new ArrayList<String>();
        if (facets != null && facets.length > 0) {
            for (String f : facets) {
                int matches = 0;
                for (FacetDecorator d : decors) {
                    //Logger.debug(f+" <=> "+d.facet.getName());              
                    if (f.startsWith(d.facet.getName())) {
                        ++matches;
                    }
                }
                if (matches == 0)
                    unspec.add(f);
            }
        }
        return unspec;
    }

    public static List<String> getSpecifiedFacets (FacetDecorator[] decors) {
        String[] facets = request().queryString().get("facet");
        List<String> spec = new ArrayList<String>();
        if (facets != null && facets.length > 0) {
            for (String f : facets) {
                int matches = 0;
                for (FacetDecorator d : decors) {
                    if (f.startsWith(d.facet.getName()))
                        ++matches;
                }
                
                if (matches > 0)
                    spec.add(f);
            }
        }
        return spec;
    }

    public static Facet[] filter (List<Facet> facets, String... names) {
        if (names == null || names.length == 0)
            return facets.toArray(new Facet[0]);
        
        List<Facet> filtered = new ArrayList<Facet>();
        for (String n : names) {
            for (Facet f : facets)
                if (n.equals(f.getName()))
                    filtered.add(f);
        }
        return filtered.toArray(new Facet[0]);
    }

    public static TextIndexer.Facet[] getFacets (final Class<?> cls,
                                                 final String... filters) {
        StringBuilder key = new StringBuilder (cls.getName()+".facets");
        for (String f : filters)
            key.append("."+f);
        try {
            TextIndexer.Facet[] facets = getOrElse_
                (key.toString(), new Callable<TextIndexer.Facet[]>() {
                        public TextIndexer.Facet[] call () {
                            return filter (getFacets (cls, FACET_DIM), filters);
                        }
                    });
            return facets;
        }
        catch (Exception ex) {
            Logger.error("Can't get facets for "+cls, ex);
            ex.printStackTrace();
        }
        return new TextIndexer.Facet[0];
    }

    public static SearchOptions.FacetRange
        createDateFacetRange (String field) {
        SearchOptions.FacetRange frange = new SearchOptions.FacetRange(field);
        
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(System.currentTimeMillis());

        Calendar cal = (Calendar)now.clone();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);

        // add a single character prefix so as to keep the map sorted; the
        // decorator strips this out
        frange.add(SearchOptions.newRange("Today", new long[]{
                    cal.getTimeInMillis(), now.getTimeInMillis()}));

        now = (Calendar)cal.clone();
        cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
        frange.add(SearchOptions.newRange("This week", new long[]{
                    cal.getTimeInMillis(), now.getTimeInMillis()}));

        now = (Calendar)cal.clone();
        cal.set(Calendar.WEEK_OF_MONTH, 1);
        frange.add(SearchOptions.newRange("This month", new long[]{
                    cal.getTimeInMillis(), now.getTimeInMillis()}));

        now = (Calendar)cal.clone();
        cal = (Calendar)now.clone();
        cal.add(Calendar.MONTH, -6);
        frange.add(SearchOptions.newRange("Past 6 months", new long[]{
                    cal.getTimeInMillis(), now.getTimeInMillis()}));

        now = (Calendar)cal.clone();
        cal = (Calendar)now.clone();
        cal.add(Calendar.YEAR, -1);
        frange.add(SearchOptions.newRange("Past 1 year", new long[]{
                    cal.getTimeInMillis(), now.getTimeInMillis()}));

        return frange;
    }
    
    public static String randvar (int size) {
        return Util.randvar(size, request ());
    }

    public static String randvar () {
        return randvar (5);
    }

    protected static Map<String, String[]> getRequestQuery () {
        Map<String, String[]> query = new HashMap<String, String[]>();
        query.putAll(request().queryString());
        // force to fetch everything at once
        //query.put("fetch", new String[]{"0"});
        return query;
    }
    
    public static SearchResult getSearchResult
        (final Class kind, final String q, final int total) {
        return getSearchResult (_textIndexer, kind, q, total);
    }

    public static SearchResult getSearchResult
        (final Class kind, final String q, final int total,
         Map<String, String[]> query, SearchOptions.FacetRange... facets) {
        return getSearchResult (_textIndexer, kind, q, total, query, facets);
    }
    
    public static SearchResult getSearchResult
        (final TextIndexer indexer, final Class kind,
         final String q, final int total, SearchOptions.FacetRange... facets) {
        return getSearchResult (indexer, kind, q,
                                total, getRequestQuery(), facets);
    }

    public static String signature (String q, Map<String, String[]> query) {
        return signature (null, q, query);
    }
    
    public static String signature (Class kind, String q,
                                    Map<String, String[]> query) {
        List<String> qfacets = new ArrayList<String>();

        if (query.get("facet") != null) {
            for (String f : query.get("facet"))
                qfacets.add(f);
        }
        
        final boolean hasFacets = q != null
            && q.indexOf('/') > 0 && q.indexOf("\"") < 0;
        if (hasFacets) {
            // treat this as facet
            qfacets.add("MeSH/"+q);
            query.put("facet", qfacets.toArray(new String[0]));
        }
        //query.put("drill", new String[]{"down"});
        
        List<String> args = new ArrayList<String>();
        args.add(request().path());
        if (q != null)
            args.add(q);
        for (String f : qfacets)
            args.add(f);
        
        if (query.get("order") != null) {
            for (String f : query.get("order"))
                args.add(f);
        }
        Collections.sort(args);
        
        if (kind != null)
            args.add(kind.getName());

        String sha1 = Util.sha1(args.toArray(new String[0]));
        //Logger.debug("SIGNATURE: "+args+" => "+sha1);

        return sha1;
    }

    public static SearchResult getSearchContext (String ctx) {
        Object result = IxCache.get(ctx);
        if (result != null) {
            if (result instanceof SearchResult) {
                return (SearchResult)result;
            }
        }
        Logger.warn("No context found: "+ctx);
        return null;
    }
        
    public static SearchResult getSearchFacets (final Class kind) {
        Http.Request req = request ();
        int fdim = FACET_DIM;
        if (req != null) {
            String q = req.getQueryString("fdim");
            if (q != null) {
                try {
                    fdim = Integer.parseInt(q);
                }
                catch (NumberFormatException ex) {
                }
            }
        }
        return getSearchFacets (kind, fdim);
    }

    public static SearchResult getSearchFacets
        (final Class kind, final Collection subset,
         SearchOptions.FacetRange... facets) {
        return getSearchFacets (kind, subset, FACET_DIM, facets);
    }
    
    public static SearchResult getSearchFacets
        (final Class kind, final Collection subset, final int fdim,
         final SearchOptions.FacetRange... facets) {
        final String sha1 = Util.sha1(kind.getName()+"/"+fdim,
                                      Util.sha1(subset));
        try {
            return getOrElse_ (sha1, new Callable<SearchResult>() {
                    public SearchResult call () throws Exception {
                        SearchResult result = SearchFactory.search
                            (subset, null, subset.size(), 0, fdim,
                             request().queryString(), facets);
                        return cacheKey (result, sha1, sha1);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't get search facets!", ex);
        }
        return null;
    }
    
    public static SearchResult getSearchFacets
        (final Class kind, final int fdim,
         final SearchOptions.FacetRange... facets) {
        final String sha1 = Util.sha1
            (App.class.getName()+"/facets/"+kind.getName()+"/"+fdim);
        try {
            return getOrElse_ (sha1, new Callable<SearchResult>() {
                    public SearchResult call () throws Exception {
                        SearchResult result = SearchFactory.search
                            (kind, null, 0, 0, fdim, null, facets);
                        return cacheKey (result, sha1, sha1);
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't get search facets!", ex);
        }
        return null;
    }

    final static Pattern RangeRe = Pattern.compile
        ("([^:]+):\\[([^,]*),([^\\]]*)\\]");
    public static SearchResult getSearchResult
        (final TextIndexer indexer, final Class kind,
         final String q, final int total, final Map<String, String[]> query,
         final SearchOptions.FacetRange... rangeFacets) {
        
        final String sha1 = signature (kind, q, query);
        final boolean hasFacets = q != null
            && q.indexOf('/') > 0 && q.indexOf("\"") < 0;
        
        try {       
            long start = System.currentTimeMillis();
            SearchResult result;
            if (indexer != _textIndexer) {
                // if it's an ad-hoc indexer, then we don't bother caching
                //  the results
                result = SearchFactory.search
                    (indexer, kind, null, hasFacets ? null : q,
                     total, 0, FACET_DIM, query);
            }
            else {
                Logger.debug("Search request sha1: "+sha1+" cached? "
                             +IxCache.contains(sha1));

                if (q != null) {
                    // check to see if q is format like a range
                    Matcher m = RangeRe.matcher(q);
                    if (m.find()) {
                        final String field = m.group(1);
                        final String min = m.group(2);
                        final String max = m.group(3);
                        
                        Logger.debug
                            ("range: field="+field+" min="+min+" max="+max);
                        
                        return getOrElse_ (sha1, new Callable<SearchResult> () {
                                public SearchResult call () throws Exception {
                                    SearchOptions options =
                                        new SearchOptions (query);
                                    options.kind = kind;
                                    options.top = total;
                                    for (SearchOptions.FacetRange fr
                                             : rangeFacets)
                                        options.addFacet(fr);
                                    
                                    SearchResult result = _textIndexer.range
                                        (options, field, min.equals("")
                                         ? null : Integer.parseInt(min),
                                         max.equals("")
                                         ? null : Integer.parseInt(max));
                                    result.updateCacheWhenComplete(sha1);
                                    return cacheKey (result, sha1, sha1);
                                }
                            });
                    }
                }

                result = getOrElse_
                    (sha1, new Callable<SearchResult>() {
                            public SearchResult call () throws Exception {
                                Logger.debug("### cache missed: "+sha1);
                                SearchResult result = SearchFactory.search
                                (kind, hasFacets ? null : q,
                                 total, 0, FACET_DIM, query, rangeFacets);
                                result.updateCacheWhenComplete(sha1);
                                return cacheKey (result, sha1, sha1);
                            }
                        });

                /*
                if (hasFacets && result.count() == 0) {
                    Logger.debug("No results found for facet; "
                                 +"retry as just query: "+q);
                    // empty result.. perhaps the query contains /'s
                    IxCache.remove(sha1); // clear cache
                    result = getOrElse
                        (sha1, new Callable<SearchResult>() {
                                public SearchResult call ()
                                    throws Exception {
                                    return SearchFactory.search
                                    (kind, q, total, 0, FACET_DIM,
                                     request().queryString());
                                }
                            });
                }
                */
                Logger.debug(sha1+" => "+result);
            }
            double elapsed = (System.currentTimeMillis() - start)*1e-3;
            Logger.debug(String.format("Elapsed %1$.3fs to retrieve "
                                       +"search %2$d/%3$d results...",
                                       elapsed, result.size(),
                                       result.count()));
            
            return result;
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.trace("Unable to perform search", ex);
        }
        return null;
    }

    static protected SearchResult cacheKey (SearchResult result,
                                            String key, String cacheKey) {
        if (key.length() > 10) {
            key = key.substring(0, 10);
        }
        result.setKey(key);
        IxCache.alias(key, cacheKey); // create alias 
        return result;
    }

    public static Result getEtag (String key, Callable<Result> callable)
        throws Exception {
        String ifNoneMatch = request().getHeader("If-None-Match");
        if (ifNoneMatch != null
            && ifNoneMatch.equals(key) && IxCache.contains(key))
            return status (304);

        response().setHeader(ETAG, key);
        return getOrElse_ (key, callable);
    }
    
    public static <T> T getOrElse (String key, Callable<T> callable)
        throws Exception {
        return getOrElse (_textIndexer.lastModified(), key, callable);
    }

    public static <T> T getOrElse_ (String key, Callable<T> callable)
        throws Exception {
        String refresh = request().getQueryString("refresh");
        if (refresh != null
            && ("true".equalsIgnoreCase(refresh)
                || "yes".equalsIgnoreCase(refresh)
                || "y".equalsIgnoreCase(refresh))) {
            IxCache.remove(key);
        }
        
        return getOrElse (_textIndexer.lastModified(), key, callable);
    }
    
    public static <T> T getOrElse (long modified,
                                   String key, Callable<T> callable)
        throws Exception {
        return IxCache.getOrElse(modified, key, callable);
    }

    public static Result marvin () {
        response().setHeader("X-Frame-Options", "SAMEORIGIN");
        return ok (ix.ncats.views.html.marvin.render());
    }

    @BodyParser.Of(value = BodyParser.Text.class, maxLength = 1024 * 10)
    public static Result smiles () {
        String data = request().body().asText();
        Logger.info(data);
        try {
            //String q = URLEncoder.encode(mol.toFormat("smarts"), "utf8");
            return ok (StructureProcessor.createQuery(data));
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.debug("** Unable to convert structure\n"+data);
            return badRequest (data);
        }
    }

    @BodyParser.Of(value = BodyParser.Json.class, maxLength = 1024*10)
    public static Result molconvert () {
        JsonNode json = request().body().asJson();        
        try {
            final String format = json.get("parameters").asText();
            final String mol = json.get("structure").asText();

            String sha1 = Util.sha1(mol);
            Logger.debug("MOLCONVERT: format="+format+" mol="
                         +mol+" sha1="+sha1);
            
            response().setContentType("application/json");
            return getOrElse (0l, sha1, new Callable<Result>() {
                    public Result call () {
                        try {
                            MolHandler mh = new MolHandler (mol);
                            if (mh.getMolecule().getDim() < 2) {
                                mh.getMolecule().clean(2, null);
                            }
                            String out = mh.getMolecule().toFormat(format);
                            //Logger.debug("MOLCONVERT: output="+out);
                            ObjectMapper mapper = new ObjectMapper ();
                            ObjectNode node = mapper.createObjectNode();
                            node.put("structure", out);
                            node.put("format", format);
                            node.put("contentUrl", "");
                           
                            return ok (node);
                        }
                        catch (Exception ex) {
                            return badRequest ("Invalid molecule: "+mol);
                        }
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't parse request", ex);
            ex.printStackTrace();
            
            return internalServerError ("Unable to convert input molecule");
        }
    }

    public static Result renderOld (final String value, final int size) {
        String key = Util.sha1(value)+"::"+size;
        Result result = null;
        try {
            result = getOrElse (key, new Callable<Result> () {
                    public Result call () throws Exception {
                        WSRequestHolder ws = WS.url(RENDERER_URL)
                        .setFollowRedirects(true)
                        .setQueryParameter("structure", value)
                        .setQueryParameter("format", RENDERER_FORMAT)
                        .setQueryParameter("size", String.valueOf(size));
                        WSResponse res = ws.get().get(5000);
                        byte[] data = res.asByteArray();
                        if (data.length > 0) {
                            return ok(data).as("image/svg+xml");
                        }
                        return null;
                    }
                });
            
            if (result == null)
                IxCache.remove(key);
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.trace("Can't render "+value, ex);
        }
        
        return result;
    }

    public static Result render (final String value, final int size) {
        String key = App.class.getName()+"/render/"
            +Util.sha1(value)+"/"+size;
        try {
            return getOrElse (0l, key, new Callable<Result>() {
                    public Result call () throws Exception {
                        MolHandler mh = new MolHandler (value);
                        Molecule mol = mh.getMolecule();
                        if (mol.getDim() < 2) {
                            mol.clean(2, null);
                        }
                        return ok (render (mol, "svg", size, null))
                            .as("image/svg+xml");
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Not a valid molecule:\n"+value, ex);
            ex.printStackTrace();
            return badRequest ("Not a valid molecule: "+value);
        }
    }

    public static Result rendertest () {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream ();
            int size = 400;
            SVGGraphics2D svg = new SVGGraphics2D
                (bos, new Dimension (size, size));
            svg.startExport();
            Chemical chem = new Jchemical ();
            chem.load("c1ccncc1", Chemical.FORMAT_SMILES);
            chem.clean2D();
            
            ChemicalRenderer cr = new NchemicalRenderer();

            BufferedImage bi = cr.createImage(chem, 200);
            //ImageIO.write(bi, "png", bos); 

            cr.renderChem(svg, chem, size, size, false);
            svg.endExport();
            svg.dispose();
            
            //response().setContentType("image/png");
            return ok(bos.toByteArray()).as("image/svg+xml");
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }

     public static byte[] render (Molecule mol, String format,
                                  int size, int[] amap) throws Exception {
         return render(mol,format,size,amap,null);
     }

     public static byte[] render (final Molecule mol, final String format,
                                  final int size, final int[] amap,
                                  final Map newDisplay)
        throws Exception {
         final String key = App.class.getName()+"/render/"
             +Util.sha1(mol.toFormat("mrv"))+"/"+format+"/"+size
             +Arrays.hashCode(amap)+"/"
             +(newDisplay!=null?newDisplay.hashCode():0);
         return getOrElse_ (key, new Callable<byte[]> () {
                 public byte[] call () throws Exception {
                     return _render (mol, format, size, amap, newDisplay);
                 }
             });
     }
    
     public static byte[] _render (Molecule mol, String format, int size,
                                   int[] amap, Map newDisplay)
        throws Exception {
        Chemical chem = new Jchemical (mol);
        DisplayParams dp = DisplayParams.DEFAULT();
        if(newDisplay!=null)
        dp.changeSettings(newDisplay);
        
        //chem.reduceMultiples();
        boolean highlight=false;
        if(amap!=null && amap.length>0){
                ChemicalAtom[] atoms = chem.getAtomArray();
                for (int i = 0; i < Math.min(atoms.length, amap.length); ++i) {
                    atoms[i].setAtomMap(amap[i]);
                    if(amap[i]!=0){
                        dp = dp.withSubstructureHighlight();
                        highlight=true;
                    }
                }
        }
        
        if(size>250 && !highlight){
            if(chem.hasStereoIsomers())
                dp.changeProperty(DisplayParams.PROP_KEY_DRAW_STEREO_LABELS, true);
        }

        /*
        DisplayParams displayParams = new DisplayParams ();
        displayParams.changeProperty
            (DisplayParams.PROP_KEY_DRAW_STEREO_LABELS_AS_ATOMS, true);
        
        ChemicalRenderer render = new NchemicalRenderer (displayParams);
        */
       
        ChemicalRenderer render = new NchemicalRenderer ();
        render.setDisplayParams(dp);
        render.addDisplayProperty("TOP_TEXT");
        render.addDisplayProperty("BOTTOM_TEXT");
        ByteArrayOutputStream bos = new ByteArrayOutputStream ();       
        if (format.equals("svg")) {
            SVGGraphics2D svg = new SVGGraphics2D
                (bos, new Dimension (size, size));
            svg.startExport();
            render.renderChem(svg, chem, size, size, false);
            svg.endExport();
            svg.dispose();
        }
        else {
            BufferedImage bi = render.createImage(chem, size);
            ImageIO.write(bi, "png", bos); 
        }
        
        return bos.toByteArray();
    }
    
    public static byte[] render (Structure struc,
                                 String format, int size, int[] amap)
        throws Exception {
        Map newDisplay = new HashMap();
        if(struc.stereoChemistry == struc.stereoChemistry.RACEMIC) {
            newDisplay.put(DisplayParams.PROP_KEY_DRAW_STEREO_LABELS_AS_RELATIVE, true);
        }
        MolHandler mh = new MolHandler
            (struc.molfile != null ? struc.molfile : struc.smiles);
        Molecule mol = mh.getMolecule();
        if (mol.getDim() < 2) {
            mol.clean(2, null);
        }
        if(struc.opticalActivity!= struc.opticalActivity.UNSPECIFIED && struc.opticalActivity!=null){
            if(struc.definedStereo>0){
                if(struc.opticalActivity==struc.opticalActivity.PLUS_MINUS){
                    if(struc.stereoChemistry==struc.stereoChemistry.EPIMERIC||struc.stereoChemistry==struc.stereoChemistry.RACEMIC||struc.stereoChemistry==struc.stereoChemistry.MIXED){
                        mol.setProperty("BOTTOM_TEXT","relative stereochemistry");
                    }
                }
            }
            if(struc.opticalActivity==struc.opticalActivity.PLUS){
                mol.setProperty("BOTTOM_TEXT","optical activity: (+)");
                if(struc.stereoChemistry == struc.stereoChemistry.UNKNOWN){
                    newDisplay.put(DisplayParams.PROP_KEY_DRAW_STEREO_LABELS_AS_STARRED, true);
                }
            }else if(struc.opticalActivity==struc.opticalActivity.MINUS){
                mol.setProperty("BOTTOM_TEXT","optical activity: (-)");
                if(struc.stereoChemistry == struc.stereoChemistry.UNKNOWN){
                    newDisplay.put(DisplayParams.PROP_KEY_DRAW_STEREO_LABELS_AS_STARRED, true);
                }
            }               
        }

        if(size>250){
            if(struc.stereoChemistry != struc.stereoChemistry.ACHIRAL)
                newDisplay.put(DisplayParams.PROP_KEY_DRAW_STEREO_LABELS, true);
        }
        if(newDisplay.size()==0)newDisplay=null;
        return render (mol, format, size, amap,newDisplay);
    }

    public static int[] stringToIntArray(String amapString){
        int[] amap=null;
        if(amapString!=null){
            String[] amapb = null;
            amapb = amapString.split(",");
            amap = new int[amapb.length];
            for(int i=0;i<amap.length;i++){
                try{
                    amap[i]=Integer.parseInt(amapb[i]);
                }catch(Exception e){
                                
                }
            }
        }
        return amap;
    }

    public static Structure structure (final String id) {
        try {
            final String key = Structure.class.getName()+"/"+id;
            return getOrElse_ (key, new Callable<Structure> () {
                    public Structure call () throws Exception {
                        return StructureFactory.getStructure(id);
                    }
                });
        }
        catch (Exception ex) {
            Logger.error("Can't retrieve structure: "+id, ex);
        }
        return null;
    }
    
    public static Result structure (final Structure struc,
                                    final String format,
                                    final int size,
                                    final String atomMap) {
        try {
            final int[] amap = stringToIntArray(atomMap);
            if (format.equals("svg") || format.equals("png")) {
                final String key =
                    App.class.getName()+"/"+Structure.class.getName()+"/"
                    +struc.id+"/"+size+"/"+struc.id+"."+format
                    +(atomMap != null ? ":" + atomMap:"");
                final String mime =
                    format.equals("svg") ? "image/svg+xml" : "image/png";
                
                return getOrElse_ (key, new Callable<Result> () {
                        public Result call () throws Exception {
                            return ok (render (struc, format,
                                               size, amap)).as(mime);
                        }
                    });
            }
            else {
                final String key =
                    Structure.class.getName()+"/"+struc.id+"."+format;
                return getOrElse_ (key, new Callable<Result> () {
                        public Result call () throws Exception {
                            //response().setContentType("text/plain");
                            if (format.equals("mrv")) {
                                MolHandler mh =
                                    new MolHandler (struc.molfile);
                                if (mh.getMolecule().getDim() < 2) {
                                    mh.getMolecule().clean(2, null);
                                }
                                return ok (mh.getMolecule()
                                           .toFormat("mrv"));
                            }
                            else if (format.equals("mol")
                                     || format.equals("sdf")) {
                                return struc.molfile != null
                                    ? ok (struc.molfile) : noContent ();
                            }

                            return struc.smiles != null
                                ?  ok (struc.smiles) : noContent ();
                        }
                    });
            }
        }
        catch (Exception ex) {
            Logger.error("Can't convert format "+format+" for structure "
                         +struc.id, ex);
            ex.printStackTrace();
            return internalServerError
                ("Unable to convert structure "+struc.id+" to format "+format);
        }
    }
    
    /**
     * Renders a chemical structure from structure ID
     * atom map can be provided for highlighting
     * 
     * @param id
     * @param format
     * @param size
     * @param atomMap
     * @return
     */
    public static Result structure (final String id,
                                    final String format, final int size,
                                    final String atomMap) {
        try {
            Structure struc = structure (id);
            if (struc != null) {
                return structure (struc, format, size, atomMap);
            }
            
            return notFound ("Unknown structure: "+id);
        }
        catch (Exception ex) {
            return internalServerError ("Unable retrieve structure: "+id);
        }
    }

    /**
     * Structure searching
     */
    public static abstract class SearchResultProcessor<T> {
        protected Enumeration<T> results;
        final SearchResultContext context = new SearchResultContext ();
        
        public SearchResultProcessor () {
        }

        public void setResults (int rows, Enumeration<T> results)
            throws Exception {
            this.results = results;
            // the idea is to generate enough results for 1.5 pages (enough
            // to show pagination) and return immediately. as the user pages,
            // the background job will fill in the rest of the results.
            int count = process (rows+1);
            
            // while we continue to fetch the rest of the results in the
            // background
            ActorRef handler = Akka.system().actorOf
                (Props.create(SearchResultHandler.class));
            handler.tell(this, ActorRef.noSender());
            Logger.debug("## search results submitted: "+handler);
        }
        
        public SearchResultContext getContext () { return context; }
        public boolean isDone () { return false; }

        public int process () throws Exception {
            return process (0);
        }
        
        public int process (int max) throws Exception {
            while (results.hasMoreElements()
                   && !isDone () && (max <= 0 || context.getCount() < max)) {
                T r = results.nextElement();
                try {
                    long start = System.currentTimeMillis();
                    Object obj = instrument (r);
                    if (obj != null) {
                        context.add(obj);
                    }
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                    Logger.error("Can't process structure search result", ex);
                }
            }
            return context.getCount();
        }
        
        //public abstract int process (int max) throws Exception;
        protected abstract Object instrument (T r) throws Exception;
    }

    public static class SearchResultContext /*implements Serializable*/ {
        public enum Status {
            Pending,
            Running,
            Done,
            Failed
        }

        Status status = Status.Pending;
        String mesg;
        Long start;
        Long stop;
        List results = new CopyOnWriteArrayList ();
        String id = randvar (10);
        Integer total;

        transient Set<String> keys = new HashSet<String>();
        transient ReentrantLock lock = new ReentrantLock ();

        SearchResultContext () {
        }

        SearchResultContext (SearchResult result) {
            id = result.getKey();
            start = result.getTimestamp();          
            total = result.count();
            if (result.finished()) {
                stop = result.getStopTime();
                setStatus (Status.Done);
            }
            else if (result.size() > 0)
                status = Status.Running;
            
            // prevent setStatus from caching this context with results
            // set
            results = result.getMatches();            
            if (status != Status.Done) {
                mesg = String.format
                    ("Loading...%1$d%%",
                     (int)(100.*result.size()/result.count()+0.5));
            }
        }

        public String getId () { return id; }
        public Status getStatus () { return status; }
        public void setStatus (Status status) { 
            this.status = status; 
            if (status == Status.Done) {
                if (total == null)
                    total = getCount ();
                // update cache
                for (String k : keys)
                    IxCache.set(k, this);
                
                // only update the cache if the instance in the cache
                //  is stale
                IxCache.setIfNewer(id, this, start);
            }
        }
        public String getMessage () { return mesg; }
        public void setMessage (String mesg) { this.mesg = mesg; }
        public Integer getCount () { return results.size(); }
        public Integer getTotal () { return total; }
        public Long getStart () { return start; }
        public Long getStop () { return stop; }
        public boolean finished () {
            return status == Status.Done || status == Status.Failed;
        }
        public void updateCacheWhenComplete (String... keys) {
            for (String k : keys)
                this.keys.add(k);
        }
        
        @com.fasterxml.jackson.annotation.JsonIgnore
        public List getResults () { return results; }
        protected void add (Object obj) {
            lock.lock();
            try {
                results.add(obj);
            }
            finally {
                lock.unlock();
            }
        }

        private void writeObject(java.io.ObjectOutputStream out)
            throws IOException {
            lock.lock();
            try {
                out.defaultWriteObject();
            }
            finally {
                lock.unlock();
            }
        }
        
        private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException {
            if (lock == null)
                lock = new ReentrantLock ();
            
            lock.lock();
            try {
                in.defaultReadObject();
            }
            finally {
                lock.unlock();
            }
        }
        
        private void readObjectNoData() throws ObjectStreamException {
        }
    }

    static public class CachableContent implements Content, Serializable {
        // change this value when the class evolve so as to invalidate
        // any cached instance
        static final long serialVersionUID = 0x2l;
        final String type;
        final String body;
        final String sha1;
        
        CachableContent (play.twirl.api.Content c) {
            type = c.contentType();
            body = c.body();
            sha1 = Util.sha1(body);
        }

        public String contentType () { return type; }
        public String body () { return body; }
        public String etag () { return sha1; }
        public Result ok () {
            String ifNoneMatch = request().getHeader("If-None-Match");
            if (ifNoneMatch != null
                && (ifNoneMatch.equals(sha1) || "*".equals(ifNoneMatch)))
                return Results.status(304);

            response().setHeader(ETAG, sha1);
            return Results.ok(this);
        }

        static public Object wrapIfContent (Object obj) {
            if (obj instanceof play.twirl.api.Content) {
                obj = new CachableContent ((play.twirl.api.Content)obj);
            }
            return obj;
        }
        
        static public CachableContent wrap (play.twirl.api.Content c) {
            return new CachableContent (c);
        }

        static public CachableContent wrap (JsonNode json) {
            return new CachableContent
                (new play.twirl.api.JavaScript(json.toString()));
        }
    }
    
    static class SearchResultHandler extends UntypedActor {
        @Override
        public void onReceive (Object obj) {
            if (obj instanceof SearchResultProcessor) {
                SearchResultProcessor processor = (SearchResultProcessor)obj;
                SearchResultContext ctx = processor.getContext();               
                try {
                    ctx.setStatus(SearchResultContext.Status.Running);
                    ctx.start = System.currentTimeMillis();            
                    int count = processor.process();
                    ctx.stop = System.currentTimeMillis();
                    ctx.setStatus(SearchResultContext.Status.Done);
                    Logger.debug("Actor "+self()+" finished; "+count
                                 +" search result(s) instrumented!");
                    context().stop(self ());
                }
                catch (Exception ex) {
                    ctx.status = SearchResultContext.Status.Failed;
                    ctx.setMessage(ex.getMessage());
                    ex.printStackTrace();
                    Logger.error("Unable to process search results", ex);
                }
            }
            else if (obj instanceof Terminated) {
                ActorRef actor = ((Terminated)obj).actor();
                Logger.debug("Terminating actor "+actor);
            }
            else {
                unhandled (obj);
            }
        }

        public void preStart () {
        }
        
        @Override
        public void postStop () {
            Logger.debug(getClass().getName()+" "+self ()+" stopped!");
        }
    }

    /**
     * This method will return a proper Call only if the query isn't already
     * finished in one way or another
     */
    public static Call checkStatus () {
        return checkStatus (null);
    }
    
    public static Call checkStatus (String kind) {
        String query = request().getQueryString("q");
        String type = request().getQueryString("type");

        Logger.debug("checkStatus: q="+query+" type="+type);
        if (type != null && query != null) {
            try {
                String key = null;
                if (type.equalsIgnoreCase("substructure")) {
                    key = "substructure/"+Util.sha1(query);
                }
                else if (type.equalsIgnoreCase("similarity")) {
                    String c = request().getQueryString("cutoff");
                    key = "similarity/"+getKey (query, Double.parseDouble(c));
                }
                else if (type.equalsIgnoreCase("sequence")) {
                    String iden = request().getQueryString("identity");
                    if (iden == null) {
                        iden = "0.5";
                    }
                    key = "sequence/"+getKey (query, Double.parseDouble(iden));
                }
                else {
                }

                Logger.debug("status: key="+key);
                Object value = IxCache.get(key);
                if (value != null) {
                    SearchResultContext context = (SearchResultContext)value;
                    Logger.debug("checkStatus: status="+context.getStatus()
                                 +" count="+context.getCount()
                                 +" total="+context.getTotal());
                    switch (context.getStatus()) {
                    case Done:
                    case Failed:
                        break;
                        
                    default:
                        return routes.App.status(key);
                    }
                    //return routes.App.status(type.toLowerCase(), query);
                }
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        else {
            Class klass = null;
            if (kind != null) {
                try {
                    klass = Class.forName(kind);
                }
                catch (Exception ex) {
                    Logger.warn("Bogus kind: "+kind, ex);
                }
            }
            
            String key = signature (klass, query, getRequestQuery ());
            Object value = IxCache.get(key);
            Logger.debug("checkStatus: key="+key+" value="+value);
            
            if (value != null) {
                SearchResult result = (SearchResult)value;
                SearchResultContext ctx = new SearchResultContext (result);
                Logger.debug("status: key="+key+" finished="+ctx.finished());

                if (!ctx.finished())
                    return routes.App.status(key);
            }
        }
        return null;
    }

    public static Result status (String key) {
        Object value = IxCache.get(key);
        Logger.debug("status["+key+"] => "+value);
        if (value != null) {
            if (value instanceof SearchResult) {
                // wrap SearchResult into SearchResultContext..
                SearchResultContext ctx
                    = new SearchResultContext ((SearchResult)value);
                
                ctx.id = key;
                value = ctx;
            }

            SearchResultContext ctx = (SearchResultContext)value;
            Logger.debug
                (" ++ status:"+ctx.getStatus()+" count="+ctx.getCount());
            
            ObjectMapper mapper = new ObjectMapper ();
            return ok ((JsonNode)mapper.valueToTree(value));
        }

        return notFound ("No key found: "+key+"!");
    }

    public static SearchResultContext batch
        (final String q, final int rows, final Tokenizer tokenizer,
         final SearchResultProcessor processor) {
        try {
            final String key = "batch/"+Util.sha1(q);
            Logger.debug("batch: q="+q+" rows="+rows);
            return getOrElse_ (key, new Callable<SearchResultContext> () {
                    public SearchResultContext call () throws Exception {
                        processor.setResults(rows, tokenizer.tokenize(q));
                        SearchResultContext ctx = processor.getContext();
                        ctx.updateCacheWhenComplete(key);
                        return ctx;
                    }
                });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform batch search", ex);
        }
        return null;
    }
    
    public static SearchResultContext sequence
        (final String seq, final double identity, final int rows,
         final int page, final SearchResultProcessor processor) {
        try {
            final String key = "sequence/"+getKey (seq, identity);
            return getOrElse
                (_seqIndexer.lastModified(), key,
                 new Callable<SearchResultContext> () {
                     public SearchResultContext call () throws Exception {
                         processor.setResults
                             (rows, _seqIndexer.search(seq, identity));
                         SearchResultContext ctx = processor.getContext();
                         ctx.updateCacheWhenComplete(key);
                         return ctx;
                     }
                 });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform sequence identity search", ex);
        }
        return null;
    }

    public static SearchResultContext substructure
        (final String query, final int rows,
         final int page, final SearchResultProcessor processor) {
        try {
            final String key = "substructure/"+Util.sha1(query);
            Logger.debug("substructure: query="+query
                         +" rows="+rows+" page="+page+" key="+key);
            return getOrElse
                (_strucIndexer.lastModified(),
                 key, new Callable<SearchResultContext> () {
                         public SearchResultContext call () throws Exception {
                             processor.setResults
                                 (rows, _strucIndexer.substructure(query, 0));
                             SearchResultContext ctx = processor.getContext();
                             Logger.debug("## cache missed: "+key+" => "+ctx);
                             ctx.updateCacheWhenComplete(key);
                             return ctx;
                         }
                     });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't perform substructure search", ex);
        }
        return null;
    }

    static String getKey (String q, double t) {
        return Util.sha1(q) + "/"+String.format("%1$d", (int)(1000*t+.5));
    }

    public static SearchResultContext similarity
        (final String query, final double threshold,
         final int rows, final int page,
         final SearchResultProcessor processor) {
        try {
            final String key = "similarity/"+getKey (query, threshold);
            return getOrElse
                (_strucIndexer.lastModified(),
                 key, new Callable<SearchResultContext> () {
                         public SearchResultContext call () throws Exception {
                             processor.setResults
                                 (rows, _strucIndexer.similarity
                                  (query, threshold, 0));
                             SearchResultContext ctx =processor.getContext();
                             ctx.updateCacheWhenComplete(key);
                             return ctx;
                         }
                     });
        }
        catch (Exception ex) {
            ex.printStackTrace();
            Logger.error("Can't execute similarity search", ex);
        }
        return null;
    }

    static String getKey (SearchResultContext context, String... params) {
        return "fetchResult/"+context.getId()
            +"/"+Util.sha1(request (), params);
    }

    public static <T> Result fetchResult
        (final SearchResultContext context, int rows,
         int page, final ResultRenderer<T> renderer) throws Exception {

        final String key = getKey (context, "facet");

        /**
         * we need to connect context.id with this key to have
         * the results of structure/sequence search context merged
         * together with facets, sorting, etc.
         */
        final SearchResult result = getOrElse_
            (key, new Callable<SearchResult> () {
                    public SearchResult call () throws Exception {
                        List results = context.getResults();
                        if (results.isEmpty()) {
                            return null;
                        }
                        
                        SearchResult searchResult =
                        SearchFactory.search (results, null, results.size(), 0,
                                              renderer.getFacetDim(),
                                              request().queryString(),
                                              renderer.getRangeFacets());
                        Logger.debug("Cache misses: "
                                     +key+" size="+results.size()
                                     +" class="+searchResult);
                        searchResult.updateCacheWhenComplete(key);
                        // make an alias for the context.id to this search
                        // result
                        return cacheKey (searchResult, context.getId(), key);
                    }
                });

        final List<T> results = new ArrayList<T>();
        final List<Facet> facets = new ArrayList<Facet>();
        int[] pages = new int[0];
        int count = 0;
        if (result != null) {
            Long stop = context.getStop();
            if (!context.finished() ||
                (stop != null && stop >= result.getTimestamp())) {
                Logger.debug("** removing cache "+key);
                IxCache.remove(key);
            }
            
            count = result.size();
            Logger.debug(key+": "+count+"/"+result.count()
                         +" finished? "+context.finished()
                         +" stop="+stop);
            
            rows = Math.min(count, Math.max(1, rows));
            int i = (page - 1) * rows;
            if (i < 0 || i >= count) {
                page = 1;
                i = 0;
            }
            pages = paging (rows, page, count);

            /*
            for (int j = 0; j < rows && i < count; ++j, ++i) 
                results.add((T)result.get(i));
            */
            result.copyTo(results, i, rows);
            facets.addAll(result.getFacets());

            if (result.finished()) {
                final String k = getKey (context);
                final int _page = page;
                final int _rows = rows;
                final int _count = count;
                final int[] _pages = pages;
            
                // result is cached
                return ok (getOrElse
                           (result.getStopTime(),
                            k, new Callable<Content> () {
                                    public Content call () throws Exception {
                                        Logger.debug("Cache misses: "+k
                                                     +" count="+_count
                                                     +" rows="+_rows
                                                     +" page="+_page);
                                        return CachableContent.wrap
                                            (renderer.getContent
                                             (context, _page, _rows,
                                              _count, _pages,
                                              facets, results));
                                    }
                                }));
            }
        }
        
        return ok (renderer.getContent(context, page, rows, count,
                                       pages, facets, results));
    }

    static ObjectNode toJson (Element elm) {
        return toJson (new ObjectMapper (), elm);
    }
    
    static ObjectNode toJson (ObjectMapper mapper, Element elm) {
        return toJson (mapper.createObjectNode(), elm);
    }

    static ObjectNode toJson (ObjectNode node, Element elm) {
        node.put("class", elm.getObjectValue().getClass().getName()
                 +"@"+String.format
                 ("%1$x", System.identityHashCode(elm.getObjectValue())));
        node.put("key", elm.getObjectKey().toString());
        node.put("creation", new Date (elm.getCreationTime()).toString());
        node.put("expiration", new Date (elm.getExpirationTime()).toString());
        node.put("lastAccess", new Date (elm.getLastAccessTime()).toString());
        node.put("lastUpdate", new Date (elm.getLastUpdateTime()).toString());
        node.put("timeToIdle", elm.getTimeToIdle());
        node.put("timeToLive", elm.getTimeToLive());
        node.put("isEternal", elm.isEternal());
        node.put("isExpired", elm.isExpired());
        return node;
    }

    @Security.Authenticated(Secured.class)    
    public static Result cache (String key) {
        try {
            Element elm = IxCache.getElm(key);
            if (elm == null) {
                return notFound ("Unknown cache: "+key);
            }

            return ok (toJson (elm));
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError ("No such cache key: "+key);
        }
    }

    @Security.Authenticated(Secured.class)    
    public static Result serverStatistics () {
        return ok (ix.ncats.views.html.serverstats.render
                   (IxCache.getStatistics()));
    }

    @Security.Authenticated(Secured.class)    
    public static Result cacheList (int top, int skip) {
        List keys = IxCache.getKeys(top, skip);
        if (keys != null) {
            ObjectMapper mapper = new ObjectMapper ();
            ArrayNode nodes = mapper.createArrayNode();
            for (Iterator it = keys.iterator(); it.hasNext(); ) {
                Object key = it.next();
                try {
                    Element elm = IxCache.getElm(key.toString());
                    if (elm != null)
                        nodes.add(toJson (mapper, elm));
                }
                catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            return ok (nodes);
        }
        return ok ("No cache available!");
    }

    @Security.Authenticated(Secured.class)    
    public static Result cacheClear () {
        try {
            IxCache.clearCache();
            return ok ();
        }
        catch (Exception ex) {
            ex.printStackTrace();
            return internalServerError (ex.getMessage());
        }
    }
    
    @Security.Authenticated(Secured.class)
    public static Result cacheDelete (String key) {
        try {
            Element elm = IxCache.getElm(key);
            if (elm == null) {
                return notFound ("Unknown cache: "+key);
            }
                
            if (IxCache.remove(key)) {
                return ok (toJson (elm));
            }
            
            return ok ("Can't remove cache element: "+key);
        }
        catch (Exception ex) {
            return internalServerError (ex.getMessage());
        }
    }
    
    public static int[] uptime () {
        int[] ups = null;
        if (Global.epoch != null) {
            ups = new int[3];
            // epoch in seconds
            long u = (new java.util.Date().getTime()
                      - Global.epoch.getTime())/1000;
            ups[0] = (int)(u/3600); // hour
            ups[1] = (int)((u/60) % 60); // min
            ups[2] = (int)(u%60); // sec
        }
        return ups;
    }

    public static Result getUptime () {
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode json = mapper.createObjectNode();
        int[] uptime = uptime ();
        json.put("time", new java.util.Date().toString());
        json.put("hour", uptime[0]);
        json.put("minute", uptime[1]);
        json.put("second", uptime[2]);
        return ok (json);
    }

    @BodyParser.Of(value = BodyParser.Text.class, maxLength = 1024 * 1024)
    public static Result molinstrument () {
        //String mime = request().getHeader("Content-Type");
        //Logger.debug("molinstrument: content-type: "+mime);
        
        ObjectMapper mapper = EntityFactory.getEntityMapper();
        ObjectNode node = mapper.createObjectNode();
        try {
            String payload = request().body().asText();
            if (payload != null) {
                List<Structure> moieties = new ArrayList<Structure>();
                Structure struc = StructureProcessor.instrument
                    (payload, moieties, false); // don't standardize!
                // we should be really use the PersistenceQueue to do this
                // so that it doesn't block
                struc.save();
                for (Structure m : moieties)
                    m.save();
                node.put("structure", mapper.valueToTree(struc));
                node.put("moieties", mapper.valueToTree(moieties));
            }
        }
        catch (Exception ex) {
            Logger.error("Can't process payload", ex);
            return internalServerError ("Can't process mol payload");       
        }
        return ok (node);
    }

    public static String getSequence (String id) {
        return getSequence (id, 0);
    }
    
    public static String getSequence (String id, int max) {
        String seq = PayloadFactory.getString(id);
        if (seq != null) {
            seq = seq.replaceAll("[\n\t\\s]", "");
            if (max > 0 && max+3 < seq.length()) {
                return seq.substring(0, max)+"...";
            }
            return seq;
        }
        return null;
    }

    public static String getPayload (String id, int max) {
        String payload = PayloadFactory.getString(id);
        if (payload != null) {
            int len = payload.length();
            if (max <= 0 || len +3 <= max)
                return payload;
            return payload.substring(0, max)+"...";
        }
        return null;
    }

    public static List<VInt> scaleFacetCounts (Facet facet, int scale) {
        return scaleFacetCounts (facet, scale, false);
    }
    
    public static List<VInt> scaleFacetCounts
        (Facet facet, int scale, boolean inverse) {
        List<VInt> values = new ArrayList<VInt>();
        if (facet != null) {
            int max = 0, min = Integer.MAX_VALUE;
            for (FV fv : facet.getValues()) {
                if (fv.getCount() > max)
                    max = fv.getCount();
                if (fv.getCount() < min)
                    min = fv.getCount();
            }
            
            if ((max-min) <= scale/2) {
                scale += scale/2;
            }
            
            
            for (FV fv : facet.getValues()) {
                VInt v = new VInt ();
                v.label = fv.getLabel();
                if (max == min) {
                    v.intval = (long)scale/2;
                }
                else if (inverse) {
                    v.intval =
                        (long)(0.5+(1. - (double)fv.getCount()/max)*scale);
                }
                else {
                    v.intval = (long)(0.5+(double)fv.getCount()*scale/max);
                }
                values.add(v);
            }
        }
        
        return values;
    }

    public static JsonNode getFacetJson (Facet facet) {
        return getFacetJson (facet, 20);
    }
    
    public static JsonNode getFacetJson (Facet facet, int max) {
        ObjectMapper mapper = new ObjectMapper ();
        ArrayNode nodes = mapper.createArrayNode();
        
        int others = 0;
        for (TextIndexer.FV fv : facet.getValues()) {
            if (nodes.size() < max) {
                ObjectNode n = mapper.createObjectNode();
                n.put("label", fv.getLabel());
                n.put("value", fv.getCount());
                nodes.add(n);
            }
            else {
                others += fv.getCount();
                break;
            }
        }

        /*
        if (others > 0) {
            ObjectNode n = mapper.createObjectNode();
            n.put("label", "Others");
            n.put("value", others);
            nodes.add(n);
        }
        */
        
        return nodes;
    }
    
    public static Integer getTermCount (Class kind, String label, String term) {
        TermVectors tvs =
            SearchFactory.getTermVectors(kind, label);
        return tvs != null ? tvs.getTermCount(term) : null;
    }
}
