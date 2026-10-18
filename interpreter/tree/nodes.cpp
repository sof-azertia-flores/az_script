#include "nodes.h"
#include "../heepalloc.h"
#include "../core/value_ops.h"
#include <cmath>
#include <stdexcept>
#include <utility>

namespace azertian {
namespace tree {
namespace {
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
V bitwise(int opcode,const V& left,const V& right) {
    const auto operation=static_cast<int>(BinaryOperation::bit_and)+opcode-43;
    return wrap(value_ops::binary(static_cast<BinaryOperation>(operation),value_of(left),value_of(right)));
}
bool constant_context(const std::shared_ptr<context_spec>& context) {
    if(context->ref>=0)return false;
    for(const auto& child:context->contexts)if(!constant_context(child))return false;
    for(const auto& child:context->placement_contexts)if(!constant_context(child))return false;
    return !context->element||constant_context(context->element);
}
bool same_context_layout(const std::shared_ptr<const type_context>& a,const std::shared_ptr<const type_context>& b,std::size_t depth=0) {
    if(!a||!b||depth>=128)return false;
    return a->abi==b->abi&&a->kind==b->kind&&a->width==b->width&&
        (a->kind!=4||same_context_layout(a->element,b->element,depth+1));
}
}
V select_expression::execute(E env) {
    tick(env);bool selected;
    {
        at_location location(env,condition->location);
        selected=truth(run(condition,env));
    }
    // Arms share the enclosing scope and statement's temporary lifetime.
    // The consuming expression retains the usual copy/materialization rule.
    return run(selected?when_true:when_false,env);
}
V convert_expression::execute(E env) {tick(env);return convert_value(run(value,env),target);}
V bitwise_expression::execute(E env) {
    tick(env);
    auto a=run(left,env)->deepCopy();auto b=run(right,env);
    return bitwise(opcode,a,b);
}
V extra_expression::execute(E env) {
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
V cleanup_expression::execute(E env) {
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
// Identity returns keep the tree's variable copy; conversions and their
// errors are the shared implementation.
V convert_return(V v,int type) {
    if(type==ANY_VALUE||(type!=VOID_VALUE&&v->type_tag()==type))return v->deepCopy();
    auto result=value_ops::convert_return(value_access::read(*v),type);
    return type==VOID_VALUE?v:wrap(std::move(result));
}
}
using namespace tree;
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
    auto scope=std::make_shared<environment>(env->ownership_arena(),env,ownership::ScopeId{},env->frame);scope->script=env->script;
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
}
