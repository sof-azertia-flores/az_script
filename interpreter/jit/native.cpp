#include "native.h"
#include "operations.h"
#include <cstddef>
#include <cstdlib>
#include <filesystem>
#include <mutex>
#include <stdexcept>
#if !defined(_WIN32)
#include <dlfcn.h>
#endif

namespace azertian::jit {
static_assert(sizeof(Shadow)==sizeof(azjit_shadow)&&offsetof(Shadow,bits)==offsetof(azjit_shadow,bits)&&
              offsetof(Shadow,tag)==offsetof(azjit_shadow,tag),"shadows are passed to native code as they are");
static_assert(static_cast<int>(Code::exit)==AZJIT_EXIT&&static_cast<int>(Code::binary)==AZJIT_BINARY&&
              static_cast<int>(Code::set_result)==AZJIT_SET_RESULT,"MIR codes are the module's");
static_assert(none==AZJIT_NONE&&absent==AZJIT_ABSENT);
static_assert(INT_VALUE==AZJIT_TAG_INT&&FLOAT_VALUE==AZJIT_TAG_FLOAT&&DOUBLE_VALUE==AZJIT_TAG_DOUBLE&&
              BOOLEAN_VALUE==AZJIT_TAG_BOOLEAN&&VOID_VALUE==AZJIT_TAG_VOID&&ADDRESS_VALUE==AZJIT_TAG_ADDRESS);
namespace {
FrameAccess& access(void* frame) noexcept{return *static_cast<FrameAccess*>(frame);}
Shadow shadow(std::uint64_t bits,std::uint32_t tag) noexcept{return {bits,static_cast<std::uint8_t>(tag)};}
int store(const std::optional<Shadow>& result,azjit_shadow* out) noexcept {
    if(!result)return 0;
    out->bits=result->bits;out->tag=result->tag;return 1;
}
const azjit_helpers helpers{
    [](void* frame,std::uint32_t reg,std::uint32_t local)->int{return access(frame).reference_local(reg,local);},
    [](void* frame,std::uint32_t reg,std::uint32_t relative)->int{return access(frame).reference_global(reg,relative);},
    [](void* frame,std::uint32_t destination,std::uint32_t source){access(frame).copy_reference(destination,source);},
    [](void* frame,std::uint32_t reg){access(frame).drop_reference(reg);},
    [](void* frame,std::uint32_t reg,azjit_shadow* out)->int {
        Value value;
        if(!access(frame).read_reference(reg,value))return 0;
        return store(shadow_of(value),out);
    },
    [](void* frame,std::uint32_t reg,std::uint64_t bits,std::uint32_t tag)->int{return access(frame).write_reference(reg,value_of(shadow(bits,tag)));},
    [](void* frame,std::uint32_t local,std::uint64_t bits,std::uint32_t tag)->int{return access(frame).declare(local,value_of(shadow(bits,tag)));},
    [](void* frame,std::uint32_t local,std::uint64_t bits,std::uint32_t tag) {
        if(tag!=absent)access(frame).set_local_shadow(local,shadow(bits,tag));
    },
    [](void* frame){access(frame).enter_scope();},
    [](void* frame){access(frame).leave_scope();},
    [](void* frame,std::uint32_t local)->int{return access(frame).undeclared(local);},
    [](void* frame,std::uint64_t bits,std::uint32_t tag,std::uint32_t location){access(frame).set_result(value_of(shadow(bits,tag)),location);},
    [](std::uint32_t operation,std::uint64_t left,std::uint32_t left_tag,std::uint64_t right,std::uint32_t right_tag,azjit_shadow* out)->int {
        return store(binary(static_cast<BinaryOperation>(operation),shadow(left,left_tag),shadow(right,right_tag)),out);
    },
    [](std::uint32_t operation,std::uint64_t operand,std::uint32_t tag,azjit_shadow* out)->int {
        return store(unary(static_cast<UnaryOperation>(operation),shadow(operand,tag)),out);
    },
    [](std::uint32_t target,std::uint64_t operand,std::uint32_t tag,azjit_shadow* out)->int {
        return store(convert(static_cast<int>(target),shadow(operand,tag)),out);
    },
};
const azjit_backend* load(std::string& error) {
#if defined(_WIN32)
    error="the JIT module is not built for Windows";return nullptr;
#else
#if defined(__APPLE__)
    constexpr const char* file="libazscript-jit.dylib";
#else
    constexpr const char* file="libazscript-jit.so";
#endif
    // Beside the binary holding this code: the shared runtime library, or
    // an executable linked with the static one.
    std::vector<std::filesystem::path> candidates;
    if(const char* path=std::getenv("AZSCRIPT_JIT_MODULE");path&&*path)candidates.emplace_back(path);
    else if(Dl_info info{};dladdr(reinterpret_cast<void*>(&native_backend),&info)&&info.dli_fname) {
        try{candidates.push_back(std::filesystem::canonical(info.dli_fname).parent_path()/file);}catch(...){}
    }
    std::string tried;
    for(const auto& candidate:candidates) {
        if(!std::filesystem::is_regular_file(candidate)){tried+=" "+candidate.string();continue;}
        void* handle=dlopen(candidate.c_str(),RTLD_NOW|RTLD_LOCAL);
        if(!handle){const char* reason=dlerror();error=candidate.string()+": "+(reason?reason:"cannot be loaded");return nullptr;}
        const auto entry=reinterpret_cast<azjit_backend_entry>(dlsym(handle,"azscript_jit_backend_v1"));
        const azjit_backend* backend=entry?entry():nullptr;
        if(!backend||backend->abi!=AZJIT_ABI_VERSION||backend->layout!=AZJIT_LAYOUT_HASH) {
            error=candidate.string()+": not a JIT module of this runtime";return nullptr;
        }
        return backend;
    }
    error="JIT module not found:"+tried;return nullptr;
#endif
}
}
const azjit_backend* native_backend(std::string& error) {
    static std::once_flag once;
    static const azjit_backend* backend=nullptr;
    static std::string failure;
    std::call_once(once,[]{backend=load(failure);});
    if(!backend)error=failure;
    return backend;
}
NativeCode::NativeCode(const azjit_backend& backend,const Function& function):backend_(&backend) {
    ops_.reserve(function.ops.size());
    for(const auto& op:function.ops)
        ops_.push_back({static_cast<std::uint8_t>(op.code),op.tag,op.tags,op.a,op.b,op.c,op.target,op.exit,op.pc,op.imm});
    resume_ids_.assign(function.resume.size(),none);
    for(std::size_t index=0;index<function.resume.size();++index) {
        if(function.resume[index]==none)continue;
        resume_ids_[index]=static_cast<std::uint32_t>(resume_ops_.size());
        resume_ops_.push_back(function.resume[index]);
        const auto& tags=function.resume_tags[index];
        resume_tags_.insert(resume_tags_.end(),tags.begin(),tags.end());
    }
    exit_live_offsets_.reserve(function.exits.size()+1);
    for(const auto& live:function.exit_registers) {
        exit_live_offsets_.push_back(static_cast<std::uint32_t>(exit_live_.size()));
        exit_live_.insert(exit_live_.end(),live.begin(),live.end());
    }
    exit_live_offsets_.push_back(static_cast<std::uint32_t>(exit_live_.size()));
    const std::string name="azscript_function_"+std::to_string(function.function);
    const azjit_function data{AZJIT_ABI_VERSION,function.slots(),function.registers,function.locals,
        static_cast<std::uint32_t>(ops_.size()),ops_.data(),
        static_cast<std::uint32_t>(resume_ops_.size()),resume_ops_.data(),resume_tags_.data(),
        static_cast<std::uint32_t>(function.exits.size()),exit_live_offsets_.data(),exit_live_.data(),&helpers,name.c_str()};
    char message[512]{};
    code_=backend.compile(&data,message,sizeof message);
    if(!code_){error=message[0]?message:"the JIT module refused the function";return;}
    entry_=backend.entry(code_);
    if(!entry_)error="the JIT module produced no entry";
}
NativeCode::~NativeCode() {if(code_)backend_->release(code_);}
Outcome NativeCode::run(const Function& function,vm::ProgramCounter pc,FrameAccess& frame,Counters& counters,Activation& activation) const {
    const auto id=resume_ids_.at(pc-function.begin);
    if(id==none)throw std::logic_error("JIT entry at a pc without a resume point");
    if(!fill(function,pc,frame,activation,counters)){++counters.rejects;return {};}
    ++counters.entries;
    azjit_state state{reinterpret_cast<azjit_shadow*>(activation.slots.data()),&frame,activation.steps,id,activation.flags};
    const auto site=entry_(&state);
    if(site>=function.exits.size())std::terminate();
    activation.steps=state.steps;activation.flags=state.flags;
    const auto& exit=function.exits[site];
    write_back(function,site,frame,activation);
    switch(exit.kind) {
    case ExitKind::step:++counters.steps;break;
    case ExitKind::redo:++counters.redos;break;
    case ExitKind::yield:++counters.yields;break;
    }
    return {true,exit.kind,exit.pc};
}
}
