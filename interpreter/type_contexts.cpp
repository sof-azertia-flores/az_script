// Validation of bound generic contexts and virtual method tables.
#include "script_internal.h"
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
void validate_type_contexts(const type_contexts& contexts,const std::shared_ptr<script>& script) {
    std::size_t visited=0;
    const auto check=[&](auto&& self,const std::shared_ptr<const type_context>& context,std::size_t depth)->void {
        if(!context||depth>128||++visited>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Invalid or excessive generic contexts");
        if(!valid_context_kind(context->abi,context->kind)||context->width<1||static_cast<std::size_t>(context->width)>MAX_VARIABLE_SLOTS||(context->kind!=3&&context->width!=1))throw std::invalid_argument("Invalid generic type context");
        if((context->factory_id||!context->contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only object type contexts have default factories");
        if(!context->factory_id&&!context->contexts.empty())throw std::invalid_argument("Factory contexts require a factory");
        if(context->factory_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->factory_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=OBJECT_VALUE||!fn->param_types.empty()||fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->contexts.size())
                throw std::invalid_argument("Invalid generic default factory signature");
        }
        if((context->placement_id||!context->placement_contexts.empty())&&context->kind!=3)
            throw std::invalid_argument("Only class contexts have placement initializers");
        if(!context->placement_id&&!context->placement_contexts.empty())throw std::invalid_argument("Placement contexts require an initializer");
        if(context->placement_id) {
            if(!script||script->closed)throw std::invalid_argument("Generic context script is unavailable");
            auto found=script->functions.find(*context->placement_id);
            auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
            if(!fn||fn->rett!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE}||fn->entry_kind!=1||
               fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=context->placement_contexts.size())
                throw std::invalid_argument("Invalid generic placement initializer signature");
        }
        if((context->kind==4)!=static_cast<bool>(context->element))throw std::invalid_argument("Buffer context requires exactly one element context");
        if(context->element) {
            if(context->element->abi==VOID_VALUE)throw std::invalid_argument("Buffer element cannot be void");
            self(self,context->element,depth+1);
        }
        for(auto& child:context->contexts)self(self,child,depth+1);
        for(auto& child:context->placement_contexts)self(self,child,depth+1);
    };
    for(auto& context:contexts)check(check,context,1);
}
void validate_method_table(const method_table& methods,const std::shared_ptr<script>& script) {
    if(!script||script->closed)throw std::invalid_argument("Virtual method table belongs to an unavailable script");
    if(methods.size()>MAX_VARIABLE_SLOTS)throw std::invalid_argument("Too many virtual method entries");
    for(const auto& method:methods) {
        auto found=script->functions.find(method.function_id);
        auto fn=found==script->functions.end()?nullptr:std::dynamic_pointer_cast<ofunction>(found->second);
        if(!fn||fn->entry_kind!=0||fn->param_types.empty()||fn->param_types.front()!=ADDRESS_VALUE||
           fn->hidden_count<0||static_cast<std::size_t>(fn->hidden_count)!=method.contexts.size())
            throw std::invalid_argument("Invalid virtual method entry: "+function_id_text(method.function_id));
        validate_type_contexts(method.contexts,script);
    }
}
}
