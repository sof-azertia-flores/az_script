#include "internal.h"
#include <stdexcept>

namespace azertian::tree {
// The expression tree is built from the validated core model. References are
// recorded in the model's discovery order, so the linker can patch them in place.
using X=std::shared_ptr<expression>;
template<class T> X raw_binary(X a,X b) {
    auto node=std::make_shared<T>();node->exp1=std::move(a);node->exp2=std::move(b);return node;
}
class tree_builder {
    std::vector<function_reference>& references;
    std::shared_ptr<context_spec> context(const exec::context& source) {
        auto spec=std::make_shared<context_spec>();
        if(source.reference){spec->ref=static_cast<int>(*source.reference);return spec;}
        spec->abi=source.abi;spec->kind=source.kind;spec->width=static_cast<int>(source.width);
        spec->factory_id=source.factory_id;spec->contexts=contexts(source.contexts);
        spec->placement_id=source.placement_id;spec->placement_contexts=contexts(source.placement_contexts);
        if(source.element)spec->element=context(*source.element);
        if(spec->placement_id)references.push_back({*spec->placement_id,&*spec->placement_id,false,static_cast<int>(spec->placement_contexts.size()),false,true});
        if(spec->factory_id)references.push_back({*spec->factory_id,&*spec->factory_id,false,static_cast<int>(spec->contexts.size()),true});
        return spec;
    }
    std::vector<std::shared_ptr<context_spec>> contexts(const std::vector<std::shared_ptr<const exec::context>>& source) {
        std::vector<std::shared_ptr<context_spec>> result;result.reserve(source.size());
        for(const auto& value:source)result.push_back(context(*value));
        return result;
    }
    std::vector<X> expressions(const exec::expression& source,std::size_t first=0) {
        std::vector<X> result;result.reserve(source.children.size()-first);
        for(auto i=first;i<source.children.size();++i)result.push_back(build(*source.children[i]));
        return result;
    }
public:
    explicit tree_builder(std::vector<function_reference>& references):references(references) {}
    X build(const exec::expression& source) {
        const auto child=[&](std::size_t index){return build(*source.children[index]);};
        const int opcode=source.opcode;
        X result;
        switch(opcode) {
        case 0:result=std::make_shared<constantExpression>(source.constant);break;
        case 1: {
            auto node=std::make_shared<complex_expression>();node->expressions=expressions(source);result=std::move(node);break;
        }
        case 2: {
            auto node=std::make_shared<functionInvocationExpression>();node->function_id=source.immediate;
            node->params=expressions(source);node->contexts=contexts(source.contexts);
            auto member=node->params.size()==1?std::dynamic_pointer_cast<object_expression>(node->params[0]):nullptr;
            if(node->function_id==ids::id(ids::builtin::mem_get)&&node->contexts.empty()&&member&&member->is_member_address())
                result=std::make_shared<member_access_expression>(std::move(node->params[0]));
            else {
                references.push_back({node->function_id,&node->function_id,false,static_cast<int>(node->contexts.size())});
                result=std::move(node);
            }
            break;
        }
        case 3: {auto node=std::make_shared<varExpression>();node->varindex=source.immediate;result=std::move(node);break;}
        case 4: {
            auto node=std::make_shared<varDefineExpression>();node->varindex=source.immediate;
            if(!source.children.empty())node->initializer=child(0);
            result=std::move(node);break;
        }
        case 5: {
            auto node=std::make_shared<varSetExpression>();node->varindex=source.immediate;node->expression=child(0);result=std::move(node);break;
        }
        case 6: {auto target=child(0);auto value=child(1);result=raw_binary<movExpression>(std::move(target),std::move(value));break;}
        case 32:result=std::make_shared<new_block_expression>(static_cast<std::size_t>(source.immediate));break;
        case 33:result=std::make_shared<block_address_expression>(child(0));break;
        case 34:result=std::make_shared<move_out_expression>(source.immediate);break;
        case 35:result=std::make_shared<drop_expression>(child(0));break;
        case 36:case 37:result=std::make_shared<context_expression>(opcode,context(*source.contexts[0]));break;
        case 38:case 39: {
            auto value=child(0);auto spec=context(*source.contexts[0]);
            result=std::make_shared<context_expression>(opcode,std::move(spec),std::move(value));break;
        }
        case 40:result=std::make_shared<buffer_new_expression>(context(*source.contexts[0]));break;
        case 41: {
            auto receiver=child(0);auto arguments=expressions(source,1);
            result=std::make_shared<buffer_operation_expression>(source.immediate,std::move(receiver),std::move(arguments));break;
        }
        case 42: {
            auto spec=context(*source.contexts[0]);auto left=child(0);auto right=child(1);
            result=std::make_shared<value_compare_expression>(std::move(spec),std::move(left),std::move(right));break;
        }
        case 43:case 44:case 45:case 46:case 47:case 48: {
            auto left=child(0);auto right=child(1);
            result=std::make_shared<bitwise_expression>(opcode,std::move(left),std::move(right));break;
        }
        case 49:result=std::make_shared<convert_expression>(source.immediate,child(0));break;
        case 52: {
            auto node=std::make_shared<set_methods_expression>(child(0));
            for(const auto& entry:source.methods) {
                auto method=std::make_shared<method_spec>();method->function_id=entry.function_id;method->contexts=contexts(entry.contexts);
                references.push_back({method->function_id,&method->function_id,false,static_cast<int>(method->contexts.size()),false,false,true});
                node->entries.push_back(std::move(method));
            }
            result=std::move(node);break;
        }
        case 53:result=std::make_shared<call_virtual_expression>(source.immediate,source.return_type,expressions(source));break;
        case 51: {
            auto value=child(0);auto spec=context(*source.contexts[0]);
            result=std::make_shared<check_object_expression>(std::move(value),std::move(spec),source.slot_types);break;
        }
        case 50: {
            auto condition=child(0);auto when_true=child(1);auto when_false=child(2);
            result=std::make_shared<select_expression>(std::move(condition),std::move(when_true),std::move(when_false));break;
        }
        case 7:case 8: {
            auto node=std::make_shared<returnExpression>();node->object_return=opcode==8;
            if(!source.children.empty())node->returnType=child(0);
            result=std::move(node);break;
        }
        case 9:result=std::make_shared<object_expression>("oa",child(0),source.immediate);break;
        case 10: {
            auto node=std::make_shared<object_expression>(child(0),source.destructor_id,source.manual);
            node->contexts=contexts(source.contexts);
            if(source.destructor_id)references.push_back({*source.destructor_id,node->destructor_address(),true,static_cast<int>(node->contexts.size())});
            result=std::move(node);break;
        }
        case 11:result=std::make_shared<object_expression>("od",child(0));break;
        case 12:case 13:case 14:case 15:case 16:case 17:case 18:case 19:case 20:case 21:case 22:case 23:case 24: {
            auto a=child(0);auto b=child(1);
            switch(opcode) {
            case 12:result=raw_binary<addExpression>(std::move(a),std::move(b));break;
            case 13:result=raw_binary<minusExpression>(std::move(a),std::move(b));break;
            case 14:result=raw_binary<multiplyExpression>(std::move(a),std::move(b));break;
            case 15:result=raw_binary<divideExpression>(std::move(a),std::move(b));break;
            case 17:result=raw_binary<gtExpression>(std::move(a),std::move(b));break;
            case 18:result=raw_binary<ltExpression>(std::move(a),std::move(b));break;
            case 19:result=raw_binary<equalExpression>(std::move(a),std::move(b));break;
            default: {
                const char* operation=opcode==16?"mod":opcode==20?"ne":opcode==21?"ge":opcode==22?"le":opcode==23?"and":"or";
                result=std::make_shared<extra_expression>(operation,std::move(a),std::move(b));break;
            }
            }
            break;
        }
        case 25:case 26:result=std::make_shared<extra_expression>(opcode==25?"not":"neg",child(0));break;
        case 27: {
            auto node=std::make_shared<ifExpression>();node->exp1=child(0);node->exp2=child(1);
            if(source.children.size()==3)node->otherwise=child(2);
            result=std::move(node);break;
        }
        case 28: {auto condition=child(0);auto body=child(1);result=raw_binary<whileExpression>(std::move(condition),std::move(body));break;}
        case 29:result=std::make_shared<breakExpression>();break;
        case 30:result=std::make_shared<continueExpression>();break;
        case 31: {
            auto body=child(0);auto cleanup=child(1);
            result=std::make_shared<cleanup_expression>(std::move(body),std::move(cleanup),source.on_error);break;
        }
        default:throw std::logic_error("Unknown exec model opcode: "+std::to_string(opcode));
        }
        result->location=source.location;return result;
    }
};
parsed_module build_module(exec::module model) {
    parsed_module module;
    module.meta=std::move(model.meta);module.global_count=model.global_count;
    module.hint=std::move(model.hint);module.assumptions=std::move(model.assumptions);
    module.extern_signatures=std::move(model.extern_signatures);
    for(const auto& source:model.functions) {
        auto fn=std::make_shared<ofunction>();
        fn->global_offset=model.global_offset;fn->global_count=module.global_count;
        fn->function_id=source.function_id;fn->rett=source.return_type;
        fn->param_count=static_cast<int>(source.param_types.size());fn->local_count=static_cast<int>(source.local_count);
        fn->param_types=source.param_types;fn->hidden_count=static_cast<int>(source.hidden_count);
        fn->entry_kind=source.entry_kind;fn->debug=source.debug;
        const auto first=module.references.size();
        fn->code=tree_builder(module.references).build(*source.body);
        // The core linker plan and the tree's patch list must name the same references.
        const auto expected=exec::references(source.body);
        if(module.references.size()-first!=expected.size())throw std::logic_error("Tree references differ from the exec model");
        for(std::size_t i=0;i<expected.size();++i) {
            const auto& found=module.references[first+i];const auto& wanted=expected[i];
            if(found.original_id!=wanted.function_id||reference_kind(found)!=wanted.kind||found.hidden_count!=static_cast<int>(wanted.hidden_count))
                throw std::logic_error("Tree references differ from the exec model");
        }
        module.functions.emplace(source.function_id,std::move(fn));
    }
    return module;
}
}
