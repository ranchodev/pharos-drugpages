package ix.utils;

import java.net.URL;
import java.net.URLConnection;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.StringReader;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.xml.parsers.*;

import org.xml.sax.InputSource;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Element;
import org.w3c.dom.Document;

import play.GlobalSettings;
import play.Application;
import play.Logger;
import play.libs.ws.*;
import play.libs.F;

import ix.core.models.Publication;
import ix.core.models.Journal;
import ix.core.models.Author;
import ix.core.models.PubAuthor;
import ix.core.models.Keyword;
import ix.core.models.Mesh;

public class Eutils {
    static String EUTILS_BASE = 
        "http://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi";
    static String EUTILS_URL = EUTILS_BASE + "?db=pubmed&rettype=xml&id=";

    static public int TIMEOUT = 20000;
    
    public interface Callback {
        void onReceived (Publication pub);
        void onFailure (Long pmid, Throwable t);
    }

    public static Publication parsePublication (byte[] content)
        throws Exception {
        return parsePublication (new InputSource
                                 (new ByteArrayInputStream (content)));
    }
    
    public static Publication parsePublication (File file) throws Exception {
        return parsePublication (new InputSource (new FileInputStream (file)));
    }

    public static Publication parsePublication (InputSource source)
        throws Exception {
        DocumentBuilder db = DocumentBuilderFactory
            .newInstance().newDocumentBuilder();
        return parsePublication (db.parse(source));
    }
    
    public static Publication parsePublication (Document dom) {
        NodeList nodes = dom.getElementsByTagName("PubmedArticle");
        if (nodes.getLength() == 0) {
            Logger.warn("Not a valid PubMed DOM!");
            return null;
        }

        Element article = (Element)nodes.item(0);
        nodes = article.getElementsByTagName("PMID");
        if (nodes.getLength() == 0) {
            Logger.warn("No PMID found in PubMed DOM!");
            return null;
        }
            
        Publication pub = new Publication ();
        pub.pmid = Long.parseLong(nodes.item(0).getTextContent());
        
        nodes = article.getElementsByTagName("Journal");
        Element jelm = nodes.getLength() > 0 
            ? (Element)nodes.item(0) : null;
        if (jelm != null) {
            pub.journal = getJournal (jelm);
        }
        //Logger.debug("Journal: "+pub.journal);
            
        // title
        nodes = article.getElementsByTagName("ArticleTitle");
        if (nodes.getLength() > 0) {
            pub.title = nodes.item(0).getTextContent();
        }
            
        // abstract
        nodes = article.getElementsByTagName("AbstractText");
        if (nodes.getLength() > 0) {
            StringBuilder text = new StringBuilder ();
            for (int i = 0; i < nodes.getLength(); ++i) {
                if (text.length() > 0) text.append("\n");
                Element elm = (Element)nodes.item(i);
                String label = elm.getAttribute("Label");
                if (label != null && label.length() > 0) {
                    text.append(label+": ");
                }
                text.append(elm.getTextContent());
            }
            pub.abstractText = text.toString();
        }
            
        // authors
        nodes = article.getElementsByTagName("AuthorList");
        if (nodes.getLength() > 0) {
            nodes = ((Element)nodes.item(0))
                .getElementsByTagName("Author");

            for (int i = 0, j = 0; i < nodes.getLength(); ++i) {
                Node n = nodes.item(i);
                Author author = getAuthor (n);
                if (author != null)
                    pub.authors.add
                        (new PubAuthor 
                         (j++, i+1 == nodes.getLength(), author));
            }
        }
                        
        nodes = article.getElementsByTagName("MedlinePgn");
        if (nodes.getLength() > 0) {
            pub.pages = nodes.item(0).getTextContent();
        }
            
        nodes = article.getElementsByTagName("ELocationID");
        if (nodes.getLength() > 0) {
            Element elm = (Element)nodes.item(0);
            if ("doi".equalsIgnoreCase(elm.getAttribute("EIdType"))) {
                pub.doi = elm.getTextContent();
            }
        }
            
        nodes = dom.getElementsByTagName("ArticleId");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element elm = (Element)nodes.item(i);
            String idtype = elm.getAttribute("IdType");
            if ("doi".equalsIgnoreCase(idtype)) {
                pub.doi = elm.getTextContent();
            }
            else if ("pubmed".equalsIgnoreCase(idtype)) {
            }
            else if ("pmc".equalsIgnoreCase(idtype)) 
                pub.pmcid = elm.getTextContent();
        }
            
        nodes = dom.getElementsByTagName("NameOfSubstance");
        if (nodes.getLength() > 0) {
            List<String> substances = new ArrayList<String>();
            for (int i = 0; i < nodes.getLength(); ++i) {
                substances.add(nodes.item(i).getTextContent());
            }
        }
            
        nodes = dom.getElementsByTagName("MeshHeading");
        if (nodes.getLength() > 0) {
            String descriptor = null;
                
            for (int i = 0; i < nodes.getLength(); ++i) {
                Element headElm = (Element)nodes.item(i);
                NodeList n = headElm
                    .getElementsByTagName("DescriptorName");
                    
                List<Mesh> heading = new ArrayList<Mesh>();
                // there must be at least one descriptor name
                if (n.getLength() > 0) {
                    descriptor = n.item(0).getTextContent();
                }
                    
                n = headElm.getElementsByTagName("QualifierName");
                if (n.getLength() > 0 && descriptor != null) {
                    for (int j = 0; j < n.getLength(); ++j) {
                        Element elm = (Element)n.item(j);
                        String term = descriptor
                            +"/"+elm.getTextContent();
                        Mesh m = new Mesh (term);
                        if ("Y".equalsIgnoreCase
                            (elm.getAttribute("MajorTopicYN")))
                            m.majorTopic = true;
                        heading.add(m);
                    }
                }
                    
                if (descriptor == null) {
                    Logger.warn
                        ("MeshHeading has no DescriptorName");
                }
                else if (heading.isEmpty())
                    pub.mesh.add(new Mesh (descriptor));
                else
                    pub.mesh.addAll(heading);
            }
        }
        return pub;
    }
    
    public static Publication fetchPublication (Long pmid) {
        try {
            org.w3c.dom.Document dom = getDOM (pmid);
            if (dom == null) {
                Logger.debug("No publication found for "+pmid);
                return null;
            }
            
            return parsePublication (dom);
        }
        catch (Throwable ex) {
            Logger.trace("Fetch failed for "+pmid, ex);
        }

        return null;
    }

    public static Publication fetchPublicationSimple (long pmid) {
        try {
            return parsePublication (getDOM (EUTILS_URL+pmid));
        }
        catch (Throwable ex) {
            ex.printStackTrace();
        }
        return null;
    }

    public static void fetchPublication (final Long pmid,
                                         final Callback callback) {
        F.Promise<WSResponse> promise = getPromise (pmid);
        try {
            promise.onFailure(new F.Callback<Throwable> () {
                    public void invoke (Throwable t) {
                        Logger.warn
                            ("Failure: can't retrieve PMID "+pmid+"; "+t);
                        callback.onFailure(pmid, t);
                    }
                });
            promise.onRedeem(new F.Callback<WSResponse> () {
                    public void invoke (WSResponse res) {
                        //Logger.warn("Redeem: "+res);
                        Publication pub = parsePublication (res.asXml());
                        callback.onReceived(pub);
                    }
                });
            WSResponse response = promise.get(100);
            callback.onReceived(parsePublication (response.asXml()));
        }
        catch (Throwable ex) {
            Logger.trace("Can't get response for "+pmid, ex);
            callback.onFailure(pmid, ex);
        }
    }

    static Author getAuthor (Node node) {
        
        NodeList nodes = ((org.w3c.dom.Element)node)
            .getElementsByTagName("LastName");

        Author author = new Author ();
        if (nodes.getLength() > 0) {
            org.w3c.dom.Element elm = (org.w3c.dom.Element)nodes.item(0);
            author.lastname = elm.getTextContent();
        }
        else {
            nodes = ((org.w3c.dom.Element)node)
                .getElementsByTagName("CollectiveName");
            if (nodes.getLength() > 0) {
                org.w3c.dom.Element elm = (org.w3c.dom.Element)nodes.item(0);
                author.lastname = elm.getTextContent();
                if (author.lastname.length() > 255)
                    author = null;
            }
            else {
                author = null;
            }

            return author;
        }

        nodes = ((org.w3c.dom.Element)node)
            .getElementsByTagName("ForeName");
        if (nodes.getLength() > 0) {
            org.w3c.dom.Element elm = (org.w3c.dom.Element)nodes.item(0);
            author.forename = elm.getTextContent();
        }

        nodes = ((org.w3c.dom.Element)node)
            .getElementsByTagName("Initials");
        if (nodes.getLength() > 0) {
            org.w3c.dom.Element elm = (org.w3c.dom.Element)nodes.item(0);
            author.initials = elm.getTextContent();
        }
        
        nodes = ((Element)node).getElementsByTagName("Affiliation");
        if (nodes.getLength() > 0) {
            author.affiliation = ((Element)nodes.item(0)).getTextContent();
        }

        nodes = ((Element)node).getElementsByTagName("Identifier");
        for (int i = 0; i < nodes.getLength(); ++i) {
            Element idElm = (Element)nodes.item(i);
            String source = idElm.getAttribute("Source");
            if ("ORCID".equalsIgnoreCase(source)) {
                author.orcid = idElm.getTextContent();
            }
        }

        return author;
    }

    static Journal getJournal (Element node) {
        Journal journal = new Journal ();
        NodeList nodes = node.getElementsByTagName("Title");
        if (nodes.getLength() > 0)
            journal.title = nodes.item(0).getTextContent();
        //Logger.info("Journal "+journal.title+"...");

        nodes = node.getElementsByTagName("Volume");
        if (nodes.getLength() > 0) 
            journal.volume = nodes.item(0).getTextContent();

        nodes = node.getElementsByTagName("Issue");
        if (nodes.getLength() > 0) 
            journal.issue = nodes.item(0).getTextContent();

        nodes = node.getElementsByTagName("Year");
        if (nodes.getLength() > 0)
            journal.year = Integer.parseInt(nodes.item(0).getTextContent());

        nodes = node.getElementsByTagName("Month");
        if (nodes.getLength() > 0) {
            journal.month = nodes.item(0).getTextContent();
            try {
                int mon = Integer.parseInt(journal.month);
                switch (mon) {
                case 1: journal.month = "Jan"; break;
                case 2: journal.month = "Feb"; break;
                case 3: journal.month = "Mar"; break;
                case 4: journal.month = "Apr"; break;
                case 5: journal.month = "May"; break;
                case 6: journal.month = "Jun"; break;
                case 7: journal.month = "Jul"; break;
                case 8: journal.month = "Aug"; break;
                case 9: journal.month = "Sep"; break;
                case 10: journal.month = "Oct"; break;
                case 11: journal.month = "Nov"; break;
                case 12: journal.month = "Dec"; break;
                default: 
                    Logger.warn("Unknown month: "+journal.month);
                }
            }
            catch (NumberFormatException ex) {
                // do nothing
            }
        }

        nodes = node.getElementsByTagName("ISSN");
        if (nodes.getLength() > 0)
            journal.issn = nodes.item(0).getTextContent();

        nodes = node.getElementsByTagName("ISOAbbreviation");
        if (nodes.getLength() > 0)
            journal.isoAbbr = nodes.item(0).getTextContent();

        return journal;
    }

    public static Document getDOM (final Long pmid) {
        F.Promise<WSResponse> promise = getPromise (pmid);
        try {
            promise.onFailure(new F.Callback<Throwable> () {
                    public void invoke (Throwable t) {
                        Logger.warn
                            ("Failure: can't retrieve PMID "+pmid+"; "+t);
                    }
                });
            WSResponse response = promise.get(TIMEOUT);
            return response.asXml();
        }
        catch (Throwable ex) {
            Logger.trace("Can't get response for "+pmid, ex);
        }
        return null;
    }

    public static byte[] getByteArray (final Long pmid) {
        F.Promise<WSResponse> promise = getPromise (pmid);
        try {
            promise.onFailure(new F.Callback<Throwable> () {
                    public void invoke (Throwable t) {
                        Logger.warn
                            ("Failure: can't retrieve PMID "+pmid+"; "+t);
                    }
                });
            WSResponse response = promise.get(TIMEOUT);
            return response.asByteArray();
        }
        catch (Throwable ex) {
            Logger.trace("Can't get response for "+pmid, ex);
        }
        return null;
    }

    static F.Promise<WSResponse> getPromise (final Long pmid) {
        WSRequestHolder ws = WS.url(EUTILS_BASE)
            .setTimeout(TIMEOUT)
            .setFollowRedirects(true)
            .setQueryParameter("db", "pubmed")
            .setQueryParameter("rettype", "xml")
            .setQueryParameter("id", pmid.toString());

        return ws.get();
    }

    static public List<Long> fetchRelated (Long pmid) {
        WSRequestHolder ws = WS.url
            ("http://eutils.ncbi.nlm.nih.gov/entrez/eutils/elink.fcgi")
            .setTimeout(TIMEOUT)
            .setFollowRedirects(true)
            .setQueryParameter("dbfrom", "pubmed")
            .setQueryParameter("linkname", "pubmed_pubmed")
            .setQueryParameter("rettype", "xml")
            .setQueryParameter("retmax", "100")
            .setQueryParameter("id", pmid.toString());

        List<Long> pmids = new ArrayList<Long>();
        F.Promise<WSResponse> promise = ws.get();
        try {
            WSResponse response = promise.get(TIMEOUT);
            Document doc = response.asXml();
            NodeList nodes = doc.getElementsByTagName("LinkSetDb");
            if (nodes.getLength() > 0) {
                Element db = (Element)nodes.item(0);
                nodes = db.getElementsByTagName("Id");
                for (int i = 0; i < nodes.getLength(); ++i) {
                    String text = nodes.item(i).getTextContent();
                    try {
                        long id = Long.parseLong(text);
                        pmids.add(id);
                    }
                    catch (NumberFormatException ex) {
                        Logger.warn("Not a valid PMID: "+text);
                    }
                }
            }
        }
        catch (Exception ex) {
            Logger.trace("Can't get response for "+pmid, ex);
        }
        return pmids;
        
    }

    static Document getDOM (String url) {
        /*
         * We first read the xml into a utf-8 byte buffer, then use the
         * StringReader to properly convert utf-8 to Java's unicode.
         * If we try to parse the xml directly, for whatever reason it
         * doesn't properly parse the encoded utf-8 stream!
         */
        try {
            URLConnection con = new URL (url).openConnection();
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            con.setUseCaches(true);
         
            Logger.debug("ContentType: "+con.getContentType());
            Logger.debug("ContentEncoding: "+con.getContentEncoding());
            Logger.debug("Expiration: "+con.getExpiration());
            Logger.debug("LastModified: "+con.getLastModified());
            Logger.debug("Date: "+con.getDate());

            ByteArrayOutputStream buffer = new ByteArrayOutputStream ();
            byte[] buf = new byte[1024];
            BufferedInputStream bis = 
                new BufferedInputStream (con.getInputStream());
            for (int nb; (nb = bis.read(buf, 0, buf.length)) > 0; ) {
                buffer.write(buf, 0, nb);
            }
            bis.close();
            
            Logger.debug("## url="+url+": size="+buffer.toString().length());
            
            DocumentBuilder db = DocumentBuilderFactory
                .newInstance().newDocumentBuilder();
            
            return db.parse
                (new InputSource (new StringReader (buffer.toString())));
        }
        catch (IOException ex) {
            Logger.trace("Failed to open connection to "+url, ex);
        }
        catch (Exception ex) {
            Logger.trace("XML parsing exception "+url, ex);
        }

        return null;
            /*
        return db.parse(new InputSource
                        (new InputStreamReader (url.openStream())));
        */
    }
}
