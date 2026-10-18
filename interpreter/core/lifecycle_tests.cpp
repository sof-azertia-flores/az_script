#include "lifecycle.h"
#include <array>
#include <cstdlib>
#include <iostream>
#include <new>
#include <stdexcept>

namespace {thread_local bool reject_allocations=false;thread_local std::size_t allocations=0;}
void* operator new(std::size_t size) {
    if(reject_allocations){++allocations;throw std::bad_alloc();}
    if(auto* value=std::malloc(size?size:1))return value;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size){return ::operator new(size);}
void operator delete(void* value) noexcept {std::free(value);}
void operator delete[](void* value) noexcept {std::free(value);}
void operator delete(void* value,std::size_t) noexcept {std::free(value);}
void operator delete[](void* value,std::size_t) noexcept {std::free(value);}

namespace {
using namespace azertian;
using lifecycle::BlockFinalizer;
std::size_t checks=0;
void require(bool value,const char* message){++checks;if(!value)throw std::runtime_error(message);}
template<class F> void rejects(F&& body,const char* message) {
    bool rejected=false;try{body();}catch(const std::logic_error&){rejected=true;}
    require(rejected,message);
}
auto child(const std::shared_ptr<slot_block>& parent,std::size_t index,int destructor) {
    auto block=blocks::create(1);block->destructor=destructor;block->depth=parent->depth+1;
    block->owner=parent->slots[index].get();parent->slots[index]->setValue(block);return block;
}
void suspended_order() {
    auto root=blocks::create(2);root->destructor=1;
    auto left=child(root,0,2),right=child(root,1,3),leaf=child(right,0,4);
    auto frame=std::make_shared<function_frame>();blocks::adopt_temporary(root,frame.get());
    pending_error error;BlockFinalizer cleanup(root,true,error);
    rejects([&]{cleanup.finish();},"unfinished traversal must not finish");
    rejects([&]{cleanup.call_returned();},"unsolicited call acknowledgement must reject");
    require(cleanup.advance()==root.get(),"parent destructor must precede members");
    require(root->state==slot_block::status::destroying&&root->slots.size()==2&&
            !frame->temporaries[0],"suspension must keep members visible and detach the temporary");
    rejects([&]{cleanup.advance();},"a suspended call must not be issued twice");
    rejects([&]{cleanup.call_failed();},"call_failed must require an active exception");
    // A second cleanup of the active object must neither call it again nor
    // destroy members before the suspended traversal regains control.
    blocks::finalize(root,{},true);
    require(left->state==slot_block::status::live,"reentrant cleanup must leave active members alone");
    cleanup.call_returned();
    require(cleanup.advance()==right.get(),"members must start in reverse slot order");
    std::weak_ptr<variable> held=root->slots[1];root->slots[1].reset();
    require(!held.expired(),"child call must retain its owning storage across parent mutation");
    cleanup.call_returned();
    require(cleanup.advance()==leaf.get(),"nested members must follow their parent's body");
    cleanup.call_returned();
    require(cleanup.advance()==left.get()&&held.expired()&&right->state==slot_block::status::dead,
            "child storage must retire before the next sibling call");
    cleanup.call_returned();require(!cleanup.advance(),"final sibling must complete the traversal");
    cleanup.finish();require(root->slots.empty()&&root->state==slot_block::status::dead,"completion must retire the root");
}
void failures_and_abandon() {
    auto root=blocks::create(1);root->destructor=1;auto nested=child(root,0,2);
    pending_error error;BlockFinalizer cleanup(root,true,error);
    require(cleanup.advance()==root.get(),"failing root must yield");
    try{throw 73;}catch(...){cleanup.call_failed();}
    require(cleanup.advance()==nested.get(),"parent failure must not skip child destruction");
    try{throw std::runtime_error("later error");}catch(...){cleanup.call_failed();}
    require(!cleanup.advance()&&nested->state==slot_block::status::dead,"all release must precede propagation");
    for(int i=0;i<2;++i) {
        bool original=false;try{cleanup.finish();}catch(int value){original=value==73;}
        require(original,"finish must preserve the first nonstandard exception on every rethrow");
    }
    auto abandoned=blocks::create(1);abandoned->destructor=1;auto member=child(abandoned,0,2);
    pending_error ignored;
    {BlockFinalizer interrupted(abandoned,true,ignored);require(interrupted.advance()==abandoned.get(),"abandon fixture must suspend");}
    require(abandoned->state==slot_block::status::dead&&member->state==slot_block::status::dead&&!ignored,
            "destroying a suspended machine must release without further script calls");
    auto pinned=blocks::create(1);pinned->buffer=std::make_shared<buffer_storage>();pinned->active_operations=1;
    {BlockFinalizer deferred(pinned,true,ignored);require(!deferred.advance(),"active buffer must defer cleanup");deferred.finish();}
    require(pinned->pending_finalize&&pinned->pending_destructor&&pinned->state==slot_block::status::live,
            "deferred buffer must retain storage and destructor intent");
    pinned->active_operations=0;blocks::finalize(pinned,{},false);
    require(pinned->state==slot_block::status::dead,"unpinned buffer must release normally");
}
void bounded_no_allocation() {
    std::array<std::shared_ptr<slot_block>,blocks::max_depth+1> chain;
    chain[0]=blocks::create(1);chain[0]->destructor=0;
    for(std::size_t i=1;i<chain.size();++i)chain[i]=child(chain[i-1],0,static_cast<int>(i));
    pending_error error;allocations=0;reject_allocations=true;
    {
        BlockFinalizer cleanup(chain[0],true,error);
        for(std::size_t i=0;i<chain.size();++i) {
            require(cleanup.advance()==chain[i].get(),"bounded traversal must visit all 65 nesting levels");
            cleanup.call_returned();
        }
        require(!cleanup.advance(),"deep traversal must complete without stack growth");cleanup.finish();
    }
    reject_allocations=false;
    require(allocations==0,"state construction, traversal and teardown must not allocate");
    for(const auto& block:chain)require(block->state==slot_block::status::dead,"every deep member must be retired");
}
void native_call_allocation_failure() {
    auto script=std::make_shared<azertian::script>();script->baseEnv=std::make_shared<environment>();
    script->baseEnv->script=script;
    for(bool foreign_context:{false,true}) {
        auto root=blocks::create(1,script);root->destructor=1;
        auto nested=child(root,0,2);nested->script_owner=script;
        pending_error failure;allocations=0;reject_allocations=true;
        lifecycle::finalize_block(root,foreign_context?nullptr:script->baseEnv,true,failure);
        reject_allocations=false;
        require(allocations>=2,"each failed destructor setup must still attempt the next member");
        require(root->state==slot_block::status::dead&&nested->state==slot_block::status::dead,
                "native call setup allocation failure must not leave destroying objects");
        bool original=false;try{failure.rethrow();}catch(const std::bad_alloc&){original=true;}
        require(original,"native call setup must retain the allocation failure");
    }
}
}
int main() {
    try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        suspended_order();failures_and_abandon();native_call_allocation_failure();bounded_no_allocation();
        std::cout<<"Lifecycle checks passed: "<<checks<<"; traversal allocation attempts: "<<allocations<<'\n';
    }catch(const std::exception& e){reject_allocations=false;std::cerr<<e.what()<<'\n';return 1;}
}
