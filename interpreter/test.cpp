#include "library.h"
#include <bit>
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
        if(argc<2){std::cerr<<"Usage: azscript-run script.exec.abd [function-id] [--insert library.exec.abd]...\n";return 2;}
        std::string source;std::vector<std::string> additions;int id=0x0fff0000;bool explicit_id=false;
        for(int i=1;i<argc;++i) {
            const std::string argument=argv[i];
            if(argument=="--insert") {
                if(++i==argc)throw std::invalid_argument("--insert requires a script path");
                additions.emplace_back(argv[i]);
            } else if(source.empty())source=argument;
            else if(!explicit_id){id=function_id(argument);explicit_id=true;}
            else throw std::invalid_argument("Unexpected interpreter argument: "+argument);
        }
        if(source.empty())throw std::invalid_argument("A primary script path is required");
        auto bytes=read_script(source);script=azertian::load_script(bytes.data(),bytes.size());
        for(auto& path:additions){auto module=read_script(path);script->insert_script(module.data(),module.size());}
        script->flush();auto value=script->invoke(id);
        if(value->type!=azertian::VOID_VALUE)std::cout<<azertian::value_to_string(value)<<'\n';
        script->destroy();return 0;
    }catch(const std::exception& error){
        if(script&&!script->closed)try{script->destroy();}catch(...){}
        std::cerr<<"AzScript error: "<<error.what()<<'\n';return 1;
    }
}
