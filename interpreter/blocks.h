#ifndef AZSCRIPT_BLOCKS_H
#define AZSCRIPT_BLOCKS_H
#include "library.h"
#include <exception>
namespace azertian {
// A slot block is the storage of one literal (value) object. Its slots are
// independent variables; copying the value copies every slot, and its address
// stops resolving as soon as the owning variable's lifetime ends.
struct slot_block {
    enum class status {live, destroying, dead};
    std::uint64_t id=0;
    std::vector<std::shared_ptr<variable>> slots;
    std::optional<int> destructor;
    std::weak_ptr<azertian::script> script_owner;
    // Exactly one storage variable owns a live block, unless it is an unbound
    // temporary of a frame or is held by a host. owner_env orders the cleanup
    // of locals and parameters together with automatic heap objects.
    const variable* owner=nullptr;
    const environment* owner_env=nullptr;
    function_frame* temp_frame=nullptr;
    std::size_t depth=0;
    status state=status::live;
};
namespace blocks {
// Address layout: bit 63 | id << 24 | slot offset. Ids are never reused.
inline constexpr std::uint64_t address_tag=std::uint64_t{1}<<63;
inline constexpr unsigned offset_bits=24;
inline constexpr std::size_t max_depth=64;
bool is_block_address(address pointer) noexcept;
// Host API. A created block is host-held: no destructor runs for it, and its
// address expires when the last handle is released.
std::shared_ptr<slot_block> create(std::size_t size,std::weak_ptr<script> owner={});
void resize(const std::shared_ptr<slot_block>& block,std::size_t size);
std::size_t length(const std::shared_ptr<slot_block>& block);
address address_of(const std::shared_ptr<slot_block>& block,std::size_t offset=0);
std::shared_ptr<slot_block> of(const std::shared_ptr<variable>& value) noexcept;
bool owns(const variable& storage) noexcept;
// Resolution of script-visible addresses; expired objects raise errors.
std::shared_ptr<slot_block> resolve(address pointer);
std::shared_ptr<variable> slot(address pointer);
address member_address(address pointer,int offset);
void set_destructor(address pointer,std::optional<int> destructor,bool manual,const std::shared_ptr<environment>& env);
// Execution support used by the interpreter and heap.
std::shared_ptr<slot_block> copy(const std::shared_ptr<slot_block>& source);
void store(const std::shared_ptr<variable>& target,const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env);
void bind_local(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env);
std::shared_ptr<variable> materialize(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env);
void adopt_result(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env);
std::shared_ptr<variable> export_result(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env);
std::shared_ptr<variable> move_out(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env);
void drop(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env);
void adopt_temporary(const std::shared_ptr<slot_block>& block,function_frame* frame);
void finalize(const std::shared_ptr<slot_block>& block,const std::shared_ptr<environment>& env,bool run_destructor);
void finalize_temporaries(function_frame& frame,std::size_t mark,const std::shared_ptr<environment>& env);
// Finalizes blocks owned by these storage variables, last first; keeps the first error.
void finalize_storage(const std::vector<std::shared_ptr<variable>>& storage,const std::shared_ptr<environment>& env,
                      bool run_destructor,std::exception_ptr& failure);
void release_owned(address pointer,const std::shared_ptr<environment>& env);
void discard_owned(address pointer,const environment* env) noexcept;
// Marks an owned block dead without running user code; used when storage dies.
void abandon(const std::shared_ptr<slot_block>& block) noexcept;
// Snapshot restore keeps the bits of a saved address whose object no longer
// exists; retiring its id guarantees no later object is ever issued that id.
void retire_id(std::uint64_t id);
}
}
#endif
