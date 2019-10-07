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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.wildfly.common.Assert;

/**
 * A selector which selects and returns a node from the available nodes in a cluster. Typical usage of a
 * {@link ClusterNodeSelector} involve load balancing of calls to various nodes in the cluster.
 *
 * @author Jaikiran Pai
 */
public interface ClusterNodeSelector {

    /**
     * Returns a node from among the {@code totalAvailableNodes}, as the target node for EJB invocations.
     * The selector can decide whether to pick an already connected node (from the passed {@code connectedNodes})
     * or decide to select a node to which a connection hasn't yet been established. If a node to which a connection
     * hasn't been established is selected then the cluster context will create a connection to it.
     *
     * @param clusterName         the name of the cluster to which the nodes belong (will not be {@code null})
     * @param connectedNodes      the node names to which a connection has been established (may be empty but will not be {@code null})
     * @param totalAvailableNodes all available nodes in the cluster, including connected nodes (will not be empty or {@code null})
     * @return the selected node name (must not be {@code null})
     */
    String selectNode(final String clusterName, final String[] connectedNodes, final String[] totalAvailableNodes);

    /**
     * Always use the first available node, regardless of whether it is connected.
     */
    ClusterNodeSelector FIRST_AVAILABLE = (clusterName, connectedNodes, totalAvailableNodes) -> totalAvailableNodes[0];

    /**
     * Always use the first connected node, or fall back to the first available node if none are connected.
     */
    ClusterNodeSelector FIRST_CONNECTED = firstConnected(FIRST_AVAILABLE);

    /**
     * Always use a random connected node, or fall back to a random unconnected node.
     */
    ClusterNodeSelector RANDOM_CONNECTED = useRandomConnectedNode(useRandomUnconnectedNode(FIRST_AVAILABLE));

    /**
     * Use a random available node, regardless of whether it is connected.
     */
    ClusterNodeSelector RANDOM = (clusterName, connectedNodes, totalAvailableNodes) -> totalAvailableNodes[ThreadLocalRandom.current().nextInt(totalAvailableNodes.length)];

    /**
     * A simple default selector which uses {@link #simpleConnectionThresholdRandomSelector(int)} with a minimum of
     * 5 connections.
     */
    ClusterNodeSelector DEFAULT = simpleConnectionThresholdRandomSelector(5);

    /**
     * A simple threshold-based random selector.  If the minimum is met, then a random connected node is used, otherwise
     * a random available node is used, increasing the total number of connections for future invocations.
     *
     * @param minimum the minimum number of connected nodes to attempt to acquire
     * @return the node selector (not {@code null})
     */
    static ClusterNodeSelector simpleConnectionThresholdRandomSelector(int minimum) {
        return minimumConnectionThreshold(minimum, RANDOM, useRandomConnectedNode(useRandomUnconnectedNode(FIRST_AVAILABLE)));
    }

    /**
     * Always try to use the first connected node.  If no nodes are connected, the fallback is used.
     *
     * @param fallback the fallback selector (must not be {@code null})
     * @return the node selector (not {@code null})
     */
    static ClusterNodeSelector firstConnected(ClusterNodeSelector fallback) {
        Assert.checkNotNullParam("fallback", fallback);
        return (clusterName, connectedNodes, totalAvailableNodes) -> connectedNodes.length > 0 ? connectedNodes[0] : fallback.selectNode(clusterName, connectedNodes, totalAvailableNodes);
    }

    /**
     * Always try to use a random connected node.  If no nodes are connected, the fallback is used.
     *
     * @param fallback the fallback selector (must not be {@code null})
     * @return the node selector (not {@code null})
     */
    static ClusterNodeSelector useRandomConnectedNode(ClusterNodeSelector fallback) {
        Assert.checkNotNullParam("fallback", fallback);
        return (clusterName, connectedNodes, totalAvailableNodes) -> {
            final int length = connectedNodes.length;
            return length > 0 ? connectedNodes[ThreadLocalRandom.current().nextInt(length)] : fallback.selectNode(clusterName, connectedNodes, totalAvailableNodes);
        };
    }

    /**
     * Determine the action to take based on a threshold of minimum connections.  If the minimum is met, <em>or</em> if
     * there are no more available nodes to choose from, the {@code met} selector is used, otherwise the {@code unmet}
     * selector is used.
     *
     * @param minimum the minimum number of connections
     * @param unmet the selector to use when the number of connections is below the minimum and there are unconnected nodes (must not be {@code null})
     * @param met the selector to use when the number of connections is at or above the minimum or there are no unconnected nodes (must not be {@code null})
     * @return the node selector (not {@code null})
     */
    static ClusterNodeSelector minimumConnectionThreshold(int minimum, ClusterNodeSelector unmet, ClusterNodeSelector met) {
        return (clusterName, connectedNodes, totalAvailableNodes) -> (connectedNodes.length < minimum ? unmet : met).selectNode(clusterName, connectedNodes, totalAvailableNodes);
    }

    /**
     * Always try to use an unconnected node.  If all nodes are connected, the fallback is used.
     *
     * @param fallback the selector to use if all available nodes are connected (must not be {@code null})
     * @return the node selector (not {@code null})
     */
    static ClusterNodeSelector useRandomUnconnectedNode(ClusterNodeSelector fallback) {
        Assert.checkNotNullParam("fallback", fallback);
        return (clusterName, connectedNodes, totalAvailableNodes) -> {
            if (connectedNodes.length == totalAvailableNodes.length) {
                // totalAvailableNodes contains all connectedNodes; if their sizes are equal then all nodes must be connected
                return fallback.selectNode(clusterName, connectedNodes, totalAvailableNodes);
            }
            final HashSet<String> connected = new HashSet<>(connectedNodes.length);
            Collections.addAll(connected, connectedNodes);
            final ArrayList<String> available = new ArrayList<>(totalAvailableNodes.length);
            Collections.addAll(available, totalAvailableNodes);
            available.removeAll(connected);
            assert ! available.isEmpty();
            return available.get(ThreadLocalRandom.current().nextInt(available.size()));
        };
    }
}
