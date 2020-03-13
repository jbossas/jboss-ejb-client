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
package org.jboss.ejb.client.test;

import org.jboss.ejb.client.legacy.JBossEJBProperties;
import org.jboss.ejb.client.legacy.JBossEJBProperties.AuthenticationConfiguration;
import org.jboss.ejb.client.legacy.JBossEJBProperties.ConnectionConfiguration;
import org.jboss.ejb.client.legacy.JBossEJBProperties.ClusterConfiguration;
import org.jboss.ejb.client.legacy.JBossEJBProperties.ClusterNodeConfiguration;
import org.jboss.logging.Logger;
import org.jboss.remoting3.RemotingOptions;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Sequence;

import java.util.List;
import java.util.Map;

/**
 * Tests basic function of JBossEJBProperties class.
 *
 * Reads in a legacy configuration file with all possible options specified.
 * Some variation in how passwords are supplied.
 *
 * @author <a href="mailto:rachmato@redhat.com">Richard Achmatowicz</a>
 */
public class JBossEJBPropertiesTestCase {

    private static final Logger logger = Logger.getLogger(JBossEJBPropertiesTestCase.class);
    private static final String PROPERTIES_FILE = "complete-jboss-ejb-client.properties";

    /**
     * Do any general setup here
     * @throws Exception
     */
    @BeforeClass
    public static void beforeClass() throws Exception {
    }

    /**
     * Do any test specific setup here
     */
    @Before
    public void beforeTest() throws Exception {

    }

    /**
     * Test some basic stuff.
     */
    @Test
    public void testLegacyProperties() throws Exception {
        // call with properties files captured from different origins
        JBossEJBProperties ejbProperties = JBossEJBProperties.fromClassPath(JBossEJBProperties.class.getClassLoader(), PROPERTIES_FILE);
        testLegacyPropertiesFunctionality(ejbProperties);
    }


    private void testLegacyPropertiesFunctionality(JBossEJBProperties properties) {
        logger.info("Testing JBossEJBProperties functionality");

        final OptionMap endpointCreateOptionMap = OptionMap.builder().set(Options.SASL_POLICY_NOANONYMOUS, false).getMap();
        final OptionMap remoteConnectionProviderOptionMap = OptionMap.builder().set(Options.SSL_ENABLED, false).getMap();
        final OptionMap connectionConnectOptionMap = OptionMap.builder().set(Options.SASL_POLICY_NOANONYMOUS, false).set(RemotingOptions.HEARTBEAT_INTERVAL, 5000)
                .set(Options.READ_TIMEOUT, 10000).set(Options.WRITE_TIMEOUT, 10000).set(Options.KEEP_ALIVE, true)
                .getMap();
        final OptionMap connectionChannelOptionMap = OptionMap.builder().set(Options.SASL_POLICY_NOANONYMOUS, false).getMap();

        final OptionMap clusterConnectOptionMap = OptionMap.builder().set(Options.SASL_POLICY_NOANONYMOUS, false).getMap();
        final OptionMap clusterChannelOptionMap = OptionMap.builder().set(Options.SASL_POLICY_NOANONYMOUS, false).getMap();

        final OptionMap clusterNodeConnectOptionMap = OptionMap.builder().set(Options.SASL_MECHANISMS, Sequence.of(new String("A"), new String("B"), new String ("C"))).getMap();
        final OptionMap clusterNodeChannelOptionMap = OptionMap.builder().set(Options.SASL_DISALLOWED_MECHANISMS, Sequence.of(new String("D"), new String("E"))).getMap();

        // endpoints
        Assert.assertEquals("Bad endpoint name value", "endpoint.name", properties.getEndpointName());

        // check endpoint common sub-configuration
        Assert.assertEquals("Bad endpoint create options value", endpointCreateOptionMap, properties.getEndpointCreationOptions());
        Assert.assertEquals("Bad endpoint invocation timeout value", 1, properties.getInvocationTimeout());
        Assert.assertEquals("Bad transaction propagation suppression value", true, properties.isSuppressTxPropagation());
        Assert.assertEquals("Bad endpoint callback handler value", null, properties.getDefaultCallbackHandlerClassName());

        // check endpoint authentication
        AuthenticationConfiguration endpointAuthConfig = properties.getAuthenticationConfiguration();
        checkAuthConfiguration("endpoint", endpointAuthConfig, "endpoint.user", null, "endpoint.password", null, "endpoint.realm");

        // check connections
        List<JBossEJBProperties.ConnectionConfiguration> connectionList = properties.getConnectionList();
        Assert.assertEquals("Bad connection list size", 2, connectionList.size());
        Assert.assertEquals("Bad remote connection provider create options value", remoteConnectionProviderOptionMap, properties.getRemoteConnectionProviderCreationOptions());

        // unfortunately connections don't have a name that we can retrieve
        for (ConnectionConfiguration connection : connectionList) {
            if (connection.getPort() == 6999) {
                Assert.assertEquals("Bad protocol value", "remote", connection.getProtocol());
                Assert.assertEquals("Bad host value", "localhost", connection.getHost());
                Assert.assertEquals("Bad port value", 6999, connection.getPort());

                // check connection common subconfiguration
                checkCommonSubConfiguration("connection",
                        connection.getConnectionOptions(), connection.getChannelOptions(),connection.getConnectionTimeout(), connection.getCallbackHandlerClassName(), connection.isConnectEagerly(),
                        connectionConnectOptionMap, connectionChannelOptionMap, 0, null, true);

                // check connection authentication
                AuthenticationConfiguration connectionAuthConfig = connection.getAuthenticationConfiguration();
                checkAuthConfiguration("connection", connectionAuthConfig, "connection.one.user", null, "connection.one.password", null, "connection.one.realm");
            } else {
                Assert.assertEquals("Bad protocol value", "remote", connection.getProtocol());
                Assert.assertEquals("Bad host value", "localhost", connection.getHost());
                Assert.assertEquals("Bad port value", 7099, connection.getPort());

                // check connection common subconfiguration
                checkCommonSubConfiguration("connection",
                        connection.getConnectionOptions(), connection.getChannelOptions(),connection.getConnectionTimeout(), connection.getCallbackHandlerClassName(), connection.isConnectEagerly(),
                        connectionConnectOptionMap, connectionChannelOptionMap, 0, null, true);

                // check connection authentication
                AuthenticationConfiguration connectionAuthConfig = connection.getAuthenticationConfiguration();
                checkAuthConfiguration("connection", connectionAuthConfig, "connection.two.user", null, "connection.two.password", null, "connection.two.realm");
            }
        }

        // check clusters
        Map<String, ClusterConfiguration> clusterMap = properties.getClusterConfigurations();
        Assert.assertEquals("Bad cluster size", 1, clusterMap.size());
        ClusterConfiguration cluster = clusterMap.get("ejb");
        Assert.assertTrue("Missing cluster configuration!",cluster != null);

        // cluster general properties
        Assert.assertEquals("Bad cluster name!", "ejb", cluster.getClusterName());
        Assert.assertEquals("Bad max allowed connected nodes!", 1000, cluster.getMaximumAllowedConnectedNodes());
        Assert.assertEquals("Bad cluster node selector class name!", "cluster.node.selector", cluster.getClusterNodeSelectorClassName());

        // check cluster common sub-configuration
        checkCommonSubConfiguration("cluster",
                cluster.getConnectionOptions(), cluster.getChannelOptions(), cluster.getConnectionTimeout(), cluster.getCallbackHandlerClassName(), cluster.isConnectEagerly(),
                clusterConnectOptionMap, clusterChannelOptionMap, 998, null, true);

        // check cluster authentication
        AuthenticationConfiguration clusterAuthConfig = cluster.getAuthenticationConfiguration();
        checkAuthConfiguration("cluster", clusterAuthConfig, "cluster.user", null, "cluster.password", null, "cluster.realm");

        // check cluster nodes
        List<ClusterNodeConfiguration> clusterNodeConfigurations = cluster.getNodeConfigurations();
        Assert.assertEquals("Bad node configurations size", 2, clusterNodeConfigurations.size());
        for (ClusterNodeConfiguration node: clusterNodeConfigurations) {
            Assert.assertNotNull("Bad node name!", node.getNodeName());

            if (node.getNodeName().equals("node1")) {

                // check cluster node common sub-configuration
                checkCommonSubConfiguration("cluster node",
                        node.getConnectionOptions(), node.getChannelOptions(), node.getConnectionTimeout(), node.getCallbackHandlerClassName(), node.isConnectEagerly(),
                        clusterNodeConnectOptionMap, clusterNodeChannelOptionMap, 999, "node1.callback.handler.class", true);

                // check cluster node authentication
                AuthenticationConfiguration nodeAuthConfig = node.getAuthenticationConfiguration();
                checkAuthConfiguration("cluster node", nodeAuthConfig, "node1.user", null, null, "node1.callback.handler.class", "node1.realm");
            } else {

                // check cluster node common sub-configuration
                checkCommonSubConfiguration("cluster node",
                        node.getConnectionOptions(), node.getChannelOptions(), node.getConnectionTimeout(), node.getCallbackHandlerClassName(), node.isConnectEagerly(),
                        clusterNodeConnectOptionMap, clusterNodeChannelOptionMap, 999, null, true);

                // check cluster node authentication
                AuthenticationConfiguration nodeAuthConfig = node.getAuthenticationConfiguration();
                checkAuthConfiguration("cluster node", nodeAuthConfig, "node2.user", null, "node2.password", null, "node2.realm");
            }
        }
    }

    /*
     * Checks the common sub-configuration elements of a EJB client component (endpoint, connection, cluster, cluster node)
     */
    private void checkCommonSubConfiguration(String componentName, OptionMap connectionOptions, OptionMap channelOptions, long connectionTimeout, String callbackHandlerClassName, boolean connectEagerly, OptionMap expectedConnectionOptions, OptionMap expectedChannelOptions, int expectedConnectionTimeout, String expectedCallbackHandlerClassName, boolean expectedConnectEagerly) {
        if (expectedConnectionOptions != null)
            Assert.assertEquals("Bad " + componentName + " connection options value", expectedConnectionOptions.size(), connectionOptions.size());
        if (expectedChannelOptions != null)
            Assert.assertEquals("Bad " + componentName + " channel options value", expectedChannelOptions, channelOptions);
        if (expectedConnectionTimeout != 0)
            Assert.assertEquals("Bad " + componentName + " connection timeout value", expectedConnectionTimeout, connectionTimeout);
        if (expectedCallbackHandlerClassName != null)
            Assert.assertEquals("Bad " + componentName + " callback handler class name value", expectedCallbackHandlerClassName, callbackHandlerClassName);
        // TODO Assert.assertEquals("Bad callback handler supplier value", "connection.one.callback.handler.supplier", authConfig.getCallbackHandlerSupplier());
    }

    /*
     * Checks the authentication elements of a EJB client component (endpoint, connection, cluster, cluster node)
     *
     * NOTE: only one of passwordBase64, password, or callback handler classname should be non-null
     * These are mutually exclusive options for specifying a password
     */
    private void checkAuthConfiguration(String componentName, AuthenticationConfiguration authConfig, String userName, String passwordBase64, String password, String callbackHandlerClassName, String realm) {
        if (userName != null)
            Assert.assertEquals("Bad " + componentName + " username value", userName, authConfig.getUserName());

        Assert.assertTrue("Only one of passwordBase64, password and callback handler class name can be defined",
                (passwordBase64 != null && password == null && callbackHandlerClassName == null) ||
                (passwordBase64 == null && password != null && callbackHandlerClassName == null) ||
                (passwordBase64 == null && password == null && callbackHandlerClassName != null) );

        if (passwordBase64 != null)
            Assert.assertEquals("Bad " + componentName + " password value", passwordBase64, authConfig.getPassword());
        if (password != null)
            Assert.assertEquals("Bad " + componentName + " password value", password, authConfig.getPassword());
        if (callbackHandlerClassName != null)
            Assert.assertEquals("Bad " + componentName + " callback handler class value", callbackHandlerClassName, authConfig.getCallbackHandlerClassName());
        // TODO Assert.assertEquals("Bad callback handler supplier value", "connection.one.callback.handler.supplier", authConfig.getCallbackHandlerSupplier());
        if (realm != null)
            Assert.assertEquals("Bad " + componentName + " realm value", realm, authConfig.getMechanismRealm());
    }


    /**
     * Do any test-specific tear down here.
     */
    @After
    public void afterTest() {
    }

    /**
     * Do any general tear down here.
     */
    @AfterClass
    public static void afterClass() {
    }

    public static void main(String[] args) throws Exception {
        JBossEJBPropertiesTestCase props = new JBossEJBPropertiesTestCase();
        props.testLegacyProperties();
    }

}
