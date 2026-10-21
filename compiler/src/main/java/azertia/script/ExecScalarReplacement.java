package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O3 scalar replacement (exec v11). A literal object local defined once from its
 * class's value factory, whose class installs no methods and has no destructor,
 * and whose every use is a field read or write, a copy into another such local
 * or a call of a method over its fields, becomes one local per field.
 * reserve_block consumes the literal id the object would have taken, so every
 * later literal keeps its address. Constructors and methods are inlined; their
 * values are constants, parameters and fields, so nothing they do can fail and
 * no error frame is lost. The argument and return checks of the calls disappear,
 * so the types they checked must be proven, or the function is left unchanged.
 */
final class ExecScalarReplacement {
    private record Factory(AcsObject function, int width, int constructor) {}
    private record Check(AcsElement statement, AcsElement value, int type) {}
    private static final int MEM_GET = 0x0abd0006;
    private final Map<Integer,AcsObject> functions = new HashMap<>();
    private final Map<Integer,Factory> factories = new HashMap<>();
    private final Map<AcsElement,int[]> positions;
    private final Map<Integer,Integer> returns = new HashMap<>();
    private final List<Check> checks = new ArrayList<>();
    private final Map<Integer,Integer> hoisted = new LinkedHashMap<>();
    private int nextSlot;

    private ExecScalarReplacement(AcsArray functions, Map<AcsElement,int[]> positions) {
        this.positions = positions;
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            this.functions.put(function.getAsInt("id"), function); returns.put(function.getAsInt("id"), function.getAsInt("return-type"));
        }
        for (var item : functions.acsa) {
            Factory factory = factory((AcsObject)item);
            if (factory != null && body(this.functions.get(factory.constructor), factory.width, 0, true))
                factories.put(((AcsObject)item).getAsInt("id"), factory);
        }
    }
    static void optimize(AcsArray functions, Map<AcsElement,int[]> positions) {
        var pass = new ExecScalarReplacement(functions, positions);
        if (pass.factories.isEmpty()) return;
        for (var item : functions.acsa) pass.function((AcsObject)item);
    }

    /** A value factory: define new_block(width), constructor(block, parameters...), bind without destructor, return. */
    private Factory factory(AcsObject function) {
        if (function.getAsInt("return-type") != OBJECT_VALUE || function.getAsInt("hidden-count") != 0
                || !(function.mmp.get("script") instanceof AcsArray body) || body.acsa.size() != 4) return null;
        int params = function.getAsInt("param-count");
        AcsElement definition = body.acsa.get(0), call = body.acsa.get(1), bind = body.acsa.get(2), returned = body.acsa.get(3);
        if (opcode(definition) != DEFINE || field(definition,"val") == null || opcode(field(definition,"val")) != NEW_BLOCK) return null;
        int object = number(definition,"v"), width = number(field(definition,"val"),"size");
        if (opcode(call) != CALL || !((AcsObject)call).getAsAcsArray("contexts").acsa.isEmpty()) return null;
        var arguments = ((AcsObject)call).getAsAcsArray("param").acsa;
        if (arguments.size() != params + 1 || !blockOf(arguments.get(0), object)) return null;
        for (int i = 0; i < params; i++)
            if (opcode(arguments.get(i + 1)) != VARIABLE || number(arguments.get(i + 1),"v") != i) return null;
        AcsObject constructor = functions.get(((AcsObject)call).getAsInt("id"));
        if (constructor == null || constructor.getAsInt("param-count") != params + 1) return null;
        var binding = (AcsObject)bind;
        if (opcode(bind) != OBJECT_BIND || !blockOf(field(bind,"v"), object) || binding.mmp.containsKey("destructor")
                || !(binding.mmp.get("manual") instanceof AcsBooleanElement manual) || manual.on
                || binding.mmp.containsKey("contexts") && !binding.getAsAcsArray("contexts").acsa.isEmpty()) return null;
        if (opcode(returned) != RETURN_OBJECT || opcode(field(returned,"r")) != VARIABLE || number(field(returned,"r"),"v") != object) return null;
        return new Factory(function, width, constructor.getAsInt("id"));
    }
    private static boolean blockOf(AcsElement value, int slot) {
        return opcode(value) == BLOCK_ADDRESS && opcode(field(value,"v")) == VARIABLE && number(field(value,"v"),"v") == slot;
    }

    // ---- bodies over a receiver -----------------------------------------------

    /**
     * Whether a function is a body over its receiver (slot 0): receiver checks,
     * an empty method table, field stores and calls of void such bodies, then for
     * a method a final return. Every value is a constant, parameter or field.
     */
    private boolean body(AcsObject function, int width, int depth, boolean constructor) {
        if (function == null || depth > 4 || function.getAsInt("hidden-count") != 0) return false;
        var types = function.getAsAcsArray("param-types").acsa;
        if (types.isEmpty() || ((AcsIntegerElement)types.get(0)).s != ADDRESS_VALUE) return false;
        int returnType = function.getAsInt("return-type");
        if (constructor && returnType != VOID_VALUE || returnType == OBJECT_VALUE || returnType == ADDRESS_VALUE) return false;
        if (!(function.mmp.get("script") instanceof AcsArray body)) return false;
        var items = body.acsa;
        for (int i = 0; i < items.size(); i++) {
            AcsElement item = items.get(i);
            if (opcode(item) == RETURN) {
                if (i != items.size() - 1) return false;
                if (returnType != VOID_VALUE && (field(item,"r") == null || !value(field(item,"r"), width))) return false;
                continue;
            }
            if (!statement(item, width, depth)) return false;
        }
        return returnType == VOID_VALUE || !items.isEmpty() && opcode(items.get(items.size() - 1)) == RETURN;
    }
    private boolean statement(AcsElement item, int width, int depth) {
        int op = opcode(item);
        if (op == BLOCK) return ((AcsArray)item).acsa.stream().allMatch(child -> statement(child, width, depth));
        if (op == OBJECT_ADDRESS) return receiver(field(item,"v")) && number(item,"offset") >= 0 && number(item,"offset") < width;
        if (op == SET_METHODS) return receiver(field(item,"v")) && ((AcsObject)item).getAsAcsArray("entries").acsa.isEmpty();
        if (op == MOVE) return thisField(field(item,"v1"), width) >= 0 && value(field(item,"v2"), width);
        if (op == CALL) {
            var arguments = ((AcsObject)item).getAsAcsArray("param").acsa;
            AcsObject base = functions.get(number(item,"id"));
            if (base == null || arguments.isEmpty() || !receiver(arguments.get(0)) || base.getAsInt("return-type") != VOID_VALUE) return false;
            if (!((AcsObject)item).getAsAcsArray("contexts").acsa.isEmpty()) return false;
            for (int i = 1; i < arguments.size(); i++) if (!value(arguments.get(i), width)) return false;
            return body(base, width, depth + 1, false);
        }
        return false;
    }
    private static boolean receiver(AcsElement value) { return opcode(value) == VARIABLE && number(value,"v") == 0; }
    /** The receiver field a mem_get(object_address(slot 0, k)) names, or -1. */
    private static int thisField(AcsElement value, int width) {
        if (opcode(value) != CALL || number(value,"id") != MEM_GET) return -1;
        var arguments = ((AcsObject)value).getAsAcsArray("param").acsa;
        if (arguments.size() != 1 || opcode(arguments.get(0)) != OBJECT_ADDRESS || !receiver(field(arguments.get(0),"v"))) return -1;
        int offset = number(arguments.get(0),"offset");
        return offset >= 0 && offset < width ? offset : -1;
    }
    /** Values that cannot fail: constants, non-receiver parameters and receiver fields. */
    private static boolean value(AcsElement value, int width) {
        int op = opcode(value);
        if (op == CONSTANT) return true;
        if (op == VARIABLE) return number(value,"v") > 0;
        return thisField(value, width) >= 0;
    }

    // ---- one function -----------------------------------------------------------

    private void function(AcsObject function) {
        if (!(function.mmp.get("script") instanceof AcsArray original) || function.getAsInt("hidden-count") != 0) return;
        if (!ExecSequenceInliner.analyzable(original)) return;
        Map<Integer,Integer> stores = new HashMap<>();
        stores(original, stores);
        List<AcsElement> defined = new ArrayList<>(); collect(original, DEFINE, defined);
        Map<Integer,Factory> candidates = new HashMap<>(); Map<Integer,Integer> copies = new HashMap<>();
        for (var item : defined) {
            int slot = number(item, "v"); AcsElement value = field(item,"val");
            if (slot < 0 || stores.getOrDefault(slot, 0) != 1 || !objectType(item) || value == null) continue;
            if (opcode(value) == CALL && factories.containsKey(number(value,"id"))) candidates.put(slot, factories.get(number(value,"id")));
        }
        for (boolean grew = true; grew;) {
            grew = false;
            for (var item : defined) {
                int slot = number(item, "v"); AcsElement value = field(item,"val");
                if (candidates.containsKey(slot) || stores.getOrDefault(slot, 0) != 1 || !objectType(item) || value == null
                        || opcode(value) != VARIABLE || !candidates.containsKey(number(value,"v"))) continue;
                candidates.put(slot, candidates.get(number(value,"v"))); copies.put(slot, number(value,"v")); grew = true;
            }
        }
        // A rejected object rejects its copies, and a rejected copy its source.
        for (boolean changed = true; changed && !candidates.isEmpty();) {
            Set<Integer> rejected = new HashSet<>();
            admitted(original, candidates, rejected, null);
            for (var copy : copies.entrySet()) {
                if (!candidates.containsKey(copy.getKey()) || !candidates.containsKey(copy.getValue())) continue;
                if (rejected.contains(copy.getValue())) rejected.add(copy.getKey());
                if (rejected.contains(copy.getKey())) rejected.add(copy.getValue());
            }
            changed = candidates.keySet().removeAll(rejected);
        }
        if (candidates.isEmpty()) return;

        int params = function.getAsInt("param-count");
        nextSlot = params + function.getAsInt("local-count"); checks.clear(); hoisted.clear();
        Map<Integer,int[]> fields = new HashMap<>();
        for (var entry : candidates.entrySet()) {
            int[] slots = new int[entry.getValue().width];
            for (int i = 0; i < slots.length; i++) slots[i] = nextSlot++;
            fields.put(entry.getKey(), slots);
        }
        AcsArray body = (AcsArray)ExecO2.copy(original, positions);
        if (!block(body, candidates, fields) || nextSlot > 256) return;
        List<AcsElement> declarations = new ArrayList<>();
        hoisted.forEach((slot, type) -> declarations.add(ExecO2.define(slot, type,
                type == BOOLEAN_VALUE ? new AcsBooleanElement(false) : new AcsIntegerElement(0), null)));
        body.acsa.addAll(0, declarations);
        // The checks of the removed calls hold only when the analysis proves them.
        if (!ExecSequenceInliner.analyzable(body)) return;
        var view = new AcsObject(); function.mmp.forEach(view.mmp::put); view.mmp.put("script", body);
        view.put("local-count", nextSlot - params);
        var analysis = new ExecScalarAnalysis(view, returns);
        for (var check : checks) {
            Block site = analysis.sites.get(check.statement);
            if (site == null || site.in == null || analysis.evaluate(check.value, site.in).type() != check.type) return;
        }
        function.mmp.put("script", body);
        function.put("local-count", nextSlot - params);
    }
    private static boolean objectType(AcsElement definition) {
        var object = (AcsObject)definition;
        return object.mmp.containsKey("declared-type") && object.getAsInt("declared-type") == OBJECT_VALUE;
    }
    private static void stores(AcsElement value, Map<Integer,Integer> stores) {
        int op = opcode(value);
        if (op == DEFINE || op == SET || op == MOVE_OUT) stores.merge(number(value,"v"), op == DEFINE ? 1 : 2, Integer::sum);
        if (op == MOVE && opcode(field(value,"v1")) == VARIABLE) stores.merge(number(field(value,"v1"),"v"), 2, Integer::sum);
        if (op == DROP && opcode(field(value,"v")) == VARIABLE) stores.merge(number(field(value,"v"),"v"), 2, Integer::sum);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) stores(child.value(), stores);
    }
    private static void collect(AcsElement value, int op, List<AcsElement> result) {
        if (opcode(value) == op) result.add(value);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) collect(child.value(), op, result);
    }

    /** Collects candidates that occur outside a replaced shape. */
    private void admitted(AcsElement value, Map<Integer,Factory> candidates, Set<Integer> rejected, AcsElement parent) {
        int op = opcode(value);
        if (op == VARIABLE && candidates.containsKey(number(value,"v"))) { rejected.add(number(value,"v")); return; }
        if (op == DEFINE && candidates.containsKey(number(value,"v"))) {
            AcsElement initial = field(value,"val");
            if (opcode(initial) == CALL) for (var argument : ((AcsObject)initial).getAsAcsArray("param").acsa) admitted(argument, candidates, rejected, value);
            return;
        }
        if (fieldBase(value, candidates) != null) return;
        if (op == MOVE && fieldBase(field(value,"v1"), candidates) != null) { admitted(field(value,"v2"), candidates, rejected, value); return; }
        Integer receiver = methodReceiver(value, candidates);
        if (receiver != null) {
            if (!body(functions.get(number(value,"id")), candidates.get(receiver).width, 0, false)) rejected.add(receiver);
            var arguments = ((AcsObject)value).getAsAcsArray("param").acsa;
            for (int i = 1; i < arguments.size(); i++) admitted(arguments.get(i), candidates, rejected, value);
            return;
        }
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) admitted(child.value(), candidates, rejected, value);
    }
    /** The candidate whose field a mem_get(object_address(block_address(c), k)) reads. */
    private static Integer fieldBase(AcsElement value, Map<Integer,Factory> candidates) {
        if (opcode(value) != CALL || number(value,"id") != MEM_GET) return null;
        var arguments = ((AcsObject)value).getAsAcsArray("param").acsa;
        if (arguments.size() != 1 || opcode(arguments.get(0)) != OBJECT_ADDRESS) return null;
        AcsElement address = field(arguments.get(0),"v");
        if (opcode(address) != BLOCK_ADDRESS || opcode(field(address,"v")) != VARIABLE) return null;
        Factory factory = candidates.get(number(field(address,"v"),"v"));
        int offset = number(arguments.get(0),"offset");
        return factory != null && offset >= 0 && offset < factory.width ? number(field(address,"v"),"v") : null;
    }
    private static int offset(AcsElement field) { return number(((AcsObject)field).getAsAcsArray("param").acsa.get(0), "offset"); }
    /** The candidate a direct call passes as its receiver, through block_address. */
    private static Integer methodReceiver(AcsElement value, Map<Integer,Factory> candidates) {
        if (opcode(value) != CALL || !((AcsObject)value).getAsAcsArray("contexts").acsa.isEmpty()) return null;
        var arguments = ((AcsObject)value).getAsAcsArray("param").acsa;
        if (arguments.isEmpty() || opcode(arguments.get(0)) != BLOCK_ADDRESS || opcode(field(arguments.get(0),"v")) != VARIABLE) return null;
        int slot = number(field(arguments.get(0),"v"),"v");
        return candidates.containsKey(slot) ? slot : null;
    }

    // ---- rewriting ------------------------------------------------------------

    /** Rewrites a block's items; every statement inside is rewritten with itself as the checks' site. */
    private boolean block(AcsArray block, Map<Integer,Factory> candidates, Map<Integer,int[]> fields) {
        List<AcsElement> output = new ArrayList<>();
        for (var item : block.acsa) {
            if (opcode(item) == DEFINE && candidates.containsKey(number(item,"v"))) {
                if (!construct(item, candidates, fields, output)) return false;
                continue;
            }
            Integer receiver = methodReceiver(item, candidates);
            if (receiver != null) {
                // A method call as a statement: its effects in place, its value dropped.
                if (!effects(item, candidates, fields, output)) return false;
                continue;
            }
            AcsElement next = statement(item, candidates, fields);
            if (next == null) return false;
            output.add(next);
        }
        block.acsa.clear(); block.acsa.addAll(output);
        return true;
    }
    private AcsElement statement(AcsElement item, Map<Integer,Factory> candidates, Map<Integer,int[]> fields) {
        int op = opcode(item);
        if (op == BLOCK) return block((AcsArray)item, candidates, fields) ? item : null;
        Integer target = op == MOVE ? fieldBase(field(item,"v1"), candidates) : null;
        if (target != null) {
            AcsElement value = expression(field(item,"v2"), candidates, fields, item);
            return value == null ? null : set(fields.get(target)[offset(field(item,"v1"))], value, item);
        }
        for (var child : ExecShape.children(item)) {
            var role = child.role();
            if (role == ExecShape.Role.CONTEXT || role == ExecShape.Role.METHOD_ENTRY) continue;
            AcsElement next;
            if (role == ExecShape.Role.BLOCK || (op == IF && !"v".equals(child.field()))) {
                next = child.value() instanceof AcsArray nested ? (block(nested, candidates, fields) ? nested : null)
                        : statement(child.value(), candidates, fields);
            } else next = expression(child.value(), candidates, fields, item);
            if (next == null) return null;
            if (next != child.value()) ExecOptimizer.replace(item, child, next);
        }
        return replaced(item, candidates, fields, item);
    }
    private AcsElement expression(AcsElement value, Map<Integer,Factory> candidates, Map<Integer,int[]> fields, AcsElement statement) {
        if (fieldBase(value, candidates) != null || methodReceiver(value, candidates) != null && opcode(value) == CALL) {
            if (fieldBase(value, candidates) == null) {
                var arguments = ((AcsObject)value).getAsAcsArray("param").acsa;
                for (int i = 1; i < arguments.size(); i++) {
                    AcsElement next = expression(arguments.get(i), candidates, fields, statement);
                    if (next == null) return null;
                    arguments.set(i, next);
                }
            }
            return replaced(value, candidates, fields, statement);
        }
        for (var child : ExecShape.children(value)) {
            if (child.role() == ExecShape.Role.CONTEXT || child.role() == ExecShape.Role.METHOD_ENTRY) continue;
            AcsElement next = expression(child.value(), candidates, fields, statement);
            if (next == null) return null;
            if (next != child.value()) ExecOptimizer.replace(value, child, next);
        }
        return replaced(value, candidates, fields, statement);
    }
    /** A field access or a value-only method call on a replaced object; anything else unchanged. */
    private AcsElement replaced(AcsElement value, Map<Integer,Factory> candidates, Map<Integer,int[]> fields, AcsElement statement) {
        Integer base = fieldBase(value, candidates);
        if (base != null) return ExecO2.variable(fields.get(base)[offset(value)], value, positions);
        if (opcode(value) == MOVE) {
            Integer target = fieldBase(field(value,"v1"), candidates);
            if (target != null) return set(fields.get(target)[offset(field(value,"v1"))], field(value,"v2"), value);
        }
        Integer receiver = methodReceiver(value, candidates);
        if (receiver != null) return method(value, fields.get(receiver), statement);
        return value;
    }
    /**
     * A method call statement: a store of each argument (whose checks are proven
     * there), the method's stores, then its result as a statement where its
     * return check is proven, although the value is unused.
     */
    private boolean effects(AcsElement call, Map<Integer,Factory> candidates, Map<Integer,int[]> fields, List<AcsElement> output) {
        AcsObject method = functions.get(number(call,"id"));
        var arguments = ((AcsObject)call).getAsAcsArray("param").acsa;
        var types = method.getAsAcsArray("param-types").acsa;
        List<AcsElement> values = new ArrayList<>();
        for (int i = 1; i < arguments.size(); i++) {
            AcsElement argument = expression(arguments.get(i), candidates, fields, call);
            if (argument == null) return false;
            int type = ((AcsIntegerElement)types.get(i)).s, snapshot = nextSlot++;
            hoisted.put(snapshot, type == ANY_VALUE ? INT_VALUE : type);
            AcsObject store = set(snapshot, argument, call);
            if (type != ANY_VALUE) checks.add(new Check(store, argument, type));
            output.add(store); values.add(ExecO2.variable(snapshot, argument, positions));
        }
        int[] own = fields.get(methodReceiver(call, candidates));
        for (var item : ((AcsArray)method.mmp.get("script")).acsa) {
            if (opcode(item) == RETURN && field(item,"r") != null && method.getAsInt("return-type") != ANY_VALUE) {
                AcsElement result = valueOf(field(item,"r"), own, values, call);
                checks.add(new Check(result, result, method.getAsInt("return-type")));
                output.add(result);
                continue;
            }
            inline(item, own, values, output, call);
        }
        return true;
    }
    /** A method used as a value: trivial arguments and no stores, so only its result remains. */
    private AcsElement method(AcsElement call, int[] fields, AcsElement statement) {
        AcsObject method = functions.get(number(call,"id"));
        var arguments = ((AcsObject)call).getAsAcsArray("param").acsa;
        var types = method.getAsAcsArray("param-types").acsa;
        List<AcsElement> values = new ArrayList<>();
        for (int i = 1; i < arguments.size(); i++) {
            AcsElement argument = arguments.get(i); int type = ((AcsIntegerElement)types.get(i)).s;
            if (opcode(argument) != CONSTANT && opcode(argument) != VARIABLE) return null;
            if (type != ANY_VALUE) checks.add(new Check(statement, argument, type));
            values.add(argument);
        }
        AcsElement result = new AcsIntegerElement(0);
        for (var item : ((AcsArray)method.mmp.get("script")).acsa) {
            int op = opcode(item);
            if (op == RETURN) {
                if (field(item,"r") == null) continue;
                result = valueOf(field(item,"r"), fields, values, call);
                if (method.getAsInt("return-type") != ANY_VALUE) checks.add(new Check(statement, result, method.getAsInt("return-type")));
            } else if (op != OBJECT_ADDRESS) return null;
        }
        return result;
    }
    /** The statements replacing a candidate's definition: argument snapshots, id reservation, fields, constructor. */
    private boolean construct(AcsElement definition, Map<Integer,Factory> candidates, Map<Integer,int[]> fields, List<AcsElement> output) {
        int[] own = fields.get(number(definition,"v"));
        AcsElement initial = field(definition,"val");
        if (opcode(initial) == VARIABLE) {
            // A copy is a new literal holding the source's fields.
            output.add(reserve(definition));
            int[] source = fields.get(number(initial,"v"));
            for (int i = 0; i < own.length; i++) output.add(ExecO2.define(own[i], ANY_VALUE, ExecO2.variable(source[i], initial, positions), positions));
            return true;
        }
        Factory factory = candidates.get(number(definition,"v"));
        var arguments = ((AcsObject)initial).getAsAcsArray("param").acsa;
        var types = factory.function.getAsAcsArray("param-types").acsa;
        var constructorTypes = functions.get(factory.constructor).getAsAcsArray("param-types").acsa;
        List<AcsElement> values = new ArrayList<>();
        for (int i = 0; i < arguments.size(); i++) {
            AcsElement argument = expression(arguments.get(i), candidates, fields, definition);
            if (argument == null) return false;
            int type = ((AcsIntegerElement)types.get(i)).s, inner = ((AcsIntegerElement)constructorTypes.get(i + 1)).s;
            int snapshot = nextSlot++;
            AcsElement snapshotDefinition = ExecO2.define(snapshot, type, argument, positions);
            // The factory's and constructor's argument checks, proven on the snapshot.
            if (type != ANY_VALUE) checks.add(new Check(snapshotDefinition, argument, type));
            if (inner != ANY_VALUE) checks.add(new Check(snapshotDefinition, argument, inner));
            output.add(snapshotDefinition);
            values.add(ExecO2.variable(snapshot, argument, positions));
        }
        // Arguments run before the factory takes its literal id.
        output.add(reserve(definition));
        for (int field : own) {
            var declaration = new AcsObject(); declaration.put("t",0); declaration.put("c",DEFINE); declaration.put("v",field);
            output.add(ExecO2.locate(declaration, definition, positions));
        }
        int start = output.size();
        for (var item : ((AcsArray)functions.get(factory.constructor).mmp.get("script")).acsa) inline(item, own, values, output, definition);
        // A field local holds no value until stored: the constructor must store each field before reading it.
        Set<Integer> stored = new HashSet<>(), mine = new HashSet<>();
        for (int field : own) mine.add(field);
        for (var item : output.subList(start, output.size())) {
            Set<Integer> read = reads(field(item,"val"));
            read.retainAll(mine);
            if (!stored.containsAll(read)) return false;
            stored.add(number(item,"v"));
        }
        return stored.containsAll(mine);
    }
    private AcsElement reserve(AcsElement located) {
        var reservation = new AcsObject(); reservation.put("t",0); reservation.put("c",RESERVE_BLOCK);
        return ExecO2.locate(reservation, located, positions);
    }
    /** Appends a body statement over the replaced fields: stores, and nested void bodies. */
    private void inline(AcsElement item, int[] fields, List<AcsElement> arguments, List<AcsElement> output, AcsElement site) {
        int op = opcode(item);
        if (op == BLOCK) { for (var child : ((AcsArray)item).acsa) inline(child, fields, arguments, output, site); return; }
        // A fresh literal passes every receiver check, and its empty method table stays empty.
        if (op == OBJECT_ADDRESS || op == SET_METHODS || op == RETURN) return;
        if (op == MOVE) { output.add(set(fields[thisField(field(item,"v1"), fields.length)], valueOf(field(item,"v2"), fields, arguments, site), site)); return; }
        if (op == CALL) {
            var nested = ((AcsObject)item).getAsAcsArray("param").acsa;
            List<AcsElement> values = new ArrayList<>();
            for (int i = 1; i < nested.size(); i++) values.add(valueOf(nested.get(i), fields, arguments, site));
            for (var child : ((AcsArray)functions.get(number(item,"id")).mmp.get("script")).acsa) inline(child, fields, values, output, site);
        }
    }
    /** A body value: a constant, an argument, or a field, located at the caller's site. */
    private AcsElement valueOf(AcsElement value, int[] fields, List<AcsElement> arguments, AcsElement site) {
        if (opcode(value) == CONSTANT) return ExecO2.locate(ExecO2.copy(value, null), site, positions);
        if (opcode(value) == VARIABLE) return ExecO2.copy(arguments.get(number(value,"v") - 1), positions);
        return ExecO2.variable(fields[thisField(value, fields.length)], site, positions);
    }
    private AcsObject set(int slot, AcsElement value, AcsElement located) {
        var store = new AcsObject(); store.put("t",0); store.put("c",SET); store.put("v",slot); store.mmp.put("val",value);
        return ExecO2.locate(store, located, positions);
    }
}
