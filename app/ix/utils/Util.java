package ix.utils;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.security.*;
import java.lang.reflect.Field;
import java.lang.annotation.Annotation;
import javax.persistence.Id;

import java.text.NumberFormat;
import ix.core.models.Value;
import ix.core.models.Keyword;

import play.Logger;
import play.mvc.Http;

public class Util {
    static public final String[] UserAgents = {
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10; rv:33.0) Gecko/20100101 Firefox/33.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_3) AppleWebKit/537.75.14 (KHTML, like Gecko) Version/7.0.3 Safari/7046A194A",
        "Mozilla/5.0 (Windows NT 6.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2228.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_10_1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/41.0.2227.1 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/36.0.1944.0 Safari/537.36"
    };

    static final Random rand = new Random ();
    static final char[] alpha = {'a','b','c','d','e','f','g','h','i','j','k',
                                 'l','m','n','o','p','q','r','s','t','u','v',
                                 'x','y','z'};
    public static String randomUserAgent () {
        return UserAgents[rand.nextInt(UserAgents.length)];
    }

    public static int rand (int from, int to) {
        if (to < from) return from;
        return from + rand.nextInt(to-from);
    }
    
    public static String randvar (int size, Http.Request req) {
        Random r = rand;
        if (req != null) {
            r = new SecureRandom
                (getSha1 (req, "q", "type", "facet", "filter", "order"));
        }
        return randvar (size, r);
    }

    public static String randvar (int size, String seed) {
        Random r = rand;
        if (seed != null) {
            try {
                r = new SecureRandom (seed.getBytes("utf-8"));
            }
            catch (Exception ex) {
                Logger.error("utf-8 encoding is not available for seeding", ex);
            }
        }
        return randvar (size, r);
    }

    public static String randvar (int size, Random r) {
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < size; ++i)
            sb.append(alpha[r.nextInt(alpha.length)]);
        return sb.toString();           
    }

    public static String sha1 (Http.Request req, String... params) {
        byte[] sha1 = getSha1 (req, params);
        return sha1 != null ? toHex (sha1) : "";
    }

    public static byte[] getSha1 (Http.Request req, String... params) {
        String path = req.method()+"/"+req.path();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            md.update(path.getBytes("utf8"));

            Set<String> uparams = new TreeSet<String>();
            if (params != null && params.length > 0) {
                for (String p : params) {
                    uparams.add(p);
                }
            }
            else {
                uparams.addAll(req.queryString().keySet());
                uparams.remove("refresh"); // we ignore this param
            }

            Set<String> sorted = new TreeSet (req.queryString().keySet());
            for (String key : sorted) {
                if (uparams.contains(key)) {
                    String[] values = req.queryString().get(key);
                    if (values != null) {
                        Arrays.sort(values);
                        md.update(key.getBytes("utf8"));
                        for (String v : values)
                            md.update(v.getBytes("utf8"));
                    }
                }
            }

            return md.digest();
        }
        catch (Exception ex) {
            Logger.error("Can't generate hash for request: "+req.uri(), ex);
        }
        return null;
    }

    public static String toHex (byte[] d) {
        StringBuilder sb = new StringBuilder ();
        for (int i = 0; i < d.length; ++i)
            sb.append(String.format("%1$02x", d[i]& 0xff));
        return sb.toString();
    }

    public static String sha1 (String... values) {
        if (values == null)
            return null;
        
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            for (String v : values) {
                md.update(v.getBytes("utf8"));
            }
            return toHex (md.digest());
        }
        catch (Exception ex) {
            Logger.trace("Can't generate sha1 hash!", ex);
        }
        return null;
    }

    public static byte[] serialize (Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream ();
        ObjectOutputStream oos = new ObjectOutputStream (bos);
        oos.writeObject(obj);
        oos.close();
        return bos.toByteArray();
    }
    
    private Util () {
    }

    /**
     * Returns an uncompressed InputStream from possibly compressed one.
     * 
     * @param is
     * @param uncompressed
     * @return
     * @throws IOException
     */
    public static InputStream getUncompressedInputStream(InputStream is,
                                                         boolean[] uncompressed) throws IOException {
        InputStream retStream = new BufferedInputStream(is);
        // if(true)return retStream;
        retStream.mark(100);
        if (uncompressed != null) {
            uncompressed[0] = false;
        }
        try {
            ZipInputStream zis = new ZipInputStream(retStream);
            ZipEntry entry;
            boolean got = false;
            // while there are entries I process them
            while ((entry = zis.getNextEntry()) != null) {
                got = true;

                // entry.
                retStream = zis;
                break;
            }
            if (!got)
                throw new IllegalStateException("Oops");
        } catch (Exception ex) {
            retStream.reset();
            // try as gzip
            try {
                GZIPInputStream gzis = new GZIPInputStream(retStream);
                retStream = gzis;
            } catch (IOException e) {
                retStream.reset();
                if (uncompressed != null) {
                    uncompressed[0] = true;
                }
                // retStream = new FileInputStream (file);
            }
            // try as plain txt file
        }
        return retStream;

    }

    /**
     * Returns an uncompressed inputstream from possibly multiply compressed
     * stream
     * 
     * @param is
     * @return
     * @throws IOException
     */
    public static InputStream getUncompressedInputStreamRecursive(InputStream is)
        throws IOException {
        boolean[] test = new boolean[1];
        test[0] = false;
        InputStream is2 = is;
        while (!test[0]) {
            is2 = getUncompressedInputStream(is2, test);
        }
        return is2;
    }

    public static <T extends Annotation> Field
        getField (Class cls, Class<T> annotation) {
        for (Field f : cls.getFields()) {
            if (f.isAnnotationPresent(annotation)) {
                return f;
            }
        }
        return null;
    }

    public static Field getIdField (Class cls) {
        return getField (cls, Id.class);
    }

    public static <T extends Annotation> Object getFieldValue
        (Object inst, Class<T> annotation) throws Exception {
        Field f = getField (inst.getClass(), annotation);
        return f != null ? f.get(inst) : null;
    }

    public static String sha1 (Collection values) {
        Set<String> vals = new TreeSet<String>();
        for (Object obj : values) {
            try {
                Object val = getFieldValue (obj, Id.class);
                vals.add(val != null ? (obj.getClass().getName()
                                        +":"+val.toString()) : obj.toString());
            }
            catch (Exception ex) {
                Logger.error("Can't get Id value for "+obj, ex);
            }
        }
        return sha1 (vals.toArray(new String[0]));
    }

    public static boolean isValidSQL (String s) {
        int len = s.length();
        for (int i = 0; i < len; ++i) {
            char ch = s.charAt(i);
            switch (ch) {
            case '<': case '>': case '|': 
            case ';': case '(': case ')': 
            case '&':
                return false;
            }
        }
        return true;
    }

    public static boolean isValidFieldName (String s) {
        int len = s.length();
        for (int i = 0; i < len; ++i) {
            char ch = s.charAt(i);
            if ((i == 0 && !Character.isJavaIdentifierStart(ch))
                || !Character.isJavaIdentifierPart(ch))
                return false;
        }
        return true;
    }

    public static String format (Number value) {
        if (value != null)
            return NumberFormat.getInstance().format(value);
        return "";
    }

    public static Map<String, Value[]> groups (Collection<Value> values) {
        Map<String, List<Value>> groups = new HashMap<>();
        for (Value v : values) {
            List<Value> vals = groups.get(v.label);
            if (vals == null)
                groups.put(v.label, vals = new ArrayList<>());
            vals.add(v);
        }
        
        Map<String, Value[]> vg = new TreeMap<>();
        for (Map.Entry<String, List<Value>> me : groups.entrySet())
            vg.put(me.getKey(), me.getValue().toArray(new Value[0]));
        return vg;
    }

    public static Map<String, String[]> groupKeywords
        (Collection<Value> values) {
        Map<String, Set<String>> groups = new HashMap<>();
        for (Value v : values) {
            if (v instanceof Keyword) {
                Keyword kw = (Keyword)v;
                Set<String> terms = groups.get(kw.label);
                if (terms == null)
                    groups.put(kw.label, terms = new TreeSet<>());
                terms.add(kw.term);
            }
        }
        
        Map<String, String[]> kg = new TreeMap<>();
        for (Map.Entry<String, Set<String>> me : groups.entrySet())
            kg.put(me.getKey(), me.getValue().toArray(new String[0]));
        return kg;
    }
}
