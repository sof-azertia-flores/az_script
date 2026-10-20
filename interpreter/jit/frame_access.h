#ifndef AZSCRIPT_JIT_FRAME_ACCESS_H
#define AZSCRIPT_JIT_FRAME_ACCESS_H

#include "../core/value.h"
#include "shadow.h"
#include "../vm/program.h"
#include <cstdint>
#include <optional>

namespace azertian::jit {
// The executor's frame as native code sees it. Every structural operation is
// exactly what the VM instruction does (the same retain, release, allocation
// and declaration order); one that would fail or allocate returns false and
// changes nothing, and native code then lets the VM run the instruction.
// Nothing here throws.
class FrameAccess {
public:
    virtual ~FrameAccess()=default;
    // Shadow transfer at entry and exit, without copying values. The reads
    // are false for a reference register or an unbound local; out is then
    // untouched, and it is nullopt for a value without a shadow (a string).
    virtual bool register_shadow(std::uint32_t reg,std::optional<Shadow>& out) const noexcept=0;
    virtual void set_register_shadow(std::uint32_t reg,const Shadow& value) noexcept=0;
    virtual bool local_shadow(std::uint32_t local,std::optional<Shadow>& out) const noexcept=0;
    virtual void set_local_shadow(std::uint32_t local,const Shadow& value) noexcept=0;
    // returned, broken and continued as bits 0, 1 and 2.
    virtual std::uint8_t flags() const noexcept=0;
    virtual void set_flags(std::uint8_t flags) noexcept=0;
    virtual std::uint64_t steps() const noexcept=0;
    virtual void set_steps(std::uint64_t steps) noexcept=0;
    virtual void set_depths(std::uint32_t statement,std::uint32_t loop) noexcept=0;
    virtual void set_pc(vm::ProgramCounter pc) noexcept=0;
    // Structural operations of load_slot, copy_reference, store, scope_enter,
    // scope_leave, declare_slot and set_return.
    virtual bool reference_local(std::uint32_t reg,std::uint32_t local) noexcept=0;
    virtual bool reference_global(std::uint32_t reg,std::uint32_t relative) noexcept=0;
    virtual void copy_reference(std::uint32_t destination,std::uint32_t source) noexcept=0;
    virtual void drop_reference(std::uint32_t reg) noexcept=0;
    virtual bool read_reference(std::uint32_t reg,Value& out) const noexcept=0;
    virtual bool write_reference(std::uint32_t reg,const Value& value) noexcept=0;
    virtual bool declare(std::uint32_t local,const Value& initial) noexcept=0;
    virtual void enter_scope() noexcept=0;
    virtual void leave_scope() noexcept=0;
    virtual bool undeclared(std::uint32_t local) const noexcept=0;
    virtual void set_result(const Value& value,std::uint32_t location) noexcept=0;
};
}
#endif
