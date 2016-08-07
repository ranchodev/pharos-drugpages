package ix.idg.controllers;

import java.io.Serializable;
import java.util.*;
import ix.idg.models.Target;
import ix.core.search.TextIndexer;
import ix.core.controllers.search.SearchFactory;
import static ix.core.search.TextIndexer.TermVectors;

public class TermVectorSummary implements Serializable {
    public final Class kind;
    public final String field;
    public final TermVectors tvec;
    public final String[] termRank;
    public final int[] termCount;
    public final Map<Target, Integer> targets;
    public final int[] termCountProfile;
    public final Double avgTermsPerDoc;
    
    public TermVectorSummary (Class kind, String field) {
        this.kind = kind;
        this.field = field;
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
        
        targets = new HashMap<Target, Integer>();       
        List<Map> docs = tvec.getDocs();
        if (!docs.isEmpty()) {
            int size = Math.min(docs.size(), 5);
            int[] terms = null;
            int total = 0;
            for (int i = 0; i < docs.size(); ++i) {
                Integer nterms = (Integer)docs.get(i).get("nTerms");
                if (i == 0) {
                    terms = new int[nterms+1];
                }
                
                if (i < size) {
                    Long id = (Long)docs.get(i).get("doc");
                    Target target = TargetFactory.getTarget(id);
                    if (target != null) 
                        targets.put(target, nterms);
                }
                total += nterms;
                ++terms[nterms];
            }
            terms[0] = tvec.getNumDocs() - tvec.getNumDocsWithTerms();
            termCountProfile = terms;
            avgTermsPerDoc = (double)total / tvec.getNumDocs();
        }
        else {
            termCountProfile = new int[0];
            avgTermsPerDoc = 0.;
        }
    }

    public int getSingletonCount () {
        int singletons = 0;
        for (int i = 0; i < termCount.length; ++i)
            if (termCount[i] == 1)
                ++singletons;
        return singletons;
    }
}
