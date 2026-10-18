#include "lifecycle.h"
#include <algorithm>
#include <array>
#include <string>
#include <vector>
#include <cstdlib>
#include <iostream>
#include <functional>
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
// Plays the engine for a composed driver: records each requested destructor
// and fails those listed, inside the call's catch.
template<class Driver> std::vector<int> drive_calls(Driver& driver,std::vector<int> failing={},
                                                     const std::function<void(int)>& during={}) {
    std::vector<int> calls;
    while(const auto* call=driver.advance()) {
        calls.push_back(call->function_id);
        try {
            if(during)during(call->function_id);
            if(std::find(failing.begin(),failing.end(),call->function_id)!=failing.end())
                throw std::runtime_error("fail "+std::to_string(call->function_id));
            driver.call_returned();
        } catch(...){driver.call_failed();}
    }
    return calls;
}
template<class F> std::string thrown(F&& body) {
    try{body();}catch(const std::exception& error){return error.what();}
    return {};
}
std::shared_ptr<variable> holding(const std::shared_ptr<slot_block>& block) {
    auto storage=std::make_shared<variable>(nullptr);storage->setValue(block);block->owner=storage.get();return storage;
}
std::shared_ptr<slot_block> object(int destructor) {auto block=blocks::create(1);block->destructor=destructor;return block;}
void temporaries_driver() {
    auto frame=std::make_shared<function_frame>();
    auto kept=object(1),bound=object(2),last=object(3);child(last,0,4);
    for(const auto& block:{kept,bound,last})blocks::adopt_temporary(block,frame.get());
    auto storage=holding(bound);
    lifecycle::TemporariesFinalizer cleanup(frame->temporary_id(),1);
    require(drive_calls(cleanup,{3})==std::vector<int>({3,4}),"temporaries end newest first; a failure keeps the members");
    require(frame->temporaries.size()==1&&bound->state==slot_block::status::live&&!bound->temporary_list&&
            last->state==slot_block::status::dead&&kept->state==slot_block::status::live,
            "only temporaries above the mark end; bound ones are only removed");
    require(thrown([&]{cleanup.finish();})=="fail 3","temporaries rethrow their first failure");
    const auto old=frame->temporary_id();frame=std::make_shared<function_frame>();
    lifecycle::TemporariesFinalizer stale(old,0);
    require(!stale.advance(),"a retired list's id ends nothing");stale.finish();
}
void storage_driver() {
    auto arena=ownership::Arena::create();ownership::Lease<ownership::ScopeId> scope(arena);
    auto first=object(5),second=object(6),plain=blocks::create(1);
    first->owner_scope=scope.id();scope.record().owned.push_back(blocks::address_of(first));
    std::vector<std::shared_ptr<variable>> slots{holding(first),nullptr,holding(second),std::make_shared<variable>(2),holding(plain)};
    pending_error seed;try{throw std::runtime_error("seed");}catch(...){seed.capture_current();}
    lifecycle::StorageFinalizer cleanup(slots,true,seed);
    require(drive_calls(cleanup,{6})==std::vector<int>({6,5}),"storage ends its owned blocks last first");
    require(scope.record().owned.empty()&&!first->owner_scope&&plain->state==slot_block::status::dead,
            "each block leaves its owner scope before it ends");
    require(thrown([&]{seed.rethrow();})=="seed","storage keeps the caller's first error");
}
struct recorded_locals final:lifecycle::ScopeLocals {
    std::vector<std::shared_ptr<variable>> left;
    bool undeclared=false;
    std::vector<std::shared_ptr<variable>> leftovers() const override {return left;}
    void undeclare() noexcept override {undeclared=true;}
};
void scope_driver() {
    auto arena=ownership::Arena::create();
    ownership::Lease<ownership::ScopeId> scope(arena);
    auto frame=std::make_shared<function_frame>(arena);
    auto temporary=object(10);blocks::adopt_temporary(temporary,frame.get());
    auto older=object(7),newer=object(8),moved=object(11),leftover=object(9);
    for(const auto& block:{older,newer,moved}) {
        block->owner_scope=scope.id();scope.record().owned.push_back(blocks::address_of(block));
    }
    moved->owner_scope={};
    recorded_locals locals;locals.left={holding(leftover)};
    lifecycle::ScopeFinalizer cleanup(scope.id(),frame->temporary_id(),locals);
    const auto calls=drive_calls(cleanup,{10,8},[&](int){require(!locals.undeclared,"locals stay declared during every destructor");});
    require(calls==std::vector<int>({10,8,7,9}),"temporaries, then owned objects newest first, then leftovers; stale entries skipped");
    require(scope.record().closing&&scope.record().owned.empty()&&locals.undeclared&&moved->state==slot_block::status::live,
            "the scope closes, releases its list and undeclares its locals");
    require(thrown([&]{cleanup.finish();})=="fail 10","the scope rethrows the first failure after every release");
    recorded_locals again;lifecycle::ScopeFinalizer twice(scope.id(),{},again);
    require(!twice.advance()&&!again.undeclared,"a closing scope is not cleaned again");twice.finish();
}
int main() {
    try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        suspended_order();failures_and_abandon();native_call_allocation_failure();bounded_no_allocation();
        temporaries_driver();storage_driver();scope_driver();
        std::cout<<"Lifecycle checks passed: "<<checks<<"; traversal allocation attempts: "<<allocations<<'\n';
    }catch(const std::exception& e){reject_allocations=false;std::cerr<<e.what()<<'\n';return 1;}
}
