package ix.idg.controllers;

import ix.core.models.*;
import ix.idg.models.Disease;
import ix.idg.models.Expression;
import ix.idg.models.Ligand;
import ix.idg.models.Target;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

    static String diseaseFromTarget(Target t) throws Exception {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        List<IDGApp.DiseaseRelevance> diseases = IDGApp.getDiseases(t);
        for (IDGApp.DiseaseRelevance dr : diseases) {
            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(IDGApp.getId(dr.disease)).append(",").
                    append(csvQuote(dr.disease.getName())).append(",").
                    append(csvQuote(dr.disease.getDescription())).append(",").
                    append(csvQuote(dr.zscore)).append(",").
                    append(dr.conf).append(",").
                    append("http://diseases.jensenlab.org/Entity?documents=10&type1=9606&id2=" + IDGApp.getId(dr.disease) + "&id1=" + t.getSynonym(Commons.STRING_ID).term).
                    append("\n");
        }
        return sb2.toString();
    }

    static String exprFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        String[] exprSources = new String[]{Commons.IDG_EXPR, Commons.GTEx_EXPR, Commons.HPM_EXPR, Commons.HPA_RNA_EXPR};
        for (String source : exprSources) {
            List<Expression> exprs = ExpressionApp.getLinkedExpr(t, source);
            for (Expression expr : exprs) {
                sb2.append(turl).append(",").
                        append(uniprot).append(",").
                        append(csvQuote(expr.getSource())).append(",").
                        append(csvQuote(expr.getTissue())).append(",").
                        append(expr.getNumberValue()).append(",").
                        append(expr.getQualValue()).append(",").
                        append(expr.getConfidence()).append(",").
                        append(expr.getEvidence() == null ? "" : expr.getEvidence()).append(",").
                        append("\n");
            }
        }
        return sb2.toString();
    }

    static String pubsFromTarget(Target t) throws Exception {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        List<Publication> pubs = IDGApp.getPublications(t);
        for (Publication p : pubs) {
            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(p.pmid).append(",").
                    append(csvQuote(p.title)).append(",").
                    append(csvQuote(p.abstractText)).append(",").
                    append("\n");
        }
        return sb2.toString();
    }

    static String generifFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        List<IDGApp.GeneRIF> rifs = IDGApp.getGeneRIFs(t);
        for (IDGApp.GeneRIF rif : rifs) {
            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(rif.pmid).append(",").
                    append(csvQuote(rif.text)).append("\n");
        }
        return sb2.toString();
    }

    static String ligandFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));


        long start = System.currentTimeMillis();
        List<Ligand> ligands = IDGApp.getLigandsWithActivity(t);
        long end = System.currentTimeMillis();
//        System.out.println("Got "+ligands.size()+" in "+ ((end-start)/1000.0)+"s for "+IDGApp.getId(t));
        for (Ligand l : ligands) {
            VNum act = IDGApp.getActivity(t, l);
            String actVal = act == null ? "" : act.getNumval().toString();
            String actUnit = act == null ? "" : act.label;
            String ligType = "";
            if (l.getSynonym(Commons.IDG_DRUG) != null)
                ligType = "Approved Drug";

            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(csvQuote(l.getName())).append(",").
                    append(ligType).append(",").
                    append(csvQuote(l.getDescription())).append(",").
                    append(IDGApp.getStructure(l) == null ? "" : IDGApp.getStructure(l).smiles).append(",").
                    append(routes.IDGApp.ligand(IDGApp.getId(l))).append(",").
                    append(actVal).append(",").
                    append(actUnit).append(",").
                    append("\n");
        }
        return sb2.toString();
    }

    static String upkwdFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        List<Value> props = IDGApp.getProperties(t, Commons.UNIPROT_KEYWORD);
        for (Value prop : props) {
            Keyword kw = (Keyword) prop;
            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(csvQuote(kw.getValue())).append(",").
                    append(kw.href).append("\n");
        }
        return sb2.toString();
    }

    static String pathwayFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        List<Value> props = IDGApp.getProperties(t, "Pathway", 1);
        for (Value prop : props) {
            Keyword kw = (Keyword) prop;
            sb2.append(turl).append(",").
                    append(uniprot).append(",").
                    append(csvQuote(kw.getValue())).append(",").
                    append(csvQuote(kw.label)).append(",").
                    append(kw.href).append("\n");
        }
        return sb2.toString();
    }

    static String goFromTarget(Target t) {
        StringBuilder sb2 = new StringBuilder();
        String turl = routes.IDGApp.target(csvQuote(IDGApp.getId(t))).toString();
        String uniprot = csvQuote(IDGApp.getId(t));

        String[] go = new String[]{Commons.GO_COMPONENT, Commons.GO_FUNCTION, Commons.GO_PROCESS};
        for (String goclass : go) {
            List<Value> props = IDGApp.getProperties(t, goclass);
            for (Value prop : props) {
                sb2.append(turl).append(",").
                        append(uniprot).append(",").
                        append(csvQuote(prop.getValue().toString())).append(",").
                        append(goclass).append("\n");
            }
        }
        return sb2.toString();
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
                append(csvQuote(String.valueOf(t.grantCount))).append(",").
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

    static String csvQuote(Double d) {
        return csvQuote(String.valueOf(d));
    }

    static String csvQuote(String s) {
        if (s == null || s.trim().equals("null")) return "";
        if (s.contains("\"")) s = s.replace("\"", "\\\"");
        if (s.contains(" ") || s.contains(","))
            return "\"" + s + "\"";
        else return s;
    }

    static byte[] downloadTargets(List<Target> targets) throws Exception {

        StringBuilder sb = new StringBuilder();

        // basic target info
        String tmp = "URL,Uniprot ID,Name,Description,Development Level,DTOClass,PantherClass,ChemblClass,Novelty,Target Family,Function," +
                "GrantCount,R01Count,PatentCount,AntibodyCount,PubmedCount,PMIDs";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(csvFromTarget(t)).append("\n");
        }
        byte[] targetFile = sb.toString().getBytes();

        // GO terms
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,GOTerm,GOType";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(goFromTarget(t));
        }
        byte[] goFile = sb.toString().getBytes();

        // Pathways
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,Name,Source,Link";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(pathwayFromTarget(t));
        }
        byte[] pathwayFile = sb.toString().getBytes();

        // Uniprot Keywords
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,Keyword,Link";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(upkwdFromTarget(t));
        }
        byte[] upkwdFile = sb.toString().getBytes();

        // Ligands
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,Name,Type,Description,SMILES,Link,ChEMBL Activity,ChEMBL Activity Type";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
//        for (Target t : targets) {
//            sb.append(ligandFromTarget(t));
//        }
        byte[] ligandFile = sb.toString().getBytes();

        // Gene RIFs
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,PMID,Abstract";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(generifFromTarget(t));
        }
        byte[] generifFile = sb.toString().getBytes();

        // Publications
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,PMID,Title,Abstract";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(pubsFromTarget(t));
        }
        byte[] pubsFile = sb.toString().getBytes();

        // Expression
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,Source,Tissue,NumericValue,QualitativeValue,Confidence,Evidence";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(exprFromTarget(t));
        }
        byte[] exprFile = sb.toString().getBytes();

        // Diseases
        sb = new StringBuilder();
        tmp = "URL,Uniprot ID,DOID,Name,Description,ZScore,Confidence,Link";
        tmp = tmp.replace(",", "\",\"");
        tmp = "\"" + tmp + "\"\n";
        sb.append(tmp);
        for (Target t : targets) {
            sb.append(diseaseFromTarget(t));
        }
        byte[] diseaseFile = sb.toString().getBytes();

        // Generate zip file with the components
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(baos);

        ZipEntry entry = new ZipEntry("targets.csv");
        zip.putNextEntry(entry);
        zip.write(targetFile);
        zip.closeEntry();

        entry = new ZipEntry("goterms.csv");
        zip.putNextEntry(entry);
        zip.write(goFile);
        zip.closeEntry();

        entry = new ZipEntry("pathways.csv");
        zip.putNextEntry(entry);
        zip.write(pathwayFile);
        zip.closeEntry();

        entry = new ZipEntry("uniprot-keywords.csv");
        zip.putNextEntry(entry);
        zip.write(upkwdFile);
        zip.closeEntry();

        entry = new ZipEntry("ligands.csv");
        zip.putNextEntry(entry);
        zip.write(ligandFile);
        zip.closeEntry();

        entry = new ZipEntry("generifs.csv");
        zip.putNextEntry(entry);
        zip.write(generifFile);
        zip.closeEntry();

        entry = new ZipEntry("publications.csv");
        zip.putNextEntry(entry);
        zip.write(pubsFile);
        zip.closeEntry();

        entry = new ZipEntry("expression.csv");
        zip.putNextEntry(entry);
        zip.write(exprFile);
        zip.closeEntry();

        entry = new ZipEntry("diseases.csv");
        zip.putNextEntry(entry);
        zip.write(diseaseFile);
        zip.closeEntry();

        zip.finish();
        zip.close();

        return baos.toByteArray();
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

    public static <T extends EntityModel> byte[] downloadEntities(List<T> entities) throws Exception {
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
            return "application/zip";
        else if (Ligand.class.isAssignableFrom(klass))
            return "text/csv";
        else if (Disease.class.isAssignableFrom(klass))
            return "text/csv";
        else throw new IllegalArgumentException("Must supply objects of class Target, Disease or Ligand");
    }
}
