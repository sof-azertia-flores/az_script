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
            "-o", "-o", "--output", "-o", "--ast", "--ast", "--exec-json", "--exec-json", "--max-errors", "--max-errors");
    /** {@code --debug-compiler} prints the stack trace of an internal compiler error. */
    private static final Set<String> FLAG_OPTIONS = Set.of("--debug-compiler");
    /** Warning options other than {@code -W<name>} and {@code -Wno-<name>}. */
    private static final Set<String> WARNING_FLAGS = Set.of("-w", "-Wall", "-Werror");
    private static final Pattern GSON_POSITION = Pattern.compile("(?s)^(?:[\\w.$]+(?:Exception|Error): )?(.*?) at line (\\d+) column (\\d+)(?: path .*)?$");

    /**
     * Parsed {@code compile}/{@code compile-json} arguments. Options may appear before
     * or after the input path, and {@code --} ends them so a path may begin with '-';
     * unset outputs are null, {@code flags} holds the given flags, {@code maxErrors}
     * is the error limit ({@link Diagnostics#DEFAULT_MAX_ERRORS} unless given, 0 for none)
     * and {@code warnings} lists the warning options in order.
     */
    public record CompileArguments(String command, Path source, Path output, Path astOutput, Path execOutput, Set<String> flags,
                                   int maxErrors, List<String> warnings) {
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
                values.put(option, args.get(++i));
            } else if (FLAG_OPTIONS.contains(argument)) {
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
        int maxErrors = Diagnostics.DEFAULT_MAX_ERRORS;
        if (values.containsKey("--max-errors")) {
            String limit = values.get("--max-errors");
            try { maxErrors = limit.matches("[0-9]+") ? Integer.parseInt(limit) : -1; }
            catch (NumberFormatException e) { maxErrors = -1; }
            if (maxErrors < 0) throw new IllegalArgumentException("--max-errors expects a non-negative integer, got " + limit);
        }
        return new CompileArguments(args.get(0), source, path(values.get("-o")), path(values.get("--ast")), path(values.get("--exec-json")),
                Set.copyOf(flags), maxErrors, List.copyOf(warnings));
    }
    /** A command-line path; one the platform cannot represent, e.g. with a NUL character, is a usage error. */
    private static Path path(String value) {
        if (value == null) return null;
        try { return Path.of(value); }
        catch (InvalidPathException e) { throw new IllegalArgumentException("Invalid path: " + e.getMessage()); }
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        boolean debug = false;
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                StringBuilder warnings = new StringBuilder();
                for (Diagnostics.Warning warning : Diagnostics.WARNINGS)
                    warnings.append(String.format("\n  %-20s %s (%s)", warning.name(), warning.description(), warning.enabledByDefault() ? "on" : "off"));
                out.println("AzScript compiler\n  compile <source.azs> [-o output.abd] [--ast tree.json] [--exec-json instructions.json]\n  compile-json <tree.json> [-o output.abd] [--exec-json instructions.json]\n  pack <output.abd> <file-or-directory>...\n  unpack <archive.abd> <new-or-empty-directory>\nCompile options:\n  --max-errors N     stop after N errors (default " + Diagnostics.DEFAULT_MAX_ERRORS + ", 0 for no limit)\n  --debug-compiler   print the stack trace of an internal compiler error\n  -Wall              enable every warning\n  -W<name>           enable one warning; -Wno-<name> disables it, also after -Wall\n  -w                 suppress all warnings\n  -Werror            report warnings as errors\nWarnings (default state):" + warnings + "\nOptions may precede or follow the input path; -- ends the options. Diagnostics are written to stderr as\nfile:line:column: error: message, or warning: message [-W<name>]. The exit status is 1 when compilation fails\nand 2 for an internal compiler error; warnings alone do not fail it.");
                return 0;
            }
            switch (args[0]) {
                case "compile", "compile-json" -> {
                    CompileArguments arguments = parseCompileArguments(Arrays.asList(args));
                    Path source = arguments.source(), output = arguments.outputOrDefault();
                    Path astOutput = arguments.astOutput(), execOutput = arguments.execOutput();
                    debug = arguments.flags().contains("--debug-compiler");
                    com.google.gson.JsonObject ast;
                    // One collector serves the parse and the compilation, which marks the AST as the parser's own.
                    Diagnostics diagnostics = arguments.diagnostics();
                    if (args[0].equals("compile-json")) {
                        String label = source.getFileName() == null ? source.toString() : source.getFileName().toString();
                        diagnostics.defaultFile(label);
                        ast = readTree(source, label);
                    } else ast = parse(source, diagnostics).toObj();
                    AcsObject compiled = Compiler.compile(ast, diagnostics);
                    // A successful compilation may still report warnings.
                    for (Diagnostic warning : diagnostics.warnings()) err.println(warning.format());
                    Path normalizedSource = source.toAbsolutePath().normalize();
                    var outputs = new java.util.HashSet<Path>();
                    for (Path target : new Path[]{output, astOutput, execOutput}) {
                        if (target == null) continue;
                        Path normalized = target.toAbsolutePath().normalize();
                        if (normalized.equals(normalizedSource) || (Files.exists(target) && Files.isSameFile(source, target)))
                            throw new IllegalArgumentException("Output must not overwrite the source: " + target);
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
    private static GeneraterJson.AzScript parse(Path source, Diagnostics diagnostics) throws IOException {
        var script = new GeneraterJson.AzScript(); script.execute(source, diagnostics); return script;
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
