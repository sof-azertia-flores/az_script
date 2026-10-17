#ifndef ABDINVOKER_LIBRARY_H
#define ABDINVOKER_LIBRARY_H
#include <cstddef>
#include <cstdint>
#include <exception>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <vector>
#include <azscript/runtime_abi.hpp>
#include "../abdC/azertianBinaryDataValues.h"
#include "vm/value.h"

namespace azertian {
inline constexpr std::size_t MAX_VARIABLE_SLOTS=1048576;
// The only exec format the loader reads; ExecCodec.VERSION in the compiler matches it.
inline constexpr int EXEC_FORMAT_VERSION=10;
class expression; class function; class environment; class variable; class script; struct loaded_module;
struct slot_block; struct function_frame;
struct type_context;
using type_contexts=std::vector<std::shared_ptr<const type_context>>;
// Bound generic operations contain no frame pointers and can outlive a call.
struct type_context {
    int abi=VOID_VALUE,kind=0;
    int width=1;
    std::optional<int> factory_id;
    type_contexts contexts;
    std::optional<int> placement_id;
    type_contexts placement_contexts;
    std::shared_ptr<const type_context> element;
};
struct bound_method {int function_id;type_contexts contexts;};
using method_table=std::vector<bound_method>;
void validate_method_table(const method_table& methods,const std::shared_ptr<script>& script);
struct context_spec;
// A source position of an exec debug record; file indexes function_debug::files.
// Line and column are 1-based; a header position of 0:0 is unknown.
struct source_position {
    std::uint32_t file=0,line=0,column=0;
};
// The optional debug record of a function, read from an exec compiled with -g. It
// never affects execution: expression::location indexes its locations.
struct function_debug {
    std::string name;
    std::shared_ptr<const std::vector<std::string>> files;
    source_position header;
    std::vector<source_position> locations;
};
// Frames are recorded innermost first. Debug names and positions are absent
// when the source was compiled without -g; function IDs are always available.
struct trace_frame {
    int function_id=0;
    bool native=false;
    std::string name,file;
    std::uint32_t line=0,column=0;
};
struct error_trace {
    std::vector<trace_frame> frames;
    std::size_t omitted_frames=0;
};
std::string function_id_text(int id);
const error_trace& last_error_trace() noexcept;
std::string format_error_trace(const error_trace& trace);
// Adds frames only for the exception type and message of the last failure on
// this thread. The original exception object and its dynamic type are retained.
std::string describe_error(const std::exception& error);
void clear_error_trace() noexcept;
namespace detail {struct trace_state;}
// Cleanup paths retain the first exception together with its trace, then
// discard traces from later cleanup failures before restoring the first one.
class pending_error {
    friend struct detail::trace_state;
    std::exception_ptr exception_;
    error_trace trace_;
public:
    pending_error()=default;
    static pending_error capture() noexcept;
    explicit operator bool() const noexcept;
    void capture_current() noexcept;
    [[noreturn]] void rethrow() const;
};
struct function_signature {
    int return_type=VOID_VALUE;
    std::vector<int> param_types;
    int hidden_count=0,entry_kind=0;
    bool operator==(const function_signature&) const=default;
};
// The compatibility heap/executor registry is shared. All entry points use this
// recursive lock so host callbacks may call back into the VM on the same thread.
std::recursive_mutex& runtime_mutex();
struct module_manifest_entry {
    std::vector<unsigned char> bytes;
    std::optional<int> actual_namespace;
    std::size_t global_offset=0,global_count=0;
    std::string hint;
    bool initialized=false;
};
// Loading only mounts code. flush() resolves every module before any onload runs.
std::shared_ptr<script> load_script(const unsigned char* bytes, std::size_t length);
[[deprecated("Use load_script(bytes, length) for bounded input")]]
std::shared_ptr<script> load_script(unsigned char* bytes);
class script : public std::enable_shared_from_this<script> {
public:
    std::shared_ptr<AbdMap> meta;
    std::shared_ptr<environment> baseEnv;
    std::map<int, std::shared_ptr<function>> functions;
    // Every source-level extern declaration emits one of these signatures. The runtime
    // enforces it around the resolved host callback.
    std::map<int, function_signature> extern_signatures;
    // Namespaces (id >> 16) that hold script functions, including the lifecycle
    // namespace 0. Ids inside them are never resolved through a host executor.
    std::set<int> script_namespaces{0};
    std::set<int> fixed_host_namespaces;
    std::vector<std::shared_ptr<function>> on_destory;
    std::uint64_t max_steps=1000000;
    std::size_t max_call_depth=256;
    // Native stack bytes execution may use below the outermost script call on
    // the calling thread. 0 relies on the thread's detected stack bounds; those
    // bounds, less a reserve, always apply as well.
    std::size_t max_stack_bytes=0;
    std::uint64_t remaining_steps=0;
    std::size_t active_calls=0;
    bool closed=false;
    bool destroying=false;
    bool setup=false;
    bool faulted=false;
    std::shared_ptr<function> getFunction(int id);
    bool hasFunction(int id);
    std::shared_ptr<variable> invoke(int id, std::vector<std::shared_ptr<variable>> args={});
    void flush();
    int namespace_for_hint(const std::string& hint) const;
    bool hint_loaded(const std::string& hint) const;
    std::vector<module_manifest_entry> module_manifest() const;

    void insert_script(const unsigned char* bytes, std::size_t length);
    void destroy();
    // Internal execution also uses this check, so cached function handles cannot
    // bypass setup, fault, or destruction state.
    void require_execution() const;
private:
    std::vector<std::shared_ptr<loaded_module>> modules;
    bool initializing=false;
};
class function {
public:
    virtual ~function()=default;
    virtual int return_type()=0;
    virtual std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,
        std::vector<std::shared_ptr<variable>> arguments={})=0;
};
class ofunction : public function {
public:
    int function_id=0;
    int rett=VOID_VALUE;
    int param_count=0;
    int local_count=0;
    int hidden_count=0,entry_kind=0;
    bool linked=false;
    std::weak_ptr<script> script_owner;
    // Negative variable ids are relative to the module that defined this
    // function, including lifecycle hooks and object destructors.
    std::size_t global_offset=0;
    std::size_t global_count=0;
    std::vector<int> param_types;
    // Null without debug info.
    std::shared_ptr<const function_debug> debug;
    int return_type() override;
    ofunction()=default;
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,
        std::vector<std::shared_ptr<variable>> arguments={}) override;
    std::shared_ptr<variable> invoke_bound(std::shared_ptr<environment> env,
        std::vector<std::shared_ptr<variable>> arguments,type_contexts contexts);
    std::shared_ptr<expression> code;
};
class expression {
public:
    virtual ~expression()=default;
    int return_type=VOID_VALUE;
    // 1-based index into the owning function's debug locations; 0 inherits the
    // position of the enclosing expression.
    std::uint32_t location=0;
    virtual std::shared_ptr<variable> execute(std::shared_ptr<environment> env)=0;
};
class complex_expression : public expression {
public:
    std::vector<std::shared_ptr<expression>> expressions;
    complex_expression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
    std::shared_ptr<variable> execute_body(std::shared_ptr<environment> env);
};
class functionInvocationExpression : public expression {
public:
    std::vector<std::shared_ptr<expression>> params;
    std::vector<std::shared_ptr<context_spec>> contexts;
    int function_id=0;
    functionInvocationExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varDefineExpression : public expression {
public:
    int varindex=0;
    std::shared_ptr<azertian::expression> initializer;
    varDefineExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varSetExpression : public expression {
public:
    int varindex=0;
    std::shared_ptr<azertian::expression> expression;
    varSetExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class returnExpression : public expression {
public:
    std::shared_ptr<expression> returnType;
    bool object_return=false;
    returnExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varExpression : public expression {
public:
    int varindex=0;
    varExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class constantExpression : public expression {
public:
    std::shared_ptr<variable> value;
    explicit constantExpression(std::shared_ptr<AbdMapValue> value);
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
#define AZ_BINARY_EXPRESSION(Name) \
class Name : public expression { public: \
    std::shared_ptr<expression> exp1,exp2; \
    Name()=default; \
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override; };
AZ_BINARY_EXPRESSION(addExpression)
AZ_BINARY_EXPRESSION(minusExpression)
AZ_BINARY_EXPRESSION(multiplyExpression)
AZ_BINARY_EXPRESSION(divideExpression)
AZ_BINARY_EXPRESSION(equalExpression)
AZ_BINARY_EXPRESSION(gtExpression)
AZ_BINARY_EXPRESSION(ltExpression)
AZ_BINARY_EXPRESSION(movExpression)
AZ_BINARY_EXPRESSION(whileExpression)
#undef AZ_BINARY_EXPRESSION
class ifExpression : public expression {
public:
    std::shared_ptr<expression> exp1,exp2,otherwise;
    ifExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class breakExpression : public expression {
public:
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class continueExpression : public expression {
public:
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class variable {
public:
    variable()=default;
    std::string name;
    int type_tag() const noexcept { return value_.type_tag(); }
    // Access is checked and read-only. The reference remains valid until this
    // variable is assigned or destroyed; use setValue() to replace its value.
    template<class T> const T& get() const {return value_.get<T>();}
    // Non-object values, including void, have no object handle.
    std::shared_ptr<slot_block> object_handle() const noexcept;
    // Storage transfer exchanges only payloads, retaining each slot's metadata.
    // Ownership bookkeeping belongs to the storage manager invoking this.
    void swap_storage(variable& other) noexcept;
    // Set only for the slots of a literal object; copies never inherit it.
    slot_block* container=nullptr;
    std::shared_ptr<variable> deepCopy();
    void copy_from(std::shared_ptr<variable> from);
    variable(const variable& other);
    variable& operator=(const variable& other);
    explicit variable(int value);
    explicit variable(address value);
    explicit variable(std::string value);
    explicit variable(const char* value);
    explicit variable(double value);
    explicit variable(float value);
    explicit variable(bool value);
    explicit variable(std::nullptr_t value);
    // Object values are handles: copying a variable shares the block; the
    // interpreter's store rule decides when all of its slots are copied.
    explicit variable(std::shared_ptr<slot_block> value);
    ~variable();
    void setValue(int value);
    void setValue(address value);
    void setValue(std::string value);
    void setValue(const char* value);
    void setValue(double value);
    void setValue(float value);
    void setValue(bool value);
    void setValue(std::nullptr_t value);
    void setValue(std::shared_ptr<slot_block> value);
private:
    vm::Value value_;
    void clear() noexcept;
    void replace_value(vm::Value value) noexcept;
    void copy_value(const variable& other);
};
struct function_frame {
    bool returned=false;
    bool break_requested=false;
    bool continue_requested=false;
    std::size_t loop_depth=0;
    std::size_t global_offset=0,global_count=0,param_count=0;
    // Zero inherits the function header. Return conversion is diagnosed at
    // the return statement after its expression has completed.
    std::uint32_t location=0,return_location=0;
    // nullptr means not declared in the currently active lexical scope.
    std::vector<std::shared_ptr<variable>> slots;
    type_contexts hidden_contexts;
    std::shared_ptr<variable> result=std::make_shared<variable>(nullptr);
    // Unbound object temporaries; each statement destroys the ones it created.
    std::vector<std::shared_ptr<slot_block>> temporaries;
    function_frame()=default;
    function_frame(const function_frame&)=delete;
    function_frame& operator=(const function_frame&)=delete;
    ~function_frame();
};
class environment {
public:
    std::vector<address> owned_pointer;
    std::weak_ptr<environment> parent;
    // Caller is separate from the lexical parent: functions cannot see locals
    // in their callers, while explicit allocation transfer still can.
    std::weak_ptr<environment> caller;
    std::vector<std::shared_ptr<variable>> variables;
    std::weak_ptr<azertian::script> script;
    std::shared_ptr<function_frame> frame;
    std::vector<std::size_t> declared_slots;
    bool closing=false;
    // The function-level scope of a frame also finalizes parameters and temporaries.
    bool frame_root=false;
    void cleanup(const std::shared_ptr<environment>& self);
    ~environment();
    std::shared_ptr<variable> getVariable(std::string name);
    std::shared_ptr<variable> getVariable(int index);
    bool testForVar(std::string name);
};
std::string value_to_string(const std::shared_ptr<variable>& value);
// Used by the process-wide executor registry to reject namespace collisions.
bool script_namespace_in_use(int namespace_id);
std::shared_ptr<variable> invoke_bound_function(const std::shared_ptr<script>& script,int id,
    const std::shared_ptr<environment>& env,std::vector<std::shared_ptr<variable>> arguments,type_contexts contexts);
void validate_type_contexts(const type_contexts& contexts,const std::shared_ptr<script>& script);
}
#endif
