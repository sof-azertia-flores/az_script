#ifndef AZSCRIPT_TREE_INTERNAL_H
#define AZSCRIPT_TREE_INTERNAL_H

// Private to the tree executor's translation units: the records its modules
// share and the helpers its expression nodes run on.
#include "expressions.h"
#include "../blocks.h"
#include "../core/admission.h"
#include "../core/exec.h"
#include "../core/ids.h"
#include "../core/statement.h"
#include "../core/trace.h"
#include "../core/value_ops.h"
#include <cstdio>
#include <exception>
#include <map>
#include <set>
#include <string>
#include <vector>

namespace azertian {
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
struct parsed_module {
    std::shared_ptr<AbdMap> meta;
    std::size_t global_count=0;
    std::map<int,std::shared_ptr<function>> functions;
    std::map<int,function_signature> extern_signatures;
    std::string hint;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
};
namespace tree {
using M=std::shared_ptr<AbdMap>;
// Internal linkage, as in the single file they came from: GCC inlines
// these helpers into the node bodies only while each copy is local.
namespace {
// Frames of tree functions use the shared recorder and their current location.
void note_script_frame(const ofunction& function,const std::shared_ptr<function_frame>& frame) noexcept {
    trace::note_script_frame(function.function_id,function.debug.get(),frame?frame->location:0);
}
V nil() { return std::make_shared<variable>(nullptr); }
// A parameter's storage. Its allocation stays inlined here: the site entry's
// parameter loop is too large for GCC to inline it there (call_16 +0.7%).
[[gnu::flatten]] V parameter_storage() { return std::make_shared<variable>(nullptr); }
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
// Native admission: logical depth, then the native stack, then the count.
struct call_guard {
    std::shared_ptr<script> s;
    admission::accounting calls;
    admission::accounting::ticket admitted;
    explicit call_guard(E env):call_guard(owner(env)) {}
    explicit call_guard(std::shared_ptr<script> owner)
        :s(std::move(owner)),calls(s->active_calls,s->max_call_depth,s->remaining_steps,s->max_steps) {
        calls.check_can_enter();
        admission::enter_native_stack(s->max_stack_bytes);
        admitted=calls.admit();
    }
    ~call_guard(){admitted.reset();admission::leave_native_stack();}
};
std::optional<int> argument_tag(const V& argument) noexcept {
    return argument?std::optional<int>(argument->type_tag()):std::nullopt;
}
// Unbound object temporaries created while the body runs are destroyed when it
// ends, including on errors; the first error wins.
template<class Body> V statement_scope(const E& env,Body body) {
    auto frame=env?env->frame:nullptr;
    const auto mark=statement::begin(frame?frame->temporary_id():ownership::TempListId{},frame?frame->temporaries.size():0);
    V result;pending_error failure;
    try {result=body();}catch(...){failure.capture_current();}
    if(frame&&frame->temporaries.size()>mark.count)try{statement::finish(mark,site_of(env));}catch(...){failure.capture_current();}
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
    auto scope=std::make_shared<environment>(env->ownership_arena(),env,ownership::ScopeId{},env->frame);
    scope->script=env->script;
    return with_cleanup(scope,[&]{return statement_scope(scope,[&]{return run(body,scope);});});
}
exec::reference_kind reference_kind(const function_reference& reference) noexcept {
    return reference.destructor?exec::reference_kind::destructor:reference.factory?exec::reference_kind::factory:
        reference.placement?exec::reference_kind::placement:reference.method?exec::reference_kind::method:exec::reference_kind::call;
}
}
namespace {
// Value semantics come from the shared core layer; these adapters add the
// tree's null-pointer checks and variable wrappers only.
V wrap(Value value) {auto result=std::make_shared<variable>();value_access::assign(*result,std::move(value));return result;}
const Value& value_of(const V& v) {
    if(!v) throw std::runtime_error("Null host value");
    return value_access::read(*v);
}
bool truth(const V& v) {return value_ops::truth(value_of(v));}
}
// Defined in values.cpp and builder.cpp.
V convert_return(V v,int type);
parsed_module build_module(exec::module model);
}
}
#endif
