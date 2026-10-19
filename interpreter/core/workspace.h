#ifndef AZSCRIPT_CORE_WORKSPACE_H
#define AZSCRIPT_CORE_WORKSPACE_H

#include "storage.h"
#include <algorithm>
#include <exception>
#include <memory>
#include <span>

namespace azertian {

// A session-owned vector with explicit backing-capacity accounting. Frame
// records retain offsets into these arrays, never element addresses: growing
// an array can move its elements while script calls remain on the VM stack.
template<class T> class WorkspaceVector {
    static_assert(std::is_nothrow_move_constructible_v<T>);
    static_assert(std::is_nothrow_destructible_v<T>);
    WorkspaceBudget* budget_;
    std::pmr::memory_resource* resource_;
    std::size_t max_size_,size_=0,capacity_=0;
    T* data_=nullptr;
    void dispose() noexcept {
        clear();
        if(data_) {
            const auto bytes=capacity_*sizeof(T);
            resource_->deallocate(data_,bytes,alignof(T));budget_->release(bytes);
        }
        data_=nullptr;capacity_=0;
    }
public:
    explicit WorkspaceVector(WorkspaceBudget& budget,
            std::size_t max_size=std::numeric_limits<std::size_t>::max()/sizeof(T),
            std::pmr::memory_resource* resource=std::pmr::get_default_resource())
        :budget_(&budget),resource_(resource),max_size_(max_size) {
        if(!resource_)throw std::invalid_argument("VM workspace vector requires a memory resource");
        checked_capacity_bytes(max_size_,sizeof(T));
    }
    WorkspaceVector(const WorkspaceVector&)=delete;
    WorkspaceVector& operator=(const WorkspaceVector&)=delete;
    WorkspaceVector(WorkspaceVector&& other) noexcept
        :budget_(other.budget_),resource_(other.resource_),max_size_(other.max_size_),
         size_(std::exchange(other.size_,0)),capacity_(std::exchange(other.capacity_,0)),
         data_(std::exchange(other.data_,nullptr)) {}
    WorkspaceVector& operator=(WorkspaceVector&& other) noexcept {
        if(this!=&other) {
            dispose();budget_=other.budget_;resource_=other.resource_;max_size_=other.max_size_;
            size_=std::exchange(other.size_,0);capacity_=std::exchange(other.capacity_,0);
            data_=std::exchange(other.data_,nullptr);
        }
        return *this;
    }
    ~WorkspaceVector(){dispose();}
    std::size_t size() const noexcept{return size_;}
    std::size_t capacity() const noexcept{return capacity_;}
    std::size_t max_size() const noexcept{return max_size_;}
    std::size_t backing_bytes() const noexcept{return capacity_*sizeof(T);}
    bool empty() const noexcept{return size_==0;}
    T* data() noexcept{return data_;}
    const T* data() const noexcept{return data_;}
    T& operator[](std::size_t index) noexcept{return data_[index];}
    const T& operator[](std::size_t index) const noexcept{return data_[index];}
    T& back() noexcept{return data_[size_-1];}
    const T& back() const noexcept{return data_[size_-1];}
    std::span<T> span() noexcept{return {data_,size_};}
    std::span<const T> span() const noexcept{return {data_,size_};}

    // Reserve exactly this many elements. Budget both arrays while moving,
    // then release the old backing charge; failed allocation changes nothing.
    void reserve(std::size_t requested) {
        if(requested>max_size_)throw std::length_error("VM workspace vector capacity exceeded");
        if(requested<=capacity_)return;
        const auto bytes=checked_capacity_bytes(requested,sizeof(T));
        auto charge=budget_->reserve(bytes);
        auto* replacement=static_cast<T*>(resource_->allocate(bytes,alignof(T)));
        for(std::size_t i=0;i<size_;++i)std::construct_at(replacement+i,std::move(data_[i]));
        for(std::size_t i=size_;i-->0;)std::destroy_at(data_+i);
        if(data_) {
            resource_->deallocate(data_,capacity_*sizeof(T),alignof(T));
            budget_->release(capacity_*sizeof(T));
        }
        data_=replacement;capacity_=requested;charge.commit();
    }
    void prepare_append(std::size_t count) {
        if(count>max_size_-size_)throw std::length_error("VM workspace vector capacity exceeded");
        const auto requested=size_+count;
        if(requested<=capacity_)return;
        const auto doubled=capacity_>max_size_/2?max_size_:capacity_*2;
        reserve(std::max(requested,doubled));
    }
    template<class... Args> T& emplace_back(Args&&... args) {
        // Snapshot arguments before a growth can invalidate aliases into data_.
        T candidate(std::forward<Args>(args)...);
        prepare_append(1);
        std::construct_at(data_+size_,std::move(candidate));
        return data_[size_++];
    }
    // Register/cell/index ranges use non-throwing default initialization. All
    // growth checks finish before any new live element is published.
    [[gnu::always_inline]] std::size_t append_default(std::size_t count) {
        static_assert(std::is_nothrow_default_constructible_v<T>);
        const auto begin=size_;prepare_append(count);
        for(std::size_t i=0;i<count;++i)std::construct_at(data_+size_++);
        return begin;
    }
    void truncate(std::size_t count) noexcept {
        if(count>size_)std::terminate();
        while(size_>count)std::destroy_at(data_+--size_);
    }
    void pop_back() noexcept {
        if(!size_)std::terminate();
        std::destroy_at(data_+--size_);
    }
    void clear() noexcept {truncate(0);}
};

}
#endif
