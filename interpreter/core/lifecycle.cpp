#include "lifecycle.h"
#include "object_access.h"
#include <algorithm>
#include <exception>
#include <stdexcept>

namespace azertian::lifecycle {
void release_temporary(slot_block& block) noexcept {
    if(!block.temporary_list)return;
    if(auto* record=ownership::find(block.temporary_list)) {
        auto& list=record->values;
        if(block.temporary_index<list.size()&&list[block.temporary_index].get()==&block)
            list[block.temporary_index].reset();
    }
    block.temporary_list={};
}
void detach_owner_scope(slot_block& block) noexcept {
    if(!block.owner_scope)return;
    if(auto* record=ownership::find(block.owner_scope)) {
        auto& list=record->owned;
        const address self{blocks::address_tag|(block.id<<blocks::offset_bits)};
        list.erase(std::remove(list.begin(),list.end(),self),list.end());
    }
    block.owner_scope={};
}
BlockFinalizer::BlockFinalizer(std::shared_ptr<slot_block> block,bool run_destructor,pending_error& failure) noexcept
    :run_destructor_(run_destructor),failure_(failure) {
    if(block){std::construct_at(&stack_[0].frame,Frame{std::move(block),{}});size_=1;}
}
BlockFinalizer::~BlockFinalizer(){abandon();}
void BlockFinalizer::pop() noexcept {std::destroy_at(&stack_[--size_].frame);}
const slot_block* BlockFinalizer::advance() {
    if(waiting_)throw std::logic_error("Lifecycle call has not completed");
    while(size_) {
        auto& frame=stack_[size_-1].frame;
        auto& block=*frame.block;
        if(frame.phase==Phase::enter) {
            if(block.state!=slot_block::status::live){pop();continue;}
            if(block.buffer&&block.active_operations) {
                block.pending_finalize=true;block.pending_destructor|=run_destructor_;pop();continue;
            }
            block.state=slot_block::status::destroying;release_temporary(block);
            frame.phase=Phase::children;
            if(run_destructor_&&block.destructor){waiting_=true;return &block;}
            frame.remaining=block.slots.size();
        }
        if(frame.remaining) {
            // Script-facing resize can remove siblings during a child call.
            // Retain the original reverse cursor; never visit appended slots.
            frame.remaining=std::min(frame.remaining,block.slots.size());
            if(!frame.remaining)continue;
            auto storage=block.slots[--frame.remaining];
            if(storage&&blocks::owns(*storage)) {
                // Object construction/copy/placement enforce this graph bound.
                if(size_==blocks::max_depth+1)throw std::runtime_error("Object nesting exceeds 64 levels");
                std::construct_at(&stack_[size_++].frame,Frame{storage->object_handle(),std::move(storage)});
            }
            continue;
        }
        block.state=slot_block::status::dead;block.owner=nullptr;block.owner_scope={};block.methods.reset();
        for(auto& slot:block.slots)if(slot){slot->setValue(nullptr);slot->container=nullptr;}
        // Keep the released variables alive until the slab has been detached,
        // matching the former recursive helper's native release order.
        {
            auto released=std::move(block.slots);block.slots.clear();
            if(block.buffer){block.buffer->length=0;block.buffer->capacity=0;block.buffer->slab.reset();}
        }
        pop();
    }
    return nullptr;
}
void BlockFinalizer::call_returned() {
    if(!waiting_)throw std::logic_error("Lifecycle has no pending call");
    waiting_=false;
    auto& frame=stack_[size_-1].frame;
    frame.remaining=frame.block->slots.size();
}
void BlockFinalizer::call_failed() {
    if(!waiting_||!std::current_exception())throw std::logic_error("Lifecycle failure requires a pending call and active exception");
    failure_.capture_current();call_returned();
}
void BlockFinalizer::finish() const {
    if(size_)throw std::logic_error("Lifecycle cleanup is incomplete");
    if(failure_)failure_.rethrow();
}
void BlockFinalizer::abandon() noexcept {
    run_destructor_=false;
    if(waiting_)call_returned();
    // Only malformed host-created graphs can exceed the fixed work stack.
    // advance has already consumed that edge; normal variable release drops
    // the unvisited payload, and the remaining frames must still be drained.
    while(size_)try{(void)advance();}catch(...){failure_.capture_current();}
}
DestructorCall destructor_call(const slot_block& block) noexcept {
    return {&block.script_owner,*block.destructor,address{blocks::address_tag|(block.id<<blocks::offset_bits)},
            &block.destructor_contexts};
}
void dispatch(const DestructorCall& call,ownership::Site caller) {
    auto script=call.owner->lock();
    if(!script||script->closed)throw std::runtime_error("Object destructor script is unavailable");
    const auto invoke=[&](ownership::Site context) {
        invoke_bound_function(script,call.function_id,context,{std::make_shared<variable>(call.object)},*call.contexts);
    };
    if(caller.record&&caller.record->script.lock()==script){invoke(caller);return;}
    // Another script's or no caller: the destructor runs from a scope of its
    // own script whose leftovers end, without scripts, after the call.
    const auto& base=*script->baseEnv;
    ownership::Scope context(base.ownership_arena(),{base.ownership_id(),caller.scope(),{}});
    context.record().script=script;
    invoke({&context.record(),nullptr});
}
void finalize_block(const std::shared_ptr<slot_block>& block,ownership::Site env,
                    bool run_destructor,pending_error& failure) {
    BlockFinalizer cleanup(block,run_destructor,failure);
    for(;;) {
        const slot_block* call;
        try {call=cleanup.advance();}
        catch(...) {failure.capture_current();cleanup.abandon();return;}
        if(!call)return;
        try {dispatch(destructor_call(*call),env);cleanup.call_returned();}
        catch(...) {cleanup.call_failed();}
    }
}
namespace {
// Advances a block traversal as finalize_block does: a malformed graph's
// failure is captured and the rest of the graph ends natively.
const slot_block* step(BlockFinalizer& block,pending_error& failure) {
    try {return block.advance();}
    catch(...){failure.capture_current();block.abandon();return nullptr;}
}
}
ReleaseFinalizer::ReleaseFinalizer(std::shared_ptr<slot_block> block,bool run_destructor) noexcept
    :block_(std::move(block),run_destructor,failure_) {}
const DestructorCall* ReleaseFinalizer::advance() {
    if(auto* block=step(block_,failure_)){call_=destructor_call(*block);return &call_;}
    return nullptr;
}
const DestructorCall* TemporariesFinalizer::advance() {
    for(;;) {
        if(block_) {
            if(auto* block=step(*block_,failure_)){call_=destructor_call(*block);return &call_;}
            // The finalized temporary ends after its traversal, as the loop's local did.
            block_.reset();held_.reset();
        }
        // Destructors can reenter script/host code: resolve the identity again
        // before touching the next entry rather than retaining a record pointer.
        auto* record=ownership::find(list_);
        if(!record||record->values.size()<=mark_)return nullptr;
        auto block=std::move(record->values.back());record->values.pop_back();
        if(!block)continue;
        block->temporary_list={};
        if(block->owner)continue;
        held_=block;block_.emplace(std::move(block),!abandoning_,failure_);
    }
}
void TemporariesFinalizer::finish() const {
    if(block_)throw std::logic_error("Lifecycle cleanup is incomplete");
    if(failure_)failure_.rethrow();
}
void TemporariesFinalizer::abandon() noexcept {
    abandoning_=true;
    if(block_){block_->abandon();block_.reset();held_.reset();}
    try {while(advance()){}}catch(...){failure_.capture_current();}
}
const DestructorCall* StorageFinalizer::advance() {
    for(;;) {
        if(block_) {
            if(auto* block=step(*block_,failure_)){call_=destructor_call(*block);return &call_;}
            block_.reset();
        }
        if(!cursor_)return nullptr;
        auto& slot=slots_[--cursor_];
        if(!slot||!blocks::owns(*slot))continue;
        auto block=slot->object_handle();detach_owner_scope(*block);
        block_.emplace(std::move(block),run_destructor_,failure_);
    }
}
void StorageFinalizer::abandon() noexcept {
    run_destructor_=false;
    if(block_){block_->abandon();block_.reset();}
    while(advance()){}
}
const DestructorCall* HeapDestroyer::advance() {
    for(;;)switch(phase_) {
    case Phase::start:
        if(!heap::detail::registered(object_)) {
            // A failed construction still destroys its completed value members.
            members_.emplace(heap::detail::allocation_slots(object_),true,failure_);
            phase_=Phase::members;continue;
        }
        heap::detail::begin_destroy(object_,destructor_,owner_,contexts_);
        registered_=true;phase_=Phase::destructor;
        if(destructor_){call_={&owner_,*destructor_,object_,&contexts_};return &call_;}
        continue;
    case Phase::destructor:
        // Value members end after the destructor body, while the object is
        // still marked as destroying so they cannot delete or free it.
        members_.emplace(heap::detail::allocation_slots(object_),true,failure_);
        phase_=Phase::members;continue;
    case Phase::members:
        if(auto* call=members_->advance())return call;
        members_.reset();phase_=Phase::done;
        // No registry iterator survives the script callbacks: they may delete
        // other objects.
        if(registered_)heap::detail::end_destroy(object_);else heap::free(object_);
        return nullptr;
    case Phase::done:
        return nullptr;
    }
}
void HeapDestroyer::call_returned() {
    if(members_)members_->call_returned();
}
void HeapDestroyer::call_failed() {
    if(members_)members_->call_failed();
    else failure_.capture_current();
}
void HeapDestroyer::finish() const {
    if(phase_!=Phase::done)throw std::logic_error("Lifecycle cleanup is incomplete");
    if(failure_)failure_.rethrow();
}
void HeapDestroyer::abandon() noexcept {
    // Not started: nothing was marked. Otherwise the members end natively and
    // the registration and allocation go as at the end of a destruction.
    if(phase_==Phase::done||(phase_==Phase::start&&!members_)){phase_=Phase::done;return;}
    try {
        if(!members_)members_.emplace(heap::detail::allocation_slots(object_),false,failure_);
        members_->abandon();members_.reset();
        if(registered_)heap::detail::end_destroy(object_);else heap::free(object_);
    } catch(...){failure_.capture_current();}
    phase_=Phase::done;
}
template<class Child> const DestructorCall* ScopeFinalizer::run_child(Child& child) {
    // A release's failure (direct or from its finish) is caught by the scope,
    // as environment::cleanup caught each call's.
    try {
        if(auto* call=child.advance())return call;
        if constexpr(!std::is_same_v<Child,StorageFinalizer>)child.finish();
    } catch(...) {failure_.capture_current();}
    child_.template emplace<std::monostate>();
    return nullptr;
}
const DestructorCall* ScopeFinalizer::advance() {
    for(;;) {
        const DestructorCall* call=std::visit([this](auto& child)->const DestructorCall* {
            if constexpr(std::is_same_v<std::decay_t<decltype(child)>,std::monostate>)return nullptr;
            else return run_child(child);
        },child_);
        if(call)return call;
        if(!std::holds_alternative<std::monostate>(child_))continue;
        auto* record=ownership::find(scope_);
        switch(phase_) {
        case Phase::start:
            // Already closing: nothing runs and nothing is undeclared.
            if(!record||record->closing){phase_=Phase::done;undeclared_=true;return nullptr;}
            record->closing=true;phase_=Phase::temporaries;
            if(temporaries_)child_.emplace<TemporariesFinalizer>(temporaries_,0);
            continue;
        case Phase::temporaries:
            phase_=Phase::owned;continue;
        case Phase::owned: {
            if(!record||record->owned.empty()){phase_=Phase::leftovers;continue;}
            const address pointer=record->owned.back();record->owned.pop_back();
            // heap::release_owned: entries of moved or already finalized
            // objects are stale and skipped.
            if(blocks::is_block_address(pointer)) {
                auto block=blocks::detail::lookup(pointer);
                if(!block||block->state!=slot_block::status::live||block->owner_scope!=scope_)continue;
                block->owner_scope={};
                child_.emplace<ReleaseFinalizer>(std::move(block),true);
            } else if(heap::owner_of(pointer)==scope_)child_.emplace<HeapDestroyer>(pointer);
            continue;
        }
        case Phase::leftovers:
            // Objects that a dynamic variable acquired after its declaration end here.
            phase_=Phase::done;
            child_.emplace<StorageFinalizer>(locals_.leftovers(),true,failure_);
            continue;
        case Phase::done:
            // Destructors may still observe the scope's locals through host
            // callbacks; they stop being declared only after every release.
            if(!undeclared_){undeclared_=true;locals_.undeclare();}
            return nullptr;
        }
    }
}
void ScopeFinalizer::call_returned() {
    std::visit([](auto& child){if constexpr(!std::is_same_v<std::decay_t<decltype(child)>,std::monostate>)child.call_returned();},child_);
}
void ScopeFinalizer::call_failed() {
    std::visit([](auto& child){if constexpr(!std::is_same_v<std::decay_t<decltype(child)>,std::monostate>)child.call_failed();},child_);
}
void ScopeFinalizer::finish() const {
    if(phase_!=Phase::done||!undeclared_)throw std::logic_error("Lifecycle cleanup is incomplete");
    if(failure_)failure_.rethrow();
}
void ScopeFinalizer::abandon() noexcept {
    std::visit([](auto& child){if constexpr(!std::is_same_v<std::decay_t<decltype(child)>,std::monostate>)child.abandon();},child_);
    child_.emplace<std::monostate>();
    if(phase_!=Phase::start&&!undeclared_){undeclared_=true;locals_.undeclare();}
    phase_=Phase::done;undeclared_=true;
}
}
