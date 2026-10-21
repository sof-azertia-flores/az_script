package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O3 unchecked arithmetic (exec v11). An int add, subtract or multiply whose
 * operand ranges prove that the result fits in int32 becomes its wrapping form
 * (opcodes 55–57), which never reports overflow. Operands are evaluated as
 * before, and a wrapping operation behaves like the checked one for every other
 * operand type, so only the overflow check disappears.
 */
final class ExecUncheckedArithmetic {
    private final ExecScalarAnalysis analysis;
    private final List<AcsObject> proven = new ArrayList<>();

    private ExecUncheckedArithmetic(ExecScalarAnalysis analysis) { this.analysis = analysis; }

    static void optimize(AcsArray functions) {
        Map<Integer,Integer> returns = new HashMap<>();
        for (var item : functions.acsa) returns.put(((AcsObject)item).getAsInt("id"), ((AcsObject)item).getAsInt("return-type"));
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            AcsElement body = function.mmp.get("script");
            // The analysis bounds of O2 regions; facts hold in a statement only when its expressions write no local.
            if ((long)function.getAsInt("param-count") + function.getAsInt("local-count") > 256
                    || count(body) > MAX_NODES || !ExecSequenceInliner.analyzable(body)) continue;
            var pass = new ExecUncheckedArithmetic(new ExecScalarAnalysis(function, returns, true));
            pass.statement(body);
            // Decided on the original tree, so the analysis never sees a rewritten operand.
            for (var operation : pass.proven) operation.put("c", operation.getAsInt("c") - ADD + WRAP_ADD);
        }
    }
    private void statement(AcsElement value) {
        if (value instanceof AcsArray block) { for (var item : block.acsa) statement(item); return; }
        int op = opcode(value);
        // A cleanup body runs on paths the analysis does not follow: only its protected region is visited.
        if (op == CLEANUP) { statement(field(value, "v")); return; }
        Block site = analysis.sites.get(value);
        for (var child : ExecShape.children(value)) {
            var role = child.role();
            if (role == ExecShape.Role.CONTEXT || role == ExecShape.Role.METHOD_ENTRY) continue;
            if (role == ExecShape.Role.BLOCK || (op == IF || op == WHILE) && !"v".equals(child.field())) statement(child.value());
            else if (site != null && site.in != null) expression(child.value(), site.in);
        }
    }
    private void expression(AcsElement value, Map<Integer,Value> facts) {
        int op = opcode(value);
        if (op == ADD || op == SUBTRACT || op == MULTIPLY) {
            Value a = analysis.evaluate(field(value, "v1"), facts), b = analysis.evaluate(field(value, "v2"), facts);
            if (a.type() == INT_VALUE && b.type() == INT_VALUE && fits(op, a, b)) proven.add((AcsObject)value);
        }
        for (var child : ExecShape.children(value)) {
            var role = child.role();
            if (role == ExecShape.Role.CONTEXT || role == ExecShape.Role.METHOD_ENTRY) continue;
            // Statements of a sequence read the same facts: the sequence writes no local.
            if (role == ExecShape.Role.BLOCK) statementsOf(child.value(), facts);
            else expression(child.value(), facts);
        }
    }
    private void statementsOf(AcsElement value, Map<Integer,Value> facts) {
        if (value instanceof AcsArray block) { for (var item : block.acsa) statementsOf(item, facts); return; }
        // A nested loop or branch may run its body with other facts.
        int op = opcode(value);
        if (op == IF || op == WHILE || op == CLEANUP) return;
        expression(value, facts);
    }
    private static boolean fits(int op, Value a, Value b) {
        long low, high;
        if (op == ADD) { low = a.low() + b.low(); high = a.high() + b.high(); }
        else if (op == SUBTRACT) { low = a.low() - b.high(); high = a.high() - b.low(); }
        else {
            long[] products = {a.low() * b.low(), a.low() * b.high(), a.high() * b.low(), a.high() * b.high()};
            low = Arrays.stream(products).min().orElseThrow(); high = Arrays.stream(products).max().orElseThrow();
        }
        return low >= Integer.MIN_VALUE && high <= Integer.MAX_VALUE;
    }
}
