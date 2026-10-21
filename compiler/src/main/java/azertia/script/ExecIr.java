package azertia.script;

import azertia.binary.complexBinary.*;
import java.util.*;
import static azertia.script.ExecOpcodes.*;
import static azertia.script.Compiler.AsTypes.*;

/** Typed analysis view of checked exec. Opaque metadata is never interpreted as code. */
final class ExecIr {
    enum Effect { READ_SLOT, WRITE_SLOT, READ_HEAP, WRITE_HEAP, CALL, THROW, ALLOCATE, CLEANUP, CONTROL }
    record Edge(ExecShape.Child location, Node value) {}
    record Node(AcsElement source, int opcode, int type, Integer slot, List<Edge> children,
                Set<Effect> ownEffects, Set<Effect> effects, List<Integer> position) {
        Node child(String field) {
            for (Edge edge : children) if (Objects.equals(field, edge.location.field())) return edge.value;
            return null;
        }
        int declaredType() {
            return opcode == DEFINE && ((AcsObject)source).mmp.containsKey("declared-type")
                    ? ((AcsObject)source).getAsInt("declared-type") : ANY_VALUE;
        }
        boolean scalarConstant() { return opcode == CONSTANT && (type == INT_VALUE || type == BOOLEAN_VALUE); }
        boolean invalidatesSlots() {
            return effects.contains(Effect.CALL) || effects.contains(Effect.WRITE_HEAP) || effects.contains(Effect.CLEANUP);
        }
    }
    enum Flow { NEXT, TRUE, FALSE, BACK, BREAK, CONTINUE, RETURN, EXCEPTION }
    record Successor(int target, Flow flow) {}
    record BasicBlock(int id, Node region, List<Successor> successors) {}
    record Function(Node body, List<BasicBlock> blocks, int entry, int exit) {}

    static Function analyze(AcsElement body, Map<AcsElement,int[]> positions) {
        Node root = read(body, positions);
        Graph graph = new Graph();
        int exit = graph.add(null, List.of());
        int entry = graph.region(root, exit, exit, exit, exit, exit);
        return new Function(root, List.copyOf(graph.blocks), entry, exit);
    }

    static Node read(AcsElement source, Map<AcsElement,int[]> positions) {
        int opcode = source instanceof AcsArray ? BLOCK : source instanceof AcsObject object
                ? object.getAsInt("t") == 1 ? CALL : object.getAsInt("c") : CONSTANT;
        List<Edge> children = new ArrayList<>();
        for (ExecShape.Child child : ExecShape.children(source))
            if (child.role() != ExecShape.Role.CONTEXT && child.role() != ExecShape.Role.METHOD_ENTRY)
                children.add(new Edge(child, read(child.value(), positions)));
        EnumSet<Effect> own = ownEffects(opcode);
        EnumSet<Effect> effects = own.clone();
        for (Edge edge : children) effects.addAll(edge.value.effects);
        Integer slot = switch (opcode) {
            case VARIABLE, DEFINE, SET, MOVE_OUT -> ((AcsObject) source).getAsInt("v");
            default -> null;
        };
        int[] location = positions == null ? null : positions.get(source);
        List<Integer> position = location == null ? List.of() : Arrays.stream(location).boxed().toList();
        return new Node(source, opcode, type(source, opcode, children), slot, List.copyOf(children),
                Collections.unmodifiableSet(own), Collections.unmodifiableSet(effects), position);
    }

    private static EnumSet<Effect> ownEffects(int opcode) {
        return switch (opcode) {
            case CONSTANT -> EnumSet.noneOf(Effect.class);
            case VARIABLE -> EnumSet.of(Effect.READ_SLOT, Effect.THROW);
            case DEFINE, SET -> EnumSet.of(Effect.WRITE_SLOT, Effect.ALLOCATE, Effect.CLEANUP, Effect.CALL, Effect.THROW);
            case MOVE_OUT, DROP -> EnumSet.of(Effect.READ_SLOT, Effect.WRITE_SLOT, Effect.CLEANUP, Effect.THROW);
            case CALL, CALL_VIRTUAL, CONTEXT_DEFAULT, BUFFER_NEW, BUFFER_OP, RETURN_TYPED,
                 CHECK_TYPE, VALUE_COMPARE, OBJECT_DELETE, OBJECT_BIND -> EnumSet.allOf(Effect.class);
            case MOVE -> EnumSet.of(Effect.WRITE_SLOT, Effect.WRITE_HEAP, Effect.CLEANUP, Effect.CALL, Effect.THROW);
            case NEW_BLOCK -> EnumSet.of(Effect.ALLOCATE, Effect.WRITE_HEAP, Effect.THROW);
            case OBJECT_ADDRESS, BLOCK_ADDRESS, CHECK_OBJECT -> EnumSet.of(Effect.READ_HEAP, Effect.THROW);
            case SET_METHODS -> EnumSet.of(Effect.WRITE_HEAP, Effect.THROW);
            case BLOCK, CLEANUP -> EnumSet.of(Effect.CONTROL, Effect.CLEANUP, Effect.CALL, Effect.THROW);
            case SEQ -> EnumSet.noneOf(Effect.class);
            case RETURN_OBJECT -> EnumSet.of(Effect.CONTROL, Effect.CLEANUP, Effect.WRITE_HEAP, Effect.THROW);
            case RETURN, IF, WHILE, BREAK, CONTINUE, SELECT, AND, OR -> EnumSet.of(Effect.CONTROL, Effect.THROW);
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, GREATER, LESS, EQUAL, NOT_EQUAL,
                 GREATER_EQUAL, LESS_EQUAL, NOT, NEGATE, CONTEXT_ABI, BIT_AND, BIT_OR, BIT_XOR,
                 SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED, CONVERT, WRAP_ADD, WRAP_SUBTRACT, WRAP_MULTIPLY -> EnumSet.of(Effect.THROW);
            default -> throw new IllegalArgumentException("Unclassified exec opcode " + opcode);
        };
    }

    private static int type(AcsElement source, int opcode, List<Edge> children) {
        if (source instanceof AcsIntegerElement) return INT_VALUE;
        if (source instanceof AcsBooleanElement) return BOOLEAN_VALUE;
        if (source instanceof AcsStringElement) return STRING_VALUE;
        if (source instanceof AcsFloat) return FLOAT_VALUE;
        if (source instanceof AcsDouble) return DOUBLE_VALUE;
        if (source instanceof AcsAddress) return ADDRESS_VALUE;
        if (source instanceof AcsByteArray) return VOID_VALUE;
        return switch (opcode) {
            case GREATER, LESS, EQUAL, NOT_EQUAL, GREATER_EQUAL, LESS_EQUAL, NOT, AND, OR -> BOOLEAN_VALUE;
            case CONTEXT_ABI, VALUE_COMPARE, BIT_AND, BIT_OR, BIT_XOR, SHIFT_LEFT, SHIFT_RIGHT, SHIFT_UNSIGNED -> INT_VALUE;
            case OBJECT_ADDRESS, BLOCK_ADDRESS -> ADDRESS_VALUE;
            case NEW_BLOCK, BUFFER_NEW -> OBJECT_VALUE;
            case CONVERT -> ((AcsObject) source).getAsInt("type");
            case DEFINE, BLOCK, RETURN, RETURN_OBJECT, RETURN_TYPED, BREAK, CONTINUE -> VOID_VALUE;
            case SEQ -> children.get(children.size() - 1).value.type;
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO, NEGATE, WRAP_ADD, WRAP_SUBTRACT, WRAP_MULTIPLY -> {
                int first = children.isEmpty() ? ANY_VALUE : children.get(0).value.type;
                yield (first == INT_VALUE || first == FLOAT_VALUE || first == DOUBLE_VALUE || (opcode == ADD && first == STRING_VALUE))
                        && children.stream().allMatch(edge -> edge.value.type == first) ? first : ANY_VALUE;
            }
            case SELECT -> children.get(1).value.type == children.get(2).value.type ? children.get(1).value.type : ANY_VALUE;
            default -> ANY_VALUE;
        };
    }

    /** Structured region CFG. Lazy expressions and explicit cleanup are opaque
     * regions with transitive effects, not flattened into unconditional work.
     * Exceptional edges conservatively leave the region; its cleanup is part of
     * the region contract. Optimizations must not move work across such regions. */
    private static final class Graph {
        final List<BasicBlock> blocks = new ArrayList<>();
        int add(Node node, List<Successor> edges) {
            int id = blocks.size(); blocks.add(new BasicBlock(id, node, List.copyOf(edges))); return id;
        }
        int region(Node node, int next, int breakTo, int continueTo, int returned, int exit) {
            if (node.opcode == BLOCK) {
                int tail = add(node, List.of(new Successor(next, Flow.NEXT), new Successor(exit, Flow.EXCEPTION)));
                int onBreak = add(node, List.of(new Successor(breakTo, Flow.BREAK), new Successor(exit, Flow.EXCEPTION)));
                int onContinue = add(node, List.of(new Successor(continueTo, Flow.CONTINUE), new Successor(exit, Flow.EXCEPTION)));
                int onReturn = add(node, List.of(new Successor(returned, Flow.RETURN), new Successor(exit, Flow.EXCEPTION)));
                int onError = add(node, List.of(new Successor(exit, Flow.EXCEPTION)));
                for (int i = node.children.size() - 1; i >= 0; --i)
                    tail = region(node.children.get(i).value, tail, onBreak, onContinue, onReturn, onError);
                // Scope cleanup is observable, including an empty lexical block.
                return tail;
            }
            if (node.opcode == IF) {
                int yes = region(node.child("val"), next, breakTo, continueTo, returned, exit);
                int no = node.child("else") == null ? next : region(node.child("else"), next, breakTo, continueTo, returned, exit);
                return add(node.child("v"), List.of(new Successor(yes, Flow.TRUE), new Successor(no, Flow.FALSE), new Successor(exit, Flow.EXCEPTION)));
            }
            if (node.opcode == WHILE) {
                int condition = add(node.child("v"), List.of());
                int back = add(null, List.of(new Successor(condition, Flow.BACK)));
                int body = region(node.child("val"), back, next, condition, returned, exit);
                blocks.set(condition, new BasicBlock(condition, node.child("v"), List.of(
                        new Successor(body, Flow.TRUE), new Successor(next, Flow.FALSE), new Successor(exit, Flow.EXCEPTION))));
                return condition;
            }
            Flow flow = switch (node.opcode) {
                case BREAK -> Flow.BREAK;
                case CONTINUE -> Flow.CONTINUE;
                case RETURN, RETURN_OBJECT, RETURN_TYPED -> Flow.RETURN;
                default -> Flow.NEXT;
            };
            int target = switch (flow) { case BREAK -> breakTo; case CONTINUE -> continueTo; case RETURN -> returned; default -> next; };
            List<Successor> edges = new ArrayList<>(); edges.add(new Successor(target, flow));
            if (node.effects.contains(Effect.THROW)) edges.add(new Successor(exit, Flow.EXCEPTION));
            // Opaque cleanup/lazy regions may carry a return or jump from an
            // operand. Preserve all possible exits instead of assuming fallthrough.
            if (flow == Flow.NEXT && node.effects.contains(Effect.CONTROL)) {
                edges.add(new Successor(returned, Flow.RETURN));
                edges.add(new Successor(breakTo, Flow.BREAK));
                edges.add(new Successor(continueTo, Flow.CONTINUE));
            }
            return add(node, edges);
        }
    }
}
