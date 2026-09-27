#include "blocks.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
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
void require_buffer(const B& block) {
    if(!block||!block->buffer||block->state!=slot_block::status::live||block->pending_finalize)
        throw std::runtime_error("Buffer is unavailable");
}
void charge(const B& block,std::size_t amount) {
    if(auto script=block->script_owner.lock();script&&script->active_calls) {
        if(amount>script->remaining_steps){script->remaining_steps=0;throw std::runtime_error("Script execution step limit exceeded");}
        script->remaining_steps-=amount;
    }
}
void require_usable(const B& block) {
    if(!block||block->state==slot_block::status::dead||block->pending_finalize)throw std::runtime_error("Object value has expired");
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
void finalize_into(const B& block,const E& env,bool run_destructor,std::exception_ptr& failure);
void abandon_contents(const B& block) noexcept;
B make_buffer(std::shared_ptr<const type_context> element,std::weak_ptr<script> owner,std::size_t capacity) {
    if(!element||element->abi==VOID_VALUE||element->width<1||
       static_cast<std::size_t>(element->width)>MAX_VARIABLE_SLOTS||capacity>MAX_VARIABLE_SLOTS/static_cast<std::size_t>(element->width))
        throw std::invalid_argument("Invalid buffer capacity or element width");
    auto result=make_block(0,std::move(owner));
    result->buffer=std::make_shared<buffer_storage>();result->buffer->element=std::move(element);
    auto& data=*result->buffer;data.capacity=capacity;
    const auto width=static_cast<std::size_t>(data.element->width);
    if(capacity)data.slab=std::shared_ptr<variable[]>(new variable[capacity*width]);
    result->slots.reserve(capacity);
    for(std::size_t i=0;i<capacity;++i) {
        auto storage=data.element->kind==3?std::make_shared<variable>(nullptr):V(data.slab,&data.slab[i]);
        storage->container=result.get();result->slots.push_back(std::move(storage));
    }
    return result;
}
B create_view(const B& buffer,std::size_t index) {
    auto& data=*buffer->buffer;const auto width=static_cast<std::size_t>(data.element->width);
    auto view=make_block(0,buffer->script_owner);view->is_view=true;view->depth=buffer->depth+1;
    depth_limit(view->depth);view->slots.reserve(width);
    for(std::size_t j=0;j<width;++j) {
        V slot(data.slab,&data.slab[index*width+j]);slot->container=view.get();view->slots.push_back(std::move(slot));
    }
    auto storage=buffer->slots[index];view->owner=storage.get();storage->setValue(view);return view;
}
bool same_element(const std::shared_ptr<const type_context>& a,const std::shared_ptr<const type_context>& b,std::size_t depth=0) {
    if(!a||!b||depth>128)return false;
    return a->abi==b->abi&&a->kind==b->kind&&a->width==b->width&&
        (a->kind!=4||same_element(a->element,b->element,depth+1));
}
void check_element(const std::shared_ptr<const type_context>& type,const V& value) {
    if(!value||value->type!=type->abi)throw std::runtime_error("Buffer element type mismatch");
    if(type->abi==OBJECT_VALUE) {
        auto block=of(value);require_usable(block);
        if(type->kind==4) {
            if(!block->buffer||!same_element(type->element,block->buffer->element))throw std::runtime_error("Buffer element type mismatch");
        } else if(block->buffer||block->slots.size()!=static_cast<std::size_t>(type->width))
            throw std::runtime_error("Buffer element layout mismatch");
    }
}
void move_storage(const V& from,const V& to) noexcept {
    // Destinations are empty. Transfer payloads, never owning variable addresses.
    std::swap(from->type,to->type);std::swap(from->value,to->value);
    if(auto nested=handle_of(*to);nested&&nested->owner==from.get())nested->owner=to.get();
}
B copy_block(const B& source,std::size_t depth,const E& env={});
void copy_fields(const B& source,const B& destination,std::size_t depth,const E& env={}) {
    for(std::size_t i=0;i<source->slots.size();++i) {
        auto& from=source->slots[i];auto& to=destination->slots[i];
        if(from&&owns(*from)) {
            auto nested=copy_block(handle_of(*from),depth+1,env);
            nested->owner=to.get();to->setValue(std::move(nested));
        } else if(from)to->copy_from(from);
    }
}
B copy_block(const B& source,std::size_t depth,const E& env) {
    require_usable(source);depth_limit(depth);
    if(source->buffer)require_buffer(source);
    if(source->buffer&&source->active_operations)throw std::runtime_error("Cannot copy a buffer during an active operation");
    charge(source,source->buffer?source->buffer->capacity*static_cast<std::size_t>(source->buffer->element->width):source->slots.size());
    if(source->buffer) {
        auto result=make_buffer(source->buffer->element,source->script_owner,source->buffer->capacity);result->depth=depth;
        try {
            for(std::size_t i=0;i<source->buffer->length;++i) {
                auto from=source->slots[i];auto to=result->slots[i];
                if(source->buffer->element->kind==3) {
                    auto view=create_view(result,i);auto old=handle_of(*from);
                    view->destructor_contexts=old->destructor_contexts;copy_fields(old,view,depth+1,env);view->destructor=old->destructor;
                } else if(owns(*from)) {
                    auto child=copy_block(handle_of(*from),depth+1,env);child->owner=to.get();to->setValue(child);
                } else to->copy_from(from);
                ++result->buffer->length;
            }
        } catch(...) {
            auto failure=std::current_exception();auto context=env;
            if(!context)if(auto script=source->script_owner.lock())context=script->baseEnv;
            finalize_into(result,context,true,failure);std::rethrow_exception(failure);
        }
        return result;
    }
    auto result=make_block(source->slots.size(),source->script_owner);result->depth=depth;
    try {result->destructor_contexts=source->destructor_contexts;copy_fields(source,result,depth,env);}
    catch(...) {
        auto failure=std::current_exception();auto context=env;
        if(!context)if(auto script=source->script_owner.lock())context=script->baseEnv;
        finalize_into(result,context,true,failure);std::rethrow_exception(failure);
    }
    result->destructor=source->destructor;
    return result;
}
void assign_block(slot_block& target,const B& source,const E& env) {
    require_usable(source);
    if(&target==source.get())return;
    if(target.buffer||source->buffer)throw std::runtime_error("Buffer assignment requires owning storage");
    if(target.slots.size()!=source->slots.size())throw std::runtime_error("Object assignment requires the same layout");
    if((target.owner&&encloses(source.get(),*target.owner))||(source->owner&&encloses(&target,*source->owner)))
        throw std::runtime_error("Cannot assign an object into itself");
    for(std::size_t i=0;i<target.slots.size();++i) {
        auto to=target.slots[i];auto from=source->slots[i];
        if(!from)continue;
        auto nested=handle_of(*from);
        if(!nested){to->copy_from(from);continue;}
        if(owns(*to)){
            if(handle_of(*to)->buffer||nested->buffer)store(to,from,env);
            else assign_block(*handle_of(*to),nested,env);
            continue;
        }
        const auto depth=base_depth(*to);depth_limit(depth+height(*nested));
        auto copy=copy_block(nested,depth,env);copy->owner=to.get();to->setValue(std::move(copy));
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
    if(block->buffer&&block->active_operations) {
        block->pending_finalize=true;block->pending_destructor|=run_destructor;return;
    }
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
    for(auto& slot:block->slots)if(slot){slot->setValue(nullptr);slot->container=nullptr;}
    auto released=std::move(block->slots);block->slots.clear();
    if(block->buffer){block->buffer->length=0;block->buffer->capacity=0;block->buffer->slab.reset();}
}
void abandon_contents(const B& block) noexcept {
    if(!block)return;
    for(auto& slot:block->slots)if(slot) {
        if(owns(*slot))abandon(handle_of(*slot));
        slot->setValue(nullptr);slot->container=nullptr;
    }
    block->slots.clear();
    if(block->buffer){block->buffer->length=0;block->buffer->capacity=0;block->buffer->slab.reset();}
}
}
bool is_block_address(address pointer) noexcept {return (pointer.value&address_tag)!=0;}
std::shared_ptr<slot_block> create(std::size_t size,std::weak_ptr<script> owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    return make_block(size,std::move(owner));
}
std::shared_ptr<slot_block> create_buffer(std::shared_ptr<const type_context> element,std::weak_ptr<script> owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    validate_type_contexts({element},owner.lock());
    return make_buffer(std::move(element),std::move(owner),0);
}
std::shared_ptr<slot_block> restore_buffer(std::shared_ptr<const type_context> element,std::size_t capacity,
                                        std::size_t length,std::weak_ptr<script> owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(length>capacity)throw std::invalid_argument("Buffer length exceeds capacity");
    validate_type_contexts({element},owner.lock());
    auto result=make_buffer(std::move(element),std::move(owner),capacity);
    if(result->buffer->element->kind==3)for(std::size_t i=0;i<length;++i)create_view(result,i);
    result->buffer->length=length;return result;
}
void validate_buffer(const std::shared_ptr<slot_block>& block) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());require_buffer(block);
    auto& data=*block->buffer;
    validate_type_contexts({data.element},block->script_owner.lock());
    if(block->destructor||!block->destructor_contexts.empty()||block->is_view||block->active_operations||
       data.length>data.capacity||data.capacity>MAX_VARIABLE_SLOTS/static_cast<std::size_t>(data.element->width)||
       block->slots.size()!=data.capacity)throw std::invalid_argument("Invalid buffer state");
    for(std::size_t i=0;i<data.length;++i) {
        check_element(data.element,block->slots[i]);
        if(data.element->abi==OBJECT_VALUE&&!owns(*block->slots[i]))throw std::invalid_argument("Buffer element is not owned");
        if(data.element->kind==3) {
            auto view=of(block->slots[i]);
            if(!view->is_view||view->state!=slot_block::status::live)throw std::invalid_argument("Invalid buffer class view");
            for(std::size_t j=0;j<view->slots.size();++j)
                if(view->slots[j].get()!=&data.slab[i*static_cast<std::size_t>(data.element->width)+j]||view->slots[j]->container!=view.get())
                    throw std::invalid_argument("Invalid buffer slab view");
        }
    }
    for(std::size_t i=data.length;i<data.capacity;++i)
        if(block->slots[i]->type!=VOID_VALUE)throw std::invalid_argument("Unused buffer element contains a value");
}
namespace {
void check_operation_alive(const B& block) {
    if(block->pending_finalize||block->state!=slot_block::status::live)throw std::runtime_error("Buffer owner ended during an active operation");
}
void clear_element(const B& buffer,std::size_t index,const E& env,std::exception_ptr& failure,bool user=true) {
    auto storage=buffer->slots[index];
    if(owns(*storage))finalize_into(of(storage),env,user,failure);
    storage->setValue(nullptr);
}
void initialize_element(const B& buffer,std::size_t index,const V& value,const E& env) {
    auto& data=*buffer->buffer;check_element(data.element,value);
    if(data.element->kind==3) {
        auto source=of(value);depth_limit(buffer->depth+1+height(*source));
        auto view=create_view(buffer,index);
        try {
            if(source->temp_frame&&!source->owner) {
                // Intrinsic arguments were materialized exactly once. Relocate
                // their payload into the slab without a second value copy.
                view->destructor=source->destructor;view->destructor_contexts.swap(source->destructor_contexts);
                for(std::size_t i=0;i<source->slots.size();++i)move_storage(source->slots[i],view->slots[i]);
                release_temporary(*source);source->state=slot_block::status::dead;source->slots.clear();
                set_depth(*view,view->depth);
            } else {
                view->destructor_contexts=source->destructor_contexts;
                copy_fields(source,view,view->depth,env);view->destructor=source->destructor;
            }
        } catch(...) {
            auto failure=std::current_exception();clear_element(buffer,index,env,failure);std::rethrow_exception(failure);
        }
    } else store(buffer->slots[index],value,env);
}
void default_element(const B& buffer,std::size_t index,const E& env) {
    auto context=buffer->buffer->element;auto storage=buffer->slots[index];
    if(context->kind==3) {
        if(!context->placement_id)throw std::runtime_error("Buffer element has no default constructor");
        auto view=create_view(buffer,index);
        try {
            auto script=buffer->script_owner.lock();
            invoke_bound_function(script,*context->placement_id,env,{std::make_shared<variable>(raw_address(*view))},context->placement_contexts);
            check_operation_alive(buffer);
        } catch(...) {
            // The placement wrapper binds its destructor only after successful
            // construction. Preserve the construction error during rollback.
            auto failure=std::current_exception();clear_element(buffer,index,env,failure);std::rethrow_exception(failure);
        }
        return;
    }
    if(context->kind==4) {
        auto child=make_buffer(context->element,buffer->script_owner,0);child->depth=buffer->depth+1;
        depth_limit(child->depth);child->owner=storage.get();storage->setValue(child);return;
    }
    switch(context->abi) {
    case INT_VALUE:storage->setValue(0);break;
    case STRING_VALUE:storage->setValue(std::string());break;
    case FLOAT_VALUE:storage->setValue(0.0f);break;
    case DOUBLE_VALUE:storage->setValue(0.0);break;
    case BOOLEAN_VALUE:storage->setValue(false);break;
    case ADDRESS_VALUE:storage->setValue(address{});break;
    default:throw std::runtime_error("Invalid buffer element type");
    }
}
void reserve_buffer(const B& buffer,std::size_t capacity) {
    auto& data=*buffer->buffer;if(capacity<=data.capacity)return;
    const auto width=static_cast<std::size_t>(data.element->width);
    if(capacity>MAX_VARIABLE_SLOTS/width)throw std::invalid_argument("Buffer exceeds the slot limit");
    charge(buffer,capacity*width);
    auto candidate=make_buffer(data.element,buffer->script_owner,capacity);candidate->depth=buffer->depth;
    if(data.element->kind==3)for(std::size_t i=0;i<data.length;++i)create_view(candidate,i);
    // All allocation and checking is finished; commit contains no user code or allocation.
    for(std::size_t i=0;i<data.length;++i) {
        if(data.element->kind==3) {
            auto old=of(buffer->slots[i]);auto next=of(candidate->slots[i]);
            next->destructor=old->destructor;next->destructor_contexts.swap(old->destructor_contexts);
            for(std::size_t j=0;j<width;++j)move_storage(old->slots[j],next->slots[j]);
            old->state=slot_block::status::dead;old->owner=nullptr;old->slots.clear();
        } else move_storage(buffer->slots[i],candidate->slots[i]);
    }
    candidate->buffer->length=data.length;
    buffer->slots.swap(candidate->slots);buffer->buffer.swap(candidate->buffer);
    for(auto& element:buffer->slots)element->container=buffer.get();
    abandon_contents(candidate);candidate->state=slot_block::status::dead;
}
}
std::shared_ptr<variable> buffer_operation(int operation,const std::shared_ptr<slot_block>& block,
    const std::vector<std::shared_ptr<variable>>& arguments,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());require_buffer(block);
    static constexpr std::size_t counts[]{0,0,1,1,2,1,1};
    if(operation<0||operation>6||arguments.size()!=counts[operation])throw std::invalid_argument("Invalid buffer operation arguments");
    if(operation<2)return std::make_shared<variable>(static_cast<int>(operation?block->buffer->capacity:block->buffer->length));
    if(block->active_operations)throw std::runtime_error("Buffer is already in an active operation");
    ++block->active_operations;V result=std::make_shared<variable>(nullptr);std::exception_ptr failure;
    try {
        std::size_t index=0;
        if(operation!=5) {
            if(!arguments[0]||arguments[0]->type!=INT_VALUE)throw std::runtime_error("Buffer index or size must be int");
            const int signed_index=*static_cast<int*>(arguments[0]->value);
            if(signed_index<0)throw std::out_of_range("Buffer index or size is negative");
            index=static_cast<std::size_t>(signed_index);
        }
        if(operation==2)reserve_buffer(block,index);
        else if(operation==3) {
            if(index>=block->buffer->length)throw std::out_of_range("Buffer index is out of range");
            if(env&&env->frame)result=materialize(block->slots[index],env);
            else if(auto child=of(block->slots[index]))result=std::make_shared<variable>(copy_block(child,0,env));
            else result=block->slots[index]->deepCopy();
        } else if(operation==4) {
            if(index>=block->buffer->length)throw std::out_of_range("Buffer index is out of range");
            check_element(block->buffer->element,arguments[1]);store(block->slots[index],arguments[1],env);
        } else if(operation==5) {
            auto& data=*block->buffer;
            if(data.length==data.capacity)throw std::out_of_range("Buffer capacity is insufficient");
            charge(block,static_cast<std::size_t>(data.element->width));
            initialize_element(block,data.length,arguments[0],env);check_operation_alive(block);++data.length;
        } else {
            auto& data=*block->buffer;
            if(index>data.capacity)throw std::out_of_range("Buffer capacity is insufficient");
            const auto old=data.length;
            if(index>old) {
                charge(block,(index-old)*static_cast<std::size_t>(data.element->width));
                try {
                    while(data.length<index){default_element(block,data.length,env);++data.length;}
                } catch(...) {
                    auto error=std::current_exception();
                    while(data.length>old){--data.length;clear_element(block,data.length,env,error);}
                    std::rethrow_exception(error);
                }
            } else {
                data.length=index;std::exception_ptr error;
                for(auto i=old;i-->index;)clear_element(block,i,env,error);
                if(error)std::rethrow_exception(error);
            }
        }
        check_operation_alive(block);
    }catch(...){failure=std::current_exception();}
    --block->active_operations;
    if(block->pending_finalize) {
        const bool user=block->pending_destructor;block->pending_finalize=false;block->pending_destructor=false;
        finalize_into(block,env,user,failure);
    }
    if(failure)std::rethrow_exception(failure);
    return result;
}
int compare_value(const std::shared_ptr<const type_context>& context,const std::shared_ptr<variable>& left,
                  const std::shared_ptr<variable>& right,const std::shared_ptr<environment>&) {
    if(!context||!left||!right||left->type!=context->abi||right->type!=context->abi)
        throw std::runtime_error("Comparison value type mismatch");
    const auto compare=[](auto a,auto b){return a<b?-1:a>b?1:0;};
    switch(context->abi) {
    case INT_VALUE:return compare(*static_cast<int*>(left->value),*static_cast<int*>(right->value));
    case BOOLEAN_VALUE:return compare(*static_cast<bool*>(left->value),*static_cast<bool*>(right->value));
    case ADDRESS_VALUE:return compare(*static_cast<address*>(left->value),*static_cast<address*>(right->value));
    case STRING_VALUE: {
        const auto& a=*static_cast<std::string*>(left->value);const auto& b=*static_cast<std::string*>(right->value);
        const int result=std::memcmp(a.data(),b.data(),std::min(a.size(),b.size()));
        return result<0?-1:result>0?1:compare(a.size(),b.size());
    }
    case FLOAT_VALUE: {
        const auto a=*static_cast<float*>(left->value),b=*static_cast<float*>(right->value);
        if(!std::isfinite(a)||!std::isfinite(b))throw std::runtime_error("Cannot compare non-finite values");
        return compare(a,b);
    }
    case DOUBLE_VALUE: {
        const auto a=*static_cast<double*>(left->value),b=*static_cast<double*>(right->value);
        if(!std::isfinite(a)||!std::isfinite(b))throw std::runtime_error("Cannot compare non-finite values");
        return compare(a,b);
    }
    default:throw std::runtime_error("This type requires a custom comparison function");
    }
}
void resize(const std::shared_ptr<slot_block>& block,std::size_t size) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    require_usable(block);
    if(block->buffer||block->is_view)throw std::runtime_error("Use buffer operations to resize buffer storage");
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
    if(block->buffer)throw std::runtime_error("Buffer does not expose raw slot storage");
    const auto offset=pointer.value&offset_mask;
    if(offset>=block->slots.size())throw std::out_of_range("Object slot is out of range: "+std::to_string(pointer.value));
    return block->slots[offset];
}
address member_address(address pointer,int offset) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=resolve(pointer);
    if(block->buffer)throw std::runtime_error("Buffer does not expose class member storage");
    if((pointer.value&offset_mask)!=0)throw std::out_of_range("Invalid or freed object address: "+std::to_string(pointer.value));
    if(offset<0||static_cast<std::size_t>(offset)>=block->slots.size())throw std::out_of_range("Object member offset out of range");
    return raw_address(*block,static_cast<std::uint64_t>(offset));
}
void set_destructor(address pointer,std::optional<int> destructor,bool manual,const std::shared_ptr<environment>& env,type_contexts contexts) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto block=resolve(pointer);
    if(block->buffer)throw std::runtime_error("Buffer lifetime is managed internally");
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
void store(const std::shared_ptr<variable>& target,const std::shared_ptr<variable>& value,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!target||!value)throw std::invalid_argument("Null variable");
    if(auto old=handle_of(*target);old&&old->buffer)require_buffer(old);
    if(target==value)return;
    auto source=handle_of(*value);
    // A scalar write releases an owned object without user code (only
    // hand-written exec can do this; the compiler keeps objects typed).
    if(!source){target->copy_from(value);return;}
    require_usable(source);
    if(owns(*target)) {
        auto existing=handle_of(*target);
        if(existing==source)return;
        if(existing->buffer||source->buffer) {
            if(!existing->buffer||!source->buffer||!same_element(existing->buffer->element,source->buffer->element))
                throw std::runtime_error("Buffer assignment type mismatch");
            if(existing->active_operations)throw std::runtime_error("Buffer is already in an active operation");
            // Retain the enclosing storage while old element destructors call
            // back into the script. They may end its lifetime, but cannot make
            // the container pointers used below dangle.
            std::vector<B> ancestors;
            for(auto parent=target->container;parent;parent=parent->owner?parent->owner->container:nullptr)
                ancestors.push_back(lookup(raw_address(*parent)));
            auto replacement_value=std::make_shared<variable>(nullptr);
            auto replacement=copy_block(source,base_depth(*target),env);
            try {replacement_value->setValue(replacement);}
            catch(...) {auto failure=std::current_exception();finalize_into(replacement,env,true,failure);std::rethrow_exception(failure);}
            auto owner_env=existing->owner_env;
            // Keep the old handle visible until its cleanup finishes. No
            // allocation or user code is needed to publish the prepared value.
            std::exception_ptr failure;finalize_into(existing,env,true,failure);
            const bool available=handle_of(*target)==existing&&std::all_of(ancestors.begin(),ancestors.end(),[](const B& parent) {
                return parent&&parent->state==slot_block::status::live&&!parent->pending_finalize;
            });
            if(available) {
                replacement->owner=target.get();replacement->owner_env=owner_env;
                if(owner_env) {
                    auto& list=const_cast<environment*>(owner_env)->owned_pointer;
                    auto found=std::find(list.begin(),list.end(),raw_address(*existing));
                    if(found!=list.end())*found=raw_address(*replacement);
                }
                target->setValue(nullptr);move_storage(replacement_value,target);
            } else {
                if(!failure)try {throw std::runtime_error("Buffer owner ended during assignment");}catch(...) {failure=std::current_exception();}
                finalize_into(replacement,env,true,failure);
            }
            if(failure)std::rethrow_exception(failure);
        } else assign_block(*existing,source,env);
        return;
    }
    const auto depth=base_depth(*target);
    depth_limit(depth+height(*source));
    B placed;
    if(source->temp_frame&&!source->owner&&!encloses(source.get(),*target)) {
        release_temporary(*source);placed=source;set_depth(*placed,depth);
    } else placed=copy_block(source,depth,env);
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
    auto copy=copy_block(block,0,env);adopt_temporary(copy,frame);
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
    } else out=copy_block(block,0,env);
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
    // A destructor callback can end the owning storage. The active finalizer
    // retains the block and must finish traversing its slots itself.
    if(block->state==slot_block::status::destroying){block->owner=nullptr;block->owner_env=nullptr;return;}
    if(block->buffer&&block->active_operations){block->pending_finalize=true;block->owner=nullptr;block->owner_env=nullptr;return;}
    block->state=slot_block::status::dead;
    release_temporary(*block);block->owner=nullptr;block->owner_env=nullptr;
    abandon_contents(block);
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
