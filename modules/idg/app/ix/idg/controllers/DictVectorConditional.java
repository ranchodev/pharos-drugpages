package ix.idg.controllers;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.Callable;

import ix.core.plugins.IxCache;
import ix.core.search.TextIndexer;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.TermVectors;

public class DictVectorConditional implements Serializable {
    private static final long serialVersionUID = 1L; //
    
    public final String condition;
    public final String[] terms;
    public final String[] categories;
    
    public final DictVector dv;    
    final Map<String, TermVectors> ctvs;
    
    protected DictVectorConditional
        (Class kind, String field, String condition) {
        dv = new DictVector (kind, field);
        ctvs = SearchFactory.getConditionalTermVectors(kind, field, condition);
        this.terms = dv.termRank;
        this.categories = ctvs.keySet().toArray(new String[0]);
        this.condition = condition;
    }

    public int count (int term, int cat) {
        TermVectors tv = ctvs.get(categories[cat]);
        Integer c = null;
        if (tv != null) {
            c = tv.getTermCount(terms[term]);
        }
        return c != null ? c : 0;
    }

    public static DictVectorConditional getInstance
        (final Class kind, final String field, final String condition) {
        final String key = DictVectorConditional.class.getName()
            +"/conditional/"+kind.getName()+"/"+field+"/"+condition;
        try {
            return IxCache.getOrElse
                (key, new Callable<DictVectorConditional> () {
                        public DictVectorConditional call () throws Exception {
                            return new DictVectorConditional
                                (kind, field, condition);
                        }
                    });
        }
        catch (Exception ex) {
            throw new RuntimeException (ex);        
        }
    }
}
