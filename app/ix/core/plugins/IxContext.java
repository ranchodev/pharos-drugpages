package ix.core.plugins;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import play.Application;
import play.Logger;
import play.Plugin;
import play.db.DB;

public class IxContext extends Plugin {
    private static final String APPLICATION_API = "application.api";
	private static final String APPLICATION_CONTEXT = "application.context";
	private static final String APPLICATION_HOST = "application.host";
	static final String IX_HOME = "ix.home";
    static final String IX_DEBUG = "ix.debug";
    static final String IX_CACHE = "ix.cache";
    static final String IX_CACHE_BASE = IX_CACHE+".base";
    static final String IX_CACHE_TIME = IX_CACHE+".time";
    static final String IX_TEXT = "ix.text";
    static final String IX_TEXT_BASE = IX_TEXT+".base";
    static final String IX_H2 = "ix.h2";
    static final String IX_H2_BASE = IX_H2+".base";
    static final String IX_STORAGE = "ix.storage";
    static final String IX_STORAGE_BASE = IX_STORAGE+".base";
    static final String IX_PAYLOAD = "ix.payload";
    static final String IX_PAYLOAD_BASE = IX_PAYLOAD+".base";
    static final String IX_STRUCTURE = "ix.structure";
    static final String IX_STRUCTURE_BASE = IX_STRUCTURE+".base";
    static final String IX_SEQUENCE = "ix.sequence";
    static final String IX_SEQUENCE_BASE = IX_SEQUENCE+".base";
    
    static final String APPLICATION_SQL_INIT = "application.sql.init";
    static final String APPLICATION_SQL_TEST = "application.sql.test";
	static final String APPLICATION_SQL_LOAD = "application.sql.load";
    

    private final Application app;
    public File home = new File (".");
    public File cache;
    public File text;
    public File h2;
    public File payload;
    public File structure;
    public File sequence;
    
    private int debug;
    private int cacheTime;
    private String context;
    private String api;
    private String host;

    public IxContext (Application app) {
        this.app = app;
    }

    private void init () throws Exception {
        String h = app.configuration().getString(IX_HOME);
        if (h != null) {
            home = new File (h);
            if (!home.exists())
                home.mkdirs();
        }
        Logger.info("##############################################");
        Logger.info("##############################################");
        Logger.info("##############################################");
        Logger.info("#Play Framework: " + play.core.PlayVersion.current()+ "#");
        Logger.info("##############################################");
        Logger.info("##############################################");
        
        if (!home.exists())
            throw new IllegalArgumentException
                (IX_HOME+" \""+h+"\" is not accessible!");
        Logger.info("## "+IX_HOME+": \""+home.getCanonicalPath()+"\"");

        cache = getFile (IX_CACHE_BASE, "cache");
        cacheTime = app.configuration().getInt(IX_CACHE_TIME, 60);
        Logger.info("## "+IX_CACHE_TIME+": "+cacheTime+"s");

        text = getFile (IX_TEXT_BASE, "text");
        h2 = getFile (IX_H2_BASE, "h2");
        payload = getFile (IX_PAYLOAD_BASE, "payload");
        structure = getFile (IX_STRUCTURE_BASE, "structure");
        sequence = getFile (IX_SEQUENCE_BASE, "sequence");
        
        Integer level = app.configuration().getInt(IX_DEBUG);
        if (level != null)
            this.debug = level;
        Logger.info("## "+IX_DEBUG+": "+debug); 

        DatabaseMetaData meta = DB.getConnection().getMetaData();
        Logger.info("## Database vendor: "+meta.getDatabaseProductName()
                    +" "+meta.getDatabaseProductVersion());
        
        String dbName=meta.getDatabaseProductName().toLowerCase();
        Logger.info("Checking for existence of DB schema ... " + dbName);
        if(!dbinitialized(meta)){
            Logger.info("Database not initialized, applying scripts");
            File f=null;
            f=getFile(APPLICATION_SQL_INIT,"../conf/sql/init/");
                
            if(f.exists()){
                Logger.info("Initialization folder exists:" + f.getCanonicalPath());
                String path = f.getAbsolutePath()+"/"+dbName+".sql";
                File initFile = new File(path);
                Logger.info("Looking for sql script:" + initFile.getCanonicalPath());
                if(initFile.exists()){
                    Logger.info("Applying SQL initialization:" + initFile.getCanonicalPath());
                    Statement s = DB.getConnection().createStatement();
                    String sqlRun = readFullFileToString(initFile);
                   // System.out.println(sqlRun);
                    sqlRun = interpretSQL(sqlRun);
                    for(String sqlLine : sqlRun.split(";")){
	                    try{
	                    	Logger.debug("applying");
	                        ResultSet rs1=s.executeQuery(sqlLine);
	                        rs1.close();
	                    	Logger.debug("closing");
	                        Logger.info("SQL initialization applied.");
	                    }catch(Exception e){
	                    	Logger.debug("ERROR");
	                    	Logger.info("SQL initialization failed:");
	                        e.printStackTrace();
	                    }
                    }
                    Logger.debug("finished");
                    s.close();
                                
                }else{
                    Logger.info("No SQL initialization present for database:" + dbName);
                }
            }else{
                Logger.info("Initialization folder does not exist:" + f.getCanonicalPath());
            }
        }else{
            Logger.info("Schema exists");
        }
        
        //Testing
        {
            Logger.info("Teting database configuration");
            File f=null;
            f=getFile(APPLICATION_SQL_TEST,"../conf/sql/test/");
                
            if(f.exists()){
                Logger.info("Test folder exists:" + f.getCanonicalPath());
                String path = f.getAbsolutePath()+"/"+dbName+".sql";
                File initFile = new File(path);
                //Logger.info("Looking for sql script:" + initFile.getCanonicalPath());
                if(initFile.exists()){
                    Logger.info("Applying SQL testing:" + initFile.getCanonicalPath());
                    Statement s = DB.getConnection().createStatement();
                    String sqlRun = readFullFileToString(initFile);
                    //System.out.println(sqlRun);
                    sqlRun = interpretSQL(sqlRun);
                    boolean working=true;
                    try{
                    	
                        ResultSet rs1=s.executeQuery(sqlRun);
                        while(rs1.next()){
                            if(!"worked".equals(rs1.getString("result"))){
                                working=false;
                                Logger.error(rs1.getString("message") + "\n\nTry running the following SQL to fix this:\n\n" + rs1.getString("sql"));
                            }
                        }
                        rs1.close();
                                        
                    }catch(Exception e){
                        e.printStackTrace();
                    }
                    if(working){
                        Logger.debug("Passed configutation test");
                    }
                    s.close();
                }else{
                    Logger.info("No SQL test present for database:" + dbName);
                }
            }else{
                Logger.info("Test folder does not exist:" + f.getCanonicalPath());
            }
                
        }
        //Loading
        {
            Logger.info("Loading database-specific production scripts");
            File f=null;
            f=getFile(APPLICATION_SQL_LOAD,"../conf/sql/load/");
                
            if(f.exists()){
                Logger.info("Load folder exists:" + f.getCanonicalPath());
                String path = f.getAbsolutePath()+"/"+dbName+".sql";
                File initFile = new File(path);
                if(initFile.exists()){
                    Logger.info("Applying SQL loading:" + initFile.getCanonicalPath());
                    Statement s = DB.getConnection().createStatement();
                    String sqlRun = readFullFileToString(initFile);
                    System.out.println(sqlRun);
                    sqlRun = interpretSQL(sqlRun);
                    System.out.println("after:" + sqlRun);
                    for(String sqlLine : sqlRun.split(";")){
	                    try{
	                    	Logger.debug("applying load");
	                        ResultSet rs1=s.executeQuery(sqlLine);
	                        rs1.close();
	                    	Logger.debug("closing load");
	                        Logger.info("SQL initialization applied.");
	                    }catch(Exception e){
	                    	Logger.debug("ERROR load");
	                    	Logger.info("SQL loading failed:");
	                        e.printStackTrace();
	                    }
                    }
                    s.close();
                }else{
                    Logger.info("No SQL load present for database:" + dbName);
                }
            }else{
                Logger.info("Load folder does not exist:" + f.getCanonicalPath());
            }
                
        }
        //meta.

        host = app.configuration().getString(IxContext.APPLICATION_HOST);
        if (host == null || host.length() == 0) {
            host = null;
        }
        else {
            int pos = host.length();
            while (--pos > 0 && host.charAt(pos) == '/')
                ;
            host = host.substring(0, pos+1);
        }
        Logger.info("## Application host: "+host);

        context = app.configuration().getString(IxContext.APPLICATION_CONTEXT);
        if (context == null) {
            context = "";
        }
        else {
            int pos = context.length();
            while (--pos > 0 && context.charAt(pos) == '/')
                ;
            context = context.substring(0, pos+1);
        }
        Logger.info("## Application context: "+context);

        api = app.configuration().getString(IxContext.APPLICATION_API);
        if (api == null)
            api = "/api";
        else if (api.charAt(0) != '/')
            api = "/"+api;
        Logger.info("## Application api context: "
                    +((host != null ? host : "") + context+api));
    }
    
    public String interpretSQL(String rawSQL){
    	StringBuilder sb=new StringBuilder();
    	for(String line:(rawSQL).split("\n")){
    		if(line.startsWith("/*eval*/")){
    			
    			String[] evals=(line+" ").split("\\$");
    			int etotal = 0;
    			String nline=evals[0];
    			for(int i=1;i<evals.length-1;i++){
    				
    				String result = app.configuration().getString(evals[i]);
    				System.out.println(evals[i] + ":" + result);
    				if(result!=null){
    					etotal++;
    					nline+=result;
    					
    				}else{
    					System.out.println("Can't find variable: $" + evals[i] + "$, removing line.");
    				}
    			}
    			nline+=evals[evals.length-1];
    			if(etotal==evals.length-2){
    				sb.append(nline + "\n");
    			}
    		}else{
    			sb.append(line + "\n");
    		}
    	}    	
    	return sb.toString();
    	
    }
    
    /**
     * Simple utility function to read the full contents of a file
     * into a string. Do not use this method if the target file
     * is large.
     * 
     * Returns null on io exceptions.
     * 
     * @param f
     * @return
     */
    private static String readFullFileToString(File f){
        
        try {
            StringBuffer sb = new StringBuffer();
            BufferedReader br;
            br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            for (int c = br.read(); c != -1; c = br.read()) sb.append((char)c);
            return sb.toString();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * Checks if the db has been initialized. Used for running
     * sql scripts needed for some databases before later initialization.
     * 
     * 
     * This method currently checks for the existence of 'play_evolutions' table
     * @param meta
     * @return
     */
    private static boolean dbinitialized(DatabaseMetaData meta){
        ResultSet rs=null;
        try {
            rs = meta.getTables(null,null, "play_evolutions", null);
            boolean exists=rs.next();
            if(exists){
                rs.close();
                return true;
            }
            rs.close();
            rs = meta.getTables(null,null, "%", null);
            while(rs.next()){
                if(rs.getString(3).toLowerCase().contains("play_evolutions")){
                    rs.close();
                    return true;
                }
            }
            rs.close();
            return exists;
                        
        } catch (SQLException e) {
            // TODO Auto-generated catch block
            if(rs!=null)
                try {
                    rs.close();
                } catch (SQLException e1) {
                    // TODO Auto-generated catch block
                    e1.printStackTrace();
                }
            e.printStackTrace();
        }    
        return true;
    }

    File getFile (String var, String def) throws IOException {
        String name = app.configuration().getString(var);
        File f = null;
        if (name != null) {
            f = new File (name);
        }
        else {
            f = new File (home, def);
        }
        f.mkdirs();
        Logger.info("## "+var+": \""+f.getCanonicalPath()+"\"");
        return f;
    }

    public void onStart () {
        Logger.info("Loading plugin "+getClass().getName()+"...");        
        try {
            init ();
        }
        catch (Exception ex) {
            Logger.trace("Can't initialize app", ex);
        }
    }

    public void onStop () {
        Logger.info("Stopping plugin "+getClass().getName());
    }

    public boolean enabled () { return true; }
    
    public File home () { return home; }
    public File cache () { return cache; }
    public File text () { return text; }
    public File h2 () { return h2; }
    public File payload () { return payload; }
    public File structure () { return structure; }
    public File sequence () { return sequence; }    
    
    public int cacheTime () { return cacheTime; }
    public boolean debug (int level) { return debug >= level; }
    public String context () { return context; }
    public String api () { return api; }
    public String host () { return host; }
}
