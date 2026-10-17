#include "internelFunctions.h"
#include "core/ids.h"
#include "extern_library.h"
#include "heepalloc.h"
#include "blocks.h"
#include "utf8_internal.h"
#include <cstdint>
#include <cstdio>
#include <iostream>
#include <limits>
#include <stdexcept>
namespace azertian {
namespace {
class print_function final:public function {
public:
    int return_type()override{return VOID_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment>,std::vector<std::shared_ptr<variable>> args={})override {
        if(args.size()!=1)throw std::invalid_argument("print requires one argument");
        std::cout<<value_to_string(args[0])<<'\n';return std::make_shared<variable>(nullptr);
    }
};
class depth_function final:public function {
public:
    int return_type()override{return INT_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args)override {
        if(!args.empty())throw std::invalid_argument("getDepth takes no arguments");
        int depth=0;for(auto e=env;e;e=e->parent.lock())++depth;
        return std::make_shared<variable>(depth);
    }
};
class load_extern_library_function final:public function {
public:
    int return_type()override{return VOID_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment>,std::vector<std::shared_ptr<variable>> args={})override {
        if(args.size()!=1||!args[0]||args[0]->type_tag()!=STRING_VALUE)throw std::invalid_argument("load_extern_library requires one string");
        load_named_extern_library(args[0]->get<std::string>());
        return std::make_shared<variable>(nullptr);
    }
};
class reflect_invoke_function final:public function {
public:
    int return_type()override{return ANY_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args)override {
        if(args.size()<2||!args[0]||!args[1]||args[0]->type_tag()!=INT_VALUE||args[1]->type_tag()!=INT_VALUE)
            throw std::invalid_argument("reflect_invoke_function requires a return type and integer function ID");
        const int expected=args[0]->get<int>(),id=args[1]->get<int>();
        if(expected<INT_VALUE||expected>OBJECT_VALUE||expected==ANY_VALUE)throw std::invalid_argument("Invalid reflection return type");
        auto script=env?env->script.lock():nullptr;
        if(!script)throw std::runtime_error("Missing execution environment");
        auto target=script->getFunction(id);
        if(auto fn=std::dynamic_pointer_cast<ofunction>(target);fn&&(fn->entry_kind!=0||fn->hidden_count!=0))
            throw std::runtime_error("Reflection requires an ordinary non-generic function; use a wrapper");
        if(auto declaration=script->extern_signatures.find(id);declaration!=script->extern_signatures.end()&&
            (declaration->second.entry_kind!=0||declaration->second.hidden_count!=0))
            throw std::runtime_error("Reflection requires an ordinary non-generic function; use a wrapper");
        args.erase(args.begin(),args.begin()+2);
        auto result=target->invoke(env,std::move(args));
        if(result)blocks::adopt_result(result,env);
        if(!result||result->type_tag()!=expected)throw std::runtime_error("Reflection return type mismatch");
        return result;
    }
};
class hint_query_function final:public function {
    bool presence;
public:
    explicit hint_query_function(bool presence):presence(presence){}
    int return_type()override{return presence?BOOLEAN_VALUE:INT_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args)override {
        if(args.size()!=1||!args[0]||args[0]->type_tag()!=STRING_VALUE)throw std::invalid_argument("Hint query requires one string");
        auto script=env?env->script.lock():nullptr;
        if(!script)throw std::runtime_error("Missing execution environment");
        const auto& hint=args[0]->get<std::string>();
        if(presence)return std::make_shared<variable>(script->hint_loaded(hint));
        return std::make_shared<variable>(script->namespace_for_hint(hint));
    }
};
class string_function final:public function {
    int operation;
    static constexpr const char* names[]{"str_length","str_byte_length","str_substring","str_char_at","str_code_at",
        "str_from_code","str_index_of","str_starts_with","str_ends_with"};
public:
    explicit string_function(int operation):operation(operation) {}
    int return_type() override {
        if(operation==0x0d||operation==0x0e||operation==0x10)return STRING_VALUE;
        if(operation==0x12||operation==0x13)return BOOLEAN_VALUE;
        return INT_VALUE;
    }
    std::shared_ptr<variable> invoke(std::shared_ptr<environment>,std::vector<std::shared_ptr<variable>> args) override {
        const std::string name=names[operation-0x0b];
        const std::size_t arity=operation==0x0d?3:(operation==0x0b||operation==0x0c||operation==0x10)?1:2;
        if(operation==0x11) {
            if(args.size()!=2&&args.size()!=3)throw std::invalid_argument(name+" requires 2 or 3 arguments");
        } else if(args.size()!=arity)
            throw std::invalid_argument(name+" requires "+std::to_string(arity)+(arity==1?" argument":" arguments"));
        auto argument=[&](std::size_t index,int type) -> const std::shared_ptr<variable>& {
            if(!args[index]||args[index]->type_tag()!=type)
                throw std::invalid_argument(name+" argument "+std::to_string(index+1)+" must be "+(type==INT_VALUE?"int":"string"));
            return args[index];
        };
        auto integer=[&](std::size_t index){return argument(index,INT_VALUE)->get<int>();};
        auto string=[&](std::size_t index) -> const std::string& {return argument(index,STRING_VALUE)->get<std::string>();};
        auto as_int=[](std::size_t size) {
            if(size>static_cast<std::size_t>(std::numeric_limits<int>::max()))throw std::overflow_error("String length exceeds int range");
            return static_cast<int>(size);
        };
        if(operation==0x10)return std::make_shared<variable>(utf8_internal::encode(integer(0)));
        const utf8_internal::view text(string(0));
        auto index=[&](int value,bool allow_end) {
            if(value<0||static_cast<std::size_t>(value)>text.length()||(!allow_end&&static_cast<std::size_t>(value)==text.length()))
                throw std::out_of_range("String index "+std::to_string(value)+" is outside length "+std::to_string(text.length()));
            return static_cast<std::size_t>(value);
        };
        if(operation==0x0b)return std::make_shared<variable>(as_int(text.length()));
        if(operation==0x0c)return std::make_shared<variable>(as_int(text.bytes().size()));
        if(operation==0x0d) {
            const int begin=integer(1),end=integer(2);const auto first=index(begin,true),last=index(end,true);
            if(first>last)throw std::out_of_range("String substring start "+std::to_string(begin)+" is after end "+std::to_string(end));
            const auto offset=text.byte_offset(first),finish=text.byte_offset(last);
            return std::make_shared<variable>(std::string(text.bytes().substr(offset,finish-offset)));
        }
        if(operation==0x0e||operation==0x0f) {
            const auto offset=text.byte_offset(index(integer(1),false));
            const auto scalar=utf8_internal::decode(text.bytes(),offset);
            if(operation==0x0f)return std::make_shared<variable>(static_cast<int>(scalar.code));
            return std::make_shared<variable>(std::string(text.bytes().substr(offset,scalar.width)));
        }
        const utf8_internal::view needle(string(1));
        if(operation==0x12)return std::make_shared<variable>(text.bytes().starts_with(needle.bytes()));
        if(operation==0x13)return std::make_shared<variable>(text.bytes().ends_with(needle.bytes()));
        const auto from=index(args.size()==3?integer(2):0,true);
        const auto found=text.bytes().find(needle.bytes(),text.byte_offset(from));
        if(found==std::string_view::npos)return std::make_shared<variable>(-1);
        // A valid UTF-8 needle never starts with a continuation byte, so every
        // byte match starts at a code-point boundary, including matches of NUL.
        return std::make_shared<variable>(as_int(utf8_internal::count(text.bytes().substr(0,found))));
    }
};
std::map<int,std::shared_ptr<executor>>& registry(){static std::map<int,std::shared_ptr<executor>> r;return r;}
std::uint64_t& generation(){static std::uint64_t value=0;return value;}
std::shared_ptr<function> builtin(int id) {
    static const std::vector<std::shared_ptr<function>> functions{
        std::make_shared<print_function>(),std::make_shared<depth_function>(),std::make_shared<f_free>(),
        std::make_shared<f_heepalloc>(),std::make_shared<f_make_free>(),std::make_shared<f_send_up>(),std::make_shared<f_get>(),
        std::make_shared<load_extern_library_function>(),std::make_shared<reflect_invoke_function>(),
        std::make_shared<hint_query_function>(false),std::make_shared<hint_query_function>(true),
        std::make_shared<string_function>(0x0b),std::make_shared<string_function>(0x0c),std::make_shared<string_function>(0x0d),
        std::make_shared<string_function>(0x0e),std::make_shared<string_function>(0x0f),std::make_shared<string_function>(0x10),
        std::make_shared<string_function>(0x11),std::make_shared<string_function>(0x12),std::make_shared<string_function>(0x13),
        std::make_shared<f_object_alloc>()};
    // The table is indexed by ids::builtin order; both engines classify by those ids.
    if(functions.size()!=static_cast<std::size_t>(ids::builtin_count))throw std::logic_error("Builtin table does not match the builtin ids");
    int i=ids::local_of(id);
    return i<static_cast<int>(functions.size())?functions[i]:nullptr;
}
}
bool isInternelFunction(int id){return static_cast<bool>(getiFunction(id));}
std::shared_ptr<function> getiFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto ns=static_cast<unsigned int>(id)>>16;
    if(ns==0xabd)return builtin(id);
    auto it=registry().find(static_cast<int>(ns));
    return it==registry().end()?nullptr:it->second->getiFunction(id);
}
void registerExecutor(std::shared_ptr<executor> e) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!e)throw std::invalid_argument("Null host executor");
    int ns=e->namespace_name();
    // 0xabd holds the builtins, 0 holds the lifecycle functions and 0xfff is the
    // default script namespace; ids there are never resolved by a host.
    if(ns<0||ns>0xffff||ns==0xabd||ns==0||ns==0xfff) {
        char text[16];std::snprintf(text,sizeof text,"0x%x",ns);
        throw std::invalid_argument(std::string("Invalid or reserved host namespace: ")+text);
    }
    if(script_namespace_in_use(ns))throw std::invalid_argument("Host namespace is mounted by an active script");
    registry()[ns]=std::move(e);
    ++generation();
}
void unregisterExecutor(int ns){std::lock_guard<std::recursive_mutex> lock(runtime_mutex());if(registry().erase(ns))++generation();}
std::set<int> registered_executor_namespaces() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    std::set<int> result;
    for(auto& [ns,value]:registry()){(void)value;result.insert(ns);}
    return result;
}
std::uint64_t executor_registry_generation(){std::lock_guard<std::recursive_mutex> lock(runtime_mutex());return generation();}
}
