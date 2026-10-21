// Object registration, member access and literal object storage.
#include "internal.h"
#include "../heepalloc.h"
#include <stdexcept>

namespace azertian {
namespace tree {
V object_expression::execute(E env) {
    tick(env);auto pointer=run(value,env);
    if(pointer->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object pointer requires address type");
    const auto object_pointer=pointer->get<address>();
    if(op=="oa")return std::make_shared<variable>(heap::object_address(object_pointer,offset));
    if(op=="ob")heap::register_object(object_pointer,destructor,manual,env,bind_contexts(contexts,env));
    else heap::delete_object(object_pointer,env);
    return nil();
}
V member_access_expression::execute(E env) {
    tick(env);const auto result=run(pointer,env);
    if(result->type_tag()!=ADDRESS_VALUE)throw std::runtime_error("Object pointer requires address type");
    return heap::getAt(result->get<address>());
}
V new_block_expression::execute(E env) {
    tick(env);auto block=blocks::create(size,env->script);
    if(env->frame)blocks::adopt_temporary(block,env->frame.get());
    return std::make_shared<variable>(std::move(block));
}
V block_address_expression::execute(E env) {
    tick(env);auto result=run(value,env);
    auto block=blocks::of(result);
    if(!block||block->buffer)throw std::runtime_error("Object address requires a class value");
    return std::make_shared<variable>(blocks::address_of(block));
}
V move_out_expression::execute(E env) {tick(env);return blocks::move_out(env->getVariable(slot),env);}
V drop_expression::execute(E env) {tick(env);blocks::drop(run(target,env),env);return nil();}
}
}
