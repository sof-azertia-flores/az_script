#include "exec.h"
#include "errors.h"
#include "ids.h"
#include "limits.h"
#include "../../abdC/azertianBinaryDataValues.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <set>
#include <stdexcept>
#include <utility>

namespace azertian::exec {
namespace {
using ids::namespace_of;
constexpr std::size_t max_slots=limits::max_slots;
using Expression=std::shared_ptr<const expression>;
using Context=std::shared_ptr<const context>;
// Exec records are raw ABD stacks. Views borrow the caller's input only while
// reading; the model owns all retained data.
struct raw_view {
    const unsigned char* data;
    std::size_t size;
    std::size_t depth;
};
void validate_utf8(raw_view value) {
    std::size_t i=0;
    while(i<value.size) {
        const unsigned first=value.data[i++];
        if(first<0x80)continue;
        int remaining;std::uint32_t code,minimum;
        if(first>=0xc2&&first<=0xdf){remaining=1;code=first&0x1f;minimum=0x80;}
        else if(first>=0xe0&&first<=0xef){remaining=2;code=first&0x0f;minimum=0x800;}
        else if(first>=0xf0&&first<=0xf4){remaining=3;code=first&0x07;minimum=0x10000;}
        else throw std::invalid_argument("Invalid UTF-8 in exec string");
        if(static_cast<std::size_t>(remaining)>value.size-i)throw std::invalid_argument("Truncated UTF-8 in exec string");
        while(remaining--) {
            const unsigned next=value.data[i++];
            if((next&0xc0)!=0x80)throw std::invalid_argument("Invalid UTF-8 in exec string");
            code=(code<<6)|(next&0x3f);
        }
        if(code<minimum||code>0x10ffff||(code>=0xd800&&code<=0xdfff))throw std::invalid_argument("Invalid UTF-8 in exec string");
    }
}
class raw_cursor {
    raw_view view;
    std::size_t position=0;
public:
    explicit raw_cursor(raw_view value):view(value) {
        if(view.depth>128)throw std::invalid_argument("ABD nesting exceeds 128 levels");
    }
    bool empty() const{return position==view.size;}
    raw_view take() {
        if(view.size-position<4)throw std::invalid_argument("Missing or truncated exec field");
        const int size=b2i(view.data+position);
        if(size<0||static_cast<std::size_t>(size)>view.size-position-4)
            throw std::invalid_argument("Negative or truncated exec field");
        raw_view result{view.data+position+4,static_cast<std::size_t>(size),view.depth+1};
        position+=4+result.size;return result;
    }
    int integer() {
        auto value=take();
        if(value.size!=4)throw std::invalid_argument("Exec integer field must contain four bytes");
        return b2i(value.data);
    }
    bool boolean() {
        auto value=take();
        if(value.size!=1||value.data[0]>1)throw std::invalid_argument("Exec boolean field must be one byte, 0 or 1");
        return value.data[0]==1;
    }
    std::string string() {auto value=take();validate_utf8(value);return std::string(reinterpret_cast<const char*>(value.data),value.size);}
    void finish() const {if(!empty())throw std::invalid_argument("Unexpected extra exec fields");}
};
// Metadata is a genuinely extensible typed Map; unlike executable constants,
// its fields can contain nested maps/arrays and arbitrary raw byte values.
void check_typed_metadata(raw_view view,int type) {
    if(type==2||type==0xad) {
        raw_cursor cursor(view);std::set<std::string> keys;
        while(!cursor.empty()) {
            if(type==2&&!keys.insert(cursor.string()).second)throw std::invalid_argument("Duplicate extension key");
            const int child_type=cursor.integer();check_typed_metadata(cursor.take(),child_type);
        }
        return;
    }
    switch(type) {
    case 1:validate_utf8(view);return;
    case 3:case 0xce867:
        if(view.size!=4)throw std::invalid_argument("Invalid extension scalar width");return;
    case 0xce1066:case 0xce200b:
        if(view.size!=8)throw std::invalid_argument("Invalid extension scalar width");return;
    case 0x0d00:
        if(view.size!=1||view.data[0]>1)throw std::invalid_argument("Invalid extension boolean");return;
    case 0xce2009:
        if(view.size==0)throw std::invalid_argument("Empty extension BigInteger");return;
    case 0xce200a:return;
    default:throw std::invalid_argument("Unsupported extension value type");
    }
}
std::shared_ptr<AbdValue> copy_raw(raw_view value) {
    return std::make_shared<AbdValue>(value.data,static_cast<int>(value.size));
}
// Scalars are checked by the same ABD value decoders the host data API uses.
Value read_constant(raw_view envelope) {
    raw_cursor fields(envelope);
    const int type=fields.integer();const auto payload=fields.take();const auto value=copy_raw(payload);fields.finish();
    switch(type) {
    case 1:validate_utf8(payload);return Value(StringAbdValue(value).data);
    case 3:return Value(IntAbdValue(value).data);
    case 0xce200b:return Value(AddressAbdValue(value).data);
    case 0xce1066: {
        const DoubleAbdValue number(value);
        if(!std::isfinite(number.data))throw std::invalid_argument("Non-finite exec constant");
        return Value(number.data);
    }
    case 0xce867: {
        const FloatAbdValue number(value);
        if(!std::isfinite(number.data))throw std::invalid_argument("Non-finite exec constant");
        return Value(number.data);
    }
    case 0x0d00:return Value(BoolAbdValue(value).data);
    case 0xce200a: {
        const ByteArrayValue bytes(value,type);
        if(bytes.length==1&&bytes.data[0]==0xff)return Value{};
        throw std::invalid_argument("Unsupported script constant");
    }
    default:throw std::invalid_argument("Unsupported exec constant type: "+std::to_string(type));
    }
}
// Pre-order numbers of a function body's expression stacks (the body is node 0,
// contexts take none), matched against the nodes of its debug record.
struct node_numbering {
    std::uint32_t next=0;
    std::size_t located=0;
    const std::vector<std::uint32_t>* nodes=nullptr;
};
struct function_layout {
    std::size_t parameters,slots,globals;
    std::size_t hidden_count=0;
    node_numbering* numbering=nullptr;
    void check_slot(int id,bool declaration=false) const {
        if(id<0) {
            if(declaration)throw std::invalid_argument("Cannot declare a negative variable slot");
            const auto index=static_cast<std::uint64_t>(-static_cast<std::int64_t>(id)-1);
            if(index>=globals)throw std::invalid_argument("Global variable slot is outside its module: "+std::to_string(id));
        } else {
            if(static_cast<std::size_t>(id)>=slots)throw std::invalid_argument("Local variable slot is outside its function: "+std::to_string(id));
            if(declaration&&static_cast<std::size_t>(id)<parameters)throw std::invalid_argument("Cannot redeclare a parameter slot");
        }
    }
};
bool valid_hint(const std::string& hint) {
    auto first=[](unsigned char c){return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||c=='_';};
    if(hint.empty()||!first(hint[0]))return false;
    return std::all_of(hint.begin()+1,hint.end(),[&](unsigned char c){return first(c)||(c>='0'&&c<='9');});
}
Context read_context(raw_view view,const function_layout& layout,std::size_t depth);
std::vector<Context> read_contexts(raw_view view,const function_layout& layout,std::size_t depth) {
    if(depth>128)throw std::invalid_argument("ABD nesting exceeds 128 levels");
    raw_cursor cursor(view);std::vector<Context> result;
    while(!cursor.empty()) {
        if(result.size()>=max_slots)throw std::invalid_argument("Too many generic contexts");
        result.push_back(read_context(cursor.take(),layout,depth+1));
    }
    return result;
}
Context read_context(raw_view view,const function_layout& layout,std::size_t depth) {
    if(depth>128)throw std::invalid_argument("ABD nesting exceeds 128 levels");
    raw_cursor fields(view);auto spec=std::make_shared<context>();const int kind=fields.integer();
    if(kind==0) {
        const int ref=fields.integer();
        if(ref<0||static_cast<std::size_t>(ref)>=layout.hidden_count)throw std::invalid_argument("Invalid generic context reference");
        spec->reference=static_cast<std::uint32_t>(ref);
    } else if(kind==1) {
        spec->abi=fields.integer();spec->kind=fields.integer();const int width=fields.integer();
        if(!valid_context_kind(spec->abi,spec->kind)||width<1||static_cast<std::size_t>(width)>max_slots||(spec->kind!=3&&width!=1))throw std::invalid_argument("Invalid generic type context");
        spec->width=static_cast<std::uint32_t>(width);
        if(fields.boolean())spec->factory_id=fields.integer();
        spec->contexts=read_contexts(fields.take(),layout,depth+1);
        if((spec->factory_id||!spec->contexts.empty())&&spec->kind!=3)
            throw std::invalid_argument("Only object type contexts have default factories");
        if(!spec->factory_id&&!spec->contexts.empty())throw std::invalid_argument("Factory contexts require a factory");
        if(fields.boolean())spec->placement_id=fields.integer();
        spec->placement_contexts=read_contexts(fields.take(),layout,depth+1);
        if(fields.boolean())spec->element=read_context(fields.take(),layout,depth+1);
        if((spec->placement_id||!spec->placement_contexts.empty())&&spec->kind!=3)
            throw std::invalid_argument("Only class contexts have placement initializers");
        if(!spec->placement_id&&!spec->placement_contexts.empty())throw std::invalid_argument("Placement contexts require an initializer");
        if((spec->kind==4)!=static_cast<bool>(spec->element))throw std::invalid_argument("Buffer context requires exactly one element context");
        if(spec->element&&!spec->element->reference&&spec->element->abi==VOID_VALUE)throw std::invalid_argument("Buffer element cannot be void");
    } else throw std::invalid_argument("Unknown generic context kind");
    fields.finish();return spec;
}
Expression read_expression(raw_view view,const function_layout& layout,std::size_t logical_depth);
std::vector<Expression> read_expressions(raw_view view,const function_layout& layout,std::size_t logical_depth) {
    raw_cursor cursor(view);std::vector<Expression> result;
    while(!cursor.empty())result.push_back(read_expression(cursor.take(),layout,logical_depth));
    return result;
}
// mem_get(oa(...)) without contexts is typed member access, not a call.
bool member_access(const expression& node) {
    return node.opcode==2&&node.immediate==ids::id(ids::builtin::mem_get)&&node.contexts.empty()&&
        node.children.size()==1&&node.children.front()->opcode==9;
}
// Variables, member access and raw mem_get(pointer) can be assigned or dropped.
bool storage_target(const expression& node) {
    return node.opcode==3||(node.opcode==2&&node.immediate==ids::id(ids::builtin::mem_get));
}
// A sequence (exec v11) runs statements in the enclosing scope and then
// yields its value. It may not declare outside a nested block, leave a loop
// it does not contain, return or own objects: none of these could keep the
// meaning they have in a block.
void check_sequence_part(const expression& node,bool loop,bool block) {
    switch(node.opcode) {
    case 4:if(!block)throw std::invalid_argument("A sequence cannot declare a variable outside a nested block");break;
    case 29:case 30:if(!loop)throw std::invalid_argument("A sequence cannot break or continue a loop outside it");break;
    case 7:case 8:throw std::invalid_argument("A sequence cannot return");
    case 10:case 11:case 31:case 32:case 34:case 35:case 40:case 52:
        throw std::invalid_argument("A sequence cannot own, release or clean up objects");
    default:break;
    }
    for(std::size_t i=0;i<node.children.size();++i)
        check_sequence_part(*node.children[i],loop||(node.opcode==28&&i==1),block||node.opcode==1);
}
Expression read_expression(raw_view view,const function_layout& layout,std::size_t logical_depth) {
    auto node=std::make_shared<expression>();
    if(auto* numbering=layout.numbering) {
        node->node=numbering->next++;
        if(numbering->nodes&&numbering->located<numbering->nodes->size()&&(*numbering->nodes)[numbering->located]==node->node)
            node->location=static_cast<std::uint32_t>(++numbering->located);
    }
    raw_cursor fields(view);const int opcode=fields.integer();node->opcode=opcode;
    // The old representation counted Map/Array levels, while literal scalars
    // did not introduce a level. Retain that budget as well as raw stack depth.
    if(logical_depth>128||(opcode!=0&&logical_depth>=128))throw std::invalid_argument("ABD nesting exceeds 128 levels");
    const auto child=[&]{node->children.push_back(read_expression(fields.take(),layout,logical_depth+1));};
    const auto children=[&](std::size_t depth) {
        for(auto& value:read_expressions(fields.take(),layout,depth))node->children.push_back(std::move(value));
    };
    const auto one_context=[&]{node->contexts.push_back(read_context(fields.take(),layout,logical_depth+1));};
    switch(opcode) {
    case 0:node->constant=read_constant(fields.take());break;
    case 1:children(logical_depth+1);break;
    case 2:
        node->immediate=fields.integer();children(logical_depth+2);
        node->contexts=read_contexts(fields.take(),layout,logical_depth+1);
        break;
    case 3:case 5: {
        const int slot=fields.integer();layout.check_slot(slot);node->immediate=slot;
        if(opcode==5)child();
        break;
    }
    case 4: {
        const int slot=fields.integer();layout.check_slot(slot,true);const int type=fields.integer();
        if(type<INT_VALUE||type>OBJECT_VALUE||type==VOID_VALUE)throw std::invalid_argument("Invalid declared variable type");
        node->immediate=slot;
        if(fields.boolean())child();
        break;
    }
    case 6:
        child();child();
        if(!storage_target(*node->children[0]))throw std::invalid_argument("Assignment target must be a variable or mem_get(pointer)");
        break;
    case 32: {
        const int size=fields.integer();
        if(size<1||static_cast<std::size_t>(size)>max_slots)throw std::invalid_argument("Invalid object slot count");
        node->immediate=size;break;
    }
    case 33:child();break;
    case 34: {
        const int slot=fields.integer();layout.check_slot(slot);
        if(slot<0)throw std::invalid_argument("Object move requires a local slot");
        node->immediate=slot;break;
    }
    case 35:
        child();
        if(!storage_target(*node->children[0]))throw std::invalid_argument("Object drop target must be a variable or mem_get(pointer)");
        break;
    case 36:case 37:case 40:one_context();break;
    case 38:case 39:child();one_context();break;
    case 41: {
        const int operation=fields.integer();child();const auto first=node->children.size();
        children(logical_depth+1);
        static constexpr std::size_t counts[]{0,0,1,1,2,1,1};
        if(operation<0||operation>6||node->children.size()-first!=counts[operation])throw std::invalid_argument("Invalid buffer operation arguments");
        node->immediate=operation;break;
    }
    case 42:one_context();child();child();break;
    case 43:case 44:case 45:case 46:case 47:case 48:child();child();break;
    case 49: {
        const int target=fields.integer();
        if(target!=INT_VALUE&&target!=STRING_VALUE&&target!=FLOAT_VALUE&&target!=DOUBLE_VALUE&&target!=ADDRESS_VALUE)
            throw std::invalid_argument("Invalid conversion target type: "+std::to_string(target));
        node->immediate=target;child();break;
    }
    case 52: {
        child();raw_cursor entries(fields.take());
        while(!entries.empty()) {
            if(node->methods.size()>=max_slots)throw std::invalid_argument("Too many virtual method entries");
            raw_cursor entry(entries.take());method value;value.function_id=entry.integer();
            value.contexts=read_contexts(entry.take(),layout,logical_depth+2);entry.finish();
            node->methods.push_back(std::move(value));
        }
        break;
    }
    case 53: {
        const int slot=fields.integer(),type=fields.integer();
        if(slot<0||static_cast<std::size_t>(slot)>=max_slots)throw std::invalid_argument("Invalid virtual method slot");
        if(type<INT_VALUE||type>OBJECT_VALUE)throw std::invalid_argument("Invalid virtual method return type");
        children(logical_depth+2);
        if(node->children.empty()||node->children.size()>max_slots)throw std::invalid_argument("Virtual method call requires a receiver and at most 1048576 arguments");
        node->immediate=slot;node->return_type=type;break;
    }
    case 51: {
        child();one_context();
        raw_cursor tags(fields.take());
        while(!tags.empty()) {
            const int tag=tags.integer();
            if(tag<INT_VALUE||tag>OBJECT_VALUE||tag==VOID_VALUE||node->slot_types.size()>=max_slots)
                throw std::invalid_argument("Invalid object-check slot type");
            node->slot_types.push_back(tag);
        }
        const auto& checked=*node->contexts[0];
        if(!checked.reference&&checked.kind==3&&node->slot_types.size()!=checked.width)
            throw std::invalid_argument("Object-check class slot count must match context width");
        break;
    }
    case 50:child();child();child();break;
    case 54:
        children(logical_depth+1);child();
        for(const auto& part:node->children)check_sequence_part(*part,false,false);
        break;
    case 55:case 56:case 57:child();child();break;
    case 59:break;
    case 58: {
        // The guarded target is kept as a method entry without contexts, so
        // discovery, relocation and linking check it as one.
        child();const int slot=fields.integer();
        if(slot<0||static_cast<std::size_t>(slot)>=max_slots)throw std::invalid_argument("Invalid virtual method slot");
        node->immediate=slot;node->methods.push_back({fields.integer(),{}});break;
    }
    case 7:case 8:if(opcode==8||fields.boolean())child();break;
    case 9: {
        child();const int offset=fields.integer();
        if(offset<0)throw std::invalid_argument("Negative object member offset");
        node->immediate=offset;break;
    }
    case 10: {
        child();
        if(fields.boolean())node->destructor_id=fields.integer();
        node->manual=fields.boolean();
        const auto destructor=node->destructor_id;
        if(destructor&&(*destructor==0||*destructor==1||*destructor==ids::main_function||namespace_of(*destructor)==ids::builtin_namespace))
            throw std::invalid_argument("Invalid object destructor id");
        node->contexts=read_contexts(fields.take(),layout,logical_depth+1);
        if(!destructor&&!node->contexts.empty())throw std::invalid_argument("Destructor contexts require a destructor");
        break;
    }
    case 11:child();break;
    case 12:case 13:case 14:case 15:case 16:case 17:case 18:case 19:case 20:case 21:case 22:case 23:case 24:
        child();child();break;
    case 25:case 26:child();break;
    case 27:child();child();if(fields.boolean())child();break;
    case 28:child();child();break;
    case 29:case 30:break;
    case 31:child();child();node->on_error=fields.boolean();break;
    default:throw std::invalid_argument("Unknown exec opcode: "+std::to_string(opcode));
    }
    fields.finish();return node;
}
std::vector<int> read_types(raw_view view) {
    raw_cursor fields(view);std::vector<int> result;
    while(!fields.empty()) {
        if(result.size()>=max_slots)throw std::invalid_argument("Too many parameter types");
        const int type=fields.integer();
        if(type<INT_VALUE||(type>BOOLEAN_VALUE&&type!=ADDRESS_VALUE&&type!=OBJECT_VALUE&&type!=ANY_VALUE))
            throw std::invalid_argument("Invalid parameter type");
        result.push_back(type);
    }
    return result;
}
// Debug names and files are valid UTF-8 without controls (C0, DEL, C1), line or
// paragraph separators or bidirectional formatting characters, like the compiler's
// SourceRules.unsafeDisplayCharacter, so they cannot forge a stack-trace line.
std::string debug_text(raw_cursor& fields,const char* label) {
    auto text=fields.string();
    if(text.empty())throw std::invalid_argument(std::string("Empty exec debug ")+label);
    for(std::size_t i=0;i<text.size();) {
        const auto first=static_cast<unsigned char>(text[i]);
        std::uint32_t code;std::size_t length;
        if(first<0x80){code=first;length=1;}
        else if(first<0xe0){code=first&0x1fu;length=2;}
        else if(first<0xf0){code=first&0x0fu;length=3;}
        else {code=first&0x07u;length=4;}
        for(std::size_t k=1;k<length;k++)code=(code<<6)|(static_cast<unsigned char>(text[i+k])&0x3fu);
        i+=length;
        if(code<0x20||(code>=0x7f&&code<=0x9f)||code==0x2028||code==0x2029||code==0x061c||code==0x200e||code==0x200f||
           (code>=0x202a&&code<=0x202e)||(code>=0x2066&&code<=0x2069))
            throw std::invalid_argument(std::string("Exec debug ")+label+" contains a control, separator or bidirectional formatting character");
    }
    return text;
}
// [name, file, line, column, locations]; locations are (node, file, line, column)
// quads with strictly increasing nodes. Nodes past the body are rejected after it is read.
std::shared_ptr<function_debug> read_function_debug(raw_view view,const std::shared_ptr<const std::vector<std::string>>& files,
        std::vector<std::uint32_t>& nodes,int function_id) {
    raw_cursor fields(view);auto debug=std::make_shared<function_debug>();debug->files=files;
    debug->name=debug_text(fields,"name");
    const auto file=[&](int value) {
        if(value<0||static_cast<std::size_t>(value)>=files->size())throw std::invalid_argument("Exec debug file index outside debug files");
        return static_cast<std::uint32_t>(value);
    };
    debug->header.file=file(fields.integer());
    const int line=fields.integer(),column=fields.integer();
    if(line<0||column<0||(line==0)!=(column==0))throw std::invalid_argument("Exec debug header position must be both zero or both positive");
    debug->header.line=static_cast<std::uint32_t>(line);debug->header.column=static_cast<std::uint32_t>(column);
    raw_cursor quads(fields.take());
    // exec v11: an extended record adds an inline index to each location, the
    // inlined functions and a display id. Plain -g output keeps five fields.
    debug->extended=!fields.empty();
    while(!quads.empty()) {
        if(debug->locations.size()>=max_slots)throw std::invalid_argument("Too many exec debug locations");
        const int node=quads.integer();
        if(quads.empty())throw std::invalid_argument(debug->extended?"Exec debug locations must be node, file, line, column, inline quintuples":
            "Exec debug locations must be node, file, line, column quads");
        source_position position;position.file=file(quads.integer());
        const int at_line=quads.integer(),at_column=quads.integer();
        if(node<0||(!nodes.empty()&&static_cast<std::uint32_t>(node)<=nodes.back()))
            throw std::invalid_argument("Exec debug location nodes must strictly increase");
        if(at_line<1||at_column<1)throw std::invalid_argument("Exec debug location line and column must be positive");
        position.line=static_cast<std::uint32_t>(at_line);position.column=static_cast<std::uint32_t>(at_column);
        if(debug->extended) {
            const int inline_index=quads.integer();
            if(inline_index<0)throw std::invalid_argument("Exec debug inline index must not be negative");
            debug->location_inlines.push_back(static_cast<std::uint32_t>(inline_index));
        }
        nodes.push_back(static_cast<std::uint32_t>(node));debug->locations.push_back(position);
    }
    if(debug->extended) {
        const auto same_namespace=[&](int id,const char* label) {
            if(id==0||id==1||namespace_of(id)!=namespace_of(function_id))
                throw std::invalid_argument(std::string("Exec debug ")+label+" must be a function of the same namespace");
        };
        raw_cursor inlined(fields.take());debug->display_id=fields.integer();fields.finish();
        same_namespace(debug->display_id,"display id");
        while(!inlined.empty()) {
            if(debug->inlined.size()>=max_slots)throw std::invalid_argument("Too many exec inlined functions");
            raw_cursor record(inlined.take());inlined_function value;
            value.function_id=record.integer();value.name=debug_text(record,"inlined name");const int call_site=record.integer();record.finish();
            same_namespace(value.function_id,"inlined function");
            // A call site lies outside the function it calls, in the caller or
            // an earlier inlined function, so every chain ends.
            if(call_site<1||static_cast<std::size_t>(call_site)>debug->locations.size()||
               debug->location_inlines[static_cast<std::size_t>(call_site)-1]>debug->inlined.size())
                throw std::invalid_argument("Exec debug inline call site must be an earlier location outside the inlined function");
            value.call_site=static_cast<std::uint32_t>(call_site);debug->inlined.push_back(std::move(value));
        }
        for(const auto index:debug->location_inlines)
            if(index>debug->inlined.size())throw std::invalid_argument("Exec debug inline index outside inlined functions");
    } else fields.finish();
    return debug;
}
module read_fields(raw_view root,std::size_t offset) {
    raw_cursor fields(root);module result;result.global_offset=offset;
    if(fields.string()!="AZSCRIPT")throw std::invalid_argument("Invalid exec magic");
    if(const int version=fields.integer();version!=format_version)
        throw std::invalid_argument("Unsupported exec version "+std::to_string(version)+"; expected exec v"+std::to_string(format_version));
    fields.integer();fields.string(); // Source version/author are non-executable metadata.
    const int globals=fields.integer();
    if(globals<0||static_cast<std::size_t>(globals)>max_slots||offset>max_slots-static_cast<std::size_t>(globals))
        throw std::invalid_argument("Invalid global variable count");
    result.global_count=static_cast<std::size_t>(globals);
    auto extension=fields.take();check_typed_metadata(extension,2);
    result.meta=std::make_shared<AbdMap>(std::make_shared<AbdStack>(copy_raw(extension)));
    raw_cursor signatures(fields.take());
    while(!signatures.empty()) {
        raw_cursor entry(signatures.take());const int id=entry.integer();function_signature value;
        if(id==0||id==1||id==ids::main_function||namespace_of(id)==ids::builtin_namespace)throw std::invalid_argument("Invalid external function id");
        value.return_type=entry.integer();
        if(value.return_type<INT_VALUE||value.return_type>OBJECT_VALUE)throw std::invalid_argument("Invalid external return type");
        value.param_types=read_types(entry.take());
        value.hidden_count=entry.integer();value.entry_kind=entry.integer();entry.finish();
        if(value.hidden_count<0||static_cast<std::size_t>(value.hidden_count)>max_slots||value.entry_kind<0||value.entry_kind>1)
            throw std::invalid_argument("Invalid external generic ABI");
        if(!value.hidden_count&&(value.return_type==ANY_VALUE||std::find(value.param_types.begin(),value.param_types.end(),ANY_VALUE)!=value.param_types.end()))
            throw std::invalid_argument("External any types require generic contexts");
        if(!result.extern_signatures.emplace(id,std::move(value)).second)throw std::invalid_argument("Duplicate external signature id: "+function_id_text(id));
    }
    raw_cursor functions(fields.take());
    result.hint=fields.string();
    if(!result.hint.empty()&&!valid_hint(result.hint))throw std::invalid_argument("Invalid namespace hint");
    raw_cursor assumptions(fields.take());
    auto debug_files=std::make_shared<std::vector<std::string>>();
    {
        raw_cursor files(fields.take());std::set<std::string> names;
        while(!files.empty()) {
            if(debug_files->size()>=max_slots)throw std::invalid_argument("Too many exec debug files");
            auto name=debug_text(files,"file name");
            if(!names.insert(name).second)throw std::invalid_argument("Duplicate exec debug file name");
            debug_files->push_back(std::move(name));
        }
    }
    fields.finish();std::set<std::string> hint_names;
    while(!assumptions.empty()) {
        raw_cursor record(assumptions.take());auto hint=record.string();const int ns=record.integer();record.finish();
        if(!valid_hint(hint)||ns<=0||ns>0xffff||ns==ids::builtin_namespace||ns==0x0fff)throw std::invalid_argument("Invalid namespace assumption");
        if(!hint_names.insert(hint).second||!result.assumptions.emplace(ns,std::move(hint)).second)
            throw std::invalid_argument("Duplicate namespace assumption");
    }
    if(!result.hint.empty()&&!hint_names.contains(result.hint))throw std::invalid_argument("Hint module requires an assumption for itself");
    std::set<int> defined;
    while(!functions.empty()) {
        raw_cursor entry(functions.take());function fn;
        fn.function_id=entry.integer();fn.return_type=entry.integer();
        const int param_count=entry.integer(),local_count=entry.integer();
        if(namespace_of(fn.function_id)==ids::builtin_namespace)
            throw std::invalid_argument("Invalid script function id");
        if(!result.hint.empty()&&namespace_of(fn.function_id)!=0)throw std::invalid_argument("Hint module definitions require namespace zero");
        if(result.hint.empty()&&fn.function_id!=0&&fn.function_id!=1&&result.assumptions.contains(namespace_of(fn.function_id)))
            throw std::invalid_argument("Namespace assumption overlaps module definitions");
        if(fn.return_type<INT_VALUE||fn.return_type>OBJECT_VALUE)throw std::invalid_argument("Unknown return type");
        if(param_count<0||local_count<0||static_cast<std::size_t>(param_count)>max_slots||
           static_cast<std::size_t>(local_count)>max_slots-static_cast<std::size_t>(param_count))
            throw std::invalid_argument("Invalid function variable count");
        fn.param_types=read_types(entry.take());
        auto body=entry.take();const int hidden=entry.integer();fn.entry_kind=entry.integer();
        std::vector<std::uint32_t> located;
        if(entry.boolean())fn.debug=read_function_debug(entry.take(),debug_files,located,fn.function_id);
        entry.finish();
        if(hidden<0||static_cast<std::size_t>(hidden)>max_slots||fn.entry_kind<0||fn.entry_kind>2)
            throw std::invalid_argument("Invalid function generic ABI");
        // exec v11 private functions (entry kind 2) are compiler clones that
        // only direct calls of their own module may reach.
        if(fn.entry_kind==2&&(fn.function_id==0||fn.function_id==1||fn.function_id==ids::main_function))
            throw std::invalid_argument("Lifecycle and main functions cannot be private");
        if(fn.return_type==ANY_VALUE&&!hidden)throw std::invalid_argument("Any return requires generic contexts");
        if(fn.param_types.size()!=static_cast<std::size_t>(param_count))throw std::invalid_argument("param-count and param-types disagree");
        if((fn.function_id==0||fn.function_id==1)&&(fn.return_type!=VOID_VALUE||param_count!=0||hidden!=0))
            throw std::invalid_argument("Lifecycle function must return void and take no parameters");
        fn.local_count=static_cast<std::uint32_t>(local_count);fn.hidden_count=static_cast<std::uint32_t>(hidden);
        node_numbering numbering;numbering.nodes=&located;
        const function_layout layout{static_cast<std::size_t>(param_count),
            static_cast<std::size_t>(param_count)+static_cast<std::size_t>(local_count),result.global_count,
            static_cast<std::size_t>(hidden),&numbering};
        fn.body=read_expression(body,layout,3);
        if(numbering.located!=located.size())throw std::invalid_argument("Exec debug location refers to a missing body node");
        if(!defined.insert(fn.function_id).second)throw std::invalid_argument("Duplicate function id: "+function_id_text(fn.function_id));
        result.functions.push_back(std::move(fn));
    }
    return result;
}

// Discovery and relocation share one walk, so a reference cannot be found
// by one and missed by the other.
class reference_walk {
    const std::function<int(const reference&)>& resolve;
    bool copying;
    int visit(int id,reference_kind kind,std::size_t hidden,const expression& owner) {
        return resolve({id,kind,static_cast<std::uint32_t>(hidden),owner.node,owner.location});
    }
    Context walk(const Context& source,const expression& owner) {
        auto result=copying?std::make_shared<context>(*source):nullptr;
        for(std::size_t i=0;i<source->contexts.size();++i) {
            auto value=walk(source->contexts[i],owner);if(result)result->contexts[i]=std::move(value);
        }
        for(std::size_t i=0;i<source->placement_contexts.size();++i) {
            auto value=walk(source->placement_contexts[i],owner);if(result)result->placement_contexts[i]=std::move(value);
        }
        if(source->element) {
            auto value=walk(source->element,owner);if(result)result->element=std::move(value);
        }
        if(source->placement_id) {
            const auto id=visit(*source->placement_id,reference_kind::placement,source->placement_contexts.size(),owner);
            if(result)result->placement_id=id;
        }
        if(source->factory_id) {
            const auto id=visit(*source->factory_id,reference_kind::factory,source->contexts.size(),owner);
            if(result)result->factory_id=id;
        }
        return result?result:source;
    }
    void walk(const std::vector<Context>& input,std::vector<Context>* output,const expression& owner) {
        for(std::size_t i=0;i<input.size();++i) {
            auto value=walk(input[i],owner);if(output)(*output)[i]=std::move(value);
        }
    }
public:
    reference_walk(const std::function<int(const reference&)>& resolve,bool copying):resolve(resolve),copying(copying) {}
    // The reader's order: value_compare reads its context before its operands;
    // every other record reads operands first.
    Expression walk(const Expression& source) {
        auto result=copying?std::make_shared<expression>(*source):nullptr;
        if(source->opcode==42)walk(source->contexts,result?&result->contexts:nullptr,*source);
        for(std::size_t i=0;i<source->children.size();++i) {
            auto value=walk(source->children[i]);if(result)result->children[i]=std::move(value);
        }
        if(source->opcode!=42)walk(source->contexts,result?&result->contexts:nullptr,*source);
        if(source->opcode==2&&!member_access(*source)) {
            const auto id=visit(source->immediate,reference_kind::call,source->contexts.size(),*source);
            if(result)result->immediate=id;
        }
        if(source->destructor_id) {
            const auto id=visit(*source->destructor_id,reference_kind::destructor,source->contexts.size(),*source);
            if(result)result->destructor_id=id;
        }
        for(std::size_t i=0;i<source->methods.size();++i) {
            const auto& entry=source->methods[i];auto* copied=result?&result->methods[i]:nullptr;
            walk(entry.contexts,copied?&copied->contexts:nullptr,*source);
            const auto id=visit(entry.function_id,reference_kind::method,entry.contexts.size(),*source);
            if(copied)copied->function_id=id;
        }
        return result?result:source;
    }
};
}

module read_module(const unsigned char* bytes,std::size_t length,std::size_t global_offset) {
    if(length>64*1024*1024)throw std::invalid_argument("Script exceeds 64 MiB limit");
    if(!bytes||length<4)throw std::invalid_argument("Truncated ABD script");
    raw_cursor file({bytes,length,0});auto root=file.take();file.finish();
    raw_cursor fields(root);
    if(!fields.empty()) {
        const auto first=fields.take();
        if(first.size==8&&std::memcmp(first.data,"AZSCRIPT",8)==0)return read_fields(root,global_offset);
    }
    throw std::invalid_argument("Unsupported script format: expected raw exec v"+std::to_string(format_version));
}
std::vector<reference> references(const Expression& root) {
    std::vector<reference> result;
    const std::function<int(const reference&)> collect=[&](const reference& value) {
        result.push_back(value);return value.function_id;
    };
    reference_walk(collect,false).walk(root);return result;
}
Expression relocate(const Expression& root,const std::function<int(const reference&)>& resolver) {
    if(!resolver)throw std::invalid_argument("Empty reference resolver");
    return reference_walk(resolver,true).walk(root);
}
}
