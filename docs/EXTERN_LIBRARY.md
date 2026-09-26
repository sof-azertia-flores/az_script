# 外部动态库

`load_extern_library(name)` 是内置函数，签名为 `void(string)`，编号 `0x0abd0007`。它按当前操作系统打开一个已经签名的动态库，并调用其中的 `azscript_load_extern`。动态库里用 C++ 宿主原本的注册接口挂上外部函数，不另设一套插件 ABI，也不把库当成 hint 模块装进脚本。

```c
extern int add(int, int):0x12340001;
int main() {
    load_extern_library("demo");
    return add(20, 22);
}
```

名字可以带目录。`plugins/demo` 只在该目录查找。不带目录时依次查找当前目录、`AZSCRIPT_EXTERN_PATH`（Unix 用 `:`，Windows 用 `;`）和当前进程可执行文件所在目录。若最后一段以 `.so`、`.dylib` 或 `.dll` 结尾，会先去掉再按本机补上：

| 系统 | 动态库 | 签名 |
| --- | --- | --- |
| macOS | `demo.dylib` | `demo.signature` |
| Windows | `demo.dll` | `demo.signature` |
| 其他 | `demo.so` | `demo.signature` |

运行时先读入整个动态库（上限 64MiB），用已配置的公钥验签，再映射这份已校验的字节。同一规范化路径再次加载直接返回。库不会被卸载，这样已经注册的函数对象保持有效。

`azscript_load_extern` 返回前必须调用 `registerExecutor`。注册进入的必须是宿主进程里的那一份运行时；链到另一份副本时，调用会失败。命名空间规则与手写宿主相同：不能占用 `0`、`0xfff`、`0xabd`，也不能占用活动脚本已经挂载的 namespace。

脚本侧的 `extern` 声明仍然负责参数和返回类型。动态库只提供对应编号的实现。`load_extern_library` 发生在脚本调用期间，因此它不会 `insert_script`，也不会 `flush`。

## 公钥

只接受 PEM `BEGIN PUBLIC KEY`（SubjectPublicKeyInfo），曲线为 P-256。私钥会被拒绝。`xxx.signature` 是对动态库文件字节做 SHA-256 之后的 ECDSA-P256 签名，DER 编码。

嵌入式宿主自己把公钥交进来：

```cpp
azertian::add_trusted_public_key_pem(pemText);
azertian::clear_trusted_public_keys();
```

```java
AbdInvoker.addTrustedPublicKey(pemText);
AbdInvoker.addTrustedPublicKey(new File("trusted_key.pem"));
AbdInvoker.clearTrustedPublicKeys();
```

这两条路径不会读取环境变量。独立解释器 `azscript-run` 在启动时合并：

- `AZSCRIPT_TRUSTED_KEY`：PEM 正文，或指向 PEM 文件的路径。
- `trusted_key.pem`：当前目录、可执行文件目录，以及共享运行库目录。

任一把公钥能验过即可。都没有时，`load_extern_library` 失败。`trusted_key.pem` 里放公钥，不要放私钥。

## 编写动态库

插件只包含 `azscript/extern_library.hpp`。这个头再包含公开的 `runtime.hpp`，因此可以直接使用 `executor`、`function`、`variable`、`registerExecutor` 和 `unregisterExecutor`。

```cpp
#include <azscript/extern_library.hpp>

class Add final : public azertian::function {
public:
    int return_type() override { return azertian::INT_VALUE; }
    std::shared_ptr<azertian::variable> invoke(
        std::shared_ptr<azertian::environment>,
        std::vector<std::shared_ptr<azertian::variable>> args) override {
        int value = *static_cast<int*>(args.at(0)->value) + *static_cast<int*>(args.at(1)->value);
        return std::make_shared<azertian::variable>(value);
    }
};

class Plugin final : public azertian::executor {
public:
    int namespace_name() override { return 0x1234; }
    std::shared_ptr<azertian::function> getiFunction(int id) override {
        if (id == 0x12340001) return std::make_shared<Add>();
        return nullptr;
    }
};

AZSCRIPT_EXTERN_ENTRY {
    azertian::registerExecutor(std::make_shared<Plugin>());
}
```

库必须链接共享运行库 `abdInvoker`（CMake 目标 `AzScript::RuntimeShared`）。静态库 `AzScript::Runtime` 有自己的一份注册表，插件里的 `registerExecutor` 不会进入那个副本。Java JNI 已经链接这份共享库。

发行包根目录：

```sh
./compile_extern_lib.sh -o demo demo.cpp
./sign_extern_lib.sh --generate-key private.pem trusted_key.pem
./sign_extern_lib.sh private.pem demo.so
```

Windows 使用 `compile_extern_lib.cmd` 和 `sign_extern_lib.cmd`。签名程序是 `bin/azscript-sign-extern`，shell 脚本只负责找到它。`compile_extern_lib.sh` 调用系统 C++ 编译器，包含 `include/`，并链接 `lib/` 中的共享运行库。

开发树里可以设置 `AZSCRIPT_INCLUDE`、`AZSCRIPT_LIBDIR` 和 `AZSCRIPT_SIGN_EXTERN`。未设置时，脚本会尝试发行包布局，再尝试仓库的 `build/native` 或 `build/sanitize`。
