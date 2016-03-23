/*
 * Copyright (c) 2011, 2015, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.graal.nodes.cfg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.vm.ci.common.JVMCIError;

import com.oracle.graal.compiler.common.cfg.AbstractControlFlowGraph;
import com.oracle.graal.compiler.common.cfg.CFGVerifier;
import com.oracle.graal.compiler.common.cfg.Loop;
import com.oracle.graal.debug.Debug;
import com.oracle.graal.graph.Node;
import com.oracle.graal.graph.NodeMap;
import com.oracle.graal.nodes.AbstractBeginNode;
import com.oracle.graal.nodes.AbstractEndNode;
import com.oracle.graal.nodes.ControlSplitNode;
import com.oracle.graal.nodes.EndNode;
import com.oracle.graal.nodes.FixedNode;
import com.oracle.graal.nodes.FixedWithNextNode;
import com.oracle.graal.nodes.IfNode;
import com.oracle.graal.nodes.LoopBeginNode;
import com.oracle.graal.nodes.LoopEndNode;
import com.oracle.graal.nodes.LoopExitNode;
import com.oracle.graal.nodes.MergeNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.StructuredGraph.GuardsStage;

public class ControlFlowGraph implements AbstractControlFlowGraph<Block> {
    /**
     * Don't allow probability values to be become too small as this makes frequency calculations
     * large enough that they can overflow the range of a double. This commonly happens with
     * infinite loops within infinite loops.
     */
    public static final double MIN_PROBABILITY = 0.000001;

    public final StructuredGraph graph;

    private NodeMap<Block> nodeToBlock;
    private Block[] reversePostOrder;
    private List<Loop<Block>> loops;

    public static ControlFlowGraph compute(StructuredGraph graph, boolean connectBlocks, boolean computeLoops, boolean computeDominators, boolean computePostdominators) {
        ControlFlowGraph cfg = new ControlFlowGraph(graph);
        cfg.identifyBlocks();
        cfg.computeProbabilities();

        if (computeLoops) {
            cfg.computeLoopInformation();
        }
        if (computeDominators) {
            AbstractControlFlowGraph.computeDominators(cfg);
        }
        if (computePostdominators) {
            cfg.computePostdominators();
        }
        // there's not much to verify when connectBlocks == false
        assert !(connectBlocks || computeLoops || computeDominators || computePostdominators) || CFGVerifier.verify(cfg);
        return cfg;
    }

    protected ControlFlowGraph(StructuredGraph graph) {
        this.graph = graph;
        this.nodeToBlock = graph.createNodeMap();
    }

    public Block[] getBlocks() {
        return reversePostOrder;
    }

    public Block getStartBlock() {
        return reversePostOrder[0];
    }

    public Block[] reversePostOrder() {
        return reversePostOrder;
    }

    public NodeMap<Block> getNodeToBlock() {
        return nodeToBlock;
    }

    public Block blockFor(Node node) {
        return nodeToBlock.get(node);
    }

    public List<Loop<Block>> getLoops() {
        return loops;
    }

    private void identifyBlock(Block block) {
        FixedWithNextNode cur = block.getBeginNode();
        while (true) {
            assert !cur.isDeleted();
            assert nodeToBlock.get(cur) == null;
            nodeToBlock.set(cur, block);
            FixedNode next = cur.next();
            if (next instanceof AbstractBeginNode) {
                block.endNode = cur;
                return;
            } else if (next instanceof FixedWithNextNode) {
                cur = (FixedWithNextNode) next;
            } else {
                nodeToBlock.set(next, block);
                block.endNode = next;
                return;
            }
        }
    }

    // Identify and connect blocks (including loop backward edges). Predecessors need to be in the
    // order expected when iterating phi inputs.
    private void identifyBlocks() {
        // Find all block headers.
        int numBlocks = 0;
        for (AbstractBeginNode begin : graph.getNodes(AbstractBeginNode.TYPE)) {
            Block block = new Block(begin);
            identifyBlock(block);
            numBlocks++;
        }

        // Compute reverse post order.
        int count = 0;
        NodeMap<Block> nodeMap = this.nodeToBlock;
        Block[] stack = new Block[numBlocks];
        int tos = 0;
        Block startBlock = blockFor(graph.start());
        stack[0] = startBlock;
        startBlock.setPredecessors(Block.EMPTY_ARRAY);
        do {
            Block block = stack[tos];
            int id = block.getId();
            if (id == BLOCK_ID_INITIAL) {
                // First time we see this block: push all successors.
                FixedNode last = block.getEndNode();
                if (last instanceof EndNode) {
                    EndNode endNode = (EndNode) last;
                    Block suxBlock = nodeMap.get(endNode.merge());
                    if (suxBlock.getId() == BLOCK_ID_INITIAL) {
                        stack[++tos] = suxBlock;
                    }
                    block.setSuccessors(new Block[]{suxBlock});
                } else if (last instanceof IfNode) {
                    IfNode ifNode = (IfNode) last;
                    Block trueSucc = nodeMap.get(ifNode.trueSuccessor());
                    stack[++tos] = trueSucc;
                    Block falseSucc = nodeMap.get(ifNode.falseSuccessor());
                    stack[++tos] = falseSucc;
                    block.setSuccessors(new Block[]{trueSucc, falseSucc});
                    Block[] ifPred = new Block[]{block};
                    trueSucc.setPredecessors(ifPred);
                    falseSucc.setPredecessors(ifPred);
                } else if (last instanceof LoopEndNode) {
                    LoopEndNode loopEndNode = (LoopEndNode) last;
                    block.setSuccessors(new Block[]{nodeMap.get(loopEndNode.loopBegin())});
                    // Nothing to do push onto the stack.
                } else {
                    assert !(last instanceof AbstractEndNode) : "Algorithm only supports EndNode and LoopEndNode.";
                    int startTos = tos;
                    Block[] ifPred = new Block[]{block};
                    for (Node suxNode : last.successors()) {
                        Block sux = nodeMap.get(suxNode);
                        stack[++tos] = sux;
                        sux.setPredecessors(ifPred);
                    }
                    int suxCount = tos - startTos;
                    Block[] successors = new Block[suxCount];
                    System.arraycopy(stack, startTos + 1, successors, 0, suxCount);
                    block.setSuccessors(successors);
                }
                block.setId(BLOCK_ID_VISITED);
                AbstractBeginNode beginNode = block.getBeginNode();
                if (beginNode instanceof LoopBeginNode) {
                    computeLoopPredecessors(nodeMap, block, (LoopBeginNode) beginNode);
                } else if (beginNode instanceof MergeNode) {
                    MergeNode mergeNode = (MergeNode) beginNode;
                    int forwardEndCount = mergeNode.forwardEndCount();
                    Block[] predecessors = new Block[forwardEndCount];
                    for (int i = 0; i < forwardEndCount; ++i) {
                        predecessors[i] = nodeMap.get(mergeNode.forwardEndAt(i));
                    }
                    block.setPredecessors(predecessors);
                }

            } else if (id == BLOCK_ID_VISITED) {
                // Second time we see this block: All successors have been processed, so add block
                // to result list. Can safely reuse the stack for this.
                --tos;
                count++;
                int index = numBlocks - count;
                stack[index] = block;
                block.setId(index);
            } else {
                throw JVMCIError.shouldNotReachHere();
            }
        } while (tos >= 0);

        // Compute reverse postorder and number blocks.
        assert count == numBlocks : "all blocks must be reachable";
        this.reversePostOrder = stack;
    }

    private static void computeLoopPredecessors(NodeMap<Block> nodeMap, Block block, LoopBeginNode loopBeginNode) {
        int forwardEndCount = loopBeginNode.forwardEndCount();
        LoopEndNode[] loopEnds = loopBeginNode.orderedLoopEnds();
        Block[] predecessors = new Block[forwardEndCount + loopEnds.length];
        for (int i = 0; i < forwardEndCount; ++i) {
            predecessors[i] = nodeMap.get(loopBeginNode.forwardEndAt(i));
        }
        for (int i = 0; i < loopEnds.length; ++i) {
            predecessors[i + forwardEndCount] = nodeMap.get(loopEnds[i]);
        }
        block.setPredecessors(predecessors);
    }

    private void computeProbabilities() {

        for (Block block : reversePostOrder) {
            Block[] predecessors = block.getPredecessors();

            double probability;
            if (predecessors.length == 0) {
                probability = 1D;
            } else if (predecessors.length == 1) {
                Block pred = predecessors[0];
                probability = pred.probability;
                if (pred.getSuccessorCount() > 1) {
                    assert pred.getEndNode() instanceof ControlSplitNode;
                    ControlSplitNode controlSplit = (ControlSplitNode) pred.getEndNode();
                    probability *= controlSplit.probability(block.getBeginNode());
                }
            } else {
                probability = predecessors[0].probability;
                for (int i = 1; i < predecessors.length; ++i) {
                    probability += predecessors[i].probability;
                }

                if (block.getBeginNode() instanceof LoopBeginNode) {
                    LoopBeginNode loopBegin = (LoopBeginNode) block.getBeginNode();
                    probability *= loopBegin.loopFrequency();
                }
            }
            block.setProbability(probability);
        }

    }

    private void computeLoopInformation() {
        loops = new ArrayList<>();
        if (graph.hasLoops()) {
            Block[] stack = new Block[this.reversePostOrder.length];
            for (Block block : reversePostOrder) {
                AbstractBeginNode beginNode = block.getBeginNode();
                if (beginNode instanceof LoopBeginNode) {
                    Loop<Block> loop = new HIRLoop(block.getLoop(), loops.size(), block);
                    loops.add(loop);
                    block.loop = loop;
                    loop.getBlocks().add(block);

                    LoopBeginNode loopBegin = (LoopBeginNode) beginNode;
                    for (LoopEndNode end : loopBegin.loopEnds()) {
                        Block endBlock = nodeToBlock.get(end);
                        computeLoopBlocks(endBlock, loop, stack, true);
                    }

                    if (graph.getGuardsStage() != GuardsStage.AFTER_FSA) {
                        for (LoopExitNode exit : loopBegin.loopExits()) {
                            Block exitBlock = nodeToBlock.get(exit);
                            assert exitBlock.getPredecessorCount() == 1;
                            computeLoopBlocks(exitBlock.getFirstPredecessor(), loop, stack, true);
                            loop.getExits().add(exitBlock);
                        }

                        // The following loop can add new blocks to the end of the loop's block
                        // list.
                        int size = loop.getBlocks().size();
                        for (int i = 0; i < size; ++i) {
                            Block b = loop.getBlocks().get(i);
                            for (Block sux : b.getSuccessors()) {
                                if (sux.loop != loop) {
                                    AbstractBeginNode begin = sux.getBeginNode();
                                    if (!(begin instanceof LoopExitNode && ((LoopExitNode) begin).loopBegin() == loopBegin)) {
                                        Debug.log(3, "Unexpected loop exit with %s, including whole branch in the loop", sux);
                                        computeLoopBlocks(sux, loop, stack, false);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private static void computeLoopBlocks(Block start, Loop<Block> loop, Block[] stack, boolean usePred) {
        if (start.loop != loop) {
            start.loop = loop;
            stack[0] = start;
            loop.getBlocks().add(start);
            int tos = 0;
            do {
                Block block = stack[tos--];

                // Add predecessors or successors to the loop.
                for (Block b : (usePred ? block.getPredecessors() : block.getSuccessors())) {
                    if (b.loop != loop) {
                        stack[++tos] = b;
                        b.loop = loop;
                        loop.getBlocks().add(b);
                    }
                }
            } while (tos >= 0);
        }
    }

    public void computePostdominators() {

        Block[] reversePostOrderTmp = this.reversePostOrder;

        outer: for (int j = reversePostOrderTmp.length - 1; j >= 0; --j) {
            Block block = reversePostOrderTmp[j];
            if (block.isLoopEnd()) {
                // We do not want the loop header registered as the postdominator of the loop end.
                continue;
            }
            if (block.getSuccessorCount() == 0) {
                // No successors => no postdominator.
                continue;
            }
            Block firstSucc = block.getSuccessors()[0];
            if (block.getSuccessorCount() == 1) {
                block.postdominator = firstSucc;
                continue;
            }
            Block postdominator = firstSucc;
            for (Block sux : block.getSuccessors()) {
                postdominator = commonPostdominator(postdominator, sux);
                if (postdominator == null) {
                    // There is a dead end => no postdominator available.
                    continue outer;
                }
            }
            assert !Arrays.asList(block.getSuccessors()).contains(postdominator) : "Block " + block + " has a wrong post dominator: " + postdominator;
            block.postdominator = postdominator;
        }
    }

    private static Block commonPostdominator(Block a, Block b) {
        Block iterA = a;
        Block iterB = b;
        while (iterA != iterB) {
            if (iterA.getId() < iterB.getId()) {
                iterA = iterA.getPostdominator();
                if (iterA == null) {
                    return null;
                }
            } else {
                assert iterB.getId() < iterA.getId();
                iterB = iterB.getPostdominator();
                if (iterB == null) {
                    return null;
                }
            }
        }
        return iterA;
    }

    public void setNodeToBlock(NodeMap<Block> nodeMap) {
        this.nodeToBlock = nodeMap;
    }
}
