package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;

/** Conservative forward scalar propagation. Facts describe already evaluated
 * values, never expressions to re-evaluate. Calls and ownership barriers kill
 * facts; loop headers discard every fact written on any back edge. */
final class ExecDataflow {
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,Integer> slotTypes = new HashMap<>();
    private boolean isolated;
    private ExecDataflow(Map<AcsElement,int[]> positions) { this.positions = positions; }
    static AcsElement optimize(AcsObject function, AcsElement body, Map<AcsElement,int[]> positions) {
        ExecDataflow pass = new ExecDataflow(positions);
        var parameters = function.getAsAcsArray("param-types").acsa;
        for (int i=0;i<parameters.size();i++) pass.slotTypes.put(i, ((AcsIntegerElement)parameters.get(i)).s);
        var ir = ExecIr.read(body, positions); pass.types(ir);
        pass.isolated = function.getAsInt("hidden-count") == 0 && parameters.stream().allMatch(value ->
                ((AcsIntegerElement)value).s == Compiler.AsTypes.INT_VALUE || ((AcsIntegerElement)value).s == Compiler.AsTypes.BOOLEAN_VALUE) && pass.isolated(ir);
        return pass.visit(ir, new HashMap<>(), false);
    }
    private void types(ExecIr.Node node) {
        if (node.opcode() == DEFINE && node.declaredType() != Compiler.AsTypes.ANY_VALUE) slotTypes.put(node.slot(), node.declaredType());
        for (var edge : node.children()) types(edge.value());
    }
    private boolean scalarSlot(int slot) {
        int type = slotTypes.getOrDefault(slot, Compiler.AsTypes.ANY_VALUE);
        return type == Compiler.AsTypes.INT_VALUE || type == Compiler.AsTypes.BOOLEAN_VALUE;
    }
    private static void forget(Map<Integer,AcsElement> facts, int slot) {
        facts.remove(slot);
        facts.entrySet().removeIf(entry -> entry.getValue() instanceof AcsObject variable && variable.getAsInt("v") == slot);
    }
    private AcsElement located(AcsElement value, AcsElement original) {
        if (positions != null && positions.containsKey(original)) positions.put(value, positions.get(original));
        return value;
    }
    private static AcsElement copy(AcsElement value) {
        if (value instanceof AcsIntegerElement integer) return new AcsIntegerElement(integer.s);
        if (value instanceof AcsBooleanElement bool) return new AcsBooleanElement(bool.on);
        AcsObject result = new AcsObject(); result.put("t",0); result.put("c",VARIABLE);
        result.put("v",((AcsObject)value).getAsInt("v")); return result;
    }
    private static boolean constant(AcsElement value) { return value instanceof AcsIntegerElement || value instanceof AcsBooleanElement; }
    private static void intersect(Map<Integer,AcsElement> target, Map<Integer,AcsElement> other) {
        target.entrySet().removeIf(entry -> !other.containsKey(entry.getKey())
                || !entry.getValue().toJson().equals(other.get(entry.getKey()).toJson()));
    }
    private boolean isolated(ExecIr.Node node) {
        if (node.opcode() == VARIABLE && !scalarSlot(node.slot())) return false;
        if ((node.opcode() == DEFINE || node.opcode() == SET) && !scalarSlot(node.slot())) return false;
        if (node.opcode() != BLOCK && node.opcode() != MOVE && node.opcode() != DEFINE && node.opcode() != SET &&
                (node.ownEffects().contains(ExecIr.Effect.CALL) || node.ownEffects().contains(ExecIr.Effect.WRITE_HEAP)
                || node.ownEffects().contains(ExecIr.Effect.CLEANUP))) return false;
        if (node.opcode() == MOVE) {
            var target = node.child("v1");
            if (target.opcode() != VARIABLE || target.slot() < 0 || !scalarSlot(target.slot())) return false;
        }
        return node.children().stream().allMatch(edge -> isolated(edge.value()));
    }
    private Integer scalarMove(ExecIr.Node node) {
        if (!isolated || node.opcode() != MOVE) return null;
        ExecIr.Node target = node.child("v1");
        return target.opcode() == VARIABLE && target.slot() >= 0 && scalarSlot(target.slot()) ? target.slot() : null;
    }
    private boolean barrier(ExecIr.Node node) {
        if (scalarMove(node) != null) return false;
        if (node.opcode() == DEFINE && constant(((AcsObject)node.source()).mmp.get("val"))) return false;
        if ((node.opcode() == DEFINE || node.opcode() == SET) && isolated && scalarSlot(node.slot())) return false;
        return node.ownEffects().contains(ExecIr.Effect.CALL) || node.ownEffects().contains(ExecIr.Effect.WRITE_HEAP)
                || node.ownEffects().contains(ExecIr.Effect.CLEANUP);
    }
    private void loopKills(ExecIr.Node node, Map<Integer,AcsElement> facts) {
        if (node.opcode() != BLOCK && barrier(node)) { facts.clear(); return; }
        if (scalarMove(node) != null) forget(facts, scalarMove(node));
        if (node.slot() != null && node.ownEffects().contains(ExecIr.Effect.WRITE_SLOT)) forget(facts, node.slot());
        for (var child : node.children()) loopKills(child.value(), facts);
    }
    private boolean deferredEffects(ExecIr.Node node) {
        if (node.opcode() != BLOCK && barrier(node)) return true;
        return node.children().stream().anyMatch(edge -> deferredEffects(edge.value()));
    }
    private AcsElement visit(ExecIr.Node node, Map<Integer,AcsElement> facts, boolean storage) {
        AcsElement source = node.source();
        if (node.opcode() == VARIABLE && !storage && facts.containsKey(node.slot()))
            return located(copy(facts.get(node.slot())), source);
        if (node.opcode() == BLOCK) {
            Set<Integer> declared = new HashSet<>();
            for (var edge : node.children()) {
                if (edge.value().opcode() == DEFINE) declared.add(edge.value().slot());
                ExecOptimizer.replace(source, edge.location(), visit(edge.value(), facts, false));
            }
            // Scalar locals have no destructor. Any ownership operation in the
            // block already invalidated the facts, including pending cleanup.
            for (int slot : declared) forget(facts, slot);
            if (deferredEffects(node)) facts.clear();
            return source;
        }
        if (node.opcode() == WHILE) {
            loopKills(node, facts);
            Map<Integer,AcsElement> header = new HashMap<>(facts);
            rewrite(node, "v", facts);
            rewrite(node, "val", facts);
            intersect(facts, header); // Includes the zero-iteration path.
            return source;
        }
        if (node.opcode() == IF || node.opcode() == SELECT || node.opcode() == AND || node.opcode() == OR) {
            boolean logical = node.opcode() == AND || node.opcode() == OR;
            rewrite(node, logical ? "v1" : "v", facts);
            Map<Integer,AcsElement> alternative = new HashMap<>(facts);
            rewrite(node, logical ? "v2" : node.opcode() == IF ? "val" : "then", facts);
            if (!logical) rewrite(node, "else", alternative);
            intersect(facts, alternative);
            return located(ExecOptimizer.fold(source), source);
        }
        if (node.opcode() == CLEANUP) {
            // Normal, return, break and exceptional paths enter cleanup with
            // different facts. Analyze each region independently.
            facts.clear();
            for (var edge : node.children())
                ExecOptimizer.replace(source, edge.location(), visit(edge.value(), new HashMap<>(), false));
            return source;
        }
        for (var edge : node.children())
            ExecOptimizer.replace(source, edge.location(), visit(edge.value(), facts,
                    edge.location().role() == ExecShape.Role.STORAGE_TARGET));
        if (barrier(node)) facts.clear();
        Integer moved = scalarMove(node);
        if (moved != null) {
            forget(facts, moved);
            AcsElement value = ((AcsObject)source).mmp.get("v2");
            if (constant(value)) facts.put(moved,value);
        }
        if (node.opcode() == DEFINE || node.opcode() == SET) {
            if (node.opcode() == SET && (!isolated || !scalarSlot(node.slot()))) facts.clear();
            forget(facts, node.slot());
            AcsElement value = ((AcsObject)source).mmp.get("val");
            if (node.slot() >= 0 && constant(value)) facts.put(node.slot(), value);
            else if (isolated && node.slot() >= 0 && value instanceof AcsObject variable && variable.getAsInt("t") == 0
                    && variable.getAsInt("c") == VARIABLE && variable.getAsInt("v") >= 0
                    && variable.getAsInt("v") != node.slot() && scalarSlot(variable.getAsInt("v"))) facts.put(node.slot(), copy(variable));
        }
        AcsElement result = ExecOptimizer.fold(source);
        // A conversion is removable only when the evaluated value has exactly
        // that scalar type. No floating conversions or unchecked slot types.
        if (node.opcode() == CONVERT) {
            AcsObject object = (AcsObject)source;
            AcsElement value = object.mmp.get("v");
            if ((object.getAsInt("type") == Compiler.AsTypes.INT_VALUE && value instanceof AcsIntegerElement)
                    || (object.getAsInt("type") == Compiler.AsTypes.BOOLEAN_VALUE && value instanceof AcsBooleanElement)) result = copy(value);
        }
        return result == source ? source : located(result, source);
    }
    static void removeDeadTemporaries(AcsElement body, Set<Integer> temporaries) {
        if (temporaries == null || temporaries.isEmpty()) return;
        Set<Integer> used = new HashSet<>();
        collectUses(ExecIr.read(body, null), used);
        erase(ExecIr.read(body, null), temporaries, used);
    }
    private static void collectUses(ExecIr.Node node, Set<Integer> used) {
        if (node.slot() != null && node.opcode() != DEFINE) used.add(node.slot());
        for (var edge : node.children()) collectUses(edge.value(), used);
    }
    private static void erase(ExecIr.Node node, Set<Integer> temporaries, Set<Integer> used) {
        for (var edge : node.children()) erase(edge.value(), temporaries, used);
        if (node.source() instanceof AcsArray block) block.acsa.removeIf(value -> {
            if (!(value instanceof AcsObject definition) || definition.getAsInt("t") != 0
                    || definition.getAsInt("c") != DEFINE) return false;
            int slot = definition.getAsInt("v");
            return temporaries.contains(slot) && !used.contains(slot) && constant(definition.mmp.get("val"));
        });
    }
    private void rewrite(ExecIr.Node node, String field, Map<Integer,AcsElement> facts) {
        for (var edge : node.children()) if (field.equals(edge.location().field()))
            ExecOptimizer.replace(node.source(), edge.location(), visit(edge.value(), facts, false));
    }
}
