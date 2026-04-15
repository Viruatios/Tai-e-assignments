/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.dataflow.solver;

import pascal.taie.analysis.dataflow.analysis.DataflowAnalysis;
import pascal.taie.analysis.dataflow.fact.DataflowResult;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.analysis.graph.cfg.Edge;

import java.util.ArrayDeque;
import java.util.Queue;

class WorkListSolver<Node, Fact> extends Solver<Node, Fact> {

    WorkListSolver(DataflowAnalysis<Node, Fact> analysis) {
        super(analysis);
    }

    @Override
    protected void doSolveForward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        // 工作队列迭代：不断传播直到达到不动点。
        Queue<Node> workList = new ArrayDeque<>(cfg.getNodes());
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            if (!cfg.isEntry(node)) {
                // IN[node] = meet(所有前驱的 OUT，经需要时的边转移)。
                Fact in = analysis.newInitialFact();
                for (Edge<Node> inEdge : cfg.getInEdgesOf(node)) {
                    Fact predOut = result.getOutFact(inEdge.getSource());
                    if (analysis.needTransferEdge(inEdge)) {
                        predOut = analysis.transferEdge(inEdge, predOut);
                    }
                    analysis.meetInto(predOut, in);
                }
                result.setInFact(node, in);
            }
            // 只有 OUT 发生变化时才把后继重新入队，避免无效迭代。
            if (analysis.transferNode(node, result.getInFact(node), result.getOutFact(node))) {
                for (Edge<Node> outEdge : cfg.getOutEdgesOf(node)) {
                    workList.offer(outEdge.getTarget());
                }
            }
        }
    }

    @Override
    protected void doSolveBackward(CFG<Node> cfg, DataflowResult<Node, Fact> result) {
        throw new UnsupportedOperationException();
    }
}
