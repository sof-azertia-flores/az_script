#include "p256.h"
#include <fstream>
#include <iostream>
#include <filesystem>
#include <iterator>
#include <string>
#include <vector>
namespace {
std::string usage() {
    return "Usage:\n"
           "  azscript-sign-extern genkey --private private.pem --public public.pem\n"
           "  azscript-sign-extern sign --key private.pem --library library-file [--output signature]\n";
}
std::vector<unsigned char> read_file(const std::filesystem::path& path) {
    std::ifstream input(path,std::ios::binary);
    if(!input)throw std::runtime_error("Cannot open "+path.string());
    return {std::istreambuf_iterator<char>(input),{}};
}
void write_file(const std::filesystem::path& path,const std::string& text,bool secret) {
    std::ofstream output(path,std::ios::binary|std::ios::trunc);
    if(!output)throw std::runtime_error("Cannot write "+path.string());
    output.write(text.data(),static_cast<std::streamsize>(text.size()));
    if(!output)throw std::runtime_error("Cannot write "+path.string());
    if(secret)std::filesystem::permissions(path,std::filesystem::perms::owner_read|std::filesystem::perms::owner_write);
}
void write_bytes(const std::filesystem::path& path,const std::vector<std::uint8_t>& bytes) {
    std::ofstream output(path,std::ios::binary|std::ios::trunc);
    if(!output)throw std::runtime_error("Cannot write "+path.string());
    output.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if(!output)throw std::runtime_error("Cannot write "+path.string());
}
std::string option(int& index,int argc,char** argv) {
    if(index+1>=argc)throw std::invalid_argument(std::string("Missing value for ")+argv[index]);
    return argv[++index];
}
std::filesystem::path signature_path(const std::filesystem::path& library) {
    std::string name=library.filename().string();
    for(const char* suffix:{".so",".dylib",".dll"}) {
        auto length=std::char_traits<char>::length(suffix);
        if(name.size()>length&&name.compare(name.size()-length,length,suffix)==0) {
            name.resize(name.size()-length);break;
        }
    }
    auto parent=library.parent_path();
    if(parent.empty())parent=".";
    return parent/(name+".signature");
}
}
int main(int argc,char** argv) {
    try {
        if(argc<2){std::cerr<<usage();return 2;}
        std::string command=argv[1];
        if(command=="genkey") {
            std::string priv,pub;
            for(int i=2;i<argc;++i) {
                std::string arg=argv[i];
                if(arg=="--private")priv=option(i,argc,argv);
                else if(arg=="--public")pub=option(i,argc,argv);
                else throw std::invalid_argument("Unexpected argument: "+arg);
            }
            if(priv.empty()||pub.empty())throw std::invalid_argument("genkey requires --private and --public");
            azertian::p256_keypair key;
            if(!azertian::p256_generate(key))throw std::runtime_error("Cannot generate a P-256 key");
            write_file(priv,azertian::p256_private_pem(key),true);
            write_file(pub,azertian::p256_public_pem(key.public_key),false);
            return 0;
        }
        if(command=="sign") {
            std::string key_path,library;std::filesystem::path output;
            for(int i=2;i<argc;++i) {
                std::string arg=argv[i];
                if(arg=="--key")key_path=option(i,argc,argv);
                else if(arg=="--library")library=option(i,argc,argv);
                else if(arg=="--output")output=option(i,argc,argv);
                else throw std::invalid_argument("Unexpected argument: "+arg);
            }
            if(key_path.empty()||library.empty())throw std::invalid_argument("sign requires --key and --library");
            auto pem_bytes=read_file(key_path);
            std::string pem(pem_bytes.begin(),pem_bytes.end());
            azertian::p256_keypair key;std::string error;
            if(!azertian::p256_parse_private_pem(pem,key,error))throw std::invalid_argument(error);
            auto bytes=read_file(library);
            std::uint8_t hash[32];
            azertian::p256_sha256(bytes.data(),bytes.size(),hash);
            std::vector<std::uint8_t> signature;
            if(!azertian::p256_sign(key.d,hash,signature))throw std::runtime_error("Cannot sign the library");
            if(output.empty())output=signature_path(library);
            write_bytes(output,signature);
            return 0;
        }
        std::cerr<<usage();return 2;
    }catch(const std::exception& error) {
        std::cerr<<"azscript-sign-extern: "<<error.what()<<'\n';return 1;
    }
}
