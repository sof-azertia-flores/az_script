#include "internelFunctions.h"
#include "extern_library.h"
#include "heepalloc.h"
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
std::map<int,std::shared_ptr<executor>>& registry(){static std::map<int,std::shared_ptr<executor>> r;return r;}
std::uint64_t& generation(){static std::uint64_t value=0;return value;}
std::shared_ptr<function> builtin(int id) {
    static const std::vector<std::shared_ptr<function>> functions{
        std::make_shared<print_function>(),std::make_shared<depth_function>(),std::make_shared<f_free>(),
        std::make_shared<f_heepalloc>(),std::make_shared<f_make_free>(),std::make_shared<f_send_up>(),std::make_shared<f_get>(),
        std::make_shared<load_extern_library_function>()};
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
