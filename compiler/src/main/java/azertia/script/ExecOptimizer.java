package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.Map;
import static azertia.script.ExecOpcodes.*;

/** Post-validation optimizations of compiler-owned exec trees, before debug numbering. */
final class ExecOptimizer {
    private ExecOptimizer() {}

    static AcsElement constants(AcsElement expression, Map<AcsElement,int[]> positions) {
        return constants(ExecIr.analyze(expression, positions).body(), positions);
    }

    private static AcsElement constants(ExecIr.Node node, Map<AcsElement,int[]> positions) {
        AcsElement expression = node.source();
        for (ExecIr.Edge edge : node.children()) {
            ExecShape.Child child = edge.location();
            AcsElement next = constants(edge.value(), positions);
            if (next == child.value()) continue;
            replace(expression, child, next);
        }
        AcsElement folded = fold(expression);
        if (folded != expression && positions != null) {
            int[] position = positions.get(expression);
            if (position != null) positions.put(folded, position);
        }
        return folded;
    }

    static void replace(AcsElement expression, ExecShape.Child child, AcsElement next) {
        if (child.field() == null) ((AcsArray) expression).acsa.set(child.index(), next);
        else if (child.index() >= 0)
            ((AcsObject) expression).getAsAcsArray(child.field()).acsa.set(child.index(), next);
        else ((AcsObject) expression).mmp.put(child.field(), next);
    }

    static AcsElement fold(AcsElement expression) {
        if (!(expression instanceof AcsObject node) || node.getAsInt("t") != 0) return expression;
        int opcode = node.getAsInt("c");
        AcsElement operand = node.mmp.get("v");
        // A failed constant calculation stays a runtime operation at its source
        // position. In particular Java's MIN_VALUE / -1 must not silently wrap.
        try {
            if (opcode == NEGATE && operand instanceof AcsIntegerElement integer)
                return integer(-(long) integer.s);
            if (opcode == NOT && operand instanceof AcsBooleanElement bool)
                return new AcsBooleanElement(!bool.on);
            AcsElement left = node.mmp.get("v1"), right = node.mmp.get("v2");
            if (left instanceof AcsIntegerElement a && right instanceof AcsIntegerElement b) {
                long x = a.s, y = b.s;
                return switch (opcode) {
                    case ADD -> integer(x + y);
                    case SUBTRACT -> integer(x - y);
                    case MULTIPLY -> integer(x * y);
                    case DIVIDE -> integer(x / y);
                    case MODULO -> integer(x % y);
                    case GREATER -> new AcsBooleanElement(x > y);
                    case LESS -> new AcsBooleanElement(x < y);
                    case EQUAL -> new AcsBooleanElement(x == y);
                    case NOT_EQUAL -> new AcsBooleanElement(x != y);
                    case GREATER_EQUAL -> new AcsBooleanElement(x >= y);
                    case LESS_EQUAL -> new AcsBooleanElement(x <= y);
                    case BIT_AND -> integer(a.s & b.s);
                    case BIT_OR -> integer(a.s | b.s);
                    case BIT_XOR -> integer(a.s ^ b.s);
                    // Shifts operate on int32 bit patterns; Java's masking of
                    // the shift distance is deliberately not used for bad counts.
                    case SHIFT_LEFT -> shift(b.s) ? new AcsIntegerElement(a.s << b.s) : expression;
                    case SHIFT_RIGHT -> shift(b.s) ? new AcsIntegerElement(a.s >> b.s) : expression;
                    case SHIFT_UNSIGNED -> shift(b.s) ? new AcsIntegerElement(a.s >>> b.s) : expression;
                    default -> expression;
                };
            }
            if (left instanceof AcsBooleanElement a && right instanceof AcsBooleanElement b)
                return switch (opcode) {
                    case AND -> new AcsBooleanElement(a.on && b.on);
                    case OR -> new AcsBooleanElement(a.on || b.on);
                    case EQUAL -> new AcsBooleanElement(a.on == b.on);
                    case NOT_EQUAL -> new AcsBooleanElement(a.on != b.on);
                    default -> expression;
                };
        } catch (ArithmeticException runtimeFailure) {
            return expression;
        }
        return expression;
    }

    private static AcsIntegerElement integer(long value) { return new AcsIntegerElement(Math.toIntExact(value)); }
    private static boolean shift(int count) { return count >= 0 && count <= 31; }
}
