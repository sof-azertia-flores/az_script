#include "storage.h"
#include <atomic>
#include <exception>

namespace azertian::vm {
WorkspaceBudget::WorkspaceBudget(std::size_t limit):limit_(limit) {
    if(!limit)throw std::invalid_argument("VM workspace limit must be positive");
}
void WorkspaceBudget::acquire(std::size_t bytes) {
    if(bytes>limit_-used_)throw std::runtime_error("VM workspace limit exceeded");
    used_+=bytes;
    if(used_>peak_)peak_=used_;
}
void WorkspaceBudget::release(std::size_t bytes) noexcept {
    if(bytes>used_)std::terminate(); // A mismatched backing charge is an internal bug.
    used_-=bytes;
}
WorkspaceBudget::Reservation::Reservation(WorkspaceBudget& budget,std::size_t bytes)
    :budget_(&budget),bytes_(bytes) {budget.acquire(bytes);}
WorkspaceBudget::Reservation::Reservation(Reservation&& other) noexcept
    :budget_(std::exchange(other.budget_,nullptr)),bytes_(other.bytes_) {}
WorkspaceBudget::Reservation::~Reservation() {if(budget_)budget_->release(bytes_);}
std::size_t checked_capacity_bytes(std::size_t count,std::size_t element_size) {
    if(element_size&&count>std::numeric_limits<std::size_t>::max()/element_size)
        throw std::length_error("VM workspace capacity overflow");
    return count*element_size;
}
std::uint32_t checked_slot_index(std::uint64_t index,std::uint64_t slot_count) {
    if(slot_count>invalid_slot_index||index>=slot_count)
        throw std::out_of_range("VM slot index is outside its storage range");
    return static_cast<std::uint32_t>(index);
}
namespace detail {
std::uint64_t next_storage_arena() {
    static std::atomic<std::uint64_t> next{1};
    auto current=next.load(std::memory_order_relaxed);
    for(;;) {
        if(current==std::numeric_limits<std::uint64_t>::max())
            throw std::overflow_error("VM storage arena identity space exhausted");
        if(next.compare_exchange_weak(current,current+1,std::memory_order_relaxed))return current;
    }
}
}
}
