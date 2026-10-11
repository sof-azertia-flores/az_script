package azertia.script;

import java.util.ArrayList;
import java.util.List;

/**
 * One compiler message in the gcc/clang layout
 * {@code file:line:column: severity: [in context: ]message}, followed by its notes.
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

    public Diagnostic(Severity severity, String code, Position position, String context, String message,
                      List<Diagnostic> notes, boolean internal) {
        this.severity = severity == null ? Severity.ERROR : severity; this.code = code; this.position = position; this.context = context;
        this.message = message == null ? "" : message; this.notes = List.copyOf(notes == null ? List.of() : notes); this.internal = internal;
    }
    public static Diagnostic error(Position position, String context, String message, List<Diagnostic> notes) {
        return new Diagnostic(Severity.ERROR, null, position, context, message, notes, false);
    }
    public static Diagnostic error(Position position, String message) { return error(position, null, message, List.of()); }
    public static Diagnostic note(Position position, String message) {
        return new Diagnostic(Severity.NOTE, null, position, null, message, List.of(), false);
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

    /** A copy with additional notes appended. */
    public Diagnostic withNotes(List<Diagnostic> extra) {
        if (extra.isEmpty()) return this;
        List<Diagnostic> all = new ArrayList<>(notes); all.addAll(extra);
        return new Diagnostic(severity, code, position, context, message, all, internal);
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
        if (context != null && !context.isEmpty()) text.append("in ").append(context).append(": ");
        text.append(message);
        if (code != null) text.append(" [-W").append(code).append(']');
    }
    @Override public String toString() { return format(); }

    /** Function IDs are printed as the full 32-bit pattern, e.g. {@code 0x0fff0002}. */
    public static String hexId(int id) { return String.format("0x%08x", id); }
}
