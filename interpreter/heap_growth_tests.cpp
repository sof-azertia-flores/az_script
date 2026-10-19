#include "heepalloc.h"
#include <bit>
#include <cstdlib>
#include <iostream>
#include <new>
#include <stdexcept>
#include <vector>

// This executable compiles the real heap implementation with its private
// observation point. It does not add statistics or test APIs to the libraries.
namespace azertian::heap {std::size_t allocation_capacity_for_test();}

namespace {
thread_local std::ptrdiff_t fail_after=-1;
}
// Fault injection belongs to this executable, not the runtime allocator. Each
// attempt fails a different actual allocation made by heap::alloc.
void* operator new(std::size_t size) {
    if(fail_after==0){fail_after=-1;throw std::bad_alloc();}
    if(fail_after>0)--fail_after;
    if(void* value=std::malloc(size?size:1))return value;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size){return ::operator new(size);}
void operator delete(void* value) noexcept {std::free(value);}
void operator delete[](void* value) noexcept {std::free(value);}
void operator delete(void* value,std::size_t) noexcept {std::free(value);}
void operator delete[](void* value,std::size_t) noexcept {std::free(value);}

namespace {
using namespace azertian;
void require(bool value,const char* message) {
    if(!value)throw std::runtime_error(message);
}
void empty_heap() {
    // clearHeap deliberately retains the range vector's capacity. Restore an
    // empty vector to measure a genuinely cold sequence on every iteration.
    heap::restore({std::make_shared<variable>(nullptr)},{});
}
void growth_regression(std::size_t count) {
    empty_heap();
    std::size_t growths=0,moved=0;
    auto capacity=heap::allocation_capacity_for_test();
    for(std::size_t i=0;i<count;++i) {
        const auto pointer=heap::alloc(1);
        require(pointer.value==i+1,"range growth changes contiguous first-fit addresses");
        const auto next=heap::allocation_capacity_for_test();
        if(next!=capacity){++growths;moved+=i;capacity=next;}
    }
    std::cout<<"heap-growth count="<<count<<" growths="<<growths
             <<" relocated-live-ranges="<<moved<<" capacity="<<capacity<<'\n';
    // Observe real capacity transitions, not a copy of the growth algorithm.
    // The bounds allow other geometric policies without fixing their sequence.
    require(growths<=2*std::bit_width(count)+2,"allocation range growth is not logarithmic");
    require(moved<=4*count,"appending allocation ranges relocates superlinear live records");
    require(capacity<=static_cast<std::size_t>(heap::max_slots-1),"allocation capacity exceeds the heap range limit");
    require(heap::lenAlloc()==static_cast<int>(count),"range growth loses live allocations");

    // Insertions into an existing gap still obey first-fit and do not grow the
    // range vector while it has enough capacity.
    for(std::size_t i=0;i<64;++i)require(heap::free(address{2+3*i}),"cannot create a range gap");
    for(std::size_t i=0;i<64;++i)
        require(heap::alloc(1)==address{2+3*i},"range growth changes first-fit gap reuse");
    require(heap::allocation_capacity_for_test()==capacity,"gap reuse needlessly grows the range vector");
    require(heap::lenAlloc()==static_cast<int>(count),"gap reuse changes the live allocation count");
}
void allocation_failure_regression() {
    std::size_t failures=0;
    bool succeeded=false;
    for(std::ptrdiff_t failure=0;failure<32;++failure) {
        auto saved=std::make_shared<variable>(42);
        heap::restore({std::make_shared<variable>(nullptr),saved},{{address{1},1}});
        // Fill any spare capacity without assuming how much the vector's
        // constructor reserved. The tested call must grow all three stores.
        while(static_cast<std::size_t>(heap::lenAlloc())<heap::allocation_capacity_for_test())heap::alloc(1);
        const auto prior_ranges=heap::lenAlloc(),prior_slots=heap::lenHeap();
        fail_after=failure;
        address result;
        bool failed=false;
        try {result=heap::alloc(2);}catch(const std::bad_alloc&){failed=true;}
        catch(...){fail_after=-1;throw;}
        fail_after=-1;
        require(heap::getAt(address{1})==saved&&saved->get<int>()==42,
                "failed allocation changes an existing slot");
        if(failed) {
            ++failures;
            require(heap::lenAlloc()==prior_ranges&&heap::lenHeap()==prior_slots,"failed allocation publishes partial heap state");
            const auto range=heap::allocAt(0);
            require(range.startpos==address{1}&&range.len==1,"failed allocation changes the existing range");
        } else {
            require(result==address{static_cast<std::uint64_t>(prior_slots)}&&
                    heap::lenAlloc()==prior_ranges+1&&heap::lenHeap()==prior_slots+2,
                    "allocation after injected failures has incorrect state");
            require(heap::getAt(result)->type_tag()==VOID_VALUE&&heap::getAt(address{result.value+1})->type_tag()==VOID_VALUE,
                    "new allocation slots are not empty");
            succeeded=true;break;
        }
    }
    require(failures>0&&succeeded,"allocation fault sweep did not cover failure and success");
    // This request passes the per-request size check, but cannot fit after the
    // live allocation; it must fail before allocating or publishing anything.
    const auto ranges=heap::lenAlloc(),slots=heap::lenHeap();
    bool rejected=false;
    try {heap::alloc(heap::max_slots-1);}catch(const std::runtime_error&){rejected=true;}
    require(rejected&&heap::lenAlloc()==ranges&&heap::lenHeap()==slots,
            "heap capacity failure changes live allocations");
    std::cout<<"heap-allocation injected-failures="<<failures<<" state-preserved=true\n";
}
}
int main() {
    try {
        for(const std::size_t count:{1000,4000,20000})growth_regression(count);
        allocation_failure_regression();
        empty_heap();
        return 0;
    }catch(const std::exception& error) {
        fail_after=-1;
        std::cerr<<"Heap growth regression failed: "<<error.what()<<'\n';
        return 1;
    }
}
