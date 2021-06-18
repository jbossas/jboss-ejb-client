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

package org.jboss.ejb._private;

import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBMethodLocator;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.RequestSendFailedException;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;
import org.jboss.logging.annotations.Once;
import org.jboss.logging.annotations.Param;
import org.jboss.logging.annotations.Property;
import org.jboss.remoting3.Channel;
import org.wildfly.client.config.ConfigXMLParseException;
import org.wildfly.client.config.ConfigurationXMLStreamReader;

import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;
import javax.naming.CommunicationException;
import javax.naming.Name;
import javax.naming.NamingException;
import javax.transaction.NotSupportedException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InvalidObjectException;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.jboss.logging.Logger.Level.*;

/**
 * Primary logging for the main EJB client API.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@MessageLogger(projectCode = "EJBCLIENT")
public interface Logs extends BasicLogger {
    Logs MAIN = Logger.getMessageLogger(Logs.class, "org.jboss.ejb.client");
    Logs REMOTING = Logger.getMessageLogger(Logs.class, "org.jboss.ejb.client.remoting");
    Logs TXN = Logger.getMessageLogger(Logs.class, "org.jboss.ejb.client.txn");
    Logs INVOCATION = Logger.getMessageLogger(Logs.class, "org.jboss.ejb.client.invocation");

    // Greeting

    @LogMessage(level = INFO)
    @Message("JBoss EJB Client version %s")
    void greeting(String version);

    // Argument errors

    @Message(id = 0, value = "Module name cannot be null or empty")
    IllegalArgumentException emptyModuleName();

    @Message(id = 1, value = "Bean name cannot be null or empty")
    IllegalArgumentException emptyBeanName();

    @Message(id = 2, value = "Bean interface type cannot be null")
    IllegalArgumentException nullViewType();

    @LogMessage(level = INFO)
    @Message(id = 3, value = "Incorrect max-allowed-connected-nodes value %s specified for cluster named %s. Defaulting to %s")
    void incorrectMaxAllowedConnectedNodesValueForCluster(final String value, final String clusterName, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 4, value = "Incorrect connection timeout value %s specified for cluster named %s. Defaulting to %s")
    void incorrectConnectionTimeoutValueForCluster(final String value, final String clusterName, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 5, value = "Incorrect connection timeout value %s specified for node %s in cluster named %s. Defaulting to %s")
    void incorrectConnectionTimeoutValueForNodeInCluster(final String value, final String nodeName, final String clusterName, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 6, value = "No host/port configured for connection named %s. Skipping connection creation")
    void skippingConnectionCreationDueToMissingHostOrPort(final String connectionName);

    @LogMessage(level = INFO)
    @Message(id = 7, value = "Incorrect port value %s specified for connection named %s. Skipping connection creation")
    void skippingConnectionCreationDueToInvalidPortNumber(final String port, final String connectionName);

    @LogMessage(level = INFO)
    @Message(id = 8, value = "Incorrect connection timeout value %s specified for connection named %s. Defaulting to %s")
    void incorrectConnectionTimeoutValueForConnection(final String value, final String connectionName, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 9, value = "Incorrect invocation timeout value %s specified. Defaulting to %s")
    void incorrectInvocationTimeoutValue(final String value, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 10, value = "Incorrect reconnect tasks timeout value %s specified. Defaulting to %s")
    void incorrectReconnectTasksTimeoutValue(final String value, final String fallbackDefaultValue);

    @LogMessage(level = INFO)
    @Message(id = 11, value = "Discarding result for invocation id %s since no waiting context found")
    void discardingInvocationResult(final short invocationId);

    @LogMessage(level = INFO)
    @Message(id = 12, value = "Cannot create a EJB receiver for %s since there was no match for a target destination")
    void cannotCreateEJBReceiverDueToUnknownTarget(final String clusterNode);

//    @Message(id = 13, value = "Successful version handshake completed for receiver context %s on channel %s")
//    @Message(id = 14, value = "Version handshake not completed for receiver context %s. Closing receiver context")

    @LogMessage(level = INFO)
    @Message(id = 15, value = "Initial module availability report for %s wasn't received during the receiver context association")
    void initialModuleAvailabilityReportNotReceived(final EJBReceiver ejbReceiver);

    @LogMessage(level = INFO)
    @Message(id = 16, value = "Channel %s can no longer process messages")
    void channelCanNoLongerProcessMessages(final Channel channel);

    @LogMessage(level = INFO)
    @Message(id = 17, value = "Received server version %d and marshalling strategies %s")
    void receivedServerVersionAndMarshallingStrategies(final int version, final Set<String> marshallingStrategies);

    // @Message(id = 18, value = "%s cannot be null")
    // @Message(id = 19, value = "Node name cannot be null or empty string, while adding a node to cluster named %s")
    // @Message(id = 20, value = "%s cannot be null or empty string")
    // @Message(id = 21, value = "EJB client context selector may not be changed")

    @Message(id = 22, value = "No EJB client context is available")
    IllegalStateException noEJBClientContextAvailable();

    // @Message(id = 23, value = "EJB client interceptor %s is already registered")

    @Message(id = 24, value = "No EJB receiver available for handling destination \"%s\"")
    NoSuchEJBException noEJBReceiverAvailable(final URI locator);

    // @Message(id = 25, value = "No EJB receiver available for handling %s")
    // @Message(id = 26, value = "%s has not been associated with %s")

    @Message(id = 27, value = "No EJBReceiver available for node name %s")
    IllegalStateException noEJBReceiverForNode(final String nodeName);

    @Message(id = 28, value = "No EJB receiver contexts available in cluster %s")
    IllegalStateException noReceiverContextsInCluster(final String clusterName);

    @Message(id = 29, value = "No cluster context available for cluster named %s")
    IllegalStateException noClusterContextAvailable(final String clusterName);

    @Message(id = 30, value = "sendRequest() called during wrong phase")
    IllegalStateException sendRequestCalledDuringWrongPhase();

    @Message(id = 31, value = "No receiver associated with invocation")
    IllegalStateException noReceiverAssociatedWithInvocation();

    @Message(id = 32, value = "Cannot retry a request which hasn't previously been completed")
    IllegalStateException cannotRetryRequest();

    @Message(id = 33, value = "getResult() called during wrong phase")
    IllegalStateException getResultCalledDuringWrongPhase();

    @Message(id = 34, value = "discardResult() called during wrong phase")
    IllegalStateException discardResultCalledDuringWrongPhase();

    @Message(id = 35, value = "Not supported")
    NamingException unsupportedNamingOperation();

    @Message(id = 36, value = "Read only naming context, operation not supported")
    NamingException unsupportedNamingOperationForReadOnlyContext();

    @Message(id = 37, value = "Could not load ejb proxy class %s")
    NamingException couldNotLoadProxyClass(final String viewClassName);

    @Message(id = 38, value = "Transaction enlistment did not yield a transaction ID")
    IllegalStateException txEnlistmentDidNotYieldTxId();

    @Message(id = 39, value = "Cannot enlist transaction")
    IllegalStateException cannotEnlistTx();

    // @Message(id = 40, value = "EJB communication channel %s is not yet ready to receive invocations (perhaps version handshake hasn't been completed), for receiver context %s")

    @Message(id = 41, value = "A session bean does not have a primary key class")
    RuntimeException primaryKeyNotRelevantForSessionBeans();

    @LogMessage(level = WARN)
    @Message(id = 42, value = "Failed to load EJB client configuration file specified in %s system property: %s")
    void failedToFindEjbClientConfigFileSpecifiedBySysProp(String sysPropName, Exception e);

    @Message(id = 43, value = "Error reading EJB client properties file %s")
    RuntimeException failedToReadEjbClientConfigFile(@Cause Exception e, String file);

    @Message(id = 44, value = "No transaction context available")
    IllegalStateException noTxContextAvailable();

    @Message(id = 45, value = "User transactions not supported by this context")
    IllegalStateException userTxNotSupportedByTxContext();

    @Message(id = 46, value = "A transaction is already associated with this thread")
    NotSupportedException txAlreadyAssociatedWithThread();

    @Message(id = 47, value = "A transaction is not associated with this thread")
    IllegalStateException noTxAssociatedWithThread();

    @Message(id = 48, value = "Transaction for this thread is not active")
    IllegalStateException txNotActiveForThread();

    @Message(id = 49, value = "Cannot proceed with invocation since transaction is pinned to node %s which has been excluded from handling invocation for the current invocation context %s")
    IllegalStateException txNodeIsExcludedForInvocation(String nodeName, EJBClientInvocationContext invocationContext);

    @Message(id = 50, value = "Node of the current transaction %s does not accept %s")
    IllegalStateException nodeDoesNotAcceptLocator(String nodeName, EJBLocator<?> locator);

    @Message(id = 51, value = "Cannot proceed with invocation since the locator %s has an affinity on node %s which has been excluded from current invocation context %s")
    IllegalStateException requiredNodeExcludedFromInvocation(EJBLocator<?> locator, String nodeName, EJBClientInvocationContext invocationContext);

    @Message(id = 52, value = "%s for cluster %s is not of type org.jboss.ejb.client.ClusterNodeSelector")
    RuntimeException unexpectedClusterNodeSelectorClassType(Class<?> nodeSelectorClass, String clusterName);

    @Message(id = 53, value = "Could not create the cluster node selector for cluster %s")
    RuntimeException couldNotCreateClusterNodeSelector(@Cause Exception e, String clusterName);

    @Message(id = 54, value = "Cannot specify both a callback handler and a username/password")
    IllegalStateException cannotSpecifyBothCallbackHandlerAndUserPass();

    @Message(id = 55, value = "Could not decode base64 encoded password")
    RuntimeException couldNotDecodeBase64Password(@Cause Exception e);

    @Message(id = 56, value = "Cannot specify both a plain text and base64 encoded password")
    IllegalStateException cannotSpecifyBothPlainTextAndEncodedPassword();

    // @Message(id = 57, value = "%s not of type org.jboss.ejb.client.DeploymentNodeSelector")

    @Message(id = 58, value = "Failed to instantiate deployment node selector class \"%s\"")
    IllegalArgumentException cannotInstantiateDeploymentNodeSelector(String name, @Cause ReflectiveOperationException e);

    @LogMessage(level = WARN)
    @Message(id = 59, value = "Could not send a message over remoting channel, to cancel invocation for invocation id %s")
    void failedToSendInvocationCancellationMessage(short invocationId, @Cause Exception e);

    @Message(id = 60, value = "Failed to create scoped EJB client context")
    RuntimeException failedToCreateScopedEjbClientContext(@Cause Exception e);

    @LogMessage(level = WARN)
    @Message(id = 61, value = "Cannot send a transaction recovery message to the server since the protocol version of EJBReceiver %s doesn't support it")
    void transactionRecoveryMessageNotSupported(EJBReceiver receiver);

    @Message(id = 62, value = "Failed to look up \"%s\"")
    CommunicationException lookupFailed(@Property Name resolvedName, Name name, @Cause Exception e);

    @Message(id = 63, value = "EJB proxy is already stateful")
    IllegalArgumentException ejbIsAlreadyStateful();

    @LogMessage(level = Logger.Level.INFO)
    @Message(id = 64, value = "org.jboss.ejb.client.naming.ejb.ejbURLContextFactory is deprecated; new applications should use org.wildfly.naming.client.WildFlyInitialContextFactory instead")
    void ejbURLContextFactoryDeprecated();

    @Message(id = 65, value = "Null session was created for \"%s\", affinity %s, identifier %s")
    CommunicationException nullSessionCreated(@Property Name resolvedName, Name name, Affinity affinity, EJBIdentifier identifier);

    @Message(id = 66, value = "Operation interrupted")
    EJBException operationInterrupted();

    @Message(id = 67, value = "Cannot convert %s to stateful")
    IllegalArgumentException cannotConvertToStateful(EJBLocator<?> locator);

    @Message(id = 68, value = "Failed to instantiate callback handler class \"%s\"")
    IllegalArgumentException cannotInstantiateCallbackHandler(String name, @Cause ReflectiveOperationException e);

    @Once
    @LogMessage
    @Message(id = 69, value = "Using legacy jboss-ejb-client.properties security configuration")
    void legacyEJBPropertiesSecurityConfigurationInUse();

    @Once
    @LogMessage
    @Message(id = 70, value = "Using legacy jboss-ejb-client.properties Remoting configuration")
    void legacyEJBPropertiesRemotingConfigurationInUse();

    @Once
    @LogMessage
    @Message(id = 71, value = "Using legacy jboss-ejb-client.properties discovery configuration")
    void legacyEJBPropertiesDiscoveryConfigurationInUse();

    @Once
    @LogMessage
    @Message(id = 72, value = "Using legacy jboss-ejb-client.properties EJB client configuration")
    void legacyEJBPropertiesEJBConfigurationInUse();

    @Message(id = 73, value = "Failed to construct Remoting endpoint")
    IllegalStateException failedToConstructEndpoint(@Cause IOException e);

    @Message(id = 74, value = "Configured selector \"%s\" returned null")
    IllegalStateException selectorReturnedNull(Object selector);

    @Message(id = 75, value = "No transport provider available for URI scheme %2$s for locator %1$s")
    NoSuchEJBException noTransportProvider(EJBLocator<?> locator, String scheme);

    @Message(id = 76, value = "Configured selector \"%s\" returned unknown node \"%s\"")
    IllegalStateException selectorReturnedUnknownNode(Object selector, String nodeName);

    @Message(id = 77, value = "EJB receiver \"%s\" returned a null session ID for EJB \"%s\"")
    IllegalArgumentException nullSessionID(EJBReceiver receiver, StatelessEJBLocator<?> statelessLocator);

    @Message(id = 78, value = "EJB receiver \"%s\" returned a stateful locator with the wrong view type (expected %s, but actual was %s)")
    IllegalArgumentException viewTypeMismatch(EJBReceiver receiver, Class<?> expectedType, Class<?> actualType);

    @Message(id = 79, value = "Unable to discover destination for request for EJB %s")
    NoSuchEJBException noDestinationEstablished(EJBLocator<?> locator);

    @Message(id = 80, value = "Request not sent")
    IllegalStateException requestNotSent();

    @Message(id = 81, value = "Failed to instantiate cluster node selector class \"%s\"")
    IllegalArgumentException cannotInstantiateClustertNodeSelector(String name, @Cause ReflectiveOperationException e);

    @Message(id = 82, value = "Cannot outflow the remote transaction \"%s\" as its timeout elapsed")
    SystemException outflowTransactionTimeoutElapsed(Transaction transaction);

    // Proxy API errors

    @Message(id = 100, value = "Object '%s' is not a valid proxy object")
    IllegalArgumentException unknownProxy(Object proxy);

    @Message(id = 101, value = "Proxy object '%s' was not generated by %s")
    IllegalArgumentException proxyNotOurs(Object proxy, String className);

    @Message(id = 102, value = "No asynchronous operation in progress")
    IllegalStateException noAsyncInProgress();

    // Configuration problems

    @Message(id = 200, value = "Cannot load from a module when jboss-modules is not available")
    ConfigXMLParseException noJBossModules(@Param ConfigurationXMLStreamReader streamReader);

    // Interceptor problems

    @Message(id = 300, value = "No valid no-argument constructor on interceptor %s")
    IllegalArgumentException noInterceptorConstructor(Class<?> type);

    @Message(id = 301, value = "Constructor is not accessible on interceptor %s")
    IllegalArgumentException interceptorConstructorNotAccessible(Class<?> type);

    @Message(id = 302, value = "Construction of interceptor %s failed")
    IllegalStateException interceptorConstructorFailed(Class<?> type, @Cause Throwable cause);

    // Invocation result exceptions

    @Message(id = 400, value = "Remote invocation failed due to an exception")
    ExecutionException remoteInvFailed(@Cause Throwable cause);

    @Message(id = 401, value = "Result was discarded (one-way invocation)")
    IllegalStateException oneWayInvocation();

    @Message(id = 402, value = "Remote invocation request was cancelled")
    CancellationException requestCancelled();

    @Message(id = 403, value = "Timed out")
    TimeoutException timedOut();

    @Message(id = 408, value = "Inflowed transaction is no longer active")
    SystemException transactionNoLongerActive();

    // @Message(id = 404, value = "Operation not allowed since this EJB client context %s has been closed")
    // @Message(id = 405, value = "An EJB client context is already registered for EJB client context identifier %s")
    // @Message(id = 406, value = "Unexpected exception when discarding invocation result")
    // @message(id = 407, value = "Issue regarding unmarshalling of EJB parameters (possible Out of Memory issue).")

    @Message(id = 409, value = "No more destinations are available")
    RequestSendFailedException noMoreDestinations();

    // Server exceptions and messages

    @Message(id = 500, value = "Protocol error: mismatched method location")
    InvalidObjectException mismatchedMethodLocation();

    @LogMessage(level = DEBUG)
    @Message(id = 501, value = "Protocol error: invalid message ID %02x received")
    void invalidMessageReceived(int code);

    @Message(id = 502, value = "Protocol error: invalid transaction type %02x received")
    IOException invalidTransactionType(int type);

    @Message(id = 503, value = "Protocol error: unable to inflow remote transaction")
    IOException unableToInflowTxn(@Cause Exception e);

    @Message(id = 504, value = "Server error: no session was created")
    IllegalStateException noSessionCreated();

    @Message(id = 505, value = "No remote transport is present on the current EJB client context")
    IllegalStateException noRemoteTransportOnEJBContext();

    @Message(id = 506, value = "Server error (invalid view): %s")
    EJBException invalidViewTypeForInvocation(String serverMessage);

    @Message(id = 507, value = "Internal server error occurred while processing a transaction")
    SystemException internalSystemErrorWithTx(@Cause Throwable t);

    @LogMessage(level = ERROR)
    @Message(id = 508, value = "Failed to execute Runnable %s")
    void taskFailed(Runnable runnable, @Cause Throwable t);

    @LogMessage(level = ERROR)
    @Message(id = 509, value = "Unexpected exception processing EJB request")
    void unexpectedException(@Cause Throwable t);

    @Message(id = 510, value = "Failed to configure SSL context")
    IOException failedToConfigureSslContext(@Cause Throwable cause);

    @Message(id = 511, value = "Cannot automatically convert stateless EJB to stateful with this protocol version")
    IllegalArgumentException cannotAddSessionID();

    @Message(id = 512, value = "Server error (remote EJB is not stateful): %s")
    EJBException ejbNotStateful(String serverMessage);

    @LogMessage(level = ERROR)
    @Message(id = 513, value = "Exception occurred when trying to close the transport provider")
    void exceptionDuringTransportProviderClose(@Cause Exception e);

    @LogMessage(level = INFO)
    @Message(id = 514, value = "No URI configured for HTTP connection named %s. Skipping connection creation")
    void skippingHttpConnectionCreationDueToMissingUri(final String name);

    @LogMessage(level = INFO)
    @Message(id = 515, value = "HTTP connection was configured with invalid URI: %s .")
    void skippingHttpConnectionCreationDueToInvalidUri(final String uri);

    @Message(id = 516, value = "Exception resolving class %s for unmarshalling; it has either been blacklisted or not whitelisted")
    InvalidClassException cannotResolveFilteredClass(String clazz);

    @LogMessage(level = WARN)
    @Message(id = 517, value = "Exception occurred when writing EJB transaction response to invocation %s over channel %s")
    void ioExceptionOnTransactionResponseWrite(int invId, Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 518, value = "Exception occurred when writing EJB transaction recovery response for invocation %s over channel %s")
    void ioExceptionOnTransactionRecoveryResponseWrite(int invId, Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 519, value = "Exception occurred when writing EJB response to invocation %s over channel %s")
    void ioExceptionOnEJBResponseWrite(int invId, Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 520, value = "Exception occurred when writing EJB session open response to invocation %s over channel %s")
    void ioExceptionOnEJBSessionOpenResponseWrite(int invId, Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 521, value = "Exception occurred when writing proceed async response to invocation %s over channel %s")
    void ioExceptionOnProceedAsyncResponseWrite(int invId, Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 522, value = "Exception occurred when writing EJB cluster message to channel %s")
    void ioExceptionOnEJBClusterMessageWrite(Channel channel, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 523, value = "Exception occurred when writing module availability message, closing channel %s")
    void ioExceptionOnModuleAvailabilityWrite(Channel channel, @Cause IOException e);

    // Remote messages; no ID for brevity but should be translated

    @Message(value = "No such EJB: %s")
    String remoteMessageNoSuchEJB(EJBIdentifier ejbIdentifier);

    @Message(value = "EJB is not stateful: %s")
    String remoteMessageEJBNotStateful(EJBIdentifier ejbIdentifier);

    @Message(value = "No such EJB method %s found on %s")
    String remoteMessageNoSuchMethod(EJBMethodLocator methodLocator, EJBIdentifier ejbIdentifier);

    @Message(value = "Session is not active for invocation of method %s on %s")
    String remoteMessageSessionNotActive(EJBMethodLocator methodLocator, EJBIdentifier locator);

    @Message(value = "EJB view is not remote: %s")
    String remoteMessageBadViewType(EJBIdentifier ejbIdentifier);

    @Message(value = "Context data under org.jboss.private.data was not of type Set<String>")
    IllegalStateException returnedContextDataKeyOfWrongType();
}
