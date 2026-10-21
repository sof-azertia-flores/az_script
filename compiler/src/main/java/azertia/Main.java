package azertia;

import azertia.achieve.MakeAchieve;
import azertia.binary.complexBinary.AcsObject;
import azertia.script.CompileException;
import azertia.script.Compiler;
import azertia.script.Diagnostic;
import azertia.script.Diagnostics;
import azertia.script.GeneraterJson;
import azertia.script.TreeLimits;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Command line entry point; library callers can keep using AzScript and Compiler directly. */
public class Main {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /**
     * The compile options shared with the distribution launcher: options taking a
     * value, mapped to their canonical spelling, and value-less flags.
     */
    private static final Map<String,String> VALUE_OPTIONS = Map.of(
            "-o", "-o", "--output", "-o", "--ast", "--ast", "--exec-json", "--exec-json", "--max-errors", "--max-errors",
            "-I", "-I", "--include-dir", "-I");
    /**
     * {@code --debug-compiler} prints the stack trace of an internal compiler error;
     * {@code --unrestricted-includes} allows absolute includes and includes outside the include roots;
     * {@code -g} adds exec debug records.
     */
    private static final Set<String> FLAG_OPTIONS = Set.of("--debug-compiler", "--unrestricted-includes", "-g", "-O0", "-O1", "-O2", "-O3", "--no-fold", "--no-propagation", "--no-inline", "--no-loop-motion", "--no-cfg-opt", "--no-cse", "--no-dce");
    /** The system property naming the standard library directory, searched after every {@code -I} directory. */
    public static final String STDLIB_PROPERTY = "azscript.stdlib";
    /** Warning options other than {@code -W<name>} and {@code -Wno-<name>}. */
    private static final Set<String> WARNING_FLAGS = Set.of("-w", "-Wall", "-Werror");
    private static final Pattern GSON_POSITION = Pattern.compile("(?s)^(?:[\\w.$]+(?:Exception|Error): )?(.*?) at line (\\d+) column (\\d+)(?: path .*)?$");

    /**
     * Parsed {@code compile}/{@code compile-json} arguments. Options may appear before
     * or after the input path, and {@code --} ends them so a path may begin with '-';
     * unset outputs are null, {@code flags} holds the given flags, {@code maxErrors}
     * is the error limit ({@link Diagnostics#DEFAULT_MAX_ERRORS} unless given, 0 for none),
     * {@code warnings} lists the warning options in order and {@code includeDirectories}
     * the {@code -I}/{@code --include-dir} directories in order.
     */
    public record CompileArguments(String command, Path source, Path output, Path astOutput, Path execOutput, Set<String> flags,
                                   int maxErrors, List<String> warnings, List<Path> includeDirectories) {
        /** Whether {@code --unrestricted-includes} was given. */
        public boolean unrestrictedIncludes() { return flags.contains("--unrestricted-includes"); }
        /** Whether {@code -g} was given: the exec then carries function and file names and source positions. */
        public boolean debugInfo() { return flags.contains("-g"); }
        /** The last -O option wins; O0 is the default. */
        public Compiler.Optimization optimization() {
            return flags.contains("-O3") ? Compiler.Optimization.AGGRESSIVE
                    : flags.contains("-O2") ? Compiler.Optimization.ADVANCED
                    : flags.contains("-O1") ? Compiler.Optimization.STANDARD : Compiler.Optimization.NONE;
        }
        /** Explicit pass switches are useful for diagnostics and independent comparisons. */
        public Set<Compiler.OptimizationPass> optimizationPasses() {
            var passes = java.util.EnumSet.noneOf(Compiler.OptimizationPass.class);
            passes.addAll(Compiler.optimizationPasses(optimization()));
            if (flags.contains("--no-fold")) passes.remove(Compiler.OptimizationPass.CONSTANTS);
            if (flags.contains("--no-propagation")) {
                passes.remove(Compiler.OptimizationPass.PROPAGATION);
                passes.remove(Compiler.OptimizationPass.FLOW_PROPAGATION);
            }
            if (flags.contains("--no-inline")) {
                passes.remove(Compiler.OptimizationPass.INLINING);
                passes.remove(Compiler.OptimizationPass.SCALAR_INLINING);
            }
            if (flags.contains("--no-loop-motion")) {
                passes.remove(Compiler.OptimizationPass.LOOP_MOTION);
                passes.remove(Compiler.OptimizationPass.RANGE_LOOP_MOTION);
            }
            if (flags.contains("--no-cfg-opt")) passes.remove(Compiler.OptimizationPass.CONTROL_FLOW);
            if (flags.contains("--no-cse")) passes.remove(Compiler.OptimizationPass.COMMON_SUBEXPRESSIONS);
            if (flags.contains("--no-dce")) passes.remove(Compiler.OptimizationPass.DEAD_CODE);
            return Set.copyOf(passes);
        }
        /** The ABD output: {@code -o}, or the source name with {@code .exec.abd} beside it. */
        public Path outputOrDefault() {
            if (output != null) return output;
            if (source.getFileName() == null) throw new IllegalArgumentException("Input path must name a file: " + source);
            return source.resolveSibling(source.getFileName().toString().replaceFirst("\\.[^.]+$", "") + ".exec.abd");
        }
        /**
         * A collector with the error limit and warning options: {@code -Wall} enables every
         * warning, {@code -W<name>}/{@code -Wno-<name>} enable or disable one whatever their
         * position relative to {@code -Wall}, {@code -w} suppresses all and {@code -Werror}
         * reports warnings as errors.
         */
        public Diagnostics diagnostics() {
            Diagnostics diagnostics = new Diagnostics().maxErrors(maxErrors);
            for (String option : warnings) switch (option) {
                case "-w" -> diagnostics.noWarnings();
                case "-Wall" -> diagnostics.allWarnings();
                case "-Werror" -> diagnostics.warningsAsErrors(true);
                default -> {
                    if (option.startsWith("-Wno-")) diagnostics.warning(option.substring(5), false);
                    else diagnostics.warning(option.substring(2), true);
                }
            }
            return diagnostics;
        }
    }
    /** Parses {@code compile} or {@code compile-json} followed by its operands and options. */
    public static CompileArguments parseCompileArguments(List<String> args) {
        if (args.isEmpty() || !args.get(0).equals("compile") && !args.get(0).equals("compile-json"))
            throw new IllegalArgumentException("Expected compile or compile-json");
        Path source = null;
        Map<String,String> values = new HashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<Path> includeDirectories = new ArrayList<>();
        boolean operands = false;
        for (int i = 1; i < args.size(); i++) {
            String argument = args.get(i), option = VALUE_OPTIONS.get(argument);
            if (operands) {
                if (source != null) throw new IllegalArgumentException("Unexpected argument: " + argument);
                source = path(argument);
            } else if (argument.equals("--")) {
                operands = true;
            } else if (option != null) {
                if (i + 1 >= args.size()) throw new IllegalArgumentException("Missing value for " + argument);
                String value = args.get(++i);
                if (!option.equals("-I")) values.put(option, value);
                // An empty directory, e.g. from an unset variable, would stand for the working directory.
                else if (value.isEmpty()) throw new IllegalArgumentException("Empty directory for " + argument + "; use . for the working directory");
                else includeDirectories.add(path(value));
            } else if (FLAG_OPTIONS.contains(argument)) {
                if (argument.equals("-O0") || argument.equals("-O1") || argument.equals("-O2") || argument.equals("-O3")) {
                    flags.remove("-O0"); flags.remove("-O1"); flags.remove("-O2"); flags.remove("-O3");
                }
                flags.add(argument);
            } else if (argument.equals("-w") || argument.startsWith("-W")) {
                String name = argument.startsWith("-Wno-") ? argument.substring(5) : argument.substring(2);
                if (!WARNING_FLAGS.contains(argument) && !Diagnostics.isWarning(name))
                    throw new IllegalArgumentException("Unknown warning option: " + argument);
                warnings.add(argument);
            } else if (argument.startsWith("-") && argument.length() > 1) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else if (source == null) source = path(argument);
            else throw new IllegalArgumentException("Unexpected argument: " + argument);
        }
        if (source == null) throw new IllegalArgumentException("Missing input path");
        if (args.get(0).equals("compile-json") && (!includeDirectories.isEmpty() || flags.contains("--unrestricted-includes")))
            throw new IllegalArgumentException("-I, --include-dir and --unrestricted-includes apply only to compile");
        int maxErrors = Diagnostics.DEFAULT_MAX_ERRORS;
        if (values.containsKey("--max-errors")) {
            String limit = values.get("--max-errors");
            try { maxErrors = limit.matches("[0-9]+") ? Integer.parseInt(limit) : -1; }
            catch (NumberFormatException e) { maxErrors = -1; }
            if (maxErrors < 0) throw new IllegalArgumentException("--max-errors expects a non-negative integer, got " + limit);
        }
        return new CompileArguments(args.get(0), source, path(values.get("-o")), path(values.get("--ast")), path(values.get("--exec-json")),
                Set.copyOf(flags), maxErrors, List.copyOf(warnings), List.copyOf(includeDirectories));
    }
    /** A command-line path; one the platform cannot represent, e.g. with a NUL character, is a usage error. */
    private static Path path(String value) {
        if (value == null) return null;
        try { return Path.of(value); }
        catch (InvalidPathException e) { throw new IllegalArgumentException("Invalid path: " + e.getMessage()); }
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err, defaultIncludeDirectories());
        if (status != 0) System.exit(status);
    }
    /**
     * The standard library directory named by the {@value #STDLIB_PROPERTY} system property,
     * which the {@code ./azscript} wrapper sets, or none when it is unset or not a directory.
     */
    public static List<Path> defaultIncludeDirectories() {
        String value = System.getProperty(STDLIB_PROPERTY);
        if (value == null || value.isBlank()) return List.of();
        try {
            Path directory = Path.of(value);
            return Files.isDirectory(directory) ? List.of(directory) : List.of();
        } catch (InvalidPathException e) { return List.of(); }
    }
    /** Runs a command without default include directories. */
    public static int run(String[] args, PrintStream out, PrintStream err) { return run(args, out, err, List.of()); }
    /**
     * Runs a command. {@code compile} searches {@code defaultIncludeDirectories} after
     * every {@code -I} directory; those that are not directories are skipped.
     */
    public static int run(String[] args, PrintStream out, PrintStream err, List<Path> defaultIncludeDirectories) {
        boolean debug = false;
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                StringBuilder warnings = new StringBuilder();
                for (Diagnostics.Warning warning : Diagnostics.WARNINGS)
                    warnings.append(String.format("\n  %-20s %s (%s)", warning.name(), warning.description(), warning.enabledByDefault() ? "on" : "off"));
                out.println("AzScript compiler\n  compile <source.azs> [-o output.abd] [--ast tree.json] [--exec-json instructions.json] [-g] [-I dir]... [--unrestricted-includes]\n  compile-json <tree.json> [-o output.abd] [--exec-json instructions.json] [-g]\n  pack <output.abd> <file-or-directory>...\n  unpack <archive.abd> <new-or-empty-directory>\nCompile options:\n  -O0, -O1, -O2     no optimization (default), conservative, or advanced scalar passes; last wins\n  -O3               aggressive passes that need an exec v11 runtime (in development; currently O2)\n  --no-fold, --no-propagation, --no-inline, --no-loop-motion\n  --no-cfg-opt, --no-cse, --no-dce\n                     disable a corresponding optimization stage\n  -g                 add debug records: function and file names and source positions\n  --max-errors N     stop after N errors (default " + Diagnostics.DEFAULT_MAX_ERRORS + ", 0 for no limit)\n  --debug-compiler   print the stack trace of an internal compiler error\n  -I dir, --include-dir dir\n                     search dir for includes; repeatable\n  --unrestricted-includes\n                     allow absolute includes and includes outside the include roots\n  -Wall              enable every warning\n  -W<name>           enable one warning; -Wno-<name> disables it, also after -Wall\n  -w                 suppress all warnings\n  -Werror            report warnings as errors\ncompile-json validates operator chains, if-chains, class method modifiers and switch sections in the readable AST.\nWarnings (default state):" + warnings + "\nIncludes are searched in the including file's directory, then in each -I directory in order, then in the\nstandard library directory of the ./azscript wrapper or the distribution. They must stay inside the main\nfile's directory or one of those directories.\nOptions may precede or follow the input path; -- ends the options. Diagnostics are written to stderr as\nfile:line:column: error: message, or warning: message [-W<name>]. The exit status is 1 when compilation fails\nand 2 for an internal compiler error; warnings alone do not fail it.");
                return 0;
            }
            switch (args[0]) {
                case "compile", "compile-json" -> {
                    CompileArguments arguments = parseCompileArguments(Arrays.asList(args));
                    Path source = arguments.source(), output = arguments.outputOrDefault();
                    Path astOutput = arguments.astOutput(), execOutput = arguments.execOutput();
                    debug = arguments.flags().contains("--debug-compiler");
                    com.google.gson.JsonObject ast;
                    List<Path> included = List.of();
                    // One collector serves the parse and the compilation, which marks the AST as the parser's own.
                    Diagnostics diagnostics = arguments.diagnostics();
                    if (args[0].equals("compile-json")) {
                        String label = source.getFileName() == null ? source.toString() : source.getFileName().toString();
                        diagnostics.defaultFile(label);
                        ast = readTree(source, label);
                    } else {
                        var script = new GeneraterJson.AzScript();
                        script.includeDirectories.addAll(arguments.includeDirectories());
                        // A missing default directory is skipped; a missing -I directory is an error.
                        for (Path directory : defaultIncludeDirectories) if (Files.isDirectory(directory)) script.includeDirectories.add(directory);
                        script.unrestrictedIncludes = arguments.unrestrictedIncludes();
                        script.execute(source, diagnostics);
                        ast = script.toObj(); included = script.includedFiles();
                    }
                    AcsObject compiled = Compiler.compile(ast, diagnostics, arguments.debugInfo(), arguments.optimizationPasses());
                    // A successful compilation may still report warnings.
                    for (Diagnostic warning : diagnostics.warnings()) err.println(warning.format());
                    var outputs = new java.util.HashSet<Path>();
                    for (Path target : new Path[]{output, astOutput, execOutput}) {
                        if (target == null) continue;
                        Path normalized = target.toAbsolutePath().normalize();
                        if (aliases(target, source)) throw new IllegalArgumentException("Output must not overwrite the source: " + target);
                        for (Path input : included)
                            if (aliases(target, input)) throw new IllegalArgumentException("Output must not overwrite an included source: " + target);
                        if (!outputs.add(normalized)) throw new IllegalArgumentException("Output paths must be distinct");
                    }
                    write(output, compiled.toValue().toAbdFormat());
                    if (astOutput != null) write(astOutput, JSON.toJson(ast).getBytes(StandardCharsets.UTF_8));
                    if (execOutput != null) write(execOutput, JSON.toJson(compiled.toJson()).getBytes(StandardCharsets.UTF_8));
                    out.println("Compiled " + source + " -> " + output);
                }
                case "pack" -> {
                    if (args.length < 3) throw new IllegalArgumentException("pack needs an output and at least one input");
                    MakeAchieve.execute(Arrays.copyOfRange(args, 2, args.length), args[1]);
                    out.println("Packed " + args[1]);
                }
                case "unpack" -> {
                    if (args.length != 3) throw new IllegalArgumentException("unpack needs an archive and destination");
                    MakeAchieve.extract(args[1], args[2]); out.println("Extracted " + args[1] + " -> " + args[2]);
                }
                default -> throw new IllegalArgumentException("Unknown command: " + args[0] + "; use --help");
            }
            return 0;
        } catch (CompileException e) {
            report(e, err);
            if (debug && e.getCause() != null && !Diagnostics.userError(e.getCause())) e.getCause().printStackTrace(err);
            return e.internal() ? 2 : 1;
        } catch (IOException | IllegalArgumentException | JsonParseException e) {
            // The compiler classifies its own exceptions, so these come from arguments, files, pack or unpack.
            err.println("Error: " + e.getMessage()); return 1;
        } catch (RuntimeException | StackOverflowError e) {
            return internal(e, debug, err);
        }
    }
    /** An exception escaping the compiler's own classification, e.g. while writing outputs. */
    private static int internal(Throwable error, boolean debug, PrintStream err) {
        report(new CompileException(Diagnostic.internalError(null, null, error, List.of()), error), err);
        if (debug) error.printStackTrace(err);
        return 2;
    }
    /**
     * Prints every diagnostic of a failed compilation, then a summary line:
     * {@code Error: compilation failed: N error(s)[, M warning(s)]}, or
     * {@code Error: compilation failed: internal compiler error}.
     */
    public static void report(CompileException failure, PrintStream err) {
        for (Diagnostic diagnostic : failure.diagnostics()) err.println(diagnostic.format());
        long errors = failure.diagnostics().stream().filter(Diagnostic::isError).count();
        long warnings = failure.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        err.println("Error: compilation failed: " + (failure.internal() ? "internal compiler error"
                : count(errors, "error") + (warnings == 0 ? "" : ", " + count(warnings, "warning"))));
    }
    private static String count(long value, String noun) { return value + " " + noun + (value == 1 ? "" : "s"); }
    /** Reads a compile-json input; malformed JSON is reported at its line and column. */
    private static com.google.gson.JsonObject readTree(Path source, String label) throws IOException {
        String text;
        try { text = Files.readString(source, StandardCharsets.UTF_8); }
        catch (CharacterCodingException e) { throw fileError(label, "Source file \"" + label + "\" is not valid UTF-8"); }
        try { TreeLimits.validateJsonText(text); }
        catch (TreeLimits.LimitExceeded e) {
            throw new CompileException(Diagnostic.error(new Diagnostic.Position(label, e.line(), e.column()), e.getMessage()));
        }
        JsonElement tree;
        try { tree = JsonParser.parseString(text); }
        catch (JsonParseException e) {
            Throwable detail = e.getCause() != null ? e.getCause() : e;
            String message = detail.getMessage() == null ? "" : detail.getMessage().lines().findFirst().orElse("");
            Matcher matcher = GSON_POSITION.matcher(message);
            if (!matcher.matches()) throw fileError(label, "Invalid JSON: " + (message.isEmpty() ? "malformed JSON" : message));
            String reason = matcher.group(1).startsWith("Use JsonReader.setStrictness") ? "malformed JSON" : matcher.group(1);
            throw new CompileException(Diagnostic.error(new Diagnostic.Position(label, Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))), "Invalid JSON: " + reason), e);
        }
        if (!tree.isJsonObject()) throw fileError(label, "Script JSON root must be an object");
        return tree.getAsJsonObject();
    }
    private static CompileException fileError(String label, String message) {
        return new CompileException(Diagnostic.error(Diagnostic.Position.file(label), message));
    }
    /** Whether writing {@code target} would overwrite the input file {@code input}. */
    private static boolean aliases(Path target, Path input) throws IOException {
        return target.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                || Files.exists(target) && Files.isSameFile(input, target);
    }
    private static void write(Path target, byte[] bytes) throws IOException {
        Path path = target.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".azscript-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
