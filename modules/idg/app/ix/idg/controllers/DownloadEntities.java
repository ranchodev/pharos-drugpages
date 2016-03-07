package ix.idg.controllers;

import ix.core.models.*;
import ix.idg.models.Disease;
import ix.idg.models.Ligand;
import ix.idg.models.Target;

import java.util.List;

/**
 * @author Rajarshi Guha
 */
public class DownloadEntities {

    static String csvFromLigand(Ligand l) throws ClassNotFoundException {

        String inchiKey = "";
        String canSmi = "";

        for (Value v : l.getProperties()) {
            if (Commons.ChEMBL_INCHI_KEY.equals(v.label))
                inchiKey = (String) v.getValue();
            else if (Commons.ChEMBL_SMILES.equals(v.label))
                canSmi = (String) v.getValue();
        }

        StringBuilder sb2 = new StringBuilder();
        String delimiter = "";
        List<XRef> links = l.getLinks();
        for (XRef xref : links) {
            if (Target.class.isAssignableFrom(Class.forName(xref.kind))) {
                sb2.append(delimiter).append(IDGApp.getId((Target) xref.deRef()));
                delimiter = "|";
            }
        }


        StringBuilder sb = new StringBuilder();
        sb.append(routes.IDGApp.ligand(IDGApp.getId(l))).append(",").
                append(IDGApp.getId(l)).append(",").
                append(csvQuote(l.getName())).append(",").
                append(csvQuote(l.getDescription())).append(",").
                append(canSmi).append(",").
                append(inchiKey).append(",").
                append(sb2.toString());
        return sb.toString();
    }

    static String csvFromTarget(Target t) {
        Object novelty = "";
        Object function = "";

        StringBuilder sb2 = new StringBuilder();
        String delimiter = "";
        for (Publication pub : t.getPublications()) {
            sb2.append(delimiter).append(pub.pmid);
            delimiter = "|";
        }

        List<Value> props = t.getProperties();
        for (Value v : props) {
            if (v.label.equals("TINX Novelty")) novelty = v.getValue();
            else if (v.label.equals("function")) function = v.getValue();
        }

        // get classifications
        String chemblClass = "";
        String dtoClass = "";
        String pantherClass = "";
        for (Value v : t.properties) {
            if (v.label == null) continue;
            if (v.label.startsWith(Commons.DTO_PROTEIN_CLASS))
                dtoClass = ((Keyword) v).getValue();
            else if (v.label.startsWith(Commons.PANTHER_PROTEIN_CLASS))
                pantherClass = ((Keyword) v).getValue();
            else if (v.label.startsWith(Commons.ChEMBL_PROTEIN_CLASS))
                chemblClass = ((Keyword) v).getValue();
        }


        StringBuilder sb = new StringBuilder();
        sb.append(routes.IDGApp.target(csvQuote(IDGApp.getId(t)))).append(",").
                append(csvQuote(IDGApp.getId(t))).append(",").
                append(csvQuote(t.getName())).append(",").
                append(csvQuote(t.getDescription())).append(",").
                append(csvQuote(t.idgTDL.toString())).append(",").
                append(csvQuote(dtoClass)).append(",").
                append(csvQuote(pantherClass)).append(",").
                append(csvQuote(chemblClass)).append(",").
                append(csvQuote((String) novelty)).append(",").
                append(csvQuote(t.idgFamily)).append(",").
                append(csvQuote(function.toString())).append(",").
                append(csvQuote(String.valueOf(t.r01Count))).append(",").
                append(csvQuote(String.valueOf(t.patentCount))).append(",").
                append(csvQuote(String.valueOf(t.antibodyCount))).append(",").
                append(csvQuote(String.valueOf(t.pubmedCount))).append(",").
                append(csvQuote(sb2.toString()));
        return sb.toString();
    }

    static String csvFromDisease(Disease d) throws ClassNotFoundException {
        StringBuilder sb2 = new StringBuilder();
        String delimiter = "";
        List<XRef> links = d.getLinks();
        for (XRef xref : links) {
            if (Target.class.isAssignableFrom(Class.forName(xref.kind))) {
                sb2.append(delimiter).append(IDGApp.getId((Target) xref.deRef()));
                delimiter = "|";
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(routes.IDGApp.disease(IDGApp.getId(d))).append(",").
                append(IDGApp.getId(d)).append(",").
                append(csvQuote(d.getName())).append(",").
                append(csvQuote(d.getDescription())).append(",").
                append(sb2.toString());
        return sb.toString();
    }

    static String csvQuote(String s) {
        if (s == null || s.trim().equals("null")) return "";
        if (s.contains("\"")) s = s.replace("\"", "\\\"");
        if (s.contains(" ") || s.contains(","))
            return "\"" + s + "\"";
        else return s;
    }

    static byte[] downloadTargets(List<Target> targets) {
        StringBuilder sb = new StringBuilder();
        String tmp = "URL,Uniprot ID,Name,Description,Development Level,DTOClass,PantherClass,ChemblClass,Novelty,Target Family,Function," +
                "R01Count,PatentCount,AntibodyCount,PubmedCount,PMIDCount";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(csvFromTarget(t)).append("\n");
        }
        return sb.toString().getBytes();
    }

    static byte[] downloadDiseases(List<Disease> diseases) throws ClassNotFoundException {
        StringBuilder sb = new StringBuilder();
        sb.append("URL,DOID,Name,Description,Targets\n");
        for (Disease d : diseases) {
            sb.append(csvFromDisease(d)).append("\n");
        }
        return sb.toString().getBytes();
    }

    static byte[] downloadLigands(List<Ligand> ligands) throws ClassNotFoundException {
        StringBuilder sb = new StringBuilder();
        sb.append("URL,ID,Name,Description,SMILES,InChI Key,Targets\n");
        for (Ligand l : ligands) {
            sb.append(csvFromLigand(l)).append("\n");
        }
        return sb.toString().getBytes();
    }

    public static <T extends EntityModel> byte[] downloadEntities(List<T> entities) throws ClassNotFoundException {
        if (entities.size() == 0) return new byte[]{};
        Class eclass = entities.get(0).getClass();
        if (Target.class.isAssignableFrom(eclass))
            return downloadTargets((List<Target>) entities);
        else if (Ligand.class.isAssignableFrom(eclass))
            return downloadLigands((List<Ligand>) entities);
        else if (Disease.class.isAssignableFrom(eclass))
            return downloadDiseases((List<Disease>) entities);
        else throw new IllegalArgumentException("Must supply disease, ligand or target entities for download");
    }

    public static String getDownloadMimeType(Class klass) {
        if (Target.class.isAssignableFrom(klass))
            return "text/csv";
        else if (Ligand.class.isAssignableFrom(klass))
            return "text/csv";
        else if (Disease.class.isAssignableFrom(klass))
            return "text/csv";
        else throw new IllegalArgumentException("Must supply objects of class Target, Disease or Ligand");
    }
}
