#ifndef AZSCRIPT_VM_SOURCE_DECODE_H
#define AZSCRIPT_VM_SOURCE_DECODE_H

#include "lowering.h"
#include <functional>
#include <span>

namespace azertian::vm {
// Internal bridge, not another public wire reader. The caller first validates
// the complete v10 module/body with the existing loader. body is the payload of
// one expression stack (without its outer frame length). located contains the
// already validated debug record's expression preorder numbers, not positions.
// The result owns its data and has no pointers into body or the old expression
// tree. Contexts do not receive expression node numbers.
std::shared_ptr<const SourceExpression> decode_source(
    std::span<const unsigned char> body,std::span<const std::uint32_t> located={});

struct SourceSignature {
    int function_id=0,return_type=VOID_VALUE,entry_kind=0;
    std::vector<int> param_types;
    std::uint32_t hidden_count=0;
};
struct SourceAssumption {std::string hint;int namespace_id=0;};
struct DecodedModuleSources {
    std::vector<SourceFunction> functions;
    std::vector<SourceSignature> extern_signatures;
    std::vector<SourceAssumption> assumptions;
    std::string hint;
    std::vector<std::string> debug_files;
    std::uint32_t global_offset=0,global_count=0;
};
// Experimental compilation can reconstruct these temporary sources from a
// script's immutable module_manifest bytes after the legacy module decoder has
// validated them. bytes includes the outer ABD frame. Definition IDs still have
// their original wire namespaces; the caller applies the mounted module's
// actual_namespace and its assumption mapping before lowering. Lifecycle IDs
// stay 0/1, with SourceFunction::lifecycle set explicitly. Metadata/extensions
// are not VM input and are skipped, not decoded into a second metadata tree.
DecodedModuleSources decode_module_sources(std::span<const unsigned char> bytes,
                                           std::uint32_t global_offset=0);

enum class SourceReferenceKind { call,destructor,factory,placement,method };
struct SourceReference {
    int function_id=0;
    SourceReferenceKind kind=SourceReferenceKind::call;
    std::uint32_t hidden_count=0,node=0,location=0;
};
// Discovery order matches parse_raw_expression/parse_context, including nested
// contexts and the decoder-only mem_get(oa(...)) exclusion. Literal function IDs
// used as ordinary integer values are deliberately not linkage references.
std::vector<SourceReference> source_references(const std::shared_ptr<const SourceExpression>& root);

// Prepare a relocated copy before flush publishes it. The original immutable
// graph is never changed, including when the resolver throws after some calls.
// Resolve/validate against the same atomic link plan as the legacy references;
// this function performs no target lookup or signature validation itself.
std::shared_ptr<const SourceExpression> relocate_source(
    const std::shared_ptr<const SourceExpression>& root,
    const std::function<int(const SourceReference&)>& resolver);
}
#endif
