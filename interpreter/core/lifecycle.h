#ifndef AZSCRIPT_CORE_LIFECYCLE_H
#define AZSCRIPT_CORE_LIFECYCLE_H
#include "../blocks.h"
#include <cstddef>
#include <memory>

namespace azertian::lifecycle {
void release_temporary(slot_block& block) noexcept;

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
                    const std::shared_ptr<environment>& context,bool run_destructor,pending_error& failure);
}
#endif
