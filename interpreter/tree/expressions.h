#ifndef AZSCRIPT_TREE_EXPRESSIONS_H
#define AZSCRIPT_TREE_EXPRESSIONS_H

// The tree executor's expression nodes. The module builder creates them from
// the validated exec model; each node's execute body is in the module of
// its concern (values, variables, control, calls, objects, generics).
#include "../library.h"

namespace azertian {
class expression {
public:
    virtual ~expression()=default;
    int return_type=VOID_VALUE;
    // 1-based index into the owning function's debug locations; 0 inherits the
    // position of the enclosing expression.
    std::uint32_t location=0;
    virtual std::shared_ptr<variable> execute(std::shared_ptr<environment> env)=0;
};
class complex_expression : public expression {
public:
    std::vector<std::shared_ptr<expression>> expressions;
    complex_expression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
    std::shared_ptr<variable> execute_body(std::shared_ptr<environment> env);
};
class functionInvocationExpression : public expression {
public:
    std::vector<std::shared_ptr<expression>> params;
    std::vector<std::shared_ptr<context_spec>> contexts;
    int function_id=0;
    functionInvocationExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varDefineExpression : public expression {
public:
    int varindex=0;
    std::shared_ptr<azertian::expression> initializer;
    varDefineExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varSetExpression : public expression {
public:
    int varindex=0;
    std::shared_ptr<azertian::expression> expression;
    varSetExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class returnExpression : public expression {
public:
    std::shared_ptr<expression> returnType;
    bool object_return=false;
    returnExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class varExpression : public expression {
public:
    int varindex=0;
    varExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class constantExpression : public expression {
public:
    std::shared_ptr<variable> value;
    explicit constantExpression(const Value& value);
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
#define AZ_BINARY_EXPRESSION(Name) \
class Name : public expression { public: \
    std::shared_ptr<expression> exp1,exp2; \
    Name()=default; \
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override; };
AZ_BINARY_EXPRESSION(addExpression)
AZ_BINARY_EXPRESSION(minusExpression)
AZ_BINARY_EXPRESSION(multiplyExpression)
AZ_BINARY_EXPRESSION(divideExpression)
AZ_BINARY_EXPRESSION(equalExpression)
AZ_BINARY_EXPRESSION(gtExpression)
AZ_BINARY_EXPRESSION(ltExpression)
AZ_BINARY_EXPRESSION(movExpression)
AZ_BINARY_EXPRESSION(whileExpression)
#undef AZ_BINARY_EXPRESSION
class ifExpression : public expression {
public:
    std::shared_ptr<expression> exp1,exp2,otherwise;
    ifExpression()=default;
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class breakExpression : public expression {
public:
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
class continueExpression : public expression {
public:
    std::shared_ptr<variable> execute(std::shared_ptr<environment> env) override;
};
namespace tree {
using V=std::shared_ptr<variable>;
using E=std::shared_ptr<environment>;
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
// exec v11: the wrapping int32 add, subtract and multiply (wire 55-57).
class wrapping_expression final:public expression {
    int opcode;
    std::shared_ptr<expression> left,right;
public:
    wrapping_expression(int opcode,std::shared_ptr<expression> left,std::shared_ptr<expression> right)
        :opcode(opcode),left(std::move(left)),right(std::move(right)) {}
    V execute(E env) override;
};
// exec v11: statements run in the enclosing scope, then the value (wire 54).
class sequence_expression final:public expression {
    std::vector<std::shared_ptr<expression>> statements;
    std::shared_ptr<expression> value;
public:
    sequence_expression(std::vector<std::shared_ptr<expression>> statements,std::shared_ptr<expression> value)
        :statements(std::move(statements)),value(std::move(value)) {}
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
}
#endif
