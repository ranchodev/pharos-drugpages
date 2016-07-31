package ix.idg.utils;

import java.io.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

import ix.utils.Eutils;
import ix.core.models.*;
import com.zaxxer.hikari.HikariDataSource;

/*
invoke like this.. 
sbt idg/"runMain ix.idg.utils.GeneExpression jdbc:mysql://localhost/tcrd305?user=root GENE..."
 */
public class GeneExpression {
    public static void main (String[] argv) throws Exception {
        if (argv.length < 2) {
            System.err.println("Usage: "+GeneExpression.class.getName()
                               +" GENE...");
            System.exit(1);
        }

        System.err.println("  JDBC: "+argv[0]);
        HikariDataSource ds = new HikariDataSource ();
        ds.setJdbcUrl(argv[0]);

        Connection con = ds.getConnection();
        PreparedStatement pstm1 = con.prepareStatement
            ("select b.* from protein a, expression b where a.sym = ? "
             +"and a.id = b.protein_id");
        PreparedStatement pstm2 = con.prepareStatement
            ("select a.* from pathway a, protein b "
             +"where a.protein_id = b.id and b.sym = ?");
        
        String[] types = new String[] {
            "GTEx",
            "HPA Protein",
            "HPA RNA",
            "HPM Gene",
            "HPM Protein",
            "JensenLab Experiment Exon array",
            "JensenLab Experiment GNF",
            "JensenLab Experiment HPA",
            "JensenLab Experiment HPA-RNA",
            "JensenLab Experiment HPM",
            "JensenLab Experiment RNA-seq",
            "JensenLab Experiment UniGene",
            "JensenLab Knowledge UniProtKB-RC",
            "JensenLab Text Mining"
        };

        String[] pathways = new String[] {
            "KEGG",
            "PathwayCommons: ctd",
            "PathwayCommons: humancyc",
            "PathwayCommons: inoh",
            "PathwayCommons: mirtarbase",
            "PathwayCommons: netpath",
            "PathwayCommons: panther",
            "PathwayCommons: pid",
            "PathwayCommons: recon",
            "PathwayCommons: smpdb",
            "PathwayCommons: transfac",
            "Reactome",
            "UniProt",
            "WikiPathways"
        };

        System.out.print("Gene");
        for (int i = 0; i < types.length; ++i)
            System.out.print(","+types[i]);
        for (int i = 0; i < pathways.length; ++i)
            System.out.print(","+pathways[i]);
        System.out.println();

        for (int i = 1; i < argv.length; ++i) {
            pstm1.setString(1, argv[i]);
            ResultSet rset = pstm1.executeQuery();
            Map<String, Set<String>> expr = new TreeMap<String, Set<String>>();
            while (rset.next()) {
                String qual = rset.getString("qual_value");
                String evid = rset.getString("evidence");
                if ("not detected".equalsIgnoreCase(qual)
                    && evid != null && !evid.equalsIgnoreCase("CURATED")) {
                    // skip
                }
                else {
                    String type = rset.getString("etype");
                    Set<String> tissue = expr.get(type);
                    if (tissue == null) {
                        expr.put(type, tissue = new TreeSet<String>());
                    }
                    tissue.add(rset.getString("tissue"));
                }
            }
            rset.close();

            Map<String, Set<String>> pw = new TreeMap<String, Set<String>>();
            pstm2.setString(1, argv[i]);
            rset = pstm2.executeQuery();
            while (rset.next()) {
                String type = rset.getString("pwtype");
                String name = rset.getString("name");
                Set<String> p = pw.get(type);
                if (p == null) {
                    pw.put(type, p = new TreeSet<String>());
                }
                p.add(name);
            }
            rset.close();
            
            if (pw.isEmpty()) {
                System.err.println(argv[i]+": no pathway information!");
            }
            
            if (expr.isEmpty()) {
                System.err.println
                    ("Can't find expression for gene '"+argv[i]+"'!");
            }
            else {
                System.out.print(argv[i]);
                for (int j = 0; j < types.length; ++j) {
                    Set<String> tissue = expr.get(types[j]);
                    System.out.print(",");
                    if (tissue != null && !tissue.isEmpty()) {
                        Iterator<String> it = tissue.iterator();
                        System.out.print("\""+it.next());
                        while (it.hasNext()) {
                            System.out.print("|"+it.next());
                        }
                        System.out.print("\"");
                    }
                }
                
                for (int j = 0; j < pathways.length; ++j) {
                    Set<String> p = pw.get(pathways[j]);
                    System.out.print(",");
                    if (p != null && !p.isEmpty()) {
                        Iterator<String> it = p.iterator();
                        System.out.print("\""+it.next());
                        while (it.hasNext())
                            System.out.print("|"+it.next());
                        System.out.print("\"");
                    }
                }

                System.out.println();
            }
        }
        pstm1.close();
        pstm2.close();
        con.close();
    }
}
