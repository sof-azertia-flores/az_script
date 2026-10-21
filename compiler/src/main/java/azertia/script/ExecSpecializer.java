package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O3 specialization (exec v11). A call with int/bool constants for parameters its
 * closed scalar callee never writes gets a private clone (entry kind 2) with the
 * constants substituted and the O2 stages run again; the clone is kept only when
 * it is at least a quarter smaller. A call of a generic function with fixed
 * contexts gets a private clone without hidden contexts. Clones take ids of the
 * callee's namespace from 0xffff downward; their debug records show the original.
 */
final class ExecSpecializer {
    static final int PER_FUNCTION = 4, PER_MODULE = 64;
    private final AcsArray functions;
    private final Set<Compiler.OptimizationPass> passes;
    private final Map<AcsElement,int[]> positions;
    private final int abdLimit;
    private final Map<Integer,AcsObject> byId = new HashMap<>();
    private final Set<Integer> used = new HashSet<>(), safe;
    private final Map<String,Integer> versions = new HashMap<>();
    private final Map<Integer,Integer> perFunction = new HashMap<>(), originals = new LinkedHashMap<>();
    private final List<AcsObject> clones = new ArrayList<>();

    private ExecSpecializer(AcsArray functions, Set<Integer> reserved, Set<Compiler.OptimizationPass> passes,
                            Map<AcsElement,int[]> positions, int abdLimit) {
        this.functions = functions; this.passes = passes; this.positions = positions; this.abdLimit = abdLimit;
        for (var item : functions.acsa) {
            var function = (AcsObject)item; byId.put(function.getAsInt("id"), function); used.add(function.getAsInt("id"));
        }
        used.addAll(reserved);
        safe = safeFunctions(functions);
    }
    /** Appends the kept clones to functions; the result maps each clone to its original. */
    static Map<Integer,Integer> optimize(AcsArray functions, Set<Integer> reserved, Set<Compiler.OptimizationPass> passes,
                                         Map<AcsElement,int[]> positions, int abdLimit) {
        var pass = new ExecSpecializer(functions, reserved, passes, positions, abdLimit);
        for (var item : List.copyOf(functions.acsa)) pass.visit(((AcsObject)item).mmp.get("script"));
        functions.acsa.addAll(pass.clones);
        return pass.originals;
    }

    private void visit(AcsElement value) {
        if (value == null) return;
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) visit(child.value());
        if (opcode(value) != CALL) return;
        var call = (AcsObject)value;
        AcsObject callee = byId.get(call.getAsInt("id"));
        if (callee == null || callee.getAsInt("entry-kind") != 0 || !(callee.mmp.get("script") instanceof AcsArray)) return;
        boolean generic = callee.getAsInt("hidden-count") > 0;
        String key = generic ? genericKey(call, callee) : constantKey(call, callee);
        if (key == null) return;
        Integer clone = versions.get(key);
        if (clone == null) {
            int id = callee.getAsInt("id");
            clone = perFunction.getOrDefault(id, 0) >= PER_FUNCTION || clones.size() >= PER_MODULE ? -1
                    : generic ? generic(call, callee) : constant(call, callee);
            versions.put(key, clone);
            if (clone >= 0) perFunction.merge(id, 1, Integer::sum);
        }
        if (clone < 0) return;
        call.put("id", clone.intValue());
        if (generic) call.mmp.put("contexts", new AcsArray());
    }

    // ---- constant arguments --------------------------------------------------

    private String constantKey(AcsObject call, AcsObject callee) {
        if (!safe.contains(callee.getAsInt("id"))) return null;
        if (call.mmp.containsKey("contexts") && !call.getAsAcsArray("contexts").acsa.isEmpty()) return null;
        var arguments = call.getAsAcsArray("param").acsa; var types = callee.getAsAcsArray("param-types").acsa;
        if (arguments.size() != types.size()) return null;
        Set<Integer> written = new HashSet<>(); writes(callee.mmp.get("script"), written);
        StringBuilder key = new StringBuilder("c:").append(callee.getAsInt("id"));
        boolean any = false;
        for (int i = 0; i < arguments.size(); i++) {
            AcsElement argument = arguments.get(i); int type = ((AcsIntegerElement)types.get(i)).s;
            boolean constant = !written.contains(i) && (argument instanceof AcsIntegerElement && type == INT_VALUE
                    || argument instanceof AcsBooleanElement && type == BOOLEAN_VALUE);
            key.append(':').append(constant ? argument.toJson().toString() : "_");
            any |= constant;
        }
        return any ? key.toString() : null;
    }
    private int constant(AcsObject call, AcsObject callee) {
        var clone = (AcsObject)ExecO2.copy(callee, positions);
        var arguments = call.getAsAcsArray("param").acsa;
        Map<Integer,AcsElement> constants = new HashMap<>();
        var types = callee.getAsAcsArray("param-types").acsa;
        Set<Integer> written = new HashSet<>(); writes(callee.mmp.get("script"), written);
        for (int i = 0; i < arguments.size(); i++) {
            AcsElement argument = arguments.get(i); int type = ((AcsIntegerElement)types.get(i)).s;
            if (!written.contains(i) && (argument instanceof AcsIntegerElement && type == INT_VALUE
                    || argument instanceof AcsBooleanElement && type == BOOLEAN_VALUE)) constants.put(i, argument);
        }
        clone.mmp.put("script", substitute(clone.mmp.get("script"), constants));
        int id = allocate(callee.getAsInt("id"));
        clone.put("id", id);
        // The O2 stages see the clone as an ordinary function, then it becomes private.
        ExecO2.optimizeFunction(clone, functions, passes, positions, abdLimit);
        if (4L * count(clone.mmp.get("script")) > 3L * count(callee.mmp.get("script"))) { used.remove(id); return -1; }
        return keep(clone, callee);
    }
    private AcsElement substitute(AcsElement value, Map<Integer,AcsElement> constants) {
        if (value instanceof AcsObject object && opcode(object) == VARIABLE && constants.containsKey(object.getAsInt("v")))
            return ExecO2.locate(ExecO2.copy(constants.get(object.getAsInt("v")), null), value, positions);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) {
                AcsElement next = substitute(child.value(), constants);
                if (next != child.value()) ExecOptimizer.replace(value, child, next);
            }
        return value;
    }

    // ---- generic contexts ----------------------------------------------------

    private String genericKey(AcsObject call, AcsObject callee) {
        if (!call.mmp.containsKey("contexts")) return null;
        var contexts = call.getAsAcsArray("contexts").acsa;
        if (contexts.size() != callee.getAsInt("hidden-count") || contexts.stream().anyMatch(ExecSpecializer::referring)) return null;
        // A body that never names its contexts needs one version for all.
        return "g:" + callee.getAsInt("id") + ":" + (referring(callee.mmp.get("script")) ? call.mmp.get("contexts").toJson().toString() : "*");
    }
    private static boolean referring(AcsElement value) {
        if (value instanceof AcsObject object) {
            if (object.mmp.containsKey("ref") && object.mmp.size() == 1) return true;
            for (var child : object.mmp.values()) if (referring(child)) return true;
        } else if (value instanceof AcsArray array) for (var child : array.acsa) if (referring(child)) return true;
        return false;
    }
    private int generic(AcsObject call, AcsObject callee) {
        var clone = (AcsObject)ExecO2.copy(callee, positions);
        var contexts = call.getAsAcsArray("contexts").acsa;
        clone.mmp.put("script", bind(clone.mmp.get("script"), contexts));
        if (referring(clone.mmp.get("script"))) return -1;
        if (clone.getAsInt("return-type") == ANY_VALUE) {
            // Erased results return through return_typed, now with a fixed context.
            List<AcsElement> returns = new ArrayList<>(); collect(clone.mmp.get("script"), returns);
            Set<Integer> abis = new HashSet<>();
            for (var node : returns) {
                if (opcode(node) != RETURN_TYPED) return -1;
                var context = ((AcsObject)node).getAsAcsObject("context");
                if (!context.mmp.containsKey("abi")) return -1;
                abis.add(context.getAsInt("abi"));
            }
            if (abis.size() != 1 || abis.contains(ANY_VALUE) || abis.contains(VOID_VALUE)) return -1;
            clone.put("return-type", abis.iterator().next().intValue());
        }
        clone.put("hidden-count", 0);
        clone.put("id", allocate(callee.getAsInt("id")));
        return keep(clone, callee);
    }
    /** Replaces each context reference with a copy of the fixed context it names. */
    private static AcsElement bind(AcsElement value, List<AcsElement> contexts) {
        if (value instanceof AcsObject object) {
            if (object.mmp.containsKey("ref") && object.mmp.size() == 1)
                return ExecO2.copy(contexts.get(object.getAsInt("ref")), null);
            object.mmp.replaceAll((key, child) -> bind(child, contexts));
        } else if (value instanceof AcsArray array) array.acsa.replaceAll(child -> bind(child, contexts));
        return value;
    }
    private static void collect(AcsElement value, List<AcsElement> returns) {
        int op = opcode(value);
        if (op == RETURN || op == RETURN_TYPED || op == RETURN_OBJECT) returns.add(value);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) collect(child.value(), returns);
    }

    // ---- clones --------------------------------------------------------------

    private int allocate(int original) {
        int namespace = original & 0xffff0000;
        for (int low = 0xffff; low > 1; low--) {
            int id = namespace | low;
            if (used.add(id)) return id;
        }
        throw new IllegalStateException("No function id left for a specialization");
    }
    private int keep(AcsObject clone, AcsObject callee) {
        if (4L + ExecShape.height(clone.mmp.get("script")) > abdLimit) { used.remove(clone.getAsInt("id")); return -1; }
        clone.put("entry-kind", 2);
        clones.add(clone); byId.put(clone.getAsInt("id"), clone);
        originals.put(clone.getAsInt("id"), callee.getAsInt("id"));
        return clone.getAsInt("id");
    }
}
