package azertia.script;

import com.google.gson.JsonElement;
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
    private record Pending(JsonElement element, int depth, boolean leaving) {}

    public static void validate(JsonElement root) {
        Deque<Pending> pending = new ArrayDeque<>();
        Set<JsonElement> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.push(new Pending(root, 0, false));
        while (!pending.isEmpty()) {
            Pending item = pending.pop(); JsonElement element = item.element();
            if (element == null || element.isJsonNull() || element.isJsonPrimitive()) continue;
            if (item.leaving()) { ancestors.remove(element); continue; }
            if (item.depth() >= MAX_NESTING) throw new IllegalArgumentException("AST nesting exceeds " + MAX_NESTING + " levels");
            if (!ancestors.add(element)) throw new IllegalArgumentException("AST contains a cycle");
            pending.push(new Pending(element, item.depth(), true));
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) pending.push(new Pending(child, item.depth() + 1, false));
            } else {
                for (var child : element.getAsJsonObject().entrySet()) pending.push(new Pending(child.getValue(), item.depth() + 1, false));
            }
        }
    }

    public static void validateJsonText(String source) {
        int depth = 0; boolean quoted = false, escaped = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '[' || c == '{') {
                if (++depth > MAX_NESTING) throw new IllegalArgumentException("JSON nesting exceeds " + MAX_NESTING + " levels");
            } else if (c == ']' || c == '}') depth--;
        }
    }
}
