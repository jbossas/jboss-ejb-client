/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.ejb.client;

import static java.security.AccessController.doPrivileged;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jboss.ejb._private.Keys;
import org.jboss.ejb._private.Logs;
import org.jboss.ejb.protocol.remote.RemotingEJBClientInterceptor;
import org.wildfly.common.Assert;
import org.wildfly.common.context.ContextManager;
import org.wildfly.common.context.Contextual;
import org.wildfly.discovery.Discovery;
import org.wildfly.discovery.ServiceType;
import org.wildfly.naming.client.NamingProvider;
import org.wildfly.security.auth.client.AuthenticationContext;

/**
 * The public API for an EJB client context.  An EJB client context may be associated with (and used by) one or more threads concurrently.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class EJBClientContext extends Attachable implements Contextual<EJBClientContext> {

    /**
     * The service type to use for EJB discovery.
     */
    public static final ServiceType EJB_SERVICE_TYPE = ServiceType.of("ejb", "jboss");

    private static final ContextManager<EJBClientContext> CONTEXT_MANAGER = new ContextManager<EJBClientContext>(EJBClientContext.class, "jboss.ejb.client");

    private static final Supplier<Discovery> DISCOVERY_SUPPLIER = doPrivileged((PrivilegedAction<Supplier<Discovery>>) Discovery.getContextManager()::getPrivilegedSupplier);

    private static final Supplier<EJBClientContext> GETTER = doPrivileged((PrivilegedAction<Supplier<EJBClientContext>>) CONTEXT_MANAGER::getPrivilegedSupplier);

    private static final EJBTransportProvider[] NO_TRANSPORT_PROVIDERS = new EJBTransportProvider[0];

    private static final EJBClientInterceptor.Registration[] NO_INTERCEPTORS = new EJBClientInterceptor.Registration[0];
    private static final AtomicReferenceFieldUpdater<EJBClientContext, EJBClientInterceptor.Registration[]> registrationsUpdater = AtomicReferenceFieldUpdater.newUpdater(EJBClientContext.class, EJBClientInterceptor.Registration[].class, "registrations");
    private volatile EJBClientInterceptor.Registration[] registrations = NO_INTERCEPTORS;

    /**
     * The discovery attribute name which contains the application and module name of the located EJB.
     */
    public static final String FILTER_ATTR_EJB_MODULE = "ejb-module";
    /**
     * The discovery attribute name which contains the application and module name with the distinct name of the located EJB.
     */
    public static final String FILTER_ATTR_EJB_MODULE_DISTINCT = "ejb-module-distinct";
    /**
     * The discovery attribute name which contains a node name.
     */
    public static final String FILTER_ATTR_NODE = "node";
    /**
     * The discovery attribute name which contains a cluster name.
     */
    public static final String FILTER_ATTR_CLUSTER = "cluster";
    /**
     * The discovery attribute name for when a rule only applies to a specific source IP address range.
     */
    public static final String FILTER_ATTR_SOURCE_IP = "source-ip";

    private static final int MAX_SESSION_RETRIES = 8;

    static {
        CONTEXT_MANAGER.setGlobalDefaultSupplier(EJBClientContext::getDefault);
    }

    static EJBClientContext getDefault() {
        return ConfigurationBasedEJBClientContextSelector.get();
    }

    private final EJBTransportProvider[] transportProviders;
    private final long invocationTimeout;
    private final boolean suppressTxPropagation;
    private final EJBReceiverContext receiverContext;
    private final List<EJBClientConnection> configuredConnections;
    private final Map<String, EJBClientCluster> configuredClusters;
    private final ClusterNodeSelector clusterNodeSelector;
    private final DeploymentNodeSelector deploymentNodeSelector;
    private final ClassValue<EJBProxyInterceptorInformation<?>> proxyInfoValue = new ClassValue<EJBProxyInterceptorInformation<?>>() {
        protected EJBProxyInterceptorInformation<?> computeValue(final Class<?> type) {
            return EJBProxyInterceptorInformation.construct(type, EJBClientContext.this);
        }
    };

    static final InterceptorList defaultInterceptors = new InterceptorList(new EJBClientInterceptorInformation[] {
        EJBClientInterceptorInformation.forClass(TransactionInterceptor.class),
        EJBClientInterceptorInformation.forClass(NamingEJBClientInterceptor.class),
        EJBClientInterceptorInformation.forClass(DiscoveryEJBClientInterceptor.class),
        EJBClientInterceptorInformation.forClass(TransactionPostDiscoveryInterceptor.class),
        EJBClientInterceptorInformation.forClass(RemotingEJBClientInterceptor.class),
    });

    private final InterceptorList classPathInterceptors;
    private final InterceptorList globalInterceptors;
    private final Map<String, InterceptorList> configuredPerClassInterceptors;
    private final Map<String, Map<EJBMethodLocator, InterceptorList>> configuredPerMethodInterceptors;
    private final int maximumConnectedClusterNodes;

    EJBClientContext(Builder builder) {
        final List<EJBTransportProvider> builderTransportProviders = builder.transportProviders;
        if (builderTransportProviders == null || builderTransportProviders.isEmpty()) {
            transportProviders = NO_TRANSPORT_PROVIDERS;
        } else {
            transportProviders = builderTransportProviders.toArray(new EJBTransportProvider[builderTransportProviders.size()]);
        }
        invocationTimeout = builder.invocationTimeout;
        suppressTxPropagation = builder.suppressTxPropagation;
        receiverContext = new EJBReceiverContext(this);
        final List<EJBClientConnection> clientConnections = builder.clientConnections;
        if (clientConnections == null || clientConnections.isEmpty()) {
            configuredConnections = Collections.emptyList();
        } else if (clientConnections.size() == 1) {
            configuredConnections = Collections.singletonList(clientConnections.get(0));
        } else {
            configuredConnections = Collections.unmodifiableList(new ArrayList<>(clientConnections));
        }
        final List<EJBClientCluster> clientClusters = builder.clientClusters;
        if (clientClusters == null || clientClusters.isEmpty()) {
            configuredClusters = Collections.emptyMap();
        } else if (clientClusters.size() == 1) {
            final EJBClientCluster clientCluster = clientClusters.get(0);
            configuredClusters = Collections.singletonMap(clientCluster.getName(), clientCluster);
        } else {
            Map<String, EJBClientCluster> map = new HashMap<>();
            for (EJBClientCluster clientCluster : clientClusters) {
                map.put(clientCluster.getName(), clientCluster);
            }
            configuredClusters = Collections.unmodifiableMap(map);
        }
        clusterNodeSelector = builder.clusterNodeSelector;
        deploymentNodeSelector = builder.deploymentNodeSelector;
        maximumConnectedClusterNodes = builder.maximumConnectedClusterNodes;

        // global interceptors
        final List<EJBClientInterceptorInformation> globalInterceptors = builder.globalInterceptors;
        if (globalInterceptors != null) {
            final Iterator<EJBClientInterceptorInformation> globalInterceptorsIterator = globalInterceptors.iterator();
            if (globalInterceptorsIterator.hasNext()) {
                final EJBClientInterceptorInformation first = globalInterceptorsIterator.next();
                if (globalInterceptorsIterator.hasNext()) {
                    // two or more
                    final ArrayList<EJBClientInterceptorInformation> arrayList = new ArrayList<>();
                    arrayList.add(first);
                    do {
                        arrayList.add(globalInterceptorsIterator.next());
                    } while (globalInterceptorsIterator.hasNext());
                    if (arrayList.isEmpty()) {
                        this.globalInterceptors = InterceptorList.EMPTY;
                    } else {
                        this.globalInterceptors = new InterceptorList(arrayList.toArray(EJBClientInterceptorInformation.NO_INTERCEPTORS));
                    }
                } else {
                    // just one
                    this.globalInterceptors = first.getSingletonList();
                }
            } else {
                this.globalInterceptors = InterceptorList.EMPTY;
            }
        } else {
            this.globalInterceptors = InterceptorList.EMPTY;
        }

        // class path interceptors
        this.classPathInterceptors = doPrivileged((PrivilegedAction<InterceptorList>) EJBClientContext::getClassPathInterceptorList);

        // configured per-class interceptors
        final List<ClassInterceptor> classInterceptors = builder.classInterceptors;
        if (classInterceptors != null) {
            final Iterator<ClassInterceptor> classInterceptorsIterator = classInterceptors.iterator();
            if (classInterceptorsIterator.hasNext()) {
                final HashMap<String, ArrayList<EJBClientInterceptorInformation>> map = new HashMap<>();
                do {
                    final ClassInterceptor classInterceptor = classInterceptorsIterator.next();
                    map.computeIfAbsent(classInterceptor.getClassName(), ignored -> new ArrayList<>()).add(classInterceptor.getInterceptor());
                } while (classInterceptorsIterator.hasNext());
                final Iterator<Map.Entry<String, ArrayList<EJBClientInterceptorInformation>>> mapIterator = map.entrySet().iterator();
                if (mapIterator.hasNext()) {
                    final Map.Entry<String, ArrayList<EJBClientInterceptorInformation>> first = mapIterator.next();
                    if (mapIterator.hasNext()) {
                        Map<String, InterceptorList> targetMap = new HashMap<>(map.size());
                        targetMap.put(first.getKey(), InterceptorList.ofList(first.getValue()));
                        do {
                            final Map.Entry<String, ArrayList<EJBClientInterceptorInformation>> next = mapIterator.next();
                            targetMap.put(next.getKey(), InterceptorList.ofList(next.getValue()));
                        } while (mapIterator.hasNext());
                        this.configuredPerClassInterceptors = targetMap;
                    } else {
                        this.configuredPerClassInterceptors = Collections.singletonMap(first.getKey(), InterceptorList.ofList(first.getValue()));
                    }
                } else {
                    this.configuredPerClassInterceptors = Collections.emptyMap();
                }
            } else {
                this.configuredPerClassInterceptors = Collections.emptyMap();
            }
        } else {
            this.configuredPerClassInterceptors = Collections.emptyMap();
        }

        // configured per-method interceptors
        final List<MethodInterceptor> methodInterceptors = builder.methodInterceptors;
        if (methodInterceptors != null) {
            final Iterator<MethodInterceptor> methodInterceptorIterator = methodInterceptors.iterator();
            if (methodInterceptorIterator.hasNext()) {
                final HashMap<String, HashMap<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>>> map = new HashMap<>();
                do {
                    final MethodInterceptor methodInterceptor = methodInterceptorIterator.next();
                    map.computeIfAbsent(methodInterceptor.getClassName(), ignored -> new HashMap<>()).computeIfAbsent(methodInterceptor.getMethodLocator(), ignored -> new ArrayList<>()).add(methodInterceptor.getInterceptor());
                } while (methodInterceptorIterator.hasNext());
                final Iterator<Map.Entry<String, HashMap<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>>>> outerIter = map.entrySet().iterator();
                if (outerIter.hasNext()) {
                    final Map.Entry<String, HashMap<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>>> first = outerIter.next();
                    if (outerIter.hasNext()) {
                        Map<String, Map<EJBMethodLocator, InterceptorList>> targetMap = new HashMap<>(map.size());
                        targetMap.put(first.getKey(), calculateMethodInterceptors(first.getValue()));
                        do {
                            final Map.Entry<String, HashMap<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>>> next = outerIter.next();
                            targetMap.put(next.getKey(), calculateMethodInterceptors(next.getValue()));
                        } while (outerIter.hasNext());
                        this.configuredPerMethodInterceptors = targetMap;
                    } else {
                        this.configuredPerMethodInterceptors = Collections.singletonMap(first.getKey(), calculateMethodInterceptors(first.getValue()));
                    }
                } else {
                    this.configuredPerMethodInterceptors = Collections.emptyMap();
                }
            } else {
                this.configuredPerMethodInterceptors = Collections.emptyMap();
            }
        } else {
            this.configuredPerMethodInterceptors = Collections.emptyMap();
        }

        // this must be last
        for (EJBTransportProvider transportProvider : transportProviders) {
            transportProvider.notifyRegistered(receiverContext);
        }
    }

    private static Map<EJBMethodLocator, InterceptorList> calculateMethodInterceptors(final HashMap<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>> map) {
        final Iterator<Map.Entry<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>>> iterator = map.entrySet().iterator();
        if (iterator.hasNext()) {
            final Map.Entry<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>> first = iterator.next();
            if (iterator.hasNext()) {
                HashMap<EJBMethodLocator, InterceptorList> targetMap = new HashMap<>(map.size());
                targetMap.put(first.getKey(), InterceptorList.ofList(first.getValue()));
                do {
                    final Map.Entry<EJBMethodLocator, ArrayList<EJBClientInterceptorInformation>> next = iterator.next();
                    targetMap.put(next.getKey(), InterceptorList.ofList(next.getValue()));
                } while (iterator.hasNext());
                return targetMap;
            } else {
                return Collections.singletonMap(first.getKey(), InterceptorList.ofList(first.getValue()));
            }
        } else {
            return Collections.emptyMap();
        }
    }

    static InterceptorList getClassPathInterceptorList() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try {
            final Enumeration<URL> resources = classLoader.getResources("META-INF/services/org.jboss.ejb.client.EJBClientInterceptor");
            if (resources.hasMoreElements()) {
                final ArrayList<EJBClientInterceptorInformation> list = new ArrayList<>();
                do {
                    final URL url = resources.nextElement();
                    try (InputStream st = url.openStream()) {
                        try (InputStreamReader isr = new InputStreamReader(st, StandardCharsets.UTF_8)) {
                            try (BufferedReader r = new BufferedReader(isr)) {
                                String line;
                                while ((line = r.readLine()) != null) {
                                    line = line.trim();
                                    if (line.isEmpty() || line.charAt(0) == '#') {
                                        continue;
                                    }
                                    try {
                                        final Class<?> interceptorClass = Class.forName(line, true, classLoader).asSubclass(EJBClientInterceptor.class);
                                        list.add(EJBClientInterceptorInformation.forClass(interceptorClass));
                                    } catch (ClassNotFoundException e) {
                                        // skip
                                    }
                                }
                            }
                        }
                    }
                } while (resources.hasMoreElements());
                if (list.isEmpty()) {
                    return InterceptorList.EMPTY;
                } else {
                    return new InterceptorList(list.toArray(EJBClientInterceptorInformation.NO_INTERCEPTORS));
                }
            } else {
                return InterceptorList.EMPTY;
            }
        } catch (IOException e) {
            return InterceptorList.EMPTY;
        }
    }

    /**
     * Register a client interceptor with this client context.
     * <p/>
     * If the passed <code>clientInterceptor</code> is already added to this context, then this method just returns the
     * old {@link org.jboss.ejb.client.EJBClientInterceptor.Registration}.
     *
     * <p>
     * Note: If an interceptor is added or removed after a proxy is used, this will not affect the proxy interceptor list.
     *</p>
     *
     * @param priority          the absolute priority of this interceptor (lower runs earlier; higher runs later)
     * @param clientInterceptor the interceptor to register
     * @return a handle which may be used to later remove this registration
     *
     * @deprecated Please use EJBClientContext.Builder to manipulate the EJBClientInterceptors.
     */
    @Deprecated
    public EJBClientInterceptor.Registration registerInterceptor(final int priority, final EJBClientInterceptor clientInterceptor) throws IllegalArgumentException {
        Assert.checkNotNullParam("clientInterceptor", clientInterceptor);
        final EJBClientInterceptor.Registration newRegistration = new EJBClientInterceptor.Registration(this, clientInterceptor, priority);
        EJBClientInterceptor.Registration[] oldRegistrations, newRegistrations;
        do {
            oldRegistrations = registrations;
            for (EJBClientInterceptor.Registration oldRegistration : oldRegistrations) {
                if (oldRegistration.getInterceptor() == clientInterceptor) {
                    if (oldRegistration.compareTo(newRegistration) == 0) {
                        // This means that a client interceptor which has already been added to this context,
                        // is being added with the same priority. In such cases, this new registration request
                        // is effectively a no-op and we just return the old registration
                        return oldRegistration;
                    }
                }
            }
            final int length = oldRegistrations.length;
            newRegistrations = Arrays.copyOf(oldRegistrations, length + 1);
            newRegistrations[length] = newRegistration;
            Arrays.sort(newRegistrations);
        } while (!registrationsUpdater.compareAndSet(this, oldRegistrations, newRegistrations));
        return newRegistration;
    }

    /**
     * Removes the EJBClientInterceptor from current registrations. It is used by EJBClientInterceptor.Registration itself.
     * <p>
     * Note: If an interceptor is added or removed after a proxy is used, this will not affect the proxy interceptor list.
     *</p>
     *
     * @param registration the EJBClientInterceptor registration handler
     *
     * @deprecated Please use EJBClientContext.Builder to manipulate the EJBClientInterceptors.
     */
    @Deprecated
    void removeInterceptor(final EJBClientInterceptor.Registration registration) {
        EJBClientInterceptor.Registration[] oldRegistrations, newRegistrations;
        do {
            oldRegistrations = registrations;
            newRegistrations = null;
            final int length = oldRegistrations.length;
            final int newLength = length - 1;
            if (length == 1) {
                if (oldRegistrations[0] == registration) {
                    newRegistrations = NO_INTERCEPTORS;
                }
            } else {
                for (int i = 0; i < length; i++) {
                    if (oldRegistrations[i] == registration) {
                        if (i == newLength) {
                            newRegistrations = Arrays.copyOf(oldRegistrations, newLength);
                            break;
                        } else {
                            newRegistrations = new EJBClientInterceptor.Registration[newLength];
                            if (i > 0) System.arraycopy(oldRegistrations, 0, newRegistrations, 0, i);
                            System.arraycopy(oldRegistrations, i + 1, newRegistrations, i, newLength - i);
                            break;
                        }
                    }
                }
            }
            if (newRegistrations == null) {
                return;
            }
        } while (!registrationsUpdater.compareAndSet(this, oldRegistrations, newRegistrations));
    }

    /**
     * Get the context manager.  Simply calls the {@code static} method {@link #getContextManager()}.
     *
     * @return the context manager (not {@code null})
     */
    public ContextManager<EJBClientContext> getInstanceContextManager() {
        return getContextManager();
    }

    /**
     * Get the context manager.
     *
     * @return the context manager (not {@code null})
     */
    public static ContextManager<EJBClientContext> getContextManager() {
        return CONTEXT_MANAGER;
    }

    /**
     * Get the configured invocation timeout.  A value of zero indicates that invocations never time out.
     *
     * @return the configured invocation timeout
     */
    public long getInvocationTimeout() {
        return invocationTimeout;
    }

    /**
     * Check whether transaction propagation is suppressed.
     *
     * @return true if transaction propagation is suppressed, false otherwise
     */
    public boolean isSuppressTxPropagation() {
        return suppressTxPropagation;
    }

    /**
     * Get the pre-configured connections for this context.  This information may not be used by some transport providers
     * and mainly exists for legacy compatibility purposes.
     *
     * @return the pre-configured connections for this context (not {@code null})
     */
    public List<EJBClientConnection> getConfiguredConnections() {
        return configuredConnections;
    }

    /**
     * Get the initially configured clusters for this context.  The collection will not reflect any topology updates
     * received from peers.
     *
     * @return the initially configured clusters for this context
     */
    public Collection<EJBClientCluster> getInitialConfiguredClusters() {
        return configuredClusters.values();
    }

    /**
     * Get the maximum connected cluster nodes setting, for connection-based protocols which support eager connection.
     *
     * @return the maximum connected cluster nodes count
     */
    public int getMaximumConnectedClusterNodes() {
        return maximumConnectedClusterNodes;
    }

    /**
     * Get a copy of this context with the given interceptor(s) added.  If the array is {@code null} or empty, the
     * current context is returned as-is.
     *
     * @param interceptors the interceptor(s) to add
     * @return the new context (not {@code null})
     */
    public EJBClientContext withAddedInterceptors(EJBClientInterceptor... interceptors) {
        if (interceptors == null) {
            return this;
        }
        final int length = interceptors.length;
        if (length == 0) {
            return this;
        }
        final Builder builder = new Builder(this);
        boolean construct = false;
        for (EJBClientInterceptor interceptor : interceptors) {
            if (interceptor != null) {
                builder.addInterceptor(interceptor);
                construct = true;
            }
        }
        return construct ? builder.build() : this;
    }

    /**
     * Get a copy of this context with the given transport provider(s) added.  If the array is {@code null} or empty, the
     * current context is returned as-is.
     *
     * @param transportProviders the transport providers(s) to add
     * @return the new context (not {@code null})
     */
    public EJBClientContext withAddedTransportProviders(EJBTransportProvider... transportProviders) {
        if (transportProviders == null) {
            return this;
        }
        final int length = transportProviders.length;
        if (length == 0) {
            return this;
        }
        final Builder builder = new Builder(this);
        boolean construct = false;
        for (EJBTransportProvider transportProvider : transportProviders) {
            if (transportProvider != null) {
                builder.addTransportProvider(transportProvider);
                construct = true;
            }
        }
        return construct ? builder.build() : this;
    }

    EJBReceiver getTransportProvider(final String scheme) {
        for (EJBTransportProvider transportProvider : transportProviders) {
            if (transportProvider.supportsProtocol(scheme)) {
                return transportProvider.getReceiver(receiverContext, scheme);
            }
        }
        return null;
    }

    Discovery getDiscovery() {
        return DISCOVERY_SUPPLIER.get();
    }

    InterceptorList getInterceptors(final Class<?> invokedProxy, final Method method) {
        return proxyInfoValue.get(invokedProxy).getInterceptors(method);
    }

    InterceptorList getInterceptors(final Class<?> invokedProxy) {
        return proxyInfoValue.get(invokedProxy).getClassInterceptors();
    }

    /**
     * Resolve the receiver for the given destination.  If there is no handler then an exception is raised.
     *
     * @param destination the destination URI
     * @param locator the locator to use for error reports (must not be {@code null})
     * @return the resolved receiver (not {@code null})
     */
    EJBReceiver resolveReceiver(final URI destination, final EJBLocator<?> locator) {
        if (destination == null) {
            throw Logs.INVOCATION.noDestinationEstablished(locator);
        }
        final String scheme = destination.getScheme();
        for (EJBTransportProvider transportProvider : transportProviders) {
            if (transportProvider.supportsProtocol(scheme)) {
                final EJBReceiver receiver = transportProvider.getReceiver(receiverContext, scheme);
                if (receiver != null) return receiver;
            }
        }
        throw Logs.INVOCATION.noEJBReceiverAvailable(destination);
    }

    ClusterNodeSelector getClusterNodeSelector() {
        return clusterNodeSelector;
    }

    DeploymentNodeSelector getDeploymentNodeSelector() {
        return deploymentNodeSelector;
    }

    static final class ClassInterceptor {
        private final String className;
        private final EJBClientInterceptorInformation interceptor;

        ClassInterceptor(final String className, final EJBClientInterceptorInformation interceptor) {
            this.className = className;
            this.interceptor = interceptor;
        }

        String getClassName() {
            return className;
        }

        EJBClientInterceptorInformation getInterceptor() {
            return interceptor;
        }
    }

    static final class MethodInterceptor {
        private final String className;
        private final EJBMethodLocator methodLocator;
        private final EJBClientInterceptorInformation interceptor;

        MethodInterceptor(final String className, final EJBMethodLocator methodLocator, final EJBClientInterceptorInformation interceptor) {
            this.className = className;
            this.methodLocator = methodLocator;
            this.interceptor = interceptor;
        }

        String getClassName() {
            return className;
        }

        EJBMethodLocator getMethodLocator() {
            return methodLocator;
        }

        EJBClientInterceptorInformation getInterceptor() {
            return interceptor;
        }
    }

    /**
     * A builder for EJB client contexts.
     */
    public static final class Builder {

        List<EJBClientInterceptorInformation> globalInterceptors;
        List<ClassInterceptor> classInterceptors;
        List<MethodInterceptor> methodInterceptors;
        List<EJBTransportProvider> transportProviders;
        List<EJBClientConnection> clientConnections;
        List<EJBClientCluster> clientClusters;
        ClusterNodeSelector clusterNodeSelector = ClusterNodeSelector.DEFAULT;
        DeploymentNodeSelector deploymentNodeSelector = DeploymentNodeSelector.RANDOM;
        long invocationTimeout;
        boolean suppressTxPropagation;
        int maximumConnectedClusterNodes = 10;

        /**
         * Construct a new instance.
         */
        public Builder() {
        }

        Builder(final EJBClientContext clientContext) {
            globalInterceptors = Arrays.stream(clientContext.globalInterceptors.getInformation()).collect(Collectors.toCollection(ArrayList::new));
            classInterceptors = new ArrayList<>();
            for (Map.Entry<String, InterceptorList> entry : clientContext.getConfiguredPerClassInterceptors().entrySet()) {
                final String className = entry.getKey();
                for (EJBClientInterceptorInformation information : entry.getValue().getInformation()) {
                    classInterceptors.add(new ClassInterceptor(className, information));
                }
            }
            methodInterceptors = new ArrayList<>();
            for (Map.Entry<String, Map<EJBMethodLocator, InterceptorList>> entry : clientContext.getConfiguredPerMethodInterceptors().entrySet()) {
                final String className = entry.getKey();
                for (Map.Entry<EJBMethodLocator, InterceptorList> entry1 : entry.getValue().entrySet()) {
                    final EJBMethodLocator methodLocator = entry1.getKey();
                    for (EJBClientInterceptorInformation information : entry1.getValue().getInformation()) {
                        methodInterceptors.add(new MethodInterceptor(className, methodLocator, information));
                    }
                }
            }
            transportProviders = new ArrayList<>();
            Collections.addAll(transportProviders, clientContext.transportProviders);
            clientConnections = new ArrayList<>();
            clientConnections.addAll(clientContext.getConfiguredConnections());
            clientClusters = new ArrayList<>();
            clientClusters.addAll(clientContext.configuredClusters.values());
            clusterNodeSelector = clientContext.clusterNodeSelector;
            deploymentNodeSelector = clientContext.deploymentNodeSelector;
            invocationTimeout = clientContext.invocationTimeout;
        }

        public Builder addInterceptor(EJBClientInterceptor interceptor) {
            Assert.checkNotNullParam("interceptor", interceptor);
            if (globalInterceptors == null) {
                globalInterceptors = new ArrayList<>();
            }
            globalInterceptors.add(EJBClientInterceptorInformation.forInstance(interceptor));
            return this;
        }

        public Builder addInterceptor(Class<? extends EJBClientInterceptor> interceptorClass) {
            Assert.checkNotNullParam("interceptorClass", interceptorClass);
            if (globalInterceptors == null) {
                globalInterceptors = new ArrayList<>();
            }
            globalInterceptors.add(EJBClientInterceptorInformation.forClass(interceptorClass));
            return this;
        }

        public Builder addClassInterceptor(String className, EJBClientInterceptor interceptor) {
            Assert.checkNotNullParam("className", className);
            Assert.checkNotNullParam("interceptor", interceptor);
            if (classInterceptors == null) {
                classInterceptors = new ArrayList<>();
            }
            classInterceptors.add(new ClassInterceptor(className, EJBClientInterceptorInformation.forInstance(interceptor)));
            return this;
        }

        public Builder addClassInterceptor(String className, Class<? extends EJBClientInterceptor> interceptorClass) {
            Assert.checkNotNullParam("className", className);
            Assert.checkNotNullParam("interceptorClass", interceptorClass);
            if (classInterceptors == null) {
                classInterceptors = new ArrayList<>();
            }
            classInterceptors.add(new ClassInterceptor(className, EJBClientInterceptorInformation.forClass(interceptorClass)));
            return this;
        }

        public Builder addMethodInterceptor(String className, EJBMethodLocator methodLocator, EJBClientInterceptor interceptor) {
            Assert.checkNotNullParam("className", className);
            Assert.checkNotNullParam("methodLocator", methodLocator);
            Assert.checkNotNullParam("interceptor", interceptor);
            if (methodInterceptors == null) {
                methodInterceptors = new ArrayList<>();
            }
            methodInterceptors.add(new MethodInterceptor(className, methodLocator, EJBClientInterceptorInformation.forInstance(interceptor)));
            return this;
        }

        public Builder addMethodInterceptor(String className, EJBMethodLocator methodLocator, Class<? extends EJBClientInterceptor> interceptorClass) {
            Assert.checkNotNullParam("className", className);
            Assert.checkNotNullParam("methodLocator", methodLocator);
            Assert.checkNotNullParam("interceptorClass", interceptorClass);
            if (methodInterceptors == null) {
                methodInterceptors = new ArrayList<>();
            }
            methodInterceptors.add(new MethodInterceptor(className, methodLocator, EJBClientInterceptorInformation.forClass(interceptorClass)));
            return this;
        }

        public Builder addTransportProvider(EJBTransportProvider provider) {
            Assert.checkNotNullParam("provider", provider);
            if (transportProviders == null) {
                transportProviders = new ArrayList<>();
            }
            transportProviders.add(provider);
            return this;
        }

        public Builder addClientConnection(EJBClientConnection connection) {
            Assert.checkNotNullParam("connection", connection);
            if (clientConnections == null) {
                clientConnections = new ArrayList<>();
            }
            clientConnections.add(connection);
            return this;
        }

        public Builder addClientCluster(EJBClientCluster cluster) {
            Assert.checkNotNullParam("cluster", cluster);
            if (clientClusters == null) {
                clientClusters = new ArrayList<>();
            }
            clientClusters.add(cluster);
            return this;
        }

        public Builder setClusterNodeSelector(final ClusterNodeSelector clusterNodeSelector) {
            Assert.checkNotNullParam("clusterNodeSelector", clusterNodeSelector);
            this.clusterNodeSelector = clusterNodeSelector;
            return this;
        }

        public Builder setDeploymentNodeSelector(final DeploymentNodeSelector deploymentNodeSelector) {
            Assert.checkNotNullParam("deploymentNodeSelector", deploymentNodeSelector);
            this.deploymentNodeSelector = deploymentNodeSelector;
            return this;
        }

        public Builder setInvocationTimeout(final long invocationTimeout) {
            Assert.checkMinimumParameter("invocationTimeout", 0L, invocationTimeout);
            this.invocationTimeout = invocationTimeout;
            return this;
        }

        public Builder setSuppressTxPropagation(final boolean suppressTxPropagation) {
            this.suppressTxPropagation = suppressTxPropagation;
            return this;
        }

        public Builder setMaximumConnectedClusterNodes(final int maximumConnectedClusterNodes) {
            Assert.checkMinimumParameter("maximumConnectedClusterNodes", 0, maximumConnectedClusterNodes);
            this.maximumConnectedClusterNodes = maximumConnectedClusterNodes;
            return this;
        }

        public EJBClientContext build() {
            return new EJBClientContext(this);
        }
    }

    /**
     * Get the current client context for this thread.
     *
     * @return the current client context
     */
    public static EJBClientContext getCurrent() {
        final EJBClientContext clientContext = GETTER.get();
        if (clientContext == null) {
            throw Logs.MAIN.noEJBClientContextAvailable();
        }
        return clientContext;
    }

    /**
     * Get the current client context for this thread.  Delegates to {@link #getCurrent()}.
     *
     * @return the current client context for this thread
     */
    public static EJBClientContext requireCurrent() {
        return getCurrent();
    }


    <T> StatefulEJBLocator<T> createSession(final StatelessEJBLocator<T> statelessLocator, final AuthenticationContext authenticationContext, final NamingProvider namingProvider) throws Exception {
        EJBSessionCreationInvocationContext context = createSessionCreationInvocationContext(statelessLocator, authenticationContext);
        return createSession(context, statelessLocator, namingProvider);
    }

    <T> EJBSessionCreationInvocationContext createSessionCreationInvocationContext(StatelessEJBLocator<T> statelessLocator, AuthenticationContext authenticationContext) {
        EJBClientContext.InterceptorList interceptorList = getInterceptors(statelessLocator.getViewType());
        return new EJBSessionCreationInvocationContext(statelessLocator, this, authenticationContext, interceptorList);
    }

    <T> StatefulEJBLocator<T> createSession(final EJBSessionCreationInvocationContext context, final StatelessEJBLocator<T> statelessLocator, final NamingProvider namingProvider) throws Exception {
        // Special hook for naming; let's replace this sometime soon.
        if (namingProvider != null) context.putAttachment(Keys.NAMING_PROVIDER_ATTACHMENT_KEY, namingProvider);

        if (Logs.INVOCATION.isDebugEnabled()) {
            Logs.INVOCATION.debugf("Calling createSession(locator = %s)", statelessLocator);
        }

        SessionID sessionID = null;
        for (int i = 0; i < MAX_SESSION_RETRIES; i++) {
            Throwable t;
            try {
                sessionID = context.proceed();
                break;
            } catch (RequestSendFailedException r) {
                if (! r.canBeRetried()) {
                    throw r;
                }
                t = r;
            } catch (Exception | Error o) {
                if (! context.shouldRetry()) {
                    throw o;
                }
                t = o;
            } catch (Throwable o) {
                if (! context.shouldRetry()) {
                    Exception e = new RequestSendFailedException(o.getClass().getSimpleName() + ": " + o.getMessage(), o.getCause());
                    e.setStackTrace(o.getStackTrace());
                    throw e;
                }
                t = o;
            }

            if (i == MAX_SESSION_RETRIES - 1) {
                throw new RequestSendFailedException(t.getMessage() + " (maximum retries exceeded)", t);
            }
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("Retrying invocation (attempt %d): %s", i + 1, statelessLocator);
            }
        }
        final Affinity affinity = context.getLocator().getAffinity();

        if (Logs.INVOCATION.isDebugEnabled()) {
            Logs.INVOCATION.debugf("Session created: session id: %s , affinity: %s", sessionID, affinity);
        }

        return statelessLocator.withSessionAndAffinity(sessionID, affinity);
    }

    InterceptorList getClassPathInterceptors() {
        return classPathInterceptors;
    }

    InterceptorList getGlobalInterceptors() {
        return globalInterceptors.combine(registeredInterceptors());
    }

    private InterceptorList registeredInterceptors() {
        final EJBClientInterceptor.Registration[] currentRegistrations = this.registrations.clone();
        ArrayList<EJBClientInterceptorInformation> al = new ArrayList<>();
        for (EJBClientInterceptor.Registration r: currentRegistrations) {
            al.add(EJBClientInterceptorInformation.forInstance(r.getInterceptor()));
        }
        return InterceptorList.ofList(al);
    }

    Map<String, InterceptorList> getConfiguredPerClassInterceptors() {
        return configuredPerClassInterceptors;
    }

    Map<String, Map<EJBMethodLocator, InterceptorList>> getConfiguredPerMethodInterceptors() {
        return configuredPerMethodInterceptors;
    }

    static <T extends Throwable> T withSuppressed(T original, Collection<Throwable> suppressed) {
        if (suppressed != null) {
            for (Throwable throwable : suppressed) {
                original.addSuppressed(throwable);
            }
        }
        return original;
    }

    static <T extends Throwable> T withSuppressed(T original, Collection<Throwable> suppressed1, Collection<Throwable> suppressed2) {
        if (suppressed1 != null) {
            for (Throwable throwable : suppressed1) {
                original.addSuppressed(throwable);
            }
        }
        if (suppressed2 != null) {
            for (Throwable throwable : suppressed2) {
                original.addSuppressed(throwable);
            }
        }
        return original;
    }

    static final class InterceptorList {
        static final InterceptorList EMPTY = new InterceptorList(EJBClientInterceptorInformation.NO_INTERCEPTORS);

        private final EJBClientInterceptorInformation[] information;
        private final int hashCode;

        InterceptorList(final EJBClientInterceptorInformation[] information) {
            Arrays.sort(information);
            this.information = information;
            hashCode = Arrays.hashCode(information);
        }

        EJBClientInterceptorInformation[] getInformation() {
            return information;
        }

        public boolean equals(final Object obj) {
            return obj instanceof InterceptorList && Arrays.equals(information, ((InterceptorList) obj).information);
        }

        public int hashCode() {
            return hashCode;
        }

        private static InterceptorList ofList(final ArrayList<EJBClientInterceptorInformation> value) {
            if (value.isEmpty()) {
                return EMPTY;
            } else if (value.size() == 1) {
                return value.get(0).getSingletonList();
            } else {
                return new InterceptorList(value.toArray(EJBClientInterceptorInformation.NO_INTERCEPTORS));
            }
        }

        InterceptorList combine(final InterceptorList other) {
            return information.length == 0 ? other : other.information.length == 0 ? this : new InterceptorList(concat(information, other.information));
        }

        private static EJBClientInterceptorInformation[] concat(final EJBClientInterceptorInformation[] a, final EJBClientInterceptorInformation[] b) {
            final EJBClientInterceptorInformation[] c = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, c, a.length, b.length);
            return c;
        }
    }
}
