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

package pascal.taie.analysis.dataflow.analysis.constprop;

import pascal.taie.analysis.dataflow.analysis.AbstractDataflowAnalysis;
import pascal.taie.analysis.graph.cfg.CFG;
import pascal.taie.config.AnalysisConfig;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.ArithmeticExp;
import pascal.taie.ir.exp.BinaryExp;
import pascal.taie.ir.exp.BitwiseExp;
import pascal.taie.ir.exp.ConditionExp;
import pascal.taie.ir.exp.Exp;
import pascal.taie.ir.exp.IntLiteral;
import pascal.taie.ir.exp.ShiftExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.DefinitionStmt;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.type.PrimitiveType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;

public class ConstantPropagation extends
        AbstractDataflowAnalysis<Stmt, CPFact> {

    public static final String ID = "constprop";

    public ConstantPropagation(AnalysisConfig config) {
        super(config);
    }

    @Override
    public boolean isForward() {
        return true;
    }

    @Override
    public CPFact newBoundaryFact(CFG<Stmt> cfg) {
        // 入口边界：参数来自调用方，保守设为 NAC（非常量）。
        CPFact boundary = new CPFact();
        IR ir = cfg.getIR();
        ir.getParams().forEach(param -> {
            if (canHoldInt(param)) {
                boundary.update(param, Value.getNAC());
            }
        });
        return boundary;
    }

    @Override
    public CPFact newInitialFact() {
        return new CPFact();
    }

    @Override
    public void meetInto(CPFact fact, CPFact target) {
        // 对每个变量做逐点 meet，合并到目标 fact。
        fact.forEach((var, value) ->
                target.update(var, meetValue(value, target.get(var))));
    }

    /**
     * Meets two Values.
     */
    public Value meetValue(Value v1, Value v2) {
        // meet 规则：UNDEF 不影响另一侧；任一 NAC 则为 NAC；不同常量合并为 NAC。
        if (v1.isUndef()) {
            return v2;
        }
        if (v2.isUndef()) {
            return v1;
        }
        if (v1.isNAC() || v2.isNAC()) {
            return Value.getNAC();
        }
        return v1.equals(v2) ? v1 : Value.getNAC();
    }

    @Override
    public boolean transferNode(Stmt stmt, CPFact in, CPFact out) {
        CPFact oldOut = out.copy();
        // 默认恒等传递：先令 out = in。
        out.clear();
        out.copyFrom(in);

        // 仅处理 int-like 左值定义语句，其它语句保持恒等传递。
        if (stmt instanceof DefinitionStmt<?, ?> defStmt
                && defStmt.getLValue() instanceof Var lhs
                && canHoldInt(lhs)) {
            Object rValue = defStmt.getRValue();
            Value value = rValue instanceof Exp exp ? evaluate(exp, in) : Value.getNAC();
            out.update(lhs, value);
        }
        return !out.equals(oldOut);
    }

    /**
     * @return true if the given variable can hold integer value, otherwise false.
     */
    public static boolean canHoldInt(Var var) {
        Type type = var.getType();
        if (type instanceof PrimitiveType) {
            switch ((PrimitiveType) type) {
                case BYTE:
                case SHORT:
                case INT:
                case CHAR:
                case BOOLEAN:
                    return true;
            }
        }
        return false;
    }

    /**
     * Evaluates the {@link Value} of given expression.
     *
     * @param exp the expression to be evaluated
     * @param in  IN fact of the statement
     * @return the resulting {@link Value}
     */
    public static Value evaluate(Exp exp, CPFact in) {
        // 直接常量。
        if (exp instanceof IntLiteral intLiteral) {
            return Value.makeConstant(intLiteral.getValue());
        }
        // 变量：读取 IN 中的抽象值；对临时常量变量直接取字面量值。
        if (exp instanceof Var var) {
            if (!canHoldInt(var)) {
                return Value.getNAC();
            }
            if (var.isTempConst() && var.getTempConstValue() instanceof IntLiteral intLiteral) {
                return Value.makeConstant(intLiteral.getValue());
            }
            return in.get(var);
        }
        if (!(exp instanceof BinaryExp binaryExp)) {
            return Value.getNAC();
        }

        Var operand1 = binaryExp.getOperand1();
        Var operand2 = binaryExp.getOperand2();
        if (!canHoldInt(operand1) || !canHoldInt(operand2)) {
            return Value.getNAC();
        }
        Value value1 = evaluate(operand1, in);
        Value value2 = evaluate(operand2, in);
        // 对 / 和 %，若右操作数可确定为 0，则按作业语义直接返回 UNDEF。
        if (exp instanceof ArithmeticExp arithmeticExp
                && (arithmeticExp.getOperator() == ArithmeticExp.Op.DIV
                || arithmeticExp.getOperator() == ArithmeticExp.Op.REM)
                && value2.isConstant() && value2.getConstant() == 0) {
            return Value.getUndef();
        }
        // NAC 优先传播；否则只要有 UNDEF，结果为 UNDEF。
        if (value1.isNAC() || value2.isNAC()) {
            return Value.getNAC();
        }
        if (value1.isUndef() || value2.isUndef()) {
            return Value.getUndef();
        }

        int c1 = value1.getConstant();
        int c2 = value2.getConstant();
        if (exp instanceof ArithmeticExp arithmeticExp) {
            // 算术运算中 / 和 % 遇到除零时按作业要求返回 UNDEF。
            return switch (arithmeticExp.getOperator()) {
                case ADD -> Value.makeConstant(c1 + c2);
                case SUB -> Value.makeConstant(c1 - c2);
                case MUL -> Value.makeConstant(c1 * c2);
                case DIV -> c2 == 0 ? Value.getUndef() : Value.makeConstant(c1 / c2);
                case REM -> c2 == 0 ? Value.getUndef() : Value.makeConstant(c1 % c2);
            };
        }
        if (exp instanceof ConditionExp conditionExp) {
            boolean result = switch (conditionExp.getOperator()) {
                case EQ -> c1 == c2;
                case NE -> c1 != c2;
                case LT -> c1 < c2;
                case GT -> c1 > c2;
                case LE -> c1 <= c2;
                case GE -> c1 >= c2;
            };
            return Value.makeConstant(result ? 1 : 0);
        }
        if (exp instanceof ShiftExp shiftExp) {
            return switch (shiftExp.getOperator()) {
                case SHL -> Value.makeConstant(c1 << c2);
                case SHR -> Value.makeConstant(c1 >> c2);
                case USHR -> Value.makeConstant(c1 >>> c2);
            };
        }
        if (exp instanceof BitwiseExp bitwiseExp) {
            return switch (bitwiseExp.getOperator()) {
                case OR -> Value.makeConstant(c1 | c2);
                case AND -> Value.makeConstant(c1 & c2);
                case XOR -> Value.makeConstant(c1 ^ c2);
            };
        }
        throw new AnalysisException("Unexpected expression: " + exp);
    }
}
