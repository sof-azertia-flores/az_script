package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O3 slot compaction. A scalar local declared once, in a block other than the
 * function's root, lives only while that block runs: its scope exit undeclares
 * it. Locals of the same declared type whose blocks never nest then share a
 * slot, and the slots that remain are numbered densely after the parameters.
 */
final class ExecSlotCompaction {
    private static final Set<Integer> TYPES = Set.of(INT_VALUE, BOOLEAN_VALUE, FLOAT_VALUE, DOUBLE_VALUE);
    private final Map<Integer,AcsElement> declaring = new HashMap<>();
    private final Map<Integer,Integer> types = new HashMap<>();
    private final Set<Integer> excluded = new HashSet<>();
    private final Map<Integer,Integer> assigned = new HashMap<>();

    static void optimize(AcsArray functions) {
        for (var item : functions.acsa) new ExecSlotCompaction().function((AcsObject)item);
    }
    private void function(AcsObject function) {
        if (function.getAsInt("hidden-count") != 0 || !(function.mmp.get("script") instanceof AcsArray root)) return;
        int params = function.getAsInt("param-count"), locals = function.getAsInt("local-count");
        declarations(root, root);
        Set<Integer> used = new TreeSet<>(); slots(root, used);
        if (used.stream().anyMatch(slot -> slot >= params + locals)) return;
        // Declaration order is the first slot of each type a block may take.
        assign(root, new HashMap<>());
        Map<Integer,Integer> renamed = new HashMap<>();
        for (int slot : used) if (slot >= params) renamed.put(slot, assigned.getOrDefault(slot, slot));
        Map<Integer,Integer> dense = new HashMap<>(); int next = params;
        for (int slot : new TreeSet<>(renamed.values())) dense.put(slot, next++);
        if (next - params >= locals) return;
        for (var entry : renamed.entrySet()) entry.setValue(dense.get(entry.getValue()));
        relabel(root, renamed);
        function.put("local-count", next - params);
    }
    /** Records where each local is declared; a local declared twice or at the root keeps its slot. */
    private void declarations(AcsElement value, AcsArray root) {
        if (value instanceof AcsArray block) for (var item : block.acsa) if (opcode(item) == DEFINE) {
            int slot = number(item, "v");
            var definition = (AcsObject)item;
            int type = definition.mmp.containsKey("declared-type") ? definition.getAsInt("declared-type") : ANY_VALUE;
            if (block == root || declaring.containsKey(slot) || !TYPES.contains(type)) excluded.add(slot);
            declaring.put(slot, block); types.put(slot, type);
        }
        for (var child : ExecShape.children(value)) {
            if (child.role() == ExecShape.Role.CONTEXT || child.role() == ExecShape.Role.METHOD_ENTRY) continue;
            // A declaration that is not a block item (a lone branch) keeps its slot.
            if (opcode(child.value()) == DEFINE && !(value instanceof AcsArray)) excluded.add(number(child.value(), "v"));
            declarations(child.value(), root);
        }
    }
    private static void slots(AcsElement value, Set<Integer> used) {
        int op = opcode(value);
        if ((op == VARIABLE || op == DEFINE || op == SET || op == MOVE_OUT) && number(value, "v") >= 0) used.add(number(value, "v"));
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) slots(child.value(), used);
    }
    /** Gives the locals of each block a slot of their type that no enclosing block holds. */
    private void assign(AcsElement value, Map<Integer,Set<Integer>> active) {
        List<int[]> mine = new ArrayList<>();
        if (value instanceof AcsArray block) for (var item : block.acsa) if (opcode(item) == DEFINE) {
            int slot = number(item, "v");
            if (excluded.contains(slot) || declaring.get(slot) != block) continue;
            int type = types.get(slot);
            var held = active.computeIfAbsent(type, ignored -> new TreeSet<>());
            int chosen = pool.computeIfAbsent(type, ignored -> new TreeSet<>()).stream()
                    .filter(candidate -> !held.contains(candidate)).findFirst().orElse(slot);
            pool.get(type).add(chosen); held.add(chosen); mine.add(new int[]{type, chosen});
            assigned.put(slot, chosen);
        }
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) assign(child.value(), active);
        for (int[] entry : mine) active.get(entry[0]).remove(entry[1]);
    }
    private final Map<Integer,TreeSet<Integer>> pool = new HashMap<>();
    private static void relabel(AcsElement value, Map<Integer,Integer> slots) {
        int op = opcode(value);
        if ((op == VARIABLE || op == DEFINE || op == SET || op == MOVE_OUT) && slots.containsKey(number(value, "v")))
            ((AcsObject)value).put("v", slots.get(number(value, "v")).intValue());
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) relabel(child.value(), slots);
    }
}
