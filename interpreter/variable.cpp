// The value cell shared by both engines, the object model and the host API.
#include "library.h"
#include "blocks.h"
#include "core/value_ops.h"
#include <stdexcept>

namespace azertian {
using V=std::shared_ptr<variable>;
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
std::string value_to_string(const V& v) {
    if(!v)throw std::invalid_argument("Null variable");
    return value_ops::to_string(value_access::read(*v));
}
}
