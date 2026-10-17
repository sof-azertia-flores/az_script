#ifndef AZSCRIPT_VM_PROGRAM_H
#define AZSCRIPT_VM_PROGRAM_H

#include "value.h"
#include "../core/debug.h"
#include <cstdint>
#include <limits>
#include <optional>
#include <span>
#include <string>
#include <vector>

namespace azertian::vm {
using Register=std::uint32_t;
using ProgramCounter=std::uint32_t;
inline constexpr std::uint32_t no_index=std::numeric_limits<std::uint32_t>::max();

// Internal instructions are independent of the exec v10 wire opcode numbers.
// A register contains either a value snapshot or a stable slot reference.
// load_slot/member_get produce references; snapshot/materialize remove aliases.
enum class Opcode : std::uint16_t {
    tick, constant, load_slot, check_declaration, declare_slot, store,
    snapshot, materialize, copy_reference, truth, unary, binary, convert,
    jump, branch_false, branch_flow, branch_returned,
    scope_enter, scope_leave, statement_enter, statement_leave,
    loop_enter, loop_next, loop_leave, set_break, set_continue,
    call, set_return, function_end,
    cleanup_enter, cleanup_leave, cleanup_end,
    member_get, object_address, object_register, object_delete,
    new_block, block_address, move_out, drop,
    bind_context, context_abi, context_default, return_typed, check_type,
    buffer_new, buffer_operation, value_compare, check_object,
    check_method_receiver, set_methods, call_virtual,
    count
};
enum class BudgetKind : std::uint32_t { expression, loop_iteration, method_entry };
enum class UnaryOperation : std::uint32_t { logical_not, negate };
enum class BinaryOperation : std::uint32_t {
    add, subtract, multiply, divide, remainder, greater, less, equal,
    not_equal, greater_equal, less_equal, bit_and, bit_or, bit_xor,
    shift_left, shift_right, unsigned_shift_right
};

// Fixed-size records keep dispatch linear. Variable length arguments and
// bindings live in immutable side tables. Unused operands must be no_index.
//
// Common layouts (r=result, a=left, b=right, k=operand, t=target):
//   constant(r,k=constant); load_slot(r,k=bit-cast signed slot);
//   check_declaration(k=slot); declare_slot(a=optional initializer,k=slot);
//   store(r,a=slot reference,b=value) returns a post-store value snapshot;
//   snapshot/materialize/copy_reference/truth(r,a);
//   unary/binary/convert(r,a[,b],k=operation/type);
//   jump/branch_flow/branch_returned(t); branch_false(a=boolean,t);
//   loop_next(t=head,k=exit) consumes break/continue after the body;
//   call/call_virtual(r,k=OperationData); set_return(a,k=owned flag);
//   cleanup_enter/leave/end(k=CleanupRegion);
//   bind_context(r=bound context register,k=Context);
//   context_abi/context_default/buffer_new(r,a=bound context register);
//   return_typed/check_type(r,a=value,b=bound context register);
//   value_compare(r,a,b,k=bound context register);
//   check_object(r,a=value,b=bound context register,k=OperationData).
//
// Control setters only set frame flags and evaluate to void. Only explicit
// branch_flow/branch_returned/loop_next consume them: even hand-written v10
// return/break operands retain evaluation of their enclosing expression.
struct Instruction {
    Opcode opcode=Opcode::function_end;
    Register result=no_index,left=no_index,right=no_index;
    std::uint32_t operand=no_index;
    ProgramCounter target=no_index;
};
struct SourceMapEntry {
    std::uint32_t node=0;
    // Effective inherited location; zero means the function header/unknown.
    std::uint32_t location=0;
};
// The debug record is the runtime's one function_debug type, shared with the
// tree engine so both report trace frames from the same record.
using SourcePosition=source_position;
using DebugInfo=function_debug;
struct Context {
    // References index a function's bound hidden contexts. Fixed contexts use
    // indices into that same function's context pool, never frame pointers.
    std::optional<std::uint32_t> reference;
    int abi=VOID_VALUE,kind=0;
    std::uint32_t width=1;
    std::optional<std::uint32_t> factory,placement;
    std::vector<std::uint32_t> contexts,placement_contexts;
    std::optional<std::uint32_t> element;
};
struct MethodBinding {
    std::uint32_t target=no_index;
    std::vector<std::uint32_t> contexts;
};
struct OperationData {
    std::vector<Register> arguments;
    std::vector<std::uint32_t> contexts;
    std::vector<MethodBinding> methods;
    std::vector<int> slot_types;
    std::optional<std::uint32_t> target;
    int immediate=0;
    int return_type=VOID_VALUE;
    bool manual=false;
};
enum class TargetKind : std::uint8_t { script, host, builtin, dynamic };
struct CallTarget {
    int function_id=0;
    TargetKind kind=TargetKind::script;
    std::uint32_t function=no_index;
    int return_type=VOID_VALUE;
    std::vector<int> param_types;
    std::uint32_t hidden_count=0;
    int entry_kind=0;
    // Legacy builtins and unsigned host callbacks have individually checked,
    // sometimes variable arity. Dynamic targets retain an unresolved ID and
    // are resolved at CALL time, after argument effects, by the VM boundary.
    // They are not an escape to the old expression executor.
    bool dynamic_signature=false;
};
struct CleanupRegion {
    ProgramCounter enter=no_index,body=no_index,leave=no_index;
    ProgramCounter finalizer=no_index,finish=no_index,after=no_index;
    bool on_error=false;
};
struct FunctionRecord {
    int function_id=0,return_type=VOID_VALUE,entry_kind=0;
    // Lifecycle IDs 0/1 are module-local and may occur more than once. They
    // are entered by function index and are never public CALL targets.
    bool lifecycle=false;
    std::vector<int> param_types;
    std::uint32_t local_count=0,register_count=0,bound_context_count=0,hidden_count=0;
    std::uint32_t global_offset=0,global_count=0,node_count=0;
    ProgramCounter begin=0,end=0; // Half-open, nonempty.
    std::optional<DebugInfo> debug;
    std::vector<Context> contexts;
    std::vector<OperationData> operations;
    std::vector<CleanupRegion> cleanups;
};
struct ExecutableProgram {
    std::vector<Instruction> instructions;
    std::vector<SourceMapEntry> sources;
    std::vector<Value> constants;
    std::vector<FunctionRecord> functions;
    std::vector<CallTarget> targets;
};

// Throws invalid_argument before a program can be published. It validates all
// instructions, including unreachable ones; CFG joins must preserve lexical,
// statement, loop and cleanup stacks. Registers start as void, never poison.
void verify_program(const ExecutableProgram& program);
const char* opcode_name(Opcode opcode) noexcept;
// Migration backends must explicitly admit every opcode they can execute.
// This scans all functions before execution and never selects another engine.
void require_capabilities(const ExecutableProgram& program,std::span<const Opcode> supported);
}
#endif
