// Generic contexts, object checks, buffers and comparisons.
#include "internal.h"
#include "../heepalloc.h"
#include <stdexcept>

namespace azertian {
namespace tree {
namespace {
bool same_context_layout(const std::shared_ptr<const type_context>& a,const std::shared_ptr<const type_context>& b,std::size_t depth=0) {
    if(!a||!b||depth>=128)return false;
    return a->abi==b->abi&&a->kind==b->kind&&a->width==b->width&&
        (a->kind!=4||same_context_layout(a->element,b->element,depth+1));
}
}
V context_expression::execute(E env) {
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
V check_object_expression::execute(E env) {
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
V buffer_new_expression::execute(E env) {
    tick(env);auto result=std::make_shared<variable>(blocks::create_buffer(bind_context(context,env),env->script));
    blocks::adopt_result(result,env);return result;
}
V buffer_operation_expression::execute(E env) {
    tick(env);auto buffer=blocks::of(run(receiver,env));
    std::vector<V> values;for(auto& argument:arguments)values.push_back(blocks::materialize(run(argument,env),env));
    return blocks::buffer_operation(operation,buffer,values,env);
}
V value_compare_expression::execute(E env) {
    tick(env);auto a=blocks::materialize(run(left,env),env);auto b=blocks::materialize(run(right,env),env);
    return std::make_shared<variable>(blocks::compare_value(bind_context(context,env),a,b,env));
}
}
}
