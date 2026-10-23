#include "jit/analysis.h"
#include "jit/mir.h"
#include "vm/lowering.h"
#include <iostream>
#include <stdexcept>
#include <string>

namespace {
using namespace azertian;
using namespace azertian::jit;
using vm::Opcode;
using Node=std::shared_ptr<exec::expression>;
std::size_t checks=0;
void check(bool condition,const std::string& detail) {
    ++checks;if(!condition)throw std::runtime_error(detail);
}
Node op(int code,std::initializer_list<Node> children={}) {
    auto result=std::make_shared<exec::expression>();result->opcode=code;
    result->children.assign(children.begin(),children.end());return result;
}
Node literal(Value value){auto result=op(0);result->constant=std::move(value);return result;}
Node slot(int index){auto result=op(3);result->immediate=index;return result;}
Node define(int index,Node value){auto result=op(4,{value});result->immediate=index;return result;}
Node store(int index,Node value){auto result=op(5,{value});result->immediate=index;return result;}
Node returned(Node value){return op(7,{value});}
Node block(std::initializer_list<Node> children){return op(1,children);}
void number(const std::shared_ptr<const exec::expression>& node,std::uint32_t& count) {
    const_cast<exec::expression&>(*node).node=count++;
    for(const auto& child:node->children)number(child,count);
}
struct Lowered {
    vm::ExecutableProgram program;
    FunctionAnalysis analysis;
};
Lowered lower(Node body,int result=INT_VALUE,std::uint32_t locals=0,std::vector<int> params={}) {
    vm::SourceFunction source;source.function_id=10;source.return_type=result;source.local_count=locals;
    source.param_types=std::move(params);source.global_count=1;source.body=std::move(body);
    std::uint32_t count=0;number(source.body,count);
    Lowered lowered{vm::lower_program(vm::SourceProgram{{source},{}}),{}};
    lowered.analysis=analyze(lowered.program,0,vm::frame_layout(lowered.program,0),false);
    return lowered;
}
// The state before the first instruction with this opcode.
const FrameState& before(const Lowered& lowered,Opcode opcode) {
    const auto& analysis=lowered.analysis;
    for(auto pc=analysis.begin;pc<analysis.end;++pc)
        if(lowered.program.instructions[pc].opcode==opcode&&analysis.reached[pc-analysis.begin])
            return analysis.states[pc-analysis.begin];
    throw std::runtime_error("no reachable instruction with that opcode");
}
void tags() {
    const auto i=tag_bit(INT_VALUE),f=tag_bit(FLOAT_VALUE),d=tag_bit(DOUBLE_VALUE),s=tag_bit(STRING_VALUE),
               b=tag_bit(BOOLEAN_VALUE),a=tag_bit(ADDRESS_VALUE),v=tag_bit(VOID_VALUE);
    check(binary_result(BinaryOperation::add,i,i)==i,"int + int");
    check(binary_result(BinaryOperation::add,i,f)==f,"int + float");
    check(binary_result(BinaryOperation::multiply,f,d)==d,"float * double");
    check(binary_result(BinaryOperation::add,s,i)==s&&binary_result(BinaryOperation::add,b,s)==s,"string concatenation");
    check(binary_result(BinaryOperation::less,i,d)==b,"comparisons are boolean");
    check(binary_result(BinaryOperation::remainder,i,f)==0,"float remainder always fails");
    check(binary_result(BinaryOperation::add,b,b)==0,"boolean arithmetic always fails");
    check(binary_result(BinaryOperation::add,a,i)==a&&binary_result(BinaryOperation::subtract,i,a)==0,"address offsets");
    check(binary_result(BinaryOperation::equal,v,v)==b&&binary_result(BinaryOperation::equal,v,i)==0,"void equality");
    check(binary_result(BinaryOperation::shift_left,i,i|f)==i,"shifts need int operands");
    check(binary_result(BinaryOperation::add,i|d,i)==(i|d),"tag sets combine");
    check(unary_result(UnaryOperation::negate,i|f|d|b)==(i|f|d),"negation");
    check(unary_result(UnaryOperation::logical_not,i|b)==b,"logical not");
    check(convert_result(INT_VALUE,s|d|a)==i,"conversion to int");
    check(convert_result(STRING_VALUE,i|b)==s,"booleans do not convert to string");
}
Node counting_loop(int count) {
    return block({define(0,literal(Value(0))),op(28,{op(18,{slot(0),literal(Value(count))}),
        block({define(1,literal(Value(3))),store(0,op(12,{slot(0),literal(Value(1))}))})}),returned(slot(0))});
}
void loops() {
    const auto lowered=lower(counting_loop(5),INT_VALUE,2);
    const auto& analysis=lowered.analysis;
    check(analysis.eligible,"counting loop is eligible: "+analysis.reason);
    for(std::size_t i=0;i<analysis.shadow_registers.size();++i)check(analysis.shadow_registers[i],"loop registers are shadowable");
    check(analysis.shadow_locals[0]&&analysis.shadow_locals[1],"loop locals are shadowable");
    using Declared=LocalState::Declared;
    const auto& end=before(lowered,Opcode::set_return);
    check(end.locals[0].declared==Declared::yes&&end.locals[0].tags==tag_bit(INT_VALUE),"the counter is a declared int at the return");
    check(end.locals[0].depth==0,"the counter belongs to the function's root scope");
    check(end.locals[1].declared==Declared::no,"the loop body's local ended with its scope");
    check(end.scope_depth==0,"no scope is open at the return");
    // Flow can leave the root block before the counter's declaration.
    check(before(lowered,Opcode::function_end).locals[0].declared==Declared::maybe,"maybe declared at the function end");
    const auto& next=before(lowered,Opcode::loop_next);
    check(next.loop_depth==1&&next.locals[1].declared==Declared::no,"loop continuation state");
    // A statement may leave the body before its declaration: the body's
    // local is maybe declared when its scope ends.
    const auto& leave=before(lowered,Opcode::scope_leave);
    check(leave.locals[1].declared==Declared::maybe&&leave.locals[1].depth==leave.scope_depth,"maybe declared at the scope's end");
    std::size_t live=0;
    for(std::size_t i=0;i<analysis.live.size();++i)
        if(analysis.reached[i])for(const bool value:analysis.live[i])live+=value;
    check(live>0,"binary operands are live between their snapshot and use");
    // The counter is read until the return; the body's local never is.
    bool counter_read=false,body_local_read=false;
    for(std::size_t i=0;i<analysis.live_locals.size();++i) {
        if(!analysis.reached[i])continue;
        body_local_read|=analysis.live_locals[i][1];
        if(lowered.program.instructions[analysis.begin+i].opcode==Opcode::set_return)counter_read=analysis.live_locals[i][0];
    }
    check(counter_read,"a local read by the return is live before it");
    check(!body_local_read,"a local that is never read is never live");
    check(!analysis.live_locals.back()[0],"nothing is live at the function end");
}
// Nothing ends a local's liveness (an orphaned reference may still read a
// replaced cell), so a local read later is live even before its declaration.
void locals_without_kills() {
    auto body=block({define(0,literal(Value(1))),define(1,literal(Value(2))),
        returned(op(12,{slot(0),slot(1)}))});
    const auto lowered=lower(body,INT_VALUE,2);
    check(lowered.analysis.eligible,"two locals are eligible: "+lowered.analysis.reason);
    const auto& analysis=lowered.analysis;
    check(analysis.live_locals[0][0]&&analysis.live_locals[0][1],"both locals are live at entry: the return reads them");
}
void reference_liveness() {
    // A reference survives replacement of its local's cell. Reading it must
    // keep the old cell current across any native exit before replacement.
    vm::ExecutableProgram program;
    program.constants={Value(1),Value(2)};
    const auto unused=vm::no_index;
    program.instructions={
        {Opcode::constant,0,unused,unused,0},
        {Opcode::declare_slot,unused,0,unused,0},
        {Opcode::load_slot,1,unused,unused,0},
        {Opcode::copy_reference,2,1},
        {Opcode::constant,0,unused,unused,1},
        {Opcode::store,0,1,0},
        {Opcode::declare_slot,unused,0,unused,0},
        {Opcode::set_return,unused,2,unused,0},
        {Opcode::function_end}};
    program.sources.resize(program.instructions.size());
    vm::FunctionRecord record;record.begin=0;record.end=program.instructions.size();
    record.register_count=3;record.local_count=2;program.functions={record};
    auto analysis=analyze(program,0,vm::frame_layout(program,0),false);
    check(analysis.eligible,"a surviving local reference is eligible");
    check(analysis.states[7].registers[2].kind==RegisterState::Kind::orphan,"a copied reference survives redeclaration");
    check(analysis.live_locals[5][0]&&analysis.live_locals[7][0],"an orphan read preserves local liveness before replacement");
    check(analysis.live_locals[7][1],"an orphan read conservatively keeps every local live");
    // With no eventual read, copying a reference alone does not read its
    // cell: neither resume loads nor exit stores should include the local.
    program.instructions[7]={Opcode::set_return,unused,0,unused,0};
    analysis=analyze(program,0,vm::frame_layout(program,0),false);
    check(!analysis.live_locals[3][0],"copying a reference does not read the local value");
    const auto code=build(program,analysis,true);
    check(code.resume_tags[3][code.registers]==0,"dead locals start absent at a native resume point");
    for(std::size_t exit=0;exit<code.exits.size();++exit)
        check(code.exit_locals[exit].empty(),"dead locals are omitted from native exits");
}
void unchecked_arithmetic() {
    for(int opcode=55;opcode<=58;++opcode) {
        const auto lowered=lower(block({returned(op(opcode,{literal(Value(INT32_MAX)),literal(Value(2))}))}));
        check(lowered.analysis.eligible,"unchecked integer arithmetic is eligible");
        const auto code=build(lowered.program,lowered.analysis,false);
        bool found=false;
        for(const auto& operation:code.ops)if(operation.code==Code::binary_i32) {
            found=true;
            check(operation.exit==none,"unchecked arithmetic has no failure exit");
            check(operation.tags==tag_bit(INT_VALUE),"unchecked arithmetic produces int32");
        }
        check(found,"proven integer operands lower to the unchecked MIR operation");
        const auto invalid=lower(block({returned(op(opcode,{literal(Value(1.0)),literal(Value(2))}))}));
        check(invalid.analysis.eligible,"a type error remains an eligible failing instruction");
        const auto checked=build(invalid.program,invalid.analysis,false);
        for(const auto& operation:checked.ops)
            check(operation.code!=Code::binary_i32,"unproven operand tags do not enter unchecked native arithmetic");
    }
}
void strings() {
    const auto lowered=lower(block({define(0,op(12,{literal(Value(std::string("n="))),literal(Value(1))})),returned(slot(0))}),STRING_VALUE,1);
    check(lowered.analysis.eligible,"string concatenation is eligible");
    check(!lowered.analysis.shadow_locals[0],"a string local is not shadowed");
    bool unshadowed=false;
    for(const bool shadow:lowered.analysis.shadow_registers)unshadowed|=!shadow;
    check(unshadowed,"the concatenation's register is not shadowed");
}
void parameters_and_globals() {
    const auto lowered=lower(block({store(-1,op(12,{slot(0),slot(1)})),returned(slot(-1))}),ANY_VALUE,0,{INT_VALUE,ANY_VALUE});
    check(lowered.analysis.eligible,"globals are eligible");
    const auto& entry=lowered.analysis.states[0];
    check(entry.locals[0].declared==LocalState::Declared::yes&&entry.locals[0].tags==tag_bit(INT_VALUE),"typed parameter");
    check(entry.locals[1].tags==any_scalar,"ANY parameter");
    check(entry.locals[0].depth==parameter_depth,"parameters are never released by a scope");
    bool global=false;
    for(const auto& state:lowered.analysis.states)
        for(const auto& reg:state.registers)global|=reg.kind==RegisterState::Kind::global;
    check(global,"a global reference");
}
void refusals() {
    const auto cleanup=lower(block({op(31,{block({}),block({})}),returned(literal(Value(1)))}));
    check(!cleanup.analysis.eligible&&cleanup.analysis.reason=="cleanup regions","cleanup regions are refused");
    const auto object=analyze(cleanup.program,0,vm::frame_layout(cleanup.program,0),true);
    check(!object.eligible&&object.reason=="object frame","object frames are refused");
    // An undeclared load always fails: nothing after it is reached normally.
    const auto failing=lower(block({returned(slot(0))}),INT_VALUE,1);
    check(failing.analysis.eligible,"a failing load is eligible");
    const auto& analysis=failing.analysis;
    for(auto pc=analysis.begin;pc<analysis.end;++pc)
        if(failing.program.instructions[pc].opcode==Opcode::load_slot)
            check(analysis.reached[pc-analysis.begin]&&!analysis.reached[pc+1-analysis.begin],"code after an always-failing load is not reached");
}
}
int main() {
    try{tags();loops();locals_without_kills();reference_liveness();unchecked_arithmetic();strings();parameters_and_globals();refusals();std::cout<<"JIT analysis checks passed: "<<checks<<'\n';}
    catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
