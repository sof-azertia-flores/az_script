#ifndef AZSCRIPT_CORE_LINK_H
#define AZSCRIPT_CORE_LINK_H
#include "exec.h"
#include "signature.h"
#include <cstddef>
#include <map>
#include <optional>
#include <set>
#include <string>
#include <vector>

// Link planning for flush: resolves every module's assumed namespaces,
// external signatures and references against the mounted modules, and orders
// onload. It changes nothing; the engines apply a complete plan atomically.
namespace azertian::link {
struct module {
    // Assumed namespace -> hint; empty once the module has been linked.
    std::map<int,std::string> assumptions;
    std::string hint;
    std::optional<int> mounted_namespace;
    std::map<int,function_signature> extern_signatures;
    // In discovery order: functions in wire order, exec::references within each.
    std::vector<exec::reference> references;
    std::vector<int> definitions;
    std::set<std::size_t> dependencies;
};
struct input {
    std::vector<module> modules;
    // Every mounted script function; nullopt marks an entry that is not one.
    std::map<int,std::optional<function_signature>> functions;
    std::set<int> script_namespaces;
};
struct plan {
    std::vector<std::map<int,function_signature>> externs;
    std::vector<std::set<std::size_t>> dependencies;
    // Resolved function IDs, parallel to each module's references.
    std::vector<std::vector<int>> references;
    std::map<int,function_signature> bindings;
    // Module indices in onload order: dependencies first, ties in load order.
    std::vector<std::size_t> order;
};
// Throws the runtime's link errors (std::runtime_error) in its order.
plan resolve(const input& source);
}
#endif
