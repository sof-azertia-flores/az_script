package azertia.script;

import com.google.gson.*;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Token based AzScript front end. The historical public class name is retained. */
public class GeneraterJson {
    private static final int MAX_MACRO_EXPANSION_CHARS = 1_048_576;
    private static final Set<String> TYPES = SourceRules.TYPES;
    /** Statement keywords and {@code any}; they can never name a function, parameter, variable, global or macro. */
    private static final Set<String> KEYWORDS = SourceRules.KEYWORDS;
    private static final Set<String> PREPROCESSOR_DIRECTIVES = Set.of(
            "ifdef", "ifndef", "if_equals", "else", "fi", "endif", "include", "author", "gvar",
            "namespace", "namespace_hint", "assume_hint", "setmeta", "setattr", "define", "undef", "pragma");
    public static final Map<String, Integer> specialNames = Map.of(
            "__script_onload", 0, "main", 0x0fff0000, "__script_pre_destroy", 1);

    public interface AzExpression { JsonElement generate(); }
    public static class AzFunction {
        public List<String> params = new ArrayList<>();
        public List<String> paramTypes = new ArrayList<>();
        public AzExpression body;
        public String returnType, name;
        public String ownerClass, functionKind, operatorKind;
        public JsonArray typeParameters = new JsonArray();
        public int id;
        public Integer explicitPosition;
        public JsonArray baseArguments;
        public String baseInitializer;
        /** Position of the parameter list: source unit (index into {@code sources}), line and column. */
        public int file, line, column;
        /** Position of the closing brace, or 0 when unknown or in another unit. */
        public int endLine, endColumn;
    }
    public static class ExternSignature {
        public String returnType;
        public List<String> paramTypes = new ArrayList<>();
        public String ownerClass, functionKind, operatorKind;
        public JsonArray typeParameters = new JsonArray();
        public int id;
        /** Position of the declaration's parameter list, or line 0 when unknown. */
        public int file, line, column;

        ExternSignature(String returnType, List<String> paramTypes) {
            this.returnType = returnType;
            this.paramTypes.addAll(paramTypes);
        }
    }
    public static class AzScript {
        public Map<String,Integer> name2Id = new LinkedHashMap<>();
        public Map<String,String> tmpoVars = new LinkedHashMap<>();
        public Map<String,String> exts = new LinkedHashMap<>();
        public String author = "";
        public int functionNamespace = SourceRules.DEFAULT_NAMESPACE;
        public String namespaceHint = "";
        public Map<String,Integer> assumeHints = new LinkedHashMap<>();
        private boolean namespaceDeclared;
        public List<String> globalVariables = new ArrayList<>();
        /**
         * Typed global declarations {@code T g;} or {@code T g = e;} in source order, as AST
         * entries {name, type, initializer?, positions}; they follow the untyped #gvar names.
         */
        public List<JsonObject> typedGlobals = new ArrayList<>();
        public List<AzFunction> functions = new ArrayList<>();
        public JsonArray classes = new JsonArray();
        public Map<String,ExternSignature> externSignatures = new LinkedHashMap<>();
        /**
         * Directories searched for an include after the including file's directory, in
         * order, like {@code -I}. With the main file's directory, or the base directory of
         * a string source, they are the include roots: unless {@link #unrestrictedIncludes}
         * is set, every include must lie inside one of them. Kept across executions.
         */
        public final List<Path> includeDirectories = new ArrayList<>();
        /** Allows absolute includes and includes outside the include roots, like {@code --unrestricted-includes}. Kept across executions. */
        public boolean unrestrictedIncludes;
        private final Map<String,Macro> macros = new LinkedHashMap<>();
        /** Original locations of the program; the readable AST stores its units as {@code sources}. */
        private SourceMap sourceMap = SourceMap.single("<source>");
        /** Real include roots: the main or base directory first, then the distinct include directories. */
        private List<Path> includeRoots = List.of();
        /** Real include directories, searched after the including file's directory. */
        private List<Path> searchDirectories = List.of();
        /**
         * Absolute spellings of include directories given through a symbolic link: an
         * include that reaches one by that spelling is inside the roots too.
         */
        private List<Path> rootAliases = List.of();
        /** The label of every source file of this execution, by real path, and the file of every label. */
        private final Map<Path,String> fileLabels = new HashMap<>();
        private final Map<String,Path> labelFiles = new HashMap<>();
        /** Real paths of the included files, in the order first included. */
        private final Set<Path> includedFiles = new LinkedHashSet<>();
        /** Locations {unit, line, column} of #namespace/#namespace_hint, #assume_hint and #gvar directives. */
        private int[] namespaceDirective;
        private final Map<String,int[]> assumeDirectives = new LinkedHashMap<>(), globalDirectives = new LinkedHashMap<>();
        /** The collector of the running execution. */
        private Diagnostics diagnostics = Diagnostics.failFast();
        /** The running parser, which locates an internal compiler error at its current token. */
        private Parser parser;
        /** Text of every source file read by this compilation, by real path; a repeated include reuses it. */
        private final Map<Path,String> sourceTexts = new HashMap<>();
        /** The error of every source file that was read but is not valid UTF-8, by real path. */
        private final Map<Path,String> sourceFailures = new HashMap<>();
        /** Real paths of the files that ran {@code #pragma once}; a later include of one expands to nothing. */
        private final Set<Path> onceFiles = new HashSet<>();
        private int includeExpansions;
        private long sourceBytes;

        /** Preprocesses a string source relative to the working directory and returns the combined text. */
        public String toPureSource(String src) throws IOException {
            reset(); resolveIncludeDirectories(); begin(new Diagnostics());
            Path base = baseDirectory(null);
            String[] text = new String[1];
            run(() -> text[0] = preprocessMain(src, "<source>", base, null));
            return text[0];
        }
        /**
         * Parses a string source; includes resolve relative to the working directory, which
         * is also an include root. Embedders compiling untrusted text should pass their own
         * base directory to {@link #execute(String, Path)} instead.
         */
        public void execute(String src) throws IOException { execute(src, new Diagnostics()); }
        /**
         * Parses a string source, reporting into {@code diagnostics}, which may then
         * compile the AST with {@link Compiler#compile(JsonObject, Diagnostics)}.
         */
        public void execute(String src, Diagnostics diagnostics) throws IOException { execute(src, null, diagnostics); }
        /** Parses a string source whose includes resolve relative to {@code baseDirectory}, which is also an include root. */
        public void execute(String src, Path baseDirectory) throws IOException { execute(src, baseDirectory, new Diagnostics()); }
        /**
         * Parses a string source whose includes resolve relative to {@code baseDirectory}, or
         * the working directory when it is null, reporting into {@code diagnostics}.
         */
        public void execute(String src, Path baseDirectory, Diagnostics diagnostics) throws IOException {
            reset(); resolveIncludeDirectories();
            Path base = baseDirectory(baseDirectory);
            begin(diagnostics);
            run(() -> parse(preprocessMain(src, "<source>", base, null)));
        }
        /**
         * Parses a source file; includes resolve relative to the including file, then to
         * each of {@link #includeDirectories}.
         */
        public void execute(Path source) throws IOException { execute(source, new Diagnostics()); }
        /**
         * Parses a source file, reporting into {@code diagnostics}. Independent errors are
         * reported together, up to {@link Diagnostics#maxErrors()}, in one {@link CompileException}.
         */
        public void execute(Path source, Diagnostics diagnostics) throws IOException {
            reset(); resolveIncludeDirectories(); begin(diagnostics);
            Path real;
            try { real = source.toRealPath(); }
            catch (IOException e) {
                String name = sourceName(source, null);
                throw fileError(name, "Cannot open source file \"" + name + "\": " + reason(e));
            }
            String name = sourceName(source, real);
            String text;
            try { text = readSource(real, name); }
            catch (IOException e) { throw fileError(name, "Cannot open source file \"" + name + "\": " + reason(e)); }
            catch (IllegalArgumentException e) {
                if (!(e instanceof LimitError) && !Diagnostics.userError(e)) throw e;
                throw fileError(name, e.getMessage());
            }
            run(() -> parse(preprocessMain(text, name, real.getParent(), real)));
        }
        /**
         * The real paths of the files the last execution included, in the order first
         * included, so a caller can keep its outputs from overwriting them.
         */
        public List<Path> includedFiles() { return List.copyOf(includedFiles); }
        /**
         * Resolves {@link #includeDirectories} to distinct real directories, with the aliases
         * of those given through a symbolic link; a missing or empty one is a usage error.
         */
        private void resolveIncludeDirectories() {
            List<Path> directories = new ArrayList<>(), aliases = new ArrayList<>();
            for (Path directory : includeDirectories) {
                // An empty path would silently stand for the working directory.
                if (directory.toString().isEmpty()) throw new IllegalArgumentException("Empty include directory; use \".\" for the working directory");
                String shown = AstShape.printable(directory.toString());
                Path real;
                try { real = directory.toRealPath(); }
                catch (NoSuchFileException e) { throw new IllegalArgumentException("Include directory not found: " + shown); }
                catch (IOException e) { throw new IllegalArgumentException("Cannot open include directory " + shown + ": " + reason(e)); }
                if (!Files.isDirectory(real)) throw new IllegalArgumentException("Include directory is not a directory: " + shown);
                if (!directories.contains(real)) directories.add(real);
                Path lexical = directory.toAbsolutePath().normalize();
                if (!lexical.equals(real) && !aliases.contains(lexical) && real.equals(realPath(lexical))) aliases.add(lexical);
            }
            searchDirectories = List.copyOf(directories); rootAliases = List.copyOf(aliases);
        }
        /** The real path of {@code path}, or null when it cannot be resolved. */
        private static Path realPath(Path path) {
            try { return path.toRealPath(); } catch (IOException e) { return null; }
        }
        /**
         * The real base directory of a string source; null means the working directory,
         * which falls back to its absolute path when it cannot be resolved.
         */
        private static Path baseDirectory(Path base) {
            if (base == null) {
                Path working = Path.of(".").toAbsolutePath().normalize();
                try { return working.toRealPath(); } catch (IOException e) { return working; }
            }
            String shown = AstShape.printable(base.toString());
            Path real;
            try { real = base.toRealPath(); }
            catch (NoSuchFileException e) { throw new IllegalArgumentException("Include base directory not found: " + shown); }
            catch (IOException e) { throw new IllegalArgumentException("Cannot open include base directory " + shown + ": " + reason(e)); }
            if (!Files.isDirectory(real)) throw new IllegalArgumentException("Include base directory is not a directory: " + shown);
            return real;
        }
        /** The diagnostic name of a main source file: the name it was given, else that of its real path. */
        private static String sourceName(Path source, Path real) {
            Path typedName = source.getFileName();
            Path realName = real == null ? null : real.getFileName();
            String name = typedName != null && !typedName.toString().isEmpty() ? typedName.toString()
                    : realName != null ? realName.toString() : source.toString();
            if (AstShape.sourceNameProblem(name) != null)
                throw new IllegalArgumentException("Invalid source file name: " + AstShape.sourceNameProblem(name));
            return name;
        }
        private CompileException fileError(String name, String message) {
            return diagnostics.record(new CompileException(Diagnostic.error(Diagnostic.Position.file(name), message)));
        }
        private void begin(Diagnostics collector) {
            collector.startParse(); diagnostics = collector;
        }
        private interface Phase { void run() throws IOException; }
        /**
         * Runs preprocessing and parsing. A deliberate error is located; any other
         * exception is an internal compiler error at the token being parsed.
         */
        private void run(Phase phase) throws IOException {
            try {
                phase.run(); diagnostics.checkpoint(); diagnostics.parsed();
            } catch (CompileException e) {
                throw diagnostics.record(e);
            } catch (RuntimeException | StackOverflowError e) {
                Diagnostic located;
                Token at = parser == null ? null : parser.current();
                if (Diagnostics.userError(e)) located = at == null ? Diagnostic.error(Diagnostic.Position.file(sourceMap.name(0)), e.getMessage())
                        : sourceMap.error(at.unit(), at.line(), at.column(), at.macro(), null, e.getMessage());
                else located = at == null ? Diagnostic.internalError(Diagnostic.Position.file(sourceMap.name(0)), null, e, List.of())
                        : Diagnostic.internalError(sourceMap.position(at.unit(), at.line(), at.column()), null, e, sourceMap.includeNotes(at.unit())).inUnit(at.unit());
                throw diagnostics.record(new CompileException(List.of(located), e, true));
            } finally { parser = null; }
        }
        private void reset() {
            name2Id.clear(); exts.clear(); globalVariables.clear(); typedGlobals.clear(); functions.clear();
            externSignatures.clear(); tmpoVars.clear(); macros.clear();
            classes = new JsonArray();
            author = ""; functionNamespace = SourceRules.DEFAULT_NAMESPACE;
            namespaceHint = ""; namespaceDeclared = false; assumeHints.clear();
            sourceMap = SourceMap.single("<source>");
            includeRoots = List.of(); searchDirectories = List.of(); rootAliases = List.of();
            fileLabels.clear(); labelFiles.clear(); includedFiles.clear();
            namespaceDirective = null; assumeDirectives.clear(); globalDirectives.clear();
            sourceTexts.clear(); sourceFailures.clear(); onceFiles.clear(); includeExpansions = 0; sourceBytes = 0;
        }
        private void parse(String source) {
            diagnostics.sources(sourceMap);
            diagnostics.checkpoint(); // preprocessing
            parser = new Parser(source, sourceMap, this, diagnostics); parser.program();
        }
        private record Conditional(boolean parent, boolean condition, boolean inElse, int line, int column) {
            boolean active() { return parent && (inElse ? !condition : condition); }
        }
        /** An object-like macro with the location of its #define directive. */
        private record Macro(String value, int reference) {}
        /** Preprocesses the main text; {@code base} is the real directory of {@code real}, or of a string source. */
        private String preprocessMain(String src, String name, Path base, Path real) throws IOException {
            sourceMap = new SourceMap(); int unit = sourceMap.addUnit(name, -1, 0, 0); diagnostics.sources(sourceMap);
            List<Path> roots = new ArrayList<>(List.of(base));
            for (Path directory : searchDirectories) if (!roots.contains(directory)) roots.add(directory);
            includeRoots = List.copyOf(roots);
            Map<Path,String> active = new LinkedHashMap<>();
            if (real != null) { active.put(real, name); fileLabels.put(real, name); }
            // A string source reserves its name with its base directory, which no included file is.
            labelFiles.put(name, real != null ? real : base);
            StringBuilder out = new StringBuilder();
            try { preprocess(src, real, base, unit, active, 0, out); }
            finally { sourceTexts.clear(); sourceFailures.clear(); }
            return out.toString();
        }
        private CompileException located(int unit, int line, int column, String message) {
            return new CompileException(sourceMap.error(unit, line, column, -1, null, message));
        }
        /** A preprocessing limit; it ends the compilation instead of skipping one directive or line. */
        private static final class LimitError extends IllegalArgumentException {
            private static final long serialVersionUID = 1L;
            LimitError(String message) { super(message); }
        }
        /**
         * Appends exactly one output line per input line to {@code out}, recording each in the
         * source map; an included file's lines precede the #include line itself. A failing
         * directive or line is reported and skipped; a failing #ifdef, #ifndef or #if_equals
         * opens an inactive branch, so its #else and #endif still match. {@code file} is the
         * real path of the text, or null for a string source.
         */
        private void preprocess(String src, Path file, Path base, int unit, Map<Path,String> includes, int depth,
                                StringBuilder out) throws IOException {
            // A UTF-8 byte order mark must not hide a directive on the first line.
            if (src.startsWith("﻿")) src = src.substring(1);
            Deque<Conditional> stack = new ArrayDeque<>();
            boolean blockComment = false;
            int lineNo = 0, commentLine = 0, commentColumn = 0;
            for (String line : lines(src)) {
                lineNo++;
                // Comments remain lexical even inside an excluded conditional branch.
                Stripped cleaned = stripComments(line, blockComment);
                if (cleaned.openColumn() > 0) { commentLine = lineNo; commentColumn = cleaned.openColumn(); }
                blockComment = cleaned.blockComment();
                String content = cleaned.text();
                String trimmed = content.stripLeading();
                boolean active = stack.isEmpty() || stack.peek().active();
                if (trimmed.startsWith("#")) {
                    int hash = content.length() - trimmed.length() + 1;
                    String[] parts = trimmed.substring(1).trim().split("\\s+", 2);
                    String command = parts[0].toLowerCase(Locale.ROOT);
                    String arg = parts.length > 1 ? parts[1].trim() : "";
                    int[] directive = {unit, lineNo, hash};
                    int depthBefore = stack.size();
                    try {
                        if (!PREPROCESSOR_DIRECTIVES.contains(command))
                            throw new IllegalArgumentException("Unknown preprocessor directive: #" + command);
                        switch (command) {
                            case "ifdef", "ifndef", "if_equals" -> {
                                String[] args = arg.split("\\s+", 2);
                                if (args[0].isBlank() || (command.equals("if_equals") && args.length != 2))
                                    throw new IllegalArgumentException("Missing condition argument");
                                boolean defined = tmpoVars.containsKey(args[0]) || macros.containsKey(args[0]);
                                boolean condition = command.equals("ifndef") ? !defined : defined;
                                if (command.equals("if_equals")) condition = defined && args[1].equals(
                                        tmpoVars.containsKey(args[0]) ? tmpoVars.get(args[0]) : macros.get(args[0]).value());
                                stack.push(new Conditional(active, condition, false, lineNo, hash));
                            }
                            case "else" -> {
                                if (stack.isEmpty() || stack.peek().inElse())
                                    throw new IllegalArgumentException("Unmatched or repeated #else");
                                Conditional previous = stack.pop();
                                stack.push(new Conditional(previous.parent(), previous.condition(), true, previous.line(), previous.column()));
                            }
                            case "fi", "endif" -> {
                                if (stack.isEmpty()) throw new IllegalArgumentException("Unmatched #" + command);
                                stack.pop();
                            }
                            default -> {
                                if (!active) break;
                                switch (command) {
                                    case "include" -> include(arg, base, unit, lineNo, hash, includes, depth, out);
                                    case "pragma" -> {
                                        String[] args = arg.split("\\s+", 2);
                                        if (args[0].isEmpty()) throw new IllegalArgumentException("Missing pragma name; only #pragma once is supported");
                                        if (!args[0].equals("once"))
                                            throw new IllegalArgumentException("Unknown pragma: " + AstShape.printable(args[0]) + "; only #pragma once is supported");
                                        if (args.length > 1) throw new IllegalArgumentException("Unexpected text after #pragma once");
                                        // A string source cannot be included, so only files are recorded.
                                        if (file != null) onceFiles.add(file);
                                    }
                                    case "author" -> author = arg;
                                    case "gvar" -> {
                                        identifier(arg);
                                        // globalDirectives has a key for every #gvar, so this check is a hash lookup.
                                        diagnostics.metrics().tableProbes++;
                                        if (globalDirectives.containsKey(arg)) throw new IllegalArgumentException("Duplicate global variable: " + arg);
                                        globalVariables.add(arg); globalDirectives.put(arg, directive);
                                    }
                                    case "namespace" -> {
                                        if (namespaceDeclared) throw new IllegalArgumentException("Only one #namespace or #namespace_hint is allowed");
                                        String[] args = arg.split("\\s+");
                                        if (args.length != 1 || args[0].isBlank())
                                            throw new IllegalArgumentException("Expected one hexadecimal namespace");
                                        int value = SourceRules.sourceHex(args[0], 4, "namespace");
                                        // 0fff is the default namespace, so naming it explicitly is allowed.
                                        String reserved = SourceRules.reservedModuleNamespace(value);
                                        if (reserved != null) throw new IllegalArgumentException("Namespace " + reserved);
                                        functionNamespace = value; namespaceDeclared = true; namespaceDirective = directive;
                                    }
                                    case "namespace_hint" -> {
                                        if (namespaceDeclared) throw new IllegalArgumentException("Only one #namespace or #namespace_hint is allowed");
                                        SourceRules.requireHint(arg); namespaceHint = arg; functionNamespace = 0; namespaceDeclared = true;
                                        namespaceDirective = directive;
                                    }
                                    case "assume_hint" -> {
                                        String[] args = arg.split("\\s+");
                                        if (args.length != 2) throw new IllegalArgumentException("Expected #assume_hint HINT namespace");
                                        SourceRules.requireHint(args[0]); int value = SourceRules.sourceHex(args[1], 4, "assumed namespace");
                                        if (!SourceRules.assumableNamespace(value))
                                            throw new IllegalArgumentException("Reserved assumed namespace");
                                        Integer previous = assumeHints.get(args[0]);
                                        if (previous != null && previous != value || previous == null && assumeHints.containsValue(value))
                                            throw new IllegalArgumentException("Conflicting hint or assumed namespace");
                                        assumeHints.put(args[0], value); assumeDirectives.putIfAbsent(args[0], directive);
                                    }
                                    case "setmeta", "setattr", "define" -> {
                                        String[] args = arg.split("\\s+", 2);
                                        if (args[0].isBlank() || (args.length != 2 && !command.equals("define")))
                                            throw new IllegalArgumentException("Expected two arguments for #" + command);
                                        String value = args.length > 1 ? args[1] : "1";
                                        switch (command) {
                                            case "setmeta" -> exts.put(args[0], value);
                                            case "setattr" -> tmpoVars.put(args[0], value);
                                            case "define" -> {
                                                functionLikeMacro(args[0]); identifier(args[0]);
                                                macros.put(args[0], new Macro(value, sourceMap.addMacro(args[0], unit, lineNo, hash)));
                                            }
                                        }
                                    }
                                    case "undef" -> { identifier(arg); macros.remove(arg); tmpoVars.remove(arg); }
                                    default -> throw new IllegalArgumentException(
                                            "Unknown preprocessor directive: #" + command);
                                }
                            }
                        }
                    } catch (CompileException e) {
                        // An included file reports its own errors; only a fatal one reaches here.
                        diagnostics.report(e);
                    } catch (IllegalArgumentException e) {
                        if (!(e instanceof LimitError) && !Diagnostics.userError(e)) throw e;
                        boolean opening = command.equals("ifdef") || command.equals("ifndef") || command.equals("if_equals");
                        if (opening && stack.size() == depthBefore) stack.push(new Conditional(false, false, false, lineNo, hash));
                        CompileException error = located(unit, lineNo, hash, e.getMessage());
                        diagnostics.report(e instanceof LimitError ? error.stopping() : error);
                    }
                    emit(out, "", unit, lineNo, null);
                } else {
                    String text = ""; int[] runs = null;
                    if (active) {
                        SourceMap.Columns columns = new SourceMap.Columns();
                        try { text = expand(content, false, new HashSet<>(), 0, columns).text(); runs = columns.build(); }
                        catch (CompileException e) { diagnostics.report(e); }
                        catch (IllegalArgumentException e) {
                            if (!(e instanceof LimitError) && !Diagnostics.userError(e)) throw e;
                            // Reaching the length limit before any macro expanded means the line itself is too long.
                            String message = e instanceof LimitError && !columns.expanded()
                                    ? "Source line exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters" : e.getMessage();
                            // The failed line stays empty, keeping one output line per input line.
                            CompileException error = located(unit, lineNo, columns.errorColumn(), message);
                            diagnostics.report(e instanceof LimitError ? error.stopping() : error);
                        }
                    }
                    emit(out, text, unit, lineNo, runs);
                }
            }
            if (blockComment) diagnostics.report(located(unit, commentLine, commentColumn, "Unterminated block comment"));
            if (!stack.isEmpty()) diagnostics.report(located(unit, stack.peek().line(), stack.peek().column(), "Unclosed preprocessor condition"));
        }
        private void include(String arg, Path base, int unit, int line, int column, Map<Path,String> includes, int depth,
                             StringBuilder out) throws IOException {
            String path = includePath(arg);
            Path included = resolveInclude(path, base);
            includedFiles.add(included);
            String label = label(included);
            if (AstShape.sourceNameProblem(label) != null)
                throw new IllegalArgumentException("Invalid include file name: " + AstShape.sourceNameProblem(label));
            // Before the cycle check: a #pragma once file may include itself back, as in C.
            if (onceFiles.contains(included)) return;
            if (includes.containsKey(included)) {
                List<String> cycle = new ArrayList<>(); boolean inCycle = false;
                for (var entry : includes.entrySet()) {
                    inCycle |= entry.getKey().equals(included);
                    if (inCycle) cycle.add(entry.getValue());
                }
                cycle.add(label);
                throw new IllegalArgumentException("Include cycle: " + String.join(" -> ", cycle));
            }
            if (depth >= TreeLimits.MAX_INCLUDE_DEPTH) throw new LimitError("Include nesting exceeds " + TreeLimits.MAX_INCLUDE_DEPTH + " levels");
            if (++includeExpansions > TreeLimits.MAX_INCLUDES)
                throw new LimitError("Include count exceeds " + TreeLimits.MAX_INCLUDES + "; add #pragma once to headers that are included repeatedly");
            String text;
            try { text = readSource(included, label); }
            catch (IOException e) { throw new IllegalArgumentException("Cannot open include file \"" + AstShape.printable(path) + "\": " + reason(e)); }
            int child = sourceMap.addUnit(label, unit, line, column);
            includes.put(included, label);
            try { preprocess(text, included, included.getParent(), child, includes, depth + 1, out); }
            finally { includes.remove(included); }
        }
        /** The path of an #include: {@code "quoted"}, or the rest of the line unquoted. */
        private static String includePath(String arg) {
            String path = arg;
            if (arg.startsWith("\"")) {
                int end = arg.indexOf('"', 1);
                if (end < 0) throw new IllegalArgumentException("Unterminated include path; expected #include \"path\"");
                if (!arg.substring(end + 1).isBlank()) throw new IllegalArgumentException("Unexpected text after include path");
                path = arg.substring(1, end);
            } else if (arg.startsWith("<")) {
                throw new IllegalArgumentException("Angle-bracket includes are not supported; use #include \"path\" with -I/--include-dir");
            }
            if (path.isBlank()) throw new IllegalArgumentException("Missing include path");
            return path;
        }
        /**
         * The real path of an included file: the first regular file named {@code path} in
         * the including file's directory {@code directory}, then in each include directory.
         * Unless includes are unrestricted, an absolute path is rejected, a candidate whose
         * normalized path lies outside the include roots is never probed, and the real path,
         * with symbolic links resolved, must lie inside a root as well. {@code ..} is
         * resolved lexically, before any symbolic link.
         */
        private Path resolveInclude(String path, Path directory) {
            String quoted = "\"" + AstShape.printable(path) + "\"";
            Path relative;
            try { relative = Path.of(path); }
            catch (InvalidPathException e) { throw new IllegalArgumentException("Invalid include path: " + quoted); }
            // A root also marks drive- or root-relative Windows paths such as C:x and \x.
            boolean absolute = relative.getRoot() != null;
            if (absolute && !unrestrictedIncludes)
                throw new IllegalArgumentException("Absolute include path " + quoted + " is not allowed; add its directory with "
                        + "-I/--include-dir and include the file by a relative path, or compile with --unrestricted-includes");
            Set<Path> candidates = new LinkedHashSet<>();
            candidates.add(directory.resolve(relative).normalize());
            if (!absolute) for (Path search : searchDirectories) candidates.add(search.resolve(relative).normalize());
            List<String> searched = new ArrayList<>();
            Path irregular = null;
            int outside = 0;
            for (Path candidate : candidates) {
                // Outside the roots, not even the existence of a file is checked.
                if (!unrestrictedIncludes && !insideRoots(candidate)) { outside++; continue; }
                // Like the read, this follows links; a directory or device of the name does not end the search.
                if (Files.isRegularFile(candidate)) {
                    Path real;
                    try { real = candidate.toRealPath(); }
                    catch (IOException e) { throw cannotOpen(quoted, e); }
                    if (!unrestrictedIncludes && !insideRoots(real)) throw linkEscape(quoted);
                    return real;
                }
                if (Files.exists(candidate)) {
                    if (irregular == null) irregular = candidate;
                } else if (!Files.notExists(candidate)) {
                    // Neither present nor missing: a symbolic link loop or a directory that cannot be
                    // searched. A path through a regular file, like a dangling link, is just missing.
                    try { candidate.toRealPath(); }
                    catch (AccessDeniedException e) { throw cannotOpen(quoted, e); }
                    catch (IOException e) { if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw cannotOpen(quoted, e); }
                }
                searched.add(AstShape.printable(candidate.toString()));
            }
            if (irregular != null) {
                Path target = realPath(irregular);
                if (!unrestrictedIncludes && target != null && !insideRoots(target)) throw linkEscape(quoted);
                String label = rootLabel(irregular);
                throw new IllegalArgumentException("Source file \"" + AstShape.printable(label != null ? label : irregular.toString()) + "\" is not a regular file");
            }
            if (searched.isEmpty())
                throw new IllegalArgumentException("Include " + quoted + " escapes the allowed include roots (" + roots()
                        + "); add a directory with -I/--include-dir or compile with --unrestricted-includes");
            throw new IllegalArgumentException("Include not found: " + quoted + " (searched " + String.join(", ", searched)
                    + (outside == 0 ? "" : "; " + outside + (outside == 1 ? " candidate outside the allowed include roots was"
                    : " candidates outside the allowed include roots were") + " not searched") + ")");
        }
        private static IllegalArgumentException cannotOpen(String quoted, IOException error) {
            return new IllegalArgumentException("Cannot open include file " + quoted + ": " + reason(error));
        }
        private IllegalArgumentException linkEscape(String quoted) {
            return new IllegalArgumentException("Include " + quoted + " resolves through a symbolic link to a file outside the allowed include roots ("
                    + roots() + "); add its directory with -I/--include-dir or compile with --unrestricted-includes");
        }
        private boolean insideRoots(Path path) {
            for (Path root : includeRoots) if (path.startsWith(root)) return true;
            for (Path alias : rootAliases) if (path.startsWith(alias)) return true;
            return false;
        }
        private String roots() {
            return String.join(", ", includeRoots.stream().map(root -> AstShape.printable(root.toString())).toList());
        }
        /**
         * The diagnostic name of an included file: its path relative to the first include
         * root containing it, '/'-separated. When no root contains it, when an earlier root
         * holds another file of that name or when another file already has it, the path
         * relative to the main directory is used, and the absolute path when that one is
         * taken too. Every inclusion of a file shares its name, whatever the include order.
         */
        private String label(Path real) {
            String label = fileLabels.get(real);
            if (label != null) return label;
            int root = rootOf(real);
            label = root < 0 ? null : SourceMap.relativeName(includeRoots.get(root), real);
            if (label == null || shadowed(label, real, root) || labelTaken(label, real))
                label = SourceMap.relativeName(includeRoots.get(0), real);
            if (labelTaken(label, real)) label = SourceMap.relativeName(null, real);
            fileLabels.put(real, label); labelFiles.put(label, real);
            return label;
        }
        /** The index of the first include root containing {@code path}, or -1. */
        private int rootOf(Path path) {
            for (int i = 0; i < includeRoots.size(); i++)
                if (path.startsWith(includeRoots.get(i)) && !path.equals(includeRoots.get(i))) return i;
            return -1;
        }
        private String rootLabel(Path path) {
            int root = rootOf(path);
            return root < 0 ? null : SourceMap.relativeName(includeRoots.get(root), path);
        }
        /**
         * Whether a root before {@code root} holds a regular file named {@code label} other
         * than {@code real}: that file keeps the name, so a reader of a diagnostic finds the
         * right one. The probed paths lie inside those roots.
         */
        private boolean shadowed(String label, Path real, int root) {
            for (int i = 0; i < root; i++) {
                Path other = includeRoots.get(i).resolve(label);
                try { if (Files.isRegularFile(other) && !Files.isSameFile(other, real)) return true; }
                catch (IOException e) { return true; }
            }
            return false;
        }
        private boolean labelTaken(String label, Path real) {
            Path owner = labelFiles.get(label);
            return owner != null && !owner.equals(real);
        }
        /**
         * The text of a source file, read at most once per compilation: a regular file of at
         * most {@link TreeLimits#MAX_SOURCE_BYTES} bytes of valid UTF-8. The size is also
         * enforced while reading, so a file that grows after the check cannot exceed it.
         */
        private String readSource(Path real, String label) throws IOException {
            String cached = sourceTexts.get(real);
            if (cached != null) return cached;
            String failed = sourceFailures.get(real);
            if (failed != null) throw new IllegalArgumentException(failed);
            // Devices, FIFOs and directories are never opened: reading them could block or never end.
            BasicFileAttributes attributes = Files.readAttributes(real, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) throw new IllegalArgumentException("Source file \"" + label + "\" is not a regular file");
            String tooLarge = "Source file \"" + label + "\" exceeds " + TreeLimits.MAX_SOURCE_BYTES + " bytes";
            if (attributes.size() > TreeLimits.MAX_SOURCE_BYTES) throw new IllegalArgumentException(tooLarge);
            byte[] bytes;
            try (InputStream in = Files.newInputStream(real)) { bytes = in.readNBytes(TreeLimits.MAX_SOURCE_BYTES + 1); }
            if (bytes.length > TreeLimits.MAX_SOURCE_BYTES) throw new IllegalArgumentException(tooLarge);
            if (bytes.length > TreeLimits.MAX_TOTAL_SOURCE_BYTES - sourceBytes)
                throw new LimitError("Source files read by one compilation exceed " + TreeLimits.MAX_TOTAL_SOURCE_BYTES + " bytes");
            sourceBytes += bytes.length;
            String text;
            // A file that failed to decode is not read again by a later include.
            try { text = decode(bytes, label); }
            catch (IllegalArgumentException e) { sourceFailures.put(real, e.getMessage()); throw e; }
            sourceTexts.put(real, text);
            return text;
        }
        /** Strict UTF-8; an invalid sequence is reported at its line and column, counted like the preprocessor's lines. */
        private static String decode(byte[] bytes, String label) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer in = ByteBuffer.wrap(bytes);
            // UTF-8 never decodes to more UTF-16 characters than it has bytes.
            CharBuffer out = CharBuffer.allocate(bytes.length);
            CoderResult result = decoder.decode(in, out, true);
            if (result.isUnderflow()) result = decoder.flush(out);
            out.flip();
            if (result.isUnderflow()) return out.toString();
            if (!result.isError()) throw new IllegalStateException("UTF-8 decoding overflowed its buffer");
            // The decoded text ends right before the invalid sequence.
            int line = 1, lineStart = 0;
            for (int i = 0; i < out.limit(); ) {
                int length = lineBreak(out, i);
                if (length == 0) { i++; continue; }
                i += length; line++; lineStart = i;
            }
            int column = out.limit() - lineStart + 1;
            if (lineStart == 0 && out.limit() > 0 && out.charAt(0) == '\ufeff') column--;
            throw new IllegalArgumentException("Source file \"" + label + "\" is not valid UTF-8 (line " + line + ", column " + column + ")");
        }
        /** The length of the line break at {@code i}, matched like the regular expression {@code \R}, or 0. */
        private static int lineBreak(CharSequence text, int i) {
            char c = text.charAt(i);
            if (c == '\r') return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? 2 : 1;
            return c == '\n' || c == '\u000b' || c == '\f' || c == '\u0085' || c == '\u2028' || c == '\u2029' ? 1 : 0;
        }
        /** The lines of a text, as {@code text.split("\\R", -1)} without holding them all at once. */
        private static Iterable<String> lines(String text) {
            return () -> new Iterator<>() {
                private int start;
                @Override public boolean hasNext() { return start >= 0; }
                @Override public String next() {
                    if (start < 0) throw new NoSuchElementException();
                    int end = start;
                    while (end < text.length() && lineBreak(text, end) == 0) end++;
                    String line = text.substring(start, end);
                    start = end == text.length() ? -1 : end + lineBreak(text, end);
                    return line;
                }
            };
        }
        /**
         * Appends one output line, ending the compilation when the text would exceed the
         * preprocessed limit; the newline ending the last line is not counted, so a source
         * file of {@link TreeLimits#MAX_SOURCE_BYTES} without includes or macros always fits.
         */
        private void emit(StringBuilder out, String text, int unit, int line, int[] runs) {
            if (text.length() > TreeLimits.MAX_PREPROCESSED_CHARS - out.length())
                diagnostics.report(located(unit, line, 1, "Preprocessed source exceeds " + TreeLimits.MAX_PREPROCESSED_CHARS
                        + " characters after includes and macro expansion").stopping());
            out.append(text).append('\n'); sourceMap.line(unit, line, runs);
        }
        /** Rejects {@code NAME(} in #define, which would be a function-like macro in C. */
        private static void functionLikeMacro(String definition) {
            int parenthesis = definition.indexOf('(');
            if (parenthesis <= 0) return;
            String name = definition.substring(0, parenthesis);
            if (!SourceRules.isIdentifier(name)) return;
            throw new IllegalArgumentException("Function-like macros are not supported: " + AstShape.printable(name)
                    + "; write a function, or put a space before '(' for an object-like macro");
        }
        private static String reason(IOException error) {
            if (error instanceof NoSuchFileException) return "file not found";
            if (error instanceof AccessDeniedException) return "permission denied";
            if (error instanceof FileSystemException fileSystem && fileSystem.getReason() != null) return fileSystem.getReason();
            return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        }
        private record Expansion(String text, boolean blockComment) {}
        /** Comment-free text; {@code openColumn} is where a comment still open at the line end started on this line, else 0. */
        private record Stripped(String text, boolean blockComment, int openColumn) {}
        private Stripped stripComments(String line, boolean block) {
            StringBuilder out = new StringBuilder();
            int i = 0, openColumn = 0;
            while (i < line.length()) {
                if (block) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) { out.append(" ".repeat(line.length() - i)); break; }
                    out.append(" ".repeat(end + 2 - i)); i = end + 2; block = false; openColumn = 0; continue;
                }
                if (line.startsWith("//", i)) { out.append(" ".repeat(line.length() - i)); break; }
                if (line.startsWith("/*", i)) { out.append("  "); openColumn = i + 1; i += 2; block = true; continue; }
                char c = line.charAt(i++); out.append(c);
                if (c == '\'' || c == '"') {
                    char quote = c;
                    while (i < line.length()) {
                        c = line.charAt(i++); out.append(c);
                        if (c == '\\' && i < line.length()) out.append(line.charAt(i++));
                        else if (c == quote) break;
                    }
                }
            }
            return new Stripped(out.toString(), block, block ? openColumn : 0);
        }
        /**
         * Expands object-like macros on one line. At depth 0, {@code columns} records which
         * output columns came from which source column and macro.
         */
        private Expansion expand(String line, boolean block, Set<String> expanding, int depth, SourceMap.Columns columns) {
            if (depth > 64) throw new LimitError("Macro nesting exceeds 64 levels");
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (columns != null) columns.verbatim(out.length(), i);
                if (block) {
                    int end = line.indexOf("*/", i);
                    if (end < 0) { out.append(line.substring(i)); break; }
                    out.append(line, i, end + 2); i = end + 2; block = false; continue;
                }
                char c = line.charAt(i);
                if (line.startsWith("//", i)) { out.append(line.substring(i)); break; }
                if (line.startsWith("/*", i)) { out.append("/*"); i += 2; block = true; continue; }
                if (c == '\'' || c == '"') {
                    char quote = c; out.append(c); i++;
                    while (i < line.length()) {
                        c = line.charAt(i++); out.append(c);
                        if (c == '\\' && i < line.length()) out.append(line.charAt(i++));
                        else if (c == quote) break;
                    }
                    // Reported at the literal's start, not after it.
                    if (out.length() > MAX_MACRO_EXPANSION_CHARS)
                        throw new LimitError("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
                } else if (Character.isDigit(c) || (c == '.' && i + 1 < line.length() && Character.isDigit(line.charAt(i + 1)))) {
                    int start = i;
                    while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                    if (i < line.length() && line.charAt(i) == '.') {
                        i++;
                        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                    }
                    if (i < line.length() && (line.charAt(i) == 'e' || line.charAt(i) == 'E')) {
                        int exponent = i + 1;
                        if (exponent < line.length() && (line.charAt(exponent) == '+' || line.charAt(exponent) == '-')) exponent++;
                        if (exponent < line.length() && Character.isDigit(line.charAt(exponent))) {
                            i = exponent + 1;
                            while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
                        }
                    }
                    // Like a C pp-number, identifier characters glued to a literal belong to it:
                    // 5N, 0x10 or 2e must reach the lexer's suffix error, not become 55, 07 or 29.
                    while (i < line.length() && SourceRules.identifierPart(line.charAt(i))) i++;
                    appendLimited(out, line.substring(start, i));
                } else if (SourceRules.identifierStart(c)) {
                    int start = i++;
                    while (i < line.length() && SourceRules.identifierPart(line.charAt(i))) i++;
                    String word = line.substring(start, i);
                    Macro macro = macros.get(word);
                    if (macro != null) {
                        if (!expanding.add(word)) throw new IllegalArgumentException("Recursive macro: " + word);
                        if (columns != null) columns.macro(out.length(), start, macro.reference());
                        appendLimited(out, expand(macro.value(), false, expanding, depth + 1, null).text());
                        expanding.remove(word);
                    } else appendLimited(out, word);
                } else { appendLimited(out, c); i++; }
            }
            if (out.length() > MAX_MACRO_EXPANSION_CHARS)
                throw new LimitError("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
            return new Expansion(out.toString(), block);
        }
        private static void appendLimited(StringBuilder out, String value) {
            if (value.length() > MAX_MACRO_EXPANSION_CHARS - out.length())
                throw new LimitError("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
            out.append(value);
        }
        private static void appendLimited(StringBuilder out, char value) {
            if (out.length() >= MAX_MACRO_EXPANSION_CHARS)
                throw new LimitError("Macro expansion exceeds " + MAX_MACRO_EXPANSION_CHARS + " characters on one line");
            out.append(value);
        }
        public JsonObject toObj() {
            JsonObject result = new JsonObject(), metadata = new JsonObject(), extensions = new JsonObject();
            metadata.addProperty("author", author); metadata.addProperty("version", 2);
            metadata.addProperty("function-namespace", functionNamespace);
            result.add("metadata", metadata);
            result.add("sources", sourceMap.toJson());
            result.addProperty("namespace-hint", namespaceHint);
            JsonArray assumptions = new JsonArray();
            assumeHints.forEach((hint, namespace) -> {
                JsonObject value = new JsonObject(); value.addProperty("hint", hint); value.addProperty("namespace", namespace); assumptions.add(value);
            });
            result.add("assume-hints", assumptions);
            exts.forEach(extensions::addProperty); result.add("ext", extensions);
            JsonObject abstracts = new JsonObject();
            name2Id.forEach((name, id) -> abstracts.addProperty(name, Integer.toHexString(id)));
            result.add("abstract", abstracts);
            JsonObject signatures = new JsonObject();
            externSignatures.forEach((name, signature) -> {
                JsonObject value = new JsonObject();
                value.addProperty("return-type", signature.returnType);
                value.addProperty("id", Integer.toHexString(signature.id));
                if (!signature.typeParameters.isEmpty()) value.add("type-parameters", signature.typeParameters.deepCopy());
                if (signature.ownerClass != null) {
                    value.addProperty("owner-class", signature.ownerClass); value.addProperty("function-kind", signature.functionKind);
                }
                if (signature.operatorKind != null) value.addProperty("operator-kind", signature.operatorKind);
                JsonArray parameterTypes = new JsonArray();
                signature.paramTypes.forEach(parameterTypes::add);
                value.add("param-types", parameterTypes);
                if (signature.line != 0) position(value, signature.file, signature.line, signature.column);
                signatures.add(name, value);
            });
            result.add("extern-signatures", signatures);
            JsonArray globals = new JsonArray(); globalVariables.forEach(globals::add);
            typedGlobals.forEach(entry -> globals.add(entry.deepCopy()));
            result.add("global-variable", globals);
            JsonObject body = new JsonObject();
            for (AzFunction function : functions) {
                String namespace = Integer.toHexString(function.id >>> 16);
                if (!body.has(namespace)) body.add(namespace, new JsonObject());
                JsonObject entry = new JsonObject(), meta = new JsonObject();
                meta.addProperty("name", function.name);
                meta.addProperty("position", function.id & 0xffff);
                meta.addProperty("return-type", function.returnType);
                if (!function.typeParameters.isEmpty()) meta.add("type-parameters", function.typeParameters.deepCopy());
                if (function.ownerClass != null) {
                    meta.addProperty("owner-class", function.ownerClass);
                    meta.addProperty("function-kind", function.functionKind);
                }
                if (function.operatorKind != null) meta.addProperty("operator-kind", function.operatorKind);
                if (function.baseArguments != null) {
                    meta.add("base-args", function.baseArguments.deepCopy());
                    meta.addProperty("base-initializer", function.baseInitializer);
                }
                if (function.line != 0) {
                    position(meta, function.file, function.line, function.column);
                    if (function.endLine != 0) {
                        meta.addProperty("_end_line", function.endLine);
                        meta.addProperty("_end_column", function.endColumn);
                    }
                }
                JsonArray params = new JsonArray(); function.params.forEach(params::add);
                JsonArray paramTypes = new JsonArray(); function.paramTypes.forEach(paramTypes::add);
                meta.add("param", params); meta.add("param-types", paramTypes);
                entry.add("metadata", meta); entry.add("script", function.body.generate());
                body.getAsJsonObject(namespace).add("_func" + Integer.toHexString(function.id), entry);
            }
            result.add("body", body);
            if (!classes.isEmpty()) result.add("classes", classes.deepCopy());
            return result;
        }
    }
    /** Writes an original position; {@code _file} indexes the AST {@code sources} table and is omitted for the main unit. */
    private static void position(JsonObject node, int file, int line, int column) {
        if (file != 0) node.addProperty("_file", file);
        node.addProperty("_line", line); node.addProperty("_column", column);
    }
    private static void identifier(String value) {
        if (!SourceRules.isIdentifier(value)) throw new IllegalArgumentException("Invalid identifier: " + AstShape.printable(value));
        SourceRules.requireUnreserved(value, "name");
    }
    private static String normalizeType(String value, boolean allowVoid, String description) {
        if (!TYPES.contains(value)) throw new IllegalArgumentException("Unknown " + description + ": " + value);
        String normalized = value.equals("bool") ? "boolean" : value;
        if (!allowVoid && normalized.equals("void"))
            throw new IllegalArgumentException("void is not a valid " + description);
        return normalized;
    }
    /** True unless the text contains a surrogate that is not part of a high/low pair. */
    static boolean wellFormedUtf16(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) i++;
            else if (Character.isSurrogate(c)) return false;
        }
        return true;
    }
    /**
     * A lexical token at its original position: source unit (index into the AST
     * {@code sources} table), line and column, plus the macro it was expanded from or -1.
     * start/end are offsets in the preprocessed text, independent of mapped macro columns.
     */
    private record Token(String text, JsonElement literal, int unit, int line, int column, boolean identifier, int macro,
                         int start, int end) {}
    /**
     * Splits preprocessed text into tokens at their original positions. A lexical
     * error is reported and skipped: an unexpected character, the rest of a broken
     * string literal up to its quote or the end of the line, or a whole malformed number.
     */
    private static final class Lexer {
        private final String source;
        private final SourceMap map;
        private final Diagnostics diagnostics;
        private int offset, line = 1, column = 1, lastLine = 1, lastColumn = 1;
        Lexer(String source, SourceMap map, Diagnostics diagnostics) { this.source = source; this.map = map; this.diagnostics = diagnostics; }
        private char peek(int delta) { return offset + delta < source.length() ? source.charAt(offset + delta) : '\0'; }
        private char take() {
            char c = source.charAt(offset++);
            if (c == '\n') { lastLine = line; lastColumn = column; line++; column = 1; } else column++;
            return c;
        }
        /** A token at preprocessed line l and column c. */
        private Token token(String text, JsonElement literal, int l, int c, boolean identifier, int start) {
            SourceMap.Origin origin = map.resolve(l, c);
            return new Token(text, literal, origin.unit(), origin.line(), origin.column(), identifier, origin.macro(), start, offset);
        }
        /** Reports an error at preprocessed line l and column c; returns when lexing may continue. */
        private void fail(String message, int l, int c) {
            SourceMap.Origin origin = map.resolve(l, c);
            diagnostics.report(new CompileException(map.error(origin.unit(), origin.line(), origin.column(), origin.macro(), null, message)));
        }
        /** Ends the compilation with an error at preprocessed line l and column c. */
        private void stop(String message, int l, int c) {
            SourceMap.Origin origin = map.resolve(l, c);
            diagnostics.report(new CompileException(map.error(origin.unit(), origin.line(), origin.column(), origin.macro(), null, message)).stopping());
        }
        private static final Set<String> TWO_CHARACTER_OPERATORS = Set.of("==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "++", "--", "::");
        List<Token> scan() {
            List<Token> tokens = new ArrayList<>();
            while (offset < source.length()) {
                char c = peek(0);
                // The preprocessor strips a leading byte order mark, so any other U+FEFF is an unexpected character.
                if (Character.isWhitespace(c)) { take(); continue; }
                int l = line, col = column, start = offset;
                if (c == '/' && peek(1) == '/') { while (offset < source.length() && peek(0) != '\n') take(); continue; }
                if (c == '/' && peek(1) == '*') {
                    take(); take();
                    while (offset < source.length() && !(peek(0) == '*' && peek(1) == '/')) take();
                    if (offset == source.length()) { fail("Unterminated block comment", l, col); continue; }
                    take(); take(); continue;
                }
                // Bounds the token list, which is far larger than the text it was read from.
                if (tokens.size() >= TreeLimits.MAX_TOKENS) stop("Program exceeds " + TreeLimits.MAX_TOKENS + " tokens", l, col);
                if (c == '\'' || c == '"') {
                    Token string = string(l, col, start);
                    if (string != null) tokens.add(string);
                } else if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).text().equals(":")
                        && SourceRules.identifierPart(c)) {
                    // IDs keep their raw spelling, even when it resembles a number
                    // such as 1e5 or overflows as 1e999. In expression position a
                    // successful numeric scan may extend through a dot or exponent sign.
                    Token numeric = Character.isDigit(c) ? numberToken(l, col, start, false) : null;
                    if (numeric != null) tokens.add(numeric);
                    else {
                        take(); while (peek(0) != '\0' && SourceRules.identifierPart(peek(0))) take();
                        String text = source.substring(start, offset);
                        JsonElement literal = text.equals("true") || text.equals("false") ? new JsonPrimitive(Boolean.parseBoolean(text)) : null;
                        tokens.add(token(text, literal, l, col, SourceRules.identifierStart(c), start));
                    }
                } else if (Character.isDigit(c) || (c == '.' && Character.isDigit(peek(1)))) {
                    Token number = numberToken(l, col, start, true);
                    if (number != null) tokens.add(number);
                } else if (SourceRules.identifierStart(c)) {
                    take(); while (SourceRules.identifierPart(peek(0)) && peek(0) != '\0') take();
                    String text = source.substring(start, offset);
                    JsonElement literal = text.equals("true") || text.equals("false") ? new JsonPrimitive(Boolean.parseBoolean(text)) : null;
                    tokens.add(token(text, literal, l, col, true, start));
                } else {
                    String two = "" + c + peek(1);
                    if (TWO_CHARACTER_OPERATORS.contains(two)) {
                        take(); take(); tokens.add(token(two, null, l, col, false, start));
                    } else if (c == ':') {
                        take(); tokens.add(token(":", null, l, col, false, start));
                    } else if ("{}[](),;+-*/%=<>!.~&|^?".indexOf(c) >= 0) {
                        take(); tokens.add(token(String.valueOf(c), null, l, col, false, start));
                    } else {
                        // Skip a whole code point, so a supplementary character is one error.
                        int codePoint = source.codePointAt(offset);
                        fail("Unexpected character: " + character(codePoint), l, col);
                        for (int n = Character.charCount(codePoint); n > 0; n--) take();
                    }
                }
            }
            // Preprocessed text ends with a newline per line; place <eof> at the end of the last line.
            boolean newline = !source.isEmpty() && source.charAt(source.length() - 1) == '\n';
            tokens.add(token("<eof>", null, newline ? lastLine : line, newline ? lastColumn : column, false, offset)); return tokens;
        }
        /** A string literal token, or null after reporting a malformed one. */
        private Token string(int l, int col, int start) {
            char quote = take(); StringBuilder value = new StringBuilder();
            while (offset < source.length()) {
                char c = take();
                if (c == quote) {
                    // UTF-8 cannot encode a lone surrogate; Java would silently write '?' instead.
                    if (!wellFormedUtf16(value)) {
                        fail("Unpaired surrogate in string literal; write a supplementary character as a \\uD8xx\\uDCxx pair", l, col);
                        return null;
                    }
                    return token(source.substring(start, offset), new JsonPrimitive(value.toString()), l, col, false, start);
                }
                if (c == '\n' || c == '\r') { fail("Newline in string literal", l, col); return null; }
                if (c != '\\') { value.append(c); continue; }
                if (offset == source.length()) { fail("Unterminated string escape", l, col); return null; }
                c = take();
                switch (c) {
                    case 'n' -> value.append('\n'); case 'r' -> value.append('\r');
                    case 't' -> value.append('\t'); case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f'); case '0' -> value.append('\0');
                    case '\\', '\'', '"' -> value.append(c);
                    case 'u' -> {
                        int code = 0;
                        for (int j = 0; j < 4; j++) {
                            if (offset == source.length()) { fail("Incomplete Unicode escape", l, col); return null; }
                            char digit = take();
                            if (Character.digit(digit, 16) < 0) {
                                fail("Invalid Unicode escape", l, col); skipString(quote, digit); return null;
                            }
                            code = code * 16 + Character.digit(digit, 16);
                        }
                        value.append((char) code);
                    }
                    default -> {
                        fail("Unknown string escape: \\" + character(source.codePointAt(offset - 1)), l, col);
                        skipString(quote, c); return null;
                    }
                }
            }
            fail("Unterminated string literal", l, col); return null;
        }
        /** Skips the rest of a malformed string up to its closing quote or the end of the line. */
        private void skipString(char quote, char last) {
            if (last == quote || last == '\n' || last == '\r') return;
            while (offset < source.length() && peek(0) != '\n' && peek(0) != '\r') {
                char c = take();
                if (c == quote) return;
                if (c == '\\' && offset < source.length() && peek(0) != '\n' && peek(0) != '\r') take();
            }
        }
        /** A failed non-strict probe changes neither cursor state nor diagnostics. */
        private Token numberToken(int l, int col, int start, boolean strict) {
            int savedOffset = offset, savedLine = line, savedColumn = column, savedLastLine = lastLine, savedLastColumn = lastColumn;
            try { return numberLiteral(l, col, start); }
            catch (IllegalArgumentException error) {
                if (strict) { fail(error.getMessage(), l, col); skipIdentifierPart(); return null; }
                offset = savedOffset; line = savedLine; column = savedColumn; lastLine = savedLastLine; lastColumn = savedLastColumn;
                return null;
            }
        }
        private Token numberLiteral(int l, int col, int start) {
            while (Character.isDigit(peek(0))) take();
            if (peek(0) == '.') { take(); while (Character.isDigit(peek(0))) take(); }
            if (peek(0) == 'e' || peek(0) == 'E') {
                take(); if (peek(0) == '+' || peek(0) == '-') take();
                if (!Character.isDigit(peek(0))) throw new IllegalArgumentException("Invalid numeric exponent");
                while (Character.isDigit(peek(0))) take();
            }
            boolean floatSuffix = peek(0) == 'f' || peek(0) == 'F';
            if (floatSuffix) take();
            if (SourceRules.identifierPart(peek(0)) && peek(0) != '\0') throw new IllegalArgumentException("Invalid numeric suffix");
            String text = source.substring(start, offset - (floatSuffix ? 1 : 0));
            BigDecimal decimal;
            try { decimal = new BigDecimal(text); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Numeric literal is out of range"); }
            if (floatSuffix) {
                float value = decimal.floatValue();
                if (!Float.isFinite(value) || (value == 0 && decimal.signum() != 0)) {
                    throw new IllegalArgumentException("Floating literal is outside float range");
                }
                Token at = token(source.substring(start, offset), null, l, col, false, start);
                JsonObject object = operation("ctrl", "float", at, new JsonPrimitive(value));
                return new Token(at.text(), object, at.unit(), at.line(), at.column(), false, at.macro(), at.start(), at.end());
            }
            JsonPrimitive literal;
            if (text.indexOf('.') >= 0 || text.indexOf('e') >= 0 || text.indexOf('E') >= 0) {
                double value = decimal.doubleValue();
                if (!Double.isFinite(value) || (value == 0 && decimal.signum() != 0)) {
                    throw new IllegalArgumentException("Floating literal is outside double range");
                }
                // Keep lexical floating kind even for 5., 5e0 and zero; BigDecimal normalizes these away.
                literal = new JsonPrimitive(value);
            } else literal = new JsonPrimitive(decimal);
            return token(text, literal, l, col, false, start);
        }
        /**
         * A character for a message: printable ASCII as itself, anything else as U+XXXX,
         * so control, invisible and bidirectional formatting characters cannot alter the output.
         */
        static String character(int codePoint) {
            return codePoint > ' ' && codePoint < 0x7f ? Character.toString(codePoint) : String.format("U+%04X", codePoint);
        }
        private void skipIdentifierPart() {
            while (peek(0) != '\0' && SourceRules.identifierPart(peek(0))) take();
        }
    }
    private static JsonObject operation(String kind, String name, Token at, JsonElement... params) {
        JsonObject object = new JsonObject(); object.addProperty("t", kind); object.addProperty("call", name);
        JsonArray array = new JsonArray(); for (JsonElement param : params) array.add(param);
        object.add("param", array);
        position(object, at.unit(), at.line(), at.column());
        return object;
    }
    private static final class Parser {
        private final List<Token> tokens;
        private final String preprocessed;
        private final AzScript script;
        private final SourceMap map;
        private final Diagnostics diagnostics;
        /** Token index of the last reported error; a second error at the same token is a cascade. */
        private int lastErrorIndex = -1;
        /** Exact tokens of declarations, so later errors keep their macro and include notes. */
        private final Map<JsonObject,Token> classTokens = new IdentityHashMap<>();
        private final Map<AzFunction,Token> functionTokens = new IdentityHashMap<>();
        private final Map<ExternSignature,Token> externTokens = new IdentityHashMap<>();
        /** The name token of every typed global declaration, by name. */
        private final Map<String,Token> typedGlobalTokens = new HashMap<>();
        private int index;
        private int recursionDepth;
        private int loopDepth;
        private int switchDepth;
        private static final Set<String> METHOD_MODIFIERS = Set.of("virtual", "override", "final");
        private record DeclarationPrefix(boolean external, JsonArray generic, Map<String,Token> modifiers) {}
        /** Extern, method modifiers and a method's own type parameters are all prefixes. */
        private DeclarationPrefix declarationPrefix(boolean member) {
            boolean external = false, genericSeen = false; JsonArray generic = new JsonArray();
            Map<String,Token> modifiers = new LinkedHashMap<>();
            while (true) {
                if (at("extern")) {
                    Token at = advance(); if (external) throw error("Duplicate extern modifier", at); external = true;
                } else if (METHOD_MODIFIERS.contains(current().text())) {
                    Token at = advance();
                    if (!member) throw error("'" + at.text() + "' is only supported on methods inside a class declaration", at);
                    if (modifiers.putIfAbsent(at.text(), at) != null) throw error("Duplicate " + at.text() + " modifier", at);
                } else if (at("<")) {
                    if (genericSeen) throw error("Duplicate method type-parameter list");
                    generic = readTypeParameters(); genericSeen = true;
                } else break;
            }
            return new DeclarationPrefix(external, generic, modifiers);
        }
        private int nextFunctionPosition = 1;
        private final Set<String> classNames = new LinkedHashSet<>();
        /** The first definition of every class in {@code script.classes}, by name. */
        private final Map<String,JsonObject> declaredClasses = new HashMap<>();
        private final Map<String,List<String>> classTypeParameters = new LinkedHashMap<>();
        private Set<String> typeParameters = new LinkedHashSet<>();
        Parser(String source, SourceMap map, AzScript script, Diagnostics diagnostics) {
            this.map = map; this.diagnostics = diagnostics; this.script = script; this.preprocessed = source;
            this.tokens = new Lexer(source, map, diagnostics).scan();
            diagnostics.checkpoint(); // lexing
            for (JsonElement item : script.classes) {
                JsonObject declaration = item.getAsJsonObject(); String name = declaration.get("name").getAsString();
                classNames.add(name); classTypeParameters.put(name, parameterNames(declaration.getAsJsonArray("type-parameters")));
                declaredClasses.putIfAbsent(name, declaration);
            }
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (tokens.get(i).text().equals("class") && tokens.get(i + 1).identifier()) {
                    String name = tokens.get(i + 1).text(); classNames.add(name);
                    classTypeParameters.putIfAbsent(name, typeParameterNamesAhead(i + 2));
                }
            }
        }
        private static List<String> parameterNames(JsonArray declarations) {
            List<String> result = new ArrayList<>();
            if (declarations != null) for (JsonElement item : declarations) result.add(item.getAsJsonObject().get("name").getAsString());
            return result;
        }
        /** Collect names before parsing bounds, so forward and recursive bounds have a scope. */
        private List<String> typeParameterNamesAhead(int start) {
            List<String> names = new ArrayList<>();
            if (start >= tokens.size() || !tokens.get(start).text().equals("<")) return names;
            int depth = 1; boolean parameter = true;
            for (int i = start + 1; i < tokens.size() && depth > 0; i++) {
                Token token = tokens.get(i); String text = token.text();
                if (parameter && depth == 1) { if (token.identifier()) names.add(text); parameter = false; }
                if (text.equals("<")) depth++;
                else if (text.equals(">")) depth--;
                else if (text.equals(",") && depth == 1) parameter = true;
                else if (text.equals("{") || text.equals(";") || text.equals("<eof>")) break;
            }
            return names;
        }
        private JsonArray readTypeParameters() {
            JsonArray declarations = new JsonArray();
            if (!at("<")) return declarations;
            List<String> names = typeParameterNamesAhead(index); Set<String> own = new HashSet<>();
            for (String parameter : names) {
                try { identifier(parameter); } catch (CompileException e) { throw e; } catch (IllegalArgumentException e) { throw error(e.getMessage()); }
                if (!own.add(parameter) || knownType(parameter) || Compiler.isBuiltinName(parameter))
                    throw error("Duplicate or reserved type parameter: " + parameter);
            }
            typeParameters.addAll(names); expect("<");
            if (at(">")) throw error("Type parameter list cannot be empty");
            do {
                JsonObject declaration = new JsonObject(); declaration.addProperty("name", name().text());
                if (match("extends")) declaration.addProperty("bound", readType(false));
                declarations.add(declaration);
            } while (match(","));
            expect(">"); return declarations;
        }
        private boolean knownType(String name) { return TYPES.contains(name) || classNames.contains(name) || typeParameters.contains(name); }
        /**
         * Whether a statement starts with a declaration type. A type name followed by
         * "::" (or a class with type arguments followed by "::") starts a qualified
         * base-method call expression such as {@code Base::m(args)} instead.
         */
        private boolean declarationAhead() {
            return knownType(current().text()) && !at("void") && classOwnerEnd(index) < 0
                    && !(index + 1 < tokens.size() && (tokens.get(index + 1).text().equals("::")
                    || SourceRules.TYPES.contains(current().text()) && tokens.get(index + 1).text().equals("(")));
        }
        private String sourceType(String value, boolean allowVoid) {
            if (classNames.contains(value) || typeParameters.contains(value)) return value;
            try { return normalizeType(value, allowVoid, "type"); }
            catch (CompileException e) { throw e; }
            catch (IllegalArgumentException e) { throw error(e.getMessage(), tokens.get(index - 1)); }
        }
        private static String rawClass(String type) {
            int arguments = type.indexOf('<');
            if (arguments >= 0) return type.substring(0, arguments);
            return type.substring(0, type.length() - classSuffix(type).length());
        }
        private boolean classType(String type) { return classNames.contains(rawClass(type)); }
        private String selfType(String owner) {
            List<String> parameters = classTypeParameters.getOrDefault(owner, List.of());
            return parameters.isEmpty() ? owner : owner + "<" + String.join(",", parameters) + ">";
        }
        private String typeApplication(String base) {
            if (!match("<")) {
                if (base.equals("buffer") || !classTypeParameters.getOrDefault(base, List.of()).isEmpty())
                    throw error("Generic type " + base + " requires explicit type arguments");
                return base;
            }
            if (!base.equals("buffer") && !classNames.contains(base)) throw error("Only a class or buffer type can have type arguments: " + base);
            List<String> arguments = new ArrayList<>();
            if (at(">")) throw error("Type argument list cannot be empty");
            do { arguments.add(readType(false)); } while (match(","));
            expect(">");
            int expected = base.equals("buffer") ? 1 : classTypeParameters.getOrDefault(base, List.of()).size();
            if (arguments.size() != expected) throw error("Type " + base + " expects " + expected + " type arguments, got " + arguments.size());
            return base + "<" + String.join(",", arguments) + ">";
        }
        private String readType(boolean allowVoid) {
            enterRecursion();
            try { return pointerSuffix(typeApplication(sourceType(name().text(), allowVoid))); }
            finally { recursionDepth--; }
        }
        /** "C *" names a pointer to C; a bare class name is a literal (value) object. */
        private String pointerSuffix(String type) {
            if (flexibleAhead()) throw error("C (*) is only allowed as a function parameter type");
            if (!at("*")) return type;
            if (!classType(type)) throw error("Only class types can be pointers; a type parameter denotes a complete type");
            advance();
            if (flexibleAhead()) throw error("A type is either C * or C (*), not both");
            return type + "*";
        }
        private boolean flexibleAhead() {
            return at("(") && index + 2 < tokens.size() && tokens.get(index + 1).text().equals("*") && tokens.get(index + 2).text().equals(")");
        }
        /** In a class body, "Type name(...)" is a field with constructor arguments when ';' follows ')'. */
        private boolean fieldConstruction() {
            int depth = 0;
            for (int i = index; i < tokens.size(); i++) {
                String text = tokens.get(i).text();
                if (text.equals("(")) depth++;
                else if (text.equals(")") && --depth == 0) {
                    String next = tokens.get(i + 1).text();
                    return next.equals(";") || next.equals("}");
                } else if (text.equals("<eof>")) return false;
            }
            return false;
        }
        private void enterRecursion() {
            if (recursionDepth >= TreeLimits.MAX_NESTING) throw error("Syntax nesting exceeds " + TreeLimits.MAX_NESTING + " levels").stopping();
            recursionDepth++;
        }
        /** Where panic-mode recovery resumes after an error. */
        private enum Sync { DECLARATION, MEMBER, STATEMENT }
        /**
         * Panic-mode recovery: reports the error, unless an error was already reported at
         * the same token, and skips to the end of the failed declaration, class member or
         * statement that began at {@code start}: past a ';' outside braces, or past the
         * '}' closing the construct's own braces (an 'else' continues an if statement),
         * but never past the '}' of the enclosing block or class. Parentheses and braces
         * are counted separately, so an unclosed '(' cannot swallow the rest of the file.
         * An error raised after the construct's last token, such as a duplicate
         * definition, skips nothing. Recovery always moves forward. Fatal errors and the
         * error limit end parsing.
         */
        private void recover(IllegalArgumentException failure, int start, Sync sync) {
            if (!(failure instanceof CompileException) && !Diagnostics.userError(failure)) throw failure;
            CompileException error = failure instanceof CompileException compiled ? compiled : error(failure.getMessage());
            int at = index;
            boolean finished = finished(start, at);
            // A finished construct's error belongs to its last token, so the next construct may report its own.
            int errorToken = finished ? at - 1 : at;
            if (error.fatal() || errorToken != lastErrorIndex || !diagnostics.recovering()) { lastErrorIndex = errorToken; diagnostics.report(error); }
            index = finished ? at : resync(start, at, sync);
        }
        /** Whether tokens [start, end) form a whole construct: balanced, and ending in a ';' or '}' outside brackets. */
        private boolean finished(int start, int end) {
            if (end <= start) return false;
            int parentheses = 0, braces = 0;
            for (int i = start; i < end; i++) {
                switch (tokens.get(i).text()) {
                    case "(" -> parentheses++;
                    case ")" -> { if (--parentheses < 0) return false; }
                    case "{" -> braces++;
                    case "}" -> { if (--braces < 0) return false; }
                    case "<eof>" -> { return false; }
                    default -> {}
                }
            }
            String last = tokens.get(end - 1).text();
            return parentheses == 0 && braces == 0 && (last.equals(";") || last.equals("}"));
        }
        private int resync(int start, int errorIndex, Sync sync) {
            int parentheses = 0, braces = 0;
            boolean forHeader = sync == Sync.STATEMENT && tokens.get(start).text().equals("for");
            // An 'else' after the end of a branch continues an if statement that is being skipped.
            boolean conditional = false;
            for (int i = start; i < tokens.size(); i++) {
                String text = tokens.get(i).text();
                boolean reached = i >= errorIndex;
                boolean elseFollows = conditional && tokens.get(Math.min(i + 1, tokens.size() - 1)).text().equals("else");
                switch (text) {
                    case "<eof>" -> { return Math.max(i, Math.min(start + 1, tokens.size() - 1)); }
                    case "if" -> { if (sync == Sync.STATEMENT && braces == 0) conditional = true; }
                    case "(" -> parentheses++;
                    case ")" -> { if (parentheses > 0) parentheses--; }
                    case "{" -> braces++;
                    case "}" -> {
                        if (braces == 0) {
                            if (sync != Sync.DECLARATION) return Math.max(i, start + 1);
                            if (reached) return i + 1; // a stray brace at the top level
                        } else if (--braces == 0 && reached && !elseFollows)
                            return i + 1;
                    }
                    // A ';' inside the parentheses of a for header separates its clauses.
                    case ";" -> { if (braces == 0 && reached && (parentheses == 0 || !forHeader) && !elseFollows) return Math.max(i + 1, start + 1); }
                    default -> {}
                }
            }
            return tokens.size() - 1;
        }
        /** Reports a post-parse validation error; parsing has finished, so nothing is skipped. */
        private void report(IllegalArgumentException failure) {
            if (!(failure instanceof CompileException) && !Diagnostics.userError(failure)) throw failure;
            diagnostics.report(failure instanceof CompileException compiled ? compiled : error(failure.getMessage()));
        }
        private Token current() { return tokens.get(index); }
        private boolean at(String text) { return current().text().equals(text); }
        private Token advance() { return tokens.get(index++); }
        private boolean match(String text) { if (!at(text)) return false; advance(); return true; }
        private Token expect(String text) { if (!at(text)) throw error("Expected '" + text + "', found '" + current().text() + "'"); return advance(); }
        private Token name() {
            if (!current().identifier() || current().literal() != null) throw error("Expected identifier");
            // Type names are read here too, so only the keywords are rejected; declaredName() adds the types.
            if (KEYWORDS.contains(current().text())) throw error(SourceRules.reservedWordMessage(current().text(), "name"));
            return advance();
        }
        /** A name being declared: keywords and type names are both rejected. */
        private Token declaredName() {
            if (current().identifier() && knownType(current().text()))
                throw error("'" + current().text() + "' is a type name and cannot be declared as a name");
            return name();
        }
        private CompileException error(String message) { return error(message, current()); }
        private CompileException error(String message, Token at) { return error(message, at, List.of()); }
        private CompileException error(String message, Token at, List<Diagnostic> notes) { return error(message, at, null, notes); }
        /** An error at a token; {@code context} names the enclosing declaration, e.g. {@code function main}, or is null. */
        private CompileException error(String message, Token at, String context, List<Diagnostic> notes) {
            return new CompileException(map.error(at.unit(), at.line(), at.column(), at.macro(), context, message).withNotes(notes));
        }
        /** A note such as "previous definition is here", followed by the include chain of that location. */
        private List<Diagnostic> noteAt(Token at, String text) {
            List<Diagnostic> notes = new ArrayList<>();
            notes.add(Diagnostic.note(map.position(at.unit(), at.line(), at.column()), text));
            notes.addAll(map.includeNotes(at.unit()));
            return notes;
        }
        /** Definitions read twice from one header also get an include-guard hint. */
        private String duplicate(String message, Token first, Token second) {
            boolean sameText = first.unit() != second.unit() && map.name(first.unit()).equals(map.name(second.unit()))
                    && first.line() == second.line() && first.column() == second.column();
            return sameText ? message + " (the header was included twice; add #pragma once or an include guard)" : message;
        }
        /** A position recorded in an AST node (for example by TreeLimits), as a token. */
        private Token located(JsonObject node, Token fallback) {
            int line = node == null ? -1 : SourceMap.integer(node.get("_line"));
            if (line <= 0) return fallback;
            return new Token("", null, map.unitOf(node), line, Math.max(0, SourceMap.integer(node.get("_column"))), false, -1, -1, -1);
        }
        private Token directive(int[] location, Token fallback) {
            return location == null ? fallback : new Token("#", null, location[0], location[1], location[2], false, -1, -1, -1);
        }
        private Token functionLocation(AzFunction function) {
            Token token = functionTokens.get(function);
            return token != null ? token : new Token(function.name, null, function.file, function.line, function.column, true, -1, -1, -1);
        }
        private final Map<String,AzFunction> definitions = new LinkedHashMap<>();
        private record DuplicateExtern(String name, ExternSignature first, ExternSignature second) {}
        private final List<DuplicateExtern> duplicateExterns = new ArrayList<>();
        /**
         * Parses the declarations, recovering at the next declaration after an error, then
         * validates base initializers, extern declarations and function IDs, each step
         * running only when the earlier ones reported no error. Class layouts are left
         * to the compiler, which reports them at the class for source and compile-json alike.
         */
        void program() {
            for (var assumption : script.assumeHints.entrySet())
                if (assumption.getValue() == script.functionNamespace)
                    diagnostics.report(error("Assumed namespace overlaps the module namespace",
                            directive(script.assumeDirectives.get(assumption.getKey()), current()),
                            script.namespaceDirective == null ? List.of()
                                    : noteAt(directive(script.namespaceDirective, current()), "module namespace declared here")));
            while (!at("<eof>")) {
                if (match(";")) continue;
                int start = index;
                Set<String> outer = typeParameters; typeParameters = new LinkedHashSet<>(outer);
                try {
                    if (match("class")) { classDeclaration(); continue; }
                    String owner = qualifiedOwnerAhead();
                    if (owner != null) typeParameters.addAll(classTypeParameters.getOrDefault(owner, List.of()));
                    DeclarationPrefix prefix = declarationPrefix(false);
                    boolean external = prefix.external(); JsonArray generic = prefix.generic();
                    Token typeStart = current();
                    String first = typeApplication(sourceType(name().text(), true));
                    if (match("::")) {
                        String className = rawClass(first); requireOwnerType(first);
                        boolean destructor = match("~"); String member = name().text();
                        if (!member.equals(className)) throw error("A method needs an explicit return type");
                        parseFunction(className, destructor ? "destructor" : "constructor", "void",
                                destructor ? "<dtor>" : "<ctor>", external, false, generic);
                    } else {
                        String type = pointerSuffix(first);
                        if (classNames.contains(current().text()) && classOwnerEnd(index) >= 0) {
                            String ownerType = typeApplication(name().text()); requireOwnerType(ownerType); expect("::");
                            String member = methodName();
                            parseFunction(rawClass(ownerType), "method", type, member, external, false, generic);
                        } else {
                            Token member = declaredName();
                            if (at(";") || at("=")) globalDeclaration(typeStart, type, member, external, generic);
                            else if (!external && classType(type) && automaticObjectAhead())
                                throw error(type.endsWith("*") ? "Global objects cannot be automatic; use = new " + type.substring(0, type.length() - 1) + "(...)"
                                        : "Global " + member.text() + " cannot hold a class value or buffer; declare a pointer", typeStart);
                            else parseFunction(null, null, type, member.text(), external, false, generic);
                        }
                    }
                } catch (IllegalArgumentException e) {
                    recover(e, start, Sync.DECLARATION);
                } finally { typeParameters = outer; }
            }
            // A typed global cannot share its name with a function defined or declared anywhere in the unit.
            for (JsonObject entry : script.typedGlobals) {
                String name = entry.get("name").getAsString(); Token at = typedGlobalTokens.get(name);
                AzFunction definition = definitions.get(name); ExternSignature declaration = script.externSignatures.get(name);
                diagnostics.metrics().tableProbes++;
                if (definition != null)
                    report(error("Global " + name + " conflicts with function " + name, at, null, noteAt(functionLocation(definition), "previous definition is here")));
                else if (declaration != null)
                    report(error("Global " + name + " conflicts with function " + name, at, null,
                            externTokens.containsKey(declaration) ? noteAt(externTokens.get(declaration), "previous declaration is here") : List.of()));
                else if (Compiler.isBuiltinName(name)) report(error("Global " + name + " conflicts with built-in function " + name, at));
            }
            diagnostics.checkpoint(); // parsing
            // The compiler validates class layouts, so source and compile-json report the same located errors.
            validateBaseInitializers();
            diagnostics.checkpoint();
            validateDuplicateExterns();
            diagnostics.checkpoint();
            try { assignFunctionIds(); }
            catch (IllegalArgumentException e) { report(e); }
            diagnostics.checkpoint();
            // Bound every tree before toObj() and the compiler copy or walk it recursively.
            for (AzFunction function : script.functions) {
                String context = Compiler.contextName(function.name);
                try { TreeLimits.validate(function.body.generate()); }
                catch (TreeLimits.LimitExceeded e) { throw error(e.getMessage(), located(e.anchor(), functionLocation(function)), context, List.of()); }
                if (function.baseArguments != null) {
                    // Like other base-initializer errors, reported at the constructor.
                    try { TreeLimits.validate(function.baseArguments); }
                    catch (TreeLimits.LimitExceeded e) { throw error(e.getMessage(), functionLocation(function), context, List.of()); }
                }
            }
            for (JsonObject entry : script.typedGlobals) {
                try { TreeLimits.validate(entry); }
                catch (TreeLimits.LimitExceeded e) {
                    String name = entry.get("name").getAsString();
                    throw error(e.getMessage(), located(e.anchor(), typedGlobalTokens.get(name)), "global " + name, List.of());
                }
            }
            for (JsonElement item : script.classes) {
                JsonObject definition = item.getAsJsonObject(); String name = definition.get("name").getAsString();
                try { TreeLimits.validate(definition); }
                catch (TreeLimits.LimitExceeded e) {
                    throw error(e.getMessage(), located(e.anchor(), classLocation(definition)),
                            (e.anchor() == definition ? "class " : "initializer of class ") + name, List.of());
                }
            }
        }
        /**
         * A typed global {@code T g;} or {@code T g = e;}. The compiler checks the type and
         * initializes the global in the module's onload: defaults first, then initializers in order.
         */
        private void globalDeclaration(Token start, String type, Token nameToken, boolean external, JsonArray generic) {
            String name = nameToken.text();
            if (external) throw error("Globals cannot be extern", start);
            if (!generic.isEmpty()) throw error("Globals cannot declare type parameters", start);
            if (type.equals("void")) throw error("Global " + name + " cannot have type void", start);
            if (SourceRules.LIFECYCLE_HOOKS.contains(name)) throw error("Global " + name + " conflicts with lifecycle function " + name, nameToken);
            diagnostics.metrics().tableProbes++;
            if (script.globalDirectives.containsKey(name)) {
                // Directives are collected before parsing; the error goes to whichever declaration comes second.
                Token directive = directive(script.globalDirectives.get(name), nameToken);
                boolean sameUnit = directive.unit() == nameToken.unit();
                boolean later = sameUnit && (directive.line() > nameToken.line() || directive.line() == nameToken.line() && directive.column() > nameToken.column());
                if (later) throw error("Duplicate global variable: " + name, directive, null, noteAt(nameToken, "previous declaration is here"));
                throw error("Duplicate global variable: " + name, nameToken, null,
                        noteAt(directive, sameUnit ? "previous declaration is here" : "other declaration is here"));
            }
            Token previous = typedGlobalTokens.get(name);
            if (previous != null)
                throw error(duplicate("Duplicate global variable: " + name, previous, nameToken), nameToken, null,
                        noteAt(previous, "previous declaration is here"));
            JsonObject entry = new JsonObject(); entry.addProperty("name", name); entry.addProperty("type", type);
            position(entry, start.unit(), start.line(), start.column());
            if (match("=")) entry.add("initializer", expression());
            if (!match(";")) throw error("Expected ';' after global declaration");
            typedGlobalTokens.put(name, nameToken); script.typedGlobals.add(entry);
        }
        /**
         * "T g(...)" is an automatic object declaration, not a function, when ';' follows ')'
         * and the parentheses do not open a typed parameter list such as "(int x)".
         */
        private boolean automaticObjectAhead() {
            if (!at("(")) return false;
            if (index + 2 >= tokens.size()) return false;
            Token first = tokens.get(index + 1), second = tokens.get(index + 2);
            if (knownType(first.text()) && (second.identifier() || second.text().equals("*") || second.text().equals("<"))) return false;
            int depth = 0;
            for (int i = index; i < tokens.size(); i++) {
                String text = tokens.get(i).text();
                if (text.equals("(")) depth++;
                else if (text.equals(")") && --depth == 0) return tokens.get(i + 1).text().equals(";");
                else if (text.equals("{") || text.equals("<eof>")) return false;
            }
            return false;
        }
        /** Index of the :: after a complete owner type, or -1 for an ordinary identifier. */
        private int classOwnerEnd(int start) {
            if (start >= tokens.size() || !classNames.contains(tokens.get(start).text())) return -1;
            int cursor = start + 1;
            if (tokens.get(cursor).text().equals("<")) {
                int depth = 0;
                do {
                    String token = tokens.get(cursor++).text();
                    if (token.equals("<")) depth++;
                    else if (token.equals(">")) depth--;
                    else if (token.equals(";") || token.equals("{") || token.equals("<eof>")) return -1;
                } while (cursor < tokens.size() && depth > 0);
            }
            return cursor < tokens.size() && tokens.get(cursor).text().equals("::") ? cursor : -1;
        }
        private String qualifiedOwnerAhead() {
            for (int cursor = index; cursor < tokens.size(); cursor++) {
                String token = tokens.get(cursor).text();
                if (token.equals("(") || token.equals("{") || token.equals(";") || token.equals("<eof>")) break;
                if (classOwnerEnd(cursor) >= 0) return token;
            }
            return null;
        }
        private void requireOwnerType(String type) {
            if (!classType(type)) throw error("Unknown owner class: " + type);
            if (!selfType(rawClass(type)).equals(type))
                throw error("An out-of-class definition must use its class type parameters: " + selfType(rawClass(type)));
        }
        private Token classLocation(JsonObject definition) {
            Token token = classTokens.get(definition);
            return token != null ? token : located(definition, current());
        }
        /** A constructor's {@code : Base(...)} initializer must name its own direct base class. */
        private void validateBaseInitializers() {
            Map<String,JsonObject> declarations = new HashMap<>();
            for (JsonElement item : script.classes) declarations.put(item.getAsJsonObject().get("name").getAsString(), item.getAsJsonObject());
            for (AzFunction function : script.functions) {
                if (function.baseInitializer == null) continue;
                JsonObject owner = declarations.get(function.ownerClass);
                if (owner == null || !owner.has("base") || !owner.get("base").getAsString().equals(function.baseInitializer))
                    report(error("Constructor " + function.ownerClass + " can initialize only its direct base class",
                            functionLocation(function)));
            }
        }
        private void validateDuplicateExterns() {
            for (DuplicateExtern duplicate : duplicateExterns) {
                ExternSignature first = duplicate.first(), second = duplicate.second();
                if (!sameExtern(first, second)) report(conflictingExtern(duplicate.name(), first, second));
            }
        }
        private CompileException conflictingExtern(String name, ExternSignature first, ExternSignature second) {
            Token at = externTokens.getOrDefault(second, current()), before = externTokens.getOrDefault(first, at);
            return error("Conflicting extern declaration: " + name, at, noteAt(before, "previous declaration is here"));
        }
        /** Classes are nominal: the declarations agree up to renamed type parameters. */
        private boolean sameExtern(ExternSignature first, ExternSignature second) {
            if (first.typeParameters.size() != second.typeParameters.size()) return false;
            Map<String,String> renames = new HashMap<>();
            for (int i = 0; i < first.typeParameters.size(); i++)
                renames.put(second.typeParameters.get(i).getAsJsonObject().get("name").getAsString(), first.typeParameters.get(i).getAsJsonObject().get("name").getAsString());
            for (int i = 0; i < first.typeParameters.size(); i++) {
                JsonObject left = first.typeParameters.get(i).getAsJsonObject(), right = second.typeParameters.get(i).getAsJsonObject();
                if (left.has("bound") != right.has("bound") || left.has("bound") && !left.get("bound").getAsString().equals(
                        renameType(right.get("bound").getAsString(), renames))) return false;
            }
            if (first.paramTypes.size() != second.paramTypes.size() || !first.returnType.equals(renameType(second.returnType, renames)))
                return false;
            for (int i = 0; i < first.paramTypes.size(); i++)
                if (!first.paramTypes.get(i).equals(renameType(second.paramTypes.get(i), renames)))
                    return false;
            return true;
        }
        private static String renameType(String type, Map<String,String> renames) {
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < type.length();) {
                int end = i + 1;
                if (SourceRules.identifierStart(type.charAt(i))) {
                    while (end < type.length() && SourceRules.identifierPart(type.charAt(end))) end++;
                    String name = type.substring(i, end); result.append(renames.getOrDefault(name, name));
                } else result.append(type.charAt(i));
                i = end;
            }
            return result.toString();
        }
        /** "(*)" for a flexible parameter, "*" for a pointer, "" for a literal object or scalar. */
        private static String classSuffix(String type) { return type.endsWith("(*)") ? "(*)" : type.endsWith("*") ? "*" : ""; }
        private void assignFunctionIds() {
            Set<Integer> used = new HashSet<>(), selfReserved = new HashSet<>();
            Map<Integer,AzFunction> owners = new HashMap<>();
            for (ExternSignature imported : script.externSignatures.values()) {
                boolean self = script.namespaceHint.isEmpty() ? (imported.id >>> 16) == script.functionNamespace
                        : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16);
                if (self) selfReserved.add((script.functionNamespace << 16) | (imported.id & 0xffff));
            }
            for (AzFunction function : script.functions) {
                Integer fixed = function.ownerClass == null ? specialNames.get(function.name) : null;
                if (!script.namespaceHint.isEmpty() && function.name.equals("main")) fixed = null;
                ExternSignature imported = script.externSignatures.get(function.name);
                if (fixed != null) {
                    if (function.explicitPosition != null && function.explicitPosition != (fixed & 0xffff))
                        throw error("Explicit ID disagrees with lifecycle function " + function.name, functionLocation(function));
                    function.id = fixed;
                } else {
                    Integer position = function.explicitPosition;
                    boolean selfImport = imported != null && (script.namespaceHint.isEmpty()
                            ? (imported.id >>> 16) == script.functionNamespace
                            : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16));
                    if (selfImport) {
                        if (position != null && position != (imported.id & 0xffff))
                            throw error("Definition ID disagrees with extern declaration: " + function.name, functionLocation(function));
                        position = imported.id & 0xffff;
                    }
                    if (position == null) continue;
                    function.id = (script.functionNamespace << 16) | position;
                    if (function.id == 0 || function.id == 1 || function.id == 0x0fff0000)
                        throw error("Explicit function ID is reserved: " + function.name, functionLocation(function));
                    if (selfReserved.contains(function.id) && !selfImport)
                        throw error("Explicit function ID is reserved by a self extern declaration: " + function.name, functionLocation(function));
                }
                AzFunction earlier = owners.putIfAbsent(function.id, function);
                if (!used.add(function.id)) {
                    Token first = functionLocation(earlier != null ? earlier : function), second = functionLocation(function);
                    throw error(duplicate("Duplicate function ID: " + Diagnostic.hexId(function.id), first, second), second,
                            earlier == null ? List.of() : noteAt(first, "previous definition is here"));
                }
                function.explicitPosition = function.id & 0xffff;
            }
            // IDs an automatic one must avoid besides the used ones: lifecycle IDs and every extern ID.
            Set<Integer> taken = new HashSet<>(specialNames.values());
            for (ExternSignature imported : script.externSignatures.values()) {
                boolean selfImport = script.namespaceHint.isEmpty() ? (imported.id >>> 16) == script.functionNamespace
                        : Objects.equals(script.assumeHints.get(script.namespaceHint), imported.id >>> 16);
                if (selfImport) used.add((script.functionNamespace << 16) | (imported.id & 0xffff));
                taken.add(imported.id);
            }
            for (AzFunction function : script.functions) {
                if (function.explicitPosition == null) {
                    do {
                        if (nextFunctionPosition > 0xffff) throw error("Namespace has no free function IDs", functionLocation(function));
                        function.id = (script.functionNamespace << 16) | nextFunctionPosition++;
                        diagnostics.metrics().idProbes++; diagnostics.metrics().tableProbes += 2;
                    } while (used.contains(function.id) || taken.contains(function.id));
                    used.add(function.id);
                }
                if ((function.id == 0 || function.id == 1) && (!function.returnType.equals("void") || !function.params.isEmpty()))
                    throw error(function.name + " must be declared as void with no parameters", functionLocation(function));
                script.name2Id.put(function.name, function.id);
            }
            script.externSignatures.forEach((name, signature) -> script.name2Id.put(name, signature.id));
        }
        private void classDeclaration() {
            Token classToken = name(); String className = classToken.text();
            try { identifier(className); } catch (CompileException e) { throw e; } catch (IllegalArgumentException e) { throw error(e.getMessage(), classToken); }
            if (TYPES.contains(className) || Compiler.isBuiltinName(className) || definitions.containsKey(className)
                    || script.externSignatures.containsKey(className) || script.globalDirectives.containsKey(className)) {
                List<Diagnostic> notes = new ArrayList<>();
                if (definitions.containsKey(className)) notes.addAll(noteAt(functionLocation(definitions.get(className)), "previous definition is here"));
                else if (script.externSignatures.containsKey(className) && externTokens.containsKey(script.externSignatures.get(className)))
                    notes.addAll(noteAt(externTokens.get(script.externSignatures.get(className)), "previous declaration is here"));
                else if (script.globalDirectives.containsKey(className))
                    notes.addAll(noteAt(directive(script.globalDirectives.get(className), classToken), "previous declaration is here"));
                throw error("Duplicate or reserved class name: " + className, classToken, notes);
            }
            JsonObject previous = declaredClasses.get(className); diagnostics.metrics().tableProbes++;
            if (previous != null) {
                Token first = classLocation(previous);
                throw error(duplicate("Duplicate class: " + className, first, classToken), classToken,
                        noteAt(first, "previous definition is here"));
            }
            Set<String> outer = typeParameters; typeParameters = new LinkedHashSet<>(outer);
            try {
            JsonObject definition = new JsonObject(); definition.addProperty("name", className);
            position(definition, classToken.unit(), classToken.line(), classToken.column()); classTokens.put(definition, classToken);
            JsonArray generic = readTypeParameters();
            if (!generic.isEmpty()) definition.add("type-parameters", generic);
            classTypeParameters.put(className, parameterNames(generic));
            if (match(":")) {
                match("public");
                if (at("private") || at("protected") || at("virtual")) throw error("Only public single inheritance is supported");
                String base = readType(false);
                if (!classType(base) || !classSuffix(base).isEmpty()) throw error("A base must be a class value type");
                definition.addProperty("base", base);
                if (at(",")) throw error("Multiple inheritance is not supported");
            }
            JsonArray fields = new JsonArray(); JsonObject methods = new JsonObject(), operators = new JsonObject();
            Map<String,JsonArray> modifiedMethods = new LinkedHashMap<>();
            for (String modifier : List.of("virtual", "override", "final")) modifiedMethods.put(modifier, new JsonArray());
            definition.add("fields", fields); definition.add("methods", methods); script.classes.add(definition);
            declaredClasses.put(className, definition);
            Set<String> members = new HashSet<>(); boolean constructor = false, destructor = false;
            expect("{");
            while (!at("}")) {
                if (at("<eof>")) throw error("Unterminated class " + className);
                if (match(";")) continue;
                int memberStart = index;
                Set<String> classScope = typeParameters; typeParameters = new LinkedHashSet<>(classScope);
                try {
                DeclarationPrefix prefix = declarationPrefix(true);
                boolean external = prefix.external(); JsonArray methodGeneric = prefix.generic();
                Token modifier = prefix.modifiers().isEmpty() ? null : prefix.modifiers().values().iterator().next();
                if (modifier != null && !methodGeneric.isEmpty())
                    throw error("Methods with virtual modifiers cannot declare their own type parameters", modifier);
                if (match("~")) {
                    if (modifier != null) throw error("Virtual modifiers are only supported on ordinary member methods", modifier);
                    if (destructor) throw error("Duplicate destructor for " + className);
                    expect(className);parseFunction(className,"destructor","void","<dtor>",external,true,methodGeneric);
                    definition.addProperty("destructor",className+"::<dtor>");destructor=true;
                } else if (at(className) && tokens.get(index + 1).text().equals("(")) {
                    if (modifier != null) throw error("Virtual modifiers are only supported on ordinary member methods", modifier);
                    if (constructor) throw error("Constructor overloading is not supported for " + className);
                    advance();parseFunction(className,"constructor","void","<ctor>",external,true,methodGeneric);
                    definition.addProperty("constructor",className+"::<ctor>");constructor=true;
                } else {
                    Token start=current();String type=readType(true);String member=methodName();
                    if (!members.add(member)) throw error("Duplicate class member: " + className + "." + member);
                    if (modifier != null && (member.startsWith("<op:") || !at("(")))
                        throw error("Virtual modifiers are only supported on ordinary member methods", modifier);
                    if (modifier != null || member.startsWith("<op:") || at("(") && !fieldConstruction()) {
                        parseFunction(className,"method",type,member,external,true,methodGeneric,modifier != null);
                        if (member.startsWith("<op:")) operators.addProperty(operatorKind(member), className+"::"+member);
                        else methods.addProperty(member,className+"::"+member);
                        for (String name : prefix.modifiers().keySet()) modifiedMethods.get(name).add(member);
                    } else {
                        if (external) throw error("Fields cannot be extern");
                        if (!methodGeneric.isEmpty()) throw error("Fields cannot declare type parameters");
                        if (type.equals("void")) throw error("Field cannot have type void");
                        JsonObject field=new JsonObject();field.addProperty("name",member);field.addProperty("type",type);
                        position(field,start.unit(),start.line(),start.column());
                        if (at("(")) {
                            // Only an embedded literal object takes constructor arguments.
                            if (type.endsWith("*")) throw error("A pointer field cannot own an automatic object; initialize it with = new " + type.substring(0, type.length() - 1) + "(...)");
                            if (!classType(type)) throw error("Only literal-object fields take constructor arguments");
                            advance();List<JsonElement> arguments=new ArrayList<>();arguments.add(new JsonPrimitive(type));
                            if(!at(")"))do{arguments.add(expression());}while(match(","));
                            expect(")");field.add("initializer",operation("ctrl","object-value",start,arguments.toArray(JsonElement[]::new)));
                        } else if(match("="))field.add("initializer",expression());
                        endStatement();fields.add(field);
                    }
                }
                } catch (IllegalArgumentException e) {
                    recover(e, memberStart, Sync.MEMBER);
                } finally { typeParameters = classScope; }
            }
            expect("}");match(";");
            if (!operators.isEmpty()) definition.add("operators", operators);
            for (var entry : modifiedMethods.entrySet()) if (!entry.getValue().isEmpty()) definition.add(entry.getKey() + "-methods", entry.getValue());
            if(!constructor) {
                AzFunction function=new AzFunction();function.ownerClass=className;function.functionKind="constructor";
                function.name=className+"::<ctor>";function.returnType="void";function.params.add("this");function.paramTypes.add(selfType(className)+"*");
                function.file=classToken.unit();function.line=classToken.line();function.column=classToken.column();functionTokens.put(function,classToken);
                function.body=JsonArray::new;addDefinition(function);definition.addProperty("constructor",function.name);
            }
            } finally { typeParameters = outer; }
        }
        private void addDefinition(AzFunction function) {
            AzFunction earlier = definitions.putIfAbsent(function.name, function);
            if (earlier != null) {
                Token first = functionLocation(earlier), second = functionLocation(function);
                throw error(duplicate("Duplicate function definition: " + function.name, first, second), second,
                        noteAt(first, "previous definition is here"));
            }
            script.functions.add(function);
        }
        private static String operatorKind(String member) { return member.substring(4, member.length() - 1); }
        private String methodName() {
            if (!match("operator")) return declaredName().text();
            String kind;
            if (match("+")) kind = "add";
            else if (match("-")) kind = "subtract";
            else if (match("*")) kind = "multiply";
            else if (match("/")) kind = "divide";
            else if (match("[")) { expect("]"); kind = match("=") ? "index-set" : "index-get"; }
            else if (match("(")) { expect(")"); kind = "call"; }
            else throw error("Supported operators are +, -, *, /, [], []= and ()");
            return "<op:" + kind + ">";
        }
        private void parseFunction(String owner,String kind,String type,String member,boolean external,boolean inClass,JsonArray generic) {
            parseFunction(owner,kind,type,member,external,inClass,generic,false);
        }
        private void parseFunction(String owner,String kind,String type,String member,boolean external,boolean inClass,JsonArray generic,boolean modifiedMember) {
            String qualified=owner==null?member:owner+"::"+member;
            if(Compiler.isBuiltinName(qualified)||owner==null&&classNames.contains(qualified))throw error("Reserved function name: "+qualified);
            AzFunction function=new AzFunction();function.name=qualified;function.ownerClass=owner;function.functionKind=kind;function.returnType=type;
            if (member.startsWith("<op:")) function.operatorKind = operatorKind(member);
            if (function.operatorKind != null && !generic.isEmpty())
                throw error("Operators cannot declare their own type parameters");
            if (!generic.isEmpty() && ("constructor".equals(kind) || "destructor".equals(kind)))
                throw error("Constructors and destructors cannot declare their own type parameters");
            if (!generic.isEmpty() && owner == null && specialNames.containsKey(member)
                    && !(member.equals("main") && !script.namespaceHint.isEmpty()))
                throw error("Entry and lifecycle functions cannot declare type parameters");
            function.typeParameters = generic.deepCopy();
            Token location=current();functionTokens.put(function,location);
            function.file=location.unit();function.line=location.line();function.column=location.column();
            if(owner!=null){function.params.add("this");function.paramTypes.add(selfType(owner)+"*");}
            Set<String> parameterNames=new HashSet<>(function.params);
            expect("(");
            if(!at(")"))do {
                String parameter=name().text(),parameterType="any";
                if(parameter.equals("void"))throw error("void cannot be a parameter type");
                if(knownType(parameter)) {
                    parameterType=typeApplication(sourceType(parameter,false));
                    // "C (*) p" accepts a C pointer or a C literal object, which is passed by address.
                    if(flexibleAhead()) {
                        if(!classType(parameterType))throw error("Only class parameters can use (*)");
                        advance();advance();advance();parameterType+="(*)";
                    } else parameterType=pointerSuffix(parameterType);
                    parameter=external&&(at(",")||at(")"))?"<argument:"+function.params.size()+">":declaredName().text();
                } else if(external)throw error("External parameters require explicit types");
                diagnostics.metrics().tableProbes++;
                if(!parameterNames.add(parameter))throw error("Duplicate parameter: "+parameter);
                function.params.add(parameter);function.paramTypes.add(parameterType);
            }while(match(","));
            expect(")");
            if (function.operatorKind != null) {
                int count = function.params.size() - 1;
                int expected = function.operatorKind.equals("index-set") ? 2 : 1;
                if (!function.operatorKind.equals("call") && count != expected)
                    throw error("Operator " + function.operatorKind + " expects " + expected + " explicit parameters");
                if (function.paramTypes.contains("any")) throw error("Operator parameters require explicit types");
                if (function.operatorKind.equals("index-set") && !type.equals("void"))
                    throw error("Index setter must return void");
                if (!function.operatorKind.equals("index-set") && !function.operatorKind.equals("call") && type.equals("void"))
                    throw error("Operator " + function.operatorKind + " requires a non-void return type");
            }
            Integer suffix=null;
            if(match(":")) {
                boolean initializer="constructor".equals(kind)&&current().identifier()
                        && (tokens.get(index+1).text().equals("(") || tokens.get(index+1).text().equals("<"));
                if(!initializer) {
                    Token token=advance();
                    try{suffix=SourceRules.sourceHex(token.text(),external?8:4,"function ID");}catch(CompileException e){throw e;}catch(IllegalArgumentException e){throw error(e.getMessage(),token);}
                    initializer=match(":");
                }
                if(initializer) {
                    if(external||!"constructor".equals(kind))throw error("Only a constructor definition may initialize a base class");
                    function.baseInitializer=typeApplication(sourceType(name().text(),false));function.baseArguments=new JsonArray();expect("(");
                    if(!at(")"))do{function.baseArguments.add(expression());}while(match(","));
                    expect(")");
                    if(at(","))throw error("Only one direct base initializer is supported");
                }
            }
            if("destructor".equals(kind)&&function.params.size()!=1)throw error("Destructor must have no parameters");
            if(external) {
                if(suffix==null)throw error("Extern function requires a full hexadecimal ID");
                if(SourceRules.reservedExternalId(suffix))throw error("Reserved external function ID");
                expect(";");ExternSignature signature=new ExternSignature(type,function.paramTypes);signature.id=suffix;
                signature.file=location.unit();signature.line=location.line();signature.column=location.column();externTokens.put(signature,location);
                signature.ownerClass=owner;signature.functionKind=kind;
                signature.operatorKind=function.operatorKind;
                signature.typeParameters=generic.deepCopy();
                ExternSignature previous=script.externSignatures.putIfAbsent(qualified,signature);
                if(previous!=null) {
                    if(previous.id!=signature.id || !Objects.equals(previous.ownerClass,owner) || !Objects.equals(previous.functionKind,kind))
                        throw conflictingExtern(qualified,previous,signature);
                    duplicateExterns.add(new DuplicateExtern(qualified,previous,signature));
                }
            } else {
                if (modifiedMember && (at(";") || at("="))) throw error("abstract methods are not supported");
                if(inClass&&!script.namespaceHint.isEmpty())throw error("Hinted library class functions must use extern declarations and out-of-class definitions");
                function.explicitPosition=suffix;int enclosingLoop=loopDepth,enclosingSwitch=switchDepth;loopDepth=0;switchDepth=0;
                JsonElement body;try{body=block();}finally{loopDepth=enclosingLoop;switchDepth=enclosingSwitch;}function.body=()->body;
                Token end=tokens.get(index-1);
                if(end.unit()==function.file){function.endLine=end.line();function.endColumn=end.column();}
                addDefinition(function);match(";");
            }
        }
        JsonElement block() {
            enterRecursion();
            try { return blockBody(); } finally { recursionDepth--; }
        }
        private JsonElement blockBody() {
            expect("{"); JsonArray body = new JsonArray();
            while (!at("}")) {
                if (at("<eof>")) throw error("Unterminated block");
                if (match(";")) continue;
                int start = index;
                try { body.add(statement()); }
                catch (IllegalArgumentException e) { recover(e, start, Sync.STATEMENT); }
            }
            expect("}"); return body;
        }
        private void endStatement() {
            if (!match(";") && !at("}") && !at("<eof>")) throw error("Expected ';' after statement");
        }
        private JsonElement statement() {
            enterRecursion();
            try { return statementBody(); } finally { recursionDepth--; }
        }
        private JsonElement statementBody() {
            if (match(";")) return new JsonArray();
            if (at("{")) return block();
            Token start = current();
            if (at("case") || at("default"))
                throw error("'" + start.text() + "' can only be used directly inside a switch", start);
            if (match("switch")) return switchStatement(start);
            if (match("return")) {
                JsonElement result;
                if (at(";") || at("}")) result = operation("ctrl", "return", start);
                else if (at("(") && tokens.get(index + 1).text().equals(")")) {
                    advance(); advance(); result = operation("ctrl", "return", start);
                } else result = operation("ctrl", "return", start, expression());
                endStatement(); return result;
            }
            if (at("break") || at("continue")) {
                String control = advance().text();
                if (loopDepth == 0 && switchDepth == 0)
                    throw error("'" + control + "' can only be used inside a loop" + (control.equals("break") ? " or switch" : ""), start);
                JsonElement result = operation("ctrl", control, start);
                endStatement(); return result;
            }
            if (match("for")) {
                expect("("); JsonElement initializer = new JsonArray();
                if (!match(";")) {
                    if (at("def") || at("var") || declarationAhead()) initializer = statement();
                    else { initializer = statementExpression(); expect(";"); }
                }
                JsonElement condition = at(";") ? new JsonPrimitive(true) : expression(); expect(";");
                JsonElement step = at(")") ? new JsonArray() : statementExpression(); expect(")");
                JsonElement body; loopDepth++;
                try { body = statement(); } finally { loopDepth--; }
                return operation("ctrl", "for", start, initializer, condition, step, body);
            }
            if (match("delete")) {
                JsonElement result = operation("ctrl", "object-delete", start, expression());
                endStatement(); return result;
            }
            if (at("if")) return ifStatement();
            if (match("while")) {
                expect("("); JsonElement condition = expression();
                JsonElement body;
                loopDepth++;
                try {
                    if (match(",")) {
                        body = at("{") ? block() : expression();
                        expect(")");
                        // The original functional spelling remains accepted.
                        endStatement();
                    } else {
                        expect(")"); body = statement();
                        match(";");
                    }
                } finally { loopDepth--; }
                return operation("ctrl", "while", start, condition, body);
            }
            if (match("def")) {
                expect("("); String variable = declaredName().text();
                JsonElement initializer = match(",") ? expression() : null;
                expect(")"); endStatement();
                return initializer == null ? operation("ctrl", "vardef", start, new JsonPrimitive(variable))
                        : operation("ctrl", "vardef", start, new JsonPrimitive(variable), initializer);
            }
            if (at("var") || declarationAhead()) {
                String spelling = advance().text();
                String declaredType = spelling.equals("var") ? null : pointerSuffix(typeApplication(sourceType(spelling, false)));
                String variable = declaredName().text();
                boolean pointer = declaredType != null && declaredType.endsWith("*");
                String valueClass = pointer ? declaredType.substring(0, declaredType.length() - 1) : declaredType;
                // "C * p(args)" is an automatic pointer object; a bare "C * p;" is an empty (null) pointer.
                if (classNames.contains(spelling) && !at("=") && (!pointer || at("("))) {
                    List<JsonElement> arguments = new ArrayList<>();
                    if (pointer) arguments.add(new JsonPrimitive(variable));
                    arguments.add(new JsonPrimitive(valueClass));
                    if (match("(")) {
                        if (!at(")")) do { arguments.add(expression()); } while (match(","));
                        expect(")");
                    }
                    endStatement();
                    if (pointer) return operation("ctrl", "object-def", start, arguments.toArray(JsonElement[]::new));
                    JsonObject definition = operation("ctrl", "vardef", start, new JsonPrimitive(variable),
                            operation("ctrl", "object-value", start, arguments.toArray(JsonElement[]::new)));
                    definition.addProperty("declared-type", declaredType);
                    return definition;
                }
                JsonElement initializer = match("=") ? expression()
                        : pointer && classNames.contains(spelling) ? operation("ctrl", "null", start) : null;
                if (spelling.equals("buffer") && initializer == null)
                    initializer = operation("ctrl", "buffer-new", start, new JsonPrimitive(declaredType.substring(7, declaredType.length() - 1)));
                if (typeParameters.contains(spelling) && initializer == null)
                    throw error("A type parameter local requires an explicit initializer: " + variable, start);
                endStatement();
                JsonObject definition = initializer == null
                        ? operation("ctrl", "vardef", start, new JsonPrimitive(variable))
                        : operation("ctrl", "vardef", start, new JsonPrimitive(variable), initializer);
                if (declaredType != null) definition.addProperty("declared-type", declaredType);
                return definition;
            }
            JsonElement expression = statementExpression(); endStatement(); return expression;
        }
        /** Else-if links are siblings while parsing; true statement nesting still uses statement(). */
        private JsonElement ifStatement() {
            Token first = current(); List<JsonObject> branches = new ArrayList<>();
            JsonElement otherwise = null;
            while (true) {
                Token start = current(); expect("if"); expect("("); JsonElement condition = expression();
                if (match(",")) {
                    // Functional if keeps its original expression/body spelling and AST.
                    JsonElement body = at("{") ? block() : expression();
                    JsonElement fallback = match(",") ? (at("{") ? block() : expression()) : null;
                    expect(")"); endStatement();
                    otherwise = fallback == null ? operation("ctrl", "if", start, condition, body)
                            : operation("ctrl", "if", start, condition, body, fallback);
                    if (branches.isEmpty()) return otherwise;
                    break;
                }
                expect(")"); JsonElement body = statement();
                branches.add(operation("ctrl", "if", start, condition, body));
                if (!match("else")) break;
                if (!at("if")) { otherwise = statement(); break; }
            }
            // The recursive parser accepted one optional trailing semicolon per link.
            for (int i = 0; i < branches.size(); i++) match(";");
            if (branches.size() > 128) {
                JsonObject chain = operation("ctrl", "if-chain", first, branches.toArray(JsonElement[]::new));
                if (otherwise != null) chain.add("else", otherwise);
                return chain;
            }
            // Preserve field order, positions and the exact legacy tree through 128 conditions.
            for (int i = branches.size() - 1; i >= 0; i--) {
                JsonObject branch = branches.get(i);
                if (otherwise != null) branch.getAsJsonArray("param").add(otherwise);
                otherwise = branch;
            }
            return otherwise;
        }
        private JsonElement switchStatement(Token start) {
            expect("("); JsonElement value = expression(); expect(")"); expect("{");
            JsonArray sections = new JsonArray(), body = null;
            switchDepth++;
            try {
                while (!at("}")) {
                    if (at("<eof>")) throw error("Unterminated switch");
                    if (at("case") || at("default")) {
                        Token label = advance(); JsonArray labels = new JsonArray();
                        boolean fallback = label.text().equals("default");
                        if (!fallback) labels.add(expression());
                        expect(":"); body = new JsonArray();
                        JsonObject section = operation("ctrl", "case", label, body);
                        section.add("labels", labels);
                        if (fallback) section.addProperty("default", true);
                        sections.add(section);
                    } else {
                        if (body == null) throw error("A switch statement cannot contain statements before its first case or default label");
                        int statementStart = index;
                        try { body.add(statement()); }
                        catch (IllegalArgumentException e) { recover(e, statementStart, Sync.STATEMENT); }
                    }
                }
                expect("}");
            } finally { switchDepth--; }
            return operation("ctrl", "switch", start, value, sections);
        }
        /** Update operators are statement sugar, so prefix and postfix have no result-value distinction. */
        private JsonElement statementExpression() {
            Token operator = at("++") || at("--") ? advance() : null;
            JsonElement value = expression();
            if (operator == null && (at("++") || at("--"))) operator = advance();
            if (operator == null) return value;
            if (!value.isJsonObject() || !value.getAsJsonObject().get("t").getAsString().equals("ctrl")
                    || !value.getAsJsonObject().get("call").getAsString().equals("var"))
                throw error("Increment/decrement requires a simple variable and is only supported as a statement or for step");
            return operation("ctrl", operator.text().equals("++") ? "increment" : "decrement", operator, value);
        }
        JsonElement expression() { return assignment(); }
        private JsonElement assignment() {
            enterRecursion();
            try { return assignmentBody(); } finally { recursionDepth--; }
        }
        private static final Set<String> ASSIGNMENT_OPERATORS = Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>="),
                ASSIGNMENT_CALLS = Set.of("varset", "member-set", "index-set", "mov");
        private JsonElement assignmentBody() {
            JsonElement left = conditional();
            String assignmentOperator = operatorText();
            if (ASSIGNMENT_OPERATORS.contains(assignmentOperator)) {
                Token operator = advanceOperator(assignmentOperator);
                JsonObject target = left.isJsonObject() ? left.getAsJsonObject() : null;
                boolean variable = target != null && target.get("t").getAsString().equals("ctrl")
                        && target.get("call").getAsString().equals("var");
                boolean memory = target != null && target.get("t").getAsString().equals("call")
                        && isMemoryGet(target.get("call").getAsString());
                boolean member = target != null && target.get("t").getAsString().equals("ctrl")
                        && target.get("call").getAsString().equals("member");
                boolean indexed = target != null && target.get("t").getAsString().equals("ctrl")
                        && target.get("call").getAsString().equals("index");
                if (!variable && !memory && !member && !indexed)
                    throw error("Left side of assignment must be a variable, member, index or mem_get(pointer)");
                if (member && !operator.text().equals("=")) throw error("Compound assignment to a member is not supported");
                if (indexed && !operator.text().equals("=")) throw error("Compound assignment to an index is not supported");
                if (memory && !operator.text().equals("="))
                    throw error("Compound assignment to mem_get(pointer) is not supported; save the pointer in a variable and use '='");
                JsonElement value = assignment();
                if (indexed) return operation("ctrl", "index-set", operator,
                        target.getAsJsonArray("param").get(0), target.getAsJsonArray("param").get(1), value);
                if (member) return operation("ctrl", "member-set", operator, left, value);
                if (memory) return operation("ctrl", "mov", operator, left, value);
                if (!operator.text().equals("=")) value = operation("ctrl", binaryName(operator.text().substring(0, operator.text().length() - 1)), operator, left, value);
                JsonObject assignment = operation("ctrl", "varset", operator, target.getAsJsonArray("param").get(0), value);
                if (!operator.text().equals("=")) assignment.addProperty("compound", true);
                return assignment;
            }
            return left;
        }
        /** The surrounding assignment guard bounds right-associative conditional nesting. */
        private JsonElement conditional() {
            JsonElement condition = binary(0);
            if (!at("?")) return condition;
            Token question = advance(); JsonElement yes = assignment(); expect(":"); JsonElement no = assignment();
            return operation("ctrl", "select", question, condition, yes, no);
        }
        private static boolean isMemoryGet(String call) {
            return call.equals("mem_get");
        }
        private static final List<Set<String>> PRECEDENCE = List.of(Set.of("||"), Set.of("&&"), Set.of("|"), Set.of("^"), Set.of("&"),
                Set.of("==", "!="), Set.of("<", ">", "<=", ">="), Set.of("<<", ">>", ">>>"), Set.of("+", "-"), Set.of("*", "/", "%"));
        /** Angle tokens stay separate for generic types; only expression parsing joins adjacent ones. */
        private String operatorText() {
            Token first = current(); String angle = first.text();
            if ((!angle.equals("<") && !angle.equals(">")) || index + 1 >= tokens.size()) return angle;
            Token second = tokens.get(index + 1);
            if (first.end() != second.start() || !(second.text().equals(angle) || second.text().equals(angle + "="))) return angle;
            String text = angle + second.text();
            if (angle.equals(">") && second.text().equals(">") && index + 2 < tokens.size()) {
                Token third = tokens.get(index + 2);
                if (second.end() == third.start() && (third.text().equals(">") || third.text().equals(">="))) text += third.text();
            }
            return text;
        }
        /** Consumes either one lexical token or a shift assembled by operatorText(). */
        private Token advanceOperator(String text) {
            Token first = advance(), last = first;
            for (int width = first.text().length(); width < text.length(); width += last.text().length()) last = advance();
            return first == last ? first : new Token(text, null, first.unit(), first.line(), first.column(), false,
                    first.macro(), first.start(), last.end());
        }
        /** One uninterrupted precedence group; its operands may contain completed tighter groups. */
        private static final class BinaryGroup {
            final int level;
            final List<JsonElement> operands = new ArrayList<>();
            final List<Token> operators = new ArrayList<>();
            BinaryGroup(int level, JsonElement left) { this.level = level; operands.add(left); }
            JsonElement finish() {
                JsonElement left = operands.get(0);
                if (operands.size() <= 128) {
                    for (int i = 0; i < operators.size(); i++)
                        left = operation("ctrl", binaryName(operators.get(i).text()), operators.get(i), left, operands.get(i + 1));
                    return left;
                }
                JsonObject chain = operation("ctrl", "chain", operators.get(operators.size() - 1), operands.toArray(JsonElement[]::new));
                JsonArray entries = new JsonArray();
                for (Token operator : operators) {
                    JsonObject entry = new JsonObject(); entry.addProperty("call", binaryName(operator.text()));
                    position(entry, operator.unit(), operator.line(), operator.column()); entries.add(entry);
                }
                chain.add("operators", entries); return chain;
            }
        }
        private static int binaryLevel(String text) {
            for (int level = 0; level < PRECEDENCE.size(); level++)
                if (PRECEDENCE.get(level).contains(text)) return level;
            return -1;
        }
        private JsonElement binary(int level) {
            JsonElement left = unary(); String text = operatorText();
            if (binaryLevel(text) < level) return left;
            // Precedence is implementation structure, not logical syntax nesting.
            // Keep its pending groups on the heap so each '(' does not retain ten Java frames.
            Deque<BinaryGroup> groups = new ArrayDeque<>();
            while (true) {
                int next = binaryLevel(text);
                if (next < level) break;
                while (!groups.isEmpty() && groups.peek().level > next) {
                    left = groups.pop().finish();
                    if (!groups.isEmpty()) groups.peek().operands.add(left);
                }
                if (groups.isEmpty()) groups.push(new BinaryGroup(next, left));
                else if (groups.peek().level < next) {
                    List<JsonElement> operands = groups.peek().operands;
                    groups.push(new BinaryGroup(next, operands.remove(operands.size() - 1)));
                }
                BinaryGroup group = groups.peek();
                group.operators.add(advanceOperator(text)); group.operands.add(unary());
                text = operatorText();
            }
            while (!groups.isEmpty()) {
                left = groups.pop().finish();
                if (!groups.isEmpty()) groups.peek().operands.add(left);
            }
            return left;
        }
        private static String binaryName(String text) {
            return switch (text) {
                case "+" -> "add"; case "-" -> "minus"; case "*" -> "multiply"; case "/" -> "divide";
                case "%" -> "mod"; case "==" -> "cmp"; case "!=" -> "ne"; case ">" -> "greater";
                case "<" -> "lower"; case ">=" -> "ge"; case "<=" -> "le"; case "&&" -> "and"; case "||" -> "or";
                case "&" -> "bit_and"; case "|" -> "bit_or"; case "^" -> "bit_xor";
                case "<<" -> "shl"; case ">>" -> "shr"; case ">>>" -> "ushr";
                default -> throw new IllegalArgumentException("Unknown binary operator: " + text);
            };
        }
        private JsonElement unary() {
            enterRecursion();
            try { return unaryBody(); } finally { recursionDepth--; }
        }
        private JsonElement unaryBody() {
            if (at("+") || at("-") || at("!") || at("~")) {
                Token operator = advance(); JsonElement value = unary();
                if (operator.text().equals("+")) return operation("ctrl", "pos", operator, value);
                if (operator.text().equals("~")) return operation("ctrl", "bit_xor", operator, value, new JsonPrimitive(-1));
                if (operator.text().equals("-") && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                    if (value.getAsNumber() instanceof Double) return new JsonPrimitive(-value.getAsDouble());
                    return new JsonPrimitive(value.getAsBigDecimal().negate());
                }
                return operation("ctrl", operator.text().equals("-") ? "neg" : "not", operator, value);
            }
            return postfix();
        }
        private JsonElement postfix() {
            JsonElement value = primary();
            while (true) {
                Token at = current();
                if (match(".")) {
                    Token member = name();
                    if (at("::") && classNames.contains(member.text()))
                        throw error("Write " + member.text() + "::method(...) without a receiver; a qualified call always calls on this", member);
                    JsonArray typeArguments = callTypeArgumentsAhead() ? readCallTypeArguments(false) : null;
                    if (match("(")) {
                        List<JsonElement> args = new ArrayList<>(); args.add(value); args.add(new JsonPrimitive(member.text()));
                        if (!at(")")) do { args.add(expression()); } while (match(","));
                        expect(")"); JsonObject call = operation("ctrl", "member-call", member, args.toArray(JsonElement[]::new));
                        if (typeArguments != null) call.add("type-args", typeArguments);
                        value = call;
                    } else value = operation("ctrl", "member", member, value, new JsonPrimitive(member.text()));
                } else if (match("[")) {
                    JsonElement key = expression(); expect("]");
                    value = operation("ctrl", "index", at, value, key);
                } else if (at("(") || callTypeArgumentsAhead()) {
                    JsonArray typeArguments = at("<") ? readCallTypeArguments(false) : null;
                    expect("("); List<JsonElement> args = new ArrayList<>(); args.add(value);
                    if (!at(")")) do { args.add(expression()); } while (match(","));
                    expect(")"); JsonObject call = operation("ctrl", "invoke", at, args.toArray(JsonElement[]::new));
                    if (typeArguments != null) call.add("type-args", typeArguments);
                    value = call;
                } else break;
            }
            return value;
        }
        /** Type names cannot also be variables, which disambiguates f<Type>(...) from comparisons. */
        private boolean callTypeArgumentsAhead() {
            if (!at("<") || index + 1 >= tokens.size() || !knownType(tokens.get(index + 1).text())) return false;
            int depth = 0;
            for (int cursor = index; cursor < tokens.size(); cursor++) {
                String token = tokens.get(cursor).text();
                if (token.equals("<")) depth++;
                else if (token.equals(">")) {
                    if (--depth == 0) return cursor + 1 < tokens.size() && tokens.get(cursor + 1).text().equals("(");
                }
                else if (!knownType(token) && !token.equals(",") && !token.equals("*")) return false;
            }
            return false;
        }
        private JsonArray readCallTypeArguments(boolean allowVoid) {
            JsonArray arguments = new JsonArray(); expect("<");
            if (at(">")) throw error("Type argument list cannot be empty");
            do { arguments.add(readType(allowVoid)); } while (match(","));
            expect(">"); return arguments;
        }
        /**
         * {@code Base::method(args)} calls the method visible in the named class (the current
         * class or one of its bases) on {@code this} with static binding. The class is named
         * without type arguments, which come from the inheritance chain.
         */
        private JsonElement baseCall(Token owner) {
            String className = owner.text();
            if (at("<")) throw error("Name a base class without type arguments in " + className + "::method(...); they come from the inheritance chain", owner);
            expect("::");
            if (at("~")) throw error("Destructors cannot be called directly; delete the object or let it expire");
            if (at("operator")) throw error("Operator methods cannot be called as " + className + "::operator...(...); qualified calls name ordinary methods");
            Token member = name();
            JsonArray typeArguments = callTypeArgumentsAhead() ? readCallTypeArguments(false) : null;
            if (!at("(")) throw error("Expected '(' after " + className + "::" + member.text() + "; only qualified method calls are supported");
            expect("(");
            List<JsonElement> arguments = new ArrayList<>();
            arguments.add(new JsonPrimitive(className)); arguments.add(new JsonPrimitive(member.text()));
            if (!at(")")) do { arguments.add(expression()); } while (match(","));
            expect(")");
            JsonObject call = operation("ctrl", "base-call", owner, arguments.toArray(JsonElement[]::new));
            if (typeArguments != null) call.add("type-args", typeArguments);
            return call;
        }
        private JsonElement primary() {
            Token token = current();
            if (at("case") || at("default"))
                throw error("'" + token.text() + "' can only be used directly inside a switch", token);
            if (match("null")) return operation("ctrl", "null", token);
            if (match("this")) return operation("ctrl", "var", token, new JsonPrimitive("this"));
            if (match("new")) {
                String type = name().text();
                if (!classNames.contains(type)) throw error("Unknown class: " + type);
                type = typeApplication(type);
                expect("("); List<JsonElement> args = new ArrayList<>(); args.add(new JsonPrimitive(type));
                if (!at(")")) do { args.add(expression()); } while (match(","));
                expect(")"); return operation("ctrl", "object-new", token, args.toArray(JsonElement[]::new));
            }
            if (token.literal() != null) { advance(); return token.literal(); }
            if (!token.text().isEmpty() && Character.isDigit(token.text().charAt(0))) {
                // Colon-glued IDs were only probed while lexing. An ID-like digit
                // run used as an expression still gets the ordinary numeric error.
                try {
                    Lexer numeric = new Lexer(preprocessed, SourceMap.single("<expression>"), Diagnostics.failFast());
                    numeric.offset = token.start();
                    Token parsed = numeric.numberToken(1, 1, token.start(), true); advance(); return parsed.literal();
                } catch (CompileException error) { throw error(error.errors().get(0).message(), token); }
            }
            if (at("(") && cStyleCastAhead()) throw error("C-style casts are not supported; write int(x)", token);
            if (match("(")) {
                JsonElement value = expression(); expect(")");
                // Diagnostic-only marker: a parenthesized assignment is an intentional value.
                if (value.isJsonObject() && ASSIGNMENT_CALLS.contains(value.getAsJsonObject().get("call").getAsString()))
                    value.getAsJsonObject().addProperty("_parenthesized", true);
                return value;
            }
            if (at("{")) return block();
            if (token.identifier()) {
                String name = advance().text();
                if ((name.equals("boolean") || name.equals("bool")) && at("("))
                    throw error("Boolean conversions are not supported; write a comparison", token);
                if (KEYWORDS.contains(name))
                    throw error("'" + name + "' is " + (name.equals("any") ? "a reserved word" : "a statement") + " and cannot be used in an expression", token);
                if (classNames.contains(name) && classOwnerEnd(index - 1) >= 0) return baseCall(token);
                if (at("::")) throw error(name + " is not a class; " + name + "::method(...) must name the current class or one of its base classes", token);
                boolean reflection = name.equals("reflect_invoke_function");
                JsonArray typeArguments = (reflection && at("<")) || callTypeArgumentsAhead()
                        ? readCallTypeArguments(reflection) : null;
                if (match("(")) {
                    List<JsonElement> arguments = new ArrayList<>();
                    if (!at(")")) do { arguments.add(expression()); } while (match(","));
                    expect(")");
                    JsonObject call = operation("call", name, token, arguments.toArray(JsonElement[]::new));
                    if (typeArguments != null) call.add("type-args", typeArguments);
                    return call;
                }
                return operation("ctrl", "var", token, new JsonPrimitive(name));
            }
            throw error("Expected expression, found '" + token.text() + "'");
        }
        /** Recognises cast-looking parentheses without changing the parser or declaration state. */
        private boolean cStyleCastAhead() {
            if (index + 2 >= tokens.size() || !knownType(tokens.get(index + 1).text())) return false;
            int depth = 0;
            for (int cursor = index + 1; cursor < tokens.size(); cursor++) {
                String text = tokens.get(cursor).text();
                if (text.equals(")") && depth == 0) {
                    if (cursor + 1 >= tokens.size()) return false;
                    Token next = tokens.get(cursor + 1);
                    return next.literal() != null || next.identifier() && (!KEYWORDS.contains(next.text())
                            || Set.of("new", "this", "null").contains(next.text()))
                            || Set.of("(", "+", "-", "!", "~", "{").contains(next.text());
                }
                if (text.equals("<")) depth++;
                else if (text.equals(">")) { if (depth == 0) return false; depth--; }
                else if (!knownType(text) && !text.equals("*") && !text.equals(",")) return false;
            }
            return false;
        }
    }
    public static AzExpression getExpression(String source, int offset, AzScript script) {
        if (offset < 0 || offset > source.length()) throw new IllegalArgumentException("Invalid source offset");
        Parser parser = new Parser(source.substring(offset), SourceMap.single("<expression>"), script, Diagnostics.failFast());
        JsonElement result;
        if (parser.at("{")) result = parser.block();
        else if (Set.of("return", "break", "continue", "def", "var", "if", "while", "for", "switch", "case", "default", "delete", "++", "--").contains(parser.current().text())
                || parser.declarationAhead()) result = parser.statement();
        else result = parser.expression();
        parser.match(";"); parser.expect("<eof>");
        try { TreeLimits.validate(result); }
        catch (TreeLimits.LimitExceeded e) { throw parser.error(e.getMessage(), parser.located(e.anchor(), parser.current())); }
        return () -> result;
    }
    public static class ConstantExpression implements AzExpression {
        private final JsonElement value;
        public ConstantExpression(String value, AzScript script) { this.value = getExpression(value, 0, script).generate(); }
        @Override public JsonElement generate() { return value; }
    }
}
