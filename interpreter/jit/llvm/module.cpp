// The JIT module: MIR (jit/abi.h) to native code with LLVM ORC. It includes
// no runtime header and reaches the runtime only through the helper table.
#include "../abi.h"
#include <llvm/ExecutionEngine/JITLink/JITLinkMemoryManager.h>
#include <llvm/ExecutionEngine/Orc/CompileUtils.h>
#include <llvm/ExecutionEngine/Orc/IRCompileLayer.h>
#include <llvm/ExecutionEngine/Orc/ExecutionUtils.h>
#include <llvm/ExecutionEngine/Orc/JITTargetMachineBuilder.h>
#include <llvm/ExecutionEngine/Orc/LLJIT.h>
#include <llvm/ExecutionEngine/Orc/ObjectLinkingLayer.h>
#include <llvm/ExecutionEngine/Orc/ThreadSafeModule.h>
#include <llvm/IR/IRBuilder.h>
#include <llvm/IR/Intrinsics.h>
#include <llvm/IR/LLVMContext.h>
#include <llvm/IR/Module.h>
#include <llvm/IR/Verifier.h>
#include <llvm/Passes/PassBuilder.h>
#include <llvm/Support/TargetSelect.h>
#include <llvm/Support/raw_ostream.h>
#include <llvm/Target/TargetMachine.h>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
using namespace llvm;
constexpr std::uint32_t max_ops=1u<<16;
constexpr std::uint64_t max_resume_cells=1u<<22;
// Larger functions take a light IR pipeline and LLVM's fast code generator:
// at O2 compile time grows faster than linearly with the size, about 5.7 s
// for 8,500 operations against 1.1 s.
constexpr std::uint32_t full_ops=2048;

struct Code {
    orc::JITDylib* library=nullptr;
    azjit_entry entry=nullptr;
};
struct Host {
    std::unique_ptr<orc::LLJIT> jit;
    std::unique_ptr<TargetMachine> machine;
    std::string failure;
};
// Compiles a module flagged "azjit.fast" with the fast code generator and
// every other one as before. Compilation is serialized by the runtime.
class TieredCompiler final:public orc::IRCompileLayer::IRCompiler {
    std::unique_ptr<TargetMachine> full,fast;
public:
    TieredCompiler(std::unique_ptr<TargetMachine> optimized,std::unique_ptr<TargetMachine> quick)
        :IRCompiler(orc::irManglingOptionsFromTargetOptions(optimized->Options)),
         full(std::move(optimized)),fast(std::move(quick)) {}
    Expected<std::unique_ptr<MemoryBuffer>> operator()(Module& module) override {
        return orc::SimpleCompiler(module.getModuleFlag("azjit.fast")?*fast:*full)(module);
    }
};
Host& host() {
    static Host instance;
    static std::once_flag once;
    std::call_once(once,[] {
        InitializeNativeTarget();InitializeNativeTargetAsmPrinter();
        auto target=orc::JITTargetMachineBuilder::detectHost();
        if(!target){instance.failure=toString(target.takeError());return;}
        target->setCodeGenOptLevel(CodeGenOptLevel::Default);
        auto machine=target->createTargetMachine();
        if(!machine){instance.failure=toString(machine.takeError());return;}
        instance.machine=std::move(*machine);
        orc::LLJITBuilder builder;
        builder.setJITTargetMachineBuilder(*target);
        builder.setCompileFunctionCreator([](orc::JITTargetMachineBuilder machines)
                -> Expected<std::unique_ptr<orc::IRCompileLayer::IRCompiler>> {
            auto optimized=machines.createTargetMachine();
            if(!optimized)return optimized.takeError();
            machines.setCodeGenOptLevel(CodeGenOptLevel::None);
            auto quick=machines.createTargetMachine();
            if(!quick)return quick.takeError();
            return std::make_unique<TieredCompiler>(std::move(*optimized),std::move(*quick));
        });
        // JITLink maps code writable, then executable (never both).
        builder.setObjectLinkingLayerCreator([](orc::ExecutionSession& session,const Triple&)
                -> Expected<std::unique_ptr<orc::ObjectLayer>> {
            auto memory=jitlink::InProcessMemoryManager::Create();
            if(!memory)return memory.takeError();
            return std::make_unique<orc::ObjectLinkingLayer>(session,std::move(*memory));
        });
        auto jit=builder.create();
        if(!jit){instance.failure=toString(jit.takeError());return;}
        instance.jit=std::move(*jit);
    });
    return instance;
}

class Generator {
    const azjit_function& source;
    LLVMContext& context;
    Module& module;
    IRBuilder<> b;
    Type *i8,*i32,*i64,*ptr;
    StructType *shadow_type,*state_type;
    Function* function=nullptr;
    Value *state=nullptr,*slots=nullptr,*frame=nullptr;
    std::vector<AllocaInst*> bits,tags;
    AllocaInst *steps=nullptr,*flags=nullptr,*site=nullptr,*out=nullptr;
    std::vector<BasicBlock*> blocks,exits;
    BasicBlock* leave=nullptr;
    // Slots some op writes. Only these can differ from what the runtime
    // filled, so only these are stored back, and only where they are read.
    std::vector<bool> written;

    Constant* c8(std::uint64_t value){return ConstantInt::get(i8,value);}
    Constant* c32(std::uint64_t value){return ConstantInt::get(i32,value);}
    Constant* c64(std::uint64_t value){return ConstantInt::get(i64,value);}
    static bool single(std::uint16_t tags){return tags&&!(tags&(tags-1));}
    static std::uint8_t only(std::uint16_t tags){return static_cast<std::uint8_t>(__builtin_ctz(tags));}
    template<class Pointer> FunctionCallee helper(Pointer pointer,Type* result,ArrayRef<Type*> parameters) {
        auto* type=FunctionType::get(result,parameters,false);
        auto* address=ConstantExpr::getIntToPtr(c64(reinterpret_cast<std::uintptr_t>(pointer)),ptr);
        return {type,address};
    }
    CallInst* call(FunctionCallee callee,ArrayRef<Value*> arguments) {
        auto* result=b.CreateCall(callee,arguments);result->setDoesNotThrow();return result;
    }
    Value* slot_field(Value* index,unsigned field){return b.CreateGEP(shadow_type,slots,{index,c32(field)});}
    BasicBlock* exit_block(std::uint32_t exit) {
        if(!exits[exit]) {
            const auto saved=b.saveIP();
            exits[exit]=BasicBlock::Create(context,"exit",function);
            b.SetInsertPoint(exits[exit]);
            // The runtime writes back this site's live registers and every
            // local; a slot no op writes still holds what it filled.
            const auto store=[&](std::uint32_t slot) {
                if(!written[slot])return;
                b.CreateStore(load_bits(slot),slot_field(c32(slot),0));
                b.CreateStore(load_tag(slot),slot_field(c32(slot),1));
            };
            for(auto at=source.exit_live_offsets[exit];at<source.exit_live_offsets[exit+1];++at)store(source.exit_live[at]);
            for(std::uint32_t local=0;local<source.locals;++local)store(source.registers+local);
            b.CreateStore(c32(exit),site);b.CreateBr(leave);
            b.restoreIP(saved);
        }
        return exits[exit];
    }
    // Continue at the next op unless cond holds, then leave through exit.
    void exit_if(Value* condition,std::uint32_t exit,std::uint32_t next) {
        b.CreateCondBr(condition,exit_block(exit),blocks[next]);
    }
    Value* load_bits(std::uint32_t slot){return b.CreateLoad(i64,bits[slot]);}
    Value* load_tag(std::uint32_t slot){return b.CreateLoad(i8,tags[slot]);}
    // Store a tag, using the analysis' tags when they name exactly one.
    void store_tag(std::uint32_t slot,Value* tag,std::uint16_t allowed) {
        b.CreateStore(single(allowed)?c8(only(allowed)):tag,tags[slot]);
    }
    void store_result(std::uint32_t slot,Value* value,Value* tag,std::uint16_t allowed) {
        b.CreateStore(value,bits[slot]);store_tag(slot,tag,allowed);
    }
    Value* helper_bits(){return b.CreateLoad(i64,b.CreateStructGEP(shadow_type,out,0));}
    Value* helper_tag(){return b.CreateLoad(i8,b.CreateStructGEP(shadow_type,out,1));}
    Value* failed(Value* status){return b.CreateICmpEQ(status,c32(0));}
    void entry_and_resumes() {
        auto* entry=BasicBlock::Create(context,"entry",function);
        b.SetInsertPoint(entry);
        state=function->getArg(0);
        slots=b.CreateLoad(ptr,b.CreateStructGEP(state_type,state,0));
        frame=b.CreateLoad(ptr,b.CreateStructGEP(state_type,state,1));
        for(std::uint32_t slot=0;slot<source.slots;++slot) {
            bits.push_back(b.CreateAlloca(i64));tags.push_back(b.CreateAlloca(i8));
        }
        steps=b.CreateAlloca(i64);flags=b.CreateAlloca(i8);site=b.CreateAlloca(i32);out=b.CreateAlloca(shadow_type);
        // A written slot an entry does not fill starts absent, once for all
        // resume points.
        for(std::uint32_t slot=0;slot<source.slots;++slot)
            if(written[slot]){b.CreateStore(c64(0),bits[slot]);b.CreateStore(c8(AZJIT_ABSENT),tags[slot]);}
        b.CreateStore(b.CreateLoad(i64,b.CreateStructGEP(state_type,state,2)),steps);
        b.CreateStore(b.CreateLoad(i8,b.CreateStructGEP(state_type,state,4)),flags);
        for(std::uint32_t op=0;op<source.op_count;++op)blocks.push_back(BasicBlock::Create(context,"op",function));
        // Past the last op: only on paths the analysis proved impossible.
        blocks.push_back(BasicBlock::Create(context,"fell_off",function));
        exits.assign(source.exit_count,nullptr);
        leave=BasicBlock::Create(context,"leave",function);
        auto* unreachable=BasicBlock::Create(context,"bad_resume",function);
        auto* resume=b.CreateLoad(i32,b.CreateStructGEP(state_type,state,3));
        auto* dispatch=b.CreateSwitch(resume,unreachable,source.resume_count);
        for(std::uint32_t id=0;id<source.resume_count;++id) {
            auto* block=BasicBlock::Create(context,"resume",function);
            dispatch->addCase(cast<ConstantInt>(c32(id)),block);
            b.SetInsertPoint(block);
            for(std::uint32_t slot=0;slot<source.slots;++slot) {
                const auto allowed=source.resume_tags[std::size_t{id}*source.slots+slot];
                if(!allowed)continue;
                b.CreateStore(b.CreateLoad(i64,slot_field(c32(slot),0)),bits[slot]);
                // A register present here is always loaded, but a local that
                // may be undeclared (a break or return skipped its declaration)
                // enters absent: its tag is loaded, never assumed.
                auto* tag=b.CreateLoad(i8,slot_field(c32(slot),1));
                if(slot<source.registers)store_tag(slot,tag,allowed);
                else b.CreateStore(tag,tags[slot]);
            }
            b.CreateBr(blocks[source.resume_ops[id]]);
        }
        b.SetInsertPoint(unreachable);b.CreateUnreachable();
        b.SetInsertPoint(blocks.back());
        b.CreateCall(Intrinsic::getDeclaration(&module,Intrinsic::trap));b.CreateUnreachable();
        // Each exit block stored its shadows; every exit leaves through here.
        b.SetInsertPoint(leave);
        b.CreateStore(b.CreateLoad(i64,steps),b.CreateStructGEP(state_type,state,2));
        b.CreateStore(b.CreateLoad(i8,flags),b.CreateStructGEP(state_type,state,4));
        b.CreateRet(b.CreateLoad(i32,site));
    }
    // Operations inlined for two ints, as value_ops computes them.
    // add .. less_equal, and the wrapping add, subtract and multiply.
    static bool fast_binary(std::uint32_t operation){return operation<=10||(operation>=17&&operation<=19);}
    void binary(const azjit_op& op,std::uint32_t index) {
        const auto next=index+1;
        auto* left_tag=load_tag(op.b);auto* right_tag=load_tag(op.c);
        auto* done=BasicBlock::Create(context,"binary_done",function);
        auto* slow=BasicBlock::Create(context,"binary_slow",function);
        BasicBlock* fast_end=nullptr;Value *fast_value=nullptr,*fast_tag=nullptr;
        if(fast_binary(op.tag)) {
            auto* fast=BasicBlock::Create(context,"binary_int",function);
            auto* ints=b.CreateAnd(b.CreateICmpEQ(left_tag,c8(AZJIT_TAG_INT)),b.CreateICmpEQ(right_tag,c8(AZJIT_TAG_INT)));
            b.CreateCondBr(ints,fast,slow);
            b.SetInsertPoint(fast);
            auto* x=b.CreateTrunc(load_bits(op.b),i32);auto* y=b.CreateTrunc(load_bits(op.c),i32);
            auto* failure=exit_block(op.exit);
            Value* result=nullptr;Value* result_tag=c8(AZJIT_TAG_INT);
            const auto checked=[&](Intrinsic::ID id) {
                auto* pair=b.CreateBinaryIntrinsic(id,x,y);
                auto* ok=BasicBlock::Create(context,"no_overflow",function);
                b.CreateCondBr(b.CreateExtractValue(pair,1),failure,ok);
                b.SetInsertPoint(ok);
                return b.CreateExtractValue(pair,0);
            };
            switch(op.tag) {
            case 17:result=b.CreateAdd(x,y);break;
            case 18:result=b.CreateSub(x,y);break;
            case 19:result=b.CreateMul(x,y);break;
            case 0:result=checked(Intrinsic::sadd_with_overflow);break;
            case 1:result=checked(Intrinsic::ssub_with_overflow);break;
            case 2:result=checked(Intrinsic::smul_with_overflow);break;
            case 3: case 4: {
                // Zero divisors fail; INT_MIN/-1 overflows; INT_MIN%-1 is 0.
                auto* ok=BasicBlock::Create(context,"nonzero",function);
                b.CreateCondBr(b.CreateICmpEQ(y,c32(0)),failure,ok);
                b.SetInsertPoint(ok);
                auto* minus_one=b.CreateICmpEQ(y,ConstantInt::get(i32,-1,true));
                if(op.tag==3) {
                    auto* safe=BasicBlock::Create(context,"no_overflow",function);
                    b.CreateCondBr(b.CreateAnd(minus_one,b.CreateICmpEQ(x,ConstantInt::get(i32,INT32_MIN,true))),failure,safe);
                    b.SetInsertPoint(safe);
                    result=b.CreateSDiv(x,b.CreateSelect(minus_one,c32(1),y));
                    result=b.CreateSelect(minus_one,b.CreateNeg(x),result);
                } else {
                    result=b.CreateSelect(minus_one,c32(0),b.CreateSRem(x,b.CreateSelect(minus_one,c32(1),y)));
                }
                break;
            }
            default: {
                ICmpInst::Predicate predicate;
                switch(op.tag) {
                case 5:predicate=ICmpInst::ICMP_SGT;break;
                case 6:predicate=ICmpInst::ICMP_SLT;break;
                case 7:predicate=ICmpInst::ICMP_EQ;break;
                case 8:predicate=ICmpInst::ICMP_NE;break;
                case 9:predicate=ICmpInst::ICMP_SGE;break;
                default:predicate=ICmpInst::ICMP_SLE;break;
                }
                result=b.CreateZExt(b.CreateICmp(predicate,x,y),i32);result_tag=c8(AZJIT_TAG_BOOLEAN);
                break;
            }
            }
            fast_value=b.CreateZExt(result,i64);fast_tag=result_tag;fast_end=b.GetInsertBlock();
            b.CreateBr(done);
        } else b.CreateBr(slow);
        b.SetInsertPoint(slow);
        auto* status=call(helper(source.helpers->binary,i32,{i32,i64,i32,i64,i32,ptr}),
            {c32(op.tag),load_bits(op.b),b.CreateZExt(left_tag,i32),load_bits(op.c),b.CreateZExt(right_tag,i32),out});
        auto* ok=BasicBlock::Create(context,"binary_ok",function);
        b.CreateCondBr(failed(status),exit_block(op.exit),ok);
        b.SetInsertPoint(ok);
        auto* slow_value=helper_bits();auto* slow_tag=helper_tag();
        b.CreateBr(done);
        b.SetInsertPoint(done);
        auto* value=b.CreatePHI(i64,2);auto* tag=b.CreatePHI(i8,2);
        value->addIncoming(slow_value,ok);tag->addIncoming(slow_tag,ok);
        if(fast_end){value->addIncoming(fast_value,fast_end);tag->addIncoming(fast_tag,fast_end);}
        store_result(op.a,value,tag,op.tags);
        b.CreateBr(blocks[next]);
    }
    void generic(const azjit_op& op,std::uint32_t index,FunctionCallee callee,ArrayRef<Value*> arguments) {
        auto* status=call(callee,arguments);
        auto* ok=BasicBlock::Create(context,"ok",function);
        b.CreateCondBr(failed(status),exit_block(op.exit),ok);
        b.SetInsertPoint(ok);
        store_result(op.a,helper_bits(),helper_tag(),op.tags);
        b.CreateBr(blocks[index+1]);
    }
    void lower(std::uint32_t index) {
        const auto& op=source.ops[index];
        b.SetInsertPoint(blocks[index]);
        const auto next=index+1;
        const auto fallthrough=[&]{b.CreateBr(blocks[next]);};
        const auto* h=source.helpers;
        switch(op.code) {
        case AZJIT_STEPS:exit_if(b.CreateICmpULT(b.CreateLoad(i64,steps),c64(op.imm)),op.exit,next);return;
        case AZJIT_TICK:b.CreateStore(b.CreateSub(b.CreateLoad(i64,steps),c64(1)),steps);fallthrough();return;
        case AZJIT_SET:b.CreateStore(c64(op.imm),bits[op.a]);b.CreateStore(c8(op.tag),tags[op.a]);fallthrough();return;
        case AZJIT_COPY:store_result(op.a,load_bits(op.b),load_tag(op.b),op.tags);fallthrough();return;
        case AZJIT_FORGET:b.CreateStore(c8(AZJIT_ABSENT),tags[op.a]);fallthrough();return;
        case AZJIT_READ_REFERENCE:
            generic(op,index,helper(h->read_reference,i32,{ptr,i32,ptr}),{frame,c32(op.b),out});return;
        case AZJIT_WRITE_REFERENCE: {
            auto* status=call(helper(h->write_reference,i32,{ptr,i32,i64,i32}),
                {frame,c32(op.a),load_bits(op.b),b.CreateZExt(load_tag(op.b),i32)});
            exit_if(failed(status),op.exit,next);return;
        }
        case AZJIT_TRUTH:exit_if(b.CreateICmpNE(load_tag(op.a),c8(AZJIT_TAG_BOOLEAN)),op.exit,next);return;
        case AZJIT_BRANCH_FALSE:b.CreateCondBr(b.CreateICmpEQ(load_bits(op.a),c64(0)),blocks[op.target],blocks[next]);return;
        case AZJIT_JUMP:b.CreateBr(blocks[op.target]);return;
        case AZJIT_BRANCH_FLAGS:
            b.CreateCondBr(b.CreateICmpNE(b.CreateAnd(b.CreateLoad(i8,flags),c8(op.tag)),c8(0)),blocks[op.target],blocks[next]);return;
        case AZJIT_SET_FLAGS:b.CreateStore(b.CreateOr(b.CreateLoad(i8,flags),c8(op.tag)),flags);fallthrough();return;
        case AZJIT_LOOP_NEXT: {
            auto* current=b.CreateLoad(i8,flags);
            auto* broken=b.CreateICmpNE(b.CreateAnd(current,c8(AZJIT_BROKEN)),c8(0));
            b.CreateStore(b.CreateSelect(broken,b.CreateAnd(current,c8(0xff&~AZJIT_BROKEN)),b.CreateAnd(current,c8(0xff&~AZJIT_CONTINUED))),flags);
            b.CreateCondBr(broken,blocks[op.b],blocks[op.target]);return;
        }
        case AZJIT_BINARY:binary(op,index);return;
        case AZJIT_UNARY:
            generic(op,index,helper(h->unary,i32,{i32,i64,i32,ptr}),{c32(op.tag),load_bits(op.b),b.CreateZExt(load_tag(op.b),i32),out});return;
        case AZJIT_CONVERT: {
            auto* same=b.CreateICmpEQ(load_tag(op.b),c8(op.imm));
            auto* copy=BasicBlock::Create(context,"same_type",function);
            auto* convert=BasicBlock::Create(context,"convert",function);
            b.CreateCondBr(same,copy,convert);
            b.SetInsertPoint(copy);store_result(op.a,load_bits(op.b),load_tag(op.b),op.tags);b.CreateBr(blocks[next]);
            b.SetInsertPoint(convert);
            generic(op,index,helper(h->convert,i32,{i32,i64,i32,ptr}),{c32(op.imm),load_bits(op.b),b.CreateZExt(load_tag(op.b),i32),out});return;
        }
        case AZJIT_REFERENCE_LOCAL:case AZJIT_REFERENCE_GLOBAL: {
            auto* status=call(helper(op.code==AZJIT_REFERENCE_LOCAL?h->reference_local:h->reference_global,i32,{ptr,i32,i32}),
                {frame,c32(op.a),c32(op.b)});
            auto* ok=BasicBlock::Create(context,"referenced",function);
            b.CreateCondBr(failed(status),exit_block(op.exit),ok);
            b.SetInsertPoint(ok);b.CreateStore(c8(AZJIT_ABSENT),tags[op.a]);fallthrough();return;
        }
        case AZJIT_COPY_REFERENCE:
            call(helper(h->copy_reference,Type::getVoidTy(context),{ptr,i32,i32}),{frame,c32(op.a),c32(op.b)});
            b.CreateStore(c8(AZJIT_ABSENT),tags[op.a]);fallthrough();return;
        case AZJIT_DROP_REFERENCE:call(helper(h->drop_reference,Type::getVoidTy(context),{ptr,i32}),{frame,c32(op.a)});fallthrough();return;
        case AZJIT_DECLARE: {
            Value* value=op.b==AZJIT_NONE?static_cast<Value*>(c64(0)):load_bits(op.b);
            Value* tag=op.b==AZJIT_NONE?static_cast<Value*>(c8(AZJIT_TAG_VOID)):load_tag(op.b);
            auto* status=call(helper(h->declare,i32,{ptr,i32,i64,i32}),{frame,c32(op.a),value,b.CreateZExt(tag,i32)});
            auto* ok=BasicBlock::Create(context,"declared",function);
            b.CreateCondBr(failed(status),exit_block(op.exit),ok);
            b.SetInsertPoint(ok);store_result(source.registers+op.a,value,tag,op.tags);fallthrough();return;
        }
        case AZJIT_FLUSH_LOCAL:
            call(helper(h->flush_local,Type::getVoidTy(context),{ptr,i32,i64,i32}),
                {frame,c32(op.b),load_bits(op.a),b.CreateZExt(load_tag(op.a),i32)});
            fallthrough();return;
        case AZJIT_ENTER_SCOPE:call(helper(h->enter_scope,Type::getVoidTy(context),{ptr}),{frame});fallthrough();return;
        case AZJIT_LEAVE_SCOPE:call(helper(h->leave_scope,Type::getVoidTy(context),{ptr}),{frame});fallthrough();return;
        case AZJIT_CHECK_UNDECLARED:
            exit_if(failed(call(helper(h->undeclared,i32,{ptr,i32}),{frame,c32(op.a)})),op.exit,next);return;
        case AZJIT_SET_RESULT:
            call(helper(h->set_result,Type::getVoidTy(context),{ptr,i64,i32,i32}),
                {frame,load_bits(op.a),b.CreateZExt(load_tag(op.a),i32),c32(op.imm)});
            b.CreateStore(b.CreateOr(b.CreateLoad(i8,flags),c8(AZJIT_RETURNED)),flags);fallthrough();return;
        case AZJIT_EXIT:b.CreateBr(exit_block(op.exit));return;
        default:report_fatal_error("unknown MIR code");
        }
    }
public:
    Generator(const azjit_function& function,LLVMContext& owner,Module& target)
        :source(function),context(owner),module(target),b(owner) {
        i8=Type::getInt8Ty(context);i32=Type::getInt32Ty(context);
        i64=Type::getInt64Ty(context);ptr=PointerType::getUnqual(context);
        shadow_type=StructType::create(context,{i64,i8,ArrayType::get(i8,7)},"azjit_shadow");
        state_type=StructType::create(context,{ptr,ptr,i64,i32,i8},"azjit_state");
    }
    void generate() {
        written.assign(source.slots,false);
        for(std::uint32_t index=0;index<source.op_count;++index) {
            const auto& op=source.ops[index];
            switch(op.code) {
            case AZJIT_SET:case AZJIT_COPY:case AZJIT_FORGET:case AZJIT_READ_REFERENCE:case AZJIT_BINARY:
            case AZJIT_UNARY:case AZJIT_CONVERT:case AZJIT_REFERENCE_LOCAL:case AZJIT_REFERENCE_GLOBAL:
            case AZJIT_COPY_REFERENCE:
                if(op.a<source.slots)written[op.a]=true;
                break;
            case AZJIT_DECLARE:
                if(source.registers+op.a<source.slots)written[source.registers+op.a]=true;
                break;
            default:break;
            }
        }
        auto* type=FunctionType::get(i32,{ptr},false);
        function=Function::Create(type,GlobalValue::ExternalLinkage,source.name,module);
        function->setDoesNotThrow();
        function->addParamAttr(0,Attribute::NoAlias);
        entry_and_resumes();
        for(std::uint32_t index=0;index<source.op_count;++index)lower(index);
    }
};

void* compile(const azjit_function* function,char* error,std::size_t capacity) {
    const auto fail=[&](const std::string& message)->void* {
        if(capacity){std::strncpy(error,message.c_str(),capacity-1);error[capacity-1]=0;}
        return nullptr;
    };
    if(!function||function->abi!=AZJIT_ABI_VERSION)return fail("JIT module ABI mismatch");
    if(function->op_count>max_ops||std::uint64_t{function->resume_count}*function->slots>max_resume_cells)
        return fail("function is too large for native code");
    if(!function->op_count||!function->resume_count)return fail("function has no code");
    if(function->registers+function->locals>function->slots||!function->exit_live_offsets||
       function->exit_live_offsets[0]!=0)return fail("invalid exit live sets");
    for(std::uint32_t exit=0;exit<function->exit_count;++exit) {
        const auto begin=function->exit_live_offsets[exit],end=function->exit_live_offsets[exit+1];
        if(end<begin)return fail("invalid exit live sets");
        for(auto at=begin;at<end;++at)if(function->exit_live[at]>=function->registers)return fail("invalid exit live sets");
    }
    auto& jit=host();
    if(!jit.jit)return fail("LLVM JIT unavailable: "+jit.failure);
    auto context=std::make_unique<LLVMContext>();
    auto module=std::make_unique<Module>(function->name,*context);
    module->setDataLayout(jit.jit->getDataLayout());
    module->setTargetTriple(jit.jit->getTargetTriple().str());
    Generator(*function,*context,*module).generate();
    std::string message;raw_string_ostream stream(message);
    if(verifyModule(*module,&stream))return fail("invalid IR: "+stream.str());
    {
        LoopAnalysisManager loops;FunctionAnalysisManager functions;CGSCCAnalysisManager calls;ModuleAnalysisManager modules;
        PassBuilder passes(jit.machine.get());
        passes.registerModuleAnalyses(modules);passes.registerCGSCCAnalyses(calls);
        passes.registerFunctionAnalyses(functions);passes.registerLoopAnalyses(loops);
        passes.crossRegisterProxies(loops,functions,calls,modules);
        if(function->op_count>full_ops) {
            ModulePassManager light;
            if(auto error=passes.parsePassPipeline(light,"function(sroa,early-cse,instcombine,simplifycfg)"))
                return fail(toString(std::move(error)));
            light.run(*module,modules);
            module->addModuleFlag(Module::Warning,"azjit.fast",1);
        } else passes.buildPerModuleDefaultPipeline(OptimizationLevel::O2).run(*module,modules);
    }
    static std::atomic<unsigned> counter{0};
    auto& library=jit.jit->getExecutionSession().createBareJITDylib(std::string(function->name)+"."+std::to_string(counter++));
    auto generator=orc::DynamicLibrarySearchGenerator::GetForCurrentProcess(jit.jit->getDataLayout().getGlobalPrefix());
    if(!generator)return fail(toString(generator.takeError()));
    library.addGenerator(std::move(*generator));
    if(auto added=jit.jit->addIRModule(library,orc::ThreadSafeModule(std::move(module),std::move(context))))
        return fail(toString(std::move(added)));
    auto symbol=jit.jit->lookup(library,function->name);
    if(!symbol) {
        const auto reason=toString(symbol.takeError());
        consumeError(jit.jit->getExecutionSession().removeJITDylib(library));
        return fail(reason);
    }
    auto* code=new Code{&library,symbol->toPtr<azjit_entry>()};
    return code;
}
azjit_entry entry(void* code){return static_cast<Code*>(code)->entry;}
void release(void* code) {
    auto* compiled=static_cast<Code*>(code);
    if(auto& jit=host();jit.jit)consumeError(jit.jit->getExecutionSession().removeJITDylib(*compiled->library));
    delete compiled;
}
const azjit_backend backend{AZJIT_ABI_VERSION,AZJIT_LAYOUT_HASH,"llvm-orc",compile,entry,release};
}

extern "C" __attribute__((visibility("default"))) const azjit_backend* azscript_jit_backend_v1(void) {return &backend;}
