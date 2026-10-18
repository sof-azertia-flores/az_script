#include "library.h"
#include "blocks.h"
#include "core/statement.h"
#include "core/lifecycle.h"
#include "heepalloc.h"
#include "internelFunctions.h"
#include "vm/session.h"
#include "core/trace.h"
#include "core/admission.h"
#include "core/ids.h"
#include "core/exec.h"
#include "core/link.h"
#include "core/value_ops.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <stdexcept>
#include <system_error>
#include <typeinfo>
#include <type_traits>
#include <utility>

namespace azertian {
namespace {
using trace::entry_scope;
// Frames of tree functions use the shared recorder and their current location.
void note_script_frame(const ofunction& function,const std::shared_ptr<function_frame>& frame) noexcept {
    trace::note_script_frame(function.function_id,function.debug.get(),frame?frame->location:0);
}
}
struct context_spec {
    int ref=-1,abi=VOID_VALUE,kind=0,width=1;
    std::optional<int> factory_id;
    std::vector<std::shared_ptr<context_spec>> contexts;
    std::optional<int> placement_id;
    std::vector<std::shared_ptr<context_spec>> placement_contexts;
    std::shared_ptr<context_spec> element;
};
struct function_reference {
    int original_id;
    int* target;
    bool destructor=false;
    int hidden_count=0;
    bool factory=false,placement=false,method=false;
};
struct loaded_module {
    module_manifest_entry manifest;
    std::shared_ptr<AbdMap> meta;
    std::map<int,std::shared_ptr<ofunction>> definitions;
    std::map<int,function_signature> extern_signatures;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
    std::set<int> fixed_namespaces;
    std::set<int> fixed_extern_namespaces;
    std::set<std::size_t> dependencies;
    std::shared_ptr<ofunction> onload,pre_destroy;
};
namespace {
using V=std::shared_ptr<variable>;
using E=std::shared_ptr<environment>;
using M=std::shared_ptr<AbdMap>;
V nil() { return std::make_shared<variable>(nullptr); }
// Runtime messages spell IDs like source and the CLI do: 0x plus the full
// 32-bit pattern, so negative and high-bit IDs read the same everywhere.
std::string hex_text(std::uint32_t value,int digits) {
    char text[16];std::snprintf(text,sizeof text,"0x%0*x",digits,static_cast<unsigned>(value));
    return text;
}
std::string namespace_text(int ns) { return hex_text(static_cast<std::uint32_t>(ns)&0xffffu,4); }
class at_location {
    std::shared_ptr<function_frame> frame;
    std::uint32_t previous=0;
    int exceptions=std::uncaught_exceptions();
public:
    at_location(const E& env,std::uint32_t location):frame(env?env->frame:nullptr) {
        if(frame){previous=frame->location;if(location)frame->location=location;}
    }
    ~at_location() {
        // An exceptional path keeps the innermost failing position until the
        // owning function has recorded its frame.
        if(frame&&std::uncaught_exceptions()==exceptions)frame->location=previous;
    }
};
V run(const std::shared_ptr<expression>& code,const E& env) {
    at_location location(env,code->location);return code->execute(env);
}
void mark_returned(const E& env,V result) {
    env->frame->return_location=env->frame->location;
    env->frame->result=std::move(result);env->frame->returned=true;
}
std::shared_ptr<const type_context> bind_context(const std::shared_ptr<context_spec>& spec,const E& env,std::size_t depth=0) {
    if(!spec||depth>=128)throw std::runtime_error("Generic context nesting exceeds 128 levels");
    if(spec->ref>=0) {
        if(!env||!env->frame||static_cast<std::size_t>(spec->ref)>=env->frame->hidden_contexts.size())
            throw std::runtime_error("Generic context reference is unavailable");
        return env->frame->hidden_contexts[static_cast<std::size_t>(spec->ref)];
    }
    auto result=std::make_shared<type_context>();result->abi=spec->abi;result->kind=spec->kind;result->width=spec->width;result->factory_id=spec->factory_id;result->placement_id=spec->placement_id;
    for(auto& child:spec->contexts)result->contexts.push_back(bind_context(child,env,depth+1));
    for(auto& child:spec->placement_contexts)result->placement_contexts.push_back(bind_context(child,env,depth+1));
    if(spec->element)result->element=bind_context(spec->element,env,depth+1);
    return result;
}
type_contexts bind_contexts(const std::vector<std::shared_ptr<context_spec>>& specs,const E& env) {
    type_contexts result;result.reserve(specs.size());
    for(auto& spec:specs)result.push_back(bind_context(spec,env));
    return result;
}
struct parsed_module {
    M meta;
    std::size_t global_count=0;
    std::map<int,std::shared_ptr<function>> functions;
    std::map<int,function_signature> extern_signatures;
    std::string hint;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
};
using ids::namespace_of;
int relocate_id(int id,int ns) noexcept {return ids::relocate(id,ns);}
using module_list=std::vector<std::shared_ptr<loaded_module>>;
// Ordinary modules keep their compiled IDs, so a collision names the earlier
// owner and how to separate the modules. Module ordinals count successful
// inserts and the primary script is #0. The owner is always ordinary: hint
// libraries move to a free namespace, and a fixed ID inside a mounted hint
// namespace is rejected as a namespace conflict first.
std::string duplicate_function_message(int id,const module_list& modules) {
    std::string message="Duplicate function id: "+function_id_text(id)+" (namespace "+namespace_text(namespace_of(id))+
        ", position "+namespace_text(id&0xffff)+") is already defined";
    for(std::size_t i=0;i<modules.size();++i) {
        const auto& definitions=modules[i]->definitions;
        if(std::any_of(definitions.begin(),definitions.end(),[&](const auto& entry){return entry.second->function_id==id;})) {
            message+=" by module #"+std::to_string(i)+" (ordinary)";break;
        }
    }
    if(id==0x0fff0000)return message+"; only one module may define main, so remove main from the inserted module";
    if(namespace_of(id)==0x0fff)
        return message+"; modules without #namespace share the default namespace 0x0fff, so give each module its own "
            "#namespace away from the low range hint libraries take, insert fixed-namespace modules before hint libraries, "
            "or use #namespace_hint";
    return message+"; give each module a distinct #namespace, or use #namespace_hint";
}
// Hint libraries take the lowest free namespace when inserted, so a later fixed
// module can find its namespace already mounted.
std::string mounted_hint_conflict(const char* what,int ns,const module_list& modules,std::size_t index,const char* remedy) {
    return std::string(what)+" "+namespace_text(ns)+" conflicts with hint "+modules[index]->manifest.hint+
        ", which module #"+std::to_string(index)+" mounted there; hint libraries take the lowest free namespace, so "+remedy;
}
std::vector<std::weak_ptr<script>>& live_scripts(){static std::vector<std::weak_ptr<script>> result;return result;}
std::shared_ptr<script> owner(E env) {
    if(!env) throw std::runtime_error("Missing execution environment");
    auto s=env->script.lock();
    if(!s || s->closed) throw std::runtime_error("Script is closed");
    return s;
}
void tick(E env) {
    auto s=owner(env);
    if(admission::native_stack.depth)admission::check_native_stack(s->max_stack_bytes,admission::stack_position());
    if(!s->remaining_steps) throw std::runtime_error("Script execution step limit exceeded");
    --s->remaining_steps;
}
// Value semantics come from the shared core layer; these adapters add the
// tree's null-pointer checks and variable wrappers only.
V wrap(Value value) {auto result=std::make_shared<variable>();value_access::assign(*result,std::move(value));return result;}
const Value& value_of(const V& v) {
    if(!v) throw std::runtime_error("Null host value");
    return value_access::read(*v);
}
bool numeric(const V& v) { return v && value_ops::numeric(value_access::read(*v)); }
double number(const V& v) {return value_ops::number(value_of(v));}
bool truth(const V& v) {return value_ops::truth(value_of(v));}
BinaryOperation binary_operation(const std::string& op) {
    static const std::pair<const char*,BinaryOperation> names[]{
        {"add",BinaryOperation::add},{"minus",BinaryOperation::subtract},{"multiply",BinaryOperation::multiply},
        {"divide",BinaryOperation::divide},{"mod",BinaryOperation::remainder},{"gt",BinaryOperation::greater},
        {"lt",BinaryOperation::less},{"eq",BinaryOperation::equal},{"ne",BinaryOperation::not_equal},
        {"ge",BinaryOperation::greater_equal},{"le",BinaryOperation::less_equal}};
    for(const auto& [name,operation]:names)if(op==name)return operation;
    throw std::logic_error("Unknown binary operation: "+op);
}
V binary(const std::string& op,const V& a,const V& b) {
    return wrap(value_ops::binary(binary_operation(op),value_access::read(*a),value_access::read(*b)));
}
V convert_value(const V& value,int target) {
    if(value->type_tag()==target)return value->deepCopy();
    return wrap(value_ops::convert(value_of(value),target));
}
class select_expression final:public expression {
    std::shared_ptr<expression> condition,when_true,when_false;
public:
    select_expression(std::shared_ptr<expression> condition,std::shared_ptr<expression> when_true,std::shared_ptr<expression> when_false)
        :condition(std::move(condition)),when_true(std::move(when_true)),when_false(std::move(when_false)) {}
    V execute(E env) override {
        tick(env);bool selected;
        {
            at_location location(env,condition->location);
            selected=truth(run(condition,env));
        }
        // Arms share the enclosing scope and statement's temporary lifetime.
        // The consuming expression retains the usual copy/materialization rule.
        return run(selected?when_true:when_false,env);
    }
};
class convert_expression final:public expression {
    int target;
    std::shared_ptr<expression> value;
public:
    convert_expression(int target,std::shared_ptr<expression> value):target(target),value(std::move(value)) {}
    V execute(E env) override {tick(env);return convert_value(run(value,env),target);}
};
V bitwise(int opcode,const V& left,const V& right) {
    const auto operation=static_cast<int>(BinaryOperation::bit_and)+opcode-43;
    return wrap(value_ops::binary(static_cast<BinaryOperation>(operation),value_of(left),value_of(right)));
}
class bitwise_expression final:public expression {
    int opcode;
    std::shared_ptr<expression> left,right;
public:
    bitwise_expression(int opcode,std::shared_ptr<expression> left,std::shared_ptr<expression> right)
        :opcode(opcode),left(std::move(left)),right(std::move(right)) {}
    V execute(E env) override {
        tick(env);
        auto a=run(left,env)->deepCopy();auto b=run(right,env);
        return bitwise(opcode,a,b);
    }
};
class extra_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> a,b;
public:
    extra_expression(std::string op,std::shared_ptr<expression> a,std::shared_ptr<expression> b=nullptr)
        :op(std::move(op)),a(std::move(a)),b(std::move(b)) {}
    V execute(E env) override {
        tick(env);
        auto x=run(a,env)->deepCopy();
        if(op=="not") return std::make_shared<variable>(!truth(x));
        if(op=="neg") return wrap(value_ops::unary(UnaryOperation::negate,value_access::read(*x)));
        if(op=="and"&&!truth(x)) return std::make_shared<variable>(false);
        if(op=="or"&&truth(x)) return std::make_shared<variable>(true);
        auto y=run(b,env);
        if(op=="and"||op=="or") return std::make_shared<variable>(truth(y));
        return binary(op,x,y);
    }
};
// Native admission: logical depth, then the native stack, then the count.
struct call_guard {
    std::shared_ptr<script> s;
    admission::accounting calls;
    admission::accounting::ticket admitted;
    explicit call_guard(E env):s(owner(env)),calls(s->active_calls,s->max_call_depth,s->remaining_steps,s->max_steps) {
        calls.check_can_enter();
        admission::enter_native_stack(s->max_stack_bytes);
        admitted=calls.admit();
    }
    ~call_guard(){admitted.reset();admission::leave_native_stack();}
};
std::optional<int> argument_tag(const V& argument) noexcept {
    return argument?std::optional<int>(argument->type_tag()):std::nullopt;
}
class checked_external_function final:public function {
    std::shared_ptr<function> target;
    function_signature signature;
    int id;
    std::weak_ptr<script> script_owner;
    bool typed;
public:
    checked_external_function(std::shared_ptr<function> target,function_signature signature,int id,std::weak_ptr<script> owner,bool typed=true)
        :target(std::move(target)),signature(std::move(signature)),id(id),script_owner(std::move(owner)),typed(typed) {}
    int return_type() override{return typed?signature.return_type:target->return_type();}
    V invoke(E env,std::vector<V> args={}) override {
        entry_scope entry;return entry.run([&]() -> V {
        try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        auto script=script_owner.lock();
        if(!script||!env||env->script.lock()!=script)throw std::runtime_error("Function belongs to another or unavailable script");
        script->require_execution();
        if(typed&&signature.hidden_count)throw std::runtime_error("Generic external function requires a script implementation and bound contexts");
        call_guard guard(env);
        if(typed)admission::check_arguments("External function",id,signature.param_types,args.size(),
            [&](std::size_t i){return argument_tag(args[i]);},signature.hidden_count>0);
        auto result=target->invoke(std::move(env),std::move(args));
        // Returning normally means any nested failure was handled by the
        // callback. It cannot become part of a later signature error.
        trace::discard_active();
        if(!result)
            throw std::runtime_error("External function "+function_id_text(id)+" returned a null pointer");
        const int got=static_cast<int>(result->type_tag());
        if(typed&&signature.return_type!=ANY_VALUE&&got!=signature.return_type)
            throw std::runtime_error("External function "+function_id_text(id)+
                " return type mismatch: expected "+type_name(signature.return_type)+", got "+type_name(got));
        return result;
        } catch(...) {
            // A callback may handle a script error and throw a fresh host
            // exception. Preserve nested frames only for the propagated one.
            if(!trace::current_is_published())trace::discard_active();
            if((static_cast<std::uint32_t>(id)>>16)!=0x0abdu) {
                trace_frame frame;frame.function_id=id;frame.native=true;trace::note_frame(std::move(frame));
            }
            throw;
        }
        });
    }
};
// Unbound object temporaries created while the body runs are destroyed when it
// ends, including on errors; the first error wins.
template<class Body> V statement_scope(const E& env,Body body) {
    auto frame=env?env->frame:nullptr;
    const auto mark=statement::begin(frame?frame->temporary_id():ownership::TempListId{},frame?frame->temporaries.size():0);
    V result;pending_error failure;
    try {result=body();}catch(...){failure.capture_current();}
    if(frame&&frame->temporaries.size()>mark.count)try{statement::finish(mark,env);}catch(...){failure.capture_current();}
    if(failure)failure.rethrow();
    return result;
}
template<class Body> V with_cleanup(const E& scope,Body body) {
    V result;
    pending_error failure;
    try {result=body();}catch(...){failure.capture_current();}
    try {scope->cleanup(scope);}catch(...){failure.capture_current();}
    if(failure)failure.rethrow();
    return result;
}
V scoped_execute(const std::shared_ptr<expression>& body,E env) {
    at_location location(env,body->location);
    if(std::dynamic_pointer_cast<complex_expression>(body)) return run(body,env);
    auto scope=std::make_shared<environment>(env->ownership_arena(),env,nullptr,env->frame);
    scope->script=env->script;
    return with_cleanup(scope,[&]{return statement_scope(scope,[&]{return run(body,scope);});});
}
// Compiler-generated base cleanup. It does not encode classes or object layouts:
// both operands are ordinary code, and the finalizer is usually a function call.
class cleanup_expression final:public expression {
    std::shared_ptr<expression> body,cleanup;
    bool on_error;
public:
    cleanup_expression(std::shared_ptr<expression> body,std::shared_ptr<expression> cleanup,bool on_error)
        :body(std::move(body)),cleanup(std::move(cleanup)),on_error(on_error) {}
    V execute(E env) override {
        pending_error failure;
        try {tick(env);scoped_execute(body,env);}catch(...){failure.capture_current();}
        if(!on_error||failure) {
            auto frame=env->frame;
            const bool returned=frame&&frame->returned,broken=frame&&frame->break_requested,
                continued=frame&&frame->continue_requested;
            const auto result=frame?frame->result:nullptr;
            auto location=frame?frame->location:0;
            const auto return_location=frame?frame->return_location:0;
            const bool had_failure=static_cast<bool>(failure);
            if(frame){frame->returned=false;frame->break_requested=false;frame->continue_requested=false;}
            try {scoped_execute(cleanup,env);}catch(...){
                if(frame&&!had_failure)location=frame->location;
                failure.capture_current();
            }
            if(frame){frame->returned=returned;frame->break_requested=broken;frame->continue_requested=continued;frame->result=result;
                frame->location=location;frame->return_location=return_location;}
        }
        if(failure)failure.rethrow();
        return nil();
    }
};
class object_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> value;
    int offset=0;
    std::optional<int> destructor;
    bool manual=false;
public:
    std::vector<std::shared_ptr<context_spec>> contexts;
    object_expression(std::string operation,std::shared_ptr<expression> value,int number=0,bool manual=false)
        :op(std::move(operation)),value(std::move(value)),manual(manual) {
        if(op=="oa")offset=number;
    }
    object_expression(std::shared_ptr<expression> value,std::optional<int> destructor,bool manual)
        :op("ob"),value(std::move(value)),destructor(destructor),manual(manual) {}
    bool is_member_address() const noexcept {return op=="oa";}
    int* destructor_address(){return destructor?&*destructor:nullptr;}
    V execute(E env) override {
        tick(env);auto pointer=run(value,env);
        if(pointer->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object pointer requires address type");
        const auto object_pointer=pointer->get<address>();
        if(op=="oa")return std::make_shared<variable>(heap::object_address(object_pointer,offset));
        if(op=="ob")heap::register_object(object_pointer,destructor,manual,env,bind_contexts(contexts,env));
        else heap::delete_object(object_pointer,env);
        return nil();
    }
};
// Only the decoder can construct this member access. Raw mem_get still guards
// object storage, including calls made before a constructor has registered it.
class member_access_expression final:public expression {
    std::shared_ptr<expression> pointer;
public:
    explicit member_access_expression(std::shared_ptr<expression> pointer):pointer(std::move(pointer)) {}
    V execute(E env) override {
        tick(env);const auto result=run(pointer,env);
        if(result->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object pointer requires address type");
        return heap::getAt(result->get<address>());
    }
};
// Literal object storage. The compiler emits these only for its own lowering:
// scripts cannot name a block, and only method code sees its address as this.
class new_block_expression final:public expression {
    std::size_t size;
public:
    explicit new_block_expression(std::size_t size):size(size) {}
    V execute(E env) override {
        tick(env);auto block=blocks::create(size,env->script);
        if(env->frame)blocks::adopt_temporary(block,env->frame.get());
        return std::make_shared<variable>(std::move(block));
    }
};
class block_address_expression final:public expression {
    std::shared_ptr<expression> value;
public:
    explicit block_address_expression(std::shared_ptr<expression> value):value(std::move(value)) {}
    V execute(E env) override {
        tick(env);auto result=run(value,env);
        auto block=blocks::of(result);
        if(!block||block->buffer)throw std::runtime_error("Object address requires a class value");
        return std::make_shared<variable>(blocks::address_of(block));
    }
};
class move_out_expression final:public expression {
    int slot;
public:
    explicit move_out_expression(int slot):slot(slot) {}
    V execute(E env) override {tick(env);return blocks::move_out(env->getVariable(slot),env);}
};
class drop_expression final:public expression {
    std::shared_ptr<expression> target;
public:
    explicit drop_expression(std::shared_ptr<expression> target):target(std::move(target)) {}
    V execute(E env) override {tick(env);blocks::drop(run(target,env),env);return nil();}
};
class context_expression final:public expression {
    int opcode;
    std::shared_ptr<context_spec> context;
    std::shared_ptr<expression> value;
public:
    context_expression(int opcode,std::shared_ptr<context_spec> context,std::shared_ptr<expression> value={})
        :opcode(opcode),context(std::move(context)),value(std::move(value)) {}
    V execute(E env) override {
        tick(env);auto bound=bind_context(context,env);
        if(opcode==36)return std::make_shared<variable>(bound->abi);
        if(opcode==37) {
            if(bound->kind==4) {
                auto result=std::make_shared<variable>(blocks::create_buffer(bound->element,env->script));
                blocks::adopt_result(result,env);return result;
            }
            if(bound->kind==3) {
                if(!bound->factory_id)throw std::runtime_error("Generic type has no default constructor");
                auto result=invoke_bound_function(owner(env),*bound->factory_id,env,{},bound->contexts);
                // Adopt first, so a malformed factory result is still cleaned.
                blocks::adopt_result(result,env);
                auto block=blocks::of(result);
                if(!block||block->buffer||block->slots.size()!=static_cast<std::size_t>(bound->width))
                    throw std::runtime_error("Generic default factory must return a class with the declared slot width");
                return result;
            }
            switch(bound->abi) {
            case INT_VALUE:return std::make_shared<variable>(0);
            case STRING_VALUE:return std::make_shared<variable>(std::string());
            case FLOAT_VALUE:return std::make_shared<variable>(0.0f);
            case DOUBLE_VALUE:return std::make_shared<variable>(0.0);
            case BOOLEAN_VALUE:return std::make_shared<variable>(false);
            case ADDRESS_VALUE:return std::make_shared<variable>(address{});
            default:throw std::runtime_error("Generic type cannot be default initialized");
            }
        }
        auto result=run(value,env);
        if(!result||result->type_tag()!=bound->abi)throw std::runtime_error("Generic value type mismatch");
        if(opcode==39)return result;
        if(!env->frame)throw std::runtime_error("Return outside function");
        if(result->type_tag()==OBJECT_VALUE)result=blocks::export_result(result,env);
        else {
            result=result->deepCopy();
            if(bound->kind==2)heap::return_object(result->get<address>(),env);
        }
        mark_returned(env,std::move(result));return nil();
    }
};
struct method_spec {int function_id;std::vector<std::shared_ptr<context_spec>> contexts;};
bool constant_context(const std::shared_ptr<context_spec>& context) {
    if(context->ref>=0)return false;
    for(const auto& child:context->contexts)if(!constant_context(child))return false;
    for(const auto& child:context->placement_contexts)if(!constant_context(child))return false;
    return !context->element||constant_context(context->element);
}
class set_methods_expression final:public expression {
    std::shared_ptr<expression> pointer;
    std::shared_ptr<const method_table> cached;
public:
    std::vector<std::shared_ptr<method_spec>> entries;
    explicit set_methods_expression(std::shared_ptr<expression> pointer):pointer(std::move(pointer)) {}
    V execute(E env) override {
        tick(env);auto address_value=run(pointer,env);
        if(address_value->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Virtual method receiver must be address");
        bool context_free=true;for(const auto& entry:entries) {
            tick(env);for(const auto& context:entry->contexts)if(!constant_context(context))context_free=false;
        }
        auto methods=cached;
        if(!methods) {
            auto bound=std::make_shared<method_table>();bound->reserve(entries.size());
            for(const auto& entry:entries)bound->push_back({entry->function_id,bind_contexts(entry->contexts,env)});
            methods=std::move(bound);
        }
        heap::set_methods(address_value->get<address>(),methods,owner(env));
        if(context_free)cached=std::move(methods);
        return nil();
    }
};
class call_virtual_expression final:public expression {
    std::size_t slot;
    int return_type;
    std::vector<std::shared_ptr<expression>> arguments;
public:
    call_virtual_expression(int slot,int return_type,std::vector<std::shared_ptr<expression>> arguments)
        :slot(static_cast<std::size_t>(slot)),return_type(return_type),arguments(std::move(arguments)) {}
    V execute(E env) override {
        tick(env);std::vector<V> args;args.reserve(arguments.size());
        for(const auto& argument:arguments)args.push_back(blocks::materialize(run(argument,env),env));
        if(args.empty()||args[0]->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Virtual method receiver must be address");
        const auto script=owner(env);const auto methods=heap::methods_at(args[0]->get<address>(),script);
        if(slot>=methods->size())throw std::runtime_error("Virtual method slot is out of range");
        const auto& method=(*methods)[slot];
        auto result=invoke_bound_function(script,method.function_id,env,std::move(args),method.contexts);
        if(!result)throw std::runtime_error("Host function returned a null pointer");
        blocks::adopt_result(result,env);
        if(return_type!=ANY_VALUE&&result->type_tag()!=return_type)
            throw std::runtime_error(std::string("Virtual method return type mismatch: expected ")+type_name(return_type)+", got "+type_name(result->type_tag()));
        return result;
    }
};
bool same_context_layout(const std::shared_ptr<const type_context>& a,const std::shared_ptr<const type_context>& b,std::size_t depth=0) {
    if(!a||!b||depth>=128)return false;
    return a->abi==b->abi&&a->kind==b->kind&&a->width==b->width&&
        (a->kind!=4||same_context_layout(a->element,b->element,depth+1));
}
class check_object_expression final:public expression {
    std::shared_ptr<expression> value;
    std::shared_ptr<context_spec> context;
    std::vector<int> slot_types;
public:
    check_object_expression(std::shared_ptr<expression> value,std::shared_ptr<context_spec> context,std::vector<int> slot_types)
        :value(std::move(value)),context(std::move(context)),slot_types(std::move(slot_types)) {}
    V execute(E env) override {
        tick(env);auto result=run(value,env);auto bound=bind_context(context,env);
        if(!result||result->type_tag()!=bound->abi)throw std::runtime_error("Object check failed: value has an incompatible type");
        if(bound->kind==2)heap::check_object(result->get<address>(),slot_types);
        else if(bound->kind==3||bound->kind==4) {
            auto block=blocks::of(result);
            if(!block||block->state!=slot_block::status::live)throw std::runtime_error("Object check failed: value object has expired");
            if(bound->kind==4) {
                if(!block->buffer||!same_context_layout(bound->element,block->buffer->element))
                    throw std::runtime_error("Object check failed: buffer element context mismatch");
            } else {
                if(block->buffer||block->slots.size()!=static_cast<std::size_t>(bound->width)||
                   (!slot_types.empty()&&block->slots.size()!=slot_types.size()))
                    throw std::runtime_error("Object check failed: class value width mismatch");
                for(std::size_t i=0;i<slot_types.size();++i)
                    if(slot_types[i]!=ANY_VALUE&&(!block->slots[i]||block->slots[i]->type_tag()!=slot_types[i]))
                        throw std::runtime_error("Object check failed: slot "+std::to_string(i)+" has an incompatible type");
            }
        }
        return result;
    }
};
class buffer_new_expression final:public expression {
    std::shared_ptr<context_spec> context;
public:
    explicit buffer_new_expression(std::shared_ptr<context_spec> context):context(std::move(context)) {}
    V execute(E env) override {
        tick(env);auto result=std::make_shared<variable>(blocks::create_buffer(bind_context(context,env),env->script));
        blocks::adopt_result(result,env);return result;
    }
};
class buffer_operation_expression final:public expression {
    int operation;std::shared_ptr<expression> receiver;
    std::vector<std::shared_ptr<expression>> arguments;
public:
    buffer_operation_expression(int operation,std::shared_ptr<expression> receiver,std::vector<std::shared_ptr<expression>> arguments)
        :operation(operation),receiver(std::move(receiver)),arguments(std::move(arguments)) {}
    V execute(E env) override {
        tick(env);auto buffer=blocks::of(run(receiver,env));
        std::vector<V> values;for(auto& argument:arguments)values.push_back(blocks::materialize(run(argument,env),env));
        return blocks::buffer_operation(operation,buffer,values,env);
    }
};
class value_compare_expression final:public expression {
    std::shared_ptr<context_spec> context;std::shared_ptr<expression> left,right;
public:
    value_compare_expression(std::shared_ptr<context_spec> context,std::shared_ptr<expression> left,std::shared_ptr<expression> right)
        :context(std::move(context)),left(std::move(left)),right(std::move(right)) {}
    V execute(E env) override {
        tick(env);auto a=blocks::materialize(run(left,env),env);auto b=blocks::materialize(run(right,env),env);
        return std::make_shared<variable>(blocks::compare_value(bind_context(context,env),a,b,env));
    }
};
// Identity returns keep the tree's variable copy; conversions and their
// errors are the shared implementation.
V convert_return(V v,int type) {
    if(type==ANY_VALUE||(type!=VOID_VALUE&&v->type_tag()==type))return v->deepCopy();
    auto result=value_ops::convert_return(value_access::read(*v),type);
    return type==VOID_VALUE?v:wrap(std::move(result));
}
// The expression tree is built from the validated core model. References are
// recorded in the model's discovery order, so the linker can patch them in place.
using X=std::shared_ptr<expression>;
template<class T> X raw_binary(X a,X b) {
    auto node=std::make_shared<T>();node->exp1=std::move(a);node->exp2=std::move(b);return node;
}
exec::reference_kind reference_kind(const function_reference& reference) noexcept {
    return reference.destructor?exec::reference_kind::destructor:reference.factory?exec::reference_kind::factory:
        reference.placement?exec::reference_kind::placement:reference.method?exec::reference_kind::method:exec::reference_kind::call;
}
class tree_builder {
    std::vector<function_reference>& references;
    std::shared_ptr<context_spec> context(const exec::context& source) {
        auto spec=std::make_shared<context_spec>();
        if(source.reference){spec->ref=static_cast<int>(*source.reference);return spec;}
        spec->abi=source.abi;spec->kind=source.kind;spec->width=static_cast<int>(source.width);
        spec->factory_id=source.factory_id;spec->contexts=contexts(source.contexts);
        spec->placement_id=source.placement_id;spec->placement_contexts=contexts(source.placement_contexts);
        if(source.element)spec->element=context(*source.element);
        if(spec->placement_id)references.push_back({*spec->placement_id,&*spec->placement_id,false,static_cast<int>(spec->placement_contexts.size()),false,true});
        if(spec->factory_id)references.push_back({*spec->factory_id,&*spec->factory_id,false,static_cast<int>(spec->contexts.size()),true});
        return spec;
    }
    std::vector<std::shared_ptr<context_spec>> contexts(const std::vector<std::shared_ptr<const exec::context>>& source) {
        std::vector<std::shared_ptr<context_spec>> result;result.reserve(source.size());
        for(const auto& value:source)result.push_back(context(*value));
        return result;
    }
    std::vector<X> expressions(const exec::expression& source,std::size_t first=0) {
        std::vector<X> result;result.reserve(source.children.size()-first);
        for(auto i=first;i<source.children.size();++i)result.push_back(build(*source.children[i]));
        return result;
    }
public:
    explicit tree_builder(std::vector<function_reference>& references):references(references) {}
    X build(const exec::expression& source) {
        const auto child=[&](std::size_t index){return build(*source.children[index]);};
        const int opcode=source.opcode;
        X result;
        switch(opcode) {
        case 0:result=std::make_shared<constantExpression>(source.constant);break;
        case 1: {
            auto node=std::make_shared<complex_expression>();node->expressions=expressions(source);result=std::move(node);break;
        }
        case 2: {
            auto node=std::make_shared<functionInvocationExpression>();node->function_id=source.immediate;
            node->params=expressions(source);node->contexts=contexts(source.contexts);
            auto member=node->params.size()==1?std::dynamic_pointer_cast<object_expression>(node->params[0]):nullptr;
            if(node->function_id==ids::id(ids::builtin::mem_get)&&node->contexts.empty()&&member&&member->is_member_address())
                result=std::make_shared<member_access_expression>(std::move(node->params[0]));
            else {
                references.push_back({node->function_id,&node->function_id,false,static_cast<int>(node->contexts.size())});
                result=std::move(node);
            }
            break;
        }
        case 3: {auto node=std::make_shared<varExpression>();node->varindex=source.immediate;result=std::move(node);break;}
        case 4: {
            auto node=std::make_shared<varDefineExpression>();node->varindex=source.immediate;
            if(!source.children.empty())node->initializer=child(0);
            result=std::move(node);break;
        }
        case 5: {
            auto node=std::make_shared<varSetExpression>();node->varindex=source.immediate;node->expression=child(0);result=std::move(node);break;
        }
        case 6: {auto target=child(0);auto value=child(1);result=raw_binary<movExpression>(std::move(target),std::move(value));break;}
        case 32:result=std::make_shared<new_block_expression>(static_cast<std::size_t>(source.immediate));break;
        case 33:result=std::make_shared<block_address_expression>(child(0));break;
        case 34:result=std::make_shared<move_out_expression>(source.immediate);break;
        case 35:result=std::make_shared<drop_expression>(child(0));break;
        case 36:case 37:result=std::make_shared<context_expression>(opcode,context(*source.contexts[0]));break;
        case 38:case 39: {
            auto value=child(0);auto spec=context(*source.contexts[0]);
            result=std::make_shared<context_expression>(opcode,std::move(spec),std::move(value));break;
        }
        case 40:result=std::make_shared<buffer_new_expression>(context(*source.contexts[0]));break;
        case 41: {
            auto receiver=child(0);auto arguments=expressions(source,1);
            result=std::make_shared<buffer_operation_expression>(source.immediate,std::move(receiver),std::move(arguments));break;
        }
        case 42: {
            auto spec=context(*source.contexts[0]);auto left=child(0);auto right=child(1);
            result=std::make_shared<value_compare_expression>(std::move(spec),std::move(left),std::move(right));break;
        }
        case 43:case 44:case 45:case 46:case 47:case 48: {
            auto left=child(0);auto right=child(1);
            result=std::make_shared<bitwise_expression>(opcode,std::move(left),std::move(right));break;
        }
        case 49:result=std::make_shared<convert_expression>(source.immediate,child(0));break;
        case 52: {
            auto node=std::make_shared<set_methods_expression>(child(0));
            for(const auto& entry:source.methods) {
                auto method=std::make_shared<method_spec>();method->function_id=entry.function_id;method->contexts=contexts(entry.contexts);
                references.push_back({method->function_id,&method->function_id,false,static_cast<int>(method->contexts.size()),false,false,true});
                node->entries.push_back(std::move(method));
            }
            result=std::move(node);break;
        }
        case 53:result=std::make_shared<call_virtual_expression>(source.immediate,source.return_type,expressions(source));break;
        case 51: {
            auto value=child(0);auto spec=context(*source.contexts[0]);
            result=std::make_shared<check_object_expression>(std::move(value),std::move(spec),source.slot_types);break;
        }
        case 50: {
            auto condition=child(0);auto when_true=child(1);auto when_false=child(2);
            result=std::make_shared<select_expression>(std::move(condition),std::move(when_true),std::move(when_false));break;
        }
        case 7:case 8: {
            auto node=std::make_shared<returnExpression>();node->object_return=opcode==8;
            if(!source.children.empty())node->returnType=child(0);
            result=std::move(node);break;
        }
        case 9:result=std::make_shared<object_expression>("oa",child(0),source.immediate);break;
        case 10: {
            auto node=std::make_shared<object_expression>(child(0),source.destructor_id,source.manual);
            node->contexts=contexts(source.contexts);
            if(source.destructor_id)references.push_back({*source.destructor_id,node->destructor_address(),true,static_cast<int>(node->contexts.size())});
            result=std::move(node);break;
        }
        case 11:result=std::make_shared<object_expression>("od",child(0));break;
        case 12:case 13:case 14:case 15:case 16:case 17:case 18:case 19:case 20:case 21:case 22:case 23:case 24: {
            auto a=child(0);auto b=child(1);
            switch(opcode) {
            case 12:result=raw_binary<addExpression>(std::move(a),std::move(b));break;
            case 13:result=raw_binary<minusExpression>(std::move(a),std::move(b));break;
            case 14:result=raw_binary<multiplyExpression>(std::move(a),std::move(b));break;
            case 15:result=raw_binary<divideExpression>(std::move(a),std::move(b));break;
            case 17:result=raw_binary<gtExpression>(std::move(a),std::move(b));break;
            case 18:result=raw_binary<ltExpression>(std::move(a),std::move(b));break;
            case 19:result=raw_binary<equalExpression>(std::move(a),std::move(b));break;
            default: {
                const char* operation=opcode==16?"mod":opcode==20?"ne":opcode==21?"ge":opcode==22?"le":opcode==23?"and":"or";
                result=std::make_shared<extra_expression>(operation,std::move(a),std::move(b));break;
            }
            }
            break;
        }
        case 25:case 26:result=std::make_shared<extra_expression>(opcode==25?"not":"neg",child(0));break;
        case 27: {
            auto node=std::make_shared<ifExpression>();node->exp1=child(0);node->exp2=child(1);
            if(source.children.size()==3)node->otherwise=child(2);
            result=std::move(node);break;
        }
        case 28: {auto condition=child(0);auto body=child(1);result=raw_binary<whileExpression>(std::move(condition),std::move(body));break;}
        case 29:result=std::make_shared<breakExpression>();break;
        case 30:result=std::make_shared<continueExpression>();break;
        case 31: {
            auto body=child(0);auto cleanup=child(1);
            result=std::make_shared<cleanup_expression>(std::move(body),std::move(cleanup),source.on_error);break;
        }
        default:throw std::logic_error("Unknown exec model opcode: "+std::to_string(opcode));
        }
        result->location=source.location;return result;
    }
};
parsed_module build_module(exec::module model) {
    parsed_module module;
    module.meta=std::move(model.meta);module.global_count=model.global_count;
    module.hint=std::move(model.hint);module.assumptions=std::move(model.assumptions);
    module.extern_signatures=std::move(model.extern_signatures);
    for(const auto& source:model.functions) {
        auto fn=std::make_shared<ofunction>();
        fn->global_offset=model.global_offset;fn->global_count=module.global_count;
        fn->function_id=source.function_id;fn->rett=source.return_type;
        fn->param_count=static_cast<int>(source.param_types.size());fn->local_count=static_cast<int>(source.local_count);
        fn->param_types=source.param_types;fn->hidden_count=static_cast<int>(source.hidden_count);
        fn->entry_kind=source.entry_kind;fn->debug=source.debug;
        const auto first=module.references.size();
        fn->code=tree_builder(module.references).build(*source.body);
        // The core linker plan and the tree's patch list must name the same references.
        const auto expected=exec::references(source.body);
        if(module.references.size()-first!=expected.size())throw std::logic_error("Tree references differ from the exec model");
        for(std::size_t i=0;i<expected.size();++i) {
            const auto& found=module.references[first+i];const auto& wanted=expected[i];
            if(found.original_id!=wanted.function_id||reference_kind(found)!=wanted.kind||found.hidden_count!=static_cast<int>(wanted.hidden_count))
                throw std::logic_error("Tree references differ from the exec model");
        }
        module.functions.emplace(source.function_id,std::move(fn));
    }
    return module;
}
}
std::recursive_mutex& runtime_mutex(){static std::recursive_mutex m;return m;}
bool script_namespace_in_use(int ns) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto& registry=live_scripts();
    registry.erase(std::remove_if(registry.begin(),registry.end(),[](auto& value){return value.expired();}),registry.end());
    for(auto& weak:registry)if(auto value=weak.lock();value&&!value->closed&&value->script_namespaces.contains(ns))return true;
    return false;
}
void validate_type_contexts(const type_contexts& contexts,const std::shared_ptr<script>& script) {
    std::size_t visited=0;
    const auto check=[&](auto&& self,const std::shared_ptr<const type_context>& context,std::size_t depth)->void {
        if(!context||depth>128||++visited>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Invalid or excessive generic contexts");
        if(!valid_context_kind(context->abi,context->kind)||context->width<1||static_cast<std::size_t>(context->width)>MAX_VARIABLE_SLOTS||(context->kind!=3&&context->width!=1))throw std::invalid_argument("Invalid generic type context");
        if((context->factory_id||!context->contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only object type contexts have default factories");
        if(!context->factory_id&&!context->contexts.empty())throw std::invalid_argument("Factory contexts require a factory");
        if(context->factory_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->factory_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=OBJECT_VALUE||!fn->param_types.empty()||fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->contexts.size())
                throw std::invalid_argument("Invalid generic default factory signature");
        }
        if((context->placement_id||!context->placement_contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only class contexts have placement initializers");
        if(!context->placement_id&&!context->placement_contexts.empty())throw std::invalid_argument("Placement contexts require an initializer");
        if(context->placement_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->placement_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE}||fn->entry_kind!=1||
               fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->placement_contexts.size())
                throw std::invalid_argument("Invalid generic placement initializer signature");
        }
        if((context->kind==4)!=static_cast<bool>(context->element))throw std::invalid_argument("Buffer context requires exactly one element context");
        if(context->element) {
            if(context->element->abi==VOID_VALUE)throw std::invalid_argument("Buffer element cannot be void");
            self(self,context->element,depth+1);
        }
        for(auto& child:context->contexts)self(self,child,depth+1);
        for(auto& child:context->placement_contexts)self(self,child,depth+1);
    };
    for(auto& context:contexts)check(check,context,1);
}
void validate_method_table(const method_table& methods,const std::shared_ptr<script>& script) {
    if(!script||script->closed)throw std::invalid_argument("Virtual method table belongs to an unavailable script");
    if(methods.size()>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Too many virtual method entries");
    for(const auto& method:methods) {
        auto found=script->functions.find(method.function_id);
        auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
        if(!fn||fn->entry_kind!=0||fn->param_types.empty()||fn->param_types.front()!=ADDRESS_VALUE||
           fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=method.contexts.size())
            throw std::invalid_argument("Invalid virtual method entry: "+function_id_text(method.function_id));
        validate_type_contexts(method.contexts,script);
    }
}
V invoke_bound_function(const std::shared_ptr<script>& script,int id,const E& env,std::vector<V> args,type_contexts contexts) {
    entry_scope entry;return entry.run([&]() -> V {
    auto target=script->getFunction(id);
    if(auto fn=std::dynamic_pointer_cast<ofunction>(target))return fn->invoke_bound(env,std::move(args),std::move(contexts));
    if(!contexts.empty())throw std::runtime_error("Generic contexts require a script function implementation");
    return target->invoke(env,std::move(args));
    });
}
std::shared_ptr<script> load_script(const unsigned char* bytes,std::size_t length) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto result=std::make_shared<script>();
    result->baseEnv=std::make_shared<environment>();result->baseEnv->script=result;
    result->insert_script(bytes,length);
    live_scripts().push_back(result);
    return result;
    });
}
std::shared_ptr<script> load_script(unsigned char* bytes) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    if(!bytes)throw std::invalid_argument("Null script");
    const int length=b2i(bytes);if(length<0)throw std::invalid_argument("Negative script length");
    return load_script(bytes,static_cast<std::size_t>(length)+4);
    });
}
void script::require_execution() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(destroying||initializing)return;
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(!setup)throw std::runtime_error("Script setup is incomplete; call flush before execution");
}
std::shared_ptr<function> script::getFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());require_execution();
    if(auto found=functions.find(id);found!=functions.end())return found->second;
    const int ns=namespace_of(id);
    if(ns!=0x0abd&&script_namespaces.contains(ns))
        throw std::runtime_error("Unknown function id: "+function_id_text(id)+" (script namespace)");
    if(auto target=getiFunction(id)) {
        if(auto signature=extern_signatures.find(id);signature!=extern_signatures.end())
            return std::make_shared<checked_external_function>(std::move(target),signature->second,id,weak_from_this());
        return std::make_shared<checked_external_function>(std::move(target),function_signature{},id,weak_from_this(),false);
    }
    throw std::runtime_error("Unknown function id: "+function_id_text(id));
}
bool script::hasFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return false;
    if(functions.contains(id))return true;
    const int ns=namespace_of(id);
    return (ns==0x0abd||!script_namespaces.contains(ns))&&isInternelFunction(id);
}
V script::invoke(int id,std::vector<V> args) {
    entry_scope entry;return entry.run([&]() -> V {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(destroying)throw std::runtime_error("Script is being destroyed");
    require_execution();return getFunction(id)->invoke(baseEnv,std::move(args));
    });
}
int script::namespace_for_hint(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(module->manifest.hint==hint&&!hint.empty())return *module->manifest.actual_namespace;
    throw std::runtime_error("Unknown namespace hint: "+hint);
}
bool script::hint_loaded(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(!hint.empty()&&module->manifest.hint==hint)return true;
    return false;
}
std::vector<module_manifest_entry> script::module_manifest() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());std::vector<module_manifest_entry> result;
    for(auto& module:modules)result.push_back(module->manifest);
    return result;
}
void vm::select_backend(script& owner,std::size_t workspace_limit) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(owner.closed)throw std::runtime_error("Script is closed");
    if(owner.faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(owner.destroying||owner.initializing||owner.active_calls)
        throw std::runtime_error("Cannot select a VM backend during script execution");
    if(owner.setup||std::any_of(owner.modules.begin(),owner.modules.end(),
            [](const auto& module){return module->manifest.initialized;}))
        throw std::runtime_error("VM backend must be selected before the first flush");
    if(!workspace_limit)throw std::invalid_argument("VM workspace limit must be positive");
    owner.vm_session_=std::make_shared<Session>(owner,workspace_limit);
}
vm::WorkspaceUsage vm::workspace_usage(const script& owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!owner.vm_session_)return {};
    return {owner.vm_session_->workspace_bytes(),owner.vm_session_->workspace_peak_bytes(),owner.vm_session_->workspace_limit()};
}
void script::insert_script(const unsigned char* bytes,std::size_t length) {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot insert during script execution");
    auto parsed=build_module(exec::read_module(bytes,length,baseEnv->variables.size()));
    auto module=std::make_shared<loaded_module>();
    module->manifest.bytes.assign(bytes,bytes+length);
    module->manifest.global_offset=baseEnv->variables.size();module->manifest.global_count=parsed.global_count;
    module->manifest.hint=std::move(parsed.hint);module->meta=std::move(parsed.meta);
    module->assumptions=std::move(parsed.assumptions);module->extern_signatures=std::move(parsed.extern_signatures);
    module->references=std::move(parsed.references);
    for(auto& [id,signature]:module->extern_signatures) {
        (void)signature;const int ns=namespace_of(id);
        if(!module->assumptions.contains(ns)&&ns!=0x0abd)module->fixed_extern_namespaces.insert(ns);
    }
    auto occupied=registered_executor_namespaces();
    occupied.insert({0,0x0abd,0x0fff});
    occupied.insert(script_namespaces.begin(),script_namespaces.end());
    occupied.insert(fixed_host_namespaces.begin(),fixed_host_namespaces.end());
    occupied.insert(module->fixed_extern_namespaces.begin(),module->fixed_extern_namespaces.end());
    for(auto& weak:live_scripts())if(auto value=weak.lock();value&&!value->closed) {
        occupied.insert(value->script_namespaces.begin(),value->script_namespaces.end());
        occupied.insert(value->fixed_host_namespaces.begin(),value->fixed_host_namespaces.end());
    }
    if(!module->manifest.hint.empty()) {
        for(auto& current:modules)if(current->manifest.hint==module->manifest.hint)throw std::invalid_argument("Duplicate namespace hint: "+module->manifest.hint);
        int candidate=1;while(candidate<=0xffff&&occupied.contains(candidate))++candidate;
        if(candidate>0xffff)throw std::runtime_error("No namespace is available for hint module");
        module->manifest.actual_namespace=candidate;
    }
    auto candidate_functions=functions;auto candidate_namespaces=script_namespaces;
    const auto host_namespaces=registered_executor_namespaces();
    for(auto& [original_id,generic]:parsed.functions) {
        auto fn=std::dynamic_pointer_cast<ofunction>(generic);fn->script_owner=weak_from_this();
        if(original_id==0){module->onload=fn;continue;}
        if(original_id==1){module->pre_destroy=fn;continue;}
        const int actual_id=module->manifest.actual_namespace?relocate_id(original_id,*module->manifest.actual_namespace):original_id;
        const int ns=namespace_of(actual_id);
        if(host_namespaces.contains(ns))throw std::invalid_argument("Script namespace "+namespace_text(ns)+" conflicts with registered host executor");
        if(!module->manifest.actual_namespace) {
            for(std::size_t i=0;i<modules.size();++i)if(modules[i]->manifest.actual_namespace==ns)
                throw std::invalid_argument(mounted_hint_conflict("Fixed namespace",ns,modules,i,
                    "insert fixed-namespace modules before hint libraries or choose another #namespace"));
            module->fixed_namespaces.insert(ns);
        }
        fn->function_id=actual_id;
        if(!candidate_functions.emplace(actual_id,fn).second)throw std::invalid_argument(duplicate_function_message(actual_id,modules));
        module->definitions.emplace(original_id,std::move(fn));candidate_namespaces.insert(ns);
    }
    if(module->manifest.actual_namespace)candidate_namespaces.insert(*module->manifest.actual_namespace);
    for(int ns:module->fixed_extern_namespaces)for(std::size_t i=0;i<modules.size();++i)
        if(modules[i]->manifest.actual_namespace==ns)
            throw std::invalid_argument(mounted_hint_conflict("Fixed external namespace",ns,modules,i,
                "insert this module before hint libraries or import script libraries through #assume_hint"));
    auto candidate_hosts=fixed_host_namespaces;candidate_hosts.insert(module->fixed_extern_namespaces.begin(),module->fixed_extern_namespaces.end());
    auto candidate_globals=baseEnv->variables;candidate_globals.reserve(candidate_globals.size()+parsed.global_count);
    for(std::size_t i=0;i<parsed.global_count;++i)candidate_globals.push_back(nil());
    auto candidate_modules=modules;candidate_modules.push_back(module);
    // Every allocation and validation above is complete before state is published.
    functions.swap(candidate_functions);script_namespaces.swap(candidate_namespaces);fixed_host_namespaces.swap(candidate_hosts);
    baseEnv->variables.swap(candidate_globals);modules.swap(candidate_modules);meta=module->meta;setup=false;
    });
}
// Link planning sees the mounted modules only through the neutral input.
link::plan plan_links(const script& owner) {
    link::input input;input.modules.reserve(owner.modules.size());
    for(const auto& module:owner.modules) {
        link::module item;
        item.assumptions=module->assumptions;item.hint=module->manifest.hint;
        item.mounted_namespace=module->manifest.actual_namespace;item.extern_signatures=module->extern_signatures;
        item.references.reserve(module->references.size());
        for(const auto& reference:module->references)
            item.references.push_back({reference.original_id,reference_kind(reference),static_cast<std::uint32_t>(reference.hidden_count)});
        for(const auto& [original,fn]:module->definitions){(void)original;item.definitions.push_back(fn->function_id);}
        item.dependencies=module->dependencies;
        input.modules.push_back(std::move(item));
    }
    for(const auto& [id,fn]:owner.functions) {
        const auto defined=std::dynamic_pointer_cast<ofunction>(fn);
        input.functions.emplace(id,defined?std::optional<function_signature>(function_signature{defined->rett,defined->param_types,
            defined->hidden_count,defined->entry_kind}):std::nullopt);
    }
    input.script_namespaces=owner.script_namespaces;
    return link::resolve(input);
}
void script::flush() {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot flush during script execution");
    if(setup)return;
    // Migration acceptance only: run every host (CLI, C++ hosts, JNI) on the
    // experimental VM without changing them. Production keeps the tree default.
    if(!vm_session_&&std::none_of(modules.begin(),modules.end(),[](const auto& module){return module->manifest.initialized;}))
        if(const char* forced=std::getenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");forced&&std::string_view(forced)=="1")
            vm_session_=std::make_shared<vm::Session>(*this,vm::default_workspace_bytes);
    auto plan=plan_links(*this);
    on_destory.reserve(on_destory.size()+modules.size());
    std::vector<std::pair<ofunction*,std::uint32_t>> vm_bindings;
    std::shared_ptr<vm::Session> vm_candidate;
    if(vm_session_) {
        // Preparation is the last fallible validation phase. It reads immutable
        // manifests, so neither tree relocation nor onload can run partially.
        // Keep the prior session until all binding checks and allocations finish:
        // destroy() can still run initialized hooks after a failed insertion.
        vm_candidate=std::make_shared<vm::Session>(*this,vm_session_->workspace_limit());
        vm_candidate->prepare();
        const auto& compiled=vm_candidate->compiled();
        if(compiled.modules.size()!=modules.size())throw std::logic_error("VM module binding differs from validated linkage");
        const auto bind=[&](const std::shared_ptr<ofunction>& function,std::uint32_t index,bool lifecycle) {
            if(!function) {
                if(index!=vm::no_index)throw std::logic_error("VM lifecycle binding differs from validated linkage");
                return;
            }
            if(index>=compiled.program.functions.size())throw std::logic_error("VM function binding is unavailable");
            const auto& record=compiled.program.functions[index];
            if(record.function_id!=function->function_id||record.lifecycle!=lifecycle||
                    record.global_offset!=function->global_offset||record.global_count!=function->global_count||
                    record.return_type!=function->rett||record.param_types!=function->param_types||
                    record.hidden_count!=static_cast<std::uint32_t>(function->hidden_count)||record.entry_kind!=function->entry_kind)
                throw std::logic_error("VM function binding differs from validated linkage");
            vm_bindings.emplace_back(function.get(),index);
        };
        for(std::size_t i=0;i<modules.size();++i) {
            for(const auto& [original,function]:modules[i]->definitions) {
                (void)original;
                const auto found=compiled.public_functions.find(function->function_id);
                if(found==compiled.public_functions.end())throw std::logic_error("VM public function binding is unavailable");
                bind(function,found->second,false);
            }
            bind(modules[i]->onload,compiled.modules[i].onload,true);
            bind(modules[i]->pre_destroy,compiled.modules[i].pre_destroy,true);
        }
    }
    // Link validation is atomic: no references change and no onload runs before
    // every module has a valid resolution and the complete order is available.
    for(const auto& [function,index]:vm_bindings)function->vm_function_index_=index;
    if(vm_candidate)vm_session_.swap(vm_candidate);
    for(std::size_t i=0;i<modules.size();++i) {
        auto& references=modules[i]->references;
        for(std::size_t k=0;k<references.size();++k){*references[k].target=plan.references[i][k];references[k].original_id=plan.references[i][k];}
        modules[i]->extern_signatures.swap(plan.externs[i]);
        modules[i]->dependencies.swap(plan.dependencies[i]);
        modules[i]->assumptions.clear();
        for(auto& [original,fn]:modules[i]->definitions){(void)original;fn->linked=true;}
        if(modules[i]->onload)modules[i]->onload->linked=true;
        if(modules[i]->pre_destroy)modules[i]->pre_destroy->linked=true;
    }
    extern_signatures.swap(plan.bindings);vm_candidate.reset();initializing=true;
    try {
        call_guard guard(baseEnv);
        for(auto index:plan.order) {
            auto& module=*modules[index];if(module.manifest.initialized)continue;
            if(module.onload)module.onload->invoke(baseEnv,{});
            module.manifest.initialized=true;
            if(module.pre_destroy)on_destory.push_back(module.pre_destroy);
        }
        initializing=false;setup=true;
    } catch(...) {initializing=false;setup=false;faulted=true;throw;}
    });
}
void script::destroy() {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return;
    if(destroying)throw std::runtime_error("Script is being destroyed");
    if(active_calls||initializing)throw std::runtime_error("Cannot destroy a running script");
    destroying=true;pending_error failure;
    try {
        call_guard guard(baseEnv);
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {
            try{(*hook)->invoke(baseEnv,{});}catch(...){failure.capture_current();}
        }
        try{baseEnv->cleanup(baseEnv);}catch(...){failure.capture_current();}
        // Only hand-written exec can keep an object in a global; it still ends here.
        blocks::finalize_storage(baseEnv->variables,baseEnv,true,failure);
    }catch(...){failure.capture_current();}
    heap::discard_script_objects(this);
    vm_session_.reset();
    closed=true;setup=false;functions.clear();extern_signatures.clear();on_destory.clear();modules.clear();baseEnv.reset();destroying=false;
    if(failure)failure.rethrow();
    });
}
int ofunction::return_type(){return rett;}
V ofunction::invoke(E ev,std::vector<V> args) {
    entry_scope entry;return entry.run([&]() -> V {
    if(hidden_count)throw std::runtime_error("Generic function requires a non-generic wrapper for public invocation");
    return invoke_bound(std::move(ev),std::move(args),{});
    });
}
V ofunction::invoke_bound(E ev,std::vector<V> args,type_contexts contexts) {
    entry_scope entry;return entry.run([&]() -> V {
    std::shared_ptr<function_frame> frame;
    bool vm_trace=false;
    try {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto bound=script_owner.lock();
    if(!bound||!ev||ev->script.lock()!=bound)throw std::runtime_error("Function belongs to another or unavailable script");
    bound->require_execution();
    if(!linked)throw std::runtime_error("Function module has not been linked");
    if(contexts.size()!=static_cast<std::size_t>(hidden_count))throw std::runtime_error("Generic context count mismatch");
    validate_type_contexts(contexts,bound);
    call_guard guard(ev);
    admission::check_arguments("Script function",function_id,param_types,args.size(),
        [&](std::size_t i){return argument_tag(args[i]);},true);
    if(bound->vm_session_) {
        if(vm_function_index_==vm::no_index)throw std::logic_error("VM function has not been linked");
        vm::ExecutionPosition position;
        // Session owns every VM frame, including the entry frame and failures
        // while importing globals or reserving arguments. Do not append a
        // duplicate tree frame when it propagates the original exception.
        vm_trace=true;
        return bound->vm_session_->invoke(vm_function_index_,args,&position,ev);
    }
    const auto& arena=guard.s->baseEnv->ownership_arena();
    auto env=std::make_shared<environment>(arena,guard.s->baseEnv,ev,std::make_shared<function_frame>(arena),true);
    env->script=guard.s;
    frame=env->frame;
    env->frame->hidden_contexts=std::move(contexts);
    env->frame->global_offset=global_offset;env->frame->global_count=global_count;
    env->frame->param_count=args.size();
    env->frame->slots.resize(static_cast<std::size_t>(param_count)+static_cast<std::size_t>(local_count));
    for(std::size_t i=0;i<args.size();++i) {
        if(!args[i]) throw std::invalid_argument("Null argument pointer; use variable(nullptr)");
        // Object arguments move from caller temporaries or are copied; parameters
        // are destroyed after every local of the call.
        auto storage=std::make_shared<variable>(nullptr);blocks::store(storage,args[i],ev);
        statement::bind_parameter(storage,i,env);
    }
    return with_cleanup(env,[&] {
        {
            at_location location(env,code->location);
            if(auto block=std::dynamic_pointer_cast<complex_expression>(code)) block->execute_body(env);
            else run(code,env);
        }
        env->frame->location=env->frame->return_location;
        return convert_return(env->frame->result,rett);
    });
    } catch(...) {if(!vm_trace)note_script_frame(*this,frame);throw;}
    });
}
V complex_expression::execute_body(E env) {
    tick(env);
    for(auto& e:expressions) {
        if(env->frame&&(env->frame->returned||env->frame->break_requested||env->frame->continue_requested)) break;
        at_location location(env,e->location);
        statement_scope(env,[&]{return run(e,env);});
    }
    return nil();
}
V complex_expression::execute(E env) {
    auto scope=std::make_shared<environment>(env->ownership_arena(),env,nullptr,env->frame);scope->script=env->script;
    return with_cleanup(scope,[&]{at_location location(scope,this->location);return execute_body(scope);});
}
V functionInvocationExpression::execute(E env) {
    tick(env);std::vector<V> args;
    // Snapshot each argument before evaluating the next one (left-to-right).
    for(auto& p:params) args.push_back(blocks::materialize(run(p,env),env));
    auto result=invoke_bound_function(owner(env),function_id,env,std::move(args),bind_contexts(contexts,env));
    if(!result) throw std::runtime_error("Host function returned a null pointer");
    blocks::adopt_result(result,env);
    return result;
}
V varExpression::execute(E env){tick(env);return env->getVariable(varindex);}
V varDefineExpression::execute(E env) {
    tick(env);
    if(!env->frame||varindex<0||static_cast<std::size_t>(varindex)<env->frame->param_count||
       static_cast<std::size_t>(varindex)>=env->frame->slots.size())
        throw std::runtime_error("Invalid local variable declaration: "+std::to_string(varindex));
    auto& slot=env->frame->slots[static_cast<std::size_t>(varindex)];
    if(slot)throw std::runtime_error("Duplicate variable slot: "+std::to_string(varindex));
    auto storage=nil();
    if(initializer)blocks::store(storage,run(initializer,env),env);
    statement::declare_local(storage,static_cast<std::size_t>(varindex),env);return nil();
}
V varSetExpression::execute(E env) {
    tick(env);auto v=env->getVariable(varindex);
    blocks::store(v,run(expression,env),env);return v->deepCopy();
}
V returnExpression::execute(E env) {
    tick(env);if(!env->frame) throw std::runtime_error("Return outside function");
    auto value=returnType?run(returnType,env):nil();
    V result;
    if(value->type_tag()==OBJECT_VALUE)result=blocks::export_result(value,env);
    else {
        result=value->deepCopy();
        if(object_return) {
            if(result->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object return requires address or object type");
            heap::return_object(result->get<address>(),env);
        }
    }
    mark_returned(env,std::move(result));return nil();
}
constantExpression::constantExpression(const Value& v):value(std::make_shared<variable>()) {value_access::assign(*value,v);}
V constantExpression::execute(E env){tick(env);return value->deepCopy();}
#define BINARY_IMPL(Class,Op) \
V Class::execute(E env){tick(env);auto a=run(exp1,env)->deepCopy();auto b=run(exp2,env);return binary(Op,a,b);}
BINARY_IMPL(addExpression,"add")
BINARY_IMPL(minusExpression,"minus")
BINARY_IMPL(multiplyExpression,"multiply")
BINARY_IMPL(divideExpression,"divide")
BINARY_IMPL(equalExpression,"eq")
BINARY_IMPL(gtExpression,"gt")
BINARY_IMPL(ltExpression,"lt")
#undef BINARY_IMPL
V movExpression::execute(E env){tick(env);auto a=run(exp1,env);auto b=run(exp2,env);blocks::store(a,b,env);return a->deepCopy();}
namespace {
// Temporaries of a condition end once its truth value is known.
bool condition(const std::shared_ptr<expression>& test,const E& env) {
    at_location location(env,test->location);
    bool value=false;
    statement_scope(env,[&]{value=truth(run(test,env));return nil();});
    return value;
}
}
V ifExpression::execute(E env){tick(env);if(condition(exp1,env)) return scoped_execute(exp2,env);return otherwise?scoped_execute(otherwise,env):nil();}
V whileExpression::execute(E env) {
    tick(env);
    if(!env->frame) throw std::runtime_error("While outside function");
    auto frame=env->frame;
    ++frame->loop_depth;
    try {
        while(!frame->returned&&condition(exp1,env)) {
            tick(env);scoped_execute(exp2,env);
            if(frame->break_requested) {frame->break_requested=false;break;}
            frame->continue_requested=false;
        }
    } catch(...) {--frame->loop_depth;throw;}
    --frame->loop_depth;
    return nil();
}
V breakExpression::execute(E env) {
    tick(env);
    if(!env->frame||env->frame->loop_depth==0) throw std::runtime_error("Break outside loop");
    env->frame->break_requested=true;
    return nil();
}
V continueExpression::execute(E env) {
    tick(env);
    if(!env->frame||env->frame->loop_depth==0) throw std::runtime_error("Continue outside loop");
    env->frame->continue_requested=true;
    return nil();
}
void variable::clear() noexcept {
    // The owning storage variable ended: its object's address expires. Value
    // itself only retains a handle; it never owns script cleanup responsibility.
    if(auto block=value_.object_handle();block&&block->owner==this)blocks::abandon(block);
    value_=Value{};
}
variable::~variable(){clear();}
const Value& value_access::read(const variable& source) noexcept {return source.value_;}
void value_access::assign(variable& destination,Value value) noexcept {
    destination.replace_value(std::move(value));
}
void variable::replace_value(Value value) noexcept {
    // The argument is already a complete snapshot, even when it came from a
    // field of the object that clear() is about to abandon.
    clear();value_.swap(value);
}
#define VALUE_IMPL(T) \
variable::variable(T v){setValue(std::move(v));} \
void variable::setValue(T v){replace_value(Value(std::move(v)));}
VALUE_IMPL(int)
VALUE_IMPL(address)
VALUE_IMPL(std::string)
VALUE_IMPL(float)
VALUE_IMPL(double)
VALUE_IMPL(bool)
VALUE_IMPL(std::shared_ptr<slot_block>)
#undef VALUE_IMPL
variable::variable(const char* value){setValue(value);}
void variable::setValue(const char* value){if(value)setValue(std::string(value));else setValue(nullptr);}
variable::variable(std::nullptr_t){}
void variable::setValue(std::nullptr_t){clear();}
std::shared_ptr<slot_block> variable::object_handle() const noexcept {
    return value_.object_handle();
}
void variable::swap_storage(variable& other) noexcept {
    value_.swap(other.value_);
}
void variable::copy_value(const variable& v) {
    replace_value(v.value_);
}
variable::variable(const variable& v):name(v.name),value_(v.value_){}
variable& variable::operator=(const variable& v){
    if(this!=&v){std::string next_name(v.name);replace_value(v.value_);name.swap(next_name);}return *this;
}
void variable::copy_from(V v){if(!v)throw std::invalid_argument("Null variable");if(v.get()!=this)copy_value(*v);}
V variable::deepCopy(){auto v=std::make_shared<variable>(*this);v->name.clear();return v;}
function_frame::function_frame(std::shared_ptr<ownership::Arena> arena)
    :temporary_storage_(std::move(arena)),temporaries(temporary_storage_.record().values) {}
environment::environment(std::shared_ptr<ownership::Arena> arena,const std::shared_ptr<environment>& parent,
                         const std::shared_ptr<environment>& caller,std::shared_ptr<function_frame> frame,bool frame_root)
    :ownership_(std::move(arena)),owned_pointer(ownership_.record().owned),parent(parent),caller(caller),
     script(ownership_.record().script),frame(std::move(frame)),closing(ownership_.record().closing),frame_root(frame_root) {
    if(this->frame)ownership_.record().temporaries=this->frame->temporary_id();
}
environment::~environment() {heap::discard_owned(this);}
namespace {
// The tree's view of a closing scope's locals for lifecycle::ScopeFinalizer.
class closing_locals final:public lifecycle::ScopeLocals {
    environment& scope_;
public:
    explicit closing_locals(environment& scope) noexcept:scope_(scope) {}
    std::vector<V> leftovers() const override {
        std::vector<V> result;
        const auto& frame=scope_.frame;
        if(!frame)return result;
        if(scope_.frame_root)for(std::size_t i=0;i<frame->param_count&&i<frame->slots.size();++i)result.push_back(frame->slots[i]);
        for(auto index:scope_.declared_slots)result.push_back(frame->slots[index]);
        return result;
    }
    void undeclare() noexcept override {
        if(scope_.frame)for(auto index:scope_.declared_slots)scope_.frame->slots[index].reset();
        scope_.declared_slots.clear();
    }
};
// True when cleanup could run no code and release nothing: it then only
// closes the scope and undeclares its locals.
bool nothing_to_release(const environment& scope) noexcept {
    if(!scope.owned_pointer.empty())return false;
    const auto& frame=scope.frame;
    if(!frame)return true;
    if(scope.frame_root&&!frame->temporaries.empty())return false;
    const auto owning=[&](std::size_t index){const auto& slot=frame->slots[index];return slot&&blocks::owns(*slot);};
    if(scope.frame_root)for(std::size_t i=0;i<frame->param_count&&i<frame->slots.size();++i)if(owning(i))return false;
    for(auto index:scope.declared_slots)if(owning(index))return false;
    return true;
}
}
void environment::cleanup(const std::shared_ptr<environment>& self) {
    if(closing)return;
    closing_locals locals(*this);
    if(nothing_to_release(*this)){closing=true;locals.undeclare();return;}
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    lifecycle::ScopeFinalizer cleanup(ownership_.id(),frame_root&&frame?frame->temporary_id():ownership::TempListId{},locals);
    lifecycle::drive(cleanup,self);
    cleanup.finish();
}
V environment::getVariable(std::string name) {
    for(auto& v:variables) if(v->name==name)return v;
    if(auto p=parent.lock())return p->getVariable(std::move(name));
    return nullptr;
}
V environment::getVariable(int index) {
    if(index<0) {
        const auto relative=static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1);
        auto owner=script.lock();
        if(!owner||owner->closed||!owner->baseEnv)throw std::runtime_error("Script is closed");
        // The base environment is also the host's access point. Its negative
        // ids address the complete global array; an executing frame is limited
        // to the module containing the callee, with that module's relocation.
        const auto count=frame?frame->global_count:owner->baseEnv->variables.size();
        const auto offset=frame?frame->global_offset:0;
        if(relative>=count||offset>owner->baseEnv->variables.size()||
           relative>=owner->baseEnv->variables.size()-offset)
            throw std::runtime_error("Global variable slot is outside its module: "+std::to_string(index));
        return owner->baseEnv->variables[offset+static_cast<std::size_t>(relative)];
    }
    if(!frame||static_cast<std::size_t>(index)>=frame->slots.size())
        throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
    auto value=frame->slots[static_cast<std::size_t>(index)];
    if(!value)throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
    return value;
}
bool environment::testForVar(std::string name){for(auto& v:variables)if(v->name==name)return true;return false;}
std::string value_to_string(const V& v) {
    if(!v)throw std::invalid_argument("Null variable");
    return value_ops::to_string(value_access::read(*v));
}
}
