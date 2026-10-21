#include "lowering.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <set>
#include <stdexcept>

using namespace azertian;
using namespace azertian::vm;
namespace {
using Node=std::shared_ptr<exec::expression>;
void expect(bool condition,const char* message) {if(!condition)throw std::runtime_error(message);}
template<class Body> void rejects(Body body,const std::string& fragment) {
    try {body();}catch(const std::invalid_argument& error) {
        if(std::string(error.what()).find(fragment)==std::string::npos)throw std::runtime_error("Unexpected rejection: "+std::string(error.what()));
        return;
    }
    throw std::runtime_error("Expected rejection: "+fragment);
}
Node node(int opcode,std::initializer_list<Node> children={}) {
    auto result=std::make_shared<exec::expression>();result->opcode=opcode;
    for(const auto& child:children)result->children.push_back(child);
    return result;
}
Node integer(int value=7) {auto result=node(0);result->constant=Value(value);return result;}
Node boolean(bool value=true) {auto result=node(0);result->constant=Value(value);return result;}
Node pointer() {auto result=node(0);result->constant=Value(address{0xffffffffffffffffULL});return result;}
Node slot(int index=0) {auto result=node(3);result->immediate=index;return result;}
std::shared_ptr<exec::context> scalar_context() {auto result=std::make_shared<exec::context>();result->abi=INT_VALUE;return result;}
void number_nodes(const Node& root,std::uint32_t& next) {
    root->node=next++;for(const auto& child:root->children)number_nodes(std::const_pointer_cast<exec::expression>(child),next);
}
SourceFunction source_function(Node body,int id=0x0fff0010) {
    std::uint32_t next=0;number_nodes(body,next);
    SourceFunction result;result.function_id=id;result.local_count=2;result.global_count=2;result.hidden_count=1;result.body=std::move(body);return result;
}
ExecutableProgram lower(Node body) {SourceProgram source;source.functions.push_back(source_function(std::move(body)));return lower_program(source);}
std::size_t find(const ExecutableProgram& program,Opcode opcode) {
    auto at=std::find_if(program.instructions.begin(),program.instructions.end(),[&](const auto& instruction){return instruction.opcode==opcode;});
    if(at==program.instructions.end())throw std::runtime_error(std::string("Missing opcode ")+opcode_name(opcode));
    return static_cast<std::size_t>(at-program.instructions.begin());
}
void scalar_order_and_control() {
    // A declaration must fail before evaluating its initializer. Assignment
    // resolves its destination before a RHS can mutate or undeclare that slot.
    auto declaration=node(4,{integer()});auto assignment=node(5,{node(12,{slot(),integer()})});
    auto loop=node(28,{boolean(),node(1,{node(27,{boolean(),node(30),node(29)})})});
    auto program=lower(node(1,{declaration,assignment,loop,node(7,{integer()})}));
    const auto declaration_check=find(program,Opcode::check_declaration);
    expect(program.instructions[declaration_check+1].opcode==Opcode::tick,"initializer starts after declaration check");
    auto store=find(program,Opcode::store);auto binary=find(program,Opcode::binary);
    expect(find(program,Opcode::load_slot)<binary&&binary<store,"assignment evaluates lookup, then RHS, then store");
    expect(program.instructions[binary-3].opcode==Opcode::snapshot,"binary LHS is snapshotted before RHS tick");
    expect(program.functions[0].register_count<12,"register allocation uses peak expression liveness");
    expect(program.instructions.front().opcode==Opcode::tick,"root block adds no lexical scope");
    expect(std::count_if(program.instructions.begin(),program.instructions.end(),[](const auto& instruction){return instruction.opcode==Opcode::tick&&instruction.operand==static_cast<unsigned>(BudgetKind::loop_iteration);})==1,"loop body has its extra logical budget point");
    // Flow setters do not immediately jump out of operand evaluation in v10.
    auto odd=lower(node(12,{node(7,{integer(1)}),integer(2)}));
    const auto returned=find(odd,Opcode::set_return);
    expect(returned<find(odd,Opcode::binary)&&odd.instructions[returned+1].opcode==Opcode::constant,"return as operand preserves subsequent evaluation");
    verify_program(lower(node(29))); // Runtime, not loader, rejects break outside loop.
    verify_program(lower(node(30)));
}
void short_circuit_and_locations() {
    auto left=node(23,{boolean(false),node(24,{boolean(true),boolean(false)})});
    left->location=1;left->children[0]=boolean(false);
    auto selection=node(50,{boolean(),integer(10),integer(20)});selection->location=2;
    auto body=node(1,{left,selection});auto function=source_function(body);
    function.debug=DebugInfo{"main",std::make_shared<const std::vector<std::string>>(std::vector<std::string>{"case.az"}),{0,1,1},{{0,4,3},{0,8,2}}};
    SourceProgram source;source.functions.push_back(function);auto program=lower_program(source);
    expect(std::count_if(program.instructions.begin(),program.instructions.end(),[](const auto& instruction){return instruction.opcode==Opcode::branch_false;})==3,"lazy expressions lower to branches");
    expect(std::none_of(program.instructions.begin(),program.instructions.end(),[](const auto& instruction){return instruction.opcode==Opcode::scope_enter;}),"select and boolean operators do not introduce scopes");
    bool inherited=false;
    for(std::size_t i=0;i<program.instructions.size();++i)
        if(program.instructions[i].opcode==Opcode::constant&&program.sources[i].location==2)inherited=true;
    expect(inherited,"child instruction inherits source position");
    auto corrupt=program;corrupt.sources[0].location=3;rejects([&]{verify_program(corrupt);},"source map");
}
void all_wire_operations() {
    std::vector<Node> samples;
    samples.push_back(integer());samples.push_back(node(1));
    auto call=node(2,{integer()});call->immediate=0x0abd0000;samples.push_back(call);
    samples.push_back(slot());samples.push_back(node(4,{integer()}));samples.push_back(node(5,{integer()}));
    samples.push_back(node(6,{slot(),integer()}));samples.push_back(node(7,{integer()}));samples.push_back(node(8,{pointer()}));
    auto member=node(9,{pointer()});member->immediate=2;samples.push_back(member);
    samples.push_back(node(10,{pointer()}));samples.push_back(node(11,{pointer()}));
    for(int opcode=12;opcode<=22;++opcode)samples.push_back(node(opcode,{integer(),integer()}));
    samples.push_back(node(23,{boolean(),boolean()}));samples.push_back(node(24,{boolean(),boolean()}));
    samples.push_back(node(25,{boolean()}));samples.push_back(node(26,{integer()}));
    samples.push_back(node(27,{boolean(),integer(),integer()}));samples.push_back(node(28,{boolean(false),integer()}));
    samples.push_back(node(29));samples.push_back(node(30));samples.push_back(node(31,{integer(),integer()}));
    auto block=node(32);block->immediate=1;samples.push_back(block);
    samples.push_back(node(33,{integer()}));samples.push_back(node(34));samples.push_back(node(35,{slot()}));
    for(int opcode=36;opcode<=40;++opcode) {
        auto operation=(opcode==38||opcode==39)?node(opcode,{integer()}):node(opcode);
        operation->contexts.push_back(scalar_context());samples.push_back(operation);
    }
    samples.push_back(node(41,{integer()}));
    auto compare=node(42,{integer(),integer()});compare->contexts.push_back(scalar_context());samples.push_back(compare);
    for(int opcode=43;opcode<=48;++opcode)samples.push_back(node(opcode,{integer(),integer()}));
    auto convert=node(49,{integer()});convert->immediate=DOUBLE_VALUE;samples.push_back(convert);
    samples.push_back(node(50,{boolean(),integer(),integer()}));
    auto check=node(51,{pointer()});auto context=std::make_shared<exec::context>();context->abi=ADDRESS_VALUE;context->kind=2;
    check->contexts.push_back(context);check->slot_types={INT_VALUE,ANY_VALUE};samples.push_back(check);
    auto methods=node(52,{pointer()});methods->methods.push_back({0x0fff0020,{}});samples.push_back(methods);
    auto virtual_call=node(53,{pointer()});virtual_call->return_type=VOID_VALUE;samples.push_back(virtual_call);
    samples.push_back(node(54,{node(5,{integer()}),integer()}));
    for(int opcode=55;opcode<=57;++opcode)samples.push_back(node(opcode,{integer(),integer()}));
    auto guard=node(58,{pointer()});guard->methods.push_back({0x0fff0020,{}});samples.push_back(guard);
    expect(samples.size()==59,"sample for every wire opcode");
    auto root=node(1);for(const auto& sample:samples)root->children.push_back(sample);
    auto method=source_function(node(7),0x0fff0020);method.hidden_count=0;method.param_types={ADDRESS_VALUE};
    SourceProgram source;source.functions={source_function(root),method};
    CallTarget builtin;builtin.function_id=0x0abd0000;builtin.kind=TargetKind::builtin;builtin.dynamic_signature=true;source.external_targets.push_back(builtin);
    auto program=lower_program(source);std::set<unsigned> covered;
    for(const auto& instruction:program.instructions)covered.insert(static_cast<unsigned>(instruction.opcode));
    expect(covered.contains(static_cast<unsigned>(Opcode::cleanup_enter)),"cleanup has an explicit region");
    expect(covered.contains(static_cast<unsigned>(Opcode::bind_context)),"generic contexts have explicit binding operations");
    expect(covered.contains(static_cast<unsigned>(Opcode::call_virtual)),"virtual calls have explicit operations");
    expect(program.functions[0].cleanups.size()==1,"cleanup descriptors retained");
    auto capabilities=std::vector<Opcode>{Opcode::tick,Opcode::constant};
    rejects([&]{require_capabilities(program,capabilities);},"VM backend does not support:");
    capabilities.clear();for(unsigned i=0;i<static_cast<unsigned>(Opcode::count);++i)capabilities.push_back(static_cast<Opcode>(i));
    require_capabilities(program,capabilities);
    // Registration checks its receiver before charging each method entry.
    const auto receiver=find(program,Opcode::check_method_receiver);
    expect(program.instructions[receiver+1].opcode==Opcode::tick&&program.instructions[receiver+1].operand==static_cast<unsigned>(BudgetKind::method_entry),"method charges follow receiver validation");
    auto corrupt=program;corrupt.functions[0].operations[program.instructions[find(program,Opcode::call_virtual)].operand].arguments[0]=no_index;
    rejects([&]{verify_program(corrupt);},"argument register");
    corrupt=program;corrupt.functions[0].cleanups[0].finalizer=0;
    rejects([&]{verify_program(corrupt);},"cleanup region");
    corrupt=program;corrupt.functions[0].operations[program.instructions[find(program,Opcode::guard_method)].operand].methods.clear();
    rejects([&]{verify_program(corrupt);},"invalid method guard layout");
}
void contexts_and_targets() {
    auto factory=source_function(integer(),0x0fff0021);factory.return_type=OBJECT_VALUE;factory.hidden_count=0;
    auto placement=source_function(node(7),0x0fff0022);placement.hidden_count=0;placement.param_types={ADDRESS_VALUE};placement.entry_kind=1;
    auto object=std::make_shared<exec::context>();object->abi=OBJECT_VALUE;object->kind=3;object->width=4;
    object->factory_id=factory.function_id;object->placement_id=placement.function_id;
    auto buffer=std::make_shared<exec::context>();buffer->abi=OBJECT_VALUE;buffer->kind=4;buffer->element=object;
    auto instruction=node(37);instruction->contexts.push_back(buffer);
    SourceProgram source;source.functions={source_function(instruction),factory,placement};
    auto program=lower_program(source);expect(program.functions[0].contexts.size()==2,"nested context pool");
    auto corrupt=program;corrupt.functions[0].contexts.back().element=1;
    rejects([&]{verify_program(corrupt);},"cyclic context");
    corrupt=program;corrupt.functions[0].contexts[0].factory=0;
    rejects([&]{verify_program(corrupt);},"factory target signature");
    auto reference=std::make_shared<exec::context>();reference->reference=0;
    auto abi=node(36);abi->contexts.push_back(reference);program=lower(abi);
    corrupt=program;corrupt.functions[0].contexts[0].reference=1;
    rejects([&]{verify_program(corrupt);},"hidden context reference");
    corrupt=program;corrupt.instructions[find(corrupt,Opcode::bind_context)]={Opcode::tick,no_index,no_index,no_index,0,no_index};
    rejects([&]{verify_program(corrupt);},"used before initialization");
    auto orphan=node(2);orphan->immediate=1234;program=lower(orphan);
    expect(program.targets.back().kind==TargetKind::dynamic&&program.targets.back().function_id==1234,"missing calls remain deferred until execution");
    // Each module owns a lifecycle entry, with duplicate non-public ID 0.
    auto lifecycle=source_function(node(1),0);lifecycle.lifecycle=true;lifecycle.hidden_count=0;
    source.functions={lifecycle,lifecycle};source.external_targets.clear();program=lower_program(source);
    expect(program.functions.size()==2&&program.targets.empty(),"lifecycle identities use function indices");
}
void constant_fidelity_and_rejections() {
    auto zero=node(0);zero->constant=Value(-0.0);
    auto narrow=node(0);narrow->constant=Value(-0.0f);
    auto text=node(0);text->constant=Value(std::string("a\0\xf0\x9f\x8c\xb8",6));
    auto program=lower(node(1,{zero,narrow,text,pointer(),integer(std::numeric_limits<int>::min())}));
    expect(std::signbit(program.constants[0].get<double>()),"double signed zero preserved");
    expect(std::signbit(program.constants[1].get<float>()),"float signed zero preserved");
    expect(program.constants[2].get<std::string>().size()==6,"UTF-8 and NUL preserved");
    expect(program.constants[3].get<address>().value==std::numeric_limits<std::uint64_t>::max(),"full address bits preserved");
    auto corrupt=program;corrupt.constants[0]=Value(std::numeric_limits<double>::infinity());rejects([&]{verify_program(corrupt);},"nonfinite");
    corrupt=program;corrupt.constants[0]=Value(std::string("\xc0\x80",2));rejects([&]{verify_program(corrupt);},"UTF-8");
    corrupt=program;corrupt.instructions[0].opcode=static_cast<Opcode>(65535);rejects([&]{verify_program(corrupt);},"unknown instruction");
    corrupt=program;corrupt.instructions[0].result=0;rejects([&]{verify_program(corrupt);},"unused instruction operand");
    corrupt=program;corrupt.instructions[find(corrupt,Opcode::constant)].result=corrupt.functions[0].register_count;rejects([&]{verify_program(corrupt);},"register index");
    corrupt=program;corrupt.functions[0].global_offset=1048576;rejects([&]{verify_program(corrupt);},"function layout");
    rejects([&]{lower(node(59));},"unsupported wire opcode");
    rejects([&]{lower(node(54));},"invalid operand count for wire opcode 54");
    auto extra=integer();extra->contexts.push_back(scalar_context());
    rejects([&]{lower(extra);},"unexpected metadata");
    auto cycle=node(1);cycle->children.push_back(cycle);
    SourceProgram cyclic_source;SourceFunction cyclic_function;cyclic_function.function_id=0x0fff0010;cyclic_function.body=cycle;
    cyclic_source.functions.push_back(cyclic_function);
    rejects([&]{lower_program(cyclic_source);},"preorder");cycle->children.clear();
}
void reject_invalid_control() {
    auto program=lower(node(27,{boolean(),node(1,{integer()}),node(1,{integer()})}));
    auto corrupt=program;corrupt.instructions[find(corrupt,Opcode::branch_false)].target=corrupt.functions[0].end;
    rejects([&]{verify_program(corrupt);},"jump target");
    corrupt=program;const auto scope=find(corrupt,Opcode::scope_enter);
    corrupt.instructions[scope].opcode=Opcode::scope_leave;
    rejects([&]{verify_program(corrupt);},"unbalanced");
    corrupt=program;corrupt.instructions[find(corrupt,Opcode::branch_false)].target=static_cast<ProgramCounter>(scope+1);
    rejects([&]{verify_program(corrupt);},"Invalid VM program");
    auto maximum=source_function(slot(1048575));maximum.param_types={INT_VALUE};maximum.local_count=1048575;
    SourceProgram source;source.functions.push_back(maximum);verify_program(lower_program(source));
    auto invalid=slot(-3);rejects([&]{lower(invalid);},"global slot");
    rejects([&]{lower(node(6,{integer(),integer()}));},"assignment target");
    rejects([&]{lower(node(35,{integer()}));},"assignment target");

    ExecutableProgram spinning;FunctionRecord spin;spin.function_id=42;spin.begin=0;spin.end=1;spin.node_count=1;
    spinning.functions.push_back(spin);spinning.instructions.push_back({Opcode::jump,no_index,no_index,no_index,no_index,0});spinning.sources.push_back({});
    rejects([&]{verify_program(spinning);},"cycle without a budget point");
    spinning.instructions.insert(spinning.instructions.begin(),{Opcode::tick,no_index,no_index,no_index,0,no_index});
    spinning.sources.push_back({});spinning.functions[0].end=2;verify_program(spinning);

    auto cleanup=node(31,{integer(),integer()});program=lower(node(27,{boolean(),cleanup,integer()}));
    corrupt=program;corrupt.instructions[find(corrupt,Opcode::branch_false)].target=corrupt.functions[0].cleanups[0].finalizer;
    rejects([&]{verify_program(corrupt);},"control stacks");
}
}
int main() {
    try {
        scalar_order_and_control();short_circuit_and_locations();all_wire_operations();contexts_and_targets();
        constant_fidelity_and_rejections();reject_invalid_control();
        std::cout<<"VM program/lowering tests passed\n";return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
