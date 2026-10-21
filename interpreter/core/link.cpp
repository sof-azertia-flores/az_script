#include "link.h"
#include "errors.h"
#include "ids.h"
#include <algorithm>
#include <stdexcept>
#include <utility>

namespace azertian::link {
namespace {
using ids::namespace_of;
// Iterative SCC discovery keeps arbitrarily long module dependency chains
// off the C++ call stack. Components and ready ties use stable load order.
std::vector<std::size_t> onload_order(const std::vector<std::set<std::size_t>>& dependencies) {
    const auto count=dependencies.size();
    std::vector<bool> visited(count);std::vector<std::size_t> finished;
    for(std::size_t start=0;start<count;++start)if(!visited[start]) {
        std::vector<std::pair<std::size_t,bool>> pending{{start,false}};
        while(!pending.empty()) {
            auto [current,expanded]=pending.back();pending.pop_back();
            if(expanded){finished.push_back(current);continue;}
            if(visited[current])continue;
            visited[current]=true;pending.emplace_back(current,true);
            for(auto next=dependencies[current].rbegin();next!=dependencies[current].rend();++next)
                if(!visited[*next])pending.emplace_back(*next,false);
        }
    }
    std::vector<std::vector<std::size_t>> incoming(count),groups;
    for(std::size_t i=0;i<count;++i)for(auto next:dependencies[i])incoming[next].push_back(i);
    std::vector<int> component(count,-1);
    for(auto start=finished.rbegin();start!=finished.rend();++start)if(component[*start]<0) {
        const int id=static_cast<int>(groups.size());std::vector<std::size_t> pending{*start},group;
        component[*start]=id;
        while(!pending.empty()) {
            auto current=pending.back();pending.pop_back();group.push_back(current);
            for(auto next:incoming[current])if(component[next]<0){component[next]=id;pending.push_back(next);}
        }
        std::sort(group.begin(),group.end());groups.push_back(std::move(group));
    }
    std::vector<std::set<int>> waits(groups.size());
    for(std::size_t i=0;i<count;++i)for(auto next:dependencies[i])if(component[i]!=component[next])waits[component[i]].insert(component[next]);
    std::set<std::pair<std::size_t,int>> ready;
    for(std::size_t i=0;i<groups.size();++i)if(waits[i].empty())ready.emplace(groups[i][0],static_cast<int>(i));
    std::vector<std::size_t> order;
    while(!ready.empty()) {
        const int current=ready.begin()->second;ready.erase(ready.begin());
        order.insert(order.end(),groups[current].begin(),groups[current].end());
        for(std::size_t i=0;i<groups.size();++i)if(waits[i].erase(current)&&waits[i].empty())ready.emplace(groups[i][0],static_cast<int>(i));
    }
    return order;
}
}

plan resolve(const input& source) {
    const auto& modules=source.modules;
    std::map<std::string,std::size_t> hints;
    for(std::size_t i=0;i<modules.size();++i)if(!modules[i].hint.empty())hints.emplace(modules[i].hint,i);
    std::map<int,std::size_t> function_modules;
    for(std::size_t i=0;i<modules.size();++i)for(int id:modules[i].definitions)function_modules.emplace(id,i);
    plan result;
    result.dependencies.reserve(modules.size());
    for(const auto& module:modules)result.dependencies.push_back(module.dependencies);
    result.externs.resize(modules.size());result.references.resize(modules.size());
    const auto found_function=[&](int id) {return source.functions.find(id);};
    for(std::size_t index=0;index<modules.size();++index) {
        const auto& module=modules[index];auto& dependencies=result.dependencies[index];std::map<int,int> mapping;
        for(const auto& [placeholder,hint]:module.assumptions) {
            auto target=hints.find(hint);if(target==hints.end())throw std::runtime_error("Missing namespace hint: "+hint);
            mapping.emplace(placeholder,*modules[target->second].mounted_namespace);
            if(target->second!=index)dependencies.insert(target->second);
        }
        const auto resolve_id=[&](int id){auto found=mapping.find(namespace_of(id));return found==mapping.end()?id:ids::relocate(id,found->second);};
        for(const auto& [original_id,signature]:module.extern_signatures) {
            const int id=resolve_id(original_id);auto target=found_function(id);
            if(target!=source.functions.end()) {
                if(auto defining=function_modules.find(id);defining!=function_modules.end()&&defining->second!=index)dependencies.insert(defining->second);
                const auto& fn=target->second;
                if(fn&&fn->entry_kind==2)throw std::runtime_error("Private script function cannot be imported: "+function_id_text(id));
                if(!fn||fn->return_type!=signature.return_type||fn->param_types!=signature.param_types||
                   fn->hidden_count!=signature.hidden_count||fn->entry_kind!=signature.entry_kind)
                    throw std::runtime_error("External signature does not match script function: "+function_id_text(id));
            } else if(mapping.contains(namespace_of(original_id))||source.script_namespaces.contains(namespace_of(id))||namespace_of(id)==0x0fff)
                throw std::runtime_error("Imported script function is missing: "+function_id_text(id));
            auto [entry,added]=result.bindings.emplace(id,signature);
            if(!added&&!(entry->second==signature))throw std::runtime_error("Conflicting external signature id: "+function_id_text(id));
            result.externs[index].emplace(id,signature);
        }
        auto& resolved=result.references[index];resolved.reserve(module.references.size());
        for(const auto& reference:module.references) {
            const int id=resolve_id(reference.function_id);
            const auto kind=reference.kind;
            if(auto defining=function_modules.find(id);defining!=function_modules.end()&&defining->second!=index)dependencies.insert(defining->second);
            if(mapping.contains(namespace_of(reference.function_id))||kind!=exec::reference_kind::call) {
                auto target=found_function(id);
                if(target==source.functions.end())throw std::runtime_error("Referenced script function is missing: "+function_id_text(id));
                const auto& fn=target->second;
                if(kind==exec::reference_kind::destructor&&(!fn||fn->return_type!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE}))
                    throw std::runtime_error("Object destructor must be a script function void(address)");
                if(kind==exec::reference_kind::placement&&(!fn||fn->return_type!=VOID_VALUE||fn->param_types!=std::vector<int>{ADDRESS_VALUE}||fn->entry_kind!=1))
                    throw std::runtime_error("Generic placement initializer must be an internal script function void(address)");
                if(kind==exec::reference_kind::method&&(!fn||fn->entry_kind!=0||fn->param_types.empty()||fn->param_types.front()!=ADDRESS_VALUE))
                    throw std::runtime_error("Virtual method entry must be an ordinary script function with an address receiver");
                if(kind==exec::reference_kind::factory&&(!fn||fn->return_type!=OBJECT_VALUE||!fn->param_types.empty()))
                    throw std::runtime_error("Generic default factory must be a script function object()");
            }
            const auto hidden=static_cast<int>(reference.hidden_count);
            if(auto target=found_function(id);target!=source.functions.end()) {
                if(target->second&&target->second->entry_kind==2&&function_modules.at(id)!=index)
                    throw std::runtime_error("Private script function is not visible outside its module: "+function_id_text(id));
                if(!target->second||target->second->hidden_count!=hidden)throw std::runtime_error("Generic context count does not match function: "+function_id_text(id));
            } else {
                auto imported=result.externs[index].find(id);
                if(imported!=result.externs[index].end()&&imported->second.hidden_count!=hidden)
                    throw std::runtime_error("Generic context count does not match external function: "+function_id_text(id));
                if(hidden&&imported==result.externs[index].end())
                    throw std::runtime_error("Generic context call requires a matching script import");
            }
            resolved.push_back(id);
        }
    }
    result.order=onload_order(result.dependencies);
    return result;
}
}
