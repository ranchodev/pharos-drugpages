package ix.idg.utils;

import java.io.*;
import java.util.*;
import java.net.*;
import javax.xml.parsers.*;
import org.xml.sax.helpers.*;
import org.xml.sax.*;

import ix.utils.Eutils;
import ix.core.models.Publication;
import ix.core.models.Mesh;
import play.Logger;

/*
 * sbt idg/"runMain ix.idg.utils.UniProtMeSH UNIPROT OUTDIR INFILE"
 */
public class UniProtMeSH extends DefaultHandler {

    File uniprot;
    File outdir;

    String accession;
    Set<Long> pmids = new TreeSet<Long>();
    StringBuilder content;
    Map<Long, Publication> pubmed = new HashMap<Long, Publication>();

    public UniProtMeSH (String uniprot, String outdir) {
        this.uniprot = new File (uniprot);
        if (!this.uniprot.isDirectory())
            throw new IllegalArgumentException (uniprot+": not a directory!");
        this.outdir = new File (outdir);
        this.outdir.mkdirs();
    }
    
    public synchronized void parse (String acc) throws Exception {
        this.accession = acc;
        File file = getXmlFile (acc);
        if (file.exists() && file.length() > 0l) {
            Logger.debug("Parsing file..."+file);
            parse (new FileInputStream (file));
        }
        else {
            URL url = new URL ("http://www.uniprot.org/uniprot/"+acc+".xml");
            Logger.debug("Parsing url..."+url);
            FileOutputStream fos = new FileOutputStream (file);
            byte[] buf = new byte[1024];
            InputStream is = url.openStream();
            int total = 0;
            for (int nb; (nb = is.read(buf, 0, buf.length)) != -1;) {
                fos.write(buf, 0, nb);
                total += nb;
            }
            fos.close();
            Logger.debug("Cached file "+file+"..."+total);
            parse (new FileInputStream (file));
        }
    }

    public void parse (InputStream is) throws Exception {
        SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
        parser.parse(is, this);
    }

    @Override
    public void characters (char[] ch, int start, int length) {
        for (int i = start, j = 0; j < length; ++j, ++i) {
            content.append(ch[i]);
        }
    }

    @Override
    public void startDocument () {
        pmids.clear();
        content = new StringBuilder ();
    }

    @Override
    public void endDocument () {
        System.out.println(accession+": "+pmids.size()+" pubmed reference(s)!");
        for (Long p : pmids) {
            try {
                dumpMeSH (p);
            }
            catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    void dumpMeSH (Long pmid) throws IOException {
        Publication pub = pubmed.get(pmid);
        if (pub == null) {
            pub = Eutils.fetchPublicationSimple(pmid);
            pubmed.put(pmid, pub);
        }

        if (pub != null) {
            File out = new File (outdir, accession);
            out.mkdirs();
            
            System.out.print(" "+pmid);
            File pd = new File (out, pmid+".txt");
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

    @Override
    public void startElement (String uri, String localName, 
                              String qName, Attributes attrs) {
        content.setLength(0);
        
        if (qName.equals("dbReference")) {
            String type = attrs.getValue("type");
            if ("pubmed".equalsIgnoreCase(type) /*&& ++npubs <= 80*/) {
                String id = attrs.getValue("id");
                try {
                    pmids.add(Long.parseLong(id));
                }
                catch (NumberFormatException ex) {
                    Logger.error(accession+": Bogus pmid "+id);
                }
            }
        }
    }

    File getXmlFile (String acc) {
        String name = acc.substring(0, 2);
        return new File 
            (new File (uniprot, name+"/"+acc.substring(2,4)), acc+".xml");
    }
    
    public static void main (String[] argv) throws Exception {
        if (argv.length < 3) {
            System.err.println("Usage: "+UniProtMeSH.class.getName()
                               +" UNIPROT OUTDIR INFILE");
            System.exit(1);
        }

        UniProtMeSH unimesh = new UniProtMeSH (argv[0], argv[1]);
        BufferedReader br = new BufferedReader (new FileReader (argv[2]));
        for (String line; (line = br.readLine()) != null; ) {
            try {
                unimesh.parse(line.trim());
            }
            catch (Exception ex) {
                Logger.error(line+": can't process uniprot", ex);
            }
        }
    }
}

