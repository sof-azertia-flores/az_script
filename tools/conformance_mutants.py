#!/usr/bin/env python3
"""Negative controls for the ownership conformance corpus.

Each mutant is the committed runtime with one deliberate lifecycle fault. It is
built into its own observation host and run through the corpus as the
reference. Its own expectations must fail (the corpus states expected
behaviour independently of any engine). With --f0-work, the mutant is also
replayed against a recorded F0 reference collection and must differ there. A
mutant that passes is a gap in the corpus and fails this tool. The VM does not
run every corpus case (registered differences), so a VM mutant is judged not
by its expectations but by comparing its own build's tree engine with its VM
over the current corpus, which applies the difference registry. Its replay is
reported too; the frozen F0 collection lacks cases added since, so it does not
decide.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]

# The unmodified runtime runs first and must pass, so a broken harness or an
# empty corpus cannot count as catching every mutant; once per engine used.
CONTROL = ('control', [])
VM_CONTROL = ('control-vm', [], 'vm')
# Each mutant is (name, [(path, old, new), ...][, engine]); every old text must
# occur exactly once, so a mutant whose anchors moved with the code fails
# loudly. The engine (default tree) is the one patched; 'vm' requires --f0-work.
MUTANTS = [
    # The traversal yields the destructor only after the members have ended.
    ('destructor-after-fields', [
        ('interpreter/core/lifecycle.cpp',
         """            if(run_destructor_&&block.destructor){waiting_=true;return &block;}
            frame.remaining=block.slots.size();""",
         """            frame.remaining=block.slots.size();"""),
        ('interpreter/core/lifecycle.cpp',
         """        block.state=slot_block::status::dead;block.owner=nullptr;block.owner_scope={};block.methods.reset();""",
         """        if(run_destructor_&&block.destructor&&frame.phase!=Phase(2)){frame.phase=Phase(2);waiting_=true;return &block;}
        block.state=slot_block::status::dead;block.owner=nullptr;block.owner_scope={};block.methods.reset();"""),
    ]),
    ('temporaries-first-in-first-out', [
        ('interpreter/core/lifecycle.cpp',
         'auto block=std::move(record->values.back());record->values.pop_back();',
         'auto block=std::move(record->values[mark_]);'
         'record->values.erase(record->values.begin()+static_cast<std::ptrdiff_t>(mark_));'),
    ]),
    ('owned-first-in-first-out', [
        ('interpreter/core/lifecycle.cpp',
         'const address pointer=record->owned.back();record->owned.pop_back();',
         'const address pointer=record->owned.front();record->owned.erase(record->owned.begin());'),
    ]),
    ('destructor-caller-always-synthesized', [
        ('interpreter/core/lifecycle.cpp',
         'if(caller.record&&caller.record->script.lock()==script){invoke(caller);return;}',
         'if(false){invoke(caller);return;}'),
    ]),
    ('step-reset-per-destructor', [
        ('interpreter/core/lifecycle.cpp',
         '    invoke_bound_function(script,call.function_id,context,',
         '    script->remaining_steps=script->max_steps;\n    invoke_bound_function(script,call.function_id,context,'),
    ]),
    ('last-error-wins', [
        ('interpreter/core/trace.cpp',
         """void pending_error::capture_current() noexcept {
    if(!exception_) {""",
         """void pending_error::capture_current() noexcept {
    if(true) {"""),
    ]),
    ('copy-not-charged', [
        ('interpreter/blocks.cpp',
         'if(auto script=block->script_owner.lock();script&&script->active_calls) {',
         'if(auto script=block->script_owner.lock();false&&script&&script->active_calls) {'),
    ]),
    # Members end first and the destructor body runs on the emptied object.
    ('heap-members-before-destructor', [
        ('interpreter/core/lifecycle.cpp',
         """        registered_=true;phase_=Phase::destructor;
        if(destructor_){call_={&owner_,*destructor_,object_,&contexts_};return &call_;}
        continue;""",
         """        registered_=true;members_.emplace(heap::detail::allocation_slots(object_),true,failure_);
        phase_=Phase::members;continue;"""),
        ('interpreter/core/lifecycle.cpp',
         """        members_.reset();phase_=Phase::done;""",
         """        members_.reset();
        if(registered_&&destructor_){phase_=Phase::destructor;call_={&owner_,*destructor_,object_,&contexts_};return &call_;}
        phase_=Phase::done;"""),
        ('interpreter/core/lifecycle.cpp',
         """        // still marked as destroying so they cannot delete or free it.
        members_.emplace(heap::detail::allocation_slots(object_),true,failure_);
        phase_=Phase::members;continue;""",
         """        phase_=Phase::done;heap::detail::end_destroy(object_);
        return nullptr;"""),
    ]),
    ('return-copies-locals', [
        ('interpreter/blocks.cpp',
         'block->owner&&owning&&owning->temporaries==frame) {',
         'false&&block->owner&&owning&&owning->temporaries==frame) {'),
    ]),
    ('teardown-without-guard', [
        ('interpreter/script.cpp',
         """    try {
        call_guard guard(baseEnv);
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {""",
         """    try {
        for(auto hook=on_destory.rbegin();hook!=on_destory.rend();++hook) {"""),
    ]),
    # The VM's destructor continuations and its unwinding walk.
    ('continuation-without-admission', [
        ('interpreter/vm/execution.cpp',
         'auto admitted=options.calls?options.calls->enter():admission::accounting::ticket{};',
         'admission::accounting::ticket admitted{};'),
    ], 'vm'),
    ('continuation-entries-succeed-on-failure', [
        ('interpreter/vm/execution.cpp',
         'pending.waiting=false;pending.function.fail();pending.dispatch.fail();',
         'pending.waiting=false;pending.function.succeed();pending.dispatch.succeed();'),
    ], 'vm'),
    ('continuation-failure-reaches-caller', [
        ('interpreter/vm/execution.cpp',
         '            if(continuation(id)) {',
         '            if(false&&continuation(id)) {'),
    ], 'vm'),
    ('continuation-calls-from-current-scope', [
        ('interpreter/vm/execution.cpp',
         'if constexpr(Continuation)enter_object_frame(index,count,argument,frame,layout,options,continuation->caller,contexts);',
         'if constexpr(Continuation)enter_object_frame(index,count,argument,frame,layout,options,ownership::Site{},contexts);'),
    ], 'vm'),
    ('unwinding-levels-not-republished', [
        ('interpreter/vm/execution.cpp',
         'try{unwind.failure.rethrow();}catch(...){unwind.failure=pending_error{};unwind.failure.capture_current();}',
         '(void)unwind;'),
    ], 'vm'),
    # The root scope ends although a region of the frame recovers.
    ('unwinding-region-ends-root', [
        ('interpreter/vm/execution.cpp',
         'if(unwind.region){unwind.mode=Unwind::Mode::finished;continue;}',
         ''),
    ], 'vm'),
    ('depth-counts-callers', [
        ('interpreter/core/ownership.cpp',
         'for(auto id=scope;const auto* record=find(id);id=record->parent)++result;',
         'for(auto id=scope;const auto* record=find(id);id=find(record->caller)?record->caller:record->parent)++result;'),
    ]),
]


def run(command, **options):
    print('+', ' '.join(map(str, command)), flush=True)
    return subprocess.run(list(map(str, command)), **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--classpath', required=True)
    parser.add_argument('--work', type=Path, required=True, help='new directory')
    parser.add_argument('--f0-work', type=Path, help='completed F0 reference collection (stage ownership)')
    parser.add_argument('--only', help='comma-separated mutant names')
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    work = args.work.resolve(); work.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'AZSCRIPT_EXPERIMENTAL_VM_BACKEND'):
        env.pop(key, None)
    chosen = [m for m in MUTANTS if not args.only or m[0] in args.only.split(',')]
    vm_chosen = any(len(m) > 2 for m in chosen)
    if vm_chosen and not args.f0_work:
        raise SystemExit('VM mutants are judged by the F0 replay: pass --f0-work')
    selected = [CONTROL] + ([VM_CONTROL] if vm_chosen else []) + chosen
    report = {'mutants': {}}
    survivors = []
    for name, patches, *rest in selected:
        engine = rest[0] if rest else 'tree'
        folder = work / name; source = folder / 'source'; source.mkdir(parents=True)
        archive = subprocess.run(['git', 'archive', 'HEAD'], cwd=ROOT, capture_output=True, check=True).stdout
        subprocess.run(['tar', '-x', '-C', source], input=archive, check=True)
        for path, old, new in patches:
            target = source / path; text = target.read_text(encoding='utf-8')
            if text.count(old) != 1:
                raise SystemExit(f'{name}: patch anchor not found exactly once in {path}')
            target.write_text(text.replace(old, new), encoding='utf-8')
        build = folder / 'build'
        configure = run(['cmake', '-S', source, '-B', build, '-DCMAKE_BUILD_TYPE=Debug', '-DAZSCRIPT_BUILD_JNI=OFF',
                         '-DAZSCRIPT_SANITIZE=OFF'], capture_output=True, text=True)
        compiled = configure.returncode == 0 and run(
            ['cmake', '--build', build, '--target', 'observation-host', '--parallel', args.jobs],
            capture_output=True, text=True).returncode == 0
        if not compiled:
            raise SystemExit(f'{name}: mutant does not build')
        host = build / 'interpreter/observation-host'
        checks = {}
        if engine == 'tree':
            own = run([sys.executable, ROOT / 'tests/vm_execution_end_to_end.py', '--classpath', args.classpath,
                       '--reference-host', host, '--reference-engine', engine, '--stage', 'ownership',
                       '--work', folder / 'expectations'], cwd=ROOT, env=env, capture_output=True, text=True)
            checks['expectations'] = 'caught' if own.returncode else 'missed'
        else:
            live = run([sys.executable, ROOT / 'tests/vm_execution_end_to_end.py', '--classpath', args.classpath,
                        '--reference-host', host, '--reference-engine', 'tree',
                        '--candidate-host', host, '--candidate-engine', engine, '--stage', 'ownership',
                        '--work', folder / 'tree-vs-vm'], cwd=ROOT, env=env, capture_output=True, text=True)
            checks['tree_vs_vm'] = 'caught' if live.returncode else 'missed'
        if args.f0_work:
            replay = run([sys.executable, ROOT / 'tests/vm_execution_end_to_end.py', '--reference-work', args.f0_work,
                          '--candidate-host', host, '--candidate-engine', engine, '--stage', 'ownership',
                          '--work', folder / 'f0-replay'], cwd=ROOT, env=env, capture_output=True, text=True)
            checks['f0_replay'] = 'caught' if replay.returncode else 'missed'
        report['mutants'][name] = checks
        if not patches:
            if 'caught' in checks.values():
                raise SystemExit(f'control runtime fails the corpus: {checks}; see {folder}')
            shutil.rmtree(folder, ignore_errors=True)
            continue
        if (checks['tree_vs_vm'] == 'missed') if engine == 'vm' else ('missed' in checks.values()):
            survivors.append(name)
            shutil.rmtree(build / 'abdC', ignore_errors=True)
        else:
            # A caught mutant's build and observations are not needed again.
            shutil.rmtree(folder, ignore_errors=True)
        print(f'{name}: {checks}', flush=True)
    (work / 'report.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(f'Conformance mutants: {len(chosen) - len(survivors)}/{len(chosen)} caught; report {work / "report.json"}')
    if survivors:
        print(f'SURVIVED: {survivors}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
