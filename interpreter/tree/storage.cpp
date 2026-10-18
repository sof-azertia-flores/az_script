#include "internal.h"
#include "../core/lifecycle.h"
#include "../core/value_ops.h"
#include <stdexcept>

namespace azertian {
using namespace tree;
void variable::clear() noexcept {
    // The owning storage variable ended: its object's address expires. Value
    // itself only retains a handle; it never owns script cleanup responsibility.
    if(auto block=value_.object_handle();block&&block->owner==this)blocks::abandon(block);
    value_=Value{};
}
variable::~variable(){clear();}
const Value& value_access::read(const variable& source) noexcept {return source.value_;}
void value_access::assign(variable& destination,Value value) noexcept {
    destination.replace_value(std::move(value));
}
void variable::replace_value(Value value) noexcept {
    // The argument is already a complete snapshot, even when it came from a
    // field of the object that clear() is about to abandon.
    clear();value_.swap(value);
}
#define VALUE_IMPL(T) \
variable::variable(T v){setValue(std::move(v));} \
void variable::setValue(T v){replace_value(Value(std::move(v)));}
VALUE_IMPL(int)
VALUE_IMPL(address)
VALUE_IMPL(std::string)
VALUE_IMPL(float)
VALUE_IMPL(double)
VALUE_IMPL(bool)
VALUE_IMPL(std::shared_ptr<slot_block>)
#undef VALUE_IMPL
variable::variable(const char* value){setValue(value);}
void variable::setValue(const char* value){if(value)setValue(std::string(value));else setValue(nullptr);}
variable::variable(std::nullptr_t){}
void variable::setValue(std::nullptr_t){clear();}
std::shared_ptr<slot_block> variable::object_handle() const noexcept {
    return value_.object_handle();
}
void variable::swap_storage(variable& other) noexcept {
    value_.swap(other.value_);
}
void variable::copy_value(const variable& v) {
    replace_value(v.value_);
}
variable::variable(const variable& v):name(v.name),value_(v.value_){}
variable& variable::operator=(const variable& v){
    if(this!=&v){std::string next_name(v.name);replace_value(v.value_);name.swap(next_name);}return *this;
}
void variable::copy_from(V v){if(!v)throw std::invalid_argument("Null variable");if(v.get()!=this)copy_value(*v);}
V variable::deepCopy(){auto v=std::make_shared<variable>(*this);v->name.clear();return v;}
function_frame::function_frame(std::shared_ptr<ownership::Arena> arena)
    :temporary_storage_(std::move(arena)),temporaries(temporary_storage_.record().values) {}
environment::environment(std::shared_ptr<ownership::Arena> arena,const std::shared_ptr<environment>& parent,
                         ownership::ScopeId caller,std::shared_ptr<function_frame> frame,bool frame_root)
    :ownership_(std::move(arena),{parent?parent->ownership_id():ownership::ScopeId{},caller,
                                  frame?frame->temporary_id():ownership::TempListId{}}),
     owned_pointer(ownership_.record().owned),parent(parent),script(ownership_.record().script),frame(std::move(frame)),
     closing(ownership_.record().closing),frame_root(frame_root) {}
environment::environment(const ownership::Scope& shared,const std::shared_ptr<environment>& parent,
                         std::shared_ptr<function_frame> frame,bool frame_root)
    :ownership_(shared,ownership::Scope::view_t{}),owned_pointer(ownership_.record().owned),parent(parent),
     script(ownership_.record().script),frame(std::move(frame)),closing(ownership_.record().closing),frame_root(frame_root) {}
// What the scope still owns ends before its frame's temporaries do.
environment::~environment() {ownership_.abandon();}
namespace {
// The tree's view of a closing scope's locals for lifecycle::ScopeFinalizer.
class closing_locals final:public lifecycle::ScopeLocals {
    environment& scope_;
public:
    explicit closing_locals(environment& scope) noexcept:scope_(scope) {}
    std::vector<V> leftovers() const override {
        std::vector<V> result;
        const auto& frame=scope_.frame;
        if(!frame)return result;
        if(scope_.frame_root)for(std::size_t i=0;i<frame->param_count&&i<frame->slots.size();++i)result.push_back(frame->slots[i]);
        for(auto index:scope_.declared_slots)result.push_back(frame->slots[index]);
        return result;
    }
    void undeclare() noexcept override {
        if(scope_.frame)for(auto index:scope_.declared_slots)scope_.frame->slots[index].reset();
        scope_.declared_slots.clear();
    }
};
// True when cleanup could run no code and release nothing: it then only
// closes the scope and undeclares its locals.
bool nothing_to_release(const environment& scope) noexcept {
    if(!scope.owned_pointer.empty())return false;
    const auto& frame=scope.frame;
    if(!frame)return true;
    if(scope.frame_root&&!frame->temporaries.empty())return false;
    const auto owning=[&](std::size_t index){const auto& slot=frame->slots[index];return slot&&blocks::owns(*slot);};
    if(scope.frame_root)for(std::size_t i=0;i<frame->param_count&&i<frame->slots.size();++i)if(owning(i))return false;
    for(auto index:scope.declared_slots)if(owning(index))return false;
    return true;
}
}
void environment::cleanup(const std::shared_ptr<environment>& self) {
    if(closing)return;
    closing_locals locals(*this);
    if(nothing_to_release(*this)){closing=true;locals.undeclare();return;}
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    lifecycle::ScopeFinalizer cleanup(ownership_.id(),frame_root&&frame?frame->temporary_id():ownership::TempListId{},locals);
    lifecycle::drive(cleanup,site());
    cleanup.finish();
}
V environment::getVariable(std::string name) {
    for(auto& v:variables) if(v->name==name)return v;
    if(auto p=parent.lock())return p->getVariable(std::move(name));
    return nullptr;
}
V environment::getVariable(int index) {
    if(index<0) {
        const auto relative=static_cast<std::uint64_t>(-static_cast<std::int64_t>(index)-1);
        auto owner=script.lock();
        if(!owner||owner->closed||!owner->baseEnv)throw std::runtime_error("Script is closed");
        // The base environment is also the host's access point. Its negative
        // ids address the complete global array; an executing frame is limited
        // to the module containing the callee, with that module's relocation.
        const auto count=frame?frame->global_count:owner->baseEnv->variables.size();
        const auto offset=frame?frame->global_offset:0;
        if(relative>=count||offset>owner->baseEnv->variables.size()||
           relative>=owner->baseEnv->variables.size()-offset)
            throw std::runtime_error("Global variable slot is outside its module: "+std::to_string(index));
        return owner->baseEnv->variables[offset+static_cast<std::size_t>(relative)];
    }
    if(!frame||static_cast<std::size_t>(index)>=frame->slots.size())
        throw std::runtime_error("Local variable slot is outside its function: "+std::to_string(index));
    auto value=frame->slots[static_cast<std::size_t>(index)];
    if(!value)throw std::runtime_error("Variable slot is not declared in this scope: "+std::to_string(index));
    return value;
}
bool environment::testForVar(std::string name){for(auto& v:variables)if(v->name==name)return true;return false;}
std::string value_to_string(const V& v) {
    if(!v)throw std::invalid_argument("Null variable");
    return value_ops::to_string(value_access::read(*v));
}
}
