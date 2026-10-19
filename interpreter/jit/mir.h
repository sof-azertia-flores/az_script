#ifndef AZSCRIPT_JIT_MIR_H
#define AZSCRIPT_JIT_MIR_H

#include "analysis.h"
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace azertian::jit {
// Native code's view of one function: operations on shadow slots (registers,
// then locals, then temporaries), the frame's control flags and the step
// count, plus calls into FrameAccess. Every exit names the VM pc whose
// instruction runs next and how the VM continues.
enum class ExitKind : std::uint8_t {
    step,   // the VM runs exactly this instruction, then native code resumes after it
    redo,   // the VM runs from here (an error, a short budget or a failed guard)
    yield   // stress only: resume here at once
};
struct ExitSite {
    vm::ProgramCounter pc=0;
    ExitKind kind=ExitKind::step;
    std::uint32_t statement_depth=0,loop_depth=0;
};
enum class Code : std::uint8_t {
    steps,            // if steps < imm: exit
    tick,             // --steps
    set,              // V[a] = {imm, tag}
    copy,             // V[a] = V[b]
    forget,           // V[a].tag = absent
    read_reference,   // V[a] = value through register b's reference; exit if not a shadow value
    write_reference,  // store V[b] through register a's reference; exit on failure
    truth,            // exit unless V[a] is a boolean
    branch_false,     // if V[a] is false: goto target
    jump,             // goto target
    branch_flags,     // if flags & tag: goto target
    set_flags,        // flags |= tag
    loop_next,        // broken ? (clear broken, goto b) : (clear continued, goto target)
    binary,           // V[a] = V[b] (tag=BinaryOperation) V[c]; exit if it fails
    unary,            // V[a] = (tag=UnaryOperation) V[b]; exit if it fails
    convert,          // V[a] = convert V[b] to imm; exit if it fails
    reference_local,  // register a references local b; V[a] absent; exit if undeclared
    reference_global, // register a references global b; V[a] absent; exit if outside the module
    copy_reference,   // register a references what register b does; V[a] absent
    drop_reference,   // register a stops referencing (before a value is written)
    declare,          // bind local a to a new cell holding V[b] (or void when b is none); exit if it allocates
    flush_local,      // write V[a]'s shadow into local b's cell before a release
    enter_scope,
    leave_scope,
    check_undeclared, // exit unless local a is undeclared
    set_result,       // the frame's result = V[a], return location imm; flags |= returned
    exit              // unconditional exit
};
inline constexpr std::uint32_t none=0xffffffffu;
struct Op {
    Code code=Code::exit;
    std::uint8_t tag=0;
    std::uint32_t a=none,b=none,c=none;
    std::uint64_t imm=0;
    std::uint32_t target=none;        // op index
    std::uint32_t exit=none;          // exit site
    vm::ProgramCounter pc=0;          // the VM instruction the op belongs to
    // The tags the analysis allows for the value written to slot a (set,
    // copy, read_reference, binary, unary, convert, declare's local); a
    // backend may rely on them, and the portable one checks them.
    TagSet tags=0;
};
struct Function {
    std::uint32_t function=0;
    std::uint32_t registers=0,locals=0,temporaries=0;
    std::vector<Op> ops;
    std::vector<ExitSite> exits;
    // Per pc-begin: the op where native code resumes, or none.
    std::vector<std::uint32_t> resume;
    // The analysis state at each resume point, for filling shadows.
    std::vector<bool> fill_registers,fill_locals;   // per slot: may hold a shadow at all
    // Per pc-begin with a resume point, the tags each slot's shadow may have
    // on entry (0 when the slot enters absent: dead registers, unbound or
    // memory-resident locals); empty elsewhere.
    std::vector<std::vector<TagSet>> resume_tags;
    // Per exit site, the shadowable registers live at its pc: the VM reads no
    // other register before writing it, so only these are written back.
    std::vector<std::vector<std::uint32_t>> exit_registers;
    const FunctionAnalysis* analysis=nullptr;
    vm::ProgramCounter begin=0,end=0;
    std::uint32_t slots() const noexcept{return registers+locals+temporaries;}
};
// Stress resumes at every reachable pc and yields after each instruction.
Function build(const vm::ExecutableProgram& program,const FunctionAnalysis& analysis,bool stress);
// The program's checks of a built function (targets, slots, exit sites).
void verify(const Function& function);
// A readable listing for AZSCRIPT_JIT_DUMP.
std::string dump(const Function& function);
}
#endif
