#include "jit/operations.h"
#include <cfloat>
#include <climits>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace azertian;
using namespace azertian::jit;
std::size_t checks=0;
void check(bool condition,const std::string& detail) {
    ++checks;if(!condition)throw std::runtime_error(detail);
}
// Every shadowable tag with the edges of its arithmetic.
std::vector<Value> grid() {
    std::vector<Value> values;
    for(const int value:{0,1,-1,2,3,7,13,-13,31,32,-32,46341,-46341,65536,INT_MAX,INT_MIN,INT_MAX-1,INT_MIN+1})values.emplace_back(value);
    for(const float value:{0.0f,-0.0f,1.5f,-2.25f,FLT_MAX,-FLT_MAX,FLT_MIN,1e-45f,NAN,INFINITY,-INFINITY,16777217.0f})values.emplace_back(value);
    for(const double value:{0.0,-0.0,2.5,-1e308,1e308,DBL_MAX,3.0,1e39,-1e39,2147483647.5,-2147483648.9,double(NAN),double(INFINITY)})values.emplace_back(value);
    values.emplace_back(true);values.emplace_back(false);values.emplace_back();
    for(const std::uint64_t value:{std::uint64_t{0},std::uint64_t{1},std::uint64_t{8},UINT64_MAX,UINT64_MAX-1})values.emplace_back(address{value});
    return values;
}
std::string describe(const Value& value) {
    return std::string(type_name(value.type_tag()))+" "+(value.type_tag()==VOID_VALUE?"":value_ops::to_string(value));
}
// The shadow operation agrees with value_ops: the same result bits and tag,
// or no result exactly where value_ops fails or yields a string.
template<class Expected,class Actual> void agree(Expected expected,Actual actual,const std::string& what) {
    std::optional<Shadow> reference;
    try{reference=shadow_of(expected());}catch(const std::exception&){}
    const auto result=actual();
    check(reference.has_value()==result.has_value(),what+": failure differs");
    if(reference)check(*reference==*result,what+": result differs");
}
void binaries(const std::vector<Value>& values) {
    for(std::uint32_t code=0;code<=static_cast<std::uint32_t>(BinaryOperation::unsigned_shift_right);++code) {
        const auto operation=static_cast<BinaryOperation>(code);
        for(const auto& left:values)for(const auto& right:values)
            agree([&]{return value_ops::binary(operation,left,right);},
                  [&]{return binary(operation,*shadow_of(left),*shadow_of(right));},
                  "binary "+std::to_string(code)+" "+describe(left)+" , "+describe(right));
    }
}
void unaries(const std::vector<Value>& values) {
    for(const auto operation:{UnaryOperation::logical_not,UnaryOperation::negate})
        for(const auto& operand:values)
            agree([&]{return value_ops::unary(operation,operand);},[&]{return unary(operation,*shadow_of(operand));},
                  "unary "+describe(operand));
}
void conversions(const std::vector<Value>& values) {
    for(const int target:{INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,BOOLEAN_VALUE,VOID_VALUE,ANY_VALUE,ADDRESS_VALUE,OBJECT_VALUE})
        for(const auto& operand:values)
            agree([&]{return value_ops::convert(operand,target);},[&]{return convert(target,*shadow_of(operand));},
                  "convert to "+std::to_string(target)+" "+describe(operand));
}
void round_trips(const std::vector<Value>& values) {
    for(const auto& value:values) {
        const auto shadow=shadow_of(value);
        check(shadow.has_value(),"a scalar has a shadow");
        const auto back=value_of(*shadow);
        check(back.type_tag()==value.type_tag()&&shadow_of(back)==shadow,"shadows keep the value's bits: "+describe(value));
    }
    check(!shadow_of(Value(std::string("x"))).has_value(),"strings have no shadow");
}
}
int main() {
    try {
        const auto values=grid();
        round_trips(values);binaries(values);unaries(values);conversions(values);
        std::cout<<"JIT operation checks passed: "<<checks<<'\n';
    } catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
