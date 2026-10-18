#include "lifecycle.h"
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
void finalize_block(const std::shared_ptr<slot_block>& block,const std::shared_ptr<environment>& env,
                    bool run_destructor,pending_error& failure) {
    BlockFinalizer cleanup(block,run_destructor,failure);
    for(;;) {
        const slot_block* call;
        try {call=cleanup.advance();}
        catch(...) {failure.capture_current();cleanup.abandon();return;}
        if(!call)return;
        try {
            auto script=call->script_owner.lock();
            if(!script||script->closed)throw std::runtime_error("Object destructor script is unavailable");
            auto context=env;
            if(!context||context->script.lock()!=script) {
                context=std::make_shared<environment>(script->baseEnv->ownership_arena());context->script=script;
                context->parent=script->baseEnv;context->caller=env;
            }
            const address pointer{blocks::address_tag|(call->id<<blocks::offset_bits)};
            invoke_bound_function(script,*call->destructor,context,{std::make_shared<variable>(pointer)},call->destructor_contexts);
            cleanup.call_returned();
        } catch(...) {cleanup.call_failed();}
    }
}
}
