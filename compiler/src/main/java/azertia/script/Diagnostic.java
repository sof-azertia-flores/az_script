package azertia.script;

import java.util.ArrayList;
import java.util.List;

/**
 * One compiler message in the gcc/clang layout
 * {@code file:line:column: severity: [in context: ]message}, followed by its notes.
 * A warning ends with its option, e.g. {@code [-Wunreachable-code]}, or with
 * {@code [-Werror=unreachable-code]} when {@code -Werror} made it an error.
 * Positions name original source files after includes and macro expansion.
 */
public final class Diagnostic {
    public enum Severity {
        ERROR("error"), WARNING("warning"), NOTE("note");
        private final String label;
        Severity(String label) { this.label = label; }
        public String label() { return label; }
    }
    /** A source position; line 0 means the whole file and column 0 an unknown column. */
    public record Position(String file, int line, int column) {
        public Position {
            // Reporting must never fail while describing another failure.
            if (file == null || file.isEmpty()) file = "<unknown>";
            if (line <= 0) { line = 0; column = 0; }
            if (column < 0) column = 0;
        }
        public static Position file(String file) { return new Position(file, 0, 0); }
        @Override public String toString() {
            return line == 0 ? file : column == 0 ? file + ":" + line : file + ":" + line + ":" + column;
        }
    }

    private final Severity severity;
    private final String code;
    private final String message;
    private final Position position;
    private final String context;
    private final List<Diagnostic> notes;
    private final boolean internal;
    /** Source unit of the position, which orders diagnostics in translation order; -1 when unknown. */
    private final int unit;

    public Diagnostic(Severity severity, String code, Position position, String context, String message,
                      List<Diagnostic> notes, boolean internal) {
        this(severity, code, position, context, message, notes, internal, -1);
    }
    private Diagnostic(Severity severity, String code, Position position, String context, String message,
                       List<Diagnostic> notes, boolean internal, int unit) {
        this.severity = severity == null ? Severity.ERROR : severity; this.code = code; this.position = position; this.context = context;
        this.message = message == null ? "" : message; this.notes = List.copyOf(notes == null ? List.of() : notes); this.internal = internal;
        this.unit = unit;
    }
    public static Diagnostic error(Position position, String context, String message, List<Diagnostic> notes) {
        return new Diagnostic(Severity.ERROR, null, position, context, message, notes, false);
    }
    public static Diagnostic error(Position position, String message) { return error(position, null, message, List.of()); }
    public static Diagnostic note(Position position, String message) {
        return new Diagnostic(Severity.NOTE, null, position, null, message, List.of(), false);
    }
    /** A warning named by its code, e.g. {@code unreachable-code}; see {@link Diagnostics#WARNINGS}. */
    public static Diagnostic warning(String code, Position position, String context, String message, List<Diagnostic> notes) {
        if (code == null || code.isEmpty()) throw new IllegalArgumentException("A warning needs a code");
        return new Diagnostic(Severity.WARNING, code, position, context, message, notes, false);
    }
    /**
     * An internal compiler error: an unexpected exception of the compiler itself, as
     * opposed to an error in the compiled program. It carries a note asking for a bug report.
     */
    public static Diagnostic internalError(Position position, String context, Throwable cause, List<Diagnostic> notes) {
        List<Diagnostic> all = new ArrayList<>(notes == null ? List.of() : notes);
        all.add(note(null, "this is a compiler bug; rerun with --debug-compiler for a stack trace"));
        return new Diagnostic(Severity.ERROR, null, position, context, "internal compiler error: " + describe(cause), all, true);
    }
    /** {@code SimpleName: first message line} of an unexpected exception. */
    static String describe(Throwable cause) {
        if (cause == null) return "unknown failure";
        String name = cause.getClass().getSimpleName().isEmpty() ? cause.getClass().getName() : cause.getClass().getSimpleName();
        String message = cause.getMessage() == null ? "" : cause.getMessage().lines().findFirst().orElse("");
        return message.isBlank() ? name : name + ": " + message;
    }

    public Severity severity() { return severity; }
    /** Warning name such as {@code unreachable-code}, or null. */
    public String code() { return code; }
    public String message() { return message; }
    public Position position() { return position; }
    /** Enclosing declaration such as {@code function main} or {@code initializer of class P}, or null. */
    public String context() { return context; }
    public List<Diagnostic> notes() { return notes; }
    public boolean internal() { return internal; }
    public boolean isError() { return severity == Severity.ERROR; }
    int unit() { return unit; }
    /** A copy whose position lies in the given source unit. */
    Diagnostic inUnit(int unit) {
        return new Diagnostic(severity, code, position, context, message, notes, internal, unit);
    }

    /** This warning reported as an error, as {@code -Werror} does; it keeps its code. */
    Diagnostic asError() {
        return new Diagnostic(Severity.ERROR, code, position, context, message, notes, internal, unit);
    }

    /** A copy with additional notes appended. */
    public Diagnostic withNotes(List<Diagnostic> extra) {
        if (extra.isEmpty()) return this;
        List<Diagnostic> all = new ArrayList<>(notes); all.addAll(extra);
        return new Diagnostic(severity, code, position, context, message, all, internal, unit);
    }

    /** This diagnostic and its notes, one line each. */
    public String format() {
        StringBuilder text = new StringBuilder(); appendLine(text);
        for (Diagnostic note : notes) { text.append('\n'); note.appendLine(text); }
        return text.toString();
    }
    private void appendLine(StringBuilder text) {
        if (position != null) text.append(position).append(": ");
        text.append(severity.label()).append(": ");
        // A context or message may quote a name from a handwritten AST; escaping keeps a
        // bidirectional override or an invisible character from reordering or hiding the line.
        if (context != null && !context.isEmpty()) text.append("in ").append(AstShape.printable(context)).append(": ");
        text.append(AstShape.printable(message));
        // A warning promoted by -Werror names the option that made it an error.
        if (code != null) text.append(severity == Severity.ERROR ? " [-Werror=" : " [-W").append(code).append(']');
    }
    @Override public String toString() { return format(); }

    /** Function IDs are printed as the full 32-bit pattern, e.g. {@code 0x0fff0002}. */
    public static String hexId(int id) { return String.format("0x%08x", id); }
}
