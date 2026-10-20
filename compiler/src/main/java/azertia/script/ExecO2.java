package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.OptimizationPass.*;
import static azertia.script.ExecScalarAnalysis.*;

/** Optional compiler-only O2 stages. O0/O1 never enter this pipeline. */
final class ExecO2 {
    private final Set<Compiler.OptimizationPass> passes;
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,Integer> returns = new HashMap<>();
    private ExecScalarAnalysis analysis;
    private record Available(int slot, Set<Integer> dependencies) {}
    private ExecO2(Set<Compiler.OptimizationPass> passes, Map<AcsElement,int[]> positions) {
        this.passes = passes; this.positions = positions;
    }
    static boolean enabled(Set<Compiler.OptimizationPass> passes) {
        return passes.contains(CONTROL_FLOW) || passes.contains(COMMON_SUBEXPRESSIONS)
                || passes.contains(DEAD_CODE) || passes.contains(SCALAR_INLINING) || passes.contains(RANGE_LOOP_MOTION);
    }
    static void optimize(AcsArray functions, Set<Compiler.OptimizationPass> passes,
                         Map<AcsElement,int[]> positions, int abdLimit) {
        if (!enabled(passes)) return;
        var optimizer = new ExecO2(passes, positions);
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            optimizer.returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        Set<Integer> safe = safeFunctions(functions);
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            if (!safe.contains(function.getAsInt("id"))) continue;
            AcsElement original = function.mmp.get("script");
            AcsElement body = copy(original, positions);
            function.mmp.put("script", body);
            optimizer.analysis = new ExecScalarAnalysis(function,optimizer.returns);
            body = optimizer.statement(body, new HashMap<>());
            if (body == null) body = new AcsArray();
            // Scope wrappers can make an otherwise smaller body deeper. In
            // that case keep the known encodable original rather than reject
            // a valid source solely because an optional pass was selected.
            if (4L + ExecShape.height(body) <= abdLimit) function.mmp.put("script",body);
            else function.mmp.put("script",original);
        }
    }
    static AcsElement copy(AcsElement value, Map<AcsElement,int[]> positions) {
        if (value == null) return null;
        AcsElement result;
        if (value instanceof AcsObject object) {
            var next = new AcsObject();
            object.mmp.forEach((key, child) -> next.mmp.put(key,copy(child,positions)));
            result = next;
        } else if (value instanceof AcsArray array) {
            var next = new AcsArray();
            for (var child : array.acsa) next.acsa.add(copy(child,positions));
            result = next;
        } else if (value instanceof AcsIntegerElement integer) result = new AcsIntegerElement(integer.s);
        else if (value instanceof AcsBooleanElement bool) result = new AcsBooleanElement(bool.on);
        else result = value;
        return locate(result,value,positions);
    }
    static <T extends AcsElement> T locate(T value, AcsElement original, Map<AcsElement,int[]> positions) {
        if (positions != null && positions.containsKey(original)) positions.put(value,positions.get(original));
        return value;
    }
    static AcsObject variable(int slot, AcsElement original, Map<AcsElement,int[]> positions) {
        var value = new AcsObject(); value.put("t",0); value.put("c",VARIABLE); value.put("v",slot);
        return locate(value,original,positions);
    }
    static AcsObject define(int slot, int type, AcsElement value, Map<AcsElement,int[]> positions) {
        var definition = new AcsObject(); definition.put("t",0); definition.put("c",DEFINE);
        definition.put("v",slot); definition.put("declared-type",type); definition.mmp.put("val",value);
        return locate(definition,value,positions);
    }
    static boolean reusable(AcsElement value) {
        if (value == null) return false;
        int op = opcode(value);
        if (op == VARIABLE || op == CONSTANT) return true;
        if (!(op >= ADD && op <= NEGATE || op >= BIT_AND && op <= SELECT)) return false;
        for (var child : ExecShape.children(value)) if (!reusable(child.value())) return false;
        return true;
    }
    private AcsElement expression(AcsElement value, Map<Integer,Value> facts, Map<String,Available> available) {
        if (value == null) return null;
        if (passes.contains(COMMON_SUBEXPRESSIONS) && reusable(value)) {
            var known = available.get(value.toJson().toString());
            if (known != null && facts.containsKey(known.slot)) return variable(known.slot,value,positions);
        }
        Value known = analysis.evaluate(value,facts);
        if (passes.contains(PROPAGATION) && known.constant() && known.total())
            return locate(known.literal(),value,positions);
        for (var child : ExecShape.children(value))
            ExecOptimizer.replace(value,child,expression(child.value(),facts,available));
        return value;
    }
    private AcsElement statement(AcsElement value, Map<String,Available> available) {
        int op = opcode(value);
        if (op == BLOCK) {
            var block = (AcsArray)value; var output = new ArrayList<AcsElement>();
            var local = new HashMap<>(available);
            // A cached result's storage may belong to this lexical block.
            // Never export it to a parent or a sibling scope.
            available.clear();
            for (var item : block.acsa) {
                AcsElement result = statement(item,local);
                if (result != null) output.add(result);
            }
            block.acsa.clear(); block.acsa.addAll(output);
            return value;
        }
        Block site = analysis.sites.get(value);
        if (site == null || site.in == null) {
            available.clear();
            return passes.contains(CONTROL_FLOW) ? null : value;
        }
        if (op == IF || op == WHILE) {
            var object = (AcsObject)value;
            var condition = field(value,"v");
            Value known = analysis.evaluate(condition,site.in);
            object.mmp.put("v",expression(condition,site.in,available));
            available.clear();
            if (passes.contains(CONTROL_FLOW) && known.type() == Compiler.AsTypes.BOOLEAN_VALUE
                    && known.constant() && known.total()) {
                if (op == IF) {
                    var chosen = field(value,known.low() != 0 ? "val" : "else");
                    return chosen == null ? null : statement(chosen,new HashMap<>());
                }
                if (known.low() == 0) return null;
            }
            for (String name : List.of("val","else")) if (object.mmp.containsKey(name)) {
                var branch = statement(field(value,name),new HashMap<>());
                object.mmp.put(name,branch == null ? new AcsArray() : branch);
            }
            return value;
        }
        int slot = written(value);
        if (op == DEFINE || op == SET || op == MOVE) {
            String name = op == MOVE ? "v2" : "val";
            AcsElement original = field(value,name);
            Value evaluated = analysis.evaluate(original,site.in);
            var rhs = expression(original,site.in,available);
            if (passes.contains(DEAD_CODE) && op != DEFINE && site.in.containsKey(slot)
                    && !site.liveOut.contains(slot) && evaluated.total()) {
                available.entrySet().removeIf(e -> e.getValue().slot == slot || e.getValue().dependencies.contains(slot));
                return null;
            }
            if (rhs != null) ((AcsObject)value).mmp.put(name,rhs);
            available.entrySet().removeIf(e -> e.getValue().slot == slot || e.getValue().dependencies.contains(slot));
            if (passes.contains(COMMON_SUBEXPRESSIONS) && rhs != null && opcode(rhs) != CONSTANT
                    && opcode(rhs) != VARIABLE && reusable(rhs) && evaluated.scalar()) {
                Set<Integer> dependencies = reads(rhs);
                if (!dependencies.contains(slot))
                    available.put(rhs.toJson().toString(),new Available(slot,dependencies));
            }
            return value;
        }
        if (op == RETURN) {
            AcsElement result = expression(field(value,"r"),site.in,available);
            if (result != null) ((AcsObject)value).mmp.put("r",result);
            available.clear(); return value;
        }
        if (op == BREAK || op == CONTINUE) { available.clear(); return value; }
        Value evaluated = analysis.evaluate(value,site.in);
        if (passes.contains(DEAD_CODE) && evaluated.total()) return null;
        return expression(value,site.in,available);
    }
}
