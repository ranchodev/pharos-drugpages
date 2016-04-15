package ix.idg.controllers;

import com.avaje.ebean.Expr;
import com.fasterxml.jackson.databind.ObjectMapper;
import ix.core.models.Value;
import ix.core.models.XRef;
import ix.idg.models.Expression;
import ix.idg.models.Target;
import ix.ncats.controllers.App;
import org.w3c.dom.*;
import play.Play;
import play.Logger;
import play.libs.XML;
import play.libs.XPath;
import play.mvc.Result;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileInputStream;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.Callable;

public class ExpressionApp extends App {

    static final Map<String, String> onm;

    static {
        onm = new LinkedHashMap<>();
        onm.put("bone", "bone");
        onm.put("nervous_system", "nervous_system");
        onm.put("nervous system", "nervous_system");
        onm.put("blood", "blood");
        onm.put("skin", "skin");
        onm.put("gall_bladder", "gall_bladder");
        onm.put("spleen", "spleen");
        onm.put("muscle", "muscle");
        onm.put("pancreas", "pancreas");
        onm.put("urine", "urine");
        onm.put("saliva", "saliva");
        onm.put("lymph_nodes", "lymph_nodes");
        onm.put("thyroid_gland", "thyroid_gland");
        onm.put("eye", "eye");
        onm.put("kidney", "kidney");
        onm.put("adrenal_gland", "adrenal_gland");
        onm.put("bone_marrow", "bone_marrow");
        onm.put("stomach", "stomach");
        onm.put("liver", "liver");
        onm.put("heart", "heart");
        onm.put("lung", "lung");
        onm.put("intestine", "intestine");

        onm.put("spinal", "nervous_system");
        onm.put("brain", "brain");
        onm.put("amygdala", "brain");
        onm.put("cortex", "brain");
        onm.put("pituitary", "brain");
        onm.put("cerebra", "brain");
        onm.put("cerebell", "brain");
        onm.put("artery", "blood");
        onm.put("circula", "blood");
        onm.put("skin", "skin");
        onm.put("sweat", "skin");
        onm.put("gall", "gall_bladder");
        onm.put("ureth", "urine");
        onm.put("lymph", "lymph_nodes");
        onm.put("thyroid", "thyroid_gland");
        onm.put("retina", "eye");
        onm.put("adrenal", "adrenal_gland");
        onm.put("bone_marrow", "bone_marrow");
        onm.put("marrow", "bone_marrow");
        onm.put("ileum", "intestine");
        onm.put("colon", "intestine");
        onm.put("digest", "intestine");

    }

    static ObjectMapper mapper = new ObjectMapper();

    public static Result error(int code, String mesg) {
        return ok(ix.idg.views.html.error.render(code, mesg));
    }

    public static Result _notFound(String mesg) {
        return notFound(ix.idg.views.html.error.render(404, mesg));
    }

    public static Result _badRequest(String mesg) {
        return badRequest(ix.idg.views.html.error.render(400, mesg));
    }

    public static Result _internalServerError(Throwable t) {
        t.printStackTrace();
        return internalServerError
                (ix.idg.views.html.error.render
                        (500, "Internal server error: " + t.getMessage()));
    }

    static String xml2str(Node node) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            StreamResult result = new StreamResult(new StringWriter());
            DOMSource source = new DOMSource(node);
            transformer.transform(source, result);
            return result.getWriter().toString();
        } catch (TransformerException ex) {
            ex.printStackTrace();
            return null;
        }
    }

    public static Map<String, Integer> getExprTissue(String q) {
        List<Target> targets = TargetFactory.finder.where().eq("uniprotId", q).findList();
        if (targets.size() == 0) return null;
        Target t = targets.get(0);
        for (Value v : t.getProperties()) {
            System.out.println(v.label);
        }
        return null;
    }

    public static List<Expression> getLinkedExpr(Target t, String sourceId) {
        List<Expression> ret = new ArrayList<>();
        for (XRef xref : t.getLinks()) {
            if (!xref.kind.equals(Expression.class.getName())) continue;
            Expression expr = (Expression) xref.deRef();
            if (expr.getSourceid() != null && expr.getSourceid().equals(sourceId))
                ret.add(expr);
        }

        if (ret.size() > 0) {
            // sort expression either by qual value or by numeric value
            if (ret.get(0).getNumberValue() == null) {
                Collections.sort(ret, new Comparator<Expression>() {
                    @Override
                    public int compare(Expression o1, Expression o2) {
                        return o1.getQualValue().compareTo(o2.getQualValue());
                    }
                });
            } else {
                Collections.sort(ret, new Comparator<Expression>() {
                    @Override
                    public int compare(Expression o1, Expression o2) {
                        return -1 * o1.getNumberValue().compareTo(o2.getNumberValue());
                    }
                });
            }
        }

        return ret;
    }

    public static Result homunculus(final String acc, final String source) throws Exception {
        if (acc == null)
            return _badRequest("Must specify a target accession");
        final String key = "expression/homunculus/" + acc + "/" + source;
        response().setContentType("image/svg+xml");
        return getOrElse(key, new Callable<Result>() {
            public Result call() throws Exception {
                List<Target> targets = TargetFactory.finder
                        .where(Expr.and(Expr.eq("synonyms.label", Commons.UNIPROT_ACCESSION),
                                Expr.eq("synonyms.term", acc))).findList();
                if (targets.size() == 0) return _notFound("No data found for " + acc);
                Target t = targets.get(0);

                // iterate over tissue names and map them to the SVG id's
                // may need to update mapping
                String ds = Commons.GTEx_EXPR;
                if (source != null) {
                    if ("uniprot".equalsIgnoreCase(source))
                        ds = Commons.UNIPROT_EXPR;
                    else if ("hpm".equalsIgnoreCase(source))
                        ds = Commons.HPM_EXPR;
                    else if ("gtex".equalsIgnoreCase(source))
                        ds = Commons.GTEx_EXPR;
                    else if ("hpa".equalsIgnoreCase(source))
                        ds = Commons.HPA_RNA_EXPR;
                    else if ("uniprot".equalsIgnoreCase(source))
                        ds = Commons.UNIPROT_EXPR;
                    else if ("jensen-tm".equalsIgnoreCase(source))
                        ds = Commons.JENSEN_TM_EXPR;
                    else if ("jensen-kb".equalsIgnoreCase(source))
                        ds = Commons.JENSEN_KB_EXPR;
                    else if ("idg".equalsIgnoreCase(source))
                        ds = Commons.IDG_EXPR;
                }

                HashMap<String, Integer> organsLevel = new HashMap<>();
                HashMap<String, Integer> organsConf = new HashMap<>();
                for (XRef xref : t.getLinks()) {
                    if (!xref.kind.equals(Expression.class.getName())) continue;
                    Expression expr = (Expression) xref.deRef();

                    // TODO HPM PROTEIN should get a key in Commons
                    if (expr.getSourceid() == null || !expr.getSourceid().equals(ds)) continue;

                    // map to canonical organ terms
                    for (String key : onm.keySet()) {
                        if (expr.getTissue().toLowerCase().contains(key)) {

                            // derive the expr level or confidence
                            Integer conf = expr.getConfidence().intValue();
                            Integer level = -1;
                            String qual = expr.getQualValue();
                            if (qual == null)
                                ;
                            else if (qual.equalsIgnoreCase("low")) level = 0;
                            else if (qual.equalsIgnoreCase("medium")) level = 1;
                            else if (qual.equalsIgnoreCase("high")) level = 2;

                            String tissue = onm.get(key);
                            if (organsLevel.containsKey(tissue)) {
                                Integer tmp = organsLevel.get(tissue);
                                if (level > tmp) organsLevel.put(tissue, level);
                            } else organsLevel.put(tissue, level);

                            if (organsConf.containsKey(tissue)) {
                                Integer tmp = organsConf.get(tissue);
                                if (conf > tmp) organsConf.put(tissue, conf);
                            } else organsConf.put(tissue, conf);

                        }
                    }
                }

                String[] confidenceColorsIDG = new String[]{
                        "#ffffff", "#EDF8E9", "#BAE4B3", "#74C476", "#31A354", "#006D2C"
                };
                String[] colorsConsHigh = new String[]{
                        "#ffe6e6", "#ffb3b3", "#ff6666", "#ff0000", "#b30000", "#660000"
                };
                String[] colorsConsMedium = new String[]{
                        "#e6e6ff", "#b3b3ff", "#6666ff", "#0000ff", "#0000b3", "#000066"
                };
                String[] colorsConsLow = new String[]{
                        "#e6ffe6", "#b3ffb3", "#66ff66", "#00ff00", "#00b300", "#006600"
                };
                String[] confidenceColorsOther = new String[]{
                        "#EDF8E9", "#74C476", "#006D2C"
                };

                String suffix = "";
                if (ds.equals(Commons.IDG_EXPR)) suffix = "-cons";
                else if (!ds.equals(Commons.JENSEN_TM_EXPR)
                         && !ds.equals(Commons.JENSEN_KB_EXPR))
                    suffix = "-qual";

                Document doc;
                if (Play.isProd()) {
                    doc = XML.fromInputStream
                            (Play.application().resourceAsStream
                                    ("public/tissues_body_human" + suffix + ".svg"), "UTF-8");
                } else {
                    File svg = Play.application().getFile
                            ("app/assets/tissues_body_human" + suffix + ".svg");
                    FileInputStream fis = new FileInputStream(svg);
                    doc = XML.fromInputStream(fis, "UTF-8");
                }

                for (String tissue : organsLevel.keySet()) {
                    Node node = XPath.selectNode("//*[@id='" + tissue + "']", doc);
                    NamedNodeMap attributes = node == null ? null : node.getAttributes();
                    if (attributes == null) continue;
                    Node attrNode = attributes.getNamedItem("style");
                    if (attrNode == null) {
                        Attr attr = doc.createAttribute("style");
                        attr.setValue("");
                        attributes.setNamedItem(attr);
                        attrNode = attributes.getNamedItem("style");
                    }
                    String color = "";
                    switch (ds) {
                    case Commons.JENSEN_KB_EXPR:
                    case Commons.JENSEN_TM_EXPR:
                        color = confidenceColorsIDG[organsConf.get(tissue)];
                        break;
                    case Commons.UNIPROT_EXPR:
                        color = "#006D2C"; // this is curated data
                        break;
                    case Commons.IDG_EXPR:
                        int level = organsLevel.get(tissue);
                        int conf = organsConf.get(tissue);
                        
                        switch (level) {
                        case 0: // low
                            color = colorsConsLow[conf];
                            break;
                        case 1: // medium
                            color = colorsConsMedium[conf];
                            break;
                        case 2: //high
                            color = colorsConsHigh[conf];
                            break;
                        }
                        break;
                    default:
                        Integer index = organsLevel.get(tissue);
                        if (index != null && index >= 0
                            && index < confidenceColorsOther.length)
                            color = confidenceColorsOther[index];
                        else 
                            Logger.warn(acc+": invalid index "+index
                                        +" for source \""+source
                                        +"\" and tissue \""+tissue+"\"!");
                        break;
                    }
                    attrNode.setNodeValue("fill:" + color + ";");
                    attributes.setNamedItem(attrNode);
                    colorChildren(node, color, doc);

                }
                return ok(xml2str(doc));
            }
        });
    }

    static void colorChildren(Node node, String color, Document doc) {
        NodeList children = XPath.selectNodes("*", node);
        if (children == null || children.getLength() == 0) return;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            NamedNodeMap childAttrs = child.getAttributes();
            Attr attr = doc.createAttribute("style");
            attr.setNodeValue("fill:" + color + ";");
            childAttrs.setNamedItem(attr);
            colorChildren(child, color, doc);
        }
    }


}
