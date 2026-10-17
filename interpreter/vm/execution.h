#ifndef AZSCRIPT_VM_EXECUTION_H
#define AZSCRIPT_VM_EXECUTION_H

#include "program.h"
#include "frames.h"
#include <memory>
#include <span>

namespace azertian::vm {
// Exceptions retain their original C++ type/message. The embedding adapter
// uses this last position to publish the ordinary script trace on failure.
struct ExecutionPosition {
    std::uint32_t function=no_index,node=0,location=0,return_location=0;
    ProgramCounter pc=no_index;
};

// The embedding adapter performs external lookup and the native/depth guard.
// Arguments are immutable snapshots in separate budgeted backing, valid even
// if the callback reenters this executor. lexical_depth is the complete old
// environment::parent-chain depth (function root=2), not the call-stack depth.
class ExecutionCallbacks {
public:
    virtual ~ExecutionCallbacks()=default;
    virtual Value invoke_external(const CallTarget& target,std::span<const Value> arguments,
        const ExecutionPosition& caller,std::size_t lexical_depth)=0;
    // Called once per failing script entry, innermost first, including entries
    // which fail admission/argument validation before acquiring a frame.
    virtual void note_script_error(const ExecutionPosition&) noexcept {}
};
struct ExecutionOptions {
    CallDepthAccounting* calls=nullptr;
    ExecutionCallbacks* callbacks=nullptr;
    // A public adapter that already owns a call admission sets this false.
    // Internal script CALL always acquires its own logical admission.
    bool admit_entry=true;
};

std::span<const Opcode> scalar_capabilities() noexcept;

// Migration-only prepared scalar backend. It rejects every unsupported path
// before execution; it never invokes the expression-tree backend. The program,
// slot pool and budget must outlive the executor. The program is immutable for
// that entire lifetime; callers serialize access to the pool and budget during
// invocations. External globals use the same pool and remain caller-owned.
// Reusable registers and alias cells are charged to the supplied workspace.
class ScalarExecutor {
    struct State;
    std::unique_ptr<State> state_;
public:
    ScalarExecutor(const ExecutableProgram& program,SlotPool& slots,WorkspaceBudget& workspace);
    ~ScalarExecutor();
    ScalarExecutor(const ScalarExecutor&)=delete;
    ScalarExecutor& operator=(const ScalarExecutor&)=delete;

    // The caller owns the session and shares its budget. Without options.calls,
    // remaining_steps is used as supplied; with accounting, admission resets it
    // only when no call/lifecycle anchor is active.
    // Only explicit tick instructions consume remaining_steps. Arguments are
    // copied into local storage; globals preserve their original SlotId values.
    Value invoke(std::uint32_t function,std::span<const Value> arguments,
                 std::span<const SlotId> globals,std::uint64_t& remaining_steps,
                 ExecutionPosition* position=nullptr,ExecutionOptions options={});
};
}
#endif
