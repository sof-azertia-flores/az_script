package azertia.distribution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Distribution-only defaults; the compiler's public CLI and API stay unchanged. */
public final class Compile {
    private static final Set<String> COMMANDS = Set.of(
            "compile", "compile-json", "pack", "unpack", "help", "--help");

    private Compile() {}

    public static void main(String[] args) {
        int status;
        try {
            String[] forwarded = arguments(args);
            if (forwarded.length == 0 || forwarded[0].equals("--help")
                    || forwarded[0].equals("help")) {
                System.out.println("Distribution shortcut: compile.sh <source.azs> [options]");
                System.out.println("Source compilation also writes .ast.json and .exec.json by default.");
                System.out.println("Explicit --ast and --exec-json override those output paths.\n");
            }
            status = azertia.Main.run(forwarded, System.out, System.err);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            status = 1;
        }
        if (status != 0) System.exit(status);
    }

    private static String[] arguments(String[] original) {
        List<String> args = new ArrayList<>(Arrays.asList(original));
        if (args.isEmpty()) return original;
        if (args.get(0).equals("-h")) args.set(0, "--help");
        if (!COMMANDS.contains(args.get(0))) args.add(0, "compile");
        String command = args.get(0);
        if ((!command.equals("compile") && !command.equals("compile-json")) || args.size() < 2)
            return args.toArray(String[]::new);

        // The compiler's own parser accepts options anywhere and reports malformed ones,
        // so no default can become a missing option's value.
        azertia.Main.CompileArguments parsed = azertia.Main.parseCompileArguments(args);
        Path source = parsed.source();
        if (source.getFileName() == null)
            throw new IllegalArgumentException("Input path must name a file: " + source);
        Path output = parsed.outputOrDefault();
        boolean explicitAst = parsed.astOutput() != null;
        boolean explicitExec = parsed.execOutput() != null;

        if (output.getFileName() == null)
            throw new IllegalArgumentException("Output path must name a file: " + output);
        String name = output.getFileName().toString();
        String stem = name.endsWith(".exec.abd") ? name.substring(0, name.length() - 9)
                : name.endsWith(".abd") ? name.substring(0, name.length() - 4) : name;
        // Defaults go right after the command, ahead of any "--" that ends the options.
        List<String> defaults = new ArrayList<>();
        if (command.equals("compile") && !explicitAst) {
            defaults.add("--ast");
            defaults.add(output.resolveSibling(stem + ".ast.json").toString());
        }
        // An AST input is never rewritten unless --ast explicitly requests it;
        // the compiler itself rejects any output which aliases its input.
        if (!explicitExec) {
            defaults.add("--exec-json");
            defaults.add(output.resolveSibling(stem + ".exec.json").toString());
        }
        args.addAll(1, defaults);
        return args.toArray(String[]::new);
    }
}
