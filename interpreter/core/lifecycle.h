#ifndef AZSCRIPT_CORE_LIFECYCLE_H
#define AZSCRIPT_CORE_LIFECYCLE_H
#include "../blocks.h"
#include <cstddef>
#include <memory>
#include <optional>
#include <variant>
#include <vector>

namespace azertian::lifecycle {
void release_temporary(slot_block& block) noexcept;
void detach_owner_scope(slot_block& block) noexcept;

// One suspended traversal of a valid object graph (at most max_depth edges).
// The caller holds the runtime lock across advance and the requested call.
// No script code or allocation is performed by the traversal itself. Slots
// and blocks stay strongly held across calls, including reentrant cleanup.
class BlockFinalizer {
    enum class Phase { enter, children };
    struct Frame {
        std::shared_ptr<slot_block> block;
        std::shared_ptr<variable> storage;
        std::size_t remaining=0;
        Phase phase=Phase::enter;
    };
    // Only live entries are constructed; the common one-block cleanup does
    // not initialize 65 shared_ptr pairs. Capacity needs no cleanup-time growth.
    union Cell {Frame frame;Cell() noexcept {} ~Cell() {}};
    Cell stack_[blocks::max_depth+1];
    std::size_t size_=0;
    bool run_destructor_,waiting_=false;
    pending_error& failure_;
    void pop() noexcept;
public:
    BlockFinalizer(std::shared_ptr<slot_block> block,bool run_destructor,pending_error& failure) noexcept;
    ~BlockFinalizer();
    BlockFinalizer(const BlockFinalizer&)=delete;
    BlockFinalizer& operator=(const BlockFinalizer&)=delete;
    // nullptr means complete. Otherwise invoke the returned block's destructor
    // with its captured contexts and address, then acknowledge exactly once.
    const slot_block* advance();
    void call_returned();
    void call_failed(); // Must be called in the catch of the requested call.
    void finish() const; // Requires completion; republishes the first error.
    void abandon() noexcept; // Finish native release without further script calls.
};

// Transitional native driver. Script dispatch retains the existing entry,
// admission, argument validation and trace behavior for either engine.
void finalize_block(const std::shared_ptr<slot_block>& block,
                    ownership::Site context,bool run_destructor,pending_error& failure);

// The drivers below compose the block traversal into the recursive cleanup
// functions they replace (finalize_temporaries, finalize_storage,
// destroy_registered, environment::cleanup). Each follows BlockFinalizer's
// protocol: advance() returns the next destructor call or nullptr when the
// work is done, the caller acknowledges each call exactly once (call_failed
// inside the call's catch), and finish() rethrows the first error where the
// recursive function did. A child driver ends with its finish() inside the
// parent's handler, so every capture and republish of an error trace keeps
// its place. Records are resolved again after every call.

// A destructor call: owner's function_id with the object's address, under
// contexts. The pointers stay valid until the call is acknowledged.
struct DestructorCall {
    const std::weak_ptr<azertian::script>* owner=nullptr;
    int function_id=0;
    address object{};
    const type_contexts* contexts=nullptr;
};
DestructorCall destructor_call(const slot_block& block) noexcept;

// blocks::finalize and release_owned: one block, its own first error.
class ReleaseFinalizer {
    pending_error failure_;
    BlockFinalizer block_;
    DestructorCall call_;
public:
    ReleaseFinalizer(std::shared_ptr<slot_block> block,bool run_destructor) noexcept;
    const DestructorCall* advance();
    void call_returned(){block_.call_returned();}
    void call_failed(){block_.call_failed();}
    void finish() const{block_.finish();}
    void abandon() noexcept{block_.abandon();}
};

// finalize_temporaries: the temporaries above mark of one list generation,
// newest first; those bound to storage are only removed.
class TemporariesFinalizer {
    ownership::TempListId list_;
    std::size_t mark_;
    pending_error failure_;
    std::shared_ptr<slot_block> held_;
    std::optional<BlockFinalizer> block_;
    DestructorCall call_;
    bool abandoning_=false;
public:
    TemporariesFinalizer(ownership::TempListId list,std::size_t mark) noexcept:list_(list),mark_(mark) {}
    TemporariesFinalizer(const TemporariesFinalizer&)=delete;
    TemporariesFinalizer& operator=(const TemporariesFinalizer&)=delete;
    const DestructorCall* advance();
    void call_returned(){block_->call_returned();}
    void call_failed(){block_->call_failed();}
    void finish() const;
    void abandon() noexcept;
};

// finalize_storage: the blocks these variables own, last first, each detached
// from its owner scope first. Writes the caller's accumulator; never throws.
class StorageFinalizer {
    std::vector<std::shared_ptr<variable>> slots_;
    std::size_t cursor_;
    bool run_destructor_;
    pending_error& failure_;
    std::optional<BlockFinalizer> block_;
    DestructorCall call_;
public:
    StorageFinalizer(std::vector<std::shared_ptr<variable>> slots,bool run_destructor,pending_error& failure) noexcept
        :slots_(std::move(slots)),cursor_(slots_.size()),run_destructor_(run_destructor),failure_(failure) {}
    StorageFinalizer(const StorageFinalizer&)=delete;
    StorageFinalizer& operator=(const StorageFinalizer&)=delete;
    const DestructorCall* advance();
    void call_returned(){block_->call_returned();}
    void call_failed(){block_->call_failed();}
    void abandon() noexcept;
};

// destroy_registered: a registered heap object's destructor runs, then the
// members of its allocation end last first (read after the destructor), then
// its registration and allocation go. An unregistered allocation (a failed
// construction) only ends its members and is freed. advance() throws where
// the recursive function threw directly (an object already being destroyed).
class HeapDestroyer {
    enum class Phase {start,destructor,members,done};
    address object_;
    Phase phase_=Phase::start;
    bool registered_=false;
    std::optional<int> destructor_;
    std::weak_ptr<azertian::script> owner_;
    type_contexts contexts_;
    pending_error failure_;
    std::optional<StorageFinalizer> members_;
    DestructorCall call_;
public:
    explicit HeapDestroyer(address object) noexcept:object_(object) {}
    HeapDestroyer(const HeapDestroyer&)=delete;
    HeapDestroyer& operator=(const HeapDestroyer&)=delete;
    const DestructorCall* advance();
    void call_returned();
    void call_failed();
    void finish() const;
    void abandon() noexcept;
};

// The engine's locals of a scope that is being closed.
class ScopeLocals {
public:
    // What is left to finalize after the owned objects: a function root's
    // parameters, then the declared locals in declaration order.
    virtual std::vector<std::shared_ptr<variable>> leftovers() const=0;
    // Afterwards the declared locals stop being declared.
    virtual void undeclare() noexcept=0;
protected:
    ~ScopeLocals()=default;
};

// environment::cleanup: a scope closes once. A function root's temporaries
// end first, then its owned objects newest first (each release's failure
// caught), then its leftovers; finally the locals are undeclared, after every
// destructor, and finish() rethrows the first error.
class ScopeFinalizer {
    enum class Phase {start,temporaries,owned,leftovers,done};
    ownership::ScopeId scope_;
    ownership::TempListId temporaries_;
    ScopeLocals& locals_;
    Phase phase_=Phase::start;
    bool undeclared_=false;
    pending_error failure_;
    std::variant<std::monostate,TemporariesFinalizer,ReleaseFinalizer,HeapDestroyer,StorageFinalizer> child_;
    template<class Child> const DestructorCall* run_child(Child& child);
public:
    // temporaries is the frame's list for a function root, empty otherwise.
    ScopeFinalizer(ownership::ScopeId scope,ownership::TempListId temporaries,ScopeLocals& locals) noexcept
        :scope_(scope),temporaries_(temporaries),locals_(locals) {}
    ScopeFinalizer(const ScopeFinalizer&)=delete;
    ScopeFinalizer& operator=(const ScopeFinalizer&)=delete;
    const DestructorCall* advance();
    void call_returned();
    void call_failed();
    void finish() const;
    void abandon() noexcept;
};

// Runs a destructor call through the existing dispatch: an object of another
// script runs from a scope of that script whose caller is context.
void dispatch(const DestructorCall& call,ownership::Site context);
// The transitional native driver of every composed driver.
template<class Driver> void drive(Driver& driver,ownership::Site context) {
    while(const auto* call=driver.advance()) {
        try {dispatch(*call,context);driver.call_returned();}
        catch(...) {driver.call_failed();}
    }
}
}
#endif
