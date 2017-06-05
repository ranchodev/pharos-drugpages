package ix.idg.controllers;

import play.Logger;
import java.util.*;
import java.util.regex.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ix.core.models.*;
import ix.idg.models.Target;

public class Sequence {
    static final char[] AA = {
        'A', // Ala
        'R', // Arg
        'N', // Asn
        'D', // Asp
        'C', // Cys
        'E', // Glu
        'Q', // Gln
        'G', // Gly
        'H', // His
        'I', // Ile
        'L', // Leu
        'K', // Lys
        'M', // Met
        'F', // Phe
        'P', // Pro
        'S', // Ser
        'T', // Thr
        'W', // Trp
        'Y', // Tyr
        'V'  // Val
    };

    final Target target;
    final String seq;
    final ObjectMapper mapper;
    final Map<String, Map> signals;
    JsonNode aaprofile;

    public Sequence (Target target) {
        this.target = target;

        mapper = new ObjectMapper ();
        Value val = target.getProperty(Commons.UNIPROT_SEQUENCE);
        seq = val != null ? ((Text)val).text : "";      

        signals = new TreeMap<>();
        initSignals ();
    }

    void initSignals () {
        for (Value val : target.properties) {
            if ("Localization Signal".equals(val.label)) {
                Keyword kw = (Keyword)val;
                Map s = new TreeMap<>();
                s.put("term", kw.term);
                try {
                    Pattern p = Pattern.compile
                        ("("+kw.term.replaceAll("x", "\\\\w")+")", 
                         Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(seq);
                    Logger.debug("** localization pattern: "+p.pattern());
                    List<int[]> locs = new ArrayList<>();
                    while (m.find()) {
                        for (int i = 1; i <= m.groupCount(); ++i) {
                            locs.add(new int[]{m.start(i), m.end(i)});
                        }
                    }
                    Collections.sort(locs, (a,b) -> {
                            int d = a[0] - b[0];
                            if (d == 0)
                                d = a[1] - b[1];
                            return d;
                        });
                    for (int[] l : locs)
                        Logger.debug("["+l[0]+","+l[1]+"]");
                    s.put("locations", locs);
                    signals.put(String.valueOf(kw.id), s);
                }
                catch (Exception ex) {
                    Logger.error("Bogus localization signal: "+kw.term, ex);
                }
            }
        }
            
        for (XRef ref : target.links) {
            if (ref.kind.equals(Keyword.class.getName())
                && signals.containsKey(ref.refid)) {
                Map s = signals.get(ref.refid);
                List<Long> pubs = new ArrayList<>();
                for (Value p : ref.properties) {
                    if ("Localization Location".equals(p.label)) {
                        s.put("location", p.getValue());
                    }
                    else if ("Localization Publication".equals(p.label)) {
                        pubs.add((Long)p.getValue());
                    }
                }
                s.put("publications", pubs);
            }
        }
    }

    public String raw () { return seq; }
    public String sequence () {
        return sequence (70);
    }
    public String sequence (int wrap) { 
        return formatSequence (seq, wrap);
    }

    public Map<String, Map> localization () {
        return signals;
    }

    public JsonNode profile () {
        if (aaprofile != null)
            return aaprofile;

        Map<Character, Integer> aa = new TreeMap<Character, Integer>();
        for (int i = 0; i < seq.length(); ++i) {
            char ch = seq.charAt(i);
            Integer c = aa.get(ch);
            aa.put(ch, c == null ? 1 : (c+1));
        }
        
        ArrayNode node = mapper.createArrayNode();
        for (int i = 0; i < AA.length; ++i) {
            Integer c = aa.get(AA[i]);
            ObjectNode n = mapper.createObjectNode();
            n.put("name", ""+AA[i]);
            ArrayNode a = mapper.createArrayNode();
            a.add(c != null ? c : 0);
            n.put("data", a);
            ObjectNode l = mapper.createObjectNode();
            l.put("enabled", true);
            l.put("rotation", -90);
            l.put("y",-20);
            l.put("format", "<b>{point.series.name}</b>: {point.y}");
            n.put("dataLabels", l);
            node.add(n);
        }
        aaprofile = node;
        return aaprofile;
    }

    public static String formatSequence (String text, int wrap) {
        StringBuilder seq = new StringBuilder ();
        int j = 1, len = text.length();
        for (int i = 1; i <= len; ++i) {
            seq.append(text.charAt(i-1));           
            if (i % wrap == 0) {
                seq.append(String.format("%1$7d - %2$d\n", j, i));
                j = i+1;
            }
        }
        
        int r = wrap - (len % wrap);
        if (r > 0 && j < len) {
            seq.append(String.format
                       ("%1$"+(r+7)+"d - %2$d\n", j, len));
        }
        seq.append("//");
        return seq.toString();
    }
}
