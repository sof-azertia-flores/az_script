#include "library.h"
#include "vm/execution.h"
#include "vm/lowering.h"
#include "jit/native.h"
#include "core/value_ops.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <new>
#include <string>
#include <typeinfo>

namespace {
thread_local bool count_allocations=false;
thread_local std::size_t allocations=0;
}
void* operator new(std::size_t size) {
    if(count_allocations)++allocations;
    if(auto result=std::malloc(size?size:1))return result;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size){return ::operator new(size);}
void operator delete(void* value) noexcept {std::free(value);}
void operator delete[](void* value) noexcept {std::free(value);}
void operator delete(void* value,std::size_t) noexcept {std::free(value);}
void operator delete[](void* value,std::size_t) noexcept {std::free(value);}

namespace {
using namespace azertian;
using namespace azertian::vm;
using Node=std::shared_ptr<exec::expression>;
using Raw=std::shared_ptr<AbdValue>;
std::size_t checks=0;
void check(bool condition,const std::string& detail) {
    ++checks;if(!condition)throw std::runtime_error(detail);
}
Node op(int code,std::initializer_list<Node> children={}) {
    auto result=std::make_shared<exec::expression>();result->opcode=code;
    result->children.assign(children.begin(),children.end());return result;
}
Node constant(Value value) {auto result=op(0);result->constant=std::move(value);return result;}
template<class T> Node literal(T value){return constant(Value(value));}
Node nil(){return constant(Value{});}
Node call(int id,std::initializer_list<Node> arguments={}){auto result=op(2,arguments);result->immediate=id;return result;}
Node slot(int index){auto result=op(3);result->immediate=index;return result;}
Node define(int index,Node value){auto result=op(4,{value});result->immediate=index;return result;}
Node store(int index,Node value){auto result=op(5,{value});result->immediate=index;return result;}
Node convert_to(int type,Node value){auto result=op(49,{value});result->immediate=type;return result;}
Node returned(Node value){return op(7,{value});}
Node block(std::initializer_list<Node> children){return op(1,children);}
Node cleanup(Node body,Node finalizer,bool on_error=false) {
    auto result=op(31,{std::move(body),std::move(finalizer)});result->on_error=on_error;return result;
}
void number_nodes(const std::shared_ptr<const exec::expression>& node,std::uint32_t& count,DebugInfo& debug) {
    auto& writable=const_cast<exec::expression&>(*node);
    writable.node=count++;writable.location=count;
    debug.locations.push_back({0,100+writable.node,7});
    for(const auto& child:node->children)number_nodes(child,count,debug);
}
SourceFunction function(Node body,int result=INT_VALUE,std::uint32_t locals=0,
        std::vector<int> params={},std::uint32_t globals=1,std::uint32_t offset=0) {
    SourceFunction source;source.function_id=10;source.return_type=result;source.local_count=locals;
    source.param_types=std::move(params);source.global_count=globals;source.global_offset=offset;source.body=std::move(body);
    DebugInfo debug;debug.name="scalar_case";debug.files=std::make_shared<const std::vector<std::string>>(std::vector<std::string>{"scalar-vm.azs"});debug.header={0,1,1};
    std::uint32_t count=0;number_nodes(source.body,count,debug);source.debug=std::move(debug);return source;
}
Raw raw(std::initializer_list<Raw> values){AbdStack stack;stack.vs=values;return stack.toAbdValue();}
Raw integer(int value){return std::make_shared<IntAbdValue>(value)->toAbdValue();}
Raw boolean(bool value){return std::make_shared<BoolAbdValue>(value)->toAbdValue();}
Raw text(const std::string& value){return std::make_shared<StringAbdValue>(value)->toAbdValue();}
std::shared_ptr<AbdMapValue> abd_value(const Value& value) {
    switch(value.type_tag()) {
    case INT_VALUE:return std::make_shared<IntAbdValue>(value.get<int>());
    case FLOAT_VALUE:return std::make_shared<FloatAbdValue>(value.get<float>());
    case DOUBLE_VALUE:return std::make_shared<DoubleAbdValue>(value.get<double>());
    case STRING_VALUE:return std::make_shared<StringAbdValue>(value.get<std::string>());
    case BOOLEAN_VALUE:return std::make_shared<BoolAbdValue>(value.get<bool>());
    case ADDRESS_VALUE:return std::make_shared<AddressAbdValue>(value.get<address>());
    case VOID_VALUE:{const unsigned char marker=0xff;return std::make_shared<ByteArrayValue>(&marker,1,0xce200a);}
    default:throw std::runtime_error("test encoder does not support objects");
    }
}
Raw wire(const std::shared_ptr<const exec::expression>& node) {
    AbdStack record;record.vs.push_back(integer(node->opcode));
    auto child=[&](std::size_t index){record.vs.push_back(wire(node->children.at(index)));};
    switch(node->opcode) {
    case 0:{AbdArray values;values.push_back(abd_value(node->constant));record.vs.push_back(values.toAbdValue());break;}
    case 1:{AbdStack children;for(const auto& node:node->children)children.vs.push_back(wire(node));record.vs.push_back(children.toAbdValue());break;}
    case 2:{
        record.vs.push_back(integer(node->immediate));AbdStack arguments;
        for(const auto& argument:node->children)arguments.vs.push_back(wire(argument));
        record.vs.push_back(arguments.toAbdValue());record.vs.push_back(raw({}));break;
    }
    case 3:record.vs.push_back(integer(node->immediate));break;
    case 4:
        record.vs.push_back(integer(node->immediate));record.vs.push_back(integer(ANY_VALUE));
        record.vs.push_back(boolean(!node->children.empty()));if(!node->children.empty())child(0);break;
    case 5:record.vs.push_back(integer(node->immediate));child(0);break;
    case 7:record.vs.push_back(boolean(!node->children.empty()));if(!node->children.empty())child(0);break;
    case 27:child(0);child(1);record.vs.push_back(boolean(node->children.size()==3));if(node->children.size()==3)child(2);break;
    case 31:child(0);child(1);record.vs.push_back(boolean(node->on_error));break;
    case 49:record.vs.push_back(integer(node->immediate));child(0);break;
    default:for(std::size_t i=0;i<node->children.size();++i)child(i);break;
    }
    return record.toAbdValue();
}
Raw module_wire(const SourceFunction* source,std::uint32_t globals) {
    AbdStack functions;
    if(source) {
        AbdStack params;for(int type:source->param_types)params.vs.push_back(integer(type));
        AbdStack locations;
        const auto add_locations=[&](auto&& self,const std::shared_ptr<const exec::expression>& node)->void {
            if(node->location) {
                const auto at=source->debug->locations.at(node->location-1);
                locations.vs.push_back(integer(static_cast<int>(node->node)));locations.vs.push_back(integer(0));
                locations.vs.push_back(integer(static_cast<int>(at.line)));locations.vs.push_back(integer(static_cast<int>(at.column)));
            }
            for(const auto& child:node->children)self(self,child);
        };
        add_locations(add_locations,source->body);
        auto debug=raw({text(source->debug->name),integer(0),integer(1),integer(1),locations.toAbdValue()});
        functions.vs.push_back(raw({integer(source->function_id),integer(source->return_type),
            integer(static_cast<int>(source->param_types.size())),integer(static_cast<int>(source->local_count)),
            params.toAbdValue(),wire(source->body),integer(0),integer(0),boolean(true),debug}));
    }
    return raw({text("AZSCRIPT"),integer(EXEC_FORMAT_VERSION),integer(1),text("scalar_vm_test"),
        integer(static_cast<int>(globals)),std::make_shared<AbdMap>()->toAbdValue(),raw({}),functions.toAbdValue(),
        text(""),raw({}),raw({text("scalar-vm.azs")})});
}
Value read_variable(const std::shared_ptr<variable>& value) {
    switch(value->type_tag()) {
    case INT_VALUE:return Value(value->get<int>());
    case FLOAT_VALUE:return Value(value->get<float>());
    case DOUBLE_VALUE:return Value(value->get<double>());
    case BOOLEAN_VALUE:return Value(value->get<bool>());
    case STRING_VALUE:return Value(value->get<std::string>());
    case ADDRESS_VALUE:return Value(value->get<address>());
    case VOID_VALUE:return Value{};
    default:throw std::runtime_error("test observer received an object");
    }
}
std::shared_ptr<variable> host_value(const Value& value) {
    switch(value.type_tag()) {
    case INT_VALUE:return std::make_shared<variable>(value.get<int>());
    case FLOAT_VALUE:return std::make_shared<variable>(value.get<float>());
    case DOUBLE_VALUE:return std::make_shared<variable>(value.get<double>());
    case BOOLEAN_VALUE:return std::make_shared<variable>(value.get<bool>());
    case STRING_VALUE:return std::make_shared<variable>(value.get<std::string>());
    case ADDRESS_VALUE:return std::make_shared<variable>(value.get<address>());
    default:return std::make_shared<variable>(nullptr);
    }
}
bool equal(const Value& left,const Value& right) {
    if(left.type_tag()!=right.type_tag())return false;
    switch(left.type_tag()) {
    case FLOAT_VALUE:return std::bit_cast<std::uint32_t>(left.get<float>())==std::bit_cast<std::uint32_t>(right.get<float>());
    case DOUBLE_VALUE:return std::bit_cast<std::uint64_t>(left.get<double>())==std::bit_cast<std::uint64_t>(right.get<double>());
    default:return value_ops::to_string(left)==value_ops::to_string(right);
    }
}
struct Observation {
    Value result;
    std::vector<Value> globals;
    std::uint64_t remaining=0;
    std::string message,type;
    std::uint32_t line=0;
};
Observation old_run(const SourceFunction& source,const std::vector<Value>& globals,
        const std::vector<Value>& arguments,std::uint64_t budget) {
    auto module=module_wire(&source,source.global_count);
    auto bytes=module->toBytes();
    std::shared_ptr<script> script;
    if(source.global_offset) {
        auto prefix=module_wire(nullptr,source.global_offset);auto prefix_bytes=prefix->toBytes();
        script=load_script(prefix_bytes.get(),prefix->size+4);script->insert_script(bytes.get(),module->size+4);
    } else script=load_script(bytes.get(),module->size+4);
    script->flush();script->max_steps=budget;
    for(std::size_t i=0;i<globals.size();++i)script->baseEnv->variables.at(i)->copy_from(host_value(globals[i]));
    std::vector<std::shared_ptr<variable>> args;for(const auto& arg:arguments)args.push_back(host_value(arg));
    Observation result;
    try{result.result=read_variable(script->invoke(source.function_id,std::move(args)));}
    catch(const std::exception& error) {
        result.message=error.what();result.type=typeid(error).name();
        if(!last_error_trace().frames.empty())result.line=last_error_trace().frames.front().line;
    }
    result.remaining=script->remaining_steps;
    for(const auto& global:script->baseEnv->variables)result.globals.push_back(read_variable(global));
    script->destroy();return result;
}
Observation vm_run(ScalarExecutor& executor,SlotPool& pool,const SourceFunction& source,
        const std::vector<SlotId>& slots,const std::vector<Value>& globals,
        const std::vector<Value>& arguments,std::uint64_t budget) {
    for(std::size_t i=0;i<slots.size();++i)pool.resolve(slots[i])=globals[i];
    Observation result;ExecutionPosition position;
    try{result.result=executor.invoke(0,arguments,slots,budget,&position);}
    catch(const std::exception& error) {
        result.message=error.what();result.type=typeid(error).name();
        result.line=position.location?source.debug->locations.at(position.location-1).line:source.debug->header.line;
    }
    result.remaining=budget;for(const auto slot:slots)result.globals.push_back(pool.resolve(slot));
    check(pool.size()==slots.size(),"exception or completion leaked scalar local storage");return result;
}
void same(const Observation& old,const Observation& current,const std::string& name,std::uint64_t budget) {
    const auto prefix=name+" budget="+std::to_string(budget)+": ";
    check(old.message==current.message,prefix+"error differs: "+old.message+" / "+current.message);
    check(old.type==current.type,prefix+"exception class differs");
    check(old.line==current.line,prefix+"error source differs: "+std::to_string(old.line)+" / "+std::to_string(current.line));
    check(old.remaining==current.remaining,prefix+"remaining budget differs");
    check(equal(old.result,current.result),prefix+"return value differs");
    check(old.globals.size()==current.globals.size(),prefix+"global count differs");
    for(std::size_t i=0;i<old.globals.size();++i)check(equal(old.globals[i],current.globals[i]),prefix+"global value differs");
}
void differential(const std::string& name,SourceFunction source,std::vector<Value> globals={Value(0)},
        std::vector<Value> arguments={},std::optional<Value> expected={}) {
    const auto program=lower_program(SourceProgram{{source},{}});
    WorkspaceBudget workspace(16*1024*1024);SlotPool pool(workspace);
    std::vector<SlotId> slots;for(const auto& global:globals)slots.push_back(pool.emplace(global));
    ScalarExecutor executor(program,pool,workspace);
    // Probing tiers move the points where frames consult the JIT tier; they
    // must not change any observation.
    ScalarExecutor probe_a(program,pool,workspace,{jit::Mode::probe,1}),probe_b(program,pool,workspace,{jit::Mode::probe,2});
    // Native code from the first entry, and one instruction per entry.
    ScalarExecutor eager(program,pool,workspace,{jit::Mode::eager}),stress(program,pool,workspace,{jit::Mode::stress});
    // The same with the LLVM module's code, when this build has the module.
    std::string missing;const bool llvm=jit::native_backend(missing)!=nullptr;
    std::optional<ScalarExecutor> native,native_stress,policy_native;
    // The policy compiles after two events, so budget sweeps cross tier-up.
    ScalarExecutor policy(program,pool,workspace,jit::Config{jit::Mode::on,1,jit::Backend::interp,2});
    if(llvm) {
        policy_native.emplace(program,pool,workspace,jit::Config{jit::Mode::on,1,jit::Backend::llvm,3});
        native.emplace(program,pool,workspace,jit::Config{jit::Mode::eager,1,jit::Backend::llvm});
        native_stress.emplace(program,pool,workspace,jit::Config{jit::Mode::stress,1,jit::Backend::llvm});
    }
    constexpr std::uint64_t high=100000;
    auto old=old_run(source,globals,arguments,high);
    auto candidate=vm_run(executor,pool,source,slots,globals,arguments,high);same(old,candidate,name,high);
    if(expected)check(old.message.empty()&&equal(old.result,*expected),name+": independent expected result differs");
    const auto consumed=high-old.remaining;
    check(consumed<2000,name+": fixture unexpectedly needs a large budget sweep");
    for(std::uint64_t budget=0;budget<=consumed+1;++budget) {
        const auto reference=old_run(source,globals,arguments,budget);
        same(reference,vm_run(executor,pool,source,slots,globals,arguments,budget),name,budget);
        same(reference,vm_run(probe_a,pool,source,slots,globals,arguments,budget),name+" probe 1",budget);
        same(reference,vm_run(probe_b,pool,source,slots,globals,arguments,budget),name+" probe 2",budget);
        same(reference,vm_run(eager,pool,source,slots,globals,arguments,budget),name+" eager",budget);
        same(reference,vm_run(stress,pool,source,slots,globals,arguments,budget),name+" stress",budget);
        same(reference,vm_run(policy,pool,source,slots,globals,arguments,budget),name+" on:2",budget);
        if(llvm) {
            same(reference,vm_run(*policy_native,pool,source,slots,globals,arguments,budget),name+" on:3+llvm",budget);
            same(reference,vm_run(*native,pool,source,slots,globals,arguments,budget),name+" llvm",budget);
            same(reference,vm_run(*native_stress,pool,source,slots,globals,arguments,budget),name+" llvm stress",budget);
        }
    }
    for(auto slot:slots)pool.retire(slot);
}
Node escaped_local(int value) {
    return op(27,{literal(true),op(50,{op(19,{define(0,literal(value)),nil()}),slot(0),literal(0)})});
}
Node counting_loop(int count) {
    return block({define(0,literal(0)),op(28,{op(18,{slot(0),literal(count)}),
        block({define(1,literal(3)),store(0,op(12,{slot(0),literal(1)}))})}),returned(slot(0))});
}
void scalar_cases() {
    differential("integer",function(returned(literal(42))),{Value(0)},{},Value(42));
    differential("float-negative-zero",function(returned(literal(-0.0f)),FLOAT_VALUE),{Value(0)},{},Value(-0.0f));
    differential("double-negative-zero",function(returned(literal(-0.0)),DOUBLE_VALUE),{Value(0)},{},Value(-0.0));
    differential("address-max",function(returned(literal(address{~std::uint64_t{0}})),ADDRESS_VALUE),{Value(0)},{},Value(address{~std::uint64_t{0}}));
    differential("unicode",function(returned(literal("a\xf0\x9f\x8c\xb8")),STRING_VALUE),{Value(0)},{},Value("a\xf0\x9f\x8c\xb8"));
    differential("lhs-snapshot",function(returned(op(12,{slot(-1),store(-1,literal(12))}))),{Value(5)},{},Value(17));
    differential("mov-lhs-snapshot",function(block({define(0,literal(5)),returned(op(12,{slot(0),op(6,{slot(0),literal(9)})}))}),INT_VALUE,1),{Value(0)},{},Value(14));
    differential("set-before-rhs",function(returned(store(0,store(-1,literal(99)))),INT_VALUE,1));
    differential("duplicate-before-initializer",function(block({define(0,literal(1)),define(0,store(-1,literal(99)))}),VOID_VALUE,1));
    differential("nested-initializer-declaration",function(block({define(0,define(0,literal(5))),returned(slot(0))}),VOID_VALUE,1),{Value(0)},{},Value{});
    differential("escaped-scope-reference",function(returned(op(12,{escaped_local(5),literal(2)})),INT_VALUE,1),{Value(0)},{},Value(7));
    differential("escaped-ref-and-redeclaration",function(returned(op(12,{escaped_local(5),escaped_local(9)})),INT_VALUE,1),{Value(0)},{},Value(14));
    differential("outer-global-reference",function(returned(op(12,{op(27,{literal(true),slot(-1)}),store(-1,literal(9))}))),{Value(5)},{},Value(14));
    differential("short-and",function(returned(op(23,{literal(false),op(19,{store(-1,literal(9)),literal(9)})})),BOOLEAN_VALUE),{Value(0)},{},Value(false));
    differential("short-or",function(returned(op(24,{literal(true),op(15,{literal(1),literal(0)})})),BOOLEAN_VALUE),{Value(0)},{},Value(true));
    differential("select-lhs",function(returned(op(12,{op(50,{literal(true),slot(-1),literal(0)}),store(-1,literal(7))}))),{Value(4)},{},Value(11));
    differential("wrong-condition",function(returned(op(50,{literal(1),literal(2),literal(3)}))));
    differential("zero-loop",function(counting_loop(0),INT_VALUE,2),{Value(0)},{},Value(0));
    differential("loop-scope-retirement",function(counting_loop(4),INT_VALUE,2),{Value(0)},{},Value(4));
    differential("break-continue",function(block({define(0,literal(0)),op(28,{literal(true),block({
        store(0,op(12,{slot(0),literal(1)})),op(27,{op(18,{slot(0),literal(3)}),op(30)}),op(29)})}),returned(slot(0))}),INT_VALUE,1),{Value(0)},{},Value(3));
    differential("return-from-loop",function(block({op(28,{literal(true),block({returned(literal(4)),store(-1,literal(99))})}),returned(literal(0))}),INT_VALUE),{Value(0)},{},Value(4));
    differential("break-outside-loop",function(op(29),VOID_VALUE));
    differential("continue-outside-loop",function(op(30),VOID_VALUE));
    differential("module-offset",function(returned(store(-1,op(12,{slot(-2),literal(3)}))),INT_VALUE,0,{},2,2),{Value(50),Value(51),Value(1),Value(7)},{},Value(10));
    differential("parameter-copy",function(returned(store(0,op(12,{slot(0),literal(1)}))),INT_VALUE,0,{INT_VALUE}),{Value(0)},{Value(8)},Value(9));
    differential("parameter-mismatch",function(returned(slot(0)),INT_VALUE,0,{INT_VALUE}),{Value(0)},{Value(true)});
    differential("parameter-count",function(returned(slot(0)),INT_VALUE,0,{INT_VALUE}));
    differential("return-widen",function(returned(literal(5)),DOUBLE_VALUE),{Value(0)},{},Value(5.0));
    differential("return-mismatch-location",function(returned(literal(true)),INT_VALUE));
    differential("fallthrough-return",function(block({literal(1)}),INT_VALUE));
    differential("void-return",function(returned(literal(1)),VOID_VALUE));
    for(int opcode:{12,13,14,15,16,17,18,19,20,21,22,43,44,45,46,47,48})
        differential("binary-"+std::to_string(opcode),function(returned(op(opcode,{literal(-17),literal(2)})),opcode>=17&&opcode<=22?BOOLEAN_VALUE:INT_VALUE));
    differential("negate",function(returned(op(26,{literal(-1.25f)})),FLOAT_VALUE),{Value(0)},{},Value(1.25f));
    differential("logical-not",function(returned(op(25,{literal(false)})),BOOLEAN_VALUE),{Value(0)},{},Value(true));
    differential("int-overflow",function(returned(op(12,{literal(std::numeric_limits<int>::max()),literal(1)}))));
    differential("division-zero",function(returned(op(15,{literal(1),literal(0)}))));
    auto inherited=function(returned(op(15,{literal(1),literal(0)})));
    const_cast<exec::expression&>(*inherited.body->children[0]).location=0;
    for(const auto& child:inherited.body->children[0]->children)const_cast<exec::expression&>(*child).location=0;
    differential("inherited-error-position",std::move(inherited));
    differential("shift-range",function(returned(op(46,{literal(1),literal(32)}))));
    differential("address-underflow",function(returned(op(13,{literal(address{0}),literal(1)}))));
    differential("address-overflow",function(returned(op(12,{literal(address{~std::uint64_t{0}}),literal(1)}))));
    differential("string-concat",function(returned(op(12,{literal("n="),literal(42)})),STRING_VALUE),{Value(0)},{},Value("n=42"));
    differential("parse-int",function(returned(convert_to(INT_VALUE,literal("+42")))),{Value(0)},{},Value(42));
    differential("parse-double",function(returned(convert_to(DOUBLE_VALUE,literal("-0.0"))),DOUBLE_VALUE),{Value(0)},{},Value(-0.0));
    differential("invalid-parse",function(returned(convert_to(INT_VALUE,literal("bad\nvalue")))));
    differential("numeric-convert",function(returned(convert_to(INT_VALUE,literal(-3.9)))),{Value(0)},{},Value(-3));
}
// Compiler cleanup regions (wire op 31) with scalar bodies. The tree executor
// is the reference for flow restoration, first-error retention and positions.
void cleanup_cases() {
    const auto global_plus=[](int value){return store(-1,op(12,{slot(-1),literal(value)}));};
    const auto divide_zero=[]{return op(15,{literal(1),literal(0)});};
    differential("cleanup-normal",function(block({cleanup(store(-1,literal(1)),store(-1,op(12,{op(14,{slot(-1),literal(10)}),literal(2)}))),
        returned(slot(-1))})),{Value(0)},{},Value(12));
    differential("cleanup-on-error-skipped",function(block({cleanup(store(-1,literal(1)),store(-1,literal(99)),true),
        returned(slot(-1))})),{Value(0)},{},Value(1));
    differential("cleanup-body-error",function(block({cleanup(block({store(-1,literal(1)),divide_zero()}),global_plus(10)),
        returned(literal(5))})));
    differential("cleanup-on-error-body-error",function(block({cleanup(block({store(-1,literal(1)),divide_zero()}),global_plus(10),true),
        returned(literal(5))})));
    differential("cleanup-finalizer-error",function(block({cleanup(store(-1,literal(1)),block({store(-1,literal(2)),divide_zero()})),
        returned(literal(5))})));
    differential("cleanup-first-error-wins",function(block({cleanup(block({store(-1,literal(1)),divide_zero()}),
        block({store(-1,literal(2)),op(12,{literal(std::numeric_limits<int>::max()),literal(1)})})),returned(literal(5))})));
    differential("cleanup-return-preserved",function(block({cleanup(returned(literal(7)),block({store(-1,literal(3)),returned(literal(9))})),
        returned(literal(1))})),{Value(0)},{},Value(7));
    differential("cleanup-break-preserved",function(block({op(28,{literal(true),block({cleanup(op(29),global_plus(1)),store(-1,literal(99))})}),
        returned(slot(-1))})),{Value(0)},{},Value(1));
    differential("cleanup-continue-preserved",function(block({define(0,literal(0)),op(28,{op(18,{slot(0),literal(3)}),block({
        store(0,op(12,{slot(0),literal(1)})),cleanup(op(30),global_plus(1)),global_plus(100)})}),returned(slot(-1))}),INT_VALUE,1),
        {Value(0)},{},Value(3));
    differential("cleanup-finalizer-flow-discarded",function(block({op(28,{literal(true),block({cleanup(global_plus(1),op(29)),
        op(27,{op(18,{slot(-1),literal(3)}),op(30)}),op(29)})}),returned(slot(-1))})),{Value(0)},{},Value(3));
    differential("cleanup-nested",function(block({cleanup(cleanup(divide_zero(),global_plus(1),true),
        store(-1,op(14,{slot(-1),literal(10)}))),returned(literal(5))})));
    differential("cleanup-nested-success",function(block({cleanup(cleanup(global_plus(1),global_plus(2)),
        store(-1,op(14,{slot(-1),literal(10)}))),returned(slot(-1))})),{Value(0)},{},Value(30));
    // The failed body's declaration is released before the finalizer declares
    // the same slot; otherwise the finalizer reports a duplicate declaration.
    differential("cleanup-scope-unwind",function(block({cleanup(block({define(0,literal(5)),divide_zero()}),
        block({define(0,literal(6)),store(-1,slot(0))})),returned(literal(1))}),INT_VALUE,1));
    differential("cleanup-loop-unwind",function(block({cleanup(op(28,{literal(true),block({global_plus(1),
        op(27,{op(19,{slot(-1),literal(3)}),divide_zero()})})}),op(28,{op(18,{slot(-1),literal(5)}),global_plus(1)})),
        returned(literal(1))})));
    // A body failing inside its own loop must not leave that loop active for
    // the finalizer: its nested on-error region sees break outside any loop.
    differential("cleanup-loop-depth-restored",function(block({cleanup(op(28,{literal(true),divide_zero()}),
        cleanup(op(29),store(-1,literal(1)),true)),returned(literal(1))})));
    // Callee failures unwind explicit frames to the caller's region; the
    // trace keeps the callee as the innermost frame.
    differential("cleanup-callee-error",function(op(27,{op(19,{slot(0),literal(1)}),returned(divide_zero()),
        block({cleanup(call(10,{literal(1)}),global_plus(5)),returned(literal(2))})}),INT_VALUE,0,{INT_VALUE}),
        {Value(0)},{Value(0)});
    differential("cleanup-callee-success",function(op(27,{op(19,{slot(0),literal(1)}),returned(literal(40)),
        block({cleanup(store(-1,call(10,{literal(1)})),global_plus(2)),returned(slot(-1))})}),INT_VALUE,0,{INT_VALUE}),
        {Value(0)},{Value(0)},Value(42));
}
void call_cases() {
    differential("recursive-call",function(returned(op(50,{op(17,{slot(0),literal(0)}),
        op(12,{call(10,{op(13,{slot(0),literal(1)})}),literal(1)}),literal(0)})),INT_VALUE,0,{INT_VALUE}),
        {Value(0)},{Value(5)},Value(5));
    differential("call-arity-after-side-effect",function(block({store(-1,literal(9)),call(10)}),INT_VALUE,0,{INT_VALUE}),
        {Value(0)},{Value(0)});
    differential("call-type-after-side-effect",function(call(10,{store(-1,literal(true))}),INT_VALUE,0,{INT_VALUE}),
        {Value(0)},{Value(0)});
    // Return is flow state, not an immediate jump out of expression evaluation.
    // The callee must still run with the void result of the return expression.
    // Equality rejects int/void operands, so the global set by the returned
    // expression selects the callee branch and only void==void is compared.
    differential("return-as-call-argument",function(block({
        op(27,{op(19,{slot(-1),literal(1)}),
            block({op(27,{op(19,{slot(0),nil()}),store(-1,literal(7))}),returned(literal(1))}),
            call(10,{returned(op(12,{store(-1,literal(1)),literal(41)}))})}),
        returned(literal(99))}),INT_VALUE,0,{ANY_VALUE}),{Value(0)},{Value(0)},Value(42));
    differential("dynamic-call-after-side-effect",function(call(0x76540001,{store(-1,literal(8))})),{Value(0)});
}
void refusal_and_resources() {
    // A program its backend cannot run is rejected while preparing, before
    // the first store: every opcode is admitted, so a backend admitting fewer
    // stands in for one.
    auto source=function(block({store(-1,literal(99)),op(41,{literal(1)})}));
    const auto unsupported=lower_program(SourceProgram{{source},{}});
    WorkspaceBudget budget(1024*1024);SlotPool pool(budget);
    auto global=pool.emplace(Value(0));bool rejected=false;
    const auto all=scalar_capabilities();
    check(std::find(all.begin(),all.end(),Opcode::buffer_operation)!=all.end(),"buffer operations are not admitted");
    std::vector<Opcode> fewer;for(const auto opcode:all)if(opcode!=Opcode::buffer_operation)fewer.push_back(opcode);
    try{require_capabilities(unsupported,fewer);}
    catch(const std::invalid_argument& error){rejected=std::string(error.what())=="VM backend does not support: buffer_operation";}
    check(rejected&&pool.resolve(global).get<int>()==0,"unsupported opcode was admitted");
    // An owned return makes an object frame. It is admitted while preparing,
    // but entering it needs the script's ownership records.
    auto owned=lower_program(SourceProgram{{function(op(8,{literal(address{0})}),ADDRESS_VALUE)},{}});
    rejected=false;
    {
        ScalarExecutor executor(owned,pool,budget);std::uint64_t steps=10;
        try{executor.invoke(0,{},std::array{global},steps);}
        catch(const std::invalid_argument& error){rejected=std::string(error.what()).find("object host")!=std::string::npos;}
    }
    check(rejected&&pool.resolve(global).get<int>()==0,"object frame ran without ownership records");
    pool.retire(global);

    const auto scalar=lower_program(SourceProgram{{function(block({define(0,literal(5)),returned(slot(0))}),INT_VALUE,1)},{}});
    WorkspaceBudget tiny(2048);SlotPool tiny_pool(tiny);ScalarExecutor executor(scalar,tiny_pool,tiny);
    std::uint64_t steps=100;rejected=false;
    try{executor.invoke(0,{}, {},steps);}catch(const std::runtime_error& error){rejected=std::string(error.what())=="VM workspace limit exceeded";}
    check(rejected&&tiny_pool.size()==0,"workspace allocation failure leaked locals");
    check(steps==100,"complete frame backing must fit before executing its first instruction");
}
void entry_indices() {
    auto onload=function(block({store(-1,literal(7))}),VOID_VALUE);onload.function_id=0;onload.lifecycle=true;
    auto main=function(returned(op(12,{slot(-1),literal(2)})),INT_VALUE);
    auto program=lower_program(SourceProgram{{onload,main},{}});
    WorkspaceBudget budget(1024*1024);SlotPool pool(budget);ScalarExecutor executor(program,pool,budget);
    const std::array globals{pool.emplace(Value(0))};std::uint64_t steps=100;
    check(executor.invoke(0,{},globals,steps).type_tag()==VOID_VALUE,"onload entry returned wrong type");
    check(pool.resolve(globals[0]).get<int>()==7&&steps==97,"onload global state or budget differs");
    check(executor.invoke(1,{},globals,steps).get<int>()==9&&steps==93,"main entry did not share caller-owned session budget");
    check(pool.size()==1,"function entries leaked locals");
    pool.resolve(globals[0])=Value(std::shared_ptr<slot_block>{});
    bool rejected=false;
    try{executor.invoke(1,{},globals,steps);}catch(const std::invalid_argument& error){rejected=std::string(error.what())=="Scalar VM does not support object values";}
    check(rejected&&steps==93,"object global was not rejected before execution");
    pool.retire(globals[0]);
}
void internal_program_guards() {
    // Actual control-flow nesting can exceed nesting in linear instruction
    // layout. The verifier accepts this acyclic, balanced two-scope graph.
    ExecutableProgram reordered;
    reordered.instructions={
        {Opcode::scope_enter},{Opcode::jump,no_index,no_index,no_index,no_index,4},
        {Opcode::scope_leave},{Opcode::jump,no_index,no_index,no_index,no_index,7},
        {Opcode::scope_enter},{Opcode::scope_leave},
        {Opcode::jump,no_index,no_index,no_index,no_index,2},{Opcode::function_end}};
    reordered.sources.resize(reordered.instructions.size());
    FunctionRecord function;function.function_id=10;function.node_count=1;
    function.end=static_cast<std::uint32_t>(reordered.instructions.size());reordered.functions.push_back(function);
    WorkspaceBudget budget(1024*1024);SlotPool pool(budget);
    ScalarExecutor executor(reordered,pool,budget);std::uint64_t steps=0;
    check(executor.invoke(0,{}, {},steps).type_tag()==VOID_VALUE,"nonlinear scope layout failed");
    check(steps==0&&pool.size()==0,"nonlinear scope layout charged an implicit step or leaked storage");

    ExecutableProgram malformed;
    malformed.instructions={{Opcode::constant,0,no_index,no_index,0},
        {Opcode::store,1,0,0},{Opcode::function_end}};
    malformed.sources.resize(malformed.instructions.size());malformed.constants.emplace_back(5);
    function.register_count=2;function.end=3;malformed.functions.push_back(function);
    bool rejected=false;
    try {
        ScalarExecutor broken(malformed,pool,budget);broken.invoke(0,{}, {},steps);
    } catch(const std::logic_error& error) {
        rejected=std::string(error.what())=="Scalar VM store target is not a slot reference";
    }
    check(rejected,"internal store silently wrote a value register instead of a slot");
}
void budget_batch_equivalence() {
    for(bool failure:{false,true}) {
        Node expression=literal(1);
        for(int i=0;i<90;++i) expression=op(12,{expression,literal(i)});
        if(failure)expression=op(15,{expression,literal(0)});
        auto program=lower_program(SourceProgram{{function(returned(expression),INT_VALUE,0,{},0)},{}});
        WorkspaceBudget budget(4*1024*1024);SlotPool pool(budget);ScalarExecutor executor(program,pool,budget);
        for(std::uint64_t supplied=0;supplied<190;++supplied) {
            std::string errors[2];std::uint64_t remaining[2];int values[2]{};ExecutionPosition positions[2];
            for(int mode=0;mode<2;++mode) {
                remaining[mode]=supplied;ExecutionOptions options;options.batch_budget=mode!=0;
                try{values[mode]=executor.invoke(0,{}, {},remaining[mode],&positions[mode],options).get<int>();}
                catch(const std::runtime_error& error){errors[mode]=error.what();}
            }
            check(errors[0]==errors[1]&&remaining[0]==remaining[1]&&values[0]==values[1],"budget batch changed result or charges");
            check(positions[0].node==positions[1].node&&positions[0].location==positions[1].location,"budget batch changed failure position");
        }
    }
}
void allocation_scaling() {
    const auto source=function(counting_loop(100000),INT_VALUE,2,{},0);
    const auto program=lower_program(SourceProgram{{source},{}});
    WorkspaceBudget budget(1024*1024);SlotPool pool(budget);ScalarExecutor executor(program,pool,budget);
    std::uint64_t steps=10000000;check(executor.invoke(0,{}, {},steps).get<int>()==100000,"warm loop result");
    const auto backing=budget.used_bytes();
    allocations=0;count_allocations=true;steps=10000000;
    const auto result=executor.invoke(0,{}, {},steps);count_allocations=false;
    check(result.get<int>()==100000,"hot loop result");
    check(allocations==0,"hot integer loop performs per-operation allocation");
    check(backing==budget.used_bytes()&&pool.size()==0,"loop aliases grow persistent workspace");
    std::cout<<"scalar-loop iterations=100000 allocations="<<allocations<<" workspace="<<backing<<'\n';
}
}
int main() {
    try{scalar_cases();call_cases();cleanup_cases();refusal_and_resources();entry_indices();internal_program_guards();budget_batch_equivalence();allocation_scaling();std::cout<<"Scalar VM checks passed: "<<checks<<'\n';}
    catch(const std::exception& error){count_allocations=false;std::cerr<<error.what()<<'\n';return 1;}
}
