package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;

/** Exact record-depth model for the exec inspection view; never serializes or changes its input. */
final class ExecShape {
    static final int INTERNAL_SEQUENCE = -1;

    enum Role { EAGER_VALUE, STORAGE_TARGET, LAZY_VALUE, LOOP_CONDITION, BLOCK, CONTEXT, METHOD_ENTRY }

    /** A list child retains its field and index; direct fields have index -1 and block items have field null. */
    record Child(String field, int index, AcsElement value, int relativeLevel, Role role) {}

    private enum Kind { EXPRESSION, CONTEXT, METHOD_ENTRY }
    private record Shape(int floor, List<Child> children) {}
    private static final class Frame {
        final AcsElement value;
        final Kind kind;
        final List<Child> children;
        int next, height;
        Frame(AcsElement value, Kind kind) {
            this.value = value; this.kind = kind;
            Shape shape = switch (kind) {
                case EXPRESSION -> expression(value, false);
                case CONTEXT -> context(value);
                case METHOD_ENTRY -> methodEntry(value);
            };
            children = shape.children(); height = shape.floor();
        }
    }
    private ExecShape() {}

    /** Deepest additional record level below the expression's own record. */
    static int height(AcsElement expression) {
        IdentityHashMap<AcsElement,EnumMap<Kind,Integer>> measured = new IdentityHashMap<>();
        IdentityHashMap<AcsElement,EnumSet<Kind>> active = new IdentityHashMap<>();
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(expression, Kind.EXPRESSION));
        active.computeIfAbsent(expression, ignored -> EnumSet.noneOf(Kind.class)).add(Kind.EXPRESSION);
        while (!frames.isEmpty()) {
            Frame frame = frames.peek();
            if (frame.next == frame.children.size()) {
                measured.computeIfAbsent(frame.value, ignored -> new EnumMap<>(Kind.class)).put(frame.kind, frame.height);
                active.get(frame.value).remove(frame.kind);
                frames.pop();
                continue;
            }
            Child child = frame.children.get(frame.next);
            Kind kind = switch (child.role()) {
                case CONTEXT -> Kind.CONTEXT;
                case METHOD_ENTRY -> Kind.METHOD_ENTRY;
                default -> Kind.EXPRESSION;
            };
            EnumMap<Kind,Integer> known = measured.get(child.value());
            if (known != null && known.containsKey(kind)) {
                try { frame.height = Math.max(frame.height, Math.addExact(child.relativeLevel(), known.get(kind))); }
                catch (ArithmeticException error) { throw invalid("depth exceeds the supported integer range"); }
                frame.next++;
                continue;
            }
            EnumSet<Kind> visiting = active.computeIfAbsent(child.value(), ignored -> EnumSet.noneOf(Kind.class));
            if (!visiting.add(kind)) throw invalid("cyclic expression or context");
            frames.push(new Frame(child.value(), kind));
        }
        return measured.get(expression).get(Kind.EXPRESSION);
    }

    /** Function bodies start at absolute level 4; the codec accepts level 128 itself. */
    static void requireFits(AcsElement expression, int absoluteLevel, int limit) {
        if (absoluteLevel < 1 || limit < 1 || limit > 128) throw invalid("invalid absolute level or ABD limit");
        if ((long) absoluteLevel + height(expression) > limit)
            throw new IllegalArgumentException("Exec ABD nesting exceeds " + limit + " levels");
    }

    /** Direct expression children; CONTEXT and METHOD_ENTRY children stay opaque to the linearizer. */
    static List<Child> children(AcsElement expression) { return expression(expression, true).children(); }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid exec shape: " + message);
    }
    private static AcsObject object(AcsElement value, String label) {
        if (!(value instanceof AcsObject result)) throw invalid(label + " must be an object");
        return result;
    }
    private static AcsArray array(AcsElement value, String label) {
        if (!(value instanceof AcsArray result)) throw invalid(label + " must be an array");
        return result;
    }
    private static int integer(AcsElement value, String label) {
        if (!(value instanceof AcsIntegerElement result)) throw invalid(label + " must be int32");
        return result.s;
    }
    private static int integer(AcsObject value, String field) { return integer(value.mmp.get(field), field); }
    private static void optionalInteger(AcsObject value, String field) {
        if (value.mmp.containsKey(field)) integer(value, field);
    }
    private static void schema(AcsObject value, String required, String optional) {
        Set<String> allowed = new HashSet<>();
        for (String field : required.split(" ")) if (!field.isEmpty()) {
            allowed.add(field);
            if (!value.mmp.containsKey(field) || value.mmp.get(field) == null) throw invalid("missing field " + field);
        }
        for (String field : optional.split(" ")) if (!field.isEmpty()) allowed.add(field);
        for (String field : value.mmp.keySet()) if (!allowed.contains(field)) throw invalid("unexpected field " + field);
    }
    private static void direct(List<Child> children, AcsObject value, String field, Role role) {
        children.add(new Child(field, -1, value.mmp.get(field), 1, role));
    }
    private static void list(List<Child> children, AcsElement value, String field, int level, Role role) {
        AcsArray values = array(value, field == null ? "block" : field);
        for (int i = 0; i < values.acsa.size(); i++)
            children.add(new Child(field, i, values.acsa.get(i), level, role));
    }
    private static void optionalContexts(List<Child> children, AcsObject value, String field) {
        AcsElement contexts = value.mmp.get(field);
        if (contexts != null) list(children, contexts, field, 2, Role.CONTEXT);
    }
    private static Shape shape(int floor, List<Child> children) { return new Shape(floor, List.copyOf(children)); }
    private static void storage(AcsElement value) {
        AcsObject target = object(value, "storage target"); int kind = integer(target, "t");
        if (!(kind == 0 && integer(target, "c") == 3) && !(kind == 1 && integer(target, "id") == 0x0abd0006))
            throw invalid("unsupported storage target");
    }
    private static void scalar(AcsElement value) {
        if (value instanceof AcsIntegerElement || value instanceof AcsStringElement || value instanceof AcsBooleanElement || value instanceof AcsAddress) return;
        if (value instanceof AcsFloat number && Float.isFinite(number.getV())) return;
        if (value instanceof AcsDouble number && Double.isFinite(number.getV())) return;
        if (value instanceof AcsByteArray bytes && Arrays.equals(bytes.getBytes(), new byte[]{(byte) 0xff})) return;
        throw invalid("unsupported scalar constant");
    }
    private static Shape expression(AcsElement value, boolean allowSequence) {
        List<Child> children = new ArrayList<>();
        if (value instanceof AcsArray) {
            list(children, value, null, 2, Role.BLOCK); return shape(1, children);
        }
        if (!(value instanceof AcsObject node)) { scalar(value); return shape(1, children); }
        int kind = integer(node, "t");
        if (kind == 1) {
            schema(node, "t id param", "contexts"); integer(node, "id");
            list(children, node.mmp.get("param"), "param", 2, Role.EAGER_VALUE);
            optionalContexts(children, node, "contexts"); return shape(1, children);
        }
        if (kind != 0) throw invalid("unknown expression kind " + kind);
        int opcode = integer(node, "c"), floor = 0;
        switch (opcode) {
            case INTERNAL_SEQUENCE -> {
                if (!allowSequence) throw invalid("internal sequence must be expanded before ABD measurement");
                schema(node, "t c statements v", "");
                list(children, node.mmp.get("statements"), "statements", 0, Role.BLOCK);
                children.add(new Child("v", -1, node.mmp.get("v"), 0, Role.EAGER_VALUE));
            }
            case 3, 34 -> { schema(node, "t c v", ""); integer(node, "v"); }
            case 4 -> {
                schema(node, "t c v", "val declared-type"); integer(node, "v"); optionalInteger(node, "declared-type");
                if (node.mmp.containsKey("val")) direct(children, node, "val", Role.EAGER_VALUE);
            }
            case 5 -> {
                schema(node, "t c v val", ""); integer(node, "v"); direct(children, node, "val", Role.EAGER_VALUE);
            }
            case 6 -> {
                schema(node, "t c v1 v2", ""); storage(node.mmp.get("v1"));
                direct(children, node, "v1", Role.STORAGE_TARGET); direct(children, node, "v2", Role.EAGER_VALUE);
            }
            case 7 -> {
                schema(node, "t c", "r");
                if (node.mmp.containsKey("r")) direct(children, node, "r", Role.EAGER_VALUE);
            }
            case 8 -> { schema(node, "t c r", ""); direct(children, node, "r", Role.EAGER_VALUE); }
            case 9 -> {
                schema(node, "t c v offset", ""); integer(node, "offset"); direct(children, node, "v", Role.EAGER_VALUE);
            }
            case 10 -> {
                schema(node, "t c v manual", "destructor contexts"); optionalInteger(node, "destructor");
                if (!(node.mmp.get("manual") instanceof AcsBooleanElement)) throw invalid("manual must be boolean");
                direct(children, node, "v", Role.EAGER_VALUE); optionalContexts(children, node, "contexts"); floor = 1;
            }
            case 11, 25, 26, 33 -> { schema(node, "t c v", ""); direct(children, node, "v", Role.EAGER_VALUE); }
            case 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 43, 44, 45, 46, 47, 48 -> {
                schema(node, "t c v1 v2", "");
                direct(children, node, "v1", Role.EAGER_VALUE); direct(children, node, "v2", Role.EAGER_VALUE);
            }
            case 23, 24 -> {
                schema(node, "t c v1 v2", "");
                direct(children, node, "v1", Role.EAGER_VALUE); direct(children, node, "v2", Role.LAZY_VALUE);
            }
            case 27 -> {
                schema(node, "t c v val", "else");
                direct(children, node, "v", Role.EAGER_VALUE); direct(children, node, "val", Role.LAZY_VALUE);
                if (node.mmp.containsKey("else")) direct(children, node, "else", Role.LAZY_VALUE);
            }
            case 28 -> {
                schema(node, "t c v val", "");
                direct(children, node, "v", Role.LOOP_CONDITION); direct(children, node, "val", Role.BLOCK);
            }
            case 29, 30 -> schema(node, "t c", "");
            case 31 -> {
                schema(node, "t c v val on-error", "");
                if (!(node.mmp.get("on-error") instanceof AcsBooleanElement)) throw invalid("on-error must be boolean");
                direct(children, node, "v", Role.BLOCK); direct(children, node, "val", Role.BLOCK);
            }
            case 32 -> { schema(node, "t c size", ""); integer(node, "size"); }
            case 35 -> {
                schema(node, "t c v", ""); storage(node.mmp.get("v")); direct(children, node, "v", Role.STORAGE_TARGET);
            }
            case 36, 37, 40 -> { schema(node, "t c context", ""); direct(children, node, "context", Role.CONTEXT); }
            case 38, 39 -> {
                schema(node, "t c v context", "");
                direct(children, node, "v", Role.EAGER_VALUE); direct(children, node, "context", Role.CONTEXT);
            }
            case 41 -> {
                schema(node, "t c op v args", ""); integer(node, "op");
                direct(children, node, "v", Role.EAGER_VALUE);
                list(children, node.mmp.get("args"), "args", 2, Role.EAGER_VALUE); floor = 1;
            }
            case 42 -> {
                schema(node, "t c context v1 v2", ""); direct(children, node, "context", Role.CONTEXT);
                direct(children, node, "v1", Role.EAGER_VALUE); direct(children, node, "v2", Role.EAGER_VALUE);
            }
            case 49 -> {
                schema(node, "t c type v", ""); integer(node, "type"); direct(children, node, "v", Role.EAGER_VALUE);
            }
            case 50 -> {
                schema(node, "t c v then else", ""); direct(children, node, "v", Role.EAGER_VALUE);
                direct(children, node, "then", Role.LAZY_VALUE); direct(children, node, "else", Role.LAZY_VALUE);
            }
            case 51 -> {
                schema(node, "t c v context slot-types", ""); direct(children, node, "v", Role.EAGER_VALUE);
                direct(children, node, "context", Role.CONTEXT);
                for (AcsElement item : array(node.mmp.get("slot-types"), "slot-types").acsa) integer(item, "slot type");
                floor = 1;
            }
            case 52 -> {
                schema(node, "t c v entries", ""); direct(children, node, "v", Role.EAGER_VALUE);
                list(children, node.mmp.get("entries"), "entries", 2, Role.METHOD_ENTRY); floor = 1;
            }
            case 53 -> {
                schema(node, "t c slot type param", ""); integer(node, "slot"); integer(node, "type");
                list(children, node.mmp.get("param"), "param", 2, Role.EAGER_VALUE); floor = 1;
            }
            default -> throw invalid("unknown control opcode " + opcode);
        }
        return shape(floor, children);
    }
    private static Shape context(AcsElement value) {
        AcsObject node = object(value, "context"); List<Child> children = new ArrayList<>();
        if (node.mmp.containsKey("ref")) {
            schema(node, "ref", ""); integer(node, "ref"); return shape(0, children);
        }
        schema(node, "abi kind", "width factory contexts placement placement-contexts element");
        integer(node, "abi"); integer(node, "kind"); optionalInteger(node, "width");
        optionalInteger(node, "factory"); optionalInteger(node, "placement");
        optionalContexts(children, node, "contexts"); optionalContexts(children, node, "placement-contexts");
        if (node.mmp.containsKey("element")) direct(children, node, "element", Role.CONTEXT);
        return shape(1, children);
    }
    private static Shape methodEntry(AcsElement value) {
        AcsObject node = object(value, "method entry"); List<Child> children = new ArrayList<>();
        schema(node, "id contexts", ""); integer(node, "id");
        list(children, node.mmp.get("contexts"), "contexts", 2, Role.CONTEXT);
        return shape(1, children);
    }
}
