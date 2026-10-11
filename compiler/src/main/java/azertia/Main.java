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
            "-o", "-o", "--output", "-o", "--ast", "--ast", "--exec-json", "--exec-json");
    private static final Set<String> FLAG_OPTIONS = Set.of();
    private static final Pattern GSON_POSITION = Pattern.compile("(?s)^(?:[\\w.$]+(?:Exception|Error): )?(.*?) at line (\\d+) column (\\d+)(?: path .*)?$");

    /**
     * Parsed {@code compile}/{@code compile-json} arguments. Options may appear before
     * or after the input path, and {@code --} ends them so a path may begin with '-';
     * unset outputs are null and {@code flags} holds the given flags.
     */
    public record CompileArguments(String command, Path source, Path output, Path astOutput, Path execOutput, Set<String> flags) {
        /** The ABD output: {@code -o}, or the source name with {@code .exec.abd} beside it. */
        public Path outputOrDefault() {
            if (output != null) return output;
            if (source.getFileName() == null) throw new IllegalArgumentException("Input path must name a file: " + source);
            return source.resolveSibling(source.getFileName().toString().replaceFirst("\\.[^.]+$", "") + ".exec.abd");
        }
    }
    /** Parses {@code compile} or {@code compile-json} followed by its operands and options. */
    public static CompileArguments parseCompileArguments(List<String> args) {
        if (args.isEmpty() || !args.get(0).equals("compile") && !args.get(0).equals("compile-json"))
            throw new IllegalArgumentException("Expected compile or compile-json");
        Path source = null;
        Map<String,Path> values = new HashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        boolean operands = false;
        for (int i = 1; i < args.size(); i++) {
            String argument = args.get(i), option = VALUE_OPTIONS.get(argument);
            if (operands) {
                if (source != null) throw new IllegalArgumentException("Unexpected argument: " + argument);
                source = Path.of(argument);
            } else if (argument.equals("--")) {
                operands = true;
            } else if (option != null) {
                if (i + 1 >= args.size()) throw new IllegalArgumentException("Missing value for " + argument);
                values.put(option, Path.of(args.get(++i)));
            } else if (FLAG_OPTIONS.contains(argument)) {
                flags.add(argument);
            } else if (argument.startsWith("-") && argument.length() > 1) {
                throw new IllegalArgumentException("Unknown option: " + argument);
            } else if (source == null) source = Path.of(argument);
            else throw new IllegalArgumentException("Unexpected argument: " + argument);
        }
        if (source == null) throw new IllegalArgumentException("Missing input path");
        return new CompileArguments(args.get(0), source, values.get("-o"), values.get("--ast"), values.get("--exec-json"), Set.copyOf(flags));
    }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                out.println("AzScript compiler\n  compile <source.azs> [-o output.abd] [--ast tree.json] [--exec-json instructions.json]\n  compile-json <tree.json> [-o output.abd] [--exec-json instructions.json]\n  pack <output.abd> <file-or-directory>...\n  unpack <archive.abd> <new-or-empty-directory>\nOptions may precede or follow the input path; -- ends the options. Diagnostics are written to stderr as\nfile:line:column: error: message; the exit status is 1 when compilation fails.");
                return 0;
            }
            switch (args[0]) {
                case "compile", "compile-json" -> {
                    CompileArguments arguments = parseCompileArguments(Arrays.asList(args));
                    Path source = arguments.source(), output = arguments.outputOrDefault();
                    Path astOutput = arguments.astOutput(), execOutput = arguments.execOutput();
                    com.google.gson.JsonObject ast;
                    Diagnostics diagnostics = new Diagnostics();
                    if (args[0].equals("compile-json")) {
                        String label = source.getFileName() == null ? source.toString() : source.getFileName().toString();
                        diagnostics.defaultFile(label);
                        ast = readTree(source, label);
                    } else ast = parse(source).toObj();
                    AcsObject compiled = Compiler.compile(ast, diagnostics);
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
            report(e, err); return 1;
        } catch (IOException | IllegalArgumentException | JsonParseException e) {
            err.println("Error: " + e.getMessage()); return 1;
        }
    }
    /** Prints every diagnostic of a failed compilation, then a summary line. */
    public static void report(CompileException failure, PrintStream err) {
        for (Diagnostic diagnostic : failure.diagnostics()) err.println(diagnostic.format());
        long errors = failure.diagnostics().stream().filter(Diagnostic::isError).count();
        long warnings = failure.diagnostics().stream().filter(d -> d.severity() == Diagnostic.Severity.WARNING).count();
        err.println("Error: compilation failed: " + count(errors, "error") + (warnings == 0 ? "" : ", " + count(warnings, "warning")));
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
    private static GeneraterJson.AzScript parse(Path source) throws IOException {
        var script = new GeneraterJson.AzScript(); script.execute(source); return script;
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
