
package ix.idg.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.util.*;

public class DTOParser {
    
    public class DTONode {
        public String id;
        public String name;
        public DTONode parent;
        public List<DTONode> children = new ArrayList<DTONode>();

        protected DTONode (String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    DTONode root;
    // quick lookup based on a given name
    Map<String, DTONode> nodes = new HashMap<String, DTONode>();
    Map<String, DTONode> ids = new HashMap<String, DTONode>();

    public DTOParser () {
    }

    DTONode parse (DTONode parent, JsonNode node) {
        String id = node.get("id").asText();
        String name = node.get("name").asText();

        DTONode n = new DTONode (id, name);
        n.parent = parent;

        // index for quick lookup
        this.nodes.put(name, n);
        this.ids.put(id, n);
        
        if (parent != null) {
            parent.children.add(n);
        }
        
        ArrayNode nodes = (ArrayNode)node.get("children");
        if (nodes != null) {
            int size = nodes.size();
            for (int i = 0; i < size; ++i) {
                parse (n, nodes.get(i));
            }
        }
        return n;
    }

    public DTONode get (String name) {
        if (name.startsWith("DTO_"))
            return ids.get(name);
        return nodes.get(name);
    }

    public int size () { return nodes.size(); }

    public DTONode parse (InputStream is) throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        JsonNode node = mapper.readTree(is);
        return root = parse (null, node);
    }

    public DTONode parse (File file) throws IOException {
        FileInputStream fis = new FileInputStream (file);
        parse (fis);
        fis.close();
        return root;
    }

    public static void main (String[] argv) throws Exception {
        if (argv.length == 0) {
            System.err.println("Usage: DTOParser FILE.json [PROTEINS...]");
            System.exit(1);
        }

        DTOParser dto = new DTOParser ();
        DTONode node = dto.parse(new File (argv[0]));
        System.out.println("DTO file \""+argv[0]+"\" parsed with "+dto.size()+" nodes!");
        for (int i = 1; i < argv.length; ++i) {
            System.out.println("Searching for protein \""+argv[i]+"\"...");
            DTONode n = dto.get(argv[i]);
            if (n != null) {
                System.out.println(n.id+": "+n.name);
                int j = 1;
                for (DTONode p = n.parent; p != null; p = p.parent) {
                    for (int k = 0; k < j; ++k)
                        System.out.print("\t");
                    System.out.println(p.id+": "+p.name);
                    ++j;
                }
                System.out.println();
            }
        }
    }
}

