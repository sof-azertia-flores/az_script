package azertia.script;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.*;

/**
 * Maps preprocessed text back to the original files. Includes are pasted in
 * place and every input line yields exactly one output line, so lines map
 * through a segment table with one record per include entry and return. Lines
 * with macro expansions also keep column runs: verbatim runs map one to one and
 * an expansion maps to the column of its macro name.
 *
 * <p>Each inclusion instance is a unit. The readable AST stores the units as its
 * {@code sources} table, and positioned nodes refer to them with {@code _file}.
 */
public final class SourceMap {
    /** One inclusion instance; {@code parent} is -1 for a unit that was not included. */
    public record Unit(String name, int parent, int line, int column) {}
    /** An original position; {@code macro} is the expanded macro or -1. */
    record Origin(int unit, int line, int column, int macro) {}
    private record Macro(String name, int unit, int line, int column) {}

    private final List<Unit> units = new ArrayList<>();
    private final List<Macro> macros = new ArrayList<>();
    private int[] segmentStart = new int[4], segmentUnit = new int[4], segmentLine = new int[4];
    private int segments, lines;
    private final Map<Integer,int[]> columns = new HashMap<>();
    /** Name for file-level errors, or null for the main unit's name. */
    private String input;

    /** A map whose single unit covers the whole text unchanged. */
    public static SourceMap single(String name) {
        SourceMap map = new SourceMap(); map.addUnit(name, -1, 0, 0); return map;
    }

    int addUnit(String name, int parent, int line, int column) {
        if (parent >= units.size()) throw new IllegalStateException("An include parent must precede its unit");
        units.add(new Unit(name, parent, line, column)); return units.size() - 1;
    }
    public int unitCount() { return units.size(); }
    public Unit unit(int index) { return units.get(index); }
    public String name(int unit) { return units.get(unit >= 0 && unit < units.size() ? unit : 0).name(); }
    int addMacro(String name, int unit, int line, int column) {
        macros.add(new Macro(name, unit, line, column)); return macros.size() - 1;
    }

    /** Records the next output line as {@code sourceLine} of {@code unit}; {@code runs} is null for an unchanged line. */
    void line(int unit, int sourceLine, int[] runs) {
        lines++;
        int last = segments - 1;
        if (segments == 0 || segmentUnit[last] != unit || segmentLine[last] + (lines - segmentStart[last]) != sourceLine) {
            if (segments == segmentStart.length) {
                segmentStart = Arrays.copyOf(segmentStart, segments * 2);
                segmentUnit = Arrays.copyOf(segmentUnit, segments * 2);
                segmentLine = Arrays.copyOf(segmentLine, segments * 2);
            }
            segmentStart[segments] = lines; segmentUnit[segments] = unit; segmentLine[segments] = sourceLine; segments++;
        }
        if (runs != null) columns.put(lines, runs);
    }

    /** The original position of a 1-based line and column of the preprocessed text. */
    Origin resolve(int outLine, int outColumn) {
        if (segments == 0) return new Origin(0, outLine, outColumn, -1);
        int line = Math.max(1, Math.min(outLine, lines));
        int low = 0, high = segments - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (segmentStart[middle] <= line) low = middle; else high = middle - 1;
        }
        int unit = segmentUnit[low], source = segmentLine[low] + (line - segmentStart[low]);
        int[] runs = columns.get(line);
        if (runs == null) return new Origin(unit, source, outColumn, -1);
        // Runs are ordered by output column; take the last one starting at or before the column.
        int first = 0, last = runs.length / 3 - 1;
        while (first < last) {
            int middle = (first + last + 1) >>> 1;
            if (runs[middle * 3] <= outColumn) first = middle; else last = middle - 1;
        }
        int run = first * 3;
        if (runs[run + 2] >= 0) return new Origin(unit, source, runs[run + 1], runs[run + 2]);
        return new Origin(unit, source, runs[run + 1] + (outColumn - runs[run]), -1);
    }

    /** Collects the column runs of one output line while macros expand. */
    static final class Columns {
        private int[] runs = new int[12];
        private int size, current;
        private boolean expanded;
        /** A piece copied from 0-based source index to 0-based output index. */
        void verbatim(int outIndex, int sourceIndex) {
            current = sourceIndex;
            if (size > 0 && runs[size - 1] < 0 && runs[size - 3] - runs[size - 2] == outIndex - sourceIndex) return;
            add(outIndex + 1, sourceIndex + 1, -1);
        }
        /** The current piece is instead an expansion of the macro name at 0-based source index. */
        void macro(int outIndex, int sourceIndex, int macro) {
            if (size > 0 && runs[size - 3] == outIndex + 1) size -= 3;
            add(outIndex + 1, sourceIndex + 1, macro); expanded = true;
        }
        /** 1-based source column of the piece being expanded, for expansion errors. */
        int errorColumn() { return current + 1; }
        private void add(int out, int source, int macro) {
            if (size + 3 > runs.length) runs = Arrays.copyOf(runs, runs.length * 2);
            runs[size++] = out; runs[size++] = source; runs[size++] = macro;
        }
        /** The runs, or null when no macro expanded and columns are unchanged. */
        int[] build() { return expanded ? Arrays.copyOf(runs, size) : null; }
    }

    public Diagnostic.Position position(int unit, int line, int column) {
        return new Diagnostic.Position(name(unit), line, column);
    }
    /** "in file included from here" notes for a unit, innermost first. */
    public List<Diagnostic> includeNotes(int unit) {
        List<Diagnostic> notes = new ArrayList<>();
        if (unit < 0 || unit >= units.size()) return notes;
        // Parents always precede their unit, so the walk terminates.
        for (Unit current = units.get(unit); current.parent() >= 0; current = units.get(current.parent()))
            notes.add(Diagnostic.note(position(current.parent(), current.line(), current.column()), "in file included from here"));
        return notes;
    }
    /**
     * The "in expansion of macro" note, followed by the include chain of the #define
     * when it lies in another unit than the use (whose chain precedes this note).
     */
    List<Diagnostic> macroNotes(int macro, int useUnit) {
        if (macro < 0 || macro >= macros.size()) return List.of();
        Macro definition = macros.get(macro);
        List<Diagnostic> notes = new ArrayList<>();
        notes.add(Diagnostic.note(position(definition.unit(), definition.line(), definition.column()),
                "in expansion of macro '" + definition.name() + "' defined here"));
        if (definition.unit() != useUnit) notes.addAll(includeNotes(definition.unit()));
        return notes;
    }
    /** An error at an original position with its include notes, then its macro notes. */
    Diagnostic error(int unit, int line, int column, int macro, String context, String message) {
        List<Diagnostic> notes = includeNotes(unit); notes.addAll(macroNotes(macro, unit));
        return Diagnostic.error(position(unit, line, column), context, message, notes).inUnit(unit);
    }
    /** Names the compiled input in file-level errors instead of the main unit, e.g. the compile-json file. */
    SourceMap input(String name) { input = name; return this; }
    /** A file-level error, for an error without a source position: it names the compiled input. */
    Diagnostic error(String context, String message) {
        return Diagnostic.error(Diagnostic.Position.file(inputName()), context, message, List.of());
    }

    /** The unit of a positioned AST node; a missing or invalid {@code _file} means the main unit. */
    int unitOf(JsonObject node) {
        int unit = integer(node.get("_file"));
        return unit >= 0 && unit < units.size() ? unit : 0;
    }
    /** The position stored in an AST node's {@code _line}/{@code _column}, or null when it has none. */
    public Diagnostic.Position position(JsonObject node) { return position(node, "_line", "_column"); }
    Diagnostic.Position position(JsonObject node, String lineKey, String columnKey) {
        if (node == null) return null;
        int line = integer(node.get(lineKey));
        if (line <= 0) return null;
        return position(unitOf(node), line, Math.max(0, integer(node.get(columnKey))));
    }
    /** An error at a node, or a file-level error when the node has no position. */
    Diagnostic error(JsonObject node, String context, String message) {
        Diagnostic.Position at = position(node);
        if (at == null) return error(context, message);
        return Diagnostic.error(at, context, message, includeNotes(unitOf(node))).inUnit(unitOf(node));
    }
    /** The name of the compiled input for file-level errors. */
    String inputName() { return input != null ? input : name(0); }
    /** A non-negative int stored in a JSON number, or -1. Never throws on malformed input. */
    static int integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return -1;
        try { return Math.max(-1, value.getAsBigDecimal().intValueExact()); }
        catch (ArithmeticException | NumberFormatException e) { return -1; }
    }

    /** The AST {@code sources} table. */
    public JsonArray toJson() {
        JsonArray result = new JsonArray();
        for (Unit unit : units) {
            JsonObject value = new JsonObject(); value.addProperty("name", unit.name());
            if (unit.parent() >= 0) {
                JsonObject from = new JsonObject();
                from.addProperty("source", unit.parent()); from.addProperty("line", unit.line()); from.addProperty("column", unit.column());
                value.add("included-from", from);
            }
            result.add(value);
        }
        return result;
    }
    /** Reads an AST {@code sources} table; an absent table is one unit with the default name. */
    public static SourceMap fromJson(JsonElement sources, String defaultName) {
        if (sources == null || sources.isJsonNull()) return single(defaultName);
        if (!sources.isJsonArray() || sources.getAsJsonArray().isEmpty())
            throw new IllegalArgumentException("sources must be a non-empty array");
        SourceMap map = new SourceMap();
        JsonArray entries = sources.getAsJsonArray();
        for (int i = 0; i < entries.size(); i++) {
            JsonElement entry = entries.get(i);
            if (!entry.isJsonObject()) throw new IllegalArgumentException("sources[" + i + "] must be an object");
            JsonElement name = entry.getAsJsonObject().get("name");
            if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString() || name.getAsString().isBlank())
                throw new IllegalArgumentException("sources[" + i + "].name must be a non-empty string");
            // Names are printed in diagnostics, so an unsafe one must not label any error.
            if (AstShape.sourceNameProblem(name.getAsString()) != null)
                throw new IllegalArgumentException("sources[" + i + "].name: " + AstShape.sourceNameProblem(name.getAsString()));
            int parent = -1, line = 0, column = 0;
            JsonElement from = entry.getAsJsonObject().get("included-from");
            if (from != null) {
                if (i == 0 || !from.isJsonObject()) throw new IllegalArgumentException("sources[" + i + "].included-from is invalid");
                parent = integer(from.getAsJsonObject().get("source"));
                line = integer(from.getAsJsonObject().get("line")); column = integer(from.getAsJsonObject().get("column"));
                // A parent always precedes its unit, which keeps include chains acyclic.
                if (parent < 0 || parent >= i || line <= 0 || column < 0)
                    throw new IllegalArgumentException("sources[" + i + "].included-from must name an earlier source and a line");
            }
            map.addUnit(name.getAsString(), parent, line, column);
        }
        return map;
    }

    /** A '/'-separated path relative to {@code root}, or the normalized absolute path when it cannot be relative. */
    static String relativeName(Path root, Path file) {
        if (root != null) {
            try {
                Path relative = root.relativize(file);
                if (!relative.toString().isEmpty() && !relative.isAbsolute()) return slashes(relative);
            } catch (IllegalArgumentException differentRoots) {
                // Fall back to the absolute path below.
            }
        }
        return slashes(file.toAbsolutePath().normalize());
    }
    private static String slashes(Path path) {
        String separator = path.getFileSystem().getSeparator();
        return separator.equals("/") ? path.toString() : path.toString().replace(separator, "/");
    }
}
