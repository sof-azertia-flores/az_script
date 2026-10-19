// Blocks, conditionals, loops and compiler cleanup regions.
#include "internal.h"
#include <stdexcept>

namespace azertian {
namespace tree {
V cleanup_expression::execute(E env) {
    pending_error failure;
    try {tick(env);scoped_execute(body,env);}catch(...){failure.capture_current();}
    if(!on_error||failure) {
        auto frame=env->frame;
        const bool returned=frame&&frame->returned,broken=frame&&frame->break_requested,
            continued=frame&&frame->continue_requested;
        const auto result=frame?frame->result:nullptr;
        auto location=frame?frame->location:0;
        const auto return_location=frame?frame->return_location:0;
        const bool had_failure=static_cast<bool>(failure);
        if(frame){frame->returned=false;frame->break_requested=false;frame->continue_requested=false;}
        try {scoped_execute(cleanup,env);}catch(...){
            if(frame&&!had_failure)location=frame->location;
            failure.capture_current();
        }
        if(frame){frame->returned=returned;frame->break_requested=broken;frame->continue_requested=continued;frame->result=result;
            frame->location=location;frame->return_location=return_location;}
    }
    if(failure)failure.rethrow();
    return nil();
}
}
using namespace tree;
V complex_expression::execute_body(E env) {
    tick(env);
    for(auto& e:expressions) {
        if(env->frame&&(env->frame->returned||env->frame->break_requested||env->frame->continue_requested)) break;
        at_location location(env,e->location);
        statement_scope(env,[&]{return run(e,env);});
    }
    return nil();
}
V complex_expression::execute(E env) {
    auto scope=std::make_shared<environment>(env->ownership_arena(),env,ownership::ScopeId{},env->frame);scope->script=env->script;
    return with_cleanup(scope,[&]{at_location location(scope,this->location);return execute_body(scope);});
}
namespace {
// Temporaries of a condition end once its truth value is known.
bool condition(const std::shared_ptr<expression>& test,const E& env) {
    at_location location(env,test->location);
    bool value=false;
    statement_scope(env,[&]{value=truth(run(test,env));return nil();});
    return value;
}
}
V ifExpression::execute(E env){tick(env);if(condition(exp1,env)) return scoped_execute(exp2,env);return otherwise?scoped_execute(otherwise,env):nil();}
V whileExpression::execute(E env) {
    tick(env);
    if(!env->frame) throw std::runtime_error("While outside function");
    auto frame=env->frame;
    ++frame->loop_depth;
    try {
        while(!frame->returned&&condition(exp1,env)) {
            tick(env);scoped_execute(exp2,env);
            if(frame->break_requested) {frame->break_requested=false;break;}
            frame->continue_requested=false;
        }
    } catch(...) {--frame->loop_depth;throw;}
    --frame->loop_depth;
    return nil();
}
V breakExpression::execute(E env) {
    tick(env);
    if(!env->frame||env->frame->loop_depth==0) throw std::runtime_error("Break outside loop");
    env->frame->break_requested=true;
    return nil();
}
V continueExpression::execute(E env) {
    tick(env);
    if(!env->frame||env->frame->loop_depth==0) throw std::runtime_error("Continue outside loop");
    env->frame->continue_requested=true;
    return nil();
}
}
