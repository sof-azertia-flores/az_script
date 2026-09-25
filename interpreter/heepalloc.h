#ifndef HEEPALLOC_H
#define HEEPALLOC_H
#include "library.h"
namespace azertian {
#define AZ_HEAP_FUNCTION(Name) class Name: public function {public: int return_type() override; std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> arguments) override;};
AZ_HEAP_FUNCTION(f_heepalloc)
AZ_HEAP_FUNCTION(f_free)
AZ_HEAP_FUNCTION(f_make_free)
AZ_HEAP_FUNCTION(f_send_up)
AZ_HEAP_FUNCTION(f_get)
#undef AZ_HEAP_FUNCTION
namespace heap {
struct heap_allocation {int startpos;int len;};
struct object_record {
    int startpos;
    std::optional<int> destructor_id;
    bool manual;
    std::weak_ptr<azertian::script> script_owner;
};
inline constexpr int max_slots=1048576;
int resize_heap();
void resize_heap(int size);
int alloc(int size);
bool free(int startpos);
std::shared_ptr<variable> getAt(int pointer);
// Raw slot access is for snapshots only; getAt checks allocation membership.
std::shared_ptr<variable> getSlot(int index);
int lenHeap();
heap_allocation allocAt(int index);
void send_alloc(heap_allocation allocation);
int lenAlloc();
void clearHeap();
// restore replaces every slot and allocation and drops all automatic-release
// ownership; the caller re-registers owners for the allocations it tracks.
void restore(std::vector<std::shared_ptr<variable>> slots,std::vector<heap_allocation> allocations);
void restore(std::vector<std::shared_ptr<variable>> slots,std::vector<heap_allocation> allocations,
             std::vector<object_record> objects,std::shared_ptr<environment> restored_owner,
             std::vector<int> owned);
std::vector<object_record> object_records();
int object_address(int pointer,int offset);
void register_object(int pointer,std::optional<int> destructor_id,bool manual,const std::shared_ptr<environment>& env);
void delete_object(int pointer,const std::shared_ptr<environment>& env);
void release_owned(int pointer,const std::shared_ptr<environment>& env);
void return_object(int pointer,const std::shared_ptr<environment>& env);
void discard_owned(const environment* env) noexcept;
void discard_script_objects(const azertian::script* script_owner) noexcept;
// Automatic-release bookkeeping. An active allocation has at most one owning
// environment (the scope whose exit frees it). Explicit frees are only
// accepted from that owner's execution chain, so a scope can never keep a
// stale entry for an address that was freed and reused elsewhere.
void set_owner(int startpos,const environment* owner);
const environment* owner_of(int startpos);
}
}
#endif
