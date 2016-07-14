package ix.idg.controllers;

import java.io.Serializable;
import java.util.*;
import ix.idg.models.Target;
import ix.core.search.TextIndexer;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.TermVectors;

public class TermVectorConditional implements Serializable {
    public final Class kind;
    public final String field;
    public final String condition;
    public final String[] terms;
    public final String[] categories;
    
    final TermVectorSummary tvs;    
    final Map<String, TermVectors> ctvs;
    
    public TermVectorConditional (Class kind, String field, String condition) {
        tvs = new TermVectorSummary (kind, field);
        ctvs = SearchFactory.getConditionalTermVectors(kind, field, condition);
        this.terms = tvs.termRank;
        this.categories = ctvs.keySet().toArray(new String[0]);
        this.kind = kind;
        this.field = field;
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
}
