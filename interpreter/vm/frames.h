#ifndef AZSCRIPT_VM_FRAMES_H
#define AZSCRIPT_VM_FRAMES_H

#include "program.h"
#include "workspace.h"

namespace azertian::vm {

// Logical admission is independent of the native stack and the number of
// script frames: host/builtin calls and lifecycle anchors also consume depth.
// References remain live until every Admission has been destroyed. The caller
// serializes access; nested host entries share the same counters and workspace.
class CallDepthAccounting {
    std::size_t& active_calls_;
    const std::size_t& max_call_depth_;
    std::uint64_t& remaining_steps_;
    const std::uint64_t& max_steps_;
    void leave() noexcept;
public:
    class Admission {
        CallDepthAccounting* owner_=nullptr;
        friend class CallDepthAccounting;
        explicit Admission(CallDepthAccounting& owner);
    public:
        Admission() noexcept=default;
        Admission(const Admission&)=delete;
        Admission& operator=(const Admission&)=delete;
        Admission(Admission&& other) noexcept:owner_(std::exchange(other.owner_,nullptr)) {}
        Admission& operator=(Admission&& other) noexcept;
        ~Admission(){reset();}
        void reset() noexcept;
        explicit operator bool() const noexcept{return owner_!=nullptr;}
    };
    CallDepthAccounting(std::size_t& active_calls,const std::size_t& max_call_depth,
                        std::uint64_t& remaining_steps,const std::uint64_t& max_steps) noexcept
        :active_calls_(active_calls),max_call_depth_(max_call_depth),
         remaining_steps_(remaining_steps),max_steps_(max_steps) {}
    CallDepthAccounting(const CallDepthAccounting&)=delete;
    CallDepthAccounting& operator=(const CallDepthAccounting&)=delete;
    // Native entry adapters check logical depth first, then enter their native
    // stack guard, then take an Admission. Ordinary script CALL uses no native
    // stack guard and is driven by the execution loop.
    void check_can_enter() const;
    Admission enter(){return Admission(*this);}
    std::size_t active_calls() const noexcept{return active_calls_;}
};

// Exceptions retain their original C++ type/message. The embedding adapter
// uses this last position to publish the ordinary script trace on failure.
struct ExecutionPosition {
    std::uint32_t function=no_index,node=0,location=0,return_location=0;
    ProgramCounter pc=no_index;
};

struct WorkspaceRange {std::size_t begin=0,count=0;};
struct FrameLayout {
    // scope_marks and cleanups are safe upper bounds from all scope-enter and
    // cleanup-enter PCs. Verified control-flow order can differ from the
    // instructions' physical order, but one entry PC is never active twice.
    std::size_t registers=0,locals=0,alias_cells=0,scope_marks=0,cleanups=0;
};
FrameLayout frame_layout(const ExecutableProgram& program,std::uint32_t function);

// Metadata only. Register values, local bindings and scope marks live in shared
// WorkspaceVectors; all references into those vectors are offsets. Objects and
// cleanup continuations must finish before the frame is popped.
struct FrameRecord {
    FrameId caller;
    ScopeId root_scope,current_scope;
    std::uint32_t function=no_index;
    ProgramCounter pc=no_index;
    Register return_register=no_index; // Relative to caller's register range.
    WorkspaceRange registers,locals,declared,scope_marks;
    std::size_t declared_count=0,scope_count=0;
    std::uint32_t loop_depth=0,statement_depth=0;
    std::uint32_t node=0,location=0,return_location=0;
    bool returned=false,broken=false,continued=false;
    Value result;
    CallDepthAccounting::Admission admission;
    FrameRecord() noexcept=default;
    FrameRecord(FrameRecord&&) noexcept=default;
    FrameRecord& operator=(FrameRecord&&) noexcept=default;
    FrameRecord(const FrameRecord&)=delete;
    FrameRecord& operator=(const FrameRecord&)=delete;
};

// Stable frame records and an explicitly charged stack of their IDs. This is
// storage machinery, not CALL dispatch: the executor must push/return using a
// single loop, and host reentry pumps only frames above its saved stack mark.
class FrameStack {
    FramePool<FrameRecord> frames_;
    WorkspaceVector<FrameId> stack_;
public:
    explicit FrameStack(WorkspaceBudget& workspace,std::uint32_t max_frames=invalid_slot_index,
                        std::pmr::memory_resource* resource=std::pmr::get_default_resource());
    ~FrameStack(){clear();}
    FrameStack(const FrameStack&)=delete;
    FrameStack& operator=(const FrameStack&)=delete;
    FrameId push(FrameRecord frame);
    bool pop() noexcept;
    void clear() noexcept {while(pop()) {}}
    std::size_t size() const noexcept{return stack_.size();}
    bool empty() const noexcept{return stack_.empty();}
    FrameId top_id() const noexcept{return stack_.empty()?FrameId{}:stack_.back();}
    FrameRecord& top();
    const FrameRecord& top() const;
    FrameRecord& resolve(FrameId id){return frames_.resolve(id);}
    const FrameRecord& resolve(FrameId id) const{return frames_.resolve(id);}
    FrameRecord* try_resolve(FrameId id) noexcept{return frames_.try_resolve(id);}
    const FrameRecord* try_resolve(FrameId id) const noexcept{return frames_.try_resolve(id);}
    std::size_t backing_bytes() const noexcept{return frames_.backing_bytes()+stack_.backing_bytes();}
};

}
#endif
