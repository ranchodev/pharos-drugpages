
package ix.idg.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.*;
import java.util.*;
import java.sql.*;
import play.Logger;

public class DTOParser implements Serializable {
    
    static public class Node implements Serializable {
        public String id;
        public String name;
        @JsonIgnore
        public Node parent;
        public List<Node> children = new ArrayList<Node>();
        public Integer size;
        public String url;
        public String tdl;
        public String fullname;
        public boolean visible = true;

        public Node () {
        }

        public Node (String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static void index (DTOParser dto, Node node) {
        dto.nodes.put(node.name, node);
        dto.ids.put(node.id, node);
        for (Node child : node.children) {
            child.parent = node;
            index (dto, child);
        }
    }
    
    static public DTOParser readJson (File file) throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        DTOParser dto = new DTOParser ();
        dto.root = mapper.readValue(file, Node.class);
        index (dto, dto.root);
        return dto;
    }

    static public DTOParser readJson (InputStream is) throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        DTOParser dto = new DTOParser ();
        dto.root = mapper.readValue(is, Node.class);
        index (dto, dto.root);
        return dto;
    }

    static public void writeJson (File file, DTOParser dto) throws IOException {
        writeJson (file, dto.root);
    }
    
    static public void writeJson (File file, Node node) throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        mapper.writerWithDefaultPrettyPrinter().writeValue(file, node);
    }

    public void load (Connection con) throws SQLException {
        nodes.clear();
        ids.clear();
        
        PreparedStatement pstm = con.prepareStatement
            ("select * from dto order by parent");
        root = new Node ("DTO_00200000", "Gene");
        ids.put(root.id, root);
        nodes.put(root.name, root);
        try {
            ResultSet rset = pstm.executeQuery();
            Map<String, String> parents = new HashMap<>();
            while (rset.next()) {
                String id = rset.getString("id");
                String name = rset.getString("name");
                String parent = rset.getString("parent");
                
                Node n = new Node (id, name);
                nodes.put(name, n);
                ids.put(id, n);
                
                if (parent != null) {
                    parents.put(id, parent);
                }
                else {
                    parents.put(id, root.id);
                    n.parent = root;
                    root.children.add(n);
                }
            }
            rset.close();

            for (Node n : ids.values()) {
                String parent = parents.get(n.id);
                n.parent = ids.get(parent);
                if (n.parent != null)
                    n.parent.children.add(n);
                else
                    Logger.error("DTO node "+n.id+" ("+n.name+") referrence an "
                                 +"unknown parent \""+parent+"\"!");
            }
        }
        finally {
            pstm.close();
        }
    }

    Node root;
    // quick lookup based on a given name
    Map<String, Node> nodes = new HashMap<String, Node>();
    Map<String, Node> ids = new HashMap<String, Node>();

    public DTOParser () {
    }

    Node parse (Node parent, JsonNode node) {
        String id = node.get("id").asText();
        String name = node.get("name").asText();

        Node n = new Node (id, name);
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

    public Node get (String name) {
        if (name == null)
            return null;
        
        if (name.startsWith("DTO_"))
            return ids.get(name);
        return nodes.get(name);
    }

    public Collection<Node> nodes () { return nodes.values(); }
    public int size () { return nodes.size(); }

    public Node parse (InputStream is) throws IOException {
        ObjectMapper mapper = new ObjectMapper ();
        JsonNode node = mapper.readTree(is);
        return root = parse (null, node);
    }

    public Node parse (File file) throws IOException {
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
        Node node = dto.parse(new File (argv[0]));
        System.out.println("DTO file \""+argv[0]+"\" parsed with "+dto.size()+" nodes!");
        for (int i = 1; i < argv.length; ++i) {
            System.out.println("Searching for protein \""+argv[i]+"\"...");
            Node n = dto.get(argv[i]);
            if (n != null) {
                System.out.println(n.id+": "+n.name);
                int j = 1;
                for (Node p = n.parent; p != null; p = p.parent) {
                    for (int k = 0; k < j; ++k)
                        System.out.print("\t");
                    System.out.println(p.id+": "+p.name);
                    ++j;
                }
                System.out.println();
                System.out.println(">> json...");
                ObjectMapper mapper = new ObjectMapper ();
                System.out.println
                    (mapper.writerWithDefaultPrettyPrinter()
                     .writeValueAsString(n));
            }
        }
    }
}

