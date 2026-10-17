#ifndef AZSCRIPT_VM_CLEANUP_H
#define AZSCRIPT_VM_CLEANUP_H

#include "frames.h"
#include "../library.h"
#include <array>

namespace azertian::vm {
// These are execution-watermarks, not addresses into movable workspace arrays.
// The executor releases statement temporaries and scope-owned storage down to
// this boundary before acknowledging an unwind request.
struct CleanupWatermark {
    ScopeId scope;
    std::size_t scopes=0,temporaries=0,actions=0;
    std::uint32_t statements=0,loops=0;
};
struct CleanupTransition {
    enum class Kind { unwind,finalizer,resume } kind=Kind::resume;
    FrameId frame;
    ProgramCounter pc=no_index;
    CleanupWatermark watermark;
    pending_error failure;
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
    CleanupTransition failed(FrameId frame,const FrameRecord& state);
    // Called after native/scope cleanup reaches the requested watermark. It
    // saves and clears flow before a finalizer; after it, all original flow is
    // restored, including the return result and return source position.
    CleanupTransition unwound(FrameId frame,FrameRecord& state);
};

// A cleanup call needs zero arguments (factory), one (destructor/placement),
// or two (comparison). Normal arbitrary-arity CALL uses the frame dispatcher.
// Contexts use an aliasing shared_ptr into immutable, already-owned bindings;
// copying this request never allocates. No pointer refers into a movable arena.
struct CleanupScriptCall {
    int function_id=0;
    std::array<Value,2> arguments;
    std::uint8_t argument_count=0;
    std::shared_ptr<const type_contexts> contexts;
};
struct CleanupActionState {
    FrameId frame;
    ScopeId scope;
    std::uint64_t identity=0;
    std::size_t cursor=0;
    std::uint32_t phase=0;
    Value hold;
};
struct CleanupActionStep {
    enum class Kind { finished,script } kind=Kind::finished;
    CleanupScriptCall call;
};
class CleanupDriver {
public:
    virtual ~CleanupDriver()=default;
    // A driver walks its prepared cursor state and yields every script call.
    // It must not invoke script code or recursively walk object trees itself.
    virtual CleanupActionStep advance(CleanupActionState& state)=0;
    virtual void returned(CleanupActionState& state,const Value& result)=0;
    // After a failed advance/call/result check, advance the failed cursor and
    // return true to continue remaining work, or false to perform final native
    // release. This method and release must not allocate or run script code.
    virtual bool failed(CleanupActionState& state) noexcept=0;
    virtual void release(CleanupActionState& state) noexcept=0;
};
struct CleanupPoll {
    enum class Kind { complete,script } kind=Kind::complete;
    FrameId frame;
    CleanupScriptCall call;
    pending_error failure;
    CleanupPoll() noexcept=default;
    CleanupPoll(CleanupPoll&&) noexcept=default;
    CleanupPoll& operator=(CleanupPoll&&) noexcept=default;
    CleanupPoll(const CleanupPoll&)=delete;
    CleanupPoll& operator=(const CleanupPoll&)=delete;
};

// Scope/temporary ownership registers actions before publishing the object.
// Reverse-order cleanup may pause for explicit script frames and may itself
// be reentered by those frames. Each nested unwind has a preallocated session.
// Object traversal drivers are supplied by the lifecycle implementation; this
// class neither calls the legacy executor nor provides a recursive fallback.
class CleanupActions {
    struct Action {CleanupDriver* driver=nullptr;CleanupActionState state;};
    struct Session {
        std::size_t mark=0,waiting_index=0;
        bool waiting=false;
        pending_error failure;
    };
    WorkspaceVector<Action> actions_;
    WorkspaceVector<Session> sessions_;
    Session& session();
    Action& waiting_action();
    void finish_action() noexcept;
    void action_failed() noexcept;
public:
    explicit CleanupActions(WorkspaceBudget& workspace,
        std::pmr::memory_resource* resource=std::pmr::get_default_resource());
    ~CleanupActions();
    CleanupActions(const CleanupActions&)=delete;
    CleanupActions& operator=(const CleanupActions&)=delete;
    std::size_t size() const noexcept{return actions_.size();}
    std::size_t unwind_depth() const noexcept{return sessions_.size();}
    std::size_t backing_bytes() const noexcept{return actions_.backing_bytes()+sessions_.backing_bytes();}
    void prepare_actions(std::size_t additional=1){actions_.prepare_append(additional);}
    void prepare_unwinds(std::size_t additional=1){sessions_.prepare_append(additional);}
    // Both calls require prepared capacity and perform no allocation. Driver
    // lifetime must cover this stack; state.hold can retain object metadata.
    void register_action(CleanupDriver& driver,CleanupActionState state);
    void begin_unwind(std::size_t mark,pending_error&& failure={});
    CleanupPoll poll();
    void returned(const Value& result);
    void failed(); // Call from the catch of the yielded script call/admission.
};
}
#endif
