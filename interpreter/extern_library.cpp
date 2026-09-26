#include "extern_library.h"
#include "internelFunctions.h"
#include "p256.h"
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <filesystem>
#include <set>
#include <stdexcept>
#include <vector>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#if defined(__linux__)
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace azertian {
namespace {
std::vector<p256_point>& trusted_keys(){static std::vector<p256_point> keys;return keys;}
std::set<std::string>& loaded_libraries(){static std::set<std::string> paths;return paths;}
constexpr std::size_t kMaxLibraryBytes=64*1024*1024;
constexpr std::size_t kMaxSignatureBytes=8192;

std::string platform_suffix() {
#if defined(_WIN32)
    return ".dll";
#elif defined(__APPLE__)
    return ".dylib";
#else
    return ".so";
#endif
}
void strip_platform_suffix(std::string& filename) {
    for(const char* suffix: {".so",".dylib",".dll"}) {
        const auto length=std::strlen(suffix);
        if(filename.size()>length&&filename.compare(filename.size()-length,length,suffix)==0) {
            filename.resize(filename.size()-length);return;
        }
    }
}
std::filesystem::path executable_directory() {
#if defined(_WIN32)
    wchar_t buffer[MAX_PATH];
    DWORD length=GetModuleFileNameW(nullptr,buffer,MAX_PATH);
    if(length>0&&length<MAX_PATH)return std::filesystem::path(buffer).parent_path();
#elif defined(__APPLE__)
    // _NSGetExecutablePath is available without an extra framework.
    extern "C" int _NSGetExecutablePath(char*,std::uint32_t*);
    std::uint32_t size=0;_NSGetExecutablePath(nullptr,&size);
    std::string buffer(size,'\0');
    if(_NSGetExecutablePath(buffer.data(),&size)==0)return std::filesystem::path(buffer.c_str()).parent_path();
#else
    char buffer[4096];
    ssize_t length=::readlink("/proc/self/exe",buffer,sizeof buffer-1);
    if(length>0){buffer[length]='\0';return std::filesystem::path(buffer).parent_path();}
#endif
    return std::filesystem::current_path();
}
std::filesystem::path shared_library_directory() {
#if !defined(_WIN32)
    Dl_info info{};
    if(dladdr(reinterpret_cast<void*>(&load_standalone_trusted_keys),&info)&&info.dli_fname)
        return std::filesystem::path(info.dli_fname).parent_path();
#else
    HMODULE module=nullptr;
    if(GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(&load_standalone_trusted_keys),&module)) {
        wchar_t buffer[MAX_PATH];
        DWORD length=GetModuleFileNameW(module,buffer,MAX_PATH);
        if(length>0&&length<MAX_PATH)return std::filesystem::path(buffer).parent_path();
    }
#endif
    return executable_directory();
}
std::vector<unsigned char> read_bounded(const std::filesystem::path& path,std::size_t limit,const char* what) {
    std::error_code error;
    if(!std::filesystem::is_regular_file(path,error))throw std::runtime_error(std::string(what)+" is missing: "+path.string());
    auto size=std::filesystem::file_size(path,error);
    if(error||size>limit)throw std::runtime_error(std::string(what)+" exceeds the size limit: "+path.string());
    std::ifstream input(path,std::ios::binary);
    if(!input)throw std::runtime_error(std::string("Cannot open ")+what+": "+path.string());
    std::vector<unsigned char> bytes(static_cast<std::size_t>(size));
    if(size&&!input.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(size)))
        throw std::runtime_error(std::string("Cannot read ")+what+": "+path.string());
    return bytes;
}
void verify_signature(const std::vector<unsigned char>& library,const std::vector<unsigned char>& signature) {
    if(trusted_keys().empty())throw std::runtime_error("No trusted public key is configured");
    std::uint8_t hash[32];
    p256_sha256(library.data(),library.size(),hash);
    for(const auto& key:trusted_keys())if(p256_verify(key,hash,signature.data(),signature.size()))return;
    throw std::runtime_error("Extern library signature verification failed");
}
std::vector<std::filesystem::path> bare_name_directories() {
    std::vector<std::filesystem::path> directories;
    directories.push_back(std::filesystem::current_path());
    if(const char* env=std::getenv("AZSCRIPT_EXTERN_PATH")) {
#if defined(_WIN32)
        const char separator=';';
#else
        const char separator=':';
#endif
        std::string text(env);std::size_t start=0;
        while(start<=text.size()) {
            auto end=text.find(separator,start);
            auto piece=text.substr(start,end==std::string::npos?std::string::npos:end-start);
            if(!piece.empty())directories.emplace_back(piece);
            if(end==std::string::npos)break;
            start=end+1;
        }
    }
    directories.push_back(executable_directory());
    return directories;
}

struct opened_library {void* handle=nullptr;};
opened_library map_verified_image(const std::vector<unsigned char>& bytes) {
#if !defined(_WIN32)
#if defined(__linux__)
    int fd=static_cast<int>(syscall(SYS_memfd_create,"azscript-extern",1));
    if(fd>=0) {
        std::size_t written=0;bool ok=true;
        while(written<bytes.size()) {
            ssize_t n=::write(fd,bytes.data()+written,bytes.size()-written);
            if(n<0){ok=false;break;}written+=static_cast<std::size_t>(n);
        }
        if(ok) {
            char proc[64];std::snprintf(proc,sizeof proc,"/proc/self/fd/%d",fd);
            void* handle=dlopen(proc,RTLD_NOW|RTLD_LOCAL);
            if(handle){static std::vector<int> kept;kept.push_back(fd);return {handle};}
        }
        ::close(fd);
    }
#endif
    auto directory=std::filesystem::temp_directory_path()/"azscript-extern";
    std::error_code ignored;std::filesystem::create_directories(directory,ignored);
    static std::uint64_t serial=0;
    auto path=directory/("lib-"+std::to_string(++serial)+platform_suffix());
    {
        std::ofstream output(path,std::ios::binary|std::ios::trunc);
        if(!output)throw std::runtime_error("Cannot stage a verified extern library");
        output.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
        if(!output)throw std::runtime_error("Cannot stage a verified extern library");
    }
    void* handle=dlopen(path.c_str(),RTLD_NOW|RTLD_LOCAL);
    const char* reason=dlerror();
    std::filesystem::remove(path,ignored);
    if(!handle)throw std::runtime_error(std::string("Cannot load extern library: ")+(reason&&*reason?reason:"unknown error"));
    return {handle};
#else
    auto directory=std::filesystem::temp_directory_path()/"azscript-extern";
    std::error_code ignored;std::filesystem::create_directories(directory,ignored);
    wchar_t file[MAX_PATH];
    if(GetTempFileNameW(directory.wstring().c_str(),L"azs",0,file)==0)throw std::runtime_error("Cannot stage a verified extern library");
    auto path=std::filesystem::path(file);path.replace_extension(platform_suffix());
    std::filesystem::rename(file,path,ignored);
    std::ofstream output(path,std::ios::binary|std::ios::trunc);
    if(!output)throw std::runtime_error("Cannot stage a verified extern library");
    output.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if(!output)throw std::runtime_error("Cannot stage a verified extern library");
    output.close();
    HMODULE handle=LoadLibraryW(path.wstring().c_str());
    if(!handle)throw std::runtime_error("Cannot load extern library");
    static std::vector<std::filesystem::path> kept;kept.push_back(path);
    return {handle};
#endif
}
void invoke_entry(void* handle) {
#if defined(_WIN32)
    auto entry=reinterpret_cast<void(*)()>(GetProcAddress(static_cast<HMODULE>(handle),"azscript_load_extern"));
#else
    dlerror();
    auto entry=reinterpret_cast<void(*)()>(dlsym(handle,"azscript_load_extern"));
#endif
    if(!entry)throw std::runtime_error("Extern library is missing azscript_load_extern");
    auto before=executor_registry_generation();
    entry();
    if(executor_registry_generation()==before)
        throw std::runtime_error("Extern library registered no host executor in this runtime");
}
void consider_trusted_file(const std::filesystem::path& path) {
    std::error_code error;
    if(!std::filesystem::is_regular_file(path,error))return;
    auto bytes=read_bounded(path,1024*1024,"Trusted public key");
    std::string pem(bytes.begin(),bytes.end());
    add_trusted_public_key_pem(pem);
}
}
void add_trusted_public_key_pem(std::string_view pem) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    std::vector<p256_point> parsed;std::string error;
    if(!p256_parse_public_pem(pem,parsed,error))throw std::invalid_argument(error);
    trusted_keys().insert(trusted_keys().end(),parsed.begin(),parsed.end());
}
void clear_trusted_public_keys() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    trusted_keys().clear();
}
std::size_t trusted_public_key_count() {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    return trusted_keys().size();
}
void load_standalone_trusted_keys() {
    if(const char* env=std::getenv("AZSCRIPT_TRUSTED_KEY")) {
        std::string value(env);
        if(value.find('\0')!=std::string::npos)throw std::invalid_argument("AZSCRIPT_TRUSTED_KEY contains NUL");
        if(value.find("-----BEGIN")!=std::string::npos)add_trusted_public_key_pem(value);
        else {
            auto bytes=read_bounded(value,1024*1024,"Trusted public key");
            add_trusted_public_key_pem(std::string(bytes.begin(),bytes.end()));
        }
    }
    consider_trusted_file(std::filesystem::current_path()/"trusted_key.pem");
    consider_trusted_file(executable_directory()/"trusted_key.pem");
    consider_trusted_file(shared_library_directory()/"trusted_key.pem");
}
void load_named_extern_library(const std::string& name) {
    std::lock_guard<std::recursive_mutex> lock(runtime_mutex());
    if(name.empty()||name.find('\0')!=std::string::npos)throw std::invalid_argument("load_extern_library requires a library name");
    std::filesystem::path input(name);
    std::string stem=input.filename().string();
    strip_platform_suffix(stem);
    if(stem.empty()||stem=="."||stem=="..")throw std::invalid_argument("load_extern_library requires a library name");
    const auto suffix=platform_suffix();
    std::filesystem::path library;
    std::filesystem::path signature;
    if(input.has_parent_path()) {
        library=std::filesystem::absolute(input.parent_path()/(stem+suffix));
        signature=library.parent_path()/(stem+".signature");
        if(!std::filesystem::is_regular_file(library))throw std::runtime_error("Extern library was not found: "+library.string());
    } else {
        bool found=false;
        for(const auto& directory:bare_name_directories()) {
            auto candidate=directory/(stem+suffix);
            std::error_code error;
            if(!std::filesystem::is_regular_file(candidate,error))continue;
            library=candidate;signature=directory/(stem+".signature");found=true;break;
        }
        if(!found)throw std::runtime_error("Extern library was not found: "+stem+suffix);
    }
    auto canonical=std::filesystem::weakly_canonical(library).string();
    if(loaded_libraries().contains(canonical))return;
    auto library_bytes=read_bounded(library,kMaxLibraryBytes,"Extern library");
    auto signature_bytes=read_bounded(signature,kMaxSignatureBytes,"Extern library signature");
    verify_signature(library_bytes,signature_bytes);
    auto opened=map_verified_image(library_bytes);
    invoke_entry(opened.handle);
    loaded_libraries().insert(std::move(canonical));
}
}
