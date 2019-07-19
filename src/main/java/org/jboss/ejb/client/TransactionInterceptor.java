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

import java.net.URI;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.transaction.Transaction;

import org.jboss.ejb._private.Logs;
import org.jboss.ejb.client.annotation.ClientInterceptorPriority;
import org.jboss.ejb.client.annotation.ClientTransactionPolicy;
import org.wildfly.transaction.client.AbstractTransaction;
import org.wildfly.transaction.client.ContextTransactionManager;
import org.wildfly.transaction.client.LocalTransaction;
import org.wildfly.transaction.client.RemoteTransaction;

/**
 * The client interceptor which associates the current transaction with the
 * invocation. Additionally, it influences discovery to "stick"
 * load-balanced requests to a single node during the scope of a transaction.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:jason.greened@redhat.com">Jason T. Greene</a>
 */
@ClientInterceptorPriority(TransactionInterceptor.PRIORITY)
public final class TransactionInterceptor implements EJBClientInterceptor {
    private static final ContextTransactionManager transactionManager = ContextTransactionManager.getInstance();

    static final Object RESOURCE_KEY = new Object();
    static final AttachmentKey<Collection<URI>> PREFERRED_DESTINATIONS = new AttachmentKey<>();
    static final AttachmentKey<ConcurrentMap<Application, URI>> APPLICATIONS = new AttachmentKey<>();

    /**
     * This interceptor's priority.
     */
    public static final int PRIORITY = ClientInterceptorPriority.JBOSS_BEFORE;

    /**
     * Construct a new instance.
     */
    public TransactionInterceptor() {
    }

    private static URI getApplicationAssociation(ConcurrentMap<Application, URI> applications, AbstractInvocationContext context) {
        return applications.get(toApplication(context.getLocator().getIdentifier()));
    }

    static Application toApplication(EJBIdentifier id) {
        return new Application(id.getAppName(), id.getModuleName(), id.getDistinctName());
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Application, URI> getOrCreateApplicationMap(AbstractTransaction transaction) {
        Object resource = transaction.getResource(RESOURCE_KEY);
        ConcurrentMap<Application, URI> map = null;
        if (resource == null) {
            map = new ConcurrentHashMap<>();
            resource = transaction.putResourceIfAbsent(RESOURCE_KEY, map);
        }

        return resource == null ? map : ConcurrentMap.class.cast(resource);
    }

    @Override
    public SessionID handleSessionCreation(EJBSessionCreationInvocationContext context) throws Exception {
        AbstractTransaction transaction = context.getTransaction();
        if (Logs.INVOCATION.isDebugEnabled()) {
            Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: Calling  handleSessionCreation: context transaction = %s", transaction);
        }

        // While session requests currently only utilize the caller thread,
        // this will support any future use of a worker. Additionally hides
        // TX from other interceptors, providing consistency with standard
        // invocation handling.
        if (transaction ==  null) {
            transaction = transactionManager.getTransaction();
            context.setTransaction(transaction);
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: Calling  handleSessionCreation: setting context transaction to caller transaction: caller transaction = %s", transaction);
            }
        }

        setupStickinessIfRequired(context, true, transaction);

        Transaction old = transactionManager.suspend();
        try {
            return context.proceed();
        } finally {
            transactionManager.resume(old);
        }
    }

    private void setupSessionAffinitiesIfNeeded(AbstractInvocationContext context) {
        if (Logs.INVOCATION.isDebugEnabled()) {
            Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: calling DiscoveryEJBClientInterceptor.setupSessionAffinitiesIfNeeded");
        }
        if (context instanceof EJBSessionCreationInvocationContext) {
            DiscoveryEJBClientInterceptor.setupSessionAffinities((EJBSessionCreationInvocationContext)context);
        }
    }

    private void setupStickinessIfRequired(AbstractInvocationContext context, boolean propagate, AbstractTransaction transaction) {

        ConcurrentMap<Application, URI> applications = null;
        if (transaction instanceof RemoteTransaction) {

            final URI location = ((RemoteTransaction) transaction).getLocation();
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: calling setupStickinessIfRequired with RemoteTransaction, transaction location = %s", location);
            }
            // we can only route this request to one place; do not load-balance
            if (location != null) {
                if (Logs.INVOCATION.isDebugEnabled()) {
                    Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: setting destination = %s", location);
                }
                context.setDestination(location);
                setupSessionAffinitiesIfNeeded(context);
            }
        }  else if (transaction instanceof LocalTransaction && propagate){

            applications = getOrCreateApplicationMap(transaction);
            URI destination = getApplicationAssociation(applications, context);
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: calling setupStickinessIfRequired with LocalTransaction, application map = %s, application destination = %s", applications, destination);
            }
            if (destination != null) {
                if (Logs.INVOCATION.isDebugEnabled()) {
                    Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: setting destination = %s", destination);
                }
                context.setDestination(destination);
                setupSessionAffinitiesIfNeeded(context);
            } else {
                if (applications.size() > 0) {
                    if (Logs.INVOCATION.isDebugEnabled()) {
                        Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: setting preferred destinations using map = %s", applications);
                    }
                    context.putAttachment(PREFERRED_DESTINATIONS, applications.values());
                }
                if (Logs.INVOCATION.isDebugEnabled()) {
                    Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: setting applications attachment using map = %s", applications);
                }
                context.putAttachment(APPLICATIONS, applications);
            }
        }
    }

    public void handleInvocation(final EJBClientInvocationContext context) throws Exception {
        final ClientTransactionPolicy transactionPolicy = context.getTransactionPolicy();
        AbstractTransaction transaction = context.getTransaction();

        if (Logs.INVOCATION.isDebugEnabled()) {
            Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: calling handleInvocation, context transaction = %s, transaction policy = %s", transaction, transactionPolicy);
        }

        // Always prefer the context TX, as the caller TX might be wrong
        // (e.g. retries happen in worker thread, not caller thread)
        if (transaction == null) {
            transaction = transactionManager.getTransaction();
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: calling handleInvocation, no context transaction; using caller transaction = %s", transaction);
            }
        }

        final boolean propagate = transactionPolicy.propagate() && !context.isSuppressTxPropagation();

        setupStickinessIfRequired(context, propagate, transaction);

        if (transactionPolicy.failIfTransactionAbsent()) {
            if (transaction == null) {
                throw Logs.TXN.txNotActiveForThread();
            }
        }
        if (transactionPolicy.failIfTransactionPresent()) {
            if (transaction != null) {
                throw Logs.TXN.txAlreadyAssociatedWithThread();
            }
        }
        if (propagate) {
            if (Logs.INVOCATION.isDebugEnabled()) {
                Logs.INVOCATION.debugf("TransactionEJBClientInterceptor: Calling  handleInvocation: setting context transaction: transaction = %s", transaction);
            }
            context.setTransaction(transaction);
        }

        // Hide any caller TX from other interceptors
        Transaction old = transactionManager.suspend();
        try {
            context.sendRequest();
        } finally {
            transactionManager.resume(old);
        }
    }

    public Object handleInvocationResult(final EJBClientInvocationContext context) throws Exception {
        return context.getResult();
    }

    final static class Application {
        private String application;
        private String distinctName;

        public Application(String application, String moduleName, String distinctName) {
            this.application = application.isEmpty()? moduleName: application;
            this.distinctName = distinctName;
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }

            if (! (o instanceof Application)) {
                return false;
            }

            Application that = (Application) o;

            return application.equals(that.application) && distinctName.equals(that.distinctName);
        }

        @Override
        public int hashCode() {
            int result = application.hashCode();
            result = 31 * result + distinctName.hashCode();
            return result;
        }

        @Override
        public String toString() {
            return "Application{" +
                    "application='" + application + '\'' +
                    ", distinctName='" + distinctName + '\'' +
                    '}';
        }
    }
}
