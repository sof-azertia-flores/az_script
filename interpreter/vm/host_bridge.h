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
};

// Scalar migration bridge: callback arguments, globals and same-thread scalar
// reentry use the existing checked host function boundary. Object ownership,
// reflection and allocation builtins await their VM continuations.
class HostBridge final:public ExecutionCallbacks {
    script& owner_;
    const ExecutableProgram& program_;
    HostBoundary& boundary_;
    bool reported_=false;
public:
    HostBridge(script& owner,const ExecutableProgram& program,HostBoundary& boundary)
        :owner_(owner),program_(program),boundary_(boundary) {}
    Value invoke_external(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller,std::size_t lexical_depth) override;
    void note_script_error(const ExecutionPosition& position) noexcept override;
    bool reported_error() const noexcept{return reported_;}
};
void require_scalar_host_targets(const ExecutableProgram& program);
}
#endif
