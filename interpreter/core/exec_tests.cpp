#include "exec.h"
#include "../library.h"
#include "../vm/lowering.h"
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using namespace azertian;
using namespace azertian::vm;
namespace limits=azertian::limits;
using B=std::vector<unsigned char>;
int checks=0;
void check(bool success,const char* label) {
    ++checks;if(!success)throw std::runtime_error(label);
}
template<class F> void rejects(F operation,const char* label,const char* message=nullptr) {
    bool failed=false;
    try {operation();}catch(const std::invalid_argument& error){failed=!message||std::string(error.what())==message;}
    check(failed,label);
}
B integer(int value) {
    const auto bits=std::bit_cast<std::uint32_t>(static_cast<std::int32_t>(value));
    return {static_cast<unsigned char>(bits),static_cast<unsigned char>(bits>>8),
        static_cast<unsigned char>(bits>>16),static_cast<unsigned char>(bits>>24)};
}
B wide(std::uint64_t bits) {
    B result;for(unsigned shift=0;shift<64;shift+=8)result.push_back(static_cast<unsigned char>(bits>>shift));return result;
}
B text(const std::string& value) {return {value.begin(),value.end()};}
B stack(std::initializer_list<B> fields) {
    B result;
    for(const auto& field:fields) {
        auto size=integer(static_cast<int>(field.size()));result.insert(result.end(),size.begin(),size.end());
        result.insert(result.end(),field.begin(),field.end());
    }
    return result;
}
B expression(int opcode,std::initializer_list<B> fields={}) {
    auto result=stack({integer(opcode)});auto rest=stack(fields);result.insert(result.end(),rest.begin(),rest.end());return result;
}
B constant(int value) {return expression(0,{stack({integer(3),integer(value)})});}
B address_constant(std::uint64_t value) {return expression(0,{stack({integer(0xce200b),wide(value)})});}
B fixed(int abi=INT_VALUE,int kind=0,int width=1) {
    return stack({integer(1),integer(abi),integer(kind),integer(width),B{0},B{},B{0},B{},B{0}});
}
B reference(int index) {return stack({integer(0),integer(index)});}
B class_context(int factory,int placement,B factories={},B placements={}) {
    return stack({integer(1),integer(OBJECT_VALUE),integer(3),integer(2),B{1},integer(factory),factories,
        B{1},integer(placement),placements,B{0}});
}
B call(int id,B args={},B contexts={}) {return expression(2,{integer(id),args,contexts});}

// One main function (one local, one hidden context) around body, in a module
// with one global; located body nodes get debug positions on lines 2, 3, ...
B module_bytes(const B& body,const std::vector<std::uint32_t>& located={}) {
    B debug{0},files;
    if(!located.empty()) {
        B quads;
        for(std::size_t i=0;i<located.size();++i) {
            const auto quad=stack({integer(static_cast<int>(located[i])),integer(0),integer(static_cast<int>(i)+2),integer(1)});
            quads.insert(quads.end(),quad.begin(),quad.end());
        }
        const auto record=stack({text("main"),integer(0),integer(1),integer(1),quads});
        debug=stack({B{1},record});
        files=stack({text("exec.azs")});
    } else debug=stack({B{0}});
    // The debug flag and the optional record are the entry's last fields.
    auto function=stack({integer(0x0fff0000),integer(INT_VALUE),integer(0),integer(1),B{},body,integer(1),integer(0)});
    function.insert(function.end(),debug.begin(),debug.end());
    return stack({stack({text("AZSCRIPT"),integer(10),integer(2),B{},integer(1),
        B{},B{},stack({function}),B{},B{},files})});
}
std::shared_ptr<const exec::expression> read_body(const B& body,const std::vector<std::uint32_t>& located={}) {
    const auto bytes=module_bytes(body,located);
    return exec::read_module(bytes.data(),bytes.size(),0).functions.at(0).body;
}
SourceFunction source_function(int id,const B& body) {
    SourceFunction result;result.function_id=id;result.body=read_body(body);return result;
}
SourceFunction receiver_function(int id,std::uint32_t hidden=0,int entry=0) {
    auto result=source_function(id,expression(7,{B{0}}));
    result.param_types={ADDRESS_VALUE};result.hidden_count=hidden;result.entry_kind=entry;return result;
}
void lower_opcode(const std::shared_ptr<const exec::expression>& body) {
    SourceProgram source;SourceFunction function;
    function.function_id=0x0fff0000;function.local_count=1;function.global_count=1;
    function.hidden_count=1;function.body=body;source.functions.push_back(std::move(function));
    source.functions.push_back(receiver_function(0x12340002));
    source.functions.push_back(receiver_function(0x12340003,1));
    const auto lowered=lower_program(source);
    check(lowered.functions.front().node_count>0,"every wire opcode passes lowering and verifier");
}
// The tree builds its expression tree from the same model and cross-checks
// its patch list against exec::references while loading.
void built_by_tree(const B& body) {
    const auto bytes=module_bytes(body);
    auto loaded=load_script(bytes.data(),bytes.size());
    check(static_cast<bool>(loaded),"the tree builds every validated record");
    loaded->destroy();
}

void all_opcodes() {
    const auto one=constant(1),two=constant(2),pointer=address_constant(1);
    const auto var=expression(3,{integer(0)}),empty=expression(1,{B{}}),context=fixed();
    std::vector<B> inputs(54);
    inputs[0]=one;inputs[1]=expression(1,{stack({one,two})});inputs[2]=call(0x12340001,stack({one}));
    inputs[3]=var;inputs[4]=expression(4,{integer(0),integer(INT_VALUE),B{1},one});
    inputs[5]=expression(5,{integer(-1),one});inputs[6]=expression(6,{var,one});
    inputs[7]=expression(7,{B{1},one});inputs[8]=expression(8,{pointer});
    inputs[9]=expression(9,{pointer,integer(3)});
    inputs[10]=expression(10,{pointer,B{1},integer(0x12340002),B{1},B{}});inputs[11]=expression(11,{pointer});
    for(int op=12;op<=24;++op)inputs[op]=expression(op,{one,two});
    inputs[25]=expression(25,{one});inputs[26]=expression(26,{one});
    inputs[27]=expression(27,{one,empty,B{1},empty});inputs[28]=expression(28,{one,empty});
    inputs[29]=expression(29);inputs[30]=expression(30);inputs[31]=expression(31,{empty,empty,B{1}});
    inputs[32]=expression(32,{integer(2)});inputs[33]=expression(33,{inputs[32]});
    inputs[34]=expression(34,{integer(0)});inputs[35]=expression(35,{var});
    inputs[36]=expression(36,{context});inputs[37]=expression(37,{context});
    inputs[38]=expression(38,{one,context});inputs[39]=expression(39,{one,context});
    inputs[40]=expression(40,{context});inputs[41]=expression(41,{integer(4),inputs[40],stack({one,two})});
    inputs[42]=expression(42,{context,one,two});
    for(int op=43;op<=48;++op)inputs[op]=expression(op,{one,two});
    inputs[49]=expression(49,{integer(ADDRESS_VALUE),one});inputs[50]=expression(50,{one,one,two});
    inputs[51]=expression(51,{pointer,fixed(ADDRESS_VALUE,2),stack({integer(INT_VALUE)})});
    inputs[52]=expression(52,{pointer,stack({stack({integer(0x12340003),stack({context})})})});
    inputs[53]=expression(53,{integer(2),integer(ADDRESS_VALUE),stack({pointer,one})});
    for(int opcode=0;opcode<54;++opcode) {
        built_by_tree(inputs[opcode]);
        auto parsed=read_body(inputs[opcode]);check(parsed->opcode==opcode,"every v10 opcode decodes");
        check(parsed->node==0&&parsed->location==0,"root node numbering starts at zero");
        // Traversal and relocation must work for every record, including ones
        // containing only contexts and no ordinary expression operands.
        const auto before=exec::references(parsed);
        const auto moved=exec::relocate(parsed,[](const auto& ref){return ref.function_id;});
        check(exec::references(moved).size()==before.size(),"identity relocation preserves references");
        lower_opcode(parsed);
    }
    check(read_body(inputs[4])->immediate==0,"definition preserves slot, not a permanent type");
    check(read_body(inputs[5])->immediate==-1,"negative global slot retained");
    check(read_body(inputs[9])->immediate==3,"member offset retained");
    const auto binding=read_body(inputs[10]);
    check(binding->manual&&binding->destructor_id==0x12340002,"object registration fields retained");
    check(read_body(inputs[31])->on_error,"cleanup mode retained");
    check(read_body(inputs[41])->immediate==4&&read_body(inputs[41])->children.size()==3,"buffer receiver precedes arguments");
    check(read_body(inputs[51])->slot_types==std::vector<int>{INT_VALUE},"object direct slot tags retained");
    const auto virtual_call=read_body(inputs[53]);
    check(virtual_call->immediate==2&&virtual_call->return_type==ADDRESS_VALUE&&virtual_call->children.size()==2,"virtual fields retained");
    check(read_body(expression(4,{integer(0),integer(ANY_VALUE),B{0}}))->children.empty(),"absent initializer");
    check(read_body(expression(7,{B{0}}))->children.empty(),"absent return expression");
    check(read_body(expression(27,{one,empty,B{0}}))->children.size()==2,"absent else");
    check(!read_body(expression(10,{pointer,B{0},B{0},B{}}))->destructor_id,"absent destructor");
    check(read_body(expression(52,{pointer,B{}}))->methods.empty(),"empty method phase retained");
}

void constants_and_locations() {
    auto text=expression(0,{stack({integer(1),B{'a',0,0xf0,0x9f,0x98,0x80}})});
    auto f=expression(0,{stack({integer(0xce867),integer(std::bit_cast<std::int32_t>(0x80000000u))})});
    auto d=expression(0,{stack({integer(0xce1066),wide(0x8000000000000000ULL)})});
    auto truth=expression(0,{stack({integer(0xd00),B{1}})});
    auto nil=expression(0,{stack({integer(0xce200a),B{0xff}})});
    auto bytes=expression(1,{stack({text,f,d,truth,nil,address_constant(0xfedcba9876543210ULL)})});
    const std::vector<std::uint32_t> locations{0,2,6};
    auto result=read_body(bytes,locations);
    // The source remains valid after the complete incoming buffer is destroyed.
    bytes.clear();bytes.shrink_to_fit();
    check(result->location==1&&result->children[0]->node==1&&result->children[0]->location==0,"unlocated nodes do not inherit during decode");
    check(result->children[1]->location==2&&result->children[5]->location==3,"located index is one-based");
    check(result->children[0]->constant.get<std::string>()==std::string("a\0\xf0\x9f\x98\x80",6),"string NUL and UTF-8 bytes owned");
    check(std::bit_cast<std::uint32_t>(result->children[1]->constant.get<float>())==0x80000000u,"float negative zero exact");
    check(std::bit_cast<std::uint64_t>(result->children[2]->constant.get<double>())==0x8000000000000000ULL,"double negative zero exact");
    check(result->children[3]->constant.get<bool>(),"boolean exact");
    check(result->children[4]->constant.type_tag()==VOID_VALUE,"void sentinel exact");
    check(result->children[5]->constant.get<address>().value==0xfedcba9876543210ULL,"address full bit pattern");
    rejects([&]{read_body(constant(1),std::vector<std::uint32_t>{1});},"out-of-body debug index rejected",
        "Exec debug location refers to a missing body node");
    rejects([&]{read_body(constant(1),std::vector<std::uint32_t>{0,0});},"duplicate debug index rejected",
        "Exec debug location nodes must strictly increase");
    rejects([&]{read_body(B{1,2,3});},"truncated buffer rejected without reading past it","Missing or truncated exec field");
    rejects([&]{read_body(expression(54));},"unknown opcode rejected","Unknown exec opcode: 54");
    rejects([&]{read_body(expression(3,{integer(0),integer(0)}));},"extra fields rejected","Unexpected extra exec fields");
}

void reference_binding() {
    const auto inner=class_context(0x12340003,0x12340004);
    const auto outer=class_context(0x12340001,0x12340002,stack({inner}),stack({reference(0)}));
    const auto nested_call=call(0x12340005,stack({call(0x12340006)}),stack({outer}));
    const auto comparison=expression(42,{outer,call(0x12340006),call(0x12340007)});
    const auto methods=expression(52,{call(0x12340007),stack({stack({integer(0x12340008),stack({outer})})})});
    const auto destructor=expression(10,{address_constant(1),B{1},integer(0x12340009),B{0},stack({outer})});
    const auto full_id=std::bit_cast<std::int32_t>(0xf1230001u);
    auto root=read_body(expression(1,{stack({nested_call,comparison,methods,destructor,
        call(full_id,stack({constant(full_id)}))})}));
    const auto refs=exec::references(root);
    const std::vector<int> suffixes{6,4,3,2,1,5,4,3,2,1,6,7,7,4,3,2,1,8,4,3,2,1,9};
    check(refs.size()==suffixes.size()+1,"all ordinary and recursive metadata references found");
    for(std::size_t i=0;i<suffixes.size();++i)
        check(refs[i].function_id==0x12340000+suffixes[i],"reference discovery matches legacy field order");
    check(refs.back().function_id==full_id,"high-bit function ID retained");
    check(refs[1].kind==exec::reference_kind::placement&&refs[2].kind==exec::reference_kind::factory,"factory and placement roles differ");
    check(refs[3].hidden_count==1&&refs[4].hidden_count==1&&refs[5].hidden_count==1,"bound hidden argument counts retained");
    check(refs[17].kind==exec::reference_kind::method&&refs[22].kind==exec::reference_kind::destructor,"method/destructor roles differ");
    const auto relocated=exec::relocate(root,[](const exec::reference& ref) {
        return std::bit_cast<std::int32_t>(std::bit_cast<std::uint32_t>(static_cast<std::int32_t>(ref.function_id))^0x00100000u);
    });
    const auto moved=exec::references(relocated),unchanged=exec::references(root);
    for(std::size_t i=0;i<refs.size();++i) {
        check(unchanged[i].function_id==refs[i].function_id,"relocation leaves original immutable graph intact");
        check(std::bit_cast<std::uint32_t>(static_cast<std::int32_t>(moved[i].function_id))==
            (std::bit_cast<std::uint32_t>(static_cast<std::int32_t>(refs[i].function_id))^0x00100000u),"every reference relocated");
    }
    check(relocated->children.back()->children.front()->constant.get<int>()==full_id,"integer constants resembling IDs are untouched");
    int visited=0;
    rejects([&]{exec::relocate(root,[&](const auto& ref) {
        if(++visited==3)throw std::invalid_argument("planned failure");
        return ref.function_id+1;
    });},"resolver failure aborts prepared copy");
    check(exec::references(root)[0].function_id==refs[0].function_id,"failed relocation does not partially alter input");

    const auto member=call(0x0abd0006,stack({expression(9,{call(0x12340010),integer(0)})}));
    const auto raw=call(0x0abd0006,stack({address_constant(1)}));
    auto member_source=read_body(expression(1,{stack({member,raw})}));
    const auto member_refs=exec::references(member_source);
    check(member_refs.size()==2&&member_refs[0].function_id==0x12340010&&member_refs[1].function_id==0x0abd0006,
        "typed member mem_get excludes callback binding, raw mem_get retains it");
    const auto member_moved=exec::relocate(member_source,[](const auto& ref){return ref.function_id+1;});
    check(member_moved->children[0]->immediate==0x0abd0006&&member_moved->children[0]->children[0]->children[0]->immediate==0x12340011,
        "typed member marker survives relocation while receiver calls relocate");

    const auto buffer=stack({integer(1),integer(OBJECT_VALUE),integer(4),integer(1),B{0},B{},B{0},B{},B{1},outer});
    auto buffer_source=read_body(expression(40,{buffer}));
    check(exec::references(buffer_source).size()==4,"buffer element contexts participate in references");
    check(buffer_source->contexts[0]->element->placement_contexts[0]->reference==0,"context reference kept, not expression numbered");
}

void module_sources() {
    const auto body=expression(1,{stack({expression(7,{B{1},expression(3,{integer(0)})})})});
    const auto locations=stack({integer(0),integer(0),integer(9),integer(2),
        integer(2),integer(0),integer(10),integer(3)});
    const auto debug=stack({text("entry"),integer(0),integer(7),integer(1),locations});
    const auto ordinary=stack({integer(2),integer(INT_VALUE),integer(2),integer(3),
        stack({integer(INT_VALUE),integer(STRING_VALUE)}),body,integer(1),integer(0),B{1},debug});
    const auto lifecycle=[&](int id) {
        return stack({integer(id),integer(VOID_VALUE),integer(0),integer(0),B{},expression(7,{B{0}}),
            integer(0),integer(0),B{0}});
    };
    const auto signature=stack({integer(0x33330004),integer(ANY_VALUE),stack({integer(ADDRESS_VALUE),integer(ANY_VALUE)}),
        integer(1),integer(0)});
    auto bytes=stack({stack({text("AZSCRIPT"),integer(10),integer(999),text("ignored author"),integer(2),B{},
        stack({signature}),stack({lifecycle(0),ordinary,lifecycle(1)}),text("BRIDGE"),
        stack({stack({text("BRIDGE"),integer(0x2222)}),stack({text("OTHER"),integer(0x3333)})}),
        stack({text("bridge.azs")})})});
    auto validated=load_script(bytes.data(),bytes.size());
    check(static_cast<bool>(validated),"complete hinted/debug module builds a tree");
    validated->destroy();
    const auto module=exec::read_module(bytes.data(),bytes.size(),17);
    bytes.clear();bytes.shrink_to_fit();
    check(module.global_offset==17&&module.global_count==2,"module global range retained");
    check(module.hint=="BRIDGE"&&module.meta,"module hint and metadata retained");
    check(module.assumptions==std::map<int,std::string>{{0x2222,"BRIDGE"},{0x3333,"OTHER"}},"module assumptions retained");
    const auto& signature_found=module.extern_signatures.at(0x33330004);
    check(module.extern_signatures.size()==1&&signature_found.return_type==ANY_VALUE&&signature_found.hidden_count==1&&
        signature_found.param_types==std::vector<int>{ADDRESS_VALUE,ANY_VALUE},"external signature generic ABI retained");
    check(module.functions.size()==3&&module.functions[0].function_id==0&&module.functions[2].function_id==1,
        "both lifecycle functions retained in wire order without relocation");
    const auto& function=module.functions[1];
    check(function.function_id==2&&function.return_type==INT_VALUE&&function.local_count==3&&function.hidden_count==1&&
        function.param_types==std::vector<int>{INT_VALUE,STRING_VALUE},"ordinary function layout and original wire ID retained");
    check(function.debug&&function.debug->name=="entry"&&function.debug->header.line==7&&function.debug->header.column==1&&
        function.debug->locations.size()==2&&function.debug->locations[1].line==10&&function.debug->locations[1].column==3&&
        *function.debug->files==std::vector<std::string>{"bridge.azs"},"full function debug positions retained");
    check(function.body->location==1&&function.body->children[0]->location==0&&
        function.body->children[0]->children[0]->location==2,"module debug nodes mapped to one-based source locations");
    check(!module.functions[0].debug&&!module.functions[2].debug,"stripped lifecycle debug stays absent");
    SourceProgram sources;
    for(const auto& definition:module.functions) {
        SourceFunction lowered;lowered.function_id=definition.function_id;lowered.return_type=definition.return_type;
        lowered.lifecycle=definition.function_id==0||definition.function_id==1;lowered.param_types=definition.param_types;
        lowered.local_count=definition.local_count;lowered.hidden_count=definition.hidden_count;
        lowered.global_offset=17;lowered.global_count=2;lowered.body=definition.body;
        if(definition.debug)lowered.debug=*definition.debug;
        sources.functions.push_back(std::move(lowered));
    }
    const auto lowered=lower_program(sources);
    check(lowered.functions.size()==3&&lowered.targets.size()==1&&lowered.targets[0].function_id==2,
        "lifecycle functions stay outside public call targets");
    check(lowered.functions[1].global_offset==17&&lowered.functions[1].debug->locations[1].line==10,
        "module global and debug data survive lowering");
}

void rejected_modules() {
    // Module-level errors keep the loader's messages and order.
    const auto main=[](const B& body,int hidden=0) {
        return stack({integer(0x0fff0000),integer(INT_VALUE),integer(0),integer(0),B{},body,integer(hidden),integer(0),B{0}});
    };
    const auto file=[](const B& functions,int globals=0,const B& hint={},const B& assumptions={}) {
        return stack({stack({text("AZSCRIPT"),integer(10),integer(2),B{},integer(globals),B{},B{},functions,hint,assumptions,B{}})});
    };
    const auto read=[](const B& bytes){return exec::read_module(bytes.data(),bytes.size(),0);};
    rejects([&]{read(file(stack({main(constant(1)),main(constant(2))})));},"duplicate definitions","Duplicate function id: 0x0fff0000");
    rejects([&]{read(file(stack({main(expression(3,{integer(1)}))})));},"local slot bound","Local variable slot is outside its function: 1");
    rejects([&]{read(file(stack({main(expression(3,{integer(-1)}))})));},"global slot bound","Global variable slot is outside its module: -1");
    rejects([&]{read(file(stack({main(expression(40,{reference(0)}))})));},"hidden reference bound","Invalid generic context reference");
    rejects([&]{read(file(stack({main(expression(6,{constant(1),constant(2)}))})));},"assignment target",
        "Assignment target must be a variable or mem_get(pointer)");
    rejects([&]{read(file(stack({main(constant(1))}),0,text("lower")));},"hint without assumption",
        "Hint module requires an assumption for itself");
    rejects([&]{read(file(stack({}),-1));},"global count","Invalid global variable count");
    const auto envelope=stack({text("AZSCRIPT"),integer(9)});
    rejects([&]{read(stack({envelope}));},"exec version","Unsupported exec version 9; expected exec v10");
    const auto bytes=file(stack({main(constant(1))}),3);
    rejects([&]{exec::read_module(bytes.data(),bytes.size(),limits::max_slots-2);},"global offset at the slot limit",
        "Invalid global variable count");
    check(exec::read_module(bytes.data(),bytes.size(),limits::max_slots-3).global_offset==limits::max_slots-3,
        "global offset up to the slot limit");
}

void lowered_metadata() {
    constexpr int factory=0x12340010,placement=0x12340011,method=0x12340012,destructor=0x12340013;
    const auto context=class_context(factory,placement,stack({reference(0)}),stack({reference(0)}));
    const auto body=expression(1,{stack({
        expression(37,{context}),
        call(0x0abd0006,stack({expression(9,{address_constant(1),integer(0)})})),
        call(0x0abd0006,stack({address_constant(1)})),
        expression(52,{address_constant(1),stack({stack({integer(method),stack({reference(0)})})})}),
        expression(53,{integer(0),integer(VOID_VALUE),stack({address_constant(1)})}),
        expression(42,{reference(0),constant(1),constant(2)}),
        expression(10,{address_constant(1),B{1},integer(destructor),B{0},stack({reference(0)})})})});
    SourceProgram source;SourceFunction function;
    function.function_id=0x0fff0000;function.hidden_count=1;
    function.body=read_body(body,std::vector<std::uint32_t>{0,3,7});
    function.debug=DebugInfo{"bridge",std::make_shared<const std::vector<std::string>>(std::vector<std::string>{"bridge.azs"}),{0,1,1},{{0,2,1},{0,3,1},{0,4,1}}};
    source.functions.push_back(function);
    auto make=source_function(factory,address_constant(1));make.return_type=OBJECT_VALUE;make.hidden_count=1;
    source.functions.push_back(std::move(make));
    source.functions.push_back(receiver_function(placement,1,1));
    source.functions.push_back(receiver_function(method,1));
    source.functions.push_back(receiver_function(destructor,1));
    const auto program=lower_program(source);const auto& first=program.functions.front();
    check(first.node_count==16,"context metadata does not consume expression preorder nodes");
    const auto has=[&](Opcode opcode,std::uint32_t node,std::uint32_t location) {
        for(auto pc=first.begin;pc<first.end;++pc)
            if(program.instructions[pc].opcode==opcode&&program.sources[pc].node==node&&
                    program.sources[pc].location==location)return true;
        return false;
    };
    check(has(Opcode::member_get,2,1)&&has(Opcode::object_address,3,2)&&has(Opcode::constant,4,2),
        "typed member path preserves parent and inherited debug sites");
    check(!has(Opcode::call,2,1)&&has(Opcode::call,5,1),"only raw mem_get lowers to a call");
    check(has(Opcode::set_methods,7,3)&&has(Opcode::call_virtual,9,1),"method and virtual debug sites retained");
    const auto method_tick=std::count_if(program.instructions.begin()+first.begin,program.instructions.begin()+first.end,
        [](const auto& instruction){return instruction.opcode==Opcode::tick&&instruction.operand==static_cast<unsigned>(BudgetKind::method_entry);});
    check(method_tick==1,"one decoded method entry retains its extra budget point");
    const auto object=std::find_if(first.contexts.begin(),first.contexts.end(),[](const auto& value){return value.kind==3;});
    check(object!=first.contexts.end()&&object->factory&&object->placement&&
        program.targets[*object->factory].function_id==factory&&program.targets[*object->placement].function_id==placement,
        "decoded factory/placement IDs bind to immutable target indices");
    const auto binding=std::find_if(first.operations.begin(),first.operations.end(),[](const auto& operation){return !operation.methods.empty();});
    check(binding!=first.operations.end()&&program.targets[binding->methods[0].target].function_id==method&&
        first.contexts[binding->methods[0].contexts[0]].reference==0,"decoded method hidden context remains bound");
}
}

int main() {
    try {
        all_opcodes();constants_and_locations();reference_binding();module_sources();rejected_modules();lowered_metadata();
        std::cout<<"Exec model checks passed: "<<checks<<"\n";return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
