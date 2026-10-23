// Backend selection, linking and initialization of the mounted modules.
#include "script_internal.h"
#include "vm/session.h"
#include "core/link.h"
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
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
        vm_candidate=std::make_shared<vm::Session>(*this,vm_session_->workspace_limit(),vm_session_->tier());
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
                const auto found=compiled.functions.find(function->function_id);
                if(found==compiled.functions.end())throw std::logic_error("VM public function binding is unavailable");
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
}
