#include "library.h"
#include "tree/expressions.h"
#include "blocks.h"
#include "core/trace.h"
#include "core/statement.h"
#include "heepalloc.h"
#include "internelFunctions.h"
#include "p256.h"
#include "extern_library.h"
#include <algorithm>
#include <clocale>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <initializer_list>
#include <iostream>
#include <limits>
#include <map>
#include <stdexcept>
#include <string>
#include <thread>
#include <typeinfo>
#include <utility>
#include <vector>
#if !defined(_WIN32)
#include <pthread.h>
#endif
namespace {
std::vector<std::uint8_t> elf64_with_needed(std::initializer_list<const char*> names) {
    std::string table(1,'\0');
    std::vector<std::size_t> offsets;
    for(const char* name:names){offsets.push_back(table.size());table.append(name);table.push_back('\0');}
    const std::size_t dyn_count=offsets.size()+3;
    const std::size_t phoff=64;
    const std::size_t dynoff=phoff+2*56;
    const std::size_t stroff=dynoff+dyn_count*16;
    std::vector<std::uint8_t> bytes(stroff+table.size(),0);
    auto w16=[&](std::size_t at,std::uint16_t value){bytes[at]=value&0xff;bytes[at+1]=value>>8;};
    auto w32=[&](std::size_t at,std::uint32_t value){for(int i=0;i<4;++i)bytes[at+i]=static_cast<std::uint8_t>(value>>(8*i));};
    auto w64=[&](std::size_t at,std::uint64_t value){for(int i=0;i<8;++i)bytes[at+i]=static_cast<std::uint8_t>(value>>(8*i));};
    bytes[0]=0x7f;bytes[1]='E';bytes[2]='L';bytes[3]='F';bytes[4]=2;bytes[5]=1;bytes[6]=1;
    w16(16,3);w16(18,62);w32(20,1);w64(32,phoff);w16(52,64);w16(54,56);w16(56,2);
    auto program=[&](int index,std::uint32_t type,std::uint64_t offset,std::uint64_t filesz,std::uint32_t flags){
        const std::size_t at=phoff+static_cast<std::size_t>(index)*56;
        w32(at,type);w32(at+4,flags);w64(at+8,offset);w64(at+16,offset);w64(at+24,offset);
        w64(at+32,filesz);w64(at+40,filesz);w64(at+48,8);
    };
    program(0,1,0,bytes.size(),5);
    program(1,2,dynoff,dyn_count*16,6);
    for(std::size_t i=0;i<offsets.size();++i){w64(dynoff+i*16,1);w64(dynoff+i*16+8,offsets[i]);}
    const std::size_t tail=dynoff+offsets.size()*16;
    w64(tail,5);w64(tail+8,stroff);
    w64(tail+16,10);w64(tail+24,table.size());
    w64(tail+32,0);w64(tail+40,0);
    std::memcpy(bytes.data()+stroff,table.data(),table.size());
    return bytes;
}
}
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
MV boolean(bool v){return std::make_shared<BoolAbdValue>(v);}
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
    return raw({ri(id),ri(type),ri(static_cast<int>(params.size())),ri(locals),types.toAbdValue(),body,ri(0),ri(0),rb(false)});
}
R rmodule(std::initializer_list<R> functions,int globals=0,R signatures=nullptr){
    return raw({rs("AZSCRIPT"),ri(EXEC_FORMAT_VERSION),ri(1),rs("test"),ri(globals),std::make_shared<AbdMap>()->toAbdValue(),signatures?signatures:raw({}),raw(functions),rs(""),raw({}),raw({})});
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
R rsignature(int id,int type,std::initializer_list<int> params={}){AbdStack types;for(int value:params)types.vs.push_back(ri(value));return raw({ri(id),ri(type),types.toAbdValue(),ri(0),ri(0)});}
R rcall(int id,std::initializer_list<R> args={}){return rx(2,{ri(id),raw(args),raw({})});}
R rvar(int slot){return rx(3,{ri(slot)});}
R rreturn(R value){return rx(7,{rb(true),value});}
R rset(int slot,R value){return rx(5,{ri(slot),value});}
R rdef(int slot,R value){return rx(4,{ri(slot),ri(ANY_VALUE),rb(true),value});}
R cref(int index){return raw({ri(0),ri(index)});}
R cfixed(int abi,int kind,std::optional<int> factory={},std::initializer_list<R> contexts={}) {
    AbdStack result;result.vs={ri(1),ri(abi),ri(kind),ri(1),rb(factory.has_value())};
    if(factory)result.vs.push_back(ri(*factory));result.vs.push_back(raw(contexts));result.vs.push_back(rb(false));result.vs.push_back(raw({}));result.vs.push_back(rb(false));return result.toAbdValue();
}
R gfn(int id,int type,R body,int hidden,int locals=0,std::initializer_list<int> params={},int entry=0) {
    return replace_raw(replace_raw(rfn(id,type,body,locals,params),6,ri(hidden)),7,ri(entry));
}
R gcall(int id,std::initializer_list<R> args,std::initializer_list<R> contexts){return rx(2,{ri(id),raw(args),raw(contexts)});}
R greturn(R value,R context){return rx(38,{value,context});}
R reflect(int expected,int id,std::initializer_list<R> args={}) {
    AbdStack arguments;arguments.vs={rc(n(expected)),rc(n(id))};arguments.vs.insert(arguments.vs.end(),args);
    return rx(2,{ri(0x0abd0008),arguments.toAbdValue(),raw({})});
}
std::shared_ptr<script> load_raw(R value,bool ready=true){
    auto bytes=value->toBytes();auto result=load_script(bytes.get(),value->size+4);
    if(ready)try{result->flush();}catch(...){auto failure=std::current_exception();try{result->destroy();}catch(...){}std::rethrow_exception(failure);}
    return result;
}
void insert_raw(const std::shared_ptr<script>& target,R value,bool ready=true){auto bytes=value->toBytes();target->insert_script(bytes.get(),value->size+4);if(ready)target->flush();}
// Test fixture authoring retains readable names; only this test encoder resolves
// them to slots. The interpreter is always given the public raw exec format.
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
            return rx(2,{map->get("id")->toAbdValue(),args.toAbdValue(),raw({})});
        }
        const auto code=fixture_string(map->get("c"));
        if(code=="v")return rx(3,{ri(slot(map->get("v")))});
        if(code=="vd") {
            R init;if(map->get("val"))init=node(map->get("val"));
            const int index=slot(map->get("v"),true);
            return init?rx(4,{ri(index),ri(ANY_VALUE),rb(true),init}):rx(4,{ri(index),ri(ANY_VALUE),rb(false)});
        }
        if(code=="vs"){const int index=slot(map->get("v"));return rx(5,{ri(index),node(map->get("val"))});}
        // Encode the value before building the list. Malformed fixtures throw here, and GCC 13
        // ASan reports stack-use-after-scope while unwinding a partly built list inside ?:.
        if(code=="r"){if(!map->get("r"))return rx(7,{rb(false)});auto value=node(map->get("r"));return rx(7,{rb(true),value});}
        if(code=="ro")return rx(8,{node(map->get("r"))});
        if(code=="oa")return rx(9,{node(map->get("v")),map->get("offset")->toAbdValue()});
        if(code=="ob") {
            auto address=node(map->get("v"));const int destructor=fixture_int(map->get("destructor"));
            return destructor==-1?rx(10,{address,rb(false),map->get("manual")->toAbdValue(),raw({})}):rx(10,{address,rb(true),ri(destructor),map->get("manual")->toAbdValue(),raw({})});
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
        signatures.vs.push_back(raw({entry->get("id")->toAbdValue(),entry->get("return-type")->toAbdValue(),types.toAbdValue(),ri(0),ri(0)}));
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
        functions.vs.push_back(raw({entry->get("id")->toAbdValue(),entry->get("return-type")->toAbdValue(),ri(params),ri(locals),types.toAbdValue(),body,ri(0),ri(0),rb(false)}));
    }
    return raw({rs("AZSCRIPT"),ri(EXEC_FORMAT_VERSION),ri(1),rs("fixture"),ri(global_count),std::make_shared<AbdMap>()->toAbdValue(),signatures.toAbdValue(),functions.toAbdValue(),rs(""),raw({}),raw({})});
}
void fixture_names(const std::shared_ptr<script>& target,M source,std::size_t offset=0) {
    if(source->get("exec-version"))return;
    for(auto& name:fixture_array(source->get("gvs"))->values)target->baseEnv->variables[offset++]->name=fixture_string(name);
}
std::shared_ptr<script> load_module(M m){auto result=load_raw(encode_fixture(m));fixture_names(result,m);return result;}
void insert_module(const std::shared_ptr<script>& target,M m){const auto offset=target->baseEnv->variables.size();insert_raw(target,encode_fixture(m));fixture_names(target,m,offset);}
std::shared_ptr<script> program(std::initializer_list<MV> funcs,A globals=block({}),A signatures=nullptr){return load_module(module(funcs,std::move(globals),std::move(signatures)));}
void insert_program(const std::shared_ptr<script>& target,std::initializer_list<MV> funcs,A globals=block({}),A signatures=nullptr){insert_module(target,module(funcs,std::move(globals),std::move(signatures)));}
address pointer(std::shared_ptr<variable> v){check(v->type_tag()==ADDRESS_VALUE,"expected address result");return v->get<address>();}
int integer(std::shared_ptr<variable> v){check(v->type_tag()==INT_VALUE,"expected int result");return v->get<int>();}
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
R bnew(R context){return rx(40,{context});}
R bop(int operation,R receiver,std::initializer_list<R> args={}){return rx(41,{ri(operation),receiver,raw(args)});}
R class_context(int width,int placement,std::initializer_list<R> contexts={}) {
    return raw({ri(1),ri(OBJECT_VALUE),ri(3),ri(width),rb(false),raw({}),rb(true),ri(placement),raw(contexts),rb(false)});
}
R buffer_context(R element){return raw({ri(1),ri(OBJECT_VALUE),ri(4),ri(1),rb(false),raw({}),rb(false),raw({}),rb(true),element});}
// The loader's hint rule must agree with the compiler's SourceRules.isHint: both
// read the same cases from tests/hint_names.txt.
void hint_name_tests() {
    std::ifstream input(AZSCRIPT_HINT_CASES,std::ios::binary);
    check(static_cast<bool>(input),"hint-name cases are readable");
    std::string line;int cases=0;
    auto hex=[](char c)->int{return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;};
    while(std::getline(input,line)) {
        if(!line.empty()&&line.back()=='\r')line.pop_back();
        if(line.empty()||line[0]=='#')continue;
        const auto space=line.find(' ');const std::string verdict=line.substr(0,space);
        if(space==std::string::npos||(verdict!="valid"&&verdict!="invalid"))throw std::runtime_error("malformed hint case: "+line);
        const std::string text=line.substr(space+1);std::string name;
        if(text!="(empty)")for(std::size_t i=0;i<text.size();++i) {
            if(text[i]!='\\'){name.push_back(text[i]);continue;}
            if(i+3>=text.size()||text[i+1]!='x'||hex(text[i+2])<0||hex(text[i+3])<0)throw std::runtime_error("malformed hint escape: "+line);
            name.push_back(static_cast<char>(hex(text[i+2])*16+hex(text[i+3])));i+=3;
        }
        // An assumption takes any name; a module hint is only checked when it is not empty.
        auto loads=[](R module,const char* error){
            try{load_raw(module,false);return true;}
            catch(const std::exception& e){if(std::string(e.what()).find(error)==std::string::npos)throw;return false;}
        };
        const bool expected=verdict=="valid";
        if(loads(rhint("",{rfn(2,INT_VALUE,rreturn(rc(n(1))))},0,raw({assume(name,0x1234)})),"Invalid namespace assumption")!=expected)
            throw std::runtime_error("loader and compiler disagree on assumed hint: "+line);
        if(!name.empty()&&loads(rhint(name,{rfn(2,INT_VALUE,rreturn(rc(n(1))))}),"Invalid namespace hint")!=expected)
            throw std::runtime_error("loader and compiler disagree on module hint: "+line);
        assertions+=2;++cases;
    }
    check(cases>=250,"every hint-name case is checked");
}
void ownership_identity_tests() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto budget=std::make_shared<WorkspaceBudget>(1024*1024);
    auto arena=ownership::Arena::create(budget);
    const auto first=arena->create_scope();
    auto* stable=ownership::find(first);
    std::vector<ownership::ScopeId> live;
    for(int i=0;i<200;++i)live.push_back(arena->create_scope());
    check(ownership::find(first)==stable,"ownership records keep their address across page growth");
    arena->retire(first);const auto reused=arena->create_scope();
    check(first.index==reused.index&&first.generation!=reused.generation&&!ownership::find(first),
          "a retired ownership ID cannot resolve a reused scope slot");
    auto other=ownership::Arena::create();const auto foreign=other->create_scope();
    check(!arena->find(foreign)&&!other->find(reused),"ownership IDs reject foreign arenas");
    // Global lookups alternate between domains; the last resolved arena of a
    // kind must not answer for another, nor outlive its arena.
    const auto foreign_list=other->create_temporaries();
    check(ownership::find(foreign)==other->find(foreign)&&ownership::find(reused)==arena->find(reused)&&
          ownership::find(foreign)==other->find(foreign)&&ownership::find(foreign_list)==other->find(foreign_list),
          "global ownership lookups alternate between arenas");
    other.reset();check(!ownership::find(foreign)&&!ownership::find(foreign_list),"destroying an arena invalidates its domain");
    auto successor=ownership::Arena::create();const auto successor_scope=successor->create_scope();
    check(!ownership::find(foreign)&&ownership::find(successor_scope)==successor->find(successor_scope),
          "a later arena does not answer for a destroyed one");
    successor->retire(successor_scope);successor.reset();
    for(auto id:live)arena->retire(id);
    arena->retire(reused);arena.reset();
    check(budget->used_bytes()==0,"ownership arena releases charged pages and directory");
    // Fail each actual pool backing allocation (directory, page), both cold
    // and while growing past the first page. A failed growth must preserve
    // existing IDs and roll back its budget reservation before a retry.
    struct failing_resource final:std::pmr::memory_resource {
        int remaining=-1;
        void* do_allocate(std::size_t bytes,std::size_t alignment) override {
            if(remaining==0)throw std::bad_alloc();
            if(remaining>0)--remaining;
            return std::pmr::new_delete_resource()->allocate(bytes,alignment);
        }
        void do_deallocate(void* p,std::size_t bytes,std::size_t alignment) override {
            std::pmr::new_delete_resource()->deallocate(p,bytes,alignment);
        }
        bool do_is_equal(const std::pmr::memory_resource& other) const noexcept override{return this==&other;}
    } resource;
    for(bool temporary:{false,true})for(int initial:{0,64})for(int failure:{0,1}) {
        auto* previous=std::pmr::set_default_resource(&resource);
        std::shared_ptr<ownership::Arena> fault_arena;
        try {fault_arena=ownership::Arena::create(budget);}
        catch(...) {std::pmr::set_default_resource(previous);throw;}
        std::pmr::set_default_resource(previous);
        const auto exercise=[&](auto create) {
            using Id=decltype(create());
            std::vector<Id> ids;
            for(int i=0;i<initial;++i)ids.push_back(create());
            const auto before=budget->used_bytes();
            resource.remaining=failure;bool failed=false;
            try {create();}catch(const std::bad_alloc&){failed=true;}
            resource.remaining=-1;
            check(failed&&budget->used_bytes()==before,"failed ownership backing growth rolls back its charge");
            for(auto id:ids)check(ownership::find(id)!=nullptr,"failed ownership growth preserves live records");
            const auto retry=create();check(ownership::find(retry)!=nullptr,"ownership growth can retry after allocation failure");
            fault_arena->retire(retry);for(auto id:ids)fault_arena->retire(id);
        };
        if(temporary)exercise([&]{return fault_arena->create_temporaries();});
        else exercise([&]{return fault_arena->create_scope();});
        fault_arena.reset();check(budget->used_bytes()==0,"fault-injected ownership arena releases all backing");
    }
    auto tiny=std::make_shared<WorkspaceBudget>(1);auto limited=ownership::Arena::create(tiny);
    rejects_containing([&]{environment scope(limited);},"workspace limit","scope allocation observes its VM workspace budget");
    check(tiny->used_bytes()==0,"failed scope construction does not publish or charge a record");
    std::weak_ptr<WorkspaceBudget> lifetime=budget;
    arena=ownership::Arena::create(budget);
    auto scope=std::make_shared<environment>(arena);const auto scope_id=scope->ownership_id();
    budget.reset();arena.reset();
    check(!lifetime.expired()&&ownership::find(scope_id),"a retained host scope keeps its arena and budget alive");
    scope.reset();check(lifetime.expired()&&!ownership::find(scope_id),"last scope releases its arena and budget");
    {
        // An engine scope viewed by a host environment: the view keeps the
        // record alive and never ends what the scope owns.
        auto shared_arena=ownership::Arena::create();
        auto engine=std::make_unique<ownership::Scope>(shared_arena);const auto engine_id=engine->id();
        auto owned=blocks::create(1,{});auto holder=std::make_shared<variable>();holder->setValue(owned);owned->owner=holder.get();
        blocks::bind_local(holder,ownership::Site{&engine->record(),nullptr});
        auto view=std::make_shared<environment>(*engine,nullptr,nullptr,false);
        check(view->ownership_id()==engine_id&&&view->owned_pointer==&engine->record().owned,"a scope view shares the scope record");
        view.reset();
        check(owned->state==slot_block::status::live&&engine->record().owned.size()==1,"a scope view never abandons the scope's objects");
        view=std::make_shared<environment>(*engine,nullptr,nullptr,false);
        engine->record().owned.clear();owned->owner_scope={};engine.reset();
        check(ownership::find(engine_id)&&view->ownership_id()==engine_id,"a scope view keeps the record after the scope ends");
        view.reset();check(!ownership::find(engine_id),"the last lease on a shared scope record retires it");
        holder->setValue(nullptr);
    }
    arena=ownership::Arena::create();
    auto frame=std::make_shared<function_frame>(arena);const auto temporary_id=frame->temporary_id();
    auto temporary=blocks::create(0,{});blocks::adopt_temporary(temporary,frame.get());
    auto owner=std::make_shared<variable>();auto value=std::make_shared<variable>(temporary);
    blocks::store(owner,value,ownership::Site{});
    check(!frame->temporaries[0]&&!temporary->temporary_list,"moving a temporary leaves its statement-mark hole");
    auto later=blocks::create(0,{});blocks::adopt_temporary(later,frame.get());
    check(later->temporary_index==1,"new temporaries do not reuse an earlier statement-mark hole");
    frame.reset();frame=std::make_shared<function_frame>(arena);
    check(!ownership::find(temporary_id)&&frame->temporary_id().generation!=temporary_id.generation,
          "temporary records reject an expired frame identity");
    check(later->state==slot_block::status::dead,"frame disposal abandons its remaining temporaries");
    blocks::drop(owner,ownership::Site{});
    auto unowned=blocks::create(0,{});blocks::release_owned(blocks::address_of(unowned),ownership::Site{});
    check(unowned->state==slot_block::status::dead,"null-scope cleanup retains the unowned literal behavior");
    auto raw=heap::alloc(1);heap::release_owned(raw,ownership::Site{});
    rejects([&]{heap::getAt(raw);},"null-scope cleanup retains the unowned heap behavior");
}

void statement_protocol_tests() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    auto arena=ownership::Arena::create();auto frame=std::make_shared<function_frame>(arena);
    auto outer=statement::begin(frame->temporary_id(),frame->temporaries.size());
    auto a=blocks::create(0,{});blocks::adopt_temporary(a,frame.get());
    auto inner=statement::begin(frame->temporary_id(),frame->temporaries.size());
    auto b=blocks::create(0,{});blocks::adopt_temporary(b,frame.get());
    auto owner=std::make_shared<variable>();blocks::store(owner,std::make_shared<variable>(b),ownership::Site{});
    auto c=blocks::create(0,{});blocks::adopt_temporary(c,frame.get());
    check(statement::pending(inner),"inner statement records moved holes and live temporaries");
    statement::finish(inner,{});
    check(!statement::pending(inner)&&a->state==slot_block::status::live&&b->state==slot_block::status::live&&c->state==slot_block::status::dead,
          "inner statement preserves the outer value and the moved value");
    statement::finish(outer,{});
    check(a->state==slot_block::status::dead&&!statement::pending(outer),"outer statement ends the remaining temporary");
    blocks::drop(owner,ownership::Site{});
    auto retired=statement::begin(frame->temporary_id(),frame->temporaries.size());frame.reset();frame=std::make_shared<function_frame>(arena);
    auto later=blocks::create(0,{});blocks::adopt_temporary(later,frame.get());
    statement::finish(retired,{});
    check(!statement::pending(retired)&&later->state==slot_block::status::live&&frame->temporaries.size()==1,
          "an expired statement cannot finalize values in a reused frame slot");
    statement::finish({frame->temporary_id(),0},{});
    check(later->state==slot_block::status::dead,"the current frame's own statement can finalize its value");
}

void buffer_tests() {
    auto ci=cfixed(INT_VALUE,0);auto cb=buffer_context(ci);auto cc=class_context(2,500);
    auto slot_at=[](R base,int offset){return rcall(0x0abd0006,{rx(9,{base,ri(offset)})});};
    auto assign=[](R left,R right){return rx(6,{left,right});};
    auto bind=[](int dtor){return rx(10,{rvar(0),rb(true),ri(dtor),rb(false),raw({})});};
    auto program=load_raw(rmodule({
        rfn(0,VOID_VALUE,rblock({rset(-1,rc(n(0))),rset(-2,rc(n(0)))})),
        gfn(500,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(7))),assign(slot_at(rvar(0),1),rc(s("field"))),bind(501)}),0,0,{ADDRESS_VALUE},1),
        gfn(501,VOID_VALUE,rset(-1,rx(12,{rvar(-1),rc(n(1))})),0,0,{ADDRESS_VALUE},1),
        gfn(502,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(7))),rx(15,{rc(n(1)),rc(n(0))})}),0,0,{ADDRESS_VALUE},1),
        gfn(503,VOID_VALUE,rblock({rset(-1,rx(12,{rvar(-1),rc(n(1))})),rx(15,{rc(n(1)),rc(n(0))})}),0,0,{ADDRESS_VALUE},1),
        gfn(504,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(7))),assign(slot_at(rvar(0),1),rc(s("field"))),bind(503)}),0,0,{ADDRESS_VALUE},1),
        gfn(505,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(7))),assign(slot_at(rvar(0),1),bnew(ci)),bind(501)}),0,0,{ADDRESS_VALUE},1),
        rfn(10,INT_VALUE,rblock({rdef(0,bnew(ci)),bop(2,rvar(0),{rc(n(4))}),bop(5,rvar(0),{rc(n(7))}),
            bop(4,rvar(0),{rc(n(0)),rc(n(9))}),rreturn(bop(3,rvar(0),{rc(n(0))}))}),1),
        rfn(11,OBJECT_VALUE,rblock({rdef(0,bnew(cc)),bop(2,rvar(0),{rc(n(2))}),bop(6,rvar(0),{rc(n(2))}),rreturn(rvar(0))}),1),
        gfn(12,ANY_VALUE,greturn(rvar(0),cref(0)),1,0,{ANY_VALUE}),
        rfn(13,INT_VALUE,rblock({rdef(0,bnew(ci)),bop(2,rvar(0),{rc(n(2))}),bop(5,rvar(0),{rc(n(42))}),
            rdef(1,gcall(12,{rvar(0)},{cb})),bop(4,rvar(1),{rc(n(0)),rc(n(9))}),rreturn(bop(3,rvar(0),{rc(n(0))}))}),2),
        rfn(14,VOID_VALUE,rblock({rdef(0,bnew(ci)),bop(2,rvar(0),{rc(n(100))})}),1),
        rfn(17,VOID_VALUE,rblock({rdef(0,bnew(cc)),bop(2,rvar(0),{rc(n(1))}),rdef(1,rx(32,{ri(2)})),
            rcall(500,{rx(33,{rvar(1)})}),bop(5,rvar(0),{rvar(1)})}),2),
        rfn(18,ADDRESS_VALUE,rblock({rdef(0,bnew(ci)),rreturn(rx(33,{rvar(0)}))}),1),
        rfn(15,INT_VALUE,rreturn(rx(42,{ci,rc(n(4)),rc(n(9))}))),
        rfn(16,INT_VALUE,rreturn(rx(42,{cfixed(STRING_VALUE,0),rc(s(std::string("a\0b",3))),rc(s(std::string("a\0c",3)))})))
    },2));
    check(integer(program->invoke(10))==9,"buffer operations borrow their receiver instead of copying it");
    check(integer(program->invoke(13))==42,"generic buffer parameters and return preserve deep value semantics");
    check(integer(program->invoke(15))==-1&&integer(program->invoke(16))==-1,"value_compare executes via exec wire");
    rejects_containing([&]{program->invoke(18);},"requires a class value","block_address cannot expose a buffer address");
    program->invoke(17);check(integer(program->baseEnv->getVariable(-1))==2,
        "push relocates its materialized class argument without an extra copied temporary destructor");
    auto int_context=std::make_shared<type_context>();int_context->abi=INT_VALUE;
    auto type=std::make_shared<type_context>();type->abi=OBJECT_VALUE;type->kind=3;type->width=2;type->placement_id=500;
    auto b=blocks::create_buffer(type,program);auto env=program->baseEnv;
    const auto number=[](int i){return std::make_shared<variable>(i);};
    const auto operation=[&](int op,const std::shared_ptr<slot_block>& receiver,std::initializer_list<V> values={}) {
        return blocks::buffer_operation(op,receiver,values,env);
    };
    check(integer(operation(0,b))==0&&integer(operation(1,b))==0,"default buffer is empty with no slab");
    operation(2,b,{number(3)});operation(6,b,{number(2)});blocks::validate_buffer(b);
    auto first=blocks::of(b->slots[0]),second=blocks::of(b->slots[1]);
    check(first->is_view&&second->is_view&&first->slots[0].get()==&b->buffer->slab[0]&&
          second->slots[0].get()==&b->buffer->slab[2],"class fields occupy consecutive regions of one variable slab");
    check(first->id!=second->id&&integer(first->slots[0])==7,"inline views have distinct object identities and placement construction");
    rejects_containing([&]{heap::getAt(blocks::address_of(b));},"raw slot storage","raw access cannot expose an initialized buffer element");
    rejects_containing([&]{heap::getAt(blocks::address_of(b,2));},"raw slot storage","raw access cannot initialize unused buffer capacity");
    auto field=heap::getAt(blocks::address_of(first));field->setValue(21);
    check(field==first->slots[0]&&integer(first->slots[0])==21,"raw access still reaches fields within a class element view");
    field->setValue(7);
    const auto expired=blocks::address_of(first);const int before=integer(env->getVariable(-1));
    operation(2,b,{number(8)});
    rejects([&]{blocks::resolve(expired);},"reserve invalidates old element view addresses");
    check(integer(env->getVariable(-1))==before&&integer(blocks::of(b->slots[0])->slots[0])==7,
          "reserve migrates initialized fields without executing constructors or destructors");
    auto copied=blocks::copy(b);blocks::of(copied->slots[0])->slots[0]->setValue(91);
    check(integer(blocks::of(b->slots[0])->slots[0])==7&&copied->buffer->capacity==8,"buffer copy owns a separate slab and preserves capacity");
    auto got=operation(3,b,{number(0)});blocks::of(got)->slots[0]->setValue(88);
    check(integer(blocks::of(b->slots[0])->slots[0])==7&&!blocks::of(got)->is_view,"buffer get exports an independent object value");
    blocks::finalize(blocks::of(got),env,true);
    auto holder=std::make_shared<variable>(copied);copied->owner=holder.get();
    blocks::store(holder,std::make_shared<variable>(b),env);
    check(copied->state==slot_block::status::dead&&blocks::of(holder)->buffer->length==2,"buffer assignment replaces ownership and finalizes old elements");
    blocks::finalize(blocks::of(holder),env,true);blocks::finalize(b,env,true);
    check(integer(env->getVariable(-1))==before+7,"inline buffer copies and replacements each finalize exactly once");
    auto absent=std::make_shared<type_context>(*type);absent->placement_id.reset();
    auto no_default=blocks::create_buffer(absent,program);operation(2,no_default,{number(1)});
    rejects_containing([&]{operation(6,no_default,{number(1)});},"no default constructor","resize requires default construction only when growing");
    auto existing=blocks::create(2,program);existing->slots[0]->setValue(55);existing->slots[1]->setValue("ok");existing->destructor=501;
    operation(5,no_default,{std::make_shared<variable>(existing)});
    check(integer(blocks::of(no_default->slots[0])->slots[0])==55,"push accepts a class without a default constructor");
    blocks::finalize(no_default,env,true);blocks::abandon(existing);
    auto failing_type=std::make_shared<type_context>(*type);failing_type->placement_id=502;
    auto failing=blocks::create_buffer(failing_type,program);operation(2,failing,{number(2)});
    const int failure_count=integer(env->getVariable(-1));
    rejects_containing([&]{operation(6,failing,{number(2)});},"Division by zero","failed placement preserves its original error");
    check(failing->buffer->length==0&&failing->slots[0]->type_tag()==VOID_VALUE&&integer(env->getVariable(-1))==failure_count,
          "failed construction does not call the incomplete element destructor");
    blocks::finalize(failing,env,true);
    auto throwing_type=std::make_shared<type_context>(*type);throwing_type->placement_id=504;
    auto throwing=blocks::create_buffer(throwing_type,program);operation(2,throwing,{number(3)});operation(6,throwing,{number(3)});
    const int clear_count=integer(env->getVariable(-1));
    rejects_containing([&]{operation(6,throwing,{number(0)});},"Division by zero","buffer clear reports the first destructor failure");
    check(throwing->buffer->length==0&&integer(env->getVariable(-1))==clear_count+3,"buffer clear continues after every destructor failure");
    blocks::finalize(throwing,env,true);
    auto nested_type=std::make_shared<type_context>();nested_type->abi=OBJECT_VALUE;nested_type->kind=4;nested_type->element=int_context;
    auto nested=blocks::create_buffer(nested_type,program);operation(2,nested,{number(2)});operation(6,nested,{number(1)});
    auto inner=blocks::of(nested->slots[0]);operation(2,inner,{number(2)});operation(5,inner,{number(13)});
    auto nested_copy=blocks::copy(nested);operation(4,blocks::of(nested_copy->slots[0]),{number(0),number(99)});
    check(integer(inner->slots[0])==13,"nested owning buffers are copied recursively");
    blocks::finalize(nested_copy,env,true);blocks::finalize(nested,env,true);
    auto nested_class_type=std::make_shared<type_context>(*type);nested_class_type->placement_id=505;
    auto nested_class=blocks::create_buffer(nested_class_type,program);operation(2,nested_class,{number(1)});operation(6,nested_class,{number(1)});
    auto root=blocks::of(nested_class->slots[0]);auto child=blocks::of(root->slots[1]);operation(2,child,{number(1)});operation(5,child,{number(5)});
    auto root_copy=blocks::copy(root);auto target=std::make_shared<variable>(root_copy);root_copy->owner=target.get();
    blocks::store(target,std::make_shared<variable>(root),env);
    check(integer(blocks::of(root_copy->slots[1])->slots[0])==5,"ordinary class assignment recursively assigns owning buffer fields");
    blocks::finalize(root_copy,env,true);blocks::finalize(nested_class,env,true);
    auto returned=program->invoke(11);check(blocks::of(returned)->buffer->length==2,"buffer return transfers the live slab before local cleanup");
    blocks::finalize(blocks::of(returned),env,true);
    program->max_steps=20;
    rejects_containing([&]{program->invoke(14);},"step limit","reserve charges work before changing the live buffer");
    program->max_steps=1000000;
    auto floating=std::make_shared<type_context>();floating->abi=DOUBLE_VALUE;
    rejects_containing([&]{blocks::compare_value(floating,std::make_shared<variable>(std::numeric_limits<double>::quiet_NaN()),std::make_shared<variable>(0.0),env);},"non-finite","compare rejects NaN");
    rejects_containing([&]{blocks::compare_value(floating,std::make_shared<variable>(std::numeric_limits<double>::infinity()),std::make_shared<variable>(0.0),env);},"non-finite","compare rejects infinity");
    check(blocks::compare_value(floating,std::make_shared<variable>(-0.0),std::make_shared<variable>(0.0),env)==0,"compare treats both finite zeros equally");
    rejects([&]{blocks::compare_value(type,returned,returned,env);},"class and buffer require custom comparison");
    {
        constexpr int callback_id=0x6cde0002;
        auto callback=std::make_shared<test_executor>(0x6cde);int mode=0;std::shared_ptr<slot_block> active;V outer_storage;
        callback->functions[callback_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&) {
            if(mode==0)rejects_containing([&]{operation(2,active,{number(10)});},"active operation","constructor cannot reenter the same buffer mutation");
            else {
                blocks::drop(outer_storage,env);
                check(active->pending_finalize&&active->buffer->slab!=nullptr,"dropping an enclosing owner defers pinned slab destruction");
            }
            return std::make_shared<variable>(nullptr);
        });
        registered_executor registered(callback);
        insert_raw(program,rmodule({gfn(706,VOID_VALUE,rblock({rcall(callback_id),assign(slot_at(rvar(0),0),rc(n(1))),
            assign(slot_at(rvar(0),1),rc(s("pinned"))),bind(501)}),0,0,{ADDRESS_VALUE},1)}));
        auto reentrant_type=std::make_shared<type_context>(*type);reentrant_type->placement_id=706;
        active=blocks::create_buffer(reentrant_type,program);operation(2,active,{number(1)});operation(6,active,{number(1)});
        check(active->buffer->length==1,"caught reentry does not corrupt successful placement");blocks::finalize(active,env,true);
        mode=1;active=blocks::create_buffer(reentrant_type,program);operation(2,active,{number(1)});
        auto outer=blocks::create(1,program);outer->slots[0]->setValue(active);active->owner=outer->slots[0].get();
        outer_storage=std::make_shared<variable>(outer);outer->owner=outer_storage.get();
        rejects_containing([&]{operation(6,active,{number(1)});},"owner ended","placement fails safely if callback destroys its owner");
        check(active->state==slot_block::status::dead&&!active->buffer->slab&&outer_storage->type_tag()==VOID_VALUE,
              "deferred owner destruction eventually releases the slab and every element");
    }
    {
        constexpr int callback_id=0x6cdf0002;
        auto callback=std::make_shared<test_executor>(0x6cdf);
        V destination,source_value,enclosing_storage;std::shared_ptr<slot_block> old;
        std::shared_ptr<environment> ending_scope,reused_scope;
        ownership::ScopeId ending_id;
        int mode=0,callbacks=0;bool inspect=false;address heap_parent;
        callback->functions[callback_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&) {
            ++callbacks;
            if(inspect) {
                inspect=false;
                check(blocks::of(destination)==old&&old->state==slot_block::status::destroying,
                      "buffer replacement keeps the old handle visible throughout its cleanup");
                rejects_containing([&]{blocks::store(destination,source_value,env);},"unavailable",
                                   "buffer assignment cannot reenter an old value being destroyed");
                rejects_containing([&]{blocks::copy(old);},"unavailable",
                                   "buffer assignment cleanup cannot copy a partly destroyed old value");
                if(mode>=7) {
                    ending_scope.reset();
                    reused_scope=std::make_shared<environment>(env->ownership_arena());
                    check(!ownership::find(ending_id)&&reused_scope->ownership_id()!=ending_id,
                          "a destructor callback can retire and reuse its buffer owner's scope slot");
                } else if(mode>=2) {
                    if(mode<4)blocks::drop(enclosing_storage,env);
                    else if(mode==5)heap::free(heap_parent);
                    else heap::delete_object(heap_parent,env);
                    check(old->state==slot_block::status::destroying&&old->slots.size()==2,
                          "ending an enclosing owner leaves the active old-value cleanup intact");
                }
                if(mode==1||mode==3||mode==6||mode==8)throw std::runtime_error("replacement destructor error");
            }
            return std::make_shared<variable>(nullptr);
        });
        registered_executor registered(callback);
        insert_raw(program,rmodule({gfn(707,VOID_VALUE,rcall(callback_id),0,0,{ADDRESS_VALUE},1),
            gfn(708,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(17))),
                assign(slot_at(rvar(0),1),rc(s("replace"))),bind(707)}),0,0,{ADDRESS_VALUE},1)}));
        auto replacement_type=std::make_shared<type_context>(*type);replacement_type->placement_id=708;
        for(mode=0;mode<9;++mode) {
            old=blocks::create_buffer(replacement_type,program);operation(2,old,{number(2)});operation(6,old,{number(2)});
            auto source=blocks::create_buffer(replacement_type,program);operation(2,source,{number(1)});operation(6,source,{number(1)});
            source_value=std::make_shared<variable>(source);
            if(mode>=7) {
                ending_scope=std::make_shared<environment>(env->ownership_arena());
                ending_id=ending_scope->ownership_id();destination=std::make_shared<variable>(old);
                old->owner=destination.get();blocks::bind_local(destination,ending_scope);
            } else if(mode>=4) {
                heap_parent=heap::object_alloc(1);heap::set_owner(heap_parent,env.get());env->owned_pointer.push_back(heap_parent);
                heap::register_object(heap_parent,std::nullopt,true,env);destination=heap::getAt(heap_parent);destination->setValue(old);
            } else if(mode>=2) {
                auto enclosing=blocks::create(1,program);destination=enclosing->slots[0];destination->setValue(old);
                enclosing_storage=std::make_shared<variable>(enclosing);enclosing->owner=enclosing_storage.get();
            } else destination=std::make_shared<variable>(old);
            old->owner=destination.get();if(mode<2)blocks::bind_local(destination,env);
            const int before_callbacks=callbacks;inspect=true;
            if(mode==0)blocks::store(destination,source_value,env);
            else rejects_containing([&]{blocks::store(destination,source_value,env);},
                                    mode==2||mode==4||mode==5||mode==7?"owner ended":"replacement destructor error",
                                    "buffer replacement preserves cleanup failures and detects a destroyed owner");
            if(mode<2) {
                auto installed=blocks::of(destination);
                check(installed&&installed!=old&&installed->buffer->length==1&&old->state==slot_block::status::dead,
                      "buffer replacement installs its candidate after all old destructors, even when one throws");
                check(callbacks==before_callbacks+2&&installed->owner_scope==env->ownership_id(),
                      "buffer replacement retains the scope cleanup registration and finishes every old destructor");
                blocks::drop(destination,env);
            } else if(mode>=7) {
                check(blocks::of(destination)==old&&old->state==slot_block::status::dead&&
                      reused_scope->owned_pointer.empty()&&callbacks==before_callbacks+3,
                      "retired scope receives no replacement, candidate cleanup finishes, and reused scope is untouched");
                destination->setValue(nullptr);reused_scope.reset();
            } else {
                check(destination->type_tag()==VOID_VALUE&&(mode>=4||enclosing_storage->type_tag()==VOID_VALUE)&&callbacks==before_callbacks+3,
                      "a destroyed assignment owner receives no replacement and candidate elements are finalized");
                if(mode>=4)rejects([&]{heap::getAt(heap_parent);},"delete and raw free retire a buffer assignment's heap parent");
            }
            blocks::finalize(source,env,true);
        }
    }
    auto exhausted=blocks::create_buffer(type,program);operation(2,exhausted,{number(3)});operation(6,exhausted,{number(3)});
    const auto exhausted_address=blocks::address_of(blocks::of(exhausted->slots[0]));
    program->active_calls=1;program->remaining_steps=0;
    rejects_containing([&]{blocks::finalize(exhausted,env,true);},"step limit","destructors share an exhausted caller budget");
    program->active_calls=0;
    check(exhausted->state==slot_block::status::dead&&!exhausted->buffer->slab,"budget exhaustion still releases an entire buffer slab");
    rejects([&]{blocks::resolve(exhausted_address);},"budget cleanup retires every element view address");
    program->destroy();
    {
        constexpr int assumed=0xbeef0002;
        auto consumer=load_raw(rhint("",{rfn(10,OBJECT_VALUE,rblock({rdef(0,bnew(class_context(2,assumed))),
            bop(2,rvar(0),{rc(n(1))}),bop(6,rvar(0),{rc(n(1))}),rreturn(rvar(0))}),1)},0,raw({assume("BUFFER_PLACEMENT",0xbeef)})),false);
        rejects_containing([&]{consumer->flush();},"Missing namespace hint","placement linking supports missing-library retries");
        auto library=rhint("BUFFER_PLACEMENT",{
            gfn(2,VOID_VALUE,rblock({assign(slot_at(rvar(0),0),rc(n(93))),assign(slot_at(rvar(0),1),rc(s("linked"))),
                rx(10,{rvar(0),rb(true),ri(0xbeef0003),rb(false),raw({})})}),0,0,{ADDRESS_VALUE},1),
            gfn(3,VOID_VALUE,rblock({}),0,0,{ADDRESS_VALUE},1)},0,raw({assume("BUFFER_PLACEMENT",0xbeef)}));
        insert_raw(consumer,library);auto value=consumer->invoke(10);auto buffer=blocks::of(value);
        check(integer(blocks::of(buffer->slots[0])->slots[0])==93&&buffer->buffer->element->placement_id==
              ((consumer->namespace_for_hint("BUFFER_PLACEMENT")<<16)|2),"flush relocates placement initializers and captures their mounted IDs");
        consumer->flush();blocks::finalize(buffer,consumer->baseEnv,true);consumer->destroy();
    }
    rejects([&]{load_raw(rmodule({rfn(10,OBJECT_VALUE,rreturn(bnew(cfixed(VOID_VALUE,0))))}))->invoke(10);},"void buffer elements rejected");
    rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,bop(5,rc(n(0)),{}))}));},"buffer wire validates operation arity");
    rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,bop(7,rc(n(0)),{}))}));},"buffer wire validates operation number");
}
void open_entry_tests() {
    // An open entry reproduces entry_scope exactly: two nested levels where
    // the inner one fails after noting a frame and the outer one catches,
    // then a successful level that clears the published trace.
    const auto frame=[](int id){trace_frame result;result.function_id=id;result.name="f"+std::to_string(id);return result;};
    const auto observe=[&](bool open) {
        std::vector<std::string> seen;
        clear_error_trace();
        const auto record=[&] {
            seen.push_back(format_error_trace(last_error_trace())+"|"+std::to_string(last_error_trace().frames.size()));
        };
        try {
            if(open) {
                auto outer=trace::open_entry::begin();
                try {
                    trace::note_frame(frame(1));
                    auto inner=trace::open_entry::begin();
                    try {trace::note_frame(frame(2));throw std::runtime_error("inner");}
                    catch(...){inner.fail();record();throw;}
                } catch(...){outer.fail();record();throw;}
            } else {
                try {
                    trace::entry_scope outer;outer.run([&] {
                        trace::note_frame(frame(1));
                        try {trace::entry_scope inner;inner.run([&]{trace::note_frame(frame(2));throw std::runtime_error("inner");});}
                        catch(...){record();throw;}
                    });
                } catch(...){record();throw;}
            }
        } catch(const std::exception& error) {record();seen.push_back(describe_error(error));}
        if(open){auto ok=trace::open_entry::begin();ok.succeed();}
        else {trace::entry_scope ok;ok.run([]{});}
        record();
        return seen;
    };
    const auto scoped=observe(false),opened=observe(true);
    check(scoped.size()==5&&scoped==opened,"open entries publish and clear as entry scopes do");
    check(opened[0]!="|0"&&opened[4]=="|0","an open entry's failure publishes and its success clears");
}
void runtime_trace_tests() {
    open_entry_tests();
    // These fixtures use the public exec format and host entry points. Locations
    // name the failing operation and its callers rather than decoder internals.
    auto debug=[](R function,const std::string& name,int line,int column,std::initializer_list<int> locations={}) {
        AbdStack quads;for(int value:locations)quads.vs.push_back(ri(value));
        return append_raw(replace_raw(function,8,rb(true)),raw({rs(name),ri(0),ri(line),ri(column),quads.toAbdValue()}));
    };
    auto files=[](R module){return replace_raw(module,10,raw({rs("trace.azs")}));};
    auto divide=[](){return rx(15,{rc(n(1)),rc(n(0))});};
    auto overflow=[](){return rx(12,{rc(n(std::numeric_limits<int>::max())),rc(n(1))});};
    constexpr int host_ns=0x6ce0,host_range=0x6ce00001,host_reenter=0x6ce00002,
                  host_swallow=0x6ce00003,host_cleanup=0x6ce00004,host_primary=0x6ce00005,
                  host_nonstandard=0x6ce00006,host_swallow_null=0x6ce00007,
                  host_swallow_wrong=0x6ce00008,host_swallow_fresh=0x6ce00009;
    std::shared_ptr<script> traced;
    auto host=std::make_shared<test_executor>(host_ns);
    host->functions[host_range]=std::make_shared<test_function>(VOID_VALUE,[](const std::vector<V>&)->V {
        throw std::out_of_range("host range");
    });
    host->functions[host_reenter]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&)->V {
        try{traced->invoke(10);}catch(const std::exception& e) {
            check(describe_error(e)=="Division by zero\n  at leaf (trace.azs:3:17)","a caught reentrant failure has its own trace");
        }
        traced->invoke(12);return std::make_shared<variable>(nullptr);
    });
    host->functions[host_swallow]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&)->V {
        try{traced->invoke(10);}catch(const std::exception&){}
        return std::make_shared<variable>(nullptr);
    });
    host->functions[host_cleanup]=std::make_shared<test_function>(VOID_VALUE,[](const std::vector<V>&)->V {
        throw std::runtime_error("cleanup failure");
    });
    host->functions[host_primary]=std::make_shared<test_function>(VOID_VALUE,[](const std::vector<V>&)->V {
        throw std::overflow_error("Integer overflow");
    });
    host->functions[host_nonstandard]=std::make_shared<test_function>(VOID_VALUE,[](const std::vector<V>&)->V {throw 73;});
    auto swallow_leaf=[&]{try{traced->invoke(10);}catch(const std::runtime_error&){}};
    host->functions[host_swallow_null]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&)->V {
        swallow_leaf();return nullptr;
    });
    host->functions[host_swallow_wrong]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>&)->V {
        swallow_leaf();return std::make_shared<variable>(std::string("wrong type"));
    });
    host->functions[host_swallow_fresh]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>&)->V {
        swallow_leaf();throw std::runtime_error("Division by zero");
    });
    registered_executor registered(host);
    auto signatures=raw({rsignature(host_range,VOID_VALUE),rsignature(host_reenter,VOID_VALUE),
        rsignature(host_swallow,VOID_VALUE),rsignature(host_cleanup,VOID_VALUE),rsignature(host_primary,VOID_VALUE,{OBJECT_VALUE}),
        rsignature(host_nonstandard,VOID_VALUE),rsignature(host_swallow_null,VOID_VALUE),
        rsignature(host_swallow_wrong,INT_VALUE),rsignature(host_swallow_fresh,VOID_VALUE)});
    traced=load_raw(files(rmodule({
        debug(rfn(10,INT_VALUE,rreturn(divide())),"leaf",3,1,{1,0,3,17}),
        debug(rfn(11,INT_VALUE,rreturn(rcall(10))),"outer",7,1,{1,0,8,12}),
        debug(rfn(12,INT_VALUE,rreturn(overflow())),"overflow",12,1,{1,0,12,19}),
        debug(rfn(13,INT_VALUE,rreturn(rc(n(7)))),"success",15,1),
        debug(rfn(14,VOID_VALUE,rcall(host_range)),"nativeCaller",20,1,{0,0,21,5}),
        debug(rfn(15,VOID_VALUE,rcall(host_reenter)),"reenter",25,1,{0,0,26,5}),
        debug(rfn(16,VOID_VALUE,rblock({rcall(host_swallow),divide()})),"afterSwallow",30,1,{2,0,32,9}),
        debug(rfn(17,INT_VALUE,rreturn(rcall(17))),"recursive",35,1,{1,0,36,12}),
        debug(rfn(18,VOID_VALUE,rcall(host_swallow_null)),"nullAfterSwallow",90,1),
        debug(rfn(19,INT_VALUE,rreturn(rcall(host_swallow_wrong))),"wrongAfterSwallow",92,1),
        debug(rfn(40,VOID_VALUE,rcall(host_swallow_fresh)),"freshAfterSwallow",94,1),
    },0,signatures)));
    clear_error_trace();
    check(last_error_trace().frames.empty()&&format_error_trace(last_error_trace()).empty(),"clearing a trace removes published frames");
    bool caught=false;
    try{traced->invoke(11);}catch(const std::runtime_error& e) {
        caught=true;
        check(std::string(e.what())=="Division by zero","script traces do not change the original exception message");
        check(describe_error(e)=="Division by zero\n  at leaf (trace.azs:3:17)\n  at outer (trace.azs:8:12)",
              "script traces list the failing operation then the caller's call site");
        const auto& trace=last_error_trace();
        check(trace.frames.size()==2&&trace.omitted_frames==0&&trace.frames[0].function_id==10&&
              !trace.frames[0].native&&trace.frames[0].name=="leaf"&&trace.frames[0].file=="trace.azs"&&
              trace.frames[0].line==3&&trace.frames[0].column==17,"structured traces expose stable function and source information");
    }
    check(caught,"a traced arithmetic failure preserves its C++ exception type");
    caught=false;
    try{traced->invoke(12);}catch(const std::overflow_error& e) {
        caught=true;
        check(describe_error(e)=="Integer overflow\n  at overflow (trace.azs:12:19)","overflow retains its specific exception type and source");
        check(describe_error(std::runtime_error(e.what()))==e.what(),"a different exception type cannot borrow a stale trace");
        check(describe_error(std::overflow_error("another overflow"))=="another overflow","a different message cannot borrow a stale trace");
    }
    check(caught,"overflow is still catchable as std::overflow_error");
    check(integer(traced->invoke(13))==7,"a successful entry remains usable after a traced failure");
    check(describe_error(std::overflow_error("Integer overflow"))=="Integer overflow","a successful entry suppresses an earlier trace");
    caught=false;
    try{traced->invoke(14);}catch(const std::out_of_range& e) {
        caught=true;
        check(describe_error(e)=="host range\n  at native function 0x6ce00001\n  at nativeCaller (trace.azs:21:5)",
              "native callback frames preserve the host exception type");
    }
    check(caught,"a native out_of_range exception keeps its dynamic type");
    caught=false;
    try{traced->invoke(15);}catch(const std::overflow_error& e) {
        caught=true;
        check(describe_error(e)=="Integer overflow\n  at overflow (trace.azs:12:19)\n  at native function 0x6ce00002\n  at reenter (trace.azs:26:5)",
              "a second nested host failure excludes a swallowed reentrant failure");
    }
    check(caught,"nested host reentry propagates the second failure");
    caught=false;
    try{traced->invoke(16);}catch(const std::runtime_error& e) {
        caught=true;
        check(describe_error(e)=="Division by zero\n  at afterSwallow (trace.azs:32:9)",
              "a successful host callback discards the failure it swallowed");
    }
    check(caught,"the caller can fail after a host callback swallowed a nested failure");
    auto fresh_callback_error=[&](int id,const std::string& expected) {
        bool failed=false;
        try{traced->invoke(id);}catch(const std::runtime_error& e) {
            failed=true;check(describe_error(e)==expected,"callback validation and fresh errors exclude swallowed script frames");
        }
        check(failed,"a callback failure remains visible after swallowing a script error");
    };
    fresh_callback_error(18,"External function 0x6ce00007 returned a null pointer\n  at native function 0x6ce00007\n  at nullAfterSwallow (trace.azs:90:1)");
    fresh_callback_error(19,"External function 0x6ce00008 return type mismatch: expected int, got string\n  at native function 0x6ce00008\n  at wrongAfterSwallow (trace.azs:92:1)");
    fresh_callback_error(40,"Division by zero\n  at native function 0x6ce00009\n  at freshAfterSwallow (trace.azs:94:1)");
    traced->max_call_depth=80;caught=false;
    try{traced->invoke(17);}catch(const std::runtime_error& e) {
        caught=true;const auto& trace=last_error_trace();
        check(std::string(e.what()).find("call depth limit")!=std::string::npos&&trace.frames.size()==64&&trace.omitted_frames>0,
              "recursive traces retain 64 frames and count the remainder");
        const auto suffix="\n  ... "+std::to_string(trace.omitted_frames)+" more frames";
        const auto formatted=format_error_trace(trace);
        check(formatted.ends_with(suffix)&&!formatted.ends_with('\n'),"truncated traces have a counted suffix and no trailing newline");
    }
    check(caught,"trace truncation preserves the recursion-limit failure");
    try{traced->invoke(12);}catch(const std::overflow_error&){}
    const auto main_trace=describe_error(std::overflow_error("Integer overflow"));
    std::string worker_trace,worker_failure;
    std::thread worker([&] {
        try {
            if(!last_error_trace().frames.empty())throw std::runtime_error("new thread inherited another thread's trace");
            auto program=load_raw(rmodule({rfn(10,INT_VALUE,rreturn(divide()))}));
            try{program->invoke(10);}catch(const std::runtime_error& e){worker_trace=describe_error(e);}
            clear_error_trace();program->destroy();
        }catch(const std::exception& e){worker_failure=e.what();}
    });
    worker.join();
    check(worker_failure.empty()&&worker_trace=="Division by zero\n  at function 0x0000000a","another thread starts empty and can publish a stripped trace");
    check(describe_error(std::overflow_error("Integer overflow"))==main_trace,"another thread cannot publish or clear the calling thread's trace");
    clear_error_trace();
    check(describe_error(std::overflow_error("Integer overflow"))=="Integer overflow","explicit clearing suppresses a matching published error");
    traced->destroy();

    auto bind=[](int destructor,R pointer){return rx(10,{pointer,rb(true),ri(destructor),rb(false),raw({})});};
    auto cleanup=load_raw(files(rmodule({
        debug(rfn(20,OBJECT_VALUE,rblock({rdef(0,rx(32,{ri(1)})),bind(30,rx(33,{rvar(0)})),rreturn(rvar(0))}),1),"temporary",40,1),
        debug(rfn(21,OBJECT_VALUE,rblock({rdef(0,rx(32,{ri(1)})),bind(31,rx(33,{rvar(0)})),rreturn(rvar(0))}),1),"firstObject",42,1),
        debug(rfn(22,VOID_VALUE,rcall(host_primary,{rcall(20)})),"temporaryFailure",45,1),
        debug(rfn(23,VOID_VALUE,rblock({rdef(0,rcall(20)),overflow()}),1),"scopeFailure",47,1,{3,0,49,9}),
        debug(rfn(24,VOID_VALUE,rx(11,{rvar(0)}),0,{ADDRESS_VALUE}),"deleteObject",51,1),
        debug(rfn(25,VOID_VALUE,rblock({rdef(0,rcall(20)),rdef(1,rcall(21))}),2),"scopeDestructors",53,1),
        debug(rfn(30,VOID_VALUE,rblock({rset(-2,rx(12,{rvar(-2),rc(n(1))})),rcall(host_cleanup)}),0,{ADDRESS_VALUE}),"cleanupDestructor",57,1),
        debug(rfn(31,VOID_VALUE,rcall(host_range),0,{ADDRESS_VALUE}),"firstDestructor",60,1),
        debug(rfn(32,VOID_VALUE,rcall(host_nonstandard),0,{ADDRESS_VALUE}),"nonStandardDestructor",61,1),
        debug(gfn(35,VOID_VALUE,rblock({rset(-1,rx(12,{rvar(-1),rc(n(1))})),
            rx(27,{rx(19,{rvar(-1),rc(n(2))}),overflow(),rb(false)}),bind(30,rvar(0))}),0,0,{ADDRESS_VALUE},1),"placement",63,1),
        debug(rfn(36,VOID_VALUE,rblock({rdef(0,bnew(class_context(1,35))),bop(2,rvar(0),{rc(n(2))}),bop(6,rvar(0),{rc(n(2))})}),1),"resizeFailure",68,1),
    },2,signatures)));
    cleanup->baseEnv->getVariable(-1)->setValue(0);cleanup->baseEnv->getVariable(-2)->setValue(0);
    auto primary_only=[&](const std::function<void()>& run,const std::string& expected) {
        bool primary=false;
        try{run();}catch(const std::overflow_error& e) {
            primary=true;const auto message=describe_error(e);
            check(std::string(e.what())=="Integer overflow"&&message.find(expected)!=std::string::npos&&
                  message.find("cleanupDestructor")==std::string::npos&&message.find("0x6ce00004")==std::string::npos,
                  "cleanup errors do not contaminate the first error's exception or frames");
        }
        check(primary,"cleanup preserves the first failure's dynamic type");
    };
    primary_only([&]{cleanup->invoke(22);},"temporaryFailure");
    primary_only([&]{cleanup->invoke(23);},"scopeFailure (trace.azs:49:9)");
    check(integer(cleanup->baseEnv->getVariable(-2))==2,"failing statement and scope cleanup both run their destructors");
    auto first_destructor_only=[&](const std::function<void()>& run) {
        bool primary=false;
        try{run();}catch(const std::out_of_range& e) {
            primary=true;const auto message=describe_error(e);
            check(std::string(e.what())=="host range"&&message.find("firstDestructor")!=std::string::npos&&
                  message.find("cleanupDestructor")==std::string::npos&&message.find("0x6ce00004")==std::string::npos,
                  "later destructors cannot replace the first destructor's error trace");
        }
        check(primary,"a destructor failure preserves its original exception type");
    };
    first_destructor_only([&]{cleanup->invoke(25);});
    auto nested=blocks::create(2,cleanup);
    for(int i=0;i<2;++i) {
        auto child=blocks::create(1,cleanup);child->destructor=i?31:30;
        nested->slots[i]->setValue(child);child->owner=nested->slots[i].get();
    }
    auto storage=std::make_shared<variable>(nested);nested->owner=storage.get();
    first_destructor_only([&]{blocks::drop(storage,cleanup->baseEnv);});
    check(storage->type_tag()==VOID_VALUE&&nested->state==slot_block::status::dead,"drop finishes every nested value after the first destructor failure");
    try{throw std::out_of_range("host range");}catch(...) {
        auto unrelated=pending_error::capture();bool recaught=false;
        try{unrelated.rethrow();}catch(const std::out_of_range& e) {
            recaught=true;
            check(describe_error(e)=="host range"&&last_error_trace().frames.empty(),
                  "a fresh host exception with matching type and message cannot inherit a prior direct cleanup trace");
        }
        check(recaught,"capturing a fresh host exception preserves its type");
    }
    auto nonstandard=blocks::create(1,cleanup);nonstandard->destructor=32;
    auto later=blocks::create(1,cleanup);later->destructor=30;
    nonstandard->slots[0]->setValue(later);later->owner=nonstandard->slots[0].get();
    auto nonstandard_storage=std::make_shared<variable>(nonstandard);nonstandard->owner=nonstandard_storage.get();
    pending_error saved_nonstandard;
    const std::string nonstandard_trace="  at native function 0x6ce00006\n  at nonStandardDestructor (trace.azs:61:1)";
    try{blocks::drop(nonstandard_storage,cleanup->baseEnv);}catch(int value) {
        check(value==73&&format_error_trace(last_error_trace())==nonstandard_trace,
              "direct host cleanup preserves a non-standard exception and excludes later cleanup frames");
        saved_nonstandard.capture_current();
    }
    check(static_cast<bool>(saved_nonstandard)&&nonstandard_storage->type_tag()==VOID_VALUE&&later->state==slot_block::status::dead,
          "direct cleanup finishes nested values before a non-standard failure is recaptured");
    for(int attempt=0;attempt<2;++attempt) {
        bool recaught=false;
        try{saved_nonstandard.rethrow();}catch(int value) {
            recaught=true;check(value==73&&format_error_trace(last_error_trace())==nonstandard_trace,
                  "rethrowing a recaptured non-standard failure retains its trace on every attempt");
        }
        check(recaught,"a recaptured non-standard exception keeps its original type");
    }
    auto pointer=heap::object_alloc(1);heap::set_owner(pointer,cleanup->baseEnv.get());
    heap::register_object(pointer,31,true,cleanup->baseEnv);
    auto member=blocks::create(1,cleanup);member->destructor=30;
    auto heap_slot=heap::getAt(pointer);heap_slot->setValue(member);member->owner=heap_slot.get();
    first_destructor_only([&]{cleanup->invoke(24,{std::make_shared<variable>(pointer)});});
    check(member->state==slot_block::status::dead,"heap object cleanup completes its value members after a destructor failure");
    rejects([&]{heap::getAt(pointer);},"a failing heap object destructor still releases storage");
    const auto before_resize=integer(cleanup->baseEnv->getVariable(-2));
    primary_only([&]{cleanup->invoke(36);},"placement");
    check(integer(cleanup->baseEnv->getVariable(-1))==2&&integer(cleanup->baseEnv->getVariable(-2))==before_resize+1,
          "buffer rollback cleans completed elements while preserving the construction failure");
    cleanup->destroy();

    auto shutdown=load_raw(files(rmodule({
        debug(rfn(1,VOID_VALUE,overflow()),"shutdown",75,1),
        debug(rfn(30,VOID_VALUE,rcall(host_cleanup),0,{ADDRESS_VALUE}),"cleanupDestructor",77,1),
    },0,signatures)));
    auto owned=blocks::create(1,shutdown);owned->destructor=30;
    auto owned_storage=std::make_shared<variable>(owned);owned->owner=owned_storage.get();blocks::bind_local(owned_storage,shutdown->baseEnv);
    primary_only([&]{shutdown->destroy();},"shutdown");
    check(shutdown->closed&&owned->state==slot_block::status::dead,"shutdown finishes owned values after a pre-destroy failure");
    clear_error_trace();
}
void virtual_tests() {
    auto entry=[](int id,std::initializer_list<R> contexts={}) {return raw({ri(id),raw(contexts)});};
    auto methods=[](R pointer,std::initializer_list<R> entries) {return rx(52,{std::move(pointer),raw(entries)});};
    auto dispatch=[](int slot,int type,std::initializer_list<R> arguments) {return rx(53,{ri(slot),ri(type),raw(arguments)});};
    auto field=[](R pointer){return rcall(0x0abd0006,{rx(9,{std::move(pointer),ri(0)})});};
    auto bind=[](int destructor){return rx(10,{rvar(0),rb(true),ri(destructor),rb(true),raw({})});};
    auto debug=[](R function,const std::string& name,int line) {
        return append_raw(replace_raw(function,8,rb(true)),raw({rs(name),ri(0),ri(line),ri(1),raw({})}));
    };
    constexpr int record_id=0x6cc20001;
    std::vector<int> phases;
    auto callback=std::make_shared<test_executor>(0x6cc2);
    callback->functions[record_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>& args) {
        phases.push_back(integer(args[0]));return std::make_shared<variable>(nullptr);
    });
    registered_executor registered(callback);
    const auto emit=[&](R expression){return rcall(record_id,{std::move(expression)});};
    const auto current=[&](){return dispatch(0,INT_VALUE,{rvar(0)});};
    auto module=rmodule({
        rfn(1000,INT_VALUE,rreturn(rx(12,{field(rvar(0)),rc(n(10))})),0,{ADDRESS_VALUE}),
        rfn(1001,INT_VALUE,rreturn(rx(12,{field(rvar(0)),rc(n(100))})),0,{ADDRESS_VALUE}),
        rfn(1002,INT_VALUE,rreturn(rx(12,{field(rvar(0)),rvar(1)})),0,{ADDRESS_VALUE,INT_VALUE}),
        rfn(1003,STRING_VALUE,rreturn(rc(s("method"))),0,{ADDRESS_VALUE}),
        gfn(1004,INT_VALUE,rreturn(rx(36,{cref(0)})),1,0,{ADDRESS_VALUE}),
        gfn(1005,ANY_VALUE,greturn(rx(37,{cref(0)}),cref(0)),1,0,{ADDRESS_VALUE}),
        rfn(1006,VOID_VALUE,rblock({methods(rvar(0),{entry(1001)}),emit(current()),
            methods(rvar(0),{entry(1000)}),emit(current())}),0,{ADDRESS_VALUE}),
        debug(rfn(1007,VOID_VALUE,rblock({methods(rvar(0),{entry(1008)}),
            rx(31,{rx(12,{rc(n(std::numeric_limits<int>::max())),rc(n(1))}),current(),rb(false)})}),0,{ADDRESS_VALUE}),"virtualDestructor",30),
        debug(rfn(1008,INT_VALUE,rreturn(rx(15,{rc(n(1)),rc(n(0))})),0,{ADDRESS_VALUE}),"cleanupVirtual",35),
        rfn(1010,ADDRESS_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(1))})),rx(6,{field(rvar(0)),rc(n(7))}),
            methods(rvar(0),{entry(1000)}),emit(current()),methods(rvar(0),{entry(1001)}),emit(current()),bind(1006),rx(8,{rvar(0)})}),1),
        rfn(1011,ADDRESS_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(1))})),methods(rvar(0),{entry(1001)}),bind(1007),rx(8,{rvar(0)})}),1),
        gfn(1012,VOID_VALUE,methods(rvar(0),{entry(1004,{cref(0)})}),1,0,{ADDRESS_VALUE}),
        rfn(1013,INT_VALUE,rblock({rset(-1,rx(12,{rx(14,{rvar(-1),rc(n(10))}),rvar(1)})),rreturn(rvar(1))}),0,{ADDRESS_VALUE,INT_VALUE}),
        rfn(1014,ADDRESS_VALUE,rblock({rset(-1,rc(n(1))),rreturn(rvar(0))}),0,{ADDRESS_VALUE}),
        rfn(1015,OBJECT_VALUE,rreturn(rx(32,{ri(1)}))),
        rfn(1016,INT_VALUE,rreturn(rc(n(1))),0,{INT_VALUE}),
        gfn(1017,VOID_VALUE,rblock({}),0,0,{ADDRESS_VALUE},1),
        rfn(1020,INT_VALUE,rreturn(dispatch(0,INT_VALUE,{rvar(0)})),0,{ADDRESS_VALUE}),
        rfn(1021,VOID_VALUE,methods(rvar(0),{}),0,{ADDRESS_VALUE}),
        rfn(1022,OBJECT_VALUE,rreturn(dispatch(0,ANY_VALUE,{rvar(0)})),0,{ADDRESS_VALUE}),
        rfn(1023,INT_VALUE,rreturn(dispatch(1,INT_VALUE,{rvar(0)})),0,{ADDRESS_VALUE}),
        rfn(1024,INT_VALUE,rreturn(dispatch(0,INT_VALUE,{rcall(1014,{rvar(0)}),rcall(1013,{rvar(0),rc(n(2))})})),0,{ADDRESS_VALUE}),
        rfn(1025,INT_VALUE,rreturn(dispatch(0,INT_VALUE,{rc(n(0))}))),
        rfn(1026,VOID_VALUE,methods(rc(n(0)),{})),
        rfn(1027,VOID_VALUE,methods(rvar(0),{entry(1000),entry(1001),entry(1002),entry(1003)}),0,{ADDRESS_VALUE}),
    },1,raw({rsignature(record_id,VOID_VALUE,{INT_VALUE})}));
    auto program=load_raw(replace_raw(module,10,raw({rs("virtual.azs")})));
    auto env=program->baseEnv;env->getVariable(-1)->setValue(0);
    const auto at=[](address value){return std::make_shared<variable>(value);};
    const auto table=[](std::initializer_list<bound_method> entries){return std::make_shared<const method_table>(entries);};
    auto base=table({{1000,{}}}),derived=table({{1001,{}}});
    const auto live=pointer(program->invoke(1010));
    check(phases==std::vector<int>({17,107})&&heap::method_table_count()==1,"constructor phase changes dispatch before object registration");
    check(integer(program->invoke(1020,{at(live)}))==107,"virtual calls resolve the current heap table");
    heap::set_methods(live,base,program);check(integer(program->invoke(1020,{at(live)}))==17,"set_methods replaces the active table");
    heap::set_methods(live,derived,program);
    auto records=heap::object_records();
    check(records.size()==1&&records[0].methods==derived&&heap::method_table_count()==1,"object_records reports table bindings without modifying them");
    auto literal=blocks::create(1,program);literal->slots[0]->setValue(7);heap::set_methods(blocks::address_of(literal),derived,program);
    auto literal_copy=blocks::copy(literal);
    check(literal_copy->methods==derived&&integer(program->invoke(1020,{at(blocks::address_of(literal_copy))}))==107,"literal copies preserve immutable method bindings");
    auto target=blocks::create(1,program);target->slots[0]->setValue(1);heap::set_methods(blocks::address_of(target),base,program);
    auto target_storage=std::make_shared<variable>(target);target->owner=target_storage.get();
    blocks::store(target_storage,std::make_shared<variable>(literal),env);
    check(target->methods==base&&integer(target->slots[0])==7&&integer(program->invoke(1020,{at(blocks::address_of(target))}))==17,
          "value assignment retains the target's table while copying fields");
    rejects_message([&]{program->invoke(1020,{at(address{})});},"Virtual call requires a non-null object","null virtual receivers reject");
    rejects_message([&]{program->invoke(1023,{at(live)});},"Virtual method slot is out of range","virtual slot bounds are checked");
    rejects_message([&]{program->invoke(1025);},"Virtual method receiver must be address","virtual receiver ABI is checked");
    rejects_message([&]{program->invoke(1026);},"Virtual method receiver must be address","set_methods receiver ABI is checked");
    heap::set_methods(live,table({{1003,{}}}),program);
    rejects_message([&]{program->invoke(1020,{at(live)});},"Virtual method return type mismatch: expected int, got string","virtual returns require the expected ABI");
    program->invoke(1021,{at(live)});
    check(heap::method_table_count()==0&&!heap::object_records()[0].methods,"empty set_methods clears table bindings and snapshot metadata");
    rejects_message([&]{program->invoke(1020,{at(live)});},"Object has no virtual method table","cleared tables reject dispatch");
    heap::set_methods(live,table({{1002,{}}}),program);
    check(integer(program->invoke(1024,{at(live)}))==9&&integer(env->getVariable(-1))==12,
          "virtual argument evaluation follows ordinary CALL left-to-right materialization");
    auto string_type=std::make_shared<type_context>();string_type->abi=STRING_VALUE;
    auto double_type=std::make_shared<type_context>();double_type->abi=DOUBLE_VALUE;
    invoke_bound_function(program,1012,env,{at(live)},{string_type});
    check(integer(program->invoke(1020,{at(live)}))==STRING_VALUE,"method tables retain bound hidden contexts after their installing call ends");
    const auto first_bound_table=heap::methods_at(live,program);
    invoke_bound_function(program,1012,env,{at(live)},{double_type});
    check(integer(program->invoke(1020,{at(live)}))==DOUBLE_VALUE&&first_bound_table->at(0).contexts[0]->abi==STRING_VALUE,
          "reference contexts are rebound instead of cached or mutated across calls");
    auto class_type=std::make_shared<type_context>();class_type->abi=OBJECT_VALUE;class_type->kind=3;class_type->factory_id=1015;
    heap::set_methods(live,table({{1005,{class_type}}}),program);
    auto returned=program->invoke(1022,{at(live)});
    check(returned->type_tag()==OBJECT_VALUE&&blocks::of(returned)->slots.size()==1,"ANY virtual return tags support runtime generic class results");
    auto foreign=load_raw(rmodule({rfn(1030,VOID_VALUE,rblock({})),rfn(1000,INT_VALUE,rreturn(rc(n(0))),0,{ADDRESS_VALUE})}));
    rejects_message([&]{heap::set_methods(live,base,foreign);},"Object belongs to another script","a foreign script cannot change heap methods");
    rejects_message([&]{heap::methods_at(live,foreign);},"Object belongs to another script","a foreign script cannot dispatch heap methods");
    rejects_message([&]{heap::methods_at(blocks::address_of(literal),foreign);},"Object belongs to another script","a foreign script cannot dispatch literal methods");
    foreign->destroy();
    const auto raw_pointer=heap::alloc(1);
    rejects_message([&]{heap::methods_at(raw_pointer,program);},"Virtual call requires a live object start","raw memory is not a virtual receiver");heap::free(raw_pointer);
    rejects_message([&]{heap::methods_at(address{live.value+1},program);},"Virtual call requires a live object start","heap interiors cannot dispatch");
    auto expired=blocks::address_of(blocks::create(1,program));
    rejects_message([&]{heap::methods_at(expired,program);},"Virtual call requires a live object start","expired literal virtual receivers reject");
    for(auto malformed:std::vector<method_table>{{{99999,{}}},{{1016,{}}},{{1017,{}}},{{1004,{}}},{{1000,{string_type}}}}) {
        bool caught=false;try {validate_method_table(malformed,program);}catch(const std::invalid_argument& error) {
            caught=true;check(typeid(error)==typeid(std::invalid_argument),"snapshot method validation preserves invalid_argument mapping");
        }
        check(caught,"invalid method signatures and hidden-context counts reject");
    }
    heap::set_methods(live,derived,program);
    heap::delete_object(live,env);
    check(phases==std::vector<int>({17,107,107,17})&&heap::method_table_count()==0,
          "destructor phase transitions dispatch while destroying and then release table bindings");
    rejects_message([&]{program->invoke(1020,{at(live)});},"Virtual call requires a live object start","deleted heap virtual receivers reject");
    const auto throwing=pointer(program->invoke(1011));
    bool first_error=false;try {heap::delete_object(throwing,env);}catch(const std::overflow_error& error) {
        first_error=true;check(describe_error(error)=="Integer overflow\n  at virtualDestructor (virtual.azs:30:1)",
            "virtual cleanup preserves the first exception and its trace");
    }
    check(first_error&&heap::method_table_count()==0,"throwing virtual cleanup releases heap tables");
    insert_raw(program,rmodule({gfn(1040,VOID_VALUE,rblock({rx(6,{field(rvar(0)),rc(n(7))}),
        methods(rvar(0),{entry(1001)}),rx(10,{rvar(0),rb(false),rb(false),raw({})})}),0,0,{ADDRESS_VALUE},1)}));
    auto element=std::make_shared<type_context>();element->abi=OBJECT_VALUE;element->kind=3;element->placement_id=1040;
    auto buffer=blocks::create_buffer(element,program);
    auto operate=[&](int operation,const std::shared_ptr<slot_block>& block,int size) {
        return blocks::buffer_operation(operation,block,{std::make_shared<variable>(size)},env);
    };
    operate(2,buffer,1);operate(6,buffer,1);
    auto view=blocks::of(buffer->slots[0]);const auto old_view=blocks::address_of(view);
    check(view->methods&&integer(program->invoke(1020,{at(old_view)}))==107,"placement constructors install tables on buffer class views");
    auto buffer_copy=blocks::copy(buffer);
    check(blocks::of(buffer_copy->slots[0])->methods==view->methods&&
          integer(program->invoke(1020,{at(blocks::address_of(blocks::of(buffer_copy->slots[0])))}))==107,
          "buffer copies retain each class view's method table");
    operate(2,buffer,4);auto moved_view=blocks::of(buffer->slots[0]);
    check(moved_view->methods&&integer(program->invoke(1020,{at(blocks::address_of(moved_view))}))==107,
          "buffer slab relocation transfers view method tables");
    rejects_message([&]{heap::methods_at(old_view,program);},"Virtual call requires a live object start","buffer reallocation expires former view receivers");
    blocks::validate_buffer(buffer);blocks::validate_buffer(buffer_copy);
    const auto snapshot=heap::object_alloc(1);heap::set_owner(snapshot,env.get());env->owned_pointer.push_back(snapshot);
    heap::getAt(snapshot)->setValue(7);heap::register_object(snapshot,std::nullopt,true,env);
    heap::set_methods(snapshot,table({{1004,{string_type}}}),program);
    std::vector<V> saved_slots;for(int i=0;i<heap::lenHeap();++i)saved_slots.push_back(heap::getSlot(i));
    std::vector<heap::heap_allocation> saved_ranges;for(int i=0;i<heap::lenAlloc();++i)saved_ranges.push_back(heap::allocAt(i));
    auto saved_objects=heap::object_records();auto saved_owned=env->owned_pointer;
    auto malformed_objects=saved_objects;malformed_objects[0].methods=table({{1016,{}}});
    rejects([&]{heap::restore(saved_slots,saved_ranges,malformed_objects,env,saved_owned);},"invalid restored method signatures reject before heap replacement");
    check(heap::method_table_count()==1&&integer(program->invoke(1020,{at(snapshot)}))==STRING_VALUE,
          "failed table restore leaves allocations and previous context bindings intact");
    heap::restore(saved_slots,saved_ranges,saved_objects,env,saved_owned);
    check(heap::method_table_count()==1&&integer(program->invoke(1020,{at(snapshot)}))==STRING_VALUE,
          "registered snapshot records restore method IDs and bound contexts atomically");
    heap::set_methods(snapshot,base,program);program->max_steps=5;
    rejects_containing([&]{program->invoke(1027,{at(snapshot)});},"step limit","set_methods charges steps for every entry before binding");
    program->max_steps=1000000;
    check(heap::methods_at(snapshot,program)==base,"budget failure leaves the previous table installed");
    program->invoke(1027,{at(snapshot)});const auto cached_table=heap::methods_at(snapshot,program);
    program->invoke(1027,{at(snapshot)});
    check(heap::methods_at(snapshot,program)==cached_table,"context-free set_methods nodes reuse immutable cached tables");
    program->max_steps=5;
    rejects_containing([&]{program->invoke(1027,{at(snapshot)});},"step limit","cached constant tables still charge every entry");
    program->max_steps=1000000;
    heap::restore(saved_slots,saved_ranges);
    check(heap::method_table_count()==0&&heap::object_records().empty()&&!heap::is_object_storage(snapshot),
          "the raw restore overload clears registrations, object tags and method tables");
    heap::free(snapshot);
    const auto orphan=heap::object_alloc(1);heap::set_owner(orphan,env.get());env->owned_pointer.push_back(orphan);
    heap::set_methods(orphan,base,program);
    check(heap::object_records().empty()&&heap::method_table_count()==1,
          "object_records stays pure when a construction table has no registration yet");
    heap::discard_script_objects(program.get());
    check(heap::method_table_count()==0,"script disposal erases orphan construction method tables");heap::free(orphan);
    program->destroy();check(heap::method_table_count()==0&&heap::lenAlloc()==0,"virtual tests release every heap method table");

    // Base and derived phases share one script even when their definitions live
    // in independently relocated hint modules loaded in either order.
    for(bool base_first:{false,true}) {
        phases.clear();
        const auto base_module=rhint("VirtualBase",{
            rfn(2,INT_VALUE,rreturn(rc(n(100))),0,{ADDRESS_VALUE}),
            rfn(4,VOID_VALUE,rblock({methods(rvar(0),{entry(static_cast<int>(0xbeef0002u))}),emit(current())}),0,{ADDRESS_VALUE}),
            rfn(5,VOID_VALUE,rblock({methods(rvar(0),{entry(static_cast<int>(0xbeef0002u))}),emit(current())}),0,{ADDRESS_VALUE}),
        },0,raw({assume("VirtualBase",0xbeef)}),raw({rsignature(record_id,VOID_VALUE,{INT_VALUE})}));
        const auto derived_module=rhint("VirtualDerived",{
            rfn(2,ADDRESS_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(1))})),
                rcall(static_cast<int>(0xbeef0004u),{rvar(0)}),methods(rvar(0),{entry(static_cast<int>(0xcafe0003u))}),emit(current()),
                rx(10,{rvar(0),rb(true),ri(static_cast<int>(0xcafe0006u)),rb(true),raw({})}),rx(8,{rvar(0)})}),1),
            rfn(3,INT_VALUE,rreturn(rc(n(200))),0,{ADDRESS_VALUE}),
            rfn(6,VOID_VALUE,rblock({emit(current()),rcall(static_cast<int>(0xbeef0005u),{rvar(0)})}),0,{ADDRESS_VALUE}),
        },0,raw({assume("VirtualBase",0xbeef),assume("VirtualDerived",0xcafe)}),raw({rsignature(record_id,VOID_VALUE,{INT_VALUE})}));
        auto cross=load_raw(base_first?base_module:derived_module,false);
        insert_raw(cross,base_first?derived_module:base_module,false);cross->flush();
        const int derived_namespace=cross->namespace_for_hint("VirtualDerived");
        const auto object=pointer(cross->invoke((derived_namespace<<16)|2));
        check(phases==std::vector<int>({100,200})&&heap::object_records()[0].methods->at(0).function_id==((derived_namespace<<16)|3),
              "cross-hint construction changes phases and relocates method IDs in either insertion order");
        heap::delete_object(object,cross->baseEnv);
        check(phases==std::vector<int>({100,200,200,100})&&heap::method_table_count()==0,
              "cross-hint destruction permits legitimate base phase transitions");
        cross->destroy();
    }
    const auto valid_set=methods(rc(ptr(0)),{entry(1100)});
    const auto valid_call=dispatch(0,INT_VALUE,{rc(ptr(0))});
    for(const auto& expression:{valid_set,valid_call}) {
        AbdStack fields(expression);
        for(std::size_t count=1;count<fields.vs.size();++count) {
            AbdStack truncated;truncated.vs.assign(fields.vs.begin(),fields.vs.begin()+static_cast<std::ptrdiff_t>(count));
            rejects([&]{load_raw(rmodule({rfn(1100,INT_VALUE,rreturn(rc(n(1))),0,{ADDRESS_VALUE}),rfn(1101,VOID_VALUE,truncated.toAbdValue())}));},
                    "virtual opcodes reject missing fields");
        }
    }
    for(const auto& malformed:std::vector<R>{append_raw(valid_set,ri(0)),replace_raw(valid_set,2,raw({raw({ri(1100)})})),
            replace_raw(valid_set,2,raw({append_raw(entry(1100),ri(0))})),append_raw(valid_call,ri(0)),
            replace_raw(valid_call,1,ri(-1)),replace_raw(valid_call,1,ri(static_cast<int>(MAX_VARIABLE_SLOTS))),
            replace_raw(valid_call,2,ri(-1)),replace_raw(valid_call,2,ri(9)),replace_raw(valid_call,3,raw({}))})
        rejects([&]{load_raw(rmodule({rfn(1100,INT_VALUE,rreturn(rc(n(1))),0,{ADDRESS_VALUE}),rfn(1101,VOID_VALUE,malformed)}));},
                "virtual opcode parser rejects malformed tables, slot, ABI and arity");
    for(const auto& target:std::vector<R>{rfn(1100,INT_VALUE,rreturn(rc(n(1))),0,{INT_VALUE}),
            gfn(1100,VOID_VALUE,rblock({}),0,0,{ADDRESS_VALUE},1),gfn(1100,INT_VALUE,rreturn(rc(n(1))),1,0,{ADDRESS_VALUE})})
        rejects([&]{load_raw(rmodule({target,rfn(1101,VOID_VALUE,valid_set)}));},"flush rejects invalid virtual receiver, internal entry and context count");
    rejects([&]{load_raw(rmodule({rfn(1101,VOID_VALUE,methods(rc(ptr(0)),{entry(99999)}))}));},"flush rejects missing virtual method entries");
    check(heap::method_table_count()==0&&heap::lenAlloc()==0,"all virtual errors leave the heap and method registry empty");
}
void checked_object_tests() {
    auto checked=[](R value,R context,std::initializer_list<int> types={}) {
        AbdStack tags;for(int type:types)tags.vs.push_back(ri(type));
        return rx(51,{std::move(value),std::move(context),tags.toAbdValue()});
    };
    const auto cp=cfixed(ADDRESS_VALUE,2),ci=cfixed(INT_VALUE,0);
    const auto cv=replace_raw(cfixed(OBJECT_VALUE,3),3,ri(2));
    constexpr int callback_id=0x6cc10001;
    auto callback=std::make_shared<test_executor>(0x6cc1);bool checked_destroying=false;
    callback->functions[callback_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>& args) {
        rejects_message([&]{heap::check_object(pointer(args[0]),{});},"Object check failed: object is being destroyed",
                        "checked reflection rejects an object during its destructor");
        checked_destroying=true;return std::make_shared<variable>(nullptr);
    });
    registered_executor registered(callback);
    auto pointer_function=append_raw(replace_raw(rfn(900,ADDRESS_VALUE,rreturn(checked(rvar(0),cp,{INT_VALUE,STRING_VALUE})),0,{ANY_VALUE}),8,rb(true)),
        raw({rs("checkedPointer"),ri(0),ri(10),ri(1),raw({ri(1),ri(0),ri(12),ri(7)})}));
    auto module=rmodule({pointer_function,
        rfn(901,OBJECT_VALUE,rreturn(checked(rvar(0),cv,{INT_VALUE,STRING_VALUE})),0,{ANY_VALUE}),
        rfn(902,OBJECT_VALUE,rreturn(checked(rvar(0),buffer_context(ci))),0,{ANY_VALUE}),
        gfn(903,ANY_VALUE,greturn(checked(rvar(0),cref(0)),cref(0)),1,0,{ANY_VALUE}),
        rfn(904,ADDRESS_VALUE,rreturn(checked(rvar(0),cp,{ANY_VALUE,STRING_VALUE})),0,{ANY_VALUE}),
        rfn(905,VOID_VALUE,rcall(callback_id,{rvar(0)}),0,{ADDRESS_VALUE}),
        gfn(906,VOID_VALUE,rblock({}),0,0,{ADDRESS_VALUE},1),
        rfn(907,ADDRESS_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(2))})),rreturn(checked(rvar(0),cp))}),1),
    },0,raw({rsignature(callback_id,VOID_VALUE,{ADDRESS_VALUE})}));
    auto program=load_raw(replace_raw(module,10,raw({rs("checks.azs")})));
    auto env=program->baseEnv;
    const auto at=[](address value){return std::make_shared<variable>(value);};
    const auto object=[&](int width,std::optional<int> destructor={}) {
        const address pointer=heap::object_alloc(width);heap::set_owner(pointer,env.get());env->owned_pointer.push_back(pointer);
        heap::register_object(pointer,destructor,true,env);return pointer;
    };
    const auto fails=[&](int id,V value,const std::string& message) {
        bool caught=false;try {program->invoke(id,{std::move(value)});}catch(const std::runtime_error& error) {
            caught=true;check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,"checked reflection raises the stable runtime error");
        }
        check(caught,"malformed reflected values fail before use");
    };
    check(pointer(program->invoke(900,{at(address{})}))==address{},"checked pointer results admit null");
    const auto live=object(3);heap::getAt(live)->setValue(7);heap::getAt(address{live.value+1})->setValue(std::string("field"));
    check(pointer(program->invoke(900,{at(live)}))==live,"checked pointers admit a registered wider object with compatible prefix");
    heap::getAt(live)->setValue(true);
    check(pointer(program->invoke(904,{at(live)}))==live,"unchecked ABI6 fields do not impose a tag");
    fails(900,at(live),"Object check failed: slot 0 has an incompatible type");heap::getAt(live)->setValue(7);
    const auto raw_pointer=heap::alloc(3);
    fails(900,at(raw_pointer),"Object check failed: address is not a live registered object start");heap::free(raw_pointer);
    const auto incomplete=heap::object_alloc(2);
    fails(900,at(incomplete),"Object check failed: address is not a live registered object start");heap::free(incomplete);
    const auto small=object(1);heap::getAt(small)->setValue(7);
    fails(900,at(small),"Object check failed: object has too few slots");
    fails(900,at(address{live.value+1}),"Object check failed: address is not a live registered object start");
    fails(900,std::make_shared<variable>(7),"Object check failed: value has an incompatible type");
    auto literal=blocks::create(3,program);literal->slots[0]->setValue(7);literal->slots[1]->setValue(std::string("field"));
    const auto literal_pointer=blocks::address_of(literal);
    check(pointer(program->invoke(900,{at(literal_pointer)}))==literal_pointer,"checked pointers admit live literal object starts");
    fails(900,at(address{literal_pointer.value+1}),"Object check failed: address is not a live class object start");
    literal->state=slot_block::status::destroying;
    fails(900,at(literal_pointer),"Object check failed: address is not a live class object start");literal->state=slot_block::status::live;
    const auto expired=blocks::address_of(blocks::create(2,program));
    fails(900,at(expired),"Object check failed: literal object has expired");
    auto value=blocks::create(2,program);value->slots[0]->setValue(7);value->slots[1]->setValue(std::string("field"));
    auto value_result=program->invoke(901,{std::make_shared<variable>(value)});
    check(blocks::of(value_result)->slots.size()==2&&integer(blocks::of(value_result)->slots[0])==7,"checked class values retain compatible slots and ownership");
    fails(901,std::make_shared<variable>(literal),"Object check failed: class value width mismatch");
    value->slots[1]->setValue(false);fails(901,std::make_shared<variable>(value),"Object check failed: slot 1 has an incompatible type");value->slots[1]->setValue(std::string("field"));
    auto int_type=std::make_shared<type_context>();int_type->abi=INT_VALUE;
    auto string_type=std::make_shared<type_context>();string_type->abi=STRING_VALUE;
    auto buffer=blocks::create_buffer(int_type,program);
    check(static_cast<bool>(blocks::of(program->invoke(902,{std::make_shared<variable>(buffer)}))->buffer),"checked buffers accept equal element layouts");
    fails(902,std::make_shared<variable>(blocks::create_buffer(string_type,program)),"Object check failed: buffer element context mismatch");
    fails(902,std::make_shared<variable>(value),"Object check failed: buffer element context mismatch");
    fails(900,at(blocks::address_of(buffer)),"Object check failed: address is not a live class object start");
    auto class_type=std::make_shared<type_context>();class_type->abi=OBJECT_VALUE;class_type->kind=3;class_type->width=2;
    const auto generic=[&](V input,const std::shared_ptr<const type_context>& type) {
        return invoke_bound_function(program,903,env,{std::move(input)},{type});
    };
    check(blocks::of(generic(std::make_shared<variable>(value),class_type))->slots.size()==2,
          "unknown generic class values use runtime context width even with an empty tag list");
    rejects_message([&]{generic(std::make_shared<variable>(literal),class_type);},"Object check failed: class value width mismatch","generic class values reject incompatible runtime widths");
    check(integer(generic(std::make_shared<variable>(19),int_type))==19,"generic primitive checks retain exact ABI identity");
    rejects_message([&]{generic(std::make_shared<variable>(false),int_type);},"Object check failed: value has an incompatible type","generic primitive ABI mismatch is checked");
    auto pointer_type=std::make_shared<type_context>();pointer_type->abi=ADDRESS_VALUE;pointer_type->kind=2;
    check(pointer(generic(at(small),pointer_type))==small,"unknown generic pointers check liveness without an unknown static layout");
    rejects_containing([&]{generic(at(address{live.value+1}),pointer_type);},"Object check failed:","generic pointer liveness rejects interior addresses");
    auto different_class_type=std::make_shared<type_context>(*class_type);different_class_type->placement_id=906;
    auto buffer_type=std::make_shared<type_context>();buffer_type->abi=OBJECT_VALUE;buffer_type->kind=4;buffer_type->element=class_type;
    auto same_layout=blocks::create_buffer(different_class_type,program);
    check(static_cast<bool>(blocks::of(generic(std::make_shared<variable>(same_layout),buffer_type))->buffer),
          "buffer checks compare layout and intentionally ignore placement identity");
    const auto destroying=object(2,905);heap::getAt(destroying)->setValue(7);heap::getAt(address{destroying.value+1})->setValue(std::string("field"));
    heap::delete_object(destroying,env);check(checked_destroying,"registered destroying objects cannot pass reflection checks");
    heap::delete_object(live,env);fails(900,at(live),"Object check failed: address is not a live registered object start");
    bool located=false;try {program->invoke(900,{std::make_shared<variable>(1)});}catch(const std::runtime_error& error) {
        located=true;check(describe_error(error)=="Object check failed: value has an incompatible type\n  at checkedPointer (checks.azs:12:7)","check_object errors use their own source position");
    }
    check(located,"checked pointer source error propagates");
    rejects_containing([&]{program->invoke(907);},"Object check failed:","reflection rejects objects before construction completes");
    program->destroy();check(heap::lenAlloc()==0,"rejected reflected results preserve cleanup and leave the heap empty");
    const auto valid=checked(rc(ptr(0)),cp,{INT_VALUE});
    AbdStack fields(valid);
    for(std::size_t count=1;count<fields.vs.size();++count) {
        AbdStack truncated;truncated.vs.assign(fields.vs.begin(),fields.vs.begin()+static_cast<std::ptrdiff_t>(count));
        rejects([&]{load_raw(rmodule({rfn(950,VOID_VALUE,truncated.toAbdValue())}));},"check_object rejects missing fields");
    }
    for(auto malformed:{append_raw(valid,ri(0)),replace_raw(valid,3,raw({ri(-1)})),replace_raw(valid,3,raw({ri(5)})),replace_raw(valid,3,raw({ri(9)})),
                        replace_raw(valid,3,raw({rb(true)})),replace_raw(valid,2,cref(0)),
                        checked(rx(32,{ri(2)}),cv),checked(rx(32,{ri(2)}),cv,{INT_VALUE})})
        rejects([&]{load_raw(rmodule({rfn(950,VOID_VALUE,malformed)}));},"check_object rejects malformed tags, contexts and extra fields");
}
void object_storage_tests() {
    auto field=[](R base,int offset=0){return rcall(0x0abd0006,{rx(9,{std::move(base),ri(offset)})});};
    auto program=load_raw(rmodule({
        rfn(800,INT_VALUE,rreturn(field(rvar(0),1)),0,{ADDRESS_VALUE}),
        rfn(801,VOID_VALUE,rx(6,{field(rvar(0),1),rvar(1)}),0,{ADDRESS_VALUE,INT_VALUE}),
        rfn(802,INT_VALUE,rreturn(rcall(0x0abd0006,{rvar(0)})),0,{ADDRESS_VALUE}),
        rfn(803,VOID_VALUE,rset(-1,rx(12,{rvar(-1),rc(n(1))})),0,{ADDRESS_VALUE}),
        rfn(804,VOID_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(2))})),rdef(1,rx(32,{ri(1)})),
            rx(10,{rx(33,{rvar(1)}),rb(true),ri(803),rb(false),raw({})}),
            rx(6,{field(rvar(0)),rvar(1)}),rx(35,{field(rvar(0))}),
            rx(10,{rvar(0),rb(false),rb(false),raw({})})}),2),
    },1));
    auto env=program->baseEnv;env->getVariable(-1)->setValue(0);
    const auto number=[](int value){return std::make_shared<variable>(value);};
    const auto pointer_value=[](address value){return std::make_shared<variable>(value);};
    check(getiFunction(0x0abd0014)->return_type()==ADDRESS_VALUE,"object_alloc reports address return type");
    auto object_allocator=getiFunction(0x0abd0014);
    check(pointer(object_allocator->invoke(env,{number(0)}))==address{},"zero object allocation is null and untagged");
    for(const auto& args:std::vector<std::vector<V>>{{},{number(1),number(2)},{nullptr},{pointer_value(address{1})},{std::make_shared<variable>(1.0)}})
        rejects_message([&]{object_allocator->invoke(env,args);},"object_alloc requires one int argument","object_alloc validates exact int arity");
    rejects([&]{object_allocator->invoke(env,{number(-1)});},"object_alloc rejects negative sizes");
    rejects([&]{object_allocator->invoke(env,{number(heap::max_slots)});},"object_alloc rejects excessive sizes");
    const address object=pointer(object_allocator->invoke(env,{number(3)}));
    heap::getAt(object)->setValue(11);heap::getAt(address{object.value+1})->setValue(22);
    const address raw=pointer(getiFunction(0x0abd0003)->invoke(env,{number(3)}));
    check(!heap::is_object_storage(address{})&&!heap::is_object_storage(raw),"ordinary raw allocations remain untagged");
    auto raw_get=getiFunction(0x0abd0006);
    raw_get->invoke(env,{pointer_value(raw)})->setValue(91);
    check(integer(raw_get->invoke(env,{pointer_value(raw)}))==91,"raw mem_get retains mutable raw storage access");
    rejects_message([&]{heap::object_address(raw,0);},"Object member access requires object storage","oa rejects raw allocation starts");
    rejects([&]{heap::register_object(raw,std::nullopt,false,env);},"raw allocations cannot be registered as objects");
    const std::vector<std::pair<int,std::string>> guards={{6,"mem_get"},{2,"mem_free"},{4,"make_free"},{5,"mem_send_up"}};
    auto reject_raw=[&](address at) {
        for(const auto& [id,name]:guards) {
            bool caught=false;
            try {getiFunction(0x0abd0000+id)->invoke(env,{pointer_value(at)});}
            catch(const std::runtime_error& error) {
                caught=true;check(typeid(error)==typeid(std::runtime_error)&&error.what()==name+" cannot access object storage; use typed member access",
                                  "raw object guards have stable runtime_error diagnostics");
            }
            check(caught,"every raw memory entry rejects object storage");
        }
    };
    for(int offset=0;offset<3;++offset) {
        const address at{object.value+static_cast<unsigned>(offset)};
        check(heap::is_object_storage(at),"the whole object range is tagged before registration");reject_raw(at);
    }
    check(heap::owner_of(object)==env->ownership_id()&&integer(heap::getAt(object))==11,"rejected construction-time raw access preserves owner and data");
    check(integer(program->invoke(800,{pointer_value(object)}))==22,"decoder member access works during construction");
    program->invoke(801,{pointer_value(object),number(42)});
    check(integer(program->invoke(800,{pointer_value(object)}))==42,"decoder member access is a writable mov target");
    rejects_message([&]{program->invoke(802,{pointer_value(object)});},"mem_get cannot access object storage; use typed member access","erasing a pointer retains the raw guard");
    rejects([&]{heap::object_address(object,3);},"oa bounds checks tagged ranges");
    rejects([&]{heap::object_address(address{object.value+1},0);},"oa requires an object start");
    rejects([&]{heap::register_object(object,800,true,env);},"invalid destructor prevents registration");
    check(heap::owner_of(object)==env->ownership_id()&&std::find(env->owned_pointer.begin(),env->owned_pointer.end(),object)!=env->owned_pointer.end(),
          "failed manual registration preserves construction ownership");
    heap::register_object(object,std::nullopt,true,env);
    check(!heap::owner_of(object)&&std::find(env->owned_pointer.begin(),env->owned_pointer.end(),object)==env->owned_pointer.end(),
          "successful manual registration detaches automatic ownership itself");
    reject_raw(object);reject_raw(address{object.value+2});
    std::vector<V> saved_slots;for(int i=0;i<heap::lenHeap();++i)saved_slots.push_back(heap::getSlot(i));
    std::vector<heap::heap_allocation> saved_ranges;for(int i=0;i<heap::lenAlloc();++i)saved_ranges.push_back(heap::allocAt(i));
    auto saved_objects=heap::object_records();auto saved_owned=env->owned_pointer;
    heap::clearHeap();check(!heap::is_object_storage(object),"clearing the heap discards all object tags");
    heap::restore(std::move(saved_slots),std::move(saved_ranges),std::move(saved_objects),env,std::move(saved_owned));
    check(heap::is_object_storage(object)&&heap::is_object_storage(address{object.value+2})&&!heap::is_object_storage(raw),
          "snapshot restore retags registered object ranges without tagging raw memory");
    reject_raw(address{object.value+1});
    heap::delete_object(object,env);check(!heap::is_object_storage(object),"delete clears an object's range tag");
    const address reused=heap::alloc(3);
    check(reused==object&&!heap::is_object_storage(reused),"same-address raw allocation does not inherit a deleted object's tag");
    raw_get->invoke(env,{pointer_value(reused)})->setValue(7);
    check(integer(raw_get->invoke(env,{pointer_value(reused)}))==7,"raw access works after tagged storage is released and reused");
    heap::free(reused);
    for(bool view:{false,true}) {
        auto literal=blocks::create(3,program);literal->is_view=view;
        const address start=blocks::address_of(literal);
        for(int offset=0;offset<3;++offset) {
            const address at{start.value+static_cast<unsigned>(offset)};
            check(heap::is_object_storage(at),"literal and view interiors are object storage");reject_raw(at);
        }
        heap::getAt(heap::object_address(start,1))->setValue(13);
        check(integer(program->invoke(800,{pointer_value(start)}))==13,"literal/view members use the same guarded decoder bypass");
    }
    program->invoke(804);
    check(integer(env->getVariable(-1))==2,"member-access nodes support drop and preserve copied object ownership");
    program->destroy();check(heap::lenAlloc()==0,"closed object-storage tests release all automatic and manual ranges");
    const auto construction=heap::object_alloc(2);heap::free(construction);
    const auto reused_construction=heap::alloc(2);
    check(reused_construction==construction&&!heap::is_object_storage(reused_construction),"failed-construction release also clears tags");
    heap::free(reused_construction);
}
void string_tests() {
    constexpr int builtin_base=0x0abd0000;
    auto function=rfn(700,STRING_VALUE,rreturn(rcall(builtin_base+0x0e,{rvar(0),rvar(1)})),0,{ANY_VALUE,ANY_VALUE});
    auto record=raw({rs("stringCall"),ri(0),ri(5),ri(1),raw({ri(1),ri(0),ri(7),ri(9)})});
    function=append_raw(replace_raw(function,8,rb(true)),record);
    auto program=load_raw(replace_raw(rmodule({function}),10,raw({rs("strings.azs")})));
    auto text=[](std::string value){return std::make_shared<variable>(std::move(value));};
    auto number=[](int value){return std::make_shared<variable>(value);};
    auto call=[&](int operation,std::vector<V> args){return program->invoke(builtin_base+operation,std::move(args));};
    auto string_result=[&](int operation,std::vector<V> args) {
        auto value=call(operation,std::move(args));check(value->type_tag()==STRING_VALUE,"string operations preserve their string result ABI");
        return value->get<std::string>();
    };
    auto boolean_result=[&](int operation,std::vector<V> args) {
        auto value=call(operation,std::move(args));check(value->type_tag()==BOOLEAN_VALUE,"string predicates return boolean values");
        return value->get<bool>();
    };
    auto failure=[&](int operation,std::vector<V> args,const std::string& message,bool range=false) {
        bool caught=false;
        try {call(operation,std::move(args));}
        catch(const std::exception& error) {
            caught=true;const auto& expected=range?typeid(std::out_of_range):typeid(std::invalid_argument);
            check(typeid(error)==expected&&error.what()==message,"string failures retain their exact bounds or argument exception type and message");
        }
        check(caught,"invalid string operations must throw");
    };
    const int result_types[]{INT_VALUE,INT_VALUE,STRING_VALUE,STRING_VALUE,INT_VALUE,STRING_VALUE,INT_VALUE,BOOLEAN_VALUE,BOOLEAN_VALUE};
    for(int operation=0x0b;operation<=0x13;++operation) {
        auto builtin=getiFunction(builtin_base+operation);
        check(builtin&&builtin->return_type()==result_types[operation-0x0b],"the dense string builtin range advertises correct result types");
    }
    const std::string cjk="\xe4\xb8\xad",emoji="\xf0\x9f\x98\x80",nul(1,'\0');
    const std::string sample="A"+cjk+emoji+nul+"B",repeated=sample+cjk+emoji;
    for(const auto& item:std::initializer_list<std::pair<std::string,int>>{
        {"",0},{"hello",5},{cjk+"\xe6\x96\x87",2},{emoji+emoji,2},{nul,1},{sample,5},{"e\xcc\x81",2},
    }) {
        check(integer(call(0x0b,{text(item.first)}))==item.second,"str_length counts Unicode scalars rather than bytes, graphemes or UTF16 units");
        check(integer(call(0x0c,{text(item.first)}))==static_cast<int>(item.first.size()),"str_byte_length includes every UTF8 byte and embedded NUL");
    }
    const std::vector<std::string> characters{"A",cjk,emoji,nul,"B"};const int codes[]{0x41,0x4e2d,0x1f600,0,0x42};
    for(int i=0;i<5;++i) {
        check(string_result(0x0e,{text(sample),number(i)})==characters[static_cast<std::size_t>(i)],"char_at returns exactly one complete code point");
        check(integer(call(0x0f,{text(sample),number(i)}))==codes[i],"code_at returns Unicode scalar values including NUL and non-BMP values");
    }
    for(const auto& item:std::initializer_list<std::pair<int,std::string>>{
        {0,nul},{0x7f,"\x7f"},{0x80,"\xc2\x80"},{0x7ff,"\xdf\xbf"},{0x800,"\xe0\xa0\x80"},
        {0xd7ff,"\xed\x9f\xbf"},{0xe000,"\xee\x80\x80"},{0xffff,"\xef\xbf\xbf"},
        {0x10000,"\xf0\x90\x80\x80"},{0x10ffff,"\xf4\x8f\xbf\xbf"},
    }) {
        auto encoded=string_result(0x10,{number(item.first)});
        check(encoded==item.second&&integer(call(0x0b,{text(encoded)}))==1&&integer(call(0x0f,{text(encoded),number(0)}))==item.first,
              "from_code emits the canonical UTF8 encoding at every scalar-width boundary");
    }
    struct slice {int begin,end;std::string expected;};
    for(const auto& item:std::initializer_list<slice>{{0,0,""},{0,5,sample},{1,3,cjk+emoji},{2,3,emoji},{3,4,nul},{4,5,"B"},{5,5,""}})
        check(string_result(0x0d,{text(sample),number(item.begin),number(item.end)})==item.expected,"substring uses half-open code-point indexes");
    check(integer(call(0x11,{text(repeated),text(cjk+emoji)}))==1&&
          integer(call(0x11,{text(repeated),text(cjk+emoji),number(2)}))==5&&
          integer(call(0x11,{text(repeated),text(cjk+emoji),number(6)}))==-1,"index_of returns code-point indexes and honors its optional start");
    check(integer(call(0x11,{text(sample),text(nul)}))==3&&integer(call(0x11,{text(sample),text("B")}))==4,
          "index_of handles NUL and does not count a non-BMP character as two positions");
    for(int from:{0,3,7})check(integer(call(0x11,{text(repeated),text(""),number(from)}))==from,"an empty needle matches the requested boundary including the end");
    check(integer(call(0x11,{text(""),text("")}))==0&&integer(call(0x11,{text(""),text("x")}))==-1&&
          integer(call(0x11,{text(repeated),text(emoji),number(7)}))==-1,"index_of handles empty text and a nonempty needle at the end");
    check(boolean_result(0x12,{text(sample),text("A"+cjk)})&&boolean_result(0x13,{text(sample),text(nul+"B")})&&
          boolean_result(0x12,{text(""),text("")})&&boolean_result(0x13,{text(""),text("")}),"prefix/suffix predicates support Unicode, NUL and empty strings");
    check(!boolean_result(0x12,{text(sample),text(emoji)})&&!boolean_result(0x13,{text(sample),text(cjk)})&&
          !boolean_result(0x12,{text("e\xcc\x81"),text("\xc3\xa9")}),"prefix/suffix checks compare code points without normalization");

    for(int operation:{0x0e,0x0f})for(int index:{-1,5})
        failure(operation,{text(sample),number(index)},"String index "+std::to_string(index)+" is outside length 5",true);
    failure(0x0e,{text(""),number(0)},"String index 0 is outside length 0",true);
    failure(0x0d,{text(sample),number(-1),number(2)},"String index -1 is outside length 5",true);
    failure(0x0d,{text(sample),number(0),number(6)},"String index 6 is outside length 5",true);
    failure(0x0d,{text(sample),number(3),number(2)},"String substring start 3 is after end 2",true);
    for(int from:{-1,8})failure(0x11,{text(repeated),text(""),number(from)},"String index "+std::to_string(from)+" is outside length 7",true);
    for(int code:{std::numeric_limits<int>::min(),-1,0xd800,0xdbff,0xdfff,0x110000,std::numeric_limits<int>::max()})
        failure(0x10,{number(code)},"Invalid Unicode code point: "+std::to_string(code));
    struct arguments {int operation;const char* name;std::vector<V> values;std::vector<int> types;};
    const std::vector<arguments> cases{
        {0x0b,"str_length",{text(sample)},{STRING_VALUE}},{0x0c,"str_byte_length",{text(sample)},{STRING_VALUE}},
        {0x0d,"str_substring",{text(sample),number(0),number(5)},{STRING_VALUE,INT_VALUE,INT_VALUE}},
        {0x0e,"str_char_at",{text(sample),number(0)},{STRING_VALUE,INT_VALUE}},
        {0x0f,"str_code_at",{text(sample),number(0)},{STRING_VALUE,INT_VALUE}},
        {0x10,"str_from_code",{number(0x41)},{INT_VALUE}},
        {0x11,"str_index_of",{text(sample),text(cjk)},{STRING_VALUE,STRING_VALUE}},
        {0x12,"str_starts_with",{text(sample),text("A")},{STRING_VALUE,STRING_VALUE}},
        {0x13,"str_ends_with",{text(sample),text("B")},{STRING_VALUE,STRING_VALUE}},
    };
    for(const auto& item:cases) {
        std::string arity=std::string(item.name)+" requires "+(item.operation==0x11?std::string("2 or 3 arguments"):
            std::to_string(item.values.size())+(item.values.size()==1?" argument":" arguments"));
        auto missing=item.values;missing.pop_back();failure(item.operation,missing,arity);
        auto extra=item.values;extra.push_back(number(0));if(item.operation==0x11)extra.push_back(number(0));failure(item.operation,extra,arity);
        for(std::size_t i=0;i<item.values.size();++i) {
            const std::string message=std::string(item.name)+" argument "+std::to_string(i+1)+" must be "+(item.types[i]==INT_VALUE?"int":"string");
            auto wrong=item.values;wrong[i]=item.types[i]==INT_VALUE?text("1"):number(1);failure(item.operation,wrong,message);
            wrong[i]=nullptr;failure(item.operation,wrong,message);
        }
    }
    failure(0x11,{text(sample),text(""),text("0")},"str_index_of argument 3 must be int");
    const std::vector<std::string> malformed{
        "\x80","\xc0\x80","\xc1\xbf","\xc2","\xe0\x80\x80","\xed\xa0\x80", "\xf0\x80\x80\x80",
        "\xf4\x90\x80\x80","\xf5\x80\x80\x80","\xff","\xe2\x28\xa1","ok\xc2",
    };
    for(const auto& invalid:malformed) {
        for(const auto& item:cases)if(item.operation!=0x10) {
            auto args=item.values;args[0]=text(invalid);failure(item.operation,args,"Invalid UTF-8 string");
        }
        for(int operation:{0x11,0x12,0x13})failure(operation,{text(""),text(invalid)},"Invalid UTF-8 string");
    }
    check(value_to_string(program->invoke(700,{text(sample),number(2)}))==emoji,"script builtin calls return intact non-BMP UTF8");
    bool caught=false;
    try {program->invoke(700,{text(sample),number(5)});}
    catch(const std::out_of_range& error) {
        caught=true;check(typeid(error)==typeid(std::out_of_range)&&
            describe_error(error)=="String index 5 is outside length 5\n  at stringCall (strings.azs:7:9)",
            "string index failures keep their JNI-compatible exception type and script trace");
    }
    check(caught,"script string index failures propagate");
    program->destroy();
}
void select_tests() {
    auto select=[](R condition,R when_true,R when_false){return rx(50,{std::move(condition),std::move(when_true),std::move(when_false)});};
    auto divide=[](){return rx(15,{rc(n(1)),rc(n(0))});};
    auto overflow=[](){return rx(12,{rc(n(std::numeric_limits<int>::max())),rc(n(1))});};
    auto literal_address=[](R value){return rx(33,{std::move(value)});};
    auto field=[](R value){return rcall(0x0abd0006,{rx(9,{std::move(value),ri(0)})});};
    auto bind=[](R value,int destructor){return rx(10,{std::move(value),rb(true),ri(destructor),rb(false),raw({})});};
    auto lifetime_body=[](std::initializer_list<R> body) {
        AbdStack statements;statements.vs={rset(-2,rc(n(0))),rset(-3,rc(n(0)))};
        statements.vs.insert(statements.vs.end(),body.begin(),body.end());return rx(1,{statements.toAbdValue()});
    };
    auto append_destructor=[&](){return rset(-2,rx(12,{rx(14,{rvar(-2),rc(n(10))}),field(rvar(0))}));};
    auto factory=[&](int id,int destructor,bool pointer) {
        R allocation=pointer?rcall(0x0abd0014,{rc(n(1))}):rx(32,{ri(1)});
        R address=pointer?rvar(1):literal_address(rvar(1));
        R returned=pointer?rx(8,{rvar(1)}):rreturn(rvar(1));
        return rfn(id,pointer?ADDRESS_VALUE:OBJECT_VALUE,rblock({
            rset(-3,rx(12,{rvar(-3),rc(n(1))})),rdef(1,allocation),rx(6,{field(address),rvar(0)}),bind(address,destructor),returned,
        }),1,{INT_VALUE});
    };
    auto chosen_object=[&](int id){return select(rvar(0),rcall(id,{rc(n(7))}),rcall(id,{rc(n(9))}));};
    auto debug=[](R function,const char* name,int line,std::initializer_list<int> positions={}) {
        AbdStack locations;for(int field:positions)locations.vs.push_back(ri(field));
        return append_raw(replace_raw(function,8,rb(true)),raw({rs(name),ri(0),ri(line),ri(1),locations.toAbdValue()}));
    };
    auto module=rmodule({
        rfn(0,VOID_VALUE,rblock({rset(-1,rc(n(0))),rset(-2,rc(n(0))),rset(-3,rc(n(0)))})),
        rfn(600,INT_VALUE,rreturn(select(rvar(0),rc(n(7)),divide())),0,{ANY_VALUE}),
        rfn(601,INT_VALUE,rreturn(select(rvar(0),divide(),rc(n(9)))),0,{ANY_VALUE}),
        rfn(602,INT_VALUE,rblock({rset(-1,rc(n(0))),rreturn(select(rcall(620,{rvar(0)}),
            rcall(621,{rc(n(2))}),rcall(621,{rc(n(3))})))}),0,{BOOLEAN_VALUE}),
        rfn(603,INT_VALUE,rblock({select(rvar(0),rdef(1,rc(n(7))),rdef(1,rc(n(9)))),rreturn(rvar(1))}),1,{BOOLEAN_VALUE}),
        rfn(606,OBJECT_VALUE,lifetime_body({rreturn(chosen_object(630))}),0,{ANY_VALUE}),
        rfn(610,INT_VALUE,lifetime_body({rdef(1,rx(12,{field(literal_address(chosen_object(630))),rvar(-2)})),
            rreturn(rvar(1))}),1,{BOOLEAN_VALUE}),
        rfn(611,INT_VALUE,lifetime_body({rdef(1,chosen_object(630)),rreturn(rx(12,{
            field(literal_address(rvar(1))),rx(14,{rvar(-2),rc(n(100))})}))}),1,{BOOLEAN_VALUE}),
        rfn(612,INT_VALUE,lifetime_body({rdef(1,chosen_object(640)),rreturn(rx(12,{
            field(rvar(1)),rx(14,{rvar(-2),rc(n(100))})}))}),1,{BOOLEAN_VALUE}),
        rfn(613,INT_VALUE,lifetime_body({rdef(0,rx(12,{select(rx(19,{
            field(literal_address(rcall(630,{rc(n(1))}))),rc(n(1))}),rc(n(7)),rc(n(9))),rvar(-2)})),
            rreturn(rvar(0))}),1),
        debug(rfn(615,VOID_VALUE,lifetime_body({rx(12,{field(literal_address(chosen_object(660))),overflow()})}),0,{BOOLEAN_VALUE}),
            "selectedCleanup",30),
        rfn(620,BOOLEAN_VALUE,rblock({rset(-1,rx(12,{rx(14,{rvar(-1),rc(n(10))}),rc(n(1))})),rreturn(rvar(0))}),0,{BOOLEAN_VALUE}),
        rfn(621,INT_VALUE,rblock({rset(-1,rx(12,{rx(14,{rvar(-1),rc(n(10))}),rvar(0)})),rreturn(rvar(0))}),0,{INT_VALUE}),
        factory(630,631,false),rfn(631,VOID_VALUE,rblock({append_destructor()}),0,{ADDRESS_VALUE}),factory(640,631,true),
        debug(rfn(650,INT_VALUE,rreturn(select(rvar(0),divide(),rc(n(9)))),0,{ANY_VALUE}),
            "chosenThen",9,{1,0,9,5,2,0,9,7,3,0,9,11}),
        debug(rfn(651,INT_VALUE,rreturn(select(rvar(0),rc(n(7)),divide())),0,{ANY_VALUE}),
            "chosenElse",10,{1,0,10,5,2,0,10,7,4,0,10,25}),
        factory(660,661,false),debug(rfn(661,VOID_VALUE,rblock({append_destructor(),divide()}),0,{ADDRESS_VALUE}),"cleanupDestructor",40),
    },3);
    auto program=load_raw(replace_raw(module,10,raw({rs("select.azs")})));
    auto input=[](bool value){return std::make_shared<variable>(value);};
    auto runtime_failure=[&](int id,V condition,const std::string& message) {
        bool caught=false;
        try {program->invoke(id,{std::move(condition)});}
        catch(const std::runtime_error& error) {
            caught=true;check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,
                "select preserves the chosen failure type and message");
        }
        check(caught,"a selected error or invalid condition must throw");
    };
    check(integer(program->invoke(600,{input(true)}))==7&&integer(program->invoke(601,{input(false)}))==9,
          "either unchosen select arm may throw without being evaluated");
    runtime_failure(600,input(false),"Division by zero");runtime_failure(601,input(true),"Division by zero");
    for(bool chosen:{false,true}) {
        const int value=chosen?7:9;
        check(integer(program->invoke(602,{input(chosen)}))==(chosen?2:3)&&
              integer(program->baseEnv->getVariable(-1))==(chosen?12:13),"select evaluates its condition once before exactly one arm");
        check(integer(program->invoke(603,{input(chosen)}))==value,"selected declarations remain in the enclosing environment");
        for(int id:{610,611,612}) {
            const auto allocations=heap::lenAlloc();
            check(integer(program->invoke(id,{input(chosen)}))==value,"chosen objects survive use in their enclosing statement or block");
            check(integer(program->baseEnv->getVariable(-2))==value&&integer(program->baseEnv->getVariable(-3))==1,
                  "only the chosen factory allocates and its value is destroyed once at the enclosing lifetime end");
            check(heap::lenAlloc()==allocations,"select automatic-pointer ownership leaves no heap allocation behind");
        }
        bool caught=false;
        try {program->invoke(615,{input(chosen)});}
        catch(const std::overflow_error& error) {
            caught=true;check(std::string(error.what())=="Integer overflow"&&
                describe_error(error)=="Integer overflow\n  at selectedCleanup (select.azs:30:1)",
                "an enclosing error retains its type and trace over a chosen temporary destructor failure");
        }
        check(caught&&integer(program->baseEnv->getVariable(-2))==value&&integer(program->baseEnv->getVariable(-3))==1,
              "selected cleanup finishes once while preserving the first error");
    }
    check(integer(program->invoke(613))==7&&integer(program->baseEnv->getVariable(-2))==1&&
          integer(program->baseEnv->getVariable(-3))==1,"condition temporaries survive until the enclosing expression statement ends");
    struct invalid_condition {const char* type;V value;};
    for(const auto& condition:std::vector<invalid_condition>{
        {"int",std::make_shared<variable>(0)},{"int",std::make_shared<variable>(1)},
        {"float",std::make_shared<variable>(0.0f)},{"double",std::make_shared<variable>(1.0)},
        {"string",std::make_shared<variable>("x")},{"void",std::make_shared<variable>(nullptr)},
        {"address",std::make_shared<variable>(address{})},{"object",std::make_shared<variable>(blocks::create(1,program))},
    }) {
        runtime_failure(606,condition.value,"Condition must be boolean, got "+std::string(condition.type));
        check(integer(program->baseEnv->getVariable(-2))==0&&integer(program->baseEnv->getVariable(-3))==0,
              "invalid select conditions do not execute or allocate either arm");
    }
    for(const auto& item:std::initializer_list<std::pair<int,std::pair<V,std::string>>>{
        {650,{input(true),"Division by zero\n  at chosenThen (select.azs:9:11)"}},
        {651,{input(false),"Division by zero\n  at chosenElse (select.azs:10:25)"}},
        {650,{std::make_shared<variable>(1),"Condition must be boolean, got int\n  at chosenThen (select.azs:9:7)"}},
    }) {
        bool caught=false;
        try {program->invoke(item.first,{item.second.first});}
        catch(const std::runtime_error& error) {
            caught=true;check(describe_error(error)==item.second.second,"select reports the chosen arm or condition source position");
        }
        check(caught,"select debug probes must throw");
    }
    for(auto malformed:{rx(50,{rc(boolean(true))}),rx(50,{rc(boolean(true)),rc(n(1))}),
        rx(50,{rc(boolean(true)),rc(n(1)),rc(n(2)),rc(n(3))})})
        rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,malformed)}));},"select records require a condition and exactly two arms");
    program->destroy();
}
void checked_return_tests() {
    AbdStack functions;
    for(int target:{INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,BOOLEAN_VALUE,VOID_VALUE,ADDRESS_VALUE,OBJECT_VALUE})
        functions.vs.push_back(rfn(400+target,target,rreturn(rvar(0)),0,{ANY_VALUE}));
    // ANY returns require a generic context even when the body only forwards a value.
    functions.vs.push_back(gfn(400+ANY_VALUE,ANY_VALUE,rreturn(rvar(0)),1,0,{ANY_VALUE}));
    functions.vs.push_back(rfn(410,INT_VALUE,rblock({})));
    functions.vs.push_back(gfn(500,ANY_VALUE,greturn(rvar(0),cref(0)),1,0,{ANY_VALUE}));
    for(int target:{INT_VALUE,FLOAT_VALUE,DOUBLE_VALUE})
        functions.vs.push_back(rfn(510+target,target,rreturn(gcall(500,{rvar(0)},{cfixed(target,0)})),0,{ANY_VALUE}));
    functions.vs.push_back(rfn(520,DOUBLE_VALUE,rreturn(gcall(500,{rvar(0)},{cfixed(INT_VALUE,0)})),0,{ANY_VALUE}));
    auto debug=[](R function,const char* name,int line,std::initializer_list<int> positions) {
        AbdStack locations;for(int field:positions)locations.vs.push_back(ri(field));
        return append_raw(replace_raw(function,8,rb(true)),raw({rs(name),ri(0),ri(line),ri(1),locations.toAbdValue()}));
    };
    functions.vs.push_back(debug(rfn(530,INT_VALUE,rreturn(rc(std::make_shared<DoubleAbdValue>(1.0)))),
        "ordinaryNarrow",3,{0,0,5,7,1,0,5,19}));
    functions.vs.push_back(debug(gfn(531,INT_VALUE,greturn(rc(std::make_shared<DoubleAbdValue>(1.0)),cref(0)),1),
        "genericNarrow",11,{0,0,13,7,1,0,13,19}));
    functions.vs.push_back(debug(rfn(532,INT_VALUE,rreturn(gcall(531,{}, {cfixed(DOUBLE_VALUE,0)}))),
        "genericCaller",20,{0,0,21,3,1,0,21,10}));
    auto module=replace_raw(replace_raw(rmodule({}),7,functions.toAbdValue()),10,raw({rs("returns.azs")}));
    auto program=load_raw(module);
    auto dynamic_context=std::make_shared<type_context>();dynamic_context->abi=INT_VALUE;
    auto dynamic_return=[&](V value) {
        return invoke_bound_function(program,400+ANY_VALUE,program->baseEnv,{std::move(value)},{dynamic_context});
    };
    auto runtime_failure=[&](int id,std::vector<V> args,const std::string& message) {
        bool caught=false;
        try {program->invoke(id,std::move(args));}
        catch(const std::runtime_error& error) {
            caught=true;
            check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,
                  "return type failures retain runtime_error and exact expected/actual tags");
        }
        check(caught,"a disallowed return conversion must fail");
    };
    struct numeric_value {const char* type;V value;};
    const std::vector<numeric_value> numeric{
        {"int",std::make_shared<variable>(std::numeric_limits<int>::min())},
        {"int",std::make_shared<variable>(0)},{"int",std::make_shared<variable>(std::numeric_limits<int>::max())},
        {"float",std::make_shared<variable>(1.0f)},{"float",std::make_shared<variable>(1.25f)},
        {"float",std::make_shared<variable>(-0.0f)},{"float",std::make_shared<variable>(std::numeric_limits<float>::max())},
        {"float",std::make_shared<variable>(std::numeric_limits<float>::denorm_min())},
        {"double",std::make_shared<variable>(1.0)},{"double",std::make_shared<variable>(1.25)},
        {"double",std::make_shared<variable>(-0.0)},{"double",std::make_shared<variable>(std::numeric_limits<double>::max())},
        {"double",std::make_shared<variable>(std::numeric_limits<double>::denorm_min())},
    };
    auto numeric_result=[](const V& value) {
        if(value->type_tag()==INT_VALUE)return static_cast<double>(value->get<int>());
        if(value->type_tag()==FLOAT_VALUE)return static_cast<double>(value->get<float>());
        return value->get<double>();
    };
    for(const auto& item:numeric)for(const auto& target:std::initializer_list<std::pair<int,const char*>>{
        {INT_VALUE,"int"},{FLOAT_VALUE,"float"},{DOUBLE_VALUE,"double"},
    }) {
        const bool widening=(item.value->type_tag()==INT_VALUE&&(target.first==FLOAT_VALUE||target.first==DOUBLE_VALUE))||
                            (item.value->type_tag()==FLOAT_VALUE&&target.first==DOUBLE_VALUE);
        if(item.value->type_tag()==target.first||widening) {
            auto result=program->invoke(400+target.first,{item.value});
            const double input=numeric_result(item.value);
            const double expected=target.first==FLOAT_VALUE?static_cast<double>(static_cast<float>(input)):input;
            check(result->type_tag()==target.first&&numeric_result(result)==expected,
                  "numeric returns preserve exact types or apply only the approved widenings");
            if(input==0.0)check(std::signbit(numeric_result(result))==std::signbit(input),"allowed numeric returns preserve signed zero");
        } else runtime_failure(400+target.first,{item.value},
            "Function return type mismatch: expected "+std::string(target.second)+", got "+item.type);
        auto dynamic=dynamic_return(item.value);
        check(dynamic->type_tag()==item.value->type_tag()&&numeric_result(dynamic)==numeric_result(item.value),"any returns preserve the actual numeric ABI");
    }
    runtime_failure(400+DOUBLE_VALUE,{std::make_shared<variable>(std::numeric_limits<float>::infinity())},"Non-finite return value");
    runtime_failure(400+DOUBLE_VALUE,{std::make_shared<variable>(std::numeric_limits<float>::quiet_NaN())},"Non-finite return value");
    auto nonfinite=std::make_shared<variable>(std::numeric_limits<double>::infinity());
    check(std::isinf(numeric_result(program->invoke(400+DOUBLE_VALUE,{nonfinite})))&&
          std::isinf(numeric_result(dynamic_return(nonfinite))),"same-type and any return handling remains unchanged for host nonfinite values");
    for(const auto& value:std::vector<V>{std::make_shared<variable>("same"),std::make_shared<variable>(true),
        std::make_shared<variable>(address{std::numeric_limits<std::uint64_t>::max()}),std::make_shared<variable>(nullptr)}) {
        auto exact=program->invoke(400+value->type_tag(),{value}),dynamic=dynamic_return(value);
        check(exact->type_tag()==value->type_tag()&&dynamic->type_tag()==value->type_tag()&&value_to_string(exact)==value_to_string(value)&&
              value_to_string(dynamic)==value_to_string(value),"scalar identity, void and any return behavior is unchanged");
    }
    runtime_failure(400+VOID_VALUE,{std::make_shared<variable>(1)},"Void function returned a value");
    runtime_failure(400+INT_VALUE,{std::make_shared<variable>("wrong")},"Function return type mismatch: expected int, got string");
    runtime_failure(410,{},"Function return type mismatch: expected int, got void");
    auto object=blocks::create(1,program);object->slots[0]->setValue(42);
    for(int target:{OBJECT_VALUE,ANY_VALUE}) {
        auto value=std::make_shared<variable>(object);
        auto result=target==ANY_VALUE?dynamic_return(value):program->invoke(400+target,{value});auto returned=blocks::of(result);
        check(returned&&returned!=object&&returned->state==slot_block::status::live&&integer(returned->slots[0])==42,
              "object and any results retain their established value ownership semantics");
        blocks::finalize(returned,program->baseEnv,true);
    }
    for(const auto& item:numeric) {
        auto result=program->invoke(510+item.value->type_tag(),{item.value});
        check(result->type_tag()==item.value->type_tag()&&numeric_result(result)==numeric_result(item.value),"generic return_typed retains exact context ABI values");
    }
    check(numeric_result(program->invoke(520,{std::make_shared<variable>(7)}))==7.0,"a caller may widen an exactly checked generic int result");
    runtime_failure(510+INT_VALUE,{std::make_shared<variable>(1.0f)},"Generic value type mismatch");
    runtime_failure(510+FLOAT_VALUE,{std::make_shared<variable>(1)},"Generic value type mismatch");
    for(const auto& item:std::initializer_list<std::pair<int,std::string>>{
        {530,"Function return type mismatch: expected int, got double\n  at ordinaryNarrow (returns.azs:5:7)"},
        {532,"Function return type mismatch: expected int, got double\n  at genericNarrow (returns.azs:13:7)\n  at genericCaller (returns.azs:21:10)"},
    }) {
        bool caught=false;
        try {program->invoke(item.first);}
        catch(const std::runtime_error& error) {
            caught=true;check(typeid(error)==typeid(std::runtime_error)&&describe_error(error)==item.second,
                "ordinary and generic return conversions report the return statement location");
        }
        check(caught,"return-conversion debug probes must throw");
    }
    program->destroy();
}
void conversion_tests() {
    auto convert=[](int target,R value){return rx(49,{ri(target),std::move(value)});};
    AbdStack functions;
    for(int target:{INT_VALUE,STRING_VALUE,FLOAT_VALUE,DOUBLE_VALUE,ADDRESS_VALUE})
        functions.vs.push_back(rfn(300+target,target,rreturn(convert(target,rvar(0))),0,{ANY_VALUE}));
    functions.vs.push_back(rfn(320,INT_VALUE,rblock({rset(-1,rc(n(0))),rreturn(convert(INT_VALUE,rcall(321)))})));
    functions.vs.push_back(rfn(321,DOUBLE_VALUE,rblock({rset(-1,rx(12,{rvar(-1),rc(n(1))})),
        rreturn(rc(std::make_shared<DoubleAbdValue>(1.75)))})));
    auto debug=[](R function,const char* name,std::initializer_list<int> positions) {
        AbdStack locations;for(int field:positions)locations.vs.push_back(ri(field));
        return append_raw(replace_raw(function,8,rb(true)),
            raw({rs(name),ri(0),ri(9),ri(1),locations.toAbdValue()}));
    };
    functions.vs.push_back(debug(rfn(323,INT_VALUE,rreturn(convert(INT_VALUE,rc(s("bad"))))),
        "conversionError",{1,0,9,5}));
    functions.vs.push_back(debug(rfn(324,INT_VALUE,rreturn(convert(INT_VALUE,rx(15,{rc(n(1)),rc(n(0))})))),
        "conversionOperand",{1,0,9,5,2,0,9,17}));
    auto module=replace_raw(replace_raw(rmodule({},1),7,functions.toAbdValue()),10,raw({rs("conversion.azs")}));
    auto program=load_raw(module);
    auto evaluate=[&](int target,V value){return program->invoke(300+target,{std::move(value)});};
    auto real=[&](int target,V value) {
        auto result=evaluate(target,std::move(value));
        check(result->type_tag()==target,"explicit numeric conversions return the requested tag");
        return target==FLOAT_VALUE?static_cast<double>(result->get<float>()):result->get<double>();
    };
    auto text=[](std::string value){return std::make_shared<variable>(std::move(value));};
    auto runtime_failure=[&](int target,V value,const std::string& message) {
        bool caught=false;
        try {evaluate(target,std::move(value));}
        catch(const std::runtime_error& error) {
            caught=true;
            check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,
                  "conversion errors retain runtime_error and their complete diagnostic");
        }
        check(caught,"an invalid explicit conversion must fail");
    };
    const std::vector<V> identities{
        std::make_shared<variable>(std::numeric_limits<int>::min()),text(std::string("x\0\xf0\x9f\x98\x80",6)),
        std::make_shared<variable>(-0.0f),std::make_shared<variable>(-0.0),
        std::make_shared<variable>(address{std::numeric_limits<std::uint64_t>::max()}),
    };
    for(const auto& input:identities) {
        auto result=evaluate(input->type_tag(),input);
        check(result!=input&&result->type_tag()==input->type_tag()&&value_to_string(result)==value_to_string(input),
              "same-tag conversions through any preserve a copied value");
        if(input->type_tag()==FLOAT_VALUE)check(std::signbit(result->get<float>()),"float identity preserves negative zero");
        if(input->type_tag()==DOUBLE_VALUE)check(std::signbit(result->get<double>()),"double identity preserves negative zero");
    }
    check(real(FLOAT_VALUE,std::make_shared<variable>(16777217))==16777216.0,"int to float uses float precision");
    check(real(DOUBLE_VALUE,std::make_shared<variable>(std::numeric_limits<int>::max()))==2147483647.0,"int to double widens without loss");
    check(real(DOUBLE_VALUE,std::make_shared<variable>(1.25f))==1.25,"float to double widens without loss");
    check(real(FLOAT_VALUE,std::make_shared<variable>(1.25))==1.25,"double to float supports narrowing");
    for(const auto& item:std::initializer_list<std::pair<double,int>>{
        {1.75,1},{-1.75,-1},{0.9,0},{-0.9,0},{2147483647.9,std::numeric_limits<int>::max()},
        {-2147483648.9,std::numeric_limits<int>::min()},
    })check(integer(evaluate(INT_VALUE,std::make_shared<variable>(item.first)))==item.second,
             "numeric to int truncates before checking the resulting range");
    check(integer(evaluate(INT_VALUE,std::make_shared<variable>(-1.75f)))==-1,"float to int truncates toward zero");
    check(real(FLOAT_VALUE,std::make_shared<variable>(std::numeric_limits<double>::denorm_min()))==0.0,
          "numeric underflow to float zero is allowed");
    check(std::signbit(real(FLOAT_VALUE,std::make_shared<variable>(-std::numeric_limits<double>::denorm_min()))),
          "numeric underflow preserves the sign of zero");
    check(real(FLOAT_VALUE,std::make_shared<variable>(static_cast<double>(std::numeric_limits<float>::denorm_min())))==
          static_cast<double>(std::numeric_limits<float>::denorm_min()),"numeric subnormal float results are allowed");

    for(const auto& item:std::initializer_list<std::pair<std::string,int>>{
        {"0",0},{"+7",7},{"-7",-7},{"0007",7},{"2147483647",std::numeric_limits<int>::max()},
        {"-2147483648",std::numeric_limits<int>::min()},
    })check(integer(evaluate(INT_VALUE,text(item.first)))==item.second,"decimal integer strings parse with optional signs");
    for(const auto& item:std::initializer_list<std::pair<std::string,double>>{
        {"+7",7.0},{"-7",-7.0},{".5",0.5},{"1.",1.0},{"1e2",100.0},{"-2.5e-1",-0.25},{"001.5",1.5},
    })for(int target:{FLOAT_VALUE,DOUBLE_VALUE})check(real(target,text(item.first))==item.second,
        "decimal real strings accept optional dots, signs and exponents");
    check(real(FLOAT_VALUE,text("16777217"))==16777216.0,"string to float rounds directly to float precision");
    check(real(FLOAT_VALUE,text("1.0000000596046447753906250000000000000001"))==
          static_cast<double>(std::nextafter(1.0f,2.0f)),"string to float avoids a double-rounding intermediate");
    for(int target:{FLOAT_VALUE,DOUBLE_VALUE}) {
        check(real(target,text("1e-9999"))==0.0,"decimal underflow to zero is allowed");
        check(std::signbit(real(target,text("-1e-9999"))),"decimal underflow preserves negative zero");
    }
    auto float_subnormal=evaluate(FLOAT_VALUE,text("1e-40"));
    check(std::fpclassify(float_subnormal->get<float>())==FP_SUBNORMAL,"parsed float subnormals are accepted");
    check(std::fpclassify(real(DOUBLE_VALUE,text("1e-310")))==FP_SUBNORMAL,"parsed double subnormals are accepted");
    check(real(FLOAT_VALUE,text("1.401298464324817070923729583289916131280e-45"))==
          static_cast<double>(std::numeric_limits<float>::denorm_min()),"the smallest float subnormal parses");
    check(real(DOUBLE_VALUE,text("4.9406564584124654e-324"))==std::numeric_limits<double>::denorm_min(),
          "the smallest double subnormal parses");
    for(const auto& item:std::initializer_list<std::pair<V,std::string>>{
        {std::make_shared<variable>(-7),"-7"},{std::make_shared<variable>(1.25f),"1.25"},
        {std::make_shared<variable>(1.25),"1.25"},{std::make_shared<variable>(address{}),"0"},
        {std::make_shared<variable>(address{std::numeric_limits<std::uint64_t>::max()}),"18446744073709551615"},
    })check(value_to_string(evaluate(STRING_VALUE,item.first))==item.second,
        "numeric and address string conversion uses the existing shortest formatter");
    check(integer(program->invoke(320))==1&&integer(program->baseEnv->getVariable(-1))==1,
          "a conversion evaluates its operand exactly once");

    const std::vector<std::string> invalid_integers{"","+","-","1.0","1e2"," 1","1 ","0x10","nan","inf","1f","1abc","1_0"};
    for(const auto& input:invalid_integers)runtime_failure(INT_VALUE,text(input),
        "Cannot convert string \""+input+"\" to int: invalid decimal integer");
    const std::vector<std::string> invalid_reals{"","+","-",".","+.","1e","1e+"," 1","1 ","0x1p2","nan","NaN","inf","Infinity","1f","1abc","1,5","--1"};
    for(const auto& input:invalid_reals)for(int target:{FLOAT_VALUE,DOUBLE_VALUE})runtime_failure(target,text(input),
        "Cannot convert string \""+input+"\" to "+(target==FLOAT_VALUE?"float":"double")+": invalid decimal number");
    for(const auto& input:{"2147483648","-2147483649"})runtime_failure(INT_VALUE,text(input),
        "Cannot convert string \""+std::string(input)+"\" to int: value is out of range");
    for(const auto& input:{"1e400","-1e400"})for(int target:{FLOAT_VALUE,DOUBLE_VALUE})runtime_failure(target,text(input),
        "Cannot convert string \""+std::string(input)+"\" to "+(target==FLOAT_VALUE?"float":"double")+": value is out of range");
    runtime_failure(FLOAT_VALUE,text("1e40"),"Cannot convert string \"1e40\" to float: value is out of range");
    for(double value:{2147483648.0,-2147483649.0,std::numeric_limits<double>::infinity(),std::numeric_limits<double>::quiet_NaN()})
        runtime_failure(INT_VALUE,std::make_shared<variable>(value),"Cannot convert double to int: value is out of range");
    runtime_failure(INT_VALUE,std::make_shared<variable>(2147483648.0f),"Cannot convert float to int: value is out of range");
    runtime_failure(FLOAT_VALUE,std::make_shared<variable>(std::numeric_limits<double>::max()),"Cannot convert double to float: value is out of range");
    runtime_failure(DOUBLE_VALUE,std::make_shared<variable>(std::numeric_limits<float>::infinity()),"Cannot convert float to double: value is out of range");
    struct unsupported_value {const char* type;V value;};
    const std::vector<unsupported_value> unsupported{
        {"boolean",std::make_shared<variable>(false)},{"boolean",std::make_shared<variable>(true)},
        {"void",std::make_shared<variable>(nullptr)},{"object",std::make_shared<variable>(blocks::create(1,program))},
    };
    for(const auto& source:unsupported)for(const auto& target:std::initializer_list<std::pair<int,const char*>>{
        {INT_VALUE,"int"},{STRING_VALUE,"string"},{FLOAT_VALUE,"float"},{DOUBLE_VALUE,"double"},{ADDRESS_VALUE,"address"},
    })runtime_failure(target.first,source.value,"Cannot convert "+std::string(source.type)+" to "+target.second);
    for(const auto& target:std::initializer_list<std::pair<int,const char*>>{{INT_VALUE,"int"},{FLOAT_VALUE,"float"},{DOUBLE_VALUE,"double"}})
        runtime_failure(target.first,std::make_shared<variable>(address{1}),"Cannot convert address to "+std::string(target.second));
    for(const auto& source:std::initializer_list<unsupported_value>{
        {"int",std::make_shared<variable>(1)},{"float",std::make_shared<variable>(1.0f)},
        {"double",std::make_shared<variable>(1.0)},{"string",text("1")},
    })runtime_failure(ADDRESS_VALUE,source.value,"Cannot convert "+std::string(source.type)+" to address");

    runtime_failure(INT_VALUE,text(std::string("\"\\\n\0",4)+"\xf0\x9f\x98\x80"),
        R"(Cannot convert string "\"\\\u000a\u0000\ud83d\ude00" to int: invalid decimal integer)");
    runtime_failure(DOUBLE_VALUE,text(std::string(63,'a')+"\xf0\x9f\x98\x80tail"),
        "Cannot convert string \""+std::string(63,'a')+"\\ud83d\\ude00...\" to double: invalid decimal number");
    runtime_failure(INT_VALUE,text(std::string(100,'9')),
        "Cannot convert string \""+std::string(64,'9')+"...\" to int: value is out of range");
    runtime_failure(FLOAT_VALUE,text(std::string(1,static_cast<char>(0xff))),
        "Cannot convert string \"\\xff\" to float: invalid decimal number");
    {
        struct numeric_locale_restore {
            std::string original;
            ~numeric_locale_restore(){std::setlocale(LC_NUMERIC,original.c_str());}
        } restore{std::setlocale(LC_NUMERIC,nullptr)};
        for(const char* locale:{"de_DE.UTF-8","fr_FR.UTF-8","de_DE","French_France.1252"})
            if(std::setlocale(LC_NUMERIC,locale))break;
        check(real(FLOAT_VALUE,text("1.5"))==1.5&&real(DOUBLE_VALUE,text("1.5"))==1.5,
              "numeric conversion uses the C locale even when a host locale is available");
        runtime_failure(DOUBLE_VALUE,text("1,5"),"Cannot convert string \"1,5\" to double: invalid decimal number");
    }
    for(int target:{-1,BOOLEAN_VALUE,VOID_VALUE,ANY_VALUE,OBJECT_VALUE,std::numeric_limits<int>::max()})
        rejects_message([&]{load_raw(rmodule({rfn(10,INT_VALUE,convert(target,rc(n(1))))}));},
                        "Invalid conversion target type: "+std::to_string(target),"exec conversions reject unsupported target tags");
    for(auto malformed:{rx(49,{ri(INT_VALUE)}),rx(49,{rs("int"),rc(n(1))}),rx(49,{ri(INT_VALUE),rc(n(1)),rc(n(2))})})
        rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,malformed)}));},"convert records require an integer target and one expression");
    for(const auto& item:std::initializer_list<std::pair<int,std::string>>{
        {323,"Cannot convert string \"bad\" to int: invalid decimal integer\n  at conversionError (conversion.azs:9:5)"},
        {324,"Division by zero\n  at conversionOperand (conversion.azs:9:17)"},
    }) {
        bool caught=false;
        try {program->invoke(item.first);}
        catch(const std::runtime_error& error) {
            caught=true;check(describe_error(error)==item.second,"conversion failures preserve their expression and operand locations");
        }
        check(caught,"conversion debug-position probes must throw");
    }
    program->destroy();
}
void bitwise_tests() {
    AbdStack functions;
    functions.vs.push_back(rfn(90,INT_VALUE,rblock({
        rset(-1,rx(12,{rx(14,{rvar(-1),rc(n(10))}),rvar(0)})),rreturn(rvar(0))}),0,{INT_VALUE}));
    for(int opcode=43;opcode<=48;++opcode) {
        functions.vs.push_back(rfn(opcode,INT_VALUE,rreturn(rx(opcode,{rvar(0),rvar(1)})),0,{ANY_VALUE,ANY_VALUE}));
        functions.vs.push_back(rfn(opcode+100,INT_VALUE,rblock({rset(-1,rc(n(0))),
            rreturn(rx(opcode,{rcall(90,{rc(n(2))}),rcall(90,{rc(n(1))})}))})));
        functions.vs.push_back(rfn(opcode+200,INT_VALUE,
            rreturn(rx(opcode,{rvar(0),rset(0,rc(n(opcode<46?3:1)))})),0,{ANY_VALUE}));
    }
    auto debug=[](R function,const char* name,std::initializer_list<int> positions) {
        AbdStack locations;for(int field:positions)locations.vs.push_back(ri(field));
        return append_raw(replace_raw(function,8,rb(true)),
            raw({rs(name),ri(0),ri(9),ri(1),locations.toAbdValue()}));
    };
    functions.vs.push_back(debug(rfn(98,INT_VALUE,rreturn(rx(46,{rc(n(1)),rc(n(32))}))),
        "shiftCount",{1,0,9,5}));
    functions.vs.push_back(debug(rfn(99,INT_VALUE,rreturn(rx(46,{rc(n(1)),rx(15,{rc(n(1)),rc(n(0))})}))),
        "shiftOperand",{1,0,9,5,3,0,9,21}));
    auto module=replace_raw(replace_raw(rmodule({},1),7,functions.toAbdValue()),10,raw({rs("bitwise.azs")}));
    auto program=load_raw(module);
    auto evaluate=[&](int opcode,int left,int right) {
        return integer(program->invoke(opcode,{std::make_shared<variable>(left),std::make_shared<variable>(right)}));
    };
    auto runtime_failure=[&](int opcode,std::vector<V> args,const std::string& message) {
        bool caught=false;
        try {program->invoke(opcode,std::move(args));}
        catch(const std::runtime_error& error) {
            caught=true;
            check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,
                  "bitwise failures preserve runtime_error and its exact diagnostic");
        }
        check(caught,"invalid bitwise operations must not return a value");
    };
    constexpr int minimum=std::numeric_limits<int>::min(),maximum=std::numeric_limits<int>::max();
    struct bit_case {int left,right,and_value,or_value,xor_value;};
    for(const auto& item:std::initializer_list<bit_case>{
        {0,0,0,0,0},{0,-1,0,-1,-1},{-1,-1,-1,-1,0},{5,3,1,7,6},
        {-2,1,0,-1,-1},{-2,2,2,-2,-4},{minimum,maximum,0,-1,-1},
        {-1,minimum,minimum,-1,maximum},{minimum,1,0,minimum+1,minimum+1},
        {maximum,1,1,maximum,maximum-1},{1431655765,-1431655766,0,-1,-1},
    }) {
        const int expected[]{item.and_value,item.or_value,item.xor_value};
        for(int opcode=43;opcode<=45;++opcode) {
            check(evaluate(opcode,item.left,item.right)==expected[opcode-43],"bitwise operators preserve all signed bit patterns");
            check(evaluate(opcode,item.right,item.left)==expected[opcode-43],"bitwise operators accept either operand order");
        }
    }
    auto signed_pattern=[](std::uint32_t value) {
        const auto widened=static_cast<std::int64_t>(value);
        return static_cast<int>(value<=0x7fffffffu?widened:widened-0x100000000ll);
    };
    for(int value:{minimum,minimum+1,-1431655766,-2,-1,0,1,2,1431655765,maximum}) {
        check(evaluate(45,value,-1)==static_cast<int>(-static_cast<std::int64_t>(value)-1),
              "xor with minus one implements unary complement without signed overflow");
        for(int count=0;count<=31;++count) {
            // Mathematical references use multiplication/division, rather than
            // relying on host signed-shift behavior or repeating sign filling.
            const auto divisor=std::int64_t{1}<<count;
            const auto product=static_cast<std::int64_t>(value)*divisor;
            auto quotient=static_cast<std::int64_t>(value)/divisor;
            if(value<0&&static_cast<std::int64_t>(value)%divisor)--quotient;
            const auto unsigned_quotient=static_cast<std::uint64_t>(static_cast<std::uint32_t>(value))/static_cast<std::uint64_t>(divisor);
            check(evaluate(46,value,count)==signed_pattern(static_cast<std::uint32_t>(product)),"left shift truncates to the low 32 bits");
            check(evaluate(47,value,count)==static_cast<int>(quotient),"arithmetic right shift rounds negative values toward minus infinity");
            check(evaluate(48,value,count)==signed_pattern(static_cast<std::uint32_t>(unsigned_quotient)),"unsigned right shift fills with zeroes");
        }
    }
    const int ordered[]{0,3,3,4,1,1},snapshotted[]{1,7,6,10,2,2};
    for(int opcode=43;opcode<=48;++opcode) {
        check(integer(program->invoke(opcode+100))==ordered[opcode-43]&&integer(program->baseEnv->getVariable(-1))==21,
              "bitwise operands execute once from left to right");
        check(integer(program->invoke(opcode+200,{std::make_shared<variable>(5)}))==snapshotted[opcode-43],
              "bitwise left values are snapshotted before the right operand mutates their storage");
    }
    static constexpr const char* names[]{"bit_and","bit_or","bit_xor","shl","shr","ushr"};
    struct invalid_value {const char* type;V value;};
    const std::vector<invalid_value> invalid{
        {"boolean",std::make_shared<variable>(false)},{"boolean",std::make_shared<variable>(true)},
        {"float",std::make_shared<variable>(1.0f)},{"double",std::make_shared<variable>(1.0)},
        {"string",std::make_shared<variable>("1")},{"void",std::make_shared<variable>(nullptr)},
        {"address",std::make_shared<variable>(address{})},{"object",std::make_shared<variable>(blocks::create(1,program))},
    };
    auto integer_value=std::make_shared<variable>(1);
    for(int opcode=43;opcode<=48;++opcode) {
        for(const auto& item:invalid)for(bool reverse:{false,true}) {
            const std::string types=reverse?std::string("int and ")+item.type:std::string(item.type)+" and int";
            runtime_failure(opcode,reverse?std::vector<V>{integer_value,item.value}:std::vector<V>{item.value,integer_value},
                std::string(names[opcode-43])+" operands must be int, got "+types);
        }
        runtime_failure(opcode,{invalid[0].value,invalid[4].value},
            std::string(names[opcode-43])+" operands must be int, got boolean and string");
        if(opcode>=46)for(int value:{minimum,-1,0,maximum})for(int count:{minimum,-1,32,33,maximum})
            runtime_failure(opcode,{std::make_shared<variable>(value),std::make_shared<variable>(count)},
                "Shift count must be between 0 and 31, got "+std::to_string(count));
        for(auto malformed:{rx(opcode,{rc(n(1))}),rx(opcode,{rc(n(1)),rc(n(2)),rc(n(3))})})
            rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,malformed)}));},"bitwise opcodes require exactly two operands");
    }
    for(const auto& item:std::initializer_list<std::pair<int,std::string>>{
        {98,"Shift count must be between 0 and 31, got 32\n  at shiftCount (bitwise.azs:9:5)"},
        {99,"Division by zero\n  at shiftOperand (bitwise.azs:9:21)"},
    }) {
        bool caught=false;
        try {program->invoke(item.first);}
        catch(const std::runtime_error& error) {
            caught=true;check(describe_error(error)==item.second,"bitwise nodes and operands retain their debug source positions");
        }
        check(caught,"debug-position probes must throw");
    }
    program->destroy();
}
void boolean_condition_equality_tests() {
    // These hand-authored functions use any parameters so their checks cannot
    // be supplied by the compiler or by a declared parameter's ABI validation.
    auto program=load_raw(rmodule({
        rfn(10,INT_VALUE,rblock({rx(27,{rvar(0),rreturn(rc(n(1))),rb(false)}),rreturn(rc(n(0)))}),0,{ANY_VALUE}),
        rfn(11,INT_VALUE,rblock({rx(28,{rvar(0),rreturn(rc(n(1)))}),rreturn(rc(n(0)))}),0,{ANY_VALUE}),
        rfn(12,BOOLEAN_VALUE,rreturn(rx(25,{rvar(0)})),0,{ANY_VALUE}),
        rfn(13,BOOLEAN_VALUE,rreturn(rx(23,{rvar(0),rc(boolean(true))})),0,{ANY_VALUE}),
        rfn(14,BOOLEAN_VALUE,rreturn(rx(24,{rvar(0),rc(boolean(false))})),0,{ANY_VALUE}),
        rfn(15,BOOLEAN_VALUE,rreturn(rx(23,{rc(boolean(true)),rvar(0)})),0,{ANY_VALUE}),
        rfn(16,BOOLEAN_VALUE,rreturn(rx(24,{rc(boolean(false)),rvar(0)})),0,{ANY_VALUE}),
        rfn(17,BOOLEAN_VALUE,rreturn(rx(23,{rc(boolean(false)),rx(15,{rc(n(1)),rc(n(0))})}))),
        rfn(18,BOOLEAN_VALUE,rreturn(rx(24,{rc(boolean(true)),rx(15,{rc(n(1)),rc(n(0))})}))),
        rfn(19,BOOLEAN_VALUE,rreturn(rx(19,{rvar(0),rvar(1)})),0,{ANY_VALUE,ANY_VALUE}),
        rfn(20,BOOLEAN_VALUE,rreturn(rx(20,{rvar(0),rvar(1)})),0,{ANY_VALUE,ANY_VALUE}),
    }));
    auto truth_result=[&](int id,std::vector<V> args) {
        auto result=program->invoke(id,std::move(args));
        check(result->type_tag()==BOOLEAN_VALUE,"boolean operations return boolean values");
        return result->get<bool>();
    };
    auto runtime_failure=[&](int id,std::vector<V> args,const std::string& message) {
        bool caught=false;
        try {program->invoke(id,std::move(args));}
        catch(const std::runtime_error& error) {
            caught=true;
            check(typeid(error)==typeid(std::runtime_error)&&error.what()==message,
                  "dynamic type violations retain their runtime_error type and exact diagnostic");
        }
        check(caught,"a dynamic type violation must fail before producing a value");
    };
    for(bool value:{false,true}) {
        auto input=std::make_shared<variable>(value);
        check(integer(program->invoke(10,{input}))==(value?1:0),"if accepts both boolean values");
        check(integer(program->invoke(11,{input}))==(value?1:0),"while accepts both boolean values");
        check(truth_result(12,{input})==!value,"logical not accepts boolean values");
        for(int id:{13,14,15,16})
            check(truth_result(id,{input})==value,"both evaluated logical operands accept boolean values");
    }
    check(!truth_result(17,{})&&truth_result(18,{}),"boolean short-circuiting leaves a failing right operand unevaluated");

    struct value_case {const char* type;V value;};
    const std::vector<value_case> non_boolean{
        {"int",std::make_shared<variable>(0)},{"int",std::make_shared<variable>(1)},
        {"float",std::make_shared<variable>(0.0f)},{"float",std::make_shared<variable>(1.5f)},
        {"double",std::make_shared<variable>(0.0)},{"double",std::make_shared<variable>(1.5)},
        {"string",std::make_shared<variable>("")},{"string",std::make_shared<variable>("x")},
        {"void",std::make_shared<variable>(nullptr)},
        {"address",std::make_shared<variable>(address{})},{"address",std::make_shared<variable>(address{1})},
        {"object",std::make_shared<variable>(blocks::create(1,program))},
    };
    for(const auto& item:non_boolean)for(int id:{10,11,12,13,14,15,16})
        runtime_failure(id,{item.value},"Condition must be boolean, got "+std::string(item.type));

    auto equality=[&](const V& left,const V& right,bool equal) {
        check(truth_result(19,{left,right})==equal,"compatible dynamic values compare for equality");
        check(truth_result(20,{left,right})==!equal,"inequality is the complement of compatible equality");
    };
    struct numeric_case {V value;int number;};
    const std::vector<numeric_case> numbers{
        {std::make_shared<variable>(2),2},{std::make_shared<variable>(2.0f),2},{std::make_shared<variable>(2.0),2},
        {std::make_shared<variable>(3),3},{std::make_shared<variable>(3.0f),3},{std::make_shared<variable>(3.0),3},
    };
    for(const auto& left:numbers)for(const auto& right:numbers)equality(left.value,right.value,left.number==right.number);
    for(bool left:{false,true})for(bool right:{false,true})
        equality(std::make_shared<variable>(left),std::make_shared<variable>(right),left==right);
    for(const std::string left:{"","same","\xe4\xb8\xad"})for(const std::string right:{"","same","\xe4\xb8\xad"})
        equality(std::make_shared<variable>(left),std::make_shared<variable>(right),left==right);
    equality(std::make_shared<variable>(nullptr),std::make_shared<variable>(nullptr),true);
    for(std::uint64_t left:{std::uint64_t{0},std::uint64_t{1},std::numeric_limits<std::uint64_t>::max()})
        for(std::uint64_t right:{std::uint64_t{0},std::uint64_t{1},std::numeric_limits<std::uint64_t>::max()})
            equality(std::make_shared<variable>(address{left}),std::make_shared<variable>(address{right}),left==right);

    const std::vector<value_case> scalar_types{
        {"int",std::make_shared<variable>(1)},{"float",std::make_shared<variable>(1.0f)},
        {"double",std::make_shared<variable>(1.0)},{"string",std::make_shared<variable>("1")},
        {"boolean",std::make_shared<variable>(true)},{"void",std::make_shared<variable>(nullptr)},
    };
    for(std::size_t i=0;i<scalar_types.size();++i)for(std::size_t j=i+1;j<scalar_types.size();++j) {
        if(i<3&&j<3)continue; // Mixed numeric types are compatible; every other unequal tag is not.
        for(bool reverse:{false,true}) {
            const auto& left=scalar_types[reverse?j:i];const auto& right=scalar_types[reverse?i:j];
            for(int id:{19,20})runtime_failure(id,{left.value,right.value},
                "Equality requires compatible operand types, got "+std::string(left.type)+" and "+right.type);
        }
    }
    auto address_value=std::make_shared<variable>(address{}),object_value=non_boolean.back().value;
    for(const auto& scalar:scalar_types)for(bool reverse:{false,true})for(int id:{19,20}) {
        runtime_failure(id,reverse?std::vector<V>{scalar.value,address_value}:std::vector<V>{address_value,scalar.value},
                        "Unsupported address operands for "+std::string(id==19?"eq":"ne"));
        runtime_failure(id,reverse?std::vector<V>{scalar.value,object_value}:std::vector<V>{object_value,scalar.value},
                        "Unsupported object value operands for "+std::string(id==19?"eq":"ne"));
    }
    for(int id:{19,20}) {
        const std::string message="Unsupported object value operands for "+std::string(id==19?"eq":"ne");
        runtime_failure(id,{object_value,object_value},message);
        runtime_failure(id,{object_value,address_value},message);
        runtime_failure(id,{address_value,object_value},message);
    }
    program->destroy();
}
#if !defined(_WIN32)
// std::thread cannot choose its stack size; POSIX threads can.
void on_thread_stack(std::size_t bytes,const std::function<void()>& body) {
    struct task {const std::function<void()>* body;std::exception_ptr failure;} work{&body,nullptr};
    pthread_attr_t attributes;
    if(pthread_attr_init(&attributes)!=0)throw std::runtime_error("Cannot initialize thread attributes");
    pthread_t thread;
    int status=pthread_attr_setstacksize(&attributes,bytes);
    if(status==0)status=pthread_create(&thread,&attributes,[](void* data)->void* {
        auto& current=*static_cast<task*>(data);
        try{(*current.body)();}catch(...){current.failure=std::current_exception();}
        return nullptr;
    },&work);
    pthread_attr_destroy(&attributes);
    if(status!=0)throw std::runtime_error("Cannot start a thread with the requested stack size");
    pthread_join(thread,nullptr);
    if(work.failure)std::rethrow_exception(work.failure);
}
#endif
void native_stack_tests() {
    constexpr int deep_id=0x7a010001,block_id=0x7a010002,reenter_id=0x7a010003,host_id=0x7a020001;
    std::weak_ptr<script> weak;
    auto host=std::make_shared<test_executor>(0x7a02);
    host->functions[host_id]=std::make_shared<test_function>(INT_VALUE,[&](const std::vector<V>& args) {
        return weak.lock()->invoke(reenter_id,{std::make_shared<variable>(args.at(0)->get<int>()-1)});
    });
    registered_executor registered(host);
    const auto stop=rx(27,{rx(19,{rvar(0),rc(n(0))}),rblock({rreturn(rc(n(0)))}),rb(false)});
    // 1 + (1 + ... 20 nested ... target(n - 1)), as in the JVM reproduction.
    const auto sum=[](R target){for(int i=0;i<20;++i)target=rx(12,{rc(n(1)),target});return target;};
    const auto minus_one=rx(13,{rvar(0),rc(n(1))});
    R nested=rreturn(rx(12,{rc(n(1)),rcall(block_id,{minus_one})}));
    for(int i=0;i<56;++i)nested=rblock({nested});
    auto deep=load_raw(rmodule({
        // Slot 1 owns an allocation in every frame, released by scope cleanup.
        rfn(deep_id,INT_VALUE,rblock({stop,rdef(1,rcall(0x0abd0003,{rc(n(1))})),rreturn(sum(rcall(deep_id,{minus_one})))}),1,{INT_VALUE}),
        rfn(block_id,INT_VALUE,rblock({stop,nested}),0,{INT_VALUE}),
        rfn(reenter_id,INT_VALUE,rblock({stop,rreturn(sum(rcall(host_id,{rvar(0)})))}),0,{INT_VALUE})}));
    weak=deep;
    const auto call=[&](int id,int argument){return integer(deep->invoke(id,{std::make_shared<variable>(argument)}));};
    const auto exhausted=[&](int id,int argument,const char* what) {
        const auto allocations=heap::lenAlloc();
        rejects_containing([&]{call(id,argument);},"Script native stack limit exceeded",what);
        check(deep->active_calls==0&&heap::lenAlloc()==allocations,"a native stack failure unwinds every call and releases scope allocations");
    };
    // Completing is fine wherever the stack suffices; crashing never is.
    const auto completes_or_exhausts=[&](int id,int argument,int expected,const char* what) {
        try{check(call(id,argument)==expected,what);}
        catch(const std::runtime_error& error){check(std::string(error.what())=="Script native stack limit exceeded"&&deep->active_calls==0,what);}
    };
    check(call(deep_id,3)==60&&call(block_id,3)==3&&call(reenter_id,3)==60,"deep fixtures compute normally when the stack suffices");
#if !defined(_WIN32)
    for(std::size_t size:{std::size_t{256*1024},std::size_t{512*1024}})on_thread_stack(size,[&] {
        exhausted(deep_id,254,"nested expressions on a small thread end in an ordinary error");
        exhausted(block_id,250,"nested blocks on a small thread end in an ordinary error");
        exhausted(reenter_id,120,"host callbacks reentering the script share the thread's stack limit");
        check(call(deep_id,3)==60,"a thread runs scripts again after a native stack failure");
    });
    on_thread_stack(4*1024*1024,[&]{completes_or_exhausts(block_id,250,250,"nested blocks on a 4 MiB thread complete or fail cleanly");});
#endif
    completes_or_exhausts(block_id,250,250,"nested blocks on the main thread complete or fail cleanly");
    deep->max_stack_bytes=128*1024;
    exhausted(deep_id,254,"an explicit stack budget applies on a large thread");
    check(call(deep_id,1)==20,"shallow calls fit an explicit stack budget");
    deep->max_stack_bytes=0;
    check(call(deep_id,20)==400,"the automatic limit leaves ordinary recursion alone");
    deep->destroy();
}
// exec v11 private functions (entry kind 2) are reachable only by direct
// calls of their own module: hosts and reflection see them as absent, and
// neither imports nor other modules' calls may name them.
void private_function_tests() {
    const auto private_fn=[](int id,int type,R body,int locals=0,std::initializer_list<int> params={}) {
        return replace_raw(rfn(id,type,body,locals,params),7,ri(2));
    };
    const char* previous=std::getenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");
    const std::string saved=previous?previous:"";
    for(const char* engine:{"0","1"}) {
        setenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND",engine,1);
        auto program=load_raw(rmodule({
            private_fn(0x0fffff00,INT_VALUE,rreturn(rx(12,{rvar(0),rc(n(1))})),0,{INT_VALUE}),
            rfn(700,INT_VALUE,rreturn(rcall(0x0fffff00,{rc(n(41))}))),
            rfn(701,INT_VALUE,rreturn(reflect(INT_VALUE,0x0fffff00,{rc(n(1))})))}));
        check(integer(program->invoke(700))==42,"a module calls its private function directly");
        rejects_message([&]{program->invoke(0x0fffff00,{std::make_shared<variable>(1)});},
            "Unknown function id: 0x0fffff00","hosts cannot invoke private functions");
        rejects_message([&]{program->invoke(701);},"Unknown function id: 0x0fffff00","reflection cannot find private functions");
        check(!program->hasFunction(0x0fffff00)&&program->hasFunction(700),"private functions are not listed");
        // Another module reaches the private function neither by import nor by a hint-mapped call.
        rejects_message([&]{insert_raw(program,rhint("",{rfn(0x10000001,INT_VALUE,rreturn(rcall(0x0fffff00,{rc(n(1))})))},0,nullptr,
            raw({rsignature(0x0fffff00,INT_VALUE,{INT_VALUE})})));},
            "Private script function cannot be imported: 0x0fffff00","private functions cannot be imported");
        program->destroy();
        auto owner=load_raw(rhint("Owner",{private_fn(0x00000002,INT_VALUE,rreturn(rc(n(5)))),rfn(3,INT_VALUE,rreturn(rcall(2)))}),false);
        insert_raw(owner,rhint("",{rfn(0x10000001,INT_VALUE,rreturn(rcall(0x20000002)))},0,raw({assume("Owner",0x2000)})),false);
        rejects_containing([&]{owner->flush();},"Private script function is not visible outside its module","other modules cannot call private functions");
        owner->destroy();
    }
    rejects_message([&]{load_raw(rmodule({replace_raw(rfn(0x0fff0000,VOID_VALUE,rblock({})),7,ri(2))}));},
        "Lifecycle and main functions cannot be private","main cannot be private");
    rejects_message([&]{load_raw(rmodule({replace_raw(rfn(5,VOID_VALUE,rblock({})),7,ri(3))}));},
        "Invalid function generic ABI","entry kinds above private are rejected");
    rejects_message([&]{load_raw(rmodule({rfn(5,VOID_VALUE,rblock({}))},0,raw({replace_raw(rsignature(0x12340001,INT_VALUE),4,ri(2))})));},
        "Invalid external generic ABI","imports cannot declare private functions");
    if(previous)setenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND",saved.c_str(),1);else unsetenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");
}
// exec v11 extended debug records restore inlined frames in both engines;
// their ids take the namespace the module is mounted at.
void inline_trace_tests() {
    const auto extended=[](R function,const std::string& name,std::initializer_list<int> locations,std::initializer_list<R> inlined,int display) {
        AbdStack values;for(int value:locations)values.vs.push_back(ri(value));
        return append_raw(replace_raw(function,8,rb(true)),raw({rs(name),ri(0),ri(1),ri(1),values.toAbdValue(),raw(inlined),ri(display)}));
    };
    const auto inner=rx(54,{raw({}),rx(15,{rc(n(1)),rc(n(0))})});
    const auto body=rblock({rreturn(rx(54,{raw({}),inner}))});
    const auto lib=replace_raw(rhint("Lib",{extended(rfn(2,INT_VALUE,body),"libmain",{1,0,2,5,0,2,0,2,12,0,3,0,7,9,1,4,0,9,14,2},
        {raw({ri(5),rs("outer"),ri(2)}),raw({ri(6),rs("inner"),ri(3)})},2)}),10,raw({rs("lib.azs")}));
    const auto main=rhint("",{rfn(0x0fff0000,INT_VALUE,rreturn(rcall(0x20000002)))},0,raw({assume("Lib",0x2000)}));
    const char* previous=std::getenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");
    const std::string saved=previous?previous:"";
    for(const char* engine:{"0","1"}) {
        setenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND",engine,1);
        auto program=load_raw(main,false);insert_raw(program,lib);
        const int mounted=program->namespace_for_hint("Lib");
        std::string message;try{program->invoke(0x0fff0000);}catch(const std::exception& error){message=describe_error(error);}
        check(message=="Division by zero\n  at inner (lib.azs:9:14)\n  at outer (lib.azs:7:9)\n  at libmain (lib.azs:2:12)\n  at function 0x0fff0000",
            "inlined frames precede their call sites");
        const auto& frames=last_error_trace().frames;
        check(frames.size()==4&&frames[0].function_id==(mounted<<16|6)&&frames[1].function_id==(mounted<<16|5)&&
            frames[2].function_id==(mounted<<16|2),"inlined frame ids follow the mounted namespace");
        program->destroy();
    }
    if(previous)setenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND",saved.c_str(),1);else unsetenv("AZSCRIPT_EXPERIMENTAL_VM_BACKEND");
}
int main(){try{
    inline_trace_tests();
    private_function_tests();
    ownership_identity_tests();
    statement_protocol_tests();
    native_stack_tests();
    virtual_tests();
    checked_object_tests();
    object_storage_tests();
    string_tests();
    select_tests();
    checked_return_tests();
    conversion_tests();
    bitwise_tests();
    boolean_condition_equality_tests();
    runtime_trace_tests();
    buffer_tests();
    hint_name_tests();
    check(getiFunction(0x0abd0006)->return_type()==ANY_VALUE,"mem_get reports its dynamic return type");
    {
        constexpr int host_id=0x6abc0002;
        auto host=std::make_shared<test_executor>(0x6abc);
        host->functions[host_id]=std::make_shared<test_function>(VOID_VALUE,[](const std::vector<V>& args) {
            if(args.size()!=1||args[0]->type_tag()!=INT_VALUE)throw std::invalid_argument("host expects int");
            return std::make_shared<variable>(args[0]->get<int>()+2);
        });
        registered_executor registered(host);
        auto ci=cfixed(INT_VALUE,0),cs=cfixed(STRING_VALUE,0),ca=cfixed(ADDRESS_VALUE,1);
        auto co=cfixed(OBJECT_VALUE,3,200,{ci});
        auto generic=load_raw(rmodule({
            rfn(0,VOID_VALUE,rblock({rset(-1,rc(n(0))),rset(-2,rc(n(0)))})),
            gfn(100,ANY_VALUE,greturn(rvar(0),cref(0)),1,0,{ANY_VALUE}),
            gfn(101,ANY_VALUE,greturn(rx(37,{cref(0)}),cref(0)),1),
            gfn(102,ANY_VALUE,greturn(gcall(100,{rvar(0)},{cref(0)}),cref(0)),1,0,{ANY_VALUE}),
            gfn(103,ANY_VALUE,greturn(rcall(0x0abd0008,{rx(36,{cref(0)}),rc(n(30)),rc(n(41))}),cref(0)),1),
            rfn(20,INT_VALUE,rreturn(gcall(101,{}, {ci}))),
            rfn(21,STRING_VALUE,rreturn(gcall(101,{}, {cs}))),
            rfn(22,ADDRESS_VALUE,rreturn(gcall(101,{}, {ca}))),
            rfn(23,DOUBLE_VALUE,rreturn(gcall(101,{}, {cfixed(DOUBLE_VALUE,0)}))),
            rfn(24,BOOLEAN_VALUE,rreturn(gcall(101,{}, {cfixed(BOOLEAN_VALUE,0)}))),
            rfn(25,INT_VALUE,rreturn(gcall(102,{rc(n(42))},{ci}))),
            rfn(26,INT_VALUE,rreturn(gcall(100,{rc(s("wrong"))},{ci}))),
            rfn(27,INT_VALUE,rreturn(gcall(103,{}, {ci}))),
            rfn(30,INT_VALUE,rreturn(rx(12,{rvar(0),rc(n(1))})),0,{INT_VALUE}),
            gfn(31,INT_VALUE,rreturn(rc(n(99))),0,0,{},1),
            rfn(32,INT_VALUE,rreturn(reflect(INT_VALUE,30,{rc(n(42))}))),
            rfn(33,INT_VALUE,rreturn(reflect(INT_VALUE,31))),
            rfn(34,INT_VALUE,rreturn(reflect(INT_VALUE,100,{rc(n(1))}))),
            rfn(35,INT_VALUE,rreturn(reflect(INT_VALUE,host_id,{rc(n(40))}))),
            rfn(36,STRING_VALUE,rreturn(reflect(STRING_VALUE,host_id,{rc(n(40))}))),
            rfn(37,INT_VALUE,rreturn(reflect(INT_VALUE,30,{rc(s("wrong"))}))),
            rfn(38,VOID_VALUE,gcall(101,{}, {cfixed(OBJECT_VALUE,3)})),
            gfn(200,OBJECT_VALUE,rblock({rdef(0,rx(32,{ri(1)})),
                rx(6,{rcall(0x0abd0006,{rx(9,{rx(33,{rvar(0)}),ri(0)})}),rx(37,{cref(0)})}),
                rx(10,{rx(33,{rvar(0)}),rb(true),ri(201),rb(false),raw({cref(0)})}),rreturn(rvar(0))}),1,1,{},1),
            gfn(201,VOID_VALUE,rblock({rset(-1,rx(12,{rvar(-1),rc(n(1))})),rset(-2,rx(36,{cref(0)}))}),1,0,{ADDRESS_VALUE},1),
            rfn(202,OBJECT_VALUE,rreturn(gcall(200,{}, {ci}))),
            rfn(210,VOID_VALUE,rblock({rdef(0,gcall(101,{}, {co})),rdef(1,rvar(0))}),2),
            rfn(211,VOID_VALUE,gcall(101,{}, {cfixed(OBJECT_VALUE,3,200,{cs})})),
            rfn(212,INT_VALUE,rreturn(reflect(INT_VALUE,200))),
            rfn(213,VOID_VALUE,rblock({rdef(0,gcall(101,{}, {co})),rx(15,{rc(n(1)),rc(n(0))})}),1),
            rfn(214,INT_VALUE,rreturn(reflect(INT_VALUE,202))),
            gfn(400,ANY_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(1))})),
                rx(6,{rcall(0x0abd0006,{rx(9,{rvar(0),ri(0)})}),rc(n(42))}),
                rx(10,{rvar(0),rb(true),ri(201),rb(false),raw({cref(0)})}),greturn(rvar(0),cref(0))}),1,1),
            rfn(500,INT_VALUE,rreturn(rcall(0x0abd0006,{rx(9,{gcall(400,{}, {cfixed(ADDRESS_VALUE,2)}),ri(0)})}))),
            rfn(501,INT_VALUE,rreturn(rcall(0x0abd0006,{rx(9,{gcall(400,{}, {ca}),ri(0)})}))),
            gfn(600,ANY_VALUE,greturn(gcall(600,{}, {cref(0)}),cref(0)),1),
            rfn(601,INT_VALUE,rreturn(gcall(600,{}, {ci})))
        },2));
        check(integer(generic->invoke(20))==0,"generic int defaults to zero");
        check(value_to_string(generic->invoke(21)).empty(),"generic string defaults to empty");
        check(pointer(generic->invoke(22))==address{},"generic address defaults to null");
        check(generic->invoke(23)->get<double>()==0.0,"generic double defaults to zero");
        check(!generic->invoke(24)->get<bool>(),"generic boolean defaults to false");
        check(integer(generic->invoke(25))==42,"shared generic calls forward immutable contexts");
        rejects_containing([&]{generic->invoke(26);},"Generic value type mismatch","typed generic return checks the actual value");
        check(integer(generic->invoke(27))==42,"generic reflection gets its expected ABI from the current context");
        check(integer(generic->invoke(32))==43,"reflection invokes a script function by runtime ID");
        rejects_containing([&]{generic->invoke(33);},"ordinary non-generic","reflection rejects internal lifecycle entries");
        rejects_containing([&]{generic->invoke(34);},"ordinary non-generic","reflection rejects generic targets");
        check(integer(generic->invoke(35))==42,"reflection calls an undeclared host and checks actual return instead of advertised type");
        rejects_containing([&]{generic->invoke(36);},"Reflection return type mismatch","reflection checks exact result type");
        rejects_containing([&]{generic->invoke(37);},"argument 1 type mismatch","reflection retains target argument checks");
        rejects_containing([&]{generic->invoke(38);},"no default constructor","object generic default requires a constructor");
        rejects_containing([&]{generic->invoke(100,{std::make_shared<variable>(1)});},"wrapper","public host calls cannot omit generic contexts");
        generic->invoke(210);check(integer(generic->baseEnv->getVariable(-1))==2,"copied generic objects preserve both destructors");
        generic->invoke(211);check(integer(generic->baseEnv->getVariable(-1))==3&&integer(generic->baseEnv->getVariable(-2))==STRING_VALUE,
            "returned generic object captures its actual destructor context");
        rejects_containing([&]{generic->invoke(212);},"ordinary non-generic","reflection cannot bypass an internal generic factory");
        rejects_containing([&]{generic->invoke(213);},"Division by zero","generic object error retains the original failure");
        check(integer(generic->baseEnv->getVariable(-1))==4,"generic object cleans up on error");
        rejects_containing([&]{generic->invoke(214);},"Reflection return type mismatch","failed reflection still owns its returned object temporary");
        check(integer(generic->baseEnv->getVariable(-1))==5,"wrong reflective result is destroyed exactly once");
        check(integer(generic->invoke(500))==42&&heap::lenAlloc()==0,"generic class pointer return transfers cleanup to the actual caller");
        rejects([&]{generic->invoke(501);},"generic raw address return does not transfer object ownership");
        check(heap::lenAlloc()==0,"raw-address generic return still releases the object");
        generic->max_call_depth=12;
        rejects_containing([&]{generic->invoke(601);},"call depth","generic recursion retains the execution depth budget");
        check(generic->active_calls==0,"generic recursion unwinds active call guards");
        generic->destroy();
        rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,rreturn(rx(36,{cref(0)})))}));},"unbound context references rejected at decode");
        rejects([&]{load_raw(rmodule({gfn(10,INT_VALUE,rreturn(rx(36,{cfixed(INT_VALUE,2)})),1)}));},"mismatched ABI and context kind rejected");
        rejects([&]{load_raw(rmodule({gfn(10,ANY_VALUE,greturn(rc(n(1)),cref(0)),1),rfn(11,INT_VALUE,rreturn(rcall(10)))}));},
            "flush rejects omitted generic context arguments");
    }
    {
        const auto hint=rc(s("ReflectionLibrary"));
        auto queries=load_raw(rmodule({
            rfn(0,VOID_VALUE,rset(-1,rcall(0x0abd000a,{hint}))),
            rfn(10,INT_VALUE,rreturn(rcall(0x0abd0009,{hint}))),
            rfn(11,BOOLEAN_VALUE,rreturn(rcall(0x0abd000a,{rc(s("missing"))}))),
            rfn(12,BOOLEAN_VALUE,rreturn(rcall(0x0abd000a,{rc(s("reflectionlibrary"))}))),
            rfn(13,INT_VALUE,rreturn(rcall(0x0abd0009,{rc(s("missing"))}))),
            rfn(14,INT_VALUE,rreturn(rcall(0x0abd0008,{rc(n(INT_VALUE)),rx(12,{rx(14,{rcall(0x0abd0009,{hint}),rc(n(65536))}),rc(n(2))})})))
        },1),false);
        insert_raw(queries,rhint("ReflectionLibrary",{rfn(2,INT_VALUE,rreturn(rc(n(42))))}),false);
        check(queries->hint_loaded("ReflectionLibrary"),"hint presence means mounted before initialization");
        queries->flush();
        check(queries->baseEnv->getVariable(-1)->get<bool>(),"onload can query already mounted hints");
        check(integer(queries->invoke(10))==queries->namespace_for_hint("ReflectionLibrary"),"script and host report the same mounted namespace");
        check(!queries->invoke(11)->get<bool>()&&!queries->invoke(12)->get<bool>(),"hint presence is case-sensitive and missing hints return false");
        rejects_containing([&]{queries->invoke(13);},"Unknown namespace hint","namespace query rejects absent hints");
        check(integer(queries->invoke(14))==42,"reflection uses the queried runtime mount namespace");
        queries->destroy();
    }
    {
        constexpr int alias=0x22440002;
        auto consumer=load_raw(rhint("",{
            gfn(100,ANY_VALUE,greturn(rx(37,{cref(0)}),cref(0)),1),
            rfn(10,VOID_VALUE,gcall(100,{}, {cfixed(OBJECT_VALUE,3,alias,{cfixed(STRING_VALUE,0)})})),
            rfn(-1,INT_VALUE,rreturn(rc(n(42)))),rfn(11,INT_VALUE,rreturn(reflect(INT_VALUE,-1)))
        },0,raw({assume("GenericFactory",0x2244)})),false);
        rejects_containing([&]{consumer->flush();},"Missing namespace hint","generic factory link waits for its hint without partial relocation");
        auto provider=rhint("GenericFactory",{
            rfn(0,VOID_VALUE,rset(-1,rc(n(0)))),
            gfn(2,OBJECT_VALUE,rblock({rdef(0,rx(32,{ri(1)})),
                rx(6,{rcall(0x0abd0006,{rx(9,{rx(33,{rvar(0)}),ri(0)})}),rx(37,{cref(0)})}),
                rx(10,{rx(33,{rvar(0)}),rb(true),ri(alias+1),rb(false),raw({cref(0)})}),rreturn(rvar(0))}),1,1,{},1),
            gfn(3,VOID_VALUE,rset(-1,rx(12,{rx(36,{cref(0)}),rc(n(1))})),1,0,{ADDRESS_VALUE},1),
            rfn(4,INT_VALUE,rreturn(rvar(-1)))
        },1,raw({assume("GenericFactory",0x2244)}));
        insert_raw(consumer,provider);
        consumer->invoke(10);
        const int count=(consumer->namespace_for_hint("GenericFactory")<<16)|4;
        check(integer(consumer->invoke(count))==2,"generic default factories and captured destructor IDs relocate across hint modules");
        consumer->flush();consumer->invoke(10);
        check(integer(consumer->invoke(count))==2,"repeated flush retains bound factory and destructor contexts");
        check(integer(consumer->invoke(11))==42,"reflection preserves the complete negative function ID bit pattern");
        consumer->destroy();
    }
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
        check(addresses->invoke(14)->get<bool>()&&addresses->invoke(15)->get<bool>(),"address equality and ordering compare unsigned values");
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
        check(!linked->setup&&!linked->faulted&&linked->baseEnv->getVariable(-1)->type_tag()==VOID_VALUE,"mount does not execute onload");
        rejects([&]{linked->invoke(0x0fff0000);},"invoke requires successful flush");
        rejects([&]{entry->invoke(linked->baseEnv);},"cached script function cannot bypass setup gate");
        rejects_containing([&]{linked->flush();},"Missing namespace hint","missing library reports its hint before initialization");
        check(reference->function_id==alias&&linked->baseEnv->getVariable(-1)->type_tag()==VOID_VALUE&&!linked->faulted,
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
        auto cached_host=linked->getFunction(0x0abd000b);
        rejects([&]{linked->getFunction(0x0abd0001);},"the retired getDepth id resolves to no function");
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
        rejects_containing([&]{insert_raw(linked,rmodule({rfn(beta_ns<<16|9,VOID_VALUE,rblock({}))}),false);},
            "conflicts with hint Beta, which module #2 mounted there","fixed module cannot occupy mounted hint namespace");
        check(linked->setup&&linked->module_manifest().size()==5,"namespace collision does not invalidate setup");
        linked->destroy();

        auto bad_signature=load_raw(rhint("",{rfn(0,VOID_VALUE,rset(-1,rc(n(1)))),rfn(0x0fff0000,DOUBLE_VALUE,rreturn(rcall(alias)))},
            1,raw({assume("Alpha",0x1234)}),raw({rsignature(alias,DOUBLE_VALUE)})),false);
        insert_raw(bad_signature,alpha,false);
        char mismatch[80];std::snprintf(mismatch,sizeof mismatch,"External signature does not match script function: 0x%08x",
            static_cast<unsigned>(bad_signature->namespace_for_hint("Alpha")<<16|2));
        rejects_message([&]{bad_signature->flush();},mismatch,"imported script signatures must match exactly");
        check(!bad_signature->setup&&!bad_signature->faulted&&bad_signature->baseEnv->getVariable(-1)->type_tag()==VOID_VALUE&&
              bad_signature->baseEnv->getVariable(-2)->type_tag()==VOID_VALUE,"signature failure executes no onload and is not a runtime fault");
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
            rejects([&]{load_raw(malformed,false);},"exec malformed hint module rejected before mounting");
        rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,rx(10,{rc(n(0)),rb(true),ri(0x0fff0000),rb(false)}))}),false);},
                "main entry is not a valid object destructor");
    }
    {
        constexpr int host_ns=0xf240,record_id=static_cast<int>(0xf2400001u),reentry_id=static_cast<int>(0xf2400002u),recursive_id=static_cast<int>(0xf2400003u);
        std::vector<int> events;std::shared_ptr<script> initializing_script;
        auto callback=std::make_shared<test_executor>(host_ns);
        callback->functions[record_id]=std::make_shared<test_function>(VOID_VALUE,[&](const std::vector<V>& args){events.push_back(args.at(0)->get<int>());return std::make_shared<variable>(nullptr);});
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
        insert_raw(initializing_script,rhint("Bad",{rfn(0,VOID_VALUE,rblock({log(20),rdef(0,rcall(0x0abd0014,{rc(n(1))})),
            rx(10,{rvar(0),rb(false),rb(false),raw({})}),rx(15,{rc(n(1)),rc(n(0))})}),1),rfn(1,VOID_VALUE,log(-20))},
            0,raw({assume("Good",0x4001)}),signatures),false);
        insert_raw(initializing_script,rhint("Good",{rfn(0,VOID_VALUE,rblock({log(10),rcall(reentry_id),
            rdef(0,rcall(0x0abd0003,{rc(n(1))})),rcall(0x0abd0005,{rvar(0)})}),1),
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
            rfn(maker,VOID_VALUE,rblock({rdef(0,rcall(0x0abd0014,{rc(n(1))})),
                rx(10,{rvar(0),rb(true),ri(-1),rb(true),raw({})}),rx(11,{rvar(0)})}),1)
        },1));
        high->invoke(maker);check(integer(high->baseEnv->getVariable(-1))==1&&heap::lenAlloc()==0,
              "optional destructor supports the full 0xffffffff function address");high->destroy();
    }
    {
        // Assembly errors print IDs in hex and name the module that already owns them.
        auto hex4=[](int ns){char text[8];std::snprintf(text,sizeof text,"0x%04x",ns);return std::string(text);};
        auto value=[](int id,int result){return rfn(id,INT_VALUE,rreturn(rc(n(result))));};
        auto assembly=load_raw(rmodule({value(0x0fff0000,1),value(0x0fff0001,2)}));
        rejects_message([&]{insert_raw(assembly,rmodule({value(0x0fff0001,3)}),false);},
            "Duplicate function id: 0x0fff0001 (namespace 0x0fff, position 0x0001) is already defined by module #0 (ordinary); "
            "modules without #namespace share the default namespace 0x0fff, so give each module its own #namespace away from "
            "the low range hint libraries take, insert fixed-namespace modules before hint libraries, or use #namespace_hint",
            "default-namespace collisions name the owner and the remedy");
        check(assembly->setup&&assembly->module_manifest().size()==1&&integer(assembly->invoke(0x0fff0001))==2,
              "a rejected collision leaves the assembled script unchanged");
        insert_raw(assembly,rmodule({value(0x51510001,4)}));
        rejects_containing([&]{insert_raw(assembly,rmodule({value(0x0fff0000,5)}),false);},
            "Duplicate function id: 0x0fff0000 (namespace 0x0fff, position 0x0000) is already defined by module #0 (ordinary); "
            "only one module may define main","main collisions say that only one module may define main");
        rejects_containing([&]{insert_raw(assembly,rmodule({value(0x51510001,6)}),false);},
            "Duplicate function id: 0x51510001 (namespace 0x5151, position 0x0001) is already defined by module #1 (ordinary); "
            "give each module a distinct #namespace","explicit-namespace collisions name the inserted owner");
        rejects_message([&]{assembly->getFunction(static_cast<int>(0xfedc0001u));},"Unknown function id: 0xfedc0001",
            "unknown IDs print the full 32-bit pattern");
        rejects_message([&]{assembly->getFunction(0x51510009);},"Unknown function id: 0x51510009 (script namespace)",
            "unknown script-namespace IDs print hex");
        // Hint libraries take the lowest free namespace, so insertion order decides fixed-namespace conflicts.
        insert_raw(assembly,rhint("Alpha",{value(2,7)}),false);
        const int alpha=assembly->namespace_for_hint("Alpha");
        rejects_message([&]{insert_raw(assembly,rmodule({value(alpha<<16|5,8)}),false);},
            "Fixed namespace "+hex4(alpha)+" conflicts with hint Alpha, which module #2 mounted there; hint libraries take "
            "the lowest free namespace, so insert fixed-namespace modules before hint libraries or choose another #namespace",
            "fixed-vs-hint conflicts name the hint and its namespace");
        rejects_message([&]{insert_raw(assembly,rmodule({},0,raw({rsignature(alpha<<16|9,INT_VALUE)})),false);},
            "Fixed external namespace "+hex4(alpha)+" conflicts with hint Alpha, which module #2 mounted there; hint libraries "
            "take the lowest free namespace, so insert this module before hint libraries or import script libraries through #assume_hint",
            "fixed external namespaces name the mounted hint");
        assembly->flush();
        check(assembly->module_manifest().size()==3&&integer(assembly->invoke(alpha<<16|2))==7&&integer(assembly->invoke(0x51510001))==4,
              "rejected inserts keep the earlier assembly linkable");
        assembly->destroy();
        auto ordered=load_raw(rmodule({value(0x0fff0000,1)}),false);
        insert_raw(ordered,rmodule({value(alpha<<16|5,8)}),false);
        insert_raw(ordered,rhint("Alpha",{value(2,7)}),false);
        ordered->flush();const int moved=ordered->namespace_for_hint("Alpha");
        check(moved!=alpha&&integer(ordered->invoke(alpha<<16|5))==8&&integer(ordered->invoke(moved<<16|2))==7,
              "a fixed-namespace module inserted first moves later hint libraries past its namespace");
        ordered->destroy();
        auto missing=load_raw(rmodule({},0,raw({rsignature(0x0fff0010,INT_VALUE)})),false);
        rejects_message([&]{missing->flush();},"Imported script function is missing: 0x0fff0010","link errors print hex IDs");
        missing->destroy();
    }
    {
        auto twenty=rc(n(20));auto zero=rc(n(0));
        auto compact=load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),rx(12,{twenty,rc(n(22))})}))}));
        check(integer(compact->invoke(10))==42,"raw exec directly executes native arithmetic expressions");
        for(int opcode=0;opcode<=58;++opcode)if(opcode!=29&&opcode!=30)
            rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,rx(opcode))}));},"exec opcode rejects missing fields");
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
            rejects([&]{load_raw(rmodule({rfn(10,VOID_VALUE,expression,1)},1));},"exec malformed expression is rejected");
        auto good_function=rfn(10,VOID_VALUE,rblock({}));auto good_module=rmodule({good_function});
        for(auto malformed:{replace_raw(good_module,1,ri(3)),replace_raw(good_module,1,ri(4)),replace_raw(good_module,1,ri(5)),replace_raw(good_module,1,ri(9)),replace_raw(good_module,1,byte(4)),
            replace_raw(good_module,2,byte(1)),replace_raw(good_module,3,invalid_utf8),replace_raw(good_module,4,ri(-1)),
            replace_raw(good_module,4,ri(1048577)),append_raw(good_module,ri(0)),
            rmodule({append_raw(good_function,ri(0))}),rmodule({replace_raw(good_function,0,byte(1))}),
            rmodule({replace_raw(good_function,1,ri(6))}),rmodule({replace_raw(good_function,2,ri(-1))}),
            rmodule({replace_raw(good_function,3,ri(1048577))}),rmodule({replace_raw(good_function,4,raw({ri(INT_VALUE)}))}),
            rmodule({rfn(10,VOID_VALUE,rx(4,{ri(0),ri(0),rb(false)}),0,{INT_VALUE})}),
            rmodule({good_function,good_function}),rmodule({},0,raw({raw({ri(0x01230001),ri(0),raw({}),ri(9)})}))})
            rejects([&]{load_raw(malformed);},"exec malformed module or function record rejected");
        // The new interpreter intentionally rejects the retired Map formats.
        rejects([&]{load_raw(module({fn(10,VOID_VALUE,block({}))})->toAbdValue());},"named Map executable is unsupported");
        rejects([&]{load_raw(slot_module({slot_fn(10,VOID_VALUE,block({}))})->toAbdValue());},"v3 Map executable is unsupported");
        auto constant_null=rx(0,{raw({ri(0xce200a),byte(sentinel)})});
        auto scalar_program=load_raw(rmodule({rfn(10,VOID_VALUE,rx(7,{rb(true),constant_null})),
            rfn(11,STRING_VALUE,rx(7,{rb(true),rc(s(std::string("中文\0",7)))}))}));
        check(scalar_program->invoke(10)->type_tag()==VOID_VALUE,"exec current void sentinel round-trips");
        check(value_to_string(scalar_program->invoke(11))==std::string("中文\0",7),"exec UTF-8 string preserves embedded zero");
        auto metadata=std::make_shared<AbdMap>();auto child=std::make_shared<AbdMap>();
        child->put("flag",std::make_shared<BoolAbdValue>(true));metadata->put("nested",block({n(3),child}));
        auto metadata_program=load_raw(replace_raw(good_module,5,metadata->toAbdValue()));
        check(metadata_program->meta->get("nested")!=nullptr,"exec extensions preserve generic typed nested metadata");
        for(auto extension:{raw({rs("x"),ri(1),invalid_utf8}),raw({rs("x"),ri(3),byte(1)}),
            raw({rs("x"),ri(0x0d00),byte(2)}),raw({rs("x"),ri(0xce2009),raw({})}),
            raw({rs("x"),ri(999),raw({})}),raw({rs("x"),ri(3),ri(1),rs("x"),ri(3),ri(2)})})
            rejects([&]{load_raw(replace_raw(good_module,5,extension));},"exec extension metadata validates types and fields");
        auto chain=rc(n(0));for(int i=0;i<100;++i)chain=rx(12,{chain,rc(n(1))});
        check(integer(load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),chain}))}))->invoke(10))==100,
              "exec keeps 100-level arithmetic chains executable");
        for(int i=0;i<35;++i)chain=rx(12,{chain,rc(n(1))});
        rejects([&]{load_raw(rmodule({rfn(10,INT_VALUE,rx(7,{rb(true),chain}))}));},"exec raw stack nesting is bounded");
        auto deep_metadata=raw({});for(int i=0;i<128;++i)deep_metadata=raw({rs("nested"),ri(2),deep_metadata});
        rejects([&]{load_raw(replace_raw(good_module,5,deep_metadata));},"metadata cannot bypass enclosing module depth");
        auto data=good_module->toBytes();const auto length=static_cast<std::size_t>(good_module->size)+4;
        rejects([&]{load_script(data.get(),length-1);},"exec truncated outer frame rejected");
        std::vector<unsigned char> trailing(data.get(),data.get()+length);trailing.push_back(0);
        rejects([&]{load_script(trailing.data(),trailing.size());},"exec trailing bytes rejected");
        rejects([&]{load_script(data.get(),64*1024*1024+1);},"exec file size checked before reading payload");
        auto truncated=std::make_shared<AbdValue>(good_module->data,good_module->size-1);
        rejects([&]{load_raw(truncated);},"exec truncated child frame rejected");
        const auto function_count=compact->functions.size();
        rejects([&]{insert_raw(compact,replace_raw(rmodule({},2),1,ri(99)));},"exec invalid inserted header rejected");
        check(compact->baseEnv->variables.empty()&&compact->functions.size()==function_count&&integer(compact->invoke(10))==42,
              "invalid exec insertion preserves the old state");
    }
    {
        // Debug records follow has_debug: [name, file, line, column, (node, file, line, column) quads];
        // root field 10 lists the debug files. Nodes are numbered in pre-order: here
        // 0 return, 1 divide, 2 and 3 the constants.
        auto with_debug=[](R function,R record){AbdStack fields(function);fields.vs.at(8)=rb(true);fields.vs.push_back(std::move(record));return fields.toAbdValue();};
        auto quads=[](std::initializer_list<int> values){AbdStack result;for(int value:values)result.vs.push_back(ri(value));return result.toAbdValue();};
        auto files=[](R module,std::initializer_list<R> names){return replace_raw(module,10,raw(names));};
        auto record=[&](R name,int file,int line,int column,R locations){return raw({name,ri(file),ri(line),ri(column),locations});};
        auto byte=[](unsigned char value){return std::make_shared<AbdValue>(&value,1);};
        auto text=[](const char* value){return std::make_shared<AbdValue>(reinterpret_cast<const unsigned char*>(value),static_cast<int>(std::strlen(value)));};
        auto body=rx(7,{rb(true),rx(15,{rc(n(10)),rc(n(0))})});
        auto plain=rfn(10,INT_VALUE,body);
        auto good=with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,1,24})));
        auto debug_program=load_raw(files(rmodule({good}),{rs("t.azs")}));
        auto fn=std::dynamic_pointer_cast<ofunction>(debug_program->getFunction(10));
        auto returned=fn?std::dynamic_pointer_cast<returnExpression>(fn->code):nullptr;
        check(fn&&fn->debug&&fn->debug->name=="deep"&&fn->debug->files&&fn->debug->files->size()==1&&fn->debug->files->at(0)=="t.azs"&&
              fn->debug->header.line==1&&fn->debug->header.column==5&&fn->debug->locations.size()==1&&
              fn->debug->locations[0].line==1&&fn->debug->locations[0].column==24,"exec debug records are decoded");
        check(returned&&returned->location==0&&returned->returnType&&returned->returnType->location==1,
              "debug locations attach to their pre-order body nodes");
        rejects_message([&]{debug_program->invoke(10);},"Division by zero","debug info does not change execution");
        auto plain_program=load_raw(rmodule({plain}));
        check(!std::dynamic_pointer_cast<ofunction>(plain_program->getFunction(10))->debug,"functions without debug records have none");
        auto unknown=with_debug(plain,record(rs("deep"),0,0,0,quads({})));
        auto unknown_program=load_raw(files(rmodule({unknown}),{rs("t.azs")}));
        check(std::dynamic_pointer_cast<ofunction>(unknown_program->getFunction(10))->debug->header.line==0,
              "an unknown 0:0 header position is accepted");
        unknown_program->destroy();
        const unsigned char bad_text[]={0xed,0xa0,0x80};
        const auto invalid_utf8=std::make_shared<AbdValue>(bad_text,3);
        auto good_files={rs("t.azs")};
        for(auto malformed:{
            files(rmodule({good}),{}),files(rmodule({plain}),{rs("")}),files(rmodule({plain}),{text("a\nb")}),
            files(rmodule({plain}),{rs("a"),rs("a")}),files(rmodule({plain}),{invalid_utf8}),files(rmodule({plain}),{text("a\xc2\x85")}),
            files(rmodule({plain}),{text("a\xe2\x80\xae")}),files(rmodule({plain}),{text("a\xe2\x80\xa8")}),files(rmodule({plain}),{text("\x7f")}),
            files(rmodule({plain}),{ri(1)}),
            files(rmodule({replace_raw(plain,8,rb(true))}),good_files),files(rmodule({replace_raw(plain,8,byte(2))}),good_files),
            files(rmodule({append_raw(plain,record(rs("deep"),0,1,5,quads({})))}),good_files),
            files(rmodule({append_raw(good,ri(0))}),good_files),
            files(rmodule({with_debug(plain,raw({rs("deep"),ri(0),ri(1),ri(5)}))}),good_files),
            files(rmodule({with_debug(plain,raw({rs("deep"),ri(0),ri(1),ri(5),quads({}),ri(0)}))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,1,24,2})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({4,0,1,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({2,0,1,1,1,0,1,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,1,1,1,0,1,2})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({-1,0,1,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,1,1,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,0,1})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,quads({1,0,1,0})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),1,1,5,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),-1,1,5,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,0,1,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,0,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,-1,-1,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs(""),0,1,5,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(text("a\x1b"),0,1,5,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(invalid_utf8,0,1,5,quads({})))}),good_files),
            files(rmodule({with_debug(plain,record(rs("deep"),0,1,5,raw({ri(1),ri(0),ri(1),byte(1)})))}),good_files),
            rmodule({good})})
            rejects([&]{load_raw(malformed);},"malformed exec debug records and files are rejected");
        const auto function_count=plain_program->functions.size();
        rejects([&]{insert_raw(plain_program,files(rmodule({with_debug(rfn(11,INT_VALUE,body),record(rs("deep"),0,1,5,quads({9,0,1,1})))}),{rs("t.azs")}));},
                "an insert with a debug location past the body is rejected");
        check(plain_program->functions.size()==function_count&&!plain_program->hasFunction(11),"a rejected debug insert leaves the script unchanged");
        rejects_message([&]{load_raw(replace_raw(rmodule({plain}),1,ri(9)));},"Unsupported exec version 9; expected exec v10",
                        "older exec versions are rejected with the expected version");
        debug_program->destroy();plain_program->destroy();
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
            slot_fn(116,INT_VALUE,block({loop(boolean(true),block({def(0,n(1)),brk()})),ret(var(0))}),1),
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
            slot_fn(204,ADDRESS_VALUE,block({def(0,call(0x0abd0014,{n(1)})),object_bind(var(0),203,false),object_return(var(0))}),1)
        },1));
        auto second=numeric->baseEnv->getVariable(-3);
        auto inserted=std::dynamic_pointer_cast<ofunction>(numeric->functions.at(200));
        check(inserted->global_offset==2&&inserted->global_count==1,"insert stores module offset and count on each function");
        check(integer(first)==10&&integer(second)==20,"insert onload initializes its own globals without overlap");
        check(integer(numeric->invoke(120))==30&&integer(numeric->invoke(201))==30,"cross-module calls use callee offsets in both directions");
        insert_module(numeric,slot_module({slot_fn(0,VOID_VALUE,block({set(-1,n(40))})),
            slot_fn(300,INT_VALUE,block({ret(binary("add",call(200),var(-1)))}))},1));
        check(integer(numeric->invoke(300))==60&&numeric->baseEnv->variables.size()==4,"successive inserts all use module-local minus one independently");
        rejects_message([&]{insert_module(numeric,slot_module({slot_fn(200,VOID_VALUE,block({}))},2));},
            "Duplicate function id: 0x000000c8 (namespace 0x0000, position 0x00c8) is already defined by module #1 (ordinary); "
            "give each module a distinct #namespace, or use #namespace_hint","duplicate numeric function insert names its owner");
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
            auto body=block({def("p",call(0x0abd0014,{n(1)})),binary("m",field(var("p")),var("__func_param0")),object_bind(var("p"),destructor,manual)});
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
            fn(24,ADDRESS_VALUE,block({def("p",call(0x0abd0014,{n(1)})),binary("m",field(var("p")),n(9)),binary("divide",n(1),n(0)),object_bind(var("p"),30,false),object_return(var("p"))})),
            fn(25,VOID_VALUE,block({set("log",n(0)),call(24)})),
            fn(26,VOID_VALUE,block({set("log",n(0)),loop(boolean(true),block({def("a",call(20,{n(6)})),brk()}))})),
            fn(27,VOID_VALUE,block({set("log",n(0)),def("a",call(20,{n(7)})),loop(boolean(true),block({}))})),
            typed_fn(30,VOID_VALUE,block({append()}),{ADDRESS_VALUE}),
            typed_fn(31,VOID_VALUE,block({append(),binary("divide",n(1),n(0))}),{ADDRESS_VALUE}),
            typed_fn(33,VOID_VALUE,block({object_delete(var("__func_param0"))}),{ADDRESS_VALUE}),
            typed_fn(34,VOID_VALUE,block({call(0x0abd0002,{var("__func_param0")})}),{ADDRESS_VALUE}),
            typed_fn(35,VOID_VALUE,block({object_delete(field(var("__func_param0")))}),{ADDRESS_VALUE}),
            typed_fn(37,VOID_VALUE,block({set("observed",field(call(41,{var("__func_param0")})))}),{ADDRESS_VALUE}),
            fn(40,ADDRESS_VALUE,block({def("p",call(0x0abd0014,{n(1)})),binary("m",field(call(41,{var("p")})),n(9)),object_bind(var("p"),-1,false),object_return(var("p"))})),
            typed_fn(41,ADDRESS_VALUE,block({object_return(var("__func_param0"))}),{ADDRESS_VALUE}),
            fn(42,ADDRESS_VALUE,block({def("p",call(0x0abd0014,{n(1)})),object_return(var("p"))}))
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
        check(!heap::owner_of(manual)&&heap::lenAlloc()==2,"new remains manual after object returns");
        objects->invoke(15,{std::make_shared<variable>(manual)});
        check(integer(log)==4&&heap::lenAlloc()==1,"delete runs manual destructor and releases slots");
        objects->invoke(15,{std::make_shared<variable>(address{})});
        rejects([&]{objects->invoke(15,{std::make_shared<variable>(manual)});},"second delete rejects freed object");
        manual=pointer(objects->invoke(14));
        rejects_message([&]{objects->invoke(18,{std::make_shared<variable>(manual)});},
                        "mem_free cannot access object storage; use typed member access","raw free preserves a manual object");
        check(integer(log)==4&&heap::object_records().size()==2,"rejected raw free preserves registration and destructor state");
        objects->invoke(15,{std::make_shared<variable>(manual)});log->setValue(4);
        address reentrant=pointer(objects->invoke(23,{std::make_shared<variable>(8)}));
        rejects_containing([&]{objects->invoke(15,{std::make_shared<variable>(reentrant)});},"being destroyed","recursive self-delete rejected");
        check(heap::lenAlloc()==1,"failed recursive destructor still frees object once");
        reentrant=pointer(objects->invoke(28,{std::make_shared<variable>(8)}));
        rejects_containing([&]{objects->invoke(15,{std::make_shared<variable>(reentrant)});},"cannot access object storage","raw free cannot invalidate a running destructor");
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

        auto limited=program({fn(10,VOID_VALUE,block({def("p",call(0x0abd0014,{n(1)})),object_bind(var("p"),11,false),loop(boolean(true),block({}))})),
            typed_fn(11,VOID_VALUE,block({loop(boolean(true),block({}))}),{ADDRESS_VALUE})});
        limited->max_steps=40;
        rejects_containing([&]{limited->invoke(10);},"step limit","cleanup never resets an exhausted execution budget");
        check(heap::lenAlloc()==0,"budget failure still releases object storage");limited->destroy();

        auto shutdown=program({
            fn(10,ADDRESS_VALUE,block({set("log",n(0)),def("p",call(0x0abd0014,{n(1)})),object_bind(var("p"),30,false),object_return(var("p"))})),
            typed_fn(30,VOID_VALUE,block({set("log",binary("add",var("log"),n(1)))}),{ADDRESS_VALUE}),
            fn(1,VOID_VALUE,block({binary("divide",n(1),n(0))}))
        },block({s("log")}));
        shutdown->invoke(10);auto shutdown_log=shutdown->baseEnv->getVariable("log");
        rejects_containing([&]{shutdown->destroy();},"Division by zero","pre-destroy hook error propagates after cleanup");
        check(integer(shutdown_log)==1&&heap::lenAlloc()==0&&shutdown->closed,"pre-destroy error cannot skip object destructors");
        shutdown->destroy();

        auto shutdown_budget=program({fn(10,ADDRESS_VALUE,block({def("p",call(0x0abd0014,{n(1)})),object_bind(var("p"),11,false),object_return(var("p"))})),
            typed_fn(11,VOID_VALUE,block({loop(boolean(true),block({}))}),{ADDRESS_VALUE})});
        shutdown_budget->invoke(10);shutdown_budget->max_steps=40;
        rejects_containing([&]{shutdown_budget->destroy();},"step limit","base destructor respects shutdown execution budget");
        check(heap::lenAlloc()==0&&shutdown_budget->closed,"shutdown budget exhaustion releases all storage");

        auto undeleted=program({
            fn(10,VOID_VALUE,block({def("p",call(0x0abd0014,{n(1)})),object_bind(var("p"),30,true)})),
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
            fn(0,VOID_VALUE,block({def("p",call(0x0abd0014,{n(1)})),object_bind(var("p"),-1,true),binary("divide",n(1),n(0))}))
        });},"Division by zero","onload failure propagates");
        check(heap::object_records().empty()&&heap::lenAlloc()==1&&integer(heap::getAt(host_block))==99,
              "failed initial load discards its manual objects without touching host memory");
        heap::free(host_block);
    }
    auto p=program({fn(10,INT_VALUE,block({ret(binary("add",n(3),binary("multiply",n(4),n(5))))}))});
    check(integer(p->invoke(10))==23,"arithmetic");
    rejects([&]{p->invoke(11);},"unknown function throws");check(!p->functions.count(11),"lookup must not insert");
    rejects([&]{p->invoke(10,{std::make_shared<variable>(1)});},"argument count");
    auto returned=program({fn(10,INT_VALUE,block({loop(boolean(true),block({ret(n(7))})),ret(n(99))}))});
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
    check(numeric->invoke(10)->get<double>()==4.5,"int minus double");
    check(numeric->invoke(11)->get<float>()==0.5f,"int times float");
    auto typed=program({
        typed_fn(20,INT_VALUE,block({ret(var("__func_param0"))}),{INT_VALUE}),
        typed_fn(21,INT_VALUE,block({ret(n(7))}),{ANY_VALUE})
    });
    check(integer(typed->invoke(20,{std::make_shared<variable>(9)}))==9,"typed script parameter accepts exact dynamic type");
    rejects_containing([&]{typed->invoke(20,{std::make_shared<variable>(std::string("9"))});},"Script function 0x00000014 argument 1 type mismatch","typed script parameter rejects wrong dynamic type");
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
        ++add_calls;return std::make_shared<variable>(args[0]->get<int>()+args[1]->get<int>());
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
    rejects_message([&]{external->invoke(host_add,{std::make_shared<variable>(1)});},"External function 0xf2340001 argument count mismatch: expected 2, got 1","direct external arity checked before callback");
    rejects_containing([&]{external->invoke(host_add,{std::make_shared<variable>(std::string("1")),std::make_shared<variable>(2)});},"argument 1 type mismatch","direct external argument type checked before callback");
    rejects_containing([&]{external->invoke(31);},"argument count mismatch","internal external arity checked before callback");
    rejects_containing([&]{external->invoke(32);},"argument 1 type mismatch","internal external argument type checked before callback");
    check(add_calls==3,"invalid arguments never reach external callback");
    rejects_message([&]{external->invoke(host_wrong_return);},"External function 0xf2340002 return type mismatch: expected int, got float","direct external return type checked");
    rejects_containing([&]{external->invoke(33);},"return type mismatch: expected int, got float","internal external return type checked");
    check(wrong_return_calls==2,"return mismatch is checked after callback");
    check(external->invoke(host_void)->type_tag()==VOID_VALUE,"direct external void return accepts actual void");
    check(external->invoke(34)->type_tag()==VOID_VALUE,"internal external void return accepts actual void");
    rejects_containing([&]{external->invoke(host_wrong_void);},"return type mismatch: expected void, got int","direct external void return must match");
    rejects_containing([&]{external->invoke(35);},"return type mismatch: expected void, got int","internal external void return must match");
    check(void_calls==2&&wrong_void_calls==2,"void callbacks exercised on direct and internal paths");

    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{VOID_VALUE})}));},"external void parameter metadata rejected");
    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{ANY_VALUE})}));},"external any parameter metadata rejected");
    rejects([&]{program({},block({}),block({signature(host_add,ANY_VALUE,{})}));},"external any return metadata rejected");
    rejects([&]{auto malformed=std::make_shared<AbdMap>();malformed->put("id",n(host_add));malformed->put("return-type",n(INT_VALUE));program({},block({}),block({malformed}));},"missing external param-types rejected");
    rejects([&]{program({},block({}),block({signature(host_add,INT_VALUE,{}),signature(host_add,INT_VALUE,{})}));},"duplicate external signature rejected");
    check(program({fn(40,VOID_VALUE,block({}))},block({}),block({signature(40,VOID_VALUE,{})}))->invoke(40)->type_tag()==VOID_VALUE,"matching declaration and script definition link successfully");
    rejects([&]{program({fn(0x0abd0000,VOID_VALUE,block({}))});},"script cannot shadow a runtime builtin id");
    check(program({fn(0x10000000,VOID_VALUE,block({}))})->invoke(0x10000000)->type_tag()==VOID_VALUE,"script ids use all 32 bits");
    check(program({fn(-1,VOID_VALUE,block({}))})->invoke(-1)->type_tag()==VOID_VALUE,"signed negative function ids retain their unsigned address bits");
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
    auto bad=program({fn(10,INT_VALUE,block({ret(binary("divide",n(1),n(0)))})),fn(11,INT_VALUE,block({ret(binary("add",n(std::numeric_limits<int>::max()),n(1)))})),fn(12,INT_VALUE,block({ret(s("wrong"))})),fn(13,VOID_VALUE,block({loop(boolean(true),block({}))})),fn(14,INT_VALUE,block({ret(call(14))}))});
    rejects([&]{bad->invoke(10);},"division zero");rejects([&]{bad->invoke(11);},"integer overflow");rejects([&]{bad->invoke(12);},"return type mismatch");
    bad->max_steps=100;rejects([&]{bad->invoke(13);},"execution budget");
    bad->max_call_depth=8;rejects([&]{bad->invoke(14);},"recursion budget");check(bad->active_calls==0,"call depth unwound after failure");
    auto lazy=program({fn(10,BOOLEAN_VALUE,block({ret(binary("and",boolean(false),binary("divide",n(1),n(0))))})),fn(11,BOOLEAN_VALUE,block({ret(binary("or",boolean(true),binary("divide",n(1),n(0))))}))});
    check(!lazy->invoke(10)->get<bool>(),"and short circuits");check(lazy->invoke(11)->get<bool>(),"or short circuits");
    heap::clearHeap();check(heap::alloc(0)==address{},"zero allocation");rejects([]{heap::alloc(-1);},"negative allocation");
    auto a=heap::alloc(2),b=heap::alloc(2);check(a==address{1}&&b==address{3},"zero address reserved");
    heap::getAt(a)->setValue(99);check(heap::free(a),"free allocation");rejects([&]{heap::getAt(a);},"freed address rejection");
    check(heap::alloc(1)==address{1},"reuse leading hole");check(heap::getAt(address{1})->type_tag()==VOID_VALUE,"reused allocation cleared");
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
                if(same&&model.count(pointer))same=static_cast<bool>(heap::getAt(address{static_cast<std::uint64_t>(pointer+model[pointer]-1)}));
            }
            same=same&&heap::lenAlloc()==static_cast<int>(model.size());
        }
        check(same,"indexed heap keeps first-fit addresses and bounds");
        heap::clearHeap();
        std::vector<address> many;
        for(int i=0;i<20000;++i)many.push_back(heap::alloc(2));
        check(many.back()==address{39999}&&heap::getAt(address{many[10000].value+1})->type_tag()==VOID_VALUE,"appending allocations stays contiguous");
        check(heap::free(many[5])&&heap::alloc(2)==many[5]&&heap::alloc(1)==address{40001},"freed gap is reused before appending");
    }
    heap::clearHeap();
    auto memory=program({fn(10,INT_VALUE,block({def("p",call(0x0abd0003,{n(3)})),binary("m",call(0x0abd0006,{var("p")}),n(42)),ret(call(0x0abd0006,{var("p")}))})),fn(11,INT_VALUE,block({def("p",call(0x0abd0003,{n(3)})),ret(binary("divide",n(1),n(0)))}))});
    check(integer(memory->invoke(10))==42,"heap assignment and return copy");check(heap::lenAlloc()==0,"normal scope cleanup");
    rejects([&]{memory->invoke(11);},"heap cleanup on exception");check(heap::lenAlloc()==0,"exception scope cleanup");
    variable v(std::string("hello"));v.copy_from(std::make_shared<variable>(5));v.setValue(true);v.setValue(1.5f);v.setValue(2.5);v.setValue(nullptr);check(v.type_tag()==VOID_VALUE,"safe type transitions");
    variable literal("hello");check(literal.type_tag()==STRING_VALUE&&value_to_string(std::make_shared<variable>(literal))=="hello","C string literal stays a string");
    const char* null_text=nullptr;variable null_literal(null_text);check(null_literal.type_tag()==VOID_VALUE,"null C string pointer stays null");
    auto original=std::make_shared<variable>(std::string("value"));auto copy=original->deepCopy();copy->setValue(std::string("other"));check(original->get<std::string>()=="value","deep copy independent");
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
        check(owner_script->invoke(14)->get<bool>(),"callee may free an allocation owned by its caller chain");
        check(owner_script->invoke(16)->get<bool>(),"make_free detaches ownership so any scope may free");
        check(heap::lenAlloc()==1,"only the global allocation remains");
        auto host_block=heap::alloc(1);check(!heap::owner_of(host_block),"host allocations are untracked");
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
            call(0x0abd0006,{object_address(var("__func_param0"))})))}),{ADDRESS_VALUE});};
        auto literal=program({
            typed_fn(20,OBJECT_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{object_address(block_address(var("o")))}),var("__func_param0")),
                object_bind(block_address(var("o")),30,false),object_return(var("o"))}),{INT_VALUE}),
            value_log(30),
            typed_fn(31,VOID_VALUE,block({set("log",binary("add",binary("multiply",var("log"),n(10)),n(9)))}),{ADDRESS_VALUE}),
            fn(10,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",call(20,{n(2)})),ret(n(0))})),
            fn(11,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",var("a")),binary("m",call(0x0abd0006,{object_address(block_address(var("b")))}),n(5)),
                ret(call(0x0abd0006,{object_address(block_address(var("a")))}))})),
            fn(12,INT_VALUE,block({def("a",call(20,{n(1)})),def("p",block_address(var("a"))),def("b",call(20,{n(7)})),set("a",var("b")),
                ret(call(0x0abd0006,{object_address(var("p"))}))})),
            typed_fn(13,OBJECT_VALUE,block({def("x",call(20,{n(4)})),object_return(var("x"))}),{}),
            fn(14,INT_VALUE,block({def("y",call(13)),ret(call(0x0abd0006,{object_address(block_address(var("y")))}))})),
            typed_fn(15,ADDRESS_VALUE,block({def("x",call(20,{n(4)})),ret(block_address(var("x")))}),{}),
            fn(16,INT_VALUE,block({ret(call(0x0abd0006,{object_address(call(15))}))})),
            fn(17,INT_VALUE,block({set("log",n(0)),call(20,{n(3)}),ret(var("log"))})),
            fn(18,INT_VALUE,block({def("i",n(0)),def("seen",n(0)),
                loop(binary("lt",binary("add",var("i"),call(0x0abd0006,{object_address(block_address(call(33)))})),n(100)),
                     block({set("seen",var("log")),set("i",binary("add",var("i"),n(1)))})),ret(var("seen"))})),
            fn(19,INT_VALUE,block({def("p",call(0x0abd0014,{n(1)})),binary("m",call(0x0abd0006,{object_address(var("p"))}),call(20,{n(5)})),
                object_bind(var("p"),31,false),ret(n(0))})),
            fn(21,INT_VALUE,block({def("p",call(0x0abd0003,{n(1)})),binary("m",call(0x0abd0006,{var("p")}),call(20,{n(5)})),
                call(0x0abd0002,{var("p")}),ret(var("log"))})),
            fn(22,INT_VALUE,block({def("a",call(20,{n(1)})),branch(call(0x0abd0002,{block_address(var("a"))}),block({ret(n(1))})),
                ret(n(0))})),
            fn(34,VOID_VALUE,block({def("a",call(20,{n(1)})),call(0x0abd0004,{block_address(var("a"))})})),
            fn(23,INT_VALUE,block({def("a",call(20,{n(1)})),object_delete(block_address(var("a"))),ret(n(0))})),
            fn(24,INT_VALUE,block({def("a",call(20,{n(1)})),def("b",call(20,{n(2)})),drop(var("a")),set("log",binary("multiply",var("log"),n(10))),ret(n(0))})),
            fn(25,VOID_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{object_address(block_address(var("o")))}),call(20,{n(6)})),
                binary("divide",n(1),n(0))})),
            typed_fn(26,INT_VALUE,block({ret(call(0x0abd0006,{object_address(block_address(var("__func_param0")))}))}),{OBJECT_VALUE}),
            fn(27,INT_VALUE,block({def("a",call(20,{n(8)})),def("r",call(26,{var("a")})),ret(var("r"))})),
            typed_fn(32,VOID_VALUE,block({set("log",binary("add",var("log"),n(1)))}),{ADDRESS_VALUE}),
            typed_fn(33,OBJECT_VALUE,block({def("o",new_block(1)),binary("m",call(0x0abd0006,{object_address(block_address(var("o")))}),n(0)),
                object_bind(block_address(var("o")),32,false),object_return(var("o"))}),{}),
            typed_fn(28,INT_VALUE,block({def("t",move_out("__func_param0")),ret(call(0x0abd0006,{object_address(block_address(var("t")))}))}),{OBJECT_VALUE}),
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
        rejects_message([&]{run(22);},"mem_free cannot access object storage; use typed member access","mem_free rejects literal storage");
        check(integer(log)==1,"rejected raw free still destroys the literal at scope exit");
        rejects_containing([&]{literal->invoke(34);},"make_free cannot access object storage","make_free rejects literal objects");
        rejects_containing([&]{run(23);},"literal object","delete rejects literal objects");
        run(24);check(integer(log)==102,"drop ends a value immediately and scope cleanup skips it");
        log->setValue(0);rejects_containing([&]{literal->invoke(25);},"Division by zero","construction failure propagates");
        check(integer(log)==6,"completed value members end when construction fails");
        check(run(27)==8&&integer(log)==88,"by-value parameters are separate copies destroyed with the call");
        check(run(29)==3&&integer(log)==3,"a moved parameter is not destroyed twice");
        auto result=literal->invoke(13);
        check(result->type_tag()==OBJECT_VALUE&&!blocks::of(result)->owner,"a host call receives a host-held object");
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
        rejects_message([&]{program({fn(0x01230001,INT_VALUE,block({ret(n(7))}))});},
            "Script namespace 0x0123 conflicts with registered host executor","mount rejects a namespace already registered by the host");
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
        check(deferred->invoke(10)->get<bool>(),"onload effects visible after flush");
    }
    p->destroy();p->destroy();rejects([&]{p->invoke(10);},"closed script");
    p256_self_test();check(true,"P-256 vectors, reduction, and sign/verify roundtrip");
    clear_trusted_public_keys();
    rejects_containing([]{add_trusted_public_key_pem("-----BEGIN EC PRIVATE KEY-----\nMHc=\n-----END EC PRIVATE KEY-----\n");},"private","trusted key material rejects a private key");
    {
        auto fn=getiFunction(0x0abd0007);
        check(fn&&fn->return_type()==VOID_VALUE,"load_extern_library is builtin 7");
        auto env=std::make_shared<environment>();
        rejects([&]{fn->invoke(env,{});},"load_extern_library requires one string");
        auto dir=std::filesystem::temp_directory_path()/"azscript-extern-runtime-test";
        std::filesystem::remove_all(dir);std::filesystem::create_directories(dir);
        std::vector<std::uint8_t> image{'n','o','t','-','a','-','l','i','b','r','a','r','y'};
#if defined(_WIN32)
        const std::string library_suffix=".dll";
#elif defined(__APPLE__)
        const std::string library_suffix=".dylib";
#else
        const std::string library_suffix=".so";
#endif
        auto library=dir/("demo"+library_suffix);auto signature=dir/"demo.signature";
        {std::ofstream out(library,std::ios::binary);out.write(reinterpret_cast<const char*>(image.data()),static_cast<std::streamsize>(image.size()));}
        p256_keypair key;check(p256_generate(key),"extern test key");
        std::uint8_t hash[32];p256_sha256(image.data(),image.size(),hash);
        std::vector<std::uint8_t> der;check(p256_sign(key.d,hash,der),"extern test signature");
        {std::ofstream out(signature,std::ios::binary);out.write(reinterpret_cast<const char*>(der.data()),static_cast<std::streamsize>(der.size()));}
        rejects_containing([&]{load_named_extern_library((dir/"demo").string());},"No trusted public key","unsigned trust store rejects a signed library");
        add_trusted_public_key_pem(p256_public_pem(key.public_key));
        rejects_containing([&]{load_named_extern_library((dir/"demo").string());},"Cannot load extern library","an image that is not a library is rejected after verification");
        auto publish=[&](const std::filesystem::path& path,const std::vector<std::uint8_t>& image){
            {std::ofstream out(path,std::ios::binary);out.write(reinterpret_cast<const char*>(image.data()),static_cast<std::streamsize>(image.size()));}
            std::uint8_t image_hash[32];p256_sha256(image.data(),image.size(),image_hash);
            std::vector<std::uint8_t> image_der;check(p256_sign(key.d,image_hash,image_der),"dependency fixture signature");
            auto sig=path;sig.replace_filename(path.stem().string()+".signature");
            {std::ofstream out(sig,std::ios::binary);out.write(reinterpret_cast<const char*>(image_der.data()),static_cast<std::streamsize>(image_der.size()));}
        };
        publish(dir/("evil"+library_suffix),elf64_with_needed({"libevil.so"}));
        rejects_containing([&]{load_named_extern_library((dir/"evil").string());},"untrusted","a signed plugin cannot import an unlisted dependency");
        image[0]^=0x01;{std::ofstream out(library,std::ios::binary);out.write(reinterpret_cast<const char*>(image.data()),static_cast<std::streamsize>(image.size()));}
        rejects_containing([&]{load_named_extern_library((dir/"demo.so").string());},"signature","a changed library no longer matches its signature");
        rejects_containing([&]{load_named_extern_library("missing-extern-library-name");},"not found","a bare library name must exist on the search path");
        std::filesystem::remove_all(dir);
        clear_trusted_public_keys();
    }
    std::cout<<"Runtime regression checks passed: "<<assertions<<'\n';return 0;
}catch(const std::exception& e){std::cerr<<"Runtime test failure: "<<e.what()<<'\n';return 1;}}
