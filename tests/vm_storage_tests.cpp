#include "../interpreter/vm/storage.h"
#include <cstdint>
#include <iostream>
#include <limits>
#include <memory_resource>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <vector>

using namespace azertian::vm;
namespace {
int checks=0;
void check(bool condition,const char* message) {
    ++checks;if(!condition)throw std::runtime_error(message);
}
template<class Exception=std::exception,class Function>
void rejects(Function function,const char* message) {
    bool rejected=false;
    try{function();}catch(const Exception&){rejected=true;}
    check(rejected,message);
}
class ObservedResource final:public std::pmr::memory_resource {
    void* do_allocate(std::size_t bytes,std::size_t alignment) override {
        ++calls;
        if(fail_all||(fail_at&&calls==fail_at))throw std::bad_alloc();
        void* result=std::pmr::new_delete_resource()->allocate(bytes,alignment);
        live_bytes+=bytes;++live_allocations;return result;
    }
    void do_deallocate(void* pointer,std::size_t bytes,std::size_t alignment) override {
        live_bytes-=bytes;--live_allocations;
        std::pmr::new_delete_resource()->deallocate(pointer,bytes,alignment);
    }
    bool do_is_equal(const std::pmr::memory_resource& other) const noexcept override {return this==&other;}
public:
    std::size_t calls=0,fail_at=0,live_bytes=0,live_allocations=0;
    bool fail_all=false;
};
struct ThrowingRecord {
    inline static bool fail=false;
    inline static int alive=0;
    int value;
    explicit ThrowingRecord(int value):value(value) {
        if(fail)throw std::runtime_error("record construction failed");
        ++alive;
    }
    ~ThrowingRecord() noexcept {--alive;}
};
static_assert(!std::is_convertible_v<SlotId,FrameId>);
static_assert(!std::is_convertible_v<FrameId,ScopeId>);
static_assert(std::is_same_v<decltype(std::declval<const SlotPool&>().resolve(SlotId{})),const Value&>);
static_assert(noexcept(std::declval<SlotPool&>().retire(SlotId{})));

void budget_checks() {
    rejects<std::invalid_argument>([]{WorkspaceBudget invalid(0);},"Zero workspace limit is rejected");
    WorkspaceBudget budget(32);
    {auto pending=budget.reserve(24);check(budget.used_bytes()==24,"Pending backing capacity consumes budget");}
    check(budget.used_bytes()==0,"Uncommitted allocation rolls back its charge");
    check(budget.peak_bytes()==24,"Peak retains transient pending backing charges after rollback");
    {auto first=budget.reserve(16);auto moved=std::move(first);moved.commit();}
    check(budget.used_bytes()==16,"Moved reservation commits its charge once");
    rejects([&]{auto rejected=budget.reserve(17);},"Workspace limit rejects growth before allocation");
    check(budget.used_bytes()==16,"Rejected growth leaves existing charge intact");
    check(budget.peak_bytes()==24,"Rejected over-budget growth does not raise the accepted high-water");
    budget.release(16);
    check(checked_capacity_bytes(0,std::numeric_limits<std::size_t>::max())==0,"Empty capacity has zero bytes");
    rejects<std::length_error>([]{checked_capacity_bytes(std::numeric_limits<std::size_t>::max(),2);},"Backing byte multiplication cannot overflow");
    check(checked_slot_index(invalid_slot_index-1,invalid_slot_index)==invalid_slot_index-1,"Highest representable valid slot index is accepted");
    rejects<std::out_of_range>([]{checked_slot_index(invalid_slot_index,invalid_slot_index);},"Reserved slot index is rejected");
    rejects<std::out_of_range>([]{checked_slot_index(0,static_cast<std::uint64_t>(invalid_slot_index)+1);},"Slot count cannot truncate to 32 bits");
    rejects<std::out_of_range>([]{checked_slot_index(std::numeric_limits<std::uint64_t>::max(),32);},"Oversized index cannot truncate into a live slot");
}

void stable_growth_and_reuse() {
    ObservedResource resource;WorkspaceBudget budget(1024*1024);
    {
        SegmentedPool<Value,SlotTag,7> slots(budget,4096,&resource);
        auto first=slots.emplace(31);auto* first_address=&slots.resolve(first);
        std::vector<SlotId> ids{first};
        for(int i=1;i<2048;++i)ids.push_back(slots.emplace(i));
        check(&slots.resolve(first)==first_address&&slots.resolve(first).get<int>()==31,"Page and directory growth preserve record addresses and values");
        bool intact=true;
        for(int i=1;i<2048;++i)intact&=slots.resolve(ids[i]).get<int>()==i;
        check(intact,"All records survive repeated directory growth");
        check(slots.backing_bytes()==resource.live_bytes&&budget.used_bytes()==resource.live_bytes,"Accounting equals all allocated page and directory capacity");
        const auto charged=budget.used_bytes();const auto calls=resource.calls;
        resource.fail_all=true;
        check(slots.retire(ids[31]),"Live record retires without allocation");
        check(resource.calls==calls&&budget.used_bytes()==charged,"Retirement neither allocates nor hides retained capacity");
        check(!slots.try_resolve(ids[31])&&!slots.retire(ids[31]),"Retired ID cannot resolve or retire twice");
        const auto replacement=slots.emplace(72);
        check(replacement.index==ids[31].index&&replacement.generation!=ids[31].generation,"Reuse changes generation at the same physical index");
        check(slots.resolve(replacement).get<int>()==72&&!slots.try_resolve(ids[31]),"Old generation cannot access a reused record");
        check(resource.calls==calls,"Reusing a free record performs no backing allocation");
        rejects([&]{slots.resolve(ids[31]);},"Checked resolution rejects stale generations");
        resource.fail_all=false;
        SlotPool other(budget,8,&resource);auto foreign=other.emplace(99);
        check(!slots.try_resolve(foreign)&&!other.try_resolve(first),"Equal index and generation from another arena cannot alias");
        check(!slots.retire(foreign)&&other.resolve(foreign).get<int>()==99,"Foreign retirement cannot destroy another pool's record");
        const auto total=slots.backing_bytes()+other.backing_bytes();
        check(total==budget.used_bytes()&&total==resource.live_bytes,"Independent pools share one workspace allowance");
    }
    check(budget.used_bytes()==0&&resource.live_bytes==0&&resource.live_allocations==0,"Pool destruction returns every backing charge and allocation");
}

void generation_exhaustion() {
    WorkspaceBudget budget(1024);
    SegmentedPool<Value,SlotTag,1,2> slots(budget,1);
    const auto first=slots.emplace(1);slots.retire(first);
    const auto last=slots.emplace(2);slots.retire(last);
    check(last.generation==2&&!slots.try_resolve(first)&&!slots.try_resolve(last),"Retired exhausted generation remains inaccessible");
    rejects<std::length_error>([&]{slots.emplace(3);},"Generation exhaustion retires the index permanently instead of wrapping");
    check(slots.size()==0,"Exhausted identity rejection publishes no record");
}

void failure_atomicity() {
    using Pool=SegmentedPool<ThrowingRecord,SlotTag,1>;
    for(std::size_t failure_step=1;failure_step<=2;++failure_step) {
        ObservedResource resource;WorkspaceBudget budget(1024);
        Pool slots(budget,4,&resource);const auto first=slots.emplace(17);
        const auto old_bytes=budget.used_bytes(),old_allocations=resource.live_allocations;
        resource.fail_at=resource.calls+failure_step;
        rejects<std::bad_alloc>([&]{slots.emplace(18);},"Injected directory/page allocation failure propagates");
        check(slots.size()==1&&slots.capacity()==1&&slots.resolve(first).value==17,"Failed growth leaves published records unchanged");
        check(budget.used_bytes()==old_bytes&&resource.live_bytes==old_bytes&&resource.live_allocations==old_allocations,"Failed growth destroys candidates and rolls back all capacity charges");
        resource.fail_at=0;
        const auto second=slots.emplace(19);
        check(second.index==1&&second.generation==1,"Failed growth does not consume a slot identity");
    }
    check(ThrowingRecord::alive==0,"Allocation failure test leaves no live records");
    {
        ObservedResource resource;WorkspaceBudget budget(1024);Pool slots(budget,4,&resource);
        const auto first=slots.emplace(23);const auto old_bytes=budget.used_bytes();
        ThrowingRecord::fail=true;
        rejects([&]{slots.emplace(24);},"Candidate record construction can fail after backing allocation");
        check(slots.size()==1&&slots.capacity()==1&&budget.used_bytes()==old_bytes&&resource.live_bytes==old_bytes,"Throwing construction leaves no partially published page or charge");
        ThrowingRecord::fail=false;slots.retire(first);
        ThrowingRecord::fail=true;
        rejects([&]{slots.emplace(25);},"Reused record construction failure propagates");
        check(slots.size()==0&&!slots.try_resolve(first),"Failed reuse leaves the stale identity retired");
        ThrowingRecord::fail=false;const auto reused=slots.emplace(26);
        check(reused.index==first.index&&reused.generation==first.generation+1,"Failed reuse preserves the free list and next generation");
    }
    check(ThrowingRecord::alive==0,"Throwing constructor rollback leaks no record");
    using ValuePool=SegmentedPool<Value,SlotTag,1>;
    const auto first_bytes=ValuePool::page_size_bytes+ValuePool::directory_entry_size_bytes;
    {
        ObservedResource resource;WorkspaceBudget budget(first_bytes-1);ValuePool slots(budget,4,&resource);
        rejects([&]{slots.emplace(1);},"Budget rejects a page plus its directory when the complete charge will not fit");
        check(resource.calls==0&&slots.size()==0&&slots.capacity()==0&&budget.used_bytes()==0,"Budget failure occurs before allocation or publication");
    }
    {
        // Final two-page capacity fits, but both old and new directory arrays
        // coexist during growth. Their true transient footprint must also fit.
        WorkspaceBudget budget(2*ValuePool::page_size_bytes+2*ValuePool::directory_entry_size_bytes);
        ValuePool slots(budget,4);const auto first=slots.emplace(7);
        rejects([&]{slots.emplace(8);},"Directory replacement accounts for its overlapping backing capacity");
        check(slots.size()==1&&slots.resolve(first).get<int>()==7&&budget.used_bytes()==first_bytes,"Peak-budget failure leaves the existing pool usable");
    }
}

void language_slot_boundaries() {
    constexpr std::uint32_t maximum_slots=1048576;
    WorkspaceBudget budget(64*1024*1024);
    {
        SlotPool slots(budget,maximum_slots);SlotId first,last;
        bool all_valid=true;
        for(std::uint32_t i=0;i<maximum_slots;++i) {
            const auto id=slots.emplace(static_cast<int>(i));
            if(i==0)first=id;
            if(i==32767)all_valid&=id.index==32767&&slots.resolve(id).get<int>()==32767;
            if(i==maximum_slots-1)last=id;
        }
        check(all_valid&&slots.resolve(first).get<int>()==0,"32k-slot point and early values survive later pool growth");
        check(last.index==maximum_slots-1&&slots.resolve(last).get<int>()==static_cast<int>(maximum_slots-1),"Legal maximum slot count includes the last declared slot");
        check(slots.size()==maximum_slots&&budget.used_bytes()==slots.backing_bytes(),"Maximum pool count uses the measured backing charge");
        rejects<std::length_error>([&]{slots.emplace(0);},"Pool rejects one slot past its configured language maximum");
        std::cout<<"layout: Value="<<sizeof(Value)<<" SlotCell="<<SlotPool::cell_size_bytes
                 <<" SlotId="<<sizeof(SlotId)<<" FrameId="<<sizeof(FrameId)<<" ScopeId="<<sizeof(ScopeId)
                 <<" maximum_slots_backing="<<slots.backing_bytes()<<'\n';
    }
    check(budget.used_bytes()==0,"Maximum-slot pool releases all backing capacity");
}
}

int main() {
    try {
        budget_checks();stable_growth_and_reuse();generation_exhaustion();failure_atomicity();language_slot_boundaries();
        std::cout<<checks<<" VM storage primitive checks passed\n";
        return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
