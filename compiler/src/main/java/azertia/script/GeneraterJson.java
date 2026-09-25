package azertia.script;

import com.google.gson.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Token based AzScript front end. The historical public class name is retained. */
public class GeneraterJson {
    private static final int DEFAULT_FUNCTION_NAMESPACE = 0xfff;
    private static final int RESERVED_RUNTIME_NAMESPACE = 0xabd;
    private static final int MAX_MACRO_EXPANSION_CHARS = 1_048_576;
    private static final Set<String> TYPES = Set.of("int", "float", "double", "string", "boolean", "bool", "void");
    /** Statement keywords; they can never name a function, parameter, variable, global or macro. */
    private static final Set<String> KEYWORDS = Set.of("return", "if", "else", "while", "break", "def", "var", "class", "new", "delete", "this", "null", "extern");
    private static final Set<String> PREPROCESSOR_DIRECTIVES = Set.of(
            "ifdef", "ifndef", "if_equals", "else", "fi", "endif", "include", "author", "gvar",
            "namespace", "namespace_hint", "assume_hint", "setmeta", "setattr", "define", "undef");
    public static final Map<String, Integer> specialNames = Map.of(
            "__script_onload", 0, "main", 0x0fff0000, "__script_pre_destroy", 1);

    private static void hintIdentifier(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid hint name: " + name);
    }
    public interface AzExpression { JsonElement generate(); }
    public static class AzFunction {
        public List<String> params = new ArrayList<>();
        public List<String> paramTypes = new ArrayList<>();
        public AzExpression body;
        public String returnType, name;
        public String ownerClass, functionKind;
        public int id;
        public Integer explicitPosition;
    }
    public static class ExternSignature {
        public String returnType;
        public List<String> paramTypes = new ArrayList<>();
        public String ownerClass, functionKind;
        public int id;

        ExternSignature(String returnType, List<String> paramTypes) {
            this.returnType = returnType;
            this.paramTypes.addAll(paramTypes);
        }
    }
    public static class AzScript {
        public Map<String,Integer> name2Id = new LinkedHashMap<>();
        public Map<String,String> tmpoVars = new LinkedHashMap<>();
        public Map<String,String> exts = new LinkedHashMap<>();
        public String author = "";
        public int functionNamespace = DEFAULT_FUNCTION_NAMESPACE;
        public String namespaceHint = "";
        public Map<String,Integer> assumeHints = new LinkedHashMap<>();
        private boolean namespaceDeclared;
        public List<String> globalVariables = new ArrayList<>();
        public List<AzFunction> functions = new ArrayList<>();
        public JsonArray classes = new JsonArray();
        public Map<String,ExternSignature> externSignatures = new LinkedHashMap<>();
        private final Map<String,String> macros = new LinkedHashMap<>();
        private String sourceName = "<source>";

        public String toPureSource(String src) throws IOException {
            return preprocess(src, Path.of(".").toAbsolutePath(), new LinkedHashSet<>(), 0);
        }
        public void execute(String src) throws IOException {
            reset();
            parse(preprocess(src, Path.of(".").toAbsolutePath(), new LinkedHashSet<>(), 0));
        }
        public void execute(Path source) throws IOException {
            reset();
            Path real = source.toRealPath();
            sourceName = real.toString();
            Set<Path> includes = new LinkedHashSet<>();
            includes.add(real);
            parse(preprocess(Files.readString(real, StandardCharsets.UTF_8), real.getParent(), includes, 0));
        }
        private void reset() {
            name2Id.clear(); exts.clear(); globalVariables.clear(); functions.clear();
            externSignatures.clear(); tmpoVars.clear(); macros.clear();
            classes = new JsonArray();
            author = ""; functionNamespace = DEFAULT_FUNCTION_NAMESPACE;
            namespaceHint = ""; namespaceDeclared = false; assumeHints.clear();
            sourceName = "<source>";
        }
        private void parse(String source) { new Parser(source, this).program(); }
        private record Conditional(boolean parent, boolean condition, boolean inElse) {
            boolean active() { return parent && (inElse ? !condition : condition); }
        }
        private String preprocess(String src, Path base, Set<Path> includes, int depth) throws IOException {
            if (depth > 64) throw new IllegalArgumentException("Include nesting exceeds 64 levels");
            // A UTF-8 byte order mark must not hide a directive on the first line.
            if (src.startsWith("﻿")) src = src.substring(1);
            StringBuilder out = new StringBuilder();
            Deque<Conditional> stack = new ArrayDeque<>();
            boolean blockComment = false;
            int lineNo = 0;
            for (String line : src.split("\\R", -1)) {
                lineNo++;
                // Comments remain lexical even inside an excluded conditional branch.
                Expansion cleaned = stripComments(line, blockComment);
                blockComment = cleaned.blockComment();
                String content = cleaned.text();
                String trimmed = content.stripLeading();
                boolean active = stack.isEmpty() || stack.peek().active();
                if (trimmed.startsWith("#")) {
                    String[] parts = trimmed.substring(1).trim().split("\\s+", 2);
                    String command = parts[0].toLowerCase(Locale.ROOT);
                    String arg = parts.length > 1 ? parts[1].trim() : "";
                    try {
                        if (!PREPROCESSOR_DIRECTIVES.contains(command))
                            throw new IllegalArgumentException("Unknown preprocessor directive: #" + command);
                        switch (command) {
                            case "ifdef", "ifndef", "if_equals" -> {
                                String[] args = arg.split("\\s+", 2);
                                if (args[0].isBlank() || (command.equals("if_equals") && args.length != 2))
                                    throw new IllegalArgumentException("Missing condition argument");
                                boolean defined = tmpoVars.containsKey(args[0]) || macros.containsKey(args[0]);
                                boolean condition = command.equals("ifndef") ? !defined : defined;
                                if (command.equals("if_equals")) condition = defined && args[1].equals(
                                        tmpoVars.getOrDefault(args[0], macros.get(args[0])));
                                stack.push(new Conditional(active, condition, false));
                            }
                            case "else" -> {
                                if (stack.isEmpty() || stack.peek().inElse())
                                    throw new IllegalArgumentException("Unmatched or repeated #else");
                                Conditional previous = stack.pop();
                                stack.push(new Conditional(previous.parent(), previous.condition(), true));
                            }
                            case "fi", "endif" -> {
                                if (stack.isEmpty()) throw new IllegalArgumentException("Unmatched #" + command);
                                stack.pop();
                            }
                            default -> {
                                if (!active) break;
                                switch (command) {
                                    case "include" -> {
                                        String path = arg;
                                        if (path.startsWith("\"") && path.endsWith("\"") && path.length() >= 2)
                                            path = path.substring(1, path.length() - 1);
                                        if (path.isBlank()) throw new IllegalArgumentException("Missing include path");
                                        Path included = base.resolve(path).toRealPath();
                                        if (!includes.add(included)) throw new IllegalArgumentException("Include cycle: " + included);
                                        try {
                                            out.append(preprocess(Files.readString(included, StandardCharsets.UTF_8),
                                                    included.getParent(), includes, depth + 1));
                                        } finally { includes.remove(included); }
                                    }
                                    case "author" -> author = arg;
                                    case "gvar" -> {
                                        identifier(arg);
                                        if (globalVariables.contains(arg)) throw new IllegalArgumentException("Duplicate global variable: " + arg);
                                        globalVariables.add(arg);
                                    }
                                    case "namespace" -> {
                                        if (namespaceDeclared) throw new IllegalArgumentException("Only one #namespace or #namespace_hint is allowed");
                                        String[] args = arg.split("\\s+");
                                        if (args.length != 1 || args[0].isBlank())
                                            throw new IllegalArgumentException("Expected one hexadecimal namespace");
                                        int value = hexadecimal(args[0], 4, "namespace");
                                        if (value == RESERVED_RUNTIME_NAMESPACE)
                                            throw new IllegalArgumentException("Namespace 0xabd is reserved for the runtime");
                                        functionNamespace = value; namespaceDeclared = true;
                                    }
                                    case "namespace_hint" -> {
                                        if (namespaceDeclared) throw new IllegalArgumentException("Only one #namespace or #namespace_hint is allowed");
                                        hintIdentifier(arg); namespaceHint = arg; functionNamespace = 0; namespaceDeclared = true;
                                    }
                                    case "assume_hint" -> {
                                        String[] args = arg.split("\\s+");
                                        if (args.length != 2) throw new IllegalArgumentException("Expected #assume_hint HINT namespace");
                                        hintIdentifier(args[0]); int value = hexadecimal(args[1], 4, "assumed namespace");
                                        if (value == 0 || value == RESERVED_RUNTIME_NAMESPACE || value == DEFAULT_FUNCTION_NAMESPACE)
                                            throw new IllegalArgumentException("Reserved assumed namespace");
                                        Integer previous = assumeHints.get(args[0]);
                                        if (previous != null && previous != value || previous == null && assumeHints.containsValue(value))
                                            throw new IllegalArgumentException("Conflicting hint or assumed namespace");
                                        assumeHints.put(args[0], value);
                                    }
                                    case "setmeta", "setattr", "define" -> {
                                        String[] args = arg.split("\\s+", 2);
                                        if (args[0].isBlank() || (args.length != 2 && !command.equals("define")))
                                            throw new IllegalArgumentException("Expected two arguments for #" + command);
                                        String value = args.length > 1 ? args[1] : "1";
                                        switch (command) {
                                            case "setmeta" -> exts.put(args[0], value);
                                            case "setattr" -> tmpoVars.put(args[0], value);
                                            case "define" -> { identifier(args[0]); macros.put(args[0], value); }
                                        }
                                    }
                                    case "undef" -> { identifier(arg); macros.remove(arg); tmpoVars.remove(arg); }
                                    default -> throw new IllegalArgumentException(
                                            "Unknown preprocessor directive: #" + command);
                                }
                            }
                        }
                    } catch (IllegalArgumentException | IOException e) {
                        throw new IllegalArgumentException("Preprocessor " + base + ":" + lineNo + ": " + e.getMessage(), e);
                    }
                    out.append('\n');
                } else {
                    if (active) {
                        Expansion expanded = expand(content, false, new HashSet<>(), 0);
                        out.append(expanded.text());
                    }
                    out.append('\n');
                }
            }
            if (blockComment) throw new IllegalArgumentException("Unterminated block comment in " + base + ":" + lineNo);
            if (!stack.isEmpty()) throw new IllegalArgumentException("Unclosed preprocessor condition in " + base);
            return out.toString();
        }
        private record Expansion(String text, boolean blockComment) {}
        private Expansion stripComments(String line, boolean block) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (block) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) { out.append(" ".repeat(line.length() - i)); break; }
                    out.append(" ".repeat(end + 2 - i)); i = end + 2; block = false; continue;
                }
                if (line.startsWith("//", i)) { out.append(" ".repeat(line.length() - i)); break; }
                if (line.startsWith("/*", i)) { out.append("  "); i += 2; block = true; continue; }
                char c = line.charAt(i++); out.append(c);
                if (c == '\'' || c == '"') {
                    char quote = c;
                    while (i < line.length()) {
                        c = line.charAt(i++); out.append(c);
                        if (c == '\\' && i < line.length()) out.append(line.charAt(i++));
                        else if (c == quote) break;
                    }
                }
            }
            return new Expansion(out.toString(), block);
        }
        private Expansion expand(String line, boolean block, Set<String> expanding, int depth) {
            if (depth > 64) throw new IllegalArgumentException("Macro nesting exceeds 64 levels");
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (block) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) { out.append(line.substring(i)); break; }
                    out.append(line, i, end + 2); i = end + 2; block = false; continue;
                }
                char c = line.charAt(i);
                if (line.startsWith("//", i)) { out.append(line.substring(i)); break; }
                if (line.startsWith("/*", i)) { out.append("/*"); i += 2; block = true; continue; }
                if (c == '\'' || c == '"') {
                    char quote = c; out.append(c); i++;
                    while (i < line.length()) {
                        c = line.charAt(i++); out.append(c);
                        if (c == '\\' && i < line.length()) out.append(line.charAt(i++));
                        else if (c == quote) break;
                    }
                } else if (Character.isDigit(c) || (c == '.' && i + 1 < line.length() && Character.isDigit(line.charAt(i + 1)))) {
                    int start = i;
                    while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                    if (i < line.length() && line.charAt(i) == '.') {
                        i++;
                        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                    }
                    if (i < line.length() && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
                        int exponent = i + 1;
                        if (exponent < line.length() && (line.charAt(exponent) == '+' || line.charAt(exponent) == '-')) exponent++;
                        if (exponent < line.length() && Character.isDigit(line.charAt(exponent))) {
                            i = exponent + 1;
                            while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                        }
                    }
                    // Like a C pp-number, identifier characters glued to a literal belong to it:
                    // 5N, 0x10 or 2e must reach the lexer's suffix error, not become 55, 07 or 29.
                    while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                    appendLimited(out, line.substring(start, i));
                } else if (Character.isJavaIdentifierStart(c)) {
                    int start = i++;
                    while (i < line.length() && Character.isJavaIdentifierPart(line.charAt(i))) i++;
                    String word = line.substring(start, i);
                    if (macros.containsKey(word)) {
                        if (!expanding.add(word)) throw new IllegalArgumentException("Recursive macro: " + word);
                        appendLimited(out, expand(macros.get(word), false, expanding, depth + 1).text());
                        expanding.remove(word);
                    } else appendLimited(out, word);
                } else { appendLimited(out, String.valueOf(c)); i++; }
            }
            if (out.length() > MAX_MACRO_EXPANSION_CHARS)
                throw new IllegalArgumentException("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
            return new Expansion(out.toString(), block);
        }
        private static void appendLimited(StringBuilder out, String value) {
            if (value.length() > MAX_MACRO_EXPANSION_CHARS - out.length())
                throw new IllegalArgumentException("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
            out.append(value);
        }
        public JsonObject toObj() {
            JsonObject result = new JsonObject(), metadata = new JsonObject(), extensions = new JsonObject();
            metadata.addProperty("author", author); metadata.addProperty("version", 2);
            metadata.addProperty("function-namespace", functionNamespace);
            result.add("metadata", metadata);
            result.addProperty("namespace-hint", namespaceHint);
            JsonArray assumptions = new JsonArray();
            assumeHints.forEach((hint, namespace) -> {
                JsonObject value = new JsonObject(); value.addProperty("hint", hint); value.addProperty("namespace", namespace); assumptions.add(value);
            });
            result.add("assume-hints", assumptions);
            exts.forEach(extensions::addProperty); result.add("ext", extensions);
            JsonObject abstracts = new JsonObject();
            name2Id.forEach((name, id) -> abstracts.addProperty(name, Integer.toHexString(id)));
            result.add("abstract", abstracts);
            JsonObject signatures = new JsonObject();
            externSignatures.forEach((name, signature) -> {
                JsonObject value = new JsonObject();
                value.addProperty("return-type", signature.returnType);
                value.addProperty("id", Integer.toHexString(signature.id));
                if (signature.ownerClass != null) {
                    value.addProperty("owner-class", signature.ownerClass); value.addProperty("function-kind", signature.functionKind);
                }
                JsonArray parameterTypes = new JsonArray();
                signature.paramTypes.forEach(parameterTypes::add);
                value.add("param-types", parameterTypes);
                signatures.add(name, value);
            });
            result.add("extern-signatures", signatures);
            JsonArray globals = new JsonArray(); globalVariables.forEach(globals::add);
            result.add("global-variable", globals);
            JsonObject body = new JsonObject();
            for (AzFunction function : functions) {
                String namespace = Integer.toHexString(function.id >>> 16);
                if (!body.has(namespace)) body.add(namespace, new JsonObject());
                JsonObject entry = new JsonObject(), meta = new JsonObject();
                meta.addProperty("name", function.name);
                meta.addProperty("position", function.id & 0xffff);
                meta.addProperty("return-type", function.returnType);
                if (function.ownerClass != null) {
                    meta.addProperty("owner-class", function.ownerClass);
                    meta.addProperty("function-kind", function.functionKind);
                }
                JsonArray params = new JsonArray(); function.params.forEach(params::add);
                JsonArray paramTypes = new JsonArray(); function.paramTypes.forEach(paramTypes::add);
                meta.add("param", params); meta.add("param-types", paramTypes);
                entry.add("metadata", meta); entry.add("script", function.body.generate());
                body.getAsJsonObject(namespace).add("_func" + Integer.toHexString(function.id), entry);
            }
            result.add("body", body);
            if (!classes.isEmpty()) result.add("classes", classes.deepCopy());
            return result;
        }
    }
    private static void identifier(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0)))
            throw new IllegalArgumentException("Invalid identifier: " + value);
        for (int i = 1; i < value.length(); i++) if (!Character.isJavaIdentifierPart(value.charAt(i)))
            throw new IllegalArgumentException("Invalid identifier: " + value);
        if (KEYWORDS.contains(value) || TYPES.contains(value) || value.equals("true") || value.equals("false"))
            throw new IllegalArgumentException("'" + value + "' is a reserved word and cannot be used as a name");
    }
    private static String normalizeType(String value, boolean allowVoid, String description) {
        if (!TYPES.contains(value)) throw new IllegalArgumentException("Unknown " + description + ": " + value);
        String normalized = value.equals("bool") ? "boolean" : value;
        if (!allowVoid && normalized.equals("void"))
            throw new IllegalArgumentException("void is not a valid " + description);
        return normalized;
    }
    /** True unless the text contains a surrogate that is not part of a high/low pair. */
    static boolean wellFormedUtf16(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) i++;
            else if (Character.isSurrogate(c)) return false;
        }
        return true;
    }
    private static int hexadecimal(String text, int digits, String description) {
        String value = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
        if (!value.matches("[0-9a-fA-F]+"))
            throw new IllegalArgumentException("Invalid " + description + ": " + text);
        value = value.replaceFirst("^0+(?=.)", "");
        if (value.length() > digits) throw new IllegalArgumentException("Invalid " + description + ": " + text);
        return (int) Long.parseLong(value, 16);
    }
    private record Token(String text, JsonElement literal, int line, int column, boolean identifier) {}
    private static final class Lexer {
        private final String source;
        private int offset, line = 1, column = 1;
        Lexer(String source) { this.source = source; }
        private char peek(int delta) { return offset + delta < source.length() ? source.charAt(offset + delta) : '\0'; }
        private char take() { char c = source.charAt(offset++); if (c == '\n') { line++; column = 1; } else column++; return c; }
        private IllegalArgumentException error(String message, int l, int c) {
            return new IllegalArgumentException("line " + l + ", column " + c + ": " + message);
        }
        List<Token> scan() {
            List<Token> tokens = new ArrayList<>();
            while (offset < source.length()) {
                char c = peek(0);
                if (Character.isWhitespace(c) || c == '\ufeff') { take(); continue; }
                int l = line, col = column, start = offset;
                if (c == '/' && peek(1) == '/') { while (offset < source.length() && peek(0) != '\n') take(); continue; }
                if (c == '/' && peek(1) == '*') {
                    take(); take();
                    while (offset < source.length() && !(peek(0) == '*' && peek(1) == '/')) take();
                    if (offset == source.length()) throw error("Unterminated block comment", l, col);
                    take(); take(); continue;
                }
                if (c == '\'' || c == '"') {
                    char quote = take(); StringBuilder value = new StringBuilder(); boolean closed = false;
                    while (offset < source.length()) {
                        c = take();
                        if (c == quote) { closed = true; break; }
                        if (c == '\n' || c == '\r') throw error("Newline in string literal", l, col);
                        if (c == '\\') {
                            if (offset == source.length()) throw error("Unterminated string escape", l, col);
                            c = take();
                            switch (c) {
                                case 'n' -> value.append('\n'); case 'r' -> value.append('\r');
                                case 't' -> value.append('\t'); case 'b' -> value.append('\b');
                                case 'f' -> value.append('\f'); case '0' -> value.append('\0');
                                case '\\', '\'', '"' -> value.append(c);
                                case 'u' -> {
                                    int code = 0;
                                    for (int j = 0; j < 4; j++) {
                                        if (offset == source.length()) throw error("Incomplete Unicode escape", l, col);
                                        int digit = Character.digit(take(), 16);
                                        if (digit < 0) throw error("Invalid Unicode escape", l, col);
                                        code = code * 16 + digit;
                                    }
                                    value.append((char) code);
                                }
                                default -> throw error("Unknown string escape: \\" + c, l, col);
                            }
                        } else value.append(c);
                    }
                    if (!closed) throw error("Unterminated string literal", l, col);
                    // UTF-8 cannot encode a lone surrogate; Java would silently write '?' instead.
                    if (!wellFormedUtf16(value))
                        throw error("Unpaired surrogate in string literal; write a supplementary character as a \\uD8xx\\uDCxx pair", l, col);
                    tokens.add(new Token(source.substring(start, offset), new JsonPrimitive(value.toString()), l, col, false));
                } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                    while (Character.isDigit(peek(0))) take();
                    if (peek(0) == '.') { take(); while (Character.isDigit(peek(0))) take(); }
                    if (peek(0) == 'e' || peek(0) == 'E') {
                        take(); if (peek(0) == '+' || peek(0) == '-') take();
                        if (!Character.isDigit(peek(0))) throw error("Invalid numeric exponent", l, col);
                        while (Character.isDigit(peek(0))) take();
                    }
                    boolean floatSuffix = peek(0) == 'f' || peek(0) == 'F';
                    if (floatSuffix) take();
                    if (Character.isJavaIdentifierPart(peek(0)) && peek(0) != '\0')
                        throw error("Invalid numeric suffix", l, col);
                    String text = source.substring(start, offset - (floatSuffix ? 1 : 0));
                    BigDecimal decimal = new BigDecimal(text);
                    JsonPrimitive literal;
                    if (floatSuffix) {
                        float value = decimal.floatValue();
                        if (!Float.isFinite(value) || (value == 0 && decimal.signum() != 0))
                            throw error("Floating literal is outside float range", l, col);
                        JsonObject object = operation("ctrl", "float", new Token(text, null, l, col, false), new JsonPrimitive(value));
                        tokens.add(new Token(source.substring(start, offset), object, l, col, false));
                        continue;
                    } else if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                        double value = decimal.doubleValue();
                        if (!Double.isFinite(value) || (value == 0 && decimal.signum() != 0))
                            throw error("Floating literal is outside double range", l, col);
                        // Keep lexical floating kind even for 5., 5e0 and zero; BigDecimal normalizes these away.
                        literal = new JsonPrimitive(value);
                    } else literal = new JsonPrimitive(decimal);
                    tokens.add(new Token(text, literal, l, col, false));
                } else if (Character.isJavaIdentifierStart(c)) {
                    take(); while (Character.isJavaIdentifierPart(peek(0)) && peek(0) != '\0') take();
                    String text = source.substring(start, offset);
                    JsonElement literal = text.equals("true") || text.equals("false") ? new JsonPrimitive(Boolean.parseBoolean(text)) : null;
                    tokens.add(new Token(text, literal, l, col, true));
                } else {
                    String two = "" + c + peek(1);
                    if (Set.of("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "::").contains(two)) {
                        take(); take(); tokens.add(new Token(two, null, l, col, false));
                    } else if (c == ':') {
                        take(); tokens.add(new Token(":", null, l, col, false));
                        while (Character.isWhitespace(peek(0))) take();
                        int hexLine = line, hexColumn = column, hexStart = offset;
                        while (Character.isLetterOrDigit(peek(0))) take();
                        if (hexStart == offset) throw error("Expected hexadecimal function ID", hexLine, hexColumn);
                        tokens.add(new Token(source.substring(hexStart, offset), null, hexLine, hexColumn, false));
                    } else if ("{}(),;+-*/%=<>!.~".indexOf(c) >= 0) {
                        take(); tokens.add(new Token(String.valueOf(c), null, l, col, false));
                    } else throw error("Unexpected character: " + c, l, col);
                }
            }
            tokens.add(new Token("<eof>", null, line, column, false)); return tokens;
        }
    }
    private static JsonObject operation(String kind, String name, Token at, JsonElement... params) {
        JsonObject object = new JsonObject(); object.addProperty("t", kind); object.addProperty("call", name);
        JsonArray array = new JsonArray(); for (JsonElement param : params) array.add(param);
        object.add("param", array);
        object.addProperty("_line", at.line()); object.addProperty("_column", at.column());
        return object;
    }
    private static final class Parser {
        private final List<Token> tokens;
        private final AzScript script;
        private int index;
        private int recursionDepth;
        private int loopDepth;
        private int nextFunctionPosition = 1;
        private final Set<String> classNames = new LinkedHashSet<>();
        Parser(String source, AzScript script) {
            this.tokens = new Lexer(source).scan(); this.script = script;
            for (JsonElement item : script.classes) classNames.add(item.getAsJsonObject().get("name").getAsString());
            for (int i = 0; i + 1 < tokens.size(); i++)
                if (tokens.get(i).text().equals("class") && tokens.get(i + 1).identifier()) classNames.add(tokens.get(i + 1).text());
        }
        private boolean knownType(String name) { return TYPES.contains(name) || classNames.contains(name); }
        private String sourceType(String value, boolean allowVoid) {
            if (classNames.contains(value)) return value;
            try { return normalizeType(value, allowVoid, "type"); }
            catch (IllegalArgumentException e) { throw error(e.getMessage(), tokens.get(index - 1)); }
        }
        private void enterRecursion() {
            if (recursionDepth >= TreeLimits.MAX_NESTING) throw error("Syntax nesting exceeds " + TreeLimits.MAX_NESTING + " levels");
            recursionDepth++;
        }
        private Token current() { return tokens.get(index); }
        private boolean at(String text) { return current().text().equals(text); }
        private Token advance() { return tokens.get(index++); }
        private boolean match(String text) { if (!at(text)) return false; advance(); return true; }
        private Token expect(String text) { if (!at(text)) throw error("Expected '" + text + "', found '" + current().text() + "'"); return advance(); }
        private Token name() {
            if (!current().identifier() || current().literal() != null) throw error("Expected identifier");
            if (KEYWORDS.contains(current().text()))
                throw error("'" + current().text() + "' is a reserved word and cannot be used as a name");
            return advance();
        }
        /** A name being declared: keywords and type names are both rejected. */
        private Token declaredName() {
            if (current().identifier() && knownType(current().text()))
                throw error("'" + current().text() + "' is a type name and cannot be declared as a name");
            return name();
        }
        private IllegalArgumentException error(String message) { return error(message, current()); }
        private IllegalArgumentException error(String message, Token at) {
            return new IllegalArgumentException(script.sourceName + ":" + at.line() + ":" + at.column() + ": " + message);
        }
        private final Map<String,AzFunction> definitions = new LinkedHashMap<>();
        private record DuplicateExtern(String name, ExternSignature first, ExternSignature second) {}
        private final List<DuplicateExtern> duplicateExterns = new ArrayList<>();
        void program() {
            if (script.assumeHints.containsValue(script.functionNamespace))
                throw error("Assumed namespace overlaps the module namespace");
            while (!at("<eof>")) {
                if (match(";")) continue;
                if (match("class")) { classDeclaration(); continue; }
                boolean external = match("extern");
                String first = name().text();
                if (classNames.contains(first) && match("::")) {
                    boolean destructor = match("~"); String member = name().text();
                    if (!member.equals(first)) throw error("A method needs an explicit return type");
                    parseFunction(first, destructor ? "destructor" : "constructor", "void",
                            destructor ? "<dtor>" : "<ctor>", external, false);
                } else {
                    String type = sourceType(first, true), name = declaredNameOrOwner();
                    if (match("::")) {
                        if (!classNames.contains(name)) throw error("Unknown owner class: " + name);
                        String member = declaredName().text();
                        parseFunction(name, "method", type, member, external, false);
                    } else parseFunction(null, null, type, name, external, false);
                }
            }
            validateDuplicateExterns(); assignFunctionIds();
            for (AzFunction function : script.functions) TreeLimits.validate(function.body.generate());
        }
        private String declaredNameOrOwner() {
            if (classNames.contains(current().text()) && tokens.get(index + 1).text().equals("::")) return advance().text();
            return declaredName().text();
        }
        private void validateDuplicateExterns() {
            Map<String,JsonArray> layouts = new HashMap<>();
            for (JsonElement item : script.classes) {
                JsonObject definition = item.getAsJsonObject();layouts.put(definition.get("name").getAsString(), definition.getAsJsonArray("fields"));
            }
            for (DuplicateExtern duplicate : duplicateExterns) {
                ExternSignature first = duplicate.first(), second = duplicate.second();
                if (first.paramTypes.size() != second.paramTypes.size() || !sameSourceType(first.returnType, second.returnType, layouts))
                    throw error("Conflicting extern declaration: " + duplicate.name());
                for (int i = 0; i < first.paramTypes.size(); i++)
                    if (!sameSourceType(first.paramTypes.get(i), second.paramTypes.get(i), layouts))
                        throw error("Conflicting extern declaration: " + duplicate.name());
            }
        }
        private boolean sameSourceType(String first, String second, Map<String,JsonArray> layouts) {
            ArrayDeque<List<String>> pending = new ArrayDeque<>();Set<List<String>> compared = new HashSet<>();pending.add(List.of(first, second));
            while (!pending.isEmpty()) {
                List<String> pair = pending.removeLast();if (pair.get(0).equals(pair.get(1))) continue;
                JsonArray left = layouts.get(pair.get(0)), right = layouts.get(pair.get(1));
                if (left == null || right == null || left.size() != right.size()) return false;
                if (!compared.add(pair)) continue;
                for (int i = 0; i < left.size(); i++)
                    pending.add(List.of(left.get(i).getAsJsonObject().get("type").getAsString(), right.get(i).getAsJsonObject().get("type").getAsString()));
            }
            return true;
        }
        private void assignFunctionIds() {
            Set<Integer> used = new HashSet<>(), selfReserved = new HashSet<>();
            for (ExternSignature imported : script.externSignatures.values()) {
                boolean self = script.namespaceHint.isEmpty() ? (imported.id >>> 16) == script.functionNamespace
                        : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16);
                if (self) selfReserved.add((script.functionNamespace << 16) | (imported.id & 0xffff));
            }
            for (AzFunction function : script.functions) {
                Integer fixed = function.ownerClass == null ? specialNames.get(function.name) : null;
                if (!script.namespaceHint.isEmpty() && function.name.equals("main")) fixed = null;
                ExternSignature imported = script.externSignatures.get(function.name);
                if (fixed != null) {
                    if (function.explicitPosition != null && function.explicitPosition != (fixed & 0xffff))
                        throw error("Explicit ID disagrees with lifecycle function " + function.name);
                    function.id = fixed;
                } else {
                    Integer position = function.explicitPosition;
                    boolean selfImport = imported != null && (script.namespaceHint.isEmpty()
                            ? (imported.id >>> 16) == script.functionNamespace
                            : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16));
                    if (selfImport) {
                        if (position != null && position != (imported.id & 0xffff))
                            throw error("Definition ID disagrees with extern declaration: " + function.name);
                        position = imported.id & 0xffff;
                    }
                    if (position == null) continue;
                    function.id = (script.functionNamespace << 16) | position;
                    if (function.id == 0 || function.id == 1 || function.id == 0x0fff0000)
                        throw error("Explicit function ID is reserved: " + function.name);
                    if (selfReserved.contains(function.id) && !selfImport)
                        throw error("Explicit function ID is reserved by a self extern declaration: " + function.name);
                }
                if (!used.add(function.id)) throw error("Duplicate function ID: " + Integer.toHexString(function.id));
                function.explicitPosition = function.id & 0xffff;
            }
            for (ExternSignature imported : script.externSignatures.values()) {
                boolean selfImport = script.namespaceHint.isEmpty() ? (imported.id >>> 16) == script.functionNamespace
                        : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16);
                if (selfImport) used.add((script.functionNamespace << 16) | (imported.id & 0xffff));
            }
            for (AzFunction function : script.functions) {
                if (function.explicitPosition == null) {
                    do {
                        if (nextFunctionPosition > 0xffff) throw error("Namespace has no free function IDs");
                        function.id = (script.functionNamespace << 16) | nextFunctionPosition++;
                    } while (used.contains(function.id) || specialNames.containsValue(function.id)
                            || script.externSignatures.values().stream().anyMatch(ext -> ext.id == function.id));
                    used.add(function.id);
                }
                if ((function.id == 0 || function.id == 1) && (!function.returnType.equals("void") || !function.params.isEmpty()))
                    throw error(function.name + " must be declared as void with no parameters");
                script.name2Id.put(function.name, function.id);
            }
            script.externSignatures.forEach((name, signature) -> script.name2Id.put(name, signature.id));
        }
        private void classDeclaration() {
            Token classToken = name(); String className = classToken.text();
            try { identifier(className); } catch (IllegalArgumentException e) { throw error(e.getMessage(), classToken); }
            if (TYPES.contains(className) || Compiler.isBuiltinName(className) || definitions.containsKey(className)
                    || script.externSignatures.containsKey(className) || script.globalVariables.contains(className))
                throw error("Duplicate or reserved class name: " + className);
            for (JsonElement item : script.classes)
                if (item.getAsJsonObject().get("name").getAsString().equals(className)) throw error("Duplicate class: " + className);
            JsonObject definition = new JsonObject(); definition.addProperty("name", className);
            JsonArray fields = new JsonArray(); JsonObject methods = new JsonObject();
            definition.add("fields", fields); definition.add("methods", methods); script.classes.add(definition);
            Set<String> members = new HashSet<>(); boolean constructor = false, destructor = false;
            expect("{");
            while (!at("}")) {
                if (at("<eof>")) throw error("Unterminated class " + className);
                if (match(";")) continue;
                boolean external = match("extern");
                if (match("~")) {
                    if (destructor) throw error("Duplicate destructor for " + className);
                    expect(className);parseFunction(className,"destructor","void","<dtor>",external,true);
                    definition.addProperty("destructor",className+"::<dtor>");destructor=true;
                } else if (at(className) && tokens.get(index + 1).text().equals("(")) {
                    if (constructor) throw error("Constructor overloading is not supported for " + className);
                    advance();parseFunction(className,"constructor","void","<ctor>",external,true);
                    definition.addProperty("constructor",className+"::<ctor>");constructor=true;
                } else {
                    Token start=current();String type=sourceType(name().text(),true);String member=declaredName().text();
                    if (!members.add(member)) throw error("Duplicate class member: " + className + "." + member);
                    if (at("(")) {
                        parseFunction(className,"method",type,member,external,true);methods.addProperty(member,className+"::"+member);
                    } else {
                        if (external) throw error("Fields cannot be extern");
                        if (type.equals("void")) throw error("Field cannot have type void");
                        JsonObject field=new JsonObject();field.addProperty("name",member);field.addProperty("type",type);
                        field.addProperty("_line",start.line());field.addProperty("_column",start.column());
                        if(match("="))field.add("initializer",expression());endStatement();fields.add(field);
                    }
                }
            }
            expect("}");match(";");
            if(fields.isEmpty())throw error("Class "+className+" must declare at least one field");
            if(!constructor) {
                AzFunction function=new AzFunction();function.ownerClass=className;function.functionKind="constructor";
                function.name=className+"::<ctor>";function.returnType="void";function.params.add("this");function.paramTypes.add(className);
                function.body=JsonArray::new;addDefinition(function);definition.addProperty("constructor",function.name);
            }
        }
        private void addDefinition(AzFunction function) {
            if(definitions.putIfAbsent(function.name,function)!=null)throw error("Duplicate function definition: "+function.name);
            script.functions.add(function);
        }
        private void parseFunction(String owner,String kind,String type,String member,boolean external,boolean inClass) {
            String qualified=owner==null?member:owner+"::"+member;
            if(Compiler.isBuiltinName(qualified)||owner==null&&classNames.contains(qualified))throw error("Reserved function name: "+qualified);
            AzFunction function=new AzFunction();function.name=qualified;function.ownerClass=owner;function.functionKind=kind;function.returnType=type;
            if(owner!=null){function.params.add("this");function.paramTypes.add(owner);}
            expect("(");
            if(!at(")"))do {
                String parameter=name().text(),parameterType="any";
                if(parameter.equals("void"))throw error("void cannot be a parameter type");
                if(knownType(parameter)) {
                    parameterType=sourceType(parameter,false);
                    parameter=external&&(at(",")||at(")"))?"<argument:"+function.params.size()+">":declaredName().text();
                } else if(external)throw error("External parameters require explicit types");
                if(function.params.contains(parameter))throw error("Duplicate parameter: "+parameter);
                function.params.add(parameter);function.paramTypes.add(parameterType);
            }while(match(","));
            expect(")");
            Integer suffix=null;
            if(match(":")){Token token=advance();try{suffix=hexadecimal(token.text(),external?8:4,"function ID");}catch(IllegalArgumentException e){throw error(e.getMessage(),token);}}
            if("destructor".equals(kind)&&function.params.size()!=1)throw error("Destructor must have no parameters");
            if(external) {
                if(suffix==null)throw error("Extern function requires a full hexadecimal ID");
                if((suffix>>>16)==RESERVED_RUNTIME_NAMESPACE||specialNames.containsValue(suffix))throw error("Reserved external function ID");
                expect(";");ExternSignature signature=new ExternSignature(type,function.paramTypes);signature.id=suffix;
                signature.ownerClass=owner;signature.functionKind=kind;
                ExternSignature previous=script.externSignatures.putIfAbsent(qualified,signature);
                if(previous!=null) {
                    if(previous.id!=signature.id || !Objects.equals(previous.ownerClass,owner) || !Objects.equals(previous.functionKind,kind))
                        throw error("Conflicting extern declaration: "+qualified);
                    duplicateExterns.add(new DuplicateExtern(qualified,previous,signature));
                }
            } else {
                if(inClass&&!script.namespaceHint.isEmpty())throw error("Hinted library class functions must use extern declarations and out-of-class definitions");
                function.explicitPosition=suffix;int enclosingLoop=loopDepth;loopDepth=0;
                JsonElement body;try{body=block();}finally{loopDepth=enclosingLoop;}function.body=()->body;addDefinition(function);match(";");
            }
        }
        JsonElement block() {
            enterRecursion();
            try { return blockBody(); } finally { recursionDepth--; }
        }
        private JsonElement blockBody() {
            expect("{"); JsonArray body = new JsonArray();
            while (!at("}")) {
                if (at("<eof>")) throw error("Unterminated block");
                if (!match(";")) body.add(statement());
            }
            expect("}"); return body;
        }
        private void endStatement() {
            if (!match(";") && !at("}") && !at("<eof>")) throw error("Expected ';' after statement");
        }
        private JsonElement statement() {
            enterRecursion();
            try { return statementBody(); } finally { recursionDepth--; }
        }
        private JsonElement statementBody() {
            if (at("{")) return block();
            Token start = current();
            if (match("return")) {
                JsonElement result;
                if (at(";") || at("}")) result = operation("ctrl", "return", start);
                else if (at("(") && tokens.get(index + 1).text().equals(")")) {
                    advance(); advance(); result = operation("ctrl", "return", start);
                } else result = operation("ctrl", "return", start, expression());
                endStatement(); return result;
            }
            if (match("break")) {
                if (loopDepth == 0) throw error("'break' can only be used inside a while loop");
                JsonElement result = operation("ctrl", "break", start);
                endStatement(); return result;
            }
            if (match("delete")) {
                JsonElement result = operation("ctrl", "object-delete", start, expression());
                endStatement(); return result;
            }
            if (at("if") || at("while")) {
                String keyword = advance().text(); expect("("); JsonElement condition = expression();
                JsonElement body, otherwise = null;
                boolean loop = keyword.equals("while");
                if (loop) loopDepth++;
                try {
                    if (match(",")) {
                        body = at("{") ? block() : expression();
                        if (keyword.equals("if") && match(",")) otherwise = at("{") ? block() : expression();
                        expect(")");
                        // The original functional spelling remains accepted.
                        endStatement();
                    } else {
                        expect(")"); body = statement();
                        if (keyword.equals("if") && match("else")) otherwise = statement();
                        match(";");
                    }
                } finally { if (loop) loopDepth--; }
                return otherwise == null ? operation("ctrl", keyword, start, condition, body)
                        : operation("ctrl", keyword, start, condition, body, otherwise);
            }
            if (match("def")) {
                expect("("); String variable = declaredName().text();
                JsonElement initializer = match(",") ? expression() : null;
                expect(")"); endStatement();
                return initializer == null ? operation("ctrl", "vardef", start, new JsonPrimitive(variable))
                        : operation("ctrl", "vardef", start, new JsonPrimitive(variable), initializer);
            }
            if (at("var") || (knownType(current().text()) && !at("void"))) {
                String spelling = advance().text();
                String declaredType = spelling.equals("var") ? null : sourceType(spelling, false);
                String variable = declaredName().text();
                if (classNames.contains(spelling) && !at("=")) {
                    List<JsonElement> arguments = new ArrayList<>();
                    arguments.add(new JsonPrimitive(variable)); arguments.add(new JsonPrimitive(spelling));
                    if (match("(")) {
                        if (!at(")")) do { arguments.add(expression()); } while (match(","));
                        expect(")");
                    }
                    endStatement(); return operation("ctrl", "object-def", start, arguments.toArray(JsonElement[]::new));
                }
                JsonElement initializer = match("=") ? expression() : null;
                endStatement();
                JsonObject definition = initializer == null
                        ? operation("ctrl", "vardef", start, new JsonPrimitive(variable))
                        : operation("ctrl", "vardef", start, new JsonPrimitive(variable), initializer);
                if (declaredType != null) definition.addProperty("declared-type", declaredType);
                return definition;
            }
            JsonElement expression = expression(); endStatement(); return expression;
        }
        JsonElement expression() { return assignment(); }
        private JsonElement assignment() {
            enterRecursion();
            try { return assignmentBody(); } finally { recursionDepth--; }
        }
        private JsonElement assignmentBody() {
            JsonElement left = binary(0);
            if (Set.of("=", "+=", "-=", "*=", "/=", "%=").contains(current().text())) {
                Token operator = advance();
                JsonObject target = left.isJsonObject() ? left.getAsJsonObject() : null;
                boolean variable = target != null && target.get("t").getAsString().equals("ctrl")
                        && target.get("call").getAsString().equals("var");
                boolean memory = target != null && target.get("t").getAsString().equals("call")
                        && isMemoryGet(target.get("call").getAsString());
                boolean member = target != null && target.get("t").getAsString().equals("ctrl")
                        && target.get("call").getAsString().equals("member");
                if (!variable && !memory && !member)
                    throw error("Left side of assignment must be a variable or mem_get(pointer)");
                if (member && !operator.text().equals("=")) throw error("Compound assignment to a member is not supported");
                if (memory && !operator.text().equals("="))
                    throw error("Compound assignment to mem_get(pointer) is not supported; save the pointer in a variable and use '='");
                JsonElement value = assignment();
                if (member) return operation("ctrl", "member-set", operator, left, value);
                if (memory) return operation("ctrl", "mov", operator, left, value);
                if (!operator.text().equals("=")) value = operation("ctrl", binaryName(operator.text().substring(0, 1)), operator, left, value);
                JsonObject assignment = operation("ctrl", "varset", operator, target.getAsJsonArray("param").get(0), value);
                if (!operator.text().equals("=")) assignment.addProperty("compound", true);
                return assignment;
            }
            return left;
        }
        private static boolean isMemoryGet(String call) {
            return call.equals("mem_get");
        }
        private static final List<Set<String>> PRECEDENCE = List.of(Set.of("||"), Set.of("&&"), Set.of("==", "!="),
                Set.of("<", ">", "<=", ">="), Set.of("+", "-"), Set.of("*", "/", "%"));
        private JsonElement binary(int level) {
            if (level == PRECEDENCE.size()) return unary();
            JsonElement left = binary(level + 1);
            while (PRECEDENCE.get(level).contains(current().text())) {
                Token operator = advance(); left = operation("ctrl", binaryName(operator.text()), operator, left, binary(level + 1));
            }
            return left;
        }
        private static String binaryName(String text) {
            return switch (text) {
                case "+" -> "add"; case "-" -> "minus"; case "*" -> "multiply"; case "/" -> "divide";
                case "%" -> "mod"; case "==" -> "cmp"; case "!=" -> "ne"; case ">" -> "greater";
                case "<" -> "lower"; case ">=" -> "ge"; case "<=" -> "le"; case "&&" -> "and"; case "||" -> "or";
                default -> throw new IllegalArgumentException("Unknown binary operator: " + text);
            };
        }
        private JsonElement unary() {
            enterRecursion();
            try { return unaryBody(); } finally { recursionDepth--; }
        }
        private JsonElement unaryBody() {
            if (at("+") || at("-") || at("!")) {
                Token operator = advance(); JsonElement value = unary();
                if (operator.text().equals("+")) return operation("ctrl", "pos", operator, value);
                if (operator.text().equals("-") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    if (value.getAsNumber() instanceof Double) return new JsonPrimitive(-value.getAsDouble());
                    return new JsonPrimitive(value.getAsBigDecimal().negate());
                }
                return operation("ctrl", operator.text().equals("-") ? "neg" : "not", operator, value);
            }
            return postfix();
        }
        private JsonElement postfix() {
            JsonElement value = primary();
            while (match(".")) {
                Token member = name();
                if (match("(")) {
                    List<JsonElement> args = new ArrayList<>(); args.add(value); args.add(new JsonPrimitive(member.text()));
                    if (!at(")")) do { args.add(expression()); } while (match(","));
                    expect(")"); value = operation("ctrl", "member-call", member, args.toArray(JsonElement[]::new));
                } else value = operation("ctrl", "member", member, value, new JsonPrimitive(member.text()));
            }
            return value;
        }
        private JsonElement primary() {
            Token token = current();
            if (match("null")) return operation("ctrl", "null", token);
            if (match("this")) return operation("ctrl", "var", token, new JsonPrimitive("this"));
            if (match("new")) {
                String type = name().text();
                if (!classNames.contains(type)) throw error("Unknown class: " + type);
                expect("("); List<JsonElement> args = new ArrayList<>(); args.add(new JsonPrimitive(type));
                if (!at(")")) do { args.add(expression()); } while (match(","));
                expect(")"); return operation("ctrl", "object-new", token, args.toArray(JsonElement[]::new));
            }
            if (token.literal() != null) { advance(); return token.literal(); }
            if (match("(")) { JsonElement value = expression(); expect(")"); return value; }
            if (at("{")) return block();
            if (token.identifier()) {
                String name = advance().text();
                if (KEYWORDS.contains(name))
                    throw error("'" + name + "' is a statement and cannot be used in an expression");
                if (match("(")) {
                    List<JsonElement> arguments = new ArrayList<>();
                    if (!at(")")) do { arguments.add(expression()); } while (match(","));
                    expect(")");
                    return operation("call", name, token, arguments.toArray(JsonElement[]::new));
                }
                return operation("ctrl", "var", token, new JsonPrimitive(name));
            }
            throw error("Expected expression, found '" + token.text() + "'");
        }
    }
    public static AzExpression getExpression(String source, int offset, AzScript script) {
        if (offset < 0 || offset > source.length()) throw new IllegalArgumentException("Invalid source offset");
        Parser parser = new Parser(source.substring(offset), script);
        JsonElement result;
        if (parser.at("{")) result = parser.block();
        else if (Set.of("return", "break", "def", "var", "if", "while", "delete").contains(parser.current().text())
                || parser.knownType(parser.current().text())) result = parser.statement();
        else result = parser.expression();
        parser.match(";"); parser.expect("<eof>"); TreeLimits.validate(result); return () -> result;
    }
    public static class ConstantExpression implements AzExpression {
        private final JsonElement value;
        public ConstantExpression(String value, AzScript script) { this.value = getExpression(value, 0, script).generate(); }
        @Override public JsonElement generate() { return value; }
    }
}
