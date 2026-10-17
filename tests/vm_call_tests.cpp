#include "../interpreter/vm/execution.h"
#include "../interpreter/vm/lowering.h"
#include <array>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace azertian;
using namespace azertian::vm;
using Node=std::shared_ptr<SourceExpression>;
std::size_t checks=0;
void check(bool value,const char* detail){++checks;if(!value)throw std::runtime_error(detail);}
Node op(int code,std::initializer_list<Node> children={}) {
    auto value=std::make_shared<SourceExpression>();value->opcode=code;value->children.assign(children.begin(),children.end());return value;
}
Node literal(Value value){auto node=op(0);node->constant=std::move(value);return node;}
template<class T> Node literal(T value){return literal(Value(value));}
Node slot(int index){auto node=op(3);node->immediate=index;return node;}
Node call(int id,std::initializer_list<Node> arguments={}){auto node=op(2,arguments);node->immediate=id;return node;}
Node returned(Node value){return op(7,{value});}
void number(const std::shared_ptr<const SourceExpression>& node,std::uint32_t& next) {
    auto& mutable_node=const_cast<SourceExpression&>(*node);mutable_node.node=next++;mutable_node.location=next;
    for(const auto& child:node->children)number(child,next);
}
SourceFunction function(int id,Node body,std::vector<int> parameters={},int result=INT_VALUE) {
    SourceFunction value;value.function_id=id;value.return_type=result;value.param_types=std::move(parameters);value.body=body;
    std::uint32_t count=0;number(body,count);
    DebugInfo debug;debug.files=std::make_shared<const std::vector<std::string>>(std::vector<std::string>{"vm-calls.azs"});debug.name="function";debug.header={0,1,1};
    for(std::uint32_t i=0;i<count;++i)debug.locations.push_back({0,10+i,1});
    value.debug=std::move(debug);return value;
}
SourceFunction recursive(bool boundary=false) {
    return function(10,returned(op(50,{op(17,{slot(0),literal(0)}),
        op(12,{call(10,{op(13,{slot(0),literal(1)})}),literal(1)}),
        boundary?call(0x76540001):literal(0)})),{INT_VALUE});
}
struct Callbacks final:ExecutionCallbacks {
    std::function<Value(const CallTarget&,std::span<const Value>,const ExecutionPosition&,std::size_t)> body;
    std::array<ExecutionPosition,64> trace{};
    std::size_t recorded=0,omitted=0;
    Value invoke_external(const CallTarget& target,std::span<const Value> arguments,
            const ExecutionPosition& position,std::size_t lexical_depth) override {
        return body(target,arguments,position,lexical_depth);
    }
    void note_script_error(const ExecutionPosition& position) noexcept override {
        if(recorded<trace.size())trace[recorded++]=position;else ++omitted;
    }
    void clear() noexcept{recorded=omitted=0;}
};
void recursion_and_depth() {
    const auto program=lower_program(SourceProgram{{recursive(true)},{}});
    WorkspaceBudget workspace(128*1024*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    std::size_t active=0,depth_limit=20002;
    std::uint64_t steps=0,max_steps=1000000;
    admission::accounting accounting(active,depth_limit,steps,max_steps);Callbacks callbacks;
    std::size_t deepest=0;
    callbacks.body=[&](const CallTarget&,std::span<const Value> args,const ExecutionPosition&,std::size_t lexical_depth) {
        auto admission=accounting.enter();deepest=active;
        check(args.empty()&&lexical_depth==2,"Recursion must not change the lexical root depth");return Value(0);
    };
    ExecutionOptions options{&accounting,&callbacks};const std::array argument{Value(20000)};
    auto result=executor.invoke(0,argument,{},steps,nullptr,options);
    check(result.get<int>()==20000&&deepest==20002,"Twenty thousand script calls use explicit frames and one host admission");
    check(active==0&&pool.size()==0,"Successful recursive return retires every frame and local");
    check(steps<max_steps&&steps>0&&callbacks.recorded==0,"Ordinary calls share ticks and do not emit failure traces");
    const auto high_water=workspace.used_bytes();
    const std::array small{Value(5)};steps=7;
    result=executor.invoke(0,small,{},steps,nullptr,options);
    check(result.get<int>()==5&&steps>7,"A fresh top-level admission initializes the configured step budget");
    check(workspace.used_bytes()==high_water,"Warm shallower calls reuse charged backing capacity");

    depth_limit=4;callbacks.clear();const std::array failing{Value(10)};ExecutionPosition failure;
    bool rejected=false;
    try{executor.invoke(0,failing,{},steps,&failure,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what())=="Script call depth limit exceeded";}
    check(rejected&&active==0&&pool.size()==0,"Call-depth rejection restores all admissions and slots");
    check(callbacks.recorded==5&&failure.function==0&&failure.location==0,"Rejected callee has a header trace before admitted callers");
    check(callbacks.trace[0].location==0&&callbacks.trace[1].location!=0,"Error trace order is innermost to outermost");

    depth_limit=30000;max_steps=500;callbacks.clear();
    rejected=false;
    try{executor.invoke(0,argument,{},steps,&failure,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what())=="Script execution step limit exceeded";}
    check(rejected&&steps==0&&active==0&&pool.size()==0,"Budget exhaustion unwinds nested frames without native recursion");
    check(callbacks.recorded>1&&failure.location!=0,"Budget failure retains the deepest instruction location");

    max_steps=10000;depth_limit=3;callbacks.clear();
    {
        auto lifecycle=accounting.enter();steps=777;options.admit_entry=false;
        // The external public entry already owns its call_guard admission.
        auto public_entry=accounting.enter();const std::array two{Value(2)};
        rejected=false;
        try{executor.invoke(0,two,{},steps,&failure,options);}
        catch(const std::runtime_error& error){rejected=std::string(error.what())=="Script call depth limit exceeded";}
        check(rejected&&active==2&&steps<777,"An admitted lifecycle/public root is neither double charged nor reset");
    }
    check(active==0&&pool.size()==0,"Lifecycle anchors outlive VM-only frame unwinding");
}
void host_reentry_and_lexical_depth() {
    auto outer=function(10,returned(call(0x76540001,{literal(12),literal("preserved across native reentry")})));
    auto inner=function(11,returned(op(12,{slot(0),slot(255)})),std::vector<int>(256,INT_VALUE));
    auto nested=function(12,op(1,{op(1,{returned(call(0x76540002))})}));
    const auto program=lower_program(SourceProgram{{outer,inner,nested},{}});
    WorkspaceBudget workspace(4*1024*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    std::size_t active=0,limit=8;std::uint64_t steps=0,max_steps=10000;
    admission::accounting accounting(active,limit,steps,max_steps);Callbacks callbacks;ExecutionOptions options{&accounting,&callbacks};
    callbacks.body=[&](const CallTarget& target,std::span<const Value> args,const ExecutionPosition&,std::size_t depth) {
        auto host=accounting.enter();
        if(target.function_id==0x76540002) {
            check(depth==3,"A nested block adds one lexical scope but root block adds none");return Value(3);
        }
        check(depth==2&&active==2,"Host admission sees the caller root lexical depth and shared logical depth");
        const auto* before=args.data();check(args.size()==2&&args[0].get<int>()==12,"Host receives all materialized arguments");
        std::array<Value,256> arguments;for(auto& value:arguments)value=Value(2);
        auto result=executor.invoke(1,arguments,{},steps,nullptr,options);
        check(result.get<int>()==4&&active==2,"Native reentry returns only its own invocation frames");
        check(args.data()==before&&args[0].get<int>()==12&&args[1].get<std::string>()=="preserved across native reentry",
            "Nested workspace growth cannot invalidate an external argument span");
        return Value(args[0].get<int>()+result.get<int>());
    };
    check(executor.invoke(0,{}, {},steps,nullptr,options).get<int>()==16,"Outer caller resumes after host reentry");
    check(executor.invoke(2,{}, {},steps,nullptr,options).get<int>()==3,"Lexical depth callback result is preserved");
    check(active==0&&pool.size()==0&&callbacks.recorded==0,"Host reentry releases every local and logical guard");

    // A failure caught by native code must unwind the nested root alone.
    callbacks.body=[&](const CallTarget&,std::span<const Value>,const ExecutionPosition&,std::size_t) {
        auto host=accounting.enter();bool rejected=false;ExecutionPosition where;
        try{executor.invoke(1,{}, {},steps,&where,options);}
        catch(const std::runtime_error&){rejected=true;}
        check(rejected&&where.function==1&&active==2,"Caught reentry failure leaves the suspended caller admitted");
        check(callbacks.recorded==1&&callbacks.trace[0].function==1,"Caught nested error reports no suspended caller trace");
        return Value(9);
    };
    check(executor.invoke(0,{}, {},steps,nullptr,options).get<int>()==9,"Caller continues after native recovery");
    check(active==0&&pool.size()==0,"Recovered native boundary releases its slots and guards");
}
Node guarded(Node body,Node finalizer,bool on_error) {
    auto node=op(31,{std::move(body),std::move(finalizer)});node->on_error=on_error;return node;
}
void cleanup_across_host_reentry() {
    // Function 0 protects a host call that reenters the executor. Each native
    // reentry is its own invocation root: it may only use its own regions.
    const auto event=[](int value){return call(0x76540003,{literal(value)});};
    auto outer=function(10,op(1,{guarded(call(0x76540001),event(1),true),returned(literal(5))}));
    auto failing=function(11,returned(op(15,{literal(1),literal(0)})));
    auto protected_failure=function(12,guarded(returned(op(15,{literal(1),literal(0)})),event(2),true));
    const auto program=lower_program(SourceProgram{{outer,failing,protected_failure},{}});
    WorkspaceBudget workspace(4*1024*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    std::size_t active=0,limit=8;std::uint64_t steps=0,max_steps=10000;
    admission::accounting accounting(active,limit,steps,max_steps);Callbacks callbacks;ExecutionOptions options{&accounting,&callbacks};
    std::vector<int> events;std::uint32_t reentered=1;bool rethrow=false;
    callbacks.body=[&](const CallTarget& target,std::span<const Value> args,const ExecutionPosition&,std::size_t) {
        auto host=accounting.enter();
        if(target.function_id==0x76540003){events.push_back(args[0].get<int>());return Value{};}
        try{executor.invoke(reentered,{}, {},steps,nullptr,options);}
        catch(const std::runtime_error&){if(rethrow)throw;}
        return Value(0);
    };
    check(executor.invoke(0,{}, {},steps,nullptr,options).get<int>()==5&&events.empty(),
        "A caught reentry failure cannot run the suspended caller's on-error finalizer");
    reentered=2;callbacks.clear();
    check(executor.invoke(0,{}, {},steps,nullptr,options).get<int>()==5&&events==std::vector<int>{2},
        "A reentered root runs its own region once and leaves the caller's region pending");
    check(active==0&&pool.size()==0,"Recovered reentry regions release guards and slots");
    reentered=1;rethrow=true;events.clear();callbacks.clear();bool rejected=false;
    try{executor.invoke(0,{}, {},steps,nullptr,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what())=="Division by zero";}
    check(rejected&&events==std::vector<int>{1},"A host-propagated failure runs the caller's finalizer once and keeps its identity");
    check(callbacks.recorded==2&&callbacks.trace[0].function==1&&callbacks.trace[1].function==0,
        "The reentered frame and the protected caller report innermost first");
    check(active==0&&pool.size()==0,"Propagated reentry failure releases every guard and slot");
}
void script_trace_and_conversion() {
    auto outer=function(10,returned(call(11)));
    auto middle=function(11,returned(call(12)));
    auto leaf=function(12,returned(literal(true)));
    const auto program=lower_program(SourceProgram{{outer,middle,leaf},{}});
    WorkspaceBudget workspace(2*1024*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    Callbacks callbacks;ExecutionOptions options{nullptr,&callbacks};ExecutionPosition position;
    std::uint64_t steps=100;bool rejected=false;
    try{executor.invoke(0,{}, {},steps,&position,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what()).find("Function return type mismatch")!=std::string::npos;}
    check(rejected&&position.function==2&&position.location==1,"Return conversion fails at the leaf return expression");
    check(callbacks.recorded==3&&callbacks.trace[0].function==2&&callbacks.trace[1].function==1&&callbacks.trace[2].function==0,
        "Explicit script unwinding reports every function in the original innermost-first order");
    check(callbacks.trace[0].location==1&&callbacks.trace[1].location==2&&callbacks.trace[2].location==2,
        "Suspended callers retain their call sites while the callee reports its return site");
    check(pool.size()==0,"Failed return conversion releases all frame slots");

    outer.return_type=DOUBLE_VALUE;middle.return_type=DOUBLE_VALUE;
    leaf=function(12,returned(literal(16777217)),{},FLOAT_VALUE);
    const auto widening=lower_program(SourceProgram{{outer,middle,leaf},{}});
    ScalarExecutor conversions(widening,pool,workspace);steps=100;callbacks.clear();
    auto result=conversions.invoke(0,{}, {},steps,&position,options);
    check(result.type_tag()==DOUBLE_VALUE&&result.get<double>()==16777216.0,
        "Each frame applies its own int-to-float-to-double return conversion before caller resumption");
    check(callbacks.recorded==0&&pool.size()==0,"Successful converted returns emit no error trace or leaked locals");
}
void large_slot_layouts() {
    for(const bool dense:{true,false}) {
        const std::uint32_t count=dense?32768:1048576;
        ExecutableProgram program;program.constants.emplace_back(7);
        program.instructions.push_back({Opcode::constant,0,no_index,no_index,0});
        for(std::uint32_t index=dense?0:count-1;index<count;++index) {
            program.instructions.push_back({Opcode::check_declaration,no_index,no_index,no_index,index});
            program.instructions.push_back({Opcode::declare_slot,no_index,0,no_index,index});
        }
        program.instructions.push_back({Opcode::load_slot,1,no_index,no_index,count-1});
        program.instructions.push_back({Opcode::set_return,no_index,1,no_index,0});
        program.instructions.push_back({Opcode::function_end});program.sources.resize(program.instructions.size());
        FunctionRecord function;function.function_id=10;function.return_type=INT_VALUE;
        function.local_count=count;function.register_count=2;function.node_count=1;
        function.end=static_cast<std::uint32_t>(program.instructions.size());program.functions.push_back(function);
        WorkspaceBudget workspace(16*1024*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
        std::uint64_t steps=0;
        check(executor.invoke(0,{}, {},steps).get<int>()==7,"Dense 32k and sparse legal-MAX frames can read their last local");
        check(steps==0&&pool.size()==0,"Large frame allocation adds no synthetic ticks and retires every declared slot");
        check(workspace.used_bytes()<16*1024*1024,"Complete sparse-MAX frame backing fits its explicit workspace budget");
        std::cout<<"vm-call locals="<<count<<" declared="<<(dense?count:1)<<" workspace="<<workspace.used_bytes()<<" peak="<<workspace.peak_bytes()<<'\n';
    }
}
void default_workspace_boundaries() {
    // The densest legal frame declares every one of the 1,048,576 slots. The
    // smallest v10 encoding of that body still fits the 64 MiB exec cap.
    {
        constexpr std::uint32_t count=1048576;
        ExecutableProgram program;program.constants.emplace_back(7);
        program.instructions.reserve(2*count+4);
        program.instructions.push_back({Opcode::constant,0,no_index,no_index,0});
        for(std::uint32_t index=0;index<count;++index) {
            program.instructions.push_back({Opcode::check_declaration,no_index,no_index,no_index,index});
            program.instructions.push_back({Opcode::declare_slot,no_index,0,no_index,index});
        }
        program.instructions.push_back({Opcode::load_slot,1,no_index,no_index,count-1});
        program.instructions.push_back({Opcode::set_return,no_index,1,no_index,0});
        program.instructions.push_back({Opcode::function_end});program.sources.resize(program.instructions.size());
        FunctionRecord function;function.function_id=10;function.return_type=INT_VALUE;
        function.local_count=count;function.register_count=2;function.node_count=1;
        function.end=static_cast<std::uint32_t>(program.instructions.size());program.functions.push_back(function);
        WorkspaceBudget workspace(default_workspace_bytes);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
        std::uint64_t steps=0;
        check(executor.invoke(0,{}, {},steps).get<int>()==7,"Default workspace admits a fully dense legal-MAX frame");
        check(pool.size()==0&&workspace.peak_bytes()<=default_workspace_bytes,"Dense legal-MAX frame retires every slot within the default");
        std::cout<<"vm-call default dense locals="<<count<<" workspace="<<workspace.used_bytes()<<" peak="<<workspace.peak_bytes()<<'\n';
    }
    // Large sparse headers recurse to the runtime's default depth limit of 256:
    // the public entry holds one admission and script CALL takes the rest.
    auto source=recursive();source.local_count=32767;
    const auto program=lower_program(SourceProgram{{source},{}});
    WorkspaceBudget workspace(default_workspace_bytes);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    std::size_t active=0,depth_limit=256;std::uint64_t steps=0,max_steps=1000000;
    admission::accounting accounting(active,depth_limit,steps,max_steps);ExecutionOptions options{&accounting,nullptr};
    const std::array deepest{Value(255)};
    check(executor.invoke(0,deepest,{},steps,nullptr,options).get<int>()==255,"Default workspace admits 32k-local headers at the default depth");
    check(active==0&&pool.size()==0&&workspace.peak_bytes()<=default_workspace_bytes,"Default-depth recursion releases all admissions and slots");
    std::cout<<"vm-call default recursion depth=256 locals=32767 workspace="<<workspace.used_bytes()<<" peak="<<workspace.peak_bytes()<<'\n';
    // Raising only the call depth cannot raise the workspace limit.
    depth_limit=1024;const std::array beyond{Value(1000)};bool rejected=false;
    try{executor.invoke(0,beyond,{},steps,nullptr,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what())=="VM workspace limit exceeded";}
    check(rejected&&active==0&&pool.size()==0,"Deeper recursion is rejected by the default workspace without partial frames");
    check(workspace.used_bytes()<=default_workspace_bytes,"Rejected growth keeps charged backing within the default");
}
void workspace_failure_atomicity() {
    const auto program=lower_program(SourceProgram{{recursive()},{}});
    WorkspaceBudget workspace(128*1024);SlotPool pool(workspace);ScalarExecutor executor(program,pool,workspace);
    std::size_t active=0,limit=10000;std::uint64_t steps=0,max_steps=1000000;
    admission::accounting accounting(active,limit,steps,max_steps);Callbacks callbacks;ExecutionOptions options{&accounting,&callbacks};
    const std::array arguments{Value(1000)};bool rejected=false;
    try{executor.invoke(0,arguments,{},steps,nullptr,options);}
    catch(const std::runtime_error& error){rejected=std::string(error.what())=="VM workspace limit exceeded";}
    check(rejected&&active==0&&pool.size()==0,"Frame/workspace allocation failure cannot leak partial parameter publication");
    check(workspace.used_bytes()<=workspace.limit_bytes(),"Cached backing never exceeds its configured budget");
    const std::array zero{Value(0)};
    check(executor.invoke(0,zero,{},steps,nullptr,options).get<int>()==0,"A failed deep call does not poison the reusable executor");
    check(active==0&&pool.size()==0,"Successful reuse also retires all parameters");
}
}
int main() {
    try{recursion_and_depth();host_reentry_and_lexical_depth();cleanup_across_host_reentry();script_trace_and_conversion();large_slot_layouts();default_workspace_boundaries();workspace_failure_atomicity();
        std::cout<<"VM call checks passed: "<<checks<<'\n';}
    catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
