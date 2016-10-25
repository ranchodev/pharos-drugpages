package ix.idg.controllers;

import play.Logger;

import java.util.concurrent.Callable;
import java.io.Serializable;
import java.util.*;
import java.lang.reflect.Field;

import ix.core.plugins.IxCache;
import ix.core.ObjectFactory;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.TermVectors;


public class DictVector extends Vector {
    private static final long serialVersionUID = 1L; //
    
    public final TermVectors tvec;
    public final String[] termRank;
    public final int[] termCount;
    public final Map<Long, Integer> dict;
    public final int[] termCountProfile;
    public final Double avgTermsPerDoc;
    
    protected DictVector (Class kind, String field) {
        super (kind, field);
        
        tvec = SearchFactory.getTermVectors(kind, field);
        Set<String> order = new TreeSet<String>
            (new Comparator<String>() {
                    public int compare (String t1, String t2) {
                        int d = tvec.getTermCount(t2) - tvec.getTermCount(t1);
                        if (d == 0)
                            d = t1.compareTo(t2);
                        return d;
                    }
                });
        order.addAll(tvec.getTerms().keySet());
        termRank = order.toArray(new String[0]);
        termCount = new int[termRank.length];
        for (int i = 0; i < termRank.length; ++i)
            termCount[i] = tvec.getTermCount(termRank[i]);
        
        dict = new HashMap<Long, Integer>();       
        List<Map> docs = tvec.getDocs();
        if (!docs.isEmpty()) {
            int size = Math.min(docs.size(), 5);
            int[] terms = null;
            int total = 0, min = Integer.MAX_VALUE, max = 0;
            for (int i = 0; i < docs.size(); ++i) {
                Integer nterms = (Integer)docs.get(i).get("nTerms");
                if (i == 0) {
                    terms = new int[nterms+1];
                }
                
                if (i < size) {
                    Long id = (Long)docs.get(i).get("doc");
                    dict.put(id, nterms);
                }
                
                if (nterms < min) min = nterms;
                if (nterms > max) max = nterms;
                this.values.add(nterms);
                total += nterms;
                ++terms[nterms];
            }
            this.min = min;
            this.max = max;
            terms[0] = tvec.getNumDocs() - tvec.getNumDocsWithTerms();
            termCountProfile = terms;
            avgTermsPerDoc = (double)total / tvec.getNumDocs();
        }
        else {
            termCountProfile = new int[0];
            avgTermsPerDoc = 0.;
        }
    }

    public static DictVector getInstance (final Class kind,
                                          final String field) {
        final String key = DictVector.class.getName()+"/"
            +kind.getName()+"/"+field;
        try {
            return IxCache.getOrElse(key, new Callable<DictVector> () {
                    public DictVector call () throws Exception {
                        return new DictVector (kind, field);
                    }
                });
        }
        catch (Exception ex) {
            throw new RuntimeException (ex);
        }
    }

    public int getSingletonCount () {
        int singletons = 0;
        for (int i = 0; i < termCount.length; ++i)
            if (termCount[i] == 1)
                ++singletons;
        return singletons;
    }

    public Integer getTermCount (String term) {
        return tvec.getTermCount(term);
    }
    
    public int getNumTerms () { return tvec.getNumTerms(); }
    public int getNumDocs () { return tvec.getNumDocs(); }

    public <T> Map<T, Integer> getDictObjectMap (Class<T> cls) {
        Map<T, Integer> map = new HashMap<T,Integer>();
        for (Map.Entry<Long, Integer> me : dict.entrySet()) {
            try {
                T obj = (T)ObjectFactory.get(cls, me.getKey());
                map.put(obj, me.getValue());
            }
            catch (Exception ex) {
                Logger.trace("Can't retrieve object for "
                             +cls+" id="+me.getKey(), ex);
            }
        }
        return map;
    }
}
