package ix.idg.controllers;

import com.avaje.ebean.Expr;
import ix.core.controllers.KeywordFactory;
import ix.core.controllers.NamespaceFactory;
import ix.core.models.Keyword;
import ix.core.models.Namespace;
import ix.core.models.Text;
import ix.core.models.XRef;
import ix.idg.models.Disease;
import play.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DiseaseOntologyRegistry {
    public static final String DOID = "DOID";
    public static final String CLASS = "Disease Class";
    public static final String PATH = "Disease Path";
    public static final String IS_A = "is_a";
    
    static Namespace namespace = NamespaceFactory.registerIfAbsent
        ("Disease Ontology", "http://www.disease-ontology.org");

    Keyword source;
    Map<String, String> parents  = new HashMap<String, String>();
    Map<String, Disease> diseaseMap = new HashMap<String, Disease>();
    
    public DiseaseOntologyRegistry () {
    }
    
    public Keyword getSource () { return source; }

    /**
     * bulk registration from .obo file
     */
    public Map<String, Disease> register (InputStream is) throws IOException {
        OboParser obo = new OboParser (is);
        source = KeywordFactory.registerIfAbsent
            (Commons.SOURCE, "DiseaseOntology v"+obo.version,
             "http://www.disease-ontology.org");

        int n = 0;
        while (obo.next()) {
            if (obo.obsolete)
                continue;
            
            List<Disease> diseases = DiseaseFactory.finder
                .where(Expr.and(Expr.eq("synonyms.label", DOID),
                                Expr.eq("synonyms.term", obo.id)))
                .findList();
            if (diseases.isEmpty()) {
                Disease disease = new Disease ();
                disease.name = obo.name;
                disease.description = obo.def;
                Keyword kw = new Keyword (DOID, obo.id);
                kw.href = "http://www.disease-ontology.org/term/"+obo.id;
                disease.synonyms.add(kw);
                disease.properties.add(source);
                for (String id : obo.alts) {
                    kw = new Keyword (DOID, id);
                    //kw.href = "http://www.disease-ontology.org/api/metadata/"+id;
                    disease.synonyms.add(kw);
                }
                for (String alias : obo.synonyms) {
                    disease.synonyms.add(new Keyword ("Keyword", alias));
                }

                if (obo.parentId != null) {
                    parents.put(obo.id, obo.parentId);
                }
                disease.save();
                Disease d = diseaseMap.put(obo.id, disease);
                if (d != null) {
                    Logger.warn(obo.id+" maps to "+disease.id+" ("+disease.name
                                +") and "+d.id+" ("+d.name+")");
                }


                // TODO we should probably resolve these with the appropriate xternal resource
                //                      int nxref = 0;
                //                      for (String xref : obo.xrefs) {
                //                              String ns = xref.split(":")[0];
                //                              String xid = xref.split(":")[1];
                //
                //                              XRef x = new XRef(disease);
                //                              if (ns.equals("OMIM"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("OMIM", "http://www.omim.org/");
                //                              else if (ns.equals("MSH"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("MeSH", "http://www.ncbi.nlm.nih.gov/mesh");
                //                              else if (ns.equals("NCI"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("NCI", "http://www.cancer.gov/");
                //                              else if (ns.startsWith("SNOMEDCT"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("SNOMED", "http://www.nlm.nih.gov/research/umls/Snomed/snomed_main.html");
                //                              else if (ns.equals("UMLS_CUI"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("UMLS_CUI", "http://www.nlm.nih.gov/research/umls");
                //                              else if (ns.equals("ICD9CM"))
                //                                      x.namespace = NamespaceFactory.registerIfAbsent("ICD9CM", "http://www.cdc.gov/nchs/icd/icd9cm.htm");
                //                              x.properties.add(new Keyword(ns, xref)); // TODO should we be adding actual objects representing these namespaces?
                //                              disease.links.add(x);
                //                              nxref++;
                //                      }

                // for now let also put these xref entries into the properties
                int nxref = 0;
                for (String xref : obo.xrefs) {
                    nxref++;
                    String ns = xref.split(":")[0];
                    kw = new Keyword(ns, xref);
                    disease.synonyms.add(kw);
                }
                if (n % 100 == 0)
                    Logger.debug(disease.id + ": " + obo.id
                            + " " + obo.name + " with " + nxref + " xref's");
            }
        }

        // now resolve references
        /*
        for (Map.Entry<String, String> me : parents.entrySet()) {
            Disease child = diseaseMap.get(me.getKey());
            Disease parent = diseaseMap.get(me.getValue());
            if (parent == null) {
                List<Disease> diseases = DiseaseFactory.finder
                    .where(Expr.and(Expr.eq("synonyms.label", "DOID"),
                                    Expr.eq("synonyms.term", me.getValue())))
                    .findList();
                if (diseases.isEmpty()) {
                    Logger.warn("Disease "+child.id+"["+me.getKey()
                                +"] references unknown parent "+me.getValue());
                }
                else {
                    parent = diseases.iterator().next();
                    
                }
            }

            if (parent != null) {
                XRef xref = createXRef (parent);
                xref.properties.add(new Text (IS_A, parent.name));
                child.links.add(xref);
                child.update();
            }
        }
        */

        Logger.debug("Computing lineages for "+diseaseMap.size()+" disease");
        for (Map.Entry<String, Disease> me : diseaseMap.entrySet()) {
            List<Disease> lineage = getLineage (me.getKey());
            Disease disease = me.getValue();
//            Logger.debug(me.getKey()+": "+disease.name
//                         +" has "+lineage.size()+" lineage");
            StringBuilder path = new StringBuilder ();
            for (int i = lineage.size(); --i >= 0;) {
                Disease d = lineage.get(i);
                path.append("/"+d.name);
                Keyword kw = new Keyword (CLASS, d.name);
                disease.properties.add(kw);
            }
            disease.properties.add(new Text (PATH, path.toString()));
            if (!lineage.isEmpty()) {
                Disease parent = lineage.get(0);
                //parent.save();
                XRef xref = createXRef (parent);
                xref.properties.add(new Text (IS_A, parent.name));
                disease.links.add(xref);
            }
            disease.save();
        }
        
        Logger.debug(diseaseMap.size()+" disease(s) registered!");
        return diseaseMap;
    }

    List<Disease> getLineage (String id) {
        List<Disease> lineage = new ArrayList<Disease>();
        String p = parents.get(id);
        while (p != null) {
            Disease d = diseaseMap.get(p);
            if (d != null) {
                lineage.add(d);
            }
            else {
                Logger.warn("Unknown disease id: "+p);
            }
            p = parents.get(p);
        }
        return lineage;
    }
    
    public XRef createXRef (Object obj) {
        XRef xref = new XRef (obj);
        xref.namespace = namespace;
        return xref;
    }
}
