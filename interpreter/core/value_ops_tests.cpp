#include "core/value_ops.h"
#include <bit>
#include <clocale>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>

namespace {
thread_local bool counting=false;
thread_local std::size_t allocations=0;
}
void* operator new(std::size_t size) {
    if(counting)++allocations;
    if(void* value=std::malloc(size?size:1))return value;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size){return ::operator new(size);}
void operator delete(void* value) noexcept {std::free(value);}
void operator delete[](void* value) noexcept {std::free(value);}
void operator delete(void* value,std::size_t) noexcept {std::free(value);}
void operator delete[](void* value,std::size_t) noexcept {std::free(value);}

namespace {
using namespace azertian;
using namespace azertian::value_ops;
using Op=BinaryOperation;
std::size_t checks=0;
void check(bool value,const char* message){++checks;if(!value)throw std::runtime_error(message);}
template<class Error,class Action> void rejects(Action action,const char* expected) {
    bool caught=false;
    try{action();}catch(const Error& error){caught=true;check(error.what()==std::string(expected),expected);}
    check(caught,expected);
}
void numeric_operations() {
    const Value min(std::numeric_limits<int>::min()),max(std::numeric_limits<int>::max()),one(1),negative_one(-1);
    check(binary(Op::add,Value(20),Value(22)).get<int>()==42,"integer addition");
    check(binary(Op::subtract,Value(20),Value(22)).get<int>()==-2,"integer subtraction");
    check(binary(Op::multiply,Value(-6),Value(7)).get<int>()==-42,"integer multiplication");
    check(binary(Op::divide,Value(-7),Value(2)).get<int>()==-3,"integer division truncates toward zero");
    check(binary(Op::remainder,Value(-7),Value(2)).get<int>()==-1,"integer remainder keeps dividend sign");
    check(binary(Op::remainder,min,negative_one).get<int>()==0,"minimum integer remainder is defined");
    rejects<std::overflow_error>([&]{binary(Op::add,max,one);},"Integer overflow");
    rejects<std::overflow_error>([&]{binary(Op::subtract,min,one);},"Integer overflow");
    rejects<std::overflow_error>([&]{binary(Op::multiply,max,Value(2));},"Integer overflow");
    rejects<std::overflow_error>([&]{binary(Op::divide,min,negative_one);},"Integer overflow");
    rejects<std::overflow_error>([&]{unary(UnaryOperation::negate,min);},"Integer overflow");
    rejects<std::runtime_error>([&]{binary(Op::divide,one,Value(0));},"Division by zero");
    rejects<std::runtime_error>([&]{binary(Op::divide,Value(1.0),Value(-0.0));},"Division by zero");
    rejects<std::runtime_error>([&]{binary(Op::remainder,Value(1.0f),one);},"Remainder requires integer operands");
    const auto rounded=binary(Op::add,Value(16777216),Value(1.0f));
    check(rounded.type_tag()==FLOAT_VALUE&&rounded.get<float>()==16777216.0f,"mixed arithmetic rounds to float");
    const auto wide=binary(Op::add,Value(16777216.0),Value(1.0f));
    check(wide.type_tag()==DOUBLE_VALUE&&wide.get<double>()==16777217.0,"double operand preserves double result");
    rejects<std::overflow_error>([]{binary(Op::add,Value(std::numeric_limits<float>::max()),Value(std::numeric_limits<float>::max()));},"Float overflow");
    rejects<std::overflow_error>([]{binary(Op::multiply,Value(std::numeric_limits<double>::max()),Value(2.0));},"Non-finite arithmetic result");
    check(std::bit_cast<std::uint64_t>(unary(UnaryOperation::negate,Value(0.0)).get<double>())==0x8000000000000000ull,"negation preserves negative zero");
    check(binary(Op::equal,Value(1),Value(1.0f)).get<bool>(),"mixed numeric equality");
    check(binary(Op::greater_equal,Value(2),Value(2.0)).get<bool>(),"numeric greater equal");
    check(binary(Op::less_equal,Value(1.0f),Value(2)).get<bool>(),"numeric less equal");
    check(binary(Op::not_equal,Value(1),Value(2.0)).get<bool>(),"numeric not equal");
}
void address_and_bits() {
    const Value maximum(address{std::numeric_limits<std::uint64_t>::max()}),zero(address{});
    check(binary(Op::greater,maximum,Value(address{0x8000000000000000ull})).get<bool>(),"address comparison retains full width");
    check(binary(Op::add,Value(-1),maximum).get<address>().value==std::numeric_limits<std::uint64_t>::max()-1,"int plus address accepts negative offsets");
    check(binary(Op::subtract,zero,Value(std::numeric_limits<int>::min())).get<address>()==address{2147483648ull},"minimum int address subtraction widens before negation");
    rejects<std::overflow_error>([&]{binary(Op::add,maximum,Value(1));},"Address overflow");
    rejects<std::overflow_error>([&]{binary(Op::subtract,zero,Value(1));},"Address underflow");
    rejects<std::runtime_error>([&]{binary(Op::equal,zero,Value(0));},"Unsupported address operands for eq");
    rejects<std::runtime_error>([&]{binary(Op::subtract,Value(1),zero);},"Unsupported address operands for minus");
    check(binary(Op::bit_and,Value(10),Value(6)).get<int>()==2,"bit and");
    check(binary(Op::bit_or,Value(10),Value(6)).get<int>()==14,"bit or");
    check(binary(Op::bit_xor,Value(10),Value(6)).get<int>()==12,"bit xor");
    check(binary(Op::shift_left,Value(1),Value(31)).get<int>()==std::numeric_limits<int>::min(),"left shift preserves int32 bit pattern");
    check(binary(Op::shift_right,Value(-3),Value(1)).get<int>()==-2,"signed right shift extends sign");
    check(binary(Op::unsigned_shift_right,Value(-1),Value(1)).get<int>()==std::numeric_limits<int>::max(),"unsigned right shift clears sign");
    check(binary(Op::shift_right,Value(-1),Value(0)).get<int>()==-1,"zero shift does not shift by word width");
    rejects<std::runtime_error>([]{binary(Op::shift_left,Value(1),Value(32));},"Shift count must be between 0 and 31, got 32");
    rejects<std::runtime_error>([]{binary(Op::shift_right,Value(1),Value(-1));},"Shift count must be between 0 and 31, got -1");
    rejects<std::runtime_error>([]{binary(Op::bit_and,Value(1),Value(true));},"bit_and operands must be int, got int and boolean");
}
void text_and_conversion() {
    check(binary(Op::add,Value("v="),Value(true)).get<std::string>()=="v=true","string concatenation formats booleans");
    check(binary(Op::add,Value(nullptr),Value("!")).get<std::string>()=="null!","string concatenation formats void");
    check(to_string(Value(address{std::numeric_limits<std::uint64_t>::max()}))=="18446744073709551615","address string retains full width");
    check(to_string(Value(0.1+0.2))=="0.30000000000000004","double formatting round trips");
    check(convert(Value("+2147483647"),INT_VALUE).get<int>()==std::numeric_limits<int>::max(),"positive decimal integer conversion");
    check(convert(Value(-2147483648.9),INT_VALUE).get<int>()==std::numeric_limits<int>::min(),"numeric conversion checks after truncation");
    check(convert(Value("1.25e2"),FLOAT_VALUE).get<float>()==125.0f,"float decimal exponent");
    check(std::bit_cast<std::uint64_t>(convert(Value("-1e-1000"),DOUBLE_VALUE).get<double>())==0x8000000000000000ull,"decimal underflow retains negative zero");
    rejects<std::runtime_error>([]{convert(Value("2147483648"),INT_VALUE);},"Cannot convert string \"2147483648\" to int: value is out of range");
    rejects<std::runtime_error>([]{convert(Value(" 1"),INT_VALUE);},"Cannot convert string \" 1\" to int: invalid decimal integer");
    rejects<std::runtime_error>([]{convert(Value(std::string("4\0x",3)),INT_VALUE);},"Cannot convert string \"4\\u0000x\" to int: invalid decimal integer");
    rejects<std::runtime_error>([]{convert(Value(std::string("\xff",1)),FLOAT_VALUE);},"Cannot convert string \"\\xff\" to float: invalid decimal number");
    rejects<std::runtime_error>([]{convert(Value("1e1000"),DOUBLE_VALUE);},"Cannot convert string \"1e1000\" to double: value is out of range");
    rejects<std::runtime_error>([]{convert(Value(true),STRING_VALUE);},"Cannot convert boolean to string");
    rejects<std::runtime_error>([]{convert(Value(1),ADDRESS_VALUE);},"Cannot convert int to address");
    check(convert_return(Value(7),DOUBLE_VALUE).get<double>()==7.0,"return integer widening");
    check(convert_return(Value(-0.0f),DOUBLE_VALUE).get<double>()==0.0,"return float widening");
    check(convert_return(Value(address{7}),ANY_VALUE).get<address>()==address{7},"any return preserves address");
    rejects<std::runtime_error>([]{convert_return(Value(1),VOID_VALUE);},"Void function returned a value");
    rejects<std::runtime_error>([]{convert_return(Value(1.0),INT_VALUE);},"Function return type mismatch: expected int, got double");
    rejects<std::runtime_error>([]{convert_return(Value(std::numeric_limits<float>::infinity()),DOUBLE_VALUE);},"Non-finite return value");
}
void type_errors() {
    check(truth(Value(true))&&!truth(Value(false)),"boolean truth");
    check(unary(UnaryOperation::logical_not,Value(false)).get<bool>(),"boolean not");
    check(binary(Op::equal,Value(nullptr),Value(nullptr)).get<bool>(),"void equality");
    rejects<std::runtime_error>([]{truth(Value(1));},"Condition must be boolean, got int");
    rejects<std::runtime_error>([]{unary(UnaryOperation::negate,Value(true));},"Negation requires a numeric operand");
    rejects<std::runtime_error>([]{binary(Op::equal,Value(true),Value(1));},"Equality requires compatible operand types, got boolean and int");
    rejects<std::runtime_error>([]{binary(Op::multiply,Value("x"),Value(2));},"Unsupported operands for multiply");
    const Value object(std::shared_ptr<slot_block>{});
    rejects<std::runtime_error>([&]{binary(Op::add,object,Value("x"));},"Unsupported object value operands for add");
    rejects<std::runtime_error>([&]{to_string(object);},"An object value cannot be converted to a string");
}
void conversion_locale() {
    struct locale_restore {
        std::string original=std::setlocale(LC_NUMERIC,nullptr);
        ~locale_restore(){std::setlocale(LC_NUMERIC,original.c_str());}
    } restore;
    for(const char* locale:{"de_DE.UTF-8","fr_FR.UTF-8","de_DE","French_France.1252"})
        if(std::setlocale(LC_NUMERIC,locale))break;
    const std::string host_locale=std::setlocale(LC_NUMERIC,nullptr);
    check(convert(Value("1.5"),FLOAT_VALUE).get<float>()==1.5f,"float parsing uses the C numeric locale");
    check(convert(Value("1.5"),DOUBLE_VALUE).get<double>()==1.5,"double parsing uses the C numeric locale");
    rejects<std::runtime_error>([]{convert(Value("1,5"),FLOAT_VALUE);},"Cannot convert string \"1,5\" to float: invalid decimal number");
    rejects<std::runtime_error>([]{convert(Value("1,5"),DOUBLE_VALUE);},"Cannot convert string \"1,5\" to double: invalid decimal number");
    check(host_locale==std::setlocale(LC_NUMERIC,nullptr),"numeric parsing preserves the host locale");
}
void wrapping() {
    const int max=std::numeric_limits<int>::max(),min=std::numeric_limits<int>::min();
    check(binary(Op::wrapping_add,Value(max),Value(1)).get<int>()==min,"wrapping add wraps past the maximum");
    check(binary(Op::wrapping_subtract,Value(min),Value(1)).get<int>()==max,"wrapping subtract wraps past the minimum");
    check(binary(Op::wrapping_multiply,Value(65536),Value(65536)).get<int>()==0,"wrapping multiply keeps the low 32 bits");
    check(binary(Op::wrapping_multiply,Value(-3),Value(7)).get<int>()==-21,"wrapping multiply is signed in range");
    // Other operands are those of add, subtract and multiply, with their errors.
    check(binary(Op::wrapping_add,Value(1.5f),Value(1)).get<float>()==2.5f,"wrapping add of a float adds");
    check(binary(Op::wrapping_add,Value("a"),Value(1)).get<std::string>()=="a1","wrapping add of a string concatenates");
    rejects<std::overflow_error>([]{binary(Op::wrapping_add,Value(address{std::numeric_limits<std::uint64_t>::max()}),Value(1));},"Address overflow");
    rejects<std::runtime_error>([]{binary(Op::wrapping_multiply,Value(true),Value(1));},"Unsupported operands for multiply");
}
void no_scalar_allocations() {
    Value value(0),one(1);allocations=0;counting=true;
    for(int i=0;i<100000;++i) {
        value=binary(Op::add,value,one);
        const auto comparison=binary(Op::greater,value,Value(i));
        const auto converted=convert(value,DOUBLE_VALUE);
        check(truth(comparison)&&converted.get<double>()==i+1,"kernel loop checksum");
        const auto bits=binary(Op::bit_xor,value,Value(0));
        check(bits.get<int>()==i+1,"kernel bit checksum");
    }
    counting=false;
    check(allocations==0,"scalar kernel allocates per operation");
    std::cout<<"scalar-kernel iterations=100000 allocations="<<allocations<<" checksum="<<value.get<int>()<<'\n';
}
}
int main() {
    try {
        numeric_operations();address_and_bits();text_and_conversion();type_errors();conversion_locale();wrapping();no_scalar_allocations();
        std::cout<<"Value operations checks="<<checks<<'\n';return 0;
    }catch(const std::exception& error) {
        counting=false;std::cerr<<"Value operations failed: "<<error.what()<<'\n';return 1;
    }
}
