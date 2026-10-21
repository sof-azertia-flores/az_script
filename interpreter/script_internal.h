#ifndef AZSCRIPT_SCRIPT_INTERNAL_H
#define AZSCRIPT_SCRIPT_INTERNAL_H

// Private to the script layer's sources: the record of a mounted module and
// the process-wide registry of live scripts.
#include "library.h"
#include "tree/internal.h"
#include <cstdio>
#include <map>
#include <set>
#include <string>
#include <vector>

namespace azertian {
struct loaded_module {
    module_manifest_entry manifest;
    std::shared_ptr<AbdMap> meta;
    std::map<int,std::shared_ptr<ofunction>> definitions;
    std::map<int,function_signature> extern_signatures;
    std::map<int,std::string> assumptions;
    std::vector<function_reference> references;
    std::set<int> fixed_namespaces;
    std::set<int> fixed_extern_namespaces;
    std::set<std::size_t> dependencies;
    std::shared_ptr<ofunction> onload,pre_destroy;
};
// Every script that has been loaded and not yet released.
std::vector<std::weak_ptr<script>>& live_scripts();
namespace {
// Runtime messages spell IDs like source and the CLI do: 0x plus the full
// 32-bit pattern, so negative and high-bit IDs read the same everywhere.
std::string hex_text(std::uint32_t value,int digits) {
    char text[16];std::snprintf(text,sizeof text,"0x%0*x",digits,static_cast<unsigned>(value));
    return text;
}
std::string namespace_text(int ns) { return hex_text(static_cast<std::uint32_t>(ns)&0xffffu,4); }
using ids::namespace_of;
int relocate_id(int id,int ns) noexcept {return ids::relocate(id,ns);}
using module_list=std::vector<std::shared_ptr<loaded_module>>;
}
}
#endif
