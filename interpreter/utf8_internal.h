#ifndef AZSCRIPT_UTF8_INTERNAL_H
#define AZSCRIPT_UTF8_INTERNAL_H
// Runtime implementation detail; this header is deliberately not installed.
#include <cstddef>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>

namespace azertian::utf8_internal {
struct scalar {std::uint32_t code;std::size_t width;};
inline scalar decode(std::string_view text,std::size_t offset) {
    auto invalid=[]() -> scalar {throw std::invalid_argument("Invalid UTF-8 string");};
    if(offset>=text.size())return invalid();
    const auto first=static_cast<unsigned char>(text[offset]);
    scalar result{first,1};
    if(first<0x80)return result;
    if(first>=0xc2&&first<=0xdf)result={static_cast<std::uint32_t>(first&0x1fu),2};
    else if(first>=0xe0&&first<=0xef)result={static_cast<std::uint32_t>(first&0x0fu),3};
    else if(first>=0xf0&&first<=0xf4)result={static_cast<std::uint32_t>(first&7u),4};
    else return invalid();
    if(result.width>text.size()-offset)return invalid();
    for(std::size_t i=1;i<result.width;++i) {
        const auto next=static_cast<unsigned char>(text[offset+i]);
        if((next&0xc0u)!=0x80u)return invalid();
        result.code=(result.code<<6)|(next&0x3fu);
    }
    if((result.width==2&&result.code<0x80)||(result.width==3&&result.code<0x800)||
       (result.width==4&&result.code<0x10000)||result.code>0x10ffff||
       (result.code>=0xd800&&result.code<=0xdfff))return invalid();
    return result;
}
inline std::size_t count(std::string_view text) {
    std::size_t length=0;
    for(std::size_t offset=0;offset<text.size();++length)offset+=decode(text,offset).width;
    return length;
}
class view {
    std::string_view text_;
    std::size_t length_;
public:
    explicit view(std::string_view text):text_(text),length_(count(text)) {}
    std::string_view bytes() const noexcept {return text_;}
    std::size_t length() const noexcept {return length_;}
    std::size_t byte_offset(std::size_t index) const {
        if(index>length_)throw std::out_of_range("String index "+std::to_string(index)+" is outside length "+std::to_string(length_));
        std::size_t offset=0;
        for(std::size_t i=0;i<index;++i)offset+=decode(text_,offset).width;
        return offset;
    }
};
inline std::string encode(int value) {
    if(value<0||value>0x10ffff||(value>=0xd800&&value<=0xdfff))
        throw std::invalid_argument("Invalid Unicode code point: "+std::to_string(value));
    const auto code=static_cast<std::uint32_t>(value);std::string result;
    if(code<0x80)result+=static_cast<char>(code);
    else if(code<0x800) {
        result+=static_cast<char>(0xc0u|(code>>6));result+=static_cast<char>(0x80u|(code&0x3fu));
    } else if(code<0x10000) {
        result+=static_cast<char>(0xe0u|(code>>12));result+=static_cast<char>(0x80u|((code>>6)&0x3fu));result+=static_cast<char>(0x80u|(code&0x3fu));
    } else {
        result+=static_cast<char>(0xf0u|(code>>18));result+=static_cast<char>(0x80u|((code>>12)&0x3fu));
        result+=static_cast<char>(0x80u|((code>>6)&0x3fu));result+=static_cast<char>(0x80u|(code&0x3fu));
    }
    return result;
}
}
#endif
