#include "library.h"
#include "tree/internal.h"
#include "blocks.h"
#include "core/statement.h"
#include "core/lifecycle.h"
#include "heepalloc.h"
#include "internelFunctions.h"
#include "vm/session.h"
#include "core/trace.h"
#include "core/admission.h"
#include "core/ids.h"
#include "core/exec.h"
#include "core/link.h"
#include "core/value_ops.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <limits>
#include <new>
#include <stdexcept>
#include <system_error>
#include <typeinfo>
#include <type_traits>
#include <utility>

namespace azertian {
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
using namespace tree;
using trace::entry_scope;
namespace {
// Runtime messages spell IDs like source and the CLI do: 0x plus the full
// 32-bit pattern, so negative and high-bit IDs read the same everywhere.
std::string hex_text(std::uint32_t value,int digits) {
    char text[16];std::snprintf(text,sizeof text,"0x%0*x",digits,static_cast<unsigned>(value));
    return text;
}
std::string namespace_text(int ns) { return hex_text(static_cast<std::uint32_t>(ns)&0xffffu,4); }
using ids::namespace_of;
int relocate_id(int id,int ns) noexcept {return ids::relocate(id,ns);}
using module_list=std::vector<std::shared_ptr<loaded_module>>;
// Ordinary modules keep their compiled IDs, so a collision names the earlier
// owner and how to separate the modules. Module ordinals count successful
// inserts and the primary script is #0. The owner is always ordinary: hint
// libraries move to a free namespace, and a fixed ID inside a mounted hint
// namespace is rejected as a namespace conflict first.
std::string duplicate_function_message(int id,const module_list& modules) {
    std::string message="Duplicate function id: "+function_id_text(id)+" (namespace "+namespace_text(namespace_of(id))+
        ", position "+namespace_text(id&0xffff)+") is already defined";
    for(std::size_t i=0;i<modules.size();++i) {
        const auto& definitions=modules[i]->definitions;
        if(std::any_of(definitions.begin(),definitions.end(),[&](const auto& entry){return entry.second->function_id==id;})) {
            message+=" by module #"+std::to_string(i)+" (ordinary)";break;
        }
    }
    if(id==0x0fff0000)return message+"; only one module may define main, so remove main from the inserted module";
    if(namespace_of(id)==0x0fff)
        return message+"; modules without #namespace share the default namespace 0x0fff, so give each module its own "
            "#namespace away from the low range hint libraries take, insert fixed-namespace modules before hint libraries, "
            "or use #namespace_hint";
    return message+"; give each module a distinct #namespace, or use #namespace_hint";
}
// Hint libraries take the lowest free namespace when inserted, so a later fixed
// module can find its namespace already mounted.
std::string mounted_hint_conflict(const char* what,int ns,const module_list& modules,std::size_t index,const char* remedy) {
    return std::string(what)+" "+namespace_text(ns)+" conflicts with hint "+modules[index]->manifest.hint+
        ", which module #"+std::to_string(index)+" mounted there; hint libraries take the lowest free namespace, so "+remedy;
}
std::vector<std::weak_ptr<script>>& live_scripts(){static std::vector<std::weak_ptr<script>> result;return result;}
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
std::recursive_mutex& runtime_mutex(){static std::recursive_mutex m;return m;}
bool script_namespace_in_use(int ns) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto& registry=live_scripts();
    registry.erase(std::remove_if(registry.begin(),registry.end(),[](auto& value){return value.expired();}),registry.end());
    for(auto& weak:registry)if(auto value=weak.lock();value&&!value->closed&&value->script_namespaces.contains(ns))return true;
    return false;
}
void validate_type_contexts(const type_contexts& contexts,const std::shared_ptr<script>& script) {
    std::size_t visited=0;
    const auto check=[&](auto&& self,const std::shared_ptr<const type_context>& context,std::size_t depth)->void {
        if(!context||depth>128||++visited>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Invalid or excessive generic contexts");
        if(!valid_context_kind(context->abi,context->kind)||context->width<1||static_cast<std::size_t>(context->width)>MAX_VARIABLE_SLOTS||(context->kind!=3&&context->width!=1))throw std::invalid_argument("Invalid generic type context");
        if((context->factory_id||!context->contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only object type contexts have default factories");
        if(!context->factory_id&&!context->contexts.empty())throw std::invalid_argument("Factory contexts require a factory");
        if(context->factory_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->factory_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=OBJECT_VALUE||!fn->param_types.empty()||fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->contexts.size())
                throw std::invalid_argument("Invalid generic default factory signature");
        }
        if((context->placement_id||!context->placement_contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only class contexts have placement initializers");
        if(!context->placement_id&&!context->placement_contexts.empty())throw std::invalid_argument("Placement contexts require an initializer");
        if(context->placement_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->placement_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE}||fn->entry_kind!=1||
               fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->placement_contexts.size())
                throw std::invalid_argument("Invalid generic placement initializer signature");
        }
        if((context->kind==4)!=static_cast<bool>(context->element))throw std::invalid_argument("Buffer context requires exactly one element context");
        if(context->element) {
            if(context->element->abi==VOID_VALUE)throw std::invalid_argument("Buffer element cannot be void");
            self(self,context->element,depth+1);
        }
        for(auto& child:context->contexts)self(self,child,depth+1);
        for(auto& child:context->placement_contexts)self(self,child,depth+1);
    };
    for(auto& context:contexts)check(check,context,1);
}
void validate_method_table(const method_table& methods,const std::shared_ptr<script>& script) {
    if(!script||script->closed)throw std::invalid_argument("Virtual method table belongs to an unavailable script");
    if(methods.size()>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Too many virtual method entries");
    for(const auto& method:methods) {
        auto found=script->functions.find(method.function_id);
        auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
        if(!fn||fn->entry_kind!=0||fn->param_types.empty()||fn->param_types.front()!=ADDRESS_VALUE||
           fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=method.contexts.size())
            throw std::invalid_argument("Invalid virtual method entry: "+function_id_text(method.function_id));
        validate_type_contexts(method.contexts,script);
    }
}
std::shared_ptr<script> load_script(const unsigned char* bytes,std::size_t length) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto result=std::make_shared<script>();
    result->baseEnv=std::make_shared<environment>();result->baseEnv->script=result;
    result->insert_script(bytes,length);
    live_scripts().push_back(result);
    return result;
    });
}
std::shared_ptr<script> load_script(unsigned char* bytes) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    if(!bytes)throw std::invalid_argument("Null script");
    const int length=b2i(bytes);if(length<0)throw std::invalid_argument("Negative script length");
    return load_script(bytes,static_cast<std::size_t>(length)+4);
    });
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
    if(functions.contains(id))return true;
    const int ns=namespace_of(id);
    return (ns==0x0abd||!script_namespaces.contains(ns))&&isInternelFunction(id);
}
V script::invoke(int id,std::vector<V> args) {
    entry_scope entry;return entry.run([&]() -> V {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(destroying)throw std::runtime_error("Script is being destroyed");
    require_execution();return getFunction(id)->invoke(baseEnv,std::move(args));
    });
}
int script::namespace_for_hint(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(module->manifest.hint==hint&&!hint.empty())return *module->manifest.actual_namespace;
    throw std::runtime_error("Unknown namespace hint: "+hint);
}
bool script::hint_loaded(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(!hint.empty()&&module->manifest.hint==hint)return true;
    return false;
}
std::vector<module_manifest_entry> script::module_manifest() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());std::vector<module_manifest_entry> result;
    for(auto& module:modules)result.push_back(module->manifest);
    return result;
}
void vm::select_backend(script& owner,std::size_t workspace_limit) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(owner.closed)throw std::runtime_error("Script is closed");
    if(owner.faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(owner.destroying||owner.initializing||owner.active_calls)
        throw std::runtime_error("Cannot select a VM backend during script execution");
    if(owner.setup||std::any_of(owner.modules.begin(),owner.modules.end(),
            [](const auto& module){return module->manifest.initialized;}))
        throw std::runtime_error("VM backend must be selected before the first flush");
    if(!workspace_limit)throw std::invalid_argument("VM workspace limit must be positive");
    owner.vm_session_=std::make_shared<Session>(owner,workspace_limit);
}
vm::WorkspaceUsage vm::workspace_usage(const script& owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!owner.vm_session_)return {};
    return {owner.vm_session_->workspace_bytes(),owner.vm_session_->workspace_peak_bytes(),owner.vm_session_->workspace_limit()};
}
void script::insert_script(const unsigned char* bytes,std::size_t length) {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot insert during script execution");
    auto parsed=build_module(exec::read_module(bytes,length,baseEnv->variables.size()));
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
        if(host_namespaces.contains(ns))throw std::invalid_argument("Script namespace "+namespace_text(ns)+" conflicts with registered host executor");
        if(!module->manifest.actual_namespace) {
            for(std::size_t i=0;i<modules.size();++i)if(modules[i]->manifest.actual_namespace==ns)
                throw std::invalid_argument(mounted_hint_conflict("Fixed namespace",ns,modules,i,
                    "insert fixed-namespace modules before hint libraries or choose another #namespace"));
            module->fixed_namespaces.insert(ns);
        }
        fn->function_id=actual_id;
        if(!candidate_functions.emplace(actual_id,fn).second)throw std::invalid_argument(duplicate_function_message(actual_id,modules));
        module->definitions.emplace(original_id,std::move(fn));candidate_namespaces.insert(ns);
    }
    if(module->manifest.actual_namespace)candidate_namespaces.insert(*module->manifest.actual_namespace);
    for(int ns:module->fixed_extern_namespaces)for(std::size_t i=0;i<modules.size();++i)
        if(modules[i]->manifest.actual_namespace==ns)
            throw std::invalid_argument(mounted_hint_conflict("Fixed external namespace",ns,modules,i,
                "insert this module before hint libraries or import script libraries through #assume_hint"));
    auto candidate_hosts=fixed_host_namespaces;candidate_hosts.insert(module->fixed_extern_namespaces.begin(),module->fixed_extern_namespaces.end());
    auto candidate_globals=baseEnv->variables;candidate_globals.reserve(candidate_globals.size()+parsed.global_count);
    for(std::size_t i=0;i<parsed.global_count;++i)candidate_globals.push_back(nil());
    auto candidate_modules=modules;candidate_modules.push_back(module);
    // Every allocation and validation above is complete before state is published.
    functions.swap(candidate_functions);script_namespaces.swap(candidate_namespaces);fixed_host_namespaces.swap(candidate_hosts);
    baseEnv->variables.swap(candidate_globals);modules.swap(candidate_modules);meta=module->meta;setup=false;
    });
}
// Link planning sees the mounted modules only through the neutral input.
link::plan plan_links(const script& owner) {
    link::input input;input.modules.reserve(owner.modules.size());
    for(const auto& module:owner.modules) {
        link::module item;
        item.assumptions=module->assumptions;item.hint=module->manifest.hint;
        item.mounted_namespace=module->manifest.actual_namespace;item.extern_signatures=module->extern_signatures;
        item.references.reserve(module->references.size());
        for(const auto& reference:module->references)
            item.references.push_back({reference.original_id,reference_kind(reference),static_cast<std::uint32_t>(reference.hidden_count)});
        for(const auto& [original,fn]:module->definitions){(void)original;item.definitions.push_back(fn->function_id);}
        item.dependencies=module->dependencies;
        input.modules.push_back(std::move(item));
    }
    for(const auto& [id,fn]:owner.functions) {
        const auto defined=std::dynamic_pointer_cast<ofunction>(fn);
        input.functions.emplace(id,defined?std::optional<function_signature>(function_signature{defined->rett,defined->param_types,
            defined->hidden_count,defined->entry_kind}):std::nullopt);
    }
    input.script_namespaces=owner.script_namespaces;
    return link::resolve(input);
}
void script::flush() {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(destroying||initializing||active_calls)throw std::runtime_error("Cannot flush during script execution");
    if(setup)return;
    // Migration acceptance only: run every host (CLI, C++ hosts, JNI) on the
    // experimental VM without changing them. Production keeps the tree default.
    if(!vm_session_&&std::none_of(modules.begin(),modules.end(),[](const auto& module){return module->manifest.initialized;}))
        if(const char* forced=std::getenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");forced&&std::string_view(forced)=="1")
            vm_session_=std::make_shared<vm::Session>(*this,vm::default_workspace_bytes);
    auto plan=plan_links(*this);
    on_destory.reserve(on_destory.size()+modules.size());
    std::vector<std::pair<ofunction*,std::uint32_t>> vm_bindings;
    std::shared_ptr<vm::Session> vm_candidate;
    if(vm_session_) {
        // Preparation is the last fallible validation phase. It reads immutable
        // manifests, so neither tree relocation nor onload can run partially.
        // Keep the prior session until all binding checks and allocations finish:
        // destroy() can still run initialized hooks after a failed insertion.
        vm_candidate=std::make_shared<vm::Session>(*this,vm_session_->workspace_limit());
        vm_candidate->prepare();
        const auto& compiled=vm_candidate->compiled();
        if(compiled.modules.size()!=modules.size())throw std::logic_error("VM module binding differs from validated linkage");
        const auto bind=[&](const std::shared_ptr<ofunction>& function,std::uint32_t index,bool lifecycle) {
            if(!function) {
                if(index!=vm::no_index)throw std::logic_error("VM lifecycle binding differs from validated linkage");
                return;
            }
            if(index>=compiled.program.functions.size())throw std::logic_error("VM function binding is unavailable");
            const auto& record=compiled.program.functions[index];
            if(record.function_id!=function->function_id||record.lifecycle!=lifecycle||
                    record.global_offset!=function->global_offset||record.global_count!=function->global_count||
                    record.return_type!=function->rett||record.param_types!=function->param_types||
                    record.hidden_count!=static_cast<std::uint32_t>(function->hidden_count)||record.entry_kind!=function->entry_kind)
                throw std::logic_error("VM function binding differs from validated linkage");
            vm_bindings.emplace_back(function.get(),index);
        };
        for(std::size_t i=0;i<modules.size();++i) {
            for(const auto& [original,function]:modules[i]->definitions) {
                (void)original;
                const auto found=compiled.public_functions.find(function->function_id);
                if(found==compiled.public_functions.end())throw std::logic_error("VM public function binding is unavailable");
                bind(function,found->second,false);
            }
            bind(modules[i]->onload,compiled.modules[i].onload,true);
            bind(modules[i]->pre_destroy,compiled.modules[i].pre_destroy,true);
        }
    }
    // Link validation is atomic: no references change and no onload runs before
    // every module has a valid resolution and the complete order is available.
    for(const auto& [function,index]:vm_bindings)function->vm_function_index_=index;
    if(vm_candidate)vm_session_.swap(vm_candidate);
    for(std::size_t i=0;i<modules.size();++i) {
        auto& references=modules[i]->references;
        for(std::size_t k=0;k<references.size();++k){*references[k].target=plan.references[i][k];references[k].original_id=plan.references[i][k];}
        modules[i]->extern_signatures.swap(plan.externs[i]);
        modules[i]->dependencies.swap(plan.dependencies[i]);
        modules[i]->assumptions.clear();
        for(auto& [original,fn]:modules[i]->definitions){(void)original;fn->linked=true;}
        if(modules[i]->onload)modules[i]->onload->linked=true;
        if(modules[i]->pre_destroy)modules[i]->pre_destroy->linked=true;
    }
    extern_signatures.swap(plan.bindings);vm_candidate.reset();initializing=true;
    try {
        call_guard guard(baseEnv);
        for(auto index:plan.order) {
            auto& module=*modules[index];if(module.manifest.initialized)continue;
            if(module.onload)module.onload->invoke(baseEnv,{});
            module.manifest.initialized=true;
            if(module.pre_destroy)on_destory.push_back(module.pre_destroy);
        }
        initializing=false;setup=true;
    } catch(...) {initializing=false;setup=false;faulted=true;throw;}
    });
}
void script::destroy() {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return;
    if(destroying)throw std::runtime_error("Script is being destroyed");
    if(active_calls||initializing)throw std::runtime_error("Cannot destroy a running script");
    destroying=true;pending_error failure;
    try {
        call_guard guard(baseEnv);
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {
            try{(*hook)->invoke(baseEnv,{});}catch(...){failure.capture_current();}
        }
        try{baseEnv->cleanup(baseEnv);}catch(...){failure.capture_current();}
        // Only hand-written exec can keep an object in a global; it still ends here.
        blocks::finalize_storage(baseEnv->variables,baseEnv,true,failure);
    }catch(...){failure.capture_current();}
    heap::discard_script_objects(this);
    vm_session_.reset();
    closed=true;setup=false;functions.clear();extern_signatures.clear();on_destory.clear();modules.clear();baseEnv.reset();destroying=false;
    if(failure)failure.rethrow();
    });
}
}
