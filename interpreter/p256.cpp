#include "p256.h"
#include <cstring>
#include <fstream>
#include <stdexcept>
#if defined(_WIN32)
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <bcrypt.h>
#endif
namespace azertian {
namespace {
struct U256 {std::uint32_t v[8]{};};

constexpr std::uint32_t P[8]={0xffffffffu,0xffffffffu,0xffffffffu,0x00000000u,0x00000000u,0x00000000u,0x00000001u,0xffffffffu};
constexpr std::uint32_t N[8]={0xfc632551u,0xf3b9cac2u,0xa7179e84u,0xbce6faadu,0xffffffffu,0xffffffffu,0x00000000u,0xffffffffu};
constexpr std::uint32_t B[8]={0x27d2604bu,0x3bce3c3eu,0xcc53b0f6u,0x651d06b0u,0x769886bcu,0xb3ebbd55u,0xaa3a93e7u,0x5ac635d8u};
constexpr std::uint8_t GX[32]={
    0x6b,0x17,0xd1,0xf2,0xe1,0x2c,0x42,0x47,0xf8,0xbc,0xe6,0xe5,0x63,0xa4,0x40,0xf2,
    0x77,0x03,0x7d,0x81,0x2d,0xeb,0x33,0xa0,0xf4,0xa1,0x39,0x45,0xd8,0x98,0xc2,0x96};
constexpr std::uint8_t GY[32]={
    0x4f,0xe3,0x42,0xe2,0xfe,0x1a,0x7f,0x9b,0x8e,0xe7,0xeb,0x4a,0x7c,0x0f,0x9e,0x16,
    0x2b,0xce,0x33,0x57,0x6b,0x31,0x5e,0xce,0xcb,0xb6,0x40,0x68,0x37,0xbf,0x51,0xf5};

int cmp(const std::uint32_t* a,const std::uint32_t* b) {
    for(int i=7;i>=0;--i){if(a[i]<b[i])return -1;if(a[i]>b[i])return 1;}
    return 0;
}
bool zero(const U256& a){for(auto x:a.v)if(x)return false;return true;}
bool one(const U256& a){if(a.v[0]!=1)return false;for(int i=1;i<8;++i)if(a.v[i])return false;return true;}
std::uint32_t add_raw(std::uint32_t* r,const std::uint32_t* a,const std::uint32_t* b) {
    std::uint64_t c=0;for(int i=0;i<8;++i){c+=static_cast<std::uint64_t>(a[i])+b[i];r[i]=static_cast<std::uint32_t>(c);c>>=32;}
    return static_cast<std::uint32_t>(c);
}
std::uint32_t sub_raw(std::uint32_t* r,const std::uint32_t* a,const std::uint32_t* b) {
    std::int64_t c=0;
    for(int i=0;i<8;++i){c+=static_cast<std::int64_t>(a[i])-b[i];r[i]=static_cast<std::uint32_t>(c);c>>=32;}
    return c<0?1u:0u;
}
void shr1(U256& a,std::uint32_t high) {
    for(int i=0;i<8;++i){std::uint32_t next=i==7?high:a.v[i+1];a.v[i]=(a.v[i]>>1)|((next&1u)<<31);}
}
U256 from_be(const std::uint8_t b[32]) {
    U256 r{};
    for(int i=0;i<32;++i){int offset=31-i;r.v[offset/4]|=static_cast<std::uint32_t>(b[i])<<((offset%4)*8);}
    return r;
}
void to_be(const U256& a,std::uint8_t b[32]) {
    for(int i=0;i<32;++i){int offset=31-i;b[i]=static_cast<std::uint8_t>(a.v[offset/4]>>((offset%4)*8));}
}
U256 load_words(const std::uint32_t w[8]){U256 r;std::memcpy(r.v,w,sizeof r.v);return r;}

void mul_raw(std::uint32_t r[16],const std::uint32_t* a,const std::uint32_t* b) {
    std::memset(r,0,16*sizeof(std::uint32_t));
    for(int i=0;i<8;++i){
        std::uint64_t carry=0;
        for(int j=0;j<8;++j){
            std::uint64_t cur=r[i+j]+static_cast<std::uint64_t>(a[i])*b[j]+carry;
            r[i+j]=static_cast<std::uint32_t>(cur);carry=cur>>32;
        }
        r[i+8]=static_cast<std::uint32_t>(carry);
    }
}
// Both p and n have bit 255 set, so a 512-bit product is strictly less than
// two times (modulus << 256). One subtraction per bit is therefore exact.
U256 mod_m(const std::uint32_t wide[16],const std::uint32_t modulus[8]) {
    std::uint32_t acc[17]{};
    std::memcpy(acc,wide,16*sizeof(std::uint32_t));
    for(int shift=256;shift>=0;--shift){
        int word=shift/32,bit=shift%32;
        std::uint32_t shifted[18]{};
        std::uint64_t carry=0;
        for(int i=0;i<8;++i){
            carry=(static_cast<std::uint64_t>(modulus[i])<<bit)|carry;
            shifted[i+word]=static_cast<std::uint32_t>(carry);
            carry>>=32;
        }
        shifted[8+word]=static_cast<std::uint32_t>(carry);
        int relation=0;
        for(int i=17;i>=0;--i){
            std::uint32_t left=i<17?acc[i]:0;
            if(left!=shifted[i]){relation=left>shifted[i]?1:-1;break;}
        }
        if(relation<0)continue;
        std::int64_t borrow=0;
        for(int i=0;i<17;++i){
            std::int64_t cur=static_cast<std::int64_t>(i<17?acc[i]:0)-shifted[i]-borrow;
            if(cur<0){cur+=4294967296LL;borrow=1;}else borrow=0;
            acc[i]=static_cast<std::uint32_t>(cur);
        }
        if(borrow)throw std::logic_error("P-256 reduction overflow");
    }
    U256 r;std::memcpy(r.v,acc,sizeof r.v);return r;
}
U256 mul_mod(const U256& a,const U256& b,bool field) {
    std::uint32_t wide[16];mul_raw(wide,a.v,b.v);
    return mod_m(wide,field?P:N);
}
U256 add_mod(const U256& a,const U256& b,const std::uint32_t* m,bool field) {
    U256 r;std::uint32_t carry=add_raw(r.v,a.v,b.v);
    if(carry||cmp(r.v,m)>=0){
        if(field){std::uint32_t borrow=sub_raw(r.v,r.v,m);(void)borrow;}
        else {std::uint32_t t[8];sub_raw(t,r.v,m);if(carry){/* r = a+b-m, carry means a+b >= 2^256 > m, so no further borrow */}std::memcpy(r.v,t,sizeof t);}
    }
    return r;
}
U256 sub_mod(const U256& a,const U256& b,const std::uint32_t* m) {
    U256 r;if(sub_raw(r.v,a.v,b.v)){U256 s;add_raw(s.v,r.v,m);return s;}return r;
}
void half_mod(U256& x,const std::uint32_t* m) {
    if(x.v[0]&1u){std::uint32_t carry=add_raw(x.v,x.v,m);shr1(x,carry);}else shr1(x,0);
}
U256 inv_mod(U256 a,const std::uint32_t* m) {
    if(zero(a))throw std::invalid_argument("P-256 inverse of zero");
    U256 u=a,v=load_words(m),x1{},x2{};x1.v[0]=1;
    while(!one(u)&&!one(v)){
        while((u.v[0]&1u)==0){shr1(u,0);half_mod(x1,m);}
        while((v.v[0]&1u)==0){shr1(v,0);half_mod(x2,m);}
        if(cmp(u.v,v.v)>=0){sub_raw(u.v,u.v,v.v);x1=sub_mod(x1,x2,m);}
        else {sub_raw(v.v,v.v,u.v);x2=sub_mod(x2,x1,m);}
    }
    return one(u)?x1:x2;
}

struct Point {U256 x,y;bool inf=true;};
Point affine(const U256& x,const U256& y){Point p;p.x=x;p.y=y;p.inf=false;return p;}
Point generator(){return affine(from_be(GX),from_be(GY));}
Point double_point_real(const Point& a) {
    if(a.inf||zero(a.y))return {};
    U256 three{};three.v[0]=3;
    U256 x2=mul_mod(a.x,a.x,true);
    U256 three_x2=mul_mod(three,x2,true);
    U256 slope_num=sub_mod(three_x2,three,P); // 3x^2 - 3
    U256 lam=mul_mod(slope_num,inv_mod(add_mod(a.y,a.y,P,true),P),true);
    U256 two_x=add_mod(a.x,a.x,P,true);
    U256 x=sub_mod(mul_mod(lam,lam,true),two_x,P);
    U256 y=sub_mod(mul_mod(lam,sub_mod(a.x,x,P),true),a.y,P);
    return affine(x,y);
}
Point add_points(const Point& a,const Point& b) {
    if(a.inf)return b;if(b.inf)return a;
    if(cmp(a.x.v,b.x.v)==0){
        if(zero(add_mod(a.y,b.y,P,true)))return {};
        return double_point_real(a);
    }
    U256 lam=mul_mod(sub_mod(b.y,a.y,P),inv_mod(sub_mod(b.x,a.x,P),P),true);
    U256 x=sub_mod(sub_mod(mul_mod(lam,lam,true),a.x,P),b.x,P);
    U256 y=sub_mod(mul_mod(lam,sub_mod(a.x,x,P),true),a.y,P);
    return affine(x,y);
}
Point scalar_mul(Point p,U256 k) {
    Point r{};
    for(int i=255;i>=0;--i){
        r=double_point_real(r);
        int limb=i/32,bit=i%32;
        if((k.v[limb]>>bit)&1u)r=add_points(r,p);
    }
    return r;
}
bool on_curve(const Point& p) {
    if(p.inf)return false;
    U256 y2=mul_mod(p.y,p.y,true);
    U256 x2=mul_mod(p.x,p.x,true);
    U256 x3=mul_mod(x2,p.x,true);
    U256 three{};three.v[0]=3;
    U256 right=add_mod(sub_mod(x3,mul_mod(three,p.x,true),P),load_words(B),P,true);
    return cmp(y2.v,right.v)==0;
}
void sha256_block(std::uint32_t s[8],const std::uint8_t block[64]) {
    static constexpr std::uint32_t K[64]={
        0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
    std::uint32_t w[64];
    for(int i=0;i<16;++i)w[i]=(std::uint32_t)block[i*4]<<24|(std::uint32_t)block[i*4+1]<<16|(std::uint32_t)block[i*4+2]<<8|block[i*4+3];
    for(int i=16;i<64;++i){
        auto rotr=[](std::uint32_t x,int n){return (x>>n)|(x<<(32-n));};
        std::uint32_t s0=rotr(w[i-15],7)^rotr(w[i-15],18)^(w[i-15]>>3);
        std::uint32_t s1=rotr(w[i-2],17)^rotr(w[i-2],19)^(w[i-2]>>10);
        w[i]=w[i-16]+s0+w[i-7]+s1;
    }
    std::uint32_t a=s[0],b=s[1],c=s[2],d=s[3],e=s[4],f=s[5],g=s[6],h=s[7];
    for(int i=0;i<64;++i){
        auto rotr=[](std::uint32_t x,int n){return (x>>n)|(x<<(32-n));};
        std::uint32_t S1=rotr(e,6)^rotr(e,11)^rotr(e,25);
        std::uint32_t ch=(e&f)^(~e&g);
        std::uint32_t t1=h+S1+ch+K[i]+w[i];
        std::uint32_t S0=rotr(a,2)^rotr(a,13)^rotr(a,22);
        std::uint32_t maj=(a&b)^(a&c)^(b&c);
        std::uint32_t t2=S0+maj;
        h=g;g=f;f=e;e=d+t1;d=c;c=b;b=a;a=t1+t2;
    }
    s[0]+=a;s[1]+=b;s[2]+=c;s[3]+=d;s[4]+=e;s[5]+=f;s[6]+=g;s[7]+=h;
}

std::vector<std::uint8_t> der_integer(const std::uint8_t be[32]) {
    int start=0;while(start<31&&be[start]==0)++start;
    std::vector<std::uint8_t> out;
    if(be[start]&0x80)out.push_back(0);
    out.insert(out.end(),be+start,be+32);
    return out;
}
void append_len(std::vector<std::uint8_t>& out,std::size_t len) {
    if(len<128)out.push_back(static_cast<std::uint8_t>(len));
    else if(len<256){out.push_back(0x81);out.push_back(static_cast<std::uint8_t>(len));}
    else {out.push_back(0x82);out.push_back(static_cast<std::uint8_t>(len>>8));out.push_back(static_cast<std::uint8_t>(len));}
}
bool read_len(const std::uint8_t*& p,const std::uint8_t* end,std::size_t& len) {
    if(p>=end)return false;std::uint8_t first=*p++;
    if((first&0x80)==0){len=first;return true;}
    int bytes=first&0x7f;if(bytes==0||bytes>2||p+bytes>end)return false;
    len=0;for(int i=0;i<bytes;++i)len=(len<<8)|*p++;
    return true;
}
bool parse_integer(const std::uint8_t*& p,const std::uint8_t* end,U256& out) {
    if(p>=end||*p++!=0x02)return false;std::size_t len=0;if(!read_len(p,end,len)||len==0||p+len>end)return false;
    if(len>33||(len>1&&p[0]==0&&(p[1]&0x80)==0))return false;
    const std::uint8_t* body=p;std::size_t n=len;p+=len;
    if(n==33){if(body[0]!=0)return false;++body;--n;}
    std::uint8_t be[32]{};std::memcpy(be+(32-n),body,n);out=from_be(be);
    return cmp(out.v,N)<0;
}
std::string b64(const std::uint8_t* data,std::size_t n) {
    static constexpr char A[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;out.reserve((n+2)/3*4+n/48);
    std::size_t col=0;
    for(std::size_t i=0;i<n;i+=3){
        unsigned v=data[i]<<16;if(i+1<n)v|=data[i+1]<<8;if(i+2<n)v|=data[i+2];
        out.push_back(A[(v>>18)&63]);out.push_back(A[(v>>12)&63]);
        out.push_back(i+1<n?A[(v>>6)&63]:'=');out.push_back(i+2<n?A[v&63]:'=');
        col+=4;if(col==64){out.push_back('\n');col=0;}
    }
    if(col)out.push_back('\n');
    return out;
}
int b64_value(char c) {
    if(c>='A'&&c<='Z')return c-'A';if(c>='a'&&c<='z')return c-'a'+26;
    if(c>='0'&&c<='9')return c-'0'+52;if(c=='+')return 62;if(c=='/')return 63;return -1;
}
bool b64_decode(std::string_view text,std::vector<std::uint8_t>& out) {
    int val=0,bits=0;
    for(char c:text){
        if(c=='='||c=='\n'||c=='\r'||c==' '||c=='\t')continue;
        int d=b64_value(c);if(d<0)return false;val=(val<<6)|d;bits+=6;
        if(bits>=8){bits-=8;out.push_back(static_cast<std::uint8_t>((val>>bits)&0xff));}
    }
    return true;
}
bool parse_spki(const std::uint8_t* der,std::size_t n,p256_point& key) {
    const std::uint8_t* p=der;const std::uint8_t* end=der+n;
    if(p>=end||*p++!=0x30)return false;std::size_t len=0;if(!read_len(p,end,len)||p+len!=end)return false;
    if(p>=end||*p++!=0x30)return false;std::size_t alen=0;if(!read_len(p,end,alen)||p+alen>end)return false;
    const std::uint8_t* alg_end=p+alen;
    static constexpr std::uint8_t ec[]={0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01};
    static constexpr std::uint8_t p256[]={0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07};
    if(static_cast<std::size_t>(alg_end-p)<sizeof ec+sizeof p256)return false;
    if(std::memcmp(p,ec,sizeof ec)||std::memcmp(p+sizeof ec,p256,sizeof p256))return false;
    p=alg_end;if(p>=end||*p++!=0x03)return false;std::size_t blen=0;if(!read_len(p,end,blen)||p+blen!=end||blen!=66||p[0]!=0||p[1]!=0x04)return false;
    std::memcpy(key.x,p+2,32);std::memcpy(key.y,p+34,32);
    Point point=affine(from_be(key.x),from_be(key.y));
    return on_curve(point);
}
bool parse_sec1(const std::uint8_t* der,std::size_t n,p256_keypair& key) {
    const std::uint8_t* p=der;const std::uint8_t* end=der+n;
    if(p>=end||*p++!=0x30)return false;std::size_t len=0;if(!read_len(p,end,len)||p+len!=end)return false;
    if(p+3>end||p[0]!=0x02||p[1]!=0x01||p[2]!=0x01)return false;p+=3;
    if(p>=end||*p++!=0x04)return false;std::size_t dlen=0;if(!read_len(p,end,dlen)||dlen!=32||p+dlen>end)return false;
    std::memcpy(key.d,p,32);p+=32;
    U256 scalar=from_be(key.d);if(zero(scalar)||cmp(scalar.v,N)>=0)return false;
    if(p<end&&*p==0xa0){
        ++p;std::size_t plen=0;if(!read_len(p,end,plen)||p+plen>end)return false;
        static constexpr std::uint8_t oid[]={0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07};
        if(plen!=sizeof oid||std::memcmp(p,oid,sizeof oid))return false;p+=plen;
    }
    Point pub=scalar_mul(generator(),scalar);if(pub.inf||!on_curve(pub))return false;
    to_be(pub.x,key.public_key.x);to_be(pub.y,key.public_key.y);
    if(p<end&&*p==0xa1){
        ++p;std::size_t plen=0;if(!read_len(p,end,plen)||p+plen!=end||plen<2||p[0]!=0x03)return false;
        const std::uint8_t* b=p;std::size_t blen=0;if(!read_len(++b,p+plen,blen))return false;
        if(blen!=66||b[0]!=0||b[1]!=0x04)return false;
        if(std::memcmp(b+2,key.public_key.x,32)||std::memcmp(b+34,key.public_key.y,32))return false;
    } else if(p!=end) return false;
    return true;
}
std::string armor(const char* kind,const std::vector<std::uint8_t>& der) {
    return std::string("-----BEGIN ")+kind+"-----\n"+b64(der.data(),der.size())+"-----END "+kind+"-----\n";
}
bool random_bytes(std::uint8_t* out,std::size_t n) {
#if defined(_WIN32)
    return BCryptGenRandom(nullptr,out,static_cast<ULONG>(n),BCRYPT_USE_SYSTEM_PREFERRED_RNG)==0;
#else
    std::ifstream in("/dev/urandom",std::ios::binary);if(!in)return false;in.read(reinterpret_cast<char*>(out),static_cast<std::streamsize>(n));return static_cast<std::size_t>(in.gcount())==n;
#endif
}
void mul_words(std::uint32_t r[16],const U256& a,const U256& b){mul_raw(r,a.v,b.v);}
} // namespace

void p256_sha256(const std::uint8_t* data,std::size_t length,std::uint8_t out[32]) {
    std::uint32_t s[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    std::uint8_t block[64];std::size_t offset=0;std::uint64_t bits=static_cast<std::uint64_t>(length)*8;
    while(length-offset>=64){sha256_block(s,data+offset);offset+=64;}
    std::size_t rem=length-offset;std::memset(block,0,64);
    if(rem)std::memcpy(block,data+offset,rem);
    block[rem]=0x80;
    if(rem>=56){sha256_block(s,block);std::memset(block,0,64);}
    for(int i=0;i<8;++i)block[63-i]=static_cast<std::uint8_t>(bits>>(8*i));
    sha256_block(s,block);
    for(int i=0;i<8;++i){out[i*4]=s[i]>>24;out[i*4+1]=s[i]>>16;out[i*4+2]=s[i]>>8;out[i*4+3]=s[i];}
}
bool p256_derive_public(const std::uint8_t d[32],p256_point& out) {
    U256 scalar=from_be(d);if(zero(scalar)||cmp(scalar.v,N)>=0)return false;
    Point pub=scalar_mul(generator(),scalar);if(pub.inf||!on_curve(pub))return false;
    to_be(pub.x,out.x);to_be(pub.y,out.y);return true;
}
bool p256_verify(const p256_point& key,const std::uint8_t hash[32],const std::uint8_t* signature,std::size_t length) {
    Point q=affine(from_be(key.x),from_be(key.y));if(!on_curve(q))return false;
    const std::uint8_t* p=signature;const std::uint8_t* end=signature+length;
    if(p>=end||*p++!=0x30)return false;std::size_t len=0;if(!read_len(p,end,len)||p+len!=end)return false;
    U256 r,s;if(!parse_integer(p,end,r)||!parse_integer(p,end,s)||p!=end)return false;
    if(zero(r)||zero(s))return false;
    std::uint32_t hashed[16]{};U256 e=from_be(hash);std::memcpy(hashed,e.v,sizeof e.v);e=mod_m(hashed,N);
    U256 w=inv_mod(s,N);
    std::uint32_t u1w[16],u2w[16];mul_words(u1w,e,w);mul_words(u2w,r,w);
    Point x=add_points(scalar_mul(generator(),mod_m(u1w,N)),scalar_mul(q,mod_m(u2w,N)));
    if(x.inf)return false;
    std::uint32_t xv[16]{};std::memcpy(xv,x.x.v,sizeof x.x.v);U256 xr=mod_m(xv,N);
    return cmp(xr.v,r.v)==0;
}
bool p256_sign(const std::uint8_t d[32],const std::uint8_t hash[32],std::vector<std::uint8_t>& der) {
    U256 secret=from_be(d);if(zero(secret)||cmp(secret.v,N)>=0)return false;
    std::uint32_t hashed[16]{};U256 e=from_be(hash);std::memcpy(hashed,e.v,sizeof e.v);e=mod_m(hashed,N);
    for(int attempt=0;attempt<16;++attempt){
        std::uint8_t kb[32];if(!random_bytes(kb,32))return false;
        U256 k=from_be(kb);if(zero(k)||cmp(k.v,N)>=0)continue;
        Point rpoint=scalar_mul(generator(),k);if(rpoint.inf)continue;
        std::uint32_t rv[16]{};std::memcpy(rv,rpoint.x.v,sizeof rpoint.x.v);U256 r=mod_m(rv,N);if(zero(r))continue;
        std::uint32_t rw[16];mul_raw(rw,r.v,secret.v);U256 rd=mod_m(rw,N);
        U256 sum=add_mod(e,rd,N,false);
        U256 s=mul_mod(inv_mod(k,N),sum,false);if(zero(s))continue;
        std::uint8_t rb[32],sb[32];to_be(r,rb);to_be(s,sb);
        auto ri=der_integer(rb),si=der_integer(sb);
        std::vector<std::uint8_t> body;body.push_back(0x02);append_len(body,ri.size());body.insert(body.end(),ri.begin(),ri.end());
        body.push_back(0x02);append_len(body,si.size());body.insert(body.end(),si.begin(),si.end());
        der.clear();der.push_back(0x30);append_len(der,body.size());der.insert(der.end(),body.begin(),body.end());
        return true;
    }
    return false;
}
bool p256_generate(p256_keypair& key) {
    for(int i=0;i<32;++i){
        if(!random_bytes(key.d,32))return false;
        if(p256_derive_public(key.d,key.public_key))return true;
    }
    return false;
}
std::string p256_public_pem(const p256_point& key) {
    std::vector<std::uint8_t> der={
        0x30,0x59,0x30,0x13,0x06,0x07,0x2a,0x86,0x48,0xce,0x3d,0x02,0x01,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07,
        0x03,0x42,0x00,0x04};
    der.insert(der.end(),key.x,key.x+32);der.insert(der.end(),key.y,key.y+32);
    return armor("PUBLIC KEY",der);
}
std::string p256_private_pem(const p256_keypair& key) {
    std::vector<std::uint8_t> der={0x30,0x00,0x02,0x01,0x01,0x04,0x20};
    der.insert(der.end(),key.d,key.d+32);
    static constexpr std::uint8_t params[]={0xa0,0x0a,0x06,0x08,0x2a,0x86,0x48,0xce,0x3d,0x03,0x01,0x07};
    der.insert(der.end(),params,params+sizeof params);
    der.push_back(0xa1);der.push_back(0x44);der.push_back(0x03);der.push_back(0x42);der.push_back(0x00);der.push_back(0x04);
    der.insert(der.end(),key.public_key.x,key.public_key.x+32);
    der.insert(der.end(),key.public_key.y,key.public_key.y+32);
    der[1]=static_cast<std::uint8_t>(der.size()-2);
    return armor("EC PRIVATE KEY",der);
}
bool p256_parse_public_pem(std::string_view pem,std::vector<p256_point>& keys,std::string& error) {
    if(pem.find("PRIVATE KEY")!=std::string_view::npos){error="Trusted key must be a public key, not a private key";return false;}
    std::size_t cursor=0;bool any=false;
    while(true){
        auto begin=pem.find("-----BEGIN PUBLIC KEY-----",cursor);if(begin==std::string_view::npos)break;
        auto end=pem.find("-----END PUBLIC KEY-----",begin);if(end==std::string_view::npos){error="Unterminated PUBLIC KEY block";return false;}
        auto body=pem.substr(begin+26,end-(begin+26));
        std::vector<std::uint8_t> der;if(!b64_decode(body,der)){error="Invalid public key PEM";return false;}
        p256_point key;if(!parse_spki(der.data(),der.size(),key)){error="Public key is not a P-256 SPKI key";return false;}
        keys.push_back(key);any=true;cursor=end+24;
    }
    if(!any){error="No PUBLIC KEY block found";return false;}
    return true;
}
bool p256_parse_private_pem(std::string_view pem,p256_keypair& key,std::string& error) {
    auto begin=pem.find("-----BEGIN EC PRIVATE KEY-----");
    auto end=pem.find("-----END EC PRIVATE KEY-----");
    if(begin==std::string_view::npos||end==std::string_view::npos||end<begin){error="Expected an EC PRIVATE KEY PEM";return false;}
    std::vector<std::uint8_t> der;if(!b64_decode(pem.substr(begin+31,end-(begin+31)),der)||!parse_sec1(der.data(),der.size(),key)){
        error="Invalid EC private key";return false;}
    return true;
}
void p256_self_test() {
    std::uint8_t empty[32];p256_sha256(nullptr,0,empty);
    static constexpr std::uint8_t empty_hash[32]={
        0xe3,0xb0,0xc4,0x42,0x98,0xfc,0x1c,0x14,0x9a,0xfb,0xf4,0xc8,0x99,0x6f,0xb9,0x24,
        0x27,0xae,0x41,0xe4,0x64,0x9b,0x93,0x4c,0xa4,0x95,0x99,0x1b,0x78,0x52,0xb8,0x55};
    if(std::memcmp(empty,empty_hash,32))throw std::logic_error("SHA-256 empty vector failed");
    std::uint32_t two256[16]{};two256[8]=1;U256 reduced=mod_m(two256,P);
    U256 expect{};expect.v[0]=1;expect.v[3]=0xffffffffu;expect.v[4]=0xffffffffu;expect.v[5]=0xffffffffu;expect.v[6]=0xfffffffeu;
    if(cmp(reduced.v,expect.v))throw std::logic_error("P-256 reduction of 2^256 failed");
    U256 two{};two.v[0]=2;U256 inv=inv_mod(two,P);U256 back=mul_mod(two,inv,true);
    if(!one(back))throw std::logic_error("P-256 field inverse failed");
    if(!on_curve(generator()))throw std::logic_error("P-256 generator is not on the curve");
    static constexpr std::uint8_t d[32]={
        0xc9,0xaf,0xa9,0xd8,0x45,0xba,0x75,0x16,0x6b,0x5c,0x21,0x57,0x67,0xb1,0xd6,0x93,
        0x4e,0x50,0xc3,0xdb,0x36,0xe8,0x9b,0x12,0x7b,0x8a,0x62,0x2b,0x12,0x0f,0x67,0x21};
    static constexpr std::uint8_t qx[32]={
        0x60,0xfe,0xd4,0xba,0x25,0x5a,0x9d,0x31,0xc9,0x61,0xeb,0x74,0xc6,0x35,0x6d,0x68,
        0xc0,0x49,0xb8,0x92,0x3b,0x61,0xfa,0x6c,0xe6,0x69,0x62,0x2e,0x60,0xf2,0x9f,0xb6};
    static constexpr std::uint8_t qy[32]={
        0x79,0x03,0xfe,0x10,0x08,0xb8,0xbc,0x99,0xa4,0x1a,0xe9,0xe9,0x56,0x28,0xbc,0x64,
        0xf2,0xf1,0xb2,0x0c,0x2d,0x7e,0x9f,0x51,0x77,0xa3,0xc2,0x94,0xd4,0x46,0x22,0x99};
    p256_point pub;if(!p256_derive_public(d,pub)||std::memcmp(pub.x,qx,32)||std::memcmp(pub.y,qy,32))
        throw std::logic_error("P-256 RFC 6979 public key mismatch");
    const char sample[]="sample";std::uint8_t hash[32];p256_sha256(reinterpret_cast<const std::uint8_t*>(sample),6,hash);
    static constexpr std::uint8_t expect_hash[32]={
        0xaf,0x2b,0xdb,0xe1,0xaa,0x9b,0x6e,0xc1,0xe2,0xad,0xe1,0xd6,0x94,0xf4,0x1f,0xc7,
        0x1a,0x83,0x1d,0x02,0x68,0xe9,0x89,0x15,0x62,0x11,0x3d,0x8a,0x62,0xad,0xd1,0xbf};
    if(std::memcmp(hash,expect_hash,32))throw std::logic_error("SHA-256 sample vector failed");
    static constexpr std::uint8_t r[32]={
        0xef,0xd4,0x8b,0x2a,0xac,0xb6,0xa8,0xfd,0x11,0x40,0xdd,0x9c,0xd4,0x5e,0x81,0xd6,
        0x9d,0x2c,0x87,0x7b,0x56,0xaa,0xf9,0x91,0xc3,0x4d,0x0e,0xa8,0x4e,0xaf,0x37,0x16};
    static constexpr std::uint8_t s[32]={
        0xf7,0xcb,0x1c,0x94,0x2d,0x65,0x7c,0x41,0xd4,0x36,0xc7,0xa1,0xb6,0xe2,0x9f,0x65,
        0xf3,0xe9,0x00,0xdb,0xb9,0xaf,0xf4,0x06,0x4d,0xc4,0xab,0x2f,0x84,0x3a,0xcd,0xa8};
    auto ri=der_integer(r),si=der_integer(s);
    std::vector<std::uint8_t> body;body.push_back(0x02);append_len(body,ri.size());body.insert(body.end(),ri.begin(),ri.end());
    body.push_back(0x02);append_len(body,si.size());body.insert(body.end(),si.begin(),si.end());
    std::vector<std::uint8_t> der;der.push_back(0x30);append_len(der,body.size());der.insert(der.end(),body.begin(),body.end());
    if(!p256_verify(pub,hash,der.data(),der.size()))throw std::logic_error("P-256 RFC 6979 signature rejected");
    der.back()^=1;if(p256_verify(pub,hash,der.data(),der.size()))throw std::logic_error("Tampered P-256 signature accepted");
    auto pem=p256_public_pem(pub);std::vector<p256_point> parsed;std::string error;
    if(!p256_parse_public_pem(pem,parsed,error)||parsed.size()!=1||std::memcmp(parsed[0].x,qx,32))
        throw std::logic_error(std::string("P-256 public PEM roundtrip failed: ")+error);
    if(p256_parse_public_pem("-----BEGIN EC PRIVATE KEY-----\nMHcCAQEE\n-----END EC PRIVATE KEY-----\n",parsed,error))
        throw std::logic_error("Private PEM was accepted as a trusted key");
    p256_keypair fresh;if(!p256_generate(fresh))throw std::logic_error("P-256 key generation failed");
    std::vector<std::uint8_t> mine;if(!p256_sign(fresh.d,hash,mine)||!p256_verify(fresh.public_key,hash,mine.data(),mine.size()))
        throw std::logic_error("P-256 sign/verify roundtrip failed");
    if(!p256_parse_private_pem(p256_private_pem(fresh),fresh,error))
        throw std::logic_error(std::string("P-256 private PEM roundtrip failed: ")+error);
}
}
