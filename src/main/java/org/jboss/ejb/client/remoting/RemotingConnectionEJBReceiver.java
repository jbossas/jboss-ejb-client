/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client.remoting;

import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.jboss.ejb.client.AttachmentKeys;
import org.jboss.ejb.client.EJBClientConfiguration;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverContext;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.Logs;
import org.jboss.ejb.client.RequestSendFailedException;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.TransactionID;
import org.jboss.ejb.client.annotation.CompressionHint;
import org.jboss.logging.Logger;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.MessageOutputStream;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

/**
 * A {@link EJBReceiver} which uses JBoss Remoting to communicate with the server for EJB invocations
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class RemotingConnectionEJBReceiver extends EJBReceiver {

    private static final Logger logger = Logger.getLogger(RemotingConnectionEJBReceiver.class);

    private static final String EJB_CHANNEL_NAME = "jboss.ejb";
    public static final int INITIAL_MODULE_WAIT_TIME;

    static {
        String s = SecurityActions.getSystemProperty("org.jboss.ejb.initial-module-wait-time");
        //default value
        int i = 30;
        try {
            i = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            //default value will be used
        }
        INITIAL_MODULE_WAIT_TIME = i;
    }

    private final Connection connection;

    private final Map<EJBReceiverContext, ChannelAssociation> channelAssociations = new IdentityHashMap<EJBReceiverContext, ChannelAssociation>();

    /**
     * A latch which will be used to wait for the initial module availability report from the server
     * after the version handshake between the server and the client is successfully completed.
     */
    private final Map<EJBReceiverContext, CountDownLatch> moduleAvailabilityReportLatches = new IdentityHashMap<EJBReceiverContext, CountDownLatch>();

    private final MarshallerFactory marshallerFactory;

    private final ReconnectHandler reconnectHandler;
    private final OptionMap channelCreationOptions;

    /**
     * Construct a new instance.
     *
     * @param connection the connection to associate with
     */
    public RemotingConnectionEJBReceiver(final Connection connection) {
        this(connection, null, OptionMap.EMPTY);
    }


    /**
     * Construct a new instance.
     *
     * @param connection             the connection to associate with
     * @param reconnectHandler       The {@link ReconnectHandler} to use when the connection breaks
     * @param channelCreationOptions The {@link OptionMap options} to be used during channel creation
     */
    public RemotingConnectionEJBReceiver(final Connection connection, final ReconnectHandler reconnectHandler, final OptionMap channelCreationOptions) {
        super(connection.getRemoteEndpointName());
        this.connection = connection;
        this.reconnectHandler = reconnectHandler;
        this.channelCreationOptions = channelCreationOptions == null ? OptionMap.EMPTY : channelCreationOptions;

        this.marshallerFactory = Marshalling.getProvidedMarshallerFactory("river");
        if (this.marshallerFactory == null) {
            throw new RuntimeException("Could not find a marshaller factory for 'river' marshalling strategy");
        }
    }

    @Override
    public void associate(final EJBReceiverContext context) {
        // a latch for waiting a version handshake
        final CountDownLatch versionHandshakeLatch = new CountDownLatch(1);
        // setup a latch which will be used for waiting initial module availability report from the server
        final CountDownLatch initialModuleAvailabilityLatch = new CountDownLatch(1);
        synchronized (this.moduleAvailabilityReportLatches) {
            this.moduleAvailabilityReportLatches.put(context, initialModuleAvailabilityLatch);
        }

        final VersionReceiver versionReceiver = new VersionReceiver(versionHandshakeLatch);
        final IoFuture<Channel> futureChannel = connection.openChannel(EJB_CHANNEL_NAME, this.channelCreationOptions);
        futureChannel.addNotifier(new IoFuture.HandlingNotifier<Channel, EJBReceiverContext>() {
            public void handleCancelled(final EJBReceiverContext context) {
                logger.debug("Channel open requested cancelled for context " + context);
                context.close();
            }

            public void handleFailed(final IOException exception, final EJBReceiverContext context) {
                logger.error("Failed to open channel for context " + context, exception);
                context.close();
            }

            public void handleDone(final Channel channel, final EJBReceiverContext context) {
                channel.addCloseHandler(new CloseHandler<Channel>() {
                    public void handleClose(final Channel closed, final IOException exception) {
                        logger.debug("Closing channel" + closed, exception);
                        context.close();
                    }
                });
                logger.debug("Channel " + channel + " opened for context " + context + " Waiting for version handshake message from server");
                // receive version message from server
                channel.receiveMessage(versionReceiver);
            }
        }, context);

        boolean successfulHandshake = false;
        try {
            // wait for the handshake to complete
            // The time to "wait" for the handshake to complete is the same as the invocation timeout
            // set in this EJB client context's configuration, since a handshake is essentially a request
            // followed for a wait for the response
            final EJBClientConfiguration ejbClientConfiguration = context.getClientContext().getEJBClientConfiguration();
            final long versionHandshakeTimeoutInMillis;
            if (ejbClientConfiguration == null || ejbClientConfiguration.getInvocationTimeout() <= 0) {
                // default to 5000 milli sec
                versionHandshakeTimeoutInMillis = 5000;
            } else {
                versionHandshakeTimeoutInMillis = ejbClientConfiguration.getInvocationTimeout();
            }
            successfulHandshake = versionHandshakeLatch.await(versionHandshakeTimeoutInMillis, TimeUnit.MILLISECONDS);
            if (successfulHandshake) {
                final Channel compatibleChannel = versionReceiver.getCompatibleChannel();
                final ChannelAssociation channelAssociation = new ChannelAssociation(this, context, compatibleChannel, (byte) versionReceiver.getNegotiatedProtocolVersion(), this.marshallerFactory, this.reconnectHandler);
                synchronized (this.channelAssociations) {
                    this.channelAssociations.put(context, channelAssociation);
                }
                Logs.REMOTING.successfulVersionHandshake(context, compatibleChannel);
            } else {
                // no version handshake done. close the context
                Logs.REMOTING.versionHandshakeNotCompleted(context);
                context.close();
                // register reconnect handler for retries due to e.g. timeouts
                if (this.reconnectHandler != null) {
                    //only add the reconnect handler if the version handshake did not fail due to an incompatibility
                    // (latch is not being counted down on failure)
                    if (!versionReceiver.failedCompatibility()) {
                        logger.debug("Adding reconnect handler to client context " + context.getClientContext());
                        context.getClientContext().registerReconnectHandler(this.reconnectHandler);
                    }
                }
            }
        } catch (InterruptedException e) {
            context.close();
        }

        if (successfulHandshake) {
            // Now that the version handshake has been completed, let's await the initial module report
            // from the server. This initial wait is necessary to ensure that any immediate invocation on the receiver
            // doesn't fail due to non-availability of the module report (which effectively means this receiver won't
            // know whether it can handle an invocation on a appname/modulename/distinctname combination
            try {
                final boolean initialReportAvailable = initialModuleAvailabilityLatch.await(INITIAL_MODULE_WAIT_TIME, TimeUnit.SECONDS);
                if (!initialReportAvailable) {
                    // let's log a message and just return back. Don't close the context since it's *not* an error
                    // that the module report wasn't available in that amount of time.
                    Logs.REMOTING.initialModuleAvailabilityReportNotReceived(this);
                }
            } catch (InterruptedException e) {
                logger.debug("Caught InterruptedException while waiting for initial module availability report for " + this, e);
            }
        }
    }

    @Override
    public void disassociate(final EJBReceiverContext context) {
        ChannelAssociation channelAssociation = null;
        synchronized (this.channelAssociations) {
            channelAssociation = this.channelAssociations.remove(context);
        }

        // close the channel that is associated
        if(channelAssociation != null) {
            try {
                channelAssociation.getChannel().close();
            } catch(IOException e) {
                logger.warn("Caught IOException when trying to close channel: " + channelAssociation.getChannel(), e);
            }
        }
    }

    @Override
    public void processInvocation(final EJBClientInvocationContext clientInvocationContext, final EJBReceiverInvocationContext ejbReceiverInvocationContext) throws Exception {
        if(System.getSecurityManager() == null) {
            processInvocationInternal(clientInvocationContext, ejbReceiverInvocationContext);
        } else {
            try {
                AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                    @Override
                    public Object run() throws Exception {
                        processInvocationInternal(clientInvocationContext, ejbReceiverInvocationContext);
                        return null;
                    }
                });
            } catch (PrivilegedActionException e) {
                throw e.getException();
            }
        }
    }

    private void processInvocationInternal(EJBClientInvocationContext clientInvocationContext, EJBReceiverInvocationContext ejbReceiverInvocationContext) throws Exception {
        ChannelAssociation channelAssociation = null;
        DataOutputStream dataOutputStream = null;
        MessageOutputStream messageOutputStream = null;
        Throwable requestSendFailureCause = null;
        try {
            channelAssociation = this.requireChannelAssociation(ejbReceiverInvocationContext.getEjbReceiverContext());
            final MethodInvocationMessageWriter messageWriter = new MethodInvocationMessageWriter(this.marshallerFactory);
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            dataOutputStream = wrapMessageOutputStream(clientInvocationContext, channelAssociation, messageOutputStream);
            final short invocationId = channelAssociation.getNextInvocationId();
            channelAssociation.receiveResponse(invocationId, ejbReceiverInvocationContext);
            messageWriter.writeMessage(dataOutputStream, invocationId, clientInvocationContext);
        } catch (Throwable t) {
            requestSendFailureCause = t;
        } finally {
            try {
                if (dataOutputStream != null) {
                    dataOutputStream.close();
                }
                if (channelAssociation != null && messageOutputStream != null) {
                    channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                }
            } finally {
                if (requestSendFailureCause != null) {
                    // let the caller know that the request *wasn't* sent successfully
                    throw new RequestSendFailedException(ejbReceiverInvocationContext.getNodeName(), requestSendFailureCause.getMessage(), requestSendFailureCause);
                }
            }
        }
    }

    @Override
    protected <T> StatefulEJBLocator<T> openSession(final EJBReceiverContext receiverContext, final Class<T> viewType, final String appName, final String moduleName, final String distinctName, final String beanName) throws IllegalArgumentException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final SessionOpenRequestWriter sessionOpenRequestWriter = new SessionOpenRequestWriter();
        final DataOutputStream dataOutputStream;
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
        } catch (Exception ioe) {
            throw new RuntimeException(ioe);
        }
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer;
        try {
            final short invocationId = channelAssociation.getNextInvocationId();
            futureResultProducer = channelAssociation.enrollForResult(invocationId);
            sessionOpenRequestWriter.writeMessage(dataOutputStream, invocationId, appName, moduleName, distinctName, beanName);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            } catch (IOException ioe) {
                throw new RuntimeException(ioe);
            }
        }
        final EJBReceiverInvocationContext.ResultProducer resultProducer;
        final EJBClientConfiguration ejbClientConfiguration = receiverContext.getClientContext().getEJBClientConfiguration();
        final long invocationTimeout = ejbClientConfiguration == null ? 0 : ejbClientConfiguration.getInvocationTimeout();
        try {
            if (invocationTimeout <= 0) {
                resultProducer = futureResultProducer.get();
            } else {
                resultProducer = futureResultProducer.get(invocationTimeout, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        final SessionOpenResponseHandler.SessionOpenResponse sessionOpenResponse;
        try {
            sessionOpenResponse = (SessionOpenResponseHandler.SessionOpenResponse) resultProducer.getResult();
        } catch (RuntimeException rte) {
            throw rte;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new StatefulEJBLocator<T>(viewType, appName, moduleName, beanName, distinctName, sessionOpenResponse.getSessionID(), sessionOpenResponse.getAffinity(), this.getNodeName());
    }

    @Override
    public boolean exists(final String appName, final String moduleName, final String distinctName, final String beanName) {
        // TODO: Implement
        logger.warn("Not yet implemented RemotingConnectionEJBReceiver#verify");
        return false;
    }

    @Override
    protected void sendCommit(final EJBReceiverContext receiverContext, final TransactionID transactionID, final boolean onePhase) throws XAException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final short invocationId = channelAssociation.getNextInvocationId();
        final MessageOutputStream messageOutputStream;

        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionCommitWriter();
            try {
                // write the tx commit message
                transactionMessageWriter.writeTxCommit(dataOutputStream, invocationId, transactionID, onePhase);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
        try {
            // wait for the result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            resultProducer.getResult();
        } catch (XAException xae) {
            throw xae;
        } catch (RuntimeException re) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(re);
            throw xae;
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    protected void sendRollback(EJBReceiverContext receiverContext, TransactionID transactionID) throws XAException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final short invocationId = channelAssociation.getNextInvocationId();
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionRollbackWriter();
            try {
                // write the tx rollback message
                transactionMessageWriter.writeTxRollback(dataOutputStream, invocationId, transactionID);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
        try {
            // wait for the result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            resultProducer.getResult();
        } catch (XAException xae) {
            throw xae;
        } catch (RuntimeException re) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(re);
            throw xae;
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    protected int sendPrepare(final EJBReceiverContext receiverContext, final TransactionID transactionID) throws XAException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final short invocationId = channelAssociation.getNextInvocationId();
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionPrepareWriter();
            try {
                // write the tx prepare message
                transactionMessageWriter.writeTxPrepare(dataOutputStream, invocationId, transactionID);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
        try {
            // wait for result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            final Object result = resultProducer.getResult();
            if (result instanceof Integer) {
                return (Integer) result;
            }
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(new RuntimeException("Unexpected result for transaction prepare: " + result));
            throw xae;
        } catch (XAException xae) {
            throw xae;
        } catch (RuntimeException re) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(re);
            throw xae;
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    protected void sendForget(final EJBReceiverContext receiverContext, final TransactionID transactionID) throws XAException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final short invocationId = channelAssociation.getNextInvocationId();
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionForgetWriter();
            try {
                // write the tx forget message
                transactionMessageWriter.writeTxForget(dataOutputStream, invocationId, transactionID);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
        try {
            // wait for result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            resultProducer.getResult();
        } catch (XAException xae) {
            throw xae;
        } catch (RuntimeException re) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(re);
            throw xae;
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    protected Xid[] sendRecover(final EJBReceiverContext receiverContext, final String txParentNodeName, final int recoveryFlags) throws XAException {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionRecoverWriter();
        if (!channelAssociation.isMessageCompatibleForNegotiatedProtocolVersion(transactionMessageWriter.getHeader())) {
            Logs.REMOTING.transactionRecoveryMessageNotSupported(receiverContext.getReceiver());
            return new Xid[0];
        }
        final short invocationId = channelAssociation.getNextInvocationId();
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            try {
                // write the tx recover message
                transactionMessageWriter.writeTxRecover(dataOutputStream, invocationId, txParentNodeName, recoveryFlags);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
        try {
            // wait for result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            final Object result = resultProducer.getResult();
            if (result instanceof Xid[]) {
                return (Xid[]) result;
            }
            throw new RuntimeException("Unexpected result for transaction recover: " + result);
        } catch (XAException xae) {
            throw xae;
        } catch (RuntimeException re) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(re);
            throw xae;
        } catch (Exception e) {
            final XAException xae = new XAException(XAException.XAER_RMFAIL);
            xae.initCause(e);
            throw xae;
        }
    }

    @Override
    protected void beforeCompletion(final EJBReceiverContext receiverContext, final TransactionID transactionID) {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverContext);
        final short invocationId = channelAssociation.getNextInvocationId();
        final Future<EJBReceiverInvocationContext.ResultProducer> futureResultProducer = channelAssociation.enrollForResult(invocationId);
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final TransactionMessageWriter transactionMessageWriter = TransactionMessageWriter.getTransactionBeforeCompletionWriter();
            try {
                // write the beforeCompletion message
                transactionMessageWriter.writeTxBeforeCompletion(dataOutputStream, invocationId, transactionID);
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            throw new RuntimeException("Error sending transaction beforeCompletion message", e);
        }
        try {
            // wait for result
            final EJBReceiverInvocationContext.ResultProducer resultProducer = futureResultProducer.get();
            resultProducer.getResult();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Send an invocation cancellation message over the remoting channel, for the invocation corresponding to the
     * passed {@link EJBReceiverInvocationContext EJB receiver invocation context}. This method does <i>not</i>
     * wait or expect an "result" back from the server. Instead this method just returns back <code>false</code>
     * after sending the cancellation request.
     *
     * @param clientInvocationContext   the client invocation context for which the invocation is being cancelled
     * @param receiverInvocationContext the receiver invocation context for which the invocation is being cancelled
     * @return
     * @see {@link EJBReceiver#cancelInvocation(org.jboss.ejb.client.EJBClientInvocationContext, org.jboss.ejb.client.EJBReceiverInvocationContext)}
     */
    @Override
    protected boolean cancelInvocation(EJBClientInvocationContext clientInvocationContext, EJBReceiverInvocationContext receiverInvocationContext) {
        final ChannelAssociation channelAssociation = this.requireChannelAssociation(receiverInvocationContext.getEjbReceiverContext());
        final Short priorInvocationId = channelAssociation.getInvocationId(receiverInvocationContext);
        // nothing to do
        if (priorInvocationId == null) {
            return false;
        }
        // send a cancel message
        final MessageOutputStream messageOutputStream;
        try {
            messageOutputStream = channelAssociation.acquireChannelMessageOutputStream();
            final DataOutputStream dataOutputStream = new NoFlushDataOutputStream(messageOutputStream);
            final InvocationCancellationMessageWriter invocationCancellationMessageWriter = new InvocationCancellationMessageWriter();
            try {
                // write the cancel message for a previous invocation
                invocationCancellationMessageWriter.writeMessage(dataOutputStream, priorInvocationId);
                // we *don't* wait for a "result" of the cancel.
            } finally {
                channelAssociation.releaseChannelMessageOutputStream(messageOutputStream);
                dataOutputStream.close();
            }
        } catch (Exception e) {
            // just log a WARN
            Logs.REMOTING.failedToSendInvocationCancellationMessage(priorInvocationId, e);
        }
        return false;
    }

    void modulesAvailable(final EJBReceiverContext receiverContext, final ModuleAvailabilityMessageHandler.EJBModuleIdentifier[] ejbModules) {
        logger.debug("Received module availability report for " + ejbModules.length + " modules");
        for (final ModuleAvailabilityMessageHandler.EJBModuleIdentifier moduleIdentifier : ejbModules) {
            logger.debug("Registering module " + moduleIdentifier + " availability for receiver context " + receiverContext);
            this.registerModule(moduleIdentifier.appName, moduleIdentifier.moduleName, moduleIdentifier.distinctName);
        }
        // notify of module availability report if anyone's waiting on the latch
        final CountDownLatch moduleAvailabilityReportLatch;
        synchronized (this.moduleAvailabilityReportLatches) {
            moduleAvailabilityReportLatch = this.moduleAvailabilityReportLatches.remove(receiverContext);
        }
        if (moduleAvailabilityReportLatch != null) {
            moduleAvailabilityReportLatch.countDown();
        }
    }

    void modulesUnavailable(final EJBReceiverContext receiverContext, final ModuleAvailabilityMessageHandler.EJBModuleIdentifier[] ejbModules) {
        logger.debug("Received module un-availability report for " + ejbModules.length + " modules");
        for (final ModuleAvailabilityMessageHandler.EJBModuleIdentifier moduleIdentifier : ejbModules) {
            logger.debug("Un-registering module " + moduleIdentifier + " from receiver context " + receiverContext);
            this.deregisterModule(moduleIdentifier.appName, moduleIdentifier.moduleName, moduleIdentifier.distinctName);
        }
    }

    private ChannelAssociation requireChannelAssociation(final EJBReceiverContext ejbReceiverContext) {
        ChannelAssociation channelAssociation;
        synchronized (this.channelAssociations) {
            channelAssociation = this.channelAssociations.get(ejbReceiverContext);
        }
        if (channelAssociation == null) {
            throw Logs.MAIN.channelNotReadyForCommunication(EJB_CHANNEL_NAME, ejbReceiverContext);
        }
        return channelAssociation;
    }

    /**
     * Wraps the {@link MessageOutputStream message output stream} into a relevant {@link DataOutputStream}, taking into account various factors like the necessity to
     * compress the data that gets passed along the stream
     *
     * @param invocationContext         The EJB client invocation context
     * @param channelAssociation        The channel association
     * @param messageOutputStream       The message outputstream that needs to be wrapped
     * @return
     * @throws Exception
     */
    private DataOutputStream wrapMessageOutputStream(final EJBClientInvocationContext invocationContext, final ChannelAssociation channelAssociation, final MessageOutputStream messageOutputStream) throws Exception {
        // if the negotiated protocol version doesn't support compressed messages then just return a normal DataOutputStream
        if (channelAssociation.getNegotiatedProtocolVersion() < 2) {
            if (logger.isTraceEnabled()) {
                logger.trace("Cannot send compressed data messages to server because the negotiated protocol version " + channelAssociation.getNegotiatedProtocolVersion() + " doesn't support compressed messages. Going to send uncompressed message");
            }
            return new NoFlushDataOutputStream(messageOutputStream);
        }

        // if "hints" are disabled, just return a DataOutputStream without the necessity of processing any "hints"
        final Boolean hintsDisabled = invocationContext.getProxyAttachment(AttachmentKeys.HINTS_DISABLED);
        if (hintsDisabled != null && hintsDisabled.booleanValue()) {
            if (logger.isTraceEnabled()) {
                logger.trace("Hints are disabled. Ignoring any CompressionHint on methods being invoked on view " + invocationContext.getViewClass());
            }
            return new NoFlushDataOutputStream(messageOutputStream);
        }

        // process any CompressionHint
        final CompressionHint compressionHint;
        // first check method level
        final Map<Method, CompressionHint> dataCompressionHintMethods = invocationContext.getProxyAttachment(AttachmentKeys.VIEW_METHOD_DATA_COMPRESSION_HINT_ATTACHMENT_KEY);
        if (dataCompressionHintMethods == null || dataCompressionHintMethods.isEmpty()) {
            // check class level
            compressionHint = invocationContext.getProxyAttachment(AttachmentKeys.VIEW_CLASS_DATA_COMPRESSION_HINT_ATTACHMENT_KEY);
        } else {
            final CompressionHint compressionHintOnMethod = dataCompressionHintMethods.get(invocationContext.getInvokedMethod());
            if (compressionHintOnMethod == null) {
                // check class level
                compressionHint = invocationContext.getProxyAttachment(AttachmentKeys.VIEW_CLASS_DATA_COMPRESSION_HINT_ATTACHMENT_KEY);
            } else {
                compressionHint = compressionHintOnMethod;
            }
        }
        // if no CompressionHint is applicable for this invocation
        if (compressionHint == null) {
            return new NoFlushDataOutputStream(messageOutputStream);
        }
        final int compressionLevel = compressionHint.compressionLevel();
        // write out a attachment to indicate whether or not the response has to be compressed
        if (compressionHint.compressResponse()) {
            invocationContext.putAttachment(AttachmentKeys.COMPRESS_RESPONSE, true);
            invocationContext.putAttachment(AttachmentKeys.RESPONSE_COMPRESSION_LEVEL, compressionLevel);
            if (logger.isTraceEnabled()) {
                logger.trace("Letting the server know that the response of method " + invocationContext.getInvokedMethod() + " has to be compressed with compression level = " + compressionLevel);
            }
        }

        // create a compressed invocation data *only* if the request has to be compressed (note, it's perfectly valid for certain methods to just specify that only the response is compressed)
        if (compressionHint.compressRequest()) {
            // write out the header indicating that it's a compressed stream
            messageOutputStream.write(0x1B);
            // create the deflater using the specified level
            final Deflater deflater = new Deflater(compressionLevel);
            // wrap the message outputstream with the deflater stream so that *any subsequent* data writes to the stream are compressed
            final DeflaterOutputStream deflaterOutputStream = new DeflaterOutputStream(messageOutputStream, deflater);
            if (logger.isTraceEnabled()) {
                logger.trace("Using a compressing stream with compression level = " + compressionLevel + " for request data for EJB invocation on method " + invocationContext.getInvokedMethod());
            }
            return new NoFlushDataOutputStream(deflaterOutputStream);
        } else {
            // just return a normal DataOutputStream without any compression
            return new NoFlushDataOutputStream(messageOutputStream);
        }

    }

    @Override
    public String toString() {
        return String.format("Remoting connection EJB receiver [connection=%s,channel=%s,nodename=%s]", this.connection, EJB_CHANNEL_NAME, getNodeName());
    }

}
