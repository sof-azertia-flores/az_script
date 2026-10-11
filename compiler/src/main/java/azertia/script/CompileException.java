package azertia.script;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A compilation failure carrying located diagnostics. It remains an
 * IllegalArgumentException so existing library callers keep working; the
 * message is the formatted errors, one diagnostic (with its notes) per block.
 */
public final class CompileException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final transient List<Diagnostic> diagnostics;

    public CompileException(Diagnostic diagnostic) { this(List.of(diagnostic), null); }
    public CompileException(Diagnostic diagnostic, Throwable cause) { this(List.of(diagnostic), cause); }
    public CompileException(List<Diagnostic> diagnostics, Throwable cause) {
        super(cause);
        if (diagnostics.stream().noneMatch(Diagnostic::isError))
            throw new IllegalArgumentException("A compilation failure needs at least one error");
        this.diagnostics = List.copyOf(diagnostics);
    }
    /** Every reported diagnostic in order, including warnings collected before the failure. */
    public List<Diagnostic> diagnostics() { return diagnostics; }
    public List<Diagnostic> errors() { return diagnostics.stream().filter(Diagnostic::isError).toList(); }
    @Override public String getMessage() {
        return errors().stream().map(Diagnostic::format).collect(Collectors.joining("\n"));
    }
}
