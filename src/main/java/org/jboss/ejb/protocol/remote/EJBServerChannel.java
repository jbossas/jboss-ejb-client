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

package org.jboss.ejb.protocol.remote;

import static java.lang.Math.min;
import static java.security.AccessController.doPrivileged;
import static org.jboss.ejb.protocol.remote.TCCLUtils.getAndSetSafeTCCL;
import static org.jboss.ejb.protocol.remote.TCCLUtils.resetTCCL;
import static org.xnio.IoUtils.safeClose;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet6Address;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import javax.ejb.EJBException;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

import org.jboss.ejb._private.Logs;
import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.AttachmentKeys;
import org.jboss.ejb.client.ClusterAffinity;
import org.jboss.ejb.client.EJBClient;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBMethodLocator;
import org.jboss.ejb.client.EJBModuleIdentifier;
import org.jboss.ejb.client.NodeAffinity;
import org.jboss.ejb.client.RequestSendFailedException;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.TransactionID;
import org.jboss.ejb.client.UserTransactionID;
import org.jboss.ejb.client.XidTransactionID;
import org.jboss.ejb.client.annotation.CompressionHint;
import org.jboss.ejb.server.Association;
import org.jboss.ejb.server.CancelHandle;
import org.jboss.ejb.server.ClusterTopologyListener;
import org.jboss.ejb.server.InvocationRequest;
import org.jboss.ejb.server.ListenerHandle;
import org.jboss.ejb.server.ModuleAvailabilityListener;
import org.jboss.ejb.server.Request;
import org.jboss.ejb.server.SessionOpenRequest;
import org.jboss.marshalling.AbstractClassResolver;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.MessageOutputStream;
import org.jboss.remoting3._private.IntIndexHashMap;
import org.jboss.remoting3.util.MessageTracker;
import org.wildfly.common.Assert;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.security.auth.server.SecurityIdentity;
import org.wildfly.transaction.client.ContextTransactionManager;
import org.wildfly.transaction.client.ImportResult;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.SimpleXid;
import org.wildfly.transaction.client.provider.remoting.RemotingTransactionServer;
import org.wildfly.transaction.client.spi.SubordinateTransactionControl;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:tadamski@redhat.com">Tomasz Adamski</a>
 * @author <a href="mailto:ropalka@redhat.com">Richard Opalka</a>
 */
@SuppressWarnings("deprecation")
final class EJBServerChannel {

    private static final char METHOD_PARAM_TYPE_SEPARATOR = ',';

    private final RemotingTransactionServer transactionServer;
    private final Channel channel;
    private final int version;
    private final MessageTracker messageTracker;
    private final MarshallerFactory marshallerFactory;
    private final MarshallingConfiguration configuration;
    private final IntIndexHashMap<InProgress> invocations = new IntIndexHashMap<>(InProgress::getInvId);
    private final Function<String, Boolean>  classResolverFilter;

    EJBServerChannel(final RemotingTransactionServer transactionServer, final Channel channel, final int version, final MessageTracker messageTracker,
                     final Function<String, Boolean>  classResolverFilter) {
        this.transactionServer = transactionServer;
        this.channel = channel;
        this.version = version;
        this.messageTracker = messageTracker;
        final MarshallingConfiguration configuration = new MarshallingConfiguration();
        if (version < 3) {
            configuration.setClassTable(ProtocolV1ClassTable.INSTANCE);
            configuration.setObjectTable(ProtocolV1ObjectTable.INSTANCE);
            configuration.setObjectResolver(new ProtocolV1ObjectResolver(channel.getConnection(), true));
            configuration.setVersion(2);
        } else {
            configuration.setObjectTable(ProtocolV3ObjectTable.INSTANCE);
            configuration.setObjectResolver(new ProtocolV3ObjectResolver(channel.getConnection(), true));
            configuration.setVersion(4);
        }
        marshallerFactory = new RiverMarshallerFactory();
        this.configuration = configuration;
        this.classResolverFilter = classResolverFilter;
    }

    Channel.Receiver getReceiver(final Association association, final ListenerHandle handle1, final ListenerHandle handle2) {
        return new ReceiverImpl(association, handle1, handle2);
    }

    ClusterTopologyListener createTopologyListener() {
        return new ClusterTopologyWriter();
    }

    ModuleAvailabilityListener createModuleListener() {
        return new ModuleAvailabilityWriter();
    }

    class ReceiverImpl implements Channel.Receiver {
        private final Association association;
        private final ListenerHandle handle1;
        private final ListenerHandle handle2;

        ReceiverImpl(final Association association, final ListenerHandle handle1, final ListenerHandle handle2) {
            this.association = association;
            this.handle1 = handle1;
            this.handle2 = handle2;
        }

        public void handleError(final Channel channel, final IOException error) {
            final ClassLoader oldCL = getAndSetSafeTCCL();
            try {
                handle1.close();
                handle2.close();
            } finally {
                resetTCCL(oldCL);
            }
        }

        public void handleEnd(final Channel channel) {
            final ClassLoader oldCL = getAndSetSafeTCCL();
            try {
                handle1.close();
                handle2.close();
            } finally {
                resetTCCL(oldCL);
            }
        }

        public void handleMessage(final Channel channel, final MessageInputStream message) {
            channel.receiveMessage(this);
            final ClassLoader oldCL = getAndSetSafeTCCL();
            try {
                final int code = message.readUnsignedByte();
                switch (code) {
                    case Protocol.COMPRESSED_INVOCATION_MESSAGE:
                    case Protocol.INVOCATION_REQUEST: {
                        try (InputStream input = code == Protocol.COMPRESSED_INVOCATION_MESSAGE ? new InflaterInputStream(message) : message) {
                            // now if we get an error, we can respond.
                            if(code == Protocol.COMPRESSED_INVOCATION_MESSAGE) {
                                int verify = input.read();
                                if(verify != Protocol.INVOCATION_REQUEST) {
                                    throw new RuntimeException();
                                }

                            }
                            final int invId = (input.read() << 8) | input.read();
                            try {
                                handleInvocationRequest(invId, input);
                            } catch (IOException | ClassNotFoundException e) {
                                // write response back to client
                                writeFailedResponse(invId, e);
                            }
                        }
                        break;
                    }
                    case Protocol.OPEN_SESSION_REQUEST: {
                        final int invId = message.readUnsignedShort();
                        try {
                            handleSessionOpenRequest(invId, message);
                        } catch (IOException e) {
                            // write response back to client
                            writeFailedResponse(invId, e);
                        }
                        break;
                    }
                    case Protocol.CANCEL_REQUEST: {
                        final int invId = message.readUnsignedShort();
                        try {
                            handleCancelRequest(invId, message);
                        } catch (IOException e) {
                            // ignored
                        }
                        break;
                    }
                    case Protocol.TXN_COMMIT_REQUEST:
                    case Protocol.TXN_ROLLBACK_REQUEST:
                    case Protocol.TXN_PREPARE_REQUEST:
                    case Protocol.TXN_FORGET_REQUEST:
                    case Protocol.TXN_BEFORE_COMPLETION_REQUEST: {
                        final int invId = message.readUnsignedShort();
                        try {
                            handleTxnRequest(code, invId, message);
                        } catch (IOException e) {
                            // ignored
                        }
                        break;
                    }
                    case Protocol.TXN_RECOVERY_REQUEST: {
                        final int invId = message.readUnsignedShort();
                        try {
                            handleTxnRecoverRequest(invId, message);
                        } catch (IOException e) {
                            // ignored
                        }
                        break;
                    }
                    default: {
                        // unrecognized
                        Logs.REMOTING.invalidMessageReceived(code);
                        break;
                    }
                }
            } catch (IOException e) {
                // nothing we can do.
            } finally {
                safeClose(message);
                resetTCCL(oldCL);
            }
        }

        private void writeTxnResponse(final int invId, final int flag) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.TXN_RESPONSE);
                os.writeShort(invId);
                os.writeBoolean(true);
                PackedInteger.writePackedInteger(os, flag);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnTransactionResponseWrite(invId, channel, e);
            }
        }

        private void writeTxnResponse(final int invId) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.TXN_RESPONSE);
                os.writeShort(invId);
                os.writeBoolean(false);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnTransactionResponseWrite(invId, channel, e);
            }
        }

        private void handleTxnRequest(final int code, final int invId, final MessageInputStream message) throws IOException {
            final byte[] bytes = new byte[PackedInteger.readPackedInteger(message)];
            message.readFully(bytes);
            final TransactionID transactionID = TransactionID.createTransactionID(bytes);
            if (transactionID instanceof XidTransactionID) try {
                final SubordinateTransactionControl control = transactionServer.getTransactionService().getTransactionContext().findOrImportTransaction(((XidTransactionID) transactionID).getXid(), 0).getControl();
                switch (code) {
                    case Protocol.TXN_COMMIT_REQUEST: {
                        boolean opc = message.readBoolean();
                        control.commit(opc);
                        writeTxnResponse(invId);
                        break;
                    }
                    case Protocol.TXN_ROLLBACK_REQUEST: {
                        control.rollback();
                        writeTxnResponse(invId);
                        break;
                    }
                    case Protocol.TXN_PREPARE_REQUEST: {
                        int res = control.prepare();
                        writeTxnResponse(invId, res);
                        break;
                    }
                    case Protocol.TXN_FORGET_REQUEST: {
                        control.forget();
                        writeTxnResponse(invId);
                        break;
                    }
                    case Protocol.TXN_BEFORE_COMPLETION_REQUEST: {
                        control.beforeCompletion();
                        writeTxnResponse(invId);
                        break;
                    }
                    default: throw Assert.impossibleSwitchCase(code);
                }
            } catch (XAException e) {
                //EJBCLIENT-373
                if (!((version <= 2) && (e.errorCode == XAException.XAER_NOTA))) {
                    writeFailedResponse(invId, e);
                }
            } else if (transactionID instanceof UserTransactionID) try {
                final LocalTransaction localTransaction = transactionServer.removeTransaction(((UserTransactionID) transactionID).getId());
                switch (code) {
                    case Protocol.TXN_COMMIT_REQUEST: {
                        // Discard unused parameter
                        message.readBoolean();
                        if (localTransaction != null) localTransaction.commit();
                        writeTxnResponse(invId);
                        break;
                    }
                    case Protocol.TXN_ROLLBACK_REQUEST: {
                        if (localTransaction != null) localTransaction.rollback();
                        writeTxnResponse(invId);
                        break;
                    }
                    case Protocol.TXN_PREPARE_REQUEST:
                    case Protocol.TXN_FORGET_REQUEST:
                    case Protocol.TXN_BEFORE_COMPLETION_REQUEST: {
                        writeFailedResponse(invId, Logs.TXN.userTxNotSupportedByTxContext());
                        break;
                    }
                    default: throw Assert.impossibleSwitchCase(code);
                }
            } catch (SystemException | HeuristicMixedException | RollbackException | HeuristicRollbackException e) {
                writeFailedResponse(invId, e);
            } catch (Throwable t) {
                // Narayana uses Errors, Exceptions, and RuntimeExceptions
                writeFailedResponse(invId, Logs.TXN.internalSystemErrorWithTx(t));
            } else {
                throw Assert.unreachableCode();
            }
        }

        void handleTxnRecoverRequest(final int invId, final MessageInputStream message) throws IOException {
            final String parentName = message.readUTF();
            final int flags = message.readInt();
            final Xid[] xids;
            try {
                xids = transactionServer.getTransactionService().getTransactionContext().getRecoveryInterface().recover(flags, parentName);
            } catch (XAException e) {
                writeFailedResponse(invId, e);
                return;
            }
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.TXN_RECOVERY_RESPONSE);
                os.writeShort(invId);
                PackedInteger.writePackedInteger(os, xids.length);
                final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
                marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
                for (Xid xid : xids) {
                    marshaller.writeObject(new XidTransactionID(xid));
                }
                marshaller.finish();
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnTransactionRecoveryResponseWrite(invId, channel, e);
            }
        }

        void handleCancelRequest(final int invId, final MessageInputStream message) throws IOException {
            final boolean cancelIfRunning = version < 3 || message.readBoolean();
            final InProgress inProgress = invocations.get(invId);
            if (inProgress != null) {
                inProgress.cancel(cancelIfRunning);
            }
        }

        void handleSessionOpenRequest(final int invId, final MessageInputStream inputStream) throws IOException {
            final String appName = inputStream.readUTF();
            final String moduleName = inputStream.readUTF();
            final String distName = inputStream.readUTF();
            final String beanName = inputStream.readUTF();
            final int securityContext;
            final ExceptionSupplier<ImportResult<?>, SystemException> transactionSupplier;
            if (version >= 3) {
                securityContext = inputStream.readInt();
                transactionSupplier = readTransaction(inputStream);
            } else {
                securityContext = 0;
                transactionSupplier = null;
            }
            final Connection connection = channel.getConnection();
            final EJBIdentifier identifier = new EJBIdentifier(appName, moduleName, beanName, distName);

            association.receiveSessionOpenRequest(new RemotingSessionOpenRequest(
                invId,
                identifier,
                transactionSupplier,
                connection.getLocalIdentity(securityContext)));
        }

        void handleInvocationRequest(final int invId, final InputStream input) throws IOException, ClassNotFoundException {
            final MarshallingConfiguration configuration = EJBServerChannel.this.configuration.clone();
            final ServerClassResolver classResolver = new ServerClassResolver(EJBServerChannel.this.classResolverFilter);
            configuration.setClassResolver(classResolver);
            final Unmarshaller unmarshaller;

            final EJBIdentifier identifier;
            final EJBMethodLocator methodLocator;

            final Connection connection = channel.getConnection();
            final SecurityIdentity identity;
            if (version >= 3) {
                unmarshaller = marshallerFactory.createUnmarshaller(configuration);
                unmarshaller.start(Marshalling.createByteInput(input));
                identifier = unmarshaller.readObject(EJBIdentifier.class);
                methodLocator = unmarshaller.readObject(EJBMethodLocator.class);
                int identityId = unmarshaller.readInt();
                identity = identityId == 0 ? connection.getLocalIdentity() : connection.getLocalIdentity(identityId);
            } else {
                assert version <= 2;
                DataInputStream data = new DataInputStream(input);
                final String methodName = data.readUTF();
                // method signature
                final String sigString = data.readUTF();
                unmarshaller = marshallerFactory.createUnmarshaller(configuration);
                unmarshaller.start(Marshalling.createByteInput(data));
                String appName = unmarshaller.readObject(String.class);
                String moduleName = unmarshaller.readObject(String.class);
                String distinctName = unmarshaller.readObject(String.class);
                String beanName = unmarshaller.readObject(String.class);
                identifier = new EJBIdentifier(appName, moduleName, beanName, distinctName);

                // parse out the signature string
                final String[] parameterTypeNames;
                if (sigString.isEmpty()) {
                    parameterTypeNames = new String[0];
                } else {
                    parameterTypeNames = sigString.split(String.valueOf(METHOD_PARAM_TYPE_SEPARATOR));
                }
                methodLocator = new EJBMethodLocator(methodName, parameterTypeNames);
                identity = connection.getLocalIdentity();
            }
            final RemotingInvocationRequest request = new RemotingInvocationRequest(
                invId, identifier, methodLocator, classResolver, unmarshaller, identity
            );
            InProgress value = new InProgress(request);
            invocations.put(value);
            try {
                value.setCancelHandle(association.receiveInvocationRequest(request));
            } catch (Throwable t) {
                //this should not happen
                //but no harm in being defensive
                Logs.INVOCATION.unexpectedException(t);
                if(t instanceof Exception) {
                    request.writeException((Exception) t);
                } else {
                    request.writeException(new EJBException(new RuntimeException(t)));
                }
            }
        }
    }

    ExceptionSupplier<ImportResult<?>, SystemException> readTransaction(final DataInput input) throws IOException {
        final int type = input.readUnsignedByte();
        if (type == 0) {
            return null;
        } else if (type == 1) {
            // remote user transaction
            final int id = input.readInt();
            final int timeout = PackedInteger.readPackedInteger(input);
            return () -> new ImportResult<Transaction>(transactionServer.getOrBeginTransaction(id, timeout), SubordinateTransactionControl.EMPTY, false);
        } else if (type == 2) {
            final int fmt = PackedInteger.readPackedInteger(input);
            final byte[] gtid = new byte[input.readUnsignedByte()];
            input.readFully(gtid);
            final byte[] bq = new byte[input.readUnsignedByte()];
            input.readFully(bq);
            final int timeout = PackedInteger.readPackedInteger(input);
            return () -> {
                try {
                    return transactionServer.getTransactionService().getTransactionContext().findOrImportTransaction(new SimpleXid(fmt, gtid, bq), timeout);
                } catch (XAException e) {
                    throw new SystemException(e.getMessage());
                }
            };
        } else {
            throw Logs.REMOTING.invalidTransactionType(type);
        }
    }

    private void writeFailedResponse(final int invId, final Throwable e) {
        try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
            os.writeByte(Protocol.APPLICATION_EXCEPTION);
            os.writeShort(invId);
            final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
            marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
            marshaller.writeObject(new RequestSendFailedException(e.getMessage() + "@" + channel.getConnection().getPeerURI(), e));
            marshaller.writeByte(0);
            marshaller.finish();
        } catch (IOException e2) {
            e2.addSuppressed(e);
            // nothing to do at this point; the client doesn't want the response
            Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e2);
        }
    }

    abstract class RemotingRequest implements Request {
        final int invId;
        SessionID sessionId;
        final SecurityIdentity identity;
        ClusterAffinity strongAffinityUpdate;
        NodeAffinity weakAffinityUpdate;

        RemotingRequest(final int invId, final SecurityIdentity identity) {
            this.invId = invId;
            this.identity = identity;
        }

        public Executor getRequestExecutor() {
            return channel.getConnection().getEndpoint().getXnioWorker();
        }

        public SocketAddress getPeerAddress() {
            return channel.getConnection().getPeerAddress();
        }

        public SocketAddress getLocalAddress() {
            return channel.getConnection().getLocalAddress();
        }

        public String getProtocol() {
            return channel.getConnection().getProtocol();
        }

        public boolean isBlockingCaller() {
            return false;
        }

        public SecurityIdentity getSecurityIdentity() {
            return identity;
        }

        public void writeNoSuchEJB() {
            final String message = Logs.REMOTING.remoteMessageNoSuchEJB(getEJBIdentifier());
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.NO_SUCH_EJB);
                os.writeShort(invId);
                os.writeUTF(message);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void writeWrongViewType() {
            final String message = Logs.REMOTING.remoteMessageBadViewType(getEJBIdentifier());
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                if (version >= 3) {
                    os.writeByte(Protocol.BAD_VIEW_TYPE);
                    os.writeShort(invId);
                    os.writeUTF(message);
                } else {
                    os.writeByte(Protocol.APPLICATION_EXCEPTION);
                    os.writeShort(invId);
                    final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
                    marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
                    marshaller.writeObject(Logs.REMOTING.invalidViewTypeForInvocation(message));
                    marshaller.writeByte(0);
                    marshaller.finish();
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void writeCancelResponse() {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CANCEL_RESPONSE);
                os.writeShort(invId);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void writeNotStateful() {
            final String message = Logs.REMOTING.remoteMessageEJBNotStateful(getEJBIdentifier());
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.EJB_NOT_STATEFUL);
                os.writeShort(invId);
                os.writeUTF(message);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void convertToStateful(@NotNull final SessionID sessionId) throws IllegalArgumentException, IllegalStateException {
            Assert.checkNotNullParam("sessionId", sessionId);
            final SessionID ourSessionId = this.sessionId;
            if (ourSessionId != null) {
                if (! sessionId.equals(ourSessionId)) {
                    throw new IllegalStateException();
                }
            } else {
                this.sessionId = sessionId;
            }
        }

        public <C> C getProviderInterface(Class<C> providerInterfaceType) {
            final Connection connection = channel.getConnection();
            return providerInterfaceType.isInstance(connection) ? providerInterfaceType.cast(connection) : null;
        }

        abstract int getEnlistmentStatus();

        protected void writeFailure(Exception reason) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.APPLICATION_EXCEPTION);
                os.writeShort(invId);
                if (version >= 3) os.writeByte(getEnlistmentStatus());
                final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
                marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
                marshaller.writeObject(reason);
                marshaller.writeByte(0);
                marshaller.finish();
            } catch (IOException e) {
                e.addSuppressed(reason);
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void updateStrongAffinity(@NotNull final Affinity affinity) {
            Assert.checkNotNullParam("affinity", affinity);
            if (affinity instanceof ClusterAffinity) {
                strongAffinityUpdate = (ClusterAffinity) affinity;
            }
        }

        public void updateWeakAffinity(@NotNull final Affinity affinity) {
            Assert.checkNotNullParam("affinity", affinity);
            if (affinity instanceof NodeAffinity) {
                weakAffinityUpdate = (NodeAffinity) affinity;
            }
        }
    }

    final class RemotingSessionOpenRequest extends RemotingRequest implements SessionOpenRequest {
        private final EJBIdentifier identifier;
        final ExceptionSupplier<ImportResult<?>, SystemException> transactionSupplier;
        int txnCmd = 0; // assume nobody will ask about the transaction

        RemotingSessionOpenRequest(final int invId, final EJBIdentifier identifier, final ExceptionSupplier<ImportResult<?>, SystemException> transactionSupplier, final SecurityIdentity identity) {
            super(invId, identity);
            this.transactionSupplier = transactionSupplier;
            this.identifier = identifier;
        }

        @NotNull
        public EJBIdentifier getEJBIdentifier() {
            return identifier;
        }

        public boolean hasTransaction() {
            return transactionSupplier != null;
        }

        public Transaction getTransaction() throws SystemException, IllegalStateException {
            final ExceptionSupplier<ImportResult<?>, SystemException> transactionSupplier = this.transactionSupplier;
            if (transactionSupplier == null) {
                return null;
            }
            if (txnCmd != 0) {
                throw new IllegalStateException();
            }
            final ImportResult<?> importResult = transactionSupplier.get();
            if (importResult.isNew()) {
                txnCmd = 1;
            } else {
                txnCmd = 2;
            }
            return importResult.getTransaction();
        }

        int getEnlistmentStatus() {
            return txnCmd;
        }

        public void writeException(@NotNull final Exception exception) {
            Assert.checkNotNullParam("exception", exception);
            writeFailure(exception);
        }

        public void convertToStateful(@NotNull final SessionID sessionId) throws IllegalArgumentException, IllegalStateException {
            super.convertToStateful(sessionId);
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.OPEN_SESSION_RESPONSE);
                os.writeShort(invId);
                final byte[] encodedForm = sessionId.getEncodedForm();
                PackedInteger.writePackedInteger(os, encodedForm.length);
                os.write(encodedForm);
                if (1 <= version && version <= 2) {
                    final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
                    marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
                    if (strongAffinityUpdate != null) {
                        marshaller.writeObject(strongAffinityUpdate);
                    } else {
                        marshaller.writeObject(new NodeAffinity(channel.getConnection().getEndpoint().getName()));
                    }
                    marshaller.finish();
                } else {
                    assert version >= 3;
                    os.writeByte(txnCmd);
                    int updateBits = 0;
                    if (weakAffinityUpdate != null) {
                        updateBits |= Protocol.UPDATE_BIT_WEAK_AFFINITY;
                    }
                    if (strongAffinityUpdate != null) {
                        updateBits |= Protocol.UPDATE_BIT_STRONG_AFFINITY;
                    }
                    os.writeByte(updateBits);
                    if (weakAffinityUpdate != null) {
                        final String nodeName = weakAffinityUpdate.getNodeName();
                        final byte[] bytes = nodeName.getBytes(StandardCharsets.UTF_8);
                        PackedInteger.writePackedInteger(os, bytes.length);
                        os.write(bytes);
                    }
                    if (strongAffinityUpdate != null) {
                        final String clusterName = strongAffinityUpdate.getClusterName();
                        final byte[] bytes = clusterName.getBytes(StandardCharsets.UTF_8);
                        PackedInteger.writePackedInteger(os, bytes.length);
                        os.write(bytes);
                    }
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBSessionOpenResponseWrite(invId, channel, e);
            }
        }
    }

    final class RemotingInvocationRequest extends RemotingRequest implements InvocationRequest {
        final EJBIdentifier identifier;
        final EJBMethodLocator methodLocator;
        final ServerClassResolver classResolver;
        final Unmarshaller remaining;
        int txnCmd = 0; // assume nobody will ask about the transaction

        RemotingInvocationRequest(final int invId, final EJBIdentifier identifier, final EJBMethodLocator methodLocator, final ServerClassResolver classResolver, final Unmarshaller remaining, final SecurityIdentity identity) {
            super(invId, identity);
            this.identifier = identifier;
            this.methodLocator = methodLocator;
            this.classResolver = classResolver;
            this.remaining = remaining;
        }

        public void convertToStateful(final SessionID sessionId) throws IllegalArgumentException, IllegalStateException {
            if (version < 3) {
                throw Logs.REMOTING.cannotAddSessionID();
            }
            super.convertToStateful(sessionId);
        }

        public Resolved getRequestContent(final ClassLoader classLoader) throws IOException, ClassNotFoundException {
            classResolver.setClassLoader(classLoader);
            int responseCompressLevel = 0;
            // resolve the rest of everything here
            try (Unmarshaller unmarshaller = remaining) {
                Affinity weakAffinity = Affinity.NONE;
                ExceptionSupplier<ImportResult<?>, SystemException> transactionSupplier = null;
                final EJBLocator<?> locator;
                if (version >= 3) {
                    weakAffinity = unmarshaller.readObject(Affinity.class);
                    if (weakAffinity == null) weakAffinity = Affinity.NONE;
                    int flags = unmarshaller.readUnsignedByte();
                    responseCompressLevel = flags & Protocol.COMPRESS_RESPONSE;
                    transactionSupplier = readTransaction(unmarshaller);
                    locator = unmarshaller.readObject(EJBLocator.class);
                    // do identity checks for these strings to guarantee integrity.
                    // noinspection StringEquality
                    if (identifier != locator.getIdentifier()) {
                        throw Logs.REMOTING.mismatchedMethodLocation();
                    }

                } else {
                    assert version <= 2;

                    locator = unmarshaller.readObject(EJBLocator.class);
                    // do identity checks for these strings to guarantee integrity.  can't check identifier because that class didn't exist in V2
                    //noinspection StringEquality
                    if (identifier.getAppName() != locator.getAppName() ||
                        identifier.getModuleName() != locator.getModuleName() ||
                        identifier.getBeanName() != locator.getBeanName() ||
                        identifier.getDistinctName() != locator.getDistinctName()) {

                        throw Logs.REMOTING.mismatchedMethodLocation();
                    }
                }
                Object[] parameters = new Object[methodLocator.getParameterCount()];
                for (int i = 0; i < parameters.length; i ++) {
                    parameters[i] = unmarshaller.readObject();
                }
                int attachmentCount = PackedInteger.readPackedInteger(unmarshaller);
                final Map<String, Object> attachments = new HashMap<>(attachmentCount);
                for (int i = 0; i < attachmentCount; i ++) {
                    String attName = unmarshaller.readObject(String.class);
                    if (attName.equals(EJBClientInvocationContext.PRIVATE_ATTACHMENTS_KEY)) {
                        if (version <= 2) {
                            // only supported for protocol v1/2 - read out transaction ID
                            @SuppressWarnings("unchecked")
                            Map<Object, Object> map = (Map<Object, Object>) unmarshaller.readObject();
                            final Object transactionIdObject = map.get(AttachmentKeys.TRANSACTION_ID_KEY);
                            if (transactionIdObject != null) {
                                // attach it
                                final TransactionID transactionId = (TransactionID) transactionIdObject;
                                // look up the transaction
                                if (transactionId instanceof UserTransactionID) {
                                    transactionSupplier = () -> new ImportResult<Transaction>(transactionServer.getOrBeginTransaction(
                                            ((UserTransactionID) transactionId).getId(), ContextTransactionManager.getGlobalDefaultTransactionTimeout()),
                                            SubordinateTransactionControl.EMPTY, false);
                                } else if (transactionId instanceof XidTransactionID) {
                                    transactionSupplier = () -> {
                                        try {
                                            return transactionServer.getTransactionService().getTransactionContext().findOrImportTransaction(
                                                    ((XidTransactionID) transactionId).getXid(), ContextTransactionManager.getGlobalDefaultTransactionTimeout());
                                        } catch (XAException e) {
                                            throw new SystemException(e.getMessage());
                                        }
                                    };
                                } else {
                                    throw Assert.impossibleSwitchCase(transactionId);
                                }
                            }
                            weakAffinity = (Affinity) map.getOrDefault(AttachmentKeys.WEAK_AFFINITY, weakAffinity);
                            boolean compressResponse = (Boolean) map.getOrDefault(AttachmentKeys.COMPRESS_RESPONSE, false);
                            if (compressResponse) {
                                responseCompressLevel = (Integer) map.getOrDefault(AttachmentKeys.RESPONSE_COMPRESSION_LEVEL, 0);
                            }
                        } else {
                            // discard content for v3
                            unmarshaller.readObject();
                        }
                    } else {
                        final Object value = unmarshaller.readObject();
                        if (value != null) attachments.put(attName, value);
                    }
                }
                attachments.put(EJBClient.SOURCE_ADDRESS_KEY, channel.getConnection().getPeerAddress());

                final ExceptionSupplier<ImportResult<?>, SystemException> finalTransactionSupplier = transactionSupplier;

                if(version == 2) {
                    //version 2 did not send compression information in the response stream
                    //instead it must be read from the class
                    Method invokedMethod = findMethod(locator.getViewType(), methodLocator);
                    CompressionHint compressionHint = invokedMethod == null ? null : invokedMethod.getAnnotation(CompressionHint.class);
                    // then class level
                    if (compressionHint == null) {
                        compressionHint = invokedMethod == null ? null : invokedMethod.getDeclaringClass().getAnnotation(CompressionHint.class);
                    }
                    if(compressionHint != null) {
                        if(compressionHint.compressResponse()) {
                            responseCompressLevel = compressionHint.compressionLevel();
                        }
                    }
                }

                final int finalResponseCompressLevel = responseCompressLevel == 15 ? Deflater.DEFAULT_COMPRESSION : min(responseCompressLevel, 9);
                return new Resolved() {

                    @NotNull
                    public Map<String, Object> getAttachments() {
                        return attachments;
                    }

                    @NotNull
                    public Object[] getParameters() {
                        return parameters;
                    }

                    @NotNull
                    public EJBLocator<?> getEJBLocator() {
                        return locator;
                    }

                    public boolean hasTransaction() {
                        return finalTransactionSupplier != null;
                    }

                    public Transaction getTransaction() throws SystemException, IllegalStateException {
                        if (finalTransactionSupplier == null) {
                            return null;
                        }
                        if (txnCmd != 0) {
                            throw new IllegalStateException();
                        }
                        final ImportResult<?> importResult = finalTransactionSupplier.get();
                        if (importResult.isNew()) {
                            txnCmd = 1;
                        } else {
                            txnCmd = 2;
                        }
                        return importResult.getTransaction();
                    }

                    public void writeInvocationResult(final Object result) {
                        MessageOutputStream os;
                        try (MessageOutputStream underlying = messageTracker.openMessageUninterruptibly()) {
                            if(finalResponseCompressLevel != 0) {
                                underlying.writeByte(Protocol.COMPRESSED_INVOCATION_MESSAGE);
                                os = new WrapperMessageOutputStream(underlying, new DeflaterOutputStream(underlying, new Deflater(finalResponseCompressLevel)));
                            } else {
                                os = underlying;
                            }
                            os.writeByte(Protocol.INVOCATION_RESPONSE);
                            os.writeShort(invId);
                            if (version >= 3) {
                                os.writeByte(txnCmd);
                                int updateBits = 0;
                                if (sessionId != null) {
                                    updateBits |= Protocol.UPDATE_BIT_SESSION_ID;
                                }
                                if (weakAffinityUpdate != null) {
                                    updateBits |= Protocol.UPDATE_BIT_WEAK_AFFINITY;
                                }
                                if (strongAffinityUpdate != null) {
                                    updateBits |= Protocol.UPDATE_BIT_STRONG_AFFINITY;
                                }
                                os.writeByte(updateBits);
                                if (sessionId != null) {
                                    final byte[] bytes = sessionId.getEncodedForm();
                                    PackedInteger.writePackedInteger(os, bytes.length);
                                    os.write(bytes);
                                }
                                if (weakAffinityUpdate != null) {
                                    final String nodeName = weakAffinityUpdate.getNodeName();
                                    final byte[] bytes = nodeName.getBytes(StandardCharsets.UTF_8);
                                    PackedInteger.writePackedInteger(os, bytes.length);
                                    os.write(bytes);
                                }
                                if (strongAffinityUpdate != null) {
                                    final String clusterName = strongAffinityUpdate.getClusterName();
                                    final byte[] bytes = clusterName.getBytes(StandardCharsets.UTF_8);
                                    PackedInteger.writePackedInteger(os, bytes.length);
                                    os.write(bytes);
                                }
                            }
                            final Marshaller marshaller = marshallerFactory.createMarshaller(configuration);
                            marshaller.start(new NoFlushByteOutput(Marshalling.createByteOutput(os)));
                            marshaller.writeObject(result);
                            attachments.remove(EJBClient.SOURCE_ADDRESS_KEY);
                            if (version >= 3) {
                                attachments.remove(Affinity.WEAK_AFFINITY_CONTEXT_KEY);
                            }
                            int count = attachments.size();
                            if (count > 255) {
                                marshaller.writeByte(255);
                            } else {
                                marshaller.writeByte(count);
                            }
                            int i = 0;
                            ProtocolObjectResolver.enableNonSerReplacement();
                            try {
                                for (Map.Entry<String, Object> entry : attachments.entrySet()) {
                                    marshaller.writeObject(entry.getKey());
                                    marshaller.writeObject(entry.getValue());
                                    if (i ++ == 255) {
                                        break;
                                    }
                                }
                            } finally {
                                ProtocolObjectResolver.disableNonSerReplacement();
                            }
                            marshaller.finish();
                            os.close();
                        } catch (IOException e) {
                            // nothing to do at this point; the client doesn't want the response
                            Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
                        } finally {
                            invocations.removeKey(invId);
                        }
                    }

                };
            }
        }

        @Override
        public void writeProceedAsync() {
            if(version >= 3) {
                //not used in newer protocols
                return;
            }
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.PROCEED_ASYNC_RESPONSE);
                os.writeShort(invId);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnProceedAsyncResponseWrite(invId, channel, e);
            }
        }

        @NotNull
        public EJBIdentifier getEJBIdentifier() {
            return identifier;
        }

        public void writeNoSuchMethod() {
            final String message = Logs.REMOTING.remoteMessageNoSuchMethod(methodLocator, identifier);
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.NO_SUCH_METHOD);
                os.writeShort(invId);
                os.writeUTF(message);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        public void writeSessionNotActive() {
            final String message = Logs.REMOTING.remoteMessageSessionNotActive(methodLocator, identifier);
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.NO_SUCH_METHOD);
                os.writeShort(invId);
                os.writeUTF(message);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            }
        }

        int getEnlistmentStatus() {
            return txnCmd;
        }

        public void writeException(@NotNull final Exception exception) {
            Assert.checkNotNullParam("exception", exception);
            writeFailure(exception);
        }

        public EJBMethodLocator getMethodLocator() {
            return methodLocator;
        }

        void writeCancellation() {
            if (version >= 3) try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CANCEL_RESPONSE);
                os.writeShort(invId);
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBResponseWrite(invId, channel, e);
            } finally {
                invocations.removeKey(invId);
            } else {
                writeFailure(Logs.REMOTING.requestCancelled());
            }
        }
    }

    private static Method findMethod(final Class componentView, final EJBMethodLocator ejbMethodLocator) {
        final Method[] viewMethods = componentView.getMethods();
        for (final Method method : viewMethods) {
            if (method.getName().equals(ejbMethodLocator.getMethodName())) {
                final Class<?>[] methodParamTypes = method.getParameterTypes();
                if (methodParamTypes.length != ejbMethodLocator.getParameterCount()) {
                    continue;
                }
                boolean found = true;
                for (int i = 0; i < methodParamTypes.length; i++) {
                    if (!methodParamTypes[i].getName().equals(ejbMethodLocator.getParameterTypeName(i))) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    return method;
                }
            }
        }
        return null;
    }

    static final class InProgress {
        private final RemotingInvocationRequest incomingInvocation;
        private CancelHandle cancelHandle;
        private boolean cancelled = false;
        private boolean aggressive = false;

        InProgress(final RemotingInvocationRequest incomingInvocation) {
            this.incomingInvocation = incomingInvocation;
        }

        int getInvId() {
            return incomingInvocation.invId;
        }

        CancelHandle getCancelHandle() {
            return cancelHandle;
        }

        synchronized void setCancelHandle(CancelHandle cancelHandle) {
            this.cancelHandle = cancelHandle;
            if(cancelled) {
                cancelHandle.cancel(aggressive);
            }
        }

        synchronized void cancel(boolean aggressive) {
            this.cancelled = true;
            this.aggressive = aggressive;
            if(cancelHandle != null) {
                cancelHandle.cancel(aggressive);
            }
        }
    }

    static final class ServerClassResolver extends AbstractClassResolver {
        private ClassLoader classLoader;
        private final Function<String, Boolean> classResolverFilter;

        ServerClassResolver(final Function<String, Boolean> classResolverFilter) {
            super(true);
            this.classResolverFilter = classResolverFilter;
        }

        public Class<?> resolveProxyClass(final Unmarshaller unmarshaller, final String[] interfaces) throws IOException, ClassNotFoundException {
            final int length = interfaces.length;
            final Class<?>[] classes = new Class<?>[length];

            for(int i = 0; i < length; ++i) {
                checkFilter(interfaces[i]);
                classes[i] = this.loadClass(interfaces[i]);
            }

            final ClassLoader classLoader;
            if (length == 1) {
                classLoader = doPrivileged((PrivilegedAction<ClassLoader>) classes[0]::getClassLoader);
            } else {
                classLoader = getClassLoader();
            }

            return Proxy.getProxyClass(classLoader, classes);
        }

        protected ClassLoader getClassLoader() {
            final ClassLoader classLoader = this.classLoader;
            return classLoader == null ? getClass().getClassLoader() : classLoader;
        }

        @Override
        public Class<?> resolveClass(final Unmarshaller unmarshaller, final String name, final long serialVersionUID) throws IOException, ClassNotFoundException {
            checkFilter(name);
            return super.resolveClass(unmarshaller, name, serialVersionUID);
        }

        void setClassLoader(final ClassLoader classLoader) {
            this.classLoader = classLoader == null ? getClass().getClassLoader() : classLoader;
        }

        private void checkFilter(String className) throws InvalidClassException {
            if (classResolverFilter != null && classResolverFilter.apply(className) != Boolean.TRUE) {
                throw Logs.REMOTING.cannotResolveFilteredClass(className);
            }
        }
    }

    final class ClusterTopologyWriter implements ClusterTopologyListener {
        ClusterTopologyWriter() {
        }

        @Override
        public void clusterTopology(final List<ClusterInfo> clusterInfoList) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CLUSTER_TOPOLOGY_COMPLETE);
                PackedInteger.writePackedInteger(os, clusterInfoList.size());
                for (ClusterInfo clusterInfo : clusterInfoList) {
                    os.writeUTF(clusterInfo.getClusterName());
                    final List<NodeInfo> nodeInfoList = clusterInfo.getNodeInfoList();
                    PackedInteger.writePackedInteger(os, nodeInfoList.size());
                    for (NodeInfo nodeInfo : nodeInfoList) {
                        os.writeUTF(nodeInfo.getNodeName());
                        final List<MappingInfo> mappingInfoList = nodeInfo.getMappingInfoList();
                        PackedInteger.writePackedInteger(os, mappingInfoList.size());
                        for (MappingInfo mappingInfo : mappingInfoList) {
                            boolean is6 = mappingInfo.getSourceAddress() instanceof Inet6Address;
                            if (is6) {
                                PackedInteger.writePackedInteger(os, mappingInfo.getNetmaskBits() << 1);
                            } else {
                                PackedInteger.writePackedInteger(os, mappingInfo.getNetmaskBits() << 1 | 1);
                            }
                            os.write(mappingInfo.getSourceAddress().getAddress());
                            os.writeUTF(mappingInfo.getDestinationAddress());
                            os.writeShort(mappingInfo.getDestinationPort());
                        }
                    }
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBClusterMessageWrite(channel, e);
            }
        }

        @Override
        public void clusterRemoval(final List<String> clusterNames) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CLUSTER_TOPOLOGY_REMOVAL);
                PackedInteger.writePackedInteger(os, clusterNames.size());
                for (String clusterName : clusterNames) {
                    os.writeUTF(clusterName);
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBClusterMessageWrite(channel, e);
            }
        }

        @Override
        public void clusterNewNodesAdded(final ClusterInfo clusterInfo) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CLUSTER_TOPOLOGY_ADDITION);
                PackedInteger.writePackedInteger(os, 1);
                os.writeUTF(clusterInfo.getClusterName());
                final List<NodeInfo> nodeInfoList = clusterInfo.getNodeInfoList();
                PackedInteger.writePackedInteger(os, nodeInfoList.size());
                for (NodeInfo nodeInfo : nodeInfoList) {
                    os.writeUTF(nodeInfo.getNodeName());
                    final List<MappingInfo> mappingInfoList = nodeInfo.getMappingInfoList();
                    PackedInteger.writePackedInteger(os, mappingInfoList.size());
                    for (MappingInfo mappingInfo : mappingInfoList) {
                        boolean is6 = mappingInfo.getSourceAddress() instanceof Inet6Address;
                        if (is6) {
                            PackedInteger.writePackedInteger(os, mappingInfo.getNetmaskBits() << 1);
                        } else {
                            PackedInteger.writePackedInteger(os, mappingInfo.getNetmaskBits() << 1 | 1);
                        }
                        os.write(mappingInfo.getSourceAddress().getAddress());
                        os.writeUTF(mappingInfo.getDestinationAddress());
                        os.writeShort(mappingInfo.getDestinationPort());
                    }
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBClusterMessageWrite(channel, e);
            }
        }

        @Override
        public void clusterNodesRemoved(final List<ClusterRemovalInfo> clusterRemovalInfoList) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(Protocol.CLUSTER_TOPOLOGY_NODE_REMOVAL);
                PackedInteger.writePackedInteger(os, clusterRemovalInfoList.size());
                for (ClusterRemovalInfo removalInfo : clusterRemovalInfoList) {
                    os.writeUTF(removalInfo.getClusterName());
                    final List<String> nodeNamesList = removalInfo.getNodeNames();
                    PackedInteger.writePackedInteger(os, nodeNamesList.size());
                    for (String name : nodeNamesList) {
                        os.writeUTF(name);
                    }
                }
            } catch (IOException e) {
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnEJBClusterMessageWrite(channel, e);
            }
        }

        @Override
        public Connection getConnection() {
            return EJBServerChannel.this.channel.getConnection();
        }
    }

    final class ModuleAvailabilityWriter implements ModuleAvailabilityListener {
        ModuleAvailabilityWriter() {
        }

        public void moduleAvailable(final List<EJBModuleIdentifier> modules) {
            doWrite(true, modules);
        }

        public void moduleUnavailable(final List<EJBModuleIdentifier> modules) {
            doWrite(false, modules);
        }

        private void doWrite(final boolean available, final List<EJBModuleIdentifier> modules) {
            try (MessageOutputStream os = messageTracker.openMessageUninterruptibly()) {
                os.writeByte(available ? Protocol.MODULE_AVAILABLE : Protocol.MODULE_UNAVAILABLE);
                PackedInteger.writePackedInteger(os, modules.size());
                for (EJBModuleIdentifier module : modules) {
                    final String appName = module.getAppName();
                    os.writeUTF(appName == null ? "" : appName);
                    final String moduleName = module.getModuleName();
                    os.writeUTF(moduleName == null ? "" : moduleName);
                    final String distinctName = module.getDistinctName();
                    os.writeUTF(distinctName == null ? "" : distinctName);
                }
            } catch (IOException e) {
                // we need to close connection, as module availability messages occurs when establishing a connection to the server
                // prior to sending an invocation request (this will prevent a frozen client at the other side)
                channel.closeAsync();
                // nothing to do at this point; the client doesn't want the response
                Logs.REMOTING.ioExceptionOnModuleAvailabilityWrite(channel, e);
            }
        }
    }
}
