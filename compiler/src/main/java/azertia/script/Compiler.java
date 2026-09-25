package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Resolves symbols and lowers the readable JSON AST to numeric-slot ABD instructions. */
public class Compiler {
    private static final ThreadLocal<Integer> EXPRESSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int NULL_TYPE = -2, FIRST_CLASS_TYPE = 100;
    private static final int MAX_VARIABLE_SLOTS = 1_048_576;
    private static final ThreadLocal<Map<String,Integer>> CLASS_TYPES = ThreadLocal.withInitial(LinkedHashMap::new);
    private static final ThreadLocal<Map<Integer,ClassInfo>> CLASS_LAYOUTS = ThreadLocal.withInitial(LinkedHashMap::new);
    public static class AsTypes {
        public static final int INT_VALUE = 0, STRING_VALUE = 1, FLOAT_VALUE = 2,
                DOUBLE_VALUE = 3, BOOLEAN_VALUE = 4, VOID_VALUE = 5, ANY_VALUE = 6, ADDRESS_VALUE = 7;
    }
    private record BuiltinSpec(String name, int id, int returnType, List<Integer> paramTypes) {}
    private static final List<BuiltinSpec> BUILTINS = List.of(
            new BuiltinSpec("print", 0x0abd0000, AsTypes.VOID_VALUE, List.of(AsTypes.ANY_VALUE)),
            new BuiltinSpec("getDepth", 0x0abd0001, AsTypes.INT_VALUE, List.of()),
            new BuiltinSpec("mem_free", 0x0abd0002, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("alloc", 0x0abd0003, AsTypes.ADDRESS_VALUE, List.of(AsTypes.INT_VALUE)),
            new BuiltinSpec("make_free", 0x0abd0004, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_send_up", 0x0abd0005, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_get", 0x0abd0006, AsTypes.ANY_VALUE, List.of(AsTypes.ADDRESS_VALUE)));
    public static boolean isBuiltinName(String name) {
        return BUILTINS.stream().anyMatch(spec -> spec.name().equals(name));
    }
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
    }
    private record Signature(int id, int returnType, List<Integer> paramTypes, boolean requireKnown) {}
    private record Field(String owner, String name, int type, JsonElement initializer, int line, int column) {}
    private static final class ClassInfo {
        String name, constructor, destructor, cleanupDestructor;
        ClassInfo base;
        int type, manualFactory, scopedFactory;
        final List<Field> ownFields = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        final Map<String,Field> visibleFields = new LinkedHashMap<>();
        final Map<String,String> ownMethods = new LinkedHashMap<>();
        final Map<String,String> methods = new LinkedHashMap<>();
        Field field(String name) {
            Field field = visibleFields.get(name);
            if (field == null) throw new IllegalArgumentException("Unknown member: " + this.name + "." + name);
            return field;
        }
    }
    private static class Counter { int next; }
    public static class LogicEnvironment {
        public LogicEnvironment parent;
        public List<LogicVariable> vars = new ArrayList<>();
        public Map<String,Integer> abs = new LinkedHashMap<>();
        private final Map<Integer,Integer> arities = new HashMap<>();
        private final Map<Integer,Integer> functionReturnTypes = new HashMap<>();
        private final Map<Integer,Signature> externSignatures = new LinkedHashMap<>();
        private final Map<Integer,Signature> scriptSignatures = new LinkedHashMap<>();
        private final Map<Integer,Signature> builtinSignatures = new LinkedHashMap<>();
        private final Map<String,Signature> namedSignatures = new LinkedHashMap<>();
        private final Map<String,ClassInfo> classes = new LinkedHashMap<>();
        private final Set<JsonObject> trustedNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        private Counter counter = new Counter();
        private String function = "<expression>";
        private int returnType = -1;
        private int loopDepth;
        private String ownerClass;
        private int moduleNamespace = 0xfff;
        LogicEnvironment child() {
            LogicEnvironment child = new LogicEnvironment(); child.parent = this; child.counter = counter;
            child.function = function; child.returnType = returnType; child.loopDepth = loopDepth; child.ownerClass = ownerClass; return child;
        }
        LogicEnvironment loopChild() {
            LogicEnvironment child = child(); child.loopDepth++; return child;
        }
        LogicEnvironment root() { return parent == null ? this : parent.root(); }
        public int getFunctionId(String name) {
            Integer id = root().abs.get(name);
            if (id != null) return id;
            throw new IllegalArgumentException("Unknown function: " + name);
        }
        public LogicVariable getVariable(String name) {
            for (LogicVariable variable : vars) if (variable.alias.contains(name)) return variable;
            if (parent != null) return parent.getVariable(name);
            throw new IllegalArgumentException("Unknown variable: " + name);
        }
        private LogicVariable findVariable(String name) {
            for (LogicVariable variable : vars) if (variable.alias.contains(name)) return variable;
            return parent == null ? null : parent.findVariable(name);
        }
        private LogicVariable findLexicalVariable(String name) {
            if (parent == null) return null;
            for (LogicVariable variable : vars) if (variable.alias.contains(name)) return variable;
            return parent.findLexicalVariable(name);
        }
        private LogicVariable define(String alias, int slot) {
            return define(alias, slot, AsTypes.ANY_VALUE);
        }
        private LogicVariable define(String alias, int slot, int type) {
            if (alias.isBlank()) throw new IllegalArgumentException("Variable name cannot be empty");
            for (LogicVariable variable : vars) if (variable.alias.contains(alias))
                throw new IllegalArgumentException("Duplicate variable in the same scope: " + alias);
            LogicVariable variable = new LogicVariable(); variable.name = alias; variable.slot = slot; variable.type = type;
            variable.alias.add(alias);
            vars.add(variable); return variable;
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
    private static AcsElement number(JsonElement element) {
        BigDecimal value = element.getAsBigDecimal();
        if (element.getAsString().matches("[+-]?[0-9]+")) {
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
            return value.getAsString().matches("[+-]?[0-9]+") ? AsTypes.INT_VALUE : AsTypes.DOUBLE_VALUE;
        return AsTypes.ANY_VALUE;
    }
    private static int expressionType(JsonElement input, LogicEnvironment environment) {
        if (input == null || input.isJsonNull()) return AsTypes.VOID_VALUE;
        if (input.isJsonPrimitive()) return primitiveType(input.getAsJsonPrimitive());
        if (input.isJsonArray()) return AsTypes.VOID_VALUE;
        if (!input.isJsonObject()) return AsTypes.ANY_VALUE;
        JsonObject object = input.getAsJsonObject();
        if (!object.has("t") || !object.has("call")) return AsTypes.ANY_VALUE;
        String kind = string(object.get("t"), "Instruction t");
        String operation = string(object.get("call"), "Instruction call");
        JsonArray params = parameters(object);
        if (kind.equals("call")) {
            String name = resolveCall(operation, environment); Signature signature = environment.root().namedSignatures.get(name);
            int id = environment.getFunctionId(name);
            return signature != null ? signature.returnType() : environment.root().functionReturnTypes.getOrDefault(id, AsTypes.ANY_VALUE);
        }
        if (!kind.equals("ctrl")) return AsTypes.ANY_VALUE;
        return switch (operation) {
            case "var" -> params.size() == 1 ? variableType(string(params.get(0), "Variable name"), environment) : AsTypes.ANY_VALUE;
            case "null" -> NULL_TYPE;
            case "object-new" -> classInfo(string(params.get(0), "Class name"), environment).type;
            case "member" -> memberField(params, environment).type();
            case "member-set" -> params.size() == 2 ? expressionType(params.get(1), environment) : AsTypes.ANY_VALUE;
            case "member-call" -> {
                ClassInfo info = receiverClass(params.get(0), environment);
                String method = string(params.get(1), "Method name");
                String qualified = info.methods.get(method);
                if (qualified == null) throw new IllegalArgumentException("Unknown method: " + info.name + "." + method);
                yield environment.root().namedSignatures.get(qualified).returnType();
            }
            case "#allocate" -> {
                requireTrusted(object, environment);
                yield classInfo(string(params.get(0), "Class name"), environment).type;
            }
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
            case "mod" -> params.size() == 2
                    && expressionType(params.get(0), environment) == AsTypes.INT_VALUE
                    && expressionType(params.get(1), environment) == AsTypes.INT_VALUE
                    ? AsTypes.INT_VALUE : AsTypes.ANY_VALUE;
            case "if" -> branchType(params, environment);
            case "vardef", "return", "break", "continue", "while", "for", "increment", "decrement", "object-def", "object-delete", "#bind", "#make-free" -> AsTypes.VOID_VALUE;
            default -> AsTypes.ANY_VALUE;
        };
    }
    private static int branchType(JsonArray params, LogicEnvironment environment) {
        if (params.size() != 3) return AsTypes.VOID_VALUE;
        int first = expressionType(params.get(1), environment);
        int second = expressionType(params.get(2), environment);
        return equivalent(first, second) ? first : AsTypes.ANY_VALUE;
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
    private static boolean returnCompatible(int expected, int actual) {
        return assignable(expected, actual) || numeric(expected) && numeric(actual);
    }
    /** A base reference addresses the same prefix; no pointer adjustment or runtime type is needed. */
    private static boolean assignable(int expected, int actual) {
        if (equivalent(expected, actual) || (isClass(expected) || expected == AsTypes.ADDRESS_VALUE) && actual == NULL_TYPE) return true;
        ClassInfo derived = CLASS_LAYOUTS.get().get(actual);
        for (ClassInfo base = derived == null ? null : derived.base; base != null; base = base.base)
            if (equivalent(expected, base.type)) return true;
        return false;
    }
    private static boolean equivalent(int first, int second) {
        ArrayDeque<Long> pending = new ArrayDeque<>(); Set<Long> compared = new HashSet<>();
        pending.add(((long)first << 32) | (second & 0xffffffffL));
        while (!pending.isEmpty()) {
            long pair = pending.removeLast(); int leftType = (int)(pair >> 32), rightType = (int)pair;
            if (leftType == rightType) continue;
            if (!isClass(leftType) || !isClass(rightType)) return false;
            if (!compared.add(pair)) continue;
            ClassInfo left = CLASS_LAYOUTS.get().get(leftType), right = CLASS_LAYOUTS.get().get(rightType);
            if (left == null || right == null || left.fields.size() != right.fields.size()) return false;
            for (int i = 0; i < left.fields.size(); i++)
                pending.add(((long)left.fields.get(i).type() << 32) | (right.fields.get(i).type() & 0xffffffffL));
        }
        return true;
    }
    private static boolean conditionType(int type) {
        return type == AsTypes.BOOLEAN_VALUE || numeric(type);
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
            default -> CLASS_TYPES.get().entrySet().stream().filter(entry -> entry.getValue() == type)
                    .map(Map.Entry::getKey).findFirst().orElse("unknown(" + type + ")");
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
    private static void requireConditionOrUnknown(String context, JsonElement value,
                                                  LogicEnvironment environment) {
        int type = expressionType(value, environment);
        if (type != AsTypes.ANY_VALUE && !conditionType(type))
            throw new IllegalArgumentException(
                    context + " must be boolean or numeric, got " + typeName(type));
    }
    private static void requireAddableOrUnknown(JsonElement first, JsonElement second,
                                                LogicEnvironment environment) {
        int firstType = expressionType(first, environment);
        int secondType = expressionType(second, environment);
        if (isClass(firstType) || isClass(secondType) || firstType == NULL_TYPE || secondType == NULL_TYPE)
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
        int depth = EXPRESSION_DEPTH.get();
        if (depth + levels + TreeLimits.ABD_LEVELS_ABOVE_FUNCTION_BODY > TreeLimits.MAX_ABD_NESTING)
            throw new IllegalArgumentException("Expression nesting exceeds the ABD limit of "
                    + TreeLimits.MAX_ABD_NESTING + " levels; split the expression or statement");
        EXPRESSION_DEPTH.set(depth + levels);
        try { return action.get(); }
        finally { if (depth == 0) EXPRESSION_DEPTH.remove(); else EXPRESSION_DEPTH.set(depth); }
    }
    public static AcsElement compileExpression(JsonElement input, LogicEnvironment environment) {
        try { return withDepth(1, () -> compileExpressionBody(input, environment)); }
        catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("In function ")) throw error;
            JsonObject object = input != null && input.isJsonObject() ? input.getAsJsonObject() : null;
            if (object == null || !object.has("_line")) throw error;
            String location = ":" + object.get("_line").getAsInt() + ":" + object.get("_column").getAsInt();
            throw new IllegalArgumentException("In function " + environment.function + location + ": " + error.getMessage(), error);
        }
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
            withDepth(1, () -> {
                for (JsonElement statement : input.getAsJsonArray()) block.acsa.add(compileExpression(statement, scope));
                return null;
            });
            return block;
        }
        if (!input.isJsonObject()) throw new IllegalArgumentException("Invalid expression");
        JsonObject object = input.getAsJsonObject();
        try { return compileInstruction(object, environment); }
        catch (IllegalArgumentException e) {
            String location = object.has("_line") ? ":" + object.get("_line").getAsInt() + ":" + object.get("_column").getAsInt() : "";
            if (e.getMessage() != null && e.getMessage().startsWith("In function ")) throw e;
            throw new IllegalArgumentException("In function " + environment.function + location + ": " + e.getMessage(), e);
        }
    }
    private static AcsElement compileInstruction(JsonObject source, LogicEnvironment environment) {
        String type = string(source.get("t"), "Instruction t"), call = string(source.get("call"), "Instruction call");
        JsonArray params = parameters(source); AcsObject result = new AcsObject();
        if (type.equals("call")) {
            String resolved = resolveCall(call, environment);
            if (!resolved.equals(call)) {
                JsonArray withThis = new JsonArray(); withThis.add(variable("this"));
                params.forEach(withThis::add); params = withThis; call = resolved;
            }
            int id = environment.getFunctionId(call);
            // Diagnostics use source terms: factories are constructors, and the
            // implicit receiver of a class function is not a user argument.
            String label = displayName(call);
            int hidden = call.contains("::") && !isFactory(call) ? 1 : 0;
            Signature signature = environment.root().namedSignatures.get(call);
            if (signature == null) signature = environment.root().externSignatures.get(id);
            if (signature == null) signature = environment.root().scriptSignatures.get(id);
            if (signature == null) signature = environment.root().builtinSignatures.get(id);
            Integer expected = signature != null ? Integer.valueOf(signature.paramTypes().size()) : environment.root().arities.get(id);
            if (expected != null && params.size() != expected)
                throw new IllegalArgumentException(label + " expects " + (expected - hidden)
                        + " arguments, got " + (params.size() - hidden));
            if (signature != null) {
                for (int i = 0; i < params.size(); i++) {
                    int expectedType = signature.paramTypes().get(i);
                    if (expectedType != AsTypes.ANY_VALUE) {
                        int actual = expressionType(params.get(i), environment);
                        if (actual != AsTypes.ANY_VALUE || signature.requireKnown())
                            requireKnownExact(label + " argument " + (i + 1 - hidden), expectedType, actual);
                    }
                }
            }
            result.put("t", 1); result.put("id", id);
            AcsArray arguments = new AcsArray();
            for (JsonElement parameter : params) {
                rejectVoidValue(label + " argument", parameter, environment);
                // The "param" array adds one ABD level around each argument.
                arguments.acsa.add(withDepth(1, () -> compileExpression(parameter, environment)));
            }
            result.put("param", arguments); return result;
        }
        if (!type.equals("ctrl")) throw new IllegalArgumentException("Unknown instruction type: " + type);
        result.put("t", 0);
        switch (call) {
            case "var" -> {
                arity(call, params, 1, 1); result.put("c", ExecOpcodes.code("v"));
                String alias = string(params.get(0), "Variable name");
                if (alias.equals("this") && environment.ownerClass == null) throw new IllegalArgumentException("this is only available inside a class");
                if (environment.findLexicalVariable(alias) == null && implicitField(alias, environment) != null)
                    return compileInstruction(node("member", variable("this"), new JsonPrimitive(alias)), environment);
                result.put("v", environment.getVariable(alias).slot);
            }
            case "vardef" -> {
                arity(call, params, 1, 2); String alias = string(params.get(0), "Variable name");
                if (alias.equals("this") && !environment.root().trustedNodes.contains(source)) throw new IllegalArgumentException("Cannot declare this");
                int declaredType = source.has("declared-type")
                        ? valueType(string(source.get("declared-type"), "Declared variable type"), true)
                        : AsTypes.ANY_VALUE;
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
                    initializer = compileExpression(params.get(1), environment);
                }
                if (environment.counter.next >= MAX_VARIABLE_SLOTS)
                    throw new IllegalArgumentException("Function frame exceeds " + MAX_VARIABLE_SLOTS + " variable slots");
                LogicVariable variable = environment.define(
                        alias, environment.counter.next++, declaredType);
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
                    return compileInstruction(node("member-set", node("member", variable("this"), new JsonPrimitive(alias)), params.get(1)), environment);
                }
                LogicVariable variable = environment.getVariable(alias);
                rejectVoidValue("Assignment to " + alias, params.get(1), environment);
                if (variable.type != AsTypes.ANY_VALUE)
                    requireKnownExact("Assignment to " + alias, variable.type,
                            expressionType(params.get(1), environment));
                result.put("v", variable.slot);
                result.put("val", compileExpression(params.get(1), environment));
            }
            case "mov" -> {
                arity(call, params, 2, 2);
                if (isThisVariable(params.get(0))) throw new IllegalArgumentException("Cannot assign to this");
                if (params.get(0).isJsonObject() && isControl(params.get(0).getAsJsonObject())) {
                    JsonObject target = params.get(0).getAsJsonObject(); String operation = target.get("call").getAsString();
                    if (operation.equals("member")) return compileInstruction(node("member-set", target, params.get(1)), environment);
                    if (operation.equals("var")) {
                        arity("var", parameters(target), 1, 1);
                        return compileInstruction(node("varset", parameters(target).get(0), params.get(1)), environment);
                    }
                }
                AcsElement left = compileExpression(params.get(0), environment);
                boolean variable = left instanceof AcsObject target && target.getAsInt("t") == 0
                        && target.getAsInt("c") == ExecOpcodes.VARIABLE;
                boolean memory = left instanceof AcsObject target && target.getAsInt("t") == 1
                        && target.getAsInt("id") == 0x0abd0006;
                if (!variable && !memory)
                    throw new IllegalArgumentException("Assignment target must be a variable or mem_get(pointer)");
                if (memory) arity("mem_get", params.get(0).getAsJsonObject().getAsJsonArray("param"), 1, 1);
                rejectVoidValue("Assignment", params.get(1), environment);
                result.put("c", ExecOpcodes.code("m")); result.put("v1", left);
                result.put("v2", compileExpression(params.get(1), environment));
            }
            case "return" -> {
                arity(call, params, 0, 1);
                if (environment.returnType == AsTypes.VOID_VALUE && !params.isEmpty())
                    throw new IllegalArgumentException("void function cannot return a value");
                if (environment.returnType >= 0 && environment.returnType != AsTypes.VOID_VALUE && params.isEmpty())
                    throw new IllegalArgumentException("Non-void function must return a value");
                result.put("c", ExecOpcodes.code(isClass(environment.returnType) ? "ro" : "r"));
                if (!params.isEmpty()) {
                    int actual = expressionType(params.get(0), environment);
                    if (isClass(environment.returnType)) requireKnownExact("return", environment.returnType, actual);
                    if (actual == AsTypes.VOID_VALUE)
                        throw new IllegalArgumentException("return cannot use a void value");
                    if (environment.returnType >= 0 && actual != AsTypes.ANY_VALUE
                            && !returnCompatible(environment.returnType, actual))
                        throw new IllegalArgumentException("return expected " + typeName(environment.returnType)
                                + ", got " + typeName(actual));
                    result.put("r", compileExpression(params.get(0), environment));
                }
            }
            case "null" -> { arity(call, params, 0, 0); return withDepth(1, () -> new AcsAddress(0L)); }
            case "member" -> {
                arity(call, params, 2, 2); Field field = memberField(params, environment);
                ClassInfo info = receiverClass(params.get(0), environment);
                AcsObject address = new AcsObject(); address.put("t", 0); address.put("c", ExecOpcodes.code("oa"));
                JsonElement receiver = params.get(0);
                address.put("v", withDepth(2, () -> compileExpression(receiver, environment))); address.put("offset", info.fields.indexOf(field));
                result.put("t", 1); result.put("id", 0x0abd0006);
                AcsArray args = new AcsArray(); args.acsa.add(address); result.put("param", args);
            }
            case "member-set" -> {
                arity(call, params, 2, 2);
                JsonObject member = params.get(0).getAsJsonObject();
                if (!isControl(member) || !string(member.get("call"), "Member target").equals("member"))
                    throw new IllegalArgumentException("Member assignment needs a field target");
                Field field = memberField(parameters(member), environment);
                requireKnownExact("Assignment to member " + field.name(), field.type(), expressionType(params.get(1), environment));
                result.put("c", ExecOpcodes.code("m")); result.put("v1", compileExpression(member, environment));
                result.put("v2", compileExpression(params.get(1), environment));
            }
            case "member-call" -> {
                arity(call, params, 2, Integer.MAX_VALUE);
                ClassInfo info = receiverClass(params.get(0), environment);
                String member = string(params.get(1), "Method name"), method = info.methods.get(member);
                if (method == null) throw new IllegalArgumentException("Unknown method: " + info.name + "." + member);
                JsonArray args = new JsonArray(); args.add(params.get(0));
                for (int i = 2; i < params.size(); i++) args.add(params.get(i));
                return compileInstruction(callNode(method, args), environment);
            }
            case "object-new" -> {
                arity(call, params, 1, Integer.MAX_VALUE);
                ClassInfo info = classInfo(string(params.get(0), "Class name"), environment);
                JsonArray args = new JsonArray(); for (int i = 1; i < params.size(); i++) args.add(params.get(i));
                return compileInstruction(callNode(factoryName(info, true), args), environment);
            }
            case "object-def" -> {
                arity(call, params, 2, Integer.MAX_VALUE);
                String alias = string(params.get(0), "Variable name");
                ClassInfo info = classInfo(string(params.get(1), "Class name"), environment);
                JsonArray args = new JsonArray(); for (int i = 2; i < params.size(); i++) args.add(params.get(i));
                JsonObject construct = callNode(factoryName(info, false), args);
                // The generated call is compiled as a nested expression; keep the declaration's location.
                if (source.has("_line") && source.has("_column")) {
                    construct.add("_line", source.get("_line")); construct.add("_column", source.get("_column"));
                }
                JsonObject definition = node("vardef", new JsonPrimitive(alias), construct);
                definition.addProperty("declared-type", info.name);
                return compileInstruction(definition, environment);
            }
            case "object-delete" -> {
                arity(call, params, 1, 1); int objectType = expressionType(params.get(0), environment);
                if (!isClass(objectType) && objectType != NULL_TYPE) throw new IllegalArgumentException("delete requires a known class reference or null");
                result.put("c", ExecOpcodes.code("od")); result.put("v", compileExpression(params.get(0), environment));
            }
            case "#allocate" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                ClassInfo info = classInfo(string(params.get(0), "Class name"), environment);
                // Compiler-generated allocation must bypass the class's user method names.
                result.put("t", 1); result.put("id", 0x0abd0003);
                AcsArray args = new AcsArray();
                args.acsa.add(withDepth(1, () -> compileExpression(new JsonPrimitive(info.fields.size()), environment)));
                result.put("param", args);
            }
            case "#bind" -> {
                requireTrusted(source, environment); arity(call, params, 3, 3);
                result.put("c", ExecOpcodes.code("ob")); result.put("v", compileExpression(params.get(0), environment));
                if (!params.get(1).isJsonNull())
                    result.put("destructor", environment.getFunctionId(string(params.get(1), "Destructor name")));
                result.put("manual", params.get(2).getAsBoolean());
            }
            case "#make-free" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("t", 1); result.put("id", 0x0abd0004);
                JsonElement pointer = params.get(0);
                AcsArray args = new AcsArray(); args.acsa.add(withDepth(1, () -> compileExpression(pointer, environment))); result.put("param", args);
            }
            case "#check-this" -> {
                requireTrusted(source, environment); arity(call, params, 0, 0);
                result.put("c", ExecOpcodes.code("oa")); result.put("v", compileExpression(variable("this"), environment)); result.put("offset", 0);
            }
            case "#field-initialize" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                // Initializers belong to the implementing class/module. Constructor parameters and
                // body locals cannot shadow their names; runtime allocations still belong to the body.
                LogicEnvironment initialization = environment.root().child();
                initialization.ownerClass = environment.ownerClass;
                initialization.function = environment.ownerClass + " initialization";
                initialization.returnType = AsTypes.VOID_VALUE; initialization.counter = environment.counter;
                initialization.define("this", 0, classInfo(environment.ownerClass, environment).type);
                try { return compileInstruction(params.get(0).getAsJsonObject(), initialization); }
                catch (IllegalArgumentException error) {
                    if (error.getMessage() != null && error.getMessage().startsWith("In function ")) throw error;
                    String location = source.has("_line") ? ":" + source.get("_line").getAsInt() + ":" + source.get("_column").getAsInt() : "";
                    throw new IllegalArgumentException("In function " + initialization.function + location + ": " + error.getMessage(), error);
                }
            }
            case "#cleanup" -> {
                requireTrusted(source, environment); arity(call, params, 3, 3);
                result.put("c", ExecOpcodes.code("cleanup"));
                result.put("v", compileExpression(params.get(0), environment));
                result.put("val", compileExpression(params.get(1), environment));
                result.put("on-error", params.get(2).getAsBoolean());
            }
            case "add", "minus", "multiply", "divide", "mod", "greater", "lower", "cmp", "ne", "ge", "le", "and", "or" -> {
                arity(call, params, 2, 2);
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
                    default -> {
                        int left = expressionType(params.get(0), environment), right = expressionType(params.get(1), environment);
                        if ((isClass(left) || isClass(right) || left == NULL_TYPE || right == NULL_TYPE)
                                && !(left == NULL_TYPE && right == AsTypes.ANY_VALUE || right == NULL_TYPE && left == AsTypes.ANY_VALUE)
                                && !assignable(left, right) && !assignable(right, left))
                            throw new IllegalArgumentException("Object equality requires compatible class references or null");
                        if ((left == AsTypes.ADDRESS_VALUE || right == AsTypes.ADDRESS_VALUE)
                                && left != AsTypes.ANY_VALUE && right != AsTypes.ANY_VALUE
                                && !assignable(left, right) && !assignable(right, left))
                            throw new IllegalArgumentException("Address equality requires an address or null");
                    }
                }
                result.put("c", ExecOpcodes.code(switch (call) { case "greater" -> "gt"; case "lower" -> "lt"; case "cmp" -> "eq"; default -> call; }));
                result.put("v1", compileExpression(params.get(0), environment));
                result.put("v2", compileExpression(params.get(1), environment));
            }
            case "neg", "pos", "not" -> {
                arity(call, params, 1, 1);
                rejectVoidValue(call + " operand", params.get(0), environment);
                if (call.equals("neg") || call.equals("pos")) requireNumericOrUnknown(call + " operand", params.get(0), environment);
                else requireConditionOrUnknown("not operand", params.get(0), environment);
                if (call.equals("pos")) {
                    // A dynamic value still needs the same numeric check as a known operand.
                    // Multiplication by int one preserves numeric type, signed zero and evaluation count.
                    if (expressionType(params.get(0), environment) == AsTypes.ANY_VALUE)
                        return compileInstruction(node("multiply", params.get(0), new JsonPrimitive(1)), environment);
                    return compileExpression(params.get(0), environment);
                }
                result.put("c", ExecOpcodes.code(call)); result.put("v", compileExpression(params.get(0), environment));
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
                arity(call, params, 0, 0);
                if (environment.loopDepth == 0) throw new IllegalArgumentException(call + " can only be used inside a loop");
                result.put("c", ExecOpcodes.code(call.equals("break") ? "brk" : "cont"));
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
                assignment.addProperty("compound", true);
                return compileExpression(assignment, environment);
            }
            case "for" -> {
                arity(call, params, 4, 4);
                // Keep the initializer in a dedicated enclosing scope. Every next
                // iteration runs the step before testing the condition; continue
                // reaches that path after the previous body's scope has cleaned up.
                String first = "#for-first";
                JsonObject firstDefinition = node("vardef", new JsonPrimitive(first), new JsonPrimitive(true));
                firstDefinition.addProperty("declared-type", "boolean");
                JsonArray iteration = array(
                        node("if", variable(first), node("varset", new JsonPrimitive(first), new JsonPrimitive(false)), params.get(2)),
                        node("if", node("not", params.get(1)), node("break")),
                        array(params.get(3)));
                return compileExpression(array(params.get(0), firstDefinition,
                        node("while", new JsonPrimitive(true), iteration)), environment);
            }
            case "if", "while" -> {
                arity(call, params, 2, call.equals("if") ? 3 : 2);
                rejectVoidValue(call + " condition", params.get(0), environment);
                requireConditionOrUnknown(call + " condition", params.get(0), environment);
                result.put("c", ExecOpcodes.code(call.equals("if") ? "if" : "wi"));
                result.put("v", compileExpression(params.get(0), environment));
                LogicEnvironment body = call.equals("while") ? environment.loopChild() : environment.child();
                result.put("val", compileExpression(params.get(1), body));
                if (params.size() == 3) result.put("else", compileExpression(params.get(2), environment.child()));
            }
            default -> throw new IllegalArgumentException("Unknown control operation: " + call);
        }
        return result;
    }
    private static int valueType(String type, boolean valueOnly) {
        int result = switch (type) {
            case "int" -> AsTypes.INT_VALUE; case "string" -> AsTypes.STRING_VALUE;
            case "float" -> AsTypes.FLOAT_VALUE; case "double" -> AsTypes.DOUBLE_VALUE;
            case "boolean", "bool" -> AsTypes.BOOLEAN_VALUE; case "void" -> AsTypes.VOID_VALUE;
            case "any" -> AsTypes.ANY_VALUE;
            case "address" -> AsTypes.ADDRESS_VALUE;
            default -> {
                Integer named = CLASS_TYPES.get().get(type);
                if (named == null) throw new IllegalArgumentException("Unknown type: " + type);
                yield named;
            }
        };
        if (valueOnly && (result == AsTypes.VOID_VALUE || result == AsTypes.ANY_VALUE))
            throw new IllegalArgumentException(type + " is not a concrete value type");
        return result;
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
            int type = valueType(string(values.get(i), context + " parameter type " + (i + 1)), false);
            if (type == AsTypes.VOID_VALUE || !allowAny && type == AsTypes.ANY_VALUE)
                throw new IllegalArgumentException(context + " parameter " + (i + 1)
                        + " must have a concrete non-void type");
            result.add(type);
        }
        return List.copyOf(result);
    }
    private record Function(int id, String name, int returnType, List<Integer> paramTypes,
                            JsonObject metadata, JsonElement script) {}
    private static boolean isClass(int type) { return type >= FIRST_CLASS_TYPE; }
    private static int abiType(int type) { return isClass(type) ? AsTypes.ADDRESS_VALUE : type; }
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
        ClassInfo result = environment.root().classes.get(name);
        if (result == null) throw new IllegalArgumentException("Unknown class: " + name);
        return result;
    }
    private static ClassInfo receiverClass(JsonElement receiver, LogicEnvironment environment) {
        int type = expressionType(receiver, environment);
        return environment.root().classes.values().stream().filter(info -> info.type == type).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Member access requires a known class type, got " + typeName(type)));
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
    private static String factoryName(ClassInfo info, boolean manual) { return info.name + (manual ? "::<new>" : "::<scoped>"); }
    private static boolean isFactory(String function) { return function.endsWith("::<new>") || function.endsWith("::<scoped>"); }
    private static String displayName(String function) {
        return isFactory(function) ? function.substring(0, function.lastIndexOf("::")) + " constructor" : function;
    }
    private static void validateSourceName(String name, String description) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) throw new IllegalArgumentException("Invalid " + description + ": " + name);
        for (int i = 1; i < name.length(); i++)
            if (!Character.isJavaIdentifierPart(name.charAt(i))) throw new IllegalArgumentException("Invalid " + description + ": " + name);
        if (Set.of("class", "new", "delete", "this", "null", "return", "if", "else", "while", "for", "break", "continue", "public", "private", "protected", "virtual", "var", "def",
                "int", "float", "double", "string", "boolean", "bool", "void", "any", "address", "true", "false", "extern").contains(name))
            throw new IllegalArgumentException("Reserved " + description + ": " + name);
    }
    private static void readClasses(JsonObject source, LogicEnvironment environment) {
        if (!source.has("classes")) return;
        JsonArray definitions = source.getAsJsonArray("classes");
        for (JsonElement element : definitions) {
            JsonObject definition = element.getAsJsonObject(); ClassInfo info = new ClassInfo();
            info.name = string(definition.get("name"), "Class name"); validateSourceName(info.name, "class name");
            if (isBuiltinName(info.name) || environment.classes.containsKey(info.name)) throw new IllegalArgumentException("Duplicate or reserved class: " + info.name);
            info.type = FIRST_CLASS_TYPE + environment.classes.size(); environment.classes.put(info.name, info);
            CLASS_TYPES.get().put(info.name, info.type);
        }
        for (JsonElement element : definitions) {
            JsonObject definition = element.getAsJsonObject(); ClassInfo info = environment.classes.get(definition.get("name").getAsString());
            if (definition.has("base")) {
                String name = string(definition.get("base"), "Base class");
                info.base = environment.classes.get(name);
                if (info.base == null) throw new IllegalArgumentException("Unknown base class " + name + " for " + info.name);
            }
            Set<String> names = new HashSet<>();
            for (JsonElement item : definition.getAsJsonArray("fields")) {
                JsonObject field = item.getAsJsonObject(); String name = string(field.get("name"), "Field name"); validateSourceName(name, "field name");
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate member: " + info.name + "." + name);
                int type = valueType(string(field.get("type"), "Field type"), true);
                int line = field.has("_line") ? field.get("_line").getAsInt() : 0;
                int column = field.has("_column") ? field.get("_column").getAsInt() : 0;
                info.ownFields.add(new Field(info.name, name, type, field.get("initializer"), line, column));
            }
            info.constructor = string(definition.get("constructor"), "Constructor function");
            if (!info.constructor.equals(info.name + "::<ctor>")) throw new IllegalArgumentException("Invalid constructor mapping for " + info.name);
            if (definition.has("destructor")) {
                info.destructor = string(definition.get("destructor"), "Destructor function");
                if (!info.destructor.equals(info.name + "::<dtor>")) throw new IllegalArgumentException("Invalid destructor mapping for " + info.name);
            }
            for (var method : definition.getAsJsonObject("methods").entrySet()) {
                validateSourceName(method.getKey(), "method name");
                if (!names.add(method.getKey())) throw new IllegalArgumentException("Duplicate member: " + info.name + "." + method.getKey());
                String qualified = string(method.getValue(), "Method function");
                if (!qualified.equals(info.name + "::" + method.getKey())) throw new IllegalArgumentException("Invalid method mapping for " + info.name);
                info.ownMethods.put(method.getKey(), qualified);
            }
            CLASS_LAYOUTS.get().put(info.type, info);
        }
        Set<ClassInfo> complete = new HashSet<>();
        for (ClassInfo leaf : environment.classes.values()) {
            List<ClassInfo> chain = new ArrayList<>(); Set<ClassInfo> visiting = new HashSet<>();
            for (ClassInfo info = leaf; info != null && !complete.contains(info); info = info.base) {
                if (!visiting.add(info)) throw new IllegalArgumentException("Inheritance cycle involving " + info.name);
                chain.add(info);
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                ClassInfo info = chain.get(i);
                if (info.base != null) {
                    info.fields.addAll(info.base.fields);
                    info.visibleFields.putAll(info.base.visibleFields);
                    info.methods.putAll(info.base.methods);
                }
                for (Field field : info.ownFields) {
                    info.fields.add(field); info.visibleFields.put(field.name(), field); info.methods.remove(field.name());
                }
                info.ownMethods.forEach((name, method) -> { info.methods.put(name, method); info.visibleFields.remove(name); });
                if (info.fields.isEmpty()) throw new IllegalArgumentException("Class " + info.name + " must declare at least one field");
                if (info.fields.size() > MAX_VARIABLE_SLOTS) throw new IllegalArgumentException("Class " + info.name + " exceeds the object slot limit");
                info.cleanupDestructor = info.destructor != null ? info.destructor : info.base == null ? null : info.base.cleanupDestructor;
                complete.add(info);
            }
        }
    }
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
        for (ClassInfo info : global.classes.values()) {
            validateClassFunction(byName, info, info.constructor, "constructor"); declaredClassFunctions.add(info.constructor);
            if (info.destructor != null) { validateClassFunction(byName, info, info.destructor, "destructor"); declaredClassFunctions.add(info.destructor); }
            for (String method : info.ownMethods.values()) { validateClassFunction(byName, info, method, "method"); declaredClassFunctions.add(method); }
        }
        for (Function function : functions) {
            boolean member = function.metadata().has("owner-class") || function.metadata().has("function-kind") || function.name().contains("::");
            if (member && !declaredClassFunctions.contains(function.name())) throw new IllegalArgumentException("Unregistered class function: " + function.name());
            if (member) {
                ClassInfo owner = global.classes.get(function.metadata().get("owner-class").getAsString());
                String kind = function.name().equals(owner.constructor) ? "constructor" : function.name().equals(owner.destructor) ? "destructor" : "method";
                validateClassFunction(Map.of(function.name(), function), owner, function.name(), kind);
            }
        }
        for (Function declaration : declarations.values()) {
            boolean member = declaration.metadata().has("owner-class") || declaration.metadata().has("function-kind") || declaration.name().contains("::");
            if (member && !declaredClassFunctions.contains(declaration.name()))
                throw new IllegalArgumentException("Unregistered class extern: " + declaration.name());
        }
        Set<Integer> occupied = new HashSet<>(ids); occupied.addAll(global.abs.values()); occupied.addAll(GeneraterJson.specialNames.values());
        int position = 1, namespace = global.moduleNamespace << 16;
        for (ClassInfo info : global.classes.values()) {
            Function constructor = byName.get(info.constructor);
            for (boolean manual : new boolean[]{false, true}) {
                while (position <= 0xffff && occupied.contains(namespace | position)) position++;
                if (position > 0xffff) throw new IllegalArgumentException("No free function ID for class factories");
                int id = namespace | position++; ids.add(id); occupied.add(id);
                String name = factoryName(info, manual);
                if (global.abs.containsKey(name)) throw new IllegalArgumentException("Reserved compiler function: " + name);
                global.abs.put(name, id); if (manual) info.manualFactory = id; else info.scopedFactory = id;
                List<Integer> types = List.copyOf(constructor.paramTypes().subList(1, constructor.paramTypes().size()));
                JsonObject metadata = new JsonObject(); metadata.addProperty("owner-class", info.name);
                JsonArray names = new JsonArray(), ctorArgs = new JsonArray(); ctorArgs.add(variable("this"));
                for (int i = 0; i < types.size(); i++) { String argument = "<argument:" + i + ">"; names.add(argument); ctorArgs.add(variable(argument)); }
                metadata.add("param", names);
                JsonArray body = new JsonArray();
                JsonObject local = node("vardef", new JsonPrimitive("this"), internal(global, "#allocate", new JsonPrimitive(info.name)));
                local.addProperty("declared-type", info.name); global.trustedNodes.add(local); body.add(local);
                body.add(callNode(info.constructor, ctorArgs));
                body.add(internal(global, "#bind", variable("this"), info.cleanupDestructor == null ? JsonNull.INSTANCE : new JsonPrimitive(info.cleanupDestructor), new JsonPrimitive(manual)));
                if (manual) body.add(internal(global, "#make-free", variable("this")));
                body.add(node("return", variable("this")));
                functions.add(new Function(id, name, info.type, types, metadata, body));
                global.arities.put(id, types.size()); global.functionReturnTypes.put(id, info.type);
                global.scriptSignatures.put(id, new Signature(id, info.type, types, true));
                global.namedSignatures.put(name, new Signature(id, info.type, types, true));
            }
        }
    }
    private static void validateClassFunction(Map<String,Function> functions, ClassInfo owner, String name, String kind) {
        Function function = functions.get(name);
        if (function == null) throw new IllegalArgumentException("Missing class function: " + name);
        JsonObject metadata = function.metadata();
        if (!owner.name.equals(string(metadata.get("owner-class"), "Function owner"))
                || !kind.equals(string(metadata.get("function-kind"), "Class function kind"))
                || function.paramTypes().isEmpty() || function.paramTypes().get(0) != owner.type
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
        ClassInfo info = classInfo(string(metadata.get("owner-class"), "Function owner"), global);
        JsonArray guarded = new JsonArray(); guarded.add(internal(global, "#check-this"));
        if (kind.equals("constructor")) {
            for (Field field : info.ownFields) guarded.add(internal(global, "#field-initialize",
                    node("member-set", node("member", variable("this"), new JsonPrimitive(field.name())), defaultValue(field.type()))));
            for (Field field : info.ownFields) if (field.initializer() != null) {
                JsonObject assignment = node("member-set", node("member", variable("this"), new JsonPrimitive(field.name())), field.initializer());
                if (field.line() > 0) { assignment.addProperty("_line", field.line()); assignment.addProperty("_column", field.column()); }
                JsonObject initializer = internal(global, "#field-initialize", assignment);
                if (field.line() > 0) { initializer.addProperty("_line", field.line()); initializer.addProperty("_column", field.column()); }
                guarded.add(initializer);
            }
        }
        body.getAsJsonArray().forEach(guarded::add);
        if (hasBaseInitializer && (info.base == null || !metadata.has("base-initializer") || !metadata.has("base-args")
                || !info.base.name.equals(string(metadata.get("base-initializer"), "Base initializer")) || !metadata.get("base-args").isJsonArray()))
            throw new IllegalArgumentException("Constructor initializer must name the direct base of " + info.name);
        if (info.base == null || !kind.equals("constructor") && !kind.equals("destructor")) return guarded;
        JsonArray receiver = new JsonArray(); receiver.add(variable("this"));
        if (kind.equals("destructor")) return info.base.cleanupDestructor == null ? guarded
                : internal(global, "#cleanup", guarded, callNode(info.base.cleanupDestructor, receiver), new JsonPrimitive(false));
        JsonArray arguments = new JsonArray(); arguments.add(variable("this"));
        if (metadata.has("base-args")) metadata.getAsJsonArray("base-args").forEach(arguments::add);
        JsonObject baseCall = callNode(info.base.constructor, arguments);
        if (metadata.has("_line")) { baseCall.add("_line", metadata.get("_line")); baseCall.add("_column", metadata.get("_column")); }
        JsonArray construction = new JsonArray(); construction.add(internal(global, "#check-this"));
        if (info.base.cleanupDestructor == null) { construction.add(baseCall); construction.add(guarded); }
        else {
            // Include base-argument temporaries in the protected scope: their own destructors
            // may fail after the child body returns, which must still roll back the base.
            String completed = "#base-constructed";
            JsonObject flag = node("vardef", new JsonPrimitive(completed), new JsonPrimitive(false));
            flag.addProperty("declared-type", "boolean"); construction.add(flag);
            JsonArray protectedBody = new JsonArray(); protectedBody.add(baseCall);
            protectedBody.add(node("varset", new JsonPrimitive(completed), new JsonPrimitive(true))); protectedBody.add(guarded);
            JsonObject cleanup = node("if", variable(completed), callNode(info.base.cleanupDestructor, receiver));
            construction.add(internal(global, "#cleanup", protectedBody, cleanup, new JsonPrimitive(true)));
        }
        return construction;
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
            case "while" -> params.size() < 2 || !isTrueLiteral(params.get(0)) || containsBreak(params.get(1));
            case "for" -> params.size() < 4 || !isTrueLiteral(params.get(1)) || containsBreak(params.get(3));
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
            for (JsonElement statement : node.getAsJsonArray()) if (containsBreak(statement)) return true;
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        if (!isControl(object)) return false;
        String call = object.get("call").getAsString();
        if (call.equals("break")) return true;
        if (call.equals("while") || call.equals("for")) return false; // a nested loop owns its own breaks
        if (!object.has("param") || !object.get("param").isJsonArray()) return false;
        for (JsonElement child : object.getAsJsonArray("param")) if (containsBreak(child)) return true;
        return false;
    }
    private static int hexId(String value, int digits, String description) {
        if (!value.matches("[0-9a-fA-F]{1," + digits + "}")) throw new IllegalArgumentException("Invalid " + description + ": " + value);
        return Integer.parseUnsignedInt(value, 16);
    }
    public static AcsObject compile(JsonObject source) {
        Map<String,Integer> previous = CLASS_TYPES.get(); Map<Integer,ClassInfo> previousLayouts = CLASS_LAYOUTS.get();
        CLASS_TYPES.set(new LinkedHashMap<>()); CLASS_LAYOUTS.set(new LinkedHashMap<>());
        try { TreeLimits.validate(source); return compileProgram(source); }
        catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed script JSON: required metadata, body or expression field is missing or invalid", e);
        }
        finally { CLASS_TYPES.set(previous); CLASS_LAYOUTS.set(previousLayouts); }
    }
    private static boolean sameSignature(Signature first, Signature second) {
        if (!equivalent(first.returnType(), second.returnType()) || first.paramTypes().size() != second.paramTypes().size()) return false;
        for (int i = 0; i < first.paramTypes().size(); i++)
            if (!equivalent(first.paramTypes().get(i), second.paramTypes().get(i))) return false;
        return true;
    }
    private static void hintName(String name) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid hint name: " + name);
    }
    private static AcsObject compileProgram(JsonObject source) {
        AcsObject result = new ExecProgram(); JsonObject metadata = source.getAsJsonObject("metadata");
        result.put("author", metadata.has("author") ? string(metadata.get("author"), "Author") : "");
        result.put("version", metadata.has("version") ? metadata.get("version").getAsInt() : 2); result.put("exec-version", ExecCodec.VERSION);
        AcsObject extension = new AcsObject();
        if (source.has("ext")) for (var entry : source.getAsJsonObject("ext").entrySet()) extension.put(entry.getKey(), string(entry.getValue(), "Extension value"));
        result.put("ext", extension);
        String hint = source.has("namespace-hint") ? string(source.get("namespace-hint"), "Namespace hint") : "";
        if (!hint.isEmpty()) hintName(hint);
        Map<String,Integer> assumptions = new LinkedHashMap<>();
        if (source.has("assume-hints")) for (JsonElement item : source.getAsJsonArray("assume-hints")) {
            JsonObject assumption = item.getAsJsonObject(); String name = string(assumption.get("hint"), "Assumed hint"); hintName(name);
            int namespace = assumption.get("namespace").getAsBigDecimal().intValueExact();
            if (namespace < 1 || namespace > 0xffff || namespace == 0xabd || namespace == 0xfff)
                throw new IllegalArgumentException("Reserved or invalid assumed namespace");
            Integer existing = assumptions.get(name);
            if (existing != null && existing != namespace || existing == null && assumptions.containsValue(namespace))
                throw new IllegalArgumentException("Conflicting hint or assumed namespace");
            assumptions.put(name, namespace);
        }
        LogicEnvironment global = new LogicEnvironment(); addDefaultIds(global.abs); addBuiltinSignatures(global); readClasses(source, global);
        global.moduleNamespace = metadata.has("function-namespace") ? metadata.get("function-namespace").getAsBigDecimal().intValueExact() : hint.isEmpty() ? 0xfff : 0;
        if (global.moduleNamespace < 0 || global.moduleNamespace > 0xffff || global.moduleNamespace == 0xabd || !hint.isEmpty() && global.moduleNamespace != 0)
            throw new IllegalArgumentException("Invalid function namespace");
        Map<String,Integer> abstractIds = new LinkedHashMap<>();
        if (source.has("abstract")) for (var entry : source.getAsJsonObject("abstract").entrySet()) {
            if (isBuiltinName(entry.getKey()) || global.classes.containsKey(entry.getKey())) throw new IllegalArgumentException("Reserved function name: " + entry.getKey());
            int id = hexId(string(entry.getValue(), "Function ID"), 8, "function ID");
            if ((id >>> 16) == 0xabd) throw new IllegalArgumentException("Abstract function cannot use the runtime namespace");
            abstractIds.put(entry.getKey(), id);
        }
        List<Function> functions = new ArrayList<>(); Map<String,Function> definitions = new LinkedHashMap<>(); Set<Integer> ids = new HashSet<>();
        for (var namespace : source.getAsJsonObject("body").entrySet()) {
            int namespaceId = hexId(namespace.getKey(), 4, "namespace") << 16;
            for (var entry : namespace.getValue().getAsJsonObject().entrySet()) {
                JsonObject function = entry.getValue().getAsJsonObject(), meta = function.getAsJsonObject("metadata");
                int position = meta.get("position").getAsBigDecimal().intValueExact();
                if (position < 0 || position > 0xffff) throw new IllegalArgumentException("Invalid function position: " + position);
                int id = namespaceId | position;
                if ((id >>> 16) == 0xabd || !hint.isEmpty() && namespaceId != 0) throw new IllegalArgumentException("Invalid definition namespace");
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate function ID: " + Integer.toHexString(id));
                String name = meta.has("name") ? string(meta.get("name"), "Function name") : abstractIds.entrySet().stream()
                        .filter(item -> item.getValue() == id).map(Map.Entry::getKey).findFirst().orElse(entry.getKey());
                if (isBuiltinName(name) || global.classes.containsKey(name)) throw new IllegalArgumentException("Reserved function name: " + name);
                int declaredReturn = returnType(string(meta.get("return-type"), "Function return type"));
                JsonArray parameterNames = meta.getAsJsonArray("param");
                if ((id == 0 || id == 1) && (declaredReturn != AsTypes.VOID_VALUE || !parameterNames.isEmpty()))
                    throw new IllegalArgumentException("Lifecycle function " + name + " must be void with no parameters");
                List<Integer> parameterTypes = readParameterTypes(meta.get("param-types"), parameterNames.size(), true, "Function " + name);
                Function value = new Function(id, name, declaredReturn, parameterTypes, meta, function.get("script"));
                if (definitions.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate function definition: " + name);
                functions.add(value); global.abs.put(name, id);
                global.namedSignatures.put(name, new Signature(id, declaredReturn, parameterTypes, true));
            }
        }
        Map<String,Function> declarations = new LinkedHashMap<>(); Map<Integer,Signature> importIds = new LinkedHashMap<>();
        if (source.has("extern-signatures")) for (var entry : source.getAsJsonObject("extern-signatures").entrySet()) {
            String name = entry.getKey(); JsonObject declaration = entry.getValue().getAsJsonObject();
            Integer id = declaration.has("id") ? hexId(string(declaration.get("id"), "External ID"), 8, "external ID") : abstractIds.get(name);
            if (id == null) throw new IllegalArgumentException("External signature has no abstract mapping: " + name);
            if (isBuiltinName(name) || global.classes.containsKey(name) || (id >>> 16) == 0xabd || GeneraterJson.specialNames.containsValue(id))
                throw new IllegalArgumentException("Reserved external function: " + name);
            int returns = returnType(string(declaration.get("return-type"), "External return type"));
            List<Integer> params = readParameterTypes(declaration.get("param-types"), -1, false, "External " + name);
            Signature signature = new Signature(id, returns, params, true);
            Signature duplicate = importIds.putIfAbsent(id, signature);
            if (duplicate != null && !sameSignature(duplicate, signature)) throw new IllegalArgumentException("Conflicting external signature ID: " + Integer.toHexString(id));
            if (global.namedSignatures.containsKey(name) && !sameSignature(global.namedSignatures.get(name), signature))
                throw new IllegalArgumentException("Extern and definition signatures disagree: " + name);
            Function defined = definitions.get(name);
            if (defined != null && !hint.isEmpty() && Objects.equals(assumptions.get(hint), id >>> 16) && (defined.id() & 0xffff) != (id & 0xffff))
                throw new IllegalArgumentException("Definition ID disagrees with its own hint extern declaration: " + name);
            JsonObject meta = declaration.deepCopy(); JsonArray paramNames = new JsonArray();
            for (int i = 0; i < params.size(); i++) paramNames.add(i == 0 && declaration.has("owner-class") ? "this" : "<argument:" + i + ">");
            meta.add("param", paramNames);
            declarations.put(name, new Function(id, name, returns, params, meta, JsonNull.INSTANCE));
            global.abs.put(name, id); global.namedSignatures.put(name, signature);
        }
        for (var entry : abstractIds.entrySet()) {
            Function definition = definitions.get(entry.getKey()), declaration = declarations.get(entry.getKey());
            if (definition == null && declaration == null) throw new IllegalArgumentException("Missing external signature for " + entry.getKey());
            int expected = declaration != null ? declaration.id() : definition.id();
            if (entry.getValue() != expected) throw new IllegalArgumentException("Function mapping disagrees with declaration: " + entry.getKey());
        }
        int globals = 0;
        if (source.has("global-variable")) for (JsonElement item : source.getAsJsonArray("global-variable")) {
            String name = string(item, "Global name");
            if (global.classes.containsKey(name)) throw new IllegalArgumentException("Global name conflicts with class: " + name);
            if (globals >= MAX_VARIABLE_SLOTS) throw new IllegalArgumentException("Program exceeds global variable slot limit");
            global.define(name, -(++globals));
        }
        result.put("gvs", globals);
        // Imported self slots are reserved even if this module does not define them.
        for (int imported : importIds.keySet()) {
            boolean self = hint.isEmpty() ? (imported >>> 16) == global.moduleNamespace
                    : Objects.equals(assumptions.get(hint), imported >>> 16);
            if (self) ids.add((global.moduleNamespace << 16) | (imported & 0xffff));
        }
        addClassFactories(functions, ids, global, declarations);
        Set<Integer> fixedNamespaces = new HashSet<>(Set.of(0, 0xabd, 0xfff));
        if (hint.isEmpty()) {fixedNamespaces.add(global.moduleNamespace); for (Function function : functions) fixedNamespaces.add(function.id() >>> 16);}
        for (int namespace : assumptions.values()) if (fixedNamespaces.contains(namespace)) throw new IllegalArgumentException("Assumed namespace overlaps a fixed function namespace");
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
        AcsArray signatures = new AcsArray();
        for (var entry : importIds.entrySet()) {
            Signature signature = entry.getValue(); AcsObject compiled = new AcsObject();compiled.put("id", entry.getKey().intValue());compiled.put("return-type", abiType(signature.returnType()));
            AcsArray params = new AcsArray();for (int parameter : signature.paramTypes()) params.acsa.add(new AcsIntegerElement(abiType(parameter)));
            compiled.put("param-types", params); signatures.acsa.add(compiled);
        }
        result.put("extern-signatures", signatures);result.put("namespace-hint", hint);AcsArray compiledAssumptions = new AcsArray();
        assumptions.forEach((name, namespace) -> {AcsObject value = new AcsObject();value.put("hint", name);value.put("namespace", namespace.intValue());compiledAssumptions.acsa.add(value);});
        result.put("assume-hints", compiledAssumptions);
        AcsArray output = new AcsArray();
        for (Function function : functions) {
            if (function.returnType() != AsTypes.VOID_VALUE && canCompleteNormally(function.script()))
                throw new IllegalArgumentException("In function " + displayName(function.name()) + ": non-void function can reach the end of its body without returning a value");
            LogicEnvironment scope = global.child();scope.counter = new Counter();scope.function = displayName(function.name());scope.returnType = function.returnType();
            if (function.metadata().has("owner-class")) scope.ownerClass = string(function.metadata().get("owner-class"), "Function owner");
            int parameter = 0; AcsArray parameterTypes = new AcsArray();
            for (JsonElement name : function.metadata().getAsJsonArray("param")) {
                if (parameter >= MAX_VARIABLE_SLOTS) throw new IllegalArgumentException("Function frame exceeds variable slot limit");
                int parameterType = function.paramTypes().get(parameter);scope.define(string(name,"Parameter name"),parameter++,parameterType);parameterTypes.acsa.add(new AcsIntegerElement(abiType(parameterType)));
            }
            scope.counter.next = parameter; AcsObject compiled = new AcsObject();compiled.put("id",function.id());compiled.put("return-type",abiType(scope.returnType));
            compiled.put("param-count",parameter);compiled.put("param-types",parameterTypes);
            try {
                AcsElement body = compileExpression(classFunctionBody(function, global), scope);
                compiled.put("script",body);
            } catch (IllegalArgumentException error) {
                if (error.getMessage()!=null&&error.getMessage().startsWith("In function "))throw error;
                throw new IllegalArgumentException("In function "+scope.function+": "+error.getMessage(),error);
            }
            compiled.put("local-count",scope.counter.next-parameter);output.acsa.add(compiled);
        }
        result.put("f",output);return result;
    }
}
