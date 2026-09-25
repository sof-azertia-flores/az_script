#include "library.h"
#include "blocks.h"
#include "heepalloc.h"
#include "internelFunctions.h"
#include <algorithm>
#include <functional>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
using namespace azertian;
using MV=std::shared_ptr<AbdMapValue>;
using M=std::shared_ptr<AbdMap>;
using A=std::shared_ptr<AbdArray>;
using V=std::shared_ptr<variable>;
using R=std::shared_ptr<AbdValue>;
int assertions=0;
void check(bool ok,const char* what){++assertions;if(!ok)throw std::runtime_error(what);}
void rejects(std::function<void()> f,const char* what){bool caught=false;try{f();}catch(const std::exception&){caught=true;}check(caught,what);}
void rejects_message(std::function<void()> f,const std::string& expected,const char* what){
    std::string actual="<no exception>";try{f();}catch(const std::exception& e){actual=e.what();}
    if(actual!=expected)throw std::runtime_error(std::string(what)+": expected '"+expected+"', got '"+actual+"'");
    ++assertions;
}
void rejects_containing(std::function<void()> f,const std::string& expected,const char* what){
    std::string actual="<no exception>";try{f();}catch(const std::exception& e){actual=e.what();}
    if(actual.find(expected)==std::string::npos)
        throw std::runtime_error(std::string(what)+": expected error containing '"+expected+"', got '"+actual+"'");
    ++assertions;
}
MV ptr(std::uint64_t v){return std::make_shared<AddressAbdValue>(address{v});}
MV n(int v){return std::make_shared<IntAbdValue>(v);}
MV s(std::string v){return std::make_shared<StringAbdValue>(v);}
A block(std::initializer_list<MV> nodes){auto a=std::make_shared<AbdArray>();for(auto x:nodes)a->push_back(x);return a;}
A type_list(std::initializer_list<int> types){auto a=std::make_shared<AbdArray>();for(int type:types)a->push_back(n(type));return a;}
M op(std::string code){auto m=std::make_shared<AbdMap>();m->put("t",n(0));m->put("c",s(code));return m;}
M binary(std::string code,MV a,MV b){auto m=op(code);m->put("v1",a);m->put("v2",b);return m;}
M var(std::string name){auto m=op("v");m->put("v",s(name));return m;}
M var(int index){auto m=op("v");m->put("v",n(index));return m;}
M def(std::string name,MV init){auto m=op("vd");m->put("v",s(name));m->put("val",init);return m;}
M def(int index,MV init){auto m=op("vd");m->put("v",n(index));m->put("val",init);return m;}
M set(std::string name,MV init){auto m=op("vs");m->put("v",s(name));m->put("val",init);return m;}
M set(int index,MV init){auto m=op("vs");m->put("v",n(index));m->put("val",init);return m;}
M ret(MV value){auto m=op("r");m->put("r",value);return m;}
M object_return(MV value){auto m=op("ro");m->put("r",value);return m;}
M object_address(MV value,int offset=0){auto m=op("oa");m->put("v",value);m->put("offset",n(offset));return m;}
M object_bind(MV value,int destructor,bool manual){auto m=op("ob");m->put("v",value);m->put("destructor",n(destructor));m->put("manual",std::make_shared<BoolAbdValue>(manual));return m;}
M object_delete(MV value){auto m=op("od");m->put("v",value);return m;}
M loop(MV condition,MV body){auto m=op("wi");m->put("v",condition);m->put("val",body);return m;}
M branch(MV condition,MV body){auto m=op("if");m->put("v",condition);m->put("val",body);return m;}
M brk(){return op("brk");}
M new_block(int size){auto m=op("new_block");m->put("size",n(size));return m;}
M block_address(MV value){auto m=op("block_address");m->put("v",value);return m;}
M move_out(std::string name){auto m=op("mv");m->put("v",s(name));return m;}
M drop(MV target){auto m=op("drop");m->put("v",target);return m;}
M call(int id,std::initializer_list<MV> args={}){auto m=std::make_shared<AbdMap>();m->put("t",n(1));m->put("id",n(id));m->put("param",block(args));return m;}
M fn(int id,int type,MV body,int arity=0){auto m=std::make_shared<AbdMap>();m->put("id",n(id));m->put("return-type",n(type));m->put("param-count",n(arity));m->put("script",body);return m;}
M typed_fn(int id,int type,MV body,std::initializer_list<int> params){auto m=fn(id,type,body,static_cast<int>(params.size()));m->put("param-types",type_list(params));return m;}
M slot_fn(int id,int type,MV body,int locals=0,std::initializer_list<int> params={}){
    auto m=typed_fn(id,type,body,params);m->put("local-count",n(locals));return m;
}
M signature(int id,int type,std::initializer_list<int> params){auto m=std::make_shared<AbdMap>();m->put("id",n(id));m->put("return-type",n(type));m->put("param-types",type_list(params));return m;}
M module(std::initializer_list<MV> funcs,A globals=block({}),A signatures=nullptr){
    auto m=std::make_shared<AbdMap>();m->put("f",block(funcs));m->put("gvs",globals);
    if(signatures)m->put("extern-signatures",signatures);
    return m;
}
M slot_module(std::initializer_list<MV> funcs,int globals=0){
    auto m=module(funcs);m->put("exec-version",n(3));m->put("gvs",n(globals));return m;
}
R raw(std::initializer_list<R> values){AbdStack result;result.vs=values;return result.toAbdValue();}
R ri(int value){return n(value)->toAbdValue();}
R rb(bool value){return std::make_shared<BoolAbdValue>(value)->toAbdValue();}
R rs(const std::string& value){return s(value)->toAbdValue();}
R rx(int opcode,std::initializer_list<R> values={}){AbdStack result;result.vs.push_back(ri(opcode));result.vs.insert(result.vs.end(),values);return result.toAbdValue();}
R rc(MV value){return rx(0,{block({value})->toAbdValue()});}
R rblock(std::initializer_list<R> values){return rx(1,{raw(values)});}
R rfn(int id,int type,R body,int locals=0,std::initializer_list<int> params={}){
    AbdStack types;for(int type:params)types.vs.push_back(ri(type));
    return raw({ri(id),ri(type),ri(static_cast<int>(params.size())),ri(locals),types.toAbdValue(),body});
}
R rmodule(std::initializer_list<R> functions,int globals=0,R signatures=nullptr){
    return raw({rs("AZSCRIPT"),ri(7),ri(1),rs("test"),ri(globals),std::make_shared<AbdMap>()->toAbdValue(),signatures?signatures:raw({}),raw(functions),rs(""),raw({})});
}
R replace_raw(R value,std::size_t field,R replacement){AbdStack fields(value);fields.vs.at(field)=std::move(replacement);return fields.toAbdValue();}
R append_raw(R value,R extra){AbdStack fields(value);fields.vs.push_back(std::move(extra));return fields.toAbdValue();}
R rhint(std::string hint,std::initializer_list<R> functions,int globals=0,R assumptions=nullptr,R signatures=nullptr){
    AbdStack entries(assumptions?assumptions:raw({}));std::set<int> used{0,0x0abd,0x0fff};bool self=false;
    for(auto& item:entries.vs) {
        AbdStack entry(item);if(StringAbdValue(entry.vs.at(0)).data==hint)self=true;
        used.insert(IntAbdValue(entry.vs.at(1)).data);
    }
    if(signatures)for(auto& item:AbdStack(signatures).vs){AbdStack entry(item);used.insert(static_cast<unsigned>(IntAbdValue(entry.vs.at(0)).data)>>16);}
    if(!hint.empty()&&!self) {
        int alias=1;while(used.contains(alias))++alias;
        entries.vs.push_back(raw({rs(hint),ri(alias)}));
    }
    return replace_raw(replace_raw(rmodule(functions,globals,signatures),8,rs(hint)),9,entries.toAbdValue());
}
R assume(std::string hint,int ns){return raw({rs(hint),ri(ns)});}
R rsignature(int id,int type,std::initializer_list<int> params={}){AbdStack types;for(int value:params)types.vs.push_back(ri(value));return raw({ri(id),ri(type),types.toAbdValue()});}
R rcall(int id,std::initializer_list<R> args={}){return rx(2,{ri(id),raw(args)});}
R rvar(int slot){return rx(3,{ri(slot)});}
R rreturn(R value){return rx(7,{rb(true),value});}
R rset(int slot,R value){return rx(5,{ri(slot),value});}
R rdef(int slot,R value){return rx(4,{ri(slot),ri(ANY_VALUE),rb(true),value});}
std::shared_ptr<script> load_raw(R value,bool ready=true){
    auto bytes=value->toBytes();auto result=load_script(bytes.get(),value->size+4);
    if(ready)try{result->flush();}catch(...){auto failure=std::current_exception();try{result->destroy();}catch(...){}std::rethrow_exception(failure);}
    return result;
}
void insert_raw(const std::shared_ptr<script>& target,R value,bool ready=true){auto bytes=value->toBytes();target->insert_script(bytes.get(),value->size+4);if(ready)target->flush();}
// Test fixture authoring retains readable names; only this test encoder resolves
// them to slots. The interpreter is always given the public raw v7 format.
int fixture_int(MV value){auto p=std::dynamic_pointer_cast<IntAbdValue>(value);if(!p)throw std::invalid_argument("fixture requires int");return p->data;}
std::string fixture_string(MV value){auto p=std::dynamic_pointer_cast<StringAbdValue>(value);if(!p)throw std::invalid_argument("fixture requires string");return p->data;}
A fixture_array(MV value){auto p=std::dynamic_pointer_cast<AbdArray>(value);if(!p)throw std::invalid_argument("fixture requires array");return p;}
struct fixture_encoder {
    bool numbered;
    int next;
    std::map<std::string,int> globals,missing;
    std::vector<std::map<std::string,int>> scopes{{}};
    int slot(MV value,bool declaration=false) {
        if(numbered)return fixture_int(value);
        auto name=fixture_string(value);
        if(declaration) {
            auto [entry,added]=scopes.back().emplace(name,next);
            if(added)++next;
            return entry->second;
        }
        for(auto scope=scopes.rbegin();scope!=scopes.rend();++scope)
            if(auto found=scope->find(name);found!=scope->end())return found->second;
        if(auto found=globals.find(name);found!=globals.end())return found->second;
        auto [entry,added]=missing.emplace(name,next);if(added)++next;return entry->second;
    }
    R scoped(MV value){scopes.emplace_back();auto result=node(value);scopes.pop_back();return result;}
    R node(MV value,bool nested=true) {
        if(auto array=std::dynamic_pointer_cast<AbdArray>(value)) {
            if(nested)scopes.emplace_back();AbdStack statements;
            for(auto& statement:array->values)statements.vs.push_back(node(statement));
            if(nested)scopes.pop_back();return rx(1,{statements.toAbdValue()});
        }
        auto map=std::dynamic_pointer_cast<AbdMap>(value);if(!map)return rc(value);
        if(fixture_int(map->get("t"))==1) {
            AbdStack args;for(auto& arg:fixture_array(map->get("param"))->values)args.vs.push_back(node(arg));
            return rx(2,{map->get("id")->toAbdValue(),args.toAbdValue()});
        }
        const auto code=fixture_string(map->get("c"));
        if(code=="v")return rx(3,{ri(slot(map->get("v")))});
        if(code=="vd") {
            R init;if(map->get("val"))init=node(map->get("val"));
            const int index=slot(map->get("v"),true);
            return init?rx(4,{ri(index),ri(ANY_VALUE),rb(true),init}):rx(4,{ri(index),ri(ANY_VALUE),rb(false)});
        }
        if(code=="vs"){const int index=slot(map->get("v"));return rx(5,{ri(index),node(map->get("val"))});}
        if(code=="r")return map->get("r")?rx(7,{rb(true),node(map->get("r"))}):rx(7,{rb(false)});
        if(code=="ro")return rx(8,{node(map->get("r"))});
        if(code=="oa")return rx(9,{node(map->get("v")),map->get("offset")->toAbdValue()});
        if(code=="ob") {
            auto address=node(map->get("v"));const int destructor=fixture_int(map->get("destructor"));
            return destructor==-1?rx(10,{address,rb(false),map->get("manual")->toAbdValue()}):rx(10,{address,rb(true),ri(destructor),map->get("manual")->toAbdValue()});
        }
        if(code=="od")return rx(11,{node(map->get("v"))});
        if(code=="brk")return rx(29);
        if(code=="new_block")return rx(32,{map->get("size")->toAbdValue()});
        if(code=="block_address")return rx(33,{node(map->get("v"))});
        if(code=="mv")return rx(34,{ri(slot(map->get("v")))});
        if(code=="drop")return rx(35,{node(map->get("v"))});
        if(code=="not"||code=="neg")return rx(code=="not"?25:26,{node(map->get("v"))});
        if(code=="if") {
            auto condition=node(map->get("v"));auto then=scoped(map->get("val"));
            return map->get("else")?rx(27,{condition,then,rb(true),scoped(map->get("else"))}):rx(27,{condition,then,rb(false)});
        }
        if(code=="wi"){auto condition=node(map->get("v"));return rx(28,{condition,scoped(map->get("val"))});}
        static const std::map<std::string,int> codes={{"m",6},{"add",12},{"minus",13},{"multiply",14},{"divide",15},
            {"mod",16},{"gt",17},{"lt",18},{"eq",19},{"ne",20},{"ge",21},{"le",22},{"and",23},{"or",24}};
        auto found=codes.find(code);if(found==codes.end())return rx(999);
        auto a=node(map->get("v1"));auto b=node(map->get("v2"));return rx(found->second,{a,b});
    }
};
R encode_fixture(M source) {
    const bool numbered=source->get("exec-version")!=nullptr;
    if(numbered&&fixture_int(source->get("exec-version"))!=3)throw std::invalid_argument("fixture version");
    int global_count=0;std::map<std::string,int> globals;
    if(numbered)global_count=fixture_int(source->get("gvs"));
    else for(auto& value:fixture_array(source->get("gvs"))->values) {
        if(!globals.emplace(fixture_string(value),-1-global_count).second)throw std::invalid_argument("Duplicate fixture global");++global_count;
    }
    AbdStack signatures;
    if(auto value=source->get("extern-signatures"))for(auto& item:fixture_array(value)->values) {
        auto entry=std::dynamic_pointer_cast<AbdMap>(item);AbdStack types;
        for(auto& type:fixture_array(entry->get("param-types"))->values)types.vs.push_back(type->toAbdValue());
        signatures.vs.push_back(raw({entry->get("id")->toAbdValue(),entry->get("return-type")->toAbdValue(),types.toAbdValue()}));
    }
    AbdStack functions;
    for(auto& value:fixture_array(source->get("f"))->values) {
        auto entry=std::dynamic_pointer_cast<AbdMap>(value);const int params=fixture_int(entry->get("param-count"));
        fixture_encoder encoder{numbered,params,globals,{},{}};encoder.scopes.emplace_back();
        if(!numbered)for(int i=0;i<params;++i)encoder.scopes.back()["__func_param"+std::to_string(i)]=i;
        auto body=encoder.node(entry->get("script"),false);AbdStack types;
        if(auto values=entry->get("param-types"))for(auto& type:fixture_array(values)->values)types.vs.push_back(type->toAbdValue());
        else for(int i=0;i<params;++i)types.vs.push_back(ri(ANY_VALUE));
        const int locals=numbered?fixture_int(entry->get("local-count")):encoder.next-params;
        functions.vs.push_back(raw({entry->get("id")->toAbdValue(),entry->get("return-type")->toAbdValue(),ri(params),ri(locals),types.toAbdValue(),body}));
    }
    return raw({rs("AZSCRIPT"),ri(7),ri(1),rs("fixture"),ri(global_count),std::make_shared<AbdMap>()->toAbdValue(),signatures.toAbdValue(),functions.toAbdValue(),rs(""),raw({})});
}
void fixture_names(const std::shared_ptr<script>& target,M source,std::size_t offset=0) {
    if(source->get("exec-version"))return;
    for(auto& name:fixture_array(source->get("gvs"))->values)target->baseEnv->variables[offset++]->name=fixture_string(name);
}
std::shared_ptr<script> load_module(M m){auto result=load_raw(encode_fixture(m));fixture_names(result,m);return result;}
void insert_module(const std::shared_ptr<script>& target,M m){const auto offset=target->baseEnv->variables.size();insert_raw(target,encode_fixture(m));fixture_names(target,m,offset);}
std::shared_ptr<script> program(std::initializer_list<MV> funcs,A globals=block({}),A signatures=nullptr){return load_module(module(funcs,std::move(globals),std::move(signatures)));}
void insert_program(const std::shared_ptr<script>& target,std::initializer_list<MV> funcs,A globals=block({}),A signatures=nullptr){insert_module(target,module(funcs,std::move(globals),std::move(signatures)));}
address pointer(std::shared_ptr<variable> v){check(v->type==ADDRESS_VALUE,"expected address result");return *static_cast<address*>(v->value);}
int integer(std::shared_ptr<variable> v){check(v->type==INT_VALUE,"expected int result");return *static_cast<int*>(v->value);}
class test_function final:public function {
    int declared_type;
    std::function<V(const std::vector<V>&)> body;
public:
    test_function(int declared_type,std::function<V(const std::vector<V>&)> body)
        :declared_type(declared_type),body(std::move(body)){}
    int return_type() override{return declared_type;}
    V invoke(std::shared_ptr<environment>,std::vector<V> args={}) override{return body(args);}
};
class test_executor final:public executor {
    int ns;
public:
    std::map<int,std::shared_ptr<function>> functions;
    explicit test_executor(int ns):ns(ns){}
    int namespace_name() override{return ns;}
    std::shared_ptr<function> getiFunction(int id) override {
        auto found=functions.find(id);return found==functions.end()?nullptr:found->second;
    }
};
class registered_executor {
    int ns;
public:
    explicit registered_executor(std::shared_ptr<executor> value):ns(value->namespace_name()) {registerExecutor(std::move(value));}
    ~registered_executor(){unregisterExecutor(ns);}
};
int main(){try{
    check(getiFunction(0x0abd0006)->return_type()==ANY_VALUE,"mem_get reports its dynamic return type");
    {
        constexpr auto maximum=std::numeric_limits<std::uint64_t>::max();
        constexpr std::uint64_t high=0x8000000000000001ull;
        check(getiFunction(0x0abd0003)->return_type()==ADDRESS_VALUE,"alloc advertises address return type");
        auto high_value=std::make_shared<variable>(address{high});
        auto copy=high_value->deepCopy();high_value->setValue(7);
        check(pointer(copy)==address{high}&&integer(high_value)==7,"address copies retain type and all 64 bits");
        copy->copy_from(std::make_shared<variable>(address{maximum}));
        check(value_to_string(copy)=="18446744073709551615","address prints lossless unsigned decimal");
        auto addresses=program({
            fn(10,ADDRESS_VALUE,block({ret(binary("add",ptr(high),n(17)))})),
            fn(11,ADDRESS_VALUE,block({ret(binary("add",n(-1),ptr(high)))})),
            fn(12,ADDRESS_VALUE,block({ret(binary("minus",ptr(high),n(17)))})),
            fn(13,ADDRESS_VALUE,block({ret(binary("minus",ptr(0),n(std::numeric_limits<int>::min())))})),
            fn(14,BOOLEAN_VALUE,block({ret(binary("eq",ptr(maximum),ptr(maximum)))})),
            fn(15,BOOLEAN_VALUE,block({ret(binary("gt",ptr(high),ptr(17)))})),
            typed_fn(16,ADDRESS_VALUE,block({ret(var("__func_param0"))}),{ADDRESS_VALUE}),
            typed_fn(17,INT_VALUE,block({ret(var("__func_param0"))}),{INT_VALUE}),
            fn(18,INT_VALUE,block({ret(ptr(high))})),
            fn(19,ADDRESS_VALUE,block({ret(n(1))})),
            fn(20,VOID_VALUE,block({object_return(n(0))})),
            fn(21,VOID_VALUE,block({object_delete(n(0))})),
            fn(22,VOID_VALUE,block({object_address(n(1))})),
            fn(23,VOID_VALUE,block({object_delete(ptr(0))})),
            fn(24,VOID_VALUE,block({call(0x0abd0006,{n(1)})})),
            fn(25,VOID_VALUE,block({call(0x0abd0002,{n(1)})})),
            fn(26,VOID_VALUE,block({call(0x0abd0004,{n(1)})})),
            fn(27,VOID_VALUE,block({call(0x0abd0005,{n(1)})})),
            fn(28,VOID_VALUE,block({call(0x0abd0003,{ptr(1)})})),
            fn(29,STRING_VALUE,block({ret(binary("add",s("address="),ptr(maximum)))}))
        });
        check(pointer(addresses->invoke(10))==address{high+17},"address plus int preserves high bits");
        check(pointer(addresses->invoke(11))==address{high-1},"int plus address supports negative offsets");
        check(pointer(addresses->invoke(12))==address{high-17},"address minus int preserves high bits");
        check(pointer(addresses->invoke(13))==address{2147483648ull},"INT_MIN address subtraction avoids signed overflow");
        check(*static_cast<bool*>(addresses->invoke(14)->value)&&*static_cast<bool*>(addresses->invoke(15)->value),"address equality and ordering compare unsigned values");
        check(pointer(addresses->invoke(16,{copy}))==address{maximum},"typed address argument and return round-trip exactly");
        rejects([&]{addresses->invoke(16,{std::make_shared<variable>(1)});},"address parameter rejects integer");
        rejects([&]{addresses->invoke(17,{copy});},"integer parameter rejects address");
        for(int id:{18,19,20,21,22,24,25,26,27,28})rejects([&]{addresses->invoke(id);},"address and integer are distinct in returns, objects, and memory functions");
        check(value_to_string(addresses->invoke(29))=="address=18446744073709551615","string concatenation formats addresses losslessly");
        addresses->invoke(23);addresses->destroy();
        for(auto expression:{binary("add",ptr(maximum),n(1)),binary("add",ptr(0),n(-1)),
            binary("minus",ptr(0),n(1)),binary("minus",ptr(maximum),n(-1)),
            binary("minus",n(1),ptr(1)),binary("minus",ptr(1),ptr(1)),binary("add",ptr(1),ptr(1)),
            binary("multiply",ptr(1),n(2)),binary("divide",ptr(1),n(2)),binary("mod",ptr(1),n(2)),
            binary("add",ptr(1),std::make_shared<DoubleAbdValue>(1.0)),binary("eq",ptr(1),n(1)),
            binary("lt",ptr(1),n(2))}) {
            auto invalid=program({fn(10,ADDRESS_VALUE,block({ret(expression)}))});
            rejects([&]{invalid->invoke(10);},"invalid address arithmetic is rejected");invalid->destroy();
        }
        for(auto payload:{ri(1),raw({}),raw({ri(1),ri(2)})})
            rejects([&]{load_raw(rmodule({rfn(10,ADDRESS_VALUE,rreturn(rx(0,{raw({ri(0xce200b),payload})})))}));},"address constant requires exactly eight payload bytes");
        auto metadata=std::make_shared<AbdMap>();metadata->put("address",ptr(maximum));
        auto metadata_script=load_raw(replace_raw(rmodule({}),5,metadata->toAbdValue()));
        check(std::dynamic_pointer_cast<AddressAbdValue>(metadata_script->meta->get("address"))->data==address{maximum},"address survives typed extension metadata");
        metadata_script->destroy();
        auto allocated=heap::alloc(1);heap::getAt(allocated)->setValue(91);
        for(auto invalid:{address{high},address{0x100000001ull},address{maximum}}) {
            rejects([&]{heap::getAt(invalid);},"large address cannot alias an existing slot by truncation");
            rejects([&]{heap::object_address(invalid,0);},"large object address cannot alias slot base");
            check(!heap::free(invalid),"free rejects addresses beyond the current heap");
        }
        rejects([&]{heap::restore({std::make_shared<variable>(nullptr)},{{address{maximum},1}});},"snapshot allocation range cannot wrap address width");
        check(integer(heap::getAt(allocated))==91&&heap::lenAlloc()==1,"invalid address operations preserve active allocations");
        heap::free(allocated);
        constexpr int host_id=0x01310002;
        auto host=std::make_shared<test_executor>(0x0131);
        host->functions[host_id]=std::make_shared<test_function>(ADDRESS_VALUE,[](const std::vector<V>& values){return values.at(0)->deepCopy();});
        registered_executor registered(host);
        auto external=program({fn(10,ADDRESS_VALUE,block({ret(call(host_id,{ptr(high)}))}))},block({}),block({signature(host_id,ADDRESS_VALUE,{ADDRESS_VALUE})}));
        check(pointer(external->invoke(10))==address{high},"external address signatures retain high bits");
        rejects([&]{external->invoke(host_id,{std::make_shared<variable>(1)});},"external address parameter rejects int");
        host->functions[host_id]=std::make_shared<test_function>(ADDRESS_VALUE,[](const std::vector<V>&){return std::make_shared<variable>(1);});
        rejects([&]{external->invoke(10);},"external address result rejects int");external->destroy();
    }

    {
        constexpr int alias=0x12340002;
        auto root_module=rhint("",{
            rfn(0,VOID_VALUE,rset(-1,rcall(alias))),
            rfn(0x0fff0000,INT_VALUE,rreturn(rx(12,{rvar(-1),rcall(alias)})))
        },1,raw({assume("Alpha",0x1234)}),raw({rsignature(alias,INT_VALUE)}));
        auto linked=load_raw(root_module,false);
        auto entry=std::dynamic_pointer_cast<ofunction>(linked->functions.at(0x0fff0000));
        auto ret_node=std::dynamic_pointer_cast<returnExpression>(entry->code);
        auto add_node=std::dynamic_pointer_cast<addExpression>(ret_node->returnType);
        auto reference=std::dynamic_pointer_cast<functionInvocationExpression>(add_node->exp2);
        check(!linked->setup&&!linked->faulted&&linked->baseEnv->getVariable(-1)->type==VOID_VALUE,"mount does not execute onload");
        rejects([&]{linked->invoke(0x0fff0000);},"invoke requires successful flush");
        rejects([&]{entry->invoke(linked->baseEnv);},"cached script function cannot bypass setup gate");
        rejects_containing([&]{linked->flush();},"Missing namespace hint","missing library reports its hint before initialization");
        check(reference->function_id==alias&&linked->baseEnv->getVariable(-1)->type==VOID_VALUE&&!linked->faulted,
              "failed linking neither patches references nor runs initialization");
        auto alpha=rhint("Alpha",{rfn(0,VOID_VALUE,rset(-1,rc(n(7)))),rfn(2,INT_VALUE,rreturn(rvar(-1)))},1);
        insert_raw(linked,alpha,false);const int alpha_ns=linked->namespace_for_hint("Alpha");
        check(alpha_ns==1&&linked->module_manifest()[1].global_offset==1,"hint mounting picks the lowest namespace and stores global offset");
        linked->flush();
        check(linked->setup&&integer(linked->invoke(0x0fff0000))==14&&reference->function_id==(alpha_ns<<16|2),
              "flush initializes dependencies before caller and commits address patches");
        auto manifests=linked->module_manifest();auto alpha_bytes=alpha->toBytes();
        check(manifests[1].bytes==std::vector<unsigned char>(alpha_bytes.get(),alpha_bytes.get()+alpha->size+4)&&
              manifests[1].initialized&&manifests[1].hint=="Alpha"&&manifests[1].actual_namespace==alpha_ns,
              "manifest preserves original module identity and initialized state");
        rejects([&]{insert_raw(linked,alpha,false);},"duplicate hint mount rejected");
        check(linked->setup&&linked->module_manifest().size()==2,"failed mounting preserves previous setup and module list");
        rejects([&]{linked->namespace_for_hint("alpha");},"hint names are case sensitive");
        auto cached_host=linked->getFunction(0x0abd0001);
        linked->baseEnv->getVariable(-1)->setValue(100);linked->baseEnv->getVariable(-2)->setValue(70);
        insert_raw(linked,rhint("Beta",{rfn(2,STRING_VALUE,rreturn(rc(s("beta"))))}),false);
        rejects([&]{entry->invoke(linked->baseEnv);},"insert invalidates cached script execution gate");
        rejects([&]{cached_host->invoke(linked->baseEnv);},"insert invalidates cached host execution gate");
        insert_raw(linked,rhint("Gamma",{rfn(2,INT_VALUE,rreturn(rcall(alias)))},0,
            raw({assume("Alpha",0x1234)}),raw({rsignature(alias,INT_VALUE)})),false);
        insert_raw(linked,rhint("Delta",{rfn(2,STRING_VALUE,rreturn(rcall(alias)))},0,
            raw({assume("Beta",0x1234)}),raw({rsignature(alias,STRING_VALUE)})),false);
        linked->flush();linked->flush();
        check(integer(linked->invoke(0x0fff0000))==170,"already initialized modules never rerun onload after insert or repeated flush");
        check(integer(linked->invoke(linked->namespace_for_hint("Gamma")<<16|2))==70&&
              value_to_string(linked->invoke(linked->namespace_for_hint("Delta")<<16|2))=="beta",
              "same placeholder address in different modules links independently with different signatures");
        const int beta_ns=linked->namespace_for_hint("Beta");
        rejects([&]{insert_raw(linked,rmodule({rfn(beta_ns<<16|9,VOID_VALUE,rblock({}))}),false);},"fixed module cannot occupy mounted hint namespace");
        check(linked->setup&&linked->module_manifest().size()==5,"namespace collision does not invalidate setup");
        linked->destroy();

        auto bad_signature=load_raw(rhint("",{rfn(0,VOID_VALUE,rset(-1,rc(n(1)))),rfn(0x0fff0000,DOUBLE_VALUE,rreturn(rcall(alias)))},
            1,raw({assume("Alpha",0x1234)}),raw({rsignature(alias,DOUBLE_VALUE)})),false);
        insert_raw(bad_signature,alpha,false);
        rejects_containing([&]{bad_signature->flush();},"signature","imported script signatures must match exactly");
        check(!bad_signature->setup&&!bad_signature->faulted&&bad_signature->baseEnv->getVariable(-1)->type==VOID_VALUE&&
              bad_signature->baseEnv->getVariable(-2)->type==VOID_VALUE,"signature failure executes no onload and is not a runtime fault");
        bad_signature->destroy();

        for(auto malformed:{replace_raw(rmodule({}),1,ri(4)),rhint("bad-hint",{}),
            replace_raw(rhint("NeedsSelf",{}),9,raw({})),
            rhint("Good",{rfn(0x12340002,VOID_VALUE,rblock({}))}),
            rhint("",{},0,raw({assume("A",0),assume("B",1)})),
            rhint("",{},0,raw({assume("A",0x0abd)})),rhint("",{},0,raw({assume("A",0x0fff)})),
            rhint("",{},0,raw({assume("A",0x10000)})),
            rhint("",{},0,raw({assume("A",1),assume("A",1)})),
            rhint("",{},0,raw({assume("A",1),assume("B",1)})),
            rhint("",{},0,raw({assume("A",1),assume("A",2)})),
            rhint("",{rfn(0x00010002,VOID_VALUE,rblock({}))},0,raw({assume("A",1)}))})
            rejects([&]{load_raw(malformed,false);},"v7 malformed hint module rejected before mounting");
        rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,rx(10,{rc(n(0)),rb(true),ri(0x0fff0000),rb(false)}))}),false);},
                "main entry is not a valid object destructor");
    }
    {
        constexpr int host_ns=0xf240,record_id=static_cast<int>(0xf2400001u),reentry_id=static_cast<int>(0xf2400002u),recursive_id=static_cast<int>(0xf2400003u);
        std::vector<int> events;std::shared_ptr<script> initializing_script;
        auto callback=std::make_shared<test_executor>(host_ns);
        callback->functions[record_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>& args){events.push_back(*static_cast<int*>(args.at(0)->value));return std::make_shared<variable>(nullptr);});
        callback->functions[reentry_id]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&){
            check(initializing_script->active_calls>0,"host callbacks run within an active call guard");
            rejects([&]{initializing_script->flush();},"host callback cannot flush");
            rejects([&]{insert_raw(initializing_script,rmodule({}),false);},"host callback cannot insert");
            rejects([&]{initializing_script->destroy();},"host callback cannot destroy running script");
            return initializing_script->invoke(initializing_script->namespace_for_hint("Good")<<16|2);
        });
        callback->functions[recursive_id]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&){return initializing_script->invoke(recursive_id);});
        registered_executor registered(callback);
        auto log=[&](int value){return rcall(record_id,{rc(n(value))});};
        auto signatures=raw({rsignature(record_id,VOID_VALUE,{INT_VALUE}),rsignature(reentry_id,INT_VALUE)});
        auto cycle=load_raw(rhint("CycleA",{rfn(0,VOID_VALUE,log(1)),rfn(1,VOID_VALUE,log(-1)),rfn(2,VOID_VALUE,rblock({}))},
            0,raw({assume("CycleB",0x4001)}),signatures),false);
        insert_raw(cycle,rhint("Independent",{rfn(0,VOID_VALUE,log(3)),rfn(1,VOID_VALUE,log(-3))},0,nullptr,signatures),false);
        insert_raw(cycle,rhint("CycleB",{rfn(0,VOID_VALUE,log(2)),rfn(1,VOID_VALUE,log(-2)),rfn(2,VOID_VALUE,rblock({}))},
            0,raw({assume("CycleA",0x4001)}),signatures),false);
        cycle->flush();check(events==std::vector<int>({1,2,3}),"SCC members and independent ready groups use stable load order");
        cycle->destroy();check(events==std::vector<int>({1,2,3,-3,-2,-1}),"shutdown reverses successful initialization order");events.clear();
        auto pending=load_raw(rhint("NeverInitialized",{rfn(1,VOID_VALUE,log(99))},0,nullptr,signatures),false);
        pending->destroy();check(events.empty(),"closing an unflushed module skips its pre-destroy hook");

        initializing_script=load_raw(rhint("Future",{rfn(0,VOID_VALUE,log(30)),rfn(1,VOID_VALUE,log(-30))},
            0,raw({assume("Bad",0x4001)}),signatures),false);
        insert_raw(initializing_script,rhint("Bad",{rfn(0,VOID_VALUE,rblock({log(20),rdef(0,rcall(0x0abd0003,{rc(n(1))})),
            rx(10,{rvar(0),rb(false),rb(false)}),rx(15,{rc(n(1)),rc(n(0))})}),1),rfn(1,VOID_VALUE,log(-20))},
            0,raw({assume("Good",0x4001)}),signatures),false);
        insert_raw(initializing_script,rhint("Good",{rfn(0,VOID_VALUE,rblock({log(10),rcall(reentry_id),
            rdef(0,rcall(0x0abd0003,{rc(n(1))})),rx(10,{rvar(0),rb(false),rb(false)}),rcall(0x0abd0005,{rvar(0)})}),1),
            rfn(1,VOID_VALUE,log(-10)),rfn(2,INT_VALUE,rreturn(rc(n(8))))},0,nullptr,signatures),false);
        rejects_containing([&]{initializing_script->flush();},"Division by zero","onload preserves its runtime failure");
        check(initializing_script->faulted&&!initializing_script->setup&&events==std::vector<int>({10,20})&&heap::lenAlloc()==1,
              "onload failure stops later modules and cleans its local objects while preserving earlier globals");
        auto states=initializing_script->module_manifest();
        check(!states[0].initialized&&!states[1].initialized&&states[2].initialized,"only fully initialized modules are marked successful");
        rejects([&]{initializing_script->flush();},"faulted scripts cannot retry initialization");
        rejects([&]{initializing_script->invoke(states[2].actual_namespace.value()<<16|2);},"faulted scripts cannot execute");
        rejects([&]{insert_raw(initializing_script,rmodule({}),false);},"faulted scripts cannot insert modules");
        initializing_script->destroy();check(events==std::vector<int>({10,20,-10})&&heap::lenAlloc()==0,
              "faulted shutdown runs successful hooks only and frees remaining objects");

        initializing_script=load_raw(rhint("Good",{rfn(2,INT_VALUE,rreturn(rc(n(8))))},0,nullptr,
            raw({rsignature(reentry_id,INT_VALUE),rsignature(recursive_id,INT_VALUE)})));
        check(integer(initializing_script->invoke(reentry_id))==8,"direct host invocation supports guarded script reentry");
        check(integer(initializing_script->getFunction(reentry_id)->invoke(initializing_script->baseEnv))==8,"cached host handle keeps the active-call guard");
        initializing_script->max_call_depth=6;
        rejects_containing([&]{initializing_script->invoke(recursive_id);},"call depth","pure host recursion obeys call depth limit");
        check(initializing_script->active_calls==0,"failed host recursion unwinds all call guards");initializing_script->destroy();
        auto cleanup_pending=load_raw(rmodule({rfn(1,VOID_VALUE,rcall(0x01240002))}));
        insert_raw(cleanup_pending,rmodule({rfn(0x01240002,VOID_VALUE,log(777))},0,signatures),false);
        rejects_containing([&]{cleanup_pending->destroy();},"has not been linked","shutdown cannot execute a newly inserted unlinked module");
        check(cleanup_pending->closed&&std::find(events.begin(),events.end(),777)==events.end(),"unlinked shutdown call executes no user instructions");
    }
    {
        auto host=std::make_shared<test_executor>(1);registered_executor registered(host);
        auto mounted=load_raw(rmodule({},0,raw({rsignature(0x00020002,INT_VALUE)})),false);
        insert_raw(mounted,rhint("AvoidReserved",{rfn(2,VOID_VALUE,rblock({}))}),false);
        const int ns=mounted->namespace_for_hint("AvoidReserved");
        check(ns==3,"automatic namespace avoids registered hosts and declared fixed host imports");
        rejects([&]{registerExecutor(std::make_shared<test_executor>(ns));},"late host registration cannot steal a mounted namespace");
        mounted->flush();mounted->destroy();
        registerExecutor(std::make_shared<test_executor>(ns));unregisterExecutor(ns);
        check(!script_namespace_in_use(ns),"closing script releases its namespace reservation");
    }
    {
        const int maker=static_cast<int>(0xffff0002u);
        auto high=load_raw(rmodule({rfn(0,VOID_VALUE,rset(-1,rc(n(0)))),
            rfn(-1,VOID_VALUE,rset(-1,rx(12,{rvar(-1),rc(n(1))})),0,{ADDRESS_VALUE}),
            rfn(maker,VOID_VALUE,rblock({rdef(0,rcall(0x0abd0003,{rc(n(1))})),
                rx(10,{rvar(0),rb(true),ri(-1),rb(true)}),rcall(0x0abd0004,{rvar(0)}),rx(11,{rvar(0)})}),1)
        },1));
        high->invoke(maker);check(integer(high->baseEnv->getVariable(-1))==1&&heap::lenAlloc()==0,
              "optional destructor supports the full 0xffffffff function address");high->destroy();
    }
    {
        auto twenty=rc(n(20));auto zero=rc(n(0));
        auto compact=load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),rx(12,{twenty,rc(n(22))})}))}));
        check(integer(compact->invoke(10))==42,"raw v7 directly executes native arithmetic expressions");
        for(int opcode=0;opcode<36;++opcode)if(opcode!=29&&opcode!=30)
            rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,rx(opcode))}));},"v7 opcode rejects missing fields");
        auto byte=[](unsigned char value){return std::make_shared<AbdValue>(&value,1);};
        const unsigned char bad_text[]={0xed,0xa0,0x80};
        const auto invalid_utf8=std::make_shared<AbdValue>(bad_text,3);
        const unsigned char sentinel=0xff;
        for(auto expression:{
            rx(32),rx(-1),rx(29,{ri(1)}),raw({byte(3),ri(0)}),rx(3,{ri(1)}),rx(3,{ri(-2)}),rx(3,{ri(std::numeric_limits<int>::min())}),
            rx(4,{ri(-1),ri(0),rb(false)}),rx(4,{ri(0),ri(VOID_VALUE),rb(false)}),rx(4,{ri(0),ri(0),byte(2)}),
            rx(4,{ri(0),ri(0),rb(false),twenty}),rx(5,{ri(0)}),rx(6,{twenty,zero}),rx(7,{ri(0)}),rx(7,{rb(false),twenty}),
            rx(9,{zero,ri(-1)}),rx(10,{zero,ri(0),rb(false)}),rx(10,{zero,ri(-2),rb(false)}),rx(10,{zero,ri(-1),byte(2)}),
            rx(27,{zero,rx(29),byte(2)}),rx(0,{raw({})}),rx(0,{raw({ri(3),ri(1),ri(3),ri(2)})}),rx(0,{raw({ri(3),byte(1)})}),
            rx(0,{raw({ri(2),raw({})})}),rx(0,{raw({ri(0xad),raw({})})}),rx(0,{raw({ri(1),invalid_utf8})}),
            rc(std::make_shared<DoubleAbdValue>(std::numeric_limits<double>::infinity())),
            rc(std::make_shared<FloatAbdValue>(std::numeric_limits<float>::quiet_NaN())),
            rx(0,{raw({ri(0xce2009),byte(sentinel)})}),rx(0,{raw({ri(0xce200a),byte(0)})})})
            rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,expression,1)},1));},"v7 malformed expression is rejected");
        auto good_function=rfn(10,VOID_VALUE,rblock({}));auto good_module=rmodule({good_function});
        for(auto malformed:{replace_raw(good_module,1,ri(3)),replace_raw(good_module,1,ri(4)),replace_raw(good_module,1,ri(5)),replace_raw(good_module,1,byte(4)),
            replace_raw(good_module,2,byte(1)),replace_raw(good_module,3,invalid_utf8),replace_raw(good_module,4,ri(-1)),
            replace_raw(good_module,4,ri(1048577)),append_raw(good_module,ri(0)),
            rmodule({append_raw(good_function,ri(0))}),rmodule({replace_raw(good_function,0,byte(1))}),
            rmodule({replace_raw(good_function,1,ri(6))}),rmodule({replace_raw(good_function,2,ri(-1))}),
            rmodule({replace_raw(good_function,3,ri(1048577))}),rmodule({replace_raw(good_function,4,raw({ri(INT_VALUE)}))}),
            rmodule({rfn(10,VOID_VALUE,rx(4,{ri(0),ri(0),rb(false)}),0,{INT_VALUE})}),
            rmodule({good_function,good_function}),rmodule({},0,raw({raw({ri(0x01230001),ri(0),raw({}),ri(9)})}))})
            rejects([&]{load_raw(malformed);},"v7 malformed module or function record rejected");
        // The new interpreter intentionally rejects the retired Map formats.
        rejects([&]{load_raw(module({fn(10,VOID_VALUE,block({}))})->toAbdValue());},"named Map executable is unsupported");
        rejects([&]{load_raw(slot_module({slot_fn(10,VOID_VALUE,block({}))})->toAbdValue());},"v3 Map executable is unsupported");
        auto constant_null=rx(0,{raw({ri(0xce200a),byte(sentinel)})});
        auto scalar_program=load_raw(rmodule({rfn(10,VOID_VALUE,rx(7,{rb(true),constant_null})),
            rfn(11,STRING_VALUE,rx(7,{rb(true),rc(s(std::string("中文\0",7)))}))}));
        check(scalar_program->invoke(10)->type==VOID_VALUE,"v7 current void sentinel round-trips");
        check(value_to_string(scalar_program->invoke(11))==std::string("中文\0",7),"v7 UTF-8 string preserves embedded zero");
        auto metadata=std::make_shared<AbdMap>();auto child=std::make_shared<AbdMap>();
        child->put("flag",std::make_shared<BoolAbdValue>(true));metadata->put("nested",block({n(3),child}));
        auto metadata_program=load_raw(replace_raw(good_module,5,metadata->toAbdValue()));
        check(metadata_program->meta->get("nested")!=nullptr,"v7 extensions preserve generic typed nested metadata");
        for(auto extension:{raw({rs("x"),ri(1),invalid_utf8}),raw({rs("x"),ri(3),byte(1)}),
            raw({rs("x"),ri(0x0d00),byte(2)}),raw({rs("x"),ri(0xce2009),raw({})}),
            raw({rs("x"),ri(999),raw({})}),raw({rs("x"),ri(3),ri(1),rs("x"),ri(3),ri(2)})})
            rejects([&]{load_raw(replace_raw(good_module,5,extension));},"v7 extension metadata validates types and fields");
        auto chain=rc(n(0));for(int i=0;i<100;++i)chain=rx(12,{chain,rc(n(1))});
        check(integer(load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),chain}))}))->invoke(10))==100,
              "v7 keeps 100-level arithmetic chains executable");
        for(int i=0;i<35;++i)chain=rx(12,{chain,rc(n(1))});
        rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),chain}))}));},"v7 raw stack nesting is bounded");
        auto deep_metadata=raw({});for(int i=0;i<128;++i)deep_metadata=raw({rs("nested"),ri(2),deep_metadata});
        rejects([&]{load_raw(replace_raw(good_module,5,deep_metadata));},"metadata cannot bypass enclosing module depth");
        auto data=good_module->toBytes();const auto length=static_cast<std::size_t>(good_module->size)+4;
        rejects([&]{load_script(data.get(),length-1);},"v7 truncated outer frame rejected");
        std::vector<unsigned char> trailing(data.get(),data.get()+length);trailing.push_back(0);
        rejects([&]{load_script(trailing.data(),trailing.size());},"v7 trailing bytes rejected");
        rejects([&]{load_script(data.get(),64*1024*1024+1);},"v7 file size checked before reading payload");
        auto truncated=std::make_shared<AbdValue>(good_module->data,good_module->size-1);
        rejects([&]{load_raw(truncated);},"v7 truncated child frame rejected");
        const auto function_count=compact->functions.size();
        rejects([&]{insert_raw(compact,replace_raw(rmodule({},2),1,ri(99)));},"v7 invalid inserted header rejected");
        check(compact->baseEnv->variables.empty()&&compact->functions.size()==function_count&&integer(compact->invoke(10))==42,
              "invalid v7 insertion preserves the old state");
    }
    {
        auto numeric=load_module(slot_module({
            slot_fn(0,VOID_VALUE,block({set(-1,n(10)),set(-2,n(100))})),
            slot_fn(1,VOID_VALUE,block({set(-1,binary("add",var(-1),n(1)))})),
            slot_fn(100,INT_VALUE,block({ret(var(-1))})),
            slot_fn(101,INT_VALUE,block({ret(binary("add",var(0),var(-1)))}),0,{INT_VALUE}),
            slot_fn(110,INT_VALUE,block({branch(binary("eq",var(0),n(0)),ret(n(0))),def(1,var(0)),
                ret(binary("add",var(1),call(110,{binary("minus",var(0),n(1))})))}),1,{INT_VALUE}),
            slot_fn(111,INT_VALUE,block({def(0,n(0)),loop(binary("lt",var(0),n(3)),
                block({def(1,n(5)),set(0,binary("add",var(0),n(1)))})),ret(var(0))}),2),
            slot_fn(112,INT_VALUE,block({block({def(0,n(9))}),ret(var(0))}),1),
            slot_fn(113,INT_VALUE,block({ret(var(0)),def(0,n(9))}),1),
            slot_fn(114,VOID_VALUE,block({def(0,n(1)),block({def(0,n(2))})}),1),
            slot_fn(115,INT_VALUE,block({block({def(0,n(1))}),block({def(0,n(2))}),ret(n(3))}),1),
            slot_fn(116,INT_VALUE,block({loop(n(1),block({def(0,n(1)),brk()})),ret(var(0))}),1),
            slot_fn(117,INT_VALUE,block({def(0,n(8)),ret(var(0))}),1),
            slot_fn(120,INT_VALUE,block({ret(binary("add",var(-1),call(200)))}))
        },2));
        auto first=numeric->baseEnv->getVariable(-1);
        check(integer(numeric->invoke(100))==10,"numeric initial module uses offset zero");
        check(integer(numeric->invoke(101,{std::make_shared<variable>(7)}))==17,"numeric parameters occupy the first slots");
        check(integer(numeric->invoke(110,{std::make_shared<variable>(5)}))==15,"recursive numeric calls have independent local frames");
        check(integer(numeric->invoke(111))==3,"loop body locals reset on each block exit");
        rejects_containing([&]{numeric->invoke(112);},"not declared in this scope","exited block slots are unreadable");
        rejects_containing([&]{numeric->invoke(113);},"not declared in this scope","numeric use before declaration rejected");
        rejects_containing([&]{numeric->invoke(114);},"Duplicate variable slot","active outer numeric slot cannot be redeclared");
        check(integer(numeric->invoke(115))==3,"non-overlapping blocks may reuse a retired slot");
        rejects_containing([&]{numeric->invoke(116);},"not declared in this scope","break retires block slots");
        check(integer(numeric->invoke(117))==8&&integer(numeric->invoke(117))==8,"return retires call frame slots");
        insert_module(numeric,slot_module({
            slot_fn(0,VOID_VALUE,block({set(-1,n(20))})),
            slot_fn(1,VOID_VALUE,block({set(-1,binary("add",var(-1),n(10)))})),
            slot_fn(200,INT_VALUE,block({ret(var(-1))})),
            slot_fn(201,INT_VALUE,block({ret(binary("add",call(100),var(-1)))})),
            slot_fn(203,VOID_VALUE,block({set(-1,binary("add",var(-1),n(1)))}),0,{ADDRESS_VALUE}),
            slot_fn(204,ADDRESS_VALUE,block({def(0,call(0x0abd0003,{n(1)})),object_bind(var(0),203,false),object_return(var(0))}),1)
        },1));
        auto second=numeric->baseEnv->getVariable(-3);
        auto inserted=std::dynamic_pointer_cast<ofunction>(numeric->functions.at(200));
        check(inserted->global_offset==2&&inserted->global_count==1,"insert stores module offset and count on each function");
        check(integer(first)==10&&integer(second)==20,"insert onload initializes its own globals without overlap");
        check(integer(numeric->invoke(120))==30&&integer(numeric->invoke(201))==30,"cross-module calls use callee offsets in both directions");
        insert_module(numeric,slot_module({slot_fn(0,VOID_VALUE,block({set(-1,n(40))})),
            slot_fn(300,INT_VALUE,block({ret(binary("add",call(200),var(-1)))}))},1));
        check(integer(numeric->invoke(300))==60&&numeric->baseEnv->variables.size()==4,"successive inserts all use module-local minus one independently");
        rejects([&]{insert_module(numeric,slot_module({slot_fn(200,VOID_VALUE,block({}))},2));},"duplicate numeric function insert rejected");
        rejects([&]{insert_module(numeric,slot_module({slot_fn(400,INT_VALUE,block({ret(var(-2))}))},1));},"module cannot escape global bounds after relocation");
        check(numeric->baseEnv->variables.size()==4&&!numeric->functions.count(400)&&integer(numeric->invoke(300))==60,
              "failed insert does not change global layout or existing functions");
        insert_program(numeric,{fn(500,INT_VALUE,block({ret(var("legacy"))})),fn(0,VOID_VALUE,block({set("legacy",n(70))}))},block({s("legacy")}));
        check(integer(numeric->invoke(500))==70&&integer(numeric->invoke(100))==10,"fixture name resolution emits numeric globals");
        insert_module(numeric,slot_module({slot_fn(0,VOID_VALUE,block({set(-1,n(80))})),slot_fn(600,INT_VALUE,block({ret(var(-1))}))},1));
        check(integer(numeric->invoke(600))==80,"insert offset includes all preceding slots");
        rejects([&]{numeric->baseEnv->getVariable(std::numeric_limits<int>::min());},"minimum int global id does not overflow");
        numeric->invoke(204);
        numeric->destroy();
        check(integer(first)==11&&integer(second)==31&&heap::lenAlloc()==0,
              "inserted destroy hooks and object destructors use their defining module offsets");
        for(auto malformed:{slot_module({},-1),slot_module({},1048577),
            slot_module({slot_fn(10,VOID_VALUE,block({}),-1)}),
            slot_module({slot_fn(10,VOID_VALUE,block({}),1048576,{INT_VALUE})}),
            slot_module({slot_fn(10,VOID_VALUE,block({def(-1,n(0))}))},1),
            slot_module({slot_fn(10,VOID_VALUE,block({def(0,n(0))}),0,{INT_VALUE})}),
            slot_module({slot_fn(10,INT_VALUE,block({ret(var(0))}))}),
            slot_module({slot_fn(10,INT_VALUE,block({ret(var(std::numeric_limits<int>::min()))}))},1),
            slot_module({slot_fn(10,INT_VALUE,block({ret(var("legacy"))}))}),
            module({fn(10,INT_VALUE,block({ret(var(0))}))}),
            slot_module({fn(10,VOID_VALUE,block({}))})})
            rejects([&]{load_module(malformed);},"malformed variable format or bounds rejected before execution");
        auto unknown=slot_module({});unknown->put("exec-version",n(4));
        rejects([&]{load_module(unknown);},"unknown exec version rejected");
        auto mixed=slot_module({});mixed->put("gvs",block({}));
        rejects([&]{load_module(mixed);},"numeric module rejects legacy global array");
    }
    {
        auto field=[](MV pointer){return call(0x0abd0006,{object_address(pointer)});};
        auto append=[&]{return set("log",binary("add",binary("multiply",var("log"),n(10)),field(var("__func_param0"))));};
        auto factory=[&](int id,int destructor,bool manual) {
            auto body=block({def("p",call(0x0abd0003,{n(1)})),binary("m",field(var("p")),var("__func_param0")),object_bind(var("p"),destructor,manual)});
            if(manual)body->push_back(call(0x0abd0004,{var("p")}));
            body->push_back(object_return(var("p")));
            return typed_fn(id,ADDRESS_VALUE,body,{id==29?ADDRESS_VALUE:INT_VALUE});
        };
        auto objects=program({
            fn(10,VOID_VALUE,block({set("log",n(0)),block({def("a",call(20,{n(1)})),def("b",call(20,{n(2)}))})})),
            fn(11,VOID_VALUE,block({set("log",n(0)),def("a",call(20,{n(1)})),def("b",call(22,{n(2)}))})),
            fn(12,VOID_VALUE,block({set("log",n(0)),def("a",call(20,{n(1)})),def("b",call(22,{n(2)})),var("missing")})),
            fn(13,ADDRESS_VALUE,block({set("log",n(0)),block({block({def("a",call(20,{n(3)})),object_return(var("a"))})})})),
            fn(14,ADDRESS_VALUE,block({object_return(call(21,{n(4)}))})),
            typed_fn(15,VOID_VALUE,block({object_delete(var("__func_param0"))}),{ADDRESS_VALUE}),
            typed_fn(16,ADDRESS_VALUE,block({object_return(var("__func_param0"))}),{ADDRESS_VALUE}),
            fn(17,VOID_VALUE,block({set("log",n(0)),def("a",call(20,{n(5)})),block({call(16,{var("a")})}),set("observed",field(var("a")))})),
            typed_fn(18,BOOLEAN_VALUE,block({ret(call(0x0abd0002,{var("__func_param0")}))}),{ADDRESS_VALUE}),
            factory(20,30,false),factory(21,30,true),factory(22,31,false),factory(23,33,true),factory(28,34,true),factory(29,35,true),factory(36,37,true),
            fn(24,ADDRESS_VALUE,block({def("p",call(0x0abd0003,{n(1)})),binary("m",field(var("p")),n(9)),binary("divide",n(1),n(0)),object_bind(var("p"),30,false),object_return(var("p"))})),
            fn(25,VOID_VALUE,block({set("log",n(0)),call(24)})),
            fn(26,VOID_VALUE,block({set("log",n(0)),loop(n(1),block({def("a",call(20,{n(6)})),brk()}))})),
            fn(27,VOID_VALUE,block({set("log",n(0)),def("a",call(20,{n(7)})),loop(n(1),block({}))})),
            typed_fn(30,VOID_VALUE,block({append()}),{ADDRESS_VALUE}),
            typed_fn(31,VOID_VALUE,block({append(),binary("divide",n(1),n(0))}),{ADDRESS_VALUE}),
            typed_fn(33,VOID_VALUE,block({object_delete(var("__func_param0"))}),{ADDRESS_VALUE}),
            typed_fn(34,VOID_VALUE,block({call(0x0abd0002,{var("__func_param0")})}),{ADDRESS_VALUE}),
            typed_fn(35,VOID_VALUE,block({object_delete(field(var("__func_param0")))}),{ADDRESS_VALUE}),
            typed_fn(37,VOID_VALUE,block({set("observed",field(call(41,{var("__func_param0")})))}),{ADDRESS_VALUE}),
            fn(40,ADDRESS_VALUE,block({def("p",call(0x0abd0003,{n(1)})),binary("m",field(call(41,{var("p")})),n(9)),object_bind(var("p"),-1,false),object_return(var("p"))})),
            typed_fn(41,ADDRESS_VALUE,block({object_return(var("__func_param0"))}),{ADDRESS_VALUE}),
            fn(42,ADDRESS_VALUE,block({def("p",call(0x0abd0003,{n(1)})),object_return(var("p"))}))
        },block({s("log"),s("observed")}));
        auto log=objects->baseEnv->getVariable("log");
        objects->invoke(10);check(integer(log)==21&&heap::lenAlloc()==0,"automatic objects destruct in reverse construction order");
        address constructing=pointer(objects->invoke(40));
        check(integer(heap::getAt(constructing))==9,"construction may call a member returning borrowed this");
        heap::free(constructing);
        rejects_containing([&]{objects->invoke(42);},"before construction is complete","unconstructed object cannot escape its owning function");
        check(heap::lenAlloc()==0,"failed unconstructed return cleans its allocation");
        rejects_containing([&]{objects->invoke(11);},"Division by zero","first destructor error propagates");
        check(integer(log)==21&&heap::lenAlloc()==0,"destructor error still cleans all objects");
        rejects_containing([&]{objects->invoke(12);},"not declared in this scope","original body error survives destructor errors");
        check(integer(log)==21&&heap::lenAlloc()==0,"exception unwinding runs every destructor");
        objects->invoke(17);check(integer(log)==5&&integer(objects->baseEnv->getVariable("observed"))==5,"borrowed object return preserves the outer ownership scope");
        objects->invoke(26);check(integer(log)==6&&heap::lenAlloc()==0,"break runs block destructors");
        rejects([&]{objects->invoke(25);},"constructor failure propagates");
        check(integer(log)==0&&heap::lenAlloc()==0,"failed construction releases slots without running destructor");
        address automatic=pointer(objects->invoke(13));
        check(integer(heap::getAt(automatic))==3&&integer(log)==0,"nested object return survives all callee blocks");
        rejects_containing([&]{objects->invoke(15,{std::make_shared<variable>(automatic)});},"automatic object","delete rejects automatic objects");
        check(heap::lenAlloc()==1&&integer(log)==0,"rejected automatic delete preserves object");
        address manual=pointer(objects->invoke(14));
        check(heap::owner_of(manual)==nullptr&&heap::lenAlloc()==2,"new remains manual after object returns");
        objects->invoke(15,{std::make_shared<variable>(manual)});
        check(integer(log)==4&&heap::lenAlloc()==1,"delete runs manual destructor and releases slots");
        objects->invoke(15,{std::make_shared<variable>(address{})});
        rejects([&]{objects->invoke(15,{std::make_shared<variable>(manual)});},"second delete rejects freed object");
        manual=pointer(objects->invoke(14));
        objects->invoke(18,{std::make_shared<variable>(manual)});
        check(integer(log)==4&&heap::object_records().size()==1,"raw free bypasses destructor and removes object registration");
        address reentrant=pointer(objects->invoke(23,{std::make_shared<variable>(8)}));
        rejects_containing([&]{objects->invoke(15,{std::make_shared<variable>(reentrant)});},"being destroyed","recursive self-delete rejected");
        check(heap::lenAlloc()==1,"failed recursive destructor still frees object once");
        reentrant=pointer(objects->invoke(28,{std::make_shared<variable>(8)}));
        rejects_containing([&]{objects->invoke(15,{std::make_shared<variable>(reentrant)});},"being destroyed","raw free cannot invalidate a running destructor");
        check(heap::lenAlloc()==1,"raw-free reentry still releases the object once");
        manual=pointer(objects->invoke(14));
        address parent=pointer(objects->invoke(29,{std::make_shared<variable>(manual)}));
        objects->invoke(15,{std::make_shared<variable>(parent)});
        check(integer(log)==44&&heap::lenAlloc()==1,"destructor can explicitly delete another manual object");
        manual=pointer(objects->invoke(36,{std::make_shared<variable>(8)}));
        objects->invoke(15,{std::make_shared<variable>(manual)});
        check(integer(objects->baseEnv->getVariable("observed"))==8&&heap::lenAlloc()==1,
              "destructor may call a member returning its borrowed this pointer");
        rejects([&]{heap::object_address(automatic,1);},"member address rejects an adjacent allocation offset");
        rejects([&]{heap::object_address(address{automatic.value+1},0);},"member address requires allocation start");
        objects->destroy();check(integer(log)==443&&heap::lenAlloc()==0,"base owned returned objects destruct before script unload");
        objects->destroy();

        auto limited=program({fn(10,VOID_VALUE,block({def("p",call(0x0abd0003,{n(1)})),object_bind(var("p"),11,false),loop(n(1),block({}))})),
            typed_fn(11,VOID_VALUE,block({loop(n(1),block({}))}),{ADDRESS_VALUE})});
        limited->max_steps=40;
        rejects_containing([&]{limited->invoke(10);},"step limit","cleanup never resets an exhausted execution budget");
        check(heap::lenAlloc()==0,"budget failure still releases object storage");limited->destroy();

        auto shutdown=program({
            fn(10,ADDRESS_VALUE,block({set("log",n(0)),def("p",call(0x0abd0003,{n(1)})),object_bind(var("p"),30,false),object_return(var("p"))})),
            typed_fn(30,VOID_VALUE,block({set("log",binary("add",var("log"),n(1)))}),{ADDRESS_VALUE}),
            fn(1,VOID_VALUE,block({binary("divide",n(1),n(0))}))
        },block({s("log")}));
        shutdown->invoke(10);auto shutdown_log=shutdown->baseEnv->getVariable("log");
        rejects_containing([&]{shutdown->destroy();},"Division by zero","pre-destroy hook error propagates after cleanup");
        check(integer(shutdown_log)==1&&heap::lenAlloc()==0&&shutdown->closed,"pre-destroy error cannot skip object destructors");
        shutdown->destroy();

        auto shutdown_budget=program({fn(10,ADDRESS_VALUE,block({def("p",call(0x0abd0003,{n(1)})),object_bind(var("p"),11,false),object_return(var("p"))})),
            typed_fn(11,VOID_VALUE,block({loop(n(1),block({}))}),{ADDRESS_VALUE})});
        shutdown_budget->invoke(10);shutdown_budget->max_steps=40;
        rejects_containing([&]{shutdown_budget->destroy();},"step limit","base destructor respects shutdown execution budget");
        check(heap::lenAlloc()==0&&shutdown_budget->closed,"shutdown budget exhaustion releases all storage");

        auto undeleted=program({
            fn(10,VOID_VALUE,block({def("p",call(0x0abd0003,{n(1)})),object_bind(var("p"),30,true),call(0x0abd0004,{var("p")})})),
            typed_fn(30,VOID_VALUE,block({set("log",n(7))}),{ADDRESS_VALUE})
        },block({s("log")}));
        auto undeleted_log=undeleted->baseEnv->getVariable("log");undeleted_log->setValue(0);
        undeleted->invoke(10);
        check(heap::lenAlloc()==1&&heap::object_records().size()==1,"an undeleted manual object outlives its call");
        undeleted->destroy();
        check(heap::lenAlloc()==0&&heap::object_records().empty()&&integer(undeleted_log)==0,
              "destroy releases undeleted manual objects without running their destructors");

        auto host_block=heap::alloc(1);heap::getAt(host_block)->setValue(99);
        rejects_containing([&]{program({
            fn(0,VOID_VALUE,block({def("p",call(0x0abd0003,{n(1)})),object_bind(var("p"),-1,true),call(0x0abd0004,{var("p")}),binary("divide",n(1),n(0))}))
        });},"Division by zero","onload failure propagates");
        check(heap::object_records().empty()&&heap::lenAlloc()==1&&integer(heap::getAt(host_block))==99,
              "failed initial load discards its manual objects without touching host memory");
        heap::free(host_block);
    }
    auto p=program({fn(10,INT_VALUE,block({ret(binary("add",n(3),binary("multiply",n(4),n(5))))}))});
    check(integer(p->invoke(10))==23,"arithmetic");
    rejects([&]{p->invoke(11);},"unknown function throws");check(!p->functions.count(11),"lookup must not insert");
    rejects([&]{p->invoke(10,{std::make_shared<variable>(1)});},"argument count");
    auto returned=program({fn(10,INT_VALUE,block({loop(n(1),block({ret(n(7))})),ret(n(99))}))});
    check(integer(returned->invoke(10))==7,"return escapes while");
    auto scope=program({fn(10,INT_VALUE,block({def("x",n(2)),block({def("x",n(9))}),ret(var("x"))}))});
    check(integer(scope->invoke(10))==2,"block shadows without replacing outer variable");
    auto repeat=program({fn(10,INT_VALUE,block({def("i",n(0)),loop(binary("lt",var("i"),n(5)),block({def("local",n(1)),set("i",binary("add",var("i"),var("local")))})),ret(var("i"))}))});
    check(integer(repeat->invoke(10))==5,"fresh loop block scope");
    auto interrupted=program({fn(10,INT_VALUE,block({def("i",n(0)),def("total",n(0)),loop(binary("lt",var("i"),n(10)),block({set("i",binary("add",var("i"),n(1))),branch(binary("eq",var("i"),n(3)),block({brk()})),set("total",binary("add",var("total"),n(1)))})),ret(binary("add",binary("multiply",var("i"),n(10)),var("total")))}))});
    check(integer(interrupted->invoke(10))==32,"break exits the current while and skips the rest of its body");
    auto invalidBreak=program({fn(10,VOID_VALUE,block({brk()}))});
    rejects([&]{invalidBreak->invoke(10);},"break outside loop");
    auto single=program({fn(10,INT_VALUE,block({def("i",n(0)),loop(binary("lt",set("i",binary("add",var("i"),n(1))),n(4)),def("iteration",n(1))),ret(var("i"))}))});
    check(integer(single->invoke(10))==4,"single statement loop has fresh lexical scope");
    auto lexical=program({fn(10,INT_VALUE,block({def("secret",n(5)),ret(call(11))})),fn(11,INT_VALUE,block({ret(var("secret"))}))});
    rejects([&]{lexical->invoke(10);},"callee must not see caller locals");
    auto arguments=program({fn(10,INT_VALUE,block({def("x",n(2)),ret(call(11,{var("x"),set("x",n(8))}))})),fn(11,INT_VALUE,block({ret(var("__func_param0"))}),2)});
    check(integer(arguments->invoke(10))==2,"arguments snapshot before later side effects");
    auto numeric=program({fn(10,DOUBLE_VALUE,block({ret(binary("minus",n(5),std::make_shared<DoubleAbdValue>(0.5)))})),fn(11,FLOAT_VALUE,block({ret(binary("multiply",n(2),std::make_shared<FloatAbdValue>(0.25f)))}))});
    check(*static_cast<double*>(numeric->invoke(10)->value)==4.5,"int minus double");
    check(*static_cast<float*>(numeric->invoke(11)->value)==0.5f,"int times float");
    auto typed=program({
        typed_fn(20,INT_VALUE,block({ret(var("__func_param0"))}),{INT_VALUE}),
        typed_fn(21,INT_VALUE,block({ret(n(7))}),{ANY_VALUE})
    });
    check(integer(typed->invoke(20,{std::make_shared<variable>(9)}))==9,"typed script parameter accepts exact dynamic type");
    rejects_containing([&]{typed->invoke(20,{std::make_shared<variable>(std::string("9"))});},"argument 1 type mismatch","typed script parameter rejects wrong dynamic type");
    check(integer(typed->invoke(21,{std::make_shared<variable>(std::string("anything"))}))==7,"script any parameter accepts arbitrary dynamic type");
    rejects([]{program({typed_fn(22,VOID_VALUE,block({}),{VOID_VALUE})});},"script void parameter metadata rejected");
    rejects([]{auto malformed=fn(23,VOID_VALUE,block({}),2);malformed->put("param-types",type_list({INT_VALUE}));program({malformed});},"script parameter count metadata disagreement rejected");

    constexpr int host_namespace=0xf234;
    constexpr int host_add=static_cast<int>((static_cast<unsigned int>(host_namespace)<<16)|1u);
    constexpr int host_wrong_return=static_cast<int>((static_cast<unsigned int>(host_namespace)<<16)|2u);
    constexpr int host_void=static_cast<int>((static_cast<unsigned int>(host_namespace)<<16)|3u);
    constexpr int host_wrong_void=static_cast<int>((static_cast<unsigned int>(host_namespace)<<16)|4u);
    int add_calls=0,wrong_return_calls=0,void_calls=0,wrong_void_calls=0;
    auto host=std::make_shared<test_executor>(host_namespace);
    // Deliberately report the wrong static return_type(): runtime signatures
    // must validate the callback's actual dynamic value instead.
    host->functions[host_add]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>& args){
        ++add_calls;return std::make_shared<variable>(*static_cast<int*>(args[0]->value)+*static_cast<int*>(args[1]->value));
    });
    host->functions[host_wrong_return]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&){
        ++wrong_return_calls;return std::make_shared<variable>(1.0f);
    });
    host->functions[host_void]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&){
        ++void_calls;return std::make_shared<variable>(nullptr);
    });
    host->functions[host_wrong_void]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&){
        ++wrong_void_calls;return std::make_shared<variable>(1);
    });
    registered_executor registered(host);
    auto external=program({
        fn(30,INT_VALUE,block({ret(call(host_add,{n(2),n(3)}))})),
        fn(31,INT_VALUE,block({ret(call(host_add,{n(2)}))})),
        fn(32,INT_VALUE,block({ret(call(host_add,{s("wrong"),n(3)}))})),
        fn(33,INT_VALUE,block({ret(call(host_wrong_return))})),
        fn(34,VOID_VALUE,block({call(host_void)})),
        fn(35,VOID_VALUE,block({call(host_wrong_void)}))
    },block({}),block({
        signature(host_add,INT_VALUE,{INT_VALUE,INT_VALUE}),
        signature(host_wrong_return,INT_VALUE,{}),
        signature(host_void,VOID_VALUE,{}),
        signature(host_wrong_void,VOID_VALUE,{})
    }));
    check(integer(external->invoke(host_add,{std::make_shared<variable>(4),std::make_shared<variable>(6)}))==10,"direct external call accepts matching signature");
    check(integer(external->invoke(30))==5,"script internal external call accepts matching signature");
    check(integer(external->getFunction(host_add)->invoke(external->baseEnv,{std::make_shared<variable>(1),std::make_shared<variable>(2)}))==3,"getFunction also returns a signature-checked callback");
    check(add_calls==3,"matching external callbacks invoked");
    rejects_containing([&]{external->invoke(host_add,{std::make_shared<variable>(1)});},"argument count mismatch","direct external arity checked before callback");
    rejects_containing([&]{external->invoke(host_add,{std::make_shared<variable>(std::string("1")),std::make_shared<variable>(2)});},"argument 1 type mismatch","direct external argument type checked before callback");
    rejects_containing([&]{external->invoke(31);},"argument count mismatch","internal external arity checked before callback");
    rejects_containing([&]{external->invoke(32);},"argument 1 type mismatch","internal external argument type checked before callback");
    check(add_calls==3,"invalid arguments never reach external callback");
    rejects_containing([&]{external->invoke(host_wrong_return);},"return type mismatch: expected int, got float","direct external return type checked");
    rejects_containing([&]{external->invoke(33);},"return type mismatch: expected int, got float","internal external return type checked");
    check(wrong_return_calls==2,"return mismatch is checked after callback");
    check(external->invoke(host_void)->type==VOID_VALUE,"direct external void return accepts actual void");
    check(external->invoke(34)->type==VOID_VALUE,"internal external void return accepts actual void");
    rejects_containing([&]{external->invoke(host_wrong_void);},"return type mismatch: expected void, got int","direct external void return must match");
    rejects_containing([&]{external->invoke(35);},"return type mismatch: expected void, got int","internal external void return must match");
    check(void_calls==2&&wrong_void_calls==2,"void callbacks exercised on direct and internal paths");

    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{VOID_VALUE})}));},"external void parameter metadata rejected");
    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{ANY_VALUE})}));},"external any parameter metadata rejected");
    rejects([&]{program({},block({}),block({signature(host_add,ANY_VALUE,{})}));},"external any return metadata rejected");
    rejects([&]{auto malformed=std::make_shared<AbdMap>();malformed->put("id",n(host_add));malformed->put("return-type",n(INT_VALUE));program({},block({}),block({malformed}));},"missing external param-types rejected");
    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{}),signature(host_add,INT_VALUE,{})}));},"duplicate external signature rejected");
    check(program({fn(40,VOID_VALUE,block({}))},block({}),block({signature(40,VOID_VALUE,{})}))->invoke(40)->type==VOID_VALUE,"matching declaration and script definition link successfully");
    rejects([&]{program({fn(0x0abd0000,VOID_VALUE,block({}))});},"script cannot shadow a runtime builtin id");
    check(program({fn(0x10000000,VOID_VALUE,block({}))})->invoke(0x10000000)->type==VOID_VALUE,"script ids use all 32 bits");
    check(program({fn(-1,VOID_VALUE,block({}))})->invoke(-1)->type==VOID_VALUE,"signed negative function ids retain their unsigned address bits");
    rejects_containing([&]{program({fn(0,INT_VALUE,block({ret(n(1))}))});},"Lifecycle function","onload return type metadata rejected");
    rejects_containing([&]{program({typed_fn(0,VOID_VALUE,block({}),{INT_VALUE})});},"Lifecycle function","onload parameter metadata rejected");
    rejects_containing([&]{program({fn(1,STRING_VALUE,block({ret(s("bad"))}))});},"Lifecycle function","predestroy return type metadata rejected");
    rejects_containing([&]{program({typed_fn(1,VOID_VALUE,block({}),{STRING_VALUE})});},"Lifecycle function","predestroy parameter metadata rejected");
    insert_program(external,{fn(36,INT_VALUE,block({ret(call(host_add,{n(8),n(9)}))}))},block({}),block({signature(host_add,INT_VALUE,{INT_VALUE,INT_VALUE})}));
    check(integer(external->invoke(36))==17,"insert_script merges matching external signature");
    rejects([&]{insert_program(external,{fn(37,VOID_VALUE,block({}))},block({}),block({signature(host_add,DOUBLE_VALUE,{INT_VALUE,INT_VALUE})}));},"insert_script rejects conflicting external signature");
    check(external->functions.count(37)&&!external->setup,"failed linking retains the mounted module without permitting execution");
    rejects([&]{insert_program(external,{},block({}),block({signature(host_add,INT_VALUE,{VOID_VALUE})}));},"insert_script rejects malformed external parameter type");

    auto zeroDivision=program({
        fn(40,INT_VALUE,block({ret(binary("divide",n(1),n(0)))})),
        fn(41,FLOAT_VALUE,block({ret(binary("divide",std::make_shared<FloatAbdValue>(1.0f),std::make_shared<FloatAbdValue>(0.0f)))})),
        fn(42,FLOAT_VALUE,block({ret(binary("divide",std::make_shared<FloatAbdValue>(1.0f),std::make_shared<FloatAbdValue>(-0.0f)))})),
        fn(43,DOUBLE_VALUE,block({ret(binary("divide",std::make_shared<DoubleAbdValue>(1.0),std::make_shared<DoubleAbdValue>(0.0)))})),
        fn(44,DOUBLE_VALUE,block({ret(binary("divide",std::make_shared<DoubleAbdValue>(1.0),std::make_shared<DoubleAbdValue>(-0.0)))}))
    });
    rejects_message([&]{zeroDivision->invoke(40);},"Division by zero","int zero division error");
    rejects_message([&]{zeroDivision->invoke(41);},"Division by zero","float positive zero division error");
    rejects_message([&]{zeroDivision->invoke(42);},"Division by zero","float negative zero division error");
    rejects_message([&]{zeroDivision->invoke(43);},"Division by zero","double positive zero division error");
    rejects_message([&]{zeroDivision->invoke(44);},"Division by zero","double negative zero division error");
    auto bad=program({fn(10,INT_VALUE,block({ret(binary("divide",n(1),n(0)))})),fn(11,INT_VALUE,block({ret(binary("add",n(std::numeric_limits<int>::max()),n(1)))})),fn(12,INT_VALUE,block({ret(s("wrong"))})),fn(13,VOID_VALUE,block({loop(n(1),block({}))})),fn(14,INT_VALUE,block({ret(call(14))}))});
    rejects([&]{bad->invoke(10);},"division zero");rejects([&]{bad->invoke(11);},"integer overflow");rejects([&]{bad->invoke(12);},"return type mismatch");
    bad->max_steps=100;rejects([&]{bad->invoke(13);},"execution budget");
    bad->max_call_depth=8;rejects([&]{bad->invoke(14);},"recursion budget");check(bad->active_calls==0,"call depth unwound after failure");
    auto lazy=program({fn(10,BOOLEAN_VALUE,block({ret(binary("and",n(0),binary("divide",n(1),n(0))))})),fn(11,BOOLEAN_VALUE,block({ret(binary("or",n(1),binary("divide",n(1),n(0))))}))});
    check(!*static_cast<bool*>(lazy->invoke(10)->value),"and short circuits");check(*static_cast<bool*>(lazy->invoke(11)->value),"or short circuits");
    heap::clearHeap();check(heap::alloc(0)==address{},"zero allocation");rejects([]{heap::alloc(-1);},"negative allocation");
    auto a=heap::alloc(2),b=heap::alloc(2);check(a==address{1}&&b==address{3},"zero address reserved");
    heap::getAt(a)->setValue(99);check(heap::free(a),"free allocation");rejects([&]{heap::getAt(a);},"freed address rejection");
    check(heap::alloc(1)==address{1},"reuse leading hole");check(heap::getAt(address{1})->type==VOID_VALUE,"reused allocation cleared");
    rejects([]{heap::getAt(address{});},"null address");rejects([&]{heap::getAt(address{b.value+2});},"out of bounds");
    rejects([]{heap::restore({std::make_shared<variable>(nullptr)},{{address{1},1}});},"invalid snapshot");check(heap::lenAlloc()==2,"snapshot failure atomic");
    {
        // Indexed lookups and the packed-prefix hint must keep exact first-fit
        // addresses and range checks; compare with a straightforward model.
        heap::clearHeap();
        std::map<int,int> model;
        unsigned seed=12345;
        const auto next=[&](unsigned bound){seed=seed*1103515245u+12345u;return (seed>>8)%bound;};
        const auto model_alloc=[&](int size){
            int start=1;
            for(auto [begin,length]:model){if(begin-start>=size)break;start=begin+length;}
            model[start]=size;return start;
        };
        const auto model_contains=[&](int pointer){
            auto it=model.upper_bound(pointer);
            if(it==model.begin())return false;
            --it;return pointer-it->first<it->second;
        };
        bool same=true;
        for(int step=0;step<6000&&same;++step) {
            const unsigned action=next(10);
            if(action<5||model.empty()) {
                const int size=static_cast<int>(next(4))+1;
                same=heap::alloc(size).value==static_cast<std::uint64_t>(model_alloc(size));
            } else if(action<8) {
                auto it=std::next(model.begin(),static_cast<long>(next(static_cast<unsigned>(model.size()))));
                // action 7 targets start+1: an interior slot, the next start, or a hole.
                const int pointer=it->first+(action==7?1:0);
                const bool expected=model.erase(pointer)==1;
                same=heap::free(address{static_cast<std::uint64_t>(pointer)})==expected;
            } else {
                const int pointer=static_cast<int>(next(static_cast<unsigned>(heap::lenHeap())+3));
                bool found=true;try{heap::getAt(address{static_cast<std::uint64_t>(pointer)});}catch(const std::out_of_range&){found=false;}
                same=found==model_contains(pointer);
                if(same&&model.count(pointer))same=heap::object_address(address{static_cast<std::uint64_t>(pointer)},model[pointer]-1).value==static_cast<std::uint64_t>(pointer+model[pointer]-1);
            }
            same=same&&heap::lenAlloc()==static_cast<int>(model.size());
        }
        check(same,"indexed heap keeps first-fit addresses and bounds");
        heap::clearHeap();
        std::vector<address> many;
        for(int i=0;i<20000;++i)many.push_back(heap::alloc(2));
        check(many.back()==address{39999}&&heap::getAt(address{many[10000].value+1})->type==VOID_VALUE,"appending allocations stays contiguous");
        check(heap::free(many[5])&&heap::alloc(2)==many[5]&&heap::alloc(1)==address{40001},"freed gap is reused before appending");
    }
    heap::clearHeap();
    auto memory=program({fn(10,INT_VALUE,block({def("p",call(0x0abd0003,{n(3)})),binary("m",call(0x0abd0006,{var("p")}),n(42)),ret(call(0x0abd0006,{var("p")}))})),fn(11,INT_VALUE,block({def("p",call(0x0abd0003,{n(3)})),ret(binary("divide",n(1),n(0)))}))});
    check(integer(memory->invoke(10))==42,"heap assignment and return copy");check(heap::lenAlloc()==0,"normal scope cleanup");
    rejects([&]{memory->invoke(11);},"heap cleanup on exception");check(heap::lenAlloc()==0,"exception scope cleanup");
    variable v(std::string("hello"));v.copy_from(std::make_shared<variable>(5));v.setValue(true);v.setValue(1.5f);v.setValue(2.5);v.setValue(nullptr);check(v.type==VOID_VALUE,"safe type transitions");
    variable literal("hello");check(literal.type==STRING_VALUE&&value_to_string(std::make_shared<variable>(literal))=="hello","C string literal stays a string");
    const char* null_text=nullptr;variable null_literal(null_text);check(null_literal.type==VOID_VALUE,"null C string pointer stays null");
    auto original=std::make_shared<variable>(std::string("value"));auto copy=original->deepCopy();copy->setValue(std::string("other"));check(*static_cast<std::string*>(original->value)=="value","deep copy independent");
    rejects([]{unsigned char b[]={100,0,0,0};load_script(b,sizeof b);},"truncated input");
    rejects([]{program({fn(10,VOID_VALUE,block({op("nonsense")}))});},"unknown opcode");
    rejects([]{program({fn(10,INT_VALUE,block({binary("m",n(1),n(2)),ret(n(0))}))});},"literal assignment rejection");
    // Numbers convert to their shortest round-trip spelling in print and string concatenation.
    check(value_to_string(std::make_shared<variable>(0.1+0.2))=="0.30000000000000004","double prints shortest round-trip form");
    check(value_to_string(std::make_shared<variable>(123456789.0))=="123456789","integral double prints all digits");
    check(value_to_string(std::make_shared<variable>(3.14159265358979))=="3.14159265358979","double keeps full precision");
    check(value_to_string(std::make_shared<variable>(0.1f))=="0.1","float prints shortest float form");
    check(value_to_string(std::make_shared<variable>(-0.0))=="-0","negative zero keeps its sign");
    check(value_to_string(std::make_shared<variable>(1e20))=="1e+20","large double uses exponent form");
    check(value_to_string(std::make_shared<variable>(-2147483647-1))=="-2147483648","int minimum");
    // Automatic-release ownership: an allocation may only be freed from its owner's chain.
    {
        constexpr int reentrant_ns=0xf235;
        constexpr int reentrant_host=static_cast<int>((static_cast<unsigned int>(reentrant_ns)<<16)|1u);
        std::shared_ptr<script> owner_script;
        auto reentrant=std::make_shared<test_executor>(reentrant_ns);
        reentrant->functions[reentrant_host]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&){
            owner_script->invoke(11);return std::make_shared<variable>(0);});
        registered_executor keep(reentrant);
        owner_script=program({
            fn(10,INT_VALUE,block({def("p",call(0x0abd0003,{n(2)})),set("g1",var("p")),call(reentrant_host),ret(var("g2"))})),
            fn(11,INT_VALUE,block({call(0x0abd0002,{var("g1")}),ret(n(0))})),
            fn(12,INT_VALUE,block({def("r",call(0x0abd0003,{n(2)})),binary("m",call(0x0abd0006,{var("r")}),n(42)),call(0x0abd0005,{var("r")}),set("g2",var("r")),ret(n(0))})),
            fn(13,INT_VALUE,block({ret(call(0x0abd0006,{var("g2")}))})),
            fn(14,BOOLEAN_VALUE,block({def("q",call(0x0abd0003,{n(1)})),ret(call(15,{var("q")}))})),
            fn(15,BOOLEAN_VALUE,block({ret(call(0x0abd0002,{var("__func_param0")}))}),1),
            fn(16,BOOLEAN_VALUE,block({def("u",call(0x0abd0003,{n(1)})),call(0x0abd0004,{var("u")}),ret(call(0x0abd0002,{var("u")}))}))
        },block({s("g1"),s("g2")}));
        rejects_containing([&]{owner_script->invoke(10);},"another active scope","re-entrant host call cannot free a suspended scope's allocation");
        check(heap::lenAlloc()==0,"rejected free leaves nothing behind after unwinding");
        check(integer(owner_script->invoke(12))==0,"global-lifetime allocation created");
        check(integer(owner_script->invoke(13))==42,"global-lifetime allocation survives unrelated scope exits");
        check(*static_cast<bool*>(owner_script->invoke(14)->value),"callee may free an allocation owned by its caller chain");
        check(*static_cast<bool*>(owner_script->invoke(16)->value),"make_free detaches ownership so any scope may free");
        check(heap::lenAlloc()==1,"only the global allocation remains");
        auto host_block=heap::alloc(1);check(heap::owner_of(host_block)==nullptr,"host allocations are untracked");
        rejects([&]{heap::set_owner(address{host_block.value+7},owner_script->baseEnv.get());},"owner requires an allocation start");
        heap::free(host_block);
        owner_script->destroy();check(heap::lenAlloc()==0,"base scope releases its allocation on destroy");
    }
    // Literal objects: slot blocks with value semantics and expiring addresses.
    {
        auto host=blocks::create(3);auto base=blocks::address_of(host);
        check(blocks::is_block_address(base)&&!blocks::is_block_address(address{5}),"object addresses carry the block tag");
        heap::getAt(address{base.value+2})->setValue(7);
        check(integer(heap::getAt(address{base.value+2}))==7&&heap::object_address(base,1).value==base.value+1,
              "object slots resolve through the public heap accessors");
        rejects([&]{heap::getAt(address{base.value+3});},"object slot beyond its length rejected");
        rejects([&]{heap::object_address(address{base.value+1},0);},"member address requires the object start");
        blocks::resize(host,5);heap::getAt(address{base.value+4})->setValue(1);
        check(blocks::length(host)==5&&integer(heap::getAt(address{base.value+2}))==7,"growing keeps existing slots and addresses");
        blocks::resize(host,2);
        rejects([&]{heap::getAt(address{base.value+2});},"shrinking expires trailing slots");
        auto copy=blocks::copy(host);
        check(copy->id!=host->id&&copy->slots.size()==2,"a copy has its own storage and address");
        host.reset();
        rejects_containing([&]{heap::getAt(base);},"expired","a released object's address expires");
        check(blocks::address_of(blocks::create(1)).value!=base.value,"object ids are never reused");
        rejects_containing([&]{heap::free(address{0});heap::delete_object(blocks::address_of(copy),nullptr);},"literal object","delete rejects literal objects");
        auto chain=blocks::create(1);
        rejects_containing([&]{for(int i=0;i<80;++i){auto outer=blocks::create(1);blocks::store(outer->slots[0],std::make_shared<variable>(chain),nullptr);chain=outer;}},
                           "nesting","object nesting is bounded");
        auto holder=std::make_shared<variable>(nullptr);blocks::store(holder,std::make_shared<variable>(blocks::create(1)),nullptr);
        auto owned=blocks::of(holder);blocks::store(owned->slots[0],holder,nullptr);
        rejects_containing([&]{blocks::store(owned->slots[0],holder,nullptr);},"itself","assigning an object into itself is rejected");

        // log = log*10 + field for each destructor run.
        auto value_log=[](int destructor){return typed_fn(destructor,VOID_VALUE,block({set("log",binary("add",binary("multiply",var("log"),n(10)),
            call(0x0abd0006,{var("__func_param0")})))}),{ADDRESS_VALUE});};
        auto literal=program({
            typed_fn(20,OBJECT_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{block_address(var("o"))}),var("__func_param0")),
                object_bind(block_address(var("o")),30,false),object_return(var("o"))}),{INT_VALUE}),
            value_log(30),
            typed_fn(31,VOID_VALUE,block({set("log",binary("add",binary("multiply",var("log"),n(10)),n(9)))}),{ADDRESS_VALUE}),
            fn(10,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",call(20,{n(2)})),ret(n(0))})),
            fn(11,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",var("a")),binary("m",call(0x0abd0006,{block_address(var("b"))}),n(5)),
                ret(call(0x0abd0006,{block_address(var("a"))}))})),
            fn(12,INT_VALUE,block({def("a",call(20,{n(1)})),def("p",block_address(var("a"))),def("b",call(20,{n(7)})),set("a",var("b")),
                ret(call(0x0abd0006,{var("p")}))})),
            typed_fn(13,OBJECT_VALUE,block({def("x",call(20,{n(4)})),object_return(var("x"))}),{}),
            fn(14,INT_VALUE,block({def("y",call(13)),ret(call(0x0abd0006,{block_address(var("y"))}))})),
            typed_fn(15,ADDRESS_VALUE,block({def("x",call(20,{n(4)})),ret(block_address(var("x")))}),{}),
            fn(16,INT_VALUE,block({ret(call(0x0abd0006,{call(15)}))})),
            fn(17,INT_VALUE,block({set("log",n(0)),call(20,{n(3)}),ret(var("log"))})),
            fn(18,INT_VALUE,block({def("i",n(0)),def("seen",n(0)),
                loop(binary("lt",binary("add",var("i"),call(0x0abd0006,{block_address(call(33))})),n(100)),
                     block({set("seen",var("log")),set("i",binary("add",var("i"),n(1)))})),ret(var("seen"))})),
            fn(19,INT_VALUE,block({def("p",call(0x0abd0003,{n(1)})),binary("m",call(0x0abd0006,{var("p")}),call(20,{n(5)})),
                object_bind(var("p"),31,false),ret(n(0))})),
            fn(21,INT_VALUE,block({def("p",call(0x0abd0003,{n(1)})),binary("m",call(0x0abd0006,{var("p")}),call(20,{n(5)})),
                call(0x0abd0002,{var("p")}),ret(var("log"))})),
            fn(22,INT_VALUE,block({def("a",call(20,{n(1)})),branch(call(0x0abd0002,{block_address(var("a"))}),block({ret(n(1))})),
                ret(n(0))})),
            fn(34,VOID_VALUE,block({def("a",call(20,{n(1)})),call(0x0abd0004,{block_address(var("a"))})})),
            fn(23,INT_VALUE,block({def("a",call(20,{n(1)})),object_delete(block_address(var("a"))),ret(n(0))})),
            fn(24,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",call(20,{n(2)})),drop(var("a")),set("log",binary("multiply",var("log"),n(10))),ret(n(0))})),
            fn(25,VOID_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{block_address(var("o"))}),call(20,{n(6)})),
                binary("divide",n(1),n(0))})),
            typed_fn(26,INT_VALUE,block({ret(call(0x0abd0006,{block_address(var("__func_param0"))}))}),{OBJECT_VALUE}),
            fn(27,INT_VALUE,block({def("a",call(20,{n(8)})),def("r",call(26,{var("a")})),ret(var("r"))})),
            typed_fn(32,VOID_VALUE,block({set("log",binary("add",var("log"),n(1)))}),{ADDRESS_VALUE}),
            typed_fn(33,OBJECT_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{block_address(var("o"))}),n(0)),
                object_bind(block_address(var("o")),32,false),object_return(var("o"))}),{}),
            typed_fn(28,INT_VALUE,block({def("t",move_out("__func_param0")),ret(call(0x0abd0006,{block_address(var("t"))}))}),{OBJECT_VALUE}),
            fn(29,INT_VALUE,block({ret(call(28,{call(20,{n(3)})}))}))
        },block({s("log")}));
        auto log=literal->baseEnv->getVariable("log");
        const auto run=[&](int id){log->setValue(0);return integer(literal->invoke(id));};
        run(10);check(integer(log)==21,"literal locals end in reverse order");
        check(run(11)==1&&integer(log)==51,"initializing from a named object copies every slot; each copy is destroyed");
        check(run(12)==7&&integer(log)==77,"assignment copies in place and keeps the object's address");
        check(run(14)==4&&integer(log)==4,"returning a local moves it to the caller without an extra copy");
        rejects_containing([&]{run(16);},"expired","an escaped address of an ended object is rejected");
        check(run(17)==3,"an unbound temporary ends with its statement");
        check(run(18)==100,"loop-condition temporaries end after each evaluation");
        run(19);check(integer(log)==95,"value fields end after the containing heap object's destructor");
        check(run(21)==0&&heap::lenAlloc()==0,"raw free releases value fields without destructors");
        check(run(22)==0&&integer(log)==1,"mem_free does not release literal-object storage");
        rejects_containing([&]{literal->invoke(34);},"literal object address","make_free rejects literal objects");
        rejects_containing([&]{run(23);},"literal object","delete rejects literal objects");
        run(24);check(integer(log)==102,"drop ends a value immediately and scope cleanup skips it");
        log->setValue(0);rejects_containing([&]{literal->invoke(25);},"Division by zero","construction failure propagates");
        check(integer(log)==6,"completed value members end when construction fails");
        check(run(27)==8&&integer(log)==88,"by-value parameters are separate copies destroyed with the call");
        check(run(29)==3&&integer(log)==3,"a moved parameter is not destroyed twice");
        auto result=literal->invoke(13);
        check(result->type==OBJECT_VALUE&&!blocks::of(result)->owner,"a host call receives a host-held object");
        literal->destroy();
        check(heap::lenAlloc()==0,"literal objects leave no heap allocations");
    }
    // Host namespaces never overlap script namespaces.
    rejects_containing([]{registerExecutor(std::make_shared<test_executor>(0));},"reserved host namespace","host cannot bind lifecycle namespace 0");
    rejects_containing([]{registerExecutor(std::make_shared<test_executor>(0xfff));},"reserved host namespace","host cannot bind the default script namespace");
    {
        auto shadow=std::make_shared<test_executor>(0x123);
        shadow->functions[0x01230009]=std::make_shared<test_function>(INT_VALUE,[](const std::vector<V>&){return std::make_shared<variable>(1);});
        registered_executor keep(shadow);
        rejects([&]{program({fn(0x01230001,INT_VALUE,block({ret(n(7))}))});},"mount rejects a namespace already registered by the host");
        unregisterExecutor(0x123);
        auto namespaced=program({fn(0x01230001,INT_VALUE,block({ret(n(7))}))});
        rejects([&]{registerExecutor(shadow);},"host registration rejects a mounted script namespace");
        check(integer(namespaced->invoke(0x01230001))==7,"script function in a custom namespace");
        rejects_containing([&]{namespaced->invoke(0x01230009);},"script namespace","missing id in a script namespace is not served by the host");
        check(!namespaced->hasFunction(0x01230009),"hasFunction mirrors the namespace rule");
        rejects([&]{program({fn(0x01230001,INT_VALUE,block({ret(n(7))}))},block({}),block({signature(0x01230009,INT_VALUE,{})}));},"external signature in a script namespace rejected");
        rejects([&]{program({fn(10,INT_VALUE,block({ret(n(7))}))},block({}),block({signature(0x00000009,INT_VALUE,{})}));},"external signature in the lifecycle namespace rejected");
    }
    // Hosts may publish a script before running its onload hook.
    {
        auto deferred_module=module({fn(0,VOID_VALUE,block({set("flag",n(1))})),fn(10,BOOLEAN_VALUE,block({ret(binary("eq",var("flag"),n(1)))}))},block({s("flag")}));
        auto data=encode_fixture(deferred_module);auto bytes=data->toBytes();
        auto deferred=load_script(bytes.get(),data->size+4);
        check(!deferred->setup&&!deferred->module_manifest()[0].initialized,"deferred onload is still pending");
        rejects([&]{deferred->invoke(10);},"script requires explicit flush before invocation");
        deferred->flush();deferred->flush();
        check(deferred->setup&&deferred->module_manifest()[0].initialized,"onload retired after running");
        check(*static_cast<bool*>(deferred->invoke(10)->value),"onload effects visible after flush");
    }
    p->destroy();p->destroy();rejects([&]{p->invoke(10);},"closed script");
    std::cout<<"Runtime regression checks passed: "<<assertions<<'\n';return 0;
}catch(const std::exception& e){std::cerr<<"Runtime test failure: "<<e.what()<<'\n';return 1;}}
