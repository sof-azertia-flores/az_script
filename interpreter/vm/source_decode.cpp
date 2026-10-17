#include "source_decode.h"
#include "../core/ids.h"
#include <bit>
#include <limits>
#include <stdexcept>
#include <utility>

namespace azertian::vm {
namespace {
using Bytes=std::span<const unsigned char>;
using Expression=std::shared_ptr<const SourceExpression>;
using ContextNode=std::shared_ptr<const SourceContext>;
[[noreturn]] void bad(const char* message) {
    throw std::invalid_argument(std::string("VM source bridge: ")+message);
}
std::uint32_t u32(Bytes bytes) {
    if(bytes.size()!=4)bad("int32 width");
    return std::uint32_t(bytes[0])|(std::uint32_t(bytes[1])<<8)|
        (std::uint32_t(bytes[2])<<16)|(std::uint32_t(bytes[3])<<24);
}
std::uint64_t u64(Bytes bytes) {
    if(bytes.size()!=8)bad("64-bit scalar width");
    return std::uint64_t(u32(bytes.first(4)))|(std::uint64_t(u32(bytes.subspan(4)))<<32);
}
class Cursor {
    Bytes remaining;
public:
    explicit Cursor(Bytes bytes):remaining(bytes) {}
    bool empty() const noexcept {return remaining.empty();}
    Bytes take() {
        if(remaining.size()<4)bad("truncated frame length");
        const auto size=u32(remaining.first(4));remaining=remaining.subspan(4);
        if(size>static_cast<std::uint32_t>(std::numeric_limits<std::int32_t>::max())||size>remaining.size())bad("truncated or negative frame");
        const auto value=remaining.first(size);remaining=remaining.subspan(size);return value;
    }
    int integer() {return std::bit_cast<std::int32_t>(u32(take()));}
    bool boolean() {
        const auto bytes=take();
        if(bytes.size()!=1||bytes[0]>1)bad("boolean width/value");
        return bytes[0]!=0;
    }
    std::string string() {
        const auto value=take();
        return value.empty()?std::string{}:std::string(reinterpret_cast<const char*>(value.data()),value.size());
    }
    void finish() const {if(!empty())bad("extra fields in validated input");}
};
void depth(std::size_t value) {if(value>128)bad("source nesting exceeds 128");}
Value scalar(Bytes bytes) {
    Cursor fields(bytes);const int type=fields.integer();const auto value=fields.take();fields.finish();
    switch(type) {
    case 1:return Value(value.empty()?std::string{}:std::string(reinterpret_cast<const char*>(value.data()),value.size()));
    case 3:return Value(std::bit_cast<std::int32_t>(u32(value)));
    case 0xce867:return Value(std::bit_cast<float>(u32(value)));
    case 0xce1066:return Value(std::bit_cast<double>(u64(value)));
    case 0xce200b:return Value(address{u64(value)});
    case 0x0d00:
        if(value.size()!=1||value[0]>1)bad("boolean constant width/value");
        return Value(value[0]!=0);
    case 0xce200a:
        if(value.size()!=1||value[0]!=0xff)bad("void constant sentinel");
        return Value{};
    default:bad("unknown constant tag in validated input");
    }
}
class Decoder {
    std::span<const std::uint32_t> located;
    std::uint32_t next_node=0;
    std::size_t next_location=0;
    std::vector<ContextNode> contexts(Bytes bytes,std::size_t level) {
        depth(level);Cursor fields(bytes);std::vector<ContextNode> result;
        while(!fields.empty())result.push_back(context(fields.take(),level+1));
        return result;
    }
    ContextNode context(Bytes bytes,std::size_t level) {
        depth(level);Cursor fields(bytes);auto result=std::make_shared<SourceContext>();
        const auto kind=fields.integer();
        if(kind==0)result->reference=static_cast<std::uint32_t>(fields.integer());
        else if(kind==1) {
            result->abi=fields.integer();result->kind=fields.integer();result->width=static_cast<std::uint32_t>(fields.integer());
            if(fields.boolean())result->factory_id=fields.integer();
            result->contexts=contexts(fields.take(),level+1);
            if(fields.boolean())result->placement_id=fields.integer();
            result->placement_contexts=contexts(fields.take(),level+1);
            if(fields.boolean())result->element=context(fields.take(),level+1);
        } else bad("unknown context kind in validated input");
        fields.finish();return result;
    }
    std::vector<Expression> expressions(Bytes bytes,std::size_t level) {
        Cursor fields(bytes);std::vector<Expression> result;
        while(!fields.empty())result.push_back(expression(fields.take(),level));
        return result;
    }
    Expression expression(Bytes bytes,std::size_t level) {
        depth(level);Cursor fields(bytes);auto result=std::make_shared<SourceExpression>();
        if(next_node==std::numeric_limits<std::uint32_t>::max())bad("too many expression nodes");
        result->node=next_node++;
        if(next_location<located.size()&&located[next_location]==result->node)
            result->location=static_cast<std::uint32_t>(++next_location);
        result->opcode=fields.integer();
        const auto child=[&]{result->children.push_back(expression(fields.take(),level+1));};
        const auto one_context=[&]{result->contexts.push_back(context(fields.take(),level+1));};
        switch(result->opcode) {
        case 0:result->constant=scalar(fields.take());break;
        case 1:result->children=expressions(fields.take(),level+1);break;
        case 2:
            result->immediate=fields.integer();result->children=expressions(fields.take(),level+2);
            result->contexts=contexts(fields.take(),level+1);break;
        case 3:case 32:case 34:result->immediate=fields.integer();break;
        case 4:
            result->immediate=fields.integer();fields.integer(); // declared_type is not a storage invariant.
            if(fields.boolean())child();
            break;
        case 5:result->immediate=fields.integer();child();break;
        case 6:case 12:case 13:case 14:case 15:case 16:case 17:case 18:
        case 19:case 20:case 21:case 22:case 23:case 24:case 28:
        case 43:case 44:case 45:case 46:case 47:case 48:child();child();break;
        case 7:if(fields.boolean())child();break;
        case 8:case 11:case 25:case 26:case 33:case 35:child();break;
        case 9:child();result->immediate=fields.integer();break;
        case 10:
            child();if(fields.boolean())result->destructor_id=fields.integer();
            result->manual=fields.boolean();result->contexts=contexts(fields.take(),level+1);break;
        case 27:child();child();if(fields.boolean())child();break;
        case 29:case 30:break;
        case 31:child();child();result->on_error=fields.boolean();break;
        case 36:case 37:case 40:one_context();break;
        case 38:case 39:child();one_context();break;
        case 41: {
            result->immediate=fields.integer();child();auto args=expressions(fields.take(),level+1);
            result->children.insert(result->children.end(),args.begin(),args.end());break;
        }
        case 42:one_context();child();child();break;
        case 49:result->immediate=fields.integer();child();break;
        case 50:child();child();child();break;
        case 51: {
            child();one_context();Cursor tags(fields.take());
            while(!tags.empty())result->slot_types.push_back(tags.integer());
            break;
        }
        case 52: {
            child();Cursor entries(fields.take());
            while(!entries.empty()) {
                Cursor entry(entries.take());SourceMethod method;method.function_id=entry.integer();
                method.contexts=contexts(entry.take(),level+2);entry.finish();result->methods.push_back(std::move(method));
            }
            break;
        }
        case 53:
            result->immediate=fields.integer();result->return_type=fields.integer();
            result->children=expressions(fields.take(),level+2);break;
        default:bad("unknown expression opcode in validated input");
        }
        fields.finish();return result;
    }
public:
    explicit Decoder(std::span<const std::uint32_t> located):located(located) {
        for(std::size_t i=1;i<located.size();++i)if(located[i]<=located[i-1])bad("debug nodes must strictly increase");
        if(located.size()>std::numeric_limits<std::uint32_t>::max())bad("too many debug nodes");
    }
    Expression decode(Bytes bytes) {
        auto result=expression(bytes,0);
        if(next_location!=located.size())bad("debug node outside function body");
        return result;
    }
};

bool typed_member(const SourceExpression& expression) {
    return expression.opcode==2&&expression.immediate==ids::id(ids::builtin::mem_get)&&expression.contexts.empty()&&
        expression.children.size()==1&&expression.children.front()&&expression.children.front()->opcode==9;
}
std::uint32_t count(std::size_t size) {
    if(size>std::numeric_limits<std::uint32_t>::max())bad("too many reference contexts");
    return static_cast<std::uint32_t>(size);
}

std::uint32_t unsigned_count(int value) {
    if(value<0)bad("negative count in validated input");
    return static_cast<std::uint32_t>(value);
}
std::vector<int> parameter_types(Bytes bytes) {
    Cursor fields(bytes);std::vector<int> result;
    while(!fields.empty())result.push_back(fields.integer());
    return result;
}
DebugInfo debug_info(Bytes bytes,const std::shared_ptr<const std::vector<std::string>>& files,std::vector<std::uint32_t>& nodes) {
    Cursor fields(bytes);DebugInfo result;result.name=fields.string();result.files=files;
    result.header.file=unsigned_count(fields.integer());result.header.line=unsigned_count(fields.integer());
    result.header.column=unsigned_count(fields.integer());Cursor locations(fields.take());fields.finish();
    while(!locations.empty()) {
        nodes.push_back(unsigned_count(locations.integer()));SourcePosition position;
        position.file=unsigned_count(locations.integer());position.line=unsigned_count(locations.integer());
        position.column=unsigned_count(locations.integer());result.locations.push_back(position);
    }
    return result;
}

// Both traversal and functional relocation use the same walk, so new context or
// method references cannot appear in discovery but accidentally evade copying.
class References {
    const std::function<int(const SourceReference&)>& resolve;
    bool copying;
    int reference(int id,SourceReferenceKind kind,std::size_t hidden,const SourceExpression& owner) {
        return resolve({id,kind,count(hidden),owner.node,owner.location});
    }
    ContextNode context(const ContextNode& source,const SourceExpression& owner,std::size_t level) {
        depth(level);if(!source)bad("null context");
        auto result=copying?std::make_shared<SourceContext>(*source):nullptr;
        auto apply=[&](const auto& input,auto* output) {
            for(std::size_t i=0;i<input.size();++i) {
                auto value=context(input[i],owner,level+1);if(output)(*output)[i]=std::move(value);
            }
        };
        apply(source->contexts,result?&result->contexts:nullptr);
        apply(source->placement_contexts,result?&result->placement_contexts:nullptr);
        if(source->element) {
            auto value=context(source->element,owner,level+1);if(result)result->element=std::move(value);
        }
        if(source->placement_id) {
            const auto id=reference(*source->placement_id,SourceReferenceKind::placement,source->placement_contexts.size(),owner);
            if(result)result->placement_id=id;
        }
        if(source->factory_id) {
            const auto id=reference(*source->factory_id,SourceReferenceKind::factory,source->contexts.size(),owner);
            if(result)result->factory_id=id;
        }
        return result?result:source;
    }
    void contexts(const std::vector<ContextNode>& input,std::vector<ContextNode>* output,
                  const SourceExpression& owner,std::size_t level) {
        for(std::size_t i=0;i<input.size();++i) {
            auto value=context(input[i],owner,level+1);if(output)(*output)[i]=std::move(value);
        }
    }
public:
    References(const std::function<int(const SourceReference&)>& resolve,bool copying):resolve(resolve),copying(copying) {}
    Expression expression(const Expression& source,std::size_t level=0) {
        depth(level);if(!source)bad("null expression");
        auto result=copying?std::make_shared<SourceExpression>(*source):nullptr;
        if(source->opcode==42)contexts(source->contexts,result?&result->contexts:nullptr,*source,level);
        for(std::size_t i=0;i<source->children.size();++i) {
            auto value=expression(source->children[i],level+1);if(result)result->children[i]=std::move(value);
        }
        if(source->opcode!=42)contexts(source->contexts,result?&result->contexts:nullptr,*source,level);
        if(source->opcode==2&&!typed_member(*source)) {
            const auto id=reference(source->immediate,SourceReferenceKind::call,source->contexts.size(),*source);
            if(result)result->immediate=id;
        }
        if(source->destructor_id) {
            const auto id=reference(*source->destructor_id,SourceReferenceKind::destructor,source->contexts.size(),*source);
            if(result)result->destructor_id=id;
        }
        for(std::size_t i=0;i<source->methods.size();++i) {
            const auto& method=source->methods[i];auto* copied=result?&result->methods[i]:nullptr;
            contexts(method.contexts,copied?&copied->contexts:nullptr,*source,level);
            const auto id=reference(method.function_id,SourceReferenceKind::method,method.contexts.size(),*source);
            if(copied)copied->function_id=id;
        }
        return result?result:source;
    }
};
}

std::shared_ptr<const SourceExpression> decode_source(Bytes body,std::span<const std::uint32_t> located) {
    return Decoder(located).decode(body);
}
DecodedModuleSources decode_module_sources(Bytes bytes,std::uint32_t global_offset) {
    Cursor file(bytes);const auto payload=file.take();file.finish();Cursor fields(payload);
    if(fields.string()!="AZSCRIPT"||fields.integer()!=10)bad("expected validated exec v10 module");
    fields.integer();fields.take(); // Non-executable source version and author.
    DecodedModuleSources result;result.global_offset=global_offset;
    result.global_count=unsigned_count(fields.integer());
    if(result.global_count>std::numeric_limits<std::uint32_t>::max()-global_offset)bad("global layout overflow");
    fields.take(); // Extensible metadata was already validated by the loader.
    Cursor signatures(fields.take());
    while(!signatures.empty()) {
        Cursor entry(signatures.take());SourceSignature signature;
        signature.function_id=entry.integer();signature.return_type=entry.integer();
        signature.param_types=parameter_types(entry.take());signature.hidden_count=unsigned_count(entry.integer());
        signature.entry_kind=entry.integer();entry.finish();result.extern_signatures.push_back(std::move(signature));
    }
    Cursor functions(fields.take());result.hint=fields.string();Cursor assumptions(fields.take());
    while(!assumptions.empty()) {
        Cursor entry(assumptions.take());SourceAssumption assumption;assumption.hint=entry.string();
        assumption.namespace_id=entry.integer();entry.finish();result.assumptions.push_back(std::move(assumption));
    }
    Cursor files(fields.take());
    while(!files.empty())result.debug_files.push_back(files.string());
    fields.finish();
    // Functions of one module share its file table, as the tree loader does.
    const auto shared_files=std::make_shared<const std::vector<std::string>>(result.debug_files);
    while(!functions.empty()) {
        Cursor entry(functions.take());SourceFunction function;
        function.function_id=entry.integer();function.return_type=entry.integer();
        const auto parameters=unsigned_count(entry.integer());function.local_count=unsigned_count(entry.integer());
        function.param_types=parameter_types(entry.take());const auto body=entry.take();
        function.hidden_count=unsigned_count(entry.integer());function.entry_kind=entry.integer();
        function.lifecycle=function.function_id==0||function.function_id==1;
        function.global_offset=global_offset;function.global_count=result.global_count;
        std::vector<std::uint32_t> located;
        if(entry.boolean())function.debug=debug_info(entry.take(),shared_files,located);
        entry.finish();
        if(parameters!=function.param_types.size())bad("parameter count mismatch in validated input");
        function.body=decode_source(body,located);result.functions.push_back(std::move(function));
    }
    return result;
}
std::vector<SourceReference> source_references(const Expression& root) {
    std::vector<SourceReference> result;
    const std::function<int(const SourceReference&)> collect=[&](const SourceReference& reference) {
        result.push_back(reference);return reference.function_id;
    };
    References(collect,false).expression(root);return result;
}
std::shared_ptr<const SourceExpression> relocate_source(const Expression& root,
        const std::function<int(const SourceReference&)>& resolver) {
    if(!resolver)bad("empty reference resolver");
    return References(resolver,true).expression(root);
}
}
