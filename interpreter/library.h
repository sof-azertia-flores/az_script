#ifndef ABDINVOKER_LIBRARY_H
#define ABDINVOKER_LIBRARY_H
#include <cstddef>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <string>
#include <vector>
#include "../abdC/azertianBinaryDataValues.h"

namespace azertian {
inline constexpr int INT_VALUE=0, STRING_VALUE=1, FLOAT_VALUE=2, DOUBLE_VALUE=3,
                     BOOLEAN_VALUE=4, VOID_VALUE=5, ANY_VALUE=6, ADDRESS_VALUE=7, OBJECT_VALUE=8;
inline constexpr std::size_t MAX_VARIABLE_SLOTS=1048576;
class expression; class function; class environment; class variable; class script; struct loaded_module;
struct slot_block; struct function_frame;
struct type_context;
using type_contexts=std::vector<std::shared_ptr<const type_context>>;
// Bound generic operations contain no frame pointers and can outlive a call.
struct type_context {
    int abi=VOID_VALUE,kind=0;
    std::optional<int> factory_id;
    type_contexts contexts;
};
struct context_spec;
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
    std::string name;
    char type=VOID_VALUE;
    void* value=nullptr;
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
    void clear() noexcept;
    void copy_value(const variable& other);
};
struct function_frame {
    bool returned=false;
    bool break_requested=false;
    bool continue_requested=false;
    std::size_t loop_depth=0;
    std::size_t global_offset=0,global_count=0,param_count=0;
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
