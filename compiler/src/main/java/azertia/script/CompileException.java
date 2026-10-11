package azertia.script;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A compilation failure carrying located diagnostics. It remains an
 * IllegalArgumentException so existing library callers keep working; the
 * message is the formatted errors, one diagnostic (with its notes) per block.
 *
 * <p>A fatal failure stops error recovery: limits, internal compiler errors and
 * the failures that end a compilation are fatal, while an ordinary error lets the
 * compiler continue with the next declaration, statement or function.
 */
public final class CompileException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final transient List<Diagnostic> diagnostics;
    private final boolean fatal;

    public CompileException(Diagnostic diagnostic) { this(List.of(diagnostic), null, false); }
    public CompileException(Diagnostic diagnostic, Throwable cause) { this(List.of(diagnostic), cause, false); }
    public CompileException(List<Diagnostic> diagnostics, Throwable cause) { this(diagnostics, cause, false); }
    public CompileException(List<Diagnostic> diagnostics, Throwable cause, boolean fatal) {
        super(cause);
        if (diagnostics.stream().noneMatch(Diagnostic::isError))
            throw new IllegalArgumentException("A compilation failure needs at least one error");
        this.diagnostics = List.copyOf(diagnostics);
        this.fatal = fatal || internal();
    }
    /** Every reported diagnostic in order, including warnings collected before the failure. */
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public List<Diagnostic> errors() { return diagnostics.stream().filter(Diagnostic::isError).toList(); }
    /** Whether compilation stops here instead of recovering at the next declaration, statement or function. */
    public boolean fatal() { return fatal; }
    /** Whether an internal compiler error, a bug of the compiler rather than of the program, is among the errors. */
    public boolean internal() { return diagnostics.stream().anyMatch(Diagnostic::internal); }
    /** A fatal copy, for an error after which compilation cannot continue. */
    CompileException stopping() { return fatal ? this : new CompileException(diagnostics, getCause(), true); }
    /** The errors and stand-alone notes, such as the error-limit note, each with its own notes. */
    @Override public String getMessage() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.isError() || diagnostic.severity() == Diagnostic.Severity.NOTE)
                .map(Diagnostic::format).collect(Collectors.joining("\n"));
    }
}
