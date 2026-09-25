# Git 版本管理

本目录是一个完整的 Git 仓库，默认分支为 `main`。编译器、ABD 数据层、解释器、JNI、数学库、测试和文档一起提交，不使用子模块。首次提交保存当前实现，标签 `baseline-2026-09-25` 标记这份起点；它是代码基线，不是发行版本号。Git 历史从这份基线开始，更早的修改过程见 `VALIDATION.md`。

## 跟踪范围

源码、构建与发行脚本、语法示例、测试、文档、Gradle wrapper 和专用二进制测试样本纳入版本管理。`build/`、`dist/`、各模块构建目录、IDE 配置、缓存、编译后的库与 JAR、编译生成的 ABD 和两份 JSON 由根 `.gitignore` 排除。`abdC/tests/cpp-fixture.abd` 是有意保留的测试样本。

旧的 `interpreter/.gitmodules`、`abdjni/.gitmodules` 及本地旧二进制仍可留在磁盘上，但不进入仓库；当前构建直接使用同仓库内的模块源码。`original-sha256.json` 和原始文件校验工具作为历史记录保留，它们指向迁移前的目录，不替代 Git 的版本管理。

发行包可以重新生成，通常不提交到源码仓库。克隆后首次构建若缺少依赖缓存，运行 `python3 tools/build_and_test.py` 下载固定版本并校验摘要，随后可以使用离线模式。`.gitattributes` 保持文本换行一致，并把 ABD 样本和 wrapper JAR 标记为二进制。

## 日常修改

从 `main` 创建功能分支，例如：

```sh
git switch main
git switch -c feat/math-extension
```

修改后查看差异并验证。普通代码修改运行完整离线测试；涉及内存、析构或解释器清理路径时，再运行 sanitizer 检查：

```sh
git status --short
git diff
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

按实际修改的路径暂存，每次提交处理一件完整的事情。下面以数学库和说明文档为例：

```sh
git add compiler/stdlib/math.azs compiler/stdlib/math.include.azs compiler/stdlib/MATH.md
git diff --cached
git diff --cached --check
git commit -m "feat: extend math library"
```

验证通过后合回主分支：

```sh
git switch main
git merge --ff-only feat/math-extension
```

如果 `main` 已有其他提交，先在功能分支合入 `main` 并解决冲突，再验证和合并。用 `git log --oneline --decorate --graph --all` 查看历史；撤销已提交的修改用 `git revert <commit>` 生成可追溯的反向提交。

## 发行与远程备份

在通过测试的提交上建立发行标签，然后导出本机平台的发行包：

```sh
git tag -a vX.Y.Z -m "AzScript X.Y.Z"
python3 tools/export_distribution.py dist/azscript --offline
```

将 `X.Y.Z` 替换为实际发行版本，并保持与构建配置中的版本一致。已有发行目录的更新按导出工具规则使用 `--force`。发行包和对应标签应一起记录，方便定位其源码。

远程 `origin` 使用 `git@github-new:sof-azertia-flores/az_script.git`。`github-new` 是本机 SSH 主机别名；其他机器需要配置同名别名，或改用其可用的 GitHub SSH 地址。提交完成后推送主分支和标签：

```sh
git push -u origin main
git push origin --tags
```

本地提交保存在 `.git/` 内，只有推送到远程或备份整个仓库后，才能覆盖本机磁盘损坏的情况。
