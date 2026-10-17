#ifndef AZSCRIPT_VM_HOST_BRIDGE_H
#define AZSCRIPT_VM_HOST_BRIDGE_H

#include "execution.h"

namespace azertian {class script;}
namespace azertian::vm {
class HostBoundary {
public:
    virtual ~HostBoundary()=default;
    virtual WorkspaceBudget& workspace() noexcept=0;
    virtual void publish_globals(script& owner) noexcept=0;
    virtual void refresh_globals(const script& owner)=0;
    // Around runtime helpers that may run script code, publication is deferred
    // until a nested invocation actually starts; refresh only follows one.
    virtual std::uint64_t defer_publication() noexcept=0;
    virtual void end_deferred_publication(const script& owner,std::uint64_t mark)=0;
};

// Migration bridge: callback arguments, globals and same-thread reentry use the
// existing checked host function boundary. Object frames call externals and
// heap builtins in their real scope; reflection and plugin loading are refused.
class HostBridge final:public ExecutionCallbacks,private ObjectHost {
    script& owner_;
    const ExecutableProgram& program_;
    HostBoundary& boundary_;
    bool reported_=false;
public:
    HostBridge(script& owner,const ExecutableProgram& program,HostBoundary& boundary)
        :owner_(owner),program_(program),boundary_(boundary) {}
    Value invoke_external(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller,std::size_t lexical_depth) override;
    std::shared_ptr<variable> invoke_external_in(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller,const std::shared_ptr<environment>& scope) override;
    void note_script_error(const ExecutionPosition& position) noexcept override;
    ObjectHost* objects() noexcept override{return this;}
    bool reported_error() const noexcept{return reported_;}
private:
    std::shared_ptr<script> owner() override;
    std::uint64_t enter_runtime() noexcept override{return boundary_.defer_publication();}
    void leave_runtime(std::uint64_t mark) override{boundary_.end_deferred_publication(owner_,mark);}
};
void require_scalar_host_targets(const ExecutableProgram& program);
}
#endif
