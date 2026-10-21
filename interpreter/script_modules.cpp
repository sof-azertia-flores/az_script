// Mounting a module: namespaces, function IDs and globals of an inserted module.
#include "script_internal.h"
#include "internelFunctions.h"
#include <algorithm>
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
namespace {
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
}
