#include "program.h"
#include "../core/limits.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <deque>
#include <map>
#include <set>
#include <stdexcept>
#include <tuple>

namespace azertian::vm {
namespace {
constexpr auto max_slots=static_cast<std::uint32_t>(limits::max_slots);
[[noreturn]] void bad(const std::string& message) {throw std::invalid_argument("Invalid VM program: "+message);}
bool type(int value,bool allow_void=true) {
    return value>=INT_VALUE&&value<=OBJECT_VALUE&&(allow_void||value!=VOID_VALUE);
}
bool context_kind(int abi,int kind) {
    return (kind==0&&abi>=INT_VALUE&&abi<=VOID_VALUE)||((kind==1||kind==2)&&abi==ADDRESS_VALUE)||
        ((kind==3||kind==4)&&abi==OBJECT_VALUE);
}
void valid_utf8(const std::string& text,bool display=false) {
    for(std::size_t i=0;i<text.size();) {
        const auto first=static_cast<unsigned char>(text[i++]);
        std::uint32_t scalar=first;std::size_t continuation=0;std::uint32_t minimum=0;
        if(first>=0xc2&&first<=0xdf){scalar=first&31;continuation=1;minimum=0x80;}
        else if(first>=0xe0&&first<=0xef){scalar=first&15;continuation=2;minimum=0x800;}
        else if(first>=0xf0&&first<=0xf4){scalar=first&7;continuation=3;minimum=0x10000;}
        else if(first>=0x80)bad("invalid UTF-8 string");
        if(text.size()-i<continuation)bad("truncated UTF-8 string");
        while(continuation--) {
            const auto next=static_cast<unsigned char>(text[i++]);
            if((next&0xc0)!=0x80)bad("invalid UTF-8 continuation");
            scalar=(scalar<<6)|(next&63);
        }
        if(scalar<minimum||scalar>0x10ffff||(scalar>=0xd800&&scalar<=0xdfff))bad("invalid UTF-8 scalar");
        if(display&&(scalar<0x20||(scalar>=0x7f&&scalar<=0x9f)||scalar==0x2028||scalar==0x2029||
            scalar==0x61c||scalar==0x200e||scalar==0x200f||(scalar>=0x202a&&scalar<=0x202e)||
            (scalar>=0x2066&&scalar<=0x2069)))bad("unsafe debug text");
    }
}
void debug_info(const DebugInfo& debug) {
    if(debug.name.empty())bad("empty debug function name");
    valid_utf8(debug.name,true);
    if(!debug.files||debug.files->empty()||debug.files->size()>max_slots||debug.locations.size()>max_slots)bad("invalid debug table size");
    std::set<std::string> files;
    for(const auto& file:*debug.files) {
        if(file.empty()||!files.insert(file).second)bad("empty or duplicate debug file");
        valid_utf8(file,true);
    }
    const auto position=[&](const SourcePosition& position,bool header) {
        if(position.file>=debug.files->size()||(position.line==0)!=(position.column==0)||(!header&&!position.line))bad("invalid source position");
    };
    position(debug.header,true);for(const auto& at:debug.locations)position(at,false);
}
const CallTarget& call_target(const ExecutableProgram& program,std::uint32_t index) {
    if(index>=program.targets.size())bad("call target index outside table");
    return program.targets[index];
}
void check_contexts(const ExecutableProgram& program,const FunctionRecord& function) {
    std::vector<std::uint8_t> colors(function.contexts.size());
    std::vector<std::uint32_t> heights(function.contexts.size());
    const auto visit=[&](auto&& self,std::uint32_t index,std::uint32_t depth)->std::uint32_t {
        if(index>=function.contexts.size())bad("context index outside table");
        if(depth>=128)bad("context nesting exceeds 128 levels");
        if(colors[index]==1)bad("cyclic context table");
        if(colors[index]==2){if(depth+heights[index]>128)bad("context nesting exceeds 128 levels");return heights[index];}
        colors[index]=1;const auto& context=function.contexts[index];std::uint32_t height=1;
        const auto child=[&](std::uint32_t child_index) {height=std::max(height,1+self(self,child_index,depth+1));};
        if(context.reference) {
            if(*context.reference>=function.hidden_count)bad("hidden context reference outside function");
            if(context.factory||context.placement||context.element||!context.contexts.empty()||!context.placement_contexts.empty())bad("reference context contains fixed data");
        } else {
            if(!context_kind(context.abi,context.kind)||!context.width||context.width>max_slots||(context.kind!=3&&context.width!=1))bad("invalid fixed context layout");
            if(context.kind!=3&&(context.factory||context.placement||!context.contexts.empty()||!context.placement_contexts.empty()))bad("factory or placement on non-class context");
            if(!context.factory&&!context.contexts.empty())bad("factory contexts without factory");
            if(!context.placement&&!context.placement_contexts.empty())bad("placement contexts without placement");
            if((context.kind==4)!=context.element.has_value())bad("buffer context element mismatch");
            if(context.factory) {
                const auto& target=call_target(program,*context.factory);
                if(target.kind!=TargetKind::script||target.return_type!=OBJECT_VALUE||!target.param_types.empty()||target.hidden_count!=context.contexts.size())bad("invalid factory target signature");
            }
            if(context.placement) {
                const auto& target=call_target(program,*context.placement);
                if(target.kind!=TargetKind::script||target.return_type!=VOID_VALUE||target.param_types!=std::vector<int>{ADDRESS_VALUE}||target.entry_kind!=1||target.hidden_count!=context.placement_contexts.size())bad("invalid placement target signature");
            }
            if(context.element) {
                child(*context.element);
                const auto& element=function.contexts[*context.element];
                if(!element.reference&&element.abi==VOID_VALUE)bad("void buffer element");
            }
            for(auto item:context.contexts)child(item);
            for(auto item:context.placement_contexts)child(item);
        }
        colors[index]=2;heights[index]=height;return height;
    };
    for(std::size_t i=0;i<function.contexts.size();++i)visit(visit,static_cast<std::uint32_t>(i),0);
}
// Each stack node is interned by its parent and the entering PC. Identity (not
// only depth) prevents a jump between distinct sibling lexical scopes.
class StackStates {
    struct Node {std::uint32_t parent;ProgramCounter enter;};
    std::vector<Node> nodes{{0,no_index}};
    std::map<std::pair<std::uint32_t,ProgramCounter>,std::uint32_t> interned;
public:
    std::uint32_t push(std::uint32_t parent,ProgramCounter enter) {
        const auto key=std::pair{parent,enter};auto found=interned.find(key);
        if(found!=interned.end())return found->second;
        if(nodes.size()>=no_index)bad("too many verifier stack states");
        const auto result=static_cast<std::uint32_t>(nodes.size());nodes.push_back({parent,enter});interned.emplace(key,result);return result;
    }
    std::uint32_t pop(std::uint32_t state) const {if(!state)bad("unbalanced scope, statement, loop or cleanup");return nodes[state].parent;}
    ProgramCounter enter(std::uint32_t state) const {if(!state)bad("missing control stack entry");return nodes[state].enter;}
};
struct FlowState {
    std::uint32_t scope=0,statement=0,loop=0,cleanup=0,finalizer=0;
    bool operator==(const FlowState&) const=default;
};
void verify_flow(const ExecutableProgram& program,const FunctionRecord& function) {
    std::vector<std::optional<FlowState>> states(function.end-function.begin);
    // Bound context temporaries are nested along expression depth (at most
    // 128); unlike value registers they cannot safely start as void.
    using BoundSet=std::array<std::uint64_t,2>;
    std::vector<BoundSet> bound_states(function.bound_context_count?states.size():0);
    std::deque<ProgramCounter> work;StackStates scopes,statements,loops,cleanups,finalizers;
    const auto queue=[&](ProgramCounter pc,const FlowState& state,const BoundSet& bound) {
        if(pc<function.begin||pc>=function.end)bad("control flow leaves function");
        auto& previous=states[pc-function.begin];
        if(previous) {
            if(*previous!=state)bad("incompatible control stacks at join");
            if(!bound_states.empty()) {
                auto& existing=bound_states[pc-function.begin];const BoundSet intersection{existing[0]&bound[0],existing[1]&bound[1]};
                if(existing!=intersection){existing=intersection;work.push_back(pc);}
            }
        } else {
            previous=state;if(!bound_states.empty())bound_states[pc-function.begin]=bound;work.push_back(pc);
        }
    };
    queue(function.begin,{},{});
    while(!work.empty()) {
        const auto pc=work.front();work.pop_front();auto state=*states[pc-function.begin];const auto& instruction=program.instructions[pc];
        BoundSet bound=bound_states.empty()?BoundSet{}:bound_states[pc-function.begin];
        const auto bound_input=[&](std::uint32_t index) {
            if(!(bound[index/64]&(std::uint64_t{1}<<(index%64))))bad("bound context used before initialization");
        };
        switch(instruction.opcode) {
        case Opcode::bind_context:bound[instruction.result/64]|=std::uint64_t{1}<<(instruction.result%64);break;
        case Opcode::context_abi:case Opcode::context_default:case Opcode::buffer_new:bound_input(instruction.left);break;
        case Opcode::return_typed:case Opcode::check_type:case Opcode::check_object:bound_input(instruction.right);break;
        case Opcode::value_compare:bound_input(instruction.operand);break;
        default:break;
        }
        switch(instruction.opcode) {
        case Opcode::scope_enter:state.scope=scopes.push(state.scope,pc);break;
        case Opcode::scope_leave:state.scope=scopes.pop(state.scope);break;
        case Opcode::statement_enter:state.statement=statements.push(state.statement,pc);break;
        case Opcode::statement_leave:state.statement=statements.pop(state.statement);break;
        case Opcode::loop_enter:state.loop=loops.push(state.loop,pc);break;
        case Opcode::loop_leave:state.loop=loops.pop(state.loop);break;
        case Opcode::jump:queue(instruction.target,state,bound);continue;
        case Opcode::branch_false:case Opcode::branch_flow:case Opcode::branch_returned:queue(instruction.target,state,bound);break;
        case Opcode::loop_next:
            if(!state.loop)bad("loop continuation outside loop");
            queue(instruction.target,state,bound);queue(instruction.operand,state,bound);continue;
        case Opcode::cleanup_enter: {
            const auto& region=function.cleanups[instruction.operand];
            // An exceptional body path first unwinds to this state; finalizer
            // execution retains the pending error/flow in its continuation.
            auto exceptional=state;exceptional.finalizer=finalizers.push(state.finalizer,region.enter);
            queue(region.finalizer,exceptional,bound);state.cleanup=cleanups.push(state.cleanup,pc);break;
        }
        case Opcode::cleanup_leave: {
            const auto& region=function.cleanups[instruction.operand];
            if(cleanups.enter(state.cleanup)!=region.enter)bad("cleanup leave does not match handler");
            state.cleanup=cleanups.pop(state.cleanup);
            auto finalizing=state;finalizing.finalizer=finalizers.push(state.finalizer,region.enter);
            queue(region.finalizer,finalizing,bound);if(region.on_error)queue(region.after,state,bound);continue;
        }
        case Opcode::cleanup_end: {
            const auto& region=function.cleanups[instruction.operand];
            if(finalizers.enter(state.finalizer)!=region.enter)bad("cleanup finish has no matching continuation");
            state.finalizer=finalizers.pop(state.finalizer);queue(region.after,state,bound);continue;
        }
        case Opcode::function_end:
            if(state.scope||state.statement||state.loop||state.cleanup||state.finalizer)bad("function ends with pending control records");
            continue;
        default:break;
        }
        queue(pc+1,state,bound);
    }
    // There is no generic unreachable-code escape: every generated instruction
    // must be entered normally or as a registered cleanup finalizer.
    if(std::any_of(states.begin(),states.end(),[](const auto& state){return !state;}))bad("unreachable instruction outside registered control flow");
}
void verify_metered_cycles(const ExecutableProgram& program,const FunctionRecord& function) {
    // A valid program can intentionally loop until the step budget expires.
    // Removing tick vertices must nevertheless leave an acyclic graph; an
    // internal jump corruption may never create an unmetered dispatch loop.
    const auto successors=[&](ProgramCounter pc,const auto& visit) {
        const auto& instruction=program.instructions[pc];
        switch(instruction.opcode) {
        case Opcode::function_end:return;
        case Opcode::jump:visit(instruction.target);return;
        case Opcode::branch_false:case Opcode::branch_flow:case Opcode::branch_returned:visit(instruction.target);break;
        case Opcode::loop_next:visit(instruction.target);visit(instruction.operand);return;
        case Opcode::cleanup_enter:visit(function.cleanups[instruction.operand].finalizer);break;
        case Opcode::cleanup_leave: {
            const auto& region=function.cleanups[instruction.operand];visit(region.finalizer);
            if(region.on_error)visit(region.after);
            return;
        }
        case Opcode::cleanup_end:visit(function.cleanups[instruction.operand].after);return;
        default:break;
        }
        visit(pc+1);
    };
    std::vector<std::uint32_t> incoming(function.end-function.begin);
    std::size_t remaining=0;
    for(auto pc=function.begin;pc<function.end;++pc) {
        if(program.instructions[pc].opcode==Opcode::tick)continue;
        ++remaining;
        successors(pc,[&](ProgramCounter next) {
            if(program.instructions[next].opcode!=Opcode::tick)++incoming[next-function.begin];
        });
    }
    std::vector<ProgramCounter> ready;ready.reserve(remaining);
    for(auto pc=function.begin;pc<function.end;++pc)
        if(program.instructions[pc].opcode!=Opcode::tick&&!incoming[pc-function.begin])ready.push_back(pc);
    while(!ready.empty()) {
        const auto pc=ready.back();ready.pop_back();--remaining;
        successors(pc,[&](ProgramCounter next) {
            if(program.instructions[next].opcode!=Opcode::tick&&!--incoming[next-function.begin])ready.push_back(next);
        });
    }
    if(remaining)bad("control-flow cycle without a budget point");
}
void check_instruction(const ExecutableProgram& program,const FunctionRecord& function,ProgramCounter pc) {
    const auto& instruction=program.instructions[pc];
    bool result=false,left=false,right=false,operand=false,target=false;
    const auto reg=[&](Register value) {if(value>=function.register_count)bad("register index outside function");};
    const auto bound=[&](std::uint32_t value) {if(value>=function.bound_context_count)bad("bound context index outside function");};
    const auto output=[&] {result=true;reg(instruction.result);};
    const auto input=[&] {left=true;reg(instruction.left);};
    const auto second=[&] {right=true;reg(instruction.right);};
    const auto destination=[&] {target=true;if(instruction.target<function.begin||instruction.target>=function.end)bad("jump target outside function");};
    const auto slot=[&](bool declaration=false,bool local=false) {
        operand=true;const auto index=std::bit_cast<std::int32_t>(instruction.operand);
        if(index<0) {
            if(declaration||local||static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1)>=function.global_count)bad("global slot outside module");
        } else if(static_cast<std::uint64_t>(index)>=function.param_types.size()+function.local_count||
                  (declaration&&static_cast<std::size_t>(index)<function.param_types.size()))bad("local slot outside function");
    };
    const auto data=[&]() -> const OperationData& {
        operand=true;if(instruction.operand>=function.operations.size())bad("operation data index outside function");
        return function.operations[instruction.operand];
    };
    const auto cleanup=[&]() -> const CleanupRegion& {
        operand=true;if(instruction.operand>=function.cleanups.size())bad("cleanup index outside function");
        return function.cleanups[instruction.operand];
    };
    switch(instruction.opcode) {
    case Opcode::tick:operand=true;if(instruction.operand>static_cast<std::uint32_t>(BudgetKind::method_entry))bad("unknown budget point");break;
    case Opcode::constant:output();operand=true;if(instruction.operand>=program.constants.size())bad("constant index outside pool");break;
    case Opcode::load_slot:output();slot();break;
    case Opcode::check_declaration:slot(true);break;
    case Opcode::declare_slot:slot(true);if(instruction.left!=no_index)input();break;
    case Opcode::store:output();input();second();break;
    case Opcode::snapshot:case Opcode::materialize:case Opcode::copy_reference:case Opcode::truth:
    case Opcode::member_get:case Opcode::object_delete:case Opcode::block_address:case Opcode::drop:
        output();input();break;
    case Opcode::unary:output();input();operand=true;if(instruction.operand>1)bad("unknown unary operation");break;
    case Opcode::binary:output();input();second();operand=true;if(instruction.operand>static_cast<std::uint32_t>(BinaryOperation::wrapping_multiply))bad("unknown binary operation");break;
    case Opcode::convert:output();input();operand=true;if(instruction.operand!=INT_VALUE&&instruction.operand!=STRING_VALUE&&instruction.operand!=FLOAT_VALUE&&instruction.operand!=DOUBLE_VALUE&&instruction.operand!=ADDRESS_VALUE)bad("invalid conversion target");break;
    case Opcode::jump:case Opcode::branch_flow:case Opcode::branch_returned:destination();break;
    case Opcode::branch_false:input();destination();break;
    case Opcode::scope_enter:case Opcode::scope_leave:case Opcode::statement_enter:case Opcode::statement_leave:
    case Opcode::loop_enter:case Opcode::loop_leave:case Opcode::set_break:case Opcode::set_continue:case Opcode::function_end:break;
    case Opcode::loop_next:
        destination();operand=true;if(instruction.operand<function.begin||instruction.operand>=function.end)bad("loop exit outside function");break;
    case Opcode::call: {
        output();const auto& call=data();if(!call.target)bad("call has no target");
        const auto& target=call_target(program,*call.target);
        if(call.contexts.size()!=target.hidden_count)bad("call hidden context count mismatch");
        // Explicit argument count/type errors remain runtime checks after
        // argument side effects; v10 accepts those malformed call sites.
        break;
    }
    case Opcode::set_return:input();operand=true;if(instruction.operand>1)bad("unknown return ownership mode");break;
    case Opcode::cleanup_enter:if(cleanup().enter!=pc)bad("cleanup entry PC mismatch");break;
    case Opcode::cleanup_leave:if(cleanup().leave!=pc)bad("cleanup leave PC mismatch");break;
    case Opcode::cleanup_end:if(cleanup().finish!=pc)bad("cleanup finish PC mismatch");break;
    case Opcode::object_address:output();input();operand=true;if(instruction.operand>static_cast<std::uint32_t>(std::numeric_limits<int>::max()))bad("negative member offset");break;
    case Opcode::object_register: {
        output();input();const auto& registration=data();
        if(registration.target) {
            const auto& target=call_target(program,*registration.target);
            if(target.kind!=TargetKind::script||target.return_type!=VOID_VALUE||target.param_types!=std::vector<int>{ADDRESS_VALUE}||target.hidden_count!=registration.contexts.size())bad("invalid object destructor signature");
        } else if(!registration.contexts.empty())bad("destructor contexts without destructor");break;
    }
    case Opcode::new_block:output();operand=true;if(!instruction.operand||instruction.operand>max_slots)bad("invalid block width");break;
    case Opcode::move_out:output();slot(false,true);break;
    case Opcode::bind_context:result=true;bound(instruction.result);operand=true;if(instruction.operand>=function.contexts.size())bad("context source outside table");break;
    case Opcode::context_abi:case Opcode::context_default:case Opcode::buffer_new:output();left=true;bound(instruction.left);break;
    case Opcode::return_typed:case Opcode::check_type:output();input();right=true;bound(instruction.right);break;
    case Opcode::buffer_operation: {
        output();input();const auto& operation=data();constexpr std::size_t counts[]{0,0,1,1,2,1,1};
        if(operation.immediate<0||operation.immediate>6||operation.arguments.size()!=counts[operation.immediate])bad("invalid buffer argument layout");
        break;
    }
    case Opcode::value_compare:output();input();second();operand=true;bound(instruction.operand);break;
    case Opcode::check_object:output();input();right=true;bound(instruction.right);data();break;
    case Opcode::check_method_receiver:input();break;
    case Opcode::set_methods: {
        output();input();for(const auto& method:data().methods) {
            const auto& target=call_target(program,method.target);
            if(target.kind!=TargetKind::script||target.entry_kind!=0||target.param_types.empty()||target.param_types[0]!=ADDRESS_VALUE||target.hidden_count!=method.contexts.size())bad("invalid virtual method target");
        }break;
    }
    case Opcode::call_virtual: {
        output();const auto& call=data();
        if(call.immediate<0||static_cast<std::uint32_t>(call.immediate)>=max_slots||call.arguments.empty()||!type(call.return_type))bad("invalid virtual call layout");
        break;
    }
    case Opcode::guard_method: {
        output();input();const auto& guard=data();
        if(guard.immediate<0||static_cast<std::uint32_t>(guard.immediate)>=max_slots||guard.methods.size()!=1||!guard.methods[0].contexts.empty())
            bad("invalid method guard layout");
        const auto& target=call_target(program,guard.methods[0].target);
        if(target.kind!=TargetKind::script||target.entry_kind!=0||target.param_types.empty()||target.param_types[0]!=ADDRESS_VALUE||target.hidden_count)
            bad("invalid method guard target");
        break;
    }
    default:bad("unknown instruction opcode");
    }
    if((!result&&instruction.result!=no_index)||(!left&&instruction.left!=no_index)||(!right&&instruction.right!=no_index)||
       (!operand&&instruction.operand!=no_index)||(!target&&instruction.target!=no_index))bad("nonempty unused instruction operand");
}
}

void verify_program(const ExecutableProgram& program) {
    if(program.instructions.size()>=no_index||program.sources.size()!=program.instructions.size()||program.functions.size()>=no_index||program.constants.size()>=no_index||program.targets.size()>=no_index)bad("invalid program table sizes");
    for(const auto& constant:program.constants) {
        if(constant.type_tag()==OBJECT_VALUE||constant.type_tag()==ANY_VALUE)bad("object or any constant");
        if(constant.type_tag()==STRING_VALUE)valid_utf8(constant.get<std::string>());
        if(constant.type_tag()==FLOAT_VALUE&&!std::isfinite(constant.get<float>()))bad("nonfinite float constant");
        if(constant.type_tag()==DOUBLE_VALUE&&!std::isfinite(constant.get<double>()))bad("nonfinite double constant");
    }
    std::set<int> public_ids;
    for(const auto& target:program.targets) {
        if(!public_ids.insert(target.function_id).second)bad("duplicate public function ID");
        if(!type(target.return_type)||target.hidden_count>max_slots||target.param_types.size()>max_slots||target.entry_kind<0||target.entry_kind>2)bad("invalid call target signature");
        for(auto parameter:target.param_types)if(!type(parameter,false))bad("invalid target parameter type");
        if(target.dynamic_signature&&target.kind==TargetKind::script)bad("dynamic signature on script target");
        if(target.kind==TargetKind::dynamic&&!target.dynamic_signature)bad("dynamic target requires dynamic signature");
        if(target.kind==TargetKind::script) {
            if(target.function>=program.functions.size())bad("script target function index outside program");
            const auto& function=program.functions[target.function];
            if(function.lifecycle||target.function_id!=function.function_id||target.return_type!=function.return_type||target.param_types!=function.param_types||target.hidden_count!=function.hidden_count||target.entry_kind!=function.entry_kind)bad("script target signature differs from function");
        } else if(target.kind!=TargetKind::host&&target.kind!=TargetKind::builtin&&target.kind!=TargetKind::dynamic)bad("unknown call target kind");
        else if(target.function!=no_index)bad("external target has a script index");
    }
    ProgramCounter next=0;std::set<int> function_ids;
    for(const auto& function:program.functions) {
        if(function.begin!=next||function.end<=function.begin||function.end>program.instructions.size())bad("function instruction ranges do not partition program");
        next=function.end;
        if(!type(function.return_type)||function.entry_kind<0||function.entry_kind>2||function.param_types.size()>max_slots||function.local_count>max_slots-function.param_types.size()||function.hidden_count>max_slots||function.global_count>max_slots||function.global_offset>max_slots-function.global_count||function.bound_context_count>128||!function.node_count)bad("invalid function layout");
        for(auto parameter:function.param_types)if(!type(parameter,false))bad("invalid function parameter type");
        if(function.lifecycle) {
            if((function.function_id!=0&&function.function_id!=1)||function.return_type!=VOID_VALUE||!function.param_types.empty()||function.hidden_count)bad("invalid lifecycle signature");
        } else if(!function_ids.insert(function.function_id).second)bad("duplicate function ID");
        if(function.debug)debug_info(*function.debug);
        check_contexts(program,function);
        for(const auto& data:function.operations) {
            if(data.arguments.size()>max_slots||data.contexts.size()>max_slots||data.methods.size()>max_slots||data.slot_types.size()>max_slots)bad("oversized operation data");
            for(auto argument:data.arguments)if(argument>=function.register_count)bad("argument register outside function");
            for(auto context:data.contexts)if(context>=function.contexts.size())bad("operation context outside table");
            for(auto slot_type:data.slot_types)if(!type(slot_type,false))bad("invalid object slot type");
            if(data.target)call_target(program,*data.target);
            for(const auto& method:data.methods) {
                call_target(program,method.target);
                if(method.contexts.size()>max_slots)bad("oversized method contexts");
                for(auto context:method.contexts)if(context>=function.contexts.size())bad("method context outside table");
            }
        }
        for(const auto& cleanup:function.cleanups) {
            if(cleanup.enter<function.begin||cleanup.body!=cleanup.enter+1||cleanup.leave<cleanup.body||cleanup.finalizer!=cleanup.leave+1||cleanup.finish<cleanup.finalizer||cleanup.after!=cleanup.finish+1||cleanup.after>=function.end)bad("invalid cleanup region");
            if(program.instructions[cleanup.enter].opcode!=Opcode::cleanup_enter||program.instructions[cleanup.leave].opcode!=Opcode::cleanup_leave||program.instructions[cleanup.finish].opcode!=Opcode::cleanup_end)bad("cleanup boundaries have wrong opcodes");
        }
        for(auto pc=function.begin;pc<function.end;++pc) {
            const auto& source=program.sources[pc];
            if(source.node>=function.node_count||source.location>(function.debug?function.debug->locations.size():0))bad("source map outside function");
            check_instruction(program,function,pc);
        }
        verify_flow(program,function);verify_metered_cycles(program,function);
    }
    if(next!=program.instructions.size())bad("instructions are not owned by a function");
}

const char* opcode_name(Opcode opcode) noexcept {
    static constexpr const char* names[]{
        "tick","constant","load_slot","check_declaration","declare_slot","store",
        "snapshot","materialize","copy_reference","truth","unary","binary","convert",
        "jump","branch_false","branch_flow","branch_returned",
        "scope_enter","scope_leave","statement_enter","statement_leave",
        "loop_enter","loop_next","loop_leave","set_break","set_continue",
        "call","set_return","function_end","cleanup_enter","cleanup_leave","cleanup_end",
        "member_get","object_address","object_register","object_delete","new_block","block_address","move_out","drop",
        "bind_context","context_abi","context_default","return_typed","check_type",
        "buffer_new","buffer_operation","value_compare","check_object","check_method_receiver","set_methods","call_virtual","guard_method"
    };
    static_assert(std::size(names)==static_cast<std::size_t>(Opcode::count));
    const auto index=static_cast<std::size_t>(opcode);return index<std::size(names)?names[index]:"unknown";
}
void require_capabilities(const ExecutableProgram& program,std::span<const Opcode> supported) {
    std::array<bool,static_cast<std::size_t>(Opcode::count)> available{};
    for(auto opcode:supported) {
        const auto index=static_cast<std::size_t>(opcode);if(index>=available.size())bad("unknown backend capability");available[index]=true;
    }
    std::set<std::string> missing;
    for(const auto& instruction:program.instructions) {
        const auto index=static_cast<std::size_t>(instruction.opcode);
        if(index>=available.size()||!available[index])missing.insert(opcode_name(instruction.opcode));
    }
    if(!missing.empty()) {
        std::string message="VM backend does not support:";for(const auto& name:missing)message+=' '+name;
        throw std::invalid_argument(message);
    }
}
}
