/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.feature.apiregions.impl;

import org.apache.felix.webconsole.WebConsoleConstants;
import org.apache.sling.feature.apiregions.impl.console.RegionWebconsolePlugin;
import org.osgi.framework.*;
import org.osgi.framework.hooks.resolver.ResolverHookFactory;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.resource.Requirement;
import org.osgi.resource.Resource;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

import javax.servlet.Servlet;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Activator implements BundleActivator, FrameworkListener {
    static final String CONFIG_ADMIN_PKG_NAME = "org.osgi.service.cm";
    static final String MANAGED_SERVICE_CLASS_NAME = CONFIG_ADMIN_PKG_NAME + ".ManagedService";
    static final String CONFIG_ADMIN_CLASS_NAME = CONFIG_ADMIN_PKG_NAME + ".ConfigurationAdmin";
    static final String CFG_LISTENER_CLASS_NAME = CONFIG_ADMIN_PKG_NAME + ".SynchronousConfigurationListener";
    static final String CFG_EVENT_CLASS_NAME = CONFIG_ADMIN_PKG_NAME + ".ConfigurationEvent";
    static final String CFG_CLASS_NAME = CONFIG_ADMIN_PKG_NAME + ".Configuration";
    static final String FACTORY_PID = "org.apache.sling.feature.apiregions.factory";

    static final String REGIONS_PROPERTY_NAME = "org.apache.sling.feature.apiregions.regions";

    static final Logger LOG = Logger.getLogger(ResolverHookImpl.class.getName());

    BundleContext bundleContext;
    ServiceRegistration<ResolverHookFactory> hookRegistration;

    RegionConfiguration configuration;

    ServiceTracker<Object, Object> configAdminTracker;

    ServiceRegistration<Servlet> regionWebconsolePluginRegistration;
    ServiceTracker<Object, Object> webconsoleTracker;

    @Override
    public synchronized void start(BundleContext context) throws Exception {
        bundleContext = context;

        createConfiguration();

        registerHook();

        registerWebconsole();

        this.configAdminTracker = new ServiceTracker<>(context, CONFIG_ADMIN_CLASS_NAME, new ServiceTrackerCustomizer<Object, Object>() {

            @Override
            public Object addingService(final ServiceReference<Object> reference) {
                final Object cfgAdmin = bundleContext.getService(reference);
                if (cfgAdmin != null) {
                    return registerConfigurationListener(cfgAdmin);
                }
                return null;
            }

            @Override
            public void modifiedService(final ServiceReference<Object> reference, final Object reg) {
                // ignore
            }

            @Override
            public void removedService(final ServiceReference<Object> reference, final Object reg) {
                if (reg != null) {
                    ((ServiceRegistration<?>) reg).unregister();
                }
            }
        });
        this.configAdminTracker.open();

        context.addFrameworkListener(this);
    }

    private void registerWebconsole() {
        if (webconsoleTracker == null) {
            webconsoleTracker = new ServiceTracker<Object, Object>(
                    bundleContext, "org.osgi.service.cm.ManagedService", null) {

                @Override
                public Object addingService(ServiceReference<Object> reference) {
                    Object result = super.addingService(reference);
                    if ("org.apache.felix.webconsole.internal.servlet.OsgiManager".equals(reference.getProperty("service.pid"))) {
                        if (regionWebconsolePluginRegistration == null) {
                            RegionWebconsolePlugin regionWebconsolePlugin = new RegionWebconsolePlugin();
                            Dictionary<String, Object> properties = new Hashtable<>();
                            properties.put(Constants.SERVICE_DESCRIPTION, "Apache Sling API Region Web Console Plugin");
                            properties.put(WebConsoleConstants.PLUGIN_LABEL, RegionWebconsolePlugin.LABEL);
                            properties.put(WebConsoleConstants.PLUGIN_TITLE, RegionWebconsolePlugin.TITLE);
                            properties.put(WebConsoleConstants.PLUGIN_CATEGORY, "Sling");
                            regionWebconsolePluginRegistration = bundleContext.registerService(Servlet.class, regionWebconsolePlugin, properties);
                        }
                    }
                    return result;
                }


                /** This method is invoked when a service is removed. Since we model
                 * a strong relationship between our component and the log service,
                 * our component must be stopped when there's no log service left.
                 * Note that the service tracker remains open (active). When a log
                 * service becomes available again, our component will be restarted. */
                @Override
                public void removedService(ServiceReference<Object> reference,
                                           Object service) {
                    super.removedService(reference, service);
                    if ("org.apache.felix.webconsole.internal.servlet.OsgiManager".equals(reference.getProperty("service.pid"))) {
                        if (regionWebconsolePluginRegistration != null) {
                            regionWebconsolePluginRegistration.unregister();
                            regionWebconsolePluginRegistration = null;
                        }
                    }
                }
            };
        }
        webconsoleTracker.open();
    }

    @Override
    public synchronized void stop(BundleContext context) throws Exception {
        // All services automatically get unregistered by the framework.

        if (configuration != null) {
            configuration.storeLocationToConfigMap(context);
        }
        if (this.configAdminTracker != null) {
            this.configAdminTracker.close();
        }

        if (this.webconsoleTracker != null) {
            this.webconsoleTracker.close();
        }
    }

    private void createConfiguration() {
        try {
            this.configuration = new RegionConfiguration(bundleContext);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Problem activating API Regions runtime enforcement component", e);
        }
    }

    synchronized void registerHook() {
        if (hookRegistration != null)
            return; // There is already a hook, no need to re-register

        if (bundleContext.getProperty(REGIONS_PROPERTY_NAME) == null) {
            LOG.log(Level.WARNING, "API Regions not enabled. To enable set framework property: " + REGIONS_PROPERTY_NAME);
            return; // Component not enabled
        }

        RegionEnforcer enforcer = new RegionEnforcer(this.configuration);
        hookRegistration = bundleContext.registerService(ResolverHookFactory.class, enforcer, this.configuration.getRegistrationProperties());
    }

    synchronized void unregisterHook() {
        if (hookRegistration != null) {
            hookRegistration.unregister();
            hookRegistration = null;
        }
    }

    @Override
    public void frameworkEvent(FrameworkEvent event) {
        if (event.getType() == FrameworkEvent.STARTED) {
            bundleContext.removeFrameworkListener(this);

            FrameworkWiring fw = bundleContext.getBundle().adapt(FrameworkWiring.class);
            if (fw == null) {
                LOG.log(Level.WARNING, "The API Regions runtime fragment is not attached to the system bundle.");
                return;
            }

            Requirement cmReq = createCMPackageRequirement();

            // Reflectively register a Configuration Admin ManagedService, if the Config Admin API is available.
            // Because this fragment is a framework extension, we need to use the wiring API to find the CM API.
            Collection<BundleCapability> providers = fw.findProviders(cmReq);
            for (BundleCapability cap : providers) {
                if (registerManagedService(cap)) {
                    return;
                }
            }
            LOG.log(Level.INFO, "No Configuration Admin API available");
        }
    }

    private boolean registerManagedService(final BundleCapability cap) {
        try {
            ClassLoader loader = cap.getRevision().getWiring().getClassLoader();
            Class<?> msClass = loader.loadClass(MANAGED_SERVICE_CLASS_NAME);
            Object ms = Proxy.newProxyInstance(loader, new Class[]{msClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    Class<?> mdDecl = method.getDeclaringClass();
                    if (mdDecl.equals(Object.class)) {
                        switch (method.getName()) {
                            case "equals":
                                return proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "Proxy for " + msClass;
                            default:
                                throw new UnsupportedOperationException("Method " + method
                                        + " not supported on proxy for " + msClass);
                        }
                    }
                    if ("updated".equals(method.getName()) && args.length == 1) {
                        Object arg = args[0];
                        if (arg == null) {
                            registerHook();
                        } else if (arg instanceof Dictionary) {
                            Dictionary<?, ?> props = (Dictionary<?, ?>) args[0];
                            Object disabled = props.get("disable");
                            if ("true".equals(disabled)) {
                                unregisterHook();
                            } else {
                                registerHook();
                            }
                        }
                    }
                    return null;
                }
            });
            Dictionary<String, Object> props = new Hashtable<>();
            props.put(Constants.SERVICE_PID, getClass().getPackage().getName());
            bundleContext.registerService(MANAGED_SERVICE_CLASS_NAME, ms, props);

            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Problem attempting to register ManagedService from " + cap, e);
        }
        return false;
    }

    private ServiceRegistration<?> registerConfigurationListener(final Object cfgAdmin) {
        try {
            final Class<?> listenerClass = cfgAdmin.getClass().getClassLoader().loadClass(CFG_LISTENER_CLASS_NAME);

            // ConfigurationEvent
            final Class<?> eventClass = cfgAdmin.getClass().getClassLoader().loadClass(CFG_EVENT_CLASS_NAME);
            final Method eventGetTypeMethod = eventClass.getMethod("getType");
            final Method eventGetFactoryPidMethod = eventClass.getDeclaredMethod("getFactoryPid");
            final Method eventGetPidMethod = eventClass.getDeclaredMethod("getPid");

            // ConfigurationAdmin
            final Method caGetConfigMethod = cfgAdmin.getClass().getDeclaredMethod("getConfiguration", String.class, String.class);
            final Method caListConfigcMethod = cfgAdmin.getClass().getDeclaredMethod("listConfigurations", String.class);

            // Configuration
            final Class<?> cfgClass = cfgAdmin.getClass().getClassLoader().loadClass(CFG_CLASS_NAME);
            final Method cfgGetPropertiesMethod = cfgClass.getDeclaredMethod("getProperties");
            final Method cfgGetPidMethod = cfgClass.getDeclaredMethod("getPid");

            Object msf = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class[]{listenerClass}, new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                    Class<?> mdDecl = method.getDeclaringClass();
                    if (mdDecl.equals(Object.class)) {
                        switch (method.getName()) {
                            case "equals":
                                return proxy == args[0];
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "toString":
                                return "Proxy for " + listenerClass;
                            default:
                                throw new UnsupportedOperationException("Method " + method
                                        + " not supported on proxy for " + listenerClass);
                        }
                    }
                    if ("configurationEvent".equals(method.getName()) && args.length == 1) {
                        // configuration event
                        final Object event = args[0];

                        // check factory pid first
                        final String factoryPid = (String) eventGetFactoryPidMethod.invoke(event, (Object[]) null);
                        if (FACTORY_PID.equals(factoryPid)) {
                            final String pid = (String) eventGetPidMethod.invoke(event, (Object[]) null);
                            final Object eventType = eventGetTypeMethod.invoke(event, (Object[]) null);
                            if (eventType.equals(1)) {
                                // update
                                final Object cfg = caGetConfigMethod.invoke(cfgAdmin, new Object[]{pid, null});
                                @SuppressWarnings("unchecked") final Dictionary<String, Object> props = (Dictionary<String, Object>) cfgGetPropertiesMethod.invoke(cfg, (Object[]) null);
                                configuration.setConfig(pid, props);
                            } else if (eventType.equals(2)) {
                                // delete
                                configuration.removeConfig(pid);
                            }
                        }
                    }
                    return null;
                }
            });
            final ServiceRegistration<?> reg = bundleContext.registerService(CFG_LISTENER_CLASS_NAME, msf, null);
            // get existing configurations
            final Object result = caListConfigcMethod.invoke(cfgAdmin, "(service.factoryPid=" + FACTORY_PID + ")");
            if (result != null) {
                for (int i = 0; i < Array.getLength(result); i++) {
                    final Object cfg = Array.get(result, i);
                    final String pid = (String) cfgGetPidMethod.invoke(cfg, (Object[]) null);
                    @SuppressWarnings("unchecked") final Dictionary<String, Object> props = (Dictionary<String, Object>) cfgGetPropertiesMethod.invoke(cfg, (Object[]) null);
                    configuration.setConfig(pid, props);
                }
            }
            return reg;

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Problem attempting to register configuration lister for " + cfgAdmin, e);
        }
        return null;
    }

    static Requirement createCMPackageRequirement() {
        Requirement cmReq = new Requirement() {
            @Override
            public String getNamespace() {
                return PackageNamespace.PACKAGE_NAMESPACE;
            }

            @Override
            public Map<String, String> getDirectives() {
                return Collections.singletonMap("filter",
                        "(" + PackageNamespace.PACKAGE_NAMESPACE + "=" + CONFIG_ADMIN_PKG_NAME + ")");
            }

            @Override
            public Map<String, Object> getAttributes() {
                return Collections.emptyMap();
            }

            @Override
            public Resource getResource() {
                return null;
            }

        };
        return cmReq;
    }
}
