#ifndef AZSCRIPT_VM_CLEANUP_H
#define AZSCRIPT_VM_CLEANUP_H

#include "frames.h"
#include "../library.h"

namespace azertian::vm {
// These are execution-watermarks, not addresses into movable workspace arrays.
// The executor releases statement temporaries and scope-owned storage down to
// this boundary before acknowledging an unwind request.
struct CleanupWatermark {
    ScopeId scope;
    std::size_t scopes=0,temporaries=0;
    std::uint32_t statements=0,loops=0;
    // Object frames: scope/statement ownership records to unwind.
    std::size_t controls=0;
};
struct CleanupTransition {
    enum class Kind { unwind,finalizer,resume } kind=Kind::resume;
    FrameId frame;
    ProgramCounter pc=no_index;
    CleanupWatermark watermark;
    pending_error failure;
    // The innermost position of the first failure, restored when resuming it.
    ExecutionPosition position;
    CleanupTransition() noexcept=default;
    CleanupTransition(CleanupTransition&&) noexcept=default;
    CleanupTransition& operator=(CleanupTransition&&) noexcept=default;
    CleanupTransition(const CleanupTransition&)=delete;
    CleanupTransition& operator=(const CleanupTransition&)=delete;
};

// Compiler cleanup nodes use one preallocated continuation for both body and
// finalizer. Errors do not allocate another record. The dispatcher executes the
// returned PC itself; this component never invokes script code recursively.
class CompilerCleanupStack {
    enum class Phase { body,unwind_body,finalizer,unwind_finalizer };
    struct SavedFlow {
        bool returned=false,broken=false,continued=false;
        Value result;
        std::uint32_t location=0,return_location=0;
        void save(const FrameRecord& frame) noexcept;
        void restore(FrameRecord& frame) const noexcept;
    };
    struct Record {
        FrameId frame;
        CleanupRegion region;
        CleanupWatermark watermark;
        Phase phase=Phase::body;
        SavedFlow saved;
        pending_error failure;
        std::uint32_t first_error_location=0;
        ExecutionPosition first_error_position;
    };
    WorkspaceVector<Record> records_;
    Record& current(FrameId frame);
    CleanupTransition transition(const Record& record,CleanupTransition::Kind kind,ProgramCounter pc=no_index) const noexcept;
public:
    explicit CompilerCleanupStack(WorkspaceBudget& workspace,
        std::pmr::memory_resource* resource=std::pmr::get_default_resource());
    std::size_t size() const noexcept{return records_.size();}
    FrameId owner() const noexcept{return records_.empty()?FrameId{}:records_.back().frame;}
    std::size_t backing_bytes() const noexcept{return records_.backing_bytes();}
    // Reserve before entering an execution frame/protected region. enter does
    // not allocate; no ownership or protected code is published before reserve.
    void prepare(std::size_t additional=1){records_.prepare_append(additional);}
    void enter(FrameId frame,const CleanupRegion& region,CleanupWatermark watermark);
    CleanupTransition body_finished(FrameId frame);
    CleanupTransition finalizer_finished(FrameId frame);
    // Call only inside the active catch block. Subsequent cleanup errors are
    // discarded by pending_error while retaining the original exception/trace.
    // position is the innermost failing position, possibly in a callee frame.
    CleanupTransition failed(FrameId frame,const FrameRecord& state,const ExecutionPosition* position=nullptr);
    // Called after native/scope cleanup reaches the requested watermark. It
    // saves and clears flow before a finalizer; after it, all original flow is
    // restored, including the return result and return source position.
    CleanupTransition unwound(FrameId frame,FrameRecord& state);
    // Emergency teardown of an invocation root whose frames are being
    // discarded: drops continuations above mark without running finalizers.
    void discard(std::size_t mark) noexcept{if(records_.size()>mark)records_.truncate(mark);}
};
}
#endif
