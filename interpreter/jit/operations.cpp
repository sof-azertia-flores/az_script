#include "operations.h"
#include <cstdint>

namespace azertian::jit {
namespace {
template<class Operation> std::optional<Shadow> general(Operation operation) noexcept {
    try{return shadow_of(operation());}
    catch(...){return std::nullopt;}
}
Shadow integer(std::int32_t value) noexcept{return {std::bit_cast<std::uint32_t>(value),INT_VALUE};}
Shadow boolean(bool value) noexcept{return {value?1u:0u,BOOLEAN_VALUE};}
}
std::optional<Shadow> binary(BinaryOperation operation,const Shadow& left,const Shadow& right) noexcept {
    if(left.tag==INT_VALUE&&right.tag==INT_VALUE) {
        const auto a=std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(left.bits));
        const auto b=std::bit_cast<std::int32_t>(static_cast<std::uint32_t>(right.bits));
        std::int32_t result;
        switch(operation) {
        case BinaryOperation::add:if(__builtin_add_overflow(a,b,&result))return std::nullopt;return integer(result);
        case BinaryOperation::subtract:if(__builtin_sub_overflow(a,b,&result))return std::nullopt;return integer(result);
        case BinaryOperation::multiply:if(__builtin_mul_overflow(a,b,&result))return std::nullopt;return integer(result);
        case BinaryOperation::wrapping_add:return integer(std::bit_cast<std::int32_t>(std::bit_cast<std::uint32_t>(a)+std::bit_cast<std::uint32_t>(b)));
        case BinaryOperation::wrapping_subtract:return integer(std::bit_cast<std::int32_t>(std::bit_cast<std::uint32_t>(a)-std::bit_cast<std::uint32_t>(b)));
        case BinaryOperation::wrapping_multiply:return integer(std::bit_cast<std::int32_t>(std::bit_cast<std::uint32_t>(a)*std::bit_cast<std::uint32_t>(b)));
        case BinaryOperation::divide:
            // In 64 bits as value_ops computes it: INT_MIN/-1 overflows.
            if(!b||(a==INT32_MIN&&b==-1))return std::nullopt;
            return integer(a/b);
        case BinaryOperation::remainder:
            if(!b)return std::nullopt;
            return integer(b==-1?0:a%b);
        case BinaryOperation::greater:return boolean(a>b);
        case BinaryOperation::less:return boolean(a<b);
        case BinaryOperation::greater_equal:return boolean(a>=b);
        case BinaryOperation::less_equal:return boolean(a<=b);
        case BinaryOperation::equal:return boolean(a==b);
        case BinaryOperation::not_equal:return boolean(a!=b);
        default:break;
        }
    } else if(left.tag==BOOLEAN_VALUE&&right.tag==BOOLEAN_VALUE) {
        if(operation==BinaryOperation::equal)return boolean(left.bits==right.bits);
        if(operation==BinaryOperation::not_equal)return boolean(left.bits!=right.bits);
    }
    return general([&]{return value_ops::binary(operation,value_of(left),value_of(right));});
}
std::optional<Shadow> unary(UnaryOperation operation,const Shadow& operand) noexcept {
    if(operation==UnaryOperation::logical_not&&operand.tag==BOOLEAN_VALUE)return boolean(!operand.bits);
    return general([&]{return value_ops::unary(operation,value_of(operand));});
}
std::optional<Shadow> convert(int target,const Shadow& operand) noexcept {
    if(operand.tag==target)return operand;
    return general([&]{return value_ops::convert(value_of(operand),target);});
}
}
