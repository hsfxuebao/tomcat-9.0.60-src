/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.startup;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Policy;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.ObjectName;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.DistributedManager;
import org.apache.catalina.Globals;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleEvent;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.security.DeployXmlPermission;
import org.apache.catalina.util.ContextName;
import org.apache.catalina.util.IOTools;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.buf.UriUtil;
import org.apache.tomcat.util.digester.Digester;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.res.StringManager;

/**
 * Startup event listener for a <b>Host</b> that configures the properties
 * of that Host, and the associated defined contexts.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class HostConfig implements LifecycleListener {

    private static final Log log = LogFactory.getLog(HostConfig.class);

    /**
     * The string resources for this package.
     */
    protected static final StringManager sm = StringManager.getManager(HostConfig.class);

    /**
     * The resolution, in milliseconds, of file modification times.
     */
    protected static final long FILE_MODIFICATION_RESOLUTION_MS = 1000;


    // ----------------------------------------------------- Instance Variables

    /**
     * The Java class name of the Context implementation we should use.
     */
    //用到的context的类型
    protected String contextClass = "org.apache.catalina.core.StandardContext";


    /**
     * The Host we are associated with.
     */
    //监听的host对象
    protected Host host = null;


    /**
     * The JMX ObjectName of this component.
     */
    //在jmx上面注册的名字
    protected ObjectName oname = null;


    /**
     * Should we deploy XML Context config files packaged with WAR files and
     * directories?
     */
    //是否要处理app的context的xml配置文件
    protected boolean deployXML = false;


    /**
     * Should XML files be copied to
     * $CATALINA_BASE/conf/&lt;engine&gt;/&lt;host&gt; by default when
     * a web application is deployed?
     */
    //是否要将xml配置文件移动到/conf/enginename/hostname/下面
    protected boolean copyXML = false;


    /**
     * Should we unpack WAR files when auto-deploying applications in the
     * <code>appBase</code> directory?
     */
    //是否要解压war
    protected boolean unpackWARs = false;


    /**
     * Map of deployed applications.
     */
    //所有已经部署的应用，key是context的名字
    protected final Map<String, DeployedApplication> deployed =
        new ConcurrentHashMap<>();


    /**
     * List of applications which are being serviced, and shouldn't be
     * deployed/undeployed/redeployed at the moment.
     * @deprecated Unused. Will be removed in Tomcat 10.1.x onwards. Replaced
     *             by the private <code>servicedSet</code> field.
     */
    @Deprecated
    protected final ArrayList<String> serviced = new ArrayList<>();


    /**
     * Set of applications which are being serviced, and shouldn't be
     * deployed/undeployed/redeployed at the moment.
     */
    private Set<String> servicedSet = Collections.newSetFromMap(new ConcurrentHashMap<String,Boolean>());

    /**
     * The <code>Digester</code> instance used to parse context descriptors.
     */
    //用于解析xml文件的
    protected Digester digester = createDigester(contextClass);
    private final Object digesterLock = new Object();

    /**
     * The list of Wars in the appBase to be ignored because they are invalid
     * (e.g. contain /../ sequences).
     */
    //忽略的war包
    protected final Set<String> invalidWars = new HashSet<>();

    // ------------------------------------------------------------- Properties


    /**
     * @return the Context implementation class name.
     */
    //获取用到的context的类型
    public String getContextClass() {
        return this.contextClass;
    }


    /**
     * Set the Context implementation class name.
     *
     * @param contextClass The new Context implementation class name.
     */
    public void setContextClass(String contextClass) {

        String oldContextClass = this.contextClass;
        this.contextClass = contextClass;

        if (!oldContextClass.equals(contextClass)) {
            synchronized (digesterLock) {
                digester = createDigester(getContextClass());
            }
        }
    }


    /**
     * @return the deploy XML config file flag for this component.
     */
    public boolean isDeployXML() {
        return this.deployXML;
    }


    /**
     * Set the deploy XML config file flag for this component.
     *
     * @param deployXML The new deploy XML flag
     */
    public void setDeployXML(boolean deployXML) {
        this.deployXML = deployXML;
    }


    private boolean isDeployThisXML(File docBase, ContextName cn) {
        boolean deployThisXML = isDeployXML();
        if (Globals.IS_SECURITY_ENABLED && !deployThisXML) {
            // When running under a SecurityManager, deployXML may be overridden
            // on a per Context basis by the granting of a specific permission
            Policy currentPolicy = Policy.getPolicy();
            if (currentPolicy != null) {
                URL contextRootUrl;
                try {
                    contextRootUrl = docBase.toURI().toURL();
                    CodeSource cs = new CodeSource(contextRootUrl, (Certificate[]) null);
                    PermissionCollection pc = currentPolicy.getPermissions(cs);
                    Permission p = new DeployXmlPermission(cn.getBaseName());
                    if (pc.implies(p)) {
                        deployThisXML = true;
                    }
                } catch (MalformedURLException e) {
                    // Should never happen
                    log.warn(sm.getString("hostConfig.docBaseUrlInvalid"), e);
                }
            }
        }

        return deployThisXML;
    }


    /**
     * @return the copy XML config file flag for this component.
     */
    public boolean isCopyXML() {
        return this.copyXML;
    }


    /**
     * Set the copy XML config file flag for this component.
     *
     * @param copyXML The new copy XML flag
     */
    public void setCopyXML(boolean copyXML) {

        this.copyXML= copyXML;

    }


    /**
     * @return the unpack WARs flag.
     */
    public boolean isUnpackWARs() {
        return this.unpackWARs;
    }


    /**
     * Set the unpack WARs flag.
     *
     * @param unpackWARs The new unpack WARs flag
     */
    public void setUnpackWARs(boolean unpackWARs) {
        this.unpackWARs = unpackWARs;
    }


    // --------------------------------------------------------- Public Methods


    /**
     * Process the START event for an associated Host.
     *
     * @param event The lifecycle event that has occurred
     */
    @Override
    public void lifecycleEvent(LifecycleEvent event) {

        // Identify the host we are associated with
        try {
            host = (Host) event.getLifecycle();
            if (host instanceof StandardHost) {
                setCopyXML(((StandardHost) host).isCopyXML());
                setDeployXML(((StandardHost) host).isDeployXML());
                setUnpackWARs(((StandardHost) host).isUnpackWARs());
                setContextClass(((StandardHost) host).getContextClass());
            }
        } catch (ClassCastException e) {
            log.error(sm.getString("hostConfig.cce", event.getLifecycle()), e);
            return;
        }

        // Process the event that has occurred
        // 不同事件执行不同的方法
        if (event.getType().equals(Lifecycle.PERIODIC_EVENT)) {
            check();
        } else if (event.getType().equals(Lifecycle.BEFORE_START_EVENT)) {
            beforeStart();
        } else if (event.getType().equals(Lifecycle.START_EVENT)) {
            start();
        } else if (event.getType().equals(Lifecycle.STOP_EVENT)) {
            stop();
        }
    }


    /**
     * Add a serviced application to the list and indicates if the application
     * was already present in the list.
     *
     * @param name the context name
     *
     * @return {@code true} if the application was not already in the list
     */
    public boolean tryAddServiced(String name) {
        if (servicedSet.add(name)) {
            synchronized (this) {
                serviced.add(name);
            }
            return true;
        }
        return false;
    }


    /**
     * Add a serviced application to the list if it is not already present. If
     * the application is already in the list of serviced applications this
     * method is a NO-OP.
     *
     * @param name the context name
     *
     * @deprecated Unused. This method will be removed in Tomcat 10.1.x onwards.
     *             Use {@link #tryAddServiced} instead.
     */
    @Deprecated
    public void addServiced(String name) {
        servicedSet.add(name);
        synchronized (this) {
            serviced.add(name);
        }
    }


    /**
     * Is application serviced ?
     *
     * @param name the context name
     *
     * @return state of the application
     *
     * @deprecated Unused. This method will be removed in Tomcat 10.1.x onwards.
     *             Use {@link #tryAddServiced} instead.
     */
    @Deprecated
    public boolean isServiced(String name) {
        return servicedSet.contains(name);
    }


    /**
     * Removed a serviced application from the list.
     * @param name the context name
     */
    public void removeServiced(String name) {
        servicedSet.remove(name);
        synchronized (this) {
            serviced.remove(name);
        }
    }


    /**
     * Get the instant where an application was deployed.
     * @param name the context name
     * @return 0L if no application with that name is deployed, or the instant
     *  on which the application was deployed
     */
    public long getDeploymentTime(String name) {
        DeployedApplication app = deployed.get(name);
        if (app == null) {
            return 0L;
        }

        return app.timestamp;
    }


    /**
     * Has the specified application been deployed? Note applications defined
     * in server.xml will not have been deployed.
     * @param name the context name
     * @return <code>true</code> if the application has been deployed and
     *  <code>false</code> if the application has not been deployed or does not
     *  exist
     */
    public boolean isDeployed(String name) {
        return deployed.containsKey(name);
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Create the digester which will be used to parse context config files.
     * @param contextClassName The class which will be used to create the
     *  context instance
     * @return the digester
     */
    protected static Digester createDigester(String contextClassName) {
        Digester digester = new Digester();
        digester.setValidating(false);
        // Add object creation rule
        digester.addObjectCreate("Context", contextClassName, "className");
        // Set the properties on that object (it doesn't matter if extra
        // properties are set)
        digester.addSetProperties("Context");
        return digester;
    }

    protected File returnCanonicalPath(String path) {
        File file = new File(path);
        if (!file.isAbsolute()) {
            file = new File(host.getCatalinaBase(), path);
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file;
        }
    }


    /**
     * Get the name of the configBase.
     * For use with JMX management.
     * @return the config base
     */
    public String getConfigBaseName() {
        return host.getConfigBaseFile().getAbsolutePath();
    }


    /**
     * Deploy applications for any directories or WAR files that are found
     * in our "application root" directory.
     */
    // 在监听到start事件类型，也就是StandardHost调用startInternal
    protected void deployApps() {
        // 在server.xml中的Host标签指定appbase的属性为 webapps
        File appBase = host.getAppBaseFile();
        //这个值是在触发before_start时间时生成的，默认是tomcat安装目录+engine名+host名
        File configBase = host.getConfigBaseFile();

        //列出appBase下的所有文件、文件夹，进行过滤
        String[] filteredAppPaths = filterAppPaths(appBase.list());
        // Deploy XML descriptors from configBase
        deployDescriptors(configBase, configBase.list());
        // Deploy WARs
        // Deploy WARs 部署war包
        deployWARs(appBase, filteredAppPaths);
        // Deploy expanded folders web目录部署
        deployDirectories(appBase, filteredAppPaths);
    }


    /**
     * Filter the list of application file paths to remove those that match
     * the regular expression defined by {@link Host#getDeployIgnore()}.
     *
     * @param unfilteredAppPaths    The list of application paths to filter
     *
     * @return  The filtered list of application paths
     */
    protected String[] filterAppPaths(String[] unfilteredAppPaths) {
        Pattern filter = host.getDeployIgnorePattern();
        if (filter == null || unfilteredAppPaths == null) {
            return unfilteredAppPaths;
        }

        List<String> filteredList = new ArrayList<>();
        Matcher matcher = null;
        for (String appPath : unfilteredAppPaths) {
            if (matcher == null) {
                matcher = filter.matcher(appPath);
            } else {
                matcher.reset(appPath);
            }
            if (matcher.matches()) {
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("hostConfig.ignorePath", appPath));
                }
            } else {
                filteredList.add(appPath);
            }
        }
        return filteredList.toArray(new String[0]);
    }


    /**
     * Deploy applications for any directories or WAR files that are found
     * in our "application root" directory.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param name The context name which should be deployed
     */
    protected void deployApps(String name) {

        File appBase = host.getAppBaseFile();
        File configBase = host.getConfigBaseFile();
        ContextName cn = new ContextName(name, false);
        String baseName = cn.getBaseName();

        if (deploymentExists(cn.getName())) {
            return;
        }

        // Deploy XML descriptor from configBase
        File xml = new File(configBase, baseName + ".xml");
        if (xml.exists()) {
            deployDescriptor(cn, xml);
            return;
        }
        // Deploy WAR
        File war = new File(appBase, baseName + ".war");
        if (war.exists()) {
            deployWAR(cn, war);
            return;
        }
        // Deploy expanded folder
        File dir = new File(appBase, baseName);
        if (dir.exists()) {
            deployDirectory(cn, dir);
        }
    }


    /**
     * Deploy XML context descriptors.
     * @param configBase The config base
     * @param files The XML descriptors which should be deployed
     */
    protected void deployDescriptors(File configBase, String[] files) {

        if (files == null) {
            return;
        }

        //获取线程池
        ExecutorService es = host.getStartStopExecutor();
        List<Future<?>> results = new ArrayList<>();

        for (String file : files) {
            File contextXml = new File(configBase, file);

            if (file.toLowerCase(Locale.ENGLISH).endsWith(".xml")) {
                //context命名，在构造函数里面进行设置，设置版本，path，命名。
                ContextName cn = new ContextName(file, true);

                if (tryAddServiced(cn.getName())) {
                    try {
                        //是否已经部署过，如果已经部署过了，就不再进行部署
                        if (deploymentExists(cn.getName())) {
                            removeServiced(cn.getName());
                            continue;
                        }

                        // DeployDescriptor will call removeServiced
                        //异步发布context描述xml
                        results.add(es.submit(new DeployDescriptor(this, cn, contextXml)));
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        removeServiced(cn.getName());
                        throw t;
                    }
                }
            }
        }

        for (Future<?> result : results) {
            try {
                //等待异步部署context描述文件结束
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.deployDescriptor.threaded.error"), e);
            }
        }
    }


    /**
     * Deploy specified context descriptor.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param cn The context name
     * @param contextXml The descriptor
     */
    @SuppressWarnings("null") // context is not null
    protected void deployDescriptor(ContextName cn, File contextXml) {

        //发布应用，用于记录发布的context名，和是否有描述文件，一些可能被修改的文件的修改时间，用于日后检测是否需要重新加载
        DeployedApplication deployedApp = new DeployedApplication(cn.getName(), true);

        long startTime = 0;
        // Assume this is a configuration descriptor and deploy it
        if (log.isInfoEnabled()) {
            startTime = System.currentTimeMillis();
            log.info(sm.getString("hostConfig.deployDescriptor", contextXml.getAbsolutePath()));
        }

        Context context = null;
        //是否为扩展war包
        boolean isExternalWar = false;
        //是否是扩展web 应用
        boolean isExternal = false;
        //记录扩展web应用的地址
        File expandedDocBase = null;

        try (FileInputStream fis = new FileInputStream(contextXml)) {
            synchronized (digesterLock) {
                try {
                    //解析context.xml文件
                    context = (Context) digester.parse(fis);
                } catch (Exception e) {
                    log.error(sm.getString("hostConfig.deployDescriptor.error", contextXml.getAbsolutePath()), e);
                } finally {
                    //释放内存
                    digester.reset();
                    //如果创建失败，就new出一个失败的context
                    if (context == null) {
                        context = new FailedContext();
                    }
                }
            }

            if (context.getPath() != null) {
                log.warn(sm.getString("hostConfig.deployDescriptor.path", context.getPath(),
                    contextXml.getAbsolutePath()));
            }

            //host.getConfigClass() == org.apache.catalina.startup.ContextConfig
            Class<?> clazz = Class.forName(host.getConfigClass());
            //给context设置ContextConfig生命周期监听器
            LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
            context.addLifecycleListener(listener);

            context.setConfigFile(contextXml.toURI().toURL());
            context.setName(cn.getName());
            //一般我们在config/engine名+host名下的配置文件都是
            context.setPath(cn.getPath());
            context.setWebappVersion(cn.getVersion());
            // Add the associated docBase to the redeployed list if it's a WAR
            if (context.getDocBase() != null) {
                File docBase = new File(context.getDocBase());
                if (!docBase.isAbsolute()) {
                    docBase = new File(host.getAppBaseFile(), context.getDocBase());
                }
                // If external docBase, register .xml as redeploy first
                // 如果是扩展的web应用 那么首先进行重发布处理
                if (!docBase.getCanonicalFile().toPath().startsWith(host.getAppBaseFile().toPath())) {
                    isExternal = true;
                    //设置应用配置xml为重发布资源，记录最后修改的时间
                    deployedApp.redeployResources.put(
                        contextXml.getAbsolutePath(), Long.valueOf(contextXml.lastModified()));
                    //设置应用目录为重发布资源，记录最后修改的时间
                    deployedApp.redeployResources.put(
                        docBase.getAbsolutePath(), Long.valueOf(docBase.lastModified()));
                    //如果是war包，设置isExternalWar为true
                    if (docBase.getAbsolutePath().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                        isExternalWar = true;
                    }
                    // Check that a WAR or DIR in the appBase is not 'hidden'
                    File war = new File(host.getAppBaseFile(), cn.getBaseName() + ".war");
                    if (war.exists()) {
                        log.warn(sm.getString("hostConfig.deployDescriptor.hiddenWar",
                            contextXml.getAbsolutePath(), war.getAbsolutePath()));
                    }
                    File dir = new File(host.getAppBaseFile(), cn.getBaseName());
                    if (dir.exists()) {
                        log.warn(sm.getString("hostConfig.deployDescriptor.hiddenDir",
                            contextXml.getAbsolutePath(), dir.getAbsolutePath()));
                    }
                } else {
                    log.warn(sm.getString("hostConfig.deployDescriptor.localDocBaseSpecified", docBase));
                    // Ignore specified docBase
                    context.setDocBase(null);
                }
            }

            // todo 将context添加到对应host容器中，这里会进行context的初始化，启动生命周期
            host.addChild(context);
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployDescriptor.error", contextXml.getAbsolutePath()), t);
        } finally {
            // Get paths for WAR and expanded WAR in appBase

            // default to appBase dir + name
            // default to appBase dir + name 默认是AppBase路径加上容器的继承名称
            expandedDocBase = new File(host.getAppBaseFile(), cn.getBaseName());
            //如果应用的docBase不为空，也就是设置了应用的位置，并且不是war包
            if (context.getDocBase() != null && !context.getDocBase().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                // first assume docBase is absolute
                // first assume docBase is absolute 重新设置expandedDocBase
                expandedDocBase = new File(context.getDocBase());
                if (!expandedDocBase.isAbsolute()) {
                    // if docBase specified and relative, it must be relative to appBase
                    //如果是相对路径，都会认为相对appbase
                    expandedDocBase = new File(host.getAppBaseFile(), context.getDocBase());
                }
            }

            boolean unpackWAR = unpackWARs;
            if (unpackWAR && context instanceof StandardContext) {
                unpackWAR = ((StandardContext) context).getUnpackWAR();
            }

            // Add the eventual unpacked WAR and all the resources which will be
            // watched inside it
            //如果是war包，并且允许解压，那么把war加入重新部署检测列表
            if (isExternalWar) {
                if (unpackWAR) {
                    deployedApp.redeployResources.put(
                        expandedDocBase.getAbsolutePath(), Long.valueOf(expandedDocBase.lastModified()));
                    //加入以expandedDocBase为基路径的资源重新加载监控
                    //在配置文件中有WatchedResource这样的xml元素，可以配置需要重加载检测文件
                    addWatchedResources(deployedApp, expandedDocBase.getAbsolutePath(), context);
                } else {
                    //如果不允许解压，那么就直接用他们的相对路径进行资源修改监控
                    addWatchedResources(deployedApp, null, context);
                }
            } else {
                // Find an existing matching war and expanded folder
                //如果不是war包，而又不是扩展目录应用，那么自动加上war后缀
                if (!isExternal) {
                    File warDocBase = new File(expandedDocBase.getAbsolutePath() + ".war");
                    if (warDocBase.exists()) {
                        deployedApp.redeployResources.put(
                            warDocBase.getAbsolutePath(), Long.valueOf(warDocBase.lastModified()));
                    } else {
                        // Trigger a redeploy if a WAR is added
                        // 如果这个war后面被添加进来了，那么就触发重新加载
                        deployedApp.redeployResources.put(warDocBase.getAbsolutePath(), Long.valueOf(0));
                    }
                }
                //这段代码和上面的war时的代码是一样的
                if (unpackWAR) {
                    deployedApp.redeployResources.put(
                        expandedDocBase.getAbsolutePath(), Long.valueOf(expandedDocBase.lastModified()));
                    addWatchedResources(deployedApp, expandedDocBase.getAbsolutePath(), context);
                } else {
                    addWatchedResources(deployedApp, null, context);
                }
                if (!isExternal) {
                    // For external docBases, the context.xml will have been
                    // added above.
                    deployedApp.redeployResources.put(
                        contextXml.getAbsolutePath(), Long.valueOf(contextXml.lastModified()));
                }
            }
            // Add the global redeploy resources (which are never deleted) at
            // the end so they don't interfere with the deletion process
            //添加全局重新部署资源，如conf/engine名称+host名/context.xml.default和conf/context.xml
            addGlobalRedeployResources(deployedApp);
        }

        //如果这个web应用已经成功添加到host中，那么记录这个应用已经被发布
        if (host.findChild(context.getName()) != null) {
            deployed.put(context.getName(), deployedApp);
        }

        if (log.isInfoEnabled()) {
            log.info(sm.getString("hostConfig.deployDescriptor.finished",
                contextXml.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
        }
    }


    /**
     * Deploy WAR files.
     * @param appBase The base path for applications
     * @param files The WARs to deploy
     */
    protected void deployWARs(File appBase, String[] files) {

        if (files == null) {
            return;
        }

        ExecutorService es = host.getStartStopExecutor();
        List<Future<?>> results = new ArrayList<>();

        for (String file : files) {
            if (file.equalsIgnoreCase("META-INF")) {
                continue;
            }
            if (file.equalsIgnoreCase("WEB-INF")) {
                continue;
            }

            File war = new File(appBase, file);
            //处理war
            if (file.toLowerCase(Locale.ENGLISH).endsWith(".war") && war.isFile() && !invalidWars.contains(file)) {
                ContextName cn = new ContextName(file, true);
                if (tryAddServiced(cn.getName())) {
                    try {
                        //如果已经发布
                        if (deploymentExists(cn.getName())) {
                            DeployedApplication app = deployed.get(cn.getName());
                            boolean unpackWAR = unpackWARs;
                            if (unpackWAR && host.findChild(cn.getName()) instanceof StandardContext) {
                                unpackWAR = ((StandardContext) host.findChild(cn.getName())).getUnpackWAR();
                            }
                            //如果不需要解压，并且已经发布。那么判断对应的docBase是否已经存在
                            if (!unpackWAR && app != null) {
                                // Need to check for a directory that should not be
                                // there
                                File dir = new File(appBase, cn.getBaseName());
                                if (dir.exists()) {
                                    if (!app.loggedDirWarning) {
                                        log.warn(sm.getString("hostConfig.deployWar.hiddenDir",
                                            dir.getAbsoluteFile(), war.getAbsoluteFile()));
                                        app.loggedDirWarning = true;
                                    }
                                } else {
                                    app.loggedDirWarning = false;
                                }
                            }
                            removeServiced(cn.getName());
                            continue;
                        }

                        // Check for WARs with /../ /./ or similar sequences in the name
                        if (!validateContextPath(appBase, cn.getBaseName())) {
                            log.error(sm.getString("hostConfig.illegalWarName", file));
                            invalidWars.add(file);
                            removeServiced(cn.getName());
                            continue;
                        }

                        // DeployWAR will call removeServiced
                        results.add(es.submit(new DeployWar(this, cn, war)));
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        removeServiced(cn.getName());
                        throw t;
                    }
                }
            }
        }

        for (Future<?> result : results) {
            try {
                result.get();
            } catch (Exception e) {
                log.error(sm.getString("hostConfig.deployWar.threaded.error"), e);
            }
        }
    }


    private boolean validateContextPath(File appBase, String contextPath) {
        // More complicated than the ideal as the canonical path may or may
        // not end with File.separator for a directory

        StringBuilder docBase;
        String canonicalDocBase = null;

        try {
            String canonicalAppBase = appBase.getCanonicalPath();
            docBase = new StringBuilder(canonicalAppBase);
            if (canonicalAppBase.endsWith(File.separator)) {
                docBase.append(contextPath.substring(1).replace('/', File.separatorChar));
            } else {
                docBase.append(contextPath.replace('/', File.separatorChar));
            }
            // At this point docBase should be canonical but will not end
            // with File.separator

            canonicalDocBase = (new File(docBase.toString())).getCanonicalPath();

            // If the canonicalDocBase ends with File.separator, add one to
            // docBase before they are compared
            if (canonicalDocBase.endsWith(File.separator)) {
                docBase.append(File.separator);
            }
        } catch (IOException ioe) {
            return false;
        }

        // Compare the two. If they are not the same, the contextPath must
        // have /../ like sequences in it
        return canonicalDocBase.equals(docBase.toString());
    }

    /**
     * Deploy packed WAR.
     * <p>
     * Note: It is expected that the caller has successfully added the app
     *       to servicedSet before calling this method.
     *
     * @param cn The context name
     * @param war The WAR file
     */
    protected void deployWAR(ContextName cn, File war) {

        File xml = new File(host.getAppBaseFile(), cn.getBaseName() + "/" + Constants.ApplicationContextXml);

        File warTracker = new File(host.getAppBaseFile(), cn.getBaseName() + Constants.WarTracker);

        boolean xmlInWar = false;
        try (JarFile jar = new JarFile(war)) {
            JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
            //判断是否有context.xml在jar包里面
            if (entry != null) {
                xmlInWar = true;
            }
        } catch (IOException e) {
            /* Ignore */
        }

        // If there is an expanded directory then any xml in that directory
        // should only be used if the directory is not out of date and
        // unpackWARs is true. Note the code below may apply further limits
        boolean useXml = false;
        // If the xml file exists then expandedDir must exists so no need to
        // test that here
        //如果对应xml存在，并且允许解压，也就是已经解压了，这个war包没有修改过
        if (xml.exists() && unpackWARs && (!warTracker.exists() || warTracker.lastModified() == war.lastModified())) {
            useXml = true;
        }

        Context context = null;
        //是否需要发布这个context.xml，通过校验
        boolean deployThisXML = isDeployThisXML(war, cn);

        try {
            //允许发布这个xml并且仅仅是使用xml并且不允许copyxml到配置目录，
            // 如果允许copy到配置目录，那么这个xml可能不是最新的。
            if (deployThisXML && useXml && !copyXML) {
                synchronized (digesterLock) {
                    try {
                        context = (Context) digester.parse(xml);
                    } catch (Exception e) {
                        log.error(sm.getString("hostConfig.deployDescriptor.error", war.getAbsolutePath()), e);
                    } finally {
                        digester.reset();
                        if (context == null) {
                            context = new FailedContext();
                        }
                    }
                }
                //设置context.xml配置文件
                context.setConfigFile(xml.toURI().toURL());
                //允许发布这个context.xml并且这个xml在war包里面
            } else if (deployThisXML && xmlInWar) {
                synchronized (digesterLock) {
                    try (JarFile jar = new JarFile(war)) {
                        JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
                        try (InputStream istream = jar.getInputStream(entry)) {
                            context = (Context) digester.parse(istream);
                        }
                    } catch (Exception e) {
                        log.error(sm.getString("hostConfig.deployDescriptor.error", war.getAbsolutePath()), e);
                    } finally {
                        digester.reset();
                        if (context == null) {
                            context = new FailedContext();
                        }
                        context.setConfigFile(UriUtil.buildJarUrl(war, Constants.ApplicationContextXml));
                    }
                }
                //不允许发布这个context.xml并且这个xml在war包里面的，那么就直接new出一个StandardContext
            } else if (!deployThisXML && xmlInWar) {
                // Block deployment as META-INF/context.xml may contain security
                // configuration necessary for a secure deployment.
                log.error(sm.getString("hostConfig.deployDescriptor.blocked",
                    cn.getPath(), Constants.ApplicationContextXml,
                    new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml")));
            } else {
                context = (Context) Class.forName(contextClass).getConstructor().newInstance();
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("hostConfig.deployWar.error", war.getAbsolutePath()), t);
        } finally {
            if (context == null) {
                context = new FailedContext();
            }
        }

        //是否需要复制xml到配置文件夹中
        boolean copyThisXml = false;
        //允许发布
        if (deployThisXML) {
                if (host instanceof StandardHost) {
                    copyThisXml = ((StandardHost) host).isCopyXML();
                }

                // If Host is using default value Context can override it.
                // context指定的copyXml属性可以覆盖host级别的
                if (!copyThisXml && context instanceof StandardContext) {
                    copyThisXml = ((StandardContext) context).getCopyXML();
                }

                //如果context.xml在war中，并且允许复制这个xml到配置路径，那么就将此context.xml复制到配置文件夹中
                if (xmlInWar && copyThisXml) {
                    // Change location of XML file to config base
                    xml = new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml");
                    try (JarFile jar = new JarFile(war)) {
                        JarEntry entry = jar.getJarEntry(Constants.ApplicationContextXml);
                        try (InputStream istream = jar.getInputStream(entry);
                            OutputStream ostream = new FileOutputStream(xml)) {
                            IOTools.flow(istream, ostream);
                        }
                    } catch (IOException e) {
                        /* Ignore */
                    }
                }
            }

            //下面这部分代码基本和deployDescriptors差不多
            DeployedApplication deployedApp = new DeployedApplication(
                cn.getName(), xml.exists() && deployThisXML && copyThisXml);

            long startTime = 0;
            // Deploy the application in this WAR file
            if (log.isInfoEnabled()) {
                startTime = System.currentTimeMillis();
                log.info(sm.getString("hostConfig.deployWar", war.getAbsolutePath()));
            }

            try {
                // Populate redeploy resources with the WAR file
                deployedApp.redeployResources.put(war.getAbsolutePath(), Long.valueOf(war.lastModified()));

                if (deployThisXML && xml.exists() && copyThisXml) {
                    //这个xml被复制到配置目录
                    deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
                } else {
                    // In case an XML file is added to the config base later
                    // 如果配置加载配置目录中才会有效
                    deployedApp.redeployResources.put(
                        (new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml")).getAbsolutePath(),
                        Long.valueOf(0));
                }

                Class<?> clazz = Class.forName(host.getConfigClass());
                LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
                context.addLifecycleListener(listener);

                context.setName(cn.getName());
                context.setPath(cn.getPath());
                context.setWebappVersion(cn.getVersion());
                context.setDocBase(cn.getBaseName() + ".war");
                //与deployDescriptors一样的操作
                host.addChild(context);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("hostConfig.deployWar.error", war.getAbsolutePath()), t);
            } finally {
                // If we're unpacking WARs, the docBase will be mutated after
                // starting the context
                boolean unpackWAR = unpackWARs;
                if (unpackWAR && context instanceof StandardContext) {
                    unpackWAR = ((StandardContext) context).getUnpackWAR();
                }
                //上面不是已经添加了docBase资源的监控了吗？这里是因为tomcat在解压了war之后会重新定义它的docBase目录，所以需要添加，不管原始的还是新的都要进行监控
                if (unpackWAR && context.getDocBase() != null) {
                    File docBase = new File(host.getAppBaseFile(), cn.getBaseName());
                    deployedApp.redeployResources.put(docBase.getAbsolutePath(), Long.valueOf(docBase.lastModified()));
                    addWatchedResources(deployedApp, docBase.getAbsolutePath(), context);
                    if (deployThisXML && !copyThisXml && (xmlInWar || xml.exists())) {
                        deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
                    }
                } else {
                    // Passing null for docBase means that no resources will be
                    // watched. This will be logged at debug level.
                    addWatchedResources(deployedApp, null, context);
                }
                // Add the global redeploy resources (which are never deleted) at
                // the end so they don't interfere with the deletion process
                addGlobalRedeployResources(deployedApp);
            }

            deployed.put(cn.getName(), deployedApp);

            if (log.isInfoEnabled()) {
                log.info(sm.getString("hostConfig.deployWar.finished",
                    war.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
            }
        }



        /**
         * Deploy exploded webapps.
         * @param appBase The base path for applications
         * @param files The exploded webapps that should be deployed
         */
        protected void deployDirectories(File appBase, String[] files) {

            if (files == null) {
                return;
            }

            ExecutorService es = host.getStartStopExecutor();
            List<Future<?>> results = new ArrayList<>();

            for (String file : files) {
                if (file.equalsIgnoreCase("META-INF")) {
                    continue;
                }
                if (file.equalsIgnoreCase("WEB-INF")) {
                    continue;
                }

                File dir = new File(appBase, file);
                if (dir.isDirectory()) {
                    ContextName cn = new ContextName(file, false);

                    if (tryAddServiced(cn.getName())) {
                        try {
                            if (deploymentExists(cn.getName())) {
                                removeServiced(cn.getName());
                                continue;
                            }

                            // DeployDirectory will call removeServiced
                            results.add(es.submit(new DeployDirectory(this, cn, dir)));
                        } catch (Throwable t) {
                            ExceptionUtils.handleThrowable(t);
                            removeServiced(cn.getName());
                            throw t;
                        }
                    }
                }
            }

            for (Future<?> result : results) {
                try {
                    result.get();
                } catch (Exception e) {
                    log.error(sm.getString("hostConfig.deployDir.threaded.error"), e);
                }
            }
        }


        /**
         * Deploy exploded webapp.
         * <p>
         * Note: It is expected that the caller has successfully added the app
         *       to servicedSet before calling this method.
         *
         * @param cn The context name
         * @param dir The path to the root folder of the webapp
         */
        protected void deployDirectory(ContextName cn, File dir) {

            long startTime = 0;
            // Deploy the application in this directory
            if( log.isInfoEnabled() ) {
                startTime = System.currentTimeMillis();
                log.info(sm.getString("hostConfig.deployDir", dir.getAbsolutePath()));
            }

            Context context = null;

            // xml文件名 = dir + META-INF/context.xml
            File xml = new File(dir, Constants.ApplicationContextXml);
            //当允许copyxml的时候会使用到
            File xmlCopy = new File(host.getConfigBaseFile(), cn.getBaseName() + ".xml");

            DeployedApplication deployedApp;
            boolean copyThisXml = isCopyXML();
            // 判断Host的deployXML属性值为true 还是false （true表示即通过Context描述文件部署)
            boolean deployThisXML = isDeployThisXML(dir, cn);

            try {
                if (deployThisXML && xml.exists()) {
                    synchronized (digesterLock) {
                        try {
                            context = (Context) digester.parse(xml);
                        } catch (Exception e) {
                            log.error(sm.getString("hostConfig.deployDescriptor.error", xml), e);
                            context = new FailedContext();
                        } finally {
                            digester.reset();
                            if (context == null) {
                                context = new FailedContext();
                            }
                        }
                    }

                    //这里有点疑问，为什么host配置的copyThisXml为false的时候才允许context进行覆盖？
                    if (copyThisXml == false && context instanceof StandardContext) {
                        // Host is using default value. Context may override it.
                        copyThisXml = ((StandardContext) context).getCopyXML();
                    }

                    //当允许copy时，进行复制，并把配置文件设置为新的目录
                    if (copyThisXml) {
                        Files.copy(xml.toPath(), xmlCopy.toPath());
                        context.setConfigFile(xmlCopy.toURI().toURL());
                    } else {
                        context.setConfigFile(xml.toURI().toURL());
                    }
                //如果不允许发布这个配置文件并且对应的xml存在的话，构造失败context，why？？？即使存在context.xml也可以构造一个默认的context
                } else if (!deployThisXML && xml.exists()) {
                    // Block deployment as META-INF/context.xml may contain security
                    // configuration necessary for a secure deployment.
                    log.error(sm.getString("hostConfig.deployDescriptor.blocked", cn.getPath(), xml, xmlCopy));
                    context = new FailedContext();
                } else {
                    // 创建StandardContext的实例
                    context = (Context) Class.forName(contextClass).getConstructor().newInstance();
                }

                //下面的步骤和部署描述文件和部署war是一样的。不再赘述
                Class<?> clazz = Class.forName(host.getConfigClass());
                LifecycleListener listener = (LifecycleListener) clazz.getConstructor().newInstance();
                context.addLifecycleListener(listener);

                context.setName(cn.getName());
                context.setPath(cn.getPath());
                context.setWebappVersion(cn.getVersion());
                context.setDocBase(cn.getBaseName());
                // todo 启动contxt
                host.addChild(context);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.error(sm.getString("hostConfig.deployDir.error", dir.getAbsolutePath()), t);
            } finally {
                deployedApp = new DeployedApplication(cn.getName(), xml.exists() && deployThisXML && copyThisXml);

                // Fake re-deploy resource to detect if a WAR is added at a later
                // point
                deployedApp.redeployResources.put(dir.getAbsolutePath() + ".war", Long.valueOf(0));
                deployedApp.redeployResources.put(dir.getAbsolutePath(), Long.valueOf(dir.lastModified()));
                if (deployThisXML && xml.exists()) {
                    if (copyThisXml) {
                        deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(xmlCopy.lastModified()));
                    } else {
                        deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(xml.lastModified()));
                        // Fake re-deploy resource to detect if a context.xml file is
                        // added at a later point
                        deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(0));
                    }
                } else {
                    // Fake re-deploy resource to detect if a context.xml file is
                    // added at a later point
                    deployedApp.redeployResources.put(xmlCopy.getAbsolutePath(), Long.valueOf(0));
                    if (!xml.exists()) {
                        deployedApp.redeployResources.put(xml.getAbsolutePath(), Long.valueOf(0));
                    }
                }
                addWatchedResources(deployedApp, dir.getAbsolutePath(), context);
                // Add the global redeploy resources (which are never deleted) at
                // the end so they don't interfere with the deletion process
                addGlobalRedeployResources(deployedApp);
            }

            deployed.put(cn.getName(), deployedApp);

            if( log.isInfoEnabled() ) {
                log.info(sm.getString("hostConfig.deployDir.finished",
                    dir.getAbsolutePath(), Long.valueOf(System.currentTimeMillis() - startTime)));
            }
        }


        /**
         * Check if a webapp is already deployed in this host.
         *
         * @param contextName of the context which will be checked
         * @return <code>true</code> if the specified deployment exists
         */
        protected boolean deploymentExists(String contextName) {
            return deployed.containsKey(contextName) || (host.findChild(contextName) != null);
        }


        /**
         * Add watched resources to the specified Context.
         * @param app HostConfig deployed app
         * @param docBase web app docBase
         * @param context web application context
         */
        protected void addWatchedResources(DeployedApplication app, String docBase, Context context) {
            // FIXME: Feature idea. Add support for patterns (ex: WEB-INF/*,
            //        WEB-INF/*.xml), where we would only check if at least one
            //        resource is newer than app.timestamp
            File docBaseFile = null;
            if (docBase != null) {
                docBaseFile = new File(docBase);
                if (!docBaseFile.isAbsolute()) {
                    docBaseFile = new File(host.getAppBaseFile(), docBase);
                }
            }
            String[] watchedResources = context.findWatchedResources();
            for (String watchedResource : watchedResources) {
                File resource = new File(watchedResource);
                if (!resource.isAbsolute()) {
                    if (docBase != null) {
                        resource = new File(docBaseFile, watchedResource);
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Ignoring non-existent WatchedResource '" +
                                resource.getAbsolutePath() + "'");
                        }
                        continue;
                    }
                }
                if (log.isDebugEnabled()) {
                    log.debug("Watching WatchedResource '" +
                        resource.getAbsolutePath() + "'");
                }
                app.reloadResources.put(resource.getAbsolutePath(),
                    Long.valueOf(resource.lastModified()));
            }
        }


        protected void addGlobalRedeployResources(DeployedApplication app) {
            // Redeploy resources processing is hard-coded to never delete this file
            File hostContextXml =
                new File(getConfigBaseName(), Constants.HostContextXml);
            if (hostContextXml.isFile()) {
                app.redeployResources.put(hostContextXml.getAbsolutePath(),
                    Long.valueOf(hostContextXml.lastModified()));
            }

            // Redeploy resources in CATALINA_BASE/conf are never deleted
            File globalContextXml =
                returnCanonicalPath(Constants.DefaultContextXml);
            if (globalContextXml.isFile()) {
                app.redeployResources.put(globalContextXml.getAbsolutePath(),
                    Long.valueOf(globalContextXml.lastModified()));
            }
        }


        /**
         * Check resources for redeployment and reloading.
         *
         * @param app   The web application to check
         * @param skipFileModificationResolutionCheck
         *              When checking files for modification should the check that
         *              requires that any file modification must have occurred at
         *              least as long ago as the resolution of the file time stamp
         *              be skipped
         */
        protected synchronized void checkResources(DeployedApplication app,
        boolean skipFileModificationResolutionCheck) {
            // 需要重新部署的守护资源
            String[] resources =
                app.redeployResources.keySet().toArray(new String[0]);
            // Offset the current time by the resolution of File.lastModified()
            long currentTimeWithResolutionOffset =
                System.currentTimeMillis() - FILE_MODIFICATION_RESOLUTION_MS;
            for (int i = 0; i < resources.length; i++) {
                File resource = new File(resources[i]);
                if (log.isDebugEnabled()) {
                    log.debug("Checking context[" + app.name +
                        "] redeploy resource " + resource);
                }
                long lastModified =
                    app.redeployResources.get(resources[i]).longValue();
                if (resource.exists() || lastModified == 0) {
                    // File.lastModified() has a resolution of 1s (1000ms). The last
                    // modified time has to be more than 1000ms ago to ensure that
                    // modifications that take place in the same second are not
                    // missed. See Bug 57765.
                    if (resource.lastModified() != lastModified && (!host.getAutoDeploy() ||
                        resource.lastModified() < currentTimeWithResolutionOffset ||
                        skipFileModificationResolutionCheck)) {
                        // 如果资源对应为目录，则更新守护资源列表中的上次修改时间
                        if (resource.isDirectory()) {
                            // No action required for modified directory
                            app.redeployResources.put(resources[i],
                                Long.valueOf(resource.lastModified()));

                        // 如果Web应用存在Context描述文件并且当前变更的是WAR包文件，
                        //则得到原Context的docBase。
                        } else if (app.hasDescriptor &&
                            resource.getName().toLowerCase(
                                Locale.ENGLISH).endsWith(".war")) {
                            // Modified WAR triggers a reload if there is an XML
                            // file present
                            // The only resource that should be deleted is the
                            // expanded WAR (if any)
                            Context context = (Context) host.findChild(app.name);
                            String docBase = context.getDocBase();

                            // 如果docBase不以“.war”结尾（即Context指向的是WAR解压目录，删除解压
                            //目录并重新加载，否则直接重新加载。更新守护资源。
                            if (!docBase.toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                                // This is an expanded directory
                                File docBaseFile = new File(docBase);
                                if (!docBaseFile.isAbsolute()) {
                                    docBaseFile = new File(host.getAppBaseFile(),
                                        docBase);
                                }
                                reload(app, docBaseFile, resource.getAbsolutePath());
                            } else {
                                reload(app, null, null);
                            }
                            // Update times
                            app.redeployResources.put(resources[i],
                                Long.valueOf(resource.lastModified()));
                            app.timestamp = System.currentTimeMillis();
                            boolean unpackWAR = unpackWARs;
                            if (unpackWAR && context instanceof StandardContext) {
                                unpackWAR = ((StandardContext) context).getUnpackWAR();
                            }
                            if (unpackWAR) {
                                addWatchedResources(app, context.getDocBase(), context);
                            } else {
                                addWatchedResources(app, null, context);
                            }
                            return;
                        } else {
                            // Everything else triggers a redeploy
                            // (just need to undeploy here, deploy will follow)
                            // 直接卸载应用，并由接下来的处理步骤重新部署
                            undeploy(app);
                            deleteRedeployResources(app, resources, i, false);
                            return;
                        }
                    }
                } else {
                    // There is a chance the the resource was only missing
                    // temporarily eg renamed during a text editor save
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e1) {
                        // Ignore
                    }
                    // Recheck the resource to see if it was really deleted
                    if (resource.exists()) {
                        continue;
                    }
                    // Undeploy application
                    // 其他情况，直接卸载应用，并由接下来的处理步骤重新部署
                    undeploy(app);
                    deleteRedeployResources(app, resources, i, true);
                    return;
                }
            }

            // 需要重新加载的资源
            resources = app.reloadResources.keySet().toArray(new String[0]);
            boolean update = false;
            for (String s : resources) {
                File resource = new File(s);
                if (log.isDebugEnabled()) {
                    log.debug("Checking context[" + app.name + "] reload resource " + resource);
                }
                long lastModified = app.reloadResources.get(s).longValue();
                // File.lastModified() has a resolution of 1s (1000ms). The last
                // modified time has to be more than 1000ms ago to ensure that
                // modifications that take place in the same second are not
                // missed. See Bug 57765.
                if ((resource.lastModified() != lastModified &&
                    (!host.getAutoDeploy() ||
                        resource.lastModified() < currentTimeWithResolutionOffset ||
                        skipFileModificationResolutionCheck)) ||
                    update) {
                    if (!update) {
                        // Reload application
                        // 如果资源已经变更，则重新加载Context对象
                        reload(app, null, null);
                        update = true;
                    }
                    // Update times. More than one file may have been updated. We
                    // don't want to trigger a series of reloads.
                    app.reloadResources.put(s,
                        Long.valueOf(resource.lastModified()));
                }
                app.timestamp = System.currentTimeMillis();
            }
        }


        /*
         * Note: If either of fileToRemove and newDocBase are null, both will be
         *       ignored.
         */
        private void reload(DeployedApplication app, File fileToRemove, String newDocBase) {
            if(log.isInfoEnabled()) {
                log.info(sm.getString("hostConfig.reload", app.name));
            }
            Context context = (Context) host.findChild(app.name);
            if (context.getState().isAvailable()) {
                if (fileToRemove != null && newDocBase != null) {
                    context.addLifecycleListener(
                        new ExpandedDirectoryRemovalListener(fileToRemove, newDocBase));
                }
                // Reload catches and logs exceptions
                context.reload();
            } else {
                // If the context was not started (for example an error
                // in web.xml) we'll still get to try to start
                if (fileToRemove != null && newDocBase != null) {
                    ExpandWar.delete(fileToRemove);
                    context.setDocBase(newDocBase);
                }
                try {
                    // todo Context启动
                    context.start();
                } catch (Exception e) {
                    log.error(sm.getString("hostConfig.context.restart", app.name), e);
                }
            }
        }


        private void undeploy(DeployedApplication app) {
            if (log.isInfoEnabled()) {
                log.info(sm.getString("hostConfig.undeploy", app.name));
            }
            Container context = host.findChild(app.name);
            try {
                host.removeChild(context);
            } catch (Throwable t) {
                ExceptionUtils.handleThrowable(t);
                log.warn(sm.getString
                    ("hostConfig.context.remove", app.name), t);
            }
            deployed.remove(app.name);
        }


        private void deleteRedeployResources(DeployedApplication app, String[] resources, int i,
        boolean deleteReloadResources) {

            // Delete other redeploy resources
            for (int j = i + 1; j < resources.length; j++) {
                File current = new File(resources[j]);
                // Never delete per host context.xml defaults
                if (Constants.HostContextXml.equals(current.getName())) {
                    continue;
                }
                // Only delete resources in the appBase or the
                // host's configBase
                if (isDeletableResource(app, current)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Delete " + current);
                    }
                    ExpandWar.delete(current);
                }
            }

            // Delete reload resources (to remove any remaining .xml descriptor)
            if (deleteReloadResources) {
                String[] resources2 = app.reloadResources.keySet().toArray(new String[0]);
                for (String s : resources2) {
                    File current = new File(s);
                    // Never delete per host context.xml defaults
                    if (Constants.HostContextXml.equals(current.getName())) {
                        continue;
                    }
                    // Only delete resources in the appBase or the host's
                    // configBase
                    if (isDeletableResource(app, current)) {
                        if (log.isDebugEnabled()) {
                            log.debug("Delete " + current);
                        }
                        ExpandWar.delete(current);
                    }
                }
            }
        }


        /*
         * Delete any resource that would trigger the automatic deployment code to
         * re-deploy the application. This means deleting:
         * - any resource located in the appBase
         * - any deployment descriptor located under the configBase
         * - symlinks in the appBase or configBase for either of the above
         */
        private boolean isDeletableResource(DeployedApplication app, File resource) {
            // The resource may be a file, a directory or a symlink to a file or
            // directory.

            // Check that the resource is absolute. This should always be the case.
            if (!resource.isAbsolute()) {
                log.warn(sm.getString("hostConfig.resourceNotAbsolute", app.name, resource));
                return false;
            }

            // Determine where the resource is located
            String canonicalLocation;
            try {
                canonicalLocation = resource.getParentFile().getCanonicalPath();
            } catch (IOException e) {
                log.warn(sm.getString(
                    "hostConfig.canonicalizing", resource.getParentFile(), app.name), e);
                return false;
            }

            String canonicalAppBase;
            try {
                canonicalAppBase = host.getAppBaseFile().getCanonicalPath();
            } catch (IOException e) {
                log.warn(sm.getString(
                    "hostConfig.canonicalizing", host.getAppBaseFile(), app.name), e);
                return false;
            }

            if (canonicalLocation.equals(canonicalAppBase)) {
                // Resource is located in the appBase so it may be deleted
                return true;
            }

            String canonicalConfigBase;
            try {
                canonicalConfigBase = host.getConfigBaseFile().getCanonicalPath();
            } catch (IOException e) {
                log.warn(sm.getString(
                    "hostConfig.canonicalizing", host.getConfigBaseFile(), app.name), e);
                return false;
            }

            if (canonicalLocation.equals(canonicalConfigBase) &&
                resource.getName().endsWith(".xml")) {
                // Resource is an xml file in the configBase so it may be deleted
                return true;
            }

            // All other resources should not be deleted
            return false;
        }


        public void beforeStart() {
            if (host.getCreateDirs()) {
                File[] dirs = new File[] {host.getAppBaseFile(),host.getConfigBaseFile()};
                for (File dir : dirs) {
                    if (!dir.mkdirs() && !dir.isDirectory()) {
                        log.error(sm.getString("hostConfig.createDirs", dir));
                    }
                }
            }
        }


        /**
         * Process a "start" event for this Host.
         */
        public void start() {

            if (log.isDebugEnabled()) {
                log.debug(sm.getString("hostConfig.start"));
            }

            try {
                ObjectName hostON = host.getObjectName();
                oname = new ObjectName
                    (hostON.getDomain() + ":type=Deployer,host=" + host.getName());
                Registry.getRegistry(null, null).registerComponent
                    (this, oname, this.getClass().getName());
            } catch (Exception e) {
                log.warn(sm.getString("hostConfig.jmx.register", oname), e);
            }

            if (!host.getAppBaseFile().isDirectory()) {
                log.error(sm.getString("hostConfig.appBase", host.getName(),
                    host.getAppBaseFile().getPath()));
                host.setDeployOnStartup(false);
                host.setAutoDeploy(false);
            }
            // 只有当deployOnStartup为true的时候才会执行
            if (host.getDeployOnStartup()) {
                deployApps();
            }
        }


        /**
         * Process a "stop" event for this Host.
         */
        public void stop() {

            if (log.isDebugEnabled()) {
                log.debug(sm.getString("hostConfig.stop"));
            }

            if (oname != null) {
                try {
                    Registry.getRegistry(null, null).unregisterComponent(oname);
                } catch (Exception e) {
                    log.warn(sm.getString("hostConfig.jmx.unregister", oname), e);
                }
            }
            oname = null;
        }


        /**
         * Check status of all webapps.
         */
        protected void check() {

            // 开启自动部署
            if (host.getAutoDeploy()) {
                // Check for resources modification to trigger redeployment
                // 每一个已部署的应用
                DeployedApplication[] apps = deployed.values().toArray(new DeployedApplication[0]);
                for (DeployedApplication app : apps) {
                    if (tryAddServiced(app.name)) {
                        try {
                            // 检验资源（需要重新部署和重新加载的资源）
                            checkResources(app, false);
                        } finally {
                            removeServiced(app.name);
                        }
                    }
                }

                // Check for old versions of applications that can now be undeployed
                if (host.getUndeployOldVersions()) {
                    checkUndeploy();
                }

                // Hotdeploy applications
                // 热部署应用
                deployApps();
            }
        }


        /**
         * Check status of a specific web application and reload, redeploy or deploy
         * it as necessary. This method is for use with functionality such as
         * management web applications that upload new/updated web applications and
         * need to trigger the appropriate action to deploy them. This method
         * assumes that any uploading/updating has been completed before this method
         * is called. Any action taken as a result of the checks will complete
         * before this method returns.
         *
         * @param name The name of the web application to check
         */
        public void check(String name) {
            if (tryAddServiced(name)) {
                try {
                    DeployedApplication app = deployed.get(name);
                    if (app != null) {
                        checkResources(app, true);
                    }
                    deployApps(name);
                } finally {
                    removeServiced(name);
                }
            }
        }

        /**
         * Check for old versions of applications using parallel deployment that are
         * now unused (have no active sessions) and undeploy any that are found.
         */
        public synchronized void checkUndeploy() {
            if (deployed.size() < 2) {
                return;
            }

            // Need ordered set of names
            SortedSet<String> sortedAppNames = new TreeSet<>(deployed.keySet());

            Iterator<String> iter = sortedAppNames.iterator();

            ContextName previous = new ContextName(iter.next(), false);
            do {
                ContextName current = new ContextName(iter.next(), false);

                if (current.getPath().equals(previous.getPath())) {
                    // Current and previous are same path - current will always
                    // be a later version
                    Context previousContext = (Context) host.findChild(previous.getName());
                    Context currentContext = (Context) host.findChild(current.getName());
                    if (previousContext != null && currentContext != null &&
                        currentContext.getState().isAvailable() &&
                        tryAddServiced(previous.getName())) {
                        try {
                            Manager manager = previousContext.getManager();
                            if (manager != null) {
                                int sessionCount;
                                if (manager instanceof DistributedManager) {
                                    sessionCount = ((DistributedManager) manager).getActiveSessionsFull();
                                } else {
                                    sessionCount = manager.getActiveSessions();
                                }
                                if (sessionCount == 0) {
                                    if (log.isInfoEnabled()) {
                                        log.info(sm.getString("hostConfig.undeployVersion", previous.getName()));
                                    }
                                    DeployedApplication app = deployed.get(previous.getName());
                                    String[] resources = app.redeployResources.keySet().toArray(new String[0]);
                                    // Version is unused - undeploy it completely
                                    // The -1 is a 'trick' to ensure all redeploy
                                    // resources are removed
                                    undeploy(app);
                                    deleteRedeployResources(app, resources, -1, true);
                                }
                            }
                        } finally {
                            removeServiced(previous.getName());
                        }
                    }
                }
                previous = current;
            } while (iter.hasNext());
        }

        /**
         * Add a new Context to be managed by us.
         * Entry point for the admin webapp, and other JMX Context controllers.
         * @param context The context instance
         */
        public void manageApp(Context context)  {

            String contextName = context.getName();

            if (deployed.containsKey(contextName)) {
                return;
            }

            DeployedApplication deployedApp =
                new DeployedApplication(contextName, false);

            // Add the associated docBase to the redeployed list if it's a WAR
            boolean isWar = false;
            if (context.getDocBase() != null) {
                File docBase = new File(context.getDocBase());
                if (!docBase.isAbsolute()) {
                    docBase = new File(host.getAppBaseFile(), context.getDocBase());
                }
                deployedApp.redeployResources.put(docBase.getAbsolutePath(),
                    Long.valueOf(docBase.lastModified()));
                if (docBase.getAbsolutePath().toLowerCase(Locale.ENGLISH).endsWith(".war")) {
                    isWar = true;
                }
            }
            host.addChild(context);
            // Add the eventual unpacked WAR and all the resources which will be
            // watched inside it
            boolean unpackWAR = unpackWARs;
            if (unpackWAR && context instanceof StandardContext) {
                unpackWAR = ((StandardContext) context).getUnpackWAR();
            }
            if (isWar && unpackWAR) {
                File docBase = new File(host.getAppBaseFile(), context.getBaseName());
                deployedApp.redeployResources.put(docBase.getAbsolutePath(),
                    Long.valueOf(docBase.lastModified()));
                addWatchedResources(deployedApp, docBase.getAbsolutePath(), context);
            } else {
                addWatchedResources(deployedApp, null, context);
            }
            deployed.put(contextName, deployedApp);
        }

        /**
         * Remove a webapp from our control.
         * Entry point for the admin webapp, and other JMX Context controllers.
         * <p>
         * Note: It is expected that the caller has successfully added the app
         *       to servicedSet before calling this method.
         *
         * @param contextName The context name
         */
        public void unmanageApp(String contextName) {
            deployed.remove(contextName);
            host.removeChild(host.findChild(contextName));
        }

        // ----------------------------------------------------- Instance Variables


        /**
         * This class represents the state of a deployed application, as well as
         * the monitored resources.
         */
        protected static class DeployedApplication {
            public DeployedApplication(String name, boolean hasDescriptor) {
                this.name = name;
                this.hasDescriptor = hasDescriptor;
            }

            /**
             * Application context path. The assertion is that
             * (host.getChild(name) != null).
             */
            public final String name;

            /**
             * Does this application have a context.xml descriptor file on the
             * host's configBase?
             */
            public final boolean hasDescriptor;

            /**
             * Any modification of the specified (static) resources will cause a
             * redeployment of the application. If any of the specified resources is
             * removed, the application will be undeployed. Typically, this will
             * contain resources like the context.xml file, a compressed WAR path.
             * The value is the last modification time.
             */
            // 应用重新部署的资源 key 为资源路径 value为更新资源的最后修改时间
            public final LinkedHashMap<String, Long> redeployResources =
                new LinkedHashMap<>();

            /**
             * Any modification of the specified (static) resources will cause a
             * reload of the application. This will typically contain resources
             * such as the web.xml of a webapp, but can be configured to contain
             * additional descriptors.
             * The value is the last modification time.
             */
            // 应用重新加载的资源 key 为资源路径 value为更新资源的最后修改时间
            public final HashMap<String, Long> reloadResources = new HashMap<>();

            /**
             * Instant where the application was last put in service.
             */
            public long timestamp = System.currentTimeMillis();

            /**
             * In some circumstances, such as when unpackWARs is true, a directory
             * may be added to the appBase that is ignored. This flag indicates that
             * the user has been warned so that the warning is not logged on every
             * run of the auto deployer.
             */
            public boolean loggedDirWarning = false;
        }

        private static class DeployDescriptor implements Runnable {

            private HostConfig config;
            private ContextName cn;
            private File descriptor;

            public DeployDescriptor(HostConfig config, ContextName cn,
                File descriptor) {
                this.config = config;
                this.cn = cn;
                this.descriptor= descriptor;
            }

            @Override
            public void run() {
                try {
                    config.deployDescriptor(cn, descriptor);
                } finally {
                    config.removeServiced(cn.getName());
                }
            }
        }

        private static class DeployWar implements Runnable {

            private HostConfig config;
            private ContextName cn;
            private File war;

            public DeployWar(HostConfig config, ContextName cn, File war) {
                this.config = config;
                this.cn = cn;
                this.war = war;
            }

            @Override
            public void run() {
                try {
                    config.deployWAR(cn, war);
                } finally {
                    config.removeServiced(cn.getName());
                }
            }
        }

        private static class DeployDirectory implements Runnable {

            private HostConfig config;
            private ContextName cn;
            private File dir;

            public DeployDirectory(HostConfig config, ContextName cn, File dir) {
                this.config = config;
                this.cn = cn;
                this.dir = dir;
            }

            @Override
            public void run() {
                try {
                    config.deployDirectory(cn, dir);
                } finally {
                    config.removeServiced(cn.getName());
                }
            }
        }


        /*
         * The purpose of this class is to provide a way for HostConfig to get
         * a Context to delete an expanded WAR after the Context stops. This is to
         * resolve this issue described in Bug 57772. The alternative solutions
         * require either duplicating a lot of the Context.reload() code in
         * HostConfig or adding a new reload(boolean) method to Context that allows
         * the caller to optionally delete any expanded WAR.
         *
         * The LifecycleListener approach offers greater flexibility and enables the
         * behaviour to be changed / extended / removed in future without changing
         * the Context API.
         */
        private static class ExpandedDirectoryRemovalListener implements LifecycleListener {

            private final File toDelete;
            private final String newDocBase;

            /**
             * Create a listener that will ensure that any expanded WAR is removed
             * and the docBase set to the specified WAR.
             *
             * @param toDelete The file (a directory representing an expanded WAR)
             *                 to be deleted
             * @param newDocBase The new docBase for the Context
             */
            public ExpandedDirectoryRemovalListener(File toDelete, String newDocBase) {
                this.toDelete = toDelete;
                this.newDocBase = newDocBase;
            }

            @Override
            public void lifecycleEvent(LifecycleEvent event) {
                if (Lifecycle.AFTER_STOP_EVENT.equals(event.getType())) {
                    // The context has stopped.
                    Context context = (Context) event.getLifecycle();

                    // Remove the old expanded WAR.
                    ExpandWar.delete(toDelete);

                    // Reset the docBase to trigger re-expansion of the WAR.
                    context.setDocBase(newDocBase);

                    // Remove this listener from the Context else it will run every
                    // time the Context is stopped.
                    context.removeLifecycleListener(this);
                }
            }
        }
    }

