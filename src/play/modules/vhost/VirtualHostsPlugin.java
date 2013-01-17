package play.modules.vhost;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import net.contentobjects.jnotify.JNotify;
import net.contentobjects.jnotify.JNotifyListener;
import play.Logger;
import play.Play;
import play.PlayPlugin;
import play.i18n.Lang;
import play.libs.MimeTypes;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.results.NotFound;
import play.templates.BaseTemplate;
import play.templates.GroovyTemplateCompiler;
import play.templates.Template;
import play.utils.Properties;
import play.vfs.VirtualFile;

import com.mchange.v2.c3p0.ComboPooledDataSource;

public class VirtualHostsPlugin extends PlayPlugin
{
  private static VirtualHostsPlugin config               = null;

  private String                    configDir            = null;
  private Integer                   notificationWatch;
  private JNotifyListener           notificationListener = null;
  private Map<String, VirtualHost>  vHostMap             = new HashMap<String, VirtualHost>();
  private Map<String, VirtualHost>  fqdnMap              = new HashMap<String, VirtualHost>();

  public static boolean isEnabled()
  {
    return config != null;
  }

  public VirtualHostsPlugin()
  {
    super();
    if (config == null) config = this;
  }

  public VirtualHostsPlugin(String pConfigDir)
  {
    super();
    if (config == null) config = this;
    config.loadVirtualHostRegistrations(pConfigDir);
  }

  @Override
  public void onApplicationStart()
  {
    String cfgDir = Play.configuration.getProperty("virtualhosts.dir", Play.getFile("conf").getAbsolutePath());
    config.loadVirtualHostRegistrations(cfgDir);
    // Create an InotifyEventListener instance
    if (notificationListener == null) {

      notificationListener = new JNotifyListener() {

        public void fileCreated(int wd, String rootPath, String name)
        {
          File f = getVirtualHostFile(rootPath, name);
          if (f != null) {
            Logger.info("Registering new virtual host from '%s'", f.getAbsolutePath());
            config.registerVirtualHost(f);
          }
        }

        public void fileDeleted(int wd, String rootPath, String name)
        {
          File f = getVirtualHostFile(rootPath, name);
          if (f != null) {
            Logger.info("Unregistering the virtual host from '%s'", f.getAbsolutePath());
            config.unregisterVirtualHost(f);
          }
        }

        public void fileModified(int wd, String rootPath, String name)
        {
          File f = getVirtualHostFile(rootPath, name);
          if (f != null) {
            Logger.info("Updating virtual host registration from '%s'", f.getAbsolutePath());
            config.registerVirtualHost(f);
          }
        }

        public void fileRenamed(int wd, String rootPath, String oldName, String newName)
        {
          fileDeleted(wd, rootPath, oldName);
          fileCreated(wd, rootPath, newName);
        }

        private File getVirtualHostFile(String dir, String nameName)
        {

          File f = new File(dir, nameName);
          return f.getName().endsWith(".vhost") ? f : null;
        }
      };
    }

    // register file change notifier for configuration directory
    try {
      notificationWatch = JNotify.addWatch(config.configDir, JNotify.FILE_ANY, false, notificationListener);
      Logger.info("Monitoring  '%s' for virtual host registrations", config.configDir);
    } catch (Throwable t) {
      Logger.error(t.getMessage());
      Logger.warn("Cannot initialize filesystem notification listener. You have to restart application if changes to virtual host registrations are made.");
    }
  }

  @Override
  public void onApplicationStop()
  {
    if (notificationWatch != null) {
      try {
        JNotify.removeWatch(notificationWatch);
        notificationWatch = null;
        Logger.info("VirtualHost registration monitor has been stopped");
      } catch (Throwable t) {
        Logger.error(t.getMessage());
      }
    }
  }

  @Override
  public Template loadTemplate(VirtualFile file)
  {
    Request currentRequest = Request.current();
    if (currentRequest == null || currentRequest.domain == null) return null;

    VirtualHost host = findHost(currentRequest.domain);
    if (host == null) return null;

    String filename = relativeFilename(file);
    VirtualFile vfile = findReplacement(filename, host);
    if (vfile == null || !vfile.exists()) return null;

    BaseTemplate result = null;
    synchronized (host.templateCache) {
      result = host.templateCache.get(filename);
      if (result == null || (Play.mode == Play.Mode.DEV && result.timestamp < vfile.lastModified())) {
        Logger.debug("VHOST: Override template '%s' with '%s'", filename,vfile.getRealFile().getAbsolutePath());
        result = new GroovyTemplateCompiler().compile(vfile);
        host.templateCache.put(filename, result);
      }
    }
    return result;
  }

  @Override
  public boolean serveStatic(VirtualFile file, Request request, Response response)
  {
    VirtualHost host = findHost(request.domain);
    if (host == null) return false;

    VirtualFile vfile = findReplacement(relativeFilename(file), host);
    if (vfile == null || !vfile.exists()) return false;

    Logger.debug("VHOST: Replaced file '%s' with '%s'", relativeFilename(file),vfile.getRealFile().getAbsolutePath());
    response.contentType = MimeTypes.getContentType(vfile.getName());
    response.status = 200;
    response.direct = vfile.getRealFile();
    return true;
  }

  private String relativeFilename(VirtualFile file)
  {
    String result = file.relativePath();
    if (result.startsWith("{")) result = result.substring(result.indexOf('}') + 1);

    return result;
  }

  private VirtualFile findReplacement(String file, VirtualHost host)
  {
    String home = host.config(VirtualHost.CFG_HOME_DIR, null);
    if (home == null) return null;
    if (!home.startsWith("/")) home = Play.applicationPath + "/" + home;

    return VirtualFile.open(home).child(file);
  }

  @Override
  public void beforeInvocation()
  {
    if (!VirtualHostsPlugin.isEnabled()) return;
    Request currentRequest = Http.Request.current();
    if (currentRequest == null || currentRequest.domain == null) return;

    VirtualHost host = findHost(currentRequest.domain);
    if (host == null) {
      if (!currentRequest.domain.equals("localhost")) throw new NotFound("");
      return;
    }

    // Fix request language if virtual host supports only a subset of application languages
    if (!host.langs.isEmpty() && !host.langs.contains(Lang.get())) {
      if (Logger.isDebugEnabled()) Logger.debug("VHOST: Override request language '%s' with '%s'", Lang.get(),host.langs.get(0));
      Lang.set(host.langs.get(0));
    }

    currentRequest.args.putAll(host.config);
    Map<String, DataSource> dataSources = new HashMap<String, DataSource>();
    if (host.getDataSource() != null) {
      dataSources.put(host.getName(), host.getDataSource());
    }
    currentRequest.args.put("dataSources", dataSources);
  }

  static VirtualHost findHost(String domain)
  {
    return config != null ? config.fqdnMap.get(domain) : null;
  }

  static VirtualHost[] getAllHosts()
  {
    final VirtualHost[] empty = {};
    return config != null ? config.fqdnMap.values().toArray(empty) : empty;
  }

  private void loadVirtualHostRegistrations(String pConfigDir)
  {
    // Clear current registrations
    synchronized (this) {
      vHostMap.clear();
      fqdnMap.clear();
    }

    configDir = pConfigDir;
    // reload existing registrations
    File cfgDir = new File(configDir);
    File[] cfgFiles = cfgDir.listFiles(new FileFilter() {

      public boolean accept(File file)
      {
        return file.getName().endsWith(".vhost") && file.isFile();
      }

    });
    if (cfgFiles == null) return;
    for (int i = 0; i < cfgFiles.length; i++) {
      Logger.info("Registering new virtual host from '%s'", cfgFiles[i].getAbsolutePath());
      registerVirtualHost(cfgFiles[i]);
    }
  }

  private void registerVirtualHost(File pVHostFile)
  {
    // Load virtual host registration file
    Properties vHostConfig = new Properties();
    String vHostFilename = pVHostFile.getAbsolutePath();
    boolean fileExists = pVHostFile.exists();
    try {
      if (fileExists) vHostConfig.load(new FileInputStream(pVHostFile));
    } catch (FileNotFoundException e) {
      Logger.error("File '%s' vanished. Registration processing is cancelled.", vHostFilename);
      return;
    } catch (IOException e) {
      Logger.info("Unexpected error occured while procesing virtual host registration from '%s'. Error message was: %s", pVHostFile.getName(), e.getMessage());
      return;
    }

    // Remove previous registration
    unregisterVirtualHost(vHostFilename);

    // Add new registration (if there is new)
    if (vHostConfig.size() == 0) return;

    final List<String> fqdnList = new ArrayList<String>();
    final String[] fqdns = ((String) vHostConfig.get("fqdns", "")).split(",");
    if (fqdns.length == 0) {
      Logger.error("No fqdns set for this virtual host. Ignoring registration file '%s'", vHostFilename);
      return;
    }
    for (String fqdn : fqdns) {
      if (fqdnMap.containsKey(fqdn)) {
        Logger.warn("'%s' is already in use by another virtual host. FQDN registration skipped.", fqdn);
      } else {
        fqdnList.add(fqdn);
      }
    }
    if (fqdnList.size() == 0) {
      Logger.error("All fqdns for this virtual host are aleady registered. Ignoring registration file '%s'", vHostFilename);
      return;
    }

    String driver = vHostConfig.get("db.driver");
    try {
      if (driver != null) Class.forName(driver);
    } catch (ClassNotFoundException e) {
      Logger.error("Database driver not found(%s). Virtual host in '%s' not loaded", driver, vHostFilename);
      return;
    }

    VirtualHost host;

    final String dbUrl = (String) vHostConfig.get("db.url", null);
    if (dbUrl == null) {
      host = new VirtualHost(fqdnList, vHostConfig, null);
    } else {
      System.setProperty("com.mchange.v2.log.MLog", "com.mchange.v2.log.FallbackMLog");
      System.setProperty("com.mchange.v2.log.FallbackMLog.DEFAULT_CUTOFF_LEVEL", "OFF");
      final ComboPooledDataSource cpds = new ComboPooledDataSource();
      cpds.setJdbcUrl("jdbc:" + dbUrl);
      cpds.setUser((String) vHostConfig.get("db.user", ""));
      cpds.setPassword((String) vHostConfig.get("db.pass", ""));
      cpds.setCheckoutTimeout(Integer.parseInt(vHostConfig.get("db.pool.timeout", "5000")));
      cpds.setMaxPoolSize(Integer.parseInt(vHostConfig.get("db.pool.maxSize", "5")));
      cpds.setMinPoolSize(Integer.parseInt(vHostConfig.get("db.pool.minSize", "1")));
      cpds.setAutoCommitOnClose(true);
      cpds.setAcquireRetryAttempts(1);
      cpds.setAcquireRetryDelay(0);
      host = new VirtualHost(fqdnList, vHostConfig, cpds);
    }

    synchronized (this) {
      vHostMap.put(vHostFilename, host);
      for (String fqdn : host.getFqdns())
        fqdnMap.put(fqdn, host);
    }
  }

  private void unregisterVirtualHost(File pVHostFile)
  {
    String vHostFilename = pVHostFile.getAbsolutePath();
    unregisterVirtualHost(vHostFilename);
  }

  private void unregisterVirtualHost(String pVHostFilename)
  {
    VirtualHost vHost = vHostMap.get(pVHostFilename);
    if (vHost != null) {
      synchronized (this) {
        for (String fqdn : vHost.getFqdns())
          fqdnMap.remove(fqdn);
        vHostMap.remove(pVHostFilename);
      }
      for (VirtualHostListener vhl : vHost.listeners)
        vhl.virtualHostUnloaded(vHost);
    }
  }

}
