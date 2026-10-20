#include "library.h"
#include "extern_library.h"
#include "vm/session.h"
#include <algorithm>
#include <bit>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <optional>
#include <stdexcept>
namespace {
std::vector<unsigned char> read_script(const std::string& path) {
    std::ifstream file(path,std::ios::binary|std::ios::ate);
    if(!file)throw std::runtime_error("Cannot open script file: "+path);
    if(file.tellg()<0||file.tellg()>64*1024*1024)throw std::runtime_error("Invalid script file size: "+path);
    file.seekg(0);return {std::istreambuf_iterator<char>(file),{}};
}
std::uint64_t execution_limit(const std::string& option,const std::string& value,bool zero_allowed) {
    try {
        if(value.empty()||!std::all_of(value.begin(),value.end(),[](unsigned char ch){return ch>='0'&&ch<='9';}))
            throw std::invalid_argument("digits required");
        const auto result=std::stoull(value);
        if(!zero_allowed&&!result)throw std::invalid_argument("positive limit required");
        if(option!="--max-steps"&&result>std::numeric_limits<std::size_t>::max())throw std::out_of_range("limit too large");
        return result;
    } catch(const std::exception&) {throw std::invalid_argument("Invalid execution limit for "+option+": "+value);}
}
int function_id(const std::string& value) {
    std::size_t used=0;
    if(!value.empty()&&value[0]=='-') {
        const auto result=std::stoll(value,&used,0);
        if(used!=value.size()||result<std::numeric_limits<int>::min())throw std::invalid_argument("Invalid function id");
        return static_cast<int>(result);
    }
    const auto result=std::stoull(value,&used,0);
    if(used!=value.size()||result>std::numeric_limits<std::uint32_t>::max())throw std::invalid_argument("Invalid function id");
    return std::bit_cast<int>(static_cast<std::uint32_t>(result));
}
// Each top-level entry (onload, the entry function, destructors) gets a fresh budget.
std::uint64_t step_limit(const std::string& value) {
    if(value=="unlimited")return std::numeric_limits<std::uint64_t>::max();
    std::size_t used=0;std::uint64_t result=0;
    const bool digits=!value.empty()&&value[0]>='0'&&value[0]<='9';
    try{if(digits)result=std::stoull(value,&used,10);}catch(const std::out_of_range&){used=0;}
    if(!digits||used!=value.size()||!result)
        throw std::invalid_argument("Invalid step limit: "+value+" (expected a positive integer or unlimited)");
    return result;
}
}
int main(int argc,char* argv[]) {
    std::shared_ptr<azertian::script> script;
    try {
        if(argc<2){std::cerr<<"Usage: azscript-run script.exec.abd [function-id] [--insert library.exec.abd]... [--backend tree|vm] [--max-steps count|unlimited] [--max-call-depth N] [--max-stack-bytes N]\n";return 2;}
        std::optional<std::uint64_t> depth,stack;
        std::string source;std::vector<std::string> additions;int id=0x0fff0000;bool explicit_id=false;
        // The tree executor stays the default; the VM refuses unsupported programs
        // while preparing them and never falls back to the tree executor.
        std::string backend;std::optional<std::uint64_t> max_steps;
        const auto choose_steps=[&](const std::string& value) {
            const auto parsed=step_limit(value);
            if(max_steps&&*max_steps!=parsed)throw std::invalid_argument("Conflicting step limits: "+value);
            max_steps=parsed;
        };
        const auto choose_backend=[&](const std::string& value) {
            if(value!="tree"&&value!="vm")throw std::invalid_argument("Unknown backend: "+value+" (expected tree or vm)");
            if(!backend.empty()&&backend!=value)throw std::invalid_argument("Conflicting backend options: "+backend+" and "+value);
            backend=value;
        };
        for(int i=1;i<argc;++i) {
            const std::string argument=argv[i];
            if(argument=="--backend") {
                if(++i==argc)throw std::invalid_argument("--backend requires tree or vm");
                choose_backend(argv[i]);
            } else if(argument.starts_with("--backend="))choose_backend(argument.substr(10));
            else if(argument=="--vm")choose_backend("vm");
            else if(argument=="--tree")choose_backend("tree");
            else if(argument=="--max-steps") {
                if(++i==argc)throw std::invalid_argument("--max-steps requires a count");
                choose_steps(argv[i]);
            } else if(argument.starts_with("--max-steps="))choose_steps(argument.substr(12));
            else if(argument=="--insert") {
                if(++i==argc)throw std::invalid_argument("--insert requires a script path");
                additions.emplace_back(argv[i]);
            } else if(argument=="--max-call-depth"||argument=="--max-stack-bytes") {
                if(++i==argc)throw std::invalid_argument(argument+" requires a number");
                const auto value=execution_limit(argument,argv[i],true);
                if(argument=="--max-call-depth")depth=value;
                else stack=value;
            } else if(source.empty())source=argument;
            else if(!explicit_id){id=function_id(argument);explicit_id=true;}
            else throw std::invalid_argument("Unexpected interpreter argument: "+argument);
        }
        if(source.empty())throw std::invalid_argument("A primary script path is required");
        azertian::load_standalone_trusted_keys();
        auto bytes=read_script(source);script=azertian::load_script(bytes.data(),bytes.size());
        if(max_steps)script->max_steps=*max_steps;
        if(depth)script->max_call_depth=static_cast<std::size_t>(*depth);
        if(stack)script->max_stack_bytes=static_cast<std::size_t>(*stack);
        if(backend=="vm")azertian::vm::select_backend(*script,azertian::vm::default_workspace_bytes);
        for(auto& path:additions) {
            auto module=read_script(path);
            try{script->insert_script(module.data(),module.size());}
            catch(const std::exception& error){throw std::runtime_error("Cannot insert "+path+": "+error.what());}
        }
        script->flush();auto value=script->invoke(id);
        if(value->type_tag()!=azertian::VOID_VALUE)std::cout<<azertian::value_to_string(value)<<'\n';
        script->destroy();return 0;
    }catch(const std::exception& error){
        const auto message=azertian::describe_error(error);
        if(script&&!script->closed)try{script->destroy();}catch(...){}
        std::cerr<<"AzScript error: "<<message<<'\n';return 1;
    }
}
