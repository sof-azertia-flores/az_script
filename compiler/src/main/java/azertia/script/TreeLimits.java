package azertia.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;

/** Bounds user supplied syntax before recursive parsing, symbol resolution or serialization. */
public final class TreeLimits {
    /**
     * Guard for parser recursion and raw JSON container depth. It only protects the
     * host from hostile input; the language limit is the ABD nesting checked while
     * lowering, which the readable JSON reaches at roughly twice the depth.
     */
    public static final int MAX_NESTING = 320;
    /** Maximum container nesting of an ABD document, shared with AcsCodec and the C++ reader. */
    public static final int MAX_ABD_NESTING = 128;
    /** ABD levels above a function body: the root stack, function-list stack and function record. */
    public static final int ABD_LEVELS_ABOVE_FUNCTION_BODY = 3;
    private TreeLimits() {}

    /**
     * A nesting or cycle limit was exceeded. For an AST it carries the innermost
     * positioned statement (an element of a block, such as a statement, class field
     * or base-initializer argument) and the innermost positioned ancestor of the
     * offending container; for JSON text, the line and column.
     */
    public static final class LimitExceeded extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final transient JsonObject statement, node;
        private final int line, column;
        LimitExceeded(String message, JsonObject statement, JsonObject node) {
            super(message); this.statement = statement; this.node = node; line = 0; column = 0;
        }
        LimitExceeded(String message, int line, int column) {
            super(message); statement = null; node = null; this.line = line; this.column = column;
        }
        /** Innermost positioned statement, else the outermost positioned ancestor, or null. */
        public JsonObject statement() { return statement; }
        /** Innermost positioned ancestor, or null. */
        public JsonObject node() { return node; }
        /** The node to report at: the statement when known. */
        public JsonObject anchor() { return statement != null ? statement : node; }
        /** 1-based position in JSON text, or 0. */
        public int line() { return line; }
        public int column() { return column; }
    }
    /** How an element is reached: an object member, the {@code param} member, or an element of either kind of array. */
    private enum Role { MEMBER, PARAMETERS, STATEMENT, ARGUMENT }
    private record Pending(JsonElement element, int depth, boolean leaving, Role role, JsonObject statement, JsonObject inner) {}

    public static void validate(JsonElement root) {
        Deque<Pending> pending = new ArrayDeque<>();
        Set<JsonElement> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(new Pending(root, 0, false, Role.MEMBER, null, null));
        while (!pending.isEmpty()) {
            Pending item = pending.pop(); JsonElement element = item.element();
            if (element == null || element.isJsonNull() || element.isJsonPrimitive()) continue;
            if (item.leaving()) { ancestors.remove(element); continue; }
            JsonObject statement = item.statement(), inner = item.inner();
            if (positioned(element)) {
                inner = element.getAsJsonObject();
                // Elements of blocks and declaration lists are statements; expression operands are not.
                if (statement == null || item.role() == Role.STATEMENT) statement = inner;
            }
            if (item.depth() >= MAX_NESTING) throw new LimitExceeded("AST nesting exceeds " + MAX_NESTING + " levels", statement, inner);
            if (!ancestors.add(element)) throw new LimitExceeded("AST contains a cycle", statement, inner);
            pending.push(new Pending(element, item.depth(), true, item.role(), null, null));
            if (element.isJsonArray()) {
                Role role = item.role() == Role.PARAMETERS ? Role.ARGUMENT : Role.STATEMENT;
                for (JsonElement child : element.getAsJsonArray()) pending.push(new Pending(child, item.depth() + 1, false, role, statement, inner));
            } else {
                for (var child : element.getAsJsonObject().entrySet())
                    pending.push(new Pending(child.getValue(), item.depth() + 1, false,
                            child.getKey().equals("param") ? Role.PARAMETERS : Role.MEMBER, statement, inner));
            }
        }
    }
    /** Lenient: a malformed {@code _line} only means no position, never a different error. */
    private static boolean positioned(JsonElement element) {
        return element.isJsonObject() && SourceMap.integer(element.getAsJsonObject().get("_line")) > 0;
    }

    public static void validateJsonText(String source) {
        int depth = 0, line = 1, lineStart = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '\n') { line++; lineStart = i + 1; }
            else if (c == '[' || c == '{') {
                if (++depth > MAX_NESTING)
                    throw new LimitExceeded("JSON nesting exceeds " + MAX_NESTING + " levels", line, i - lineStart + 1);
            } else if (c == ']' || c == '}') depth--;
        }
    }
}
