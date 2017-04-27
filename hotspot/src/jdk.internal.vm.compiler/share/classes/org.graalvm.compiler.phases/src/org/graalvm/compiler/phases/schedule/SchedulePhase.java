/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.graalvm.compiler.phases.schedule;

import static org.graalvm.compiler.core.common.GraalOptions.OptScheduleOutOfLoops;
import static org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph.strictlyDominates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Formatter;
import java.util.List;

import org.graalvm.compiler.core.common.LocationIdentity;
import org.graalvm.compiler.core.common.SuppressFBWarnings;
import org.graalvm.compiler.core.common.cfg.AbstractControlFlowGraph;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.graph.Graph.NodeEvent;
import org.graalvm.compiler.graph.Graph.NodeEventListener;
import org.graalvm.compiler.graph.Graph.NodeEventScope;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeBitMap;
import org.graalvm.compiler.graph.NodeMap;
import org.graalvm.compiler.graph.NodeStack;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.ControlSinkNode;
import org.graalvm.compiler.nodes.ControlSplitNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.GuardNode;
import org.graalvm.compiler.nodes.LoopBeginNode;
import org.graalvm.compiler.nodes.LoopExitNode;
import org.graalvm.compiler.nodes.PhiNode;
import org.graalvm.compiler.nodes.ProxyNode;
import org.graalvm.compiler.nodes.StartNode;
import org.graalvm.compiler.nodes.StateSplit;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.GuardsStage;
import org.graalvm.compiler.nodes.StructuredGraph.ScheduleResult;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.VirtualState;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.cfg.ControlFlowGraph;
import org.graalvm.compiler.nodes.cfg.HIRLoop;
import org.graalvm.compiler.nodes.cfg.LocationSet;
import org.graalvm.compiler.nodes.memory.FloatingReadNode;
import org.graalvm.compiler.nodes.memory.MemoryCheckpoint;
import org.graalvm.compiler.nodes.memory.MemoryNode;
import org.graalvm.compiler.nodes.memory.MemoryPhiNode;
import org.graalvm.compiler.phases.Phase;

public final class SchedulePhase extends Phase {

    public enum SchedulingStrategy {
        EARLIEST,
        LATEST,
        LATEST_OUT_OF_LOOPS,
        FINAL_SCHEDULE
    }

    private final SchedulingStrategy selectedStrategy;

    private final boolean immutableGraph;

    public SchedulePhase() {
        this(false);
    }

    public SchedulePhase(boolean immutableGraph) {
        this(OptScheduleOutOfLoops.getValue() ? SchedulingStrategy.LATEST_OUT_OF_LOOPS : SchedulingStrategy.LATEST, immutableGraph);
    }

    public SchedulePhase(SchedulingStrategy strategy) {
        this(strategy, false);
    }

    public SchedulePhase(SchedulingStrategy strategy, boolean immutableGraph) {
        this.selectedStrategy = strategy;
        this.immutableGraph = immutableGraph;
    }

    private NodeEventScope verifyImmutableGraph(StructuredGraph graph) {
        boolean assertionsEnabled = false;
        assert (assertionsEnabled = true) == true;
        if (immutableGraph && assertionsEnabled) {
            return graph.trackNodeEvents(new NodeEventListener() {
                @Override
                public void event(NodeEvent e, Node node) {
                    assert false : "graph changed: " + e + " on node " + node;
                }
            });
        } else {
            return null;
        }
    }

    @Override
    @SuppressWarnings("try")
    protected void run(StructuredGraph graph) {
        try (NodeEventScope scope = verifyImmutableGraph(graph)) {
            Instance inst = new Instance();
            inst.run(graph, selectedStrategy, immutableGraph);
        }
    }

    public static class Instance {

        /**
         * Map from blocks to the nodes in each block.
         */
        protected ControlFlowGraph cfg;
        protected BlockMap<List<Node>> blockToNodesMap;
        protected NodeMap<Block> nodeToBlockMap;

        @SuppressWarnings("try")
        public void run(StructuredGraph graph, SchedulingStrategy selectedStrategy, boolean immutableGraph) {
            // assert GraphOrder.assertNonCyclicGraph(graph);
            cfg = ControlFlowGraph.compute(graph, true, true, true, false);

            NodeMap<Block> currentNodeMap = graph.createNodeMap();
            NodeBitMap visited = graph.createNodeBitMap();
            BlockMap<List<Node>> earliestBlockToNodesMap = new BlockMap<>(cfg);
            this.nodeToBlockMap = currentNodeMap;
            this.blockToNodesMap = earliestBlockToNodesMap;

            scheduleEarliestIterative(earliestBlockToNodesMap, currentNodeMap, visited, graph, immutableGraph);

            if (selectedStrategy != SchedulingStrategy.EARLIEST) {
                // For non-earliest schedules, we need to do a second pass.
                BlockMap<List<Node>> latestBlockToNodesMap = new BlockMap<>(cfg);
                for (Block b : cfg.getBlocks()) {
                    latestBlockToNodesMap.put(b, new ArrayList<Node>());
                }

                BlockMap<ArrayList<FloatingReadNode>> watchListMap = calcLatestBlocks(selectedStrategy, currentNodeMap, earliestBlockToNodesMap, visited, latestBlockToNodesMap, immutableGraph);
                sortNodesLatestWithinBlock(cfg, earliestBlockToNodesMap, latestBlockToNodesMap, currentNodeMap, watchListMap, visited);

                assert verifySchedule(cfg, latestBlockToNodesMap, currentNodeMap);
                assert MemoryScheduleVerification.check(cfg.getStartBlock(), latestBlockToNodesMap);

                this.blockToNodesMap = latestBlockToNodesMap;

                cfg.setNodeToBlock(currentNodeMap);
            }

            graph.setLastSchedule(new ScheduleResult(this.cfg, this.nodeToBlockMap, this.blockToNodesMap));
        }

        @SuppressFBWarnings(value = "RCN_REDUNDANT_NULLCHECK_WOULD_HAVE_BEEN_A_NPE", justification = "false positive found by findbugs")
        private BlockMap<ArrayList<FloatingReadNode>> calcLatestBlocks(SchedulingStrategy strategy, NodeMap<Block> currentNodeMap, BlockMap<List<Node>> earliestBlockToNodesMap, NodeBitMap visited,
                        BlockMap<List<Node>> latestBlockToNodesMap, boolean immutableGraph) {
            BlockMap<ArrayList<FloatingReadNode>> watchListMap = new BlockMap<>(cfg);
            Block[] reversePostOrder = cfg.reversePostOrder();
            for (int j = reversePostOrder.length - 1; j >= 0; --j) {
                Block currentBlock = reversePostOrder[j];
                List<Node> blockToNodes = earliestBlockToNodesMap.get(currentBlock);
                LocationSet killed = null;
                int previousIndex = blockToNodes.size();
                for (int i = blockToNodes.size() - 1; i >= 0; --i) {
                    Node currentNode = blockToNodes.get(i);
                    assert currentNodeMap.get(currentNode) == currentBlock;
                    assert !(currentNode instanceof PhiNode) && !(currentNode instanceof ProxyNode);
                    assert visited.isMarked(currentNode);
                    if (currentNode instanceof FixedNode) {
                        // For these nodes, the earliest is at the same time the latest block.
                    } else {
                        Block latestBlock = null;

                        LocationIdentity constrainingLocation = null;
                        if (currentNode instanceof FloatingReadNode) {
                            // We are scheduling a floating read node => check memory
                            // anti-dependencies.
                            FloatingReadNode floatingReadNode = (FloatingReadNode) currentNode;
                            LocationIdentity location = floatingReadNode.getLocationIdentity();
                            if (location.isMutable()) {
                                // Location can be killed.
                                constrainingLocation = location;
                                if (currentBlock.canKill(location)) {
                                    if (killed == null) {
                                        killed = new LocationSet();
                                    }
                                    fillKillSet(killed, blockToNodes.subList(i + 1, previousIndex));
                                    previousIndex = i;
                                    if (killed.contains(location)) {
                                        // Earliest block kills location => we need to stay within
                                        // earliest block.
                                        latestBlock = currentBlock;
                                    }
                                }
                            }
                        }

                        if (latestBlock == null) {
                            // We are not constraint within earliest block => calculate optimized
                            // schedule.
                            calcLatestBlock(currentBlock, strategy, currentNode, currentNodeMap, constrainingLocation, watchListMap, latestBlockToNodesMap, visited, immutableGraph);
                        } else {
                            selectLatestBlock(currentNode, currentBlock, latestBlock, currentNodeMap, watchListMap, constrainingLocation, latestBlockToNodesMap);
                        }
                    }
                }
            }
            return watchListMap;
        }

        protected static void selectLatestBlock(Node currentNode, Block currentBlock, Block latestBlock, NodeMap<Block> currentNodeMap, BlockMap<ArrayList<FloatingReadNode>> watchListMap,
                        LocationIdentity constrainingLocation, BlockMap<List<Node>> latestBlockToNodesMap) {

            assert checkLatestEarliestRelation(currentNode, currentBlock, latestBlock);
            if (currentBlock != latestBlock) {
                currentNodeMap.setAndGrow(currentNode, latestBlock);

                if (constrainingLocation != null && latestBlock.canKill(constrainingLocation)) {
                    if (watchListMap.get(latestBlock) == null) {
                        watchListMap.put(latestBlock, new ArrayList<>());
                    }
                    watchListMap.get(latestBlock).add((FloatingReadNode) currentNode);
                }
            }

            latestBlockToNodesMap.get(latestBlock).add(currentNode);
        }

        private static boolean checkLatestEarliestRelation(Node currentNode, Block earliestBlock, Block latestBlock) {
            assert AbstractControlFlowGraph.dominates(earliestBlock, latestBlock) || (currentNode instanceof VirtualState && latestBlock == earliestBlock.getDominator()) : String.format(
                            "%s %s (%s) %s (%s)", currentNode, earliestBlock, earliestBlock.getBeginNode(), latestBlock, latestBlock.getBeginNode());
            return true;
        }

        private static boolean verifySchedule(ControlFlowGraph cfg, BlockMap<List<Node>> blockToNodesMap, NodeMap<Block> nodeMap) {
            for (Block b : cfg.getBlocks()) {
                List<Node> nodes = blockToNodesMap.get(b);
                for (Node n : nodes) {
                    assert n.isAlive();
                    assert nodeMap.get(n) == b;
                    StructuredGraph g = (StructuredGraph) n.graph();
                    if (g.hasLoops() && g.getGuardsStage() == GuardsStage.AFTER_FSA && n instanceof DeoptimizeNode) {
                        assert b.getLoopDepth() == 0 : n;
                    }
                }
            }
            return true;
        }

        public static Block checkKillsBetween(Block earliestBlock, Block latestBlock, LocationIdentity location) {
            assert strictlyDominates(earliestBlock, latestBlock);
            Block current = latestBlock.getDominator();

            // Collect dominator chain that needs checking.
            List<Block> dominatorChain = new ArrayList<>();
            dominatorChain.add(latestBlock);
            while (current != earliestBlock) {
                // Current is an intermediate dominator between earliestBlock and latestBlock.
                assert strictlyDominates(earliestBlock, current) && strictlyDominates(current, latestBlock);
                if (current.canKill(location)) {
                    dominatorChain.clear();
                }
                dominatorChain.add(current);
                current = current.getDominator();
            }

            // The first element of dominatorChain now contains the latest possible block.
            assert dominatorChain.size() >= 1;
            assert dominatorChain.get(dominatorChain.size() - 1).getDominator() == earliestBlock;

            Block lastBlock = earliestBlock;
            for (int i = dominatorChain.size() - 1; i >= 0; --i) {
                Block currentBlock = dominatorChain.get(i);
                if (currentBlock.getLoopDepth() > lastBlock.getLoopDepth()) {
                    // We are entering a loop boundary. The new loops must not kill the location for
                    // the crossing to be safe.
                    if (currentBlock.getLoop() != null && ((HIRLoop) currentBlock.getLoop()).canKill(location)) {
                        break;
                    }
                }

                if (currentBlock.canKillBetweenThisAndDominator(location)) {
                    break;
                }
                lastBlock = currentBlock;
            }

            return lastBlock;
        }

        private static void fillKillSet(LocationSet killed, List<Node> subList) {
            if (!killed.isAny()) {
                for (Node n : subList) {
                    // Check if this node kills a node in the watch list.
                    if (n instanceof MemoryCheckpoint.Single) {
                        LocationIdentity identity = ((MemoryCheckpoint.Single) n).getLocationIdentity();
                        killed.add(identity);
                        if (killed.isAny()) {
                            return;
                        }
                    } else if (n instanceof MemoryCheckpoint.Multi) {
                        for (LocationIdentity identity : ((MemoryCheckpoint.Multi) n).getLocationIdentities()) {
                            killed.add(identity);
                            if (killed.isAny()) {
                                return;
                            }
                        }
                    }
                }
            }
        }

        private static void sortNodesLatestWithinBlock(ControlFlowGraph cfg, BlockMap<List<Node>> earliestBlockToNodesMap, BlockMap<List<Node>> latestBlockToNodesMap, NodeMap<Block> currentNodeMap,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, NodeBitMap visited) {
            for (Block b : cfg.getBlocks()) {
                sortNodesLatestWithinBlock(b, earliestBlockToNodesMap, latestBlockToNodesMap, currentNodeMap, watchListMap, visited);
            }
        }

        private static void sortNodesLatestWithinBlock(Block b, BlockMap<List<Node>> earliestBlockToNodesMap, BlockMap<List<Node>> latestBlockToNodesMap, NodeMap<Block> nodeMap,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, NodeBitMap unprocessed) {
            List<Node> earliestSorting = earliestBlockToNodesMap.get(b);
            ArrayList<Node> result = new ArrayList<>(earliestSorting.size());
            ArrayList<FloatingReadNode> watchList = null;
            if (watchListMap != null) {
                watchList = watchListMap.get(b);
                assert watchList == null || !b.getKillLocations().isEmpty();
            }
            AbstractBeginNode beginNode = b.getBeginNode();
            if (beginNode instanceof LoopExitNode) {
                LoopExitNode loopExitNode = (LoopExitNode) beginNode;
                for (ProxyNode proxy : loopExitNode.proxies()) {
                    unprocessed.clear(proxy);
                    ValueNode value = proxy.value();
                    // if multiple proxies reference the same value, schedule the value of a
                    // proxy once
                    if (value != null && nodeMap.get(value) == b && unprocessed.isMarked(value)) {
                        sortIntoList(value, b, result, nodeMap, unprocessed, null);
                    }
                }
            }
            FixedNode endNode = b.getEndNode();
            FixedNode fixedEndNode = null;
            if (isFixedEnd(endNode)) {
                // Only if the end node is either a control split or an end node, we need to force
                // it to be the last node in the schedule.
                fixedEndNode = endNode;
            }
            for (Node n : earliestSorting) {
                if (n != fixedEndNode) {
                    if (n instanceof FixedNode) {
                        assert nodeMap.get(n) == b;
                        checkWatchList(b, nodeMap, unprocessed, result, watchList, n);
                        sortIntoList(n, b, result, nodeMap, unprocessed, null);
                    } else if (nodeMap.get(n) == b && n instanceof FloatingReadNode) {
                        FloatingReadNode floatingReadNode = (FloatingReadNode) n;
                        LocationIdentity location = floatingReadNode.getLocationIdentity();
                        if (b.canKill(location)) {
                            // This read can be killed in this block, add to watch list.
                            if (watchList == null) {
                                watchList = new ArrayList<>();
                            }
                            watchList.add(floatingReadNode);
                        }
                    }
                }
            }

            for (Node n : latestBlockToNodesMap.get(b)) {
                assert nodeMap.get(n) == b : n;
                assert !(n instanceof FixedNode);
                if (unprocessed.isMarked(n)) {
                    sortIntoList(n, b, result, nodeMap, unprocessed, fixedEndNode);
                }
            }

            if (endNode != null && unprocessed.isMarked(endNode)) {
                sortIntoList(endNode, b, result, nodeMap, unprocessed, null);
            }

            latestBlockToNodesMap.put(b, result);
        }

        private static void checkWatchList(Block b, NodeMap<Block> nodeMap, NodeBitMap unprocessed, ArrayList<Node> result, ArrayList<FloatingReadNode> watchList, Node n) {
            if (watchList != null && !watchList.isEmpty()) {
                // Check if this node kills a node in the watch list.
                if (n instanceof MemoryCheckpoint.Single) {
                    LocationIdentity identity = ((MemoryCheckpoint.Single) n).getLocationIdentity();
                    checkWatchList(watchList, identity, b, result, nodeMap, unprocessed);
                } else if (n instanceof MemoryCheckpoint.Multi) {
                    for (LocationIdentity identity : ((MemoryCheckpoint.Multi) n).getLocationIdentities()) {
                        checkWatchList(watchList, identity, b, result, nodeMap, unprocessed);
                    }
                }
            }
        }

        private static void checkWatchList(ArrayList<FloatingReadNode> watchList, LocationIdentity identity, Block b, ArrayList<Node> result, NodeMap<Block> nodeMap, NodeBitMap unprocessed) {
            assert identity.isMutable();
            if (identity.isAny()) {
                for (FloatingReadNode r : watchList) {
                    if (unprocessed.isMarked(r)) {
                        sortIntoList(r, b, result, nodeMap, unprocessed, null);
                    }
                }
                watchList.clear();
            } else {
                int index = 0;
                while (index < watchList.size()) {
                    FloatingReadNode r = watchList.get(index);
                    LocationIdentity locationIdentity = r.getLocationIdentity();
                    assert locationIdentity.isMutable();
                    if (unprocessed.isMarked(r)) {
                        if (identity.overlaps(locationIdentity)) {
                            sortIntoList(r, b, result, nodeMap, unprocessed, null);
                        } else {
                            ++index;
                            continue;
                        }
                    }
                    int lastIndex = watchList.size() - 1;
                    watchList.set(index, watchList.get(lastIndex));
                    watchList.remove(lastIndex);
                }
            }
        }

        private static void sortIntoList(Node n, Block b, ArrayList<Node> result, NodeMap<Block> nodeMap, NodeBitMap unprocessed, Node excludeNode) {
            assert unprocessed.isMarked(n) : n;
            assert nodeMap.get(n) == b;

            if (n instanceof PhiNode) {
                return;
            }

            unprocessed.clear(n);

            for (Node input : n.inputs()) {
                if (nodeMap.get(input) == b && unprocessed.isMarked(input) && input != excludeNode) {
                    sortIntoList(input, b, result, nodeMap, unprocessed, excludeNode);
                }
            }

            if (n instanceof ProxyNode) {
                // Skip proxy nodes.
            } else {
                result.add(n);
            }

        }

        protected void calcLatestBlock(Block earliestBlock, SchedulingStrategy strategy, Node currentNode, NodeMap<Block> currentNodeMap, LocationIdentity constrainingLocation,
                        BlockMap<ArrayList<FloatingReadNode>> watchListMap, BlockMap<List<Node>> latestBlockToNodesMap, NodeBitMap visited, boolean immutableGraph) {
            Block latestBlock = null;
            assert currentNode.hasUsages();
            for (Node usage : currentNode.usages()) {
                if (immutableGraph && !visited.contains(usage)) {
                    /*
                     * Normally, dead nodes are deleted by the scheduler before we reach this point.
                     * Only when the scheduler is asked to not modify a graph, we can see dead nodes
                     * here.
                     */
                    continue;
                }
                latestBlock = calcBlockForUsage(currentNode, usage, latestBlock, currentNodeMap);
            }

            if (strategy == SchedulingStrategy.FINAL_SCHEDULE || strategy == SchedulingStrategy.LATEST_OUT_OF_LOOPS) {
                assert latestBlock != null;
                while (latestBlock.getLoopDepth() > earliestBlock.getLoopDepth() && latestBlock != earliestBlock.getDominator()) {
                    latestBlock = latestBlock.getDominator();
                }
            }

            if (latestBlock != earliestBlock && latestBlock != earliestBlock.getDominator() && constrainingLocation != null) {
                latestBlock = checkKillsBetween(earliestBlock, latestBlock, constrainingLocation);
            }

            selectLatestBlock(currentNode, earliestBlock, latestBlock, currentNodeMap, watchListMap, constrainingLocation, latestBlockToNodesMap);
        }

        private static Block calcBlockForUsage(Node node, Node usage, Block startBlock, NodeMap<Block> currentNodeMap) {
            assert !(node instanceof PhiNode);
            Block currentBlock = startBlock;
            if (usage instanceof PhiNode) {
                // An input to a PhiNode is used at the end of the predecessor block that
                // corresponds to the PhiNode input. One PhiNode can use an input multiple times.
                PhiNode phi = (PhiNode) usage;
                AbstractMergeNode merge = phi.merge();
                Block mergeBlock = currentNodeMap.get(merge);
                for (int i = 0; i < phi.valueCount(); ++i) {
                    if (phi.valueAt(i) == node) {
                        Block otherBlock = mergeBlock.getPredecessors()[i];
                        currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, otherBlock);
                    }
                }
            } else if (usage instanceof AbstractBeginNode) {
                AbstractBeginNode abstractBeginNode = (AbstractBeginNode) usage;
                if (abstractBeginNode instanceof StartNode) {
                    currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, currentNodeMap.get(abstractBeginNode));
                } else {
                    Block otherBlock = currentNodeMap.get(abstractBeginNode).getDominator();
                    currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, otherBlock);
                }
            } else {
                // All other types of usages: Put the input into the same block as the usage.
                Block otherBlock = currentNodeMap.get(usage);
                currentBlock = AbstractControlFlowGraph.commonDominatorTyped(currentBlock, otherBlock);
            }
            return currentBlock;
        }

        private void scheduleEarliestIterative(BlockMap<List<Node>> blockToNodes, NodeMap<Block> nodeToBlock, NodeBitMap visited, StructuredGraph graph, boolean immutableGraph) {

            BitSet floatingReads = new BitSet(cfg.getBlocks().length);

            // Add begin nodes as the first entry and set the block for phi nodes.
            for (Block b : cfg.getBlocks()) {
                AbstractBeginNode beginNode = b.getBeginNode();
                ArrayList<Node> nodes = new ArrayList<>();
                nodeToBlock.set(beginNode, b);
                nodes.add(beginNode);
                blockToNodes.put(b, nodes);

                if (beginNode instanceof AbstractMergeNode) {
                    AbstractMergeNode mergeNode = (AbstractMergeNode) beginNode;
                    for (PhiNode phi : mergeNode.phis()) {
                        nodeToBlock.set(phi, b);
                    }
                } else if (beginNode instanceof LoopExitNode) {
                    LoopExitNode loopExitNode = (LoopExitNode) beginNode;
                    for (ProxyNode proxy : loopExitNode.proxies()) {
                        nodeToBlock.set(proxy, b);
                    }
                }
            }

            NodeStack stack = new NodeStack();

            // Start analysis with control flow ends.
            Block[] reversePostOrder = cfg.reversePostOrder();
            for (int j = reversePostOrder.length - 1; j >= 0; --j) {
                Block b = reversePostOrder[j];
                FixedNode endNode = b.getEndNode();
                if (isFixedEnd(endNode)) {
                    stack.push(endNode);
                    nodeToBlock.set(endNode, b);
                }
            }

            processStack(cfg, blockToNodes, nodeToBlock, visited, floatingReads, stack);

            // Visit back input edges of loop phis.
            boolean changed;
            boolean unmarkedPhi;
            do {
                changed = false;
                unmarkedPhi = false;
                for (LoopBeginNode loopBegin : graph.getNodes(LoopBeginNode.TYPE)) {
                    for (PhiNode phi : loopBegin.phis()) {
                        if (visited.isMarked(phi)) {
                            for (int i = 0; i < loopBegin.getLoopEndCount(); ++i) {
                                Node node = phi.valueAt(i + loopBegin.forwardEndCount());
                                if (node != null && !visited.isMarked(node)) {
                                    changed = true;
                                    stack.push(node);
                                    processStack(cfg, blockToNodes, nodeToBlock, visited, floatingReads, stack);
                                }
                            }
                        } else {
                            unmarkedPhi = true;
                        }
                    }
                }

                /*
                 * the processing of one loop phi could have marked a previously checked loop phi,
                 * therefore this needs to be iterative.
                 */
            } while (unmarkedPhi && changed);

            // Check for dead nodes.
            if (!immutableGraph && visited.getCounter() < graph.getNodeCount()) {
                for (Node n : graph.getNodes()) {
                    if (!visited.isMarked(n)) {
                        n.clearInputs();
                        n.markDeleted();
                    }
                }
            }

            // Add end nodes as the last nodes in each block.
            for (Block b : cfg.getBlocks()) {
                FixedNode endNode = b.getEndNode();
                if (isFixedEnd(endNode)) {
                    if (endNode != b.getBeginNode()) {
                        addNode(blockToNodes, b, endNode);
                    }
                }
            }

            if (!floatingReads.isEmpty()) {
                for (Block b : cfg.getBlocks()) {
                    if (floatingReads.get(b.getId())) {
                        resortEarliestWithinBlock(b, blockToNodes, nodeToBlock, visited);
                    }
                }
            }

            assert MemoryScheduleVerification.check(cfg.getStartBlock(), blockToNodes);
        }

        private static boolean isFixedEnd(FixedNode endNode) {
            return endNode instanceof ControlSplitNode || endNode instanceof ControlSinkNode || endNode instanceof AbstractEndNode;
        }

        private static void resortEarliestWithinBlock(Block b, BlockMap<List<Node>> blockToNodes, NodeMap<Block> nodeToBlock, NodeBitMap unprocessed) {
            ArrayList<FloatingReadNode> watchList = new ArrayList<>();
            List<Node> oldList = blockToNodes.get(b);
            AbstractBeginNode beginNode = b.getBeginNode();
            for (Node n : oldList) {
                if (n instanceof FloatingReadNode) {
                    FloatingReadNode floatingReadNode = (FloatingReadNode) n;
                    LocationIdentity locationIdentity = floatingReadNode.getLocationIdentity();
                    MemoryNode lastLocationAccess = floatingReadNode.getLastLocationAccess();
                    if (locationIdentity.isMutable() && lastLocationAccess != null) {
                        ValueNode lastAccessLocation = lastLocationAccess.asNode();
                        if (nodeToBlock.get(lastAccessLocation) == b && lastAccessLocation != beginNode && !(lastAccessLocation instanceof MemoryPhiNode)) {
                            // This node's last access location is within this block. Add to watch
                            // list when processing the last access location.
                        } else {
                            watchList.add(floatingReadNode);
                        }
                    }
                }
            }

            ArrayList<Node> newList = new ArrayList<>(oldList.size());
            assert oldList.get(0) == beginNode;
            unprocessed.clear(beginNode);
            newList.add(beginNode);
            for (int i = 1; i < oldList.size(); ++i) {
                Node n = oldList.get(i);
                if (unprocessed.isMarked(n)) {
                    if (n instanceof MemoryNode) {
                        if (n instanceof MemoryCheckpoint) {
                            assert n instanceof FixedNode;
                            if (watchList.size() > 0) {
                                // Check whether we need to commit reads from the watch list.
                                checkWatchList(b, nodeToBlock, unprocessed, newList, watchList, n);
                            }
                        }
                        // Add potential dependent reads to the watch list.
                        for (Node usage : n.usages()) {
                            if (usage instanceof FloatingReadNode) {
                                FloatingReadNode floatingReadNode = (FloatingReadNode) usage;
                                if (nodeToBlock.get(floatingReadNode) == b && floatingReadNode.getLastLocationAccess() == n && !(n instanceof MemoryPhiNode)) {
                                    watchList.add(floatingReadNode);
                                }
                            }
                        }
                    }
                    assert unprocessed.isMarked(n);
                    unprocessed.clear(n);
                    newList.add(n);
                } else {
                    // This node was pulled up.
                    assert !(n instanceof FixedNode) : n;
                }
            }

            for (Node n : newList) {
                unprocessed.mark(n);
            }

            assert newList.size() == oldList.size();
            blockToNodes.put(b, newList);
        }

        private static void addNode(BlockMap<List<Node>> blockToNodes, Block b, Node endNode) {
            assert !blockToNodes.get(b).contains(endNode) : endNode;
            blockToNodes.get(b).add(endNode);
        }

        private static void processStack(ControlFlowGraph cfg, BlockMap<List<Node>> blockToNodes, NodeMap<Block> nodeToBlock, NodeBitMap visited, BitSet floatingReads, NodeStack stack) {
            Block startBlock = cfg.getStartBlock();
            while (!stack.isEmpty()) {
                Node current = stack.peek();
                if (visited.checkAndMarkInc(current)) {

                    // Push inputs and predecessor.
                    Node predecessor = current.predecessor();
                    if (predecessor != null) {
                        stack.push(predecessor);
                    }

                    if (current instanceof PhiNode) {
                        processStackPhi(stack, (PhiNode) current);
                    } else if (current instanceof ProxyNode) {
                        processStackProxy(stack, (ProxyNode) current);
                    } else if (current instanceof FrameState) {
                        processStackFrameState(stack, current);
                    } else {
                        current.pushInputs(stack);
                    }
                } else {
                    stack.pop();
                    if (nodeToBlock.get(current) == null) {
                        Block curBlock = cfg.blockFor(current);
                        if (curBlock == null) {
                            assert current.predecessor() == null && !(current instanceof FixedNode) : "The assignment of blocks to fixed nodes is already done when constructing the cfg.";
                            Block earliest = startBlock;
                            for (Node input : current.inputs()) {
                                Block inputEarliest = nodeToBlock.get(input);
                                if (inputEarliest == null) {
                                    assert current instanceof FrameState && input instanceof StateSplit && ((StateSplit) input).stateAfter() == current : current;
                                } else {
                                    assert inputEarliest != null;
                                    if (inputEarliest.getEndNode() == input) {
                                        // This is the last node of the block.
                                        if (current instanceof FrameState && input instanceof StateSplit && ((StateSplit) input).stateAfter() == current) {
                                            // Keep regular inputEarliest.
                                        } else if (input instanceof ControlSplitNode) {
                                            inputEarliest = nodeToBlock.get(((ControlSplitNode) input).getPrimarySuccessor());
                                        } else {
                                            assert inputEarliest.getSuccessorCount() == 1;
                                            assert !(input instanceof AbstractEndNode);
                                            // Keep regular inputEarliest
                                        }
                                    }
                                    if (earliest.getDominatorDepth() < inputEarliest.getDominatorDepth()) {
                                        earliest = inputEarliest;
                                    }
                                }
                            }
                            curBlock = earliest;
                        }
                        assert curBlock != null;
                        addNode(blockToNodes, curBlock, current);
                        nodeToBlock.set(current, curBlock);
                        if (current instanceof FloatingReadNode) {
                            FloatingReadNode floatingReadNode = (FloatingReadNode) current;
                            if (curBlock.canKill(floatingReadNode.getLocationIdentity())) {
                                floatingReads.set(curBlock.getId());
                            }
                        }
                    }
                }
            }
        }

        private static void processStackFrameState(NodeStack stack, Node current) {
            for (Node input : current.inputs()) {
                if (input instanceof StateSplit && ((StateSplit) input).stateAfter() == current) {
                    // Ignore the cycle.
                } else {
                    stack.push(input);
                }
            }
        }

        private static void processStackProxy(NodeStack stack, ProxyNode proxyNode) {
            LoopExitNode proxyPoint = proxyNode.proxyPoint();
            for (Node input : proxyNode.inputs()) {
                if (input != proxyPoint) {
                    stack.push(input);
                }
            }
        }

        private static void processStackPhi(NodeStack stack, PhiNode phiNode) {
            AbstractMergeNode merge = phiNode.merge();
            for (int i = 0; i < merge.forwardEndCount(); ++i) {
                Node input = phiNode.valueAt(i);
                if (input != null) {
                    stack.push(input);
                }
            }
        }

        public String printScheduleHelper(String desc) {
            Formatter buf = new Formatter();
            buf.format("=== %s / %s ===%n", getCFG().getStartBlock().getBeginNode().graph(), desc);
            for (Block b : getCFG().getBlocks()) {
                buf.format("==== b: %s (loopDepth: %s). ", b, b.getLoopDepth());
                buf.format("dom: %s. ", b.getDominator());
                buf.format("preds: %s. ", Arrays.toString(b.getPredecessors()));
                buf.format("succs: %s ====%n", Arrays.toString(b.getSuccessors()));

                if (blockToNodesMap.get(b) != null) {
                    for (Node n : nodesFor(b)) {
                        printNode(n);
                    }
                } else {
                    for (Node n : b.getNodes()) {
                        printNode(n);
                    }
                }
            }
            buf.format("%n");
            return buf.toString();
        }

        private static void printNode(Node n) {
            Formatter buf = new Formatter();
            buf.format("%s", n);
            if (n instanceof MemoryCheckpoint.Single) {
                buf.format(" // kills %s", ((MemoryCheckpoint.Single) n).getLocationIdentity());
            } else if (n instanceof MemoryCheckpoint.Multi) {
                buf.format(" // kills ");
                for (LocationIdentity locid : ((MemoryCheckpoint.Multi) n).getLocationIdentities()) {
                    buf.format("%s, ", locid);
                }
            } else if (n instanceof FloatingReadNode) {
                FloatingReadNode frn = (FloatingReadNode) n;
                buf.format(" // from %s", frn.getLocationIdentity());
                buf.format(", lastAccess: %s", frn.getLastLocationAccess());
                buf.format(", address: %s", frn.getAddress());
            } else if (n instanceof GuardNode) {
                buf.format(", anchor: %s", ((GuardNode) n).getAnchor());
            }
            Debug.log("%s", buf);
        }

        public ControlFlowGraph getCFG() {
            return cfg;
        }

        /**
         * Gets the nodes in a given block.
         */
        public List<Node> nodesFor(Block block) {
            return blockToNodesMap.get(block);
        }
    }

}
