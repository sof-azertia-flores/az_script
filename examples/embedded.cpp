#include <azscript/runtime.hpp>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>

// Linking abdInvokero makes the runtime part of the host executable.
int main(int argc,char** argv) {
    std::shared_ptr<azertian::script> script;
    try {
        if(argc!=2)throw std::invalid_argument("Usage: azscript-embed file.exec.abd");
        std::ifstream input(argv[1],std::ios::binary|std::ios::ate);
        if(!input||input.tellg()<0||input.tellg()>64*1024*1024)throw std::runtime_error("Invalid script file");
        input.seekg(0);
        std::vector<unsigned char> bytes(std::istreambuf_iterator<char>(input),{});
        script=azertian::load_script(bytes.data(),bytes.size());
        script->max_steps=200000;
        script->max_call_depth=128;
        script->flush();
        auto value=script->invoke(0x0fff0000);
        std::cout<<"Host received: "<<azertian::value_to_string(value)<<'\n';
        script->destroy();
    }catch(const std::exception& e){
        if(script)try{script->destroy();}catch(...){}
        std::cerr<<e.what()<<'\n';return 1;
    }
}
