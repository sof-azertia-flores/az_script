#ifndef AZSCRIPT_TREE_NODES_H
#define AZSCRIPT_TREE_NODES_H

// The tree executor's own expression nodes; the module builder creates them
// from the validated exec model and nodes.cpp defines what they execute.
#include "internal.h"

namespace azertian::tree {
class select_expression final:public expression {
    std::shared_ptr<expression> condition,when_true,when_false;
public:
    select_expression(std::shared_ptr<expression> condition,std::shared_ptr<expression> when_true,std::shared_ptr<expression> when_false)
        :condition(std::move(condition)),when_true(std::move(when_true)),when_false(std::move(when_false)) {}
    V execute(E env) override;
};
class convert_expression final:public expression {
    int target;
    std::shared_ptr<expression> value;
public:
    convert_expression(int target,std::shared_ptr<expression> value):target(target),value(std::move(value)) {}
    V execute(E env) override;
};
class bitwise_expression final:public expression {
    int opcode;
    std::shared_ptr<expression> left,right;
public:
    bitwise_expression(int opcode,std::shared_ptr<expression> left,std::shared_ptr<expression> right)
        :opcode(opcode),left(std::move(left)),right(std::move(right)) {}
    V execute(E env) override;
};
class extra_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> a,b;
public:
    extra_expression(std::string op,std::shared_ptr<expression> a,std::shared_ptr<expression> b=nullptr)
        :op(std::move(op)),a(std::move(a)),b(std::move(b)) {}
    V execute(E env) override;
};
// Compiler-generated base cleanup. It does not encode classes or object layouts:
// both operands are ordinary code, and the finalizer is usually a function call.
class cleanup_expression final:public expression {
    std::shared_ptr<expression> body,cleanup;
    bool on_error;
public:
    cleanup_expression(std::shared_ptr<expression> body,std::shared_ptr<expression> cleanup,bool on_error)
        :body(std::move(body)),cleanup(std::move(cleanup)),on_error(on_error) {}
    V execute(E env) override;
};
class object_expression final:public expression {
    std::string op;
    std::shared_ptr<expression> value;
    int offset=0;
    std::optional<int> destructor;
    bool manual=false;
public:
    std::vector<std::shared_ptr<context_spec>> contexts;
    object_expression(std::string operation,std::shared_ptr<expression> value,int number=0,bool manual=false)
        :op(std::move(operation)),value(std::move(value)),manual(manual) {
        if(op=="oa")offset=number;
    }
    object_expression(std::shared_ptr<expression> value,std::optional<int> destructor,bool manual)
        :op("ob"),value(std::move(value)),destructor(destructor),manual(manual) {}
    bool is_member_address() const noexcept {return op=="oa";}
    int* destructor_address(){return destructor?&*destructor:nullptr;}
    V execute(E env) override;
};
// Only the decoder can construct this member access. Raw mem_get still guards
// object storage, including calls made before a constructor has registered it.
class member_access_expression final:public expression {
    std::shared_ptr<expression> pointer;
public:
    explicit member_access_expression(std::shared_ptr<expression> pointer):pointer(std::move(pointer)) {}
    V execute(E env) override;
};
// Literal object storage. The compiler emits these only for its own lowering:
// scripts cannot name a block, and only method code sees its address as this.
class new_block_expression final:public expression {
    std::size_t size;
public:
    explicit new_block_expression(std::size_t size):size(size) {}
    V execute(E env) override;
};
class block_address_expression final:public expression {
    std::shared_ptr<expression> value;
public:
    explicit block_address_expression(std::shared_ptr<expression> value):value(std::move(value)) {}
    V execute(E env) override;
};
class move_out_expression final:public expression {
    int slot;
public:
    explicit move_out_expression(int slot):slot(slot) {}
    V execute(E env) override;
};
class drop_expression final:public expression {
    std::shared_ptr<expression> target;
public:
    explicit drop_expression(std::shared_ptr<expression> target):target(std::move(target)) {}
    V execute(E env) override;
};
class context_expression final:public expression {
    int opcode;
    std::shared_ptr<context_spec> context;
    std::shared_ptr<expression> value;
public:
    context_expression(int opcode,std::shared_ptr<context_spec> context,std::shared_ptr<expression> value={})
        :opcode(opcode),context(std::move(context)),value(std::move(value)) {}
    V execute(E env) override;
};
struct method_spec {int function_id;std::vector<std::shared_ptr<context_spec>> contexts;};
class set_methods_expression final:public expression {
    std::shared_ptr<expression> pointer;
    std::shared_ptr<const method_table> cached;
public:
    std::vector<std::shared_ptr<method_spec>> entries;
    explicit set_methods_expression(std::shared_ptr<expression> pointer):pointer(std::move(pointer)) {}
    V execute(E env) override;
};
class call_virtual_expression final:public expression {
    std::size_t slot;
    int return_type;
    std::vector<std::shared_ptr<expression>> arguments;
public:
    call_virtual_expression(int slot,int return_type,std::vector<std::shared_ptr<expression>> arguments)
        :slot(static_cast<std::size_t>(slot)),return_type(return_type),arguments(std::move(arguments)) {}
    V execute(E env) override;
};
class check_object_expression final:public expression {
    std::shared_ptr<expression> value;
    std::shared_ptr<context_spec> context;
    std::vector<int> slot_types;
public:
    check_object_expression(std::shared_ptr<expression> value,std::shared_ptr<context_spec> context,std::vector<int> slot_types)
        :value(std::move(value)),context(std::move(context)),slot_types(std::move(slot_types)) {}
    V execute(E env) override;
};
class buffer_new_expression final:public expression {
    std::shared_ptr<context_spec> context;
public:
    explicit buffer_new_expression(std::shared_ptr<context_spec> context):context(std::move(context)) {}
    V execute(E env) override;
};
class buffer_operation_expression final:public expression {
    int operation;std::shared_ptr<expression> receiver;
    std::vector<std::shared_ptr<expression>> arguments;
public:
    buffer_operation_expression(int operation,std::shared_ptr<expression> receiver,std::vector<std::shared_ptr<expression>> arguments)
        :operation(operation),receiver(std::move(receiver)),arguments(std::move(arguments)) {}
    V execute(E env) override;
};
class value_compare_expression final:public expression {
    std::shared_ptr<context_spec> context;std::shared_ptr<expression> left,right;
public:
    value_compare_expression(std::shared_ptr<context_spec> context,std::shared_ptr<expression> left,std::shared_ptr<expression> right)
        :context(std::move(context)),left(std::move(left)),right(std::move(right)) {}
    V execute(E env) override;
};
}
#endif
