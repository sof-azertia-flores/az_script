#ifndef AZSCRIPT_CORE_STORAGE_H
#define AZSCRIPT_CORE_STORAGE_H

#include "value.h"
#include <cstddef>
#include <cstdint>
#include <limits>
#include <memory_resource>
#include <new>
#include <optional>
#include <stdexcept>
#include <type_traits>
#include <utility>

// Generation-checked storage shared by the runtime's record arenas: ids that
// never alias across arenas or reuse, stable pools, and charged capacity.
namespace azertian {

// All pools participating in one execution session share this budget. Count
// allocated backing capacity, including empty cells and the page directory.
// Callers serialize access and keep the budget and memory resource alive until
// their pools are gone.
class WorkspaceBudget {
    std::size_t limit_,used_=0,peak_=0;
    void acquire(std::size_t bytes);
public:
    class Reservation {
        WorkspaceBudget* budget_=nullptr;
        std::size_t bytes_=0;
        friend class WorkspaceBudget;
        Reservation(WorkspaceBudget& budget,std::size_t bytes);
    public:
        Reservation(const Reservation&)=delete;
        Reservation& operator=(const Reservation&)=delete;
        Reservation(Reservation&& other) noexcept;
        Reservation& operator=(Reservation&&)=delete;
        ~Reservation();
        // Ownership of this charge passes to the backing allocation's owner.
        void commit() noexcept {budget_=nullptr;}
    };
    explicit WorkspaceBudget(std::size_t limit);
    WorkspaceBudget(const WorkspaceBudget&)=delete;
    WorkspaceBudget& operator=(const WorkspaceBudget&)=delete;
    std::size_t limit_bytes() const noexcept {return limit_;}
    std::size_t used_bytes() const noexcept {return used_;}
    // High-water of charged backing capacity, including pending reservations.
    std::size_t peak_bytes() const noexcept {return peak_;}
    Reservation reserve(std::size_t bytes) {return Reservation(*this,bytes);}
    void release(std::size_t bytes) noexcept;
};

std::size_t checked_capacity_bytes(std::size_t count,std::size_t element_size);
inline constexpr std::uint32_t invalid_slot_index=std::numeric_limits<std::uint32_t>::max();
// A slot count may be UINT32_MAX, but the index with those bits is reserved.
std::uint32_t checked_slot_index(std::uint64_t index,std::uint64_t slot_count);

template<class Tag> struct StorageId {
    std::uint64_t arena=0;
    std::uint32_t index=invalid_slot_index,generation=0;
    explicit operator bool() const noexcept {
        return arena!=0&&index!=invalid_slot_index&&generation!=0;
    }
    bool operator==(const StorageId&) const=default;
};
namespace detail {
// Process-wide, monotonically issued domains prevent equal indexes/generations
// in independent pools from accidentally authorizing access to each other.
std::uint64_t next_storage_arena();
}

// Stable addresses, explicit generation checks, and no allocation on retire.
// T contains native state only: script destructors and cleanup continuations
// must complete before the caller retires the record.
template<class T,class Tag,std::size_t PageSize=256,
         std::uint32_t GenerationLimit=std::numeric_limits<std::uint32_t>::max()>
class SegmentedPool {
    static_assert(PageSize>0&&PageSize<=invalid_slot_index);
    static_assert(GenerationLimit>0);
    static_assert(std::is_nothrow_destructible_v<T>);
    struct Cell {
        std::optional<T> value;
        std::uint32_t generation=1,next_free=invalid_slot_index;
    };
    struct Page {Cell cells[PageSize];};
    static_assert(std::is_nothrow_default_constructible_v<Page>);
    WorkspaceBudget& budget_;
    std::pmr::memory_resource* resource_;
    const std::uint64_t arena_;
    const std::uint32_t max_slots_;
    Page** pages_=nullptr;
    std::size_t page_count_=0,directory_capacity_=0,size_=0;
    std::uint32_t frontier_=0,free_head_=invalid_slot_index;

    Cell& cell(std::uint32_t index) noexcept {
        return pages_[index/PageSize]->cells[index%PageSize];
    }
    const Cell& cell(std::uint32_t index) const noexcept {
        return pages_[index/PageSize]->cells[index%PageSize];
    }
    const Cell* find(StorageId<Tag> id) const noexcept {
        if(id.arena!=arena_||id.index>=frontier_||id.generation==0)return nullptr;
        const auto& found=cell(id.index);
        return found.generation==id.generation&&found.value?&found:nullptr;
    }
    void destroy_page(Page* page) noexcept {
        page->~Page();resource_->deallocate(page,sizeof(Page),alignof(Page));
    }
    void destroy_directory(Page** directory,std::size_t capacity) noexcept {
        resource_->deallocate(directory,capacity*sizeof(Page*),alignof(Page*));
    }
    std::size_t next_directory_capacity() const {
        const auto limit=(static_cast<std::uint64_t>(max_slots_)+PageSize-1)/PageSize;
        if(!directory_capacity_)return 1;
        return static_cast<std::size_t>(directory_capacity_>limit/2?limit:directory_capacity_*2);
    }
    template<class... Args> StorageId<Tag> grow_and_emplace(Args&&... args) {
        const bool grow_directory=page_count_==directory_capacity_;
        const auto new_capacity=grow_directory?next_directory_capacity():directory_capacity_;
        const auto directory_bytes=grow_directory?checked_capacity_bytes(new_capacity,sizeof(Page*)):0;
        // Account for old and new directories coexisting during growth. Reserve
        // both charges before allocation, and publish only after T is ready.
        auto directory_charge=budget_.reserve(directory_bytes);
        auto page_charge=budget_.reserve(sizeof(Page));
        Page** directory=nullptr;
        Page* page=nullptr;
        try {
            if(grow_directory) {
                directory=static_cast<Page**>(resource_->allocate(directory_bytes,alignof(Page*)));
                for(std::size_t i=0;i<page_count_;++i)directory[i]=pages_[i];
            }
            void* storage=resource_->allocate(sizeof(Page),alignof(Page));
            page=::new(storage) Page;
            page->cells[frontier_%PageSize].value.emplace(std::forward<Args>(args)...);
        } catch(...) {
            if(page)destroy_page(page);
            if(directory)destroy_directory(directory,new_capacity);
            throw;
        }
        if(grow_directory) {
            auto* previous=pages_;const auto previous_capacity=directory_capacity_;
            pages_=directory;directory_capacity_=new_capacity;
            directory_charge.commit();
            if(previous) {
                destroy_directory(previous,previous_capacity);
                budget_.release(previous_capacity*sizeof(Page*));
            }
        }
        pages_[page_count_++]=page;
        page_charge.commit();
        const auto index=frontier_++;++size_;
        return {arena_,index,cell(index).generation};
    }
public:
    using value_type=T;
    using id_type=StorageId<Tag>;
    static constexpr std::size_t page_size=PageSize;
    static constexpr std::size_t cell_size_bytes=sizeof(Cell);
    static constexpr std::size_t page_size_bytes=sizeof(Page);
    static constexpr std::size_t directory_entry_size_bytes=sizeof(Page*);

    explicit SegmentedPool(WorkspaceBudget& budget,std::uint32_t max_slots=invalid_slot_index,
                           std::pmr::memory_resource* resource=std::pmr::get_default_resource())
        :budget_(budget),resource_(resource),arena_(detail::next_storage_arena()),max_slots_(max_slots) {
        if(!max_slots_)throw std::invalid_argument("VM slot pool must permit at least one slot");
        if(!resource_)throw std::invalid_argument("VM slot pool requires a memory resource");
    }
    SegmentedPool(const SegmentedPool&)=delete;
    SegmentedPool& operator=(const SegmentedPool&)=delete;
    SegmentedPool(SegmentedPool&&)=delete;
    SegmentedPool& operator=(SegmentedPool&&)=delete;
    ~SegmentedPool() {
        for(std::size_t i=0;i<page_count_;++i)destroy_page(pages_[i]);
        if(pages_)destroy_directory(pages_,directory_capacity_);
        budget_.release(backing_bytes());
    }
    std::uint64_t arena_id() const noexcept {return arena_;}
    std::size_t size() const noexcept {return size_;}
    std::size_t capacity() const noexcept {return page_count_*PageSize;}
    std::uint32_t max_size() const noexcept {return max_slots_;}
    std::size_t backing_bytes() const noexcept {
        return page_count_*sizeof(Page)+directory_capacity_*sizeof(Page*);
    }
    template<class... Args> id_type emplace(Args&&... args) {
        if(free_head_!=invalid_slot_index) {
            const auto index=free_head_;auto& available=cell(index);
            // A failed construction leaves the free-list and generation intact.
            available.value.emplace(std::forward<Args>(args)...);
            free_head_=available.next_free;available.next_free=invalid_slot_index;++size_;
            return {arena_,index,available.generation};
        }
        if(frontier_==max_slots_)throw std::length_error("VM slot identity space exhausted");
        if(static_cast<std::size_t>(frontier_)/PageSize==page_count_)
            return grow_and_emplace(std::forward<Args>(args)...);
        const auto index=frontier_;auto& available=cell(index);
        available.value.emplace(std::forward<Args>(args)...);
        ++frontier_;++size_;return {arena_,index,available.generation};
    }
    T* try_resolve(id_type id) noexcept {
        const auto* found=find(id);return found?const_cast<T*>(&*found->value):nullptr;
    }
    const T* try_resolve(id_type id) const noexcept {
        const auto* found=find(id);return found?&*found->value:nullptr;
    }
    T& resolve(id_type id) {
        auto* value=try_resolve(id);
        if(!value)throw std::runtime_error("Invalid or expired VM storage identity");
        return *value;
    }
    const T& resolve(id_type id) const {
        const auto* value=try_resolve(id);
        if(!value)throw std::runtime_error("Invalid or expired VM storage identity");
        return *value;
    }
    bool retire(id_type id) noexcept {
        if(!find(id))return false;
        auto& retired=cell(id.index);retired.value.reset();--size_;
        if(retired.generation<GenerationLimit) {
            ++retired.generation;retired.next_free=free_head_;free_head_=id.index;
        }
        // Exhausted generations stay empty forever rather than wrap to old IDs.
        return true;
    }
};

}
#endif
