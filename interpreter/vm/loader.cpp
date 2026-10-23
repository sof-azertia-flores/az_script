#include "loader.h"
#include "../core/ids.h"
#include "lowering.h"
#include "../core/link.h"
#include "../library.h"
#include <limits>
#include <mutex>
#include <stdexcept>

namespace azertian::vm {
CompiledScript compile_script(const azertian::script& script) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(script.closed)throw std::runtime_error("Script is closed");
    const auto manifests=script.module_manifest();
    // The same plan the tree applies: resolved references, merged external
    // signatures. The VM performs no relocation of its own.
    const auto plan=plan_links(script);
    if(plan.references.size()!=manifests.size())throw std::logic_error("VM module binding differs from validated linkage");
    SourceProgram source;
    CompiledScript compiled;
    compiled.modules.resize(manifests.size());
    for(std::size_t index=0;index<manifests.size();++index) {
        const auto& manifest=manifests[index];
        if(manifest.global_offset>std::numeric_limits<std::uint32_t>::max())
            throw std::length_error("VM module global offset exceeds its index space");
        const auto module=exec::read_module(manifest.bytes.data(),manifest.bytes.size(),manifest.global_offset);
        if(module.global_count!=manifest.global_count||module.hint!=manifest.hint)
            throw std::logic_error("VM module manifest differs from validated source");
        const auto& resolved=plan.references[index];std::size_t next=0;
        for(const auto& definition:module.functions) {
            if(source.functions.size()>=no_index)
                throw std::length_error("VM function table exceeds its index space");
            const auto position=static_cast<std::uint32_t>(source.functions.size());
            SourceFunction function;
            function.function_id=definition.function_id;function.return_type=definition.return_type;
            function.entry_kind=definition.entry_kind;function.lifecycle=definition.function_id==0||definition.function_id==1;
            function.param_types=definition.param_types;
            function.local_count=definition.local_count;function.hidden_count=definition.hidden_count;
            function.global_offset=static_cast<std::uint32_t>(manifest.global_offset);
            function.global_count=static_cast<std::uint32_t>(module.global_count);
            if(definition.debug)function.debug=*definition.debug;
            if(function.lifecycle) {
                if(function.function_id==0)compiled.modules[index].onload=position;
                else compiled.modules[index].pre_destroy=position;
            } else {
                if(manifest.actual_namespace)function.function_id=ids::relocate(function.function_id,*manifest.actual_namespace);
                if(!compiled.functions.emplace(function.function_id,position).second)
                    throw std::logic_error("VM public function differs from validated linkage");
                if(function.entry_kind!=2)compiled.public_functions.emplace(function.function_id,position);
            }
            function.body=exec::relocate(definition.body,[&](const exec::reference&) {
                if(next>=resolved.size())throw std::logic_error("VM references differ from validated linkage");
                return resolved[next++];
            });
            source.functions.push_back(std::move(function));
        }
        if(next!=resolved.size())throw std::logic_error("VM references differ from validated linkage");
    }
    for(const auto& [id,signature]:plan.bindings) {
        if(compiled.public_functions.contains(id))continue;
        CallTarget target;
        target.function_id=id;target.kind=TargetKind::host;
        target.return_type=signature.return_type;target.param_types=signature.param_types;
        target.hidden_count=static_cast<std::uint32_t>(signature.hidden_count);target.entry_kind=signature.entry_kind;
        source.external_targets.push_back(std::move(target));
    }
    // Calls without a declaration remain deferred targets. Their arguments and
    // contexts must run before the runtime resolves a potentially absent host.
    compiled.program=lower_program(source);
    return compiled;
}
}
