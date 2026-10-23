// Observe allocator effects that remain visible after a closed object region.
// Invoked by compiler_o3_heap.py in a fresh process for each compiler/engine.
#include <azscript/runtime.hpp>
#include "../interpreter/vm/session.h"
#include <blocks.h>
#include <heepalloc.h>

#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <string>

using namespace azertian;

namespace {
std::string quote(const std::string& value) {
    static constexpr char hex[]="0123456789abcdef";
    std::string result="\"";
    for(unsigned char c:value) {
        if(c=='"'||c=='\\') {result+='\\';result+=static_cast<char>(c);}
        else if(c<32) {result+="\\u00";result+=hex[c>>4];result+=hex[c&15];}
        else result+=static_cast<char>(c);
    }
    return result+'"';
}
std::string returned(const std::shared_ptr<variable>& value) {
    std::string payload;
    switch(value->type_tag()) {
    case INT_VALUE:payload=std::to_string(value->get<int>());break;
    case BOOLEAN_VALUE:payload=value->get<bool>()?"true":"false";break;
    case ADDRESS_VALUE:payload=std::to_string(value->get<address>().value);break;
    default:throw std::runtime_error("Unexpected heap regression result type");
    }
    return "{\"tag\":"+std::to_string(value->type_tag())+",\"value\":"+payload+'}';
}
}

int main(int argc,char** argv) {
    try {
        if(argc!=4||(std::string(argv[2])!="tree"&&std::string(argv[2])!="vm"))
            throw std::invalid_argument("usage: o3-heap-observation-host module tree|vm heap-length");
        std::ifstream input(argv[1],std::ios::binary);
        if(!input)throw std::runtime_error("Cannot open regression module");
        std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(input),{}};
        auto program=load_script(bytes.data(),bytes.size());
        if(std::string(argv[2])=="vm")vm::select_backend(*program,16*1024*1024);
        program->flush();
        // A host can create an empty trailing region directly or by restoring
        // a snapshot. Removing an automatic object's alloc/free would leave
        // this length unchanged, whereas its real cleanup shrinks the heap.
        heap::resize_heap(std::stoi(argv[3]));
        const int before=heap::lenHeap();
        std::string result="null",error="null";
        try {result=returned(program->invoke(0x0fff0000));}
        catch(const std::exception& caught) {error=quote(describe_error(caught));}
        const int length=heap::lenHeap(),allocations=heap::lenAlloc();
        const auto records=heap::object_records().size(),methods=heap::method_table_count();
        const auto next_block=blocks::address_of(blocks::create(0)).value;
        const auto first_fit=heap::alloc(1);heap::free(first_fit);
        program->destroy();
        std::cout<<"{\"return\":"<<result<<",\"error\":"<<error
                 <<",\"before_length\":"<<before<<",\"after_length\":"<<length
                 <<",\"allocations\":"<<allocations<<",\"object_records\":"<<records
                 <<",\"method_tables\":"<<methods<<",\"next_block\":"<<next_block
                 <<",\"first_fit\":"<<first_fit.value<<",\"teardown_length\":"<<heap::lenHeap()
                 <<",\"teardown_allocations\":"<<heap::lenAlloc()<<"}\n";
    } catch(const std::exception& error) {
        std::cerr<<error.what()<<'\n';return 1;
    }
}
