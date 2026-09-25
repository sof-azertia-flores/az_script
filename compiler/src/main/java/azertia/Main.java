package azertia;

import azertia.achieve.MakeAchieve;
import azertia.binary.complexBinary.AcsObject;
import azertia.script.Compiler;
import azertia.script.GeneraterJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Command line entry point; library callers can keep using AzScript and Compiler directly. */
public class Main {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                out.println("AzScript compiler\n  compile <source.azs> [-o output.abd] [--ast tree.json] [--exec-json instructions.json]\n  compile-json <tree.json> [-o output.abd] [--exec-json instructions.json]\n  pack <output.abd> <file-or-directory>...\n  unpack <archive.abd> <new-or-empty-directory>");
                return 0;
            }
            switch (args[0]) {
                case "compile", "compile-json" -> {
                    if (args.length < 2) throw new IllegalArgumentException("Missing input path");
                    Path source = Path.of(args[1]);
                    Path output = source.resolveSibling(source.getFileName().toString().replaceFirst("\\.[^.]+$", "") + ".exec.abd");
                    Path astOutput = null, execOutput = null;
                    for (int i = 2; i < args.length; i++) {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
                        switch (args[i]) {
                            case "-o", "--output" -> output = Path.of(args[++i]);
                            case "--ast" -> astOutput = Path.of(args[++i]);
                            case "--exec-json" -> execOutput = Path.of(args[++i]);
                            default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                        }
                    }
                    com.google.gson.JsonObject ast;
                    if (args[0].equals("compile-json")) {
                        String text = Files.readString(source, StandardCharsets.UTF_8);
                        azertia.script.TreeLimits.validateJsonText(text);
                        var tree = JsonParser.parseString(text);
                        if (!tree.isJsonObject()) throw new IllegalArgumentException("Script JSON root must be an object");
                        ast = tree.getAsJsonObject();
                    } else ast = parse(source).toObj();
                    AcsObject compiled = Compiler.compile(ast);
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
        } catch (IOException | IllegalArgumentException | com.google.gson.JsonParseException e) {
            err.println("Error: " + e.getMessage()); return 1;
        }
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
