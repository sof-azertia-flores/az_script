#include "lowering.h"
#include "../core/limits.h"
#include "../core/ids.h"
#include <algorithm>
#include <bit>
#include <limits>
#include <map>
#include <stdexcept>
#include <utility>

namespace azertian::vm {
namespace {
constexpr auto max_slots=static_cast<std::uint32_t>(limits::max_slots);
[[noreturn]] void bad(const std::string& message) {
    throw std::invalid_argument("VM lowering: "+message);
}
std::uint32_t index_of(std::size_t value) {
    if(value>=no_index)bad("program exceeds 32-bit index space");
    return static_cast<std::uint32_t>(value);
}
class Lowerer {
    ExecutableProgram program;
    std::map<int,std::uint32_t> targets;
    FunctionRecord* function=nullptr;
    Register next_register=0;
    std::uint32_t next_context=0,next_node=0;
    SourceMapEntry site;

    ProgramCounter pc() const {return index_of(program.instructions.size());}
    ProgramCounter emit(Opcode opcode,Register result=no_index,Register left=no_index,
            Register right=no_index,std::uint32_t operand=no_index,ProgramCounter target=no_index) {
        const auto at=pc();
        program.instructions.push_back({opcode,result,left,right,operand,target});
        program.sources.push_back(site);return at;
    }
    Register allocate() {
        if(next_register==no_index)bad("too many temporary registers");
        auto result=next_register++;
        function->register_count=std::max(function->register_count,next_register);return result;
    }
    std::uint32_t target(int id,bool allow_dynamic=false) {
        auto found=targets.find(id);
        if(found==targets.end()) {
            if(!allow_dynamic)bad("unresolved function ID "+std::to_string(id));
            CallTarget deferred;deferred.function_id=id;deferred.kind=TargetKind::dynamic;deferred.dynamic_signature=true;
            const auto result=index_of(program.targets.size());program.targets.push_back(std::move(deferred));
            targets.emplace(id,result);return result;
        }
        return found->second;
    }
    std::uint32_t operation(OperationData data) {
        auto result=index_of(function->operations.size());
        function->operations.push_back(std::move(data));return result;
    }
    void constant(Register result,const Value& value) {
        auto index=index_of(program.constants.size());program.constants.push_back(value);
        emit(Opcode::constant,result,no_index,no_index,index);
    }
    void nil(Register result) {constant(result,Value{});}
    void children(const SourceExpression& node,std::size_t low,std::size_t high) const {
        if(node.children.size()<low||node.children.size()>high)bad("invalid operand count for wire opcode "+std::to_string(node.opcode));
    }
    void contexts(const SourceExpression& node,std::size_t count) const {
        if(node.contexts.size()!=count)bad("invalid context count for wire opcode "+std::to_string(node.opcode));
    }
    void assignment_target(const SourceExpression& node) const {
        if(!node.children[0]||(node.children[0]->opcode!=3&&
            !(node.children[0]->opcode==2&&node.children[0]->immediate==ids::id(ids::builtin::mem_get))))
            bad("assignment target must be a variable or mem_get(pointer)");
    }
    std::uint32_t context(const std::shared_ptr<const SourceContext>& source,std::size_t depth=0) {
        if(!source||depth>=128)bad("null, cyclic or excessively nested context");
        Context result;result.reference=source->reference;result.abi=source->abi;
        result.kind=source->kind;result.width=source->width;
        if(source->factory_id)result.factory=target(*source->factory_id);
        if(source->placement_id)result.placement=target(*source->placement_id);
        for(const auto& item:source->contexts)result.contexts.push_back(context(item,depth+1));
        for(const auto& item:source->placement_contexts)result.placement_contexts.push_back(context(item,depth+1));
        if(source->element)result.element=context(source->element,depth+1);
        auto index=index_of(function->contexts.size());function->contexts.push_back(std::move(result));return index;
    }
    std::vector<std::uint32_t> context_list(const std::vector<std::shared_ptr<const SourceContext>>& list) {
        std::vector<std::uint32_t> result;result.reserve(list.size());
        for(const auto& item:list)result.push_back(context(item));
        return result;
    }
    std::uint32_t bind(const SourceExpression& node) {
        contexts(node,1);
        if(next_context==no_index)bad("too many bound context temporaries");
        const auto result=next_context++;
        function->bound_context_count=std::max(function->bound_context_count,next_context);
        emit(Opcode::bind_context,result,no_index,no_index,context(node.contexts[0]));return result;
    }
    Register child(const SourceExpression& node,std::size_t index,std::size_t depth) {
        auto parent=site;auto result=expression(node.children[index],depth+1);site=parent;return result;
    }
    std::uint32_t child_location(const SourceExpression& node,std::size_t index) const {
        if(index>=node.children.size()||!node.children[index])bad("null or missing child expression");
        return node.children[index]->location?node.children[index]->location:site.location;
    }
    Register condition(const SourceExpression& node,std::size_t index,std::size_t depth,bool temporary_boundary) {
        auto parent=site;site.location=child_location(node,index);
        if(temporary_boundary)emit(Opcode::statement_enter);
        const auto value=child(node,index,depth);
        // truth produces a scalar snapshot before condition temporaries expire.
        emit(Opcode::truth,value,value);
        if(temporary_boundary)emit(Opcode::statement_leave);
        site=parent;return value;
    }
    Register scoped_child(const SourceExpression& node,std::size_t index,std::size_t depth) {
        if(!node.children[index])bad("null expression");
        const bool block=node.children[index]->opcode==1;
        auto parent=site;site.location=child_location(node,index);
        if(!block){emit(Opcode::scope_enter);emit(Opcode::statement_enter);}
        const auto value=child(node,index,depth);
        if(!block){emit(Opcode::statement_leave);emit(Opcode::scope_leave);}
        site=parent;return value;
    }
    void copy(Register destination,Register source) {emit(Opcode::copy_reference,destination,source);}
    void arguments(const SourceExpression& node,std::size_t first,std::size_t depth,OperationData& data) {
        for(std::size_t i=first;i<node.children.size();++i) {
            const auto value=child(node,i,depth);emit(Opcode::materialize,value,value);
            data.arguments.push_back(value);
        }
    }
    Register expression(const std::shared_ptr<const SourceExpression>& source,std::size_t depth,bool root=false) {
        if(!source||depth>=128)bad("null, cyclic or excessively nested expression");
        const auto& node=*source;
        if(node.opcode<0||node.opcode>53)bad("unsupported wire opcode "+std::to_string(node.opcode));
        if(next_node==no_index||node.node!=next_node++)bad("expression nodes are not in wire preorder");
        const bool has_contexts=node.opcode==2||node.opcode==10||(node.opcode>=36&&node.opcode<=40)||node.opcode==42||node.opcode==51;
        if((!has_contexts&&!node.contexts.empty())||(node.opcode!=52&&!node.methods.empty())||
           (node.opcode!=51&&!node.slot_types.empty())||(node.opcode!=10&&(node.destructor_id||node.manual))||
           (node.opcode!=31&&node.on_error)||(node.opcode!=53&&node.return_type!=VOID_VALUE)||
           (node.opcode!=0&&node.constant.type_tag()!=VOID_VALUE))bad("unexpected metadata for wire opcode "+std::to_string(node.opcode));
        site={node.node,node.location?node.location:site.location};
        const auto parent=site;
        const auto result=allocate();
        const auto context_mark=next_context;
        // A block creates its lexical scope before its own budget point. The
        // function root block runs directly in the already-created frame scope.
        if(node.opcode==1&&!root)emit(Opcode::scope_enter);
        // cleanup catches failure of its own tick, so its handler is installed
        // before this point (see case 31).
        if(node.opcode!=31)emit(Opcode::tick,no_index,no_index,no_index,static_cast<std::uint32_t>(BudgetKind::expression));
        switch(node.opcode) {
        case 0:children(node,0,0);constant(result,node.constant);break;
        case 1: {
            std::vector<ProgramCounter> exits;
            for(std::size_t i=0;i<node.children.size();++i) {
                exits.push_back(emit(Opcode::branch_flow));
                site.location=child_location(node,i);emit(Opcode::statement_enter);site=parent;
                child(node,i,depth);site.location=child_location(node,i);emit(Opcode::statement_leave);site=parent;
                next_register=result+1;
            }
            const auto end=pc();for(auto at:exits)program.instructions[at].target=end;
            nil(result);if(!root)emit(Opcode::scope_leave);break;
        }
        case 2: {
            // This is the same decoder-only typed member path as v10. It must
            // not become a native mem_get callback with a second call charge.
            if(node.immediate==ids::id(ids::builtin::mem_get)&&node.contexts.empty()&&node.children.size()==1&&node.children[0]&&node.children[0]->opcode==9) {
                const auto value=child(node,0,depth);emit(Opcode::member_get,result,value);break;
            }
            OperationData data;data.target=target(node.immediate,true);
            arguments(node,0,depth,data);data.contexts=context_list(node.contexts);
            emit(Opcode::call,result,no_index,no_index,operation(std::move(data)));break;
        }
        case 3:children(node,0,0);emit(Opcode::load_slot,result,no_index,no_index,std::bit_cast<std::uint32_t>(node.immediate));break;
        case 4: {
            children(node,0,1);
            emit(Opcode::check_declaration,no_index,no_index,no_index,std::bit_cast<std::uint32_t>(node.immediate));
            const auto value=node.children.empty()?no_index:child(node,0,depth);
            emit(Opcode::declare_slot,no_index,value,no_index,std::bit_cast<std::uint32_t>(node.immediate));nil(result);break;
        }
        case 5: {
            children(node,1,1);auto slot=allocate();
            emit(Opcode::load_slot,slot,no_index,no_index,std::bit_cast<std::uint32_t>(node.immediate));
            const auto value=child(node,0,depth);emit(Opcode::store,result,slot,value);break;
        }
        case 6: {
            children(node,2,2);assignment_target(node);
            const auto left=child(node,0,depth),right=child(node,1,depth);
            emit(Opcode::store,result,left,right);break;
        }
        case 7:case 8: {
            children(node,node.opcode==8?1:0,1);
            auto value=node.children.empty()?allocate():child(node,0,depth);
            if(node.children.empty())nil(value);
            emit(Opcode::set_return,no_index,value,no_index,node.opcode==8?1:0);nil(result);break;
        }
        case 9: {
            children(node,1,1);const auto value=child(node,0,depth);
            emit(Opcode::object_address,result,value,no_index,std::bit_cast<std::uint32_t>(node.immediate));break;
        }
        case 10: {
            children(node,1,1);const auto value=child(node,0,depth);
            OperationData data;data.manual=node.manual;data.contexts=context_list(node.contexts);
            if(node.destructor_id)data.target=target(*node.destructor_id);
            emit(Opcode::object_register,result,value,no_index,operation(std::move(data)));break;
        }
        case 11:children(node,1,1);emit(Opcode::object_delete,result,child(node,0,depth));break;
        case 12:case 13:case 14:case 15:case 16:case 17:case 18:case 19:case 20:case 21:case 22:
        case 43:case 44:case 45:case 46:case 47:case 48: {
            children(node,2,2);const auto left=child(node,0,depth);emit(Opcode::snapshot,left,left);
            const auto right=child(node,1,depth);
            const auto op=node.opcode<=22?node.opcode-12:node.opcode-43+11;
            emit(Opcode::binary,result,left,right,static_cast<std::uint32_t>(op));break;
        }
        case 23:case 24: {
            children(node,2,2);const auto left=child(node,0,depth);
            emit(Opcode::snapshot,left,left);emit(Opcode::truth,result,left);
            auto branch=emit(Opcode::branch_false,no_index,result);
            ProgramCounter skip=no_index;
            if(node.opcode==24)skip=emit(Opcode::jump);
            if(node.opcode==24)program.instructions[branch].target=pc();
            next_register=result+1;
            const auto right=child(node,1,depth);emit(Opcode::truth,result,right);
            program.instructions[node.opcode==23?branch:skip].target=pc();break;
        }
        case 25:case 26: {
            children(node,1,1);const auto value=child(node,0,depth);emit(Opcode::snapshot,value,value);
            emit(Opcode::unary,result,value,no_index,node.opcode==25?0:1);break;
        }
        case 27:case 50: {
            children(node,node.opcode==27?2:3,3);
            const auto value=condition(node,0,depth,node.opcode==27);
            auto branch=emit(Opcode::branch_false,no_index,value);
            next_register=result+1;
            const auto yes=node.opcode==27?scoped_child(node,1,depth):child(node,1,depth);copy(result,yes);
            const auto skip=emit(Opcode::jump);program.instructions[branch].target=pc();
            next_register=result+1;
            if(node.children.size()==3) {
                const auto no=node.opcode==27?scoped_child(node,2,depth):child(node,2,depth);copy(result,no);
            } else nil(result);
            program.instructions[skip].target=pc();break;
        }
        case 28: {
            children(node,2,2);emit(Opcode::loop_enter);const auto head=pc();
            const auto returned=emit(Opcode::branch_returned);
            const auto value=condition(node,0,depth,true);
            const auto branch=emit(Opcode::branch_false,no_index,value);
            next_register=result+1;
            emit(Opcode::tick,no_index,no_index,no_index,static_cast<std::uint32_t>(BudgetKind::loop_iteration));
            scoped_child(node,1,depth);
            const auto next=emit(Opcode::loop_next,no_index,no_index,no_index,no_index,head);
            const auto exit=pc();program.instructions[returned].target=exit;program.instructions[branch].target=exit;
            program.instructions[next].operand=exit;emit(Opcode::loop_leave);nil(result);break;
        }
        case 29:case 30:
            children(node,0,0);emit(node.opcode==29?Opcode::set_break:Opcode::set_continue);nil(result);break;
        case 31: {
            children(node,2,2);const auto id=index_of(function->cleanups.size());function->cleanups.emplace_back();
            CleanupRegion region;region.on_error=node.on_error;
            region.enter=emit(Opcode::cleanup_enter,no_index,no_index,no_index,id);region.body=pc();
            emit(Opcode::tick,no_index,no_index,no_index,static_cast<std::uint32_t>(BudgetKind::expression));
            scoped_child(node,0,depth);next_register=result+1;
            region.leave=emit(Opcode::cleanup_leave,no_index,no_index,no_index,id);region.finalizer=pc();
            scoped_child(node,1,depth);next_register=result+1;
            region.finish=emit(Opcode::cleanup_end,no_index,no_index,no_index,id);region.after=pc();
            function->cleanups[id]=region;nil(result);break;
        }
        case 32:children(node,0,0);emit(Opcode::new_block,result,no_index,no_index,std::bit_cast<std::uint32_t>(node.immediate));break;
        case 33:children(node,1,1);emit(Opcode::block_address,result,child(node,0,depth));break;
        case 34:children(node,0,0);emit(Opcode::move_out,result,no_index,no_index,std::bit_cast<std::uint32_t>(node.immediate));break;
        case 35:children(node,1,1);assignment_target(node);emit(Opcode::drop,result,child(node,0,depth));break;
        case 36:case 37:case 40: {
            children(node,0,0);const auto bound=bind(node);
            emit(node.opcode==36?Opcode::context_abi:node.opcode==37?Opcode::context_default:Opcode::buffer_new,result,bound);break;
        }
        case 38:case 39: {
            children(node,1,1);const auto bound=bind(node);const auto value=child(node,0,depth);
            emit(node.opcode==38?Opcode::return_typed:Opcode::check_type,result,value,bound);break;
        }
        case 41: {
            children(node,1,max_slots);OperationData data;data.immediate=node.immediate;
            const auto receiver=child(node,0,depth);
            // Preserve receiver identity before argument evaluation; a later
            // argument may overwrite the receiver variable.
            emit(Opcode::snapshot,receiver,receiver);
            arguments(node,1,depth,data);
            emit(Opcode::buffer_operation,result,receiver,no_index,operation(std::move(data)));break;
        }
        case 42: {
            children(node,2,2);const auto left=child(node,0,depth);emit(Opcode::materialize,left,left);
            const auto right=child(node,1,depth);emit(Opcode::materialize,right,right);
            const auto bound=bind(node);emit(Opcode::value_compare,result,left,right,bound);break;
        }
        case 49:children(node,1,1);emit(Opcode::convert,result,child(node,0,depth),no_index,std::bit_cast<std::uint32_t>(node.immediate));break;
        case 51: {
            children(node,1,1);const auto value=child(node,0,depth),bound=bind(node);
            OperationData data;data.slot_types=node.slot_types;
            emit(Opcode::check_object,result,value,bound,operation(std::move(data)));break;
        }
        case 52: {
            children(node,1,1);const auto value=child(node,0,depth);
            // Receiver type rejection precedes every per-entry tick.
            emit(Opcode::check_method_receiver,no_index,value);
            OperationData data;
            for(const auto& method:node.methods) {
                emit(Opcode::tick,no_index,no_index,no_index,static_cast<std::uint32_t>(BudgetKind::method_entry));
                data.methods.push_back({target(method.function_id),context_list(method.contexts)});
            }
            emit(Opcode::set_methods,result,value,no_index,operation(std::move(data)));break;
        }
        case 53: {
            children(node,1,max_slots);OperationData data;data.immediate=node.immediate;data.return_type=node.return_type;
            arguments(node,0,depth,data);emit(Opcode::call_virtual,result,no_index,no_index,operation(std::move(data)));break;
        }
        default:bad("unsupported wire opcode "+std::to_string(node.opcode));
        }
        site=parent;next_register=result+1;next_context=context_mark;return result;
    }
public:
    ExecutableProgram lower(const SourceProgram& source) {
        program.functions.reserve(source.functions.size());
        for(std::size_t i=0;i<source.functions.size();++i) {
            const auto& source_function=source.functions[i];
            if(source_function.lifecycle)continue;
            CallTarget target;target.function_id=source_function.function_id;target.function=index_of(i);
            target.return_type=source_function.return_type;target.param_types=source_function.param_types;
            target.hidden_count=source_function.hidden_count;target.entry_kind=source_function.entry_kind;
            if(!targets.emplace(target.function_id,index_of(program.targets.size())).second)bad("duplicate script function ID");
            program.targets.push_back(std::move(target));
        }
        for(const auto& target:source.external_targets) {
            if(target.kind==TargetKind::script||target.function!=no_index)bad("external target refers to a script function");
            if(!targets.emplace(target.function_id,index_of(program.targets.size())).second)bad("duplicate external function ID");
            program.targets.push_back(target);
        }
        for(const auto& source_function:source.functions) {
            program.functions.emplace_back();function=&program.functions.back();
            function->function_id=source_function.function_id;function->return_type=source_function.return_type;
            function->lifecycle=source_function.lifecycle;
            function->entry_kind=source_function.entry_kind;function->param_types=source_function.param_types;
            function->local_count=source_function.local_count;function->hidden_count=source_function.hidden_count;
            function->global_offset=source_function.global_offset;function->global_count=source_function.global_count;
            function->debug=source_function.debug;function->begin=pc();
            next_register=0;next_context=0;next_node=0;site={};
            expression(source_function.body,0,true);emit(Opcode::function_end);
            function->end=pc();function->node_count=next_node;
        }
        verify_program(program);return std::move(program);
    }
};
}
ExecutableProgram lower_program(const SourceProgram& source) {return Lowerer{}.lower(source);}
}
