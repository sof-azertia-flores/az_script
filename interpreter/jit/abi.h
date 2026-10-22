#ifndef AZSCRIPT_JIT_ABI_H
#define AZSCRIPT_JIT_ABI_H

/* The C interface between the runtime and the JIT module (libazscript-jit).
 * The module sees MIR as plain data, native state as plain memory and the
 * runtime only through the helper functions below; it includes no runtime
 * header. Both sides check AZJIT_ABI_VERSION and the layout hash. */
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define AZJIT_ABI_VERSION 3u
#define AZJIT_ABSENT 0xffu
/* Public value tags (INT_VALUE...) a shadow can carry. */
#define AZJIT_TAG_INT 0u
#define AZJIT_TAG_FLOAT 2u
#define AZJIT_TAG_DOUBLE 3u
#define AZJIT_TAG_BOOLEAN 4u
#define AZJIT_TAG_VOID 5u
#define AZJIT_TAG_ADDRESS 7u
/* Flags: returned, broken, continued. */
#define AZJIT_RETURNED 1u
#define AZJIT_BROKEN 2u
#define AZJIT_CONTINUED 4u

typedef struct azjit_shadow {uint64_t bits; uint8_t tag; uint8_t padding[7];} azjit_shadow;

/* Codes in jit::Code order. */
enum {
    AZJIT_STEPS,AZJIT_TICK,AZJIT_SET,AZJIT_COPY,AZJIT_FORGET,AZJIT_READ_REFERENCE,AZJIT_WRITE_REFERENCE,
    AZJIT_TRUTH,AZJIT_BRANCH_FALSE,AZJIT_JUMP,AZJIT_BRANCH_FLAGS,AZJIT_SET_FLAGS,AZJIT_LOOP_NEXT,
    AZJIT_BINARY,AZJIT_UNARY,AZJIT_CONVERT,AZJIT_REFERENCE_LOCAL,AZJIT_REFERENCE_GLOBAL,AZJIT_COPY_REFERENCE,
    AZJIT_DROP_REFERENCE,AZJIT_DECLARE,AZJIT_FLUSH_LOCAL,AZJIT_ENTER_SCOPE,AZJIT_LEAVE_SCOPE,
    AZJIT_CHECK_UNDECLARED,AZJIT_SET_RESULT,AZJIT_RESERVE_BLOCK,AZJIT_EXIT
};
#define AZJIT_NONE 0xffffffffu

typedef struct azjit_op {
    uint8_t code,tag;
    uint16_t tags;
    uint32_t a,b,c,target,exit,pc;
    uint64_t imm;
} azjit_op;

/* Every helper takes the frame passed in azjit_state and does exactly what
 * the corresponding VM instruction does; a zero int result means it would
 * fail and nothing changed. None throws or unwinds. */
typedef struct azjit_helpers {
    int (*reference_local)(void* frame,uint32_t reg,uint32_t local);
    int (*reference_global)(void* frame,uint32_t reg,uint32_t relative);
    void (*copy_reference)(void* frame,uint32_t destination,uint32_t source);
    void (*drop_reference)(void* frame,uint32_t reg);
    int (*read_reference)(void* frame,uint32_t reg,azjit_shadow* out);
    int (*write_reference)(void* frame,uint32_t reg,uint64_t bits,uint32_t tag);
    int (*declare)(void* frame,uint32_t local,uint64_t bits,uint32_t tag);
    void (*flush_local)(void* frame,uint32_t local,uint64_t bits,uint32_t tag);
    void (*enter_scope)(void* frame);
    void (*leave_scope)(void* frame);
    int (*undeclared)(void* frame,uint32_t local);
    void (*set_result)(void* frame,uint64_t bits,uint32_t tag,uint32_t location);
    int (*binary)(uint32_t operation,uint64_t left,uint32_t left_tag,uint64_t right,uint32_t right_tag,azjit_shadow* out);
    int (*unary)(uint32_t operation,uint64_t operand,uint32_t tag,azjit_shadow* out);
    int (*convert)(uint32_t target,uint64_t operand,uint32_t tag,azjit_shadow* out);
    int (*reserve_block)(void);
} azjit_helpers;

typedef struct azjit_function {
    uint32_t abi;
    /* Slots are the registers, then the locals, then native temporaries. */
    uint32_t slots,registers,locals;
    uint32_t op_count;
    const azjit_op* ops;
    /* Resume points: the op each starts at and, per slot, the tags its shadow
     * may have on entry (resume_count rows of slots entries; 0 = absent). */
    uint32_t resume_count;
    const uint32_t* resume_ops;
    const uint16_t* resume_tags;
    /* Per exit site, the registers live at its pc: exit_live[exit_live_offsets[s]
     * .. exit_live_offsets[s+1]). The runtime writes back only these registers
     * and every local, so native code stores nothing else. */
    uint32_t exit_count;
    const uint32_t* exit_live_offsets;
    const uint32_t* exit_live;
    const azjit_helpers* helpers;
    const char* name;
} azjit_function;

/* What native code runs on: the shadows (filled by the runtime before entry,
 * written back by it after the exit), the control flags, the step count and
 * the resume point. The entry returns the exit site it left through. */
typedef struct azjit_state {
    azjit_shadow* slots;
    void* frame;
    uint64_t steps;
    uint32_t resume;
    uint8_t flags;
} azjit_state;
typedef uint32_t (*azjit_entry)(azjit_state* state);

typedef struct azjit_backend {
    uint32_t abi;
    uint64_t layout;
    const char* name;
    /* NULL with a message in error when the function cannot be compiled. */
    void* (*compile)(const azjit_function* function,char* error,size_t capacity);
    azjit_entry (*entry)(void* code);
    void (*release)(void* code);
} azjit_backend;

/* The layout hash both sides compute from these definitions. */
#define AZJIT_LAYOUT_HASH ((uint64_t)sizeof(azjit_shadow)|((uint64_t)sizeof(azjit_op)<<8)| \
    ((uint64_t)sizeof(azjit_helpers)<<16)|((uint64_t)sizeof(azjit_function)<<24)|((uint64_t)sizeof(azjit_state)<<32)| \
    ((uint64_t)offsetof(azjit_state,flags)<<40)|((uint64_t)AZJIT_EXIT<<48))

const azjit_backend* azscript_jit_backend_v1(void);
typedef const azjit_backend* (*azjit_backend_entry)(void);

#ifdef __cplusplus
}
#endif
#endif
