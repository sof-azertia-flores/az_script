#include "internelFunctions.h"
#include "extern_library.h"
#include "heepalloc.h"
#include "blocks.h"
#include <cstdint>
#include <cstdio>
#include <iostream>
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
        if(args.size()!=1||!args[0]||args[0]->type!=STRING_VALUE)throw std::invalid_argument("load_extern_library requires one string");
        load_named_extern_library(*static_cast<std::string*>(args[0]->value));
        return std::make_shared<variable>(nullptr);
    }
};
class reflect_invoke_function final:public function {
public:
    int return_type()override{return ANY_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args)override {
        if(args.size()<2||!args[0]||!args[1]||args[0]->type!=INT_VALUE||args[1]->type!=INT_VALUE)
            throw std::invalid_argument("reflect_invoke_function requires a return type and integer function ID");
        const int expected=*static_cast<int*>(args[0]->value),id=*static_cast<int*>(args[1]->value);
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
        if(!result||result->type!=expected)throw std::runtime_error("Reflection return type mismatch");
        return result;
    }
};
class hint_query_function final:public function {
    bool presence;
public:
    explicit hint_query_function(bool presence):presence(presence){}
    int return_type()override{return presence?BOOLEAN_VALUE:INT_VALUE;}
    std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args)override {
        if(args.size()!=1||!args[0]||args[0]->type!=STRING_VALUE)throw std::invalid_argument("Hint query requires one string");
        auto script=env?env->script.lock():nullptr;
        if(!script)throw std::runtime_error("Missing execution environment");
        const auto& hint=*static_cast<std::string*>(args[0]->value);
        if(presence)return std::make_shared<variable>(script->hint_loaded(hint));
        return std::make_shared<variable>(script->namespace_for_hint(hint));
    }
};
std::map<int,std::shared_ptr<executor>>& registry(){static std::map<int,std::shared_ptr<executor>> r;return r;}
std::uint64_t& generation(){static std::uint64_t value=0;return value;}
std::shared_ptr<function> builtin(int id) {
    static const std::vector<std::shared_ptr<function>> functions{
        std::make_shared<print_function>(),std::make_shared<depth_function>(),std::make_shared<f_free>(),
        std::make_shared<f_heepalloc>(),std::make_shared<f_make_free>(),std::make_shared<f_send_up>(),std::make_shared<f_get>(),
        std::make_shared<load_extern_library_function>(),std::make_shared<reflect_invoke_function>(),
        std::make_shared<hint_query_function>(false),std::make_shared<hint_query_function>(true)};
    int i=id&0xffff;
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
