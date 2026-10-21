#ifndef AZSCRIPT_BLOCKS_H
#define AZSCRIPT_BLOCKS_H
#include "library.h"
namespace azertian {
struct buffer_storage {
    std::shared_ptr<const type_context> element;
    std::size_t length=0,capacity=0;
    // Every direct field of every element belongs to this one fixed slab.
    std::shared_ptr<variable[]> slab;
};
// A slot block is the storage of one literal (value) object. Its slots are
// independent variables; copying the value copies every slot, and its address
// stops resolving as soon as the owning variable's lifetime ends.
struct slot_block {
    enum class status {live, destroying, dead};
    std::uint64_t id=0;
    std::vector<std::shared_ptr<variable>> slots;
    std::optional<int> destructor;
    type_contexts destructor_contexts;
    std::shared_ptr<const method_table> methods;
    std::weak_ptr<azertian::script> script_owner;
    // Exactly one storage variable owns a live block, unless it is an unbound
    // temporary of a frame or is held by a host. owner_scope orders the cleanup
    // of locals and parameters together with automatic heap objects.
    const variable* owner=nullptr;
    ownership::ScopeId owner_scope;
    ownership::TempListId temporary_list;
    std::size_t temporary_index=0;
    std::size_t depth=0;
    status state=status::live;
    std::shared_ptr<buffer_storage> buffer;
    bool is_view=false;
    unsigned active_operations=0;
    bool pending_finalize=false,pending_destructor=false;
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
// Consumes the id create would take next, without a block (exec v11 reserve_block).
void reserve();
std::shared_ptr<slot_block> create_buffer(std::shared_ptr<const type_context> element,std::weak_ptr<script> owner={});
// Restore creates storage and views only, never constructors. The decoder fills
// the first length element slots, reusing any pre-created class view.
std::shared_ptr<slot_block> restore_buffer(std::shared_ptr<const type_context> element,std::size_t capacity,
                                        std::size_t length,std::weak_ptr<script> owner={});
void validate_buffer(const std::shared_ptr<slot_block>& block);
std::shared_ptr<variable> buffer_operation(int operation,const std::shared_ptr<slot_block>& block,
    const std::vector<std::shared_ptr<variable>>& arguments,const std::shared_ptr<environment>& env);
int compare_value(const std::shared_ptr<const type_context>& context,const std::shared_ptr<variable>& left,
                  const std::shared_ptr<variable>& right,const std::shared_ptr<environment>& env);
void resize(const std::shared_ptr<slot_block>& block,std::size_t size);
std::size_t length(const std::shared_ptr<slot_block>& block);
address address_of(const std::shared_ptr<slot_block>& block,std::size_t offset=0);
std::shared_ptr<slot_block> of(const std::shared_ptr<variable>& value) noexcept;
bool owns(const variable& storage) noexcept;
// Resolution of script-visible addresses; expired objects raise errors.
std::shared_ptr<slot_block> resolve(address pointer);
std::shared_ptr<variable> slot(address pointer);
address member_address(address pointer,int offset);
void set_destructor(address pointer,std::optional<int> destructor,bool manual,ownership::Site env,type_contexts contexts={});
// Execution support used by the interpreter and heap.
std::shared_ptr<slot_block> copy(const std::shared_ptr<slot_block>& source);
// The partial copies a failed copy leaves for its caller to end, innermost
// first, each with the context its level would have ended it in.
struct copy_rollback {std::vector<std::pair<std::shared_ptr<slot_block>,ownership::Site>> partial;};
// Object-model operations run in a site: the scope that owns new locals and
// the frame whose temporaries receive new values. With deferred, a failed
// copy leaves its partial copies (which may run destructors) to the caller.
void store(const std::shared_ptr<variable>& target,const std::shared_ptr<variable>& value,ownership::Site env,
           copy_rollback* deferred=nullptr);
void bind_local(const std::shared_ptr<variable>& storage,ownership::Site env);
std::shared_ptr<variable> materialize(const std::shared_ptr<variable>& value,ownership::Site env,copy_rollback* deferred=nullptr);
void adopt_result(const std::shared_ptr<variable>& value,ownership::Site env);
std::shared_ptr<variable> export_result(const std::shared_ptr<variable>& value,ownership::Site env,copy_rollback* deferred=nullptr);
std::shared_ptr<variable> move_out(const std::shared_ptr<variable>& storage,ownership::Site env);
void drop(const std::shared_ptr<variable>& storage,ownership::Site env);
// drop's first step for a driving engine: the block to end, detached from its
// owner scope (null when storage owns none). The engine ends it (destructors
// included), then clears storage, then raises the first error.
std::shared_ptr<slot_block> begin_drop(const std::shared_ptr<variable>& storage);
void adopt_temporary(const std::shared_ptr<slot_block>& block,function_frame* frame);
void adopt_temporary(const std::shared_ptr<slot_block>& block,ownership::TempListId list);
void finalize(const std::shared_ptr<slot_block>& block,ownership::Site env,bool run_destructor);
void finalize_temporaries(function_frame& frame,std::size_t mark,ownership::Site env);
void finalize_temporaries(ownership::TempListId list,std::size_t mark,ownership::Site env);
// Finalizes blocks owned by these storage variables, last first; keeps the first error.
void finalize_storage(const std::vector<std::shared_ptr<variable>>& storage,ownership::Site env,
                      bool run_destructor,pending_error& failure);
void release_owned(address pointer,ownership::Site env);
// Tree adapters: an environment runs these operations as its own site.
using environment_ref=const std::shared_ptr<environment>&;
inline void set_destructor(address pointer,std::optional<int> destructor,bool manual,environment_ref env,type_contexts contexts={})
    {set_destructor(pointer,destructor,manual,site_of(env),std::move(contexts));}
inline void store(const std::shared_ptr<variable>& target,const std::shared_ptr<variable>& value,environment_ref env)
    {store(target,value,site_of(env));}
inline void bind_local(const std::shared_ptr<variable>& storage,environment_ref env){bind_local(storage,site_of(env));}
inline std::shared_ptr<variable> materialize(const std::shared_ptr<variable>& value,environment_ref env)
    {return materialize(value,site_of(env));}
inline void adopt_result(const std::shared_ptr<variable>& value,environment_ref env){adopt_result(value,site_of(env));}
inline std::shared_ptr<variable> export_result(const std::shared_ptr<variable>& value,environment_ref env)
    {return export_result(value,site_of(env));}
inline std::shared_ptr<variable> move_out(const std::shared_ptr<variable>& storage,environment_ref env)
    {return move_out(storage,site_of(env));}
inline void drop(const std::shared_ptr<variable>& storage,environment_ref env){drop(storage,site_of(env));}
inline void finalize(const std::shared_ptr<slot_block>& block,environment_ref env,bool run_destructor)
    {finalize(block,site_of(env),run_destructor);}
inline void finalize_temporaries(function_frame& frame,std::size_t mark,environment_ref env)
    {finalize_temporaries(frame,mark,site_of(env));}
inline void finalize_temporaries(ownership::TempListId list,std::size_t mark,environment_ref env)
    {finalize_temporaries(list,mark,site_of(env));}
inline void finalize_storage(const std::vector<std::shared_ptr<variable>>& storage,environment_ref env,
                             bool run_destructor,pending_error& failure)
    {finalize_storage(storage,site_of(env),run_destructor,failure);}
inline void release_owned(address pointer,environment_ref env){release_owned(pointer,site_of(env));}
void discard_owned(address pointer,ownership::ScopeId scope) noexcept;
// Marks an owned block dead without running user code; used when storage dies.
void abandon(const std::shared_ptr<slot_block>& block) noexcept;
// Snapshot restore keeps the bits of a saved address whose object no longer
// exists; retiring its id guarantees no later object is ever issued that id.
void retire_id(std::uint64_t id);
}
}
#endif
