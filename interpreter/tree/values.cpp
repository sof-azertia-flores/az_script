// Constants, operators, conversions and return conversion.
#include "internal.h"
#include "../core/value_ops.h"
#include <stdexcept>
#include <utility>

namespace azertian {
namespace tree {
namespace {
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
V wrapping_expression::execute(E env) {
    tick(env);
    auto a=run(left,env)->deepCopy();auto b=run(right,env);
    const auto operation=static_cast<int>(BinaryOperation::wrapping_add)+opcode-55;
    return wrap(value_ops::binary(static_cast<BinaryOperation>(operation),value_of(a),value_of(b)));
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
// Identity returns keep the tree's variable copy; conversions and their
// errors are the shared implementation.
V convert_return(V v,int type) {
    if(type==ANY_VALUE||(type!=VOID_VALUE&&v->type_tag()==type))return v->deepCopy();
    auto result=value_ops::convert_return(value_access::read(*v),type);
    return type==VOID_VALUE?v:wrap(std::move(result));
}
}
using namespace tree;
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
}
