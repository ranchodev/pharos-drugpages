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
sbt idg/"runMain ix.idg.utils.PPIProteinMeSH jdbc:mysql://localhost/tcrd302?user=root"
 */
public class PPIProteinMeSH {
    public static void main (String[] argv) throws Exception {
        if (argv.length < 2) {
            System.err.println("Usage: "+PPIProteinMeSH.class.getName()
                               +"TCRD:JDBC OUTDIR");
            System.exit(1);
        }

        System.out.println("  JDBC: "+argv[0]);
        System.out.println("OUTDIR: "+argv[1]);
        HikariDataSource ds = new HikariDataSource ();
        ds.setJdbcUrl(argv[0]);

        File outdir = new File (argv[1]);
        outdir.mkdirs();

        Connection con = ds.getConnection();
        PreparedStatement pstm = con.prepareStatement
            ("select pubmed_id from protein2pubmed where protein_id = ?");

        Map<Long, Publication> processed = new HashMap<Long, Publication>();
        Statement stm = con.createStatement();
        ResultSet rset = stm.executeQuery
            ("select a.id,a.uniprot \n"+
             "from protein a, ppi b\n"+
             "where a.id = b.protein1_id\n"+
             "and b.ppitype = 'BioPlex'\n");
        while (rset.next()) {
            long protein = rset.getLong(1);
            pstm.setLong(1, protein);
            ResultSet rs = pstm.executeQuery();
            String uniprot = rset.getString(2);

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
        rset.close();
        
        con.close();
        System.out.println("SUCCESS!");
    }
}
