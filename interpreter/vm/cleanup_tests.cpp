#include "cleanup.h"
#include <array>
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
        CleanupWatermark mark{scope_id,4,5,6,7};
        stack.enter(frame_id,region(),mark);
        auto transition=stack.body_finished(frame_id);
        expect(transition.kind==CleanupTransition::Kind::unwind&&transition.watermark.scope==scope_id&&transition.watermark.actions==6,"body cleanup watermark retained");
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
struct Events {
    std::array<int,128> values{};std::size_t count=0;
    void add(int value) noexcept {if(count==values.size())std::terminate();values[count++]=value;}
};
class Driver:public CleanupDriver {
    Events& events_;
public:
    unsigned calls=1;bool advance_error=false,return_error=false;
    explicit Driver(Events& events):events_(events) {}
    CleanupActionStep advance(CleanupActionState& state) override {
        if(state.cursor>=calls)return {};
        events_.add(static_cast<int>(state.identity*10+state.cursor));
        if(advance_error)throw Marker(20+static_cast<int>(state.cursor));
        CleanupActionStep result;result.kind=CleanupActionStep::Kind::script;
        result.call.function_id=100+static_cast<int>(state.identity);result.call.argument_count=1;
        result.call.arguments[0]=Value(address{state.identity});return result;
    }
    void returned(CleanupActionState& state,const Value&) override {
        if(return_error)throw Marker(30+static_cast<int>(state.cursor));
        ++state.cursor;
    }
    bool failed(CleanupActionState& state) noexcept override {++state.cursor;return state.cursor<calls;}
    void release(CleanupActionState& state) noexcept override {events_.add(-static_cast<int>(state.identity));}
};
CleanupActionState action(std::uint64_t identity) {CleanupActionState result;result.frame=frame_id;result.scope=scope_id;result.identity=identity;return result;}
void action_order_and_reentry() {
    WorkspaceBudget workspace(1<<20);Resource resource;Events events;Driver driver(events);
    {
        CleanupActions actions(workspace,&resource);actions.prepare_actions(4);actions.prepare_unwinds(3);resource.deny=true;
        actions.register_action(driver,action(1));actions.register_action(driver,action(2));actions.begin_unwind(0);
        auto next=actions.poll();expect(next.kind==CleanupPoll::Kind::script&&next.call.function_id==102&&next.call.arguments[0].get<address>().value==2,"last owned object yields first script call");
        misuse([&]{actions.poll();});misuse([&]{actions.begin_unwind(1);});
        // The yielded destructor's explicit frame creates a nested object and
        // unwinds it without consuming the suspended parent's action record.
        actions.register_action(driver,action(3));actions.begin_unwind(2);
        next=actions.poll();expect(next.call.function_id==103&&actions.unwind_depth()==2,"nested script frame owns independent unwind session");
        actions.returned(Value{});next=actions.poll();expect(next.kind==CleanupPoll::Kind::complete&&!next.failure,"nested unwind completes independently");
        actions.returned(Value{});next=actions.poll();expect(next.kind==CleanupPoll::Kind::script&&next.call.function_id==101,"parent cleanup resumes and continues reverse order");
        actions.returned(Value{});next=actions.poll();expect(next.kind==CleanupPoll::Kind::complete&&!next.failure&&actions.size()==0&&actions.unwind_depth()==0,"all parent actions released");
        expect(events.count==6&&events.values[0]==20&&events.values[1]==30&&events.values[2]==-3&&events.values[3]==-2&&events.values[4]==10&&events.values[5]==-1,"script and native release event order");
        expect(resource.allocations==2,"nested cleanup uses only prepared workspace");
    }
    expect(workspace.used_bytes()==0,"action storage releases complete backing charge");
}
void action_errors_and_budget() {
    WorkspaceBudget workspace(1<<20);Resource resource;Events events;Driver driver(events);driver.calls=3;
    CleanupActions actions(workspace,&resource);actions.prepare_actions(3);actions.prepare_unwinds(2);resource.deny=true;
    actions.register_action(driver,action(4));pending_error initial;const Marker* original=nullptr;
    try {throw Marker(99);}catch(const Marker& error){original=&error;initial=pending_error::capture();}
    actions.begin_unwind(0,std::move(initial));std::uint64_t remaining_steps=0;unsigned attempted=0;
    for(;;) {
        auto next=actions.poll();
        if(next.kind==CleanupPoll::Kind::complete) {
            expect(static_cast<bool>(next.failure),"body error retained through exhausted destructors");
            try {next.failure.rethrow();}catch(const Marker& error){expect(error.code==99&&&error==original,"secondary budget errors do not replace original exception");}
            break;
        }
        ++attempted;
        try {
            if(!remaining_steps)throw std::runtime_error("Script execution step limit exceeded");
            --remaining_steps;actions.returned(Value{});
        } catch(...) {actions.failed();}
    }
    expect(remaining_steps==0&&attempted==3&&events.values[events.count-1]==-4,"no cleanup budget refill; necessary native release still runs");
    driver.advance_error=true;actions.register_action(driver,action(5));actions.begin_unwind(0);
    auto next=actions.poll();expect(next.kind==CleanupPoll::Kind::complete&&static_cast<bool>(next.failure),"native traversal errors continue remaining work then release");
    try {next.failure.rethrow();}catch(const Marker& error){expect(error.code==20,"first native traversal error retained");}
    driver.advance_error=false;driver.return_error=true;actions.register_action(driver,action(6));actions.begin_unwind(0);
    for(;;) {next=actions.poll();if(next.kind==CleanupPoll::Kind::complete)break;actions.returned(Value{});}
    try {next.failure.rethrow();}catch(const Marker& error){expect(error.code==30,"first result-validation error retained");}
    expect(events.values[events.count-1]==-6,"result errors still release storage");
}
void registration_atomicity() {
    WorkspaceBudget workspace(1<<20);Resource resource;Events events;Driver driver(events);
    {
        CleanupActions actions(workspace,&resource);misuse([&]{actions.register_action(driver,action(7));});
        expect(actions.size()==0&&events.count==0,"failed unprepared registration does not publish ownership");
        resource.deny=true;try {actions.prepare_actions();}catch(const std::bad_alloc&){}
        expect(workspace.used_bytes()==0&&actions.size()==0,"allocation failure rolls back workspace reservation");
        resource.deny=false;actions.prepare_actions();actions.register_action(driver,action(7));
    }
    expect(events.count==1&&events.values[0]==-7,"emergency teardown releases native storage without script recursion");
}
}
int main() {
    try {
        compiler_flow();compiler_failures();action_order_and_reentry();action_errors_and_budget();registration_atomicity();
        std::cout<<"VM cleanup state-machine tests passed\n";return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
