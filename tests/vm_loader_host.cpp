#include <azscript/runtime.hpp>
#include "../interpreter/vm/loader.h"
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>
#include <vector>

int main(int argc,char** argv) {
    std::shared_ptr<azertian::script> script;
    try {
        if(argc<2)throw std::invalid_argument("Usage: vm-loader-host module.abd [module.abd ...]");
        for(int i=1;i<argc;++i) {
            std::ifstream input(argv[i],std::ios::binary);
            if(!input)throw std::runtime_error("Cannot read module");
            std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(input)),{});
            if(!script)script=azertian::load_script(bytes.data(),bytes.size());
            else script->insert_script(bytes.data(),bytes.size());
        }
        // The production decoder/linker remains the validation authority during
        // migration. This host checks the subsequent VM compilation boundary.
        script->flush();
        auto compiled=azertian::vm::compile_script(*script);
        azertian::vm::verify_program(compiled.program);
        std::cout<<"{\"modules\":"<<compiled.modules.size()
                 <<",\"functions\":"<<compiled.program.functions.size()
                 <<",\"public_functions\":"<<compiled.public_functions.size()
                 <<",\"instructions\":"<<compiled.program.instructions.size()<<"}\n";
        script->destroy();
        return 0;
    } catch(const std::exception& error) {
        std::cerr<<azertian::describe_error(error)<<'\n';
        if(script&&!script->closed)try{script->destroy();}catch(...){}
        return 1;
    }
}
