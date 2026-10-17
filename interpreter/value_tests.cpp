#include "library.h"
#include "blocks.h"
#include <bit>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>

namespace {
thread_local bool count_allocations=false;
thread_local std::size_t allocations=0;
thread_local std::ptrdiff_t fail_after=-1;
}
void* operator new(std::size_t size) {
    if(fail_after==0){fail_after=-1;throw std::bad_alloc();}
    if(fail_after>0)--fail_after;
    if(count_allocations)++allocations;
    if(void* value=std::malloc(size?size:1))return value;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size){return ::operator new(size);}
void operator delete(void* value) noexcept {std::free(value);}
void operator delete[](void* value) noexcept {std::free(value);}
void operator delete(void* value,std::size_t) noexcept {std::free(value);}
void operator delete[](void* value,std::size_t) noexcept {std::free(value);}

namespace {
using namespace azertian;
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
void scalar_values() {
    variable stored;
    allocations=0;count_allocations=true;
    for(int i=0;i<10000;++i) {
        stored.setValue(i);variable copy(stored);stored=copy;
        require(stored.get<int>()==i,"inline integer copy changed its value");
        stored.setValue(std::numeric_limits<int>::min());
        require(stored.get<int>()==std::numeric_limits<int>::min(),"integer lower bound changed");
        stored.setValue(address{std::numeric_limits<std::uint64_t>::max()});
        require(stored.get<address>().value==std::numeric_limits<std::uint64_t>::max(),"address lost high bits");
        stored.setValue(-0.0f);
        require(std::bit_cast<std::uint32_t>(stored.get<float>())==0x80000000u,"float negative zero changed");
        stored.setValue(-0.0);
        require(std::bit_cast<std::uint64_t>(stored.get<double>())==0x8000000000000000ull,"double negative zero changed");
        stored.setValue(true);require(stored.get<bool>(),"boolean changed");
        stored.setValue(nullptr);require(stored.type_tag()==VOID_VALUE,"void has an incorrect tag");
        Value value(address{42}),moved(std::move(value));
        require(value.type_tag()==VOID_VALUE&&moved.get<address>()==address{42},"Value move leaves invalid state");
        Value another(12);another.swap(moved);
        require(another.type_tag()==ADDRESS_VALUE&&moved.get<int>()==12,"Value swap changes tags");
    }
    count_allocations=false;
    require(allocations==0,"scalar Value/variable operations allocate payloads");
    std::cout<<"scalar-iterations=10000 allocations="<<allocations<<'\n';
    stored.setValue(42);allocations=0;count_allocations=true;
    auto snapshot=stored.deepCopy();
    count_allocations=false;
    require(allocations<=1&&snapshot->get<int>()==42,"scalar snapshot allocates beyond its variable wrapper");
    std::cout<<"scalar-deep-copy allocations="<<allocations<<'\n';
    bool rejected=false;
    try{(void)stored.get<double>();}catch(const std::invalid_argument&){rejected=true;}
    require(rejected,"checked access accepts the wrong scalar tag");
}
void string_values() {
    const std::string text=std::string("a\0",2)+"\xf0\x9f\x8c\xb8"+std::string(2000,'x');
    variable source(text);
    allocations=0;count_allocations=true;
    variable copy(source);
    count_allocations=false;
    require(allocations==0,"immutable string snapshot copies payload bytes");
    source.setValue(source.get<std::string>());
    require(source.get<std::string>()==text,"string self-aliasing replacement loses bytes");
    source.setValue(std::string("replacement"));
    require(copy.get<std::string>()==text,"string snapshot changes when its source is replaced");
    Value moved_from(text),moved(std::move(moved_from));
    require(moved_from.type_tag()==VOID_VALUE&&moved.get<std::string>()==text,"moved string has invalid state");
    variable null_text(static_cast<const char*>(nullptr));
    require(null_text.type_tag()==VOID_VALUE,"null C string does not become void");
}
void object_values() {
    auto block=blocks::create(1);
    variable owner(block);block->owner=&owner;
    variable borrowed(owner);
    require(borrowed.object_handle()==block&&block->owner==&owner,"handle copy changes script ownership");
    borrowed.setValue(nullptr);
    require(block->state==slot_block::status::live,"clearing borrowed handle destroys owner");
    block->slots[0]->setValue(std::string(2000,'q'));
    // The source field expires when the old owning value is replaced.
    owner.setValue(block->slots[0]->get<std::string>());
    require(owner.get<std::string>()==std::string(2000,'q')&&block->state==slot_block::status::dead,
            "replacement fails to snapshot a field before abandoning its owner");

    auto container=blocks::create(1);variable from(7),to(address{9});
    from.name="from";to.name="to";from.container=container.get();
    from.swap_storage(to);
    require(from.get<address>()==address{9}&&to.get<int>()==7&&from.name=="from"&&to.name=="to"&&
            from.container==container.get()&&to.container==nullptr,"storage swap changes slot identity");
    variable null_handle(std::shared_ptr<slot_block>{});
    require(null_handle.type_tag()==OBJECT_VALUE&&!null_handle.object_handle(),"null object handle loses its tag");
}
void strong_replacement() {
    const std::string replacement(2000,'r');
    std::size_t failures=0;bool succeeded=false;
    for(std::ptrdiff_t failure=0;failure<16;++failure) {
        auto block=blocks::create(1);variable destination(block);block->owner=&destination;
        fail_after=failure;bool failed=false;
        try{destination.setValue(replacement);}catch(const std::bad_alloc&){failed=true;}
        catch(...){fail_after=-1;throw;}
        fail_after=-1;
        if(failed) {
            ++failures;
            require(destination.object_handle()==block&&block->owner==&destination&&
                    block->state==slot_block::status::live,"failed string replacement abandons previous object");
        } else {
            require(destination.get<std::string>()==replacement&&block->state==slot_block::status::dead,
                    "successful string replacement does not release previous object");
            succeeded=true;break;
        }
    }
    require(failures>0&&succeeded,"string replacement fault sweep missed failure or success");
    variable source(123);source.name=std::string(2000,'n');
    auto block=blocks::create(1);variable destination(block);block->owner=&destination;destination.name="original";
    fail_after=0;bool failed=false;
    try{destination=source;}catch(const std::bad_alloc&){failed=true;}
    catch(...){fail_after=-1;throw;}
    fail_after=-1;
    require(failed&&destination.object_handle()==block&&destination.name=="original"&&
            block->state==slot_block::status::live,"failed named copy assignment changes the destination");
    destination=source;
    require(destination.get<int>()==123&&destination.name==source.name&&block->state==slot_block::status::dead,
            "successful named copy assignment does not commit correctly");
    std::cout<<"replacement injected-failures="<<failures+1<<" prior-state-preserved=true\n";
}
}
int main() {
    try {
        std::cout<<"sizeof(Value)="<<sizeof(azertian::Value)<<" sizeof(variable)="<<sizeof(azertian::variable)<<'\n';
        scalar_values();string_values();object_values();strong_replacement();return 0;
    }catch(const std::exception& error) {
        count_allocations=false;fail_after=-1;
        std::cerr<<"Value regression failed: "<<error.what()<<'\n';return 1;
    }
}
