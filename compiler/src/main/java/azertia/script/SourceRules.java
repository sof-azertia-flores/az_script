package azertia.script;

import java.util.HashSet;
import java.util.Set;

/**
 * Naming, hint, namespace and hexadecimal-ID rules shared by the parser, the
 * compiler and the exec codec, so source and compile-json accept the same names.
 * The C++ loader repeats the hint rule; {@code tests/hint_names.txt} checks both.
 */
final class SourceRules {
    private SourceRules() {}

    /** Built-in type names. */
    static final Set<String> TYPES = Set.of("int", "float", "double", "string", "boolean", "bool", "void", "address", "buffer");
    /** Scalar conversion calls. Boolean conversions are deliberately not part of the language. */
    static final Set<String> CONVERSIONS = Set.of("int", "float", "double", "string", "address");
    /**
     * Statement keywords and {@code any}. They are rejected even where a type name is
     * read, because the parser reads type names as identifiers.
     */
    static final Set<String> KEYWORDS = Set.of("return", "if", "else", "while", "for", "switch", "case", "default", "break", "continue", "def", "var",
            "class", "new", "delete", "this", "null", "extern", "extends", "operator", "public", "private", "protected", "virtual", "any");
    /** Words that can never name a class, member, function, parameter, variable, global, macro or type parameter. */
    static final Set<String> RESERVED_WORDS;
    static {
        Set<String> words = new HashSet<>(KEYWORDS); words.addAll(TYPES); words.add("true"); words.add("false");
        RESERVED_WORDS = Set.copyOf(words);
    }

    /** The lifecycle hooks; a typed global cannot take their names, since the compiler may add the onload. */
    static final Set<String> LIFECYCLE_HOOKS = Set.of("__script_onload", "__script_pre_destroy");
    /** Lifecycle hooks of every module and the placeholders of hint libraries. */
    static final int LIFECYCLE_NAMESPACE = 0;
    /** Runtime built-ins. */
    static final int RUNTIME_NAMESPACE = 0xabd;
    /** The namespace of a module without {@code #namespace}; {@code main} is 0x0fff0000. */
    static final int DEFAULT_NAMESPACE = 0xfff;

    /**
     * A character that may start an identifier. Unlike Java, an identifier can never
     * contain an ignorable character: controls and format characters such as U+200B
     * or the bidirectional override U+202E would make two names look alike.
     */
    static boolean identifierStart(char c) { return Character.isJavaIdentifierStart(c) && !Character.isIdentifierIgnorable(c); }
    static boolean identifierPart(char c) { return Character.isJavaIdentifierPart(c) && !Character.isIdentifierIgnorable(c); }
    static boolean isIdentifier(String value) {
        if (value.isEmpty() || !identifierStart(value.charAt(0))) return false;
        for (int i = 1; i < value.length(); i++) if (!identifierPart(value.charAt(i))) return false;
        return true;
    }
    /** The message for a reserved word; {@code description} completes "cannot be used as a ...". */
    static String reservedWordMessage(String value, String description) {
        return "'" + value + "' is a reserved word and cannot be used as a " + description;
    }
    /** Rejects a reserved word; {@code description} completes "cannot be used as a ...". */
    static void requireUnreserved(String value, String description) {
        if (RESERVED_WORDS.contains(value)) throw new IllegalArgumentException(reservedWordMessage(value, description));
    }
    /** Checks a declared source name, e.g. {@code requireName(name, "field name")}. */
    static void requireName(String value, String description) {
        if (!isIdentifier(value)) throw new IllegalArgumentException("Invalid " + description + ": " + AstShape.printable(value));
        requireUnreserved(value, description);
    }

    /**
     * A control (C0, DEL or C1), line or paragraph separator, or bidirectional formatting
     * character. Source labels and exec debug names never contain one, because it could
     * forge or reorder a diagnostic or stack-trace line. The C++ loader repeats this rule.
     */
    static boolean unsafeDisplayCharacter(char c) {
        return Character.isISOControl(c) || c == 0x2028 || c == 0x2029 || c == 0x061c || c == 0x200e || c == 0x200f
                || c >= 0x202a && c <= 0x202e || c >= 0x2066 && c <= 0x2069;
    }
    /** Why a name or file of an exec debug record is invalid, or null when it is valid. */
    static String debugTextProblem(String value) {
        if (value.isEmpty()) return "is empty";
        if (!GeneraterJson.wellFormedUtf16(value)) return "contains an unpaired surrogate";
        for (int i = 0; i < value.length(); i++)
            if (unsafeDisplayCharacter(value.charAt(i))) return "contains a control, separator or bidirectional formatting character";
        return null;
    }
    /** A display name with every unsafe character and unpaired surrogate replaced by U+FFFD, valid for a debug record. */
    static String debugText(String value) {
        if (value.isEmpty()) return "\uFFFD";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                out.append(c).append(value.charAt(++i));
            } else out.append(Character.isSurrogate(c) || unsafeDisplayCharacter(c) ? '\uFFFD' : c);
        }
        return out.toString();
    }

    /** A hint name is ASCII only, like the C++ loader's check: {@code [A-Za-z_][A-Za-z0-9_]*}. */
    static boolean isHint(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_';
            if (!letter && (i == 0 || c < '0' || c > '9')) return false;
        }
        return true;
    }
    static void requireHint(String value) {
        if (!isHint(value)) throw new IllegalArgumentException("Invalid hint name: " + AstShape.printable(value));
    }

    /**
     * Parses a hexadecimal number as the readable AST writes it: 1 to {@code digits}
     * hexadecimal digits with no prefix. The parser writes IDs canonically, so this
     * strict form is the only one compile-json accepts.
     */
    static int hex(String text, int digits, String description) {
        boolean valid = !text.isEmpty() && text.length() <= digits;
        for (int i = 0; valid && i < text.length(); i++) {
            char c = text.charAt(i);
            valid = c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
        }
        if (!valid) throw new IllegalArgumentException("Invalid " + description + ": " + AstShape.printable(text));
        return Integer.parseUnsignedInt(text, 16);
    }
    /**
     * Parses a hexadecimal number as source writes it: an optional {@code 0x} prefix
     * and any number of leading zeros, then at most {@code digits} significant digits.
     */
    static int sourceHex(String text, int digits, String description) {
        String value = text.startsWith("0x") || text.startsWith("0X") ? text.substring(2) : text;
        int start = 0;
        while (start + 1 < value.length() && value.charAt(start) == '0') start++;
        try { return hex(value.substring(start), digits, description); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid " + description + ": " + AstShape.printable(text)); }
    }

    /**
     * Why an ordinary module cannot use {@code namespace} as its function namespace,
     * or null when it can. The default namespace 0fff may be named explicitly.
     */
    static String reservedModuleNamespace(int namespace) {
        if (namespace == LIFECYCLE_NAMESPACE) return "0000 is reserved for lifecycle hooks and hint-library placeholders";
        if (namespace == RUNTIME_NAMESPACE) return "0abd is reserved for runtime built-ins";
        return null;
    }
    /** A namespace {@code #assume_hint} may name: 0001..ffff except the runtime and default namespaces. */
    static boolean assumableNamespace(int namespace) {
        return namespace > LIFECYCLE_NAMESPACE && namespace <= 0xffff && namespace != RUNTIME_NAMESPACE && namespace != DEFAULT_NAMESPACE;
    }
    /** True for an ID in the runtime built-in namespace. */
    static boolean runtimeId(int id) { return id >>> 16 == RUNTIME_NAMESPACE; }
    /** An ID an extern cannot import: a runtime built-in, a lifecycle hook or {@code main}. */
    static boolean reservedExternalId(int id) { return runtimeId(id) || GeneraterJson.specialNames.containsValue(id); }
}
