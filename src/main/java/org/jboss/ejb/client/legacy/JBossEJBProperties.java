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

package org.jboss.ejb.client.legacy;

import static java.security.AccessController.doPrivileged;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.security.auth.callback.CallbackHandler;

import org.jboss.ejb._private.Logs;
import org.jboss.ejb.client.ClusterNodeSelector;
import org.jboss.ejb.client.DeploymentNodeSelector;
import org.wildfly.common.Assert;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.common.context.ContextManager;
import org.wildfly.common.context.Contextual;
import org.wildfly.common.expression.Expression;
import org.wildfly.common.function.ExceptionBiFunction;
import org.wildfly.common.function.ExceptionFunction;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.security.util.CodePointIterator;
import org.xnio.OptionMap;
import org.xnio.Options;

/**
 * An object model of the legacy {@code jboss-ejb.properties} file format.
 *
 * @author Jaikiran Pai
 * @author <a href=mailto:tadamski@redhat.com>Tomasz Adamski</a>
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class JBossEJBProperties implements Contextual<JBossEJBProperties> {

    public static final String DEFAULT_PATH_NAME = "jboss-ejb-client.properties";

    public static final String PROPERTY_KEY_CLUSTERS = "remote.clusters";

    private static final ContextManager<JBossEJBProperties> CONTEXT_MANAGER = new ContextManager<>(JBossEJBProperties.class, "org.jboss.ejb.client.legacy-properties");
    private static final Supplier<JBossEJBProperties> SUPPLIER = doPrivileged((PrivilegedAction<Supplier<JBossEJBProperties>>) CONTEXT_MANAGER::getPrivilegedSupplier);

    private static final String PROPERTY_KEY_ENDPOINT_NAME = "endpoint.name";
    private static final String DEFAULT_ENDPOINT_NAME = "config-based-ejb-client-endpoint";

    private static final String PROPERTY_KEY_INVOCATION_TIMEOUT = "invocation.timeout";
    private static final String PROPERTY_KEY_SUPPRESS_TX_PROPAGATION = "transaction.propagation.suppress";
    private static final String PROPERTY_KEY_RECONNECT_TASKS_TIMEOUT = "reconnect.tasks.timeout";
    private static final String PROPERTY_KEY_DEPLOYMENT_NODE_SELECTOR = "deployment.node.selector";

    private static final String ENDPOINT_CREATION_OPTIONS_PREFIX = "endpoint.create.options.";
    // The default options that will be used (unless overridden by the config file) for endpoint creation
    private static final OptionMap DEFAULT_ENDPOINT_CREATION_OPTIONS = OptionMap.create(Options.THREAD_DAEMON, Boolean.TRUE);

    // The default options that will be used (unless overridden by the config file) while adding a remote connection
    // provider to the endpoint
    private static final OptionMap DEFAULT_CONNECTION_PROVIDER_CREATION_OPTIONS = OptionMap.EMPTY;
    private static final String REMOTE_CONNECTION_PROVIDER_CREATE_OPTIONS_PREFIX = "remote.connectionprovider.create.options.";

    private static final String PROPERTY_KEY_REMOTE_CONNECTIONS = "remote.connections";
    private static final String PROPERTY_KEY_REMOTE_CONNECTIONS_CONNECT_EAGER = "remote.connections.connect.eager";

    // The default options that will be used (unless overridden by the config file) while creating a connection
    private static final OptionMap DEFAULT_CONNECTION_CREATION_OPTIONS = OptionMap.EMPTY;
    private static final long DEFAULT_CONNECTION_TIMEOUT_IN_MILLIS = 5000;

    private static final String PROPERTY_KEY_USERNAME = "username";
    private static final String PROPERTY_KEY_PASSWORD = "password";
    private static final String PROPERTY_KEY_PASSWORD_BASE64 = "password.base64";
    private static final String PROPERTY_KEY_REALM = "realm";
    private static final String PROPERTY_KEY_CALLBACK_HANDLER_CLASS = "callback.handler.class";

    private static final String PROPERTY_KEY_HOST = "host";
    private static final String PROPERTY_KEY_PORT = "port";
    private static final String PROPERTY_KEY_PROTOCOL = "protocol";
    private static final String DEFAULT_PROTOCOL = "http-remoting";

    private static final boolean expandPasswords;
    private static final String CONFIGURED_PATH_NAME;

    static {
        expandPasswords = doPrivileged((PrivilegedAction<Boolean>) () ->
            Boolean.valueOf(System.getProperty("jboss-ejb-client.expandPasswords", "false"))).booleanValue();
        final String filePathPropertyName = "jboss.ejb.client.properties.file.path";
        CONFIGURED_PATH_NAME = doPrivileged((PrivilegedAction<String>) () -> System.getProperty(filePathPropertyName));
        final AtomicReference<JBossEJBProperties> onceRef = new AtomicReference<>();
        CONTEXT_MANAGER.setGlobalDefaultSupplier(() -> {
            JBossEJBProperties value = onceRef.get();
            if (value == null) {
                synchronized (onceRef) {
                    value = onceRef.get();
                    if (value == null) {
                        try {
                            if (CONFIGURED_PATH_NAME != null) try {
                                File propertiesFile = new File(CONFIGURED_PATH_NAME);
                                if (! propertiesFile.isAbsolute()) {
                                    propertiesFile = new File(System.getProperty("user.dir"), propertiesFile.toString());
                                }
                                value = JBossEJBProperties.fromFile(propertiesFile);
                            } catch (IOException e) {
                                Logs.MAIN.failedToFindEjbClientConfigFileSpecifiedBySysProp(filePathPropertyName, e);
                                value = JBossEJBProperties.fromClassPath();
                            } else {
                                value = JBossEJBProperties.fromClassPath();
                            }
                        } catch (IOException e) {
                        }
                        onceRef.set(value);
                    }
                }
            }
            return value;
        });
    }

    // Remoting-specific properties

    private final String endpointName;
    private final AuthenticationConfiguration authenticationConfiguration;
    private final String defaultCallbackHandlerClassName;
    private final OptionMap endpointCreationOptions;
    private final OptionMap remoteConnectionProviderCreationOptions;

    // Connections

    private final List<ConnectionConfiguration> connectionList;

    // Security-specific properties

    private final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier;

    // EJB discovery and clustering properties

    private final ExceptionSupplier<DeploymentNodeSelector, ReflectiveOperationException> deploymentNodeSelectorSupplier;
    private final Map<String, ClusterConfiguration> clusterConfigurations;

    // Other EJB parameters

    private final long invocationTimeout;
    private final boolean suppressTxPropagation;
    private final long reconnectTimeout;
    private final String deploymentNodeSelectorClassName;
    private final boolean defaultConnectEagerly;

    JBossEJBProperties(final Builder builder) {
        this.endpointName = builder.endpointName;
        this.defaultCallbackHandlerClassName = builder.callbackHandlerClassName;
        this.authenticationConfiguration = builder.authenticationConfiguration;
        this.endpointCreationOptions = builder.endpointCreationOptions;
        this.remoteConnectionProviderCreationOptions = builder.remoteConnectionProviderCreationOptions;
        this.callbackHandlerSupplier = builder.callbackHandlerSupplier;
        this.deploymentNodeSelectorSupplier = builder.deploymentNodeSelectorSupplier;
        this.clusterConfigurations = builder.clusterConfigurations;
        this.invocationTimeout = builder.invocationTimeout;
        this.suppressTxPropagation = builder.suppressTxPropagation;
        this.reconnectTimeout = builder.reconnectTimeout;
        this.deploymentNodeSelectorClassName = builder.deploymentNodeSelectorClassName;
        this.connectionList = builder.connectionList;
        this.defaultConnectEagerly = builder.connectEagerly;
    }

    public String getEndpointName() {
        return endpointName;
    }

    public String getDefaultCallbackHandlerClassName() {
        return defaultCallbackHandlerClassName;
    }

    public AuthenticationConfiguration getAuthenticationConfiguration() {
        return authenticationConfiguration;
    }

    public OptionMap getEndpointCreationOptions() {
        return endpointCreationOptions;
    }

    public OptionMap getRemoteConnectionProviderCreationOptions() {
        return remoteConnectionProviderCreationOptions;
    }

    public List<ConnectionConfiguration> getConnectionList() {
        return connectionList;
    }

    public ExceptionSupplier<CallbackHandler, ReflectiveOperationException> getDefaultCallbackHandlerSupplier() {
        return callbackHandlerSupplier;
    }

    public ExceptionSupplier<DeploymentNodeSelector, ReflectiveOperationException> getDeploymentNodeSelectorSupplier() {
        return deploymentNodeSelectorSupplier;
    }

    public Map<String, ClusterConfiguration> getClusterConfigurations() {
        return clusterConfigurations;
    }

    public long getInvocationTimeout() {
        return invocationTimeout;
    }

    public boolean isSuppressTxPropagation() {
        return suppressTxPropagation;
    }

    public long getReconnectTimeout() {
        return reconnectTimeout;
    }

    public String getDeploymentNodeSelectorClassName() {
        return deploymentNodeSelectorClassName;
    }

    public boolean isDefaultConnectEagerly() {
        return defaultConnectEagerly;
    }

    /**
     * Get the context manager.
     *
     * @return the context manager (not {@code null})
     */
    @NotNull
    public ContextManager<JBossEJBProperties> getInstanceContextManager() {
        return getContextManager();
    }

    /**
     * Get the context manager.
     *
     * @return the context manager (not {@code null})
     */
    @NotNull
    public static ContextManager<JBossEJBProperties> getContextManager() {
        return CONTEXT_MANAGER;
    }

    // Factories

    private static OptionMap getOptionMapFromProperties(final Properties properties, final String propertyPrefix, final ClassLoader classLoader) {
        return OptionMap.builder().parseAll(properties, propertyPrefix, classLoader).getMap();
    }

    private static long getLongValueFromProperties(final Properties properties, final String propertyName, final long defVal) {
        final String str = getProperty(properties, propertyName, null, true);
        if (str == null) {
            return defVal;
        }
        try {
            return Long.parseLong(str);
        } catch (NumberFormatException e) {
            return defVal;
        }
    }

    private static boolean getBooleanValueFromProperties(final Properties properties, final String name, final boolean defaultValue) {
        final String stringValue = getProperty(properties, name, null, true);
        if (stringValue == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(stringValue);
    }

    private static String getProperty(final Properties properties, final String propertyName, final String defaultValue, final boolean expand) {
        final String str = properties.getProperty(propertyName);
        if (str == null) {
            return defaultValue;
        }
        if (expand) {
            final Expression expression = Expression.compile(str, Expression.Flag.LENIENT_SYNTAX);
            return expression.evaluateWithPropertiesAndEnvironment(false);
        } else {
            return str.trim();
        }
    }

    public static JBossEJBProperties fromProperties(final String fileName, final Properties properties) {
        Assert.checkNotNullParam("fileName", fileName);
        Assert.checkNotNullParam("properties", properties);

        final ClassLoader classLoader = JBossEJBProperties.class.getClassLoader();

        final Builder builder = new Builder();

        builder.populateFromProperties(properties, "", classLoader, null);

        // if it's null, leave it null so that Remoting can pick a unique (hopefully) name based on our standard properties
        builder.setEndpointName(getProperty(properties, PROPERTY_KEY_ENDPOINT_NAME, null, true));

        // default callback handler class
        final String defaultCallbackHandlerClassName = getProperty(properties, PROPERTY_KEY_CALLBACK_HANDLER_CLASS, null, true);
        builder.setCallbackHandlerClassName(defaultCallbackHandlerClassName);

        builder.setCallbackHandlerSupplier(() ->
            Class.forName(defaultCallbackHandlerClassName, true, classLoader).asSubclass(CallbackHandler.class).getConstructor().newInstance());

        // endpoint creation options
        builder.setEndpointCreationOptions(getOptionMapFromProperties(properties, ENDPOINT_CREATION_OPTIONS_PREFIX, classLoader));

        // remote connection provider options
        builder.setRemoteConnectionProviderCreationOptions(getOptionMapFromProperties(properties, REMOTE_CONNECTION_PROVIDER_CREATE_OPTIONS_PREFIX, classLoader));

        // invocation timeout
        builder.setInvocationTimeout(getLongValueFromProperties(properties, PROPERTY_KEY_INVOCATION_TIMEOUT, -1L));

        // tx propagation suppression
        builder.setSuppressTxPropagation(getBooleanValueFromProperties(properties, PROPERTY_KEY_SUPPRESS_TX_PROPAGATION, false));

        // reconnect timeout
        builder.setReconnectTimeout(getLongValueFromProperties(properties, PROPERTY_KEY_RECONNECT_TASKS_TIMEOUT, -1L));

        // deployment node selector
        final String deploymentNodeSelectorClassName = getProperty(properties, PROPERTY_KEY_DEPLOYMENT_NODE_SELECTOR, null, true);
        if (deploymentNodeSelectorClassName != null) {
            builder.setDeploymentNodeSelectorClassName(deploymentNodeSelectorClassName);

            builder.setDeploymentNodeSelectorSupplier(() ->
                Class.forName(deploymentNodeSelectorClassName, true, classLoader).asSubclass(DeploymentNodeSelector.class).getConstructor().newInstance());
        }

        // connections
        final String connectionsString = getProperty(properties, PROPERTY_KEY_REMOTE_CONNECTIONS, "", true).trim();
        final List<ConnectionConfiguration> connectionList;

        if (! connectionsString.isEmpty()) {
            final ArrayList<ConnectionConfiguration> mutableList = new ArrayList<>();

            // Parse this the same way as the legacy code.
            final StringTokenizer tokenizer = new StringTokenizer(connectionsString, ",");
            while (tokenizer.hasMoreTokens()) {
                final String connectionName = tokenizer.nextToken().trim();
                if (! connectionName.isEmpty()) {
                    final ConnectionConfiguration.Builder connBuilder = new ConnectionConfiguration.Builder();

                    String prefix = "remote.connection." + connectionName + ".";

                    if (! connBuilder.populateFromProperties(properties, prefix, classLoader, builder, connectionName)) {
                        continue;
                    }

                    mutableList.add(new ConnectionConfiguration(connBuilder));
                }
            }

            if (mutableList.isEmpty()) {
                connectionList = Collections.emptyList();
            } else {
                mutableList.trimToSize();
                connectionList = Collections.unmodifiableList(mutableList);
            }
        } else {
            connectionList = Collections.emptyList();
        }
        builder.setConnectionList(connectionList);

        // clusters
        final String clustersString = getProperty(properties, PROPERTY_KEY_CLUSTERS, "", true).trim();
        final Map<String, ClusterConfiguration> clusterMap;

        if (! clustersString.isEmpty()) {
            final HashMap<String, ClusterConfiguration> map = new HashMap<>();
            final StringTokenizer tokenizer = new StringTokenizer(clustersString, ",");
            while (tokenizer.hasMoreTokens()) {
                final String clusterName = tokenizer.nextToken().trim();
                if (! clusterName.isEmpty()) {
                    String prefix = "remote.cluster." + clusterName + ".";

                    final ClusterConfiguration.Builder clusterBuilder = new ClusterConfiguration.Builder();
                    clusterBuilder.populateFromProperties(clusterName, properties, prefix, classLoader, builder);

                    map.put(clusterName, new ClusterConfiguration(clusterBuilder));
                }
            }
            if (map.isEmpty()) {
                clusterMap = Collections.emptyMap();
            } else {
                clusterMap = Collections.unmodifiableMap(map);
            }
        } else {
            clusterMap = Collections.emptyMap();
        }
        builder.setClusterConfigurations(clusterMap);

        return new JBossEJBProperties(builder);
    }

    public static <T, U> JBossEJBProperties fromResource(final String fileName, final ExceptionBiFunction<T, U, InputStream, IOException> streamSupplier, T param1, U param2) throws IOException {
        Assert.checkNotNullParam("fileName", fileName);
        Assert.checkNotNullParam("streamSupplier", streamSupplier);
        final InputStream stream;
        try {
            stream = streamSupplier.apply(param1, param2);
        } catch (FileNotFoundException | NoSuchFileException e) {
            return null;
        }
        if (stream == null) {
            return null;
        }
        return fromResource(fileName, stream);
    }

    private static JBossEJBProperties fromResource(String fileName, InputStream stream) throws IOException {
        try (InputStream inputStream = stream) {
            try (BufferedInputStream bis = new BufferedInputStream(inputStream)) {
                try (InputStreamReader reader = new InputStreamReader(bis, StandardCharsets.UTF_8)) {
                    final Properties properties = new Properties();
                    properties.load(reader);
                    return fromProperties(fileName, properties);
                }
            }
        }
    }

    public static <T> JBossEJBProperties fromResource(final String fileName, final ExceptionFunction<T, InputStream, IOException> streamSupplier, T param) throws IOException {
        return fromResource(fileName, ExceptionFunction::apply, streamSupplier, param);
    }

    public static JBossEJBProperties fromResource(final String fileName, final ExceptionSupplier<InputStream, IOException> streamSupplier) throws IOException {
        return fromResource(fileName, ExceptionSupplier::get, streamSupplier);
    }

    public static JBossEJBProperties fromFile(final File propertiesFile) throws IOException {
        Assert.checkNotNullParam("propertiesFile", propertiesFile);
        return fromResource(propertiesFile.getPath(), FileInputStream::new, propertiesFile);
    }

    public static JBossEJBProperties fromPath(final Path propertiesFile) throws IOException {
        Assert.checkNotNullParam("propertiesFile", propertiesFile);
        return fromResource(propertiesFile.toString(), Files::newInputStream, propertiesFile);
    }

    public static JBossEJBProperties fromClassPath(final ClassLoader classLoader, final String pathName) throws IOException {
        if (classLoader == null) {
            return fromResource(pathName, ClassLoader.getSystemResourceAsStream(pathName));
        }
        return fromResource(pathName, ClassLoader::getResourceAsStream, classLoader, pathName);
    }

    public static JBossEJBProperties fromClassPath() throws IOException {
        return fromClassPath(JBossEJBProperties.class.getClassLoader(), DEFAULT_PATH_NAME);
    }

    static JBossEJBProperties getCurrent() {
        return SUPPLIER.get();
    }

    static final class Builder extends CommonSubconfiguration.Builder {
        String endpointName;

        OptionMap endpointCreationOptions;
        OptionMap remoteConnectionProviderCreationOptions;
        List<ConnectionConfiguration> connectionList;
        Map<String, ClusterConfiguration> clusterConfigurations;
        long invocationTimeout;
        boolean suppressTxPropagation;
        long reconnectTimeout;
        String deploymentNodeSelectorClassName;
        ExceptionSupplier<DeploymentNodeSelector, ReflectiveOperationException> deploymentNodeSelectorSupplier;

        Builder() {
        }

        Builder setEndpointName(final String endpointName) {
            this.endpointName = endpointName;
            return this;
        }

        Builder setEndpointCreationOptions(final OptionMap endpointCreationOptions) {
            this.endpointCreationOptions = endpointCreationOptions;
            return this;
        }

        Builder setRemoteConnectionProviderCreationOptions(final OptionMap remoteConnectionProviderCreationOptions) {
            this.remoteConnectionProviderCreationOptions = remoteConnectionProviderCreationOptions;
            return this;
        }

        Builder setConnectionList(final List<ConnectionConfiguration> connectionList) {
            this.connectionList = connectionList;
            return this;
        }

        Builder setClusterConfigurations(final Map<String, ClusterConfiguration> clusterConfigurations) {
            this.clusterConfigurations = clusterConfigurations;
            return this;
        }

        Builder setInvocationTimeout(final long invocationTimeout) {
            this.invocationTimeout = invocationTimeout;
            return this;
        }

        Builder setSuppressTxPropagation(final boolean suppressTxPropagation) {
            this.suppressTxPropagation = suppressTxPropagation;
            return this;
        }

        Builder setReconnectTimeout(final long reconnectTimeout) {
            this.reconnectTimeout = reconnectTimeout;
            return this;
        }

        Builder setDeploymentNodeSelectorClassName(final String deploymentNodeSelectorClassName) {
            this.deploymentNodeSelectorClassName = deploymentNodeSelectorClassName;
            return this;
        }

        Builder setDeploymentNodeSelectorSupplier(final ExceptionSupplier<DeploymentNodeSelector, ReflectiveOperationException> deploymentNodeSelectorSupplier) {
            this.deploymentNodeSelectorSupplier = deploymentNodeSelectorSupplier;
            return this;
        }
    }

    abstract static class CommonSubconfiguration {
        private final OptionMap connectionOptions;
        private final String callbackHandlerClassName;
        private final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier;
        private final long connectionTimeout;
        private final OptionMap channelOptions;
        private final boolean connectEagerly;
        private final AuthenticationConfiguration authenticationConfiguration;

        CommonSubconfiguration(Builder builder) {
            this.connectionOptions = builder.connectionOptions;
            this.callbackHandlerClassName = builder.callbackHandlerClassName;
            this.callbackHandlerSupplier = builder.callbackHandlerSupplier;
            this.connectionTimeout = builder.connectionTimeout;
            this.channelOptions = builder.channelOptions;
            this.connectEagerly = builder.connectEagerly;
            this.authenticationConfiguration = builder.authenticationConfiguration;
        }

        public OptionMap getConnectionOptions() {
            return connectionOptions;
        }

        public long getConnectionTimeout() {
            return connectionTimeout;
        }

        public boolean isConnectEagerly() {
            return connectEagerly;
        }

        public String getCallbackHandlerClassName() {
            return callbackHandlerClassName;
        }

        public AuthenticationConfiguration getAuthenticationConfiguration() {
            return authenticationConfiguration;
        }

        public OptionMap getChannelOptions() {
            return channelOptions;
        }

        public ExceptionSupplier<CallbackHandler, ReflectiveOperationException> getCallbackHandlerSupplier() {
            return callbackHandlerSupplier;
        }

        abstract static class Builder {
            OptionMap connectionOptions;
            String callbackHandlerClassName;
            ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier;
            long connectionTimeout;
            OptionMap channelOptions;
            boolean connectEagerly;
            AuthenticationConfiguration authenticationConfiguration;

            Builder() {
            }

            Builder setConnectionOptions(final OptionMap connectionOptions) {
                this.connectionOptions = connectionOptions;
                return this;
            }

            Builder setCallbackHandlerClassName(final String callbackHandlerClassName) {
                this.callbackHandlerClassName = callbackHandlerClassName;
                return this;
            }

            Builder setCallbackHandlerSupplier(final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier) {
                this.callbackHandlerSupplier = callbackHandlerSupplier;
                return this;
            }

            Builder setConnectionTimeout(final long connectionTimeout) {
                this.connectionTimeout = connectionTimeout;
                return this;
            }

            Builder setChannelOptions(final OptionMap channelOptions) {
                this.channelOptions = channelOptions;
                return this;
            }

            Builder setConnectEagerly(final boolean connectEagerly) {
                this.connectEagerly = connectEagerly;
                return this;
            }

            Builder setAuthenticationConfiguration(final AuthenticationConfiguration authenticationConfiguration) {
                this.authenticationConfiguration = authenticationConfiguration;
                return this;
            }

            boolean populateFromProperties(final Properties properties, final String prefix, final ClassLoader classLoader, final Builder defaultsBuilder) {
                // connection options
                String connectOptionsPrefix = prefix + "connect.options" + ".";
                setConnectionOptions(getOptionMapFromProperties(properties, connectOptionsPrefix, classLoader));

                // connection timeout
                setConnectionTimeout(getLongValueFromProperties(properties, prefix + "connect.timeout", defaultsBuilder == null ? 5000L : defaultsBuilder.connectionTimeout));

                // connect eagerly
                setConnectEagerly(Boolean.parseBoolean(getProperty(properties, prefix + "connect.eager", Boolean.toString(defaultsBuilder == null || defaultsBuilder.connectEagerly), true).trim()));

                // callback handler class
                final String callbackHandlerClassName = getProperty(properties, prefix + PROPERTY_KEY_CALLBACK_HANDLER_CLASS, null, true);
                setCallbackHandlerClassName(callbackHandlerClassName);

                final AuthenticationConfiguration.Builder authBuilder = new AuthenticationConfiguration.Builder();
                if (authBuilder.populateFromProperties(properties, prefix, classLoader)) {
                    setAuthenticationConfiguration(new AuthenticationConfiguration(authBuilder));
                } else {
                    if (defaultsBuilder != null) {
                        setAuthenticationConfiguration(defaultsBuilder.authenticationConfiguration);
                    }
                }

                setChannelOptions(getOptionMapFromProperties(properties, prefix + "channel.options" + ".", classLoader));

                final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier =
                    () -> Class.forName(callbackHandlerClassName, true, classLoader).asSubclass(CallbackHandler.class).getConstructor().newInstance();

                return true;
            }

        }
    }

    public static class ConnectionConfiguration extends CommonSubconfiguration {

        private final String host;
        private final int port;
        private final String protocol;

        ConnectionConfiguration(Builder builder) {
            super(builder);
            this.host = builder.host;
            this.port = builder.port;
            this.protocol = builder.protocol;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getProtocol() {
            return protocol;
        }

        static final class Builder extends CommonSubconfiguration.Builder {
            String host;
            int port;
            String protocol;

            Builder() {
            }

            boolean populateFromProperties(final Properties properties, final String prefix, final ClassLoader classLoader, final CommonSubconfiguration.Builder defaultsBuilder) {
                // just to ensure this overload isn't used by mistake
                throw Assert.unsupported();
            }

            boolean populateFromProperties(final Properties properties, final String prefix, final ClassLoader classLoader, final CommonSubconfiguration.Builder defaultsBuilder, final String connectionName) {
                super.populateFromProperties(properties, prefix, classLoader, defaultsBuilder);

                // connection host name
                String host = getProperty(properties,prefix + PROPERTY_KEY_HOST, "", true).trim();
                if (host.isEmpty()) {
                    Logs.MAIN.skippingConnectionCreationDueToMissingHostOrPort(connectionName);
                    return false;
                }
                setHost(host);

                // connection port#
                String portStr = getProperty(properties,prefix + PROPERTY_KEY_PORT, "", true).trim();
                if (portStr.isEmpty()) {
                    Logs.MAIN.skippingConnectionCreationDueToMissingHostOrPort(connectionName);
                    return false;
                }
                int port;
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    Logs.MAIN.skippingConnectionCreationDueToInvalidPortNumber(portStr, connectionName);
                    return false;
                }
                setPort(port);

                String protocol = getProperty(properties, prefix + PROPERTY_KEY_PROTOCOL, DEFAULT_PROTOCOL, true).trim();
                this.protocol = protocol;
                return true;
            }

            Builder setHost(final String host) {
                this.host = host;
                return this;
            }

            Builder setPort(final int port) {
                this.port = port;
                return this;
            }
        }
    }

    public static class ClusterConfiguration extends CommonSubconfiguration {
        private final String clusterName;
        private final long maximumAllowedConnectedNodes;
        private final String clusterNodeSelectorClassName;
        private final ExceptionSupplier<ClusterNodeSelector, ReflectiveOperationException> clusterNodeSelectorSupplier;
        private final List<ClusterNodeConfiguration> nodeConfigurations;

        ClusterConfiguration(final Builder builder) {
            super(builder);
            this.clusterName = builder.clusterName;
            this.maximumAllowedConnectedNodes = builder.maximumAllowedConnectedNodes;
            this.clusterNodeSelectorClassName = builder.clusterNodeSelectorClassName;
            this.clusterNodeSelectorSupplier = builder.clusterNodeSelectorSupplier;
            this.nodeConfigurations = builder.nodeConfigurations;
        }

        public String getClusterName() {
            return clusterName;
        }

        public long getMaximumAllowedConnectedNodes() {
            return maximumAllowedConnectedNodes;
        }

        public String getClusterNodeSelectorClassName() {
            return clusterNodeSelectorClassName;
        }

        public ExceptionSupplier<ClusterNodeSelector, ReflectiveOperationException> getClusterNodeSelectorSupplier() {
            return clusterNodeSelectorSupplier;
        }

        public List<ClusterNodeConfiguration> getNodeConfigurations() {
            return nodeConfigurations;
        }

        static final class Builder extends CommonSubconfiguration.Builder {
            String clusterName;
            long maximumAllowedConnectedNodes;
            String clusterNodeSelectorClassName;
            ExceptionSupplier<ClusterNodeSelector, ReflectiveOperationException> clusterNodeSelectorSupplier;
            List<ClusterNodeConfiguration> nodeConfigurations = new ArrayList<ClusterNodeConfiguration>();

            Builder() {
            }

            Builder setClusterName(final String clusterName) {
                this.clusterName = clusterName;
                return this;
            }

            Builder setMaximumAllowedConnectedNodes(final long maximumAllowedConnectedNodes) {
                this.maximumAllowedConnectedNodes = maximumAllowedConnectedNodes;
                return this;
            }

            Builder setClusterNodeSelectorClassName(final String clusterNodeSelectorClassName) {
                this.clusterNodeSelectorClassName = clusterNodeSelectorClassName;
                return this;
            }

            Builder setClusterNodeSelectorSupplier(final ExceptionSupplier<ClusterNodeSelector, ReflectiveOperationException> clusterNodeSelectorSupplier) {
                this.clusterNodeSelectorSupplier = clusterNodeSelectorSupplier;
                return this;
            }

            Builder setNodeConfigurations(final List<ClusterNodeConfiguration> nodeConfigurations) {
                this.nodeConfigurations = nodeConfigurations;
                return this;
            }

            boolean populateFromProperties(final String clusterName, final Properties properties, final String prefix, final ClassLoader classLoader, final CommonSubconfiguration.Builder defaultsBuilder) {
                if (! super.populateFromProperties(properties, prefix, classLoader, defaultsBuilder)) {
                    return false;
                }
                if (clusterName == null) {
                    return false;
                }
                setClusterName(clusterName);
                setMaximumAllowedConnectedNodes(getLongValueFromProperties(properties, prefix + "max-allowed-connected-nodes", 1000L));
                final String clusterNodeSelectorClassName = getProperty(properties, prefix + "clusternode.selector", null, true);
                if (clusterNodeSelectorClassName != null) {
                    setClusterNodeSelectorClassName(clusterNodeSelectorClassName);
                    setClusterNodeSelectorSupplier(() ->
                        Class.forName(clusterNodeSelectorClassName, true, classLoader).asSubclass(ClusterNodeSelector.class).getConstructor().newInstance()
                    );
                }

                final HashSet<String> nodeNames = new HashSet<>();
                // the cluster prefix already has a trailing dot
                final String nodePrefix = prefix + "node" + ".";
                final int prefixLen = nodePrefix.length();
                final List<ClusterNodeConfiguration> nodes = new ArrayList<ClusterNodeConfiguration>();
                String nodeName;
                for (String propertyName : properties.stringPropertyNames()) {
                    if (propertyName.startsWith(nodePrefix)) {
                        int idx = propertyName.indexOf('.', prefixLen);
                        if (idx != -1) {
                            nodeName = propertyName.substring(prefixLen, idx);
                        } else {
                            nodeName = propertyName.substring(prefixLen);
                        }
                        if (nodeNames.add(nodeName)) {
                            final ClusterNodeConfiguration.Builder builder = new ClusterNodeConfiguration.Builder();
                            builder.setNodeName(nodeName);
                            if (builder.populateFromProperties(properties, nodePrefix + nodeName + ".", classLoader, this)) {
                                nodes.add(new ClusterNodeConfiguration(builder));
                            }
                        }
                    }
                    // otherwise ignore it
                }
                setNodeConfigurations(nodes);
                return true;
            }
        }
    }

    public static final class ClusterNodeConfiguration extends CommonSubconfiguration {
        private final String nodeName;

        ClusterNodeConfiguration(final Builder builder) {
            super(builder);
            this.nodeName = builder.nodeName;
        }

        public String getNodeName() {
            return nodeName;
        }

        static final class Builder extends CommonSubconfiguration.Builder {
            String nodeName;

            Builder() {
            }

            Builder setNodeName(final String nodeName) {
                this.nodeName = nodeName;
                return this;
            }
        }
    }

    public static final class AuthenticationConfiguration {
        private final String userName;
        private final String password;
        private final String mechanismRealm;
        private final String callbackHandlerClassName;
        private final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier;

        AuthenticationConfiguration(Builder builder) {
            userName = builder.userName;
            password = builder.password;
            mechanismRealm = builder.mechanismRealm;
            callbackHandlerClassName = builder.callbackHandlerClassName;
            callbackHandlerSupplier = builder.callbackHandlerSupplier;
        }

        public String getUserName() {
            return userName;
        }

        public String getPassword() {
            return password;
        }

        public String getMechanismRealm() {
            return mechanismRealm;
        }

        public String getCallbackHandlerClassName() {
            return callbackHandlerClassName;
        }

        public ExceptionSupplier<CallbackHandler, ReflectiveOperationException> getCallbackHandlerSupplier() {
            return callbackHandlerSupplier;
        }

        static final class Builder {
            String userName;
            String password;
            String mechanismRealm;
            String callbackHandlerClassName;
            ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier;

            Builder() {
            }

            Builder setUserName(final String userName) {
                this.userName = userName;
                return this;
            }

            Builder setPassword(final String password) {
                this.password = password;
                return this;
            }

            Builder setMechanismRealm(final String mechanismRealm) {
                this.mechanismRealm = mechanismRealm;
                return this;
            }

            Builder setCallbackHandlerClassName(final String callbackHandlerClassName) {
                this.callbackHandlerClassName = callbackHandlerClassName;
                return this;
            }

            Builder setCallbackHandlerSupplier(final ExceptionSupplier<CallbackHandler, ReflectiveOperationException> callbackHandlerSupplier) {
                this.callbackHandlerSupplier = callbackHandlerSupplier;
                return this;
            }

            boolean populateFromProperties(final Properties properties, final String prefix, final ClassLoader classLoader) {
                final String userName = getProperty(properties, prefix + PROPERTY_KEY_USERNAME, null, true);
                if (userName != null) {
                    setUserName(userName);
                }
                final String mechanismRealm = getProperty(properties, prefix + PROPERTY_KEY_REALM, null, true);
                if (mechanismRealm != null) {
                    setMechanismRealm(mechanismRealm);
                }
                final String finalPassword;
                final String b64Password = getProperty(properties, prefix + PROPERTY_KEY_PASSWORD_BASE64, null, expandPasswords);
                if (b64Password != null) {
                    setPassword(CodePointIterator.ofString(b64Password).base64Decode().asUtf8String().drainToString());
                } else {
                    final String password = getProperty(properties, prefix + PROPERTY_KEY_PASSWORD, null, expandPasswords);
                    if (password != null) {
                        setPassword(password);
                    } else {
                        final String callbackHandlerClassName = getProperty(properties, prefix + PROPERTY_KEY_CALLBACK_HANDLER_CLASS, null, true);
                        if (callbackHandlerClassName != null) {
                            setCallbackHandlerClassName(callbackHandlerClassName);
                            setCallbackHandlerSupplier(() ->
                                Class.forName(callbackHandlerClassName, true, classLoader).asSubclass(CallbackHandler.class).getConstructor().newInstance());
                        } else {
                            if (userName == null) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            }
        }
    }
}
