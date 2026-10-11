package azertia.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.*;

/**
 * Checks the shape of a readable AST before compilation: the presence and JSON
 * types of the fields the compiler reads, positions, the {@code sources} table and
 * numeric literals. Violations are reported with JSON-pointer paths, e.g.
 * {@code Malformed script JSON at /body/fff/_funcfff0000/metadata/position: expected an integer, got "abc"},
 * so a hand-written or edited AST fails with a clear message instead of an
 * exception inside the compiler. Unknown fields are ignored, and the meaning of
 * well-typed values (names, types, operations) is left to the compiler.
 *
 * <p>The walk is iterative; it runs after {@link TreeLimits#validate} has bounded
 * the nesting and excluded cycles.
 */
final class AstShape {
    /** The preprocessor's include depth limit; a stored include chain is no longer. */
    static final int MAX_INCLUDE_CHAIN = TreeLimits.MAX_INCLUDE_DEPTH;
    static final int MAX_SOURCE_NAME = TreeLimits.MAX_SOURCE_LABEL_CHARS;
    /** The main source and one unit per include expansion. */
    static final int MAX_SOURCES = TreeLimits.MAX_INCLUDES + 1;
    private static final String[] POSITIVE_POSITIONS = {"_line", "_column", "_end_line", "_end_column"};

    /**
     * A JSON pointer: a member name or an array index below a parent. Most nodes are
     * valid, so the pointer is spelled out only when a violation is reported at it.
     */
    private static final class Pointer {
        static final Pointer ROOT = new Pointer(null, null, 0);
        private final Pointer parent;
        private final String key;
        private final int index;
        private Pointer(Pointer parent, String key, int index) { this.parent = parent; this.key = key; this.index = index; }
        Pointer key(String name) { return new Pointer(this, name, 0); }
        Pointer index(int position) { return new Pointer(this, null, position); }
        @Override public String toString() {
            Deque<Pointer> chain = new ArrayDeque<>();
            for (Pointer pointer = this; pointer.parent != null; pointer = pointer.parent) chain.push(pointer);
            StringBuilder text = new StringBuilder();
            for (Pointer pointer : chain) text.append('/').append(pointer.key != null ? escape(pointer.key) : String.valueOf(pointer.index));
            return text.toString();
        }
    }
    private record Pending(JsonElement node, Pointer path) {}
    private final List<String> errors = new ArrayList<>();
    private final Deque<Pending> nodes = new ArrayDeque<>();
    private int units = 1;

    private AstShape() {}

    /** Every shape violation of an AST, in document order; empty when the shape is valid. */
    static List<String> validate(JsonObject root) {
        AstShape shape = new AstShape(); shape.root(root); return shape.errors;
    }

    private void root(JsonObject root) {
        Pointer top = Pointer.ROOT;
        if (root.has("sources")) sources(root.get("sources"), top.key("sources"));
        JsonObject metadata = object(root, "metadata", top, true);
        if (metadata != null) {
            Pointer meta = top.key("metadata");
            string(metadata, "author", meta, false);
            integer(metadata, "version", meta, false);
            integer(metadata, "function-namespace", meta, false);
        }
        string(root, "namespace-hint", top, false);
        JsonArray assumptions = array(root, "assume-hints", top, false);
        if (assumptions != null) for (int i = 0; i < assumptions.size(); i++) {
            Pointer path = top.key("assume-hints").index(i);
            JsonObject assumption = element(assumptions.get(i), path);
            if (assumption == null) continue;
            string(assumption, "hint", path, true);
            integer(assumption, "namespace", path, true);
        }
        strings(object(root, "ext", top, false), top.key("ext"));
        strings(object(root, "abstract", top, false), top.key("abstract"));
        JsonObject externs = object(root, "extern-signatures", top, false);
        if (externs != null) for (var entry : externs.entrySet()) {
            Pointer path = top.key("extern-signatures").key(entry.getKey());
            JsonObject signature = element(entry.getValue(), path);
            if (signature == null) continue;
            positions(signature, path);
            string(signature, "id", path, false);
            string(signature, "return-type", path, true);
            stringArray(signature, "param-types", path, true);
            declarationKinds(signature, path);
        }
        JsonArray globals = array(root, "global-variable", top, false);
        if (globals != null) for (int i = 0; i < globals.size(); i++) stringElement(globals.get(i), top.key("global-variable").index(i));
        JsonObject body = object(root, "body", top, true);
        if (body != null) for (var namespace : body.entrySet()) {
            Pointer namespacePath = top.key("body").key(namespace.getKey());
            JsonObject functions = element(namespace.getValue(), namespacePath);
            if (functions != null) for (var entry : functions.entrySet()) function(entry.getValue(), namespacePath.key(entry.getKey()));
        }
        JsonArray classes = array(root, "classes", top, false);
        if (classes != null) for (int i = 0; i < classes.size(); i++) definition(classes.get(i), top.key("classes").index(i));
    }

    private void function(JsonElement value, Pointer path) {
        JsonObject function = element(value, path);
        if (function == null) return;
        JsonObject metadata = object(function, "metadata", path, true);
        if (metadata != null) {
            Pointer meta = path.key("metadata");
            positions(metadata, meta);
            string(metadata, "name", meta, false);
            integer(metadata, "position", meta, true);
            string(metadata, "return-type", meta, true);
            stringArray(metadata, "param", meta, true);
            stringArray(metadata, "param-types", meta, false);
            declarationKinds(metadata, meta);
            string(metadata, "base-initializer", meta, false);
            JsonArray baseArguments = array(metadata, "base-args", meta, false);
            if (baseArguments != null) node(baseArguments, meta.key("base-args"));
        }
        if (!function.has("script")) missing(path.key("script"));
        else node(function.get("script"), path.key("script"));
    }
    /** Fields shared by function metadata and extern signatures. */
    private void declarationKinds(JsonObject declaration, Pointer path) {
        string(declaration, "owner-class", path, false);
        string(declaration, "function-kind", path, false);
        string(declaration, "operator-kind", path, false);
        typeParameters(declaration, path);
    }
    private void typeParameters(JsonObject declaration, Pointer path) {
        JsonArray parameters = array(declaration, "type-parameters", path, false);
        if (parameters == null) return;
        for (int i = 0; i < parameters.size(); i++) {
            Pointer parameterPath = path.key("type-parameters").index(i);
            JsonObject parameter = element(parameters.get(i), parameterPath);
            if (parameter == null) continue;
            string(parameter, "name", parameterPath, true);
            string(parameter, "bound", parameterPath, false);
        }
    }
    private void definition(JsonElement value, Pointer path) {
        JsonObject definition = element(value, path);
        if (definition == null) return;
        positions(definition, path);
        string(definition, "name", path, true);
        string(definition, "constructor", path, true);
        string(definition, "destructor", path, false);
        string(definition, "base", path, false);
        typeParameters(definition, path);
        JsonObject methods = object(definition, "methods", path, true);
        strings(methods, path.key("methods"));
        strings(object(definition, "operators", path, false), path.key("operators"));
        JsonArray fields = array(definition, "fields", path, true);
        if (fields != null) for (int i = 0; i < fields.size(); i++) {
            Pointer fieldPath = path.key("fields").index(i);
            JsonObject field = element(fields.get(i), fieldPath);
            if (field == null) continue;
            positions(field, fieldPath);
            string(field, "name", fieldPath, true);
            string(field, "type", fieldPath, true);
            if (field.has("initializer")) node(field.get("initializer"), fieldPath.key("initializer"));
        }
    }

    /** Checks an expression, statement or block and everything below it, without recursion. */
    private void node(JsonElement value, Pointer path) { nodes.push(new Pending(value, path)); drainNodes(); }
    private void drainNodes() {
        while (!nodes.isEmpty()) {
            Pending pending = nodes.pop(); JsonElement value = pending.node(); Pointer path = pending.path();
            // Null is reported by the compiler as a value error at its context.
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) { number(value.getAsJsonPrimitive(), path); continue; }
            List<Pending> children = new ArrayList<>();
            if (value.isJsonArray()) {
                JsonArray block = value.getAsJsonArray();
                for (int i = 0; i < block.size(); i++) children.add(new Pending(block.get(i), path.index(i)));
            } else {
                JsonObject node = value.getAsJsonObject();
                positions(node, path);
                string(node, "t", path, true);
                String call = string(node, "call", path, true);
                string(node, "declared-type", path, false);
                stringArray(node, "type-args", path, false);
                literalTrue(node, "compound", path);
                literalTrue(node, "_parenthesized", path);
                JsonArray params = array(node, "param", path, false);
                // A missing param list is an empty one.
                Pointer paramPath = path.key("param");
                if (call != null && (params != null || !node.has("param"))) operands(call, params != null ? params : new JsonArray(), paramPath);
                // A name operand is a string, never an expression node.
                Set<Integer> names = call == null ? Set.of() : NAME_OPERANDS.getOrDefault(call, Set.of());
                if (params != null) for (int i = 0; i < params.size(); i++) {
                    if (names.contains(i)) stringElement(params.get(i), paramPath.index(i));
                    else children.add(new Pending(params.get(i), paramPath.index(i)));
                }
            }
            // Keep document order: the first child is checked first.
            for (int i = children.size() - 1; i >= 0; i--) nodes.push(children.get(i));
        }
    }
    /** Operand positions that hold variable, member or class names. */
    private static final Map<String,Set<Integer>> NAME_OPERANDS = Map.of(
            "var", Set.of(0), "vardef", Set.of(0), "varset", Set.of(0), "object-def", Set.of(0, 1),
            "member", Set.of(1), "member-call", Set.of(1));
    /**
     * Operands the compiler reads before it checks an operation's arity or types. A
     * receiver may be any expression, e.g. the literal in {@code "abc".x}, which the
     * compiler rejects by type; only the field target of a member assignment is an object.
     */
    private void operands(String call, JsonArray params, Pointer path) {
        switch (call) {
            case "member-set" -> {
                if (params.size() > 0 && !params.get(0).isJsonObject()) error(path.index(0), "expected an object, got " + describe(params.get(0)));
            }
            case "member-call" -> {
                if (params.size() < 2) error(path, call + " needs a receiver and a member name");
            }
            case "object-new", "object-value", "buffer-new" -> {
                if (params.isEmpty()) error(path, call + " needs a type name");
                else stringElement(params.get(0), path.index(0));
            }
            default -> {}
        }
    }

    private void sources(JsonElement value, Pointer path) {
        if (!value.isJsonArray()) { error(path, "expected an array, got " + describe(value)); return; }
        JsonArray entries = value.getAsJsonArray();
        if (entries.isEmpty()) { error(path, "expected at least the main source"); return; }
        units = entries.size();
        if (units > MAX_SOURCES) { error(path, "expected at most " + MAX_SOURCES + " sources, got " + units); return; }
        int[] depth = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Pointer entryPath = path.index(i);
            JsonObject entry = element(entries.get(i), entryPath);
            if (entry == null) continue;
            String name = string(entry, "name", entryPath, true);
            String problem = name == null ? null : sourceNameProblem(name);
            if (problem != null) error(entryPath.key("name"), problem);
            if (!entry.has("included-from")) continue;
            Pointer fromPath = entryPath.key("included-from");
            if (i == 0) { error(fromPath, "the main source cannot be included"); continue; }
            JsonObject from = object(entry, "included-from", entryPath, true);
            if (from == null) continue;
            Integer parent = integer(from, "source", fromPath, true);
            positive(from, "line", fromPath, true);
            positive(from, "column", fromPath, true);
            if (parent == null) continue;
            // A parent always precedes its unit, which keeps include chains acyclic.
            if (parent < 0 || parent >= i) { error(fromPath.key("source"), "expected an earlier source index, got " + parent); continue; }
            depth[i] = depth[parent] + 1;
            if (depth[i] > MAX_INCLUDE_CHAIN) error(fromPath, "the include chain exceeds " + MAX_INCLUDE_CHAIN + " levels");
        }
    }
    /**
     * Why a source name cannot be stored, or null. Names are printed verbatim in
     * diagnostics, so they may not contain control characters, line separators or
     * bidirectional formatting characters that hide or reorder text.
     */
    static String sourceNameProblem(String name) {
        if (name.isBlank()) return "a source name cannot be empty";
        if (name.length() > MAX_SOURCE_NAME) return "a source name cannot exceed " + MAX_SOURCE_NAME + " characters";
        if (!GeneraterJson.wellFormedUtf16(name)) return "a source name cannot contain an unpaired surrogate";
        for (int i = 0; i < name.length(); i++)
            if (unsafe(name.charAt(i))) return "a source name cannot contain control or bidirectional formatting characters";
        return null;
    }
    /** A control, line separator or bidirectional formatting character, which could forge or reorder a diagnostic line. */
    private static boolean unsafe(char c) {
        return Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || c == 0x061c || c == 0x200e || c == 0x200f
                || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069;
    }
    /** Input text for a message: unsafe characters and unpaired surrogates become backslash-u hexadecimal escapes. */
    static String printable(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean pair = Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1));
            if (pair) { out.append(c).append(text.charAt(++i)); continue; }
            if (unsafe(c) || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        return out.toString();
    }

    private void positions(JsonObject object, Pointer path) {
        for (String key : POSITIVE_POSITIONS) positive(object, key, path, false);
        Integer file = integer(object, "_file", path, false);
        if (file != null && (file < 0 || file >= units))
            error(path.key("_file"), "source index " + file + " is outside the sources table (" + units + (units == 1 ? " entry)" : " entries)"));
    }
    private void number(JsonPrimitive value, Pointer path) {
        if (!value.isNumber()) return;
        // Integers, finite floats and BigDecimals always fit; NaN, an infinity and text numbers
        // whose exponent or length exceeds Gson's limits fail the conversion below.
        Number stored = value.getAsNumber();
        if (stored instanceof Integer || stored instanceof Long || stored instanceof BigDecimal) return;
        if ((stored instanceof Float || stored instanceof Double) && Double.isFinite(stored.doubleValue())) return;
        try { value.getAsBigDecimal(); }
        catch (NumberFormatException e) { error(path, "number is outside the supported range: " + printable(abbreviate(value.getAsString()))); }
    }

    private JsonObject element(JsonElement value, Pointer path) {
        if (value != null && value.isJsonObject()) return value.getAsJsonObject();
        error(path, "expected an object, got " + describe(value)); return null;
    }
    private void stringElement(JsonElement value, Pointer path) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            error(path, "expected a string, got " + describe(value));
    }
    private JsonObject object(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = field(parent, key, path, required);
        return value == null ? null : element(value, path.key(key));
    }
    private JsonArray array(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = field(parent, key, path, required);
        if (value == null) return null;
        if (value.isJsonArray()) return value.getAsJsonArray();
        error(path.key(key), "expected an array, got " + describe(value)); return null;
    }
    private String string(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = field(parent, key, path, required);
        if (value == null) return null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return value.getAsString();
        error(path.key(key), "expected a string, got " + describe(value)); return null;
    }
    private void stringArray(JsonObject parent, String key, Pointer path, boolean required) {
        JsonArray values = array(parent, key, path, required);
        if (values != null) for (int i = 0; i < values.size(); i++) stringElement(values.get(i), path.key(key).index(i));
    }
    /** Every member of an object is a string, e.g. ext, abstract and method mappings. */
    private void strings(JsonObject object, Pointer path) {
        if (object != null) for (var entry : object.entrySet()) stringElement(entry.getValue(), path.key(entry.getKey()));
    }
    private Integer integer(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = field(parent, key, path, required);
        if (value == null) return null;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            Integer exact = SourceMap.exactInt(value.getAsJsonPrimitive());
            if (exact != null) return exact;
        }
        error(path.key(key), "expected an integer, got " + describe(value)); return null;
    }
    private void positive(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = field(parent, key, path, required);
        if (value == null) return;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            Integer exact = SourceMap.exactInt(value.getAsJsonPrimitive());
            if (exact != null && exact > 0) return;
        }
        error(path.key(key), "expected a positive integer, got " + describe(value));
    }
    /** Marker fields are either absent or literally true. */
    private void literalTrue(JsonObject parent, String key, Pointer path) {
        JsonElement value = parent.get(key);
        if (value != null && !(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()))
            error(path.key(key), "expected true, got " + describe(value));
    }
    private JsonElement field(JsonObject parent, String key, Pointer path, boolean required) {
        JsonElement value = parent.get(key);
        if (value == null && required) missing(path.key(key));
        return value;
    }

    private void missing(Pointer path) { error(path, "required field is missing"); }
    private void error(Pointer path, String message) { errors.add("Malformed script JSON at " + path + ": " + message); }
    /** One JSON-pointer reference token, made printable. */
    private static String escape(String key) { return printable(key.replace("~", "~0").replace("/", "~1")); }
    private static String describe(JsonElement value) {
        if (value == null || value.isJsonNull()) return "null";
        if (value.isJsonObject()) return "an object";
        if (value.isJsonArray()) return "an array";
        return printable(abbreviate(value.toString()));
    }
    private static String abbreviate(String text) { return text.length() <= 40 ? text : text.substring(0, 37) + "..."; }
}
