package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.ExecScalarAnalysis.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O3 (exec v11): inlines closed scalar callees at any call site of an expression,
 * eager or lazy, through {@code seq}, which runs statements in the enclosing scope
 * and then yields a value. Arguments are evaluated once, in order, into snapshot
 * slots unless a constant or an unwritten local can stand for the parameter.
 * Inlined slots are declared once at the caller's root; sequences that never
 * nest share them. Early returns become a result slot written at the tail of
 * each path. Callers may hold object operations elsewhere; argument and return
 * types are proven, so no runtime check is lost.
 */
final class ExecSequenceInliner {
    static final int MAX_DEPTH = 4, MAX_CALLEE_NODES = 256, MAX_CALLER_NODES = 2048, MAX_SLOTS = 256,
            MAX_DUPLICATED_TAIL = 16;
    private static final int VIRTUAL = 1 << 24;

    /** A lifted callee: returns only at the tail of each path. */
    private record Candidate(AcsObject function, AcsArray body, ExecScalarAnalysis analysis, int params,
                             int returnType, boolean simpleTail, Set<Integer> writtenParams,
                             Map<Integer,Integer> hoisted, Map<Integer,Integer> scoped, int nodes, boolean total, int display) {}
    private record Owned(int slot, int type, boolean hoisted) {}
    private record Context(ExecScalarAnalysis analysis, Map<Integer,Integer> rename,
                           Map<Integer,AcsElement> substitutes, int depth, int site) {}

    private final Map<Integer,Candidate> candidates;
    private final Map<AcsElement,int[]> positions;
    private final ExecInlineSites sites;
    private final IdentityHashMap<AcsElement,List<Owned>> owned = new IdentityHashMap<>();
    private int nextVirtual = VIRTUAL, nodes, limit, expansions, namespace;
    private boolean objects;

    private final Map<Integer,Integer> originals;
    private ExecSequenceInliner(Map<Integer,Candidate> candidates, Map<AcsElement,int[]> positions, ExecInlineSites sites,
                                Map<Integer,Integer> originals) {
        this.candidates = candidates; this.positions = positions; this.sites = sites; this.originals = originals;
    }

    /** A method whose receiver is used only by leading object_address checks. */
    private record Method(Candidate body, List<Integer> checks) {}
    private Map<Integer,Method> methods = Map.of();
    private Map<Integer,Integer> slotTargets = Map.of();
    private Map<Integer,List<Integer>> factoryTables = Map.of();

    /** originals maps O3 specializations to the functions their frames show. */
    static void optimize(AcsArray functions, Map<AcsElement,int[]> positions, ExecInlineSites sites, int abdLimit,
                         Map<Integer,Integer> originals) {
        optimize(functions, positions, sites, abdLimit, originals, true, false);
    }
    /** inline expands calls; devirtualize gives virtual calls a guarded inlined target (O3 objects). */
    static void optimize(AcsArray functions, Map<AcsElement,int[]> positions, ExecInlineSites sites, int abdLimit,
                         Map<Integer,Integer> originals, boolean inline, boolean devirtualize) {
        Map<Integer,Integer> returns = new HashMap<>();
        for (var item : functions.acsa) {
            var function = (AcsObject)item; returns.put(function.getAsInt("id"),function.getAsInt("return-type"));
        }
        // Candidates are frozen before any caller changes, so expansion depth,
        // not declaration order, bounds how far calls are followed.
        Set<Integer> safe = safeFunctions(functions);
        Map<Integer,Candidate> candidates = new HashMap<>();
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            if (!inline || !safe.contains(function.getAsInt("id"))) continue;
            Candidate candidate = candidate(function,returns,positions);
            if (candidate != null) candidates.put(function.getAsInt("id"),candidate);
        }
        Map<Integer,Method> methods = new HashMap<>(); Map<Integer,Integer> slotTargets = new HashMap<>();
        Map<Integer,List<Integer>> factoryTables = new HashMap<>();
        if (devirtualize) {
            for (var item : functions.acsa) {
                Method method = method((AcsObject)item, returns, safe, positions);
                if (method != null) methods.put(((AcsObject)item).getAsInt("id"), method);
            }
            targets(functions, slotTargets, factoryTables);
        }
        if (candidates.isEmpty() && methods.isEmpty()) return;
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            var pass = new ExecSequenceInliner(candidates,positions,sites,originals);
            pass.methods = methods; pass.slotTargets = slotTargets; pass.factoryTables = factoryTables;
            AcsElement body = pass.caller(function,returns);
            if (body == null) continue;
            if (4L + ExecShape.height(body) > abdLimit) continue;
            function.mmp.put("script",body);
        }
    }

    // ---- callees ------------------------------------------------------------

    private static Candidate candidate(AcsObject function, Map<Integer,Integer> returns, Map<AcsElement,int[]> positions) {
        int returnType = function.getAsInt("return-type"), params = function.getAsInt("param-count");
        if (!scalar(returnType) && returnType != VOID_VALUE || params > 16) return null;
        if (!(function.mmp.get("script") instanceof AcsArray original) || count(original) > MAX_CALLEE_NODES) return null;
        for (var item : original.acsa) if (opcode(item) == DEFINE && field(item,"val") == null) return null;
        if (returnInLoop(original, false)) return null;
        var lifted = lift(items(ExecO2.copy(original,positions)), positions);
        if (lifted == null || returnType != VOID_VALUE && !alwaysReturns(lifted)) return null;
        var body = new AcsArray(); body.acsa.addAll(lifted);
        var view = new AcsObject(); function.mmp.forEach(view.mmp::put); view.mmp.put("script",body);
        var analysis = new ExecScalarAnalysis(view,returns);
        List<AcsElement> returned = new ArrayList<>(); collect(body,RETURN,returned);
        for (var node : returned) {
            var site = analysis.sites.get(node);
            if (returnType == VOID_VALUE || site == null || site.in == null) continue;
            if (analysis.evaluate(field(node,"r"),site.in).type() != returnType) return null;
        }
        Map<Integer,Integer> hoisted = new HashMap<>(), scoped = new HashMap<>();
        Set<AcsElement> topLevel = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var item : body.acsa) if (opcode(item) == DEFINE) { hoisted.put(number(item,"v"),declared(item)); topLevel.add(item); }
        List<AcsElement> definitions = new ArrayList<>(); collect(body,DEFINE,definitions);
        for (var node : definitions) {
            int slot = number(node,"v");
            if (topLevel.contains(node)) continue;
            if (hoisted.containsKey(slot) || slot < params) return null;
            scoped.merge(slot,declared(node),(a,b) -> a.equals(b) ? a : ANY_VALUE);
        }
        Set<Integer> written = new HashSet<>(); writes(body,written);
        Set<Integer> writtenParams = new HashSet<>();
        for (int slot : written) if (slot < params) writtenParams.add(slot);
        // Every slot read is a parameter or a local of the callee.
        for (int slot : reads(body))
            if (slot >= params && !hoisted.containsKey(slot) && !scoped.containsKey(slot)) return null;
        boolean simpleTail = returnType != VOID_VALUE && returned.size() == 1 && body.acsa.get(body.acsa.size() - 1) == returned.get(0);
        var frozen = new AcsObject(); function.mmp.forEach(frozen.mmp::put);
        return new Candidate(frozen,body,analysis,params,returnType,simpleTail,writtenParams,hoisted,scoped,count(body),total(analysis),
                function.getAsInt("id"));
    }
    /**
     * A method candidate: a receiver (address) followed by closed scalar
     * parameters, whose body checks the receiver with leading object_address
     * statements and never uses it again. The rest is inlined as a function of
     * the other parameters; the checks are kept.
     */
    private static Method method(AcsObject function, Map<Integer,Integer> returns, Set<Integer> safe, Map<AcsElement,int[]> positions) {
        var types = function.getAsAcsArray("param-types").acsa;
        if (types.isEmpty() || ((AcsIntegerElement)types.get(0)).s != ADDRESS_VALUE || function.getAsInt("hidden-count") != 0
                || function.getAsInt("entry-kind") == 1 || !(function.mmp.get("script") instanceof AcsArray body)) return null;
        List<Integer> checks = new ArrayList<>(); int first = 0;
        for (; first < body.acsa.size(); first++) {
            AcsElement item = body.acsa.get(first);
            if (opcode(item) != OBJECT_ADDRESS || opcode(field(item,"v")) != VARIABLE || number(field(item,"v"),"v") != 0) break;
            checks.add(number(item,"offset"));
        }
        var rest = new AcsArray();
        for (int i = first; i < body.acsa.size(); i++) rest.acsa.add(ExecO2.copy(body.acsa.get(i), positions));
        if (reads(rest).contains(0)) return null;
        Set<Integer> written = new HashSet<>(); writes(rest, written);
        if (written.contains(0) || !shift(rest)) return null;
        var view = new AcsObject(); function.mmp.forEach(view.mmp::put);
        var params = new AcsArray(); params.acsa.addAll(types.subList(1, types.size()));
        view.mmp.put("param-types", params); view.put("param-count", types.size() - 1); view.mmp.put("script", rest);
        if (!eligible(view, safe)) return null;
        Candidate candidate = candidate(view, returns, positions);
        return candidate == null ? null : new Method(candidate, checks);
    }
    /** Renumbers slots one down, past the dropped receiver; false if a global appears. */
    private static boolean shift(AcsElement value) {
        if (value instanceof AcsObject object && (expression(object, VARIABLE) || expression(object, DEFINE) || expression(object, SET))) {
            if (object.getAsInt("v") < 1) return false;
            object.put("v", object.getAsInt("v") - 1);
        }
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY && !shift(child.value())) return false;
        return true;
    }
    /**
     * Likely targets of virtual calls: a slot bound to one function by every
     * method table of the module, and the table each factory's constructor
     * installs last. A guard makes a wrong guess cost only time.
     */
    private static void targets(AcsArray functions, Map<Integer,Integer> slotTargets, Map<Integer,List<Integer>> factoryTables) {
        Map<Integer,AcsObject> byId = new HashMap<>();
        for (var item : functions.acsa) byId.put(((AcsObject)item).getAsInt("id"), (AcsObject)item);
        Map<Integer,Set<Integer>> bound = new HashMap<>();
        for (var item : functions.acsa) {
            List<AcsElement> tables = new ArrayList<>(); collect(((AcsObject)item).mmp.get("script"), SET_METHODS, tables);
            for (var table : tables) {
                var entries = ((AcsObject)table).getAsAcsArray("entries").acsa;
                for (int i = 0; i < entries.size(); i++) {
                    var entry = (AcsObject)entries.get(i);
                    bound.computeIfAbsent(i, ignored -> new HashSet<>())
                            .add(entry.getAsAcsArray("contexts").acsa.isEmpty() ? entry.getAsInt("id") : -1);
                }
            }
        }
        bound.forEach((slot, ids) -> { if (ids.size() == 1 && !ids.contains(-1)) slotTargets.put(slot, ids.iterator().next()); });
        for (var item : functions.acsa) {
            var factory = (AcsObject)item;
            if (!(factory.mmp.get("script") instanceof AcsArray body) || body.acsa.isEmpty() || opcode(body.acsa.get(0)) != DEFINE) continue;
            int object = number(body.acsa.get(0), "v");
            for (var statement : body.acsa) {
                if (opcode(statement) != CALL) continue;
                var arguments = ((AcsObject)statement).getAsAcsArray("param").acsa;
                if (arguments.isEmpty() || opcode(arguments.get(0)) != VARIABLE || number(arguments.get(0),"v") != object) continue;
                AcsObject constructor = byId.get(((AcsObject)statement).getAsInt("id"));
                if (constructor == null) continue;
                List<AcsElement> tables = new ArrayList<>(); collect(constructor.mmp.get("script"), SET_METHODS, tables);
                tables.removeIf(table -> opcode(field(table,"v")) != VARIABLE || number(field(table,"v"),"v") != 0);
                if (tables.isEmpty()) continue;
                List<Integer> ids = new ArrayList<>();
                for (var entry : ((AcsObject)tables.get(tables.size() - 1)).getAsAcsArray("entries").acsa)
                    ids.add(((AcsObject)entry).getAsAcsArray("contexts").acsa.isEmpty() ? ((AcsObject)entry).getAsInt("id") : -1);
                factoryTables.put(factory.getAsInt("id"), ids);
            }
        }
    }
    /** Whether no reachable statement of the callee can raise an error (the budget aside). */
    private static boolean total(ExecScalarAnalysis analysis) {
        for (var block : analysis.blocks) {
            if (block.source == null || block.in == null) continue;
            int op = opcode(block.source);
            AcsElement value = block.condition ? field(block.source,"v")
                    : op == DEFINE || op == SET ? field(block.source,"val") : op == MOVE ? field(block.source,"v2")
                    : op == RETURN ? field(block.source,"r") : op == BREAK || op == CONTINUE ? null : block.source;
            if (op == MOVE && opcode(field(block.source,"v1")) != VARIABLE) return false;
            if (value != null && !analysis.evaluate(value, block.in).total()) return false;
        }
        return true;
    }
    /**
     * Whether the caller may run destructors while an error unwinds its own frame.
     * A host callback there sees the trace published when the error left the
     * failing function's entry, which an inlined function no longer has.
     */
    private static boolean holdsObjects(AcsElement value, Map<Integer,Integer> returns) {
        int op = opcode(value);
        if (op == OBJECT_BIND || op == CLEANUP || op == NEW_BLOCK || op == BUFFER_NEW || op == MOVE_OUT || op == DROP) return true;
        if (op == DEFINE && ((AcsObject)value).mmp.containsKey("declared-type") && number(value,"declared-type") == OBJECT_VALUE) return true;
        if (op == CALL && returns.getOrDefault(number(value,"id"), ANY_VALUE) == OBJECT_VALUE) return true;
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY && holdsObjects(child.value(), returns)) return true;
        return false;
    }
    private static int declared(AcsElement definition) {
        var object = (AcsObject)definition;
        return object.mmp.containsKey("declared-type") ? object.getAsInt("declared-type") : ANY_VALUE;
    }
    private static List<AcsElement> items(AcsElement value) {
        return value instanceof AcsArray block ? new ArrayList<>(block.acsa) : new ArrayList<>(List.of(value));
    }
    private static AcsArray block(List<AcsElement> items) { var result = new AcsArray(); result.acsa.addAll(items); return result; }
    private static boolean returnInLoop(AcsElement value, boolean loop) {
        int op = opcode(value);
        if (op == RETURN) return loop;
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY
                    && returnInLoop(child.value(), loop || op == WHILE)) return true;
        return false;
    }
    private static boolean containsReturn(AcsElement value) { return returnInLoop(value, true); }
    private static boolean alwaysReturns(List<AcsElement> items) {
        for (var item : items) {
            if (opcode(item) == RETURN) return true;
            if (opcode(item) == IF && field(item,"else") != null
                    && alwaysReturns(items(field(item,"val"))) && alwaysReturns(items(field(item,"else")))) return true;
        }
        return false;
    }
    /** Moves the statements after an early return into the other branch, so every return is a tail. */
    private static List<AcsElement> lift(List<AcsElement> items, Map<AcsElement,int[]> positions) {
        for (int i = 0; i < items.size(); i++) {
            AcsElement item = items.get(i);
            if (opcode(item) == RETURN) return new ArrayList<>(items.subList(0, i + 1));
            if (!containsReturn(item)) continue;
            if (opcode(item) != IF) return null;
            List<AcsElement> rest = items.subList(i + 1, items.size());
            List<AcsElement> then = items(field(item,"val")), otherwise = field(item,"else") == null ? new ArrayList<>() : items(field(item,"else"));
            boolean thenReturns = alwaysReturns(then), elseReturns = alwaysReturns(otherwise);
            int tail = rest.stream().mapToInt(ExecScalarAnalysis::count).sum();
            if (!thenReturns && !elseReturns && tail > MAX_DUPLICATED_TAIL) return null;
            if (!thenReturns) then.addAll(copies(rest, positions));
            if (!elseReturns) otherwise.addAll(copies(rest, positions));
            var liftedThen = lift(then, positions); var liftedElse = lift(otherwise, positions);
            if (liftedThen == null || liftedElse == null) return null;
            var branch = (AcsObject)item;
            branch.mmp.put("val", ExecO2.locate(block(liftedThen), field(item,"val"), positions));
            AcsElement previous = field(item,"else");
            branch.mmp.put("else", previous == null ? block(liftedElse) : ExecO2.locate(block(liftedElse), previous, positions));
            var result = new ArrayList<>(items.subList(0, i)); result.add(item); return result;
        }
        return items;
    }
    private static List<AcsElement> copies(List<AcsElement> items, Map<AcsElement,int[]> positions) {
        List<AcsElement> result = new ArrayList<>();
        for (var item : items) result.add(ExecO2.copy(item, positions));
        return result;
    }
    private static void collect(AcsElement value, int op, List<AcsElement> result) {
        if (opcode(value) == op) result.add(value);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) collect(child.value(), op, result);
    }

    // ---- callers ------------------------------------------------------------

    /** The rewritten body, or null when no call was inlined or the result exceeds a limit. */
    private AcsElement caller(AcsObject function, Map<Integer,Integer> returns) {
        if (!(function.mmp.get("script") instanceof AcsArray original)) return null;
        int base = function.getAsInt("param-count") + function.getAsInt("local-count");
        int originalNodes = count(original);
        // Debug records name inlined functions within the caller's namespace;
        // lifecycle hooks (namespace 0) keep their calls.
        namespace = function.getAsInt("id") >>> 16;
        if (originalNodes > MAX_CALLER_NODES || base > MAX_SLOTS || !analyzable(original)) return null;
        nodes = originalNodes; limit = Math.min(MAX_CALLER_NODES, Math.max(originalNodes * 3, originalNodes + 512));
        objects = holdsObjects(original, returns);
        if (!methods.isEmpty()) factories(original);
        var context = new Context(new ExecScalarAnalysis(function,returns), null, Map.of(), 1, 0);
        var body = (AcsArray)clone(original, context);
        body = (AcsArray)statement(original, body, context);
        if (expansions == 0) return null;
        List<Integer> physical = new ArrayList<>();
        Map<Integer,Integer> slots = new HashMap<>();
        compact(body, base, physical, new HashSet<>(), slots);
        if (base + physical.size() > MAX_SLOTS) return null;
        relabel(body, slots);
        // Constant initializers keep each slot's tag stable from the entry on.
        var declarations = new ArrayList<AcsElement>();
        for (int i = 0; i < physical.size(); i++) {
            int type = physical.get(i);
            if (type == SCOPED) continue;
            AcsElement initial = type == BOOLEAN_VALUE ? new AcsBooleanElement(false) : new AcsIntegerElement(0);
            declarations.add(ExecO2.define(base + i, type, initial, null));
        }
        body.acsa.addAll(0, declarations);
        function.put("local-count", base + physical.size() - function.getAsInt("param-count"));
        return body;
    }
    private static final int SCOPED = -2;
    /**
     * Facts at a statement hold for every expression in it only when writes are
     * visible to the analysis: at statement roots of blocks, branches and loops,
     * and never in a cleanup body.
     */
    private static boolean analyzable(AcsElement value) {
        int op = opcode(value);
        if (op == BLOCK) return ((AcsArray)value).acsa.stream().allMatch(ExecSequenceInliner::analyzable);
        if (op == IF || op == WHILE) return !writesLocal(field(value,"v")) && analyzable(field(value,"val"))
                && (field(value,"else") == null || analyzable(field(value,"else")));
        if (op == CLEANUP) return analyzable(field(value,"v")) && !writesLocal(field(value,"val"));
        if (op == DEFINE || op == SET) return !writesLocal(field(value,"val"));
        if (op == MOVE) return !writesLocal(field(value,"v1")) && !writesLocal(field(value,"v2"));
        return !writesLocal(value);
    }
    private static boolean writesLocal(AcsElement value) {
        if (value == null) return false;
        if (written(value) >= 0) return true;
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY && writesLocal(child.value())) return true;
        return false;
    }

    /** Applies expansions to copy, a clone of original, following the analysis structure. */
    private AcsElement statement(AcsElement original, AcsElement copy, Context context) {
        int op = opcode(original);
        if (op == BLOCK) {
            var items = ((AcsArray)original).acsa; var copies = ((AcsArray)copy).acsa;
            for (int i = 0; i < items.size(); i++) copies.set(i, statement(items.get(i), copies.get(i), context));
            return copy;
        }
        if (op == CLEANUP) {
            ((AcsObject)copy).mmp.put("v", statement(field(original,"v"), field(copy,"v"), context));
            return copy;
        }
        Block site = context.analysis.sites.get(original);
        Map<Integer,Value> facts = site == null ? null : site.in;
        if (op == IF || op == WHILE) {
            var object = (AcsObject)copy;
            if (facts != null) object.mmp.put("v", expression(field(original,"v"), field(copy,"v"), context, facts, false));
            for (String name : List.of("val", "else"))
                if (object.mmp.containsKey(name)) object.mmp.put(name, statement(field(original,name), field(copy,name), context));
            return copy;
        }
        return facts == null ? copy : expression(original, copy, context, facts, true);
    }
    private AcsElement expression(AcsElement original, AcsElement copy, Context context, Map<Integer,Value> facts, boolean root) {
        var originals = ExecShape.children(original); var copies = ExecShape.children(copy);
        for (int i = 0; i < originals.size(); i++) {
            var role = originals.get(i).role();
            if (role == ExecShape.Role.CONTEXT || role == ExecShape.Role.METHOD_ENTRY || role == ExecShape.Role.BLOCK) continue;
            AcsElement next = expression(originals.get(i).value(), copies.get(i).value(), context, facts, false);
            if (next != copies.get(i).value()) ExecOptimizer.replace(copy, copies.get(i), next);
        }
        if (opcode(original) == CALL) {
            AcsElement sequence = inline(original, copy, context, facts, root);
            if (sequence != null) return sequence;
        }
        if (opcode(original) == CALL_VIRTUAL && !methods.isEmpty()) {
            AcsElement guarded = devirtualize(original, copy, context, facts);
            if (guarded != null) return guarded;
        }
        return copy;
    }
    /**
     * select(guard_method(r, slot, T), the inlined T, the original call): the
     * guard is false wherever the call would not enter T or would fail, so that
     * path keeps every error. Arguments are pure, so neither path sees a table
     * the other would not.
     */
    private AcsElement devirtualize(AcsElement original, AcsElement copy, Context context, Map<Integer,Value> facts) {
        if (context.rename != null) return null;
        var call = (AcsObject)original;
        var arguments = call.getAsAcsArray("param").acsa;
        if (arguments.isEmpty() || opcode(arguments.get(0)) != VARIABLE) return null;
        int slot = call.getAsInt("slot"), receiver = number(arguments.get(0), "v");
        Integer target = target(receiver, slot);
        Method method = target == null ? null : methods.get(target);
        if (method == null || target >>> 16 != namespace) return null;
        Candidate callee = method.body;
        int type = call.getAsInt("type");
        if (type != ANY_VALUE && type != callee.returnType || callee.returnType == VOID_VALUE) return null;
        if (objects && !callee.total || arguments.size() - 1 != callee.params) return null;
        var types = callee.function.getAsAcsArray("param-types").acsa;
        var copied = ((AcsObject)copy).getAsAcsArray("param").acsa;
        for (int i = 1; i < arguments.size(); i++) {
            if (!ExecO2.reusable(copied.get(i))) return null;
            if (context.analysis.evaluate(arguments.get(i), facts).type() != ((AcsIntegerElement)types.get(i - 1)).s) return null;
        }
        if (nodes + 2 * callee.nodes + 4 * arguments.size() + 6 > limit) return null;
        var plain = new Context(null, null, Map.of(), 0, 0);
        List<AcsElement> rest = new ArrayList<>();
        for (int i = 1; i < copied.size(); i++) rest.add(clone(copied.get(i), plain));
        List<AcsElement> checks = new ArrayList<>();
        for (int offset : method.checks) {
            var check = new AcsObject(); check.put("t",0); check.put("c",OBJECT_ADDRESS);
            check.mmp.put("v", clone(copied.get(0), plain)); check.put("offset", offset);
            checks.add(ExecO2.locate(check, copy, positions));
        }
        var guard = new AcsObject(); guard.put("t",0); guard.put("c",GUARD_METHOD);
        guard.mmp.put("v", clone(copied.get(0), plain)); guard.put("slot", slot); guard.put("id", target.intValue());
        ExecO2.locate(guard, copy, positions);
        var choice = new AcsObject(); choice.put("t",0); choice.put("c",SELECT);
        choice.mmp.put("v", guard);
        choice.mmp.put("then", build(callee, rest, copy, context, checks));
        choice.mmp.put("else", copy);
        // The original call stays on the other path.
        nodes += count(copy) + count(guard) + 1;
        return ExecO2.locate(choice, copy, positions);
    }
    /** The receiver's class from its factory when it is assigned once, else the slot's only binding. */
    private Integer target(int receiver, int slot) {
        if (receiver >= 0) {
            Integer factory = singleFactory.get(receiver);
            List<Integer> table = factory == null ? null : factoryTables.get(factory);
            if (table != null && slot < table.size() && table.get(slot) >= 0) return table.get(slot);
        }
        return slotTargets.get(slot);
    }
    private final Map<Integer,Integer> singleFactory = new HashMap<>();
    /** Locals stored exactly once, by a definition from a call: the call's function. */
    private void factories(AcsElement body) {
        Map<Integer,Integer> stores = new HashMap<>();
        countStores(body, stores);
        List<AcsElement> definitions = new ArrayList<>(); collect(body, DEFINE, definitions);
        for (var definition : definitions) {
            int slot = number(definition, "v");
            AcsElement value = field(definition, "val");
            if (stores.getOrDefault(slot, 0) == 1 && value != null && opcode(value) == CALL) singleFactory.put(slot, number(value, "id"));
        }
    }
    private static void countStores(AcsElement value, Map<Integer,Integer> stores) {
        int slot = written(value);
        if (slot >= 0) stores.merge(slot, 1, Integer::sum);
        if (opcode(value) == MOVE_OUT) stores.merge(number(value, "v"), 2, Integer::sum);
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) countStores(child.value(), stores);
    }
    private AcsElement inline(AcsElement original, AcsElement copy, Context context, Map<Integer,Value> facts, boolean root) {
        if (context.depth > MAX_DEPTH) return null;
        var call = (AcsObject)original;
        Candidate callee = candidates.get(call.getAsInt("id"));
        if (callee == null || callee.returnType == VOID_VALUE && !root) return null;
        if (call.getAsInt("id") >>> 16 != namespace) return null;
        if (objects && !callee.total) return null;
        if (call.mmp.containsKey("contexts") && !call.getAsAcsArray("contexts").acsa.isEmpty()) return null;
        var arguments = call.getAsAcsArray("param").acsa;
        var types = callee.function.getAsAcsArray("param-types").acsa;
        if (arguments.size() != callee.params) return null;
        for (int i = 0; i < arguments.size(); i++)
            if (context.analysis.evaluate(arguments.get(i), facts).type() != ((AcsIntegerElement)types.get(i)).s) return null;
        if (nodes + callee.nodes + 2 * arguments.size() + 2 > limit) return null;
        return build(callee, ((AcsObject)copy).getAsAcsArray("param").acsa, copy, context, List.of());
    }
    /**
     * The sequence of callee at a call, from the call's (copied, expanded)
     * arguments; checks run after the argument snapshots, before the body.
     */
    private AcsObject build(Candidate callee, List<AcsElement> copied, AcsElement copy, Context context, List<AcsElement> checks) {
        int before = nodes;
        var types = callee.function.getAsAcsArray("param-types").acsa;
        int id = callee.display;
        int site = sites == null ? 0 : sites.add(originals.getOrDefault(id, id));
        List<Owned> slots = new ArrayList<>();
        Map<Integer,Integer> rename = new HashMap<>(); Map<Integer,AcsElement> substitutes = new HashMap<>();
        List<AcsElement> statements = new ArrayList<>();
        for (int i = 0; i < copied.size(); i++) {
            AcsElement argument = copied.get(i); int op = opcode(argument);
            // A constant or local stands for an unwritten parameter: nothing in
            // the sequence writes a slot of the caller.
            if (!callee.writtenParams.contains(i) && (op == CONSTANT || op == VARIABLE)) { substitutes.put(i, argument); continue; }
            int slot = virtual(slots, ((AcsIntegerElement)types.get(i)).s, true);
            rename.put(i, slot);
            statements.add(set(slot, argument, copy));
        }
        for (var check : checks) statements.add(clone(check, new Context(null, null, Map.of(), 0, site)));
        callee.hoisted.forEach((slot, type) -> rename.put(slot, virtual(slots, type, true)));
        callee.scoped.forEach((slot, type) -> rename.put(slot, virtual(slots, type, false)));
        int result = callee.simpleTail || callee.returnType == VOID_VALUE ? -1 : virtual(slots, callee.returnType, true);

        var inner = new Context(callee.analysis, rename, substitutes, context.depth + 1, site);
        var body = (AcsArray)clone(callee.body, inner);
        statement(callee.body, body, inner);
        AcsElement value = callee.returnType == VOID_VALUE ? new AcsIntegerElement(0) : null;
        List<AcsElement> converted = new ArrayList<>(body.acsa);
        if (callee.simpleTail) value = field(converted.remove(converted.size() - 1), "r");
        for (int i = 0; i < converted.size(); i++) {
            AcsElement item = converted.get(i);
            // Top-level declarations become stores of their hoisted slots.
            if (opcode(item) == DEFINE) item = set(number(item,"v"), field(item,"val"), item);
            converted.set(i, returns(item, result));
        }
        converted.removeIf(item -> item == null);
        statements.addAll(converted);
        if (value == null) value = ExecO2.variable(result, copy, positions);

        var sequence = new AcsObject(); sequence.put("t",0); sequence.put("c",SEQ);
        sequence.mmp.put("statements", block(statements)); sequence.mmp.put("v", value);
        ExecO2.locate(sequence, copy, positions);
        if (sites != null) sites.callSite(site, sequence);
        owned.put(sequence, slots);
        // Nested expansions are part of the sequence already.
        nodes = before + count(sequence) - count(copy); expansions++;
        return sequence;
    }
    /** The tail returns of a lifted body as stores of the result slot (or nothing in a void callee). */
    private AcsElement returns(AcsElement value, int result) {
        int op = opcode(value);
        if (op == RETURN) return result < 0 ? null : set(result, field(value,"r"), value);
        if (op == IF) {
            var object = (AcsObject)value;
            for (String name : List.of("val", "else")) if (object.mmp.containsKey(name)) {
                AcsElement branch = object.mmp.get(name);
                if (branch instanceof AcsArray items) {
                    for (int i = 0; i < items.acsa.size(); i++) items.acsa.set(i, returns(items.acsa.get(i), result));
                    items.acsa.removeIf(item -> item == null);
                } else {
                    AcsElement next = returns(branch, result);
                    object.mmp.put(name, next == null ? new AcsArray() : next);
                }
            }
        }
        return value;
    }
    private int virtual(List<Owned> slots, int type, boolean hoisted) {
        int slot = nextVirtual++; slots.add(new Owned(slot, type, hoisted)); return slot;
    }
    private AcsObject set(int slot, AcsElement value, AcsElement located) {
        var store = new AcsObject(); store.put("t",0); store.put("c",SET); store.put("v",slot); store.mmp.put("val",value);
        return ExecO2.locate(store, located, positions);
    }
    /** A deep copy with slots renamed, substituted parameters replaced and positions placed in the context's site. */
    private AcsElement clone(AcsElement value, Context context) {
        if (value == null) return null;
        AcsElement result;
        if (value instanceof AcsObject object) {
            if (context.rename != null && expression(object, VARIABLE)
                    && context.substitutes.containsKey(object.getAsInt("v")))
                return clone(context.substitutes.get(object.getAsInt("v")), new Context(null, null, Map.of(), 0, 0));
            var next = new AcsObject();
            object.mmp.forEach((key, child) -> next.mmp.put(key, clone(child, context)));
            if (context.rename != null && (expression(next, VARIABLE) || expression(next, DEFINE) || expression(next, SET))) {
                if (next.getAsInt("v") < 0) throw new IllegalStateException("Global in an inline candidate");
                Integer renamed = context.rename.get(next.getAsInt("v"));
                if (renamed == null) throw new IllegalStateException("Unmapped inline slot " + next.getAsInt("v"));
                next.put("v", renamed.intValue());
            }
            result = next;
        } else if (value instanceof AcsArray array) {
            var next = new AcsArray();
            for (var child : array.acsa) next.acsa.add(clone(child, context));
            result = next;
        } else if (value instanceof AcsIntegerElement integer) result = new AcsIntegerElement(integer.s);
        else if (value instanceof AcsBooleanElement bool) result = new AcsBooleanElement(bool.on);
        else result = value;
        if (positions != null && positions.containsKey(value))
            positions.put(result, context.site == 0 ? positions.get(value) : ExecInlineSites.tagged(positions.get(value), context.site));
        return result;
    }

    // ---- slot compaction ----------------------------------------------------

    /**
     * Gives each sequence's slots physical slots that no enclosing sequence holds,
     * so sequences that never nest share them. Hoisted slots keep one type each;
     * scoped ones are declared in nested blocks, which undeclare them on exit.
     */
    private void compact(AcsElement value, int base, List<Integer> physical, Set<Integer> active, Map<Integer,Integer> slots) {
        List<Integer> mine = new ArrayList<>();
        var claimed = owned.get(value);
        if (claimed != null) for (var slot : claimed) {
            int kind = slot.hoisted ? slot.type : SCOPED, chosen = -1;
            for (int i = 0; i < physical.size() && chosen < 0; i++)
                if (physical.get(i) == kind && !active.contains(i) && (slot.hoisted || scopedType(i) == slot.type)) chosen = i;
            if (chosen < 0) { chosen = physical.size(); physical.add(kind); scopedTypes.put(chosen, slot.type); }
            active.add(chosen); mine.add(chosen); slots.put(slot.slot, base + chosen);
        }
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY)
                compact(child.value(), base, physical, active, slots);
        active.removeAll(mine);
    }
    private final Map<Integer,Integer> scopedTypes = new HashMap<>();
    private int scopedType(int index) { return scopedTypes.getOrDefault(index, -1); }
    private static boolean expression(AcsObject value, int op) {
        return value.mmp.get("t") instanceof AcsIntegerElement kind && kind.s == 0
                && value.mmp.get("c") instanceof AcsIntegerElement code && code.s == op;
    }
    private static void relabel(AcsElement value, Map<Integer,Integer> slots) {
        if (value instanceof AcsObject object && (expression(object, VARIABLE) || expression(object, DEFINE) || expression(object, SET))
                && object.getAsInt("v") >= VIRTUAL)
            object.put("v", slots.get(object.getAsInt("v")).intValue());
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY) relabel(child.value(), slots);
    }
}
