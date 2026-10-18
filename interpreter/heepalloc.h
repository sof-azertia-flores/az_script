#ifndef HEEPALLOC_H
#define HEEPALLOC_H
#include "library.h"
namespace azertian {
#define AZ_HEAP_FUNCTION(Name) class Name: public function {public: int return_type() override; std::shared_ptr<variable> invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> arguments) override;};
AZ_HEAP_FUNCTION(f_heepalloc)
AZ_HEAP_FUNCTION(f_object_alloc)
AZ_HEAP_FUNCTION(f_free)
AZ_HEAP_FUNCTION(f_make_free)
AZ_HEAP_FUNCTION(f_send_up)
AZ_HEAP_FUNCTION(f_get)
#undef AZ_HEAP_FUNCTION
namespace heap {
struct heap_allocation {address startpos;int len;};
struct object_record {
    address startpos;
    std::optional<int> destructor_id;
    bool manual;
    std::weak_ptr<azertian::script> script_owner;
    type_contexts destructor_contexts;
    std::shared_ptr<const method_table> methods;
};
inline constexpr int max_slots=static_cast<int>(limits::max_slots);
int resize_heap();
void resize_heap(int size);
address alloc(int size);
// Compiler object storage is tagged before construction starts.
address object_alloc(int size);
// Includes tagged range interiors and literal/view addresses.
bool is_object_storage(address pointer);
// Reflection checks liveness and direct ABI tags, never nominal class identity.
void check_object(address pointer,const std::vector<int>& slot_types);
// Empty tables clear a binding; construction/destruction phase changes are allowed.
void set_methods(address pointer,std::shared_ptr<const method_table> methods,const std::shared_ptr<script>& script);
std::shared_ptr<const method_table> methods_at(address pointer,const std::shared_ptr<script>& script);
std::size_t method_table_count();
bool free(address startpos);
std::shared_ptr<variable> getAt(address pointer);
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
             std::vector<address> owned);
std::vector<object_record> object_records();
address object_address(address pointer,int offset);
void register_object(address pointer,std::optional<int> destructor_id,bool manual,const std::shared_ptr<environment>& env,type_contexts contexts={});
void delete_object(address pointer,const std::shared_ptr<environment>& env);
void release_owned(address pointer,const std::shared_ptr<environment>& env);
void return_object(address pointer,const std::shared_ptr<environment>& env);
void discard_owned(ownership::ScopeId scope,ownership::ScopeRecord& record) noexcept;
void discard_script_objects(const azertian::script* script_owner) noexcept;
// Automatic-release bookkeeping. An active allocation has at most one owning
// environment (the scope whose exit frees it). Explicit frees are only
// accepted from that owner's execution chain, so a scope can never keep a
// stale entry for an address that was freed and reused elsewhere.
void set_owner(address startpos,const environment* owner);
void set_owner(address startpos,ownership::ScopeId owner,const ownership::ScopeRecord& record);
ownership::ScopeId owner_of(address startpos);
}
}
#endif
