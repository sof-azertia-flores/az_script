package azertia.script;

import java.util.*;

/**
 * Collects the diagnostics of one compilation and holds its options; use a new
 * instance for each compilation. The same instance may serve
 * {@link GeneraterJson.AzScript#execute(java.nio.file.Path, Diagnostics)} and then
 * {@link Compiler#compile(com.google.gson.JsonObject, Diagnostics)} of the resulting AST.
 *
 * <p>Compilation runs in phases: preprocessing, lexing, parsing, post-parse
 * validation, AST validation, the global declaration loops and the function
 * bodies. Within a phase the compiler recovers at the next directive,
 * declaration, class member, statement, class, global item or function, and at
 * the end of a phase it stops when errors were reported, so later phases never
 * see broken input. At most {@link #maxErrors()} errors are reported.
 *
 * <p>Deliberate compiler errors are plain {@code IllegalArgumentException}s or
 * {@link CompileException}s; the compiler locates the former at the nearest
 * positioned AST node or source token. Any other exception is unexpected: for a
 * program compiled from source it is an internal compiler error, while for an
 * AST supplied as JSON it is reported as malformed input.
 *
 * <p>Warnings are listed in {@link #WARNINGS}. They never stop a compilation
 * unless {@link #warningsAsErrors(boolean)} reports them as errors. An option for
 * one warning overrides {@link #allWarnings()} in either order, and
 * {@link #noWarnings()} suppresses every warning.
 */
public final class Diagnostics {
    public static final int DEFAULT_MAX_ERRORS = 20;
    /** A compiler warning: its name as in {@code -W<name>}, whether it is reported by default, and what it reports. */
    public record Warning(String name, boolean enabledByDefault, String description) {}
    /** Every warning the compiler reports. */
    public static final List<Warning> WARNINGS = List.of(
            new Warning("assign-in-condition", true, "an assignment used directly as an if, while or for condition"),
            new Warning("unreachable-code", true, "a statement after return, break, continue or another statement that never completes"),
            new Warning("unused-variable", false, "a local variable that is declared but never used"));
    private enum Stage { NEW, PARSING, PARSED, COMPILING }

    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<String> reported = new HashSet<>();
    private String defaultFile;
    private int maxErrors = DEFAULT_MAX_ERRORS, errors, phaseStart;
    private Stage stage = Stage.NEW;
    /** Warnings enabled or disabled one by one; they override {@link #allWarnings}. */
    private final Map<String,Boolean> warningChoices = new HashMap<>();
    private boolean allWarnings, noWarnings, warningsAsErrors;
    private SourceMap sources;
    /** The first unexpected exception, kept as the cause of the final failure. */
    private Throwable unexpected;

    /** A collector that stops at the first error. */
    public static Diagnostics failFast() { return new Diagnostics().maxErrors(1); }

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

    /** Reports at most {@code limit} errors; 0 means no limit and 1 stops at the first error. */
    public Diagnostics maxErrors(int limit) {
        if (limit < 0) throw new IllegalArgumentException("The error limit cannot be negative");
        maxErrors = limit; return this;
    }
    public int maxErrors() { return maxErrors; }
    /** Whether the compiler continues after an error to report further ones. */
    public boolean recovering() { return maxErrors != 1; }

    /** Whether the compiler reports a warning of this name. */
    public static boolean isWarning(String name) { return WARNINGS.stream().anyMatch(warning -> warning.name().equals(name)); }
    /** Enables or disables one warning, like {@code -W<name>} and {@code -Wno-<name>}; the last choice for a warning wins. */
    public Diagnostics warning(String name, boolean enabled) {
        if (!isWarning(name)) throw new IllegalArgumentException("Unknown warning: " + name);
        warningChoices.put(name, enabled); return this;
    }
    /** Enables every warning that is not disabled by name, like {@code -Wall}. */
    public Diagnostics allWarnings() { allWarnings = true; return this; }
    /** Suppresses every warning, like {@code -w}, whatever the other warning options say. */
    public Diagnostics noWarnings() { noWarnings = true; return this; }
    /** Reports enabled warnings as errors, like {@code -Werror}, so that they fail the compilation. */
    public Diagnostics warningsAsErrors(boolean enabled) { warningsAsErrors = enabled; return this; }
    public boolean warningsAsErrors() { return warningsAsErrors; }
    /** Whether a warning is reported under the current options. */
    public boolean warningEnabled(String name) {
        Warning warning = WARNINGS.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown warning: " + name));
        if (noWarnings) return false;
        Boolean chosen = warningChoices.get(name);
        return chosen != null ? chosen : allWarnings || warning.enabledByDefault();
    }

    /** Marks the start of parsing; a collector parses at most once. */
    void startParse() {
        if (stage != Stage.NEW) throw new IllegalStateException("A Diagnostics instance serves a single compilation");
        stage = Stage.PARSING;
    }
    /** Marks a successful parse, whose AST this collector may then compile. */
    void parsed() { if (stage == Stage.PARSING) stage = Stage.PARSED; }
    /**
     * Marks the start of compiling an AST and returns whether the in-process parser
     * produced it with this collector, which makes an unexpected exception an
     * internal compiler error instead of malformed input.
     */
    boolean startCompile() {
        if (stage != Stage.NEW && stage != Stage.PARSED)
            throw new IllegalStateException("A Diagnostics instance serves a single compilation");
        boolean parsed = stage == Stage.PARSED;
        stage = Stage.COMPILING; return parsed;
    }
    /** The source table that orders diagnostics in translation order. */
    void sources(SourceMap map) { sources = map; }

    /**
     * Whether a failure is an error in the compiled program: a non-internal
     * {@link CompileException}, an exact {@code IllegalArgumentException}, or an
     * exceeded nesting limit. Anything else, including subclasses such as
     * {@code NumberFormatException}, is unexpected.
     */
    public static boolean userError(Throwable failure) {
        if (failure instanceof CompileException compiled) return !compiled.internal();
        return failure != null && (failure.getClass() == IllegalArgumentException.class || failure instanceof TreeLimits.LimitExceeded);
    }

    /**
     * Records a failure and returns when the compiler may recover from it. Throws the
     * final failure, carrying every diagnostic recorded so far, when the failure is
     * fatal, when recovery is off, or when the error limit is exceeded.
     */
    void report(CompileException failure) {
        Throwable cause = failure.getCause();
        if (unexpected == null && cause != null && !userError(cause)) unexpected = cause;
        boolean limited = false;
        for (Diagnostic diagnostic : failure.diagnostics()) {
            if (reported.contains(diagnostic.format())) continue;
            // Internal errors are always kept: they decide the exit status.
            if (diagnostic.isError() && !diagnostic.internal() && maxErrors > 0 && errors >= maxErrors) { limited = true; continue; }
            add(diagnostic);
        }
        if (limited) {
            sortPhase();
            add(Diagnostic.note(null, "too many errors emitted (limit " + maxErrors + "); stopping"));
            throw failure();
        }
        if (failure.fatal() || !recovering()) throw failure();
    }
    /**
     * Records a warning when it is enabled. With {@link #warningsAsErrors(boolean)} it
     * is recorded as an error instead, which fails the compilation at the end of the
     * phase, or at once when recovery is off or the error limit is exceeded.
     */
    void warn(Diagnostic warning) {
        if (warning.severity() != Diagnostic.Severity.WARNING || !warningEnabled(warning.code())) return;
        if (warningsAsErrors) { report(new CompileException(warning.asError())); return; }
        if (!reported.contains(warning.format())) add(warning);
    }
    /** Ends a phase: throws the failure when any error was reported. */
    void checkpoint() {
        sortPhase();
        if (errors > 0) throw failure();
    }
    /** Records a failure and returns the final failure with everything recorded so far. */
    public CompileException record(CompileException failure) {
        try { report(failure.stopping()); }
        catch (CompileException stopped) { return stopped; }
        return failure();
    }
    /** The failure ending this compilation, carrying every diagnostic recorded so far. */
    CompileException failure() {
        sortPhase();
        return new CompileException(diagnostics, unexpected, true);
    }
    /** Every recorded diagnostic in report order. */
    public List<Diagnostic> diagnostics() { return List.copyOf(diagnostics); }
    public int errorCount() { return errors; }
    /** Recorded warnings, not counting those reported as errors. */
    public int warningCount() {
        return (int) diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.WARNING).count();
    }
    /** The recorded warnings in report order. */
    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Diagnostic.Severity.WARNING).toList();
    }

    private void add(Diagnostic diagnostic) {
        reported.add(diagnostic.format()); diagnostics.add(diagnostic);
        if (diagnostic.isError()) errors++;
    }
    /**
     * Orders the current phase's diagnostics stably: internal errors first, then
     * file-level ones, then by position in translation order, where an included
     * file's text stands at its #include directive.
     */
    private void sortPhase() {
        List<Diagnostic> phase = diagnostics.subList(phaseStart, diagnostics.size());
        if (phase.size() > 1) {
            Map<Diagnostic,int[]> keys = new IdentityHashMap<>();
            for (Diagnostic diagnostic : phase) keys.put(diagnostic, key(diagnostic));
            List<Diagnostic> sorted = new ArrayList<>(phase);
            sorted.sort(Comparator.comparing((Diagnostic diagnostic) -> !diagnostic.internal())
                    .thenComparing((first, second) -> Arrays.compare(keys.get(first), keys.get(second))));
            for (int i = 0; i < sorted.size(); i++) phase.set(i, sorted.get(i));
        }
        phaseStart = diagnostics.size();
    }
    /** (line, column) pairs from the main file down to the position: include sites, then the position itself. */
    private int[] key(Diagnostic diagnostic) {
        Diagnostic.Position at = diagnostic.position();
        if (at == null || at.line() == 0) return new int[0];
        int unit = diagnostic.unit();
        Deque<Integer> path = new ArrayDeque<>();
        path.addFirst(at.column()); path.addFirst(at.line());
        if (sources != null && unit >= 0 && unit < sources.unitCount()) {
            // Parents precede their units, so the walk ends; a stored chain is at most 64 deep.
            for (SourceMap.Unit current = sources.unit(unit); current.parent() >= 0; current = sources.unit(current.parent())) {
                path.addFirst(current.column()); path.addFirst(current.line());
            }
        }
        return path.stream().mapToInt(Integer::intValue).toArray();
    }
}
