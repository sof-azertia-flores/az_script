#ifndef AZSCRIPT_JIT_ANALYSIS_H
#define AZSCRIPT_JIT_ANALYSIS_H

#include "../vm/program.h"
#include "../vm/frames.h"
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

namespace azertian::jit {
// Value tags a register or local may hold, one bit per public type tag
// (INT_VALUE...). Scalar frames never hold objects.
using TagSet=std::uint16_t;
constexpr TagSet tag_bit(int tag) noexcept{return static_cast<TagSet>(1u<<tag);}
// Every value a scalar frame can hold.
inline constexpr TagSet any_scalar=tag_bit(INT_VALUE)|tag_bit(STRING_VALUE)|tag_bit(FLOAT_VALUE)|
    tag_bit(DOUBLE_VALUE)|tag_bit(BOOLEAN_VALUE)|tag_bit(VOID_VALUE)|tag_bit(ADDRESS_VALUE);
// Values whose bits fit a shadow word: everything but strings.
inline constexpr TagSet shadowable=any_scalar&~tag_bit(STRING_VALUE);

// What a register holds before an instruction. A value register's tags are
// those of its own value; a reference reads a local's current cell (whose
// tags are the local's), a global, or (orphan) a cell whose local binding
// has ended or been replaced. Unknown is any of these.
struct RegisterState {
    enum class Kind : std::uint8_t {value,local,global,orphan,unknown};
    Kind kind=Kind::value;
    TagSet tags=tag_bit(VOID_VALUE);
    std::uint32_t index=0;            // local slot or module-relative global
    bool operator==(const RegisterState&) const=default;
};
// A local is declared on every path, on none, or on some (a statement can
// leave its block before a later declaration runs). depth is the scope count
// when it was declared: the scope_leave at that depth releases it.
// Parameters are never released by a scope.
struct LocalState {
    enum class Declared : std::uint8_t {no,yes,maybe};
    Declared declared=Declared::no;
    std::uint32_t depth=0;
    TagSet tags=0;                    // of the bound cell's value
    bool bound() const noexcept{return declared!=Declared::no;}
    bool operator==(const LocalState&) const=default;
};
inline constexpr std::uint32_t parameter_depth=std::numeric_limits<std::uint32_t>::max();
// The abstract VM frame before one instruction. The verifier makes scope,
// statement and loop depths equal at every join, so they are static per pc;
// the declaration list and scope marks are not, and stay in VM memory.
struct FrameState {
    std::vector<RegisterState> registers;
    std::vector<LocalState> locals;
    std::uint32_t scope_depth=0,statement_depth=0,loop_depth=0;
    bool operator==(const FrameState&) const=default;
};

// Which tags a value may hold after each operation, sampled from value_ops
// itself so the analysis cannot disagree with the interpreter.
TagSet binary_result(BinaryOperation operation,TagSet left,TagSet right);
TagSet unary_result(UnaryOperation operation,TagSet operand);
TagSet convert_result(int target,TagSet operand);

struct FunctionAnalysis {
    bool eligible=false;
    std::string reason;                       // why not, when not eligible
    std::uint32_t function=0;
    vm::ProgramCounter begin=0,end=0;
    // Indexed by pc-begin: the state before the instruction, if reachable on
    // a path that does not fail.
    std::vector<bool> reached;
    std::vector<FrameState> states;
    // Register values each instruction may read later (live before it).
    std::vector<std::vector<bool>> live;
    // Per function, whether a register's or local's value stays out of
    // strings everywhere, so its value can live in a shadow word.
    std::vector<bool> shadow_registers,shadow_locals;
};
// The abstract interpretation of one scalar function. Object frames, cleanup
// regions, locals declared at different depths on joining paths and
// oversized functions are
// not eligible (the VM runs them as before).
FunctionAnalysis analyze(const vm::ExecutableProgram& program,std::uint32_t function,
                         const vm::FrameLayout& layout,bool object_frame);
// The value tags a read of this register yields in this state.
TagSet read_tags(const FrameState& state,std::uint32_t reg);
}
#endif
