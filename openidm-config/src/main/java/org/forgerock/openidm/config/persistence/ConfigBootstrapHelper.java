/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions copyright [year] [name of copyright owner]".
 *
 * Copyright 2011-2017 ForgeRock AS.
 */
package org.forgerock.openidm.config.persistence;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.forgerock.json.JsonValue;
import org.forgerock.openidm.config.enhanced.JSONEnhancedConfig;
import org.forgerock.openidm.config.installer.JSONConfigInstaller;
import org.forgerock.openidm.core.IdentityServer;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.crypto.factory.CryptoServiceFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper class for bootstrapping the configuration, and in turn the system
 * 
 * The boostrapping mechanism works in the following order:
 * 1. Repository bundle activators register a bootstrap repository that knows how to access configuration.
 *    The basic info to bootstrap comes from system properties or configuration files
 * 2. A repo persistence plug-in for the configuration admin is registered for configuration to get loaded/stored in the repository
 * 3. When the OSGi configuration administration service comes up, proceed with handling configuration in files (if enabled)
 *    via the felix file install mechanism
 */
public class ConfigBootstrapHelper {

    // Properties to set bootstrapping behavior
    public static final String OPENIDM_REPO_TYPE = "openidm.repo.type";
    
    // Properties to set configuration file handling behavior
    public static final String OPENIDM_FILEINSTALL_BUNDLES_NEW_START = "openidm.fileinstall.bundles.new.start";
    public static final String OPENIDM_FILEINSTALL_FILTER = "openidm.fileinstall.filter";
    public static final String OPENIDM_FILEINSTALL_DIR = "openidm.fileinstall.dir";
    public static final String OPENIDM_FILEINSTALL_POLL = "openidm.fileinstall.poll";
    public static final String OPENIDM_FILEINSTALL_ENABLED = "openidm.fileinstall.enabled";

    public static final String FELIX_FILEINSTALL_PID = "org.apache.felix.fileinstall";
    
    public static final String CONFIG_ALIAS = "config__factory-pid";
    public static final String SERVICE_PID = "service__pid";
    public static final String SERVICE_FACTORY_PID = "service__factoryPid";

    // Filename prefix for repository configuration
    public static final String REPO_FILE_PREFIX = "repo.";
    public static final String DATASOURCE_FILE_PREFIX = "datasource.";
    public static final String JSON_CONFIG_FILE_EXT = ".json";
    
    final static Logger logger = LoggerFactory.getLogger(ConfigBootstrapHelper.class);

    static boolean warnMissingConfig = true;

    /**
     * Get the configured bootstrap information for the particular config/file prefix.
     *
     * Currently only one bootstrap repository is selected.
     *
     * Configuration keys returned are lower case
     *
     * @param repoType the type of the bootstrap repository, equivalent to the last part of its PID
     * @param bundleContext the BundleContext
     * @return The relevant bootstrap configuration if this repository should be bootstraped, null if not
     */
    private static JsonValue getBootConfig(String filePrefix, String repoType, BundleContext bundleContext) {
        JsonValue result = new JsonValue(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
        result.put(OPENIDM_REPO_TYPE, repoType);

        String confDir = getConfigFileInstallDir();
        File unqualified = new File(confDir, filePrefix + repoType.toLowerCase() + JSON_CONFIG_FILE_EXT);
        File qualified = new File(confDir, ServerConstants.SERVICE_RDN_PREFIX
                + filePrefix + repoType.toLowerCase() + JSON_CONFIG_FILE_EXT);

        final File loadedFile;
        try {
            final Dictionary<String, Object> rawConfig;
            if (unqualified.exists()) {
                rawConfig = JSONConfigInstaller.loadConfigFile(unqualified);
                loadedFile = unqualified;
            } else if (qualified.exists()) {
                rawConfig = JSONConfigInstaller.loadConfigFile(qualified);
                loadedFile = qualified;
            } else {
                logger.debug("No configuration to bootstrap {}", repoType);

                // Check at least one configuration exists
                String[] configFiles = getRepoConfigFiles(confDir);
                if (warnMissingConfig && (configFiles == null || configFiles.length == 0)) {
                    logger.error("No configuration to bootstrap {} found.", repoType);
                    warnMissingConfig = false;
                }

                return null;
            }
            final JSONEnhancedConfig jsonEnhancedConfig = new JSONEnhancedConfig();
            jsonEnhancedConfig.bindCryptoService(CryptoServiceFactory.getInstance());
            JsonValue jsonCfg = jsonEnhancedConfig.getConfiguration(rawConfig, bundleContext, repoType);
            Map<String, Object> cfg = jsonCfg.asMap();
            for (Entry<String, Object> entry : cfg.entrySet()) {
                result.put(entry.getKey(), entry.getValue());
            }
        } catch (Exception e) {
            logger.warn("Failed to load configuration file to bootstrap {}", repoType, e);
            throw new RuntimeException("Failed to load configuration file to bootstrap " + repoType, e);
        }
        logger.info("Bootstrapping with settings from configuration file {}", loadedFile);

        return result;
    }

    /**
     * Get the configured bootstrap information for a repository.
     *
     * Currently only one bootstrap repository is selected.
     * 
     * Configuration keys returned are lower case, whether they originate from system properties or
     * configuration files.
     * 
     * @param repoType the type of the bootstrap repository, equivalent to the last part of its PID
     * @param bundleContext the BundleContext
     * @return The relevant bootstrap configuration if this repository should be bootstraped, null if not
     */
    public static JsonValue getRepoBootConfig(String repoType, BundleContext bundleContext) {
        return getBootConfig(REPO_FILE_PREFIX, repoType, bundleContext);
    }

    /**
     * Get the configured bootstrap information for the datasource.
     *
     * Currently only one bootstrap datasource is selected.
     *
     * Configuration keys returned are lower case, whether they originate from system properties or
     * configuration files.
     *
     * @param repoType the type of the bootstrap repository, equivalent to the last part of its PID
     * @param bundleContext the BundleContext
     * @return The relevant bootstrap configuration if this repository should be bootstraped, null if not
     */
    public static JsonValue getDataSourceBootConfig(String repoType, BundleContext bundleContext) {
        return getBootConfig(DATASOURCE_FILE_PREFIX, repoType, bundleContext);
    }

    // A list of repository configuration files 
    static String[] getRepoConfigFiles(final String confDir) {
        FilenameFilter repoConfFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(REPO_FILE_PREFIX);
            }
        };

        return IdentityServer.getFileForProjectPath(confDir).list(repoConfFilter);
    }
    
    /**
     * Get the directory for configuration file view
     * 
     * @return config dir
     */
    public static String getConfigFileInstallDir() {
        // Default the configuration directory if not declared
        String dir = System.getProperty(OPENIDM_FILEINSTALL_DIR, "conf");
        dir =  IdentityServer.getFileForProjectPath(dir).getAbsolutePath();
        logger.debug("Configuration file directory {}", dir);
        return dir;
    }
    
    /**
     * Configure to process all JSON configuration files (if enabled)
     * 
     * @param configAdmin the OSGi configuration admin service
     * @throws java.io.IOException
     */
    public static void installAllConfig(ConfigurationAdmin configAdmin) throws IOException {
        String enabled = System.getProperty(OPENIDM_FILEINSTALL_ENABLED, "true");
        String poll = System.getProperty(OPENIDM_FILEINSTALL_POLL, "2000");
        String dir = getConfigFileInstallDir();
        String filter = System.getProperty(OPENIDM_FILEINSTALL_FILTER, ".*\\.cfg|.*\\.json");
        String start = System.getProperty(OPENIDM_FILEINSTALL_BUNDLES_NEW_START, "false");

        Configuration config = configAdmin.createFactoryConfiguration(FELIX_FILEINSTALL_PID, null);
        
        Dictionary<String, Object> props = config.getProperties();
        if (props == null) {
            props = new Hashtable<>();
        }
        
        if ("true".equals(enabled)) {
            // Apply the latest configuration changes from file
            props.put("felix.fileinstall.poll", poll);
            props.put("felix.fileinstall.noInitialDelay", "true");
            props.put("felix.fileinstall.dir", dir);
            props.put("felix.fileinstall.filter", filter);
            props.put("felix.fileinstall.bundles.new.start", start);
            props.put("config.factory-pid","openidm");
            config.update(props);
            logger.info("Configuration from file enabled");
        } else {
            logger.info("Configuration from file disabled");
        }
    }
    
    /**
     * Prefixes unqualified PIDs with the default RDN qualifier
     * I.e. file names can be unqualified and will be prefixed
     * with the default. 
     * Configuring services with PIDs that are not qualified 
     * by org. or com. is currently not supported.
     * @param fileNamePid
     * @return
     */
    public static String qualifyPid(String fileNamePid) {
        String qualifiedPid = fileNamePid;
        // Prefix unqualified pid names with the default.
        if (fileNamePid != null && !(fileNamePid.startsWith("org.") || fileNamePid.startsWith("com."))) {
            qualifiedPid = ServerConstants.SERVICE_RDN_PREFIX + fileNamePid;
        }
        return qualifiedPid;
    }
    
    /**
     * Removes the default RDN prefix if this is a qualified name 
     * int the default namespace. IF not, it STAYS qualified.
     * 
     * Configuring services with PIDs that are not qualified 
     * by org. or com. is currently not supported.
     * @param qualifiedPid
     * @return
     */
    public static String unqualifyPid(String qualifiedPid) {
        if (qualifiedPid != null && qualifiedPid.startsWith(ServerConstants.SERVICE_RDN_PREFIX)) {
            return qualifiedPid.substring(ServerConstants.SERVICE_RDN_PREFIX.length());
        } else {
            return qualifiedPid;
        }
    }
    


    /**
     * Construct the configurations's resource ID.
     * 
     * @param alias the config factory pid alias
     * @param pid the service pid
     * @param factoryPid the service factory pid
     * @return the configuration's resource ID
     */
    public static String getId(String alias, String pid, String factoryPid) {
        String unqualifiedPid = ConfigBootstrapHelper.unqualifyPid(pid);
        String unqualifiedFactoryPid = ConfigBootstrapHelper.unqualifyPid(factoryPid);
        // If there is an alias for factory config is available, make a nicer ID then the internal PID
        return unqualifiedFactoryPid != null && alias != null
                ? unqualifiedFactoryPid + "/" + alias
                : unqualifiedPid;
    }

    /**
     * Extracts the alias, pid, and factorPin and then calls {@link #getId(String, String, String)}.
     *
     * @param conf the configuration.
     * @return the configuration's resource ID
     */
    public static String getId(Configuration conf) {
        String alias = null;
        Dictionary properties = conf.getProperties();
        if (properties != null) {
            alias = (String) properties.get(JSONConfigInstaller.SERVICE_FACTORY_PID_ALIAS);
        }
        return getId(alias, conf.getPid(), conf.getFactoryPid());
    }

}
