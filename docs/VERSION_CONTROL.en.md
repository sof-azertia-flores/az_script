# Git Version Control

[中文](VERSION_CONTROL.md) | English

This directory is a complete Git repository with `main` as its default branch. The compiler, ABD data layers, interpreter, JNI, math library, tests, and documentation are committed together without submodules. The first commit preserves the implementation at that point; `baseline-2026-09-25` marks that starting point, not a release version. Git history starts at this baseline; earlier changes are recorded in `VALIDATION.md` (English: [VALIDATION.en.md](../VALIDATION.en.md)).

## Tracked files

Track source, build and export scripts, language examples, tests, documentation, Gradle wrappers, and dedicated binary fixtures. The root `.gitignore` excludes `build/`, `dist/`, module build directories, IDE settings, caches, compiled libraries and JARs, generated ABD files, and their two JSON outputs. `abdC/tests/cpp-fixture.abd` is intentionally retained.

Historical `interpreter/.gitmodules`, `abdjni/.gitmodules`, and old local binaries may remain on disk but are not part of the repository. Current builds use module sources in this repository. `original-sha256.json` and the original-file checker remain historical records pointing at pre-migration directories; they do not replace Git.

Distributions are reproducible and normally are not committed to the source repository. After cloning, run `python3 tools/build_and_test.py` if dependencies are uncached; it downloads pinned versions and verifies digests. Subsequent builds can run offline. `.gitattributes` normalizes text line endings and marks ABD fixtures and wrapper JARs as binary.

## Everyday changes

Create a feature branch from `main`, for example:

```sh
git switch main
git switch -c feat/math-extension
```

Inspect changes and validate them. Ordinary code changes require the complete offline suite; memory, destructor, or interpreter cleanup changes also require sanitizer checks:

```sh
git status --short
git diff
python3 tools/build_and_test.py --offline
python3 tools/build_and_test.py --offline --sanitize
```

Stage actual changed paths, keeping each commit focused on one complete change. For example, for the math library and its documentation:

```sh
git add compiler/stdlib/math.azs compiler/stdlib/math.include.azs compiler/stdlib/MATH.md
git diff --cached
git diff --cached --check
git commit -m "feat: extend math library"
```

After validation, merge into the main branch:

```sh
git switch main
git merge --ff-only feat/math-extension
```

If `main` has advanced, merge it into the feature branch, resolve conflicts, and validate before merging back. Inspect history with `git log --oneline --decorate --graph --all`. Undo committed changes with `git revert <commit>` to create a traceable inverse commit.

## Releases and remote backup

Tag a tested commit and export the distribution for the current platform:

```sh
git tag -a vX.Y.Z -m "AzScript X.Y.Z"
python3 tools/export_distribution.py dist/azscript --offline
```

Replace `X.Y.Z` with the actual release version, consistent with build configuration. Update an existing distribution with `--force` according to exporter rules. Record the distribution and its tag together so its source can be identified.

The `origin` remote is `git@github-new:sof-azertia-flores/az_script.git`. `github-new` is a local SSH host alias. Other machines must configure the same alias or use an available GitHub SSH address. After committing, push the main branch and tags:

```sh
git push -u origin main
git push origin --tags
```

Local commits live in `.git/`. Protection against local disk failure requires pushing to a remote or backing up the whole repository.
