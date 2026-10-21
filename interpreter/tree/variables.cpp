// Locals, assignment and returns.
#include "internal.h"
#include "../heepalloc.h"
#include <stdexcept>
#include <utility>

namespace azertian {
using namespace tree;
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
V movExpression::execute(E env){tick(env);auto a=run(exp1,env);auto b=run(exp2,env);blocks::store(a,b,env);return a->deepCopy();}
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
}
