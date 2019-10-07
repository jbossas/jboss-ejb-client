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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.wildfly.common.Assert;

/**
 * A selector which selects and returns a node, from among the passed eligible nodes, that can handle a specific
 * deployment within a EJB client context. Typical usage of {@link DeploymentNodeSelector} involves load balancing
 * calls to multiple nodes which can all handle the same deployment. This allows the application to have a deterministic
 * node selection policy while dealing with multiple nodes with same deployment.
 * <p>
 * Node selection is only used when discovery yields nodes as a result of its query to locate an EJB.  If discovery
 * yields a URI or cluster, this mechanism is not used.
 *
 * @author Jaikiran Pai
 */
public interface DeploymentNodeSelector {

    /**
     * Selects and returns a node from among the <code>eligibleNodes</code> to handle the invocation on a deployment
     * represented by the passed <code>appName</code>, <code>moduleName</code> and <code>distinctName</code> combination.
     * Implementations of this method must <b>not</b> return null or any other node name which isn't in the
     * <code>eligibleNodes</code>
     *
     * @param eligibleNodes the eligible nodes which can handle the deployment; not {@code null}, will not be empty
     * @param appName       the app name of the deployment
     * @param moduleName    the module name of the deployment
     * @param distinctName  the distinct name of the deployment
     * @return the node selection (must not be {@code null})
     */
    String selectNode(final String[] eligibleNodes, final String appName, final String moduleName, final String distinctName);

    /**
     * A deployment node selector which prefers the first node always.  This will generally avoid load balancing in most
     * cases.
     */
    DeploymentNodeSelector FIRST = (eligibleNodes, appName, moduleName, distinctName) -> eligibleNodes[0];

    /**
     * A deployment node selector which randomly chooses the next node.  This will generally provide the best possible
     * load balancing over a large number of requests.
     */
    DeploymentNodeSelector RANDOM = (eligibleNodes, appName, moduleName, distinctName) -> eligibleNodes[ThreadLocalRandom.current().nextInt(eligibleNodes.length)];

    /**
     * A deployment node selector which uses an approximate round-robin policy among all of the eligible nodes.  Note
     * that the round-robin node count may be shared among multiple node sets, thus certain specific usage patterns
     * <em>may</em> defeat the round-robin behavior.
     */
    DeploymentNodeSelector ROUND_ROBIN = new DeploymentNodeSelector() {
        private final AtomicInteger counter = new AtomicInteger();

        public String selectNode(final String[] eligibleNodes, final String appName, final String moduleName, final String distinctName) {
            final int length = eligibleNodes.length;
            assert length > 0;
            return eligibleNodes[Math.floorMod(counter.getAndIncrement(), length)];
        }
    };
}
