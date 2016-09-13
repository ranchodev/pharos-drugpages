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
sbt idg/"runMain ix.idg.utils.ProteinMeSH jdbc:mysql://localhost/tcrd302?user=root INFILE OUTDIR"
 */
public class ProteinMeSH {
    public static void main (String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("Usage: "+ProteinMeSH.class.getName()
                               +"TCRD:JDBC INFILE OUTDIR");
            System.err.println("where INFILE is an input file of UniProt IDs");
            System.exit(1);
        }

        System.out.println("  JDBC: "+argv[0]);
        System.out.println("INFILE: "+argv[1]);
        System.out.println("OUTDIR: "+argv[2]);
        HikariDataSource ds = new HikariDataSource ();
        ds.setJdbcUrl(argv[0]);

        File outdir = new File (argv[2]);
        outdir.mkdirs();

        Connection con = ds.getConnection();
        PreparedStatement pstm = con.prepareStatement
            ("select a.pubmed_id from protein2pubmed a, protein b "
             +"where uniprot = ? and a.protein_id = b.id");

        Map<Long, Publication> processed = new HashMap<Long, Publication>();
        BufferedReader br = new BufferedReader (new FileReader (argv[1]));
        for (String line; (line = br.readLine()) != null;) {
            String uniprot = line.trim();
            pstm.setString(1, uniprot);
            ResultSet rs = pstm.executeQuery();

            File ud = new File (outdir, uniprot);
            ud.mkdirs();

            System.out.println(uniprot);
            while (rs.next()) {
                long pmid = rs.getLong(1);
                Publication pub = processed.get(pmid);
                if (pub == null) {
                    pub = Eutils.fetchPublicationSimple(pmid);
                    processed.put(pmid, pub);
                }

                if (pub != null) {
                    System.out.print(" "+pmid);
                    File pd = new File (ud, pmid+".txt");
                    PrintWriter pw = new PrintWriter (new FileWriter (pd));
                    for (Mesh msh : pub.mesh) {
                        pw.println(msh.heading);
                    }
                    pw.close();
                    System.out.println(".."+pd+" "+pub.mesh.size());
                }
                else {
                    System.err.println
                        ("** error: can't retrieve pubmed "+pmid+"! **");
                }
            }
            rs.close();

            System.out.println();
        }
        br.close();
        con.close();

        System.out.println("SUCCESS!");
    }
}
