/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.environment.servlet;

import static org.jboss.weld.config.ConfigurationKey.BEAN_IDENTIFIER_INDEX_OPTIMIZATION;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.jsp.JspApplicationContext;
import javax.servlet.jsp.JspFactory;

import org.jboss.weld.bean.builtin.BeanManagerProxy;
import org.jboss.weld.bootstrap.WeldBootstrap;
import org.jboss.weld.bootstrap.api.CDI11Bootstrap;
import org.jboss.weld.bootstrap.api.Environments;
import org.jboss.weld.bootstrap.api.TypeDiscoveryConfiguration;
import org.jboss.weld.bootstrap.spi.BeanDeploymentArchive;
import org.jboss.weld.bootstrap.spi.CDI11Deployment;
import org.jboss.weld.bootstrap.spi.EEModuleDescriptor;
import org.jboss.weld.bootstrap.spi.EEModuleDescriptor.ModuleType;
import org.jboss.weld.bootstrap.spi.Metadata;
import org.jboss.weld.bootstrap.spi.helpers.EEModuleDescriptorImpl;
import org.jboss.weld.configuration.spi.ExternalConfiguration;
import org.jboss.weld.configuration.spi.helpers.ExternalConfigurationBuilder;
import org.jboss.weld.el.WeldELContextListener;
import org.jboss.weld.environment.Container;
import org.jboss.weld.environment.ContainerContext;
import org.jboss.weld.environment.ContainerInstance;
import org.jboss.weld.environment.ContainerInstanceFactory;
import org.jboss.weld.environment.deployment.WeldBeanDeploymentArchive;
import org.jboss.weld.environment.deployment.WeldDeployment;
import org.jboss.weld.environment.deployment.WeldResourceLoader;
import org.jboss.weld.environment.deployment.discovery.DiscoveryStrategy;
import org.jboss.weld.environment.deployment.discovery.DiscoveryStrategyFactory;
import org.jboss.weld.environment.gwtdev.GwtDevHostedModeContainer;
import org.jboss.weld.environment.jetty.JettyContainer;
import org.jboss.weld.environment.logging.CommonLogger;
import org.jboss.weld.environment.servlet.deployment.ServletContextBeanArchiveHandler;
import org.jboss.weld.environment.servlet.deployment.WebAppBeanArchiveScanner;
import org.jboss.weld.environment.servlet.logging.WeldServletLogger;
import org.jboss.weld.environment.servlet.services.ServletResourceInjectionServices;
import org.jboss.weld.environment.servlet.util.ServiceLoader;
import org.jboss.weld.environment.tomcat.TomcatContainer;
import org.jboss.weld.environment.undertow.UndertowContainer;
import org.jboss.weld.environment.util.Reflections;
import org.jboss.weld.injection.spi.ResourceInjectionServices;
import org.jboss.weld.manager.api.WeldManager;
import org.jboss.weld.metadata.MetadataImpl;
import org.jboss.weld.resources.ManagerObjectFactory;
import org.jboss.weld.resources.WeldClassLoaderResourceLoader;
import org.jboss.weld.resources.spi.ClassFileServices;
import org.jboss.weld.resources.spi.ResourceLoader;
import org.jboss.weld.servlet.WeldInitialListener;
import org.jboss.weld.servlet.api.ServletListener;
import org.jboss.weld.util.collections.ImmutableSet;

/**
 *
 * @author Martin Kouba
 * @see Listener
 * @see EnhancedListener
 */
public class WeldServletLifecycle {

    public static final String BEAN_MANAGER_ATTRIBUTE_NAME = WeldServletLifecycle.class.getPackage().getName() + "." + BeanManager.class.getName();
    static final String INSTANCE_ATTRIBUTE_NAME = WeldServletLifecycle.class.getPackage().getName() + ".lifecycleInstance";

    private static final String EXPRESSION_FACTORY_NAME = "org.jboss.weld.el.ExpressionFactory";

    private static final String PROBE_EXTENSION_CLASS_NAME = "org.jboss.weld.probe.ProbeExtension";

    private static final String PROBE_FILTER_CLASS_NAME = "org.jboss.weld.probe.ProbeFilter";

    private static final String CONTEXT_PARAM_ARCHIVE_ISOLATION = WeldServletLifecycle.class.getPackage().getName() + ".archive.isolation";

    // This context param is used to activate the development mode
    private static final String CONTEXT_PARAM_DEV_MODE = "org.jboss.weld.development";

    private static final String JSP_FACTORY_CLASS_NAME = "javax.servlet.jsp.JspFactory";

    private Runnable shutdownAction;

    private final transient ServletListener weldListener;

    private final transient ResourceLoader resourceLoader;

    private Container container;

    // WELD-1665 Bootstrap might be already performed
    private boolean isBootstrapNeeded = true;

    private boolean isDevModeEnabled;

    WeldServletLifecycle() {
        resourceLoader = new WeldResourceLoader();
        weldListener = new WeldInitialListener();
    }

    /**
     *
     * @param context
     * @return <code>true</code> if initialized properly, <code>false</code> otherwise
     */
    boolean initialize(ServletContext context) {

        isDevModeEnabled = Boolean.valueOf(context.getInitParameter(CONTEXT_PARAM_DEV_MODE));

        WeldManager manager = (WeldManager) context.getAttribute(BEAN_MANAGER_ATTRIBUTE_NAME);
        if (manager != null) {
            isBootstrapNeeded = false;
        } else {
            Object container = context.getAttribute(Listener.CONTAINER_ATTRIBUTE_NAME);
            if (container instanceof ContainerInstanceFactory) {
                ContainerInstanceFactory factory = (ContainerInstanceFactory) container;
                // start the container
                ContainerInstance containerInstance = factory.initialize();
                container = containerInstance;
                // we are in charge of shutdown also
                this.shutdownAction = () -> containerInstance.shutdown();
            }
            if (container instanceof ContainerInstance) {
                // the container instance was either passed to us directly or was created in the block above
                manager = BeanManagerProxy.unwrap(ContainerInstance.class.cast(container).getBeanManager());
                isBootstrapNeeded = false;
            }
        }

        final CDI11Bootstrap bootstrap = new WeldBootstrap();
        if (isBootstrapNeeded) {
            final CDI11Deployment deployment = createDeployment(context, bootstrap);

            deployment.getServices().add(ExternalConfiguration.class,
                    new ExternalConfigurationBuilder().add(BEAN_IDENTIFIER_INDEX_OPTIMIZATION.get(), Boolean.FALSE.toString()).build());

            if (deployment.getBeanDeploymentArchives().isEmpty()) {
                // Skip initialization - there is no bean archive in the deployment
                CommonLogger.LOG.initSkippedNoBeanArchiveFound();
                return false;
            }

            ResourceInjectionServices resourceInjectionServices = new ServletResourceInjectionServices() {
            };
            try {
                for (BeanDeploymentArchive archive : deployment.getBeanDeploymentArchives()) {
                    archive.getServices().add(ResourceInjectionServices.class, resourceInjectionServices);
                }
            } catch (NoClassDefFoundError e) {
                // Support GAE
                WeldServletLogger.LOG.resourceInjectionNotAvailable();
            }

            String id = context.getInitParameter(org.jboss.weld.Container.CONTEXT_ID_KEY);
            if (id != null) {
                bootstrap.startContainer(id, Environments.SERVLET, deployment);
            } else {
                bootstrap.startContainer(Environments.SERVLET, deployment);
            }
            bootstrap.startInitialization();

            /*
             * Determine the BeanManager used for example for EL resolution - this should work fine as all bean archives share the same classloader. The only
             * difference this can make is per-BDA (CDI 1.0 style) enablement of alternatives, interceptors and decorators. Nothing we can do about that.
             *
             * First try to find the bean archive for WEB-INF/classes. If not found, take the first one available.
             */
            for (BeanDeploymentArchive bda : deployment.getBeanDeploymentArchives()) {
                if (bda.getId().contains(ManagerObjectFactory.WEB_INF_CLASSES_FILE_PATH) || bda.getId().contains(ManagerObjectFactory.WEB_INF_CLASSES)) {
                    manager = bootstrap.getManager(bda);
                    break;
                }
            }
            if (manager == null) {
                manager = bootstrap.getManager(deployment.getBeanDeploymentArchives().iterator().next());
            }

            // Push the manager into the servlet context so we can access in JSF
            context.setAttribute(BEAN_MANAGER_ATTRIBUTE_NAME, manager);
        }

        ContainerContext containerContext = new ContainerContext(context, manager);
        StringBuilder dump = new StringBuilder();
        Container container = findContainer(containerContext, dump);
        if (container == null) {
            WeldServletLogger.LOG.noSupportedServletContainerDetected();
            WeldServletLogger.LOG.debugv("Exception dump from Container lookup: {0}", dump);
        } else {
            container.initialize(containerContext);
            this.container = container;
        }

        if (Reflections.isClassLoadable(WeldClassLoaderResourceLoader.INSTANCE, JSP_FACTORY_CLASS_NAME) && JspFactory.getDefaultFactory() != null) {
            JspApplicationContext jspApplicationContext = JspFactory.getDefaultFactory().getJspApplicationContext(context);

            // Register the ELResolver with JSP
            jspApplicationContext.addELResolver(manager.getELResolver());

            // Register ELContextListener with JSP
            try {
                jspApplicationContext.addELContextListener(new WeldELContextListener());
            } catch (Exception e) {
                throw WeldServletLogger.LOG.errorLoadingWeldELContextListener(e);
            }

            // Push the wrapped expression factory into the servlet context so that Tomcat or Jetty can hook it in using a container code
            context.setAttribute(EXPRESSION_FACTORY_NAME, manager.wrapExpressionFactory(jspApplicationContext.getExpressionFactory()));
        }

        if (isBootstrapNeeded) {

            bootstrap.deployBeans().validateBeans().endInitialization();

            if (isDevModeEnabled) {
                FilterRegistration.Dynamic filterDynamic = context.addFilter("Weld Probe Filter", PROBE_FILTER_CLASS_NAME);
                filterDynamic.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST, DispatcherType.FORWARD, DispatcherType.INCLUDE), true, "/*");
            }
            this.shutdownAction = () -> bootstrap.shutdown();
        }
        return true;
    }

    void destroy(ServletContext context) {

        if (shutdownAction != null) {
            // Shutdown only if bootstrap not skipped
            shutdownAction.run();
        }

        if (container != null) {
            container.destroy(new ContainerContext(context, null));
        }
    }

    /**
     *
     * @return the original Weld listener all notifications should be delegated to
     */
    ServletListener getWeldListener() {
        return weldListener;
    }

    /**
     * Create servlet deployment.
     *
     * Can be overridden with custom servlet deployment. e.g. exact resources listing in restricted env like GAE
     *
     * @param context the servlet context
     * @param bootstrap the bootstrap
     * @return new servlet deployment
     */
    protected CDI11Deployment createDeployment(ServletContext context, CDI11Bootstrap bootstrap) {

        ImmutableSet.Builder<Metadata<Extension>> extensionsBuilder = ImmutableSet.builder();
        extensionsBuilder.addAll(bootstrap.loadExtensions(WeldResourceLoader.getClassLoader()));
        if (isDevModeEnabled) {
            try {
                Class<? extends Extension> probeExtensionClass = Reflections.loadClass(resourceLoader, PROBE_EXTENSION_CLASS_NAME);
                if (probeExtensionClass == null) {
                    throw WeldServletLogger.LOG.probeComponentNotFoundOnClasspath(PROBE_EXTENSION_CLASS_NAME);
                }
                extensionsBuilder.add(new MetadataImpl<Extension>(SecurityActions.newInstance(probeExtensionClass), "N/A"));
            } catch (Exception e) {
                throw WeldServletLogger.LOG.unableToInitializeProbeComponent(e.getMessage(), e);
            }
        }

        final Iterable<Metadata<Extension>> extensions = extensionsBuilder.build();
        final TypeDiscoveryConfiguration typeDiscoveryConfiguration = bootstrap.startExtensions(extensions);
        final EEModuleDescriptor eeModule = new EEModuleDescriptorImpl(context.getContextPath(), ModuleType.WEB);

        final DiscoveryStrategy strategy = DiscoveryStrategyFactory.create(resourceLoader, bootstrap,
                typeDiscoveryConfiguration.getKnownBeanDefiningAnnotations());
        strategy.registerHandler(new ServletContextBeanArchiveHandler(context));

        strategy.setScanner(new WebAppBeanArchiveScanner(resourceLoader, bootstrap, context));
        Set<WeldBeanDeploymentArchive> beanDeploymentArchives = strategy.performDiscovery();

        String isolation = context.getInitParameter(CONTEXT_PARAM_ARCHIVE_ISOLATION);

        if (isolation != null && Boolean.valueOf(isolation).equals(Boolean.FALSE)) {
            CommonLogger.LOG.archiveIsolationDisabled();
            beanDeploymentArchives = Collections.singleton(WeldBeanDeploymentArchive.merge(bootstrap, beanDeploymentArchives));
        } else {
            CommonLogger.LOG.archiveIsolationEnabled();
        }

        for (BeanDeploymentArchive archive : beanDeploymentArchives) {
            archive.getServices().add(EEModuleDescriptor.class, eeModule);
        }

        CDI11Deployment deployment = new WeldDeployment(resourceLoader, bootstrap, beanDeploymentArchives, extensions) {
            @Override
            protected WeldBeanDeploymentArchive createAdditionalBeanDeploymentArchive(Class<?> beanClass) {
                WeldBeanDeploymentArchive archive = super.createAdditionalBeanDeploymentArchive(beanClass);
                archive.getServices().add(EEModuleDescriptor.class, eeModule);
                return archive;
            }
        };

        if (strategy.getClassFileServices() != null) {
            deployment.getServices().add(ClassFileServices.class, strategy.getClassFileServices());
        }
        return deployment;
    }

    /**
     * Find container env.
     *
     * @param ctx the container context
     * @param dump the exception dump
     * @return valid container or null
     */
    protected Container findContainer(ContainerContext ctx, StringBuilder dump) {
        Container container = null;
        // 1. Custom container class
        String containerClassName = ctx.getServletContext().getInitParameter(Container.CONTEXT_PARAM_CONTAINER_CLASS);
        if (containerClassName != null) {
            try {
                Class<Container> containerClass = Reflections.classForName(resourceLoader, containerClassName);
                container = SecurityActions.newInstance(containerClass);
                WeldServletLogger.LOG.containerDetectionSkipped(containerClassName);
            } catch (Exception e) {
                WeldServletLogger.LOG.unableToInstantiateCustomContainerClass(containerClassName);
                WeldServletLogger.LOG.catchingDebug(e);
            }
        }
        if (container == null) {
            // 2. Service providers
            Iterable<Container> extContainers = ServiceLoader.load(Container.class, getClass().getClassLoader());
            container = checkContainers(ctx, dump, extContainers);
            if (container == null) {
                // 3. Built-in containers in predefined order
                container = checkContainers(ctx, dump, Arrays.asList(TomcatContainer.INSTANCE, JettyContainer.INSTANCE, UndertowContainer.INSTANCE, GwtDevHostedModeContainer.INSTANCE));
            }
        }
        return container;
    }

    protected Container checkContainers(ContainerContext containerContext, StringBuilder dump, Iterable<Container> containers) {
        for (Container container : containers) {
            try {
                if (container.touch(resourceLoader, containerContext)) {
                    return container;
                }
            } catch (Throwable t) {
                dump.append(container).append("->").append(t.getMessage()).append("\n");
            }
        }
        return null;
    }

}
