#include "exec.h"
#include "trace.h"
#include "value_ops.h"
#include "../library.h"
#include "../heepalloc.h"
#include "../blocks.h"
#include "../vm/session.h"
#include <bit>
#include <cstdint>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <typeinfo>
#include <vector>

namespace {
using namespace azertian;
using B=std::vector<unsigned char>;
std::size_t checks=0;
void check(bool value,const std::string& label) {++checks;if(!value)throw std::runtime_error(label);}
B integer(int value) {
    const auto bits=std::bit_cast<std::uint32_t>(value);
    return {static_cast<unsigned char>(bits),static_cast<unsigned char>(bits>>8),
        static_cast<unsigned char>(bits>>16),static_cast<unsigned char>(bits>>24)};
}
B text(const std::string& value) {return {value.begin(),value.end()};}
B stack(const std::vector<B>& fields) {
    B result;
    for(const auto& field:fields) {
        auto length=integer(static_cast<int>(field.size()));result.insert(result.end(),length.begin(),length.end());
        result.insert(result.end(),field.begin(),field.end());
    }
    return result;
}
B op(int opcode,std::vector<B> fields={}) {
    fields.insert(fields.begin(),integer(opcode));return stack(fields);
}
B literal(int value) {return op(0,{stack({integer(3),integer(value)})});}
B boolean(bool value) {return op(0,{stack({integer(0x0d00),B{static_cast<unsigned char>(value)}})});}
B pointer(std::uint64_t value) {
    B bits;for(unsigned i=0;i<8;++i)bits.push_back(static_cast<unsigned char>(value>>(i*8)));
    return op(0,{stack({integer(0xce200b),bits})});
}
B slot(int index) {return op(3,{integer(index)});}
B define(int index,int type,B value) {return op(4,{integer(index),integer(type),B{1},value});}
B set(int index,B value) {return op(5,{integer(index),value});}
B returned(B value) {return op(7,{B{1},value});}
B block(std::vector<B> values) {return op(1,{stack(values)});}
B call(int id,std::vector<B> args={}) {return op(2,{integer(id),stack(args),B{}});}
B sequence(std::vector<B> statements,B value) {return op(54,{stack(statements),value});}
B fn(int id,B body,int locals=0,std::vector<int> params={},int result=INT_VALUE,int entry=0,B debug={}) {
    std::vector<B> types;for(int type:params)types.push_back(integer(type));
    std::vector<B> fields{integer(id),integer(result),integer(static_cast<int>(params.size())),integer(locals),stack(types),body,integer(0),integer(entry),B{static_cast<unsigned char>(!debug.empty())}};
    if(!debug.empty())fields.push_back(debug);return stack(fields);
}
B module(std::vector<B> functions,B signatures={},B files={},B hint={},B assumptions={}) {
    return stack({stack({text("AZSCRIPT"),integer(10),integer(2),B{},integer(0),B{},signatures,stack(functions),hint,assumptions,files})});
}
constexpr int main_id=0x0fff0000,private_id=0x0fffffff,method_id=0x0fff0010;
std::shared_ptr<script> load(const B& bytes,bool vm=false) {
    auto result=load_script(bytes.data(),bytes.size());
    if(vm)vm::select_backend(*result,vm::default_workspace_bytes);return result;
}
struct Observation {
    std::string result,error,kind,trace;
    std::uint64_t remaining=0;
    bool operator==(const Observation&)const=default;
};
Observation observe(const B& bytes,bool vm,std::uint64_t budget=1000) {
    auto script=load(bytes,vm);script->flush();script->max_steps=budget;Observation result;
    try {auto value=script->invoke(main_id);result.result=value_ops::to_string(value_access::read(*value));}
    catch(const std::exception& error){result.error=error.what();result.kind=typeid(error).name();result.trace=format_error_trace(last_error_trace());}
    result.remaining=script->remaining_steps;script->destroy();return result;
}
void same(const B& bytes,const std::string& expected,const std::string& label,bool error=false,int budgets=48) {
    const auto tree=observe(bytes,false),vm=observe(bytes,true);
    check(tree==vm,label+" tree/VM observation");check((error?tree.error:tree.result)==expected,label+" result: expected "+expected+", got "+tree.result+" / "+tree.error);
    for(int budget=1;budget<=budgets;++budget)
        check(observe(bytes,false,budget)==observe(bytes,true,budget),label+" budget "+std::to_string(budget));
}
template<class F> void rejects(F action,const std::string& label,const std::string& fragment={}) {
    bool rejected=false;try{action();}catch(const std::exception& error){rejected=fragment.empty()||std::string(error.what()).find(fragment)!=std::string::npos;}
    check(rejected,label);
}
void arithmetic_and_sequence() {
    const int max=std::numeric_limits<int>::max(),min=std::numeric_limits<int>::min();
    same(module({fn(main_id,returned(op(55,{literal(max),literal(1)})))}),std::to_string(min),"unchecked add");
    same(module({fn(main_id,returned(op(56,{literal(min),literal(1)})))}),std::to_string(max),"unchecked subtract");
    same(module({fn(main_id,returned(op(57,{literal(max),literal(2)})))}),"-2","unchecked multiply");
    for(int count:{-1,32,63})same(module({fn(main_id,returned(op(58,{literal(1),literal(count)})))}),count==32?"1":std::to_string(min),"unchecked shift "+std::to_string(count));
    same(module({fn(main_id,returned(op(55,{boolean(false),literal(1)})))}),"Unsupported operands for add","unchecked type check",true);
    same(module({fn(main_id,returned(op(58,{literal(1),boolean(false)})))}),"shl operands must be int, got int and boolean","unchecked shift type check",true);
    same(module({fn(main_id,block({define(0,INT_VALUE,literal(max)),returned(op(59,{slot(0),integer(1),B{0}}))}),1)}),std::to_string(max),"unchecked postfix");
    same(module({fn(main_id,block({define(0,INT_VALUE,literal(max)),returned(op(59,{slot(0),integer(1),B{1}}))}),1)}),std::to_string(min),"unchecked prefix");
    auto nested=sequence({set(0,literal(4)),op(6,{slot(0),op(55,{slot(0),literal(2)})})},slot(0));
    same(module({fn(main_id,block({define(0,INT_VALUE,literal(2)),returned(op(12,{slot(0),nested}))}),1)}),"8","sequence evaluation order and operand snapshots");
    auto condition=sequence({set(0,op(55,{slot(0),literal(1)}))},op(18,{slot(0),literal(4)}));
    same(module({fn(main_id,block({define(0,INT_VALUE,literal(0)),op(28,{condition,block({})}),returned(slot(0))}),1)}),"4","sequence loop condition",false,80);
    auto fail=sequence({set(0,op(15,{literal(1),literal(0)}))},slot(0));
    same(module({fn(main_id,block({define(0,INT_VALUE,literal(9)),returned(op(50,{boolean(true),slot(0),fail}))}),1)}),"9","sequence in lazy branch");
    for(const auto& invalid:std::vector<B>{define(0,INT_VALUE,literal(1)),returned(literal(1)),op(29),op(30),block({}),set(-1,literal(0)),op(6,{call(0x0abd0006,{pointer(1)}),literal(0)})}) {
        auto bytes=module({fn(main_id,returned(sequence({invalid},literal(0))),1)});
        rejects([&]{load(bytes);},"sequence rejects non-local assignment or control statement");
    }
    auto bytes=module({fn(main_id,returned(sequence({set(0,returned(literal(1)))},literal(0))),1)});
    rejects([&]{load(bytes);},"sequence rejects nested control flow");
}
void private_functions() {
    const auto bytes=module({fn(main_id,returned(call(private_id))),fn(private_id,returned(literal(42)),0,{},INT_VALUE,2)});
    same(bytes,"42","private direct call");
    same(module({fn(main_id,returned(call(0x0abd0008,{literal(INT_VALUE),literal(private_id)}))),
        fn(private_id,returned(literal(42)),0,{},INT_VALUE,2)}),
        "Unknown function id: 0x0fffffff (private function)","reflection cannot invoke private clone",true);
    for(bool vm:{false,true}) {
        auto script=load(bytes,vm);script->flush();check(!script->hasFunction(private_id),"private absent from hasFunction");
        rejects([&]{script->getFunction(private_id);},"private unavailable through host lookup","private");
        rejects([&]{script->invoke(private_id);},"private host invocation rejected","private");
        rejects([&]{invoke_bound_function(script,private_id,script->baseEnv,{},{});},"public bound helper rejects private entries","private");
        rejects([&]{invoke_bound_function(script,private_id,site_of(script->baseEnv),{},{});},"public site helper rejects private entries","private");
        rejects([&]{script->functions.at(private_id)->invoke(script->baseEnv);},"cached private function host entry rejected","Private");
        auto cached=std::dynamic_pointer_cast<ofunction>(script->functions.at(private_id));
        rejects([&]{cached->invoke_bound(script->baseEnv,{},{});},"cached private bound environment entry rejected","Private");
        rejects([&]{cached->invoke_bound(site_of(script->baseEnv),{},{});},"cached private bound site entry rejected","Private");
        check(script->invoke(main_id)->get<int>()==42,"private direct call still works after rejected cached member calls");
        auto compiled=vm::compile_script(*script);
        check(!compiled.public_functions.contains(private_id)&&compiled.functions.contains(private_id),"VM indexes private definition internally");
        script->destroy();
        auto foreign=module({fn(0x12340001,returned(call(private_id)))});
        script=load(bytes,vm);script->insert_script(foreign.data(),foreign.size());
        rejects([&]{script->flush();},"cross-module private call rejected atomically","own module");
        check(!std::dynamic_pointer_cast<ofunction>(script->functions.at(private_id))->linked&&
            !std::dynamic_pointer_cast<ofunction>(script->functions.at(0x12340001))->linked,"failed private linkage publishes no linked function");
        script->destroy();
    }
    auto signature=stack({integer(private_id),integer(INT_VALUE),B{},integer(0),integer(0)});
    auto importing=module({fn(0x12340002,returned(literal(0)))},stack({signature}));
    auto script=load(bytes);script->insert_script(importing.data(),importing.size());
    rejects([&]{script->flush();},"extern cannot import private function","External signature");script->destroy();
    const auto private_method=module({fn(main_id,returned(literal(0))),fn(private_id,returned(literal(3)),0,{ADDRESS_VALUE},INT_VALUE,2),
        fn(0x0fff0002,block({op(52,{pointer(0),stack({stack({integer(private_id),B{}})})}),returned(literal(0))}))});
    script=load(private_method);rejects([&]{script->flush();},"private method binding rejected","own module");script->destroy();
    const auto assumption=stack({stack({text("PRIVATE_TEST"),integer(0x7777)})});
    const auto hinted=module({fn(2,returned(call(0x7777ffff))),fn(0xffff,returned(literal(42)),0,{},INT_VALUE,2)},B{},B{},text("PRIVATE_TEST"),assumption);
    for(bool vm:{false,true}) {
        script=load(hinted,vm);script->flush();
        const auto ns=script->namespace_for_hint("PRIVATE_TEST");
        check(script->invoke((ns<<16)|2)->get<int>()==42&&!script->hasFunction((ns<<16)|0xffff),"private hint relocation preserves same-module direct access");
        script->destroy();
    }
    const auto private_destructor=module({fn(main_id,returned(literal(0))),fn(private_id,op(7,{B{0}}),0,{ADDRESS_VALUE},VOID_VALUE,2)});
    script=load(private_destructor);script->flush();
    const auto heap_before=heap::lenAlloc();
    std::vector<std::shared_ptr<variable>> values{std::make_shared<variable>(nullptr),std::make_shared<variable>(0)};
    rejects([&]{heap::restore(values,{{address{1},1}},{{address{1},private_id,false,script,{},{}}},script->baseEnv,{address{1}});},
        "snapshot cannot bind private destructor","Object destructor");
    check(heap::lenAlloc()==heap_before,"private snapshot rejection preserves live heap");
    auto literal_object=blocks::create(1,script);
    rejects([&]{blocks::set_destructor(blocks::address_of(literal_object),private_id,false,site_of(script->baseEnv));},
        "literal registration cannot bind private destructor","Object destructor");
    check(!literal_object->destructor,"failed private destructor binding leaves literal unchanged");
    script->destroy();
}
void method_guards() {
    const auto method=fn(method_id,returned(literal(1)),0,{ADDRESS_VALUE});
    for(const auto& receiver:std::vector<B>{pointer(0),pointer(999999),literal(4),boolean(true)})
        same(module({fn(main_id,returned(op(60,{receiver,integer(0),integer(method_id)})),0,{},BOOLEAN_VALUE),method}),"false","invalid method guard returns false");
    const auto create=call(0x0abd0014,{literal(1)});
    for(int target:{method_id,method_id+1})for(int guard_slot:{0,1}) {
        auto body=block({define(0,ADDRESS_VALUE,create),op(10,{slot(0),B{0},B{0},B{}}),op(52,{slot(0),stack({stack({integer(method_id),B{}})})}),
            define(1,BOOLEAN_VALUE,op(60,{slot(0),integer(guard_slot),integer(target)})),returned(slot(1))});
        same(module({fn(main_id,body,2,{},BOOLEAN_VALUE),method,fn(method_id+1,returned(literal(2)),0,{ADDRESS_VALUE})}),
            target==method_id&&guard_slot==0?"true":"false","method binding guard",false,60);
    }
    const auto bytes=module({fn(main_id,returned(op(60,{pointer(0),integer(0),integer(method_id)})),0,{},BOOLEAN_VALUE),method});
    const auto guarded=exec::read_module(bytes.data(),bytes.size(),0);
    const auto references=exec::references(guarded.functions[0].body);
    check(references.size()==1&&references[0].kind==exec::reference_kind::guard_method&&references[0].function_id==method_id,"method guard participates in references");
    const auto relocated=exec::relocate(guarded.functions[0].body,[](const exec::reference& reference){return reference.function_id+1;});
    check(relocated->children[0]->function_id==method_id+1&&guarded.functions[0].body->children[0]->function_id==method_id,"guard relocation preserves original model");
}
void inline_records() {
    const auto locations=stack({integer(0),integer(0),integer(7),integer(3),integer(0),integer(2),integer(0),integer(22),integer(5),integer(1)});
    const auto item=stack({text("leaf"),integer(0),integer(20),integer(1),integer(0)});
    const auto debug=stack({text("main"),integer(0),integer(1),integer(1),locations,stack({item})});
    const auto bytes=module({fn(main_id,block({returned(op(15,{literal(1),literal(0)}))}),0,{},INT_VALUE,0,debug)},B{},stack({text("inline.azs")}));
    same(bytes,"Division by zero","inline debug error",true);
    const auto observation=observe(bytes,false);
    check(observation.trace=="  at leaf (inline.azs:22:5)\n  at main (inline.azs:7:3)","inline trace uses body position then caller site");
    auto cyclic=stack({text("leaf"),integer(0),integer(20),integer(1),integer(1)});
    auto bad_debug=stack({text("main"),integer(0),integer(1),integer(1),locations,stack({cyclic})});
    auto bad=module({fn(main_id,block({returned(literal(0))}),0,{},INT_VALUE,0,bad_debug)},B{},stack({text("inline.azs")}));
    rejects([&]{load(bad);},"cyclic inline metadata rejected","Cyclic");
    auto malformed=stack({text("leaf"),integer(0),integer(20),integer(1),integer(2)});
    bad_debug=stack({text("main"),integer(0),integer(1),integer(1),locations,stack({malformed})});
    bad=module({fn(main_id,block({returned(literal(0))}),0,{},INT_VALUE,0,bad_debug)},B{},stack({text("inline.azs")}));
    rejects([&]{load(bad);},"out-of-range inline call site rejected","outside locations");
}
}
int main() {
    try {
        arithmetic_and_sequence();private_functions();method_guards();inline_records();
        std::cout<<"Exec v11 checks passed: "<<checks<<'\n';return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
