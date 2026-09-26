#include "extern_library.h"
#include "internelFunctions.h"
#include "p256.h"
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <filesystem>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>
#else
#include <cerrno>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#endif
#if defined(__linux__)
#include <sys/syscall.h>
#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x4
#endif
#endif
#if defined(__APPLE__)
#include <mach-o/dyld.h>
#ifndef RTLD_NOLOAD
#define RTLD_NOLOAD 0x10
#endif
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

struct ByteReader {
    const unsigned char* data;
    std::size_t size;
    bool little;
    bool ok=true;
    std::uint64_t take(std::size_t offset,int width) {
        if(width<=0||offset+static_cast<std::size_t>(width)>size){ok=false;return 0;}
        std::uint64_t value=0;
        if(little){for(int i=width-1;i>=0;--i)value=(value<<8)|data[offset+static_cast<std::size_t>(i)];}
        else {for(int i=0;i<width;++i)value=(value<<8)|data[offset+static_cast<std::size_t>(i)];}
        return value;
    }
};
std::string dependency_leaf(std::string name) {
    for(const char* prefix:{"@rpath/","@loader_path/","@executable_path/"}) {
        const auto length=std::strlen(prefix);
        if(name.size()>=length&&name.compare(0,length,prefix)==0)name.erase(0,length);
    }
    const auto slash=name.find_last_of("/\\");
    if(slash!=std::string::npos)name.erase(0,slash+1);
    return name;
}
bool equals_ignore_case(const std::string& text,const char* expected) {
    const auto length=std::strlen(expected);
    if(text.size()!=length)return false;
    for(std::size_t i=0;i<length;++i) {
        char left=text[i];
        if(left>='A'&&left<='Z')left=static_cast<char>(left-'A'+'a');
        char right=expected[i];
        if(right>='A'&&right<='Z')right=static_cast<char>(right-'A'+'a');
        if(left!=right)return false;
    }
    return true;
}
bool starts_with(const std::string& text,const char* prefix) {
    const auto length=std::strlen(prefix);
    return text.size()>=length&&text.compare(0,length,prefix)==0;
}
bool allowed_dependency(const std::string& leaf) {
    if(leaf.empty()||leaf.size()>4096||leaf.find('/')!=std::string::npos||leaf.find('\\')!=std::string::npos)return false;
    if(leaf=="libabdInvoker.so"||leaf=="libabdInvoker.dylib"||starts_with(leaf,"libabdInvoker.so."))return true;
    if(equals_ignore_case(leaf,"abdInvoker.dll"))return true;
    if(starts_with(leaf,"libc.so.")||starts_with(leaf,"libm.so.")||starts_with(leaf,"libdl.so.")
            ||starts_with(leaf,"libpthread.so.")||starts_with(leaf,"librt.so.")
            ||starts_with(leaf,"libgcc_s.so.")||starts_with(leaf,"libstdc++.so.")
            ||starts_with(leaf,"libc.musl-")||starts_with(leaf,"ld-musl-"))return true;
    if(starts_with(leaf,"ld-linux")&&leaf.find(".so")!=std::string::npos)return true;
    if(leaf=="libSystem.B.dylib"||leaf=="libc++.1.dylib"||leaf=="libc++abi.1.dylib"||leaf=="libunwind.1.dylib")return true;
    if(equals_ignore_case(leaf,"kernel32.dll")||equals_ignore_case(leaf,"kernelbase.dll")||equals_ignore_case(leaf,"ntdll.dll")
            ||equals_ignore_case(leaf,"ucrtbase.dll")||equals_ignore_case(leaf,"vcruntime140.dll")
            ||equals_ignore_case(leaf,"vcruntime140_1.dll")||equals_ignore_case(leaf,"msvcp140.dll")
            ||equals_ignore_case(leaf,"msvcp140_1.dll")||equals_ignore_case(leaf,"msvcp140_2.dll")
            ||equals_ignore_case(leaf,"concrt140.dll")||equals_ignore_case(leaf,"msvcrt.dll")
            ||equals_ignore_case(leaf,"libstdc++-6.dll")||equals_ignore_case(leaf,"libgcc_s_seh-1.dll")
            ||equals_ignore_case(leaf,"libgcc_s_dw2-1.dll")||equals_ignore_case(leaf,"libwinpthread-1.dll"))return true;
    std::string lower=leaf;
    for(char& ch:lower)if(ch>='A'&&ch<='Z')ch=static_cast<char>(ch-'A'+'a');
    return starts_with(lower,"api-ms-win-crt-")||starts_with(lower,"api-ms-win-core-");
}
bool runtime_dependency(const std::string& leaf) {
    return leaf=="libabdInvoker.so"||leaf=="libabdInvoker.dylib"||starts_with(leaf,"libabdInvoker.so.")
        ||equals_ignore_case(leaf,"abdInvoker.dll");
}
void read_c_string(ByteReader& reader,std::size_t offset,std::size_t limit,std::string& out) {
    out.clear();
    if(offset>=reader.size||limit==0){reader.ok=false;return;}
    const std::size_t end=std::min(reader.size,offset+limit);
    for(std::size_t i=offset;i<end;++i) {
        if(reader.data[i]==0)return;
        out.push_back(static_cast<char>(reader.data[i]));
    }
    reader.ok=false;
}
bool elf_dependencies(const std::vector<unsigned char>& bytes,std::vector<std::string>& needed) {
    if(bytes.size()<64||bytes[0]!=0x7f||bytes[1]!='E'||bytes[2]!='L'||bytes[3]!='F')return false;
    if(bytes[4]!=1&&bytes[4]!=2)throw std::runtime_error("Cannot load extern library");
    if(bytes[5]!=1&&bytes[5]!=2)throw std::runtime_error("Cannot load extern library");
    ByteReader reader{bytes.data(),bytes.size(),bytes[5]==1};
    const bool elf64=bytes[4]==2;
    const std::size_t phoff=static_cast<std::size_t>(reader.take(elf64?32:28,elf64?8:4));
    const std::size_t phentsize=static_cast<std::size_t>(reader.take(elf64?54:42,2));
    const std::size_t phnum=static_cast<std::size_t>(reader.take(elf64?56:44,2));
    if(!reader.ok||phnum==0||phnum>512||phentsize<(elf64?56u:32u)
            ||phoff>bytes.size()||phentsize>(bytes.size()-phoff)/phnum)
        throw std::runtime_error("Cannot load extern library");
    struct Segment {std::uint64_t offset,vaddr,filesz;};
    std::vector<Segment> loads;
    Segment dynamic{};bool have_dynamic=false;
    for(std::size_t i=0;i<phnum;++i) {
        const std::size_t entry=phoff+i*phentsize;
        const auto type=reader.take(entry,4);
        std::uint64_t offset,vaddr,filesz;
        if(elf64){offset=reader.take(entry+8,8);vaddr=reader.take(entry+16,8);filesz=reader.take(entry+32,8);}
        else {offset=reader.take(entry+4,4);vaddr=reader.take(entry+8,4);filesz=reader.take(entry+16,4);}
        if(type==1)loads.push_back({offset,vaddr,filesz});
        if(type==2){dynamic={offset,vaddr,filesz};have_dynamic=true;}
    }
    if(!reader.ok)throw std::runtime_error("Cannot load extern library");
    if(!have_dynamic)return true;
    if(dynamic.offset>bytes.size()||dynamic.filesz>bytes.size()-static_cast<std::size_t>(dynamic.offset))
        throw std::runtime_error("Cannot load extern library");
    const int step=elf64?16:8;
    if(dynamic.filesz>1024*1024||dynamic.filesz/static_cast<std::uint64_t>(step)>4096)throw std::runtime_error("Cannot load extern library");
    std::uint64_t strtab=0,strsz=0;bool have_strtab=false;
    std::vector<std::uint64_t> offsets;
    const int half=step/2;
    for(std::uint64_t cursor=0;cursor+static_cast<std::uint64_t>(step)<=dynamic.filesz;cursor+=static_cast<std::uint64_t>(step)) {
        const std::size_t at=static_cast<std::size_t>(dynamic.offset+cursor);
        const auto real_tag=reader.take(at,half);
        const auto value=reader.take(at+static_cast<std::size_t>(half),half);
        if(!reader.ok)throw std::runtime_error("Cannot load extern library");
        if(real_tag==0)break;
        if(real_tag==1||real_tag==0x7ffffffdULL||real_tag==0x7fffffffULL)offsets.push_back(value);
        else if(real_tag==5){strtab=value;have_strtab=true;}
        else if(real_tag==10)strsz=value;
    }
    if(!have_strtab||strsz==0||strsz>1024*1024||offsets.size()>128)throw std::runtime_error("Cannot load extern library");
    std::size_t str_file=reader.size;
    for(const auto& segment:loads) {
        if(strtab<segment.vaddr||strtab-segment.vaddr>=segment.filesz)continue;
        str_file=static_cast<std::size_t>(segment.offset+(strtab-segment.vaddr));
        break;
    }
    if(str_file>=reader.size||strsz>reader.size-str_file)throw std::runtime_error("Cannot load extern library");
    for(auto offset:offsets) {
        if(offset>=strsz)throw std::runtime_error("Cannot load extern library");
        std::string name;
        read_c_string(reader,str_file+static_cast<std::size_t>(offset),static_cast<std::size_t>(strsz-offset),name);
        if(!reader.ok)throw std::runtime_error("Cannot load extern library");
        needed.push_back(std::move(name));
    }
    return true;
}
bool macho_dependencies(const unsigned char* bytes,std::size_t size,std::vector<std::string>& needed) {
    if(size<8)return false;
    if(bytes[0]==0xca&&bytes[1]==0xfe&&bytes[2]==0xba&&(bytes[3]==0xbe||bytes[3]==0xbf)) {
        const bool fat64=bytes[3]==0xbf;
        ByteReader fat{bytes,size,false};
        const auto count=fat.take(4,4);
        if(!fat.ok||count==0||count>32)throw std::runtime_error("Cannot load extern library");
        const std::size_t arch_size=fat64?32:20;
        for(std::uint64_t i=0;i<count;++i) {
            const std::size_t at=8+static_cast<std::size_t>(i)*arch_size;
            const auto cpu=fat.take(at,4);
            const auto offset=fat.take(at+8,fat64?8:4);
            const auto slice=fat.take(at+(fat64?16:12),fat64?8:4);
#if defined(__aarch64__)
            const bool host=cpu==0x0100000cu;
#elif defined(__x86_64__)
            const bool host=cpu==0x01000007u;
#else
            const bool host=false;
#endif
            if(!host)continue;
            if(offset>size||slice>size-offset)throw std::runtime_error("Cannot load extern library");
            return macho_dependencies(bytes+static_cast<std::size_t>(offset),static_cast<std::size_t>(slice),needed);
        }
        throw std::runtime_error("Cannot load extern library");
    }
    ByteReader probe{bytes,size,true};
    const auto magic=probe.take(0,4);
    const bool little=magic==0xfeedfacfu||magic==0xfeedfaceu;
    const bool big=magic==0xcffaedfeu||magic==0xcefaedfeu;
    if(!little&&!big)return false;
    const bool macho64=magic==0xfeedfacfu||magic==0xcffaedfeu;
    ByteReader reader{bytes,size,little};
    const auto ncmds=reader.take(16,4);
    const auto sizeofcmds=reader.take(20,4);
    const std::size_t header=macho64?32:28;
    if(!reader.ok||ncmds>4096||sizeofcmds>size||header+sizeofcmds>size)throw std::runtime_error("Cannot load extern library");
    std::size_t cursor=header;
    for(std::uint64_t i=0;i<ncmds;++i) {
        const auto cmd=reader.take(cursor,4);
        const auto cmdsize=reader.take(cursor+4,4);
        if(!reader.ok||cmdsize<8||cursor+cmdsize>size)throw std::runtime_error("Cannot load extern library");
        const bool load=cmd==0xcu||cmd==0x80000018u||cmd==0x8000001fu||cmd==0x80000023u||cmd==0x20u;
        if(load) {
            const auto name_offset=reader.take(cursor+8,4);
            if(name_offset<24||name_offset>=cmdsize)throw std::runtime_error("Cannot load extern library");
            std::string name;
            read_c_string(reader,cursor+static_cast<std::size_t>(name_offset),static_cast<std::size_t>(cmdsize-name_offset),name);
            if(!reader.ok)throw std::runtime_error("Cannot load extern library");
            needed.push_back(std::move(name));
        }
        cursor+=static_cast<std::size_t>(cmdsize);
    }
    if(needed.size()>128)throw std::runtime_error("Cannot load extern library");
    return true;
}
bool pe_dependencies(const std::vector<unsigned char>& bytes,std::vector<std::string>& needed) {
    if(bytes.size()<0x40||bytes[0]!='M'||bytes[1]!='Z')return false;
    ByteReader reader{bytes.data(),bytes.size(),true};
    const auto lfanew=static_cast<std::size_t>(reader.take(0x3c,4));
    if(!reader.ok||lfanew+24>bytes.size()||reader.take(lfanew,4)!=0x4550u)throw std::runtime_error("Cannot load extern library");
    const auto sections=reader.take(lfanew+6,2);
    const auto optsize=reader.take(lfanew+20,2);
    const std::size_t optional=lfanew+24;
    if(sections>96||optional+optsize>bytes.size())throw std::runtime_error("Cannot load extern library");
    const auto magic=reader.take(optional,2);
    std::size_t directory=0;
    if(magic==0x10bu)directory=optional+96;
    else if(magic==0x20bu)directory=optional+112;
    else throw std::runtime_error("Cannot load extern library");
    if(directory+16>bytes.size())throw std::runtime_error("Cannot load extern library");
    const auto import_rva=reader.take(directory+8,4);
    if(import_rva==0)return true;
    const std::size_t section_table=optional+static_cast<std::size_t>(optsize);
    auto rva_to_offset=[&](std::uint64_t rva)->std::size_t {
        for(std::uint64_t i=0;i<sections;++i) {
            const std::size_t section=section_table+static_cast<std::size_t>(i)*40;
            const auto virtual_size=reader.take(section+8,4);
            const auto virtual_address=reader.take(section+12,4);
            const auto raw_size=reader.take(section+16,4);
            const auto raw=reader.take(section+20,4);
            const auto span=virtual_size>raw_size?virtual_size:raw_size;
            if(rva>=virtual_address&&rva-virtual_address<span&&rva-virtual_address<raw_size)
                return static_cast<std::size_t>(raw+(rva-virtual_address));
        }
        return static_cast<std::size_t>(-1);
    };
    const auto import_offset=rva_to_offset(import_rva);
    if(import_offset==static_cast<std::size_t>(-1))throw std::runtime_error("Cannot load extern library");
    for(int descriptor=0;descriptor<128;++descriptor) {
        const std::size_t at=import_offset+static_cast<std::size_t>(descriptor)*20;
        if(at+20>bytes.size())throw std::runtime_error("Cannot load extern library");
        const auto name_rva=reader.take(at+12,4);
        if(reader.take(at,4)==0&&reader.take(at+16,4)==0&&name_rva==0)break;
        const auto name_offset=rva_to_offset(name_rva);
        if(name_offset==static_cast<std::size_t>(-1))throw std::runtime_error("Cannot load extern library");
        std::string name;
        read_c_string(reader,name_offset,bytes.size()-name_offset,name);
        if(!reader.ok||name.empty())throw std::runtime_error("Cannot load extern library");
        needed.push_back(std::move(name));
    }
    return true;
}
std::vector<std::string> image_dependencies(const std::vector<unsigned char>& bytes) {
    std::vector<std::string> needed;
    if(elf_dependencies(bytes,needed)||macho_dependencies(bytes.data(),bytes.size(),needed)||pe_dependencies(bytes,needed))
        return needed;
    throw std::runtime_error("Cannot load extern library");
}
#if !defined(_WIN32)
bool dependency_loaded(const std::string& leaf,const std::string& original) {
    if(void* existing=dlopen(original.c_str(),RTLD_NOW|RTLD_NOLOAD)){(void)existing;return true;}
    dlerror();
    if(leaf!=original) {
        if(void* existing=dlopen(leaf.c_str(),RTLD_NOW|RTLD_NOLOAD)){(void)existing;return true;}
        dlerror();
    }
#if defined(__APPLE__)
    const auto count=_dyld_image_count();
    for(std::uint32_t i=0;i<count;++i) {
        const char* image=_dyld_get_image_name(i);
        if(image&&dependency_leaf(image)==leaf)return true;
    }
#endif
    return false;
}
void pin_dependency(const std::string& original) {
    const auto leaf=dependency_leaf(original);
    if(!allowed_dependency(leaf))throw std::runtime_error("Extern library depends on an untrusted library: "+original);
    if(dependency_loaded(leaf,original))return;
    if(runtime_dependency(leaf))throw std::runtime_error("Extern library requires the shared AzScript runtime");
    if(!dlopen(leaf.c_str(),RTLD_NOW|RTLD_GLOBAL)) {
        const char* reason=dlerror();
        throw std::runtime_error(std::string("Cannot load trusted extern dependency: ")+leaf+(reason?std::string(": ")+reason:""));
    }
}
#else
void pin_dependency(const std::string& original) {
    const auto leaf=dependency_leaf(original);
    if(!allowed_dependency(leaf))throw std::runtime_error("Extern library depends on an untrusted library: "+original);
    std::wstring wide(leaf.begin(),leaf.end());
    if(GetModuleHandleW(wide.c_str()))return;
    if(runtime_dependency(leaf))throw std::runtime_error("Extern library requires the shared AzScript runtime");
    if(!LoadLibraryW(wide.c_str()))throw std::runtime_error("Cannot load trusted extern dependency: "+leaf);
}
#endif
void require_trusted_dependencies(const std::vector<unsigned char>& bytes) {
    for(const auto& dependency:image_dependencies(bytes))pin_dependency(dependency);
}
#if !defined(_WIN32)
bool write_all(int fd,const std::vector<unsigned char>& bytes) {
    std::size_t written=0;
    while(written<bytes.size()) {
        const ssize_t n=::write(fd,bytes.data()+written,bytes.size()-written);
        if(n<0){if(errno==EINTR)continue;return false;}
        written+=static_cast<std::size_t>(n);
    }
    return true;
}
#endif
struct opened_library {void* handle=nullptr;};
opened_library map_verified_image(const std::vector<unsigned char>& bytes) {
#if defined(__linux__)
    int fd=static_cast<int>(syscall(SYS_memfd_create,"azscript-extern",1));
    if(fd<0||!write_all(fd,bytes)) {
        if(fd>=0)::close(fd);
        auto pattern=(std::filesystem::temp_directory_path()/"azscript-extern-XXXXXX").string();
        std::vector<char> directory(pattern.begin(),pattern.end());
        directory.push_back('\0');
        if(!::mkdtemp(directory.data()))throw std::runtime_error("Cannot stage a verified extern library");
        const std::string dir(directory.data());
        const std::string file=dir+"/image";
        fd=::open(file.c_str(),O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
        if(fd>=0)::unlink(file.c_str());
        ::rmdir(dir.c_str());
        if(fd<0||!write_all(fd,bytes)) {
            if(fd>=0)::close(fd);
            throw std::runtime_error("Cannot stage a verified extern library");
        }
    }
    char proc[64];std::snprintf(proc,sizeof proc,"/proc/self/fd/%d",fd);
    void* handle=dlopen(proc,RTLD_NOW|RTLD_LOCAL);
    const char* reason=dlerror();
    if(!handle){::close(fd);throw std::runtime_error(std::string("Cannot load extern library: ")+(reason&&*reason?reason:"unknown error"));}
    static std::vector<int> kept;kept.push_back(fd);
    return {handle};
#elif defined(__APPLE__)
    auto pattern=(std::filesystem::temp_directory_path()/"azscript-extern-XXXXXX").string();
    std::vector<char> directory(pattern.begin(),pattern.end());
    directory.push_back('\0');
    if(!::mkdtemp(directory.data()))throw std::runtime_error("Cannot stage a verified extern library");
    const std::string dir(directory.data());
    const std::string file=dir+"/image"+platform_suffix();
    const int fd=::open(file.c_str(),O_RDWR|O_CREAT|O_EXCL|O_CLOEXEC|O_NOFOLLOW,0600);
    if(fd<0||!write_all(fd,bytes)) {
        if(fd>=0)::close(fd);
        ::unlink(file.c_str());::rmdir(dir.c_str());
        throw std::runtime_error("Cannot stage a verified extern library");
    }
    ::close(fd);
    void* handle=dlopen(file.c_str(),RTLD_NOW|RTLD_LOCAL);
    const char* reason=dlerror();
    ::unlink(file.c_str());::rmdir(dir.c_str());
    if(!handle)throw std::runtime_error(std::string("Cannot load extern library: ")+(reason&&*reason?reason:"unknown error"));
    return {handle};
#elif defined(_WIN32)
    wchar_t root[MAX_PATH];
    const DWORD root_length=GetTempPathW(MAX_PATH,root);
    unsigned char random[16];
    if(root_length==0||root_length>=MAX_PATH
            ||BCryptGenRandom(nullptr,random,sizeof random,BCRYPT_USE_SYSTEM_PREFERRED_RNG)!=0)
        throw std::runtime_error("Cannot stage a verified extern library");
    std::wstring directory(root,root_length);
    if(directory.back()!=L'\\'&&directory.back()!=L'/')directory.push_back(L'\\');
    directory+=L"azscript-extern-";
    static constexpr wchar_t hex[]=L"0123456789abcdef";
    for(unsigned char byte:random){directory.push_back(hex[byte>>4]);directory.push_back(hex[byte&0x0f]);}
    if(!CreateDirectoryW(directory.c_str(),nullptr))throw std::runtime_error("Cannot stage a verified extern library");
    const std::wstring file=directory+L"\\image.dll";
    HANDLE output=CreateFileW(file.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(output==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot stage a verified extern library");
    std::size_t written=0;bool ok=true;
    while(written<bytes.size()) {
        DWORD chunk=0;
        const DWORD ask=static_cast<DWORD>(std::min<std::size_t>(bytes.size()-written,1<<20));
        if(!WriteFile(output,bytes.data()+written,ask,&chunk,nullptr)||chunk==0){ok=false;break;}
        written+=chunk;
    }
    CloseHandle(output);
    if(!ok){DeleteFileW(file.c_str());RemoveDirectoryW(directory.c_str());throw std::runtime_error("Cannot stage a verified extern library");}
    HMODULE handle=LoadLibraryW(file.c_str());
    if(!handle){DeleteFileW(file.c_str());RemoveDirectoryW(directory.c_str());throw std::runtime_error("Cannot load extern library");}
    static std::vector<std::wstring> kept;kept.push_back(file);
    return {handle};
#else
    throw std::runtime_error("Cannot load extern library");
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
    // A standalone bundle may ship its public key beside the working directory.
    // Embedded hosts do not read this file; only azscript-run calls this function.
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
    require_trusted_dependencies(library_bytes);
    auto opened=map_verified_image(library_bytes);
    invoke_entry(opened.handle);
    loaded_libraries().insert(std::move(canonical));
}
}
