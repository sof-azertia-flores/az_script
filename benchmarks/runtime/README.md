# Runtime performance evidence

This opt-in benchmark implements the R01 measurement infrastructure. It does not
replace correctness tests, sanitizer checks, JNI tests or distribution validation.
No runtime speedup is implied by adding the harness.

`manifest.json` freezes four groups at 25% each: scalar loops, ordinary calls,
objects and virtual calls. Cases are equally weighted inside each group; medium
and large sizes are equally weighted inside each case. Small cases, startup,
load, flush and destruction are reported separately. `generate.py` supplies the
same deterministic source for all runtimes and independently computes expected
integer results. Ordinary calls cover 0, 1, 4 and 16 arguments. Object cases cover
construction, fields, ordinary methods and independent copies; virtual cases
cover one receiver type and alternating derived receiver types.

The primary adoption comparison uses a frozen **heap-growth-only** reference.
The original reference is reported separately so R02's allocation improvement
is not credited twice. A reference role is an explicit provenance declaration,
not something the benchmark can infer from machine code. Preserve the reference
source revision, patch and build artifacts together.

## Build identical hosts

The ordinary test build is Debug and is unsuitable for performance conclusions.
Use a dedicated Release directory with compile commands recorded:

```sh
cmake -S . -B build/runtime-perf/release \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
  -DAZSCRIPT_BUILD_JNI=ON -DBUILD_TESTING=ON
cmake --build build/runtime-perf/release --target runtime-benchmark-host --parallel 4
```

For a frozen source archive that predates the benchmark, build the **same current
host source** against that runtime. This command creates an external CMake
wrapper; it does not modify the archived source or run the full validation suite:

```sh
python3 tools/bench_runtime.py \
  --prepare-host build/runtime-perf/baseline-source \
  --out build/runtime-perf/baseline-host --jobs 4
```

For the fairest build comparison, use `--prepare-host` for both source trees with
the same compiler, CMake, flags and environment. Both runtimes are shared builds;
their separate processes resolve their own libraries. The harness records the
native compiler, build type, NDEBUG, CMake cache, compile commands and library
digests. Check these records before interpreting comparisons; different custom
flags, CPU quotas or library search paths can invalidate a comparison. Missing
CMake build metadata, mismatched recorded compiler/optimization/link flags or
sanitizers disable the aggregate score. Target-specific compile commands remain
available for manual review.

## Measure

After a normal build has generated `build/java/classpath.txt`, pass its contents
as `--classpath`. For example, in a POSIX shell:

```sh
python3 tools/bench_runtime.py \
  --classpath "$(cat build/java/classpath.txt)" \
  --host build/runtime-perf/release/interpreter/runtime-benchmark-host \
  --baseline-host build/runtime-perf/baseline-host/native/runtime-benchmark-host \
  --baseline-source build/runtime-perf/baseline-source \
  --baseline-revision BASELINE_COMMIT_SHA \
  --reference-role original --out build/runtime-perf/R01-original
```

Use `--reference-role heap-growth-only` and its separate frozen source/host for
the adoption comparison. The initial `frozen.json` calibrates calls per batch
**only on the reference**, targeting 250 ms; no candidate measurements occur
until the complete calibration and input identities have been written. Each
measurement then uses exactly the same call count and execution budgets on both
hosts. Each pair uses fresh processes, with reference/candidate order alternating
between rounds. A host loads once, flushes once, records the first call, warms up,
then measures five batches. Seven independent rounds are the default.

Reuse the reference's calibration with `--calibration /path/to/frozen.json` for
later candidate runs. Input identity, harness identity, reference executable,
shared libraries, build metadata and settings must still match. Different
reference builds/roles need distinct calibrations. Select a new `--out` for each
run; existing observations are never overwritten. Without `--baseline-host` the
script records a single-host baseline and does not compute a speedup.

For development only, `--cases integer_loop --sizes small --rounds 1 --batches 1
--target-seconds 0.01` is a quick correctness smoke run. Subsets, short runs and
`--allow-debug` cannot earn an aggregate performance score. `--runner` is an alias
for the benchmark `--host`, not the ordinary `azscript-run` CLI.

## Migration VM backend

`runtime-benchmark-vm-host` is the same harness compiled with
`AZSCRIPT_BENCHMARK_VM`: it selects the experimental register VM with the
default 128 MiB workspace before the first flush. Every host reports its
`backend` (`tree` or `vm`). The VM still refuses object, generic and virtual
programs while preparing, so only the scalar and call groups run on it; pair it
with `runtime-benchmark-host` from the same Release build and select
`--cases integer_loop,branch_loop,call_0,call_1,call_4,call_16`. A subset never
earns the core score, and such a comparison isolates the backend change, not
the cumulative change against a frozen reference.

## Timing boundaries and artifacts

The native host records file read, load, flush, first invoke, warmup, each steady
batch and destruction separately, with wall and process CPU time. Python also
records the entire subprocess elapsed time. `cold_to_first_invoke` measures from
native `main` entry through completion of the first call; it excludes OS process
creation and dynamic-loader startup. The entire subprocess elapsed time includes process startup,
all batches, validation, JSON output and exit; it must not be described as pure
loader latency. Peak RSS is the process high-water mark, including native
runtime and host buffers, not live VM memory; `-1` means unavailable.

Steady timing includes the public C++ `script::invoke` boundary, copying the
one-argument parameter vector, checking the return tag and storing one integer
per call into a preallocated buffer. Result validation and the FNV-1a checksum
run after the timer stops. It does not measure only opcode dispatch. Workload
generation, compilation and timed-region output are excluded. The integer
results prevent accepting fast-but-wrong execution. Allocation/profiler probes
are intentionally separate future measurements; this harness does not claim
to have measured allocations merely from RSS.

Outputs include:

- `metadata.json`: tool versions, machine/cgroup settings, source provenance,
  suite/harness hashes, build flags and binary/library hashes.
- `compilation.json`, `inputs/`: compiler commands/results and the exact source
  and executable bytes; all runtimes receive the same executable.
- `frozen.json`, `workload.json`: reference-only calibration, case sizes,
  independent expected results, budgets, input hashes and frozen call counts.
- `observations.jsonl`: every raw command, stdout/stderr, exit code, timeout,
  phase measurement and checksum, flushed after each subprocess.
- `summary.json`: per-run medians, median absolute deviation, ranges, paired
  ratios and (only for a complete eligible run) group/overall geometric means.

Failures and timeouts remain in the raw data and make the run fail. They are not
discarded as timing outliers. A failed/incomplete, Debug or smoke run has no core
score. `core_speedup` is descriptive evidence, not automatic passage of the
roadmap's correctness, regression, memory and distribution gates.
