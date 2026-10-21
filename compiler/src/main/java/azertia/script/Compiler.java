package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Resolves symbols and lowers the readable JSON AST to numeric-slot ABD instructions. */
public class Compiler {
    /**
     * NONE preserves lowering; STANDARD, ADVANCED and AGGRESSIVE select the O1, O2 and
     * O3 pipelines. O3 may use exec v11 constructs, so its output needs a v11 runtime.
     */
    public enum Optimization { NONE, CONSTANTS, STANDARD, ADVANCED, AGGRESSIVE }
    public enum OptimizationPass { CONSTANTS, PROPAGATION, INLINING, LOOP_MOTION,
        CONTROL_FLOW, COMMON_SUBEXPRESSIONS, DEAD_CODE, SCALAR_INLINING, RANGE_LOOP_MOTION, FLOW_PROPAGATION,
        // O3 (exec v11) only.
        SEQUENCE_INLINING, SLOT_COMPACTION }
    // O2 is listed explicitly, so passes added for O3 never change its output.
    private static final Set<OptimizationPass> ADVANCED_PASSES = Set.of(OptimizationPass.CONSTANTS,
            OptimizationPass.PROPAGATION, OptimizationPass.INLINING, OptimizationPass.LOOP_MOTION,
            OptimizationPass.CONTROL_FLOW, OptimizationPass.COMMON_SUBEXPRESSIONS, OptimizationPass.DEAD_CODE,
            OptimizationPass.SCALAR_INLINING, OptimizationPass.RANGE_LOOP_MOTION, OptimizationPass.FLOW_PROPAGATION);
    public static Set<OptimizationPass> optimizationPasses(Optimization optimization) {
        return switch (Objects.requireNonNull(optimization)) {
            case NONE -> Set.of();
            case CONSTANTS -> Set.of(OptimizationPass.CONSTANTS);
            case STANDARD -> Set.of(OptimizationPass.CONSTANTS, OptimizationPass.PROPAGATION,
                    OptimizationPass.INLINING, OptimizationPass.LOOP_MOTION);
            case ADVANCED -> ADVANCED_PASSES;
            // Sequence inlining replaces O2's statement-root inliner: it also
            // records inlined frames, which O3 restores with -g. O1's inliner
            // stays, as its callees cannot fail.
            case AGGRESSIVE -> EnumSet.complementOf(EnumSet.of(OptimizationPass.SCALAR_INLINING));
        };
    }
    // Mutable holders: the depth changes at every node, and updating a holder avoids a ThreadLocal.set each time.
    private static final ThreadLocal<int[]> EXPRESSION_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final ThreadLocal<int[]> TYPE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);
    private static final int NULL_TYPE = -2, FIRST_CLASS_TYPE = 100;
    private static final int MAX_VARIABLE_SLOTS = 1_048_576, MAX_VALUE_NESTING = 64;
    private static final ThreadLocal<Map<Integer,ClassInfo>> CLASS_LAYOUTS = ThreadLocal.withInitial(LinkedHashMap::new);
    private record TypeParameter(String name, int bound) {}
    private static final ThreadLocal<Map<Integer,TypeParameter>> TYPE_PARAMETERS = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<String,Integer>> TYPE_SCOPE = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<String,ClassInfo>> CLASS_DECLARATIONS = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<String,ClassInfo>> CLASS_APPLICATIONS = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<JsonObject,Map<String,Integer>>> FUNCTION_SCOPES = ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Integer> NEXT_CLASS = ThreadLocal.withInitial(() -> FIRST_CLASS_TYPE);
    private static final ThreadLocal<Map<Integer,Integer>> BUFFER_ELEMENTS = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<Integer,Integer>> BUFFER_TYPES = ThreadLocal.withInitial(LinkedHashMap::new);
    public static class AsTypes {
        public static final int INT_VALUE = 0, STRING_VALUE = 1, FLOAT_VALUE = 2,
                DOUBLE_VALUE = 3, BOOLEAN_VALUE = 4, VOID_VALUE = 5, ANY_VALUE = 6, ADDRESS_VALUE = 7, OBJECT_VALUE = 8;
    }
    private record BuiltinSpec(String name, int id, int returnType, List<Integer> paramTypes) {}
    private static final List<BuiltinSpec> BUILTINS = List.of(
            new BuiltinSpec("print", 0x0abd0000, AsTypes.VOID_VALUE, List.of(AsTypes.ANY_VALUE)),
            new BuiltinSpec("mem_free", 0x0abd0002, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("alloc", 0x0abd0003, AsTypes.ADDRESS_VALUE, List.of(AsTypes.INT_VALUE)),
            new BuiltinSpec("make_free", 0x0abd0004, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_send_up", 0x0abd0005, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_get", 0x0abd0006, AsTypes.ANY_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("load_extern_library", 0x0abd0007, AsTypes.VOID_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("reflect_invoke_function", 0x0abd0008, AsTypes.ANY_VALUE, List.of()),
            new BuiltinSpec("reflect_get_hint_namespace", 0x0abd0009, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("reflect_hint_loaded", 0x0abd000a, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("str_length", 0x0abd000b, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("str_byte_length", 0x0abd000c, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("str_substring", 0x0abd000d, AsTypes.STRING_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.INT_VALUE, AsTypes.INT_VALUE)),
            new BuiltinSpec("str_char_at", 0x0abd000e, AsTypes.STRING_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.INT_VALUE)),
            new BuiltinSpec("str_code_at", 0x0abd000f, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.INT_VALUE)),
            new BuiltinSpec("str_from_code", 0x0abd0010, AsTypes.STRING_VALUE, List.of(AsTypes.INT_VALUE)),
            new BuiltinSpec("str_index_of", 0x0abd0011, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.STRING_VALUE, AsTypes.INT_VALUE)),
            new BuiltinSpec("str_starts_with", 0x0abd0012, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.STRING_VALUE)),
            new BuiltinSpec("str_ends_with", 0x0abd0013, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.STRING_VALUE, AsTypes.STRING_VALUE)));
    private static final Map<String,BuiltinSpec> STRING_BUILTINS = stringBuiltins();
    private static Map<String,BuiltinSpec> stringBuiltins() {
        Map<String,BuiltinSpec> result = new LinkedHashMap<>();
        for (BuiltinSpec spec : BUILTINS) if (spec.id() >= 0x0abd000b && spec.id() <= 0x0abd0013) result.put(spec.name(), spec);
        return Map.copyOf(result);
    }
    private static final Map<String,String> STRING_METHODS = Map.of(
            "length", "str_length", "byte_length", "str_byte_length", "substring", "str_substring",
            "char_at", "str_char_at", "code_at", "str_code_at", "index_of", "str_index_of",
            "contains", "str_index_of", "starts_with", "str_starts_with", "ends_with", "str_ends_with");
    private static final Set<String> BUILTIN_NAMES = builtinNames();
    private static Set<String> builtinNames() {
        Set<String> names = new HashSet<>(Set.of("value_compare", "buffer", "object_alloc"));
        names.addAll(SourceRules.CONVERSIONS);
        for (BuiltinSpec spec : BUILTINS) names.add(spec.name());
        return Set.copyOf(names);
    }
    public static boolean isBuiltinName(String name) { return BUILTIN_NAMES.contains(name); }
    public static void addDefaultIds(Map<String,Integer> map) {
        for (BuiltinSpec spec : BUILTINS) map.putIfAbsent(spec.name(), spec.id());
    }
    public static class LogicVariable {
        /** Source name retained only for compiler clients and diagnostics. */
        public String name;
        /** Negative globals; nonnegative parameters and locals in the current function frame. */
        public int slot;
        public List<String> alias = new ArrayList<>();
        public int type = AsTypes.ANY_VALUE;
        /** The source declaration checked by the unused-variable warning, or null when the variable is exempt. */
        private JsonObject declaration;
        private boolean used;
    }
    private record Signature(int id, int returnType, List<Integer> paramTypes, boolean requireKnown,
                             List<Integer> typeParameters, int classParameters, boolean internal) {
        Signature(int id, int returns, List<Integer> params, boolean known) { this(id, returns, params, known, List.of(), 0, false); }
    }
    /** A class field; {@code file}/{@code line}/{@code column} locate its declaration (line 0 when unknown). */
    private record Field(String owner, String name, int type, JsonElement initializer, int file, int line, int column) {}
    private record VirtualMethod(String name, String function, ClassInfo owner, boolean finalMethod) {}
    private static final class ClassInfo {
        String name, constructor, destructor, cleanupDestructor;
        ClassInfo base;
        // Literal (value) objects and pointers are distinct types of one layout; the
        // flexible "C (*)" parameter type accepts either and is a pointer inside the function.
        int valueType, pointerType, flexibleType, manualFactory, scopedFactory, valueFactory;
        boolean synthesizedDestructor;
        ClassInfo declaration;
        JsonObject source;
        /** Source table of the program declaring this class, for located layout errors. */
        SourceMap sources;
        boolean ready, building, prepared;
        List<Integer> arguments = List.of();
        final Map<String,Integer> typeScope = new LinkedHashMap<>();
        final List<Field> ownFields = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        final Map<String,Field> visibleFields = new LinkedHashMap<>();
        /** The slot offset of every visible field in {@link #fields}. */
        final Map<String,Integer> fieldOffsets = new HashMap<>();
        final Map<String,String> ownMethods = new LinkedHashMap<>();
        final Map<String,String> methods = new LinkedHashMap<>();
        final Set<String> virtualMethods = new LinkedHashSet<>(), overrideMethods = new LinkedHashSet<>(), finalMethods = new LinkedHashSet<>();
        final List<VirtualMethod> vtable = new ArrayList<>();
        final Map<String,Integer> virtualSlots = new LinkedHashMap<>();
        final Map<String,String> ownOperators = new LinkedHashMap<>();
        final Map<String,String> operators = new LinkedHashMap<>();
        Field field(String name) {
            Field field = visibleFields.get(name);
            if (field == null) throw new IllegalArgumentException("Unknown member: " + this.name + "." + name);
            return field;
        }
    }
    private static class Counter { int next, hidden; }
    /** A synthetic loop can relay control to the nearest enclosing real loop. */
    private record SyntheticLoop(String breakFlag, String continueFlag, int outerLoopDepth, boolean transparent, boolean breakAllowed) {}
    /** Per-compilation state shared through the root environment. */
    static final class CompilationState {
        Set<OptimizationPass> optimizationPasses = Set.of();
        final Map<Integer,Set<Integer>> optimizerTemps = new HashMap<>();
        final Diagnostics diagnostics;
        final SourceMap sources;
        final CompilerMetrics metrics;
        final int abdLimit;
        /** Whether the in-process parser produced the AST, which makes unexpected exceptions internal compiler errors. */
        final boolean parsed;
        /**
         * Nodes the compiler synthesized and positioned after a source node. Warnings
         * are reported only at positioned nodes of the compiled AST, never at these.
         */
        final Set<JsonObject> synthesized = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Source-level labels for checks performed by lowered nodes, such as a for condition. */
        final IdentityHashMap<JsonObject,String> diagnosticLabels = new IdentityHashMap<>();
        final IdentityHashMap<JsonObject,SyntheticLoop> syntheticLoops = new IdentityHashMap<>();
        /** Compiler-resolved dispatch metadata, never part of the readable AST. */
        final IdentityHashMap<JsonObject,Integer> virtualCalls = new IdentityHashMap<>();
        /** The memo of the running outermost type query (see expressionType), reused by the next query. */
        final TypeQuery typeQuery = new TypeQuery();
        /**
         * With debug info: the {@code {file, line, column}} source position of each compiled
         * object or block, the first (innermost) one recorded winning; null without debug info.
         */
        IdentityHashMap<AcsElement,int[]> positions;
        /** With debug info: the call sites O3 inlined, which positions of inlined nodes name. */
        ExecInlineSites inlineSites;
        /** With debug info: the exec debug file table, and the index of each source unit's name in it. */
        List<String> debugFiles = List.of();
        int[] unitFiles;
        CompilationState(Diagnostics diagnostics, SourceMap sources, boolean parsed, int abdLimit) {
            this.diagnostics = diagnostics; this.sources = sources; this.parsed = parsed; metrics = diagnostics.metrics();
            this.abdLimit=abdLimit;
        }
        /**
         * Turns debug info on: the debug file table holds each distinct source unit name,
         * with unsafe characters replaced, in unit order.
         */
        void enableDebugInfo() {
            positions = new IdentityHashMap<>(); inlineSites = new ExecInlineSites(); unitFiles = new int[sources.unitCount()];
            Map<String,Integer> indexes = new LinkedHashMap<>();
            for (int unit = 0; unit < unitFiles.length; unit++) {
                String name = SourceRules.debugText(sources.name(unit));
                Integer index = indexes.putIfAbsent(name, indexes.size());
                unitFiles[unit] = index == null ? indexes.size() - 1 : index;
            }
            debugFiles = List.copyOf(indexes.keySet());
        }
        /** The {@code {file, line, column}} debug position of a positioned AST node, or null. */
        int[] debugPosition(JsonElement node) {
            if (node == null || !node.isJsonObject()) return null;
            JsonObject object = node.getAsJsonObject();
            int line = SourceMap.integer(object.get("_line")), column = SourceMap.integer(object.get("_column"));
            if (line < 1 || column < 1) return null;
            return new int[]{unitFiles[sources.unitOf(object)], line, column};
        }
    }
    /**
     * Expression types memoized during one outermost type query, for its environment.
     * A query normally stays in one environment; others get maps of their own.
     */
    private static final class TypeQuery {
        /** Larger memos are dropped after their query, so clearing never costs more than a small table. */
        private static final int REUSED_ENTRIES = 64;
        int depth;
        private LogicEnvironment environment;
        private IdentityHashMap<JsonElement,Integer> types = new IdentityHashMap<>();
        private IdentityHashMap<LogicEnvironment,IdentityHashMap<JsonElement,Integer>> others;
        IdentityHashMap<JsonElement,Integer> types(LogicEnvironment scope) {
            if (environment == null) environment = scope;
            if (environment == scope) return types;
            if (others == null) others = new IdentityHashMap<>();
            return others.computeIfAbsent(scope, ignored -> new IdentityHashMap<>());
        }
        /** Ends the outermost query: later declarations can change lexical lookup. */
        void finish() {
            environment = null; others = null;
            if (types.size() > REUSED_ENTRIES) types = new IdentityHashMap<>(); else if (!types.isEmpty()) types.clear();
        }
    }
    public static class LogicEnvironment {
        public LogicEnvironment parent;
        /**
         * The variables of this scope in declaration order. Lookups use an index of
         * this list: change it through {@code define} or by appending variables. An
         * appended variable, or a replacement list, is indexed under the aliases it has
         * then at the next lookup in this scope. Replacing or removing entries and
         * changing the aliases of an indexed variable are not supported: a lookup then
         * never returns a variable that has left the list or lost the alias, but can
         * miss a name added that way.
         */
        public List<LogicVariable> vars = new ArrayList<>();
        public Map<String,Integer> abs = new LinkedHashMap<>();
        // Tables only the root fills; a child scope shares the root's instead of allocating its own.
        private final Map<Integer,Integer> arities;
        private final Map<Integer,Integer> functionReturnTypes;
        private final Map<Integer,Signature> externSignatures;
        private final Map<Integer,Signature> scriptSignatures;
        private final Map<Integer,Signature> builtinSignatures;
        private final Map<String,Signature> namedSignatures;
        private final Map<String,ClassInfo> classes;
        private final Set<JsonObject> trustedNodes;
        private final Map<JsonObject,ClassInfo> callOwners;
        /** The positions in {@link #vars} by alias, the list they index and how many of its entries. */
        private final Map<String,Integer> index = new HashMap<>();
        private List<LogicVariable> indexedList;
        private int indexed;
        /** The outermost environment, cached; null until a scope with a parent first needs it. */
        private LogicEnvironment root;
        private Map<String,Integer> typeScope = Map.of();
        private FunctionTables tables;
        private List<Integer> typeParameters = List.of();
        private Counter counter = new Counter();
        /** Root only: diagnostics and the source table; a bare environment stops at the first error and names its source {@code <expression>}. */
        private CompilationState state;
        /** Diagnostic context such as "function main", or null outside a function. */
        private String context;
        private int returnType = -1;
        private int loopDepth;
        private SyntheticLoop syntheticLoop;
        private String ownerClass;
        private int moduleNamespace = SourceRules.DEFAULT_NAMESPACE;
        public LogicEnvironment() {
            arities = new HashMap<>(); functionReturnTypes = new HashMap<>(); externSignatures = new LinkedHashMap<>();
            scriptSignatures = new LinkedHashMap<>(); builtinSignatures = new LinkedHashMap<>(); namedSignatures = new LinkedHashMap<>();
            classes = new LinkedHashMap<>(); trustedNodes = Collections.newSetFromMap(new IdentityHashMap<>()); callOwners = new IdentityHashMap<>();
            tables=new FunctionTables(Map.of(),null);
        }
        private LogicEnvironment(LogicEnvironment root) {
            arities = root.arities; functionReturnTypes = root.functionReturnTypes; externSignatures = root.externSignatures;
            scriptSignatures = root.scriptSignatures; builtinSignatures = root.builtinSignatures; namedSignatures = root.namedSignatures;
            classes = root.classes; trustedNodes = root.trustedNodes; callOwners = root.callOwners; this.root = root;
            tables=root.tables;
        }
        LogicEnvironment child() {
            LogicEnvironment child = new LogicEnvironment(root()); child.parent = this; child.counter = counter;
            child.context = context; child.returnType = returnType; child.loopDepth = loopDepth; child.ownerClass = ownerClass;
            child.syntheticLoop = syntheticLoop;
            child.typeScope = typeScope; child.typeParameters = typeParameters;child.tables=tables;return child;
        }
        LogicEnvironment loopChild() {
            LogicEnvironment child = child(); child.loopDepth++; child.syntheticLoop = null; return child;
        }
        LogicEnvironment root() {
            if (parent == null) return this;
            if (root == null) {
                // A parent assigned outside child(); the chain is walked once.
                LogicEnvironment cursor = parent;
                while (cursor.parent != null) cursor = cursor.parent;
                root = cursor;
            }
            return root;
        }
        CompilationState state() {
            LogicEnvironment root = root();
            if (root.state == null) root.state = new CompilationState(Diagnostics.failFast(), SourceMap.single("<expression>"), false, abdLimit());
            return root.state;
        }
        public int getFunctionId(String name) {
            Integer id = root().abs.get(name);
            if (id != null) return id;
            throw new IllegalArgumentException("Unknown function: " + name);
        }
        /** The variable of this scope with an alias, without marking it used. */
        private LogicVariable local(String alias) {
            if (indexedList != vars || indexed != vars.size()) reindex();
            Integer position = index.get(alias);
            if (position == null) return null;
            LogicVariable variable = position < vars.size() ? vars.get(position) : null;
            if (variable != null && variable.alias.contains(alias)) return variable;
            // An entry was replaced or lost the alias outside define().
            reindex(); position = index.get(alias);
            return position == null ? null : vars.get(position);
        }
        /** Indexes vars after a change outside define(); the first variable with an alias keeps it. */
        private void reindex() {
            index.clear();
            for (int i = 0; i < vars.size(); i++) for (String name : vars.get(i).alias) index.putIfAbsent(name, i);
            indexedList = vars; indexed = vars.size();
        }
        // Every lookup is a use of the variable for the unused-variable warning.
        public LogicVariable getVariable(String name) {
            LogicVariable variable = findVariable(name);
            if (variable == null) throw new IllegalArgumentException("Unknown variable: " + name);
            return variable;
        }
        /** The innermost variable with this name, the globals of the root included. */
        private LogicVariable findVariable(String name) { return lookup(name, true); }
        /** The innermost local variable with this name; the root's globals are not lexical. */
        private LogicVariable findLexicalVariable(String name) { return lookup(name, false); }
        private LogicVariable lookup(String name, boolean globals) {
            int probes = 0; LogicVariable found = null;
            for (LogicEnvironment scope = this; scope != null && (globals || scope.parent != null); scope = scope.parent) {
                probes++;
                found = scope.local(name);
                if (found != null) { found.used = true; break; }
            }
            state().metrics.scopeProbes += probes;
            return found;
        }
        private LogicVariable define(String alias, int slot) {
            return define(alias, slot, AsTypes.ANY_VALUE);
        }
        private LogicVariable define(String alias, int slot, int type) {
            if (alias.isBlank()) throw new IllegalArgumentException("Variable name cannot be empty");
            state().metrics.scopeProbes++;
            if (local(alias) != null) throw new IllegalArgumentException("Duplicate variable in the same scope: " + alias);
            LogicVariable variable = new LogicVariable(); variable.name = alias; variable.slot = slot; variable.type = type;
            variable.alias.add(alias);
            tables.slots.put(slot,abiType(type));
            index.put(alias, vars.size()); vars.add(variable); indexed++; return variable;
        }
    }
    private static JsonArray parameters(JsonObject object) {
        if (!object.has("param")) return new JsonArray();
        if (!object.get("param").isJsonArray()) throw new IllegalArgumentException("Instruction param must be an array");
        return object.getAsJsonArray("param");
    }
    private static void arity(String operation, JsonArray params, int min, int max) {
        if (params.size() < min || params.size() > max)
            throw new IllegalArgumentException(operation + " expects " + (min == max ? min : min + ".." + max)
                    + " arguments, got " + params.size());
    }
    private static String string(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(field + " must be a string");
        return element.getAsString();
    }
    /** Whether a JSON number is written as an integer, {@code [+-]?[0-9]+}, rather than with a fraction or exponent. */
    private static boolean integerLiteral(String text) {
        int start = !text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-') ? 1 : 0;
        if (start == text.length()) return false;
        for (int i = start; i < text.length(); i++) if (text.charAt(i) < '0' || text.charAt(i) > '9') return false;
        return true;
    }
    private static AcsElement number(JsonElement element) {
        BigDecimal value = element.getAsBigDecimal();
        if (integerLiteral(element.getAsString())) {
            try { return new AcsIntegerElement(value.intValueExact()); }
            catch (ArithmeticException e) { throw new IllegalArgumentException("Integer literal is outside signed 32-bit range: " + value); }
        }
        double number = element.getAsDouble(); // Preserve the sign bit of floating -0.0.
        if (!Double.isFinite(number) || (number == 0 && value.signum() != 0))
            throw new IllegalArgumentException("Floating literal is outside double range");
        return new AcsDouble(number);
    }
    private static int primitiveType(JsonPrimitive value) {
        if (value.isString()) return AsTypes.STRING_VALUE;
        if (value.isBoolean()) return AsTypes.BOOLEAN_VALUE;
        if (value.isNumber())
            return integerLiteral(value.getAsString()) ? AsTypes.INT_VALUE : AsTypes.DOUBLE_VALUE;
        return AsTypes.ANY_VALUE;
    }
    // Operator selection and signature inference may revisit the same operands.
    // Cache only during one query: later declarations can change lexical lookup.
    private static int expressionType(JsonElement input, LogicEnvironment environment) {
        CompilationState state=environment.state();TypeQuery query=state.typeQuery;
        query.depth++;
        try {
            var values=query.types(environment);
            Integer known=values.get(input);if(known!=null)return known;
            int result;state.metrics.typeEvaluations++;
            // A failing query names the innermost positioned subexpression, e.g. the unknown variable.
            try{result=expressionTypeUncached(input,environment);}catch(RuntimeException error){throw relocate(error,input,environment);}
            values.put(input,result);return result;
        } finally {if(--query.depth==0)query.finish();}
    }
    private static int expressionTypeUncached(JsonElement input, LogicEnvironment environment) {
        if (input == null || input.isJsonNull()) return AsTypes.VOID_VALUE;
        if (input.isJsonPrimitive()) return primitiveType(input.getAsJsonPrimitive());
        if (input.isJsonArray()) return AsTypes.VOID_VALUE;
        if (!input.isJsonObject()) return AsTypes.ANY_VALUE;
        JsonObject object = input.getAsJsonObject();
        if (!object.has("t") || !object.has("call")) return AsTypes.ANY_VALUE;
        String kind = string(object.get("t"), "Instruction t");
        String operation = string(object.get("call"), "Instruction call");
        JsonArray params = parameters(object);
        JsonObject overloaded = overloadedCall(object, environment);
        if (overloaded != null) return expressionType(overloaded, environment);
        if(kind.equals("call")) {
            if(resolveCall(operation,environment).equals(operation)) {
                if (STRING_BUILTINS.containsKey(operation)) return STRING_BUILTINS.get(operation).returnType();
                if (SourceRules.CONVERSIONS.contains(operation)) return conversionTarget(operation);
                if (operation.equals("boolean") || operation.equals("bool"))
                    throw new IllegalArgumentException("Boolean conversions are not supported; write a comparison");
                if(operation.equals("reflect_invoke_function"))return reflectionType(object,environment);
                if(operation.equals("value_compare"))return AsTypes.INT_VALUE;
            }
            CallBinding binding=bindCall(object,environment);
            return binding.signature()!=null?binding.signature().returnType():AsTypes.ANY_VALUE;
        }
        if (!kind.equals("ctrl")) return AsTypes.ANY_VALUE;
        return switch (operation) {
            case "#precompiled" -> precompiled(object,environment).type();
            case "chain" -> chainType(object,environment);
            case "var" -> params.size() == 1 ? variableType(string(params.get(0), "Variable name"), environment) : AsTypes.ANY_VALUE;
            case "null" -> NULL_TYPE;
            case "object-new" -> classInfo(string(params.get(0), "Class name"), environment).pointerType;
            case "object-value" -> classInfo(string(params.get(0), "Class name"), environment).valueType;
            case "buffer-new" -> bufferType(valueType(string(params.get(0), "Buffer element type"), true));
            case "member" -> memberField(params, environment).type();
            case "member-set" -> params.size() == 2 ? expressionType(params.get(1), environment) : AsTypes.ANY_VALUE;
            case "member-call" -> isStringReceiver(object,environment) ? stringMethodType(object)
                    : isBufferReceiver(object,environment) ? bufferMethodType(object,environment) : expressionType(memberCall(object,environment),environment);
            case "index" -> isStringReceiver(object,environment) ? AsTypes.STRING_VALUE : AsTypes.ANY_VALUE;
            case "index-set" -> {
                if (isStringReceiver(object,environment)) throw new IllegalArgumentException("Strings are immutable; cannot assign through an index");
                yield AsTypes.ANY_VALUE;
            }
            case "base-call" -> expressionType(baseCall(object,environment),environment);
            case "#context-abi" -> {requireTrusted(object,environment);yield AsTypes.INT_VALUE;}
            case "#context-default" -> {requireTrusted(object,environment);yield params.get(0).getAsInt();}
            case "#allocate" -> {
                requireTrusted(object, environment);
                yield classInfo(string(params.get(0), "Class name"), environment).pointerType;
            }
            case "#new-block" -> {
                requireTrusted(object, environment);
                yield classInfo(string(params.get(0), "Class name"), environment).valueType;
            }
            case "#address-of" -> {
                requireTrusted(object, environment);
                yield CLASS_LAYOUTS.get().get(typeBound(expressionType(params.get(0), environment))).pointerType;
            }
            case "#move" -> { requireTrusted(object, environment); yield expressionType(params.get(0), environment); }
            case "varset", "mov" -> params.size() == 2
                    ? expressionType(params.get(1), environment) : AsTypes.ANY_VALUE;
            case "float" -> AsTypes.FLOAT_VALUE;
            case "neg", "pos" -> params.size() == 1
                    ? numericOrAny(expressionType(params.get(0), environment)) : AsTypes.ANY_VALUE;
            case "not", "cmp", "ne", "greater", "lower", "ge", "le", "and", "or" -> AsTypes.BOOLEAN_VALUE;
            case "add" -> params.size() == 2
                    ? additionType(expressionType(params.get(0), environment), expressionType(params.get(1), environment))
                    : AsTypes.ANY_VALUE;
            case "minus" -> params.size() == 2
                    ? subtractionType(expressionType(params.get(0), environment), expressionType(params.get(1), environment))
                    : AsTypes.ANY_VALUE;
            case "multiply", "divide" -> params.size() == 2
                    ? arithmeticType(expressionType(params.get(0), environment), expressionType(params.get(1), environment))
                    : AsTypes.ANY_VALUE;
            case "bit_and", "bit_or", "bit_xor", "shl", "shr", "ushr" -> AsTypes.INT_VALUE;
            case "mod" -> params.size() == 2
                    && expressionType(params.get(0), environment) == AsTypes.INT_VALUE
                    && expressionType(params.get(1), environment) == AsTypes.INT_VALUE
                    ? AsTypes.INT_VALUE : AsTypes.ANY_VALUE;
            case "if" -> branchType(params, environment);
            case "select" -> selectType(params, environment);
            case "vardef", "return", "break", "continue", "while", "for", "switch", "if-chain", "#switch-loop", "#if-loop", "#synthetic-break", "increment", "decrement", "object-def", "object-delete", "#bind", "#set-methods", "#base-completed", "#drop" -> AsTypes.VOID_VALUE;
            default -> AsTypes.ANY_VALUE;
        };
    }
    private static int branchType(JsonArray params, LogicEnvironment environment) {
        if (params.size() != 3) return AsTypes.VOID_VALUE;
        return commonType(expressionType(params.get(1), environment), expressionType(params.get(2), environment), false);
    }
    private static int selectType(JsonArray params, LogicEnvironment environment) {
        arity("select", params, 3, 3);
        rejectVoidValue("conditional condition", params.get(0), environment);
        requireConditionOrUnknown("conditional condition", params.get(0), environment);
        int first = expressionType(params.get(1), environment), second = expressionType(params.get(2), environment);
        if (first == AsTypes.VOID_VALUE || second == AsTypes.VOID_VALUE)
            throw new IllegalArgumentException("Conditional expression arms cannot use void values");
        return commonType(first, second, true);
    }
    private static AcsElement selectArm(JsonElement arm, int target, JsonObject source, LogicEnvironment environment) {
        int actual = expressionType(arm, environment);
        if (numeric(target) && numeric(actual) && actual != target) {
            JsonObject conversion = callNode(typeName(target), array(arm));
            copyPosition(arm.isJsonObject() ? arm : source, conversion, environment);
            return compileNode(conversion, environment);
        }
        return compileNode(arm, environment);
    }
    /**
     * The type of a value that is either branch: identical types, a pointer for null,
     * or the base pointer of a derived/base pair. Strict callers convert the narrower
     * numeric arm, so they also get the wider numeric type; without that conversion
     * mixed numerics stay any. Unrelated scalars give any unless strict. Object
     * references never pass through any: a class, buffer, type parameter or null
     * without a common type is an error.
     */
    private static int commonType(int first, int second, boolean strict) {
        if (first == second) return first;
        if (numeric(first) && numeric(second)) return strict ? arithmeticType(first, second) : AsTypes.ANY_VALUE;
        if (first != NULL_TYPE && second != NULL_TYPE && !isValueClass(first) && !isValueClass(second)) {
            if (assignable(first, second)) return first;
            if (assignable(second, first)) return second;
        } else if (first == NULL_TYPE && assignable(second, NULL_TYPE)) return second;
        else if (second == NULL_TYPE && assignable(first, NULL_TYPE)) return first;
        if (strict || reference(first) || reference(second))
            throw new IllegalArgumentException((strict ? "Conditional expression arms have incompatible types: " : "Branches have incompatible types ")
                    + typeName(first) + " and " + typeName(second));
        return AsTypes.ANY_VALUE;
    }
    /** Class, buffer, type-parameter and null values, which must keep their static type. */
    private static boolean reference(int type) {
        return isClass(type) || isBuffer(type) || isTypeParameter(type) || type == NULL_TYPE;
    }
    private static int numericOrAny(int type) {
        return numeric(type) ? type : AsTypes.ANY_VALUE;
    }
    private static int additionType(int first, int second) {
        if (first == AsTypes.STRING_VALUE || second == AsTypes.STRING_VALUE) return AsTypes.STRING_VALUE;
        if (first == AsTypes.ADDRESS_VALUE && second == AsTypes.INT_VALUE
                || second == AsTypes.ADDRESS_VALUE && first == AsTypes.INT_VALUE) return AsTypes.ADDRESS_VALUE;
        return arithmeticType(first, second);
    }
    private static int subtractionType(int first, int second) {
        return first == AsTypes.ADDRESS_VALUE && second == AsTypes.INT_VALUE ? AsTypes.ADDRESS_VALUE : arithmeticType(first, second);
    }
    private static int arithmeticType(int first, int second) {
        if (!numeric(first) || !numeric(second)) return AsTypes.ANY_VALUE;
        if (first == AsTypes.DOUBLE_VALUE || second == AsTypes.DOUBLE_VALUE) return AsTypes.DOUBLE_VALUE;
        if (first == AsTypes.FLOAT_VALUE || second == AsTypes.FLOAT_VALUE) return AsTypes.FLOAT_VALUE;
        return AsTypes.INT_VALUE;
    }
    private static boolean numeric(int type) {
        return type == AsTypes.INT_VALUE || type == AsTypes.FLOAT_VALUE || type == AsTypes.DOUBLE_VALUE;
    }
    private static int conversionTarget(String name) {
        return switch (name) {
            case "int" -> AsTypes.INT_VALUE;
            case "float" -> AsTypes.FLOAT_VALUE;
            case "double" -> AsTypes.DOUBLE_VALUE;
            case "string" -> AsTypes.STRING_VALUE;
            case "address" -> AsTypes.ADDRESS_VALUE;
            default -> throw new IllegalArgumentException("Unknown conversion target: " + name);
        };
    }
    /** Explicit scalar conversions leave all type/range checks for dynamic operands to the runtime. */
    private static AcsElement compileConversion(JsonObject source, LogicEnvironment environment) {
        String name = string(source.get("call"), "Conversion name");
        JsonArray params = parameters(source); arity(name, params, 1, 1);
        if (source.has("type-args")) throw new IllegalArgumentException(name + " conversion does not take type arguments");
        JsonElement value = params.get(0);
        int target = conversionTarget(name), actual = expressionType(value, environment);
        if (target == AsTypes.ADDRESS_VALUE && isClassPointer(actual)) return compileNode(value, environment);
        boolean supported = actual == AsTypes.ANY_VALUE || actual == target
                || numeric(target) && (numeric(actual) || actual == AsTypes.STRING_VALUE)
                || target == AsTypes.STRING_VALUE && (numeric(actual) || actual == AsTypes.ADDRESS_VALUE);
        if (!supported) throw new IllegalArgumentException("Cannot convert " + typeName(actual) + " to " + typeName(target));
        if (actual == target) return compileNode(value, environment);
        AcsObject result = new AcsObject(); result.put("t", 0); result.put("c", ExecOpcodes.CONVERT);
        result.put("type", target); result.put("v", compileNode(value, environment)); return result;
    }
    private static boolean returnCompatible(int expected, int actual) {
        return assignable(expected, actual)
                || actual == AsTypes.INT_VALUE && (expected == AsTypes.FLOAT_VALUE || expected == AsTypes.DOUBLE_VALUE)
                || actual == AsTypes.FLOAT_VALUE && expected == AsTypes.DOUBLE_VALUE;
    }
    /**
     * A base pointer addresses the same prefix; no pointer adjustment or runtime type is needed.
     * Literal objects never convert: copying one keeps its layout and cleanup destructor.
     */
    private static boolean assignable(int expected, int actual) {
        if(isTypeParameter(expected))return expected==actual || actual==NULL_TYPE&&isPointerClass(typeBound(expected));
        if(isTypeParameter(actual))return isPointerClass(typeBound(actual))&&assignable(expected,typeBound(actual));
        // Classes are nominal; a literal object only takes its own class, so it never slices.
        if (expected == actual) return true;
        if ((isPointerClass(expected) || isFlexibleClass(expected) || expected == AsTypes.ADDRESS_VALUE) && actual == NULL_TYPE) return true;
        // "C (*)" takes a pointer, or a literal object by its address, under the pointer conversion rules.
        if (isFlexibleClass(expected))
            return (isPointerClass(actual) || isValueClass(actual)) && assignable(pointerOf(expected), pointerOf(actual));
        if (!isPointerClass(expected) || !isPointerClass(actual)) return false;
        for (ClassInfo base = laidOutBase(CLASS_LAYOUTS.get().get(actual)); base != null; base = laidOutBase(base))
            if (expected == base.pointerType) return true;
        return false;
    }
    /** A generic application learns its base when its layout is prepared, which may not have happened yet. */
    private static ClassInfo laidOutBase(ClassInfo info) {
        if (!info.prepared && !info.building) ensureLayout(info);
        return info.base;
    }
    private static boolean conditionType(int type) {
        return type == AsTypes.BOOLEAN_VALUE || type == AsTypes.ANY_VALUE;
    }
    private static String typeName(int type) {
        return switch (type) {
            case AsTypes.INT_VALUE -> "int";
            case AsTypes.STRING_VALUE -> "string";
            case AsTypes.FLOAT_VALUE -> "float";
            case AsTypes.DOUBLE_VALUE -> "double";
            case AsTypes.BOOLEAN_VALUE -> "boolean";
            case AsTypes.VOID_VALUE -> "void";
            case AsTypes.ANY_VALUE -> "any";
            case AsTypes.ADDRESS_VALUE -> "address";
            case NULL_TYPE -> "null";
            case AsTypes.OBJECT_VALUE -> "object";
            default -> {
                if(isTypeParameter(type))yield TYPE_PARAMETERS.get().get(type).name();
                if(isBuffer(type))yield "buffer<"+typeName(BUFFER_ELEMENTS.get().get(type))+">";
                ClassInfo info = CLASS_LAYOUTS.get().get(type);
                yield info == null ? "unknown(" + type + ")" : isPointerClass(type) ? info.name + " *"
                        : isFlexibleClass(type) ? info.name + " (*)" : info.name;
            }
        };
    }
    private static void requireKnownExact(String context, int expected, int actual) {
        if (actual == AsTypes.ANY_VALUE)
            throw new IllegalArgumentException(context + " has unknown type; expected " + typeName(expected));
        if (!assignable(expected, actual))
            throw new IllegalArgumentException(
                    context + " expected " + typeName(expected) + ", got " + typeName(actual));
    }
    private static void rejectVoidValue(String context, JsonElement value, LogicEnvironment environment) {
        if (expressionType(value, environment) == AsTypes.VOID_VALUE)
            throw new IllegalArgumentException(context + " cannot use a void value");
    }
    private static void requireNumericOrUnknown(String context, JsonElement value,
                                                LogicEnvironment environment) {
        int type = expressionType(value, environment);
        if (type != AsTypes.ANY_VALUE && !numeric(type))
            throw new IllegalArgumentException(context + " must be numeric, got " + typeName(type));
    }
    private static void requireIntegerOrUnknown(String context, JsonElement value,
                                                LogicEnvironment environment) {
        int type = expressionType(value, environment);
        if (type != AsTypes.ANY_VALUE && type != AsTypes.INT_VALUE)
            throw new IllegalArgumentException(context + " must be int, got " + typeName(type));
    }
    private static void requireBitwiseIntegerOrUnknown(String context, JsonElement value, LogicEnvironment environment) {
        int type = expressionType(value, environment);
        if (type != AsTypes.ANY_VALUE && type != AsTypes.INT_VALUE)
            throw new IllegalArgumentException(context + " must be int, got " + typeName(type)
                    + (type == AsTypes.BOOLEAN_VALUE ? "; parenthesize comparisons, e.g. (a & b) == 0" : ""));
    }
    /** A signed integer literal, including the explicit unary node in a hand-written AST. */
    private static BigDecimal shiftCountLiteral(JsonElement value) {
        boolean negate = false;
        if (value.isJsonObject()) {
            JsonObject node = value.getAsJsonObject();
            if (!isControl(node) || !(node.get("call").getAsString().equals("pos") || node.get("call").getAsString().equals("neg"))) return null;
            JsonArray params = parameters(node);
            if (params.size() != 1) return null;
            negate = node.get("call").getAsString().equals("neg"); value = params.get(0);
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !integerLiteral(value.getAsString())) return null;
        BigDecimal count = value.getAsBigDecimal(); return negate ? count.negate() : count;
    }
    private static void requireConditionOrUnknown(String context, JsonElement value,
                                                  LogicEnvironment environment) {
        int type = expressionType(value, environment);
        if (!conditionType(type))
            throw new IllegalArgumentException(
                    context + " must be boolean, got " + typeName(type));
    }
    /** Equality preserves nominal references; only dynamic non-class operands defer to the runtime. */
    private static void requireComparable(JsonElement first, JsonElement second, LogicEnvironment environment) {
        int left = expressionType(first, environment), right = expressionType(second, environment);
        if (isOwnedValue(left) || isOwnedValue(right))
            throw new IllegalArgumentException("Literal objects cannot be compared; compare their fields or pointers");
        if (isClass(left) || isClass(right) || left == NULL_TYPE || right == NULL_TYPE) {
            if (left == NULL_TYPE && right == AsTypes.ANY_VALUE || right == NULL_TYPE && left == AsTypes.ANY_VALUE
                    || assignable(left, right) || assignable(right, left)) return;
            throw new IllegalArgumentException("Object equality requires compatible class references or null");
        }
        if ((left == AsTypes.ADDRESS_VALUE || right == AsTypes.ADDRESS_VALUE)
                && left != AsTypes.ANY_VALUE && right != AsTypes.ANY_VALUE && left != right)
            throw new IllegalArgumentException("Address equality requires an address or null");
        if (left == AsTypes.ANY_VALUE || right == AsTypes.ANY_VALUE || left == right || numeric(left) && numeric(right)) return;
        throw new IllegalArgumentException("Equality requires compatible operand types, got " + typeName(left) + " and " + typeName(right));
    }
    private static void requireAddableOrUnknown(JsonElement first, JsonElement second,
                                                LogicEnvironment environment) {
        int firstType = expressionType(first, environment);
        int secondType = expressionType(second, environment);
        if (isClass(firstType) || isClass(secondType) || isBuffer(firstType) || isBuffer(secondType) || isTypeParameter(firstType) || isTypeParameter(secondType) || firstType == NULL_TYPE || secondType == NULL_TYPE)
            throw new IllegalArgumentException("Object references cannot be used in arithmetic");
        if (firstType == AsTypes.ANY_VALUE || secondType == AsTypes.ANY_VALUE
                || firstType == AsTypes.STRING_VALUE || secondType == AsTypes.STRING_VALUE
                || numeric(firstType) && numeric(secondType)
                || firstType == AsTypes.ADDRESS_VALUE && secondType == AsTypes.INT_VALUE
                || secondType == AsTypes.ADDRESS_VALUE && firstType == AsTypes.INT_VALUE) return;
        throw new IllegalArgumentException("add operands must be numeric or include a string, got "
                + typeName(firstType) + " and " + typeName(secondType));
    }
    /**
     * Tracks physical compact-ABD containers while lowering. Blocks and calls
     * contain a separate expression-list stack; constants contain a typed scalar
     * array. Exceeding the ABD limit is reported here with
     * the enclosing instruction's location instead of failing later in the codec.
     */
    private static <T> T withDepth(int levels, java.util.function.Supplier<T> action) {
        int[] holder = EXPRESSION_DEPTH.get(); int depth = holder[0];
        if (depth + levels > 2*TreeLimits.MAX_NESTING)
            throw new IllegalArgumentException("Expression nesting exceeds the compiler limit of " + 2*TreeLimits.MAX_NESTING + " levels");
        holder[0] = depth + levels;
        try { return action.get(); }
        finally { holder[0] = depth; }
    }
    public static AcsElement compileExpression(JsonElement input,LogicEnvironment environment) {
        TreeLimits.validate(input);
        AcsElement compiled=compileNode(input,environment);
        environment.tables.nextSlot=environment.counter.next;
        try { return new AbdLinearizer(environment.tables,environment.state().abdLimit).standalone(compiled); }
        catch(AbdLinearizer.LimitExceeded error) { throw relocate(error,environment.tables.origins.getOrDefault(error.node,input),environment); }
        finally { environment.counter.next=environment.tables.nextSlot; }
    }
    private static int abdLimit() {
        String configured=System.getProperty("azscript.internal.abdLimit");
        if(configured==null)return TreeLimits.MAX_ABD_NESTING;
        try { int value=Integer.parseInt(configured);if(value>=32&&value<=TreeLimits.MAX_ABD_NESTING)return value; }
        catch(NumberFormatException ignored) { }
        throw new IllegalArgumentException("azscript.internal.abdLimit must be an integer between 32 and 128");
    }
    private static AcsElement compileNode(JsonElement input, LogicEnvironment environment) {
        environment.state().metrics.compiledNodes++;
        // Nested expressions almost always share their function's type scope; set it only when it changes.
        Map<String,Integer> previousScope=TYPE_SCOPE.get();boolean changed=previousScope!=environment.typeScope;
        if(changed)TYPE_SCOPE.set(environment.typeScope);
        try { return recordPosition(input, withDepth(1, () -> compileExpressionBody(input, environment)), environment); }
        catch (RuntimeException error) { throw relocate(error, input, environment); }
        finally {if(changed)TYPE_SCOPE.set(previousScope);}
    }
    /**
     * With debug info, records the source position of a compiled object or block.
     * Constants and variable reads are not recorded: at run time they cannot fail
     * apart from the node that uses them, whose position they inherit.
     */
    private static AcsElement recordPosition(JsonElement input, AcsElement result, LogicEnvironment environment) {
        CompilationState state = environment.state();
        if(input instanceof JsonObject)environment.tables.origins.putIfAbsent(result,input);
        if (state.positions == null || !(result instanceof AcsObject || result instanceof AcsArray)) return result;
        if (result instanceof AcsObject object && object.mmp.get("t") instanceof AcsIntegerElement kind && kind.s == 0
                && object.mmp.get("c") instanceof AcsIntegerElement code && code.s == ExecOpcodes.VARIABLE) return result;
        int[] position = state.debugPosition(input);
        if (position != null) state.positions.putIfAbsent(result, position);
        return result;
    }
    private static String message(Throwable error) {
        return error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
    }
    /**
     * Locates an error at a positioned node. An error that is already located passes
     * through, and an unpositioned node leaves the error to the nearest positioned
     * ancestor. An unexpected exception is classified at the innermost positioned node.
     */
    private static RuntimeException relocate(RuntimeException error, JsonElement node, LogicEnvironment environment) {
        if (error instanceof CompileException || node == null || !node.isJsonObject()) return error;
        SourceMap sources = environment.state().sources;
        if (sources.position(node.getAsJsonObject()) == null) return error;
        if (!Diagnostics.userError(error)) return unexpected(error, node.getAsJsonObject(), environment.context, environment.state());
        return new CompileException(sources.error(node.getAsJsonObject(), environment.context, message(error)), error);
    }
    /** Locates an error at a declaration, or at the main file when the declaration has no position. */
    private static CompileException locate(Throwable error, JsonObject anchor, String context, LogicEnvironment environment) {
        if (error instanceof CompileException compiled) return compiled;
        if (!Diagnostics.userError(error)) return unexpected(error, anchor, context, environment.state());
        return new CompileException(environment.state().sources.error(anchor, context, message(error)), error);
    }
    /**
     * Reports an error of one function, class or global item and returns, so the
     * compiler continues with the next one; a fatal error, the error limit or a
     * fail-fast collector ends the compilation instead.
     */
    private static void recover(Throwable error, JsonObject anchor, String context, LogicEnvironment environment) {
        environment.state().diagnostics.report(locate(error, anchor, context, environment));
    }
    /**
     * An exception the compiler did not raise deliberately. For a program parsed in
     * this process it is an internal compiler error. For an AST supplied as JSON it
     * is most likely input the shape check does not cover, so it is reported as
     * malformed input near the innermost positioned node. Either way it is fatal.
     */
    static CompileException unexpected(Throwable error, JsonObject anchor, String context, CompilationState state) {
        SourceMap sources = state.sources;
        Diagnostic.Position near = anchor == null ? null : sources.position(anchor);
        Diagnostic diagnostic;
        if (state.parsed) {
            List<Diagnostic> notes = near == null ? List.of() : sources.includeNotes(sources.unitOf(anchor));
            diagnostic = Diagnostic.internalError(near != null ? near : Diagnostic.Position.file(sources.inputName()), context, error, notes);
            if (near != null) diagnostic = diagnostic.inUnit(sources.unitOf(anchor));
        } else diagnostic = sources.error(context, "Malformed script JSON" + (near == null ? "" : " near " + near));
        return new CompileException(List.of(diagnostic), error, true);
    }
    /** A layout error of a declared class, located at the given node of its declaration. */
    private static RuntimeException classError(ClassInfo info, JsonObject anchor, RuntimeException error) {
        if (error instanceof CompileException || info.sources == null || !Diagnostics.userError(error)) return error;
        return new CompileException(info.sources.error(anchor, "class " + info.name, message(error)), error);
    }
    /** Copies a node's original position onto a node the compiler synthesized from it; such a node never warns. */
    private static void copyPosition(JsonElement from, JsonObject to, LogicEnvironment environment) {
        if (from == null || !from.isJsonObject() || !from.getAsJsonObject().has("_line")) return;
        for (String key : new String[]{"_file", "_line", "_column"})
            if (from.getAsJsonObject().has(key)) to.add(key, from.getAsJsonObject().get(key));
        environment.state().synthesized.add(to);
    }
    /** Positions a node synthesized for a field at the field's declaration; such a node never warns. */
    private static void position(JsonObject node, Field field, LogicEnvironment environment) {
        if (field.line() <= 0) return;
        if (field.file() != 0) node.addProperty("_file", field.file());
        node.addProperty("_line", field.line()); node.addProperty("_column", field.column());
        environment.state().synthesized.add(node);
    }
    /** The diagnostic context of a function: "function main", "function C::C", "function C::operator[]". */
    static String contextName(String function) {
        int separator = function.lastIndexOf("::");
        if (separator < 0) return "function " + function;
        String owner = function.substring(0, separator), member = function.substring(separator + 2);
        String name = switch (member) {
            case "<ctor>", "<new>", "<scoped>", "<value>", "<placement>" -> owner;
            case "<dtor>" -> "~" + owner;
            default -> member.startsWith("<op:") && member.endsWith(">") ? "operator" + switch (member.substring(4, member.length() - 1)) {
                case "add" -> "+"; case "subtract" -> "-"; case "multiply" -> "*"; case "divide" -> "/";
                case "index-get" -> "[]"; case "index-set" -> "[]="; case "call" -> "()";
                default -> member.substring(4, member.length() - 1);
            } : member;
        };
        return "function " + owner + "::" + name;
    }
    /**
     * Reports a warning at a node of the compiled AST. Nodes without a source
     * position, such as handwritten AST statements, and nodes the compiler
     * synthesized never warn.
     */
    private static void warn(String code, JsonObject node, String message, LogicEnvironment environment) {
        CompilationState state = environment.state();
        if (!state.diagnostics.warningEnabled(code) || state.synthesized.contains(node)) return;
        Diagnostic.Position at = state.sources.position(node);
        if (at == null) return;
        int unit = state.sources.unitOf(node);
        state.diagnostics.warn(Diagnostic.warning(code, at, environment.context, message, state.sources.includeNotes(unit)).inUnit(unit));
    }
    /** assign-in-condition: an assignment written directly as a condition is probably a mistyped '=='. */
    private static void warnAssignmentCondition(JsonElement condition, LogicEnvironment environment) {
        if (!condition.isJsonObject() || !isControl(condition.getAsJsonObject())) return;
        JsonObject node = condition.getAsJsonObject();
        // Parentheses around the assignment mark it as intended; compound assignments are never comparisons.
        if (node.has("_parenthesized")) return;
        String call = node.get("call").getAsString();
        if (call.equals("varset") && !node.has("compound") || call.equals("member-set") || call.equals("index-set") || call.equals("mov"))
            warn("assign-in-condition", node, "assignment used as a condition; use '==' to compare, or wrap the assignment in parentheses", environment);
    }
    /**
     * unreachable-code: reports the first positioned statement in or at {@code statement},
     * which follows {@code end}, a statement that never completes normally. Returns
     * whether the statement was reported; a statement without a position is skipped.
     */
    private static boolean warnUnreachable(JsonElement end, JsonElement statement, LogicEnvironment environment) {
        JsonObject first = firstStatement(statement, environment.state());
        if (first == null) return false;
        String call = end.isJsonObject() && isControl(end.getAsJsonObject()) ? end.getAsJsonObject().get("call").getAsString() : "";
        String message = switch (call) {
            case "return", "break", "continue" -> "unreachable statement after " + call;
            case "while", "for" -> "unreachable statement after a loop that never ends";
            default -> "unreachable statement";
        };
        warn("unreachable-code", first, message, environment);
        return true;
    }
    /** The first positioned source statement of a statement or nested block, or null. */
    private static JsonObject firstStatement(JsonElement statement, CompilationState state) {
        if (statement.isJsonArray()) {
            for (JsonElement inner : statement.getAsJsonArray()) {
                JsonObject first = firstStatement(inner, state);
                if (first != null) return first;
            }
            return null;
        }
        if (!statement.isJsonObject()) return null;
        JsonObject node = statement.getAsJsonObject();
        return state.synthesized.contains(node) || state.sources.position(node) == null ? null : node;
    }
    /** unused-variable: reports the source declarations of a scope's variables that were never looked up. */
    private static void finishScope(LogicEnvironment scope) {
        for (LogicVariable variable : scope.vars)
            if (variable.declaration != null && !variable.used)
                warn("unused-variable", variable.declaration, "unused variable '" + variable.name + "'", scope);
    }
    private static AcsElement compileExpressionBody(JsonElement input, LogicEnvironment environment) {
        if (input == null || input.isJsonNull()) throw new IllegalArgumentException("Null is not an AzScript value");
        if (input.isJsonPrimitive()) {
            JsonPrimitive value = input.getAsJsonPrimitive();
            return withDepth(1, () -> {
                if (value.isString()) {
                    if (!GeneraterJson.wellFormedUtf16(value.getAsString()))
                        throw new IllegalArgumentException("String literal contains an unpaired surrogate");
                    return new AcsStringElement(value.getAsString());
                }
                if (value.isBoolean()) return new AcsBooleanElement(value.getAsBoolean());
                if (value.isNumber()) return number(value);
                throw new IllegalArgumentException("Invalid literal");
            });
        }
        if (input.isJsonArray()) {
            AcsArray block = new AcsArray(); LogicEnvironment scope = environment.child();
            boolean checkReachability = scope.state().diagnostics.warningEnabled("unreachable-code");
            withDepth(1, () -> {
                // The first statement that execution cannot reach is reported once per block.
                JsonElement end = null; boolean reported = !checkReachability;
                for (JsonElement statement : input.getAsJsonArray()) {
                    if (end != null && !reported) reported = warnUnreachable(end, statement, scope);
                    block.acsa.add(compileStatementNode(statement, scope,true));
                    if (end == null && !reported && !canCompleteNormally(statement)) end = statement;
                }
                return null;
            });
            finishScope(scope);
            return block;
        }
        if (!input.isJsonObject()) throw new IllegalArgumentException("Invalid expression");
        return compileInstruction(input.getAsJsonObject(), environment);
    }
    private static AcsElement compileStatementNode(JsonElement source,LogicEnvironment environment) {return compileStatementNode(source,environment,false);}
    private static AcsElement compileStatementNode(JsonElement source,LogicEnvironment environment,boolean siblings) {
        boolean added=environment.tables.statements.add(source),siblingAdded=siblings&&environment.tables.statementSiblings.add(source);
        try{return compileNode(source,environment);}
        finally{if(added)environment.tables.statements.remove(source);if(siblingAdded)environment.tables.statementSiblings.remove(source);}
    }
    private static AcsElement compileLadderElse(JsonElement source,LogicEnvironment environment) {
        boolean added=environment.tables.ladderTails.add(source);
        try{return compileStatementNode(source,environment);}
        finally{if(added)environment.tables.ladderTails.remove(source);}
    }
    private static AcsElement compileInstruction(JsonObject source, LogicEnvironment environment) {
        String type = string(source.get("t"), "Instruction t"), call = string(source.get("call"), "Instruction call");
        JsonArray params = parameters(source); AcsObject result = new AcsObject();
        JsonObject overloaded=overloadedCall(source,environment);
        if(overloaded!=null)return compileInstruction(overloaded,environment);
        if (type.equals("call")) {
            if(resolveCall(call,environment).equals(call)) {
                if (STRING_BUILTINS.containsKey(call)) return compileStringBuiltin(call, params, source.has("type-args"), environment);
                if (SourceRules.CONVERSIONS.contains(call)) return compileConversion(source, environment);
                if (call.equals("boolean") || call.equals("bool"))
                    throw new IllegalArgumentException("Boolean conversions are not supported; write a comparison");
                if(call.equals("reflect_invoke_function"))return compileReflection(source,environment);
                if(call.equals("value_compare"))return compileCompare(source,environment);
            }
            CallBinding binding=bindCall(source,environment);Signature signature=binding.signature();
            params=binding.arguments();String label=displayName(binding.name());int hidden=binding.name().contains("::")&&!isFactory(binding.name())?1:0;
            if(signature!=null&&params.size()!=signature.paramTypes().size())throw new IllegalArgumentException(label+" expects "+(signature.paramTypes().size()-hidden)+" arguments, got "+(params.size()-hidden));
            JsonArray converted=new JsonArray();
            for(int i=0;i<params.size();i++) {
                JsonElement argument=params.get(i);int actual=expressionType(argument,environment);
                if(signature!=null) {
                    int expected=signature.paramTypes().get(i);
                    if(expected==AsTypes.ANY_VALUE) {
                        if(!binding.name().equals("print")) rejectUntypedPointer(label+" argument "+(i+1-hidden),actual);
                        if(isOwnedValue(actual)||isTypeParameter(actual))throw new IllegalArgumentException(label+" argument cannot take an object or type parameter without a declared type");
                    }else if(actual!=AsTypes.ANY_VALUE||signature.requireKnown())requireKnownExact(label+" argument "+(i+1-hidden),expected,actual);
                    if(isFlexibleClass(expected)&&isValueClass(typeBound(actual)))argument=internal(environment,"#address-of",argument);
                }
                converted.add(argument);
            }
            Integer virtualSlot=environment.state().virtualCalls.get(source);
            if(virtualSlot==null) { result.put("t",1);result.put("id",environment.getFunctionId(binding.name())); }
            else { result.put("t",0);result.put("c",ExecOpcodes.CALL_VIRTUAL);result.put("slot",virtualSlot.intValue());result.put("type",abiType(signature.returnType())); }
            AcsArray args=new AcsArray();
            for(JsonElement argument:converted) {
                rejectVoidValue(label+" argument",argument,environment);
                args.acsa.add(withDepth(1,()->compileNode(argument,environment)));
            }
            result.put("param",args);if(virtualSlot==null)result.put("contexts",contexts(binding.types(),environment));
            if(binding.genericReturn())return typeCheck(result,binding.signature().returnType(),environment);
            return result;
        }
        if (!type.equals("ctrl")) throw new IllegalArgumentException("Unknown instruction type: " + type);
        result.put("t", 0);
        switch (call) {
            case "#precompiled" -> { return Objects.requireNonNull(precompiled(source,environment).value(),"Type-only precompiled value"); }
            case "chain" -> { return compileChain(source,environment); }
            case "buffer-new" -> {
                arity(call,params,1,1);int element=valueType(string(params.get(0),"Buffer element type"),true);bufferType(element);
                result.put("c",ExecOpcodes.BUFFER_NEW);result.put("context",context(element,environment,0));
            }
            case "var" -> {
                arity(call, params, 1, 1); result.put("c", ExecOpcodes.code("v"));
                String alias = string(params.get(0), "Variable name");
                if (alias.equals("this") && environment.ownerClass == null) throw new IllegalArgumentException("this is only available inside a class");
                if (environment.findLexicalVariable(alias) == null && implicitField(alias, environment) != null) {
                    JsonObject member = node("member", variable("this"), new JsonPrimitive(alias)); copyPosition(source, member, environment);
                    return compileInstruction(member, environment);
                }
                result.put("v", environment.getVariable(alias).slot);
            }
            case "vardef" -> {
                arity(call, params, 1, 2); String alias = string(params.get(0), "Variable name");
                boolean trusted = environment.root().trustedNodes.contains(source);
                if (alias.equals("this") && !trusted) throw new IllegalArgumentException("Cannot declare this");
                // Lowering names its own locals #...; any other name must be one source could declare.
                if (!trusted && !alias.startsWith("#")) validateSourceName(alias, "variable name");
                int declaredType = source.has("declared-type")
                        ? valueType(string(source.get("declared-type"), "Declared variable type"), true)
                        : AsTypes.ANY_VALUE;
                if(isBuffer(declaredType)&&params.size()==1) {
                    params=params.deepCopy();params.add(node("buffer-new",new JsonPrimitive(typeName(BUFFER_ELEMENTS.get().get(declaredType)))));
                }
                if (declaredType != AsTypes.ANY_VALUE && params.size() != 2)
                    throw new IllegalArgumentException(
                            "Typed variable " + alias + " must have an initializer");
                // Compile the initializer before introducing the binding: a shadow initializer can read its outer name.
                AcsElement initializer = null;
                if (params.size() == 2) {
                    rejectVoidValue("Variable initializer", params.get(1), environment);
                    if (declaredType != AsTypes.ANY_VALUE)
                        requireKnownExact("Initializer for " + alias, declaredType,
                                expressionType(params.get(1), environment));
                    else rejectUntypedObject("Untyped variable " + alias, params.get(1), environment);
                    initializer = compileNode(params.get(1), environment);
                }
                if (environment.counter.next >= MAX_VARIABLE_SLOTS)
                    throw new IllegalArgumentException("Function frame exceeds " + MAX_VARIABLE_SLOTS + " variable slots");
                LogicVariable variable = environment.define(
                        alias, environment.counter.next++, declaredType);
                // Objects and buffers declared for their construction and destruction are exempt.
                if (!isOwnedValue(declaredType) && !isTypeParameter(declaredType) && !alias.equals("this")
                        && !alias.startsWith("_") && !alias.startsWith("#") && !alias.startsWith("<"))
                    variable.declaration = source;
                result.put("c", ExecOpcodes.code("vd")); result.put("v", variable.slot);
                if (initializer != null) result.put("val", initializer);
                if (declaredType != AsTypes.ANY_VALUE) result.put("declared-type", abiType(declaredType));
            }
            case "varset" -> {
                arity(call, params, 2, 2); result.put("c", ExecOpcodes.code("vs"));
                String alias = string(params.get(0), "Variable name");
                if (alias.equals("this")) throw new IllegalArgumentException("Cannot assign to this");
                if (environment.findLexicalVariable(alias) == null && implicitField(alias, environment) != null) {
                    if (source.has("compound")) throw new IllegalArgumentException("Compound assignment to a member is not supported");
                    JsonObject assignment = node("member-set", node("member", variable("this"), new JsonPrimitive(alias)), params.get(1));
                    copyPosition(source, assignment, environment); return compileInstruction(assignment, environment);
                }
                LogicVariable variable = environment.getVariable(alias);
                rejectVoidValue("Assignment to " + alias, params.get(1), environment);
                if (variable.type != AsTypes.ANY_VALUE)
                    requireKnownExact("Assignment to " + alias, variable.type,
                            expressionType(params.get(1), environment));
                else rejectUntypedObject("Untyped variable " + alias, params.get(1), environment);
                result.put("v", variable.slot);
                result.put("val", compileNode(params.get(1), environment));
                environment.tables.results.put(result,isOwnedValue(variable.type)||isTypeParameter(variable.type)||variable.type==AsTypes.ANY_VALUE);
            }
            case "mov" -> {
                arity(call, params, 2, 2);
                if (isThisVariable(params.get(0))) throw new IllegalArgumentException("Cannot assign to this");
                if (params.get(0).isJsonObject() && isControl(params.get(0).getAsJsonObject())) {
                    JsonObject target = params.get(0).getAsJsonObject(); String operation = target.get("call").getAsString();
                    if (operation.equals("member")) {
                        JsonObject assignment = node("member-set", target, params.get(1)); copyPosition(source, assignment, environment);
                        return compileInstruction(assignment, environment);
                    }
                    if (operation.equals("var")) {
                        arity("var", parameters(target), 1, 1);
                        JsonObject assignment = node("varset", parameters(target).get(0), params.get(1)); copyPosition(source, assignment, environment);
                        return compileInstruction(assignment, environment);
                    }
                }
                AcsElement left = compileNode(params.get(0), environment);
                boolean variable = left instanceof AcsObject target && target.getAsInt("t") == 0
                        && target.getAsInt("c") == ExecOpcodes.VARIABLE;
                boolean memory = left instanceof AcsObject target && target.getAsInt("t") == 1
                        && target.getAsInt("id") == 0x0abd0006;
                if (!variable && !memory)
                    throw new IllegalArgumentException("Assignment target must be a variable or mem_get(pointer)");
                if (memory) arity("mem_get", params.get(0).getAsJsonObject().getAsJsonArray("param"), 1, 1);
                rejectVoidValue("Assignment", params.get(1), environment);
                rejectUntypedObject("Raw memory", params.get(1), environment);
                result.put("c", ExecOpcodes.code("m")); result.put("v1", left);
                result.put("v2", compileNode(params.get(1), environment));
            }
            case "return" -> {
                arity(call, params, 0, 1);
                if (environment.returnType == AsTypes.VOID_VALUE && !params.isEmpty())
                    throw new IllegalArgumentException("void function cannot return a value");
                if (environment.returnType != -1 && environment.returnType != AsTypes.VOID_VALUE && params.isEmpty())
                    throw new IllegalArgumentException("Non-void function must return a value");
                result.put("c", isTypeParameter(environment.returnType)?38:ExecOpcodes.code(isClass(environment.returnType)?"ro":"r"));
                if(isTypeParameter(environment.returnType))result.put("context",context(environment.returnType,environment,0));
                if (!params.isEmpty()) {
                    int actual = expressionType(params.get(0), environment);
                    if (isClass(environment.returnType)||isBuffer(environment.returnType)||isTypeParameter(environment.returnType)) requireKnownExact("return", environment.returnType, actual);
                    if (actual == AsTypes.VOID_VALUE)
                        throw new IllegalArgumentException("return cannot use a void value");
                    if (environment.returnType >= 0 && actual != AsTypes.ANY_VALUE
                            && !returnCompatible(environment.returnType, actual))
                        throw new IllegalArgumentException("return expected " + typeName(environment.returnType)
                                + ", got " + typeName(actual)
                                + (numeric(environment.returnType) && numeric(actual)
                                ? "; narrowing requires an explicit cast such as " + typeName(environment.returnType) + "(x)" : ""));
                    result.put(isTypeParameter(environment.returnType)?"v":"r", compileNode(params.get(0), environment));
                }
            }
            case "null" -> { arity(call, params, 0, 0); return withDepth(1, () -> new AcsAddress(0L)); }
            case "member" -> {
                arity(call, params, 2, 2); Field field = memberField(params, environment);
                ClassInfo info = receiverClass(params.get(0), environment);
                AcsObject address = new AcsObject(); address.put("t", 0); address.put("c", ExecOpcodes.code("oa"));
                JsonElement receiver = receiverAddress(params.get(0), environment);
                address.put("v", withDepth(2, () -> compileNode(receiver, environment))); address.put("offset", (int) info.fieldOffsets.get(field.name()));
                environment.state().metrics.tableProbes++;
                result.put("t", 1); result.put("id", 0x0abd0006);
                AcsArray args = new AcsArray(); args.acsa.add(address); result.put("param", args);
                environment.tables.results.put(result,isOwnedValue(field.type())||isTypeParameter(field.type())||field.type()==AsTypes.ANY_VALUE);
            }
            case "member-set" -> {
                arity(call, params, 2, 2);
                JsonObject member = params.get(0).getAsJsonObject();
                if (!isControl(member) || !string(member.get("call"), "Member target").equals("member"))
                    throw new IllegalArgumentException("Member assignment needs a field target");
                arity("member", parameters(member), 2, 2);
                requireWritableReceiver(parameters(member).get(0),environment);
                Field field = memberField(parameters(member), environment);
                requireKnownExact("Assignment to member " + field.name(), field.type(), expressionType(params.get(1), environment));
                result.put("c", ExecOpcodes.code("m")); result.put("v1", compileNode(member, environment));
                result.put("v2", compileNode(params.get(1), environment));
                environment.tables.results.put(result,isOwnedValue(field.type())||isTypeParameter(field.type())||field.type()==AsTypes.ANY_VALUE);
            }
            case "member-call" -> { return isStringReceiver(source,environment) ? compileStringMethod(source,environment)
                    : isBufferReceiver(source,environment)?compileBufferCall(source,environment):compileInstruction(memberCall(source,environment),environment); }
            case "index" -> {
                arity(call,params,2,2);
                if (!isStringReceiver(source,environment)) throw new IllegalArgumentException("Indexing requires a string or a class with an index operator");
                return compileStringBuiltin("str_char_at",params,source.has("type-args"),environment);
            }
            case "index-set" -> throw new IllegalArgumentException("Strings are immutable; cannot assign through an index");
            case "base-call" -> { return compileInstruction(baseCall(source,environment),environment); }
            case "object-new" -> {
                arity(call, params, 1, Integer.MAX_VALUE);
                ClassInfo info = classInfo(string(params.get(0), "Class name"), environment);
                JsonArray args = new JsonArray(); for (int i = 1; i < params.size(); i++) args.add(params.get(i));
                validateConstruction(info,environment,new HashSet<>());
                return compileInstruction(ownedCall(factoryName(info,true),args,info,environment),environment);
            }
            case "object-value" -> {
                arity(call, params, 1, Integer.MAX_VALUE);
                ClassInfo info = classInfo(string(params.get(0), "Class name"), environment);
                JsonArray args = new JsonArray(); for (int i = 1; i < params.size(); i++) args.add(params.get(i));
                validateConstruction(info,environment,new HashSet<>());
                return compileInstruction(ownedCall(info.declaration.name+VALUE_FACTORY,args,info,environment),environment);
            }
            case "object-def" -> {
                arity(call, params, 2, Integer.MAX_VALUE);
                String alias = string(params.get(0), "Variable name");
                ClassInfo info = classInfo(string(params.get(1), "Class name"), environment);
                JsonArray args = new JsonArray(); for (int i = 2; i < params.size(); i++) args.add(params.get(i));
                validateConstruction(info,environment,new HashSet<>());
                JsonObject construct = ownedCall(factoryName(info,false),args,info,environment);
                // The generated call is compiled as a nested expression; keep the declaration's location.
                copyPosition(source, construct, environment);
                JsonObject definition = node("vardef", new JsonPrimitive(alias), construct);
                definition.addProperty("declared-type", info.name + "*");
                return compileInstruction(definition, environment);
            }
            case "object-delete" -> {
                arity(call, params, 1, 1); int objectType = expressionType(params.get(0), environment);
                if (!isPointerClass(objectType) && objectType != NULL_TYPE)
                    throw new IllegalArgumentException(isValueClass(objectType) ? "delete requires a class pointer; a literal object ends with its variable"
                            : "delete requires a known class pointer or null");
                result.put("c", ExecOpcodes.code("od")); result.put("v", compileNode(params.get(0), environment));
            }
            case "#context-abi", "#context-default" -> {
                requireTrusted(source,environment);arity(call,params,1,1);
                result.put("c",call.equals("#context-abi")?36:37);result.put("context",context(params.get(0).getAsInt(),environment,0));
            }
            case "#allocate" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                ClassInfo info = classInfo(string(params.get(0), "Class name"), environment);
                // Compiler-generated allocation must bypass the class's user method names.
                result.put("t", 1); result.put("id", 0x0abd0014);
                AcsArray args = new AcsArray();
                args.acsa.add(withDepth(1, () -> compileNode(new JsonPrimitive(info.fields.size()), environment)));
                result.put("param", args);
            }
            case "#bind" -> {
                requireTrusted(source, environment); arity(call, params, 3, 3);
                result.put("c", ExecOpcodes.code("ob")); result.put("v", compileNode(params.get(0), environment));
                if (!params.get(1).isJsonNull()) {
                    String destructor=string(params.get(1),"Destructor name");result.put("destructor",environment.getFunctionId(destructor));
                    ClassInfo owner=environment.root().classes.get(destructor.substring(0,destructor.indexOf("::")));
                    ClassInfo actual=ancestor(classInfo(environment.ownerClass,environment),owner);
                    result.put("contexts",contexts(actual.arguments,environment));
                }
                result.put("manual", params.get(2).getAsBoolean());
            }
            case "#set-methods" -> {
                requireTrusted(source,environment);arity(call,params,0,0);
                ClassInfo info=classInfo(environment.ownerClass,environment);
                result.put("c",ExecOpcodes.SET_METHODS);result.put("v",compileNode(variable("this"),environment));
                AcsArray entries=new AcsArray();
                for(VirtualMethod method:info.vtable) {
                    AcsObject entry=new AcsObject();entry.put("id",environment.getFunctionId(method.function()));
                    AcsArray bindings=contexts(method.owner().arguments,environment);
                    // Entry list, entry record and its contexts list are physical ABD containers.
                    withDepth(3,()->null);
                    for(AcsElement binding:bindings.acsa)withDepth(4+contextHeight((AcsObject)binding),()->null);
                    entry.put("contexts",bindings);entries.acsa.add(entry);
                }
                result.put("entries",entries);
            }
            case "#base-completed" -> {
                requireTrusted(source,environment);arity(call,params,2,2);
                result.put("c",ExecOpcodes.SET);result.put("v",environment.getVariable(string(params.get(0),"Completion flag")).slot);
                // Store completion before the full statement releases literal
                // argument temporaries. Comparing two actual VOID values yields true.
                AcsObject completed=new AcsObject();completed.put("t",0);completed.put("c",ExecOpcodes.EQUAL);
                JsonElement baseCall=params.get(1);
                completed.put("v1",withDepth(1,()->compileNode(baseCall,environment)));
                completed.put("v2",withDepth(3,()->new AcsByteArray(new byte[]{(byte)0xff})));
                result.put("val",completed);environment.tables.atomic.add(result);
            }
            case "#new-block" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("new_block"));
                result.put("size", classInfo(string(params.get(0), "Class name"), environment).fields.size());
            }
            case "#address-of" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("block_address")); result.put("v", compileNode(params.get(0), environment));
            }
            case "#move" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                JsonArray target = parameters(params.get(0).getAsJsonObject());
                result.put("c", ExecOpcodes.code("mv")); result.put("v", environment.getVariable(string(target.get(0), "Moved variable")).slot);
            }
            case "#drop" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("drop")); result.put("v", compileNode(params.get(0), environment));
            }
            case "#check-this" -> {
                requireTrusted(source, environment); arity(call, params, 0, 0);
                result.put("c", ExecOpcodes.code("oa")); result.put("v", compileNode(variable("this"), environment)); result.put("offset", 0);
            }
            case "#field-initialize" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                // Initializers belong to the implementing class/module. Constructor parameters and
                // body locals cannot shadow their names; runtime allocations still belong to the body.
                LogicEnvironment initialization = environment.root().child();
                initialization.ownerClass = environment.ownerClass;initialization.typeScope=environment.typeScope;initialization.typeParameters=environment.typeParameters;
                initialization.context = "initializer of class " + environment.ownerClass;
                initialization.returnType = AsTypes.VOID_VALUE; initialization.counter = environment.counter;initialization.tables=environment.tables;
                initialization.define("this", 0, classInfo(environment.ownerClass, environment).pointerType);
                try { return compileInstruction(params.get(0).getAsJsonObject(), initialization); }
                catch (RuntimeException error) { throw relocate(error, source, initialization); }
            }
            case "#global-initialize" -> {
                requireTrusted(source, environment); arity(call, params, 2, 2);
                // A typed global is initialized in the module's onload, in a scope of its own:
                // onload locals cannot shadow it and its errors name the global.
                String name = string(params.get(0), "Global name");
                LogicEnvironment initialization = environment.root().child();
                initialization.context = "global " + name;
                initialization.returnType = AsTypes.VOID_VALUE; initialization.counter = environment.counter;initialization.tables=environment.tables;
                try {
                    JsonElement value = params.get(1);
                    rejectVoidValue("Initializer for global " + name, value, initialization);
                    requireKnownExact("Initializer for global " + name, initialization.getVariable(name).type, expressionType(value, initialization));
                    JsonObject assignment = node("varset", params.get(0), value); copyPosition(source, assignment, initialization);
                    return compileInstruction(assignment, initialization);
                }
                // Each initializer is a global item of its own: report it and go on with the next one and the
                // onload body; the checkpoint after the function phase still stops the compilation.
                catch (RuntimeException error) { recover(relocate(error, source, initialization), source, initialization.context, initialization); return new AcsArray(); }
            }
            case "#cleanup" -> {
                requireTrusted(source, environment); arity(call, params, 3, 3);
                result.put("c", ExecOpcodes.code("cleanup"));
                result.put("v", compileNode(params.get(0), environment));
                result.put("val", compileNode(params.get(1), environment));
                result.put("on-error", params.get(2).getAsBoolean());
            }
            case "bit_and", "bit_or", "bit_xor", "shl", "shr", "ushr" -> {
                arity(call, params, 2, 2);
                requireBitwiseIntegerOrUnknown(call + " left operand", params.get(0), environment);
                requireBitwiseIntegerOrUnknown(call + " right operand", params.get(1), environment);
                if (call.equals("shl") || call.equals("shr") || call.equals("ushr")) {
                    BigDecimal count = shiftCountLiteral(params.get(1));
                    if (count != null && (count.signum() < 0 || count.compareTo(BigDecimal.valueOf(31)) > 0))
                        throw new IllegalArgumentException("Shift count must be between 0 and 31, got " + count.toPlainString());
                }
                result.put("c", ExecOpcodes.code(call));
                result.put("v1", compileNode(params.get(0), environment));
                result.put("v2", compileNode(params.get(1), environment));
            }
            case "add", "minus", "multiply", "divide", "mod", "greater", "lower", "cmp", "ne", "ge", "le", "and", "or" -> {
                arity(call, params, 2, 2);
                if(isTypeParameter(expressionType(params.get(0),environment))||isTypeParameter(expressionType(params.get(1),environment)))throw new IllegalArgumentException("Operators are not supported on a type parameter");
                rejectVoidValue(call + " left operand", params.get(0), environment);
                rejectVoidValue(call + " right operand", params.get(1), environment);
                switch (call) {
                    case "add" -> requireAddableOrUnknown(params.get(0), params.get(1), environment);
                    case "minus" -> {
                        int left = expressionType(params.get(0), environment), right = expressionType(params.get(1), environment);
                        if (left == AsTypes.ADDRESS_VALUE) requireIntegerOrUnknown("Address offset", params.get(1), environment);
                        else if (left == AsTypes.ANY_VALUE && right == AsTypes.INT_VALUE) { /* Runtime decides numeric vs address. */ }
                        else {
                            requireNumericOrUnknown(call + " left operand", params.get(0), environment);
                            requireNumericOrUnknown(call + " right operand", params.get(1), environment);
                        }
                    }
                    case "greater", "lower", "ge", "le" -> {
                        int left = expressionType(params.get(0), environment), right = expressionType(params.get(1), environment);
                        if (left == AsTypes.ADDRESS_VALUE || right == AsTypes.ADDRESS_VALUE) {
                            if (left != AsTypes.ADDRESS_VALUE && left != AsTypes.ANY_VALUE || right != AsTypes.ADDRESS_VALUE && right != AsTypes.ANY_VALUE)
                                throw new IllegalArgumentException("Address ordering requires two addresses");
                        } else {
                            requireNumericOrUnknown(call + " left operand", params.get(0), environment);
                            requireNumericOrUnknown(call + " right operand", params.get(1), environment);
                        }
                    }
                    case "multiply", "divide" -> {
                        requireNumericOrUnknown(call + " left operand", params.get(0), environment);
                        requireNumericOrUnknown(call + " right operand", params.get(1), environment);
                    }
                    case "mod" -> {
                        requireIntegerOrUnknown("mod left operand", params.get(0), environment);
                        requireIntegerOrUnknown("mod right operand", params.get(1), environment);
                    }
                    case "and", "or" -> {
                        requireConditionOrUnknown(call + " left operand", params.get(0), environment);
                        requireConditionOrUnknown(call + " right operand", params.get(1), environment);
                    }
                    default -> requireComparable(params.get(0), params.get(1), environment);
                }
                result.put("c", ExecOpcodes.code(switch (call) { case "greater" -> "gt"; case "lower" -> "lt"; case "cmp" -> "eq"; default -> call; }));
                result.put("v1", compileNode(params.get(0), environment));
                result.put("v2", compileNode(params.get(1), environment));
            }
            case "neg", "pos", "not" -> {
                arity(call, params, 1, 1);
                String label = environment.state().diagnosticLabels.getOrDefault(source, call + " operand");
                rejectVoidValue(label, params.get(0), environment);
                if (call.equals("neg") || call.equals("pos")) requireNumericOrUnknown(call + " operand", params.get(0), environment);
                else requireConditionOrUnknown(label, params.get(0), environment);
                if (call.equals("pos")) {
                    // A dynamic value still needs the same numeric check as a known operand.
                    // Multiplication by int one preserves numeric type, signed zero and evaluation count.
                    if (expressionType(params.get(0), environment) == AsTypes.ANY_VALUE) {
                        JsonObject product = node("multiply", params.get(0), new JsonPrimitive(1)); copyPosition(source, product, environment);
                        return compileInstruction(product, environment);
                    }
                    return compileNode(params.get(0), environment);
                }
                result.put("c", ExecOpcodes.code(call)); result.put("v", compileNode(params.get(0), environment));
            }
            case "float" -> {
                arity(call, params, 1, 1);
                JsonElement value = params.get(0);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("float expects one numeric literal");
                BigDecimal decimal = value.getAsBigDecimal();
                float number = value.getAsFloat();
                if (!Float.isFinite(number) || (number == 0 && decimal.signum() != 0))
                    throw new IllegalArgumentException("Floating literal is outside float range");
                return withDepth(1, () -> new AcsFloat(number));
            }
            case "break", "continue" -> {
                arity(call, params, 0, 0);SyntheticLoop loop=environment.syntheticLoop;
                if(loop!=null&&(call.equals("continue")||loop.transparent())) {
                    if(call.equals("continue")&&loop.outerLoopDepth()==0)throw new IllegalArgumentException("continue can only be used inside a loop");
                    if(call.equals("break")&&!loop.breakAllowed())throw new IllegalArgumentException("break can only be used inside a loop or switch");
                    String flag=call.equals("break")?loop.breakFlag():loop.continueFlag();
                    if(!loop.transparent())return compileNode(array(node("varset",new JsonPrimitive(flag),new JsonPrimitive(true)),node("break")),environment);
                    AcsElement set=compileNode(node("varset",new JsonPrimitive(flag),new JsonPrimitive(true)),environment);recordPosition(source,set,environment);
                    AcsObject exit=new AcsObject();exit.put("t",0);exit.put("c",ExecOpcodes.BREAK);recordPosition(source,exit,environment);
                    return execSequence(List.of(set,exit),source,environment);
                }
                if (environment.loopDepth == 0 && loop == null)
                    throw new IllegalArgumentException(call + " can only be used inside " + (call.equals("break") ? "a loop or switch" : "a loop"));
                result.put("c", ExecOpcodes.code(call.equals("break") ? "brk" : "cont"));
            }
            case "#synthetic-break" -> {requireTrusted(source,environment);arity(call,params,0,0);result.put("c",ExecOpcodes.BREAK);}
            case "switch" -> { return compileSwitch(source, environment); }
            case "if-chain" -> { return compileFlatLadder(source,environment,environment.tables.statementSiblings.contains(source)); }
            case "case" -> throw new IllegalArgumentException("case/default can only be used inside a switch");
            case "#switch-loop", "#if-loop" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                SyntheticLoop descriptor = environment.state().syntheticLoops.get(source);
                if (descriptor == null) throw new IllegalArgumentException("Missing synthetic loop descriptor");
                LogicEnvironment loop = environment.child(); loop.syntheticLoop = descriptor;
                result.put("c", ExecOpcodes.WHILE); result.put("v", new AcsBooleanElement(true));
                result.put("val", compileStatementNode(params.get(0), loop)); finishScope(loop);
            }
            case "increment", "decrement" -> {
                arity(call, params, 1, 1); JsonElement target = params.get(0);
                if (!target.isJsonObject() || !"ctrl".equals(string(target.getAsJsonObject().get("t"), "Update target t"))
                        || !"var".equals(string(target.getAsJsonObject().get("call"), "Update target call")))
                    throw new IllegalArgumentException("Increment/decrement requires a simple variable");
                JsonArray targetParams = parameters(target.getAsJsonObject()); arity(call, targetParams, 1, 1);
                if (expressionType(target, environment) != AsTypes.ADDRESS_VALUE) requireNumericOrUnknown(call, target, environment);
                // Subtraction checks numeric values or address offsets even for dynamic variables.
                JsonObject assignment = node("varset", targetParams.get(0), node("minus", target,
                        new JsonPrimitive(call.equals("increment") ? -1 : 1)));
                assignment.addProperty("compound", true); copyPosition(source, assignment, environment);
                return compileNode(assignment, environment);
            }
            case "for" -> {
                arity(call, params, 4, 4);
                warnAssignmentCondition(params.get(1), environment);
                // Keep the initializer in a dedicated enclosing scope. Every next
                // iteration runs the step before testing the condition; continue
                // reaches that path after the previous body's scope has cleaned up.
                String first = "#for-first";
                JsonObject firstDefinition = node("vardef", new JsonPrimitive(first), new JsonPrimitive(true));
                firstDefinition.addProperty("declared-type", "boolean");
                // The loop test reports at the condition itself, or at the for statement for a literal condition.
                JsonElement condition = params.get(1).isJsonObject() && params.get(1).getAsJsonObject().has("_line") ? params.get(1) : source;
                JsonObject negated = node("not", params.get(1)), exit = node("if", negated, node("break"));
                copyPosition(condition, negated, environment); copyPosition(condition, exit, environment);
                environment.state().diagnosticLabels.put(negated, "for condition");
                JsonArray iteration = array(
                        node("if", variable(first), node("varset", new JsonPrimitive(first), new JsonPrimitive(false)), params.get(2)),
                        exit, array(params.get(3)));
                return compileNode(array(params.get(0), firstDefinition,
                        node("while", new JsonPrimitive(true), iteration)), environment);
            }
            case "select" -> {
                arity(call, params, 3, 3);
                warnAssignmentCondition(params.get(0), environment);
                int selectedType = selectType(params, environment);
                result.put("c", ExecOpcodes.SELECT);
                result.put("v", compileNode(params.get(0), environment));
                // The selected arm's owned temporaries belong to this statement's
                // existing scope. A statement-if would end an extra branch scope.
                result.put("then", selectArm(params.get(1), selectedType, source, environment));
                result.put("else", selectArm(params.get(2), selectedType, source, environment));
            }
            case "if", "while" -> {
                arity(call, params, 2, call.equals("if") ? 3 : 2);
                warnAssignmentCondition(params.get(0), environment);
                rejectVoidValue(call + " condition", params.get(0), environment);
                requireConditionOrUnknown(call + " condition", params.get(0), environment);
                result.put("c", ExecOpcodes.code(call.equals("if") ? "if" : "wi"));
                result.put("v", compileNode(params.get(0), environment));
                boolean statement=call.equals("while")||environment.tables.statements.contains(source);
                LogicEnvironment body = call.equals("while") ? environment.loopChild() : environment.child();
                // A body without braces still ends its own scope. Value-if arms
                // remain values and cannot acquire statement-only ladder lowering.
                result.put("val", statement?compileStatementNode(params.get(1),body):compileNode(params.get(1),body)); finishScope(body);
                if (params.size() == 3) {
                    LogicEnvironment otherwise = environment.child();
                    result.put("else",statement?compileLadderElse(params.get(2),otherwise):compileNode(params.get(2),otherwise)); finishScope(otherwise);
                }
                if(call.equals("if")&&statement&&!environment.tables.ladderTails.contains(source)&&ladder(source).branches().size()>1) {
                    LogicEnvironment captured=snapshotEnvironment(environment);boolean siblings=environment.tables.statementSiblings.contains(source);
                    environment.tables.ladders.put(result,()-> {
                        captured.counter.next=captured.tables.nextSlot;
                        AcsElement flattened=compileFlatLadder(source,captured,siblings);captured.tables.nextSlot=captured.counter.next;return flattened;
                    });
                }
            }
            default -> throw new IllegalArgumentException("Unknown control operation: " + call);
        }
        return result;
    }
    private record IfLadder(List<JsonObject> branches,JsonElement otherwise) {}
    private static IfLadder ladder(JsonObject source) {
        List<JsonObject> branches=new ArrayList<>();JsonElement otherwise=null;
        if(source.get("call").getAsString().equals("if-chain")) {
            for(JsonElement branch:parameters(source))branches.add(branch.getAsJsonObject());
            otherwise=source.get("else");
        } else {
            JsonObject current=source;
            while(true) {
                branches.add(current);JsonArray params=parameters(current);if(params.size()<3)break;
                JsonElement next=params.get(2);
                if(next.isJsonObject()&&isControl(next.getAsJsonObject())&&next.getAsJsonObject().get("call").getAsString().equals("if"))current=next.getAsJsonObject();
                else {otherwise=next;break;}
            }
        }
        return new IfLadder(List.copyOf(branches),otherwise);
    }
    /** Capture only bindings already visible at this source point, before later declarations. */
    private static LogicEnvironment snapshotEnvironment(LogicEnvironment environment) {
        if(environment.parent==null)return environment;
        LogicEnvironment snapshot=new LogicEnvironment(environment.root());snapshot.parent=snapshotEnvironment(environment.parent);
        snapshot.counter=environment.counter;snapshot.context=environment.context;snapshot.returnType=environment.returnType;
        snapshot.loopDepth=environment.loopDepth;snapshot.ownerClass=environment.ownerClass;snapshot.syntheticLoop=environment.syntheticLoop;
        snapshot.typeScope=environment.typeScope;snapshot.typeParameters=environment.typeParameters;snapshot.tables=environment.tables;
        snapshot.vars=new ArrayList<>(environment.vars);return snapshot;
    }
    private static AcsElement execSequence(List<AcsElement> statements,JsonElement source,LogicEnvironment environment) {
        if(statements.size()==1)return statements.get(0);
        AcsObject sequence=new AcsObject();sequence.put("t",0);sequence.put("c",ExecShape.INTERNAL_SEQUENCE);
        AcsArray prefix=new AcsArray();prefix.acsa.addAll(statements.subList(0,statements.size()-1));sequence.put("statements",prefix);sequence.put("v",statements.get(statements.size()-1));
        return recordPosition(source,sequence,environment);
    }
    /** All source transfers are retargeted while compiling under this transparent loop. */
    private static AcsElement compileFlatLadder(JsonObject source,LogicEnvironment environment,boolean firstInOriginalScope) {
        IfLadder ladder=ladder(source);boolean breaks=false,continues=false;
        for(int i=0;i<ladder.branches().size();i++) {
            JsonObject branch=ladder.branches().get(i);JsonArray params=parameters(branch);
            if((i>0||!firstInOriginalScope)&&!scopeSafeExpression(params.get(0),environment)
                    ||i>0&&!predicateReturnsSafe(params.get(0),environment)||!scopeSafeExpression(params.get(1),environment))
                throw ladderScopeError(branch,environment);
        }
        if(ladder.otherwise()!=null&&!scopeSafeExpression(ladder.otherwise(),environment))throw ladderScopeError(source,environment);
        for(JsonObject branch:ladder.branches()) {
            breaks|=ladderFlow(parameters(branch).get(1),"break");continues|=ladderFlow(parameters(branch).get(1),"continue");
        }
        if(ladder.otherwise()!=null) {breaks|=ladderFlow(ladder.otherwise(),"break");continues|=ladderFlow(ladder.otherwise(),"continue");}
        int serial=environment.counter.hidden++;String broken="#if-break-"+serial,continued="#if-continue-"+serial;
        boolean breakAllowed=environment.loopDepth>0||environment.syntheticLoop!=null&&environment.syntheticLoop.breakAllowed();
        SyntheticLoop descriptor=new SyntheticLoop(broken,continued,environment.loopDepth,true,breakAllowed);
        List<AcsElement> statements=new ArrayList<>();
        JsonObject firstBranch=ladder.branches().get(0);JsonElement firstCondition=parameters(firstBranch).get(0);
        warnAssignmentCondition(firstCondition,environment);rejectVoidValue("if condition",firstCondition,environment);requireConditionOrUnknown("if condition",firstCondition,environment);
        JsonElement firstAt=firstCondition.isJsonObject()&&firstCondition.getAsJsonObject().has("_line")?firstCondition:firstBranch;
        JsonObject inner=node("not",firstCondition),checked=node("not",inner);copyPosition(firstAt,inner,environment);copyPosition(firstAt,checked,environment);
        String firstValue="#if-first-"+serial;JsonObject saved=node("vardef",new JsonPrimitive(firstValue),checked);saved.addProperty("declared-type","boolean");copyPosition(firstAt,saved,environment);
        statements.add(compileStatementNode(saved,environment));
        // These are siblings of the loop and its relay tests, never an extra scope.
        for(String flag:List.of(broken,continued)) {
            if(flag.equals(broken)&&!breaks||flag.equals(continued)&&!continues)continue;
            JsonObject definition=node("vardef",new JsonPrimitive(flag),new JsonPrimitive(false));definition.addProperty("declared-type","boolean");copyPosition(source,definition,environment);
            statements.add(compileStatementNode(definition,environment));
        }
        JsonArray iteration=new JsonArray();
        for(int index=0;index<ladder.branches().size();index++) {
            JsonObject branch=ladder.branches().get(index);JsonArray params=parameters(branch);JsonArray body=new JsonArray();
            if(params.get(1).isJsonArray())body.addAll(params.get(1).getAsJsonArray());else body.add(params.get(1));
            JsonObject exit=internal(environment,"#synthetic-break");copyPosition(branch,exit,environment);body.add(exit);JsonObject conditional=node("if",index==0?variable(firstValue):params.get(0),body);copyPosition(branch,conditional,environment);iteration.add(conditional);
        }
        if(ladder.otherwise()!=null)iteration.add(ladder.otherwise());
        JsonObject exit=internal(environment,"#synthetic-break");copyPosition(source,exit,environment);iteration.add(exit);
        JsonObject loop=internal(environment,"#if-loop",iteration);copyPosition(source,loop,environment);environment.state().syntheticLoops.put(loop,descriptor);
        statements.add(compileStatementNode(loop,environment));
        if(breaks) {JsonObject relay=node("if",variable(broken),node("break"));copyPosition(source,relay,environment);statements.add(compileStatementNode(relay,environment));}
        if(continues) {JsonObject relay=node("if",variable(continued),node("continue"));copyPosition(source,relay,environment);statements.add(compileStatementNode(relay,environment));}
        return execSequence(statements,source,environment);
    }
    private static RuntimeException ladderScopeError(JsonObject source,LogicEnvironment environment) {
        return relocate(new IllegalArgumentException("Flattening this if ladder would change object lifetime or scope; store this operand in a local variable first"),source,environment);
    }
    private static boolean scopeSafeExpression(JsonElement source,LogicEnvironment environment) {
        Set<Integer> dependencies=new HashSet<>();return scopeDependencies(source,environment,dependencies)&&environment.tables.scopeSafeCalls.containsAll(dependencies);
    }
    private static boolean predicateReturnsSafe(JsonElement source,LogicEnvironment environment) {
        if(source==null||source.isJsonNull()||source.isJsonPrimitive())return true;
        if(source.isJsonArray()){for(JsonElement child:source.getAsJsonArray())if(!predicateReturnsSafe(child,environment))return false;return true;}
        JsonObject node=source.getAsJsonObject();String kind=node.get("t").getAsString(),call=node.get("call").getAsString();
        String resolved=kind.equals("call")?resolveCall(call,environment):call;
        if(kind.equals("call")&&(!SourceRules.CONVERSIONS.contains(call)||!resolved.equals(call))) {
            Integer id=environment.root().abs.get(resolved);if(id==null)return false;
            int type=environment.root().functionReturnTypes.getOrDefault(id,AsTypes.ANY_VALUE);
            if(id!=0x0abd0006&&(type<0||type>AsTypes.VOID_VALUE))return false;
        }
        for(JsonElement child:parameters(node))if(!predicateReturnsSafe(child,environment))return false;
        return true;
    }
    private static boolean ladderFlow(JsonElement value,String transfer) {
        if(value==null||value.isJsonNull()||value.isJsonPrimitive())return false;
        if(value.isJsonArray()) {for(JsonElement child:value.getAsJsonArray())if(ladderFlow(child,transfer))return true;return false;}
        JsonObject node=value.getAsJsonObject();if(!isControl(node))return false;String call=node.get("call").getAsString();
        if(call.equals(transfer))return true;if(call.equals("while")||call.equals("for")||transfer.equals("break")&&call.equals("switch"))return false;
        for(JsonElement child:parameters(node))if(ladderFlow(child,transfer))return true;
        return call.equals("if-chain")&&node.has("else")&&ladderFlow(node.get("else"),transfer);
    }
    private static FunctionTables.Precompiled precompiled(JsonObject source,LogicEnvironment environment) {
        FunctionTables.Precompiled value=environment.tables.precompiled.get(source);
        if(value==null)throw new IllegalArgumentException("Unknown internal precompiled expression");return value;
    }
    private static JsonObject precompiled(AcsElement value,int type,JsonElement source,LogicEnvironment environment) {
        JsonObject node=node("#precompiled");environment.tables.precompiled.put(node,new FunctionTables.Precompiled(value,type));
        copyPosition(source,node,environment);return node;
    }
    /** Query a left fold without constructing a recursively nested source tree. */
    private static int chainType(JsonObject source,LogicEnvironment environment) {
        JsonArray operands=parameters(source),operators=source.getAsJsonArray("operators");
        int type=expressionType(operands.get(0),environment);
        for(int i=1;i<operands.size();i++) {
            JsonObject operator=operators.get(i-1).getAsJsonObject();
            JsonObject pair=node(operator.get("call").getAsString(),precompiled(null,type,operator,environment),operands.get(i));
            copyPosition(operator,pair,environment);type=expressionType(pair,environment);
        }
        return type;
    }
    private static boolean checkedStringOperand(JsonElement operand,LogicEnvironment environment) {
        if(operand.isJsonPrimitive())return operand.getAsJsonPrimitive().isString();
        if(!operand.isJsonObject())return false;
        JsonObject node=operand.getAsJsonObject();String kind=node.get("t").getAsString(),call=node.get("call").getAsString();
        if(kind.equals("ctrl")&&call.equals("var")) {
            LogicVariable local=environment.findLexicalVariable(parameters(node).get(0).getAsString());
            return local!=null&&local.type==AsTypes.STRING_VALUE;
        }
        return (kind.equals("call")||kind.equals("ctrl")&&(call.equals("member-call")||call.equals("base-call")))
                &&expressionType(operand,environment)==AsTypes.STRING_VALUE;
    }
    private static JsonElement balancedChain(List<JsonElement> operands,JsonArray operators,int first,int end,LogicEnvironment environment) {
        if(end-first==1)return operands.get(first);
        int middle=(first+end)>>>1;JsonObject operator=operators.get(middle-1).getAsJsonObject();
        JsonObject result=node(operator.get("call").getAsString(),balancedChain(operands,operators,first,middle,environment),
                balancedChain(operands,operators,middle,end,environment));copyPosition(operator,result,environment);return result;
    }
    private static AcsElement compileChain(JsonObject source,LogicEnvironment environment) {
        JsonArray operands=parameters(source),operators=source.getAsJsonArray("operators");
        String first=operators.get(0).getAsJsonObject().get("call").getAsString();
        boolean uniform=true;for(JsonElement operator:operators)uniform&=operator.getAsJsonObject().get("call").getAsString().equals(first);
        boolean logical=uniform&&(first.equals("and")||first.equals("or"));
        boolean strings=uniform&&first.equals("add");
        if(strings)for(JsonElement operand:operands)if(!checkedStringOperand(operand,environment)){strings=false;break;}
        if(logical||strings) {
            List<JsonElement> leaves=new ArrayList<>();
            for(int i=0;i<operands.size();i++) {
                JsonElement operand=operands.get(i);
                if(logical) {
                    JsonObject operator=operators.get(Math.max(0,i-1)).getAsJsonObject();
                    try {
                        if(isTypeParameter(expressionType(operand,environment)))throw new IllegalArgumentException("Operators are not supported on a type parameter");
                        String label=first+(i==0?" left operand":" right operand");
                        rejectVoidValue(label,operand,environment);requireConditionOrUnknown(label,operand,environment);
                    }catch(RuntimeException error){throw relocate(error,operator,environment);}
                    // Every original leaf is checked before later effects, even when
                    // the balanced parent groups several leaves on its right.
                    JsonObject inner=node("not",operand),checked=node("not",inner);
                    copyPosition(operator,inner,environment);copyPosition(operator,checked,environment);operand=checked;
                }
                leaves.add(operand);
            }
            return compileNode(balancedChain(leaves,operators,0,leaves.size(),environment),environment);
        }
        JsonElement left=operands.get(0);AcsElement value=null;AcsArray prefixes=new AcsArray();
        for(int i=1;i<operands.size();i++) {
            JsonObject operator=operators.get(i-1).getAsJsonObject();
            JsonObject pair=node(operator.get("call").getAsString(),left,operands.get(i));copyPosition(operator,pair,environment);
            value=compileNode(pair,environment);int type=expressionType(pair,environment);
            if(i%64==0&&i+1<operands.size()) {
                if(environment.tables.owningResult(value))throw relocate(new IllegalArgumentException(
                        "Expression nesting exceeds the ABD limit of "+environment.state().abdLimit
                        +" levels; this cut would change object lifetime or copying; store this operand in a local variable first"),operator,environment);
                if(environment.counter.next>=MAX_VARIABLE_SLOTS)throw new IllegalArgumentException("Function frame exceeds "+MAX_VARIABLE_SLOTS+" variable slots");
                int slot=environment.counter.next++;environment.tables.slots.put(slot,0);environment.tables.temporarySlots.add(slot);
                AcsObject definition=new AcsObject();definition.put("t",0);definition.put("c",ExecOpcodes.DEFINE);definition.put("v",slot);definition.put("val",value);
                environment.tables.stagedCaptures.add(definition);recordPosition(operator,definition,environment);prefixes.acsa.add(definition);
                AcsObject read=new AcsObject();read.put("t",0);read.put("c",ExecOpcodes.VARIABLE);read.put("v",slot);
                left=precompiled(read,type,operator,environment);
            } else left=precompiled(value,type,operator,environment);
        }
        if(prefixes.acsa.isEmpty())return value;
        AcsObject sequence=new AcsObject();sequence.put("t",0);sequence.put("c",ExecShape.INTERNAL_SEQUENCE);sequence.put("statements",prefixes);sequence.put("v",value);
        return sequence;
    }
    private record SwitchSection(List<JsonElement> labels, JsonArray body, boolean fallback, JsonObject anchor) {}
    private static AcsElement compileSwitch(JsonObject source, LogicEnvironment environment) {
        JsonArray params = parameters(source); arity("switch", params, 2, 2);
        int type = expressionType(params.get(0), environment);
        if (type != AsTypes.INT_VALUE && type != AsTypes.STRING_VALUE)
            throw new IllegalArgumentException("switch value must be int or string, got " + typeName(type)
                    + (type == AsTypes.ANY_VALUE ? "; convert it, e.g. switch (int(value))" : ""));
        if (!params.get(1).isJsonArray()) throw new IllegalArgumentException("switch sections must be an array");
        JsonArray sections = params.get(1).getAsJsonArray();
        Set<Object> seen = new HashSet<>(); boolean defaultSeen = false;
        List<SwitchSection> groups = new ArrayList<>(); List<JsonElement> labels = new ArrayList<>();
        boolean fallback = false; JsonObject anchor = null;
        for (int i = 0; i < sections.size(); i++) {
            JsonObject section = sections.get(i).getAsJsonObject(); JsonArray sectionParams = parameters(section);
            if (!isControl(section) || !section.get("call").getAsString().equals("case"))
                throw relocate(new IllegalArgumentException("Invalid switch section"), section, environment);
            arity("case", sectionParams, 1, 1);
            if (anchor == null) anchor = section;
            if (section.has("default")) {
                if (defaultSeen) throw relocate(new IllegalArgumentException("Duplicate default label"), section, environment);
                defaultSeen = true; fallback = true;
            }
            for (JsonElement label : section.getAsJsonArray("labels")) {
                boolean valid = label.isJsonPrimitive() && (type == AsTypes.INT_VALUE
                        ? label.getAsJsonPrimitive().isNumber() && integerLiteral(label.getAsString()) : label.getAsJsonPrimitive().isString());
                if (!valid) throw relocate(new IllegalArgumentException("case label must be " + (type == AsTypes.INT_VALUE ? "an int" : "a string") + " literal"), section, environment);
                Object key;
                if (type == AsTypes.INT_VALUE) {
                    try { key = label.getAsBigDecimal().intValueExact(); }
                    catch (ArithmeticException error) { throw relocate(new IllegalArgumentException("case label is outside signed 32-bit range"), section, environment); }
                } else key = label.getAsString();
                if (!seen.add(key)) throw relocate(new IllegalArgumentException("Duplicate case label "
                        + (type == AsTypes.INT_VALUE ? key : label.toString())), section, environment);
                labels.add(label);
            }
            JsonArray body = sectionParams.get(0).getAsJsonArray();
            if (!body.isEmpty() || i == sections.size() - 1) {
                groups.add(new SwitchSection(List.copyOf(labels), body, fallback, anchor));
                labels.clear(); fallback = false; anchor = null;
            }
        }
        for (int i = 0; i + 1 < groups.size(); i++) if (canCompleteNormally(groups.get(i).body()))
            throw relocate(new IllegalArgumentException("Switch section can fall through; end it with break, continue or return"), groups.get(i).anchor(), environment);
        int serial = environment.counter.hidden++;
        String value = "#switch-value-" + serial, continued = "#switch-continue-" + serial;
        // breakFlag is reserved for transparent synthetic loops (C27); source
        // switch breaks already target this wrapper directly.
        SyntheticLoop descriptor = new SyntheticLoop("#switch-break-" + serial, continued, environment.loopDepth,false,true);
        JsonObject saved = node("vardef", new JsonPrimitive(value), params.get(0)); saved.addProperty("declared-type", typeName(type));
        copyPosition(source, saved, environment);
        JsonArray wrapper = array(saved); boolean relayContinue = groups.stream().anyMatch(group -> containsContinue(group.body()));
        if (relayContinue) {
            JsonObject flag = node("vardef", new JsonPrimitive(continued), new JsonPrimitive(false)); flag.addProperty("declared-type", "boolean");
            copyPosition(source, flag, environment); wrapper.add(flag);
        }
        JsonArray iteration = new JsonArray(); SwitchSection defaultSection = null;
        for (SwitchSection group : groups) {
            if (group.fallback()) defaultSection = group;
            if (group.labels().isEmpty()) continue;
            List<JsonElement> matches = new ArrayList<>();
            for (JsonElement label : group.labels()) matches.add(node("cmp", variable(value), label));
            JsonObject branch = node("if", balancedOr(matches, 0, matches.size()), switchBody(group.body()));
            copyPosition(group.anchor(), branch, environment); iteration.add(branch);
        }
        if (defaultSection != null) iteration.add(switchBody(defaultSection.body()));
        iteration.add(node("break"));
        JsonObject loop = internal(environment, "#switch-loop", iteration); copyPosition(source, loop, environment);
        environment.state().syntheticLoops.put(loop, descriptor); wrapper.add(loop);
        if (relayContinue && descriptor.outerLoopDepth() > 0) {
            JsonObject relay = node("if", variable(continued), node("continue")); copyPosition(source, relay, environment); wrapper.add(relay);
        }
        return compileNode(wrapper, environment);
    }
    private static JsonArray switchBody(JsonArray body) {
        JsonArray result = new JsonArray(); result.addAll(body);
        if (canCompleteNormally(body)) result.add(node("break"));
        return result;
    }
    private static JsonElement balancedOr(List<JsonElement> values, int begin, int end) {
        if (end - begin == 1) return values.get(begin);
        int middle = (begin + end) >>> 1;
        return node("or", balancedOr(values, begin, middle), balancedOr(values, middle, end));
    }
    private static int valueType(String type, boolean valueOnly) {
        int[] holder=TYPE_DEPTH.get();int depth=holder[0];
        if(depth>=MAX_VALUE_NESTING)throw new IllegalArgumentException("Type nesting exceeds "+MAX_VALUE_NESTING+" levels");
        holder[0]=depth+1;
        try{return valueTypeBody(type,valueOnly);}finally{holder[0]=depth;}
    }
    private static int valueTypeBody(String type, boolean valueOnly) {
        type=type.replace(" ","");
        Integer variable=TYPE_SCOPE.get().get(type);
        if(variable!=null)return variable;
        int result = switch(type) {
            case "int" -> 0;case "string" -> 1;case "float" -> 2;case "double" -> 3;
            case "boolean","bool" -> 4;case "void" -> 5;case "any" -> 6;case "address" -> 7;
            default -> {
                boolean pointer=type.endsWith("*");String base=pointer?type.substring(0,type.length()-1):type;
                if(TYPE_SCOPE.get().containsKey(base))throw new IllegalArgumentException("A type parameter represents a complete type; cannot append * or (*)");
                int angle=base.indexOf('<');String name=angle<0?base:base.substring(0,angle);
                if(name.equals("buffer")) {
                    if(pointer||angle<0||!base.endsWith(">"))throw new IllegalArgumentException("buffer requires one element type and cannot be a pointer");
                    List<String> arguments=typeArguments(base.substring(angle+1,base.length()-1));
                    if(arguments.size()!=1)throw new IllegalArgumentException("buffer requires exactly one element type");
                    yield bufferType(valueType(arguments.get(0),true));
                }
                ClassInfo declaration=CLASS_DECLARATIONS.get().get(name);
                if(declaration==null)throw new IllegalArgumentException("Unknown type: "+type);
                ClassInfo applied=declaration;
                if(angle>=0) {
                    if(!base.endsWith(">"))throw new IllegalArgumentException("Invalid parameterized type: "+type);
                    List<Integer> arguments=new ArrayList<>();
                    for(String argument:typeArguments(base.substring(angle+1,base.length()-1))) arguments.add(valueType(argument,true));
                    applied=applyClass(declaration,arguments);
                } else if(!declaration.arguments.isEmpty()) {
                    // Only a compiler-generated owner reference can use its open declaration name.
                    boolean inOwner=declaration.typeScope.entrySet().stream().allMatch(e->Objects.equals(TYPE_SCOPE.get().get(e.getKey()),e.getValue()));
                    if(!inOwner)throw new IllegalArgumentException("Class "+name+" requires explicit type arguments");
                }
                yield applied.valueType+(pointer?1:0);
            }
        };
        if(valueOnly&&(result==5||result==6))throw new IllegalArgumentException(type+" is not a concrete value type");
        return result;
    }
    private static int parameterType(String type) {
        if(!type.endsWith("(*)"))return valueType(type,false);
        int value=valueType(type.substring(0,type.length()-3),true);
        if(!isValueClass(value))throw new IllegalArgumentException("(*) requires a class type");
        return value+2;
    }
    private static int returnType(String type) {
        int result = valueType(type, false);
        if (result == AsTypes.ANY_VALUE) throw new IllegalArgumentException("Unknown return type: " + type);
        return result;
    }
    private static void addBuiltinSignatures(LogicEnvironment environment) {
        for (BuiltinSpec spec : BUILTINS) {
            environment.builtinSignatures.put(
                    spec.id(), new Signature(spec.id(), spec.returnType(), spec.paramTypes(), false));
            environment.functionReturnTypes.put(spec.id(), spec.returnType());
        }
    }
    private static List<Integer> readParameterTypes(JsonElement field, int expectedCount,
                                                    boolean allowAny, String context) {
        if (field == null) {
            if (!allowAny) throw new IllegalArgumentException(context + " is missing param-types");
            return new ArrayList<>(Collections.nCopies(expectedCount, AsTypes.ANY_VALUE));
        }
        if (!field.isJsonArray()) throw new IllegalArgumentException(context + " param-types must be an array");
        JsonArray values = field.getAsJsonArray();
        if (expectedCount >= 0 && values.size() != expectedCount)
            throw new IllegalArgumentException(context + " param-types count does not match param count");
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            int type = parameterType(string(values.get(i), context + " parameter type " + (i + 1)));
            if (type == AsTypes.VOID_VALUE || !allowAny && type == AsTypes.ANY_VALUE)
                throw new IllegalArgumentException(context + " parameter " + (i + 1)
                        + " must have a concrete non-void type");
            result.add(type);
        }
        return List.copyOf(result);
    }
    private record Function(int id, String name, int returnType, List<Integer> paramTypes,
                            JsonObject metadata, JsonElement script) {
        Map<String,Integer> typeScope(){return functionScope(metadata);}
        List<Integer> typeParameters(){return List.copyOf(typeScope().values());}
        int classParameters(){return metadata.has("owner-class")?CLASS_DECLARATIONS.get().get(metadata.get("owner-class").getAsString()).arguments.size():0;}
        boolean internal(){return id==0||id==1||isFactory(name)||metadata.has("function-kind")&&!metadata.get("function-kind").getAsString().equals("method");}
        Signature signature(){return new Signature(id,returnType,paramTypes,true,typeParameters(),classParameters(),internal());}
    }
    private static Map<String,Integer> functionScope(JsonObject metadata) {
        Map<String,Integer> saved=FUNCTION_SCOPES.get().get(metadata);if(saved!=null)return saved;
        Map<String,Integer> result=new LinkedHashMap<>();
        if(metadata.has("owner-class")) {
            ClassInfo owner=CLASS_DECLARATIONS.get().get(string(metadata.get("owner-class"),"Function owner"));
            if(owner==null)throw new IllegalArgumentException("Unknown function owner");
            result.putAll(owner.typeScope);
        }
        if(metadata.has("function-kind")&&!metadata.get("function-kind").getAsString().equals("method")
                &&metadata.has("type-parameters")&&!metadata.getAsJsonArray("type-parameters").isEmpty())
            throw new IllegalArgumentException("Constructors and destructors cannot declare their own type parameters");
        declareTypeParameters(metadata,result);FUNCTION_SCOPES.get().put(metadata,result);return result;
    }
    private record CallBinding(String name, Signature signature, JsonArray arguments,List<Integer> types,boolean genericReturn) {}
    private static ClassInfo ancestor(ClassInfo info,ClassInfo declaration) {
        for(ClassInfo cursor=info;cursor!=null;cursor=cursor.base) {
            ensureLayout(cursor);if(cursor.declaration==declaration.declaration)return cursor;
        }
        throw new IllegalArgumentException(info.name+" does not inherit "+declaration.name);
    }
    private static JsonObject ownedCall(String name,JsonArray arguments,ClassInfo owner,LogicEnvironment environment) {
        JsonObject result=callNode(name,arguments);environment.root().callOwners.put(result,owner);return result;
    }
    private static JsonObject memberCall(JsonObject source,LogicEnvironment environment) {
        JsonArray params=parameters(source);arity("member-call",params,2,Integer.MAX_VALUE);
        ClassInfo info=receiverClass(params.get(0),environment);
        String method=info.methods.get(string(params.get(1),"Member name"));
        if(method==null)throw new IllegalArgumentException("Unknown method: "+info.name+"."+params.get(1).getAsString());
        JsonArray arguments=new JsonArray();arguments.add(receiverAddress(params.get(0),environment));
        for(int i=2;i<params.size();i++)arguments.add(params.get(i));
        JsonObject result=ownedCall(method,arguments,info,environment);
        markVirtualCall(result,info,params.get(1).getAsString(),expressionType(params.get(0),environment),environment);
        if(source.has("type-args"))result.add("type-args",source.get("type-args"));return result;
    }
    private static void markVirtualCall(JsonObject call,ClassInfo receiver,String name,int receiverType,LogicEnvironment environment) {
        Integer slot=receiver.virtualSlots.get(name);
        if(slot!=null&&isClassPointer(receiverType)&&!receiver.vtable.get(slot).finalMethod())
            environment.state().virtualCalls.put(call,slot);
    }
    /**
     * {@code Base::m(args)}: the method {@code m} visible in {@code Base}, which is the
     * current class or one of its bases, called on {@code this} with static binding. The
     * class is named without type arguments; they come from the inheritance chain.
     */
    private static JsonObject baseCall(JsonObject source,LogicEnvironment environment) {
        JsonArray params=parameters(source);arity("base-call",params,2,Integer.MAX_VALUE);
        String className=string(params.get(0),"Class name"),name=string(params.get(1),"Member name");
        if(environment.ownerClass==null)
            throw new IllegalArgumentException(className+"::"+name+"() calls are only available inside class functions");
        ClassInfo owner=receiverClass(variable("this"),environment),target=null;
        for(ClassInfo cursor=owner;cursor!=null&&target==null;cursor=cursor.base){ensureLayout(cursor);if(cursor.declaration.name.equals(className))target=cursor;}
        if(target==null)throw new IllegalArgumentException(className+" is not "+owner.declaration.name+" or one of its base classes");
        String method=target.methods.get(name);
        boolean constructor=false;
        for(ClassInfo cursor=owner;cursor!=null&&!constructor;cursor=cursor.base)constructor=cursor.declaration.name.equals(name);
        if(method==null&&constructor)
            throw new IllegalArgumentException("Constructors cannot be called as "+className+"::"+name+"(...); use a base initializer");
        if(method==null)throw new IllegalArgumentException(target.visibleFields.containsKey(name)
                ?className+"."+name+" is a field, not a method":"Unknown method: "+className+"."+name);
        JsonArray arguments=new JsonArray();arguments.add(variable("this"));
        for(int i=2;i<params.size();i++)arguments.add(params.get(i));
        JsonObject result=ownedCall(method,arguments,target,environment);
        if(source.has("type-args"))result.add("type-args",source.get("type-args"));return result;
    }
    private static final Set<String> OPERATOR_KINDS=Set.of("add","subtract","multiply","divide","index-get","index-set","call");
    private static JsonObject operatorCall(String operation,JsonElement receiver,JsonArray arguments,JsonObject source,LogicEnvironment environment) {
        ClassInfo info=receiverClass(receiver,environment);
        String method=info.operators.get(operation);
        if(method==null)throw new IllegalArgumentException("Class "+info.name+" does not define operator "+operation);
        JsonArray all=new JsonArray();all.add(receiverAddress(receiver,environment));arguments.forEach(all::add);
        JsonObject result=ownedCall(method,all,info,environment);
        if(source.has("type-args"))result.add("type-args",source.get("type-args"));
        return result;
    }
    private static final Set<String> INDEX_OPERATIONS=Set.of("index","index-set","invoke"),ARITHMETIC_OPERATIONS=Set.of("add","minus","multiply","divide");
    /** Operators are resolved from static types and lower to ordinary member calls. */
    private static JsonObject overloadedCall(JsonObject source,LogicEnvironment environment) {
        String kind=string(source.get("t"),"Instruction t"),operation=string(source.get("call"),"Instruction call");
        JsonArray params=parameters(source);
        if(kind.equals("call")) {
            if(operation.contains("::")||isBuiltinName(operation))return null;
            LogicVariable local=environment.findLexicalVariable(operation);
            Field field=local==null?implicitField(operation,environment):null;
            LogicVariable global=local==null&&field==null?environment.findVariable(operation):null;
            int type=local!=null?local.type:field!=null?field.type():global!=null?global.type:AsTypes.ANY_VALUE;
            if(isClass(typeBound(type)))return operatorCall("call",variable(operation),params,source,environment);
            return null;
        }
        if(!kind.equals("ctrl"))return null;
        if(INDEX_OPERATIONS.contains(operation)) {
            arity(operation,params,operation.equals("invoke")?1:operation.equals("index")?2:3,operation.equals("invoke")?Integer.MAX_VALUE:operation.equals("index")?2:3);
            if (!operation.equals("invoke") && expressionType(params.get(0),environment) == AsTypes.STRING_VALUE) return null;
            if(operation.equals("index-set"))requireWritableReceiver(params.get(0),environment);
            JsonArray arguments=new JsonArray();for(int i=1;i<params.size();i++)arguments.add(params.get(i));
            return operatorCall(operation.equals("index")?"index-get":operation.equals("index-set")?"index-set":"call",params.get(0),arguments,source,environment);
        }
        if(ARITHMETIC_OPERATIONS.contains(operation)&&params.size()==2
                &&isValueClass(typeBound(expressionType(params.get(0),environment))))
            return operatorCall(operation.equals("minus")?"subtract":operation,params.get(0),array(params.get(1)),source,environment);
        return null;
    }
    private static void requireWritableReceiver(JsonElement receiver,LogicEnvironment environment) {
        int type=typeBound(expressionType(receiver,environment));
        if(!isOwnedValue(type)||!receiver.isJsonObject())return;
        JsonObject object=receiver.getAsJsonObject();if(!isControl(object))return;
        String operation=object.get("call").getAsString();JsonArray params=parameters(object);
        if(operation.equals("index")||operation.equals("member-call")&&isBufferReceiver(object,environment)
                &&params.get(1).getAsString().equals("get"))
            throw new IllegalArgumentException("Cannot modify a value returned by an index getter; modify a local value and write it back");
        if(operation.equals("member"))requireWritableReceiver(params.get(0),environment);
        if(operation.equals("select")) {
            arity("select",params,3,3);
            requireWritableReceiver(params.get(1),environment);requireWritableReceiver(params.get(2),environment);
        }
    }
    private static final List<String> BUFFER_METHODS=List.of("length","capacity","reserve","get","set","push","resize");
    private static boolean isStringReceiver(JsonObject source, LogicEnvironment environment) {
        JsonArray params = parameters(source);
        return !params.isEmpty() && expressionType(params.get(0),environment) == AsTypes.STRING_VALUE;
    }
    private static String stringMethod(JsonObject source) {
        JsonArray params = parameters(source); arity("String method",params,2,Integer.MAX_VALUE);
        String name = string(params.get(1),"String method name");
        if (!STRING_METHODS.containsKey(name)) throw new IllegalArgumentException("Unknown string method: " + name);
        return name;
    }
    private static int stringMethodType(JsonObject source) {
        String method = stringMethod(source);
        return method.equals("contains") ? AsTypes.BOOLEAN_VALUE : STRING_BUILTINS.get(STRING_METHODS.get(method)).returnType();
    }
    private static AcsElement compileStringBuiltin(String name, JsonArray params, boolean typeArguments, LogicEnvironment environment) {
        if (typeArguments) throw new IllegalArgumentException(name + " does not take type arguments");
        BuiltinSpec spec = STRING_BUILTINS.get(name);
        arity(name,params,name.equals("str_index_of") ? 2 : spec.paramTypes().size(),spec.paramTypes().size());
        JsonArray normalized = new JsonArray(); params.forEach(normalized::add);
        if (name.equals("str_index_of") && normalized.size() == 2) normalized.add(0);
        AcsArray arguments = new AcsArray();
        for (int i = 0; i < normalized.size(); i++) {
            JsonElement argument = normalized.get(i); String label = name + " argument " + (i + 1);
            rejectVoidValue(label,argument,environment);
            int actual = expressionType(argument,environment);
            if (actual != AsTypes.ANY_VALUE) requireKnownExact(label,spec.paramTypes().get(i),actual);
            arguments.acsa.add(withDepth(1,()->compileNode(argument,environment)));
        }
        // Constant IDs keep an enclosing class's same-named method from capturing
        // a primitive string operation through implicit-this name resolution.
        AcsObject result = new AcsObject(); result.put("t",1); result.put("id",spec.id()); result.put("param",arguments);
        result.put("contexts",new AcsArray()); return result;
    }
    private static AcsElement compileStringMethod(JsonObject source, LogicEnvironment environment) {
        if (source.has("type-args")) throw new IllegalArgumentException("String methods do not take type arguments");
        String method = stringMethod(source), builtin = STRING_METHODS.get(method);
        JsonArray params = parameters(source), supplied = new JsonArray();
        for (int i = 2; i < params.size(); i++) supplied.add(params.get(i));
        int count = STRING_BUILTINS.get(builtin).paramTypes().size() - 1;
        arity("String method " + method,supplied,method.equals("contains") || method.equals("index_of") ? 1 : count,method.equals("contains") ? 1 : count);
        JsonArray arguments = new JsonArray(); arguments.add(params.get(0)); supplied.forEach(arguments::add);
        if (!method.equals("contains")) return compileStringBuiltin(builtin,arguments,false,environment);
        AcsElement result = withDepth(1,()->compileStringBuiltin(builtin,arguments,false,environment));
        AcsObject found = new AcsObject(); found.put("t",0); found.put("c",ExecOpcodes.GREATER_EQUAL); found.put("v1",result);
        found.put("v2",compileNode(new JsonPrimitive(0),environment));
        return found;
    }
    private static boolean isBufferReceiver(JsonObject source,LogicEnvironment environment) {
        JsonArray params=parameters(source);
        return !params.isEmpty()&&isBuffer(expressionType(params.get(0),environment));
    }
    private static int bufferAction(JsonObject source) {
        JsonArray params=parameters(source);arity("buffer member",params,2,4);
        String name=string(params.get(1),"Buffer method");int action=BUFFER_METHODS.indexOf(name);
        if(action<0)throw new IllegalArgumentException("Unknown buffer method: "+name);
        int arguments=action<2?0:action==4?2:1;arity("buffer."+name,params,arguments+2,arguments+2);
        if(source.has("type-args"))throw new IllegalArgumentException("Buffer methods do not take type arguments");
        return action;
    }
    private static int bufferMethodType(JsonObject source,LogicEnvironment environment) {
        int action=bufferAction(source);
        return action<2?AsTypes.INT_VALUE:action==3?BUFFER_ELEMENTS.get().get(expressionType(parameters(source).get(0),environment)):AsTypes.VOID_VALUE;
    }
    private static AcsElement compileBufferCall(JsonObject source,LogicEnvironment environment) {
        int action=bufferAction(source);JsonArray params=parameters(source);
        int element=BUFFER_ELEMENTS.get().get(expressionType(params.get(0),environment));
        if(action!=0&&action!=1&&action!=3)requireWritableReceiver(params.get(0),environment);
        if(action==2||action==3||action==4||action==6)
            requireKnownExact("Buffer index or capacity",AsTypes.INT_VALUE,expressionType(params.get(2),environment));
        if(action==4||action==5)requireKnownExact("Buffer element",element,expressionType(params.get(action==4?3:2),environment));
        AcsObject result=new AcsObject();result.put("t",0);result.put("c",ExecOpcodes.BUFFER_OP);result.put("op",action);
        result.put("v",compileNode(params.get(0),environment));AcsArray args=new AcsArray();
        for(int i=2;i<params.size();i++){JsonElement arg=params.get(i);args.acsa.add(withDepth(1,()->compileNode(arg,environment)));}
        result.put("args",args);if(action==3)environment.tables.results.put(result,isOwnedValue(element)||isTypeParameter(element)||element==AsTypes.ANY_VALUE);return result;
    }
    private static AcsElement compileCompare(JsonObject source,LogicEnvironment environment) {
        if(!source.has("type-args")||source.getAsJsonArray("type-args").size()!=1)
            throw new IllegalArgumentException("value_compare requires one explicit type argument");
        int type=valueType(string(source.getAsJsonArray("type-args").get(0),"Comparison type"),true);
        if(isOwnedValue(type)||isFlexibleClass(type))throw new IllegalArgumentException("value_compare does not support class values or buffers; use a custom comparator");
        JsonArray params=parameters(source);arity("value_compare",params,2,2);
        for(JsonElement value:params)requireKnownExact("Comparison operand",type,expressionType(value,environment));
        AcsObject result=new AcsObject();result.put("t",0);result.put("c",ExecOpcodes.VALUE_COMPARE);result.put("context",context(type,environment,0));
        result.put("v1",compileNode(params.get(0),environment));result.put("v2",compileNode(params.get(1),environment));return result;
    }
    private static void infer(int formal,int actual,Set<Integer> parameters,Map<Integer,Integer> known) {
        if(parameters.contains(formal)) {
            if(actual==NULL_TYPE||actual==AsTypes.ANY_VALUE)return;
            Integer previous=known.putIfAbsent(formal,actual);
            if(previous!=null&&previous!=actual)throw new IllegalArgumentException("Conflicting inferred types for "+typeName(formal)+": "+typeName(previous)+" and "+typeName(actual));
            return;
        }
        if(isBuffer(formal)&&isBuffer(actual)) {
            infer(BUFFER_ELEMENTS.get().get(formal),BUFFER_ELEMENTS.get().get(actual),parameters,known);return;
        }
        if(isClass(formal)&&isClass(typeBound(actual))) {
            ClassInfo expected=CLASS_LAYOUTS.get().get(formal),value=CLASS_LAYOUTS.get().get(typeBound(actual));
            if(isPointerClass(formal)||isFlexibleClass(formal)) {
                for(ClassInfo cursor=value;cursor!=null;cursor=cursor.base) {
                    ensureLayout(cursor);if(cursor.declaration==expected.declaration){value=cursor;break;}
                }
            }
            if(expected.declaration==value.declaration)
                for(int i=0;i<expected.arguments.size();i++)infer(expected.arguments.get(i),value.arguments.get(i),parameters,known);
        }
    }
    private static CallBinding bindCall(JsonObject source,LogicEnvironment environment) {
        String original=string(source.get("call"),"Function name"),name=resolveCall(original,environment);
        JsonArray args=parameters(source);
        if(!original.equals(name)) {
            JsonArray receiver=new JsonArray();receiver.add(variable("this"));args.forEach(receiver::add);args=receiver;
            ClassInfo implicit=receiverClass(receiver.get(0),environment);
            markVirtualCall(source,implicit,original,expressionType(receiver.get(0),environment),environment);
        }
        int id=environment.getFunctionId(name);Signature signature=environment.root().namedSignatures.get(name);
        if(signature==null)signature=environment.root().builtinSignatures.get(id);
        if(signature==null)throw new IllegalArgumentException("Unknown function signature: "+name);
        Map<Integer,Integer> known=new HashMap<>();ClassInfo owner=environment.root().callOwners.get(source);
        if(signature.classParameters()>0) {
            ClassInfo declaration=environment.root().classes.get(name.substring(0,name.indexOf("::")));
            if(owner==null) {
                if(isFactory(name))owner=classInfo(declaration.name,environment);
                else if(!args.isEmpty())owner=receiverClass(args.get(0),environment);
            }
            if(owner==null)throw new IllegalArgumentException("Missing class type arguments for "+name);
            owner=ancestor(owner,declaration);
            for(int i=0;i<signature.classParameters();i++)known.put(signature.typeParameters().get(i),owner.arguments.get(i));
        }
        List<Integer> own=signature.typeParameters().subList(signature.classParameters(),signature.typeParameters().size());
        if(source.has("type-args")) {
            JsonArray explicit=source.getAsJsonArray("type-args");
            if(explicit.size()!=own.size())throw new IllegalArgumentException(name+" expects "+own.size()+" function type arguments");
            for(int i=0;i<own.size();i++)known.put(own.get(i),resolveType(string(explicit.get(i),"Type argument"),environment.typeScope));
        } else {
            Set<Integer> variables=new HashSet<>(own);
            for(int i=0;i<Math.min(args.size(),signature.paramTypes().size());i++)infer(signature.paramTypes().get(i),expressionType(args.get(i),environment),variables,known);
        }
        List<Integer> types=new ArrayList<>();
        for(int parameter:signature.typeParameters()) {
            Integer actual=known.get(parameter);
            if(actual==null)throw new IllegalArgumentException("Cannot infer "+typeName(parameter)+" for "+name+"; provide explicit type arguments");
            types.add(actual);
        }
        checkBounds(signature.typeParameters(),types);
        Signature concrete=new Signature(signature.id(),substitute(signature.returnType(),known),signature.paramTypes().stream().map(t->substitute(t,known)).toList(),signature.requireKnown(),signature.typeParameters(),signature.classParameters(),signature.internal());
        return new CallBinding(name,concrete,args,types,isTypeParameter(signature.returnType()));
    }
    private static int reflectionType(JsonObject source,LogicEnvironment environment) {
        if(!source.has("type-args")||source.getAsJsonArray("type-args").size()!=1)throw new IllegalArgumentException("reflect_invoke_function requires one return type argument");
        int type=resolveType(string(source.getAsJsonArray("type-args").get(0),"Reflection return type"),environment.typeScope);
        if(type==AsTypes.ANY_VALUE||isFlexibleClass(type))throw new IllegalArgumentException("Invalid reflection return type");return type;
    }
    private static AcsElement compileReflection(JsonObject source,LogicEnvironment environment) {
        int returns=reflectionType(source,environment);
        if (!isClass(returns) && !isBuffer(returns) && !isTypeParameter(returns)) return compileReflectionCall(source,returns,environment);
        AcsObject checked=new AcsObject();checked.put("t",0);checked.put("c",ExecOpcodes.CHECK_OBJECT);
        checked.put("v",withDepth(1,()->compileReflectionCall(source,returns,environment)));
        checked.put("context",context(returns,environment,0));
        AcsArray slots=new AcsArray();
        if(isClass(returns)) {
            ClassInfo info=CLASS_LAYOUTS.get().get(returns);ensureLayout(info);
            for(Field field:info.fields)slots.acsa.add(new AcsIntegerElement(isTypeParameter(field.type())?AsTypes.ANY_VALUE:abiType(field.type())));
        }
        checked.put("slot-types",slots);return checked;
    }
    private static AcsElement compileReflectionCall(JsonObject source,int returns,LogicEnvironment environment) {
        JsonArray params=parameters(source);arity("reflect_invoke_function",params,1,Integer.MAX_VALUE);
        requireKnownExact("Reflection function ID",AsTypes.INT_VALUE,expressionType(params.get(0),environment));
        AcsObject result=new AcsObject();result.put("t",1);result.put("id",0x0abd0008);
        AcsArray args=new AcsArray();
        if(isTypeParameter(returns)){AcsObject abi=new AcsObject();abi.put("t",0);abi.put("c",36);abi.put("context",context(returns,environment,0));args.acsa.add(abi);}
        else args.acsa.add(new AcsIntegerElement(abiType(returns)));
        for(JsonElement arg:params){rejectVoidValue("Reflection argument",arg,environment);args.acsa.add(withDepth(1,()->compileNode(arg,environment)));}
        result.put("param",args);return result;
    }
    private static AcsArray contexts(List<Integer> types,LogicEnvironment environment) {
        AcsArray result=new AcsArray();for(int type:types)result.acsa.add(context(type,environment,0));return result;
    }
    /** Additional compact-ABD levels below a context record, including its empty lists. */
    private static int contextHeight(AcsObject context) {
        if(context.mmp.containsKey("ref"))return 0;
        int height=1;
        for(String key:List.of("contexts","placement-contexts")) {
            AcsElement children=context.mmp.get(key);
            if(children instanceof AcsArray values)for(AcsElement child:values.acsa)height=Math.max(height,2+contextHeight((AcsObject)child));
        }
        AcsElement element=context.mmp.get("element");
        return element instanceof AcsObject child?Math.max(height,1+contextHeight(child)):height;
    }
    private static AcsObject context(int type,LogicEnvironment environment,int depth) {
        if(depth>MAX_VALUE_NESTING)throw new IllegalArgumentException("Generic operation context nesting exceeds "+MAX_VALUE_NESTING);
        AcsObject result=new AcsObject();
        if(isTypeParameter(type)) {
            int position=environment.typeParameters.indexOf(type);
            if(position<0)throw new IllegalArgumentException("Unbound type parameter: "+typeName(type));
            result.put("ref",position);return result;
        }
        int abi=abiType(type);
        if(abi==AsTypes.ANY_VALUE||abi==NULL_TYPE||isFlexibleClass(type))throw new IllegalArgumentException("Invalid type operation context: "+typeName(type));
        result.put("abi",abi);result.put("kind",isBuffer(type)?4:isValueClass(type)?3:isPointerClass(type)?2:type==AsTypes.ADDRESS_VALUE?1:0);
        result.put("width",1);AcsArray children=new AcsArray(),placementChildren=new AcsArray();
        if(isBuffer(type))result.put("element",context(BUFFER_ELEMENTS.get().get(type),environment,depth+1));
        if(isValueClass(type)) {
            ClassInfo info=CLASS_LAYOUTS.get().get(type);ensureLayout(info);
            result.put("width",info.fields.size());
            Signature constructor=environment.root().namedSignatures.get(info.constructor);
            if(constructor!=null&&constructor.paramTypes().size()==1) {
                result.put("factory",environment.getFunctionId(info.declaration.name+VALUE_FACTORY));
                result.put("placement",environment.getFunctionId(info.declaration.name+PLACEMENT_FACTORY));
                for(int argument:info.arguments) {
                    children.acsa.add(context(argument,environment,depth+1));placementChildren.acsa.add(context(argument,environment,depth+1));
                }
            }
        }
        result.put("contexts",children);result.put("placement-contexts",placementChildren);return result;
    }
    private static AcsObject typeCheck(AcsElement value,int expected,LogicEnvironment environment) {
        AcsObject result=new AcsObject();result.put("t",0);result.put("c",39);result.put("v",value);result.put("context",context(expected,environment,0));return result;
    }
    private static void validateConstruction(ClassInfo info,LogicEnvironment environment,Set<ClassInfo> visiting) {
        ensureLayout(info);
        if(!visiting.add(info))throw new IllegalArgumentException("Class "+info.name+" contains itself by value; use a pointer field");
        if(visiting.size()>MAX_VALUE_NESTING)throw new IllegalArgumentException("Literal object nesting exceeds "+MAX_VALUE_NESTING+" levels");
        for(Field field:info.fields)if(isValueClass(field.type())) {
            ClassInfo nested=CLASS_LAYOUTS.get().get(field.type());ensureLayout(nested);
            Signature constructor=environment.root().namedSignatures.get(nested.constructor);
            if(field.initializer()==null&&constructor!=null&&constructor.paramTypes().size()!=1)throw new IllegalArgumentException("Field "+info.name+"."+field.name()+" requires a default constructor for "+nested.name);
            validateConstruction(nested,environment,visiting);
        }
        visiting.remove(info);
    }
    private static void normalizeContexts(AcsElement value) {
        if(value instanceof AcsArray array){for(AcsElement item:array.acsa)normalizeContexts(item);}
        else if(value instanceof AcsObject object) {
            if(object.mmp.containsKey("t")&&object.mmp.get("t") instanceof AcsIntegerElement kind
                    &&(kind.s==1||kind.s==0&&object.mmp.get("c") instanceof AcsIntegerElement code&&code.s==10))
                object.mmp.putIfAbsent("contexts",new AcsArray());
            for(AcsElement item:object.mmp.values())normalizeContexts(item);
        }
    }
    private static boolean isTypeParameter(int type) { return type <= -100; }
    private static int typeBound(int type) {return isTypeParameter(type)?TYPE_PARAMETERS.get().get(type).bound():type;}
    private static boolean isClass(int type) { return CLASS_LAYOUTS.get().containsKey(type); }
    private static boolean isBuffer(int type) { return BUFFER_ELEMENTS.get().containsKey(type); }
    private static boolean isOwnedValue(int type) { return isValueClass(type)||isBuffer(type); }
    private static int bufferType(int element) {
        if(element==AsTypes.VOID_VALUE||element==AsTypes.ANY_VALUE||element==NULL_TYPE||isFlexibleClass(element))
            throw new IllegalArgumentException("Invalid buffer element type: "+typeName(element));
        Integer cached=BUFFER_TYPES.get().get(element);if(cached!=null)return cached;
        int code=NEXT_CLASS.get();NEXT_CLASS.set(code+3);
        if(code>MAX_VARIABLE_SLOTS*3)throw new IllegalArgumentException("Too many parameterized types");
        BUFFER_ELEMENTS.get().put(code,element);BUFFER_TYPES.get().put(element,code);return code;
    }
    // Each class owns three consecutive codes: literal object, pointer, flexible "(*)" parameter.
    private static int classKind(int type) { return (type - FIRST_CLASS_TYPE) % 3; }
    private static boolean isValueClass(int type) { return isClass(type) && classKind(type) == 0; }
    private static boolean isPointerClass(int type) { return isClass(type) && classKind(type) == 1; }
    private static boolean isFlexibleClass(int type) { return isClass(type) && classKind(type) == 2; }
    private static boolean isClassPointer(int type) { return isPointerClass(typeBound(type)) || isFlexibleClass(typeBound(type)); }
    private static int pointerTypeOf(int valueType) { return valueType + 1; }
    /** The pointer type of the same class for any class type code. */
    private static int pointerOf(int classType) { return classType - classKind(classType) + 1; }
    /** Pointers erase to address; literal objects cross calls as runtime object values. */
    private static int abiType(int type) {
        return isTypeParameter(type) ? abiType(typeBound(type)) : isOwnedValue(type) ? AsTypes.OBJECT_VALUE : isClass(type) ? AsTypes.ADDRESS_VALUE : type;
    }
    private static void rejectUntypedObject(String context, JsonElement value, LogicEnvironment environment) {
        int type = expressionType(value, environment);
        rejectUntypedPointer(context,type);
        if (isOwnedValue(type) || isTypeParameter(type))
            throw new IllegalArgumentException(context + " cannot hold an object value; declare it with the class type or use a pointer");
    }
    private static void rejectUntypedPointer(String context, int type) {
        if (isClassPointer(type))
            throw new IllegalArgumentException(context + " cannot hold a class pointer (" + typeName(type)
                    + "); declare it as " + typeName(type) + " or convert explicitly with address(p)");
    }
    /** Member access through a literal object uses the address of its storage. */
    private static JsonElement receiverAddress(JsonElement receiver, LogicEnvironment environment) {
        return isValueClass(typeBound(expressionType(receiver, environment))) ? internal(environment, "#address-of", receiver) : receiver;
    }
    private static JsonArray array(JsonElement... values) {
        JsonArray result = new JsonArray(); for (JsonElement value : values) result.add(value); return result;
    }
    private static JsonObject node(String operation, JsonElement... values) {
        JsonObject result = new JsonObject(); result.addProperty("t", "ctrl"); result.addProperty("call", operation);
        result.add("param", array(values)); return result;
    }
    private static JsonObject variable(String name) { return node("var", new JsonPrimitive(name)); }
    private static JsonObject callNode(String name, JsonArray args) {
        JsonObject result = new JsonObject(); result.addProperty("t", "call"); result.addProperty("call", name); result.add("param", args); return result;
    }
    private static JsonObject internal(LogicEnvironment environment, String operation, JsonElement... values) {
        JsonObject result = node(operation, values); environment.root().trustedNodes.add(result); return result;
    }
    private static void requireTrusted(JsonObject node, LogicEnvironment environment) {
        if (!environment.root().trustedNodes.contains(node)) throw new IllegalArgumentException("Internal compiler operation is not allowed in source AST");
    }
    private static ClassInfo classInfo(String name, LogicEnvironment environment) {
        int type=resolveType(name,environment.typeScope);
        if(!isValueClass(type))throw new IllegalArgumentException("Unknown class: "+name);
        ClassInfo result=CLASS_LAYOUTS.get().get(type);ensureLayout(result);return result;
    }
    private static ClassInfo receiverClass(JsonElement receiver, LogicEnvironment environment) {
        int type = typeBound(expressionType(receiver, environment));
        if (type == AsTypes.STRING_VALUE) throw new IllegalArgumentException("String member access requires a supported string method, such as length()");
        ClassInfo info = isClass(type) ? CLASS_LAYOUTS.get().get(type) : null;
        if (info == null) throw new IllegalArgumentException("Member access requires a known class type, got " + typeName(type));
        ensureLayout(info);return info;
    }
    private static Field memberField(JsonArray params, LogicEnvironment environment) {
        arity("member", params, 2, 2);
        return receiverClass(params.get(0), environment).field(string(params.get(1), "Member name"));
    }
    private static Field implicitField(String name, LogicEnvironment environment) {
        ClassInfo info = environment.ownerClass == null ? null : environment.root().classes.get(environment.ownerClass);
        return info == null ? null : info.visibleFields.get(name);
    }
    private static int variableType(String name, LogicEnvironment environment) {
        if (name.equals("this") && environment.ownerClass == null) throw new IllegalArgumentException("this is only available inside a class");
        LogicVariable local = environment.findLexicalVariable(name);
        if (local != null) return local.type;
        Field field = implicitField(name, environment);
        if (field != null) return field.type();
        return environment.getVariable(name).type;
    }
    private static boolean isThisVariable(JsonElement value) {
        if (!value.isJsonObject()) return false;
        JsonObject object = value.getAsJsonObject();
        return isControl(object) && object.get("call").getAsString().equals("var") && parameters(object).size() == 1
                && parameters(object).get(0).isJsonPrimitive() && parameters(object).get(0).getAsString().equals("this");
    }
    private static String resolveCall(String name, LogicEnvironment environment) {
        if (environment.ownerClass != null) {
            ClassInfo info = environment.root().classes.get(environment.ownerClass);
            if (info != null && info.methods.containsKey(name)) return info.methods.get(name);
        }
        return name;
    }
    private static final String VALUE_FACTORY = "::<value>";
    private static final String PLACEMENT_FACTORY = "::<placement>";
    private static String factoryName(ClassInfo info, boolean manual) { return info.declaration.name + (manual ? "::<new>" : "::<scoped>"); }
    private static boolean isFactory(String function) {
        return function.endsWith("::<new>") || function.endsWith("::<scoped>") || function.endsWith(VALUE_FACTORY)||function.endsWith(PLACEMENT_FACTORY);
    }
    private static String displayName(String function) {
        return isFactory(function) ? function.substring(0, function.lastIndexOf("::")) + " constructor" : function;
    }
    /** A name the parser could have read from source; the rules are shared through {@link SourceRules}. */
    private static void validateSourceName(String name, String description) { SourceRules.requireName(name, description); }
    /**
     * Function, extern and abstract names: a qualified class member such as {@code P::get}
     * or {@code P::<ctor>} is checked with its class instead.
     */
    private static void validateFunctionName(String name, String description) {
        if (!name.contains("::")) validateSourceName(name, description);
    }
    private static int allocateClass(ClassInfo info) {
        int code = NEXT_CLASS.get(); NEXT_CLASS.set(code + 3);
        if (code > MAX_VARIABLE_SLOTS * 3) throw new IllegalArgumentException("Too many parameterized class types");
        info.valueType=code; info.pointerType=code+1; info.flexibleType=code+2;
        for(int i=0;i<3;i++) CLASS_LAYOUTS.get().put(code+i,info);
        return code;
    }
    private static List<Integer> declareTypeParameters(JsonObject declaration, Map<String,Integer> scope) {
        if (!declaration.has("type-parameters")) return List.of();
        List<Integer> result=new ArrayList<>();
        for(JsonElement element:declaration.getAsJsonArray("type-parameters")) {
            JsonObject parameter=element.getAsJsonObject(); String name=string(parameter.get("name"),"Type parameter");
            validateSourceName(name,"type parameter");
            if(scope.containsKey(name)||CLASS_DECLARATIONS.get().containsKey(name)||isBuiltinName(name))
                throw new IllegalArgumentException("Duplicate or shadowed type parameter: "+name);
            int bound=parameter.has("bound")?resolveType(string(parameter.get("bound"),"Type parameter bound"),scope):AsTypes.ANY_VALUE;
            if(parameter.has("bound")&&!isClass(bound)) throw new IllegalArgumentException("A type parameter bound must be a class value or pointer");
            int id=-100-TYPE_PARAMETERS.get().size(); TYPE_PARAMETERS.get().put(id,new TypeParameter(name,bound));
            scope.put(name,id);result.add(id);
        }
        return List.copyOf(result);
    }
    private static int resolveType(String name, Map<String,Integer> scope) {
        Map<String,Integer> previous=TYPE_SCOPE.get();
        if(previous==scope)return valueType(name,false);
        TYPE_SCOPE.set(scope);
        try{return valueType(name,false);}finally{TYPE_SCOPE.set(previous);}
    }
    private static List<String> typeArguments(String source) {
        List<String> result=new ArrayList<>();int depth=0,start=0;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);if(c=='<')depth++;else if(c=='>')depth--;
            if(depth<0)throw new IllegalArgumentException("Invalid type arguments: "+source);
            if(c==','&&depth==0){result.add(source.substring(start,i));start=i+1;}
        }
        if(depth!=0)throw new IllegalArgumentException("Invalid type arguments: "+source);
        result.add(source.substring(start));return result;
    }
    private static ClassInfo applyClass(ClassInfo declaration,List<Integer> arguments) {
        if(arguments.size()!=declaration.arguments.size())throw new IllegalArgumentException("Class "+declaration.name+" expects "+declaration.arguments.size()+" type arguments");
        if(arguments.equals(declaration.arguments))return declaration;
        String key=declaration.name+arguments;
        ClassInfo cached=CLASS_APPLICATIONS.get().get(key);if(cached!=null)return cached;
        ClassInfo view=new ClassInfo();view.declaration=declaration;view.arguments=List.copyOf(arguments);
        view.name=declaration.name+"<"+String.join(",",arguments.stream().map(Compiler::typeName).toList())+">";
        allocateClass(view);CLASS_APPLICATIONS.get().put(key,view);
        // Cached first, so a recursive bound can name this application; a failed
        // check uncaches it, so a later function reports the same error again.
        try { checkBounds(declaration.arguments,arguments); }
        catch (RuntimeException error) { CLASS_APPLICATIONS.get().remove(key); throw error; }
        return view;
    }
    private static Map<Integer,Integer> substitutions(ClassInfo info) {
        Map<Integer,Integer> result=new HashMap<>();
        for(int i=0;i<info.arguments.size();i++) result.put(info.declaration.arguments.get(i),info.arguments.get(i));
        return result;
    }
    private static int substitute(int type,Map<Integer,Integer> substitutions) {
        Integer mapped=substitutions.get(type);if(mapped!=null)return mapped;
        if(isBuffer(type))return bufferType(substitute(BUFFER_ELEMENTS.get().get(type),substitutions));
        if(!isClass(type))return type;
        ClassInfo info=CLASS_LAYOUTS.get().get(type);
        if(info.arguments.isEmpty())return type;
        List<Integer> arguments=info.arguments.stream().map(t->substitute(t,substitutions)).toList();
        return applyClass(info.declaration,arguments).valueType+classKind(type);
    }
    private static void checkBounds(List<Integer> parameters,List<Integer> arguments) {
        Map<Integer,Integer> substitution=new HashMap<>();
        for(int i=0;i<parameters.size();i++) {
            int actual=arguments.get(i);
            if(actual==AsTypes.VOID_VALUE||actual==AsTypes.ANY_VALUE||actual==NULL_TYPE||isFlexibleClass(actual))
                throw new IllegalArgumentException("Invalid generic argument: "+typeName(actual));
            int bound=substitute(TYPE_PARAMETERS.get().get(parameters.get(i)).bound(),substitution);
            if(bound!=AsTypes.ANY_VALUE) {
                int candidate=typeBound(actual);
                if(!isClass(candidate)||classKind(candidate)!=classKind(bound))throw new IllegalArgumentException("Type "+typeName(actual)+" does not extend "+typeName(bound));
                boolean matches=false;
                for(ClassInfo cursor=CLASS_LAYOUTS.get().get(candidate);cursor!=null;) {
                    if(sameApplication(cursor,CLASS_LAYOUTS.get().get(bound))){matches=true;break;}
                    ensureLayout(cursor);cursor=cursor.base;
                }
                if(!matches)throw new IllegalArgumentException("Type "+typeName(actual)+" does not extend "+typeName(bound));
            }
            substitution.put(parameters.get(i),actual);
        }
    }
    private static boolean sameApplication(ClassInfo first,ClassInfo second) {
        return first.declaration==second.declaration&&first.arguments.equals(second.arguments);
    }
    private static void queueLayout(ClassInfo info,ArrayDeque<ClassInfo> pending) {
        if(info.building)throw new IllegalArgumentException("Inheritance cycle involving "+info.name);
        info.building=true;pending.push(info);
    }
    private static void ensureLayout(ClassInfo requested) {
        if(requested.ready)return;
        ArrayDeque<ClassInfo> pending=new ArrayDeque<>();queueLayout(requested,pending);
        try {
            while(!pending.isEmpty()) {
                ClassInfo info=pending.peek();
                if(info!=info.declaration&&!info.declaration.ready){queueLayout(info.declaration,pending);continue;}
                if(!info.prepared){layoutStep(info,true);info.prepared=true;}
                if(info.base!=null&&!info.base.ready){queueLayout(info.base,pending);continue;}
                layoutStep(info,false);info.building=false;pending.pop();
            }
        } finally {for(ClassInfo info:pending)info.building=false;}
    }
    /**
     * A declared class's layout errors are located at its declaration. Errors of a
     * generic application stay unlocated, so the use that instantiated it is named.
     */
    private static void layoutStep(ClassInfo info, boolean prepare) {
        try { if (prepare) prepareLayout(info); else finishLayout(info); }
        catch (RuntimeException error) { throw info == info.declaration ? classError(info, info.source, error) : error; }
    }
    private static void prepareLayout(ClassInfo info) {
            // A failed layout may be retried by another class or function; start over each time.
            info.ownFields.clear();info.ownMethods.clear();info.ownOperators.clear();
            info.virtualMethods.clear();info.overrideMethods.clear();info.finalMethods.clear();
            if(info!=info.declaration) {
                ClassInfo original=info.declaration;Map<Integer,Integer> substitutions=substitutions(info);
                info.constructor=original.constructor;info.destructor=original.destructor;info.cleanupDestructor=original.cleanupDestructor;
                info.synthesizedDestructor=original.synthesizedDestructor;info.ownMethods.putAll(original.ownMethods);info.ownOperators.putAll(original.ownOperators);
                info.virtualMethods.addAll(original.virtualMethods);info.overrideMethods.addAll(original.overrideMethods);info.finalMethods.addAll(original.finalMethods);
                if(original.base!=null)info.base=CLASS_LAYOUTS.get().get(substitute(original.base.valueType,substitutions));
                for(Field field:original.ownFields) info.ownFields.add(new Field(field.owner(),field.name(),substitute(field.type(),substitutions),field.initializer(),field.file(),field.line(),field.column()));
            } else {
                JsonObject definition=info.source;
                if(definition.has("base")) {
                    int base=resolveType(string(definition.get("base"),"Base class"),info.typeScope);
                    if(!isValueClass(base))throw new IllegalArgumentException("Base must name a class: "+info.name);
                    info.base=CLASS_LAYOUTS.get().get(base);
                }
                Set<String> names=new HashSet<>();
                for(JsonElement element:definition.getAsJsonArray("fields")) {
                    JsonObject field=element.getAsJsonObject();
                    try {
                        String name=string(field.get("name"),"Field name");validateSourceName(name,"field name");
                        if(!names.add(name))throw new IllegalArgumentException("Duplicate member: "+info.name+"."+name);
                        int type=resolveType(string(field.get("type"),"Field type"),info.typeScope);
                        if(type==AsTypes.VOID_VALUE||type==AsTypes.ANY_VALUE||isFlexibleClass(type))throw new IllegalArgumentException("Field requires a concrete type");
                        int line=Math.max(0,SourceMap.integer(field.get("_line")));
                        info.ownFields.add(new Field(info.name,name,type,field.get("initializer"),line>0&&info.sources!=null?info.sources.unitOf(field):0,line,line>0?Math.max(0,SourceMap.integer(field.get("_column"))):0));
                    } catch (RuntimeException error) { throw classError(info, field, error); }
                }
                info.constructor=string(definition.get("constructor"),"Constructor function");
                if(!info.constructor.equals(info.name+"::<ctor>"))throw new IllegalArgumentException("Invalid constructor mapping for "+info.name);
                if(definition.has("destructor")) {
                    info.destructor=string(definition.get("destructor"),"Destructor function");
                    if(!info.destructor.equals(info.name+"::<dtor>"))throw new IllegalArgumentException("Invalid destructor mapping for "+info.name);
                }
                for(var method:definition.getAsJsonObject("methods").entrySet()) {
                    validateSourceName(method.getKey(),"method name");
                    if(!names.add(method.getKey()))throw new IllegalArgumentException("Duplicate member: "+info.name+"."+method.getKey());
                    String qualified=string(method.getValue(),"Method function");
                    if(!qualified.equals(info.name+"::"+method.getKey()))throw new IllegalArgumentException("Invalid method mapping for "+info.name);
                    info.ownMethods.put(method.getKey(),qualified);
                }
                for(String modifier:List.of("virtual","override","final")) {
                    Set<String> targets=modifier.equals("virtual")?info.virtualMethods:modifier.equals("override")?info.overrideMethods:info.finalMethods;
                    if(definition.has(modifier+"-methods"))for(JsonElement method:definition.getAsJsonArray(modifier+"-methods")) {
                        String name=string(method,modifier+" method name");
                        if(!info.ownMethods.containsKey(name))throw new IllegalArgumentException(modifier+" modifier requires an ordinary method declared in "+info.name);
                        if(!targets.add(name))throw new IllegalArgumentException("Duplicate "+modifier+" method: "+info.name+"."+name);
                    }
                }
                if(definition.has("operators"))for(var operation:definition.getAsJsonObject("operators").entrySet()) {
                    String name=operation.getKey(),qualified=string(operation.getValue(),"Operator function");
                    if(!OPERATOR_KINDS.contains(name)||!qualified.equals(info.name+"::<op:"+name+">"))
                        throw new IllegalArgumentException("Invalid operator mapping for "+info.name);
                    info.ownOperators.put(name,qualified);
                }
                if(info.destructor==null&&(!info.arguments.isEmpty()||info.ownFields.stream().anyMatch(f->isOwnedValue(f.type())||isTypeParameter(f.type())))) {
                    info.destructor=info.name+"::<dtor>";info.synthesizedDestructor=true;
                }
            }
    }
    private static void finishLayout(ClassInfo info) {
            info.fields.clear();info.visibleFields.clear();info.fieldOffsets.clear();info.methods.clear();info.operators.clear();
            info.vtable.clear();info.virtualSlots.clear();
            if(info.base!=null){info.fields.addAll(info.base.fields);info.visibleFields.putAll(info.base.visibleFields);info.fieldOffsets.putAll(info.base.fieldOffsets);info.methods.putAll(info.base.methods);info.operators.putAll(info.base.operators);info.vtable.addAll(info.base.vtable);info.virtualSlots.putAll(info.base.virtualSlots);}
            for(Field field:info.ownFields){info.fieldOffsets.put(field.name(),info.fields.size());info.fields.add(field);info.visibleFields.put(field.name(),field);info.methods.remove(field.name());}
            info.ownMethods.forEach((name,method)->{info.methods.put(name,method);info.visibleFields.remove(name);info.fieldOffsets.remove(name);});
            for(var method:info.ownMethods.entrySet()) {
                String name=method.getKey();Integer slot=info.virtualSlots.get(name);
                if(slot!=null)info.vtable.set(slot,new VirtualMethod(name,method.getValue(),info,info.finalMethods.contains(name)));
                else if(info.virtualMethods.contains(name)||info.overrideMethods.contains(name)) {
                    info.virtualSlots.put(name,info.vtable.size());info.vtable.add(new VirtualMethod(name,method.getValue(),info,info.finalMethods.contains(name)));
                }
            }
            info.operators.putAll(info.ownOperators);
            if(info.fields.isEmpty())throw new IllegalArgumentException("Class "+info.name+" must declare at least one field");
            if(info.fields.size()>MAX_VARIABLE_SLOTS)throw new IllegalArgumentException("Class exceeds object slot limit");
            info.cleanupDestructor=info.destructor!=null?info.destructor:info.base==null?null:info.base.cleanupDestructor;
            info.ready=true;
    }
    /**
     * Declares, parameterizes and lays out the classes. Each step reports the errors
     * of every class and stops before the next step when any class failed, since
     * later steps resolve the types the earlier ones declare.
     */
    private static void readClasses(JsonObject source,LogicEnvironment environment) {
        if(!source.has("classes"))return;
        Diagnostics diagnostics=environment.state().diagnostics;
        for(JsonElement element:source.getAsJsonArray("classes")) {
            JsonObject definition=element.getAsJsonObject();ClassInfo info=new ClassInfo();
            try {
                info.name=string(definition.get("name"),"Class name");validateSourceName(info.name,"class name");
                if(isBuiltinName(info.name)||environment.classes.containsKey(info.name))throw new IllegalArgumentException("Duplicate or reserved class: "+info.name);
            } catch (RuntimeException error) { recover(error, definition, info.name == null ? null : "class " + info.name, environment); continue; }
            info.declaration=info;info.source=definition;info.sources=environment.state().sources;allocateClass(info);
            environment.classes.put(info.name,info);CLASS_DECLARATIONS.get().put(info.name,info);
        }
        diagnostics.checkpoint();
        // Collect every class's parameter names before resolving bounds, so a bound
        // can name a later generic class just like any other forward class type.
        for(ClassInfo info:environment.classes.values()) try {
            List<Integer> parameters=new ArrayList<>();
            if(info.source.has("type-parameters"))for(JsonElement element:info.source.getAsJsonArray("type-parameters")) {
                String name=string(element.getAsJsonObject().get("name"),"Type parameter");validateSourceName(name,"type parameter");
                if(info.typeScope.containsKey(name)||environment.classes.containsKey(name)||isBuiltinName(name))throw new IllegalArgumentException("Duplicate or shadowed type parameter: "+name);
                int id=-100-TYPE_PARAMETERS.get().size();TYPE_PARAMETERS.get().put(id,new TypeParameter(name,AsTypes.ANY_VALUE));info.typeScope.put(name,id);parameters.add(id);
            }
            info.arguments=List.copyOf(parameters);
        } catch (RuntimeException error) { recover(error, info.source, "class " + info.name, environment); }
        diagnostics.checkpoint();
        for(ClassInfo info:environment.classes.values()) try {
            Map<String,Integer> earlier=new LinkedHashMap<>();int index=0;
            if(info.source.has("type-parameters"))for(JsonElement element:info.source.getAsJsonArray("type-parameters")) {
                JsonObject parameter=element.getAsJsonObject();int id=info.arguments.get(index++);String name=TYPE_PARAMETERS.get().get(id).name();
                int bound=parameter.has("bound")?resolveType(string(parameter.get("bound"),"Type parameter bound"),earlier):AsTypes.ANY_VALUE;
                if(parameter.has("bound")&&!isClass(bound))throw new IllegalArgumentException("A type parameter bound must be a class value or pointer");
                TYPE_PARAMETERS.get().put(id,new TypeParameter(name,bound));earlier.put(name,id);
            }
        } catch (RuntimeException error) { recover(error, info.source, "class " + info.name, environment); }
        diagnostics.checkpoint();
        // Applications created while collecting forward bounds saw provisional
        // bounds. Validate them once every declaration has its final contract.
        for(ClassInfo application:new ArrayList<>(CLASS_APPLICATIONS.get().values())) {
            try { checkBounds(application.declaration.arguments,application.arguments); }
            catch (RuntimeException error) { recover(error, application.declaration.source, "class " + application.name, environment); }
        }
        diagnostics.checkpoint();
        for(ClassInfo info:environment.classes.values()) {
            try { ensureLayout(info); }
            catch (RuntimeException error) { recover(error, info.source, "class " + info.name, environment); }
        }
        diagnostics.checkpoint();
        Map<ClassInfo,Integer> nesting=new HashMap<>();Set<ClassInfo> reported=new HashSet<>();
        for(ClassInfo info:environment.classes.values()) {
            if(reported.contains(info))continue;
            Set<ClassInfo> visiting=new HashSet<>();
            try { valueNesting(info,nesting,visiting); }
            catch (RuntimeException error) {
                recover(error, info.source, "class " + info.name, environment);
                // Report a containment problem once: the classes on the failing path, and the
                // generic declarations they apply, count as checked.
                for(ClassInfo path:visiting){nesting.putIfAbsent(path,1);reported.add(path.declaration);}
            }
        }
        diagnostics.checkpoint();
    }
    /** Literal-object fields are embedded storage, so a class cannot contain itself by value. */
    private static int valueNesting(ClassInfo info, Map<ClassInfo,Integer> known, Set<ClassInfo> visiting) {
        ensureLayout(info);
        Integer cached = known.get(info);
        if (cached != null) return cached;
        if (!visiting.add(info)) throw new IllegalArgumentException("Class " + info.name + " contains itself by value; use a pointer field");
        if(visiting.size()>MAX_VALUE_NESTING)throw new IllegalArgumentException("Literal object nesting exceeds "+MAX_VALUE_NESTING+" levels");
        int levels = 1;
        for (Field field : info.fields)
            if (isValueClass(field.type())) levels = Math.max(levels, 1 + valueNesting(CLASS_LAYOUTS.get().get(field.type()), known, visiting));
        if (levels > MAX_VALUE_NESTING) throw new IllegalArgumentException("Literal object nesting of " + info.name + " exceeds " + MAX_VALUE_NESTING + " levels");
        visiting.remove(info); known.put(info, levels); return levels;
    }
    /** Scalars and pointers only; literal-object fields are constructed in declaration order. */
    private static JsonElement defaultValue(int type) {
        return switch (type) {
            case AsTypes.STRING_VALUE -> new JsonPrimitive("");
            case AsTypes.FLOAT_VALUE -> node("float", new JsonPrimitive(0.0f));
            case AsTypes.DOUBLE_VALUE -> new JsonPrimitive(0.0);
            case AsTypes.BOOLEAN_VALUE -> new JsonPrimitive(false);
            default -> isClass(type) || type == AsTypes.ADDRESS_VALUE ? node("null") : new JsonPrimitive(0);
        };
    }
    private static void addClassFactories(List<Function> functions, Set<Integer> ids, LogicEnvironment global,
                                         Map<String,Function> declarations) {
        Map<String,Function> byName = new HashMap<>(); functions.forEach(function -> byName.put(function.name(), function));
        byName.putAll(declarations);
        Set<String> declaredClassFunctions = new HashSet<>();
        for (ClassInfo info : global.classes.values()) try {
            validateClassFunction(byName, info, info.constructor, "constructor"); declaredClassFunctions.add(info.constructor);
            if (info.destructor != null && !info.synthesizedDestructor) { validateClassFunction(byName, info, info.destructor, "destructor"); declaredClassFunctions.add(info.destructor); }
            for (String method : info.ownMethods.values()) { validateClassFunction(byName, info, method, "method"); declaredClassFunctions.add(method); }
            for (var operation:info.ownOperators.entrySet()) {
                String name=operation.getValue();validateClassFunction(byName,info,name,"method");declaredClassFunctions.add(name);
                Function function=byName.get(name);int count=function.paramTypes().size();String kind=operation.getKey();
                if(!function.metadata().has("operator-kind")||!function.metadata().get("operator-kind").getAsString().equals(kind))
                    throw new IllegalArgumentException("Operator metadata mismatch: "+name);
                if(function.typeParameters().size()!=function.classParameters())throw new IllegalArgumentException("Operators use class type parameters, not separate method type parameters");
                if(!kind.equals("call")&&count!=(kind.equals("index-set")?3:2))throw new IllegalArgumentException("Invalid operator parameter count: "+name);
                if(kind.equals("index-set")?function.returnType()!=AsTypes.VOID_VALUE:!kind.equals("call")&&function.returnType()==AsTypes.VOID_VALUE)
                    throw new IllegalArgumentException("Invalid operator return type: "+name);
            }
            if(info.operators.containsKey("index-set")) {
                if(!info.operators.containsKey("index-get"))throw new IllegalArgumentException("Index setter requires a getter: "+info.name);
                Signature getter=operatorSignature(info,byName.get(info.operators.get("index-get")));
                Signature setter=operatorSignature(info,byName.get(info.operators.get("index-set")));
                if(getter.paramTypes().get(1)!=setter.paramTypes().get(1)||getter.returnType()!=setter.paramTypes().get(2))
                    throw new IllegalArgumentException("Index getter and setter types must match: "+info.name);
            }
        } catch (RuntimeException error) { recover(error, info.source, "class " + info.name, global); }
        Diagnostics diagnostics = global.state().diagnostics;
        diagnostics.checkpoint();
        validateVirtualMethods(byName,global);
        diagnostics.checkpoint();
        for (Function function : functions) try {
            boolean member = function.metadata().has("owner-class") || function.metadata().has("function-kind") || function.name().contains("::");
            if (member && !declaredClassFunctions.contains(function.name())) throw new IllegalArgumentException("Unregistered class function: " + function.name());
            if (member) {
                JsonElement ownerName = function.metadata().get("owner-class");
                ClassInfo owner = ownerName == null ? null : global.classes.get(string(ownerName, "Function owner"));
                if (owner == null) throw new IllegalArgumentException("Class function has no owner class: " + function.name());
                String kind = function.name().equals(owner.constructor) ? "constructor" : function.name().equals(owner.destructor) ? "destructor" : "method";
                validateClassFunction(Map.of(function.name(), function), owner, function.name(), kind);
            }
        } catch (RuntimeException error) { recover(error, function.metadata(), contextName(function.name()), global); }
        for (Function declaration : declarations.values()) {
            boolean member = declaration.metadata().has("owner-class") || declaration.metadata().has("function-kind") || declaration.name().contains("::");
            if (member && !declaredClassFunctions.contains(declaration.name()))
                recover(new IllegalArgumentException("Unregistered class extern: " + declaration.name()), declaration.metadata(), null, global);
        }
        // Factories need every class function registered and validated.
        diagnostics.checkpoint();
        Set<Integer> occupied = new HashSet<>(ids); occupied.addAll(global.abs.values()); occupied.addAll(GeneraterJson.specialNames.values());
        int[] position = {1}; int namespace = global.moduleNamespace << 16;
        java.util.function.Supplier<Integer> nextId = () -> {
            while (position[0] <= 0xffff && occupied.contains(namespace | position[0])) { position[0]++; global.state().metrics.idProbes++; }
            global.state().metrics.idProbes++;
            if (position[0] > 0xffff) throw new IllegalArgumentException("No free function ID for class factories");
            int id = namespace | position[0]++; ids.add(id); occupied.add(id); return id;
        };
        // A class with literal-object fields and no declared destructor gets one that
        // ends those fields; every module creating such objects generates its own.
        for (ClassInfo info : global.classes.values()) if (info.synthesizedDestructor) try {
            int id = nextId.get(); String name = info.destructor;
            if (global.abs.containsKey(name)) throw new IllegalArgumentException("Reserved compiler function: " + name);
            JsonObject metadata = new JsonObject(); metadata.addProperty("owner-class", info.name); metadata.addProperty("function-kind", "destructor");
            copyPosition(info.source, metadata, global);
            JsonArray names = new JsonArray(); names.add("this"); metadata.add("param", names);
            List<Integer> types = List.of(info.pointerType);
            functions.add(new Function(id, name, AsTypes.VOID_VALUE, types, metadata, new JsonArray()));
            registerCompilerFunction(global, name, id, AsTypes.VOID_VALUE, types);
        } catch (RuntimeException error) { recover(error, info.source, "class " + info.name, global); }
        for (ClassInfo info : global.classes.values()) try {
            Function constructor = byName.get(info.constructor);
            List<Integer> types = List.copyOf(constructor.paramTypes().subList(1, constructor.paramTypes().size()));
            for (int kind = 0; kind < 3; kind++) {
                boolean manual = kind == 1, literal = kind == 2;
                int id = nextId.get();
                String name = literal ? info.name + VALUE_FACTORY : factoryName(info, manual);
                if (global.abs.containsKey(name)) throw new IllegalArgumentException("Reserved compiler function: " + name);
                if (literal) info.valueFactory = id; else if (manual) info.manualFactory = id; else info.scopedFactory = id;
                JsonObject metadata = new JsonObject(); metadata.addProperty("owner-class", info.name); copyPosition(info.source, metadata, global);
                String storage = literal ? "#object" : "this";
                JsonArray names = new JsonArray(), ctorArgs = new JsonArray();
                ctorArgs.add(literal ? internal(global, "#address-of", variable(storage)) : variable(storage));
                for (int i = 0; i < types.size(); i++) {
                    String argument = "<argument:" + i + ">"; names.add(argument);
                    // A literal-object argument was already copied into the factory; move it on.
                    ctorArgs.add(isOwnedValue(types.get(i)) || isTypeParameter(types.get(i)) ? internal(global, "#move", variable(argument)) : variable(argument));
                }
                metadata.add("param", names);
                JsonArray body = new JsonArray();
                JsonObject local = node("vardef", new JsonPrimitive(storage),
                        internal(global, literal ? "#new-block" : "#allocate", new JsonPrimitive(info.name)));
                local.addProperty("declared-type", literal ? info.name : info.name + "*"); global.trustedNodes.add(local); body.add(local);
                body.add(callNode(info.constructor, ctorArgs));
                JsonElement self = literal ? internal(global, "#address-of", variable(storage)) : variable(storage);
                body.add(internal(global, "#bind", self, info.cleanupDestructor == null ? JsonNull.INSTANCE : new JsonPrimitive(info.cleanupDestructor), new JsonPrimitive(manual)));
                body.add(node("return", variable(storage)));
                int returns = literal ? info.valueType : info.pointerType;
                functions.add(new Function(id, name, returns, types, metadata, body));
                registerCompilerFunction(global, name, id, returns, types);
            }
            if(constructor.paramTypes().size()==1) {
                int id=nextId.get();String name=info.name+PLACEMENT_FACTORY;
                JsonObject metadata=new JsonObject();metadata.addProperty("owner-class",info.name);copyPosition(info.source,metadata,global);metadata.add("param",array(new JsonPrimitive("this")));
                JsonArray body=new JsonArray();body.add(callNode(info.constructor,array(variable("this"))));
                body.add(internal(global,"#bind",variable("this"),info.cleanupDestructor==null?JsonNull.INSTANCE:new JsonPrimitive(info.cleanupDestructor),new JsonPrimitive(false)));
                functions.add(new Function(id,name,AsTypes.VOID_VALUE,List.of(info.pointerType),metadata,body));
                registerCompilerFunction(global,name,id,AsTypes.VOID_VALUE,List.of(info.pointerType));
            }
        } catch (RuntimeException error) { recover(error, info.source, "class " + info.name, global); }
        diagnostics.checkpoint();
    }
    private static void validateVirtualMethods(Map<String,Function> functions,LogicEnvironment global) {
        for(ClassInfo info:global.classes.values())for(var method:info.ownMethods.entrySet()) {
            String name=method.getKey(),display=info.name+"."+name;
            Function function=functions.get(method.getValue());
            try {
                boolean marked=info.virtualMethods.contains(name)||info.overrideMethods.contains(name)||info.finalMethods.contains(name);
                if(marked&&function.typeParameters().size()!=function.classParameters())
                    throw new IllegalArgumentException("Virtual method "+display+" cannot declare method type parameters");
                Integer inherited=info.base==null?null:info.base.virtualSlots.get(name);
                if(inherited==null) {
                    if(info.overrideMethods.contains(name))throw new IllegalArgumentException("Method "+display+" is declared override but does not override a virtual method");
                    if(info.finalMethods.contains(name)&&!info.virtualMethods.contains(name))
                        throw new IllegalArgumentException("Method "+display+" declared final must be virtual or override");
                    continue;
                }
                VirtualMethod base=info.base.vtable.get(inherited);
                String parent=base.function().replace("::", ".");
                if(!info.overrideMethods.contains(name))throw new IllegalArgumentException("Method "+display+" overrides virtual method "+parent+" and must be declared override");
                if(base.finalMethod())throw new IllegalArgumentException("Method "+display+" cannot override final method "+parent);
                Signature expected=operatorSignature(base.owner(),functions.get(base.function())),actual=operatorSignature(info,function);
                if(expected.paramTypes().size()!=actual.paramTypes().size()
                        ||!expected.paramTypes().subList(1,expected.paramTypes().size()).equals(actual.paramTypes().subList(1,actual.paramTypes().size())))
                    throw new IllegalArgumentException("Override "+display+" must have the same parameter types as "+parent);
                int wanted=expected.returnType(),returned=actual.returnType();
                if(wanted!=returned&&!(isPointerClass(wanted)&&isPointerClass(returned)&&assignable(wanted,returned)))
                    throw new IllegalArgumentException("Override "+display+" has incompatible return type: "+typeName(returned)+"; expected "+typeName(wanted));
            } catch(RuntimeException error) { recover(error,function==null?info.source:function.metadata(),"class "+info.name,global); }
        }
    }
    private static Signature operatorSignature(ClassInfo receiver,Function function) {
        ClassInfo declaration=CLASS_DECLARATIONS.get().get(function.metadata().get("owner-class").getAsString());
        Map<Integer,Integer> substitution=substitutions(ancestor(receiver,declaration));
        return new Signature(function.id(),substitute(function.returnType(),substitution),function.paramTypes().stream().map(t->substitute(t,substitution)).toList(),true);
    }
    /** A typed global declaration of the AST; {@code declaration} locates it and holds its initializer. */
    private record TypedGlobal(String name, int type, JsonObject declaration) {}
    private static final String ONLOAD = "__script_onload";
    /**
     * Initializes typed globals at the start of the module's onload (function 0),
     * synthesized when the module has none: every typed global first gets its type's
     * default, then the explicit initializers run in declaration order, then the
     * onload body. The statements are spliced into the body's top-level list, so the
     * body is not nested any deeper. A module without typed globals is unchanged.
     */
    private static void addGlobalInitialization(List<Function> functions, LogicEnvironment global, List<TypedGlobal> typedGlobals) {
        if (typedGlobals.isEmpty()) return;
        JsonArray prologue = new JsonArray();
        for (TypedGlobal typed : typedGlobals) prologue.add(globalInitializer(global, typed, defaultValue(typed.type())));
        for (TypedGlobal typed : typedGlobals)
            if (typed.declaration().has("initializer")) prologue.add(globalInitializer(global, typed, typed.declaration().get("initializer")));
        for (int i = 0; i < functions.size(); i++) {
            Function onload = functions.get(i);
            if (onload.id() != 0) continue;
            if (onload.script().isJsonArray()) prologue.addAll(onload.script().getAsJsonArray()); else prologue.add(onload.script());
            functions.set(i, new Function(onload.id(), onload.name(), onload.returnType(), onload.paramTypes(), onload.metadata(), prologue));
            return;
        }
        if (global.abs.containsKey(ONLOAD)) {
            JsonObject anchor = functions.stream().filter(function -> function.name().equals(ONLOAD)).map(Function::metadata)
                    .findFirst().orElse(typedGlobals.get(0).declaration());
            recover(new IllegalArgumentException("Typed globals are initialized in function " + Diagnostic.hexId(0) + ", but " + ONLOAD + " has another ID"),
                    anchor, contextName(ONLOAD), global);
            return;
        }
        // A #gvar cannot share its name with the synthesized onload either.
        if (global.local(ONLOAD) != null) {
            recover(new IllegalArgumentException("Global " + ONLOAD + " conflicts with function " + ONLOAD), typedGlobals.get(0).declaration(), "global " + ONLOAD, global);
            return;
        }
        JsonObject metadata = new JsonObject(); metadata.addProperty("name", ONLOAD);
        metadata.add("param", new JsonArray()); metadata.add("param-types", new JsonArray());
        Function onload = new Function(0, ONLOAD, AsTypes.VOID_VALUE, List.of(), metadata, prologue);
        functions.add(onload); global.abs.put(ONLOAD, 0); global.namedSignatures.put(ONLOAD, onload.signature());
    }
    private static JsonObject globalInitializer(LogicEnvironment global, TypedGlobal typed, JsonElement value) {
        JsonObject result = internal(global, "#global-initialize", new JsonPrimitive(typed.name()), value);
        copyPosition(typed.declaration(), result, global); return result;
    }
    private static void registerCompilerFunction(LogicEnvironment global, String name, int id, int returns, List<Integer> types) {
        global.abs.put(name, id); global.arities.put(id, types.size()); global.functionReturnTypes.put(id, returns);
        global.scriptSignatures.put(id, new Signature(id, returns, types, true));
        global.namedSignatures.put(name, new Signature(id, returns, types, true));
    }
    private static void validateClassFunction(Map<String,Function> functions, ClassInfo owner, String name, String kind) {
        Function function = functions.get(name);
        if (function == null) throw new IllegalArgumentException("Missing class function: " + name);
        JsonObject metadata = function.metadata();
        if (!owner.name.equals(string(metadata.get("owner-class"), "Function owner"))
                || !kind.equals(string(metadata.get("function-kind"), "Class function kind"))
                || function.paramTypes().isEmpty() || function.paramTypes().get(0) != owner.pointerType
                || !string(metadata.getAsJsonArray("param").get(0), "this parameter").equals("this"))
            throw new IllegalArgumentException("Invalid implicit this signature for " + name);
        if (!kind.equals("method") && function.returnType() != AsTypes.VOID_VALUE)
            throw new IllegalArgumentException("Constructor/destructor must return void: " + name);
        if (kind.equals("destructor") && function.paramTypes().size() != 1)
            throw new IllegalArgumentException("Destructor must have no parameters: " + name);
    }
    private static JsonElement classFunctionBody(Function function, LogicEnvironment global) {
        JsonObject metadata = function.metadata(); JsonElement body = function.script();
        String kind = metadata.has("function-kind") ? string(metadata.get("function-kind"), "Class function kind") : "";
        boolean hasBaseInitializer = metadata.has("base-args") || metadata.has("base-initializer");
        if (hasBaseInitializer && !kind.equals("constructor"))
            throw new IllegalArgumentException("Base initializer is only allowed on a constructor");
        if (kind.isEmpty()) return body;
        if (!body.isJsonArray()) throw new IllegalArgumentException("Class function body must be a block");
        ClassInfo info = global.classes.get(string(metadata.get("owner-class"), "Function owner"));
        JsonArray guarded = new JsonArray(); guarded.add(internal(global, "#check-this"));
        if(kind.equals("constructor")||kind.equals("destructor")) {
            JsonObject methods=internal(global,"#set-methods");copyPosition(metadata,methods,global);guarded.add(methods);
        }
        if(kind.equals("constructor")) {
            for(Field field:info.ownFields)if(!isOwnedValue(field.type())) {
                JsonObject member=node("member",variable("this"),new JsonPrimitive(field.name()));
                if(isTypeParameter(field.type())) {
                    JsonElement abi=internal(global,"#context-abi",new JsonPrimitive(field.type()));
                    JsonElement value=internal(global,"#context-default",new JsonPrimitive(field.type()));
                    guarded.add(internal(global,"#field-initialize",node("if",node("ne",abi,new JsonPrimitive(8)),node("member-set",member,value))));
                }else guarded.add(internal(global,"#field-initialize",node("member-set",member,defaultValue(field.type()))));
            }
            for(Field field:info.ownFields) {
                JsonElement value=field.initializer();
                if(value==null&&!isOwnedValue(field.type())&&!isTypeParameter(field.type()))continue;
                boolean genericDefault=value==null&&isTypeParameter(field.type());
                if(value==null) {
                    value=genericDefault?internal(global,"#context-default",new JsonPrimitive(field.type())):isBuffer(field.type())?node("buffer-new",new JsonPrimitive(typeName(BUFFER_ELEMENTS.get().get(field.type())))):node("object-value",new JsonPrimitive(CLASS_LAYOUTS.get().get(field.type()).name));
                    if(value.isJsonObject())position(value.getAsJsonObject(),field,global);
                }
                JsonObject assignment=node("member-set",node("member",variable("this"),new JsonPrimitive(field.name())),value);
                position(assignment,field,global);
                JsonElement initialize=genericDefault?node("if",node("cmp",internal(global,"#context-abi",new JsonPrimitive(field.type())),new JsonPrimitive(8)),assignment):assignment;
                JsonObject initializer=internal(global,"#field-initialize",initialize);
                position(initializer,field,global);
                guarded.add(initializer);
            }
        }
        body.getAsJsonArray().forEach(guarded::add);
        if (hasBaseInitializer && (info.base == null || !metadata.has("base-initializer") || !metadata.has("base-args")
                || resolveType(string(metadata.get("base-initializer"), "Base initializer"),function.typeScope()) != info.base.valueType || !metadata.get("base-args").isJsonArray()))
            throw new IllegalArgumentException("Constructor initializer must name the direct base of " + info.name);
        JsonArray receiver = new JsonArray(); receiver.add(variable("this"));
        if (kind.equals("destructor")) {
            // C++ order: this body, this class's literal-object fields (last first), then the base.
            JsonArray finalizer = fieldDrops(info,global);
            if (info.base != null && info.base.cleanupDestructor != null) finalizer.add(callNode(info.base.cleanupDestructor, receiver));
            return finalizer.isEmpty() ? guarded : internal(global, "#cleanup", guarded, cleanupSequence(finalizer,0,finalizer.size(),global), new JsonPrimitive(false));
        }
        JsonElement phase=guarded;
        if(kind.equals("constructor")&&info.base==null) {
            // A failed construction never enters the user's destructor. End only
            // the fields that completed (uninitialized slots are void), before
            // the outer guard rolls back the completed base.
            JsonArray fields=fieldDrops(info,global);
            if(!fields.isEmpty())phase=internal(global,"#cleanup",guarded,cleanupSequence(fields,0,fields.size(),global),new JsonPrimitive(true));
        }
        if (info.base == null || !kind.equals("constructor")) return phase;
        JsonArray arguments = new JsonArray(); arguments.add(variable("this"));
        if (metadata.has("base-args")) metadata.getAsJsonArray("base-args").forEach(arguments::add);
        JsonObject baseCall = callNode(info.base.constructor, arguments);
        copyPosition(metadata, baseCall, global);
        JsonArray construction = new JsonArray(); construction.add(internal(global, "#check-this"));
        JsonArray rollback=fieldDrops(info,global);
        if(info.base.cleanupDestructor!=null)rollback.add(callNode(info.base.cleanupDestructor,receiver));
        if (rollback.isEmpty()) { construction.add(baseCall); construction.add(phase); }
        else {
            // Include base-argument temporaries in the protected scope: their own destructors
            // may fail after the child body returns, which must still roll back the base.
            String completed = "#base-constructed";
            JsonObject flag = node("vardef", new JsonPrimitive(completed), new JsonPrimitive(false));
            flag.addProperty("declared-type", "boolean"); construction.add(flag);
            JsonArray protectedBody = new JsonArray();
            JsonObject complete=internal(global,"#base-completed",new JsonPrimitive(completed),baseCall);copyPosition(metadata,complete,global);
            protectedBody.add(complete);protectedBody.add(phase);
            JsonObject cleanup = node("if", variable(completed), cleanupSequence(rollback,0,rollback.size(),global));
            construction.add(internal(global, "#cleanup", protectedBody, cleanup, new JsonPrimitive(true)));
        }
        return construction;
    }
    private static JsonArray fieldDrops(ClassInfo info,LogicEnvironment global) {
        JsonArray fields=new JsonArray();
        for(int i=info.ownFields.size()-1;i>=0;i--) {
            Field field=info.ownFields.get(i);
            if(isOwnedValue(field.type())||isTypeParameter(field.type()))
                fields.add(internal(global,"#drop",node("member",variable("this"),new JsonPrimitive(field.name()))));
        }
        return fields;
    }
    /** Every cleanup runs, in order, even when an earlier cleanup throws. */
    private static JsonElement cleanupSequence(JsonArray steps,int begin,int end,LogicEnvironment global) {
        if(end-begin==1)return steps.get(begin);
        int middle=(begin+end)>>>1;
        return internal(global,"#cleanup",cleanupSequence(steps,begin,middle,global),cleanupSequence(steps,middle,end,global),new JsonPrimitive(false));
    }
    /**
     * Conservative control-flow check: can execution fall off the end of this
     * statement or block? A return never completes normally; an if completes
     * normally unless both branches cannot; a while completes normally unless its
     * condition is a true literal and its own body contains no break. Anything
     * unrecognised is assumed to complete normally.
     */
    static boolean canCompleteNormally(JsonElement node) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) return true;
        if (node.isJsonArray()) {
            for (JsonElement statement : node.getAsJsonArray()) if (!canCompleteNormally(statement)) return false;
            return true;
        }
        JsonObject object = node.getAsJsonObject();
        if (!isControl(object)) return true;
        JsonArray params = object.has("param") && object.get("param").isJsonArray()
                ? object.getAsJsonArray("param") : new JsonArray();
        return switch (object.get("call").getAsString()) {
            case "return", "break", "continue" -> false;
            case "if" -> params.size() < 3 || canCompleteNormally(params.get(1)) || canCompleteNormally(params.get(2));
            case "if-chain" -> {
                boolean completes=!object.has("else")||canCompleteNormally(object.get("else"));
                for(JsonElement branch:params)completes|=canCompleteNormally(parameters(branch.getAsJsonObject()).get(1));yield completes;
            }
            case "while" -> params.size() < 2 || !isTrueLiteral(params.get(0)) || containsBreak(params.get(1));
            case "for" -> params.size() < 4 || !isTrueLiteral(params.get(1)) || containsBreak(params.get(3));
            case "switch" -> switchCanComplete(params);
            default -> true;
        };
    }
    private static boolean isControl(JsonObject object) {
        return object.has("t") && object.get("t").isJsonPrimitive() && object.get("t").getAsJsonPrimitive().isString()
                && object.get("t").getAsString().equals("ctrl")
                && object.has("call") && object.get("call").isJsonPrimitive() && object.get("call").getAsJsonPrimitive().isString();
    }
    private static boolean isTrueLiteral(JsonElement condition) {
        if (!condition.isJsonPrimitive()) return false;
        JsonPrimitive value = condition.getAsJsonPrimitive();
        if (value.isBoolean()) return value.getAsBoolean();
        return value.isNumber() && value.getAsBigDecimal().signum() != 0;
    }
    /** True when a break belonging to this loop level appears anywhere in the body. */
    private static boolean containsBreak(JsonElement node) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) return false;
        if (node.isJsonArray()) {
            for (JsonElement statement : node.getAsJsonArray()) {
                if (containsBreak(statement)) return true;
                if (!canCompleteNormally(statement)) break;
            }
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        if (!isControl(object)) return false;
        String call = object.get("call").getAsString();
        if (call.equals("break")) return true;
        if (call.equals("while") || call.equals("for") || call.equals("switch")) return false;
        if (!object.has("param") || !object.get("param").isJsonArray()) return false;
        for (JsonElement child : object.getAsJsonArray("param")) if (containsBreak(child)) return true;
        return call.equals("if-chain")&&object.has("else")&&containsBreak(object.get("else"));
    }
    private static boolean switchCanComplete(JsonArray params) {
        if (params.size() != 2 || !params.get(1).isJsonArray()) return true;
        JsonArray sections = params.get(1).getAsJsonArray(); boolean fallback = false, completes = false;
        for (int i = 0; i < sections.size(); i++) {
            JsonObject section = sections.get(i).getAsJsonObject(); fallback |= section.has("default");
            JsonArray body = parameters(section).get(0).getAsJsonArray();
            if (body.isEmpty() && i + 1 < sections.size()) continue;
            completes |= canCompleteNormally(body) || containsBreak(body);
        }
        return !fallback || completes;
    }
    private static boolean containsContinue(JsonElement node) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) return false;
        if (node.isJsonArray()) {
            for (JsonElement statement : node.getAsJsonArray()) {
                if (containsContinue(statement)) return true;
            }
            return false;
        }
        JsonObject object = node.getAsJsonObject(); if (!isControl(object)) return false;
        String call = object.get("call").getAsString();
        if (call.equals("continue")) return true;
        if (call.equals("while") || call.equals("for")) return false;
        for (JsonElement child : parameters(object)) if (containsContinue(child)) return true;
        return call.equals("if-chain")&&object.has("else")&&containsContinue(object.get("else"));
    }
    /**
     * Compiles a readable AST without debug info, reporting up to
     * {@link Diagnostics#DEFAULT_MAX_ERRORS} errors in one {@link CompileException}.
     */
    public static AcsObject compile(JsonObject source) { return compile(source, new Diagnostics(), false); }
    /**
     * Compiles a readable AST; with {@code debugInfo}, as the {@code -g} option gives,
     * each function gets an exec debug record with its display name and the source
     * positions of its nodes.
     */
    public static AcsObject compile(JsonObject source, boolean debugInfo) { return compile(source, new Diagnostics(), debugInfo); }
    /** Compiles a readable AST without debug info; see {@link #compile(JsonObject, Diagnostics, boolean)}. */
    public static AcsObject compile(JsonObject source, Diagnostics diagnostics) { return compile(source, diagnostics, false); }
    /**
     * Compiles a readable AST. Errors are reported as a {@link CompileException}
     * located through the AST's {@code sources} table; an AST without one is named
     * after {@link Diagnostics#defaultFile()}, which also names the input in errors
     * without a source position. A {@code Diagnostics} instance serves one
     * compilation: it may first parse the program with
     * {@link GeneraterJson.AzScript#execute(java.nio.file.Path, Diagnostics)}.
     *
     * <p>The AST's nesting is bounded and its shape is checked before compilation.
     * An unexpected exception is an internal compiler error for an AST parsed with
     * the same collector, and malformed input otherwise.
     *
     * <p>With {@code debugInfo}, the exec gets a {@code debug-files} table of the
     * source names and each function a {@code debug} record (see {@link ExecCodec});
     * without it the table is empty and no function has a record. Debug info never
     * changes the instructions.
     */
    public static AcsObject compile(JsonObject source, Diagnostics diagnostics, boolean debugInfo) {
        return compile(source, diagnostics, debugInfo, Optimization.NONE);
    }
    /** Compiles with an explicit optimization level; source AST and diagnostics are unchanged. */
    public static AcsObject compile(JsonObject source, Diagnostics diagnostics, boolean debugInfo, Optimization optimization) {
        return compile(source, diagnostics, debugInfo, optimizationPasses(optimization));
    }
    /** Select passes independently; the empty set is the byte-preserving O0 path. */
    public static AcsObject compile(JsonObject source, Diagnostics diagnostics, boolean debugInfo, Set<OptimizationPass> passes) {
        passes = Set.copyOf(passes);
        int limit=abdLimit();
        boolean parsed = diagnostics.startCompile();
        Map<Integer,ClassInfo> previousLayouts = CLASS_LAYOUTS.get();
        var previousParameters=TYPE_PARAMETERS.get();var previousScope=TYPE_SCOPE.get();var previousDeclarations=CLASS_DECLARATIONS.get();
        var previousApplications=CLASS_APPLICATIONS.get();var previousFunctions=FUNCTION_SCOPES.get();int previousNext=NEXT_CLASS.get();
        var previousBufferElements=BUFFER_ELEMENTS.get();var previousBufferTypes=BUFFER_TYPES.get();
        CLASS_LAYOUTS.set(new LinkedHashMap<>());TYPE_PARAMETERS.set(new LinkedHashMap<>());
        BUFFER_ELEMENTS.set(new LinkedHashMap<>());BUFFER_TYPES.set(new LinkedHashMap<>());
        TYPE_SCOPE.set(new LinkedHashMap<>());CLASS_DECLARATIONS.set(new LinkedHashMap<>());CLASS_APPLICATIONS.set(new LinkedHashMap<>());FUNCTION_SCOPES.set(new IdentityHashMap<>());NEXT_CLASS.set(FIRST_CLASS_TYPE);
        SourceMap sources = SourceMap.single(diagnostics.defaultFile());
        // A malformed sources table is reported by the shape check; positions then name the default file.
        try { sources = SourceMap.fromJson(source.get("sources"), diagnostics.defaultFile()); }
        catch (IllegalArgumentException malformed) { /* reported below */ }
        if (diagnostics.namesInput()) sources.input(diagnostics.defaultFile());
        diagnostics.sources(sources);
        CompilationState state = new CompilationState(diagnostics, sources, parsed, limit);
        state.optimizationPasses = passes;
        if (debugInfo) state.enableDebugInfo();
        try {
            // Bound nesting and reject cycles first: the shape check walks the whole tree.
            try { TreeLimits.validate(source); }
            catch (TreeLimits.LimitExceeded e) { diagnostics.report(new CompileException(List.of(sources.error(e.anchor(), null, e.getMessage())), e, true)); }
            for (String problem : AstShape.validate(source)) {
                // The in-process parser never produces a malformed AST; if it did, that is a compiler bug.
                Diagnostic diagnostic = sources.error(null, problem);
                if (parsed) diagnostic = new Diagnostic(diagnostic.severity(), null, diagnostic.position(), null, "internal compiler error: " + problem,
                        List.of(Diagnostic.note(null, "this is a compiler bug; rerun with --debug-compiler for a stack trace")), true);
                diagnostics.report(new CompileException(diagnostic));
            }
            diagnostics.checkpoint();
            AcsObject result = compileProgram(source, state);
            diagnostics.checkpoint();
            return result;
        }
        catch (CompileException e) { throw diagnostics.record(e); }
        catch (RuntimeException | StackOverflowError e) {
            // A deliberate error outside the per-item recovery names the input; anything else is unexpected.
            throw diagnostics.record(Diagnostics.userError(e) ? new CompileException(sources.error(null, message(e)), e) : unexpected(e, null, null, state));
        }
        finally { CLASS_LAYOUTS.set(previousLayouts);TYPE_PARAMETERS.set(previousParameters);TYPE_SCOPE.set(previousScope);CLASS_DECLARATIONS.set(previousDeclarations);CLASS_APPLICATIONS.set(previousApplications);FUNCTION_SCOPES.set(previousFunctions);NEXT_CLASS.set(previousNext);BUFFER_ELEMENTS.set(previousBufferElements);BUFFER_TYPES.set(previousBufferTypes); }
    }
    private static boolean sameSignature(Signature first, Signature second) {
        if(first.typeParameters().size()!=second.typeParameters().size()||first.classParameters()!=second.classParameters()||first.internal()!=second.internal())return false;
        Map<Integer,Integer> renaming=new HashMap<>();
        for(int i=0;i<first.typeParameters().size();i++) {
            int left=first.typeParameters().get(i),right=second.typeParameters().get(i);
            if(TYPE_PARAMETERS.get().get(left).bound()!=substitute(TYPE_PARAMETERS.get().get(right).bound(),renaming))return false;
            renaming.put(right,left);
        }
        if (first.returnType() != substitute(second.returnType(),renaming) || first.paramTypes().size() != second.paramTypes().size()) return false;
        for (int i = 0; i < first.paramTypes().size(); i++)
            if (first.paramTypes().get(i) != substitute(second.paramTypes().get(i),renaming)) return false;
        return true;
    }
    /**
     * Lowers a shape-checked AST. Each global loop reports the errors of every item
     * and ends with a barrier, and function bodies are checked one by one, so
     * independent errors are reported together without cascading into later phases.
     */
    private static AcsObject compileProgram(JsonObject source, CompilationState state) {
        AcsObject result = new ExecProgram(); JsonObject metadata = source.getAsJsonObject("metadata");
        LogicEnvironment global = new LogicEnvironment(); global.state = state;
        Diagnostics diagnostics = state.diagnostics;
        result.put("author", metadata.has("author") ? string(metadata.get("author"), "Author") : "");
        result.put("version", metadata.has("version") ? metadata.get("version").getAsInt() : 2); result.put("exec-version", ExecCodec.VERSION);
        AcsObject extension = new AcsObject();
        if (source.has("ext")) for (var entry : source.getAsJsonObject("ext").entrySet()) {
            try { extension.put(entry.getKey(), string(entry.getValue(), "Extension value")); }
            catch (RuntimeException error) { recover(error, null, null, global); }
        }
        result.put("ext", extension);
        String hint = source.has("namespace-hint") ? string(source.get("namespace-hint"), "Namespace hint") : "";
        try { if (!hint.isEmpty()) SourceRules.requireHint(hint); }
        catch (RuntimeException error) { recover(error, null, null, global); }
        Map<String,Integer> assumptions = new LinkedHashMap<>();
        if (source.has("assume-hints")) for (JsonElement item : source.getAsJsonArray("assume-hints")) try {
            JsonObject assumption = item.getAsJsonObject(); String name = string(assumption.get("hint"), "Assumed hint"); SourceRules.requireHint(name);
            int namespace = assumption.get("namespace").getAsBigDecimal().intValueExact();
            if (!SourceRules.assumableNamespace(namespace))
                throw new IllegalArgumentException("Reserved or invalid assumed namespace");
            Integer existing = assumptions.get(name);
            if (existing != null && existing != namespace || existing == null && assumptions.containsValue(namespace))
                throw new IllegalArgumentException("Conflicting hint or assumed namespace");
            assumptions.put(name, namespace);
        } catch (RuntimeException error) { recover(error, null, null, global); }
        diagnostics.checkpoint();
        addDefaultIds(global.abs); addBuiltinSignatures(global); readClasses(source, global);
        global.moduleNamespace = metadata.has("function-namespace") ? metadata.get("function-namespace").getAsBigDecimal().intValueExact()
                : hint.isEmpty() ? SourceRules.DEFAULT_NAMESPACE : SourceRules.LIFECYCLE_NAMESPACE;
        String reservedNamespace = hint.isEmpty() ? SourceRules.reservedModuleNamespace(global.moduleNamespace) : null;
        if (global.moduleNamespace < 0 || global.moduleNamespace > 0xffff || !hint.isEmpty() && global.moduleNamespace != SourceRules.LIFECYCLE_NAMESPACE)
            recover(new IllegalArgumentException("Invalid function namespace"), null, null, global);
        else if (reservedNamespace != null)
            recover(new IllegalArgumentException("Invalid function namespace: " + reservedNamespace), null, null, global);
        Map<String,Integer> abstractIds = new LinkedHashMap<>();
        // Every abstract name must also name a definition or an extern, where it is checked with a location.
        if (source.has("abstract")) for (var entry : source.getAsJsonObject("abstract").entrySet()) try {
            if (isBuiltinName(entry.getKey()) || global.classes.containsKey(entry.getKey())) throw new IllegalArgumentException("Reserved function name: " + entry.getKey());
            int id = SourceRules.hex(string(entry.getValue(), "Function ID"), 8, "function ID");
            if (SourceRules.runtimeId(id)) throw new IllegalArgumentException("Abstract function cannot use the runtime namespace");
            abstractIds.put(entry.getKey(), id);
        } catch (RuntimeException error) { recover(error, null, null, global); }
        diagnostics.checkpoint();
        // An unnamed definition takes the first abstract name mapped to its ID.
        Map<Integer,String> abstractNames = new HashMap<>();
        abstractIds.forEach((abstractName, abstractId) -> abstractNames.putIfAbsent(abstractId, abstractName));
        List<Function> functions = new ArrayList<>(); Map<String,Function> definitions = new LinkedHashMap<>(); Set<Integer> ids = new HashSet<>();
        for (var namespace : source.getAsJsonObject("body").entrySet()) {
            int namespaceId;
            try { namespaceId = SourceRules.hex(namespace.getKey(), 4, "namespace") << 16; }
            catch (RuntimeException error) { recover(error, null, null, global); continue; }
            for (var entry : namespace.getValue().getAsJsonObject().entrySet()) {
                JsonObject function = entry.getValue().getAsJsonObject(), meta = function.getAsJsonObject("metadata");
                String name = null;
                try {
                int position = meta.get("position").getAsBigDecimal().intValueExact();
                if (position < 0 || position > 0xffff) throw new IllegalArgumentException("Invalid function position: " + position);
                int id = namespaceId | position;
                if (!hint.isEmpty() && namespaceId != 0) throw new IllegalArgumentException("Invalid definition namespace");
                if (SourceRules.runtimeId(id))
                    throw new IllegalArgumentException("Invalid definition namespace: " + SourceRules.reservedModuleNamespace(SourceRules.RUNTIME_NAMESPACE));
                if (hint.isEmpty() && namespaceId == 0 && id != 0 && id != 1)
                    throw new IllegalArgumentException("Invalid definition namespace: outside hint libraries, namespace 0000 holds only the lifecycle functions "
                            + Diagnostic.hexId(0) + " and " + Diagnostic.hexId(1));
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate function ID: " + Diagnostic.hexId(id));
                name = meta.has("name") ? string(meta.get("name"), "Function name") : abstractNames.getOrDefault(id, entry.getKey());
                validateFunctionName(name, "function name");
                diagnostics.metrics().tableProbes++;
                if (isBuiltinName(name) || global.classes.containsKey(name)) throw new IllegalArgumentException("Reserved function name: " + name);
                TYPE_SCOPE.set(functionScope(meta));
                int declaredReturn = returnType(string(meta.get("return-type"), "Function return type"));
                JsonArray parameterNames = meta.getAsJsonArray("param");
                for (int i = 0; i < parameterNames.size(); i++) {
                    // A class function's receiver is the first parameter, named this.
                    String parameter = string(parameterNames.get(i), "Parameter name");
                    if (i > 0 || !parameter.equals("this") || !name.contains("::")) validateSourceName(parameter, "parameter name");
                }
                if ((id == 0 || id == 1) && (declaredReturn != AsTypes.VOID_VALUE || !parameterNames.isEmpty()))
                    throw new IllegalArgumentException("Lifecycle function " + name + " must be void with no parameters");
                List<Integer> parameterTypes = readParameterTypes(meta.get("param-types"), parameterNames.size(), true, "Function " + name);
                Function value = new Function(id, name, declaredReturn, parameterTypes, meta, function.get("script"));
                if (definitions.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate function definition: " + name);
                functions.add(value); global.abs.put(name, id);
                global.namedSignatures.put(name, value.signature());
                } catch (RuntimeException error) { recover(error, meta, name == null ? null : contextName(name), global); }
            }
        }
        diagnostics.checkpoint();
        Map<String,Function> declarations = new LinkedHashMap<>(); Map<Integer,Signature> importIds = new LinkedHashMap<>();
        if (source.has("extern-signatures")) for (var entry : source.getAsJsonObject("extern-signatures").entrySet()) {
            String name = entry.getKey(); JsonObject declaration = entry.getValue().getAsJsonObject();
            try {
            // Boxed in both branches, so a missing mapping is reported below instead of failing to unbox.
            validateFunctionName(name, "function name");
            Integer id = declaration.has("id") ? Integer.valueOf(SourceRules.hex(string(declaration.get("id"), "External ID"), 8, "external ID")) : abstractIds.get(name);
            if (id == null) throw new IllegalArgumentException("External signature has no abstract mapping: " + name);
            if (isBuiltinName(name) || global.classes.containsKey(name) || SourceRules.reservedExternalId(id))
                throw new IllegalArgumentException("Reserved external function: " + name);
            TYPE_SCOPE.set(functionScope(declaration));
            int returns = returnType(string(declaration.get("return-type"), "External return type"));
            List<Integer> params = readParameterTypes(declaration.get("param-types"), -1, false, "External " + name);
            List<Integer> genericParameters=List.copyOf(functionScope(declaration).values());
            int classCount=declaration.has("owner-class")?global.classes.get(declaration.get("owner-class").getAsString()).arguments.size():0;
            boolean internal=declaration.has("function-kind")&&!declaration.get("function-kind").getAsString().equals("method");
            Signature signature = new Signature(id, returns, params, true,genericParameters,classCount,internal);
            Signature duplicate = importIds.putIfAbsent(id, signature);
            if (duplicate != null && !sameSignature(duplicate, signature)) throw new IllegalArgumentException("Conflicting external signature ID: " + Diagnostic.hexId(id));
            if (global.namedSignatures.containsKey(name) && !sameSignature(global.namedSignatures.get(name), signature))
                throw new IllegalArgumentException("Extern and definition signatures disagree: " + name);
            Function defined = definitions.get(name);
            if (defined != null && !hint.isEmpty() && Objects.equals(assumptions.get(hint), id >>> 16) && (defined.id() & 0xffff) != (id & 0xffff))
                throw new IllegalArgumentException("Definition ID disagrees with its own hint extern declaration: " + name);
            JsonObject meta = declaration.deepCopy(); JsonArray paramNames = new JsonArray();
            for (int i = 0; i < params.size(); i++) paramNames.add(i == 0 && declaration.has("owner-class") ? "this" : "<argument:" + i + ">");
            meta.add("param", paramNames);FUNCTION_SCOPES.get().put(meta,functionScope(declaration));
            declarations.put(name, new Function(id, name, returns, params, meta, JsonNull.INSTANCE));
            global.abs.put(name, id); global.namedSignatures.put(name, signature);
            } catch (RuntimeException error) { recover(error, declaration, null, global); }
        }
        diagnostics.checkpoint();
        for (var entry : abstractIds.entrySet()) {
            Function definition = definitions.get(entry.getKey()), declaration = declarations.get(entry.getKey());
            JsonObject anchor = declaration != null ? declaration.metadata() : definition != null ? definition.metadata() : null;
            if (definition == null && declaration == null)
                recover(new IllegalArgumentException("Missing external signature for " + entry.getKey()), null, null, global);
            else if (entry.getValue() != (declaration != null ? declaration.id() : definition.id()))
                recover(new IllegalArgumentException("Function mapping disagrees with declaration: " + entry.getKey()), anchor, contextName(entry.getKey()), global);
        }
        diagnostics.checkpoint();
        // Global types never see the type parameters of the last extern or definition read above.
        TYPE_SCOPE.set(Map.of());
        int globals = 0; List<TypedGlobal> typedGlobals = new ArrayList<>();
        if (source.has("global-variable")) for (JsonElement item : source.getAsJsonArray("global-variable")) {
            // A #gvar global is a bare name; a typed declaration is an object.
            JsonObject entry = item.isJsonObject() ? item.getAsJsonObject() : null;
            String name = string(entry == null ? item : entry.get("name"), "Global name");
            try {
                validateSourceName(name, "global name");
                if (global.classes.containsKey(name)) throw new IllegalArgumentException("Global name conflicts with class: " + name);
                int type = AsTypes.ANY_VALUE;
                if (entry != null) {
                    diagnostics.metrics().tableProbes += 2;
                    if (global.local(name) != null) throw new IllegalArgumentException("Duplicate global variable: " + name);
                    if (SourceRules.LIFECYCLE_HOOKS.contains(name)) throw new IllegalArgumentException("Global " + name + " conflicts with lifecycle function " + name);
                    if (global.abs.containsKey(name))
                        throw new IllegalArgumentException("Global " + name + " conflicts with " + (isBuiltinName(name) ? "built-in function " : "function ") + name);
                    type = valueType(string(entry.get("type"), "Global type"), true);
                    if (isOwnedValue(type)) throw new IllegalArgumentException("Global " + name + " cannot hold a class value or buffer; declare a pointer");
                }
                if (globals >= MAX_VARIABLE_SLOTS) throw new IllegalArgumentException("Program exceeds global variable slot limit");
                global.define(name, -(++globals), type);
                if (entry != null) typedGlobals.add(new TypedGlobal(name, type, entry));
            } catch (RuntimeException error) { recover(error, entry, "global " + name, global); }
        }
        diagnostics.checkpoint();
        result.put("gvs", globals);
        // Imported self slots are reserved even if this module does not define them.
        for (int imported : importIds.keySet()) {
            boolean self = hint.isEmpty() ? (imported >>> 16) == global.moduleNamespace
                    : Objects.equals(assumptions.get(hint), imported >>> 16);
            if (self) ids.add((global.moduleNamespace << 16) | (imported & 0xffff));
        }
        addClassFactories(functions, ids, global, declarations);
        for(Function function:functions) {
            if(isFactory(function.name())||function.metadata().has("function-kind")&&function.metadata().get("function-kind").getAsString().equals("destructor")&&global.classes.get(function.metadata().get("owner-class").getAsString()).synthesizedDestructor)
                global.namedSignatures.put(function.name(),function.signature());
        }
        try { addGlobalInitialization(functions, global, typedGlobals); }
        catch (RuntimeException error) { recover(error, null, null, global); }
        diagnostics.checkpoint();
        Set<Integer> fixedNamespaces = new HashSet<>(Set.of(SourceRules.LIFECYCLE_NAMESPACE, SourceRules.RUNTIME_NAMESPACE, SourceRules.DEFAULT_NAMESPACE));
        if (hint.isEmpty()) {fixedNamespaces.add(global.moduleNamespace); for (Function function : functions) fixedNamespaces.add(function.id() >>> 16);}
        for (int namespace : assumptions.values()) if (fixedNamespaces.contains(namespace))
            recover(new IllegalArgumentException("Assumed namespace overlaps a fixed function namespace"), null, null, global);
        diagnostics.checkpoint();
        if (!hint.isEmpty() && !assumptions.containsKey(hint)) {
            Set<Integer> occupied = new HashSet<>(fixedNamespaces); occupied.addAll(assumptions.values());
            for (int id : importIds.keySet()) occupied.add(id >>> 16);
            int namespace = 1; while (namespace <= 0xffff && occupied.contains(namespace)) namespace++;
            if (namespace > 0xffff) throw new IllegalArgumentException("No namespace available for self hint");
            assumptions.put(hint, namespace);
        }
        for (Function function : functions) {
            if (!declarations.containsKey(function.name())) {
                int target = !hint.isEmpty() && function.id() != 0 && function.id() != 1 ? (assumptions.get(hint) << 16) | (function.id() & 0xffff) : function.id();
                global.abs.put(function.name(), target);
            }
        }
        for (var entry : global.namedSignatures.entrySet()) {
            int id = global.getFunctionId(entry.getKey()); Signature signature = entry.getValue();
            global.arities.put(id, signature.paramTypes().size()); global.functionReturnTypes.put(id, signature.returnType());
            global.scriptSignatures.put(id, signature);
        }
        Map<Integer,Integer> returnAbis=new HashMap<>();global.functionReturnTypes.forEach((id,type)->returnAbis.put(id,abiType(type)));
        global.tables=new FunctionTables(returnAbis,state.positions);
        Set<Integer> scriptIds=new HashSet<>();
        for(Function function:functions){scriptIds.add(function.id());scriptIds.add(global.getFunctionId(function.name()));}
        global.tables.scriptIds=Set.copyOf(scriptIds);global.tables.scopeSafeCalls=scopeSafeCalls(functions,global);
        AcsArray signatures = new AcsArray();
        for (var entry : importIds.entrySet()) {
            Signature signature = entry.getValue(); AcsObject compiled = new AcsObject();compiled.put("id", entry.getKey().intValue());compiled.put("return-type", abiType(signature.returnType()));
            AcsArray params = new AcsArray();for (int parameter : signature.paramTypes()) params.acsa.add(new AcsIntegerElement(abiType(parameter)));
            compiled.put("param-types", params);compiled.put("hidden-count",signature.typeParameters().size());compiled.put("entry-kind",signature.internal()?1:0); signatures.acsa.add(compiled);
        }
        result.put("extern-signatures", signatures);result.put("namespace-hint", hint);AcsArray compiledAssumptions = new AcsArray();
        assumptions.forEach((name, namespace) -> {AcsObject value = new AcsObject();value.put("hint", name);value.put("namespace", namespace.intValue());compiledAssumptions.acsa.add(value);});
        result.put("assume-hints", compiledAssumptions);
        AcsArray output = new AcsArray();
        for (Function function : functions) {
            String context = contextName(function.name());
            try { output.acsa.add(compileFunction(function, global, context)); }
            // A failing body ends only its own function; the next one is checked on its own.
            catch (RuntimeException | StackOverflowError error) { recover(error, function.metadata(), context, global); }
        }
        diagnostics.checkpoint();
        result.put("f",output);normalizeContexts(result);
        Set<OptimizationPass> passes = state.optimizationPasses;
        for (AcsElement item : output.acsa) {
            AcsObject compiled = (AcsObject)item; AcsElement optimized = compiled.mmp.get("script");
            if (passes.contains(OptimizationPass.CONSTANTS)) optimized = ExecOptimizer.constants(optimized, state.positions);
            if (passes.contains(OptimizationPass.PROPAGATION)) {
                optimized = ExecDataflow.optimize(compiled, optimized, state.positions);
                ExecDataflow.removeDeadTemporaries(optimized, state.optimizerTemps.get(compiled.getAsInt("id")));
            }
            compiled.mmp.put("script", optimized);
        }
        if (passes.contains(OptimizationPass.INLINING)) ExecInliner.optimize(output, state.positions);
        for (AcsElement item : output.acsa) {
            AcsObject compiled = (AcsObject)item;
            if (passes.contains(OptimizationPass.PROPAGATION) && passes.contains(OptimizationPass.INLINING))
                compiled.mmp.put("script", ExecDataflow.optimize(compiled, compiled.mmp.get("script"), state.positions));
            if (passes.contains(OptimizationPass.LOOP_MOTION)) ExecLoopMotion.optimize(compiled, state.positions);
            if (!passes.isEmpty()) ExecShape.requireFits(compiled.mmp.get("script"), 4, state.abdLimit);
        }
        ExecO2.optimize(output, passes, state.positions, state.abdLimit);
        if (passes.contains(OptimizationPass.SEQUENCE_INLINING))
            ExecSequenceInliner.optimize(output, state.positions, state.inlineSites, state.abdLimit);
        if (passes.contains(OptimizationPass.SLOT_COMPACTION)) ExecSlotCompaction.optimize(output);
        AcsArray debugFiles = new AcsArray();
        for (String file : state.debugFiles) debugFiles.acsa.add(new AcsStringElement(file));
        result.put("debug-files", debugFiles);
        // Every function compiled, so the outputs are in function order.
        Map<Integer,String> names = new HashMap<>();
        if (state.positions != null) for (int i = 0; i < functions.size(); i++)
            names.put(((AcsObject) output.acsa.get(i)).getAsInt("id"), SourceRules.debugText(debugName(functions.get(i).name())));
        if (state.positions != null) for (int i = 0; i < functions.size(); i++) {
            AcsObject compiled = (AcsObject) output.acsa.get(i);
            compiled.put("debug", debugRecord(functions.get(i), compiled, globals, global, names));
        }
        return result;
    }
    /**
     * The debug record of a compiled function: its display name, the position of its
     * declaration (the class declaration for a synthesized class function, 0:0 when
     * unknown) and the positions of its nodes.
     */
    private static AcsObject debugRecord(Function function, AcsObject compiled, int globals, LogicEnvironment global,
                                         Map<Integer,String> names) {
        CompilationState state = global.state(); JsonObject meta = function.metadata();
        int[] header = state.debugPosition(meta);
        ClassInfo owner = meta.has("owner-class") ? global.classes.get(meta.get("owner-class").getAsString()) : null;
        if (header == null && owner != null) header = state.debugPosition(owner.source);
        if (header == null) header = new int[]{0, 0, 0};
        AcsObject debug = new AcsObject();
        debug.put("name", SourceRules.debugText(debugName(function.name())));
        debug.put("file", header[0]); debug.put("line", header[1]); debug.put("column", header[2]);
        AcsObject located = ExecCodec.debugLocations(compiled, globals, state.positions, state.inlineSites, names);
        debug.mmp.put("locations", located.mmp.get("locations"));
        // exec v11: frames of functions O3 inlined here.
        if (located.mmp.containsKey("inlined")) {
            debug.mmp.put("inlined", located.mmp.get("inlined")); debug.put("display-id", compiled.getAsInt("id"));
        }
        return debug;
    }
    /**
     * The name a stack trace shows for a function: {@code C::C}, {@code C::~C} and
     * {@code C::operator+} for class members, and {@code <new C>}, {@code <scoped C>},
     * {@code <value C>} and {@code <placement C>} for the synthesized factories.
     */
    static String debugName(String function) {
        int separator = function.lastIndexOf("::");
        if (separator >= 0) {
            String owner = function.substring(0, separator), member = function.substring(separator + 2);
            switch (member) {
                case "<new>", "<scoped>", "<value>", "<placement>" -> { return "<" + member.substring(1, member.length() - 1) + " " + owner + ">"; }
                default -> { }
            }
        }
        return contextName(function).substring("function ".length());
    }
    private static final Set<String> SCOPE_SCALAR_CONTROLS=Set.of(
            "var","vardef","varset","mov","return","null","float","neg","pos","not",
            "add","minus","multiply","divide","mod","greater","lower","cmp","ne","ge","le","and","or",
            "bit_and","bit_or","bit_xor","shl","shr","ushr","if","while","for","switch","case","break","continue",
            "increment","decrement","select","chain","if-chain");
    /** Conservative transitive proof: helpers cannot promote allocations into a caller scope. */
    private static Set<Integer> scopeSafeCalls(List<Function> functions,LogicEnvironment global) {
        Map<Integer,Set<Integer>> dependencies=new HashMap<>();
        for(Function function:functions) {
            if(function.internal()||function.metadata().has("owner-class")||!function.typeParameters().isEmpty()
                    ||isClass(function.returnType())||isBuffer(function.returnType())||isTypeParameter(function.returnType()))continue;
            boolean scalar=true;for(int type:function.paramTypes())if(type==AsTypes.ANY_VALUE||isClass(type)||isBuffer(type)||isTypeParameter(type)){scalar=false;break;}
            Set<Integer> called=new HashSet<>();
            if(scalar&&scopeDependencies(function.script(),global,called)) {
                dependencies.put(function.id(),called);dependencies.put(global.getFunctionId(function.name()),called);
            }
        }
        Set<Integer> safe=new HashSet<>(dependencies.keySet());Map<Integer,List<Integer>> callers=new HashMap<>();Deque<Integer> unsafe=new ArrayDeque<>();
        for(var entry:dependencies.entrySet())for(int callee:entry.getValue()) {
            callers.computeIfAbsent(callee,ignored->new ArrayList<>()).add(entry.getKey());
            if(!safe.contains(callee))unsafe.addLast(entry.getKey());
        }
        while(!unsafe.isEmpty()) {
            int id=unsafe.removeFirst();if(!safe.remove(id))continue;
            for(int caller:callers.getOrDefault(id,List.of()))unsafe.addLast(caller);
        }
        for(BuiltinSpec builtin:BUILTINS)if(builtin.id()!=0x0abd0005&&builtin.id()!=0x0abd0007&&builtin.id()!=0x0abd0008)safe.add(builtin.id());
        return Set.copyOf(safe);
    }
    private static LogicVariable scopeVariable(LogicEnvironment environment,String name) {
        for(LogicEnvironment scope=environment;scope!=null;scope=scope.parent){LogicVariable variable=scope.local(name);if(variable!=null)return variable;}
        return null;
    }
    private static boolean scopeDependencies(JsonElement source,LogicEnvironment environment,Set<Integer> dependencies) {
        Deque<JsonElement> pending=new ArrayDeque<>();if(source!=null)pending.push(source);
        while(!pending.isEmpty()) {
            JsonElement value=pending.pop();if(value.isJsonNull()||value.isJsonPrimitive())continue;
            if(value.isJsonArray()){for(JsonElement child:value.getAsJsonArray())pending.push(child);continue;}
            JsonObject node=value.getAsJsonObject();String kind=node.get("t").getAsString(),call=node.get("call").getAsString();
            if(kind.equals("call")) {
                String resolved=resolveCall(call,environment);
                if(!SourceRules.CONVERSIONS.contains(call)||!resolved.equals(call)) {
                    Integer id=environment.root().abs.get(resolved);if(id==null)return false;
                    if(SourceRules.runtimeId(id)) {
                        if(id==0x0abd0005||id==0x0abd0007||id==0x0abd0008)return false;
                    } else if(environment.tables.scriptIds.contains(id))dependencies.add(id);else return false;
                }
            } else if(!kind.equals("ctrl")||!SCOPE_SCALAR_CONTROLS.contains(call))return false;
            if(call.equals("vardef")) {
                if(!node.has("declared-type"))return false;
                String type=node.get("declared-type").getAsString();if(!SourceRules.TYPES.contains(type)||type.equals("buffer"))return false;
            }
            if((call.equals("var")||call.equals("varset"))&&!parameters(node).isEmpty()) {
                LogicVariable variable=scopeVariable(environment,parameters(node).get(0).getAsString());
                if(variable!=null&&variable.type==AsTypes.ANY_VALUE)return false;
            }
            for(JsonElement child:parameters(node))pending.push(child);
            if(call.equals("if-chain")&&node.has("else"))pending.push(node.get("else"));
        }
        return true;
    }
    private static AcsObject compileFunction(Function function, LogicEnvironment global, String context) {
        LogicEnvironment scope = global.child();scope.counter = new Counter();scope.context = context;scope.returnType = function.returnType();
        scope.tables=new FunctionTables(global.tables.returns,global.state().positions);scope.tables.scriptIds=global.tables.scriptIds;scope.tables.scopeSafeCalls=global.tables.scopeSafeCalls;
        for(LogicVariable variable:global.vars)scope.tables.slots.put(variable.slot,abiType(variable.type));
        scope.typeScope=function.typeScope();scope.typeParameters=function.typeParameters();TYPE_SCOPE.set(scope.typeScope);
        if (function.metadata().has("owner-class")) scope.ownerClass = string(function.metadata().get("owner-class"), "Function owner");
        int parameter = 0; AcsArray parameterTypes = new AcsArray();
        for (JsonElement name : function.metadata().getAsJsonArray("param")) {
            if (parameter >= MAX_VARIABLE_SLOTS) throw new IllegalArgumentException("Function frame exceeds variable slot limit");
            int parameterType = function.paramTypes().get(parameter);
            scope.define(string(name,"Parameter name"),parameter++,isFlexibleClass(parameterType) ? pointerOf(parameterType) : parameterType);parameterTypes.acsa.add(new AcsIntegerElement(abiType(parameterType)));
        }
        scope.counter.next = parameter; AcsObject compiled = new AcsObject();compiled.put("id",function.id());compiled.put("return-type",abiType(scope.returnType));
        compiled.put("param-count",parameter);compiled.put("param-types",parameterTypes);compiled.put("hidden-count",scope.typeParameters.size());compiled.put("entry-kind",function.internal()?1:0);
        AcsElement body=compileNode(classFunctionBody(function, global), scope);
        if (function.returnType() != AsTypes.VOID_VALUE && canCompleteNormally(function.script())) {
            // Reported at the closing brace that execution can reach.
            CompilationState state = global.state(); JsonObject meta = function.metadata();
            Diagnostic.Position end = state.sources.position(meta, "_end_line", "_end_column");
            String text = "non-void function can reach the end of its body without returning a value";
            throw new CompileException(end == null ? state.sources.error(meta, context, text)
                    : Diagnostic.error(end, context, text, state.sources.includeNotes(state.sources.unitOf(meta))).inUnit(state.sources.unitOf(meta)));
        }
        scope.tables.nextSlot=scope.counter.next;
        try {
            body=new AbdLinearizer(scope.tables,global.state().abdLimit).function(body);
            ExecShape.requireFits(body,4,global.state().abdLimit);
        } catch(AbdLinearizer.LimitExceeded error) {
            JsonElement anchor=scope.tables.origins.get(error.node);
            if(anchor==null||!anchor.isJsonObject()||global.state().sources.position(anchor.getAsJsonObject())==null)anchor=function.metadata();
            throw relocate(error,anchor,scope);
        }
        scope.counter.next=scope.tables.nextSlot;compiled.put("script",body);
        global.state().optimizerTemps.put(function.id(), Set.copyOf(scope.tables.temporarySlots));
        compiled.put("local-count",scope.counter.next-parameter);return compiled;
    }
}
