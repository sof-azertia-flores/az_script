package azertia.script;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the diagnostics of one compilation and holds its options; use a new
 * instance for each compilation. Compilation currently stops at the first error,
 * which is thrown as a {@link CompileException} carrying every diagnostic recorded so far.
 *
 * <p>Deliberate compiler errors are plain {@code IllegalArgumentException}s or
 * {@link CompileException}s; the compiler locates the former at the nearest
 * positioned AST node or source token.
 */
public final class Diagnostics {
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private String defaultFile;
    private boolean started;

    /**
     * Names the compiled input, e.g. the JSON file passed to {@code compile-json}.
     * Errors without a source position then name this file, and so do all positions
     * of an AST without a {@code sources} table. Unset, file-level errors name the
     * AST's main source and an AST without {@code sources} is {@code <source>}.
     */
    public Diagnostics defaultFile(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Default diagnostic file name cannot be empty");
        defaultFile = name; return this;
    }
    public String defaultFile() { return defaultFile != null ? defaultFile : "<source>"; }
    /** Whether {@link #defaultFile(String)} named the compiled input. */
    boolean namesInput() { return defaultFile != null; }

    /** Marks the start of the single compilation this collector serves. */
    void start() {
        if (started) throw new IllegalStateException("A Diagnostics instance serves a single compilation");
        started = true;
    }
    /** Records the diagnostics of a failure and returns the failure with everything recorded so far. */
    public CompileException record(CompileException failure) {
        for (Diagnostic diagnostic : failure.diagnostics()) if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
        return new CompileException(diagnostics, failure.getCause());
    }
    /** Every recorded diagnostic in report order. */
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
}
