// Script calls, method tables and virtual calls.
#include "internal.h"
#include "../heepalloc.h"
#include <stdexcept>
#include <utility>

namespace azertian {
namespace tree {
namespace {
bool constant_context(const std::shared_ptr<context_spec>& context) {
    if(context->ref>=0)return false;
    for(const auto& child:context->contexts)if(!constant_context(child))return false;
    for(const auto& child:context->placement_contexts)if(!constant_context(child))return false;
    return !context->element||constant_context(context->element);
}
}
V set_methods_expression::execute(E env) {
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
V call_virtual_expression::execute(E env) {
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
}
using namespace tree;
V functionInvocationExpression::execute(E env) {
    tick(env);std::vector<V> args;
    // Snapshot each argument before evaluating the next one (left-to-right).
    for(auto& p:params) args.push_back(blocks::materialize(run(p,env),env));
    auto result=invoke_bound_function(owner(env),function_id,env,std::move(args),bind_contexts(contexts,env));
    if(!result) throw std::runtime_error("Host function returned a null pointer");
    blocks::adopt_result(result,env);
    return result;
}
}
