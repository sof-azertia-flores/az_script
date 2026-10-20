package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;

/**
 * O2's closed scalar regions. No heap identity, host callback or scope observer
 * is admitted here. Facts describe successful evaluations, not nominal types.
 * The structured tree remains the executable representation; this CFG is only
 * a compiler-side analysis and never changes the v10 contract.
 */
final class ExecScalarAnalysis {
    static final int MAX_NODES = 4096;
    static final Value UNKNOWN = new Value(-1, 0, 0, false);
    record Value(int type, long low, long high, boolean total) {
        boolean scalar() { return type == INT_VALUE || type == BOOLEAN_VALUE; }
        boolean constant() { return scalar() && low == high; }
        Value evaluated() { return scalar() ? new Value(type, low, high, true) : UNKNOWN; }
        AcsElement literal() {
            return type == BOOLEAN_VALUE ? new AcsBooleanElement(low != 0) : new AcsIntegerElement((int)low);
        }
    }
    static Value integer(long low, long high, boolean total) {
        return new Value(INT_VALUE, low, high, total);
    }
    static Value full(int type) {
        return type == INT_VALUE ? integer(Integer.MIN_VALUE, Integer.MAX_VALUE, true)
                : type == BOOLEAN_VALUE ? new Value(type, 0, 1, true) : UNKNOWN;
    }
    static int opcode(AcsElement value) {
        if (value instanceof AcsArray) return BLOCK;
        if (value instanceof AcsObject object) return object.getAsInt("t") == 1 ? CALL : object.getAsInt("c");
        return CONSTANT;
    }
    static AcsElement field(AcsElement value, String name) { return ((AcsObject)value).mmp.get(name); }
    static int number(AcsElement value, String name) { return ((AcsObject)value).getAsInt(name); }
    static boolean scalar(int type) { return type == INT_VALUE || type == BOOLEAN_VALUE; }
    static int count(AcsElement value) {
        int size = 1;
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY)
                size += count(child.value());
        return size;
    }

    /** A least fixed point: recursion and unresolved callees never become safe. */
    static Set<Integer> safeFunctions(AcsArray functions) {
        Set<Integer> ids = new HashSet<>();
        for (var item : functions.acsa) ids.add(((AcsObject)item).getAsInt("id"));
        Map<Integer,Set<Integer>> pending = new LinkedHashMap<>();
        Map<Integer,List<Integer>> callers = new HashMap<>();
        var work = new ArrayDeque<Integer>();
        for (var item : functions.acsa) {
            var function = (AcsObject)item;
            if (!eligible(function,ids)) continue;
            int id = function.getAsInt("id");
            Set<Integer> dependencies = new HashSet<>();
            calls(function.mmp.get("script"),dependencies);
            pending.put(id,dependencies);
            if (dependencies.isEmpty()) work.add(id);
            for (int callee : dependencies) callers.computeIfAbsent(callee,ignored -> new ArrayList<>()).add(id);
        }
        Set<Integer> safe = new HashSet<>();
        while (!work.isEmpty()) {
            int id = work.remove();
            if (!safe.add(id)) continue;
            for (int caller : callers.getOrDefault(id,List.of())) {
                Set<Integer> dependencies = pending.get(caller);
                dependencies.remove(id);
                if (dependencies.isEmpty()) work.add(caller);
            }
        }
        return Set.copyOf(safe);
    }
    private static void calls(AcsElement value, Set<Integer> result) {
        if (opcode(value) == CALL) result.add(number(value,"id"));
        for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY)
                calls(child.value(),result);
    }
    static boolean eligible(AcsObject function, Set<Integer> safe) {
        if (function.getAsInt("hidden-count") != 0 || function.getAsInt("entry-kind") != 0) return false;
        // Bound the product of CFG states and local facts, not just tree size.
        if ((long)function.getAsInt("param-count") + function.getAsInt("local-count") > 256) return false;
        if (!scalar(function.getAsInt("return-type")) && function.getAsInt("return-type") != VOID_VALUE) return false;
        for (var type : function.getAsAcsArray("param-types").acsa)
            if (!scalar(((AcsIntegerElement)type).s)) return false;
        AcsElement body = function.mmp.get("script");
        return count(body) <= MAX_NODES && statement(body, safe);
    }
    static boolean expression(AcsElement value, Set<Integer> safe) {
        if (value == null) return true;
        int op = opcode(value);
        if (op == CONSTANT) return value instanceof AcsIntegerElement || value instanceof AcsBooleanElement;
        if (op == VARIABLE) return number(value, "v") >= 0;
        if (op == CALL) {
            if (!safe.contains(number(value, "id"))) return false;
            var call = (AcsObject)value;
            if (call.mmp.containsKey("contexts") && !call.getAsAcsArray("contexts").acsa.isEmpty()) return false;
        } else if (!(op >= ADD && op <= NEGATE || op >= BIT_AND && op <= SELECT)) return false;
        if (op == CONVERT && !scalar(number(value, "type"))) return false;
        for (var child : ExecShape.children(value))
            if (!expression(child.value(), safe)) return false;
        return true;
    }
    private static boolean statement(AcsElement value, Set<Integer> safe) {
        int op = opcode(value);
        if (op == BLOCK) return ((AcsArray)value).acsa.stream().allMatch(item -> statement(item, safe));
        if (op == DEFINE || op == SET) {
            var object = (AcsObject)value;
            if (number(value, "v") < 0 || object.mmp.containsKey("declared-type")
                    && !scalar(object.getAsInt("declared-type"))) return false;
            return expression(field(value, "val"), safe);
        }
        if (op == MOVE) return opcode(field(value, "v1")) == VARIABLE
                && number(field(value, "v1"), "v") >= 0 && expression(field(value, "v2"), safe);
        if (op == RETURN) return expression(field(value, "r"), safe);
        if (op == BREAK || op == CONTINUE) return true;
        if (op == IF || op == WHILE) return expression(field(value, "v"), safe)
                && statement(field(value, "val"), safe)
                && (field(value, "else") == null || statement(field(value, "else"), safe));
        return expression(value, safe);
    }

    static final class Block {
        final int id;
        final AcsElement source;
        final boolean condition;
        final List<Block> next = new ArrayList<>(), previous = new ArrayList<>();
        Map<Integer,Value> in;
        final Set<Integer> liveIn = new HashSet<>(), liveOut = new HashSet<>();
        BitSet dominators = new BitSet();
        int visits;
        Block(int id, AcsElement source, boolean condition) {
            this.id = id; this.source = source; this.condition = condition;
        }
    }
    final List<Block> blocks = new ArrayList<>();
    final IdentityHashMap<AcsElement,Block> sites = new IdentityHashMap<>();
    final Map<Integer,Integer> returns;
    final Block entry, exit;
    ExecScalarAnalysis(AcsObject function, Map<Integer,Integer> returns) {
        this.returns = returns;
        exit = add(null, false);
        entry = region(function.mmp.get("script"), exit, exit, exit);
        var initial = new HashMap<Integer,Value>();
        var params = function.getAsAcsArray("param-types").acsa;
        for (int i = 0; i < params.size(); i++) initial.put(i, full(((AcsIntegerElement)params.get(i)).s));
        solve(initial);
        liveness();
        dominance();
    }
    private Block add(AcsElement source, boolean condition) {
        var block = new Block(blocks.size(), source, condition); blocks.add(block);
        if (source != null) sites.put(source, block);
        return block;
    }
    private static void edge(Block from, Block to) { from.next.add(to); to.previous.add(from); }
    private Block region(AcsElement source, Block next, Block broken, Block continued) {
        int op = opcode(source);
        if (op == BLOCK) {
            var items = ((AcsArray)source).acsa;
            for (int i = items.size(); i-- > 0;) next = region(items.get(i), next, broken, continued);
            return next;
        }
        if (op == IF || op == WHILE) {
            Block condition = add(source, true);
            if (op == WHILE) {
                edge(condition, region(field(source, "val"), condition, next, condition));
                edge(condition, next);
            } else {
                edge(condition, region(field(source, "val"), next, broken, continued));
                var alternative = field(source, "else");
                edge(condition, alternative == null ? next : region(alternative, next, broken, continued));
            }
            return condition;
        }
        Block block = add(source, false);
        edge(block, op == RETURN ? exit : op == BREAK ? broken : op == CONTINUE ? continued : next);
        return block;
    }
    private void solve(Map<Integer,Value> initial) {
        entry.in = initial;
        var work = new ArrayDeque<Block>(); var queued = new HashSet<Block>();
        work.add(entry); queued.add(entry);
        while (!work.isEmpty()) {
            Block block = work.remove(); queued.remove(block);
            Map<Integer,Value> out = transfer(block);
            for (int i = 0; i < block.next.size(); i++) {
                Block target = block.next.get(i);
                var incoming = new HashMap<>(out);
                if (block.condition && !refine(field(block.source, "v"), i == 0, incoming)) continue;
                Map<Integer,Value> merged = target.in == null ? incoming : join(target.in, incoming, target.visits > 3);
                if (!merged.equals(target.in)) {
                    target.in = merged; target.visits++;
                    if (queued.add(target)) work.add(target);
                }
            }
        }
    }
    static Map<Integer,Value> join(Map<Integer,Value> a, Map<Integer,Value> b, boolean widen) {
        Map<Integer,Value> result = new HashMap<>();
        for (var item : a.entrySet()) {
            Value x = item.getValue(), y = b.get(item.getKey());
            if (y == null || !x.scalar() || x.type != y.type) continue;
            long low = Math.min(x.low, y.low), high = Math.max(x.high, y.high);
            if (widen && (low != x.low || high != x.high)) result.put(item.getKey(), full(x.type));
            else result.put(item.getKey(), new Value(x.type, low, high, true));
        }
        return result;
    }
    Map<Integer,Value> transfer(Block block) {
        var out = new HashMap<>(block.in);
        if (block.source == null || block.condition) return out;
        int slot = written(block.source);
        if (slot >= 0) {
            int op = opcode(block.source);
            Value value = evaluate(field(block.source, op == MOVE ? "v2" : "val"), out).evaluated();
            out.remove(slot);
            if (value.scalar()) out.put(slot, value);
        }
        return out;
    }
    static int written(AcsElement value) {
        int op = opcode(value);
        return op == DEFINE || op == SET ? number(value, "v")
                : op == MOVE && opcode(field(value, "v1")) == VARIABLE ? number(field(value, "v1"), "v") : -1;
    }
    static Set<Integer> reads(AcsElement value) {
        Set<Integer> result = new HashSet<>();
        if (value == null) return result;
        if (opcode(value) == VARIABLE) result.add(number(value, "v"));
        else for (var child : ExecShape.children(value))
            if (child.role() != ExecShape.Role.STORAGE_TARGET) result.addAll(reads(child.value()));
        return result;
    }
    private void liveness() {
        boolean changed;
        do {
            changed = false;
            for (Block block : blocks) {
                Set<Integer> out = new HashSet<>();
                for (Block next : block.next) out.addAll(next.liveIn);
                Set<Integer> in = new HashSet<>(out);
                if (block.source != null) {
                    if (!block.condition) in.remove(written(block.source));
                    in.addAll(reads(block.condition ? field(block.source, "v") : block.source));
                }
                if (!in.equals(block.liveIn) || !out.equals(block.liveOut)) {
                    block.liveIn.clear(); block.liveIn.addAll(in);
                    block.liveOut.clear(); block.liveOut.addAll(out); changed = true;
                }
            }
        } while (changed);
    }
    private void dominance() {
        BitSet reached = new BitSet();
        for (Block b : blocks) if (b.in != null) reached.set(b.id);
        for (Block b : blocks) b.dominators = (BitSet)reached.clone();
        entry.dominators.clear(); entry.dominators.set(entry.id);
        boolean changed;
        do {
            changed = false;
            for (Block b : blocks) if (b != entry && b.in != null) {
                BitSet next = (BitSet)reached.clone();
                for (Block previous : b.previous) if (previous.in != null) next.and(previous.dominators);
                next.set(b.id);
                if (!next.equals(b.dominators)) { b.dominators = next; changed = true; }
            }
        } while (changed);
    }
    Value evaluate(AcsElement source, Map<Integer,Value> facts) {
        if (source == null) return UNKNOWN;
        if (source instanceof AcsIntegerElement n) return integer(n.s, n.s, true);
        if (source instanceof AcsBooleanElement b) return new Value(BOOLEAN_VALUE, b.on ? 1 : 0, b.on ? 1 : 0, true);
        int op = opcode(source);
        if (op == VARIABLE) return facts.getOrDefault(number(source, "v"), UNKNOWN);
        if (op == CALL) return new Value(returns.getOrDefault(number(source, "id"), -1),
                full(returns.getOrDefault(number(source, "id"), -1)).low,
                full(returns.getOrDefault(number(source, "id"), -1)).high, false);
        if (op == SELECT) {
            Value test = evaluate(field(source, "v"), facts);
            if (test.type != BOOLEAN_VALUE) return UNKNOWN;
            if (test.constant()) {
                Value selected = evaluate(field(source, test.low != 0 ? "then" : "else"), facts);
                return new Value(selected.type,selected.low,selected.high,test.total && selected.total);
            }
            Value a = evaluate(field(source, "then"), facts), b = evaluate(field(source, "else"), facts);
            return a.scalar() && a.type == b.type
                    ? new Value(a.type, Math.min(a.low,b.low), Math.max(a.high,b.high), test.total && a.total && b.total) : UNKNOWN;
        }
        if (op == NEGATE || op == NOT || op == CONVERT) {
            Value a = evaluate(field(source, "v"), facts);
            if (op == NOT && a.type == BOOLEAN_VALUE) return new Value(BOOLEAN_VALUE, 1-a.high, 1-a.low, a.total);
            if (op == NEGATE && a.type == INT_VALUE) return checked(-a.high, -a.low, a.total);
            if (op == CONVERT && number(source, "type") == a.type) return a;
            return UNKNOWN;
        }
        Value a = evaluate(field(source, "v1"), facts), b = evaluate(field(source, "v2"), facts);
        if (a.type == BOOLEAN_VALUE && (op == AND || op == OR)) {
            if (a.constant() && (op == AND && a.low == 0 || op == OR && a.low != 0)) return a;
            if (b.type != BOOLEAN_VALUE) return UNKNOWN;
            return new Value(BOOLEAN_VALUE, op == AND ? a.low & b.low : a.low | b.low,
                    op == AND ? a.high & b.high : a.high | b.high, a.total && b.total);
        }
        if (!a.scalar() || a.type != b.type) return UNKNOWN;
        boolean total = a.total && b.total;
        if (op >= GREATER && op <= LESS_EQUAL) {
            if (a.type == BOOLEAN_VALUE && op != EQUAL && op != NOT_EQUAL) return UNKNOWN;
            Boolean known = switch (op) {
                case LESS -> a.high < b.low ? Boolean.TRUE : a.low >= b.high ? Boolean.FALSE : null;
                case GREATER -> a.low > b.high ? Boolean.TRUE : a.high <= b.low ? Boolean.FALSE : null;
                case LESS_EQUAL -> a.high <= b.low ? Boolean.TRUE : a.low > b.high ? Boolean.FALSE : null;
                case GREATER_EQUAL -> a.low >= b.high ? Boolean.TRUE : a.high < b.low ? Boolean.FALSE : null;
                case EQUAL, NOT_EQUAL -> a.high < b.low || b.high < a.low ? Boolean.valueOf(op == NOT_EQUAL)
                        : a.constant() && b.constant() ? Boolean.valueOf(op == EQUAL) : null;
                default -> null;
            };
            return new Value(BOOLEAN_VALUE, known == null ? 0 : known ? 1 : 0, known == null ? 1 : known ? 1 : 0, total);
        }
        if (a.type != INT_VALUE) return UNKNOWN;
        if (a.constant() && b.constant()) {
            AcsObject constant = new AcsObject(); constant.put("t",0); constant.put("c",op);
            constant.mmp.put("v1",a.literal()); constant.mmp.put("v2",b.literal());
            AcsElement result = ExecOptimizer.fold(constant);
            if (result != constant) {
                Value folded = evaluate(result, facts);
                return new Value(folded.type, folded.low, folded.high, total);
            }
        }
        return switch (op) {
            case ADD -> checked(a.low+b.low, a.high+b.high, total);
            case SUBTRACT -> checked(a.low-b.high, a.high-b.low, total);
            case MULTIPLY -> {
                long[] products = {a.low*b.low, a.low*b.high, a.high*b.low, a.high*b.high};
                yield checked(Arrays.stream(products).min().orElseThrow(), Arrays.stream(products).max().orElseThrow(), total);
            }
            case BIT_AND -> a.low >= 0 || b.low >= 0
                    ? integer(0, b.low >= 0 ? b.high : a.high, total)
                    : integer(Integer.MIN_VALUE,Integer.MAX_VALUE,total);
            case BIT_OR, BIT_XOR -> integer(Integer.MIN_VALUE, Integer.MAX_VALUE, total);
            case SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED -> integer(Integer.MIN_VALUE,Integer.MAX_VALUE,
                    total && b.low >= 0 && b.high <= 31);
            case DIVIDE, MODULO -> integer(Integer.MIN_VALUE,Integer.MAX_VALUE,
                    total && (b.low > 0 || b.high < 0)
                            && (op == MODULO || a.low > Integer.MIN_VALUE || b.low > -1 || b.high < -1));
            default -> UNKNOWN;
        };
    }
    private static Value checked(long low, long high, boolean total) {
        return low >= Integer.MIN_VALUE && high <= Integer.MAX_VALUE ? integer(low,high,total)
                : integer(Integer.MIN_VALUE,Integer.MAX_VALUE,false);
    }
    boolean refine(AcsElement source, boolean truth, Map<Integer,Value> facts) {
        Value test = evaluate(source, facts);
        if (test.type == BOOLEAN_VALUE && test.constant()) return (test.low != 0) == truth;
        int op = opcode(source);
        if (op == NOT) return refine(field(source,"v"), !truth, facts);
        if (op == AND && truth || op == OR && !truth)
            return refine(field(source,"v1"),truth,facts) && refine(field(source,"v2"),truth,facts);
        if (op == VARIABLE && test.type == BOOLEAN_VALUE) {
            facts.put(number(source,"v"),new Value(BOOLEAN_VALUE,truth?1:0,truth?1:0,true)); return true;
        }
        if (op < GREATER || op > LESS_EQUAL) return true;
        AcsElement left = field(source,"v1"), right = field(source,"v2");
        if (opcode(left) != VARIABLE || !(right instanceof AcsIntegerElement constant)) return true;
        int slot = number(left,"v"); Value value = facts.getOrDefault(slot,UNKNOWN);
        if (value.type != INT_VALUE) return true;
        long low = value.low, high = value.high, n = constant.s;
        switch (op) {
            case LESS -> { if (truth) high=Math.min(high,n-1); else low=Math.max(low,n); }
            case LESS_EQUAL -> { if (truth) high=Math.min(high,n); else low=Math.max(low,n+1); }
            case GREATER -> { if (truth) low=Math.max(low,n+1); else high=Math.min(high,n); }
            case GREATER_EQUAL -> { if (truth) low=Math.max(low,n); else high=Math.min(high,n-1); }
            case EQUAL -> { if (truth) { low=Math.max(low,n); high=Math.min(high,n); } }
            case NOT_EQUAL -> { if (!truth) { low=Math.max(low,n); high=Math.min(high,n); } }
            default -> { }
        }
        if (low > high) return false;
        facts.put(slot,integer(low,high,true)); return true;
    }
}
