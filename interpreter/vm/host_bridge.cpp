#include "host_bridge.h"
#include "session.h"
#include "storage.h"
#include "../core/workspace.h"
#include "../library.h"
#include "../core/trace.h"
#include "../core/ids.h"
#include <stdexcept>

namespace azertian::vm {
void require_scalar_host_targets(const ExecutableProgram& program) {
    for(const auto& target:program.targets) {
        if(target.kind!=TargetKind::script&&(target.hidden_count||target.entry_kind))
            throw std::invalid_argument("Scalar VM does not support generic or object host call contexts");
        if(target.kind==TargetKind::script)continue;
    }
}
std::shared_ptr<script> HostBridge::owner() {return owner_.shared_from_this();}
Value HostBridge::invoke_external(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller) {
    // Heap builtins act on scope ownership; their callers are object frames.
    if(ids::is_heap_builtin(target.function_id))throw std::logic_error("VM heap builtin called from a scalar frame");
    if(caller.function>=program_.functions.size())
        throw std::logic_error("VM host boundary has an invalid caller");
    boundary_.publish_globals(owner_);
    // Host wrappers are boundary objects only. No variable or environment is
    // created by the VM's ordinary arithmetic or script-to-script CALL path.
    std::vector<std::shared_ptr<variable>> host_arguments;
    host_arguments.reserve(arguments.size());
    for(const auto& value:arguments) {
        auto argument=std::make_shared<variable>();value_access::assign(*argument,value);
        host_arguments.push_back(std::move(argument));
    }
    const auto& function=program_.functions[caller.function];
    auto frame=std::make_shared<function_frame>(boundary_.ownership_arena());
    frame->global_offset=function.global_offset;frame->global_count=function.global_count;
    frame->location=caller.location;frame->return_location=caller.return_location;
    // Scope structure is unobservable; the host sees one frame root, which
    // keeps the caller's module for global access.
    auto scope=std::make_shared<environment>(boundary_.ownership_arena(),owner_.baseEnv,ownership::ScopeId{},frame,true);
    scope->script=owner_.weak_from_this();
    std::shared_ptr<variable> result;
    pending_error failure;
    try {
        auto resolved=owner_.getFunction(target.function_id);
        if(std::dynamic_pointer_cast<ofunction>(resolved))
            throw std::logic_error("VM external target unexpectedly resolved to script code");
        result=resolved->invoke(scope,std::move(host_arguments));
    } catch(...) {failure.capture_current();}
    try {boundary_.refresh_globals(owner_);}catch(...) {failure.capture_current();}
    if(failure)failure.rethrow();
    if(!result)throw std::runtime_error("VM host callback returned a null value");
    if(result->type_tag()==OBJECT_VALUE)
        throw std::invalid_argument("Scalar VM does not support object callback results");
    return value_access::read(*result);
}
std::shared_ptr<variable> HostBridge::invoke_external_in(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller,const std::shared_ptr<environment>& scope) {
    if(caller.function>=program_.functions.size()||!scope)throw std::logic_error("VM host boundary has an invalid caller");
    boundary_.publish_globals(owner_);
    std::vector<std::shared_ptr<variable>> host_arguments;
    host_arguments.reserve(arguments.size());
    for(const auto& value:arguments) {
        auto argument=std::make_shared<variable>();value_access::assign(*argument,value);
        host_arguments.push_back(std::move(argument));
    }
    std::shared_ptr<variable> result;
    pending_error failure;
    try {
        auto resolved=owner_.getFunction(target.function_id);
        if(std::dynamic_pointer_cast<ofunction>(resolved))
            throw std::logic_error("VM external target unexpectedly resolved to script code");
        result=resolved->invoke(scope,std::move(host_arguments));
    } catch(...) {failure.capture_current();}
    try {boundary_.refresh_globals(owner_);}catch(...) {failure.capture_current();}
    if(failure)failure.rethrow();
    if(!result)throw std::runtime_error("Host function returned a null pointer");
    return result;
}
void HostBridge::note_script_error(const ExecutionPosition& position) noexcept {
    if(position.function<program_.functions.size()) {
        const auto& function=program_.functions[position.function];
        trace::note_script_frame(function.function_id,function.debug?&*function.debug:nullptr,position.location);
        reported_=true;
    }
}
}
