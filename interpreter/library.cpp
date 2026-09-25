#include "library.h"
#include "heepalloc.h"
#include "internelFunctions.h"
#include <charconv>
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstring>
#include <exception>
#include <limits>
#include <stdexcept>
#include <system_error>
#include <utility>

namespace azertian {
struct function_reference {
    int original_id;
    int* target;
    bool destructor=false;
};
struct loaded_module {
    module_manifest_entry manifest;
    std::shared_ptr<AbdMap> meta;
    std::map<int,std::shared_ptr<ofunction>> definitions;
    std::map<int,function_signature> extern_signatures;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
    std::set<int> fixed_namespaces;
    std::set<int> fixed_extern_namespaces;
    std::set<std::size_t> dependencies;
    std::shared_ptr<ofunction> onload,pre_destroy;
};
namespace {
using V=std::shared_ptr<variable>;
using E=std::shared_ptr<environment>;
using M=std::shared_ptr<AbdMap>;
V nil() { return std::make_shared<variable>(nullptr); }
const char* type_name(int type) {
    switch(type) {
    case INT_VALUE:return "int";
    case STRING_VALUE:return "string";
    case FLOAT_VALUE:return "float";
    case DOUBLE_VALUE:return "double";
    case BOOLEAN_VALUE:return "boolean";
    case VOID_VALUE:return "void";
    case ANY_VALUE:return "any";
    case ADDRESS_VALUE:return "address";
    default:return "unknown";
    }
}
void validate_arguments(const std::string& kind,int id,const std::vector<int>& expected,
                        const std::vector<V>& actual,bool allow_any) {
    if(actual.size()!=expected.size())
        throw std::runtime_error(kind+" "+std::to_string(id)+" argument count mismatch: expected "+
            std::to_string(expected.size())+", got "+std::to_string(actual.size()));
    for(std::size_t i=0;i<expected.size();++i) {
        if(!actual[i])
            throw std::runtime_error(kind+" "+std::to_string(id)+" argument "+std::to_string(i+1)+
                " is a null pointer; use variable(nullptr) for void");
        const int got=static_cast<int>(actual[i]->type);
        if(expected[i]!=got&&!(allow_any&&expected[i]==ANY_VALUE))
            throw std::runtime_error(kind+" "+std::to_string(id)+" argument "+std::to_string(i+1)+
                " type mismatch: expected "+type_name(expected[i])+", got "+type_name(got));
    }
}
struct parsed_module {
    M meta;
    std::size_t global_count=0;
    std::map<int,std::shared_ptr<function>> functions;
    std::map<int,function_signature> extern_signatures;
    std::set<int> namespaces{0};
    std::string hint;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
};
int namespace_of(int id) { return static_cast<int>(static_cast<unsigned int>(id)>>16); }
int relocate_id(int id,int ns) {
    return std::bit_cast<int>((static_cast<std::uint32_t>(ns)<<16)|(static_cast<std::uint32_t>(id)&0xffffu));
}
bool valid_hint(const std::string& hint) {
    auto first=[](unsigned char c){return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='_';};
    if(hint.empty()||!first(hint[0]))return false;
    return std::all_of(hint.begin()+1,hint.end(),[&](unsigned char c){return first(c)||(c>='0'&&c<='9');});
}
std::vector<std::weak_ptr<script>>& live_scripts(){static std::vector<std::weak_ptr<script>> result;return result;}
std::shared_ptr<script> owner(E env) {
    if(!env) throw std::runtime_error("Missing execution environment");
    auto s=env->script.lock();
    if(!s || s->closed) throw std::runtime_error("Script is closed");
    return s;
}
void tick(E env) {
    auto s=owner(env);
    if(!s->remaining_steps) throw std::runtime_error("Script execution step limit exceeded");
    --s->remaining_steps;
}
bool numeric(const V& v) { return v && (v->type==INT_VALUE||v->type==FLOAT_VALUE||v->type==DOUBLE_VALUE); }
double number(const V& v) {
    if(!v) throw std::runtime_error("Null host value");
    switch(v->type) {
    case INT_VALUE:return *static_cast<int*>(v->value);
    case FLOAT_VALUE:return *static_cast<float*>(v->value);
    case DOUBLE_VALUE:return *static_cast<double*>(v->value);
    default:throw std::runtime_error("Expected a numeric value");
    }
}
bool truth(const V& v) {
    if(!v) throw std::runtime_error("Null host value");
    if(v->type==BOOLEAN_VALUE) return *static_cast<bool*>(v->value);
    if(numeric(v)) return number(v)!=0;
    if(v->type==VOID_VALUE) return false;
    throw std::runtime_error("Condition must be boolean or numeric");
}
V checked_int(std::int64_t n) {
    if(n<std::numeric_limits<int>::min()||n>std::numeric_limits<int>::max())
        throw std::overflow_error("Integer overflow");
    return std::make_shared<variable>(static_cast<int>(n));
}
V binary(const std::string& op,const V& a,const V& b) {
    if(op=="add"&&(a->type==STRING_VALUE||b->type==STRING_VALUE))
        return std::make_shared<variable>(value_to_string(a)+value_to_string(b));
    if(a->type==ADDRESS_VALUE||b->type==ADDRESS_VALUE) {
        if(a->type==ADDRESS_VALUE&&b->type==ADDRESS_VALUE) {
            const auto x=static_cast<address*>(a->value)->value,y=static_cast<address*>(b->value)->value;
            if(op=="eq")return std::make_shared<variable>(x==y);
            if(op=="ne")return std::make_shared<variable>(x!=y);
            if(op=="lt")return std::make_shared<variable>(x<y);
            if(op=="le")return std::make_shared<variable>(x<=y);
            if(op=="gt")return std::make_shared<variable>(x>y);
            if(op=="ge")return std::make_shared<variable>(x>=y);
        }
        const bool left_address=a->type==ADDRESS_VALUE;
        if((op=="add"||(op=="minus"&&left_address))&&
           (left_address?b->type==INT_VALUE:a->type==INT_VALUE)) {
            const auto base=static_cast<address*>((left_address?a:b)->value)->value;
            std::int64_t offset=*static_cast<int*>((left_address?b:a)->value);
            if(op=="minus")offset=-offset;
            if(offset>=0) {
                const auto magnitude=static_cast<std::uint64_t>(offset);
                if(base>std::numeric_limits<std::uint64_t>::max()-magnitude)throw std::overflow_error("Address overflow");
                return std::make_shared<variable>(address{base+magnitude});
            }
            const auto magnitude=static_cast<std::uint64_t>(-offset);
            if(base<magnitude)throw std::overflow_error("Address underflow");
            return std::make_shared<variable>(address{base-magnitude});
        }
        throw std::runtime_error("Unsupported address operands for "+op);
    }
    if(op=="eq"||op=="ne") {
        bool eq=false;
        if(numeric(a)&&numeric(b)) eq=number(a)==number(b);
        else if(a->type==b->type) {
            if(a->type==VOID_VALUE) eq=true;
            else if(a->type==STRING_VALUE) eq=*static_cast<std::string*>(a->value)==*static_cast<std::string*>(b->value);
            else if(a->type==BOOLEAN_VALUE) eq=*static_cast<bool*>(a->value)==*static_cast<bool*>(b->value);
        }
        return std::make_shared<variable>(op=="eq"?eq:!eq);
    }
    if(!numeric(a)||!numeric(b)) throw std::runtime_error("Unsupported operands for "+op);
    const double x=number(a),y=number(b);
    if(op=="gt") return std::make_shared<variable>(x>y);
    if(op=="lt") return std::make_shared<variable>(x<y);
    if(op=="ge") return std::make_shared<variable>(x>=y);
    if(op=="le") return std::make_shared<variable>(x<=y);
    if((op=="divide"||op=="mod")&&y==0) throw std::runtime_error("Division by zero");
    if(a->type==INT_VALUE&&b->type==INT_VALUE) {
        const std::int64_t i=*static_cast<int*>(a->value),j=*static_cast<int*>(b->value);
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
    else throw std::runtime_error("Unknown binary operation: "+op);
    if(!std::isfinite(out)) throw std::overflow_error("Non-finite arithmetic result");
    if(a->type==DOUBLE_VALUE||b->type==DOUBLE_VALUE) return std::make_shared<variable>(out);
    const auto f=static_cast<float>(out);
    if(!std::isfinite(f)) throw std::overflow_error("Float overflow");
    return std::make_shared<variable>(f);
}
class extra_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> a,b;
public:
    extra_expression(std::string op,std::shared_ptr<expression> a,std::shared_ptr<expression> b=nullptr)
        :op(std::move(op)),a(std::move(a)),b(std::move(b)) {}
    V execute(E env) override {
        tick(env);
        auto x=a->execute(env)->deepCopy();
        if(op=="not") return std::make_shared<variable>(!truth(x));
        if(op=="neg") {
            if(x->type==INT_VALUE) return checked_int(-static_cast<std::int64_t>(*static_cast<int*>(x->value)));
            if(!numeric(x)) throw std::runtime_error("Negation requires a numeric operand");
            return x->type==FLOAT_VALUE?std::make_shared<variable>(-*static_cast<float*>(x->value)):
                std::make_shared<variable>(-number(x));
        }
        if(op=="and"&&!truth(x)) return std::make_shared<variable>(false);
        if(op=="or"&&truth(x)) return std::make_shared<variable>(true);
        auto y=b->execute(env);
        if(op=="and"||op=="or") return std::make_shared<variable>(truth(y));
        return binary(op,x,y);
    }
};
struct call_guard {
    std::shared_ptr<script> s;
    explicit call_guard(E env):s(owner(env)) {
        if(s->active_calls>=s->max_call_depth) throw std::runtime_error("Script call depth limit exceeded");
        if(s->active_calls==0) s->remaining_steps=s->max_steps;
        ++s->active_calls;
    }
    ~call_guard(){--s->active_calls;}
};
class checked_external_function final:public function {
    std::shared_ptr<function> target;
    function_signature signature;
    int id;
    std::weak_ptr<script> script_owner;
    bool typed;
public:
    checked_external_function(std::shared_ptr<function> target,function_signature signature,int id,std::weak_ptr<script> owner,bool typed=true)
        :target(std::move(target)),signature(std::move(signature)),id(id),script_owner(std::move(owner)),typed(typed) {}
    int return_type() override{return typed?signature.return_type:target->return_type();}
    V invoke(E env,std::vector<V> args={}) override {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        auto script=script_owner.lock();
        if(!script||!env||env->script.lock()!=script)throw std::runtime_error("Function belongs to another or unavailable script");
        script->require_execution();
        call_guard guard(env);
        if(typed)validate_arguments("External function",id,signature.param_types,args,false);
        auto result=target->invoke(std::move(env),std::move(args));
        if(!result)
            throw std::runtime_error("External function "+std::to_string(id)+" returned a null pointer");
        const int got=static_cast<int>(result->type);
        if(typed&&got!=signature.return_type)
            throw std::runtime_error("External function "+std::to_string(id)+
                " return type mismatch: expected "+type_name(signature.return_type)+", got "+type_name(got));
        return result;
    }
};
template<class Body> V with_cleanup(const E& scope,Body body) {
    V result;
    std::exception_ptr failure;
    try {result=body();}catch(...){failure=std::current_exception();}
    try {scope->cleanup(scope);}catch(...){if(!failure)failure=std::current_exception();}
    if(failure)std::rethrow_exception(failure);
    return result;
}
V scoped_execute(const std::shared_ptr<expression>& body,E env) {
    if(std::dynamic_pointer_cast<complex_expression>(body)) return body->execute(env);
    auto scope=std::make_shared<environment>();
    scope->parent=env;scope->script=env->script;scope->frame=env->frame;
    return with_cleanup(scope,[&]{return body->execute(scope);});
}
// Compiler-generated base cleanup. It does not encode classes or object layouts:
// both operands are ordinary code, and the finalizer is usually a function call.
class cleanup_expression final:public expression {
    std::shared_ptr<expression> body,cleanup;
    bool on_error;
public:
    cleanup_expression(std::shared_ptr<expression> body,std::shared_ptr<expression> cleanup,bool on_error)
        :body(std::move(body)),cleanup(std::move(cleanup)),on_error(on_error) {}
    V execute(E env) override {
        std::exception_ptr failure;
        try {tick(env);scoped_execute(body,env);}catch(...){failure=std::current_exception();}
        if(!on_error||failure) {
            auto frame=env->frame;
            const bool returned=frame&&frame->returned,broken=frame&&frame->break_requested,
                continued=frame&&frame->continue_requested;
            const auto result=frame?frame->result:nullptr;
            if(frame){frame->returned=false;frame->break_requested=false;frame->continue_requested=false;}
            try {scoped_execute(cleanup,env);}catch(...){if(!failure)failure=std::current_exception();}
            if(frame){frame->returned=returned;frame->break_requested=broken;frame->continue_requested=continued;frame->result=result;}
        }
        if(failure)std::rethrow_exception(failure);
        return nil();
    }
};
class object_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> value;
    int offset=0;
    std::optional<int> destructor;
    bool manual=false;
public:
    object_expression(std::string operation,std::shared_ptr<expression> value,int number=0,bool manual=false)
        :op(std::move(operation)),value(std::move(value)),manual(manual) {
        if(op=="oa")offset=number;
    }
    object_expression(std::shared_ptr<expression> value,std::optional<int> destructor,bool manual)
        :op("ob"),value(std::move(value)),destructor(destructor),manual(manual) {}
    int* destructor_address(){return destructor?&*destructor:nullptr;}
    V execute(E env) override {
        tick(env);auto pointer=value->execute(env);
        if(pointer->type!=ADDRESS_VALUE)throw std::runtime_error("Object pointer requires address type");
        const auto object_pointer=*static_cast<address*>(pointer->value);
        if(op=="oa")return std::make_shared<variable>(heap::object_address(object_pointer,offset));
        if(op=="ob")heap::register_object(object_pointer,destructor,manual,env);
        else heap::delete_object(object_pointer,env);
        return nil();
    }
};
V convert_return(V v,int type) {
    if(type==VOID_VALUE) {
        if(v->type!=VOID_VALUE) throw std::runtime_error("Void function returned a value");
        return v;
    }
    if(v->type==type) return v->deepCopy();
    if(numeric(v)&&(type==INT_VALUE||type==FLOAT_VALUE||type==DOUBLE_VALUE)) {
        double n=number(v);
        if(!std::isfinite(n)) throw std::runtime_error("Non-finite return value");
        if(type==DOUBLE_VALUE) return std::make_shared<variable>(n);
        if(type==FLOAT_VALUE) {
            const float f=static_cast<float>(n);
            if(!std::isfinite(f)) throw std::overflow_error("Float return value overflow");
            return std::make_shared<variable>(f);
        }
        if(std::trunc(n)!=n||n<std::numeric_limits<int>::min()||n>std::numeric_limits<int>::max())
            throw std::runtime_error("Return value cannot be represented as int");
        return std::make_shared<variable>(static_cast<int>(n));
    }
    throw std::runtime_error("Function return type mismatch or missing return");
}
// v6 records are raw ABD stacks. Views borrow the caller's input only while
// decoding; the resulting native expression tree owns all retained data.
struct raw_view {
    const unsigned char* data;
    std::size_t size;
    std::size_t depth;
};
void validate_utf8(raw_view value) {
    std::size_t i=0;
    while(i<value.size) {
        const unsigned first=value.data[i++];
        if(first<0x80)continue;
        int remaining;std::uint32_t code,minimum;
        if(first>=0xc2&&first<=0xdf){remaining=1;code=first&0x1f;minimum=0x80;}
        else if(first>=0xe0&&first<=0xef){remaining=2;code=first&0x0f;minimum=0x800;}
        else if(first>=0xf0&&first<=0xf4){remaining=3;code=first&0x07;minimum=0x10000;}
        else throw std::invalid_argument("Invalid UTF-8 in exec string");
        if(static_cast<std::size_t>(remaining)>value.size-i)throw std::invalid_argument("Truncated UTF-8 in exec string");
        while(remaining--) {
            const unsigned next=value.data[i++];
            if((next&0xc0)!=0x80)throw std::invalid_argument("Invalid UTF-8 in exec string");
            code=(code<<6)|(next&0x3f);
        }
        if(code<minimum||code>0x10ffff||(code>=0xd800&&code<=0xdfff))throw std::invalid_argument("Invalid UTF-8 in exec string");
    }
}
class raw_cursor {
    raw_view view;
    std::size_t position=0;
public:
    explicit raw_cursor(raw_view value):view(value) {
        if(view.depth>128)throw std::invalid_argument("ABD nesting exceeds 128 levels");
    }
    bool empty() const{return position==view.size;}
    raw_view take() {
        if(view.size-position<4)throw std::invalid_argument("Missing or truncated exec field");
        const int size=b2i(view.data+position);
        if(size<0||static_cast<std::size_t>(size)>view.size-position-4)
            throw std::invalid_argument("Negative or truncated exec field");
        raw_view result{view.data+position+4,static_cast<std::size_t>(size),view.depth+1};
        position+=4+result.size;return result;
    }
    int integer() {
        auto value=take();
        if(value.size!=4)throw std::invalid_argument("Exec integer field must contain four bytes");
        return b2i(value.data);
    }
    bool boolean() {
        auto value=take();
        if(value.size!=1||value.data[0]>1)throw std::invalid_argument("Exec boolean field must be one byte, 0 or 1");
        return value.data[0]==1;
    }
    std::string string() {auto value=take();validate_utf8(value);return std::string(reinterpret_cast<const char*>(value.data),value.size);}
    void finish() const {if(!empty())throw std::invalid_argument("Unexpected extra exec fields");}
};
// Metadata is a genuinely extensible typed Map; unlike executable constants,
// its fields can contain nested maps/arrays and arbitrary raw byte values.
void check_typed_metadata(raw_view view,int type) {
    if(type==2||type==0xad) {
        raw_cursor cursor(view);std::set<std::string> keys;
        while(!cursor.empty()) {
            if(type==2&&!keys.insert(cursor.string()).second)throw std::invalid_argument("Duplicate extension key");
            const int child_type=cursor.integer();check_typed_metadata(cursor.take(),child_type);
        }
        return;
    }
    switch(type) {
    case 1:validate_utf8(view);return;
    case 3:case 0xce867:
        if(view.size!=4)throw std::invalid_argument("Invalid extension scalar width");return;
    case 0xce1066:case 0xce200b:
        if(view.size!=8)throw std::invalid_argument("Invalid extension scalar width");return;
    case 0x0d00:
        if(view.size!=1||view.data[0]>1)throw std::invalid_argument("Invalid extension boolean");return;
    case 0xce2009:
        if(view.size==0)throw std::invalid_argument("Empty extension BigInteger");return;
    case 0xce200a:return;
    default:throw std::invalid_argument("Unsupported extension value type");
    }
}
std::shared_ptr<AbdValue> copy_raw(raw_view value) {
    return std::make_shared<AbdValue>(value.data,static_cast<int>(value.size));
}
std::shared_ptr<expression> parse_raw_constant(raw_view envelope) {
    raw_cursor fields(envelope);
    const int type=fields.integer();const auto payload=fields.take();const auto value=copy_raw(payload);fields.finish();
    std::shared_ptr<AbdMapValue> scalar;
    switch(type) {
    case 1:validate_utf8(payload);scalar=std::make_shared<StringAbdValue>(value);break;
    case 3:scalar=std::make_shared<IntAbdValue>(value);break;
    case 0xce200b:scalar=std::make_shared<AddressAbdValue>(value);break;
    case 0xce1066: {
        auto number=std::make_shared<DoubleAbdValue>(value);
        if(!std::isfinite(number->data))throw std::invalid_argument("Non-finite exec constant");
        scalar=std::move(number);break;
    }
    case 0xce867: {
        auto number=std::make_shared<FloatAbdValue>(value);
        if(!std::isfinite(number->data))throw std::invalid_argument("Non-finite exec constant");
        scalar=std::move(number);break;
    }
    case 0x0d00:scalar=std::make_shared<BoolAbdValue>(value);break;
    case 0xce200a:scalar=std::make_shared<ByteArrayValue>(value,type);break;
    default:throw std::invalid_argument("Unsupported exec constant type: "+std::to_string(type));
    }
    return std::make_shared<constantExpression>(std::move(scalar));
}
struct raw_function_layout {
    std::size_t parameters,slots,globals;
    std::vector<function_reference>* references;
    void check_slot(int id,bool declaration=false) const {
        if(id<0) {
            if(declaration)throw std::invalid_argument("Cannot declare a negative variable slot");
            const auto index=static_cast<std::uint64_t>(-static_cast<std::int64_t>(id)-1);
            if(index>=globals)throw std::invalid_argument("Global variable slot is outside its module: "+std::to_string(id));
        } else {
            if(static_cast<std::size_t>(id)>=slots)throw std::invalid_argument("Local variable slot is outside its function: "+std::to_string(id));
            if(declaration&&static_cast<std::size_t>(id)<parameters)throw std::invalid_argument("Cannot redeclare a parameter slot");
        }
    }
};
using X=std::shared_ptr<expression>;
X parse_raw_expression(raw_view view,const raw_function_layout& layout,std::size_t logical_depth);
std::vector<X> parse_raw_expressions(raw_view view,const raw_function_layout& layout,std::size_t logical_depth) {
    raw_cursor cursor(view);std::vector<X> result;
    while(!cursor.empty())result.push_back(parse_raw_expression(cursor.take(),layout,logical_depth));
    return result;
}
template<class T> X raw_binary(X a,X b) {
    auto node=std::make_shared<T>();node->exp1=std::move(a);node->exp2=std::move(b);return node;
}
X parse_raw_expression(raw_view view,const raw_function_layout& layout,std::size_t logical_depth) {
    raw_cursor fields(view);const int opcode=fields.integer();
    // The old representation counted Map/Array levels, while literal scalars
    // did not introduce a level. Retain that budget as well as raw stack depth.
    if(logical_depth>128||(opcode!=0&&logical_depth>=128))throw std::invalid_argument("ABD nesting exceeds 128 levels");
    const auto child=[&]{return parse_raw_expression(fields.take(),layout,logical_depth+1);};
    X result;
    switch(opcode) {
    case 0:result=parse_raw_constant(fields.take());break;
    case 1: {
        auto node=std::make_shared<complex_expression>();
        node->expressions=parse_raw_expressions(fields.take(),layout,logical_depth+1);result=std::move(node);break;
    }
    case 2: {
        auto node=std::make_shared<functionInvocationExpression>();node->function_id=fields.integer();
        layout.references->push_back({node->function_id,&node->function_id,false});
        node->params=parse_raw_expressions(fields.take(),layout,logical_depth+2);result=std::move(node);break;
    }
    case 3: {
        const int slot=fields.integer();layout.check_slot(slot);
        auto node=std::make_shared<varExpression>();node->varindex=slot;result=std::move(node);break;
    }
    case 4: {
        const int slot=fields.integer();layout.check_slot(slot,true);const int type=fields.integer();
        if(type<INT_VALUE||type>ADDRESS_VALUE||type==VOID_VALUE)throw std::invalid_argument("Invalid declared variable type");
        auto node=std::make_shared<varDefineExpression>();node->varindex=slot;
        if(fields.boolean())node->initializer=child();result=std::move(node);break;
    }
    case 5: {
        const int slot=fields.integer();layout.check_slot(slot);
        auto node=std::make_shared<varSetExpression>();node->varindex=slot;node->expression=child();result=std::move(node);break;
    }
    case 6: {
        auto target=child();auto value=child();
        auto call=std::dynamic_pointer_cast<functionInvocationExpression>(target);
        if(!std::dynamic_pointer_cast<varExpression>(target)&&!(call&&call->function_id==0x0abd0006))
            throw std::invalid_argument("Assignment target must be a variable or mem_get(pointer)");
        result=raw_binary<movExpression>(std::move(target),std::move(value));break;
    }
    case 7:case 8: {
        auto node=std::make_shared<returnExpression>();node->object_return=opcode==8;
        if(opcode==8||fields.boolean())node->returnType=child();result=std::move(node);break;
    }
    case 9: {
        auto base=child();const int offset=fields.integer();
        if(offset<0)throw std::invalid_argument("Negative object member offset");
        result=std::make_shared<object_expression>("oa",std::move(base),offset);break;
    }
    case 10: {
        auto address=child();std::optional<int> destructor;
        if(fields.boolean())destructor=fields.integer();
        const bool manual=fields.boolean();
        if(destructor&&(*destructor==0||*destructor==1||*destructor==0x0fff0000||namespace_of(*destructor)==0x0abd))
            throw std::invalid_argument("Invalid object destructor id");
        auto node=std::make_shared<object_expression>(std::move(address),destructor,manual);
        if(destructor)layout.references->push_back({*destructor,node->destructor_address(),true});
        result=std::move(node);break;
    }
    case 11:result=std::make_shared<object_expression>("od",child());break;
    case 12:case 13:case 14:case 15:case 16:case 17:case 18:case 19:case 20:case 21:case 22:case 23:case 24: {
        auto a=child();auto b=child();
        switch(opcode) {
        case 12:result=raw_binary<addExpression>(std::move(a),std::move(b));break;
        case 13:result=raw_binary<minusExpression>(std::move(a),std::move(b));break;
        case 14:result=raw_binary<multiplyExpression>(std::move(a),std::move(b));break;
        case 15:result=raw_binary<divideExpression>(std::move(a),std::move(b));break;
        case 17:result=raw_binary<gtExpression>(std::move(a),std::move(b));break;
        case 18:result=raw_binary<ltExpression>(std::move(a),std::move(b));break;
        case 19:result=raw_binary<equalExpression>(std::move(a),std::move(b));break;
        default: {
            const char* operation=opcode==16?"mod":opcode==20?"ne":opcode==21?"ge":opcode==22?"le":opcode==23?"and":"or";
            result=std::make_shared<extra_expression>(operation,std::move(a),std::move(b));break;
        }
        }
        break;
    }
    case 25:case 26:result=std::make_shared<extra_expression>(opcode==25?"not":"neg",child());break;
    case 27: {
        auto node=std::make_shared<ifExpression>();node->exp1=child();node->exp2=child();
        if(fields.boolean())node->otherwise=child();result=std::move(node);break;
    }
    case 28: {auto condition=child();auto body=child();result=raw_binary<whileExpression>(std::move(condition),std::move(body));break;}
    case 29:result=std::make_shared<breakExpression>();break;
    case 30:result=std::make_shared<continueExpression>();break;
    case 31: {
        auto body=child();auto cleanup=child();const bool on_error=fields.boolean();
        result=std::make_shared<cleanup_expression>(std::move(body),std::move(cleanup),on_error);break;
    }
    default:throw std::invalid_argument("Unknown exec opcode: "+std::to_string(opcode));
    }
    fields.finish();return result;
}
std::vector<int> parse_raw_types(raw_view view,bool script_parameters) {
    raw_cursor fields(view);std::vector<int> result;
    while(!fields.empty()) {
        if(result.size()>=MAX_VARIABLE_SLOTS)throw std::invalid_argument("Too many parameter types");
        const int type=fields.integer();
        if(type<INT_VALUE||(type>BOOLEAN_VALUE&&type!=ADDRESS_VALUE&&!(script_parameters&&type==ANY_VALUE)))
            throw std::invalid_argument("Invalid parameter type");
        result.push_back(type);
    }
    return result;
}
parsed_module parse_raw_module(raw_view root,std::size_t offset) {
    raw_cursor fields(root);parsed_module module;
    if(fields.string()!="AZSCRIPT")throw std::invalid_argument("Invalid exec magic");
    if(fields.integer()!=6)throw std::invalid_argument("Unsupported exec version");
    fields.integer();fields.string(); // Source version/author are non-executable metadata.
    const int globals=fields.integer();
    if(globals<0||static_cast<std::size_t>(globals)>MAX_VARIABLE_SLOTS||offset>MAX_VARIABLE_SLOTS-static_cast<std::size_t>(globals))
        throw std::invalid_argument("Invalid global variable count");
    module.global_count=static_cast<std::size_t>(globals);
    auto extension=fields.take();check_typed_metadata(extension,2);
    module.meta=std::make_shared<AbdMap>(std::make_shared<AbdStack>(copy_raw(extension)));
    raw_cursor signatures(fields.take());
    while(!signatures.empty()) {
        raw_cursor entry(signatures.take());const int id=entry.integer();function_signature signature;
        if(id==0||id==1||id==0x0fff0000||namespace_of(id)==0x0abd)throw std::invalid_argument("Invalid external function id");
        signature.return_type=entry.integer();
        if(signature.return_type<INT_VALUE||(signature.return_type>VOID_VALUE&&signature.return_type!=ADDRESS_VALUE))throw std::invalid_argument("Invalid external return type");
        signature.param_types=parse_raw_types(entry.take(),false);entry.finish();
        if(!module.extern_signatures.emplace(id,std::move(signature)).second)throw std::invalid_argument("Duplicate external signature id: "+std::to_string(id));
    }
    raw_cursor functions(fields.take());
    module.hint=fields.string();
    if(!module.hint.empty()&&!valid_hint(module.hint))throw std::invalid_argument("Invalid namespace hint");
    raw_cursor assumptions(fields.take());fields.finish();std::set<std::string> hint_names;
    while(!assumptions.empty()) {
        raw_cursor record(assumptions.take());auto hint=record.string();const int ns=record.integer();record.finish();
        if(!valid_hint(hint)||ns<=0||ns>0xffff||ns==0x0abd||ns==0x0fff)throw std::invalid_argument("Invalid namespace assumption");
        if(!hint_names.insert(hint).second||!module.assumptions.emplace(ns,std::move(hint)).second)
            throw std::invalid_argument("Duplicate namespace assumption");
    }
    if(!module.hint.empty()&&!hint_names.contains(module.hint))throw std::invalid_argument("Hint module requires an assumption for itself");
    while(!functions.empty()) {
        raw_cursor entry(functions.take());auto fn=std::make_shared<ofunction>();
        fn->global_offset=offset;fn->global_count=module.global_count;
        fn->function_id=entry.integer();fn->rett=entry.integer();fn->param_count=entry.integer();fn->local_count=entry.integer();
        if(namespace_of(fn->function_id)==0x0abd)
            throw std::invalid_argument("Invalid script function id");
        if(!module.hint.empty()&&namespace_of(fn->function_id)!=0)throw std::invalid_argument("Hint module definitions require namespace zero");
        if(module.hint.empty()&&fn->function_id!=0&&fn->function_id!=1&&module.assumptions.contains(namespace_of(fn->function_id)))
            throw std::invalid_argument("Namespace assumption overlaps module definitions");
        if(fn->rett<INT_VALUE||(fn->rett>VOID_VALUE&&fn->rett!=ADDRESS_VALUE))throw std::invalid_argument("Unknown return type");
        if(fn->param_count<0||fn->local_count<0||static_cast<std::size_t>(fn->param_count)>MAX_VARIABLE_SLOTS||
           static_cast<std::size_t>(fn->local_count)>MAX_VARIABLE_SLOTS-static_cast<std::size_t>(fn->param_count))
            throw std::invalid_argument("Invalid function variable count");
        fn->param_types=parse_raw_types(entry.take(),true);
        if(fn->param_types.size()!=static_cast<std::size_t>(fn->param_count))throw std::invalid_argument("param-count and param-types disagree");
        if((fn->function_id==0||fn->function_id==1)&&(fn->rett!=VOID_VALUE||fn->param_count!=0))
            throw std::invalid_argument("Lifecycle function must return void and take no parameters");
        const raw_function_layout layout{static_cast<std::size_t>(fn->param_count),
            static_cast<std::size_t>(fn->param_count)+static_cast<std::size_t>(fn->local_count),module.global_count,&module.references};
        fn->code=parse_raw_expression(entry.take(),layout,3);entry.finish();
        const int id=fn->function_id;
        if(!module.functions.emplace(id,std::move(fn)).second)throw std::invalid_argument("Duplicate function id: "+std::to_string(id));
        module.namespaces.insert(namespace_of(id));
    }
    return module;
}
parsed_module decode_module(const unsigned char* bytes,std::size_t length,std::size_t offset=0) {
    if(length>64*1024*1024)throw std::invalid_argument("Script exceeds 64 MiB limit");
    if(!bytes||length<4)throw std::invalid_argument("Truncated ABD script");
    raw_cursor file({bytes,length,0});auto root=file.take();file.finish();
    raw_cursor fields(root);
    if(!fields.empty()) {
        const auto first=fields.take();
        if(first.size==8&&std::memcmp(first.data,"AZSCRIPT",8)==0)return parse_raw_module(root,offset);
    }
    throw std::invalid_argument("Unsupported script format: expected raw exec v6");
}
}
std::recursive_mutex& runtime_mutex(){static std::recursive_mutex m;return m;}
bool script_namespace_in_use(int ns) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto& registry=live_scripts();
    registry.erase(std::remove_if(registry.begin(),registry.end(),[](auto& value){return value.expired();}),registry.end());
    for(auto& weak:registry)if(auto value=weak.lock();value&&!value->closed&&value->script_namespaces.contains(ns))return true;
    return false;
}
std::shared_ptr<script> load_script(const unsigned char* bytes,std::size_t length) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto result=std::make_shared<script>();
    result->baseEnv=std::make_shared<environment>();result->baseEnv->script=result;
    result->insert_script(bytes,length);
    live_scripts().push_back(result);
    return result;
}
std::shared_ptr<script> load_script(unsigned char* bytes) {
    if(!bytes)throw std::invalid_argument("Null script");
    const int length=b2i(bytes);if(length<0)throw std::invalid_argument("Negative script length");
    return load_script(bytes,static_cast<std::size_t>(length)+4);
}
void script::require_execution() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(destroying||initializing)return;
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(!setup)throw std::runtime_error("Script setup is incomplete; call flush before execution");
}
std::shared_ptr<function> script::getFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());require_execution();
    if(auto found=functions.find(id);found!=functions.end())return found->second;
    const int ns=namespace_of(id);
    if(ns!=0x0abd&&script_namespaces.contains(ns))
        throw std::runtime_error("Unknown function id: "+std::to_string(id)+" (script namespace)");
    if(auto target=getiFunction(id)) {
        if(auto signature=extern_signatures.find(id);signature!=extern_signatures.end())
            return std::make_shared<checked_external_function>(std::move(target),signature->second,id,weak_from_this());
        return std::make_shared<checked_external_function>(std::move(target),function_signature{},id,weak_from_this(),false);
    }
    throw std::runtime_error("Unknown function id: "+std::to_string(id));
}
bool script::hasFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return false;
    if(functions.contains(id))return true;
    const int ns=namespace_of(id);
    return (ns==0x0abd||!script_namespaces.contains(ns))&&isInternelFunction(id);
}
V script::invoke(int id,std::vector<V> args) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(destroying)throw std::runtime_error("Script is being destroyed");
    require_execution();return getFunction(id)->invoke(baseEnv,std::move(args));
}
int script::namespace_for_hint(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(module->manifest.hint==hint&&!hint.empty())return *module->manifest.actual_namespace;
    throw std::runtime_error("Unknown namespace hint: "+hint);
}
std::vector<module_manifest_entry> script::module_manifest() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());std::vector<module_manifest_entry> result;
    for(auto& module:modules)result.push_back(module->manifest);
    return result;
}
void script::insert_script(const unsigned char* bytes,std::size_t length) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot insert during script execution");
    auto parsed=decode_module(bytes,length,baseEnv->variables.size());
    auto module=std::make_shared<loaded_module>();
    module->manifest.bytes.assign(bytes,bytes+length);
    module->manifest.global_offset=baseEnv->variables.size();module->manifest.global_count=parsed.global_count;
    module->manifest.hint=std::move(parsed.hint);module->meta=std::move(parsed.meta);
    module->assumptions=std::move(parsed.assumptions);module->extern_signatures=std::move(parsed.extern_signatures);
    module->references=std::move(parsed.references);
    for(auto& [id,signature]:module->extern_signatures) {
        (void)signature;const int ns=namespace_of(id);
        if(!module->assumptions.contains(ns)&&ns!=0x0abd)module->fixed_extern_namespaces.insert(ns);
    }
    auto occupied=registered_executor_namespaces();
    occupied.insert({0,0x0abd,0x0fff});
    occupied.insert(script_namespaces.begin(),script_namespaces.end());
    occupied.insert(fixed_host_namespaces.begin(),fixed_host_namespaces.end());
    occupied.insert(module->fixed_extern_namespaces.begin(),module->fixed_extern_namespaces.end());
    for(auto& weak:live_scripts())if(auto value=weak.lock();value&&!value->closed) {
        occupied.insert(value->script_namespaces.begin(),value->script_namespaces.end());
        occupied.insert(value->fixed_host_namespaces.begin(),value->fixed_host_namespaces.end());
    }
    if(!module->manifest.hint.empty()) {
        for(auto& current:modules)if(current->manifest.hint==module->manifest.hint)throw std::invalid_argument("Duplicate namespace hint: "+module->manifest.hint);
        int candidate=1;while(candidate<=0xffff&&occupied.contains(candidate))++candidate;
        if(candidate>0xffff)throw std::runtime_error("No namespace is available for hint module");
        module->manifest.actual_namespace=candidate;
    }
    auto candidate_functions=functions;auto candidate_namespaces=script_namespaces;
    const auto host_namespaces=registered_executor_namespaces();
    for(auto& [original_id,generic]:parsed.functions) {
        auto fn=std::dynamic_pointer_cast<ofunction>(generic);fn->script_owner=weak_from_this();
        if(original_id==0){module->onload=fn;continue;}
        if(original_id==1){module->pre_destroy=fn;continue;}
        const int actual_id=module->manifest.actual_namespace?relocate_id(original_id,*module->manifest.actual_namespace):original_id;
        const int ns=namespace_of(actual_id);
        if(host_namespaces.contains(ns))throw std::invalid_argument("Script namespace conflicts with registered host executor");
        if(!module->manifest.actual_namespace) {
            for(auto& current:modules)if(current->manifest.actual_namespace==ns)throw std::invalid_argument("Fixed namespace conflicts with mounted hint module");
            module->fixed_namespaces.insert(ns);
        }
        fn->function_id=actual_id;
        if(!candidate_functions.emplace(actual_id,fn).second)throw std::invalid_argument("Duplicate function id: "+std::to_string(actual_id));
        module->definitions.emplace(original_id,std::move(fn));candidate_namespaces.insert(ns);
    }
    if(module->manifest.actual_namespace)candidate_namespaces.insert(*module->manifest.actual_namespace);
    for(int ns:module->fixed_extern_namespaces)for(auto& current:modules)
        if(current->manifest.actual_namespace==ns)throw std::invalid_argument("Fixed external namespace conflicts with mounted hint module");
    auto candidate_hosts=fixed_host_namespaces;candidate_hosts.insert(module->fixed_extern_namespaces.begin(),module->fixed_extern_namespaces.end());
    auto candidate_globals=baseEnv->variables;candidate_globals.reserve(candidate_globals.size()+parsed.global_count);
    for(std::size_t i=0;i<parsed.global_count;++i)candidate_globals.push_back(nil());
    auto candidate_modules=modules;candidate_modules.push_back(module);
    // Every allocation and validation above is complete before state is published.
    functions.swap(candidate_functions);script_namespaces.swap(candidate_namespaces);fixed_host_namespaces.swap(candidate_hosts);
    baseEnv->variables.swap(candidate_globals);modules.swap(candidate_modules);meta=module->meta;setup=false;
}
void script::flush() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot flush during script execution");
    if(setup)return;
    std::map<std::string,std::size_t> hints;
    for(std::size_t i=0;i<modules.size();++i)if(!modules[i]->manifest.hint.empty())hints.emplace(modules[i]->manifest.hint,i);
    std::map<int,std::size_t> function_modules;
    for(std::size_t i=0;i<modules.size();++i)for(auto& [original,fn]:modules[i]->definitions){(void)original;function_modules.emplace(fn->function_id,i);}
    std::vector<std::set<std::size_t>> dependencies;
    for(auto& module:modules)dependencies.push_back(module->dependencies);
    std::vector<std::map<int,function_signature>> resolved_externs(modules.size());
    std::vector<std::pair<function_reference*,int>> patches;std::map<int,function_signature> bindings;
    for(std::size_t index=0;index<modules.size();++index) {
        auto& module=*modules[index];std::map<int,int> mapping;
        for(auto& [placeholder,hint]:module.assumptions) {
            auto target=hints.find(hint);if(target==hints.end())throw std::runtime_error("Missing namespace hint: "+hint);
            mapping.emplace(placeholder,*modules[target->second]->manifest.actual_namespace);
            if(target->second!=index)dependencies[index].insert(target->second);
        }
        const auto resolve=[&](int id){auto found=mapping.find(namespace_of(id));return found==mapping.end()?id:relocate_id(id,found->second);};
        for(auto& [original_id,signature]:module.extern_signatures) {
            const int id=resolve(original_id);auto target=functions.find(id);
            if(target!=functions.end()) {
                if(auto defining=function_modules.find(id);defining!=function_modules.end()&&defining->second!=index)dependencies[index].insert(defining->second);
                auto fn=std::dynamic_pointer_cast<ofunction>(target->second);
                if(!fn||fn->rett!=signature.return_type||fn->param_types!=signature.param_types)
                    throw std::runtime_error("External signature does not match script function: "+std::to_string(id));
            } else if(mapping.contains(namespace_of(original_id))||script_namespaces.contains(namespace_of(id))||namespace_of(id)==0x0fff)
                throw std::runtime_error("Imported script function is missing: "+std::to_string(id));
            auto [entry,added]=bindings.emplace(id,signature);
            if(!added&&!(entry->second==signature))throw std::runtime_error("Conflicting external signature id: "+std::to_string(id));
            resolved_externs[index].emplace(id,signature);
        }
        for(auto& reference:module.references) {
            const int id=resolve(reference.original_id);
            if(auto defining=function_modules.find(id);defining!=function_modules.end()&&defining->second!=index)dependencies[index].insert(defining->second);
            if(mapping.contains(namespace_of(reference.original_id))||reference.destructor) {
                auto target=functions.find(id);
                if(target==functions.end())throw std::runtime_error("Referenced script function is missing: "+std::to_string(id));
                if(reference.destructor) {
                    auto fn=std::dynamic_pointer_cast<ofunction>(target->second);
                    if(!fn||fn->rett!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE})
                        throw std::runtime_error("Object destructor must be a script function void(address)");
                }
            }
            patches.emplace_back(&reference,id);
        }
    }
    // Iterative SCC discovery keeps arbitrarily long module dependency chains
    // off the C++ call stack. Components and ready ties use stable load order.
    std::vector<bool> visited(modules.size());std::vector<std::size_t> finished;
    for(std::size_t start=0;start<modules.size();++start)if(!visited[start]) {
        std::vector<std::pair<std::size_t,bool>> pending{{start,false}};
        while(!pending.empty()) {
            auto [current,expanded]=pending.back();pending.pop_back();
            if(expanded){finished.push_back(current);continue;}
            if(visited[current])continue;
            visited[current]=true;pending.emplace_back(current,true);
            for(auto next=dependencies[current].rbegin();next!=dependencies[current].rend();++next)
                if(!visited[*next])pending.emplace_back(*next,false);
        }
    }
    std::vector<std::vector<std::size_t>> incoming(modules.size()),groups;
    for(std::size_t i=0;i<dependencies.size();++i)for(auto next:dependencies[i])incoming[next].push_back(i);
    std::vector<int> component(modules.size(),-1);
    for(auto start=finished.rbegin();start!=finished.rend();++start)if(component[*start]<0) {
        const int id=static_cast<int>(groups.size());std::vector<std::size_t> pending{*start},group;
        component[*start]=id;
        while(!pending.empty()) {
            auto current=pending.back();pending.pop_back();group.push_back(current);
            for(auto next:incoming[current])if(component[next]<0){component[next]=id;pending.push_back(next);}
        }
        std::sort(group.begin(),group.end());groups.push_back(std::move(group));
    }
    std::vector<std::set<int>> waits(groups.size());
    for(std::size_t i=0;i<dependencies.size();++i)for(auto next:dependencies[i])if(component[i]!=component[next])waits[component[i]].insert(component[next]);
    std::set<std::pair<std::size_t,int>> ready;
    for(std::size_t i=0;i<groups.size();++i)if(waits[i].empty())ready.emplace(groups[i][0],static_cast<int>(i));
    std::vector<std::size_t> order;
    while(!ready.empty()) {
        const int current=ready.begin()->second;ready.erase(ready.begin());
        order.insert(order.end(),groups[current].begin(),groups[current].end());
        for(std::size_t i=0;i<groups.size();++i)if(waits[i].erase(current)&&waits[i].empty())ready.emplace(groups[i][0],static_cast<int>(i));
    }
    on_destory.reserve(on_destory.size()+modules.size());
    // Link validation is atomic: no references change and no onload runs before
    // every module has a valid resolution and the complete order is available.
    for(auto& [reference,id]:patches){*reference->target=id;reference->original_id=id;}
    for(std::size_t i=0;i<modules.size();++i) {
        modules[i]->extern_signatures.swap(resolved_externs[i]);
        modules[i]->dependencies.swap(dependencies[i]);
        modules[i]->assumptions.clear();
        for(auto& [original,fn]:modules[i]->definitions){(void)original;fn->linked=true;}
        if(modules[i]->onload)modules[i]->onload->linked=true;
        if(modules[i]->pre_destroy)modules[i]->pre_destroy->linked=true;
    }
    extern_signatures.swap(bindings);initializing=true;
    try {
        call_guard guard(baseEnv);
        for(auto index:order) {
            auto& module=*modules[index];if(module.manifest.initialized)continue;
            if(module.onload)module.onload->invoke(baseEnv,{});
            module.manifest.initialized=true;
            if(module.pre_destroy)on_destory.push_back(module.pre_destroy);
        }
        initializing=false;setup=true;
    } catch(...) {initializing=false;setup=false;faulted=true;throw;}
}
void script::destroy() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return;
    if(destroying)throw std::runtime_error("Script is being destroyed");
    if(active_calls||initializing)throw std::runtime_error("Cannot destroy a running script");
    destroying=true;std::exception_ptr failure;
    try {
        call_guard guard(baseEnv);
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {
            try{(*hook)->invoke(baseEnv,{});}catch(...){if(!failure)failure=std::current_exception();}
        }
        try{baseEnv->cleanup(baseEnv);}catch(...){if(!failure)failure=std::current_exception();}
    }catch(...){if(!failure)failure=std::current_exception();}
    heap::discard_script_objects(this);
    closed=true;setup=false;functions.clear();extern_signatures.clear();on_destory.clear();modules.clear();baseEnv.reset();destroying=false;
    if(failure)std::rethrow_exception(failure);
}
int ofunction::return_type(){return rett;}
V ofunction::invoke(E ev,std::vector<V> args) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto bound=script_owner.lock();
    if(!bound||!ev||ev->script.lock()!=bound)throw std::runtime_error("Function belongs to another or unavailable script");
    bound->require_execution();
    if(!linked)throw std::runtime_error("Function module has not been linked");
    call_guard guard(ev);
    validate_arguments("Script function",function_id,param_types,args,true);
    auto env=std::make_shared<environment>();
    env->script=guard.s;env->parent=guard.s->baseEnv;env->caller=ev;
    env->frame=std::make_shared<function_frame>();
    env->frame->global_offset=global_offset;env->frame->global_count=global_count;
    env->frame->param_count=args.size();
    env->frame->slots.resize(static_cast<std::size_t>(param_count)+static_cast<std::size_t>(local_count));
    for(std::size_t i=0;i<args.size();++i) {
        if(!args[i]) throw std::invalid_argument("Null argument pointer; use variable(nullptr)");
        env->frame->slots[i]=args[i]->deepCopy();
    }
    return with_cleanup(env,[&] {
        if(auto block=std::dynamic_pointer_cast<complex_expression>(code)) block->execute_body(env);
        else code->execute(env);
        return convert_return(env->frame->result,rett);
    });
}
V complex_expression::execute_body(E env) {
    tick(env);
    for(auto& e:expressions) {
        if(env->frame&&(env->frame->returned||env->frame->break_requested||env->frame->continue_requested)) break;
        e->execute(env);
    }
    return nil();
}
V complex_expression::execute(E env) {
    auto scope=std::make_shared<environment>();scope->parent=env;scope->script=env->script;scope->frame=env->frame;
    return with_cleanup(scope,[&]{return execute_body(scope);});
}
V functionInvocationExpression::execute(E env) {
    tick(env);std::vector<V> args;
    // Snapshot each argument before evaluating the next one (left-to-right).
    for(auto& p:params) args.push_back(p->execute(env)->deepCopy());
    auto result=owner(env)->getFunction(function_id)->invoke(env,std::move(args));
    if(!result) throw std::runtime_error("Host function returned a null pointer");
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
    auto value=initializer?initializer->execute(env)->deepCopy():nil();
    env->declared_slots.push_back(static_cast<std::size_t>(varindex));
    slot=std::move(value);return nil();
}
V varSetExpression::execute(E env) {
    tick(env);auto v=env->getVariable(varindex);
    v->copy_from(expression->execute(env));return v->deepCopy();
}
V returnExpression::execute(E env) {
    tick(env);if(!env->frame) throw std::runtime_error("Return outside function");
    auto result=returnType?returnType->execute(env)->deepCopy():nil();
    if(object_return) {
        if(result->type!=ADDRESS_VALUE)throw std::runtime_error("Object return requires address type");
        heap::return_object(*static_cast<address*>(result->value),env);
    }
    env->frame->result=std::move(result);env->frame->returned=true;return nil();
}
constantExpression::constantExpression(std::shared_ptr<AbdMapValue> v) {
    if(auto p=std::dynamic_pointer_cast<IntAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<AddressAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<StringAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<FloatAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<DoubleAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<BoolAbdValue>(v)) value=std::make_shared<variable>(p->data);
    else if(auto p=std::dynamic_pointer_cast<ByteArrayValue>(v);p&&p->length==1&&p->data[0]==0xff) value=nil();
    else throw std::invalid_argument("Unsupported script constant");
}
V constantExpression::execute(E env){tick(env);return value->deepCopy();}
#define BINARY_IMPL(Class,Op) \
V Class::execute(E env){tick(env);auto a=exp1->execute(env)->deepCopy();auto b=exp2->execute(env);return binary(Op,a,b);}
BINARY_IMPL(addExpression,"add")
BINARY_IMPL(minusExpression,"minus")
BINARY_IMPL(multiplyExpression,"multiply")
BINARY_IMPL(divideExpression,"divide")
BINARY_IMPL(equalExpression,"eq")
BINARY_IMPL(gtExpression,"gt")
BINARY_IMPL(ltExpression,"lt")
#undef BINARY_IMPL
V movExpression::execute(E env){tick(env);auto a=exp1->execute(env);auto b=exp2->execute(env);a->copy_from(b);return a->deepCopy();}
V ifExpression::execute(E env){tick(env);if(truth(exp1->execute(env))) return scoped_execute(exp2,env);return otherwise?scoped_execute(otherwise,env):nil();}
V whileExpression::execute(E env) {
    tick(env);
    if(!env->frame) throw std::runtime_error("While outside function");
    auto frame=env->frame;
    ++frame->loop_depth;
    try {
        while(!frame->returned&&truth(exp1->execute(env))) {
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
void variable::clear() noexcept {
    switch(type) {
    case INT_VALUE:delete static_cast<int*>(value);break;
    case ADDRESS_VALUE:delete static_cast<address*>(value);break;
    case STRING_VALUE:delete static_cast<std::string*>(value);break;
    case FLOAT_VALUE:delete static_cast<float*>(value);break;
    case DOUBLE_VALUE:delete static_cast<double*>(value);break;
    case BOOLEAN_VALUE:delete static_cast<bool*>(value);break;
    }
    value=nullptr;type=VOID_VALUE;
}
variable::~variable(){clear();}
#define VALUE_IMPL(T,Tag) \
variable::variable(T v){setValue(std::move(v));} \
void variable::setValue(T v){auto p=std::make_unique<T>(std::move(v));clear();value=p.release();type=Tag;}
VALUE_IMPL(int,INT_VALUE)
VALUE_IMPL(address,ADDRESS_VALUE)
VALUE_IMPL(std::string,STRING_VALUE)
VALUE_IMPL(float,FLOAT_VALUE)
VALUE_IMPL(double,DOUBLE_VALUE)
VALUE_IMPL(bool,BOOLEAN_VALUE)
#undef VALUE_IMPL
variable::variable(const char* value){setValue(value);}
void variable::setValue(const char* value){if(value)setValue(std::string(value));else setValue(nullptr);}
variable::variable(std::nullptr_t){}
void variable::setValue(std::nullptr_t){clear();}
void variable::copy_value(const variable& v) {
    switch(v.type) {
    case INT_VALUE:setValue(*static_cast<int*>(v.value));break;
    case ADDRESS_VALUE:setValue(*static_cast<address*>(v.value));break;
    case STRING_VALUE:setValue(*static_cast<std::string*>(v.value));break;
    case FLOAT_VALUE:setValue(*static_cast<float*>(v.value));break;
    case DOUBLE_VALUE:setValue(*static_cast<double*>(v.value));break;
    case BOOLEAN_VALUE:setValue(*static_cast<bool*>(v.value));break;
    case VOID_VALUE:setValue(nullptr);break;
    default:throw std::runtime_error("Unknown runtime value type");
    }
}
variable::variable(const variable& v):name(v.name){copy_value(v);}
variable& variable::operator=(const variable& v){if(this!=&v){copy_value(v);name=v.name;}return *this;}
void variable::copy_from(V v){if(!v)throw std::invalid_argument("Null variable");if(v.get()!=this)copy_value(*v);}
V variable::deepCopy(){auto v=std::make_shared<variable>(*this);v->name.clear();return v;}
environment::~environment() {heap::discard_owned(this);}
void environment::cleanup(const std::shared_ptr<environment>& self) {
    if(closing)return;
    closing=true;
    std::exception_ptr failure;
    while(!owned_pointer.empty()) {
        address pointer=owned_pointer.back();owned_pointer.pop_back();
        try {heap::release_owned(pointer,self);}catch(...){if(!failure)failure=std::current_exception();}
    }
    // Destructors may still observe the current block through host callbacks;
    // retire its locals only after every owned allocation has been cleaned.
    if(frame)for(auto index:declared_slots)frame->slots[index].reset();
    declared_slots.clear();
    if(failure)std::rethrow_exception(failure);
}
V environment::getVariable(std::string name) {
    for(auto& v:variables) if(v->name==name)return v;
    if(auto p=parent.lock())return p->getVariable(std::move(name));
    return nullptr;
}
V environment::getVariable(int index) {
    if(index<0) {
        const auto relative=static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1);
        auto owner=script.lock();
        if(!owner||owner->closed||!owner->baseEnv)throw std::runtime_error("Script is closed");
        // The base environment is also the host's access point. Its negative
        // ids address the complete global array; an executing frame is limited
        // to the module containing the callee, with that module's relocation.
        const auto count=frame?frame->global_count:owner->baseEnv->variables.size();
        const auto offset=frame?frame->global_offset:0;
        if(relative>=count||offset>owner->baseEnv->variables.size()||
           relative>=owner->baseEnv->variables.size()-offset)
            throw std::runtime_error("Global variable slot is outside its module: "+std::to_string(index));
        return owner->baseEnv->variables[offset+static_cast<std::size_t>(relative)];
    }
    if(!frame||static_cast<std::size_t>(index)>=frame->slots.size())
        throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
    auto value=frame->slots[static_cast<std::size_t>(index)];
    if(!value)throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
    return value;
}
bool environment::testForVar(std::string name){for(auto& v:variables)if(v->name==name)return true;return false;}
namespace {
// Shortest representation that parses back to the same value, e.g. 0.1+0.2
// prints 0.30000000000000004 and 123456789.0 prints 123456789.
template<class T> std::string shortest_number(T value) {
    char text[64];
    auto result=std::to_chars(text,text+sizeof text,value);
    if(result.ec!=std::errc()) throw std::runtime_error("Cannot format number");
    return std::string(text,result.ptr);
}
}
std::string value_to_string(const V& v) {
    if(!v)throw std::invalid_argument("Null variable");
    if(v->type==STRING_VALUE)return *static_cast<std::string*>(v->value);
    if(v->type==BOOLEAN_VALUE)return *static_cast<bool*>(v->value)?"true":"false";
    if(v->type==VOID_VALUE)return "null";
    if(v->type==INT_VALUE)return shortest_number(*static_cast<int*>(v->value));
    if(v->type==ADDRESS_VALUE)return shortest_number(static_cast<address*>(v->value)->value);
    if(v->type==FLOAT_VALUE)return shortest_number(*static_cast<float*>(v->value));
    if(v->type==DOUBLE_VALUE)return shortest_number(*static_cast<double*>(v->value));
    throw std::runtime_error("Unknown runtime value type");
}
}
