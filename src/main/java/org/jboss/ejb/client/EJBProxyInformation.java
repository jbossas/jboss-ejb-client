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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;

import javax.ejb.EJBHome;
import javax.ejb.EJBObject;

import org.jboss.ejb.client.annotation.ClientAsynchronous;
import org.jboss.ejb.client.annotation.ClientInterceptors;
import org.jboss.ejb.client.annotation.ClientTransaction;
import org.jboss.ejb.client.annotation.ClientTransactionPolicy;
import org.jboss.ejb.client.annotation.CompressionHint;
import org.jboss.ejb.client.annotation.Idempotent;

import static java.security.AccessController.doPrivileged;

/**
 * Cached information about an EJB proxy.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class EJBProxyInformation<T> {

    static final boolean ENABLE_SCANNING = SecurityUtils.getBoolean(SystemProperties.VIEW_ANNOTATION_SCAN_ENABLED, true);

    static final Class<?>[] JUST_INV_HANDLER = new Class<?>[] { InvocationHandler.class };

    static final int MT_BUSINESS        = 0;
    static final int MT_EQUALS          = 1;
    static final int MT_HASH_CODE       = 2;
    static final int MT_TO_STRING       = 3;
    static final int MT_GET_PRIMARY_KEY = 4;
    static final int MT_GET_HANDLE      = 5;
    static final int MT_IS_IDENTICAL    = 6;
    static final int MT_GET_HOME_HANDLE = 7;

    private static final ClassValue<EJBProxyInformation<?>> PROXY_INFORMATION_CLASS_VALUE = new ClassValue<EJBProxyInformation<?>>() {
        protected EJBProxyInformation<?> computeValue(final Class<?> type) {
            final SecurityManager sm = System.getSecurityManager();
            if (sm != null) {
                return doPrivileged((PrivilegedAction<EJBProxyInformation<?>>) () -> doCompute(type));
            } else {
                return doCompute(type);
            }
        }

        private <P> EJBProxyInformation<P> doCompute(final Class<P> type) {
            final HashMap<Method, ProxyMethodInfo> fallbackMap = new HashMap<>();
            final IdentityHashMap<Method, ProxyMethodInfo> methodInfoMap = new IdentityHashMap<>();
            final HashMap<EJBMethodLocator, ProxyMethodInfo> methodLocatorMap = new HashMap<>();
            final CompressionHint classCompressionHint = ENABLE_SCANNING ? type.getAnnotation(CompressionHint.class) : null;
            final ClientTransaction classTransactionHint = ENABLE_SCANNING ? type.getAnnotation(ClientTransaction.class) : null;
            final ClientInterceptors classClientInterceptors = ENABLE_SCANNING ? type.getAnnotation(ClientInterceptors.class) : null;
            final EJBClientContext.InterceptorList classInterceptors = getInterceptorsFromAnnotation(classClientInterceptors);

            final int classCompressionLevel;
            final ProxyMethodInfo.CompressionHint classCompressRequest;
            final ProxyMethodInfo.CompressionHint classCompressResponse;
            if (classCompressionHint == null) {
                classCompressionLevel = -1;
                classCompressRequest = classCompressResponse = ProxyMethodInfo.CompressionHint.NONE;
            } else {
                classCompressionLevel = classCompressionHint.compressionLevel() == -1 ? Deflater.DEFAULT_COMPRESSION : classCompressionHint.compressionLevel();
                classCompressRequest = ProxyMethodInfo.CompressionHint.fromBoolean(classCompressionHint.compressRequest());
                classCompressResponse = ProxyMethodInfo.CompressionHint.fromBoolean(classCompressionHint.compressResponse());
            }
            final boolean classIdempotent = ENABLE_SCANNING && type.getAnnotation(Idempotent.class) != null;
            final boolean classAsync = ENABLE_SCANNING && type.getAnnotation(ClientAsynchronous.class) != null;


            final Class<? extends P> proxyClass = Proxy.getProxyClass(type.getClassLoader(), type).asSubclass(type);
            final Constructor<? extends P> constructor;
            try {
                constructor = proxyClass.getConstructor(JUST_INV_HANDLER);
            } catch (NoSuchMethodException e) {
                throw new NoSuchMethodError("No valid constructor found on proxy class");
            }


            final ProxyClassMethodResolver<P> resolver = new ProxyClassMethodResolver<>(proxyClass, constructor);

            for (Method m : type.getMethods()) {
                final Method method = resolver.resolve(m);

                final boolean alwaysAsync = method.getReturnType() == Future.class;
                final boolean idempotent = classIdempotent || ENABLE_SCANNING && method.getAnnotation(Idempotent.class) != null;
                final boolean clientAsync = alwaysAsync || classAsync || ENABLE_SCANNING && method.getAnnotation(ClientAsynchronous.class) != null;
                final CompressionHint compressionHint = ENABLE_SCANNING ? method.getAnnotation(CompressionHint.class) : null;
                final ClientTransaction transactionHint = ENABLE_SCANNING ? method.getAnnotation(ClientTransaction.class) : null;
                final ClientInterceptors clientInterceptors = ENABLE_SCANNING ? method.getAnnotation(ClientInterceptors.class) : null;
                final EJBClientContext.InterceptorList interceptors = getInterceptorsFromAnnotation(clientInterceptors);
                final int compressionLevel;
                final ProxyMethodInfo.CompressionHint compressRequest;
                final ProxyMethodInfo.CompressionHint compressResponse;
                final ClientTransactionPolicy transactionPolicy;
                if (compressionHint == null) {
                    compressionLevel = classCompressionLevel;
                    compressRequest = classCompressRequest;
                    compressResponse = classCompressResponse;
                } else {
                    compressionLevel = compressionHint.compressionLevel() == -1 ? Deflater.DEFAULT_COMPRESSION : compressionHint.compressionLevel();
                    compressRequest = ProxyMethodInfo.CompressionHint.fromBoolean(compressionHint.compressRequest());
                    compressResponse = ProxyMethodInfo.CompressionHint.fromBoolean(compressionHint.compressResponse());
                }
                transactionPolicy = transactionHint != null ? transactionHint.value() : clientAsync ? ClientTransactionPolicy.NOT_SUPPORTED : classTransactionHint != null ? classTransactionHint.value() : ClientTransactionPolicy.SUPPORTS;
                // build the old signature format
                final StringBuilder b = new StringBuilder();
                final Class<?>[] methodParamTypes = method.getParameterTypes();
                final String[] parameterTypeNames = new String[methodParamTypes.length];
                if (parameterTypeNames.length > 0) {
                    b.append(parameterTypeNames[0] = methodParamTypes[0].getName());
                    for (int i = 1; i < methodParamTypes.length; i++) {
                        b.append(',');
                        b.append(parameterTypeNames[i] = methodParamTypes[i].getName());
                    }
                }
                final String methodName = method.getName();
                final int methodType = getMethodType(type, methodName, methodParamTypes);
                final EJBMethodLocator methodLocator = new EJBMethodLocator(methodName, parameterTypeNames);
                final ProxyMethodInfo proxyMethodInfo = new ProxyMethodInfo(methodType, compressionLevel, compressRequest, compressResponse, idempotent, transactionPolicy, method, methodLocator, b.toString(), clientAsync, interceptors);
                methodInfoMap.put(method, proxyMethodInfo);
                fallbackMap.put(method, proxyMethodInfo);
                methodLocatorMap.put(methodLocator, proxyMethodInfo);
            }
            for (Method m : Object.class.getMethods()) {
                final String methodName = m.getName();
                if (!"hashCode".equals(methodName) && !"toString".equals(methodName) && !"equals".equals(methodName)) continue;

                final Method method = resolver.resolve(m);
                final boolean alwaysAsync = false;
                final boolean idempotent = classIdempotent;
                final boolean clientAsync = alwaysAsync || classAsync;
                final EJBClientContext.InterceptorList interceptors = EJBClientContext.InterceptorList.EMPTY;
                final int compressionLevel = classCompressionLevel;
                final ProxyMethodInfo.CompressionHint compressRequest = classCompressRequest;
                final ProxyMethodInfo.CompressionHint compressResponse = classCompressResponse;
                final ClientTransactionPolicy transactionPolicy;
                transactionPolicy = clientAsync ? ClientTransactionPolicy.NOT_SUPPORTED : classTransactionHint != null ? classTransactionHint.value() : ClientTransactionPolicy.SUPPORTS;
                // build the old signature format
                final StringBuilder b = new StringBuilder();
                final Class<?>[] methodParamTypes = method.getParameterTypes();
                final String[] parameterTypeNames = new String[methodParamTypes.length];
                if (parameterTypeNames.length > 0) {
                    b.append(parameterTypeNames[0] = methodParamTypes[0].getName());
                    for (int i = 1; i < methodParamTypes.length; i++) {
                        b.append(',');
                        b.append(parameterTypeNames[i] = methodParamTypes[i].getName());
                    }
                }
                final int methodType = getMethodType(type, methodName, methodParamTypes);
                final EJBMethodLocator methodLocator = new EJBMethodLocator(methodName, parameterTypeNames);
                final ProxyMethodInfo proxyMethodInfo = new ProxyMethodInfo(methodType, compressionLevel, compressRequest, compressResponse, idempotent, transactionPolicy, method, methodLocator, b.toString(), clientAsync, interceptors);
                methodInfoMap.put(method, proxyMethodInfo);
                fallbackMap.put(method, proxyMethodInfo);
                methodLocatorMap.put(methodLocator, proxyMethodInfo);
            }
            return new EJBProxyInformation<>(proxyClass, constructor, methodInfoMap, fallbackMap, methodLocatorMap, classCompressionLevel, classIdempotent, classAsync, classInterceptors);
        }

        private EJBClientContext.InterceptorList getInterceptorsFromAnnotation(final ClientInterceptors classClientInterceptors) {
            if (classClientInterceptors != null) {
                final Class<?>[] interceptorClasses = classClientInterceptors.value();
                final int length = interceptorClasses.length;
                if (length == 0) {
                    return EJBClientContext.InterceptorList.EMPTY;
                } else if (length == 1) {
                    return EJBClientInterceptorInformation.forClass(interceptorClasses[0]).getSingletonList();
                } else {
                    final EJBClientInterceptorInformation[] interceptors = new EJBClientInterceptorInformation[length];
                    for (int i = 0; i < length; i++) {
                        interceptors[i] = EJBClientInterceptorInformation.forClass(interceptorClasses[i]);
                    }
                    return new EJBClientContext.InterceptorList(interceptors);
                }
            } else {
                return EJBClientContext.InterceptorList.EMPTY;
            }
        }

        private int getMethodType(final Class<?> interfaceClass, final String name, final Class<?>[] methodParamTypes) {
            switch (name) {
                case "equals": {
                    if (methodParamTypes.length == 1 && methodParamTypes[0] == Object.class) {
                        return MT_EQUALS;
                    }
                    break;
                }
                case "hashCode": {
                    if (methodParamTypes.length == 0) {
                        return MT_HASH_CODE;
                    }
                    break;
                }
                case "toString": {
                    if (methodParamTypes.length == 0) {
                        return MT_TO_STRING;
                    }
                    break;
                }
                case "getPrimaryKey": {
                    if (methodParamTypes.length == 0 && EJBObject.class.isAssignableFrom(interfaceClass)) {
                        return MT_GET_PRIMARY_KEY;
                    }
                    break;
                }
                case "getHandle": {
                    if (methodParamTypes.length == 0 && EJBObject.class.isAssignableFrom(interfaceClass)) {
                        return MT_GET_HANDLE;
                    }
                    break;
                }
                case "isIdentical": {
                    if (methodParamTypes.length == 1 && EJBObject.class.isAssignableFrom(interfaceClass) && methodParamTypes[0] == EJBObject.class) {
                        return MT_IS_IDENTICAL;
                    }
                    break;
                }
                case "getHomeHandle": {
                    if (methodParamTypes.length == 0 && EJBHome.class.isAssignableFrom(interfaceClass)) {
                        return MT_GET_HOME_HANDLE;
                    }
                    break;
                }
            }
            return MT_BUSINESS;
        }
    };

    EJBProxyInformation(final Class<? extends T> proxyClass, final Constructor<? extends T> proxyConstructor, final IdentityHashMap<Method, ProxyMethodInfo> methodInfoMap, final HashMap<Method, ProxyMethodInfo> fallbackMap, final HashMap<EJBMethodLocator, ProxyMethodInfo> methodLocatorMap, final int classCompressionHint, final boolean classIdempotent, final boolean classAsync, final EJBClientContext.InterceptorList classInterceptors) {
        this.proxyClass = proxyClass;
        this.proxyConstructor = proxyConstructor;
        this.methodInfoMap = methodInfoMap;
        this.fallbackMap = fallbackMap;
        this.methodLocatorMap = methodLocatorMap;
        this.classCompressionHint = classCompressionHint;
        this.classIdempotent = classIdempotent;
        this.classAsync = classAsync;
        this.classInterceptors = classInterceptors;
    }

    @SuppressWarnings("unchecked")
    static <T> EJBProxyInformation<T> forViewType(Class<T> clazz) {
        return (EJBProxyInformation<T>) PROXY_INFORMATION_CLASS_VALUE.get(clazz);
    }

    private final Class<? extends T> proxyClass;
    private final Constructor<? extends T> proxyConstructor;
    private final IdentityHashMap<Method, ProxyMethodInfo> methodInfoMap;
    private final HashMap<Method, ProxyMethodInfo> fallbackMap;
    private final HashMap<EJBMethodLocator, ProxyMethodInfo> methodLocatorMap;
    private final int classCompressionHint;
    private final boolean classIdempotent;
    private final boolean classAsync;
    private final EJBClientContext.InterceptorList classInterceptors;

    boolean hasCompressionHint(Method proxyMethod) {
        final ProxyMethodInfo proxyMethodInfo = getProxyMethodInfo(proxyMethod);
        return proxyMethodInfo != null && proxyMethodInfo.compressionLevel != -1;
    }

    boolean isIdempotent(Method proxyMethod) {
        final ProxyMethodInfo proxyMethodInfo = getProxyMethodInfo(proxyMethod);
        return proxyMethodInfo != null && (classIdempotent || proxyMethodInfo.idempotent);
    }

    Class<? extends T> getProxyClass() {
        return proxyClass;
    }

    Constructor<? extends T> getProxyConstructor() {
        return proxyConstructor;
    }

    int getClassCompressionHint() {
        return classCompressionHint;
    }

    boolean isClassIdempotent() {
        return classIdempotent;
    }

    boolean isClassAsync() {
        return classAsync;
    }

    ProxyMethodInfo getProxyMethodInfo(Method method) {
        final ProxyMethodInfo info = methodInfoMap.get(method);
        return info == null ? fallbackMap.get(method) : info;
    }

    ProxyMethodInfo getProxyMethodInfo(EJBMethodLocator locator) {
        return methodLocatorMap.get(locator);
    }

    EJBClientContext.InterceptorList getClassInterceptors() {
        return classInterceptors;
    }

    Collection<ProxyMethodInfo> getMethods() {
        return methodInfoMap.values();
    }

    static final class ProxyClassMethodResolver<P> implements InvocationHandler {

        private static final Object[] NO_PARAM = new Object[0];
        private static final Map<Class<?>, Object> DEFAULT_VALUES = Stream
                .of(boolean.class, byte.class, char.class, double.class, float.class, int.class, long.class, short.class)
                .collect(Collectors.toMap(clazz -> (Class<?>) clazz, clazz -> Array.get(Array.newInstance(clazz, 1), 0)));


        private P proxyInstance;
        private Method resolvedProxyMethod;

        public ProxyClassMethodResolver(Class<? extends P> proxyClass, Constructor<? extends P> constructor) {
            try {
                proxyInstance = constructor.newInstance(this);
            } catch (InstantiationException e) {
                throw new InstantiationError(e.getMessage());
            } catch (IllegalAccessException e) {
                throw new IllegalAccessError(e.getMessage());
            } catch (InvocationTargetException e) {
                throw new UndeclaredThrowableException(e.getCause());
            }
        }


        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            resolvedProxyMethod = method;
            return DEFAULT_VALUES.get(method.getReturnType());
        }

        public Method resolve(Method method) {
            Object[] parameters = NO_PARAM;
            if(method.getParameterCount() > 0) {
                final Class<?>[] parameterTypes = method.getParameterTypes();
                parameters = new Object[parameterTypes.length];

                for(int i = 0; i < parameters.length; ++i) {
                    parameters[i] = DEFAULT_VALUES.get(parameterTypes[i]);
                }
            }

            try {
                resolvedProxyMethod = null;
                method.invoke(proxyInstance, parameters);
                return resolvedProxyMethod;
            } catch (IllegalAccessException e) {
                throw new IllegalAccessError(e.getMessage());
            } catch (InvocationTargetException e) {
                throw new UndeclaredThrowableException(e.getCause());
            }
        }
    }

    static final class ProxyMethodInfo {

        enum CompressionHint {
            NONE, TRUE, FALSE;

            static CompressionHint fromBoolean(final boolean hint){
                return hint ? TRUE : FALSE;
            }
        }

        final int methodType;
        final int compressionLevel;
        final CompressionHint compressRequest;
        final CompressionHint compressResponse;
        final boolean idempotent;
        final ClientTransactionPolicy transactionPolicy;
        final Method method;
        final EJBMethodLocator methodLocator;
        final String signature;
        final boolean clientAsync;
        final EJBClientContext.InterceptorList interceptors;

        ProxyMethodInfo(final int methodType, final int compressionLevel, final CompressionHint compressRequest, final CompressionHint compressResponse, final boolean idempotent, final ClientTransactionPolicy transactionPolicy, final Method method, final EJBMethodLocator methodLocator, final String signature, final boolean clientAsync, final EJBClientContext.InterceptorList interceptors) {
            this.methodType = methodType;
            this.compressionLevel = compressionLevel;
            this.compressRequest = compressRequest;
            this.compressResponse = compressResponse;
            this.idempotent = idempotent;
            this.transactionPolicy = transactionPolicy;
            this.method = method;
            this.methodLocator = methodLocator;
            this.signature = signature;
            this.clientAsync = clientAsync;
            this.interceptors = interceptors;
        }

        public int getMethodType() {
            return methodType;
        }

        int getCompressionLevel() {
            return compressionLevel;
        }

        boolean isIdempotent() {
            return idempotent;
        }

        ClientTransactionPolicy getTransactionPolicy() {
            return transactionPolicy;
        }

        Method getMethod() {
            return method;
        }

        String getSignature() {
            return signature;
        }

        boolean isClientAsync() {
            return clientAsync;
        }

        CompressionHint getCompressRequestHint() {
            return compressRequest;
        }

        CompressionHint getCompressResponseHint() {
            return compressResponse;
        }

        EJBMethodLocator getMethodLocator() {
            return methodLocator;
        }

        EJBClientContext.InterceptorList getInterceptors() {
            return interceptors;
        }

        boolean isSynchronous() {
            final Class<?> returnType = method.getReturnType();
            return returnType != void.class && returnType != Future.class;
        }
    }
}