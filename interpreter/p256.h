#ifndef AZSCRIPT_P256_H
#define AZSCRIPT_P256_H
#include <cstddef>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>
namespace azertian {
struct p256_point {
    std::uint8_t x[32]{};
    std::uint8_t y[32]{};
};
struct p256_keypair {
    std::uint8_t d[32]{};
    p256_point public_key;
};
void p256_sha256(const std::uint8_t* data,std::size_t length,std::uint8_t out[32]);
bool p256_derive_public(const std::uint8_t d[32],p256_point& out);
bool p256_verify(const p256_point& key,const std::uint8_t hash[32],const std::uint8_t* signature,std::size_t length);
bool p256_sign(const std::uint8_t d[32],const std::uint8_t hash[32],std::vector<std::uint8_t>& der);
bool p256_generate(p256_keypair& key);
std::string p256_public_pem(const p256_point& key);
std::string p256_private_pem(const p256_keypair& key);
// Parses every SubjectPublicKeyInfo block. Private-key armor is rejected.
bool p256_parse_public_pem(std::string_view pem,std::vector<p256_point>& keys,std::string& error);
bool p256_parse_private_pem(std::string_view pem,p256_keypair& key,std::string& error);
void p256_self_test();
}
#endif
