#include "internal.h"
#include "../vm/session.h"
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
V invoke_bound_function(const std::shared_ptr<script>& script,int id,const E& env,std::vector<V> args,type_contexts contexts) {
    entry_scope entry;return entry.run([&]() -> V {
    auto target=script->getFunction(id);
    if(auto fn=std::dynamic_pointer_cast<ofunction>(target))return fn->invoke_bound(env,std::move(args),std::move(contexts));
    if(!contexts.empty())throw std::runtime_error("Generic contexts require a script function implementation");
    return target->invoke(env,std::move(args));
    });
}
V invoke_bound_function(const std::shared_ptr<script>& script,int id,ownership::Site caller,std::vector<V> args,type_contexts contexts) {
    entry_scope entry;return entry.run([&]() -> V {
    auto fn=std::dynamic_pointer_cast<ofunction>(script->getFunction(id));
    // Destructors are validated as script functions when they are bound.
    if(!fn)throw std::runtime_error("Object destructor must be a script function void(address)");
    return fn->invoke_bound(caller,std::move(args),std::move(contexts));
    });
}
int ofunction::return_type(){return rett;}
V ofunction::invoke(E ev,std::vector<V> args) {
    entry_scope entry;return entry.run([&]() -> V {
    if(hidden_count)throw std::runtime_error("Generic function requires a non-generic wrapper for public invocation");
    return invoke_bound(std::move(ev),std::move(args),{});
    });
}
V ofunction::invoke_bound(E ev,std::vector<V> args,type_contexts contexts) {
    return invoke_bound(site_of(ev),std::move(args),std::move(contexts));
}
V ofunction::invoke_bound(ownership::Site caller,std::vector<V> args,type_contexts contexts) {
    entry_scope entry;return entry.run([&]() -> V {
    std::shared_ptr<function_frame> frame;
    bool vm_trace=false;
    try {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto bound=script_owner.lock();
    if(!bound||!caller.record||caller.record->script.lock()!=bound)throw std::runtime_error("Function belongs to another or unavailable script");
    bound->require_execution();
    if(!linked)throw std::runtime_error("Function module has not been linked");
    if(contexts.size()!=static_cast<std::size_t>(hidden_count))throw std::runtime_error("Generic context count mismatch");
    validate_type_contexts(contexts,bound);
    call_guard guard(bound);
    admission::check_arguments("Script function",function_id,param_types,args.size(),
        [&](std::size_t i){return argument_tag(args[i]);},true);
    if(bound->vm_session_) {
        if(vm_function_index_==vm::no_index)throw std::logic_error("VM function has not been linked");
        vm::ExecutionPosition position;
        // Session owns every VM frame, including the entry frame and failures
        // while importing globals or reserving arguments. Do not append a
        // duplicate tree frame when it propagates the original exception.
        vm_trace=true;
        return bound->vm_session_->invoke(vm_function_index_,args,&position,caller,contexts);
    }
    const auto& arena=guard.s->baseEnv->ownership_arena();
    auto env=std::make_shared<environment>(arena,guard.s->baseEnv,caller.scope(),std::make_shared<function_frame>(arena),true);
    env->script=guard.s;
    frame=env->frame;
    env->frame->hidden_contexts=std::move(contexts);
    env->frame->global_offset=global_offset;env->frame->global_count=global_count;
    env->frame->param_count=args.size();
    env->frame->slots.resize(static_cast<std::size_t>(param_count)+static_cast<std::size_t>(local_count));
    for(std::size_t i=0;i<args.size();++i) {
        if(!args[i]) throw std::invalid_argument("Null argument pointer; use variable(nullptr)");
        // Object arguments move from caller temporaries or are copied; parameters
        // are destroyed after every local of the call.
        auto storage=parameter_storage();blocks::store(storage,args[i],caller);
        statement::bind_parameter(storage,i,env);
    }
    return with_cleanup(env,[&] {
        {
            at_location location(env,code->location);
            if(auto block=std::dynamic_pointer_cast<complex_expression>(code)) block->execute_body(env);
            else run(code,env);
        }
        env->frame->location=env->frame->return_location;
        return convert_return(env->frame->result,rett);
    });
    } catch(...) {if(!vm_trace)note_script_frame(*this,frame);throw;}
    });
}
}
