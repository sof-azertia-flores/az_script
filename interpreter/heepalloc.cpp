#include "heepalloc.h"
#include <algorithm>
#include <map>
#include <exception>
#include <set>
#include <stdexcept>
namespace azertian {
namespace heap {
namespace {
std::vector<std::shared_ptr<variable>> slots{std::make_shared<variable>(nullptr)};
// Always sorted by startpos: alloc inserts in order, validate sorts restored ranges
// and free erases in place. Lookups can therefore use binary search.
std::vector<heap_allocation> allocations;
// Lower bound on the number of leading allocations packed without gaps from
// slot 1. First-fit allocation can skip them, so appending is O(1).
std::size_t packed=0;
// startpos -> environment that releases the allocation when it exits.
std::map<address,const environment*> owners;
struct registered_object {object_record value;bool destroying=false;};
std::map<address,registered_object> objects;
void check_not_destroying(address pointer) {
    auto it=objects.find(pointer);
    if(it!=objects.end()&&it->second.destroying)throw std::runtime_error("Object is being destroyed");
}
void detach_owner(address pointer) {
    auto it=owners.find(pointer);
    if(it==owners.end())return;
    auto& list=const_cast<environment*>(it->second)->owned_pointer;
    list.erase(std::remove(list.begin(),list.end(),pointer),list.end());
    owners.erase(it);
}
// The allocation whose range contains pointer, or allocations.end().
std::vector<heap_allocation>::iterator containing(address pointer) {
    auto it=std::upper_bound(allocations.begin(),allocations.end(),pointer,
        [](address value,const heap_allocation& a){return value<a.startpos;});
    if(it==allocations.begin())return allocations.end();
    --it;
    return pointer.value-it->startpos.value<static_cast<std::uint64_t>(it->len)?it:allocations.end();
}
bool is_allocation_start(address pointer) {
    auto it=containing(pointer);
    return it!=allocations.end()&&it->startpos==pointer;
}
std::uint64_t packed_end() {
    return packed?allocations[packed-1].startpos.value+allocations[packed-1].len:1;
}
void extend_packed() {
    while(packed<allocations.size()&&allocations[packed].startpos.value==packed_end())++packed;
}
void validate(const std::vector<std::shared_ptr<variable>>& values,std::vector<heap_allocation>& ranges) {
    if(values.empty()||values.size()>static_cast<std::size_t>(max_slots)) throw std::invalid_argument("Invalid heap size");
    for(auto& v:values) if(!v) throw std::invalid_argument("Null heap slot");
    if(values[0]->type!=VOID_VALUE) throw std::invalid_argument("Heap slot zero is reserved");
    std::sort(ranges.begin(),ranges.end(),[](auto a,auto b){return a.startpos<b.startpos;});
    std::uint64_t end=1;
    for(auto a:ranges) {
        if(a.len<=0||a.startpos.value<end||a.startpos.value>values.size()||static_cast<std::uint64_t>(a.len)>values.size()-a.startpos.value)
            throw std::invalid_argument("Invalid or overlapping heap allocation");
        end=a.startpos.value+a.len;
    }
}
void validate_object(const object_record& record) {
    auto script=record.script_owner.lock();
    if(!script||script->closed)throw std::invalid_argument("Object belongs to an unavailable script");
    if(!record.destructor_id)return;
    auto it=script->functions.find(*record.destructor_id);
    auto destructor=it==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(it->second);
    if(!destructor||destructor->rett!=VOID_VALUE||destructor->param_count!=1||
       destructor->param_types!=std::vector<int>{ADDRESS_VALUE})
        throw std::invalid_argument("Object destructor must be a script function void(address)");
}
}
void restore(std::vector<std::shared_ptr<variable>> values,std::vector<heap_allocation> ranges) {
    restore(std::move(values),std::move(ranges),{},nullptr,{});
}
void restore(std::vector<std::shared_ptr<variable>> values,std::vector<heap_allocation> ranges,
             std::vector<object_record> records,std::shared_ptr<environment> restored_owner,std::vector<address> owned) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    for(const auto& [pointer,object]:objects)if(object.destroying)throw std::runtime_error("Cannot restore during object destruction");
    validate(values,ranges);
    std::map<address,const environment*> restored_owners;
    for(address pointer:owned) {
        if(!restored_owner||restored_owner->closing||
           std::none_of(ranges.begin(),ranges.end(),[&](auto a){return a.startpos==pointer;})||
           !restored_owners.emplace(pointer,restored_owner.get()).second)
            throw std::invalid_argument("Invalid restored allocation ownership");
    }
    std::map<address,registered_object> restored_objects;
    for(auto& record:records) {
        validate_object(record);
        if(std::none_of(ranges.begin(),ranges.end(),[&](auto a){return a.startpos==record.startpos;})||
           (record.manual&&restored_owners.contains(record.startpos))||
           !restored_objects.emplace(record.startpos,registered_object{record}).second)
            throw std::invalid_argument("Invalid restored object registration");
        if(restored_owner&&record.script_owner.lock()!=restored_owner->script.lock())
            throw std::invalid_argument("Restored object belongs to another script");
    }
    slots.swap(values);allocations.swap(ranges);owners.swap(restored_owners);objects.swap(restored_objects);
    packed=0;extend_packed();
    if(restored_owner)restored_owner->owned_pointer.swap(owned);
}
void set_owner(address pointer,const environment* owner) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    check_not_destroying(pointer);
    if(owner&&owner->closing)throw std::runtime_error("Cannot transfer an allocation into a closing scope");
    if(!owner){owners.erase(pointer);return;}
    if(!is_allocation_start(pointer)) throw std::invalid_argument("Pointer is not the start of an active allocation: "+std::to_string(pointer.value));
    owners[pointer]=owner;
}
const environment* owner_of(address pointer) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto it=owners.find(pointer);
    return it==owners.end()?nullptr:it->second;
}
std::shared_ptr<variable> getSlot(int index) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(index<0||static_cast<std::size_t>(index)>=slots.size()) throw std::out_of_range("Heap slot out of range");
    return slots[index];
}
std::shared_ptr<variable> getAt(address pointer) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(containing(pointer)!=allocations.end())return slots[static_cast<std::size_t>(pointer.value)];
    throw std::out_of_range("Invalid or freed heap pointer: "+std::to_string(pointer.value));
}
heap_allocation allocAt(int index) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(index<0||static_cast<std::size_t>(index)>=allocations.size()) throw std::out_of_range("Allocation index out of range");
    return allocations[index];
}
void send_alloc(heap_allocation a) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto candidate=allocations;candidate.push_back(a);validate(slots,candidate);allocations.swap(candidate);
    packed=0;extend_packed();
}
void clearHeap() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    for(const auto& [pointer,object]:objects)if(object.destroying)throw std::runtime_error("Cannot clear heap during object destruction");
    std::vector<std::shared_ptr<variable>> empty{std::make_shared<variable>(nullptr)};
    slots.swap(empty);allocations.clear();packed=0;owners.clear();objects.clear();
}
void resize_heap(int size) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(size<1||size>max_slots)throw std::invalid_argument("Invalid heap size");
    if(!allocations.empty()&&static_cast<std::uint64_t>(size)<allocations.back().startpos.value+allocations.back().len)throw std::invalid_argument("Cannot shrink active heap");
    auto candidate=slots;
    while(candidate.size()<static_cast<std::size_t>(size))candidate.push_back(std::make_shared<variable>(nullptr));
    candidate.resize(size);slots.swap(candidate);
}
int lenHeap(){std::lock_guard<std::recursive_mutex> lock(runtime_mutex());return static_cast<int>(slots.size());}
int lenAlloc(){std::lock_guard<std::recursive_mutex> lock(runtime_mutex());return static_cast<int>(allocations.size());}
address alloc(int size) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(size<0||size>=max_slots)throw std::invalid_argument("Invalid allocation size");
    if(size==0)return address{};
    // First fit: the lowest gap large enough. The packed prefix has no gaps.
    std::size_t index=packed;
    int start=static_cast<int>(packed_end());
    for(;index<allocations.size();++index) {
        if(allocations[index].startpos.value-static_cast<std::uint64_t>(start)>=static_cast<std::uint64_t>(size))break;
        start=static_cast<int>(allocations[index].startpos.value)+allocations[index].len;
    }
    if(static_cast<std::int64_t>(start)+size>max_slots)throw std::runtime_error("Heap capacity exceeded");
    // Allocate everything that can throw before publishing the range, so a failure
    // cannot leave a partial allocation; insertion into reserved capacity cannot throw.
    allocations.reserve(allocations.size()+1);
    std::vector<std::shared_ptr<variable>> fresh;
    fresh.reserve(size);
    for(int i=0;i<size;++i)fresh.push_back(std::make_shared<variable>(nullptr));
    if(slots.size()<static_cast<std::size_t>(start+size))slots.resize(start+size);
    for(int i=0;i<size;++i)slots[start+i]=std::move(fresh[i]);
    allocations.insert(allocations.begin()+static_cast<std::ptrdiff_t>(index),heap_allocation{address{static_cast<std::uint64_t>(start)},size});
    extend_packed();
    return address{static_cast<std::uint64_t>(start)};
}
int resize_heap() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    const auto size=allocations.empty()?1:static_cast<int>(allocations.back().startpos.value)+allocations.back().len;
    slots.resize(size);return size;
}
bool free(address pointer) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    check_not_destroying(pointer);
    auto it=containing(pointer);
    if(it==allocations.end()||it->startpos!=pointer)return false;
    // Existing host references may survive, but can no longer be reached by an address.
    for(int i=0;i<it->len;++i) slots[static_cast<std::size_t>(pointer.value)+static_cast<std::size_t>(i)]->setValue(nullptr);
    packed=std::min(packed,static_cast<std::size_t>(it-allocations.begin()));
    allocations.erase(it);detach_owner(pointer);objects.erase(pointer);resize_heap();return true;
}
std::vector<object_record> object_records() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    std::vector<object_record> result;
    for(const auto& [pointer,object]:objects) {
        if(object.destroying)throw std::runtime_error("Cannot snapshot an object during destruction");
        result.push_back(object.value);
    }
    return result;
}
address object_address(address pointer,int offset) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto it=containing(pointer);
    if(it!=allocations.end()&&it->startpos==pointer) {
        if(offset<0||offset>=it->len)throw std::out_of_range("Object member offset out of range");
        return address{pointer.value+static_cast<std::uint64_t>(offset)};
    }
    throw std::out_of_range("Invalid or freed object address: "+std::to_string(pointer.value));
}
void register_object(address pointer,std::optional<int> destructor_id,bool manual,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(!env||env->closing||owner_of(pointer)!=env.get()||!is_allocation_start(pointer))
        throw std::runtime_error("Object construction must finish in its allocation scope");
    object_record record{pointer,destructor_id,manual,env->script};validate_object(record);
    if(!objects.emplace(pointer,registered_object{record}).second)throw std::runtime_error("Object is already registered");
}
namespace {
void destroy_registered(address pointer,const std::shared_ptr<environment>& env) {
    auto it=objects.find(pointer);
    if(it==objects.end()){free(pointer);return;}
    check_not_destroying(pointer);
    const object_record record=it->second.value;
    it->second.destroying=true;
    std::exception_ptr failure;
    try {
        if(record.destructor_id) {
            auto script=record.script_owner.lock();
            if(!script||script->closed)throw std::runtime_error("Object destructor script is unavailable");
            auto context=env;
            if(!context||context->script.lock()!=script) {
                context=std::make_shared<environment>();context->script=script;
                context->parent=script->baseEnv;context->caller=env;
            }
            script->getFunction(*record.destructor_id)->invoke(context,{std::make_shared<variable>(pointer)});
        }
    } catch(...) {failure=std::current_exception();}
    // No registry iterator survives the script callback: it may delete other objects.
    objects.erase(pointer);
    free(pointer);
    if(failure)std::rethrow_exception(failure);
}
}
void delete_object(address pointer,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(pointer.value==0)return;
    auto it=objects.find(pointer);
    if(it==objects.end())throw std::runtime_error("delete requires a live object");
    check_not_destroying(pointer);
    if(!it->second.value.manual)throw std::runtime_error("Cannot delete an automatic object");
    destroy_registered(pointer,env);
}
void release_owned(address pointer,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(owner_of(pointer)!=env.get())return;
    destroy_registered(pointer,env);
}
void return_object(address pointer,const std::shared_ptr<environment>& env) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(pointer.value==0)return;
    auto object=objects.find(pointer);
    if(!is_allocation_start(pointer))throw std::runtime_error("Object return requires a live allocation");
    const environment* owner=owner_of(pointer);
    std::shared_ptr<environment> holder,root=env;
    for(auto e=env;e&&e->frame==env->frame;e=e->parent.lock()) {
        root=e;if(e.get()==owner)holder=e;
    }
    if(object==objects.end()) {
        // A constructor may call a method returning its borrowed this pointer.
        // The allocating function cannot export a not-yet-constructed object.
        if(holder)throw std::runtime_error("Cannot return an object before construction is complete");
        return;
    }
    if(object->second.value.manual)return;
    if(!holder)return; // A borrowed object retains its original, longer-lived owner.
    check_not_destroying(pointer);
    auto caller=root->caller.lock();
    if(!caller||caller->closing)throw std::runtime_error("Cannot return an object into a closing or missing caller scope");
    caller->owned_pointer.push_back(pointer);
    try {set_owner(pointer,caller.get());}catch(...){caller->owned_pointer.pop_back();throw;}
    holder->owned_pointer.erase(std::remove(holder->owned_pointer.begin(),holder->owned_pointer.end(),pointer),holder->owned_pointer.end());
}
void discard_owned(const environment* env) noexcept {
    try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        auto& list=const_cast<environment*>(env)->owned_pointer;
        while(!list.empty()) {
            address pointer=list.back();list.pop_back();
            if(owner_of(pointer)!=env)continue;
            objects.erase(pointer);free(pointer);
        }
    }catch(...) {}
}
void discard_script_objects(const azertian::script* script_owner) noexcept {
    try {
        std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
        for(auto it=objects.begin();it!=objects.end();) {
            const address pointer=it->first;
            if(it->second.value.script_owner.lock().get()!=script_owner){++it;continue;}
            // Load failure is state disposal, not a source-language delete.
            it=objects.erase(it);free(pointer);
        }
    }catch(...) {}
}
}
namespace {
int size_argument(const std::vector<std::shared_ptr<variable>>& args) {
    if(args.size()!=1||!args[0]||args[0]->type!=INT_VALUE)throw std::invalid_argument("alloc requires one int argument");
    return *static_cast<int*>(args[0]->value);
}
address pointer_argument(const std::vector<std::shared_ptr<variable>>& args) {
    if(args.size()!=1||!args[0]||args[0]->type!=ADDRESS_VALUE)throw std::invalid_argument("Memory function requires one address argument");
    return *static_cast<address*>(args[0]->value);
}
std::shared_ptr<environment> transfer_parent(const std::shared_ptr<environment>& env) {
    if(auto caller=env->caller.lock())return caller;
    return env->parent.lock();
}
}
int f_heepalloc::return_type(){return ADDRESS_VALUE;}
int f_free::return_type(){return BOOLEAN_VALUE;}
int f_make_free::return_type(){return VOID_VALUE;}
int f_send_up::return_type(){return VOID_VALUE;}
int f_get::return_type(){return ANY_VALUE;}
std::shared_ptr<variable> f_heepalloc::invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args) {
    if(!env||env->closing)throw std::invalid_argument("Missing or closing allocation scope");
    address p=heap::alloc(size_argument(args));
    try {if(p.value!=0){env->owned_pointer.push_back(p);heap::set_owner(p,env.get());}}catch(...){heap::free(p);throw;}
    return std::make_shared<variable>(p);
}
std::shared_ptr<variable> f_free::invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args) {
    address p=pointer_argument(args);
    const environment* owner=heap::owner_of(p);
    if(!owner) return std::make_shared<variable>(heap::free(p));
    // The owner must be reachable from the current scope through lexical
    // parents and callers. A re-entrant host call or a foreign scope cannot
    // release an allocation that another live scope will release itself.
    std::shared_ptr<environment> holder;
    for(auto e=env;e;e=transfer_parent(e)) if(e.get()==owner){holder=e;break;}
    if(!holder) throw std::runtime_error("Allocation "+std::to_string(p.value)+
        " is scheduled for automatic release by another active scope and cannot be freed here");
    bool released=heap::free(p);
    if(released) holder->owned_pointer.erase(
        std::remove(holder->owned_pointer.begin(),holder->owned_pointer.end(),p),holder->owned_pointer.end());
    return std::make_shared<variable>(released);
}
std::shared_ptr<variable> f_make_free::invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args) {
    address p=pointer_argument(args);
    if(!env)throw std::invalid_argument("Missing allocation scope");
    auto it=std::find(env->owned_pointer.begin(),env->owned_pointer.end(),p);
    if(it==env->owned_pointer.end())throw std::runtime_error("Allocation is not owned by current scope");
    heap::set_owner(p,nullptr);env->owned_pointer.erase(it);return std::make_shared<variable>(nullptr);
}
std::shared_ptr<variable> f_send_up::invoke(std::shared_ptr<environment> env,std::vector<std::shared_ptr<variable>> args) {
    address p=pointer_argument(args);
    if(!env)throw std::invalid_argument("Missing allocation scope");
    auto parent=transfer_parent(env);
    if(!parent)throw std::runtime_error("Allocation scope has no parent");
    auto it=std::find(env->owned_pointer.begin(),env->owned_pointer.end(),p);
    if(it==env->owned_pointer.end())throw std::runtime_error("Allocation is not owned by current scope");
    parent->owned_pointer.push_back(p);
    try {heap::set_owner(p,parent.get());}catch(...){parent->owned_pointer.pop_back();throw;}
    env->owned_pointer.erase(it);
    return std::make_shared<variable>(nullptr);
}
std::shared_ptr<variable> f_get::invoke(std::shared_ptr<environment>,std::vector<std::shared_ptr<variable>> args) {
    return heap::getAt(pointer_argument(args));
}
}
