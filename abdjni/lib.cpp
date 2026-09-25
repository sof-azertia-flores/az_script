#include "main.h"
#include "azertia_jni_Caller.h"
#include "azertia_jni_Caller20.h"
#include "azertia_jni_Caller220.h"
#include "azertia_jni_Caller2220.h"
#include "azertia_jni_Caller22220.h"

#include <algorithm>
#include <bit>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <exception>
#include <filesystem>
#include <fstream>
#include <limits>
#include <mutex>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <vector>

using namespace azertian;

namespace {
std::shared_ptr<script> loadedScript;
int activeCalls = 0;
constexpr std::size_t maxFileBytes = 64 * 1024 * 1024;

struct VmEnvironment {
    JavaVM* vm;
    JNIEnv* env = nullptr;
    bool attached = false;
    explicit VmEnvironment(JavaVM* v) : vm(v) {
        jint state = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (state == JNI_EDETACHED) {
            if (vm->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) throw std::runtime_error("Cannot attach callback thread to JVM");
            attached = true;
        } else if (state != JNI_OK) throw std::runtime_error("Cannot obtain JNI environment");
    }
    ~VmEnvironment() { if (attached) vm->DetachCurrentThread(); }
};
// Retain the original throwable while clearing the thread's pending exception.
// Native unwinding may invoke other script destructors and Java callbacks, which
// must run with a usable JNI environment. The outer boundary reinstalls this
// same throwable after cleanup, preserving its identity, type and stack trace.
struct JavaException {
    struct Throwable {
        JavaVM* vm = nullptr;
        jthrowable value = nullptr;
        ~Throwable() {
            if (value) { try { VmEnvironment current(vm); current.env->DeleteGlobalRef(value); } catch (...) {} }
        }
    };
    std::shared_ptr<Throwable> throwable;
    explicit JavaException(JNIEnv* env) {
        auto local = env->ExceptionOccurred();
        env->ExceptionClear();
        JavaVM* vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK) {
            env->DeleteLocalRef(local);
            throw std::runtime_error("Cannot obtain JavaVM");
        }
        auto retained = static_cast<jthrowable>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        if (!retained) {
            env->ExceptionClear();
            throw std::bad_alloc();
        }
        try { throwable = std::make_shared<Throwable>(); }
        catch (...) { env->DeleteGlobalRef(retained); throw; }
        throwable->vm = vm;
        throwable->value = retained;
    }
};
void checked(JNIEnv* env) { if (env->ExceptionCheck()) throw JavaException(env); }
void require(JNIEnv* env, const void* value, const char* message) {
    checked(env);
    if (!value) throw std::invalid_argument(message);
}
void throwJava(JNIEnv* env, const char* kind, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    auto cls = env->FindClass(kind);
    if (cls) { env->ThrowNew(cls, message); env->DeleteLocalRef(cls); }
}
void reportException(JNIEnv* env) noexcept {
    if (env->ExceptionCheck()) return;
    try { throw; }
    catch (const JavaException& error) { env->Throw(error.throwable->value); }
    catch (const std::bad_alloc&) { throwJava(env, "java/lang/OutOfMemoryError", "Native allocation failed"); }
    catch (const std::out_of_range& e) { throwJava(env, "java/lang/IndexOutOfBoundsException", e.what()); }
    catch (const std::invalid_argument& e) { throwJava(env, "java/lang/IllegalArgumentException", e.what()); }
    catch (const std::exception& e) { throwJava(env, "java/lang/IllegalStateException", e.what()); }
    catch (...) { throwJava(env, "java/lang/IllegalStateException", "Unknown native runtime error"); }
}
template<class T, class F> T boundary(JNIEnv* env, T fallback, F&& action) noexcept {
    try { std::lock_guard<std::recursive_mutex> lock(runtime_mutex()); return action(); }
    catch (...) { reportException(env); return fallback; }
}
template<class F> void boundary(JNIEnv* env, F&& action) noexcept {
    try { std::lock_guard<std::recursive_mutex> lock(runtime_mutex()); action(); }
    catch (...) { reportException(env); }
}
struct LocalFrame {
    JNIEnv* env;
    explicit LocalFrame(JNIEnv* e) : env(e) { if (env->PushLocalFrame(32) < 0) throw JavaException(env); }
    ~LocalFrame() { env->PopLocalFrame(nullptr); }
};
struct CallScope { CallScope() { if (activeCalls >= 256) throw std::runtime_error("Native host call depth exceeded"); ++activeCalls; } ~CallScope() { --activeCalls; } };
address fromJAddress(jlong bits) { return address{std::bit_cast<std::uint64_t>(bits)}; }
jlong toJAddress(address value) { return std::bit_cast<jlong>(value.value); }
struct HeapBlock {
    address pointer;
    explicit HeapBlock(std::size_t count) : pointer(count ? heap::alloc(static_cast<int>(count)) : address{}) {}
    ~HeapBlock() { if (pointer.value != 0) { try { heap::free(pointer); } catch (...) {} } }
    HeapBlock(const HeapBlock&) = delete;
    HeapBlock& operator=(const HeapBlock&) = delete;
};
void requireScript() { if (!loadedScript) throw std::logic_error("No script is loaded"); }
void requireIdle() { if (activeCalls) throw std::logic_error("Cannot replace or snapshot a script during a function call"); }

// Java's byte[] UTF-8 conversion handles empty strings, NUL, CJK and supplementary
// characters. JNI modified UTF-8 is deliberately not used for script strings.
std::string fromJava(JNIEnv* env, jstring value) {
    require(env, value, "String must not be null");
    LocalFrame frame(env);
    auto cls = env->FindClass("java/lang/String"); checked(env);
    auto getBytes = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B"); checked(env);
    auto encoding = env->NewStringUTF("UTF-8"); checked(env);
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, encoding)); checked(env);
    const auto count = env->GetArrayLength(bytes);
    std::string result(static_cast<std::size_t>(count), '\0');
    if (count) env->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte*>(result.data()));
    checked(env);
    return result;
}
jstring toJava(JNIEnv* env, const std::string& value) {
    if (value.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) throw std::length_error("String is too long");
    if (env->PushLocalFrame(8) < 0) throw JavaException(env);
    try {
        auto cls = env->FindClass("java/lang/String"); checked(env);
        auto ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V"); checked(env);
        auto bytes = env->NewByteArray(static_cast<jsize>(value.size())); checked(env);
        if (!value.empty()) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()), reinterpret_cast<const jbyte*>(value.data()));
        checked(env);
        auto encoding = env->NewStringUTF("UTF-8"); checked(env);
        auto result = env->NewObject(cls, ctor, bytes, encoding); checked(env);
        return static_cast<jstring>(env->PopLocalFrame(result));
    } catch (...) { env->PopLocalFrame(nullptr); throw; }
}
std::shared_ptr<variable> fromJavaObject(JNIEnv* env, jobject object) {
    if (!object) return std::make_shared<variable>(nullptr);
    struct Kind { const char* name; const char* method; const char* signature; int type; };
    const Kind kinds[] = {{"java/lang/Integer", "intValue", "()I", INT_VALUE},
        {"java/lang/Float", "floatValue", "()F", FLOAT_VALUE},
        {"java/lang/Double", "doubleValue", "()D", DOUBLE_VALUE},
        {"java/lang/Boolean", "booleanValue", "()Z", BOOLEAN_VALUE},
        {"azertia/Address", "bits", "()J", ADDRESS_VALUE},
        {"java/lang/String", nullptr, nullptr, STRING_VALUE}};
    for (const auto& kind : kinds) {
        auto cls = env->FindClass(kind.name); checked(env);
        const auto match = env->IsInstanceOf(object, cls);
        if (!match) { env->DeleteLocalRef(cls); continue; }
        if (kind.type == STRING_VALUE) { env->DeleteLocalRef(cls); return std::make_shared<variable>(fromJava(env, static_cast<jstring>(object))); }
        auto method = env->GetMethodID(cls, kind.method, kind.signature); checked(env);
        std::shared_ptr<variable> result;
        switch (kind.type) {
            case INT_VALUE: result = std::make_shared<variable>(static_cast<int>(env->CallIntMethod(object, method))); break;
            case FLOAT_VALUE: result = std::make_shared<variable>(static_cast<float>(env->CallFloatMethod(object, method))); break;
            case DOUBLE_VALUE: result = std::make_shared<variable>(static_cast<double>(env->CallDoubleMethod(object, method))); break;
            case BOOLEAN_VALUE: result = std::make_shared<variable>(env->CallBooleanMethod(object, method) == JNI_TRUE); break;
            case ADDRESS_VALUE: result = std::make_shared<variable>(fromJAddress(env->CallLongMethod(object, method))); break;
        }
        checked(env); env->DeleteLocalRef(cls); return result;
    }
    throw std::invalid_argument("Callback must return Integer, Float, Double, Boolean, String, Address, or null");
}
std::vector<unsigned char> readFile(const std::string& name) {
    if (name.find('\0') != std::string::npos) throw std::invalid_argument("File name contains NUL");
    std::ifstream stream(std::filesystem::path(std::u8string(name.begin(), name.end())), std::ios::binary | std::ios::ate);
    if (!stream) throw std::runtime_error("Cannot open file: " + name);
    auto length = stream.tellg();
    if (length < 4 || length > static_cast<std::streamoff>(maxFileBytes)) throw std::invalid_argument("Invalid file size (expected 4 bytes to 64 MiB)");
    std::vector<unsigned char> bytes(static_cast<std::size_t>(length));
    stream.seekg(0); stream.read(reinterpret_cast<char*>(bytes.data()), length);
    if (!stream) throw std::runtime_error("Cannot read complete file: " + name);
    return bytes;
}
std::shared_ptr<variable> typedSlot(jlong pointer, int type) {
    auto value = heap::getAt(fromJAddress(pointer));
    if (value->type != type) throw std::invalid_argument("Heap value has the wrong type");
    return value;
}

struct CallbackContext {
    JavaVM* vm = nullptr;
    jclass caller = nullptr;
    jmethodID callback = nullptr;
    explicit CallbackContext(JNIEnv* env) {
        if (env->GetJavaVM(&vm) != JNI_OK) throw std::runtime_error("Cannot obtain JavaVM");
        auto local = env->FindClass("azertia/jni/Caller"); checked(env);
        callback = env->GetStaticMethodID(local, "callbackValue", "(I[J)Ljava/lang/Object;"); checked(env);
        caller = static_cast<jclass>(env->NewGlobalRef(local)); env->DeleteLocalRef(local); checked(env);
        if (!caller) throw std::bad_alloc();
    }
    ~CallbackContext() { if (caller) { try { VmEnvironment current(vm); current.env->DeleteGlobalRef(caller); } catch (...) {} } }
};
class JavaFunction final : public function {
    std::shared_ptr<CallbackContext> context;
    int id;
public:
    JavaFunction(std::shared_ptr<CallbackContext> c, int functionId) : context(std::move(c)), id(functionId) {}
    int return_type() override { return VOID_VALUE; }
    std::shared_ptr<variable> invoke(std::shared_ptr<environment>, std::vector<std::shared_ptr<variable>> arguments={}) override {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        CallScope calling;
        VmEnvironment current(context->vm);
        auto env = current.env;
        LocalFrame frame(env);
        HeapBlock block(arguments.size());
        std::vector<jlong> pointers(arguments.size());
        for (std::size_t i = 0; i < arguments.size(); ++i) {
            const address pointer{block.pointer.value + i};
            pointers[i] = toJAddress(pointer);
            heap::getAt(pointer)->copy_from(arguments[i]);
        }
        auto array = env->NewLongArray(static_cast<jsize>(pointers.size())); checked(env);
        if (!pointers.empty()) env->SetLongArrayRegion(array, 0, static_cast<jsize>(pointers.size()), pointers.data());
        checked(env);
        auto result = env->CallStaticObjectMethod(context->caller, context->callback, id, array);
        checked(env);
        return fromJavaObject(env, result);
    }
};
class JavaExecutor final : public executor {
    int name;
    std::shared_ptr<CallbackContext> context;
public:
    JavaExecutor(int ns, JNIEnv* env) : name(ns), context(std::make_shared<CallbackContext>(env)) {}
    int namespace_name() override { return name; }
    std::shared_ptr<function> getiFunction(int id) override { return std::make_shared<JavaFunction>(context, id); }
};

std::shared_ptr<AbdMapValue> encodeValue(const std::shared_ptr<variable>& value) {
    switch (value->type) {
        case INT_VALUE: return std::make_shared<IntAbdValue>(*static_cast<int*>(value->value));
        case ADDRESS_VALUE: return std::make_shared<AddressAbdValue>(*static_cast<address*>(value->value));
        case FLOAT_VALUE: return std::make_shared<FloatAbdValue>(*static_cast<float*>(value->value));
        case DOUBLE_VALUE: return std::make_shared<DoubleAbdValue>(*static_cast<double*>(value->value));
        case BOOLEAN_VALUE: return std::make_shared<BoolAbdValue>(*static_cast<bool*>(value->value));
        case STRING_VALUE: return std::make_shared<StringAbdValue>(*static_cast<std::string*>(value->value));
        case VOID_VALUE: { unsigned char sentinel = 0xff; return std::make_shared<ByteArrayValue>(&sentinel, 1); }
        default: throw std::invalid_argument("Unsupported value in snapshot");
    }
}
std::shared_ptr<variable> decodeValue(const std::shared_ptr<AbdMapValue>& value) {
    if (auto v = std::dynamic_pointer_cast<IntAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<AddressAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<FloatAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<DoubleAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<BoolAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<StringAbdValue>(value)) return std::make_shared<variable>(v->data);
    if (auto v = std::dynamic_pointer_cast<ByteArrayValue>(value); v && v->length == 1 && v->data[0] == 0xff) return std::make_shared<variable>(nullptr);
    throw std::invalid_argument("Invalid snapshot value");
}
template<class T> std::shared_ptr<T> field(const std::shared_ptr<AbdMap>& map, const std::string& key) {
    auto value = std::dynamic_pointer_cast<T>(map->get(key));
    if (!value) throw std::invalid_argument("Missing or invalid snapshot field: " + key);
    return value;
}
std::vector<module_manifest_entry> stableManifest() {
    requireScript();
    if (!loadedScript->setup || loadedScript->faulted)
        throw std::logic_error("Script must be successfully flushed before snapshot operations");
    auto modules = loadedScript->module_manifest();
    if (std::any_of(modules.begin(), modules.end(), [](const auto& entry) { return !entry.initialized; }))
        throw std::logic_error("Script initialization is incomplete");
    return modules;
}
std::shared_ptr<AbdMap> snapshot() {
    const auto modules = stableManifest();
    std::size_t moduleBytes = 0;
    for (const auto& module : modules) {
        if (module.bytes.size() > maxFileBytes - moduleBytes)
            throw std::length_error("Snapshot module manifest exceeds 64 MiB limit");
        moduleBytes += module.bytes.size();
    }
    auto result = std::make_shared<AbdMap>();
    result->put("snapshot version", std::make_shared<IntAbdValue>(6));
    auto manifest = std::make_shared<AbdArray>();
    for (const auto& module : modules) {
        auto entry = std::make_shared<AbdMap>();
        entry->put("bytes", std::make_shared<ByteArrayValue>(module.bytes.data(), static_cast<int>(module.bytes.size())));
        entry->put("has namespace", std::make_shared<BoolAbdValue>(module.actual_namespace.has_value()));
        if (module.actual_namespace) entry->put("namespace", std::make_shared<IntAbdValue>(*module.actual_namespace));
        entry->put("global offset", std::make_shared<IntAbdValue>(static_cast<int>(module.global_offset)));
        entry->put("global count", std::make_shared<IntAbdValue>(static_cast<int>(module.global_count)));
        entry->put("hint", std::make_shared<StringAbdValue>(module.hint));
        manifest->push_back(entry);
    }
    result->put("module manifest", manifest);
    auto globals = std::make_shared<AbdArray>();
    for (const auto& value : loadedScript->baseEnv->variables) globals->push_back(encodeValue(value));
    result->put("variable global", globals);
    auto owned = std::make_shared<AbdArray>();
    for (address pointer : loadedScript->baseEnv->owned_pointer) owned->push_back(std::make_shared<AddressAbdValue>(pointer));
    result->put("global owned allocations", owned);
    result->put("length heap", std::make_shared<IntAbdValue>(heap::lenHeap()));
    auto slots = std::make_shared<AbdArray>();
    for (int i = 0; i < heap::lenHeap(); ++i) slots->push_back(encodeValue(heap::getSlot(i)));
    result->put("heap", slots);
    auto allocations = std::make_shared<AbdArray>();
    for (int i = 0; i < heap::lenAlloc(); ++i) {
        const auto allocation = heap::allocAt(i);
        auto entry = std::make_shared<AbdMap>();
        entry->put("begin position", std::make_shared<AddressAbdValue>(allocation.startpos));
        entry->put("length", std::make_shared<IntAbdValue>(allocation.len));
        allocations->push_back(entry);
    }
    result->put("heap allocation", allocations);
    auto objects = std::make_shared<AbdArray>();
    for (const auto& object : heap::object_records()) {
        if (object.script_owner.lock() != loadedScript) throw std::invalid_argument("Cannot snapshot an object belonging to another script");
        auto entry = std::make_shared<AbdMap>();
        entry->put("begin position", std::make_shared<AddressAbdValue>(object.startpos));
        entry->put("has destructor", std::make_shared<BoolAbdValue>(object.destructor_id.has_value()));
        if (object.destructor_id) entry->put("destructor", std::make_shared<IntAbdValue>(*object.destructor_id));
        entry->put("manual", std::make_shared<BoolAbdValue>(object.manual));
        objects->push_back(entry);
    }
    result->put("objects", objects);
    return result;
}
void restoreSnapshot(const std::vector<unsigned char>& bytes) {
    const auto modules = stableManifest();
    auto result = std::make_shared<AbdMap>(std::make_shared<AbdStack>(AbdValue::fromBytes(bytes.data(), bytes.size())));
    if (field<IntAbdValue>(result, "snapshot version")->data != 6)
        throw std::invalid_argument("Unsupported snapshot version; expected v6");
    auto manifest = field<AbdArray>(result, "module manifest");
    if (manifest->values.size() != modules.size()) throw std::invalid_argument("Snapshot module count differs from script");
    for (std::size_t i = 0; i < modules.size(); ++i) {
        auto entry = std::dynamic_pointer_cast<AbdMap>(manifest->values[i]);
        if (!entry) throw std::invalid_argument("Invalid snapshot module manifest entry");
        const auto& module = modules[i];
        auto code = field<ByteArrayValue>(entry, "bytes");
        const bool hasNamespace = field<BoolAbdValue>(entry, "has namespace")->data;
        if (code->length != static_cast<int>(module.bytes.size()) ||
            !std::equal(module.bytes.begin(), module.bytes.end(), code->data) ||
            hasNamespace != module.actual_namespace.has_value() ||
            (hasNamespace && field<IntAbdValue>(entry, "namespace")->data != *module.actual_namespace) ||
            (!hasNamespace && entry->get("namespace")) ||
            field<IntAbdValue>(entry, "global offset")->data != static_cast<int>(module.global_offset) ||
            field<IntAbdValue>(entry, "global count")->data != static_cast<int>(module.global_count) ||
            field<StringAbdValue>(entry, "hint")->data != module.hint)
            throw std::invalid_argument("Snapshot module manifest differs from mounted script");
    }
    std::vector<std::shared_ptr<variable>> restoredGlobals;
    const auto& originals = loadedScript->baseEnv->variables;
    restoredGlobals.reserve(originals.size());
    auto globals = field<AbdArray>(result, "variable global");
    if (globals->values.size() != originals.size()) throw std::invalid_argument("Snapshot global count differs from script");
    for (std::size_t i = 0; i < originals.size(); ++i) {
        auto value = decodeValue(globals->values[i]);
        value->name = originals[i]->name;
        restoredGlobals.push_back(std::move(value));
    }
    auto values = field<AbdArray>(result, "heap");
    if (field<IntAbdValue>(result, "length heap")->data != static_cast<int>(values->values.size())) throw std::invalid_argument("Snapshot heap length is inconsistent");
    std::vector<std::shared_ptr<variable>> slots;
    for (const auto& value : values->values) slots.push_back(decodeValue(value));
    auto entries = field<AbdArray>(result, "heap allocation");
    std::vector<heap::heap_allocation> allocations;
    for (const auto& entry : entries->values) {
        auto map = std::dynamic_pointer_cast<AbdMap>(entry);
        if (!map) throw std::invalid_argument("Invalid snapshot allocation");
        heap::heap_allocation allocation{field<AddressAbdValue>(map, "begin position")->data, field<IntAbdValue>(map, "length")->data};
        allocations.push_back(allocation);
    }
    std::vector<address> owned;
    for (const auto& item : field<AbdArray>(result, "global owned allocations")->values) {
        auto pointer = std::dynamic_pointer_cast<AddressAbdValue>(item);
        if (!pointer || std::find(owned.begin(), owned.end(), pointer->data) != owned.end() ||
            std::none_of(allocations.begin(), allocations.end(), [&](const auto& a) { return a.startpos == pointer->data; }))
            throw std::invalid_argument("Invalid snapshot global ownership");
        owned.push_back(pointer->data);
    }
    std::vector<heap::object_record> objects;
    for (const auto& entry : field<AbdArray>(result, "objects")->values) {
        auto map = std::dynamic_pointer_cast<AbdMap>(entry);
        if (!map) throw std::invalid_argument("Invalid snapshot object");
        std::optional<int> destructor;
        if (field<BoolAbdValue>(map, "has destructor")->data) destructor = field<IntAbdValue>(map, "destructor")->data;
        else if (map->get("destructor")) throw std::invalid_argument("Unexpected snapshot destructor id");
        objects.push_back({field<AddressAbdValue>(map, "begin position")->data,
                           destructor,
                           field<BoolAbdValue>(map, "manual")->data, loadedScript});
    }
    // The heap validates all ranges, objects, destructor signatures and owners
    // before atomically replacing state. The final globals swap cannot throw.
    // Replacing a snapshot intentionally does not execute the old destructors.
    heap::restore(std::move(slots), std::move(allocations), std::move(objects),
                  loadedScript->baseEnv, std::move(owned));
    loadedScript->baseEnv->variables.swap(restoredGlobals);
}
std::string memoryReport() {
    requireScript();
    std::ostringstream out;
    out << "AZERTIAN RUNTIME\nGlobal slots: " << loadedScript->baseEnv->variables.size() << '\n';
    for (std::size_t i = 0; i < loadedScript->baseEnv->variables.size(); ++i) {
        const auto& value = loadedScript->baseEnv->variables[i];
        out << (value->name.empty() ? "-" + std::to_string(i + 1) : value->name)
            << " :: type " << static_cast<int>(value->type) << '\n';
    }
    out << "Heap slots: " << heap::lenHeap() << "\nAllocations: " << heap::lenAlloc() << '\n';
    return out.str();
}
} // namespace

extern "C" {
JNIEXPORT void JNICALL Java_azertia_jni_Caller_init(JNIEnv* env, jclass) {
    boundary(env, [] {});
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller_loadScript(JNIEnv* env, jclass, jstring file) {
    boundary(env, [&] {
        requireIdle();
        if (loadedScript) throw std::logic_error("A script is already loaded");
        auto bytes = readFile(fromJava(env, file));
        CallScope loading;
        loadedScript = load_script(bytes.data(), bytes.size());
    });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller_insertScript(JNIEnv* env, jclass, jstring file) {
    boundary(env, [&] {
        requireScript(); requireIdle();
        auto bytes = readFile(fromJava(env, file));
        CallScope inserting;
        loadedScript->insert_script(bytes.data(), bytes.size());
    });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller_flush(JNIEnv* env, jclass) {
    boundary(env, [&] {
        requireScript(); requireIdle();
        CallScope flushing;
        loadedScript->flush();
    });
}
JNIEXPORT jint JNICALL Java_azertia_jni_Caller_namespaceForHint(JNIEnv* env, jclass, jstring hint) {
    return boundary<jint>(env, 0, [&] {
        requireScript();
        return static_cast<jint>(loadedScript->namespace_for_hint(fromJava(env, hint)));
    });
}
JNIEXPORT jlong JNICALL Java_azertia_jni_Caller_call(JNIEnv* env, jclass, jint id, jlongArray args) {
    return boundary<jlong>(env, 0, [&] {
        requireScript(); require(env, args, "Argument array must not be null");
        const auto length = env->GetArrayLength(args);
        std::vector<jlong> pointers(static_cast<std::size_t>(length));
        if (length) env->GetLongArrayRegion(args, 0, length, pointers.data()); checked(env);
        std::vector<std::shared_ptr<variable>> values;
        for (jlong pointer : pointers) values.push_back(heap::getAt(fromJAddress(pointer))->deepCopy());
        CallScope calling;
        auto result = loadedScript->invoke(id, std::move(values));
        if (!result || result->type == VOID_VALUE) return jlong(0);
        HeapBlock storage(1);
        heap::getAt(storage.pointer)->copy_from(result);
        const address pointer = storage.pointer; storage.pointer = address{};
        return toJAddress(pointer);
    });
}
JNIEXPORT jint JNICALL Java_azertia_jni_Caller_getMemType(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jint>(env, 0, [&] { return static_cast<jint>(heap::getAt(fromJAddress(pointer))->type); });
}
JNIEXPORT jstring JNICALL Java_azertia_jni_Caller_getMemStr(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jstring>(env, nullptr, [&] { return toJava(env, *static_cast<std::string*>(typedSlot(pointer, STRING_VALUE)->value)); });
}
JNIEXPORT jfloat JNICALL Java_azertia_jni_Caller_getMemFloat(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jfloat>(env, 0, [&] { return *static_cast<float*>(typedSlot(pointer, FLOAT_VALUE)->value); });
}
JNIEXPORT jdouble JNICALL Java_azertia_jni_Caller_getMemDouble(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jdouble>(env, 0, [&] { return *static_cast<double*>(typedSlot(pointer, DOUBLE_VALUE)->value); });
}
JNIEXPORT jboolean JNICALL Java_azertia_jni_Caller_getMemBool(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jboolean>(env, JNI_FALSE, [&] { return static_cast<jboolean>(*static_cast<bool*>(typedSlot(pointer, BOOLEAN_VALUE)->value) ? JNI_TRUE : JNI_FALSE); });
}
JNIEXPORT jint JNICALL Java_azertia_jni_Caller_getMemInt(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jint>(env, 0, [&] { return *static_cast<int*>(typedSlot(pointer, INT_VALUE)->value); });
}
JNIEXPORT jlong JNICALL Java_azertia_jni_Caller_getMemAddress(JNIEnv* env, jclass, jlong pointer) {
    return boundary<jlong>(env, 0, [&] { return toJAddress(*static_cast<address*>(typedSlot(pointer, ADDRESS_VALUE)->value)); });
}
JNIEXPORT jlong JNICALL Java_azertia_jni_Caller20_memAlloc(JNIEnv* env, jclass, jint size) {
    return boundary<jlong>(env, 0, [&] { return toJAddress(heap::alloc(size)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller20_memFree(JNIEnv* env, jclass, jlong pointer) {
    boundary(env, [&] {
        if (!pointer) return;
        const address nativePointer = fromJAddress(pointer);
        // Java may release its own blocks and global (base scope) allocations. A block
        // that a running script scope will release itself must stay with that scope.
        const environment* owner = heap::owner_of(nativePointer);
        environment* base = loadedScript && loadedScript->baseEnv ? loadedScript->baseEnv.get() : nullptr;
        if (owner && owner != base)
            throw std::invalid_argument("Pointer is scheduled for automatic release by a running script scope and cannot be freed from Java");
        if (!heap::free(nativePointer)) throw std::invalid_argument("Pointer is not the start of an active allocation");
        if (owner) {
            auto& owners = loadedScript->baseEnv->owned_pointer;
            owners.erase(std::remove(owners.begin(), owners.end(), nativePointer), owners.end());
        }
    });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller20_destroyScript(JNIEnv* env, jclass) {
    boundary(env, [&] {
        requireIdle();
        CallScope destroying;
        try { if (loadedScript) loadedScript->destroy(); }
        catch (...) { loadedScript.reset(); throw; }
        loadedScript.reset();
    });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMem__JI(JNIEnv* env, jclass, jlong pointer, jint value) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(static_cast<int>(value)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMem__JF(JNIEnv* env, jclass, jlong pointer, jfloat value) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(static_cast<float>(value)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMem__JD(JNIEnv* env, jclass, jlong pointer, jdouble value) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(static_cast<double>(value)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMem__JZ(JNIEnv* env, jclass, jlong pointer, jboolean value) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(value == JNI_TRUE); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMem__JLjava_lang_String_2(JNIEnv* env, jclass, jlong pointer, jstring value) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(fromJava(env, value)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMemAddress(JNIEnv* env, jclass, jlong pointer, jlong bits) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(fromJAddress(bits)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller220_ACputMemNull(JNIEnv* env, jclass, jlong pointer) {
    boundary(env, [&] { heap::getAt(fromJAddress(pointer))->setValue(nullptr); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller2220_bindNamespace(JNIEnv* env, jclass, jint ns) {
    boundary(env, [&] { if (ns < 0 || ns > 65535) throw std::invalid_argument("Namespace must be an unsigned 16-bit integer"); registerExecutor(std::make_shared<JavaExecutor>(ns, env)); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller2220_unbindNamespace(JNIEnv* env, jclass, jint ns) {
    boundary(env, [&] { unregisterExecutor(ns); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller22220_printGlobalMemories(JNIEnv* env, jclass) {
    boundary(env, [&] { std::cout << memoryReport(); });
}
JNIEXPORT jstring JNICALL Java_azertia_jni_Caller22220_globalMemories2Str(JNIEnv* env, jclass) {
    return boundary<jstring>(env, nullptr, [&] { return toJava(env, memoryReport()); });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller22220_saveStatusToFile(JNIEnv* env, jclass, jstring file) {
    boundary(env, [&] {
        requireScript(); requireIdle();
        const auto name = fromJava(env, file);
        if (name.find('\0') != std::string::npos) throw std::invalid_argument("File name contains NUL");
        auto value = snapshot()->toAbdValue();
        if (value->size > static_cast<int>(maxFileBytes - 4)) throw std::length_error("Snapshot exceeds 64 MiB limit");
        auto bytes = value->toBytes();
        auto target = std::filesystem::path(std::u8string(name.begin(), name.end()));
        auto parent = target.parent_path();
        if (parent.empty()) parent = ".";
        // An exclusively created sibling directory stages a complete file before
        // rename, keeping an existing snapshot intact on a failed write.
        static std::uint64_t serial = 0;
        std::filesystem::path staging;
        for (int attempt = 0; attempt < 32; ++attempt) {
            auto stamp = std::chrono::steady_clock::now().time_since_epoch().count();
            auto candidate = parent / (".azscript-snapshot-" + std::to_string(stamp) + "-" + std::to_string(++serial));
            if (std::filesystem::create_directory(candidate)) { staging = std::move(candidate); break; }
        }
        if (staging.empty()) throw std::runtime_error("Cannot create snapshot staging directory");
        const auto temporary = staging / "snapshot.abd";
        try {
            std::ofstream out(temporary, std::ios::binary | std::ios::trunc);
            if (!out) throw std::runtime_error("Cannot open snapshot for writing: " + name);
            out.write(reinterpret_cast<const char*>(bytes.get()), value->size + 4); out.close();
            if (!out) throw std::runtime_error("Cannot write complete snapshot: " + name);
            std::filesystem::rename(temporary, target);
        } catch (...) {
            std::error_code ignored;
            std::filesystem::remove(temporary, ignored); std::filesystem::remove(staging, ignored);
            throw;
        }
        std::error_code ignored;
        std::filesystem::remove(staging, ignored);
    });
}
JNIEXPORT void JNICALL Java_azertia_jni_Caller22220_loadStatusFromFile(JNIEnv* env, jclass, jstring file) {
    boundary(env, [&] { requireScript(); requireIdle(); restoreSnapshot(readFile(fromJava(env, file))); });
}
} // extern C
