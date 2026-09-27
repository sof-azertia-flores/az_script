#include "blocks.h"
#include <algorithm>
#include <set>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace azertian {
namespace blocks {
namespace {
using V=std::shared_ptr<variable>;
using E=std::shared_ptr<environment>;
using B=std::shared_ptr<slot_block>;
constexpr std::uint64_t offset_mask=(std::uint64_t{1}<<offset_bits)-1;
constexpr std::uint64_t max_id=(address_tag>>offset_bits)-1;
struct registry_state {
    std::unordered_map<std::uint64_t,std::weak_ptr<slot_block>> blocks;
    // Retired ids at or above next_id; allocation skips them.
    std::set<std::uint64_t> retired;
    std::uint64_t next_id=1;
    std::size_t prune_at=64;
};
registry_state& registry(){static registry_state state;return state;}
std::uint64_t allocate_id() {
    auto& state=registry();
    for(auto it=state.retired.begin();it!=state.retired.end()&&*it<=state.next_id;it=state.retired.erase(it))
        if(*it==state.next_id)++state.next_id;
    if(state.next_id>max_id)throw std::runtime_error("Object id space is exhausted");
    return state.next_id++;
}
// Entries of expired or dead blocks are removed lazily, so noexcept paths never
// touch the registry.
void publish(const B& block) {
    auto& state=registry();
    if(state.blocks.size()>=state.prune_at) {
        for(auto it=state.blocks.begin();it!=state.blocks.end();) {
            auto current=it->second.lock();
            if(!current||current->state==slot_block::status::dead)it=state.blocks.erase(it);else ++it;
        }
        state.prune_at=std::max<std::size_t>(64,state.blocks.size()*2);
    }
    state.blocks[block->id]=block;
}
B lookup(address pointer) {
    if(!is_block_address(pointer))return nullptr;
    auto& state=registry();
    auto found=state.blocks.find((pointer.value&~address_tag)>>offset_bits);
    return found==state.blocks.end()?nullptr:found->second.lock();
}
B handle_of(const variable& value) noexcept {
    return value.type==OBJECT_VALUE&&value.value?*static_cast<B*>(value.value):nullptr;
}
address raw_address(const slot_block& block,std::uint64_t offset=0) noexcept {
    return address{address_tag|(block.id<<offset_bits)|offset};
}
B make_block(std::size_t size,std::weak_ptr<script> owner) {
    if(size>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Object exceeds the slot limit");
    auto block=std::make_shared<slot_block>();
    block->slots.reserve(size);
    for(std::size_t i=0;i<size;++i) {
        auto slot=std::make_shared<variable>(nullptr);slot->container=block.get();block->slots.push_back(std::move(slot));
    }
    block->script_owner=std::move(owner);
    block->id=allocate_id();publish(block);
    return block;
}
void require_usable(const B& block) {
    if(!block||block->state==slot_block::status::dead)throw std::runtime_error("Object value has expired");
}
void depth_limit(std::size_t depth) {
    if(depth>max_depth)throw std::runtime_error("Object nesting exceeds 64 levels");
}
std::size_t height(const slot_block& block,std::size_t level=0) {
    depth_limit(level);
    std::size_t result=0;
    for(auto& slot:block.slots)if(slot&&owns(*slot))result=std::max(result,1+height(*handle_of(*slot),level+1));
    return result;
}
std::size_t base_depth(const variable& target) noexcept {return target.container?target.container->depth+1:0;}
void set_depth(slot_block& block,std::size_t depth) noexcept {
    block.depth=depth;
    for(auto& slot:block.slots)if(slot&&owns(*slot))set_depth(*handle_of(*slot),depth+1);
}
// True when ancestor encloses the storage variable, directly or through parents.
bool encloses(const slot_block* ancestor,const variable& storage) noexcept {
    for(auto current=storage.container;current;current=current->owner?current->owner->container:nullptr)
        if(current==ancestor)return true;
    return false;
}
void release_temporary(slot_block& block) noexcept {
    if(!block.temp_frame)return;
    auto& list=block.temp_frame->temporaries;
    // Leave an empty entry: statement marks are indexes into this vector.
    for(auto it=list.rbegin();it!=list.rend();++it)if(it->get()==&block){it->reset();break;}
    block.temp_frame=nullptr;
}
void detach_owner_env(slot_block& block) noexcept {
    if(!block.owner_env)return;
    auto& list=const_cast<environment*>(block.owner_env)->owned_pointer;
    list.erase(std::remove(list.begin(),list.end(),raw_address(block)),list.end());
    block.owner_env=nullptr;
}
B copy_block(const B& source,std::size_t depth) {
    require_usable(source);depth_limit(depth);
    auto result=make_block(source->slots.size(),source->script_owner);
    result->destructor=source->destructor;result->destructor_contexts=source->destructor_contexts;result->depth=depth;
    for(std::size_t i=0;i<source->slots.size();++i) {
        auto& from=source->slots[i];auto& to=result->slots[i];
        if(from&&owns(*from)) {
            auto nested=copy_block(handle_of(*from),depth+1);
            nested->owner=to.get();to->setValue(std::move(nested));
        } else if(from)to->copy_from(from);
    }
    return result;
}
void assign_block(slot_block& target,const B& source) {
    require_usable(source);
    if(&target==source.get())return;
    if(target.slots.size()!=source->slots.size())throw std::runtime_error("Object assignment requires the same layout");
    if((target.owner&&encloses(source.get(),*target.owner))||(source->owner&&encloses(&target,*source->owner)))
        throw std::runtime_error("Cannot assign an object into itself");
    for(std::size_t i=0;i<target.slots.size();++i) {
        auto to=target.slots[i];auto from=source->slots[i];
        if(!from)continue;
        auto nested=handle_of(*from);
        if(!nested){to->copy_from(from);continue;}
        if(owns(*to)){assign_block(*handle_of(*to),nested);continue;}
        const auto depth=base_depth(*to);depth_limit(depth+height(*nested));
        auto copy=copy_block(nested,depth);copy->owner=to.get();to->setValue(std::move(copy));
    }
}
void call_destructor(const slot_block& block,const E& env) {
    auto script=block.script_owner.lock();
    if(!script||script->closed)throw std::runtime_error("Object destructor script is unavailable");
    auto context=env;
    if(!context||context->script.lock()!=script) {
        context=std::make_shared<environment>();context->script=script;
        context->parent=script->baseEnv;context->caller=env;
    }
    invoke_bound_function(script,*block.destructor,context,{std::make_shared<variable>(raw_address(block))},block.destructor_contexts);
}
// The destructor sees every member; members are destroyed afterwards, last first.
void finalize_into(const B& block,const E& env,bool run_destructor,std::exception_ptr& failure) {
    if(!block||block->state!=slot_block::status::live)return;
    block->state=slot_block::status::destroying;
    release_temporary(*block);
    if(run_destructor&&block->destructor) {
        try {call_destructor(*block,env);}catch(...){if(!failure)failure=std::current_exception();}
    }
    for(auto i=block->slots.size();i-->0;) {
        auto slot=block->slots[i];
        if(slot&&owns(*slot))finalize_into(handle_of(*slot),env,run_destructor,failure);
    }
    block->state=slot_block::status::dead;block->owner=nullptr;block->owner_env=nullptr;
    auto released=std::move(block->slots);block->slots.clear();
}
}
bool is_block_address(address pointer) noexcept {return (pointer.value&address_tag)!=0;}
std::shared_ptr<slot_block> create(std::size_t size,std::weak_ptr<script> owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    return make_block(size,std::move(owner));
}
void resize(const std::shared_ptr<slot_block>& block,std::size_t size) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    require_usable(block);
    if(size>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Object exceeds the slot limit");
    while(block->slots.size()>size) {
        auto slot=block->slots.back();block->slots.pop_back();
        if(slot&&owns(*slot))abandon(handle_of(*slot));
    }
    block->slots.reserve(size);
    while(block->slots.size()<size) {
        auto slot=std::make_shared<variable>(nullptr);slot->container=block.get();block->slots.push_back(std::move(slot));
    }
}
std::size_t length(const std::shared_ptr<slot_block>& block) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    require_usable(block);return block->slots.size();
}
address address_of(const std::shared_ptr<slot_block>& block,std::size_t offset) {
    require_usable(block);
    if(offset>offset_mask)throw std::out_of_range("Object slot offset is too large");
    return raw_address(*block,offset);
}
std::shared_ptr<slot_block> of(const std::shared_ptr<variable>& value) noexcept {return value?handle_of(*value):nullptr;}
bool owns(const variable& storage) noexcept {
    auto block=handle_of(storage);
    return block&&block->owner==&storage;
}
std::shared_ptr<slot_block> resolve(address pointer) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=lookup(pointer);
    if(!block||block->state==slot_block::status::dead)
        throw std::out_of_range("Invalid or expired object address: "+std::to_string(pointer.value));
    return block;
}
std::shared_ptr<variable> slot(address pointer) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=resolve(pointer);
    const auto offset=pointer.value&offset_mask;
    if(offset>=block->slots.size())throw std::out_of_range("Object slot is out of range: "+std::to_string(pointer.value));
    return block->slots[offset];
}
address member_address(address pointer,int offset) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=resolve(pointer);
    if((pointer.value&offset_mask)!=0)throw std::out_of_range("Invalid or freed object address: "+std::to_string(pointer.value));
    if(offset<0||static_cast<std::size_t>(offset)>=block->slots.size())throw std::out_of_range("Object member offset out of range");
    return raw_address(*block,static_cast<std::uint64_t>(offset));
}
void set_destructor(address pointer,std::optional<int> destructor,bool manual,const std::shared_ptr<environment>& env,type_contexts contexts) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=resolve(pointer);
    if((pointer.value&offset_mask)!=0||block->state!=slot_block::status::live)
        throw std::runtime_error("Object construction must bind a live object start");
    if(manual)throw std::runtime_error("A literal object cannot be manually managed");
    auto script=env?env->script.lock():nullptr;
    if(!script||script->closed)throw std::runtime_error("Object belongs to an unavailable script");
    validate_type_contexts(contexts,script);
    if(!destructor&&!contexts.empty())throw std::invalid_argument("Destructor contexts require a destructor");
    if(destructor) {
        auto found=script->functions.find(*destructor);
        auto function=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
        if(!function||function->rett!=VOID_VALUE||function->param_count!=1||function->param_types!=std::vector<int>{ADDRESS_VALUE}||
           static_cast<std::size_t>(function->hidden_count)!=contexts.size())
            throw std::invalid_argument("Object destructor must be a script function void(address)");
    }
    block->destructor=destructor;block->destructor_contexts=std::move(contexts);block->script_owner=script;
}
std::shared_ptr<slot_block> copy(const std::shared_ptr<slot_block>& source) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    return copy_block(source,0);
}
void store(const std::shared_ptr<variable>& target,const std::shared_ptr<variable>& value,const std::shared_ptr<environment>&) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!target||!value)throw std::invalid_argument("Null variable");
    if(target==value)return;
    auto source=handle_of(*value);
    // A scalar write releases an owned object without user code (only
    // hand-written exec can do this; the compiler keeps objects typed).
    if(!source){target->copy_from(value);return;}
    require_usable(source);
    if(owns(*target)) {
        auto existing=handle_of(*target);
        if(existing!=source)assign_block(*existing,source);
        return;
    }
    const auto depth=base_depth(*target);
    depth_limit(depth+height(*source));
    B placed;
    if(source->temp_frame&&!source->owner&&!encloses(source.get(),*target)) {
        release_temporary(*source);placed=source;set_depth(*placed,depth);
    } else placed=copy_block(source,depth);
    placed->owner=target.get();placed->owner_env=nullptr;
    target->setValue(std::move(placed));
}
void bind_local(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env) {
    if(!storage||!env||!owns(*storage))return;
    auto block=handle_of(*storage);
    env->owned_pointer.push_back(raw_address(*block));
    block->owner_env=env.get();
}
std::shared_ptr<variable> materialize(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=handle_of(*value);
    if(!block)return value->deepCopy();
    require_usable(block);
    auto frame=env?env->frame.get():nullptr;
    if(!frame||(!block->owner&&block->temp_frame==frame))return std::make_shared<variable>(block);
    // Snapshot an owned object before the next argument can change it.
    auto copy=copy_block(block,0);adopt_temporary(copy,frame);
    return std::make_shared<variable>(std::move(copy));
}
void adopt_result(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=of(value);
    if(!block||block->owner||block->temp_frame||block->state!=slot_block::status::live||!env||!env->frame)return;
    adopt_temporary(block,env->frame.get());
}
std::shared_ptr<variable> export_result(const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=of(value);require_usable(block);
    auto frame=env->frame;std::shared_ptr<environment> root=env;
    for(auto e=env;e&&e->frame==frame;e=e->parent.lock())root=e;
    auto caller=root->caller.lock();
    function_frame* destination=caller&&caller->frame?caller->frame.get():nullptr;
    B out;
    if(block->owner&&block->owner_env&&block->owner_env->frame==frame) {
        // A local or parameter of this call moves to the caller instead of being copied.
        detach_owner_env(*block);block->owner=nullptr;out=block;
    } else if(!block->owner&&block->temp_frame==frame.get()) {
        release_temporary(*block);out=block;
    } else out=copy_block(block,0);
    set_depth(*out,0);
    // Without a script caller (a host call) the result becomes host-held.
    if(destination)adopt_temporary(out,destination);
    return std::make_shared<variable>(std::move(out));
}
std::shared_ptr<variable> move_out(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=of(storage);
    if(!block)return storage->deepCopy();
    if(!owns(*storage))throw std::runtime_error("Object value was already moved");
    require_usable(block);
    detach_owner_env(*block);block->owner=nullptr;set_depth(*block,0);
    if(env&&env->frame)adopt_temporary(block,env->frame.get());
    return std::make_shared<variable>(std::move(block));
}
void drop(const std::shared_ptr<variable>& storage,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!storage||!owns(*storage))return;
    auto block=handle_of(*storage);
    detach_owner_env(*block);
    std::exception_ptr failure;finalize_into(block,env,true,failure);
    storage->setValue(nullptr);
    if(failure)std::rethrow_exception(failure);
}
void adopt_temporary(const std::shared_ptr<slot_block>& block,function_frame* frame) {
    if(!block||!frame)return;
    frame->temporaries.push_back(block);block->temp_frame=frame;
}
void finalize(const std::shared_ptr<slot_block>& block,const std::shared_ptr<environment>& env,bool run_destructor) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    std::exception_ptr failure;finalize_into(block,env,run_destructor,failure);
    if(failure)std::rethrow_exception(failure);
}
void finalize_temporaries(function_frame& frame,std::size_t mark,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    std::exception_ptr failure;
    while(frame.temporaries.size()>mark) {
        auto block=std::move(frame.temporaries.back());frame.temporaries.pop_back();
        if(!block)continue;
        block->temp_frame=nullptr;
        if(!block->owner)finalize_into(block,env,true,failure);
    }
    if(failure)std::rethrow_exception(failure);
}
void finalize_storage(const std::vector<std::shared_ptr<variable>>& storage,const std::shared_ptr<environment>& env,
                      bool run_destructor,std::exception_ptr& failure) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    for(auto i=storage.size();i-->0;) {
        auto& slot=storage[i];
        if(!slot||!owns(*slot))continue;
        auto block=handle_of(*slot);detach_owner_env(*block);
        finalize_into(block,env,run_destructor,failure);
    }
}
void release_owned(address pointer,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=lookup(pointer);
    // Entries of moved or already finalized objects are stale and skipped.
    if(!block||block->state!=slot_block::status::live||block->owner_env!=env.get())return;
    block->owner_env=nullptr;
    std::exception_ptr failure;finalize_into(block,env,true,failure);
    if(failure)std::rethrow_exception(failure);
}
void discard_owned(address pointer,const environment* env) noexcept {
    try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        auto block=lookup(pointer);
        if(block&&block->state==slot_block::status::live&&block->owner_env==env)abandon(block);
    } catch(...) {}
}
void abandon(const std::shared_ptr<slot_block>& block) noexcept {
    if(!block||block->state==slot_block::status::dead)return;
    block->state=slot_block::status::dead;
    release_temporary(*block);block->owner=nullptr;block->owner_env=nullptr;
    auto released=std::move(block->slots);block->slots.clear();
}
void retire_id(std::uint64_t id) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto& state=registry();
    if(id>=state.next_id&&id<=max_id)state.retired.insert(id);
}
}
function_frame::~function_frame() {
    for(auto& block:temporaries)if(block){block->temp_frame=nullptr;blocks::abandon(block);}
}
}
