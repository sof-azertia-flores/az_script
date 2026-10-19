#include "../interpreter/vm/frames.h"
#include <iostream>
#include <limits>
#include <memory_resource>
#include <stdexcept>
#include <string>
#include <type_traits>

using namespace azertian;
using namespace azertian::vm;
namespace {
int checks=0;
void check(bool condition,const char* message) {
    ++checks;if(!condition)throw std::runtime_error(message);
}
template<class Exception=std::exception,class Function>
void rejects(Function function,const char* message) {
    bool rejected=false;try{function();}catch(const Exception&){rejected=true;}
    check(rejected,message);
}
class ObservedResource final:public std::pmr::memory_resource {
    void* do_allocate(std::size_t bytes,std::size_t alignment) override {
        ++calls;if(fail)throw std::bad_alloc();
        void* result=std::pmr::new_delete_resource()->allocate(bytes,alignment);
        live_bytes+=bytes;return result;
    }
    void do_deallocate(void* pointer,std::size_t bytes,std::size_t alignment) override {
        live_bytes-=bytes;std::pmr::new_delete_resource()->deallocate(pointer,bytes,alignment);
    }
    bool do_is_equal(const std::pmr::memory_resource& other) const noexcept override{return this==&other;}
public:
    bool fail=false;
    std::size_t live_bytes=0,calls=0;
};
struct Counted {
    inline static int alive=0;
    int value=0;
    Counted() noexcept{++alive;}
    explicit Counted(int value):value(value) {
        if(value<0)throw std::runtime_error("candidate failed");
        ++alive;
    }
    Counted(Counted&& other) noexcept:value(other.value){++alive;}
    Counted(const Counted& other) noexcept:value(other.value){++alive;}
    ~Counted() noexcept{--alive;}
};
static_assert(std::is_nothrow_move_constructible_v<FrameRecord>);
static_assert(std::is_nothrow_move_constructible_v<WorkspaceVector<Value>>);
static_assert(!std::is_copy_constructible_v<admission::accounting::ticket>);

void workspace_arrays() {
    WorkspaceBudget budget(1024*1024);ObservedResource resource;
    {
        WorkspaceVector<Value> values(budget,4096,&resource);
        values.emplace_back(42);
        values.emplace_back(values[0]);
        check(values[0].get<int>()==42&&values[1].get<int>()==42,"Append snapshots an existing element before moving the backing array");
        const auto before=values.size();const auto offset=values.append_default(2048);
        check(offset==before&&values[offset].type_tag()==VOID_VALUE&&values.size()==2050,"Default range append returns a stable offset into zeroed registers");
        check(values[0].get<int>()==42&&budget.used_bytes()==values.backing_bytes()&&resource.live_bytes==budget.used_bytes(),"Growing registers preserves values and charges all capacity");
        const auto charged=budget.used_bytes(),calls=resource.calls;
        resource.fail=true;values.truncate(1);values.clear();
        check(values.empty()&&budget.used_bytes()==charged&&resource.calls==calls,"Unwinding ranges allocates nothing and retains charged backing capacity");
        values.emplace_back(17);
        check(values[0].get<int>()==17&&resource.calls==calls,"Warm workspace reuse needs no new backing allocation");
        resource.fail=false;
        WorkspaceVector<Value> moved(std::move(values));
        check(values.empty()&&values.capacity()==0&&moved[0].get<int>()==17&&budget.used_bytes()==charged,"Moving a workspace transfers ownership of its backing charge");
        WorkspaceVector<Value> assigned(budget,4096,&resource);assigned.emplace_back(9);
        assigned=std::move(moved);
        check(assigned[0].get<int>()==17&&moved.capacity()==0&&budget.used_bytes()==charged,"Move assignment releases the replaced allocation exactly once");
    }
    check(budget.used_bytes()==0&&resource.live_bytes==0,"Workspace destruction releases all live backing allocations");
    {
        WorkspaceVector<Counted> values(budget,8,&resource);values.emplace_back(5);
        const auto bytes=budget.used_bytes(),capacity=values.capacity();
        rejects([&]{values.emplace_back(-1);},"Throwing candidate construction propagates");
        check(values.size()==1&&values[0].value==5&&values.capacity()==capacity&&budget.used_bytes()==bytes,"Candidate failure cannot grow or partially append a vector");
        values.append_default(3);values.truncate(2);
        check(Counted::alive==2,"Range truncation destroys removed records exactly once");
    }
    check(Counted::alive==0&&budget.used_bytes()==0,"Workspace cleanup destroys the remaining records");
    {
        // One old and one new array coexist during growth, even when the final
        // two-element array by itself would fit this limit.
        WorkspaceBudget tight(2*sizeof(Value));WorkspaceVector<Value> values(tight,2);
        values.emplace_back(1);
        rejects([&]{values.emplace_back(2);},"Vector growth accounts for old plus new backing capacity");
        check(values.size()==1&&values.capacity()==1&&values[0].get<int>()==1&&tight.used_bytes()==sizeof(Value),"Rejected growth preserves the existing vector");
    }
    {
        WorkspaceVector<Value> values(budget,2,&resource);values.reserve(2);
        values.append_default(2);
        rejects<std::length_error>([&]{values.append_default(1);},"Vector checks the logical element limit before appending");
        rejects<std::length_error>([&]{values.reserve(3);},"Explicit reserve honors the element limit");
        check(values.size()==2,"Invalid growth does not publish additional elements");
    }
}

void logical_call_accounting() {
    std::size_t active=0,limit=3;
    std::uint64_t remaining=9,max_steps=100;
    admission::accounting accounting(active,limit,remaining,max_steps);
    {
        auto lifecycle=accounting.enter();
        check(active==1&&remaining==100,"Lifecycle anchor initializes the execution budget without a script frame");
        remaining=81;
        auto script=accounting.enter();
        check(active==2&&remaining==81,"Nested script entry retains the remaining step budget");
        auto host=accounting.enter();
        check(active==3&&remaining==81,"Host and builtin calls consume logical depth");
        rejects([&]{accounting.enter();},"Depth limit rejects an entry before changing counters");
        check(active==3&&remaining==81,"Rejected admission preserves depth and remaining steps");
        auto transferred=std::move(host);host.reset();
        check(active==3,"Moving an admission does not enter or leave a logical call");
        transferred.reset();script.reset();
        check(active==1&&remaining==81,"Lifecycle anchor remains active between onload functions");
    }
    check(active==0&&remaining==81,"Final admission exit does not replenish steps");
    {
        max_steps=75;auto root=accounting.enter();
        check(active==1&&remaining==75,"Next outermost invocation starts the current configured budget");
        remaining=0;auto nested=accounting.enter();
        check(remaining==0,"A nested entry cannot gift steps after budget exhaustion");
        limit=2;rejects([&]{accounting.enter();},"Depth accounting reads current host limits");
    }
    limit=0;remaining=31;
    {
        std::vector<admission::accounting::ticket> entries;
        for(int i=0;i<1024;++i)entries.push_back(accounting.enter());
        check(active==1024&&remaining==75,"Zero depth cap permits deep entry but refills budget only once");
        remaining=0;auto nested=accounting.enter();
        check(remaining==0,"Uncapped reentry cannot refill an exhausted budget");
    }
    check(active==0&&remaining==0,"Uncapped admissions unwind without resetting budget");
}

void layouts() {
    ExecutableProgram program;
    program.instructions={{Opcode::scope_enter},{Opcode::scope_enter},{Opcode::scope_leave},
                          {Opcode::scope_enter},{Opcode::scope_leave},{Opcode::scope_leave},{Opcode::function_end}};
    FunctionRecord function;function.begin=0;function.end=7;function.register_count=9;
    function.local_count=5;function.param_types={INT_VALUE,INT_VALUE};program.functions.push_back(function);
    const auto layout=frame_layout(program,0);
    check(layout.registers==9&&layout.locals==7&&layout.alias_cells==17&&layout.scope_marks==3,"Frame layout sizes parameters, locals, alias replacement and a safe scope bound");
    rejects<std::out_of_range>([&]{frame_layout(program,1);},"Frame layout rejects an unavailable function");
    program.instructions={{Opcode::scope_enter},{Opcode::jump},{Opcode::scope_leave},{Opcode::jump},
                          {Opcode::scope_enter},{Opcode::scope_leave},{Opcode::jump},{Opcode::function_end}};
    program.instructions[1].target=4;program.instructions[3].target=7;program.instructions[6].target=2;
    program.functions[0].end=8;
    check(frame_layout(program,0).scope_marks==2,"Scope storage covers CFG depth two even when linear instruction depth is only one");
    program.functions[0].register_count=no_index-7;
    rejects<std::length_error>([&]{frame_layout(program,0);},"Alias count cannot overflow its reserved index space");
}

void frames_and_reentry() {
    WorkspaceBudget budget(32*1024*1024);ObservedResource resource;
    std::size_t active=0,limit=25000;std::uint64_t remaining=0,max_steps=50000;
    admission::accounting accounting(active,limit,remaining,max_steps);
    {
        FrameStack frames(budget,22000,&resource);
        FrameRecord first;first.function=3;first.pc=17;first.admission=accounting.enter();
        const auto root=frames.push(std::move(first));auto* root_address=&frames.resolve(root);
        for(std::uint32_t i=1;i<20000;++i) {
            FrameRecord child;child.function=i;child.pc=i+20;child.caller=frames.top_id();
            child.return_register=i%7;child.admission=accounting.enter();
            frames.push(std::move(child));--remaining;
        }
        check(frames.size()==20000&&active==20000&&remaining==30001,"Explicit frame storage accounts for a deep sequence without resetting its session");
        check(&frames.resolve(root)==root_address&&frames.resolve(root).pc==17,"Frame pool growth leaves saved caller records at stable addresses");
        bool valid=true;
        while(frames.size()>1) {
            const auto id=frames.top_id();const auto caller=frames.top().caller;
            const auto function=frames.top().function;const auto destination=frames.top().return_register;
            frames.pop();
            valid&=!frames.try_resolve(id)&&frames.top_id()==caller&&destination==function%7;
        }
        check(valid&&active==1,"Returning through stored caller IDs retires each generation and logical admission once");
        const auto old_id=frames.top_id();
        {
            auto host=accounting.enter();const auto mark=frames.size();
            FrameRecord reentrant_root;reentrant_root.function=99;reentrant_root.admission=accounting.enter();
            frames.push(std::move(reentrant_root));
            check(frames.size()==mark+1&&active==3&&!frames.top().caller,"A host reentry root does not inherit the suspended frame as its script caller");
            frames.pop();
            check(frames.size()==mark&&active==2&&frames.top_id()==old_id&&remaining==30001,"Nested execution returns to its saved stack mark without resetting the outer call");
        }
        check(active==1&&frames.top().pc==17,"Completing the host boundary restores the suspended caller");
        const auto allocation_calls=resource.calls;const auto bytes=budget.used_bytes();resource.fail=true;
        frames.clear();
        check(active==0&&!frames.try_resolve(old_id)&&resource.calls==allocation_calls&&budget.used_bytes()==bytes,"Frame unwinding allocates nothing and keeps cached capacity charged");
        resource.fail=false;
        check(budget.used_bytes()==frames.backing_bytes()&&budget.used_bytes()==resource.live_bytes,"Frame pages and ID-stack capacities are fully accounted");
        std::cout<<"layout: FrameRecord="<<sizeof(FrameRecord)<<" frame_stack_peak_backing="<<frames.backing_bytes()<<'\n';
    }
    check(active==0&&budget.used_bytes()==0&&resource.live_bytes==0,"Frame storage destruction frees all backing capacity");
    {
        FrameStack frames(budget,16,&resource);resource.fail=true;
        rejects<std::bad_alloc>([&]{FrameRecord frame;frame.admission=accounting.enter();frames.push(std::move(frame));},"Frame allocation failure propagates");
        check(frames.empty()&&active==0&&budget.used_bytes()==0,"Failed frame publication relinquishes its pending logical admission");
        resource.fail=false;FrameRecord frame;frame.admission=accounting.enter();const auto id=frames.push(std::move(frame));
        resource.fail=true;
        rejects<std::bad_alloc>([&]{FrameRecord child;child.admission=accounting.enter();frames.push(std::move(child));},"Failure growing a live frame stack propagates");
        check(frames.size()==1&&frames.top_id()==id&&active==1,"Failed nested call leaves the existing frame and depth usable");
        resource.fail=false;
    }
    check(active==0&&budget.used_bytes()==0&&resource.live_bytes==0,"Failed-call cleanup leaves no retained admission or allocation");
}
}

int main() {
    try {
        workspace_arrays();logical_call_accounting();layouts();frames_and_reentry();
        std::cout<<checks<<" VM frame/workspace primitive checks passed\n";
        return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
