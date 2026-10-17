#include "library.h"
#include "vm/host_bridge.h"
#include "vm/session.h"
#include <iostream>
#include <string>

namespace {
using namespace azertian;
using R=std::shared_ptr<AbdValue>;
std::size_t checks=0;
void check(bool condition,const std::string& message) {
    ++checks;if(!condition)throw std::runtime_error(message);
}
template<class Body> void rejects(Body body,const std::string& expected) {
    try{body();}catch(const std::exception& error) {
        check(std::string(error.what()).find(expected)!=std::string::npos,
            "expected '"+expected+"', got '"+error.what()+"'");return;
    }
    check(false,"expected rejection: "+expected);
}
R raw(std::initializer_list<R> values){AbdStack stack;stack.vs=values;return stack.toAbdValue();}
R integer(int value){return std::make_shared<IntAbdValue>(value)->toAbdValue();}
R boolean(bool value){return std::make_shared<BoolAbdValue>(value)->toAbdValue();}
R text(const std::string& value){return std::make_shared<StringAbdValue>(value)->toAbdValue();}
R op(int code,std::initializer_list<R> children={}) {
    AbdStack stack;stack.vs.push_back(integer(code));stack.vs.insert(stack.vs.end(),children);return stack.toAbdValue();
}
R constant(int value){AbdArray values;values.push_back(std::make_shared<IntAbdValue>(value));return op(0,{values.toAbdValue()});}
R block(std::initializer_list<R> values){return op(1,{raw(values)});}
R returned(R value){return op(7,{boolean(true),std::move(value)});}
R slot(int index){return op(3,{integer(index)});}
R store(int index,int value){return op(5,{integer(index),constant(value)});}
R function(int id,int type,R body,std::initializer_list<int> params={},bool debug=false,int locals=0) {
    AbdStack types;for(int type:params)types.vs.push_back(integer(type));
    AbdStack fields;fields.vs={integer(id),integer(type),integer(static_cast<int>(params.size())),integer(locals),
        types.toAbdValue(),body,integer(0),integer(0),boolean(debug)};
    if(debug)fields.vs.push_back(raw({text("session_case"),integer(0),integer(10),integer(3),
        raw({integer(0),integer(0),integer(41),integer(2)})}));
    return fields.toAbdValue();
}
R module(std::initializer_list<R> functions,int globals=0) {
    return raw({text("AZSCRIPT"),integer(EXEC_FORMAT_VERSION),integer(1),text("vm_session_test"),integer(globals),
        std::make_shared<AbdMap>()->toAbdValue(),raw({}),raw(functions),text(""),raw({}),raw({text("session.azs")})});
}
std::shared_ptr<script> load(const R& source) {
    const auto bytes=source->toBytes();return load_script(bytes.get(),source->size+4);
}
void insert(const std::shared_ptr<script>& script,const R& source) {
    const auto bytes=source->toBytes();script->insert_script(bytes.get(),source->size+4);
}
constexpr std::size_t workspace=4*1024*1024;

void lifecycle_and_rebuild() {
    // Both modules have global_offset=0. Lifecycle IDs 0/1 must be bound by
    // module ordinal, even when the first module owns no globals.
    auto script=load(module({function(0,VOID_VALUE,block({constant(5)})),
        function(1,VOID_VALUE,block({constant(6)}))}));
    insert(script,module({function(0,VOID_VALUE,block({store(-1,41)})),
        function(1,VOID_VALUE,block({store(-1,99)})),function(10,INT_VALUE,returned(slot(-1)))},1));
    vm::select_backend(*script,workspace);script->flush();
    check(script->baseEnv->variables[0]->get<int>()==41,"module-local onload index was mixed up");
    auto cached=script->getFunction(10);
    check(cached->invoke(script->baseEnv)->get<int>()==41,"cached function did not use VM binding");
    rejects([&]{vm::select_backend(*script,workspace);},"before the first flush");
    script->baseEnv->variables[0]->setValue(51);
    insert(script,module({function(0,VOID_VALUE,block({store(-1,77)})),
        function(20,INT_VALUE,returned(slot(-1)))},1));
    rejects([&]{vm::select_backend(*script,workspace);},"before the first flush");
    rejects([&]{cached->invoke(script->baseEnv);},"setup is incomplete");
    script->flush();
    check(script->invoke(20)->get<int>()==77,"new module global offset was not rebound");
    check(cached->invoke(script->baseEnv)->get<int>()==51,"rebuild reran an initialized onload or lost host global write");
    auto retained=script->baseEnv->variables[0];auto env=script->baseEnv;
    script->destroy();
    check(retained->get<int>()==99,"pre_destroy did not use its module-local index");
    rejects([&]{cached->invoke(env);},"Script is closed");
    rejects([&]{vm::select_backend(*script,workspace);},"Script is closed");
}

void errors_and_trace() {
    auto script=load(module({function(10,INT_VALUE,block({store(-1,7),
        returned(op(15,{constant(1),constant(0)}))}),{},true),
        function(11,INT_VALUE,returned(slot(0)),{INT_VALUE},true)},1));
    vm::select_backend(*script,workspace);script->flush();
    script->baseEnv->variables[0]->setValue(1);
    rejects([&]{script->invoke(10);},"Division by zero");
    check(script->baseEnv->variables[0]->get<int>()==7,"partial global writes were lost on VM exception");
    check(last_error_trace().frames.size()==1,"VM exception emitted a duplicate or missing root frame");
    const auto frame=last_error_trace().frames.front();
    check(frame.function_id==10&&frame.name=="session_case"&&frame.file=="session.azs"&&frame.line==41&&frame.column==2,
        "VM exception lost the inherited debug position");
    rejects([&]{script->invoke(11,{std::make_shared<variable>(true)});},"type mismatch: expected int, got boolean");
    check(last_error_trace().frames.size()==1&&last_error_trace().frames.front().line==10,
        "pre-entry argument validation lost its tree-compatible header trace");
    check(script->invoke(11,{std::make_shared<variable>(13)})->get<int>()==13,"VM parameter copy returned wrong value");
    check(last_error_trace().frames.empty(),"successful VM call retained an old error trace");
    script->max_steps=0;
    rejects([&]{script->invoke(11,{std::make_shared<variable>(1)});},"Script execution step limit exceeded");
    check(last_error_trace().frames.size()==1&&script->remaining_steps==0,"VM budget failure lost root trace or decremented below zero");
    check(script->active_calls==0,"VM exception leaked logical admission");
    script->max_steps=1000;script->destroy();
}

void preflight_and_selection() {
    vm::ExecutableProgram host_program;
    vm::CallTarget target;target.kind=vm::TargetKind::host;target.function_id=0x6dff0001;target.hidden_count=1;
    host_program.targets.push_back(target);
    rejects([&]{vm::require_scalar_host_targets(host_program);},"generic or object host call contexts");
    host_program.targets[0].hidden_count=0;host_program.targets[0].entry_kind=1;
    rejects([&]{vm::require_scalar_host_targets(host_program);},"generic or object host call contexts");
    host_program.targets[0].entry_kind=0;host_program.targets[0].return_type=OBJECT_VALUE;
    rejects([&]{vm::require_scalar_host_targets(host_program);},"object callback signatures");
    auto source=module({function(0,VOID_VALUE,block({store(-1,99)})),
        function(10,VOID_VALUE,block({op(32,{integer(1)})}))},1);
    auto script=load(source);script->baseEnv->variables[0]->setValue(3);
    rejects([&]{vm::select_backend(*script,0);},"workspace limit must be positive");
    vm::select_backend(*script,workspace);
    rejects([&]{script->flush();},"VM backend does not support");
    check(script->baseEnv->variables[0]->get<int>()==3,"unsupported program ran onload before refusal");
    check(!script->setup&&!script->faulted&&!script->module_manifest()[0].initialized,
        "preflight failure partially initialized the module");
    check(!std::dynamic_pointer_cast<ofunction>(script->functions.at(10))->linked,
        "preflight failure published tree relocation");
    script->destroy();

    script=load(module({function(0,VOID_VALUE,block({store(-1,8)})),
        function(10,INT_VALUE,returned(slot(-1)))},1));
    vm::select_backend(*script,1);
    rejects([&]{script->flush();},"VM workspace limit exceeded");
    check(script->baseEnv->variables[0]->type_tag()==VOID_VALUE&&!script->faulted,
        "workspace failure ran initialization or faulted an unstarted program");
    vm::select_backend(*script,workspace);script->flush();
    check(script->invoke(10)->get<int>()==8,"failed preflight could not be retried");
    script->active_calls=1;
    rejects([&]{vm::select_backend(*script,workspace);},"during script execution");
    script->active_calls=0;script->destroy();
}

void aliased_host_globals() {
    auto script=load(module({function(10,INT_VALUE,block({store(-1,7),returned(slot(-2))}))},2));
    script->baseEnv->variables[0]->setValue(1);
    script->baseEnv->variables[1]=script->baseEnv->variables[0];
    vm::select_backend(*script,workspace);script->flush();
    check(script->invoke(10)->get<int>()==7,"host global aliases lost shared storage identity");
    check(script->baseEnv->variables[0]->get<int>()==7,"global export overwrote an aliased write");
    // Host bindings can also change after preparation. Re-import must preserve
    // the current shared_ptr identity, not just the originally observed shape.
    script->baseEnv->variables[1]=std::make_shared<variable>(21);
    check(script->invoke(10)->get<int>()==21,"split host global binding retained stale VM alias");
    script->baseEnv->variables[1]=script->baseEnv->variables[0];
    check(script->invoke(10)->get<int>()==7,"merged host global binding was not reflected by VM import");
    script->destroy();
}

void teardown_with_unprepared_insert() {
    for(bool attempt_flush:{false,true}) {
        auto script=load(module({function(0,VOID_VALUE,block({store(-1,5)})),
            function(1,VOID_VALUE,block({store(-1,99)}))},1));
        vm::select_backend(*script,workspace);script->flush();
        auto retained=script->baseEnv->variables[0];
        insert(script,module({function(20,VOID_VALUE,block({op(32,{integer(1)})}))},1));
        if(attempt_flush)rejects([&]{script->flush();},"VM backend does not support");
        script->destroy();
        check(retained->get<int>()==99,"pending insertion prevented an initialized module's pre_destroy");
    }
}

void workspace_layout_acceptance() {
    // 256 MiB is an explicit measurement allowance, not the default-contract
    // proposal. Both cases must execute successfully before reporting usage.
    constexpr std::size_t measurement_limit=256*1024*1024;
    for(bool dense:{false,true}) {
        const int slots=dense?32768:static_cast<int>(MAX_VARIABLE_SLOTS);
        AbdStack statements;
        for(int index=dense?0:slots-1;index<slots;++index)
            statements.vs.push_back(op(4,{integer(index),integer(INT_VALUE),boolean(true),constant(17)}));
        statements.vs.push_back(returned(slot(slots-1)));
        auto source=module({function(10,INT_VALUE,op(1,{statements.toAbdValue()}),{},false,slots)});
        auto script=load(source);vm::select_backend(*script,measurement_limit);script->flush();
        check(script->invoke(10)->get<int>()==17,"legal large loader/Session frame could not read its last slot");
        const auto used=vm::workspace_usage(*script);
        check(used.used_bytes>0&&used.peak_bytes>=used.used_bytes&&used.peak_bytes<=measurement_limit,
            "workspace measurement did not include successful frame backing");
        check(script->active_calls==0,"large Session frame retained an admission after return");
        std::cout<<"vm-session locals="<<slots<<" declared="<<(dense?slots:1)
            <<" used="<<used.used_bytes<<" peak="<<used.peak_bytes<<" limit="<<used.limit_bytes<<'\n';
        script->destroy();
    }

    // A recursive frame carries a legal but sparse 1024-local header. Force a
    // workspace failure before the depth or tick limit, then reuse the exact
    // same prepared session to verify partial admission was unwound.
    auto recursive=block({op(27,{op(19,{slot(0),constant(0)}),returned(constant(0)),boolean(false)}),
        returned(op(2,{integer(10),raw({op(13,{slot(0),constant(1)})}),raw({})}))});
    auto script=load(module({function(10,INT_VALUE,recursive,{INT_VALUE},false,1024)}));
    constexpr std::size_t constrained=512*1024;
    vm::select_backend(*script,constrained);script->max_call_depth=10000;script->max_steps=1000000;script->flush();
    rejects([&]{script->invoke(10,{std::make_shared<variable>(1000)});},"VM workspace limit exceeded");
    const auto rejected=vm::workspace_usage(*script);
    check(script->active_calls==0&&script->remaining_steps>0&&rejected.peak_bytes<=constrained,
        "rejected frame leaked admission, exceeded capacity, or exhausted the wrong budget");
    check(script->invoke(10,{std::make_shared<variable>(0)})->get<int>()==0,
        "workspace failure poisoned subsequent invocation of the prepared Session");
    check(script->active_calls==0,"successful post-failure call leaked admission");
    std::cout<<"vm-session rejected-recursion used="<<rejected.used_bytes<<" peak="<<rejected.peak_bytes
        <<" limit="<<rejected.limit_bytes<<'\n';
    script->destroy();
}
}

int main() {
    try {
        lifecycle_and_rebuild();errors_and_trace();preflight_and_selection();aliased_host_globals();
        teardown_with_unprepared_insert();
        workspace_layout_acceptance();
        std::cout<<"VM session checks passed: "<<checks<<'\n';return 0;
    } catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
}
