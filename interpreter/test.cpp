#include "library.h"
#include "extern_library.h"
#include <bit>
#include <optional>
#include <algorithm>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
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
}
int main(int argc,char* argv[]) {
    std::shared_ptr<azertian::script> script;
    try {
        if(argc<2){std::cerr<<"Usage: azscript-run script.exec.abd [function-id] [--insert library.exec.abd]... [--max-steps N] [--max-call-depth N] [--max-stack-bytes N]\n";return 2;}
        std::optional<std::uint64_t> steps,depth,stack;
        std::string source;std::vector<std::string> additions;int id=0x0fff0000;bool explicit_id=false;
        for(int i=1;i<argc;++i) {
            const std::string argument=argv[i];
            if(argument=="--insert") {
                if(++i==argc)throw std::invalid_argument("--insert requires a script path");
                additions.emplace_back(argv[i]);
            } else if(argument=="--max-steps"||argument=="--max-call-depth"||argument=="--max-stack-bytes") {
                if(++i==argc)throw std::invalid_argument(argument+" requires a number");
                const auto value=execution_limit(argument,argv[i],argument!="--max-steps");
                if(argument=="--max-steps")steps=value;
                else if(argument=="--max-call-depth")depth=value;
                else stack=value;
            } else if(source.empty())source=argument;
            else if(!explicit_id){id=function_id(argument);explicit_id=true;}
            else throw std::invalid_argument("Unexpected interpreter argument: "+argument);
        }
        if(source.empty())throw std::invalid_argument("A primary script path is required");
        azertian::load_standalone_trusted_keys();
        auto bytes=read_script(source);script=azertian::load_script(bytes.data(),bytes.size());
        if(steps)script->max_steps=*steps;
        if(depth)script->max_call_depth=static_cast<std::size_t>(*depth);
        if(stack)script->max_stack_bytes=static_cast<std::size_t>(*stack);
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
