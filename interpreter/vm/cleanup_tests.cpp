#include "cleanup.h"
#include <iostream>
#include <memory_resource>
#include <stdexcept>

using namespace azertian;
using namespace azertian::vm;
namespace {
void expect(bool value,const char* message) {if(!value)throw std::runtime_error(message);}
template<class Body> void misuse(Body body) {
    try {body();}catch(const std::logic_error&){return;}throw std::runtime_error("Expected cleanup protocol rejection");
}
struct Marker:std::runtime_error {int code;explicit Marker(int code):std::runtime_error("first cleanup error"),code(code) {}};
struct Resource:std::pmr::memory_resource {
    bool deny=false;std::size_t allocations=0;
    void* do_allocate(std::size_t bytes,std::size_t alignment) override {
        if(deny)throw std::bad_alloc();
        ++allocations;return std::pmr::new_delete_resource()->allocate(bytes,alignment);
    }
    void do_deallocate(void* pointer,std::size_t bytes,std::size_t alignment) override {
        std::pmr::new_delete_resource()->deallocate(pointer,bytes,alignment);
    }
    bool do_is_equal(const std::pmr::memory_resource& other) const noexcept override{return this==&other;}
};
constexpr FrameId frame_id{1,0,1};
constexpr ScopeId scope_id{2,0,1};
CleanupRegion region(bool on_error=false) {return {1,2,9,10,19,20,on_error};}
void compiler_flow() {
    WorkspaceBudget workspace(1<<20);Resource resource;
    {
        CompilerCleanupStack stack(workspace,&resource);stack.prepare(3);resource.deny=true;
        FrameRecord frame;frame.returned=true;frame.broken=true;frame.continued=true;
        frame.result=Value(7);frame.location=11;frame.return_location=12;
        CleanupWatermark mark{scope_id,4,5,7};
        stack.enter(frame_id,region(),mark);
        auto transition=stack.body_finished(frame_id);
        expect(transition.kind==CleanupTransition::Kind::unwind&&transition.watermark.scope==scope_id&&transition.watermark.statements==7,"body cleanup watermark retained");
        transition=stack.unwound(frame_id,frame);
        expect(transition.kind==CleanupTransition::Kind::finalizer&&transition.pc==10,"normal cleanup dispatches finalizer");
        expect(!frame.returned&&!frame.broken&&!frame.continued&&frame.result.get<int>()==7,"only control flags are reset");
        frame.returned=true;frame.result=Value(99);frame.location=100;frame.return_location=101;
        stack.finalizer_finished(frame_id);transition=stack.unwound(frame_id,frame);
        expect(transition.kind==CleanupTransition::Kind::resume&&transition.pc==20&&!transition.failure&&stack.size()==0,"normal cleanup resumes after region");
        expect(frame.returned&&frame.broken&&frame.continued&&frame.result.get<int>()==7&&frame.location==11&&frame.return_location==12,"finalizer restores all original flow fields");

        stack.enter(frame_id,region(true),{});stack.body_finished(frame_id);transition=stack.unwound(frame_id,frame);
        expect(transition.kind==CleanupTransition::Kind::resume&&stack.size()==0,"on-error finalizer is skipped on success");
        misuse([&]{stack.finalizer_finished(frame_id);});
        expect(resource.allocations==1,"prepared compiler cleanup performs no extra workspace allocation");
    }
    expect(workspace.used_bytes()==0,"compiler cleanup releases its backing charge");
}
void compiler_failures() {
    WorkspaceBudget workspace(1<<20);Resource resource;CompilerCleanupStack stack(workspace,&resource);stack.prepare(3);resource.deny=true;
    FrameRecord frame;frame.returned=true;frame.result=Value(42);frame.return_location=23;
    stack.enter(frame_id,region(true),{});const Marker* first=nullptr;
    frame.location=31;
    try {throw Marker(1);}catch(const Marker& error){first=&error;stack.failed(frame_id,frame);}
    frame.location=41;
    try {throw Marker(2);}catch(...){stack.failed(frame_id,frame);}
    auto transition=stack.unwound(frame_id,frame);
    expect(transition.kind==CleanupTransition::Kind::finalizer,"on-error finalizer runs after body failure");
    frame.location=51;frame.result=Value(999);frame.return_location=99;
    try {throw Marker(3);}catch(...){stack.failed(frame_id,frame);}
    transition=stack.unwound(frame_id,frame);
    expect(frame.location==31&&frame.return_location==23&&frame.result.get<int>()==42,"first body error site and original result survive later cleanup errors");
    try {transition.failure.rethrow();}catch(const Marker& error){expect(error.code==1&&&error==first,"first exception identity retained");}

    frame.location=60;stack.enter(frame_id,region(),{});stack.body_finished(frame_id);stack.unwound(frame_id,frame);
    frame.location=70;
    try {throw Marker(4);}catch(...){stack.failed(frame_id,frame);}
    transition=stack.unwound(frame_id,frame);
    expect(frame.location==70&&frame.return_location==23,"first finalizer failure site replaces successful body site");
    try {transition.failure.rethrow();}catch(const Marker& error){expect(error.code==4,"finalizer error propagated without a body error");}

    frame.result=Value(17);frame.returned=true;stack.enter(frame_id,region(),{});stack.body_finished(frame_id);stack.unwound(frame_id,frame);
    frame.result=Value(18);frame.broken=true;
    stack.enter(frame_id,region(),{});stack.body_finished(frame_id);stack.unwound(frame_id,frame);
    frame.result=Value(19);stack.finalizer_finished(frame_id);stack.unwound(frame_id,frame);
    expect(frame.result.get<int>()==18&&frame.broken&&!frame.returned,"nested finalizer restores enclosing finalizer flow");
    stack.finalizer_finished(frame_id);stack.unwound(frame_id,frame);
    expect(frame.result.get<int>()==17&&frame.returned,"outer finalizer restores original body flow");
}
}
int main() {
    try {
        compiler_flow();compiler_failures();
        std::cout<<"VM cleanup state-machine tests passed\n";return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
