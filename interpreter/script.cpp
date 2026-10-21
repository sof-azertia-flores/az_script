// Scripts: the live-script registry, loading, state checks, invocation and teardown.
#include "script_internal.h"
#include "core/lifecycle.h"
#include "heepalloc.h"
#include <algorithm>
#include <stdexcept>

namespace azertian {
using namespace tree;
using trace::entry_scope;
std::vector<std::weak_ptr<script>>& live_scripts(){static std::vector<std::weak_ptr<script>> result;return result;}
std::recursive_mutex& runtime_mutex(){static std::recursive_mutex m;return m;}
bool script_namespace_in_use(int ns) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto& registry=live_scripts();
    registry.erase(std::remove_if(registry.begin(),registry.end(),[](auto& value){return value.expired();}),registry.end());
    for(auto& weak:registry)if(auto value=weak.lock();value&&!value->closed&&value->script_namespaces.contains(ns))return true;
    return false;
}
std::shared_ptr<script> load_script(const unsigned char* bytes,std::size_t length) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto result=std::make_shared<script>();
    result->baseEnv=std::make_shared<environment>();result->baseEnv->script=result;
    result->insert_script(bytes,length);
    live_scripts().push_back(result);
    return result;
    });
}
std::shared_ptr<script> load_script(unsigned char* bytes) {
    entry_scope entry;return entry.run([&]() -> std::shared_ptr<script> {
    if(!bytes)throw std::invalid_argument("Null script");
    const int length=b2i(bytes);if(length<0)throw std::invalid_argument("Negative script length");
    return load_script(bytes,static_cast<std::size_t>(length)+4);
    });
}
void script::require_execution() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    if(destroying||initializing)return;
    if(faulted)throw std::runtime_error("Script initialization failed; close and rebuild the script");
    if(!setup)throw std::runtime_error("Script setup is incomplete; call flush before execution");
}
V script::invoke(int id,std::vector<V> args) {
    entry_scope entry;return entry.run([&]() -> V {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(destroying)throw std::runtime_error("Script is being destroyed");
    require_execution();return getFunction(id)->invoke(baseEnv,std::move(args));
    });
}
int script::namespace_for_hint(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(module->manifest.hint==hint&&!hint.empty())return *module->manifest.actual_namespace;
    throw std::runtime_error("Unknown namespace hint: "+hint);
}
bool script::hint_loaded(const std::string& hint) const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)throw std::runtime_error("Script is closed");
    for(auto& module:modules)if(!hint.empty()&&module->manifest.hint==hint)return true;
    return false;
}
std::vector<module_manifest_entry> script::module_manifest() const {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());std::vector<module_manifest_entry> result;
    for(auto& module:modules)result.push_back(module->manifest);
    return result;
}
void script::destroy() {
    entry_scope entry;return entry.run([&]() -> void {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(closed)return;
    if(destroying)throw std::runtime_error("Script is being destroyed");
    if(active_calls||initializing)throw std::runtime_error("Cannot destroy a running script");
    destroying=true;pending_error failure;
    try {
        call_guard guard(baseEnv);
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {
            try{(*hook)->invoke(baseEnv,{});}catch(...){failure.capture_current();}
        }
        try{baseEnv->cleanup(baseEnv);}catch(...){failure.capture_current();}
        // Only hand-written exec can keep an object in a global; it still ends here.
        blocks::finalize_storage(baseEnv->variables,baseEnv,true,failure);
    }catch(...){failure.capture_current();}
    heap::discard_script_objects(this);
    vm_session_.reset();
    closed=true;setup=false;functions.clear();extern_signatures.clear();on_destory.clear();modules.clear();baseEnv.reset();destroying=false;
    if(failure)failure.rethrow();
    });
}
}
