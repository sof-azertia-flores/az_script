#ifndef AZSCRIPT_CORE_ADMISSION_H
#define AZSCRIPT_CORE_ADMISSION_H
#include <cstddef>
#include <cstdint>
#include <optional>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include "types.h"
#if defined(_MSC_VER)
#include <intrin.h>
#endif

// Entry admission shared by both engines: logical call depth and the step
// budget, the native-stack guard, and argument validation. Not installed.
namespace azertian {
std::string function_id_text(int id);
}
namespace azertian::admission {
// One script's logical call depth and step budget, read through references so
// current host limits apply. Entry at depth zero is the only budget refill.
class accounting {
    std::size_t& active_calls_;
    const std::size_t& max_call_depth_;
    std::uint64_t& remaining_steps_;
    const std::uint64_t& max_steps_;
public:
    // Counts one admitted entry until destroyed or reset.
    class ticket {
        accounting* owner_=nullptr;
        friend class accounting;
        explicit ticket(accounting& owner) noexcept:owner_(&owner) {}
    public:
        ticket() noexcept=default;
        ticket(const ticket&)=delete;
        ticket& operator=(const ticket&)=delete;
        ticket(ticket&& other) noexcept:owner_(std::exchange(other.owner_,nullptr)) {}
        ticket& operator=(ticket&& other) noexcept {
            if(this!=&other){reset();owner_=std::exchange(other.owner_,nullptr);}return *this;
        }
        ~ticket(){reset();}
        void reset() noexcept;
        explicit operator bool() const noexcept{return owner_!=nullptr;}
    };
    accounting(std::size_t& active_calls,const std::size_t& max_call_depth,
               std::uint64_t& remaining_steps,const std::uint64_t& max_steps) noexcept
        :active_calls_(active_calls),max_call_depth_(max_call_depth),
         remaining_steps_(remaining_steps),max_steps_(max_steps) {}
    accounting(const accounting&)=delete;
    accounting& operator=(const accounting&)=delete;
    // Native entries check depth, then enter the native-stack guard, then admit.
    // In-loop VM entries have no native-stack guard and use enter().
    void check_can_enter() const {
        if(max_call_depth_ && active_calls_>=max_call_depth_)throw std::runtime_error("Script call depth limit exceeded");
    }
    ticket admit() noexcept {
        if(!active_calls_)remaining_steps_=max_steps_;
        ++active_calls_;return ticket(*this);
    }
    ticket enter(){check_can_enter();return admit();}
    std::size_t active_calls() const noexcept{return active_calls_;}
};

// Natively recursive execution (tree calls and blocks, host and JNI re-entry)
// is bounded by the thread's real stack and the script's byte budget.
struct native_stack_state {
    std::uintptr_t base=0,floor=0;
    std::size_t depth=0;
    // Cached per thread: the main thread's bounds can require reading /proc.
    std::uintptr_t low=0,high=0;
};
extern thread_local native_stack_state native_stack;
inline std::uintptr_t stack_position() noexcept {
#if defined(_MSC_VER)
    return reinterpret_cast<std::uintptr_t>(_AddressOfReturnAddress());
#else
    // The frame address, unlike a local's address, is never on a sanitizer's fake stack.
    return reinterpret_cast<std::uintptr_t>(__builtin_frame_address(0));
#endif
}
void check_native_stack(std::size_t max_stack_bytes,std::uintptr_t here);
// Counts the entry only after the check passes; pair with leave_native_stack.
void enter_native_stack(std::size_t max_stack_bytes);
inline void leave_native_stack() noexcept {--native_stack.depth;}

// Argument checks of script and typed host entries. tag(i) is the type tag of
// argument i, or empty for a null host pointer.
template<class Tag> void check_arguments(const char* kind,int id,const std::vector<int>& expected,
                                         std::size_t count,Tag tag,bool allow_any) {
    if(count!=expected.size())
        throw std::runtime_error(std::string(kind)+" "+function_id_text(id)+" argument count mismatch: expected "+
            std::to_string(expected.size())+", got "+std::to_string(count));
    for(std::size_t i=0;i<expected.size();++i) {
        const std::optional<int> got=tag(i);
        if(!got)
            throw std::runtime_error(std::string(kind)+" "+function_id_text(id)+" argument "+std::to_string(i+1)+
                " is a null pointer; use variable(nullptr) for void");
        if(expected[i]!=*got&&!(allow_any&&expected[i]==ANY_VALUE))
            throw std::runtime_error(std::string(kind)+" "+function_id_text(id)+" argument "+std::to_string(i+1)+
                " type mismatch: expected "+type_name(expected[i])+", got "+type_name(*got));
    }
}
}
#endif
