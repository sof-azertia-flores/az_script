#include "value_ops.h"
#include <bit>
#include <cerrno>
#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <locale.h>
#include <string_view>
#include <type_traits>

namespace azertian::value_ops {
bool numeric(const Value& v) noexcept { return (v.type_tag()==INT_VALUE||v.type_tag()==FLOAT_VALUE||v.type_tag()==DOUBLE_VALUE); }
double number(const Value& v) {
    switch(v.type_tag()) {
    case INT_VALUE:return v.get<int>();
    case FLOAT_VALUE:return v.get<float>();
    case DOUBLE_VALUE:return v.get<double>();
    default:throw std::runtime_error("Expected a numeric value");
    }
}
bool truth(const Value& v) {
    if(v.type_tag()==BOOLEAN_VALUE) return v.get<bool>();
    throw std::runtime_error("Condition must be boolean, got "+std::string(type_name(v.type_tag())));
}
static Value checked_int(std::int64_t n) {
    if(n<std::numeric_limits<int>::min()||n>std::numeric_limits<int>::max())
        throw std::overflow_error("Integer overflow");
    return Value(static_cast<int>(n));
}
namespace {
template<class T> std::string shortest_number(T value) {
    char text[64];
    auto result=std::to_chars(text,text+sizeof text,value);
    if(result.ec!=std::errc()) throw std::runtime_error("Cannot format number");
    return std::string(text,result.ptr);
}
}
std::string to_string(const Value& v) {
    if(v.type_tag()==STRING_VALUE)return v.get<std::string>();
    if(v.type_tag()==BOOLEAN_VALUE)return v.get<bool>()?"true":"false";
    if(v.type_tag()==VOID_VALUE)return "null";
    if(v.type_tag()==INT_VALUE)return shortest_number(v.get<int>());
    if(v.type_tag()==ADDRESS_VALUE)return shortest_number(v.get<address>().value);
    if(v.type_tag()==FLOAT_VALUE)return shortest_number(v.get<float>());
    if(v.type_tag()==DOUBLE_VALUE)return shortest_number(v.get<double>());
    if(v.type_tag()==OBJECT_VALUE)throw std::runtime_error("An object value cannot be converted to a string");
    throw std::runtime_error("Unknown runtime value type");
}
static Value bitwise(int opcode,const Value& left,const Value& right) {
    static constexpr const char* names[]{"bit_and","bit_or","bit_xor","shl","shr","ushr"};
    if(left.type_tag()!=INT_VALUE||right.type_tag()!=INT_VALUE)
        throw std::runtime_error(std::string(names[opcode-43])+" operands must be int, got "+
            type_name(left.type_tag())+" and "+type_name(right.type_tag()));
    const auto a=static_cast<std::uint32_t>(left.get<int>());
    const auto b=static_cast<std::uint32_t>(right.get<int>());
    std::uint32_t result;
    if(opcode==43)result=a&b;
    else if(opcode==44)result=a|b;
    else if(opcode==45)result=a^b;
    else {
        const int count=right.get<int>();
        if(count<0||count>31)
            throw std::runtime_error("Shift count must be between 0 and 31, got "+std::to_string(count));
        const auto shift=static_cast<unsigned>(count);
        if(opcode==46)result=a<<shift;
        else {
            result=a>>shift;
            // Use unsigned shifts for every bit pattern. The explicit sign
            // extension also avoids shifting by 32 when the count is zero.
            if(opcode==47&&shift&&(a&0x80000000u))result|=std::uint32_t{0xffffffffu}<<(32-shift);
        }
    }
    return Value(std::bit_cast<int>(result));
}
namespace {
const char* operation_name(BinaryOperation operation) {
    switch(operation) {
    case BinaryOperation::add:return "add";
    case BinaryOperation::subtract:return "minus";
    case BinaryOperation::multiply:return "multiply";
    case BinaryOperation::divide:return "divide";
    case BinaryOperation::remainder:return "mod";
    case BinaryOperation::greater:return "gt";
    case BinaryOperation::less:return "lt";
    case BinaryOperation::equal:return "eq";
    case BinaryOperation::not_equal:return "ne";
    case BinaryOperation::greater_equal:return "ge";
    case BinaryOperation::less_equal:return "le";
    default:throw std::invalid_argument("Unknown binary operation");
    }
}
}
Value binary(BinaryOperation operation,const Value& a,const Value& b) {
    if(operation>=BinaryOperation::bit_and&&operation<=BinaryOperation::unsigned_shift_right)
        return bitwise(43+static_cast<int>(operation)-static_cast<int>(BinaryOperation::bit_and),a,b);
    const std::string_view op=operation_name(operation);
    if(a.type_tag()==OBJECT_VALUE||b.type_tag()==OBJECT_VALUE)throw std::runtime_error("Unsupported object value operands for "+std::string(op));
    if(op=="add"&&(a.type_tag()==STRING_VALUE||b.type_tag()==STRING_VALUE))
        return Value(to_string(a)+to_string(b));
    if(a.type_tag()==ADDRESS_VALUE||b.type_tag()==ADDRESS_VALUE) {
        if(a.type_tag()==ADDRESS_VALUE&&b.type_tag()==ADDRESS_VALUE) {
            const auto x=a.get<address>().value,y=b.get<address>().value;
            if(op=="eq")return Value(x==y);
            if(op=="ne")return Value(x!=y);
            if(op=="lt")return Value(x<y);
            if(op=="le")return Value(x<=y);
            if(op=="gt")return Value(x>y);
            if(op=="ge")return Value(x>=y);
        }
        const bool left_address=a.type_tag()==ADDRESS_VALUE;
        if((op=="add"||(op=="minus"&&left_address))&&
           (left_address?b.type_tag()==INT_VALUE:a.type_tag()==INT_VALUE)) {
            const auto base=(left_address?a:b).get<address>().value;
            std::int64_t offset=(left_address?b:a).get<int>();
            if(op=="minus")offset=-offset;
            if(offset>=0) {
                const auto magnitude=static_cast<std::uint64_t>(offset);
                if(base>std::numeric_limits<std::uint64_t>::max()-magnitude)throw std::overflow_error("Address overflow");
                return Value(address{base+magnitude});
            }
            const auto magnitude=static_cast<std::uint64_t>(-offset);
            if(base<magnitude)throw std::overflow_error("Address underflow");
            return Value(address{base-magnitude});
        }
        throw std::runtime_error("Unsupported address operands for "+std::string(op));
    }
    if(op=="eq"||op=="ne") {
        bool eq=false;
        if(numeric(a)&&numeric(b)) eq=number(a)==number(b);
        else if(a.type_tag()!=b.type_tag())
            throw std::runtime_error("Equality requires compatible operand types, got "+
                std::string(type_name(a.type_tag()))+" and "+type_name(b.type_tag()));
        else {
            if(a.type_tag()==VOID_VALUE) eq=true;
            else if(a.type_tag()==STRING_VALUE) eq=a.get<std::string>()==b.get<std::string>();
            else if(a.type_tag()==BOOLEAN_VALUE) eq=a.get<bool>()==b.get<bool>();
        }
        return Value(op=="eq"?eq:!eq);
    }
    if(!numeric(a)||!numeric(b)) throw std::runtime_error("Unsupported operands for "+std::string(op));
    const double x=number(a),y=number(b);
    if(op=="gt") return Value(x>y);
    if(op=="lt") return Value(x<y);
    if(op=="ge") return Value(x>=y);
    if(op=="le") return Value(x<=y);
    if((op=="divide"||op=="mod")&&y==0) throw std::runtime_error("Division by zero");
    if(a.type_tag()==INT_VALUE&&b.type_tag()==INT_VALUE) {
        const std::int64_t i=a.get<int>(),j=b.get<int>();
        if(op=="add") return checked_int(i+j);
        if(op=="minus") return checked_int(i-j);
        if(op=="multiply") return checked_int(i*j);
        if(op=="divide") return checked_int(i/j);
        if(op=="mod") return checked_int(i%j);
    }
    if(op=="mod") throw std::runtime_error("Remainder requires integer operands");
    double out;
    if(op=="add") out=x+y;
    else if(op=="minus") out=x-y;
    else if(op=="multiply") out=x*y;
    else if(op=="divide") out=x/y;
    else throw std::runtime_error("Unknown binary operation: "+std::string(op));
    if(!std::isfinite(out)) throw std::overflow_error("Non-finite arithmetic result");
    if(a.type_tag()==DOUBLE_VALUE||b.type_tag()==DOUBLE_VALUE) return Value(out);
    const auto f=static_cast<float>(out);
    if(!std::isfinite(f)) throw std::overflow_error("Float overflow");
    return Value(f);
}
namespace {
std::string quoted_conversion_text(const std::string& value) {
    static constexpr char hex[]="0123456789abcdef";
    std::string result="\"";
    auto unicode=[&](std::uint32_t code) {
        result+="\\u";
        for(int shift=12;shift>=0;shift-=4)result+=hex[(code>>shift)&15u];
    };
    std::size_t offset=0;
    for(std::size_t count=0;offset<value.size()&&count<64;++count) {
        const auto first=static_cast<unsigned char>(value[offset]);
        std::uint32_t code=first;std::size_t width=1;
        if(first>=0xc2&&first<=0xdf){width=2;code=first&0x1fu;}
        else if(first>=0xe0&&first<=0xef){width=3;code=first&0x0fu;}
        else if(first>=0xf0&&first<=0xf4){width=4;code=first&7u;}
        bool valid=first<0x80||width>1;
        if(width>value.size()-offset)valid=false;
        if(valid)for(std::size_t i=1;i<width;++i) {
            const auto next=static_cast<unsigned char>(value[offset+i]);
            if((next&0xc0u)!=0x80u){valid=false;break;}
            code=(code<<6)|(next&0x3fu);
        }
        if(valid&&((width==2&&code<0x80)||(width==3&&code<0x800)||(width==4&&code<0x10000)||
           code>0x10ffff||(code>=0xd800&&code<=0xdfff)))valid=false;
        if(!valid) {
            result+="\\x";result+=hex[first>>4];result+=hex[first&15u];++offset;continue;
        }
        offset+=width;
        if(code=='"'||code=='\\'){result+='\\';result+=static_cast<char>(code);}
        else if(code>=0x20&&code<=0x7e)result+=static_cast<char>(code);
        else if(code<=0xffff)unicode(code);
        else {code-=0x10000;unicode(0xd800+(code>>10));unicode(0xdc00+(code&0x3ff));}
    }
    if(offset<value.size())result+="...";
    return result+'"';
}
[[noreturn]] void conversion_error(const Value& value,int target,const char* reason=nullptr) {
    std::string source=type_name(value.type_tag());
    if(reason&&value.type_tag()==STRING_VALUE)source+=' '+quoted_conversion_text(value.get<std::string>());
    std::string message="Cannot convert "+source+" to "+type_name(target);
    if(reason)message+=std::string(": ")+reason;
    throw std::runtime_error(message);
}
bool decimal_text(const std::string& text,bool integer) {
    std::size_t offset=0;
    if(offset<text.size()&&(text[offset]=='+'||text[offset]=='-'))++offset;
    const auto start=offset;
    while(offset<text.size()&&text[offset]>='0'&&text[offset]<='9')++offset;
    bool digits=offset>start;
    if(!integer&&offset<text.size()&&text[offset]=='.') {
        const auto fraction=++offset;
        while(offset<text.size()&&text[offset]>='0'&&text[offset]<='9')++offset;
        digits=digits||offset>fraction;
    }
    if(!digits)return false;
    if(!integer&&offset<text.size()&&(text[offset]=='e'||text[offset]=='E')) {
        ++offset;if(offset<text.size()&&(text[offset]=='+'||text[offset]=='-'))++offset;
        const auto exponent=offset;
        while(offset<text.size()&&text[offset]>='0'&&text[offset]<='9')++offset;
        if(offset==exponent)return false;
    }
    return offset==text.size();
}
class conversion_locale {
#if defined(_WIN32)
    _locale_t locale=_create_locale(LC_NUMERIC,"C");
#else
    locale_t locale=newlocale(LC_NUMERIC_MASK,"C",nullptr);
#endif
public:
    conversion_locale() {if(!locale)throw std::runtime_error("Cannot create C numeric locale");}
    conversion_locale(const conversion_locale&)=delete;
    conversion_locale& operator=(const conversion_locale&)=delete;
    ~conversion_locale() {
#if defined(_WIN32)
        _free_locale(locale);
#else
        freelocale(locale);
#endif
    }
    template<class T> T parse(const char* text,char** end) const {
#if defined(_WIN32)
        if constexpr(std::is_same_v<T,float>)return _strtof_l(text,end,locale);
        else return _strtod_l(text,end,locale);
#else
        if constexpr(std::is_same_v<T,float>)return strtof_l(text,end,locale);
        else return strtod_l(text,end,locale);
#endif
    }
};
template<class T> T parsed_real(const Value& value,int target) {
    const auto& text=value.get<std::string>();
    if(!decimal_text(text,false))conversion_error(value,target,"invalid decimal number");
    conversion_locale locale;char* end=nullptr;errno=0;
    const auto result=locale.parse<T>(text.c_str(),&end);
    const bool range=errno==ERANGE;
    if(end!=text.c_str()+text.size())conversion_error(value,target,"invalid decimal number");
    // ERANGE also signals permitted underflow. Some libraries report it when
    // rounding a tiny value up to the least normal value, which is allowed too.
    if(!std::isfinite(result)||(range&&std::abs(result)>std::numeric_limits<T>::min()))
        conversion_error(value,target,"value is out of range");
    return result;
}
}
Value convert(const Value& value,int target) {
    if(value.type_tag()==target)return value;
    if(target==STRING_VALUE) {
        if(numeric(value)||value.type_tag()==ADDRESS_VALUE)return Value(to_string(value));
        conversion_error(value,target);
    }
    if(target==ADDRESS_VALUE)conversion_error(value,target);
    if(value.type_tag()==STRING_VALUE) {
        if(target==INT_VALUE) {
            const auto& text=value.get<std::string>();
            if(!decimal_text(text,true))conversion_error(value,target,"invalid decimal integer");
            const char* first=text.data();const char* last=first+text.size();
            if(*first=='+')++first;
            int result=0;const auto parsed=std::from_chars(first,last,result,10);
            if(parsed.ec==std::errc::result_out_of_range)conversion_error(value,target,"value is out of range");
            if(parsed.ec!=std::errc()||parsed.ptr!=last)conversion_error(value,target,"invalid decimal integer");
            return Value(result);
        }
        if(target==FLOAT_VALUE)return Value(parsed_real<float>(value,target));
        if(target==DOUBLE_VALUE)return Value(parsed_real<double>(value,target));
    }
    if(numeric(value)) {
        const double input=number(value);
        if(!std::isfinite(input))conversion_error(value,target,"value is out of range");
        if(target==INT_VALUE) {
            const double truncated=std::trunc(input);
            if(truncated<std::numeric_limits<int>::min()||truncated>std::numeric_limits<int>::max())
                conversion_error(value,target,"value is out of range");
            return Value(static_cast<int>(truncated));
        }
        if(target==FLOAT_VALUE) {
            if(std::abs(input)>std::numeric_limits<float>::max())conversion_error(value,target,"value is out of range");
            return Value(static_cast<float>(input));
        }
        if(target==DOUBLE_VALUE)return Value(input);
    }
    conversion_error(value,target);
}
Value convert_return(const Value& v,int type) {
    if(type==ANY_VALUE)return v;
    if(type==VOID_VALUE) {
        if(v.type_tag()!=VOID_VALUE) throw std::runtime_error("Void function returned a value");
        return v;
    }
    if(v.type_tag()==type) return v;
    if((v.type_tag()==INT_VALUE&&(type==FLOAT_VALUE||type==DOUBLE_VALUE))||
       (v.type_tag()==FLOAT_VALUE&&type==DOUBLE_VALUE)) {
        double n=number(v);
        if(!std::isfinite(n)) throw std::runtime_error("Non-finite return value");
        if(type==DOUBLE_VALUE) return Value(n);
        if(type==FLOAT_VALUE) {
            const float f=static_cast<float>(n);
            if(!std::isfinite(f)) throw std::overflow_error("Float return value overflow");
            return Value(f);
        }
    }
    throw std::runtime_error("Function return type mismatch: expected "+std::string(type_name(type))+", got "+type_name(v.type_tag()));
}
Value unary(UnaryOperation operation,const Value& value) {
    if(operation==UnaryOperation::logical_not)return Value(!truth(value));
    if(operation!=UnaryOperation::negate)throw std::invalid_argument("Unknown unary operation");
    if(value.type_tag()==INT_VALUE)return checked_int(-static_cast<std::int64_t>(value.get<int>()));
    if(!numeric(value))throw std::runtime_error("Negation requires a numeric operand");
    return value.type_tag()==FLOAT_VALUE?Value(-value.get<float>()):Value(-number(value));
}
}
