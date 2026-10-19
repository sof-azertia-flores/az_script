#include "admission.h"
#include <algorithm>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#else
#include <pthread.h>
#endif

namespace azertian::admission {
void accounting::ticket::reset() noexcept {
    if(owner_){auto* owner=std::exchange(owner_,nullptr);--owner->active_calls_;}
}
thread_local native_stack_state native_stack;
namespace {
// Calls, blocks and expressions recurse natively, so call depth and nesting
// limits alone do not bound stack use. Each thread records where its outermost
// script call began; execution stops at the script's byte budget or near the
// bottom of the thread's real stack, keeping a reserve for unwinding, cleanup,
// host calls and host guard pages such as the JVM's. Stacks grow downwards.
constexpr std::size_t min_stack_reserve=64*1024,max_stack_reserve=1024*1024;
// Applies only where the thread's stack bounds cannot be determined.
constexpr std::size_t fallback_stack_budget=256*1024;
void query_stack_bounds(native_stack_state& state) noexcept {
    state.low=state.high=0;
#if defined(_WIN32)
#if defined(_WIN32_WINNT)&&_WIN32_WINNT>=0x0602
    ULONG_PTR low=0,high=0;GetCurrentThreadStackLimits(&low,&high);
    state.low=low;state.high=high;
#endif
#elif defined(__APPLE__)
    const auto self=pthread_self();
    state.high=reinterpret_cast<std::uintptr_t>(pthread_get_stackaddr_np(self));
    state.low=state.high-pthread_get_stacksize_np(self);
#elif defined(__linux__)
    pthread_attr_t attributes;
    if(pthread_getattr_np(pthread_self(),&attributes)!=0)return;
    void* low=nullptr;std::size_t size=0;
    if(pthread_attr_getstack(&attributes,&low,&size)==0) {
        state.low=reinterpret_cast<std::uintptr_t>(low);state.high=state.low+size;
    }
    pthread_attr_destroy(&attributes);
#endif
}
}
void check_native_stack(std::size_t max_stack_bytes,std::uintptr_t here) {
    const auto& state=native_stack;
    const std::size_t budget=max_stack_bytes?max_stack_bytes:state.floor?0:fallback_stack_budget;
    if((state.floor&&here<state.floor)||(budget&&here<state.base&&state.base-here>budget))
        throw std::runtime_error("Script native stack limit exceeded");
}
void enter_native_stack(std::size_t max_stack_bytes) {
    auto& state=native_stack;
    const auto here=stack_position();
    if(!state.depth) {
        // Bounds that do not contain this frame are stale, e.g. after a stack switch.
        if(here<=state.low||here>state.high)query_stack_bounds(state);
        state.base=here;state.floor=0;
        if(here>state.low&&here<=state.high)
            state.floor=state.low+std::clamp<std::size_t>((state.high-state.low)/4,min_stack_reserve,max_stack_reserve);
    }
    check_native_stack(max_stack_bytes,here);
    ++state.depth;
}
}
