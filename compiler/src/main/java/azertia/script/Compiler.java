package azertia.script;

import azertia.binary.complexBinary.*;
import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

/** Resolves symbols and lowers the readable JSON AST to numeric-slot ABD instructions. */
public class Compiler {
    private static final ThreadLocal<Integer> EXPRESSION_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Integer> TYPE_DEPTH = ThreadLocal.withInitial(() -> 0);
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
            new BuiltinSpec("getDepth", 0x0abd0001, AsTypes.INT_VALUE, List.of()),
            new BuiltinSpec("mem_free", 0x0abd0002, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("alloc", 0x0abd0003, AsTypes.ADDRESS_VALUE, List.of(AsTypes.INT_VALUE)),
            new BuiltinSpec("make_free", 0x0abd0004, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_send_up", 0x0abd0005, AsTypes.VOID_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("mem_get", 0x0abd0006, AsTypes.ANY_VALUE, List.of(AsTypes.ADDRESS_VALUE)),
            new BuiltinSpec("load_extern_library", 0x0abd0007, AsTypes.VOID_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("reflect_invoke_function", 0x0abd0008, AsTypes.ANY_VALUE, List.of()),
            new BuiltinSpec("reflect_get_hint_namespace", 0x0abd0009, AsTypes.INT_VALUE, List.of(AsTypes.STRING_VALUE)),
            new BuiltinSpec("reflect_hint_loaded", 0x0abd000a, AsTypes.BOOLEAN_VALUE, List.of(AsTypes.STRING_VALUE)));
    public static boolean isBuiltinName(String name) {
        return name.equals("value_compare") || name.equals("buffer") || BUILTINS.stream().anyMatch(spec -> spec.name().equals(name));
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
    private record Signature(int id, int returnType, List<Integer> paramTypes, boolean requireKnown,
                             List<Integer> typeParameters, int classParameters, boolean internal) {
        Signature(int id, int returns, List<Integer> params, boolean known) { this(id, returns, params, known, List.of(), 0, false); }
    }
    private record Field(String owner, String name, int type, JsonElement initializer, int line, int column) {}
    private static final class ClassInfo {
        String name, constructor, destructor, cleanupDestructor;
        ClassInfo base;
        // Literal (value) objects and pointers are distinct types of one layout; the
        // flexible "C (*)" parameter type accepts either and is a pointer inside the function.
        int valueType, pointerType, flexibleType, manualFactory, scopedFactory, valueFactory;
        boolean synthesizedDestructor;
        ClassInfo declaration;
        JsonObject source;
        boolean ready, building, prepared;
        List<Integer> arguments = List.of();
        final Map<String,Integer> typeScope = new LinkedHashMap<>();
        final List<Field> ownFields = new ArrayList<>();
        final List<Field> fields = new ArrayList<>();
        final Map<String,Field> visibleFields = new LinkedHashMap<>();
        final Map<String,String> ownMethods = new LinkedHashMap<>();
        final Map<String,String> methods = new LinkedHashMap<>();
        final Map<String,String> ownOperators = new LinkedHashMap<>();
        final Map<String,String> operators = new LinkedHashMap<>();
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
        private final Map<JsonObject,ClassInfo> callOwners = new IdentityHashMap<>();
        private Map<String,Integer> typeScope = Map.of();
        private List<Integer> typeParameters = List.of();
        private Counter counter = new Counter();
        private String function = "<expression>";
        private int returnType = -1;
        private int loopDepth;
        private String ownerClass;
        private int moduleNamespace = 0xfff;
        LogicEnvironment child() {
            LogicEnvironment child = new LogicEnvironment(); child.parent = this; child.counter = counter;
            child.function = function; child.returnType = returnType; child.loopDepth = loopDepth; child.ownerClass = ownerClass;
            child.typeScope = typeScope; child.typeParameters = typeParameters; return child;
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
    // Operator selection and signature inference may revisit the same operands.
    // Cache only during one query: later declarations can change lexical lookup.
    private static final ThreadLocal<IdentityHashMap<LogicEnvironment, IdentityHashMap<JsonElement,Integer>>> TYPE_QUERY = new ThreadLocal<>();
    private static int expressionType(JsonElement input, LogicEnvironment environment) {
        var query=TYPE_QUERY.get();boolean outer=query==null;
        if(outer){query=new IdentityHashMap<>();TYPE_QUERY.set(query);}
        try {
            var values=query.computeIfAbsent(environment,ignored->new IdentityHashMap<>());
            Integer known=values.get(input);if(known!=null)return known;
            int result=expressionTypeUncached(input,environment);values.put(input,result);return result;
        } finally {if(outer)TYPE_QUERY.remove();}
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
            if(operation.equals("reflect_invoke_function"))return reflectionType(object,environment);
            if(operation.equals("value_compare"))return AsTypes.INT_VALUE;
            CallBinding binding=bindCall(object,environment);
            return binding.signature()!=null?binding.signature().returnType():AsTypes.ANY_VALUE;
        }
        if (!kind.equals("ctrl")) return AsTypes.ANY_VALUE;
        return switch (operation) {
            case "var" -> params.size() == 1 ? variableType(string(params.get(0), "Variable name"), environment) : AsTypes.ANY_VALUE;
            case "null" -> NULL_TYPE;
            case "object-new" -> classInfo(string(params.get(0), "Class name"), environment).pointerType;
            case "object-value" -> classInfo(string(params.get(0), "Class name"), environment).valueType;
            case "buffer-new" -> bufferType(valueType(string(params.get(0), "Buffer element type"), true));
            case "member" -> memberField(params, environment).type();
            case "member-set" -> params.size() == 2 ? expressionType(params.get(1), environment) : AsTypes.ANY_VALUE;
            case "member-call" -> isBufferReceiver(object,environment) ? bufferMethodType(object,environment) : expressionType(memberCall(object,environment),environment);
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
            case "mod" -> params.size() == 2
                    && expressionType(params.get(0), environment) == AsTypes.INT_VALUE
                    && expressionType(params.get(1), environment) == AsTypes.INT_VALUE
                    ? AsTypes.INT_VALUE : AsTypes.ANY_VALUE;
            case "if" -> branchType(params, environment);
            case "vardef", "return", "break", "continue", "while", "for", "increment", "decrement", "object-def", "object-delete", "#bind", "#make-free", "#drop" -> AsTypes.VOID_VALUE;
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
    /**
     * A base pointer addresses the same prefix; no pointer adjustment or runtime type is needed.
     * Literal objects never convert: copying one keeps its layout and cleanup destructor.
     */
    private static boolean assignable(int expected, int actual) {
        if(isTypeParameter(expected))return expected==actual || actual==NULL_TYPE&&isPointerClass(typeBound(expected));
        if(isTypeParameter(actual))return isPointerClass(typeBound(actual))&&assignable(expected,typeBound(actual));
        if (equivalent(expected, actual))
            return !isValueClass(expected) || Objects.equals(CLASS_LAYOUTS.get().get(expected).cleanupDestructor,
                    CLASS_LAYOUTS.get().get(actual).cleanupDestructor) || expected == actual;
        if ((isPointerClass(expected) || isFlexibleClass(expected) || expected == AsTypes.ADDRESS_VALUE) && actual == NULL_TYPE) return true;
        // "C (*)" takes a pointer, or a literal object by its address, under the pointer conversion rules.
        if (isFlexibleClass(expected))
            return (isPointerClass(actual) || isValueClass(actual)) && assignable(pointerOf(expected), pointerOf(actual));
        if (!isPointerClass(expected) || !isPointerClass(actual)) return false;
        for (ClassInfo base = CLASS_LAYOUTS.get().get(actual).base; base != null; base = base.base)
            if (equivalent(expected, base.pointerType)) return true;
        return false;
    }
    private static boolean equivalent(int first, int second) {
        ArrayDeque<Long> pending = new ArrayDeque<>(); Set<Long> compared = new HashSet<>();
        pending.add(((long)first << 32) | (second & 0xffffffffL));
        while (!pending.isEmpty()) {
            long pair = pending.removeLast(); int leftType = (int)(pair >> 32), rightType = (int)pair;
            if (leftType == rightType) continue;
            if (!isClass(leftType) || !isClass(rightType) || classKind(leftType) != classKind(rightType)) return false;
            if (!compared.add(pair)) continue;
            ClassInfo left = CLASS_LAYOUTS.get().get(leftType), right = CLASS_LAYOUTS.get().get(rightType);
            if(left==null||right==null)return false;
            if(!left.arguments.isEmpty()||!right.arguments.isEmpty()) {
                if(!sameApplication(left,right))return false;
                continue;
            }
            ensureLayout(left);ensureLayout(right);
            if (left.fields.size() != right.fields.size()) return false;
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
        int depth = EXPRESSION_DEPTH.get();
        if (depth + levels + TreeLimits.ABD_LEVELS_ABOVE_FUNCTION_BODY > TreeLimits.MAX_ABD_NESTING)
            throw new IllegalArgumentException("Expression nesting exceeds the ABD limit of "
                    + TreeLimits.MAX_ABD_NESTING + " levels; split the expression or statement");
        EXPRESSION_DEPTH.set(depth + levels);
        try { return action.get(); }
        finally { if (depth == 0) EXPRESSION_DEPTH.remove(); else EXPRESSION_DEPTH.set(depth); }
    }
    public static AcsElement compileExpression(JsonElement input, LogicEnvironment environment) {
        Map<String,Integer> previousScope=TYPE_SCOPE.get();TYPE_SCOPE.set(environment.typeScope);
        try { return withDepth(1, () -> compileExpressionBody(input, environment)); }
        catch (IllegalArgumentException error) {
            if (error.getMessage() != null && error.getMessage().startsWith("In function ")) throw error;
            JsonObject object = input != null && input.isJsonObject() ? input.getAsJsonObject() : null;
            if (object == null || !object.has("_line")) throw error;
            String location = ":" + object.get("_line").getAsInt() + ":" + object.get("_column").getAsInt();
            throw new IllegalArgumentException("In function " + environment.function + location + ": " + error.getMessage(), error);
        } finally {TYPE_SCOPE.set(previousScope);}
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
        JsonObject overloaded=overloadedCall(source,environment);
        if(overloaded!=null)return compileInstruction(overloaded,environment);
        if (type.equals("call")) {
            if(call.equals("reflect_invoke_function"))return compileReflection(source,environment);
            if(call.equals("value_compare"))return compileCompare(source,environment);
            CallBinding binding=bindCall(source,environment);Signature signature=binding.signature();
            params=binding.arguments();String label=displayName(binding.name());int hidden=binding.name().contains("::")&&!isFactory(binding.name())?1:0;
            if(signature!=null&&params.size()!=signature.paramTypes().size())throw new IllegalArgumentException(label+" expects "+(signature.paramTypes().size()-hidden)+" arguments, got "+(params.size()-hidden));
            JsonArray converted=new JsonArray();
            for(int i=0;i<params.size();i++) {
                JsonElement argument=params.get(i);int actual=expressionType(argument,environment);
                if(signature!=null) {
                    int expected=signature.paramTypes().get(i);
                    if(expected==AsTypes.ANY_VALUE) {
                        if(isOwnedValue(actual)||isTypeParameter(actual))throw new IllegalArgumentException(label+" argument cannot take an object or type parameter without a declared type");
                    }else if(actual!=AsTypes.ANY_VALUE||signature.requireKnown())requireKnownExact(label+" argument "+(i+1-hidden),expected,actual);
                    if(isFlexibleClass(expected)&&isValueClass(typeBound(actual)))argument=internal(environment,"#address-of",argument);
                }
                converted.add(argument);
            }
            result.put("t",1);result.put("id",environment.getFunctionId(binding.name()));
            AcsArray args=new AcsArray();
            for(JsonElement argument:converted) {
                rejectVoidValue(label+" argument",argument,environment);
                args.acsa.add(withDepth(1,()->compileExpression(argument,environment)));
            }
            result.put("param",args);result.put("contexts",contexts(binding.types(),environment));
            if(binding.genericReturn())return typeCheck(result,binding.signature().returnType(),environment);
            return result;
        }
        if (!type.equals("ctrl")) throw new IllegalArgumentException("Unknown instruction type: " + type);
        result.put("t", 0);
        switch (call) {
            case "buffer-new" -> {
                arity(call,params,1,1);int element=valueType(string(params.get(0),"Buffer element type"),true);bufferType(element);
                result.put("c",ExecOpcodes.BUFFER_NEW);result.put("context",context(element,environment,0));
            }
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
                else rejectUntypedObject("Untyped variable " + alias, params.get(1), environment);
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
                rejectUntypedObject("Raw memory", params.get(1), environment);
                result.put("c", ExecOpcodes.code("m")); result.put("v1", left);
                result.put("v2", compileExpression(params.get(1), environment));
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
                                + ", got " + typeName(actual));
                    result.put(isTypeParameter(environment.returnType)?"v":"r", compileExpression(params.get(0), environment));
                }
            }
            case "null" -> { arity(call, params, 0, 0); return withDepth(1, () -> new AcsAddress(0L)); }
            case "member" -> {
                arity(call, params, 2, 2); Field field = memberField(params, environment);
                ClassInfo info = receiverClass(params.get(0), environment);
                AcsObject address = new AcsObject(); address.put("t", 0); address.put("c", ExecOpcodes.code("oa"));
                JsonElement receiver = receiverAddress(params.get(0), environment);
                address.put("v", withDepth(2, () -> compileExpression(receiver, environment))); address.put("offset", info.fields.indexOf(field));
                result.put("t", 1); result.put("id", 0x0abd0006);
                AcsArray args = new AcsArray(); args.acsa.add(address); result.put("param", args);
            }
            case "member-set" -> {
                arity(call, params, 2, 2);
                JsonObject member = params.get(0).getAsJsonObject();
                if (!isControl(member) || !string(member.get("call"), "Member target").equals("member"))
                    throw new IllegalArgumentException("Member assignment needs a field target");
                requireWritableReceiver(parameters(member).get(0),environment);
                Field field = memberField(parameters(member), environment);
                requireKnownExact("Assignment to member " + field.name(), field.type(), expressionType(params.get(1), environment));
                result.put("c", ExecOpcodes.code("m")); result.put("v1", compileExpression(member, environment));
                result.put("v2", compileExpression(params.get(1), environment));
            }
            case "member-call" -> { return isBufferReceiver(source,environment)?compileBufferCall(source,environment):compileInstruction(memberCall(source,environment),environment); }
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
                if (source.has("_line") && source.has("_column")) {
                    construct.add("_line", source.get("_line")); construct.add("_column", source.get("_column"));
                }
                JsonObject definition = node("vardef", new JsonPrimitive(alias), construct);
                definition.addProperty("declared-type", info.name + "*");
                return compileInstruction(definition, environment);
            }
            case "object-delete" -> {
                arity(call, params, 1, 1); int objectType = expressionType(params.get(0), environment);
                if (!isPointerClass(objectType) && objectType != NULL_TYPE)
                    throw new IllegalArgumentException(isValueClass(objectType) ? "delete requires a class pointer; a literal object ends with its variable"
                            : "delete requires a known class pointer or null");
                result.put("c", ExecOpcodes.code("od")); result.put("v", compileExpression(params.get(0), environment));
            }
            case "#context-abi", "#context-default" -> {
                requireTrusted(source,environment);arity(call,params,1,1);
                result.put("c",call.equals("#context-abi")?36:37);result.put("context",context(params.get(0).getAsInt(),environment,0));
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
                if (!params.get(1).isJsonNull()) {
                    String destructor=string(params.get(1),"Destructor name");result.put("destructor",environment.getFunctionId(destructor));
                    ClassInfo owner=environment.root().classes.get(destructor.substring(0,destructor.indexOf("::")));
                    ClassInfo actual=ancestor(classInfo(environment.ownerClass,environment),owner);
                    result.put("contexts",contexts(actual.arguments,environment));
                }
                result.put("manual", params.get(2).getAsBoolean());
            }
            case "#make-free" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("t", 1); result.put("id", 0x0abd0004);
                JsonElement pointer = params.get(0);
                AcsArray args = new AcsArray(); args.acsa.add(withDepth(1, () -> compileExpression(pointer, environment))); result.put("param", args);
            }
            case "#new-block" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("new_block"));
                result.put("size", classInfo(string(params.get(0), "Class name"), environment).fields.size());
            }
            case "#address-of" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("block_address")); result.put("v", compileExpression(params.get(0), environment));
            }
            case "#move" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                JsonArray target = parameters(params.get(0).getAsJsonObject());
                result.put("c", ExecOpcodes.code("mv")); result.put("v", environment.getVariable(string(target.get(0), "Moved variable")).slot);
            }
            case "#drop" -> {
                requireTrusted(source, environment); arity(call, params, 1, 1);
                result.put("c", ExecOpcodes.code("drop")); result.put("v", compileExpression(params.get(0), environment));
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
                initialization.ownerClass = environment.ownerClass;initialization.typeScope=environment.typeScope;initialization.typeParameters=environment.typeParameters;
                initialization.function = environment.ownerClass + " initialization";
                initialization.returnType = AsTypes.VOID_VALUE; initialization.counter = environment.counter;
                initialization.define("this", 0, classInfo(environment.ownerClass, environment).pointerType);
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
                    default -> {
                        int left = expressionType(params.get(0), environment), right = expressionType(params.get(1), environment);
                        if (isOwnedValue(left) || isOwnedValue(right))
                            throw new IllegalArgumentException("Literal objects cannot be compared; compare their fields or pointers");
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
        int depth=TYPE_DEPTH.get();
        if(depth>=MAX_VALUE_NESTING)throw new IllegalArgumentException("Type nesting exceeds "+MAX_VALUE_NESTING+" levels");
        TYPE_DEPTH.set(depth+1);
        try{return valueTypeBody(type,valueOnly);}finally{if(depth==0)TYPE_DEPTH.remove();else TYPE_DEPTH.set(depth);}
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
        if(Set.of("index","index-set","invoke").contains(operation)) {
            arity(operation,params,operation.equals("invoke")?1:operation.equals("index")?2:3,operation.equals("invoke")?Integer.MAX_VALUE:operation.equals("index")?2:3);
            if(operation.equals("index-set"))requireWritableReceiver(params.get(0),environment);
            JsonArray arguments=new JsonArray();for(int i=1;i<params.size();i++)arguments.add(params.get(i));
            return operatorCall(operation.equals("index")?"index-get":operation.equals("index-set")?"index-set":"call",params.get(0),arguments,source,environment);
        }
        if(Set.of("add","minus","multiply","divide").contains(operation)&&params.size()==2
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
    }
    private static final List<String> BUFFER_METHODS=List.of("length","capacity","reserve","get","set","push","resize");
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
        result.put("v",compileExpression(params.get(0),environment));AcsArray args=new AcsArray();
        for(int i=2;i<params.size();i++){JsonElement arg=params.get(i);args.acsa.add(withDepth(1,()->compileExpression(arg,environment)));}
        result.put("args",args);return result;
    }
    private static AcsElement compileCompare(JsonObject source,LogicEnvironment environment) {
        if(!source.has("type-args")||source.getAsJsonArray("type-args").size()!=1)
            throw new IllegalArgumentException("value_compare requires one explicit type argument");
        int type=valueType(string(source.getAsJsonArray("type-args").get(0),"Comparison type"),true);
        if(isOwnedValue(type)||isFlexibleClass(type))throw new IllegalArgumentException("value_compare does not support class values or buffers; use a custom comparator");
        JsonArray params=parameters(source);arity("value_compare",params,2,2);
        for(JsonElement value:params)requireKnownExact("Comparison operand",type,expressionType(value,environment));
        AcsObject result=new AcsObject();result.put("t",0);result.put("c",ExecOpcodes.VALUE_COMPARE);result.put("context",context(type,environment,0));
        result.put("v1",compileExpression(params.get(0),environment));result.put("v2",compileExpression(params.get(1),environment));return result;
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
        if(!original.equals(name)){JsonArray receiver=new JsonArray();receiver.add(variable("this"));args.forEach(receiver::add);args=receiver;}
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
        int returns=reflectionType(source,environment);JsonArray params=parameters(source);arity("reflect_invoke_function",params,1,Integer.MAX_VALUE);
        requireKnownExact("Reflection function ID",AsTypes.INT_VALUE,expressionType(params.get(0),environment));
        AcsObject result=new AcsObject();result.put("t",1);result.put("id",0x0abd0008);
        AcsArray args=new AcsArray();
        if(isTypeParameter(returns)){AcsObject abi=new AcsObject();abi.put("t",0);abi.put("c",36);abi.put("context",context(returns,environment,0));args.acsa.add(abi);}
        else args.acsa.add(new AcsIntegerElement(abiType(returns)));
        for(JsonElement arg:params){rejectVoidValue("Reflection argument",arg,environment);args.acsa.add(withDepth(1,()->compileExpression(arg,environment)));}
        result.put("param",args);return result;
    }
    private static AcsArray contexts(List<Integer> types,LogicEnvironment environment) {
        AcsArray result=new AcsArray();for(int type:types)result.acsa.add(context(type,environment,0));return result;
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
        if(visiting.size()>MAX_VALUE_NESTING)throw new IllegalArgumentException("Literal object nesting exceeds "+MAX_VALUE_NESTING);
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
    private static int pointerTypeOf(int valueType) { return valueType + 1; }
    /** The pointer type of the same class for any class type code. */
    private static int pointerOf(int classType) { return classType - classKind(classType) + 1; }
    /** Pointers erase to address; literal objects cross calls as runtime object values. */
    private static int abiType(int type) {
        return isTypeParameter(type) ? abiType(typeBound(type)) : isOwnedValue(type) ? AsTypes.OBJECT_VALUE : isClass(type) ? AsTypes.ADDRESS_VALUE : type;
    }
    private static void rejectUntypedObject(String context, JsonElement value, LogicEnvironment environment) {
        if (isOwnedValue(expressionType(value, environment)) || isTypeParameter(expressionType(value, environment)))
            throw new IllegalArgumentException(context + " cannot hold an object value; declare it with the class type or use a pointer");
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
    private static void validateSourceName(String name, String description) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) throw new IllegalArgumentException("Invalid " + description + ": " + name);
        for (int i = 1; i < name.length(); i++)
            if (!Character.isJavaIdentifierPart(name.charAt(i))) throw new IllegalArgumentException("Invalid " + description + ": " + name);
        if (Set.of("class", "extends", "new", "delete", "this", "null", "return", "if", "else", "while", "for", "break", "continue", "public", "private", "protected", "virtual", "var", "def",
                "int", "float", "double", "string", "boolean", "bool", "void", "any", "address", "buffer", "operator", "true", "false", "extern").contains(name))
            throw new IllegalArgumentException("Reserved " + description + ": " + name);
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
        Map<String,Integer> previous=TYPE_SCOPE.get();TYPE_SCOPE.set(scope);
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
        checkBounds(declaration.arguments,arguments);
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
                if(!info.prepared){prepareLayout(info);info.prepared=true;}
                if(info.base!=null&&!info.base.ready){queueLayout(info.base,pending);continue;}
                finishLayout(info);info.building=false;pending.pop();
            }
        } finally {for(ClassInfo info:pending)info.building=false;}
    }
    private static void prepareLayout(ClassInfo info) {
            if(info!=info.declaration) {
                ClassInfo original=info.declaration;Map<Integer,Integer> substitutions=substitutions(info);
                info.constructor=original.constructor;info.destructor=original.destructor;info.cleanupDestructor=original.cleanupDestructor;
                info.synthesizedDestructor=original.synthesizedDestructor;info.ownMethods.putAll(original.ownMethods);info.ownOperators.putAll(original.ownOperators);
                if(original.base!=null)info.base=CLASS_LAYOUTS.get().get(substitute(original.base.valueType,substitutions));
                for(Field field:original.ownFields) info.ownFields.add(new Field(field.owner(),field.name(),substitute(field.type(),substitutions),field.initializer(),field.line(),field.column()));
            } else {
                JsonObject definition=info.source;
                if(definition.has("base")) {
                    int base=resolveType(string(definition.get("base"),"Base class"),info.typeScope);
                    if(!isValueClass(base))throw new IllegalArgumentException("Base must name a class: "+info.name);
                    info.base=CLASS_LAYOUTS.get().get(base);
                }
                Set<String> names=new HashSet<>();
                for(JsonElement element:definition.getAsJsonArray("fields")) {
                    JsonObject field=element.getAsJsonObject();String name=string(field.get("name"),"Field name");validateSourceName(name,"field name");
                    if(!names.add(name))throw new IllegalArgumentException("Duplicate member: "+info.name+"."+name);
                    int type=resolveType(string(field.get("type"),"Field type"),info.typeScope);
                    if(type==AsTypes.VOID_VALUE||type==AsTypes.ANY_VALUE||isFlexibleClass(type))throw new IllegalArgumentException("Field requires a concrete type");
                    info.ownFields.add(new Field(info.name,name,type,field.get("initializer"),field.has("_line")?field.get("_line").getAsInt():0,field.has("_column")?field.get("_column").getAsInt():0));
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
            if(info.base!=null){info.fields.addAll(info.base.fields);info.visibleFields.putAll(info.base.visibleFields);info.methods.putAll(info.base.methods);info.operators.putAll(info.base.operators);}
            for(Field field:info.ownFields){info.fields.add(field);info.visibleFields.put(field.name(),field);info.methods.remove(field.name());}
            info.ownMethods.forEach((name,method)->{info.methods.put(name,method);info.visibleFields.remove(name);});
            info.operators.putAll(info.ownOperators);
            if(info.fields.isEmpty())throw new IllegalArgumentException("Class "+info.name+" must declare at least one field");
            if(info.fields.size()>MAX_VARIABLE_SLOTS)throw new IllegalArgumentException("Class exceeds object slot limit");
            info.cleanupDestructor=info.destructor!=null?info.destructor:info.base==null?null:info.base.cleanupDestructor;
            info.ready=true;
    }
    private static void readClasses(JsonObject source,LogicEnvironment environment) {
        if(!source.has("classes"))return;
        for(JsonElement element:source.getAsJsonArray("classes")) {
            JsonObject definition=element.getAsJsonObject();ClassInfo info=new ClassInfo();
            info.name=string(definition.get("name"),"Class name");validateSourceName(info.name,"class name");
            if(isBuiltinName(info.name)||environment.classes.containsKey(info.name))throw new IllegalArgumentException("Duplicate or reserved class: "+info.name);
            info.declaration=info;info.source=definition;allocateClass(info);
            environment.classes.put(info.name,info);CLASS_DECLARATIONS.get().put(info.name,info);
        }
        // Collect every class's parameter names before resolving bounds, so a bound
        // can name a later generic class just like any other forward class type.
        for(ClassInfo info:environment.classes.values()) {
            List<Integer> parameters=new ArrayList<>();
            if(info.source.has("type-parameters"))for(JsonElement element:info.source.getAsJsonArray("type-parameters")) {
                String name=string(element.getAsJsonObject().get("name"),"Type parameter");validateSourceName(name,"type parameter");
                if(info.typeScope.containsKey(name)||environment.classes.containsKey(name)||isBuiltinName(name))throw new IllegalArgumentException("Duplicate or shadowed type parameter: "+name);
                int id=-100-TYPE_PARAMETERS.get().size();TYPE_PARAMETERS.get().put(id,new TypeParameter(name,AsTypes.ANY_VALUE));info.typeScope.put(name,id);parameters.add(id);
            }
            info.arguments=List.copyOf(parameters);
        }
        for(ClassInfo info:environment.classes.values()) {
            Map<String,Integer> earlier=new LinkedHashMap<>();int index=0;
            if(info.source.has("type-parameters"))for(JsonElement element:info.source.getAsJsonArray("type-parameters")) {
                JsonObject parameter=element.getAsJsonObject();int id=info.arguments.get(index++);String name=TYPE_PARAMETERS.get().get(id).name();
                int bound=parameter.has("bound")?resolveType(string(parameter.get("bound"),"Type parameter bound"),earlier):AsTypes.ANY_VALUE;
                if(parameter.has("bound")&&!isClass(bound))throw new IllegalArgumentException("A type parameter bound must be a class value or pointer");
                TYPE_PARAMETERS.get().put(id,new TypeParameter(name,bound));earlier.put(name,id);
            }
        }
        // Applications created while collecting forward bounds saw provisional
        // bounds. Validate them once every declaration has its final contract.
        for(ClassInfo application:new ArrayList<>(CLASS_APPLICATIONS.get().values()))
            checkBounds(application.declaration.arguments,application.arguments);
        for(ClassInfo info:environment.classes.values())ensureLayout(info);
        Map<ClassInfo,Integer> nesting=new HashMap<>();
        for(ClassInfo info:environment.classes.values())valueNesting(info,nesting,new HashSet<>());
    }
    /** Literal-object fields are embedded storage, so a class cannot contain itself by value. */
    private static int valueNesting(ClassInfo info, Map<ClassInfo,Integer> known, Set<ClassInfo> visiting) {
        ensureLayout(info);
        Integer cached = known.get(info);
        if (cached != null) return cached;
        if (!visiting.add(info)) throw new IllegalArgumentException("Class " + info.name + " contains itself by value; use a pointer field");
        if(visiting.size()>MAX_VALUE_NESTING)throw new IllegalArgumentException("Literal object nesting exceeds "+MAX_VALUE_NESTING);
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
        for (ClassInfo info : global.classes.values()) {
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
                if(!equivalent(getter.paramTypes().get(1),setter.paramTypes().get(1))||!equivalent(getter.returnType(),setter.paramTypes().get(2)))
                    throw new IllegalArgumentException("Index getter and setter types must match: "+info.name);
            }
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
        int[] position = {1}; int namespace = global.moduleNamespace << 16;
        java.util.function.Supplier<Integer> nextId = () -> {
            while (position[0] <= 0xffff && occupied.contains(namespace | position[0])) position[0]++;
            if (position[0] > 0xffff) throw new IllegalArgumentException("No free function ID for class factories");
            int id = namespace | position[0]++; ids.add(id); occupied.add(id); return id;
        };
        // A class with literal-object fields and no declared destructor gets one that
        // ends those fields; every module creating such objects generates its own.
        for (ClassInfo info : global.classes.values()) if (info.synthesizedDestructor) {
            int id = nextId.get(); String name = info.destructor;
            if (global.abs.containsKey(name)) throw new IllegalArgumentException("Reserved compiler function: " + name);
            JsonObject metadata = new JsonObject(); metadata.addProperty("owner-class", info.name); metadata.addProperty("function-kind", "destructor");
            JsonArray names = new JsonArray(); names.add("this"); metadata.add("param", names);
            List<Integer> types = List.of(info.pointerType);
            functions.add(new Function(id, name, AsTypes.VOID_VALUE, types, metadata, new JsonArray()));
            registerCompilerFunction(global, name, id, AsTypes.VOID_VALUE, types);
        }
        for (ClassInfo info : global.classes.values()) {
            Function constructor = byName.get(info.constructor);
            List<Integer> types = List.copyOf(constructor.paramTypes().subList(1, constructor.paramTypes().size()));
            for (int kind = 0; kind < 3; kind++) {
                boolean manual = kind == 1, literal = kind == 2;
                int id = nextId.get();
                String name = literal ? info.name + VALUE_FACTORY : factoryName(info, manual);
                if (global.abs.containsKey(name)) throw new IllegalArgumentException("Reserved compiler function: " + name);
                if (literal) info.valueFactory = id; else if (manual) info.manualFactory = id; else info.scopedFactory = id;
                JsonObject metadata = new JsonObject(); metadata.addProperty("owner-class", info.name);
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
                if (manual) body.add(internal(global, "#make-free", variable(storage)));
                body.add(node("return", variable(storage)));
                int returns = literal ? info.valueType : info.pointerType;
                functions.add(new Function(id, name, returns, types, metadata, body));
                registerCompilerFunction(global, name, id, returns, types);
            }
            if(constructor.paramTypes().size()==1) {
                int id=nextId.get();String name=info.name+PLACEMENT_FACTORY;
                JsonObject metadata=new JsonObject();metadata.addProperty("owner-class",info.name);metadata.add("param",array(new JsonPrimitive("this")));
                JsonArray body=new JsonArray();body.add(callNode(info.constructor,array(variable("this"))));
                body.add(internal(global,"#bind",variable("this"),info.cleanupDestructor==null?JsonNull.INSTANCE:new JsonPrimitive(info.cleanupDestructor),new JsonPrimitive(false)));
                functions.add(new Function(id,name,AsTypes.VOID_VALUE,List.of(info.pointerType),metadata,body));
                registerCompilerFunction(global,name,id,AsTypes.VOID_VALUE,List.of(info.pointerType));
            }
        }
    }
    private static Signature operatorSignature(ClassInfo receiver,Function function) {
        ClassInfo declaration=CLASS_DECLARATIONS.get().get(function.metadata().get("owner-class").getAsString());
        Map<Integer,Integer> substitution=substitutions(ancestor(receiver,declaration));
        return new Signature(function.id(),substitute(function.returnType(),substitution),function.paramTypes().stream().map(t->substitute(t,substitution)).toList(),true);
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
                    if(field.line()>0&&value.isJsonObject()){value.getAsJsonObject().addProperty("_line",field.line());value.getAsJsonObject().addProperty("_column",field.column());}
                }
                JsonObject assignment=node("member-set",node("member",variable("this"),new JsonPrimitive(field.name())),value);
                if(field.line()>0){assignment.addProperty("_line",field.line());assignment.addProperty("_column",field.column());}
                JsonElement initialize=genericDefault?node("if",node("cmp",internal(global,"#context-abi",new JsonPrimitive(field.type())),new JsonPrimitive(8)),assignment):assignment;
                JsonObject initializer=internal(global,"#field-initialize",initialize);
                if(field.line()>0){initializer.addProperty("_line",field.line());initializer.addProperty("_column",field.column());}
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
            JsonArray finalizer = new JsonArray();
            for (int i = info.ownFields.size() - 1; i >= 0; i--) if (isOwnedValue(info.ownFields.get(i).type()) || isTypeParameter(info.ownFields.get(i).type()))
                finalizer.add(internal(global, "#drop", node("member", variable("this"), new JsonPrimitive(info.ownFields.get(i).name()))));
            if (info.base != null && info.base.cleanupDestructor != null) finalizer.add(callNode(info.base.cleanupDestructor, receiver));
            return finalizer.isEmpty() ? guarded : internal(global, "#cleanup", guarded, finalizer, new JsonPrimitive(false));
        }
        if (info.base == null || !kind.equals("constructor")) return guarded;
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
        Map<Integer,ClassInfo> previousLayouts = CLASS_LAYOUTS.get();
        var previousParameters=TYPE_PARAMETERS.get();var previousScope=TYPE_SCOPE.get();var previousDeclarations=CLASS_DECLARATIONS.get();
        var previousApplications=CLASS_APPLICATIONS.get();var previousFunctions=FUNCTION_SCOPES.get();int previousNext=NEXT_CLASS.get();
        var previousBufferElements=BUFFER_ELEMENTS.get();var previousBufferTypes=BUFFER_TYPES.get();
        CLASS_LAYOUTS.set(new LinkedHashMap<>());TYPE_PARAMETERS.set(new LinkedHashMap<>());
        BUFFER_ELEMENTS.set(new LinkedHashMap<>());BUFFER_TYPES.set(new LinkedHashMap<>());
        TYPE_SCOPE.set(new LinkedHashMap<>());CLASS_DECLARATIONS.set(new LinkedHashMap<>());CLASS_APPLICATIONS.set(new LinkedHashMap<>());FUNCTION_SCOPES.set(new IdentityHashMap<>());NEXT_CLASS.set(FIRST_CLASS_TYPE);
        try { TreeLimits.validate(source); return compileProgram(source); }
        catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) {
            throw new IllegalArgumentException("Malformed script JSON: required metadata, body or expression field is missing or invalid", e);
        }
        finally { CLASS_LAYOUTS.set(previousLayouts);TYPE_PARAMETERS.set(previousParameters);TYPE_SCOPE.set(previousScope);CLASS_DECLARATIONS.set(previousDeclarations);CLASS_APPLICATIONS.set(previousApplications);FUNCTION_SCOPES.set(previousFunctions);NEXT_CLASS.set(previousNext);BUFFER_ELEMENTS.set(previousBufferElements);BUFFER_TYPES.set(previousBufferTypes); }
    }
    private static boolean sameSignature(Signature first, Signature second) {
        if(first.typeParameters().size()!=second.typeParameters().size()||first.classParameters()!=second.classParameters()||first.internal()!=second.internal())return false;
        Map<Integer,Integer> renaming=new HashMap<>();
        for(int i=0;i<first.typeParameters().size();i++) {
            int left=first.typeParameters().get(i),right=second.typeParameters().get(i);
            if(!equivalent(TYPE_PARAMETERS.get().get(left).bound(),substitute(TYPE_PARAMETERS.get().get(right).bound(),renaming)))return false;
            renaming.put(right,left);
        }
        if (!equivalent(first.returnType(), substitute(second.returnType(),renaming)) || first.paramTypes().size() != second.paramTypes().size()) return false;
        for (int i = 0; i < first.paramTypes().size(); i++)
            if (!equivalent(first.paramTypes().get(i), substitute(second.paramTypes().get(i),renaming))) return false;
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
                TYPE_SCOPE.set(functionScope(meta));
                int declaredReturn = returnType(string(meta.get("return-type"), "Function return type"));
                JsonArray parameterNames = meta.getAsJsonArray("param");
                if ((id == 0 || id == 1) && (declaredReturn != AsTypes.VOID_VALUE || !parameterNames.isEmpty()))
                    throw new IllegalArgumentException("Lifecycle function " + name + " must be void with no parameters");
                List<Integer> parameterTypes = readParameterTypes(meta.get("param-types"), parameterNames.size(), true, "Function " + name);
                Function value = new Function(id, name, declaredReturn, parameterTypes, meta, function.get("script"));
                if (definitions.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate function definition: " + name);
                functions.add(value); global.abs.put(name, id);
                global.namedSignatures.put(name, value.signature());
            }
        }
        Map<String,Function> declarations = new LinkedHashMap<>(); Map<Integer,Signature> importIds = new LinkedHashMap<>();
        if (source.has("extern-signatures")) for (var entry : source.getAsJsonObject("extern-signatures").entrySet()) {
            String name = entry.getKey(); JsonObject declaration = entry.getValue().getAsJsonObject();
            Integer id = declaration.has("id") ? hexId(string(declaration.get("id"), "External ID"), 8, "external ID") : abstractIds.get(name);
            if (id == null) throw new IllegalArgumentException("External signature has no abstract mapping: " + name);
            if (isBuiltinName(name) || global.classes.containsKey(name) || (id >>> 16) == 0xabd || GeneraterJson.specialNames.containsValue(id))
                throw new IllegalArgumentException("Reserved external function: " + name);
            TYPE_SCOPE.set(functionScope(declaration));
            int returns = returnType(string(declaration.get("return-type"), "External return type"));
            List<Integer> params = readParameterTypes(declaration.get("param-types"), -1, false, "External " + name);
            List<Integer> genericParameters=List.copyOf(functionScope(declaration).values());
            int classCount=declaration.has("owner-class")?global.classes.get(declaration.get("owner-class").getAsString()).arguments.size():0;
            boolean internal=declaration.has("function-kind")&&!declaration.get("function-kind").getAsString().equals("method");
            Signature signature = new Signature(id, returns, params, true,genericParameters,classCount,internal);
            Signature duplicate = importIds.putIfAbsent(id, signature);
            if (duplicate != null && !sameSignature(duplicate, signature)) throw new IllegalArgumentException("Conflicting external signature ID: " + Integer.toHexString(id));
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
        TYPE_SCOPE.set(Map.of());
        addClassFactories(functions, ids, global, declarations);
        for(Function function:functions) {
            if(isFactory(function.name())||function.metadata().has("function-kind")&&function.metadata().get("function-kind").getAsString().equals("destructor")&&global.classes.get(function.metadata().get("owner-class").getAsString()).synthesizedDestructor)
                global.namedSignatures.put(function.name(),function.signature());
        }
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
            compiled.put("param-types", params);compiled.put("hidden-count",signature.typeParameters().size());compiled.put("entry-kind",signature.internal()?1:0); signatures.acsa.add(compiled);
        }
        result.put("extern-signatures", signatures);result.put("namespace-hint", hint);AcsArray compiledAssumptions = new AcsArray();
        assumptions.forEach((name, namespace) -> {AcsObject value = new AcsObject();value.put("hint", name);value.put("namespace", namespace.intValue());compiledAssumptions.acsa.add(value);});
        result.put("assume-hints", compiledAssumptions);
        AcsArray output = new AcsArray();
        for (Function function : functions) {
            if (function.returnType() != AsTypes.VOID_VALUE && canCompleteNormally(function.script()))
                throw new IllegalArgumentException("In function " + displayName(function.name()) + ": non-void function can reach the end of its body without returning a value");
            LogicEnvironment scope = global.child();scope.counter = new Counter();scope.function = displayName(function.name());scope.returnType = function.returnType();
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
            try {
                AcsElement body = compileExpression(classFunctionBody(function, global), scope);
                compiled.put("script",body);
            } catch (IllegalArgumentException error) {
                if (error.getMessage()!=null&&error.getMessage().startsWith("In function "))throw error;
                throw new IllegalArgumentException("In function "+scope.function+": "+error.getMessage(),error);
            }
            compiled.put("local-count",scope.counter.next-parameter);output.acsa.add(compiled);
        }
        result.put("f",output);normalizeContexts(result);return result;
    }
}
