## Plan: 完成 A1 活跃变量与迭代求解器

基于 Tai-e 现有框架，先补全后向活跃变量分析的四个核心 API，再补全后向初始化与迭代求解循环，使 `livevar` 在测试基准上与期望结果一致。重点是严格对齐 `DataflowAnalysis` 契约：`OUT` 由后继 `IN` meet 得到，`IN` 由 `transferNode` 从 `OUT` 推导，并保证初始化与边界条件匹配后向分析语义。

### Steps
1. 对齐接口语义：核对 `DataflowAnalysis`、`SetFact`、`DataflowResult` 与 `CFG` 的调用约束（[DataflowAnalysis.java](src/main/java/pascal/taie/analysis/dataflow/analysis/DataflowAnalysis.java), [SetFact.java](src/main/java/pascal/taie/analysis/dataflow/fact/SetFact.java), [DataflowResult.java](src/main/java/pascal/taie/analysis/dataflow/fact/DataflowResult.java), [CFG.java](src/main/java/pascal/taie/analysis/graph/cfg/CFG.java)）。
2. 补全 `LiveVariableAnalysis`：实现 `newBoundaryFact`、`newInitialFact`、`meetInto`、`transferNode`（[LiveVariableAnalysis.java](src/main/java/pascal/taie/analysis/dataflow/analysis/LiveVariableAnalysis.java)）。
3. 细化 `transferNode` 规则：先从 `out` 复制到 `in`，再 kill `stmt.getDef()` 中的 `Var`，再 gen `stmt.getUses()` 中的 `Var`，最后返回是否变化（`Stmt.getDef`/`Stmt.getUses`）。
4. 实现后向初始化：为 `cfg` 全节点设置 `IN`/`OUT` 初值，`exit` 用 `newBoundaryFact`，其余用 `newInitialFact`，并满足 `OUT` 初值与 `IN` 一致（[Solver.java](src/main/java/pascal/taie/analysis/dataflow/solver/Solver.java)）。
5. 实现后向迭代求解：循环直至不变；每轮对节点重算 `OUT = meet(succ.IN)`，再调用 `analysis.transferNode(node, in, out)` 传播变化（[IterativeSolver.java](src/main/java/pascal/taie/analysis/dataflow/solver/IterativeSolver.java)）。
6. 用现有基准检查一致性：对照 [LiveVarTest.java](src/test/java/pascal/taie/analysis/dataflow/analysis/LiveVarTest.java) 与 `*-livevar-expected.txt` 逐项确认输出格式与集合内容。

### Further Considerations
1. `newBoundaryFact` 是否取空集？建议 A: 采用空集（经典 live variable）/ B: 若课程另有约定再调整。
2. 迭代是否遍历 `entry/exit` 节点？建议 A: 全节点统一处理 / B: 仅语句节点并显式保留边界节点事实。
3. `getDef()` 非 `Var`（如字段/数组左值）如何处理？建议 A: 仅 kill `Var` / B: 扩展规则前先确认评分标准。请先确认选项后我再细化下一版计划。
