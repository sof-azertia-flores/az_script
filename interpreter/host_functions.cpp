// Function lookup and the checked boundary around host functions.
#include "script_internal.h"
#include "internelFunctions.h"
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
namespace {
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
        entry_scope entry;return entry.run([&]() -> V {
        try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        auto script=script_owner.lock();
        if(!script||!env||env->script.lock()!=script)throw std::runtime_error("Function belongs to another or unavailable script");
        script->require_execution();
        if(typed&&signature.hidden_count)throw std::runtime_error("Generic external function requires a script implementation and bound contexts");
        call_guard guard(env);
        if(typed)admission::check_arguments("External function",id,signature.param_types,args.size(),
            [&](std::size_t i){return argument_tag(args[i]);},signature.hidden_count>0);
        auto result=target->invoke(std::move(env),std::move(args));
        // Returning normally means any nested failure was handled by the
        // callback. It cannot become part of a later signature error.
        trace::discard_active();
        if(!result)
            throw std::runtime_error("External function "+function_id_text(id)+" returned a null pointer");
        const int got=static_cast<int>(result->type_tag());
        if(typed&&signature.return_type!=ANY_VALUE&&got!=signature.return_type)
            throw std::runtime_error("External function "+function_id_text(id)+
                " return type mismatch: expected "+type_name(signature.return_type)+", got "+type_name(got));
        return result;
        } catch(...) {
            // A callback may handle a script error and throw a fresh host
            // exception. Preserve nested frames only for the propagated one.
            if(!trace::current_is_published())trace::discard_active();
            if((static_cast<std::uint32_t>(id)>>16)!=0x0abdu) {
                trace_frame frame;frame.function_id=id;frame.native=true;trace::note_frame(std::move(frame));
            }
            throw;
        }
        });
    }
};
}
std::shared_ptr<function> script::getFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());require_execution();
    if(auto found=functions.find(id);found!=functions.end())return found->second;
    const int ns=namespace_of(id);
    if(ns!=0x0abd&&script_namespaces.contains(ns))
        throw std::runtime_error("Unknown function id: "+function_id_text(id)+" (script namespace)");
    if(auto target=getiFunction(id)) {
        if(auto signature=extern_signatures.find(id);signature!=extern_signatures.end())
            return std::make_shared<checked_external_function>(std::move(target),signature->second,id,weak_from_this());
        return std::make_shared<checked_external_function>(std::move(target),function_signature{},id,weak_from_this(),false);
    }
    throw std::runtime_error("Unknown function id: "+function_id_text(id));
}
bool script::hasFunction(int id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return false;
    if(auto found=functions.find(id);found!=functions.end()) {
        auto fn=std::dynamic_pointer_cast<ofunction>(found->second);
        return !fn||fn->entry_kind!=2;
    }
    const int ns=namespace_of(id);
    return (ns==0x0abd||!script_namespaces.contains(ns))&&isInternelFunction(id);
}
}
