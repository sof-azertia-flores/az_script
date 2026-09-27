#!/usr/bin/env python3
"""Real-JVM v9 buffer snapshot restoration and malformed-state atomicity."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

from compact_exec_end_to_end import encode_compact_module

ROOT = Path(__file__).resolve().parents[1]
BASE = 0x22220000


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bridge', type=Path, required=True)
    parser.add_argument('--library', type=Path, required=True)
    parser.add_argument('--classpath', required=True)
    args = parser.parse_args()

    def var(slot):
        return {'t': 0, 'c': 'v', 'v': slot}

    def context(abi=0, kind=0, width=1, **extra):
        return {'abi': abi, 'kind': kind, 'width': width, 'contexts': [], 'placement-contexts': [], **extra}

    def call(ident, *values, contexts=()):
        return {'t': 1, 'id': ident, 'param': list(values), 'contexts': list(contexts)}

    def op(operation, receiver, *arguments):
        return {'t': 0, 'c': 'buffer_op', 'op': operation, 'v': receiver, 'args': list(arguments)}

    def new(element):
        return {'t': 0, 'c': 'buffer_new', 'context': element}

    def setvar(slot, value):
        return {'t': 0, 'c': 'vs', 'v': slot, 'val': value}

    def memory(pointer):
        return call(0x0abd0006, pointer)

    def put(pointer, value):
        return {'t': 0, 'c': 'm', 'v1': memory(pointer), 'v2': value}

    def ret(value):
        return {'t': 0, 'c': 'r', 'r': value}

    def function(ident, body, returns=5, params=(), hidden=0, internal=0, locals=0):
        return {'id': ident, 'return-type': returns, 'param-count': len(params), 'param-types': list(params),
                'local-count': locals, 'hidden-count': hidden, 'entry-kind': internal, 'script': body}

    scalar = context()
    nested = context(8, 4, element=scalar)
    element = context(8, 3, 2, factory=BASE + 3, contexts=[scalar], placement=BASE + 4,
                      **{'placement-contexts': [scalar]})
    block_address = lambda value: {'t': 0, 'c': 'block_address', 'v': value}
    address = lambda value, offset: {'t': 0, 'c': 'oa', 'v': value, 'offset': offset}
    body = [setvar(-2, {'address': '0'}), setvar(-3, 0), setvar(-1, new(element)), op(2, var(-1), 5), op(6, var(-1), 2),
            setvar(-4, new(nested)), op(2, var(-4), 3),
            {'t': 0, 'c': 'vd', 'v': 0, 'declared-type': 8, 'val': new(scalar)},
            op(2, var(0), 3), op(5, var(0), 41), op(5, var(-4), var(0)), setvar(-5, new(scalar))]
    placement = [put(address(var(0), 0), {'t': 0, 'c': 'context_default', 'context': {'ref': 0}}),
                 put(address(var(0), 1), 7),
                 {'t': 0, 'c': 'if', 'v': {'t': 0, 'c': 'eq', 'v1': var(-2), 'v2': {'address': '0'}},
                  'val': setvar(-2, var(0))},
                 {'t': 0, 'c': 'ob', 'v': var(0), 'destructor': BASE + 5, 'manual': False, 'contexts': [{'ref': 0}]}]
    factory = [{'t': 0, 'c': 'vd', 'v': 0, 'declared-type': 8, 'val': {'t': 0, 'c': 'new_block', 'size': 2}},
               call(BASE + 4, block_address(var(0)), contexts=[{'ref': 0}]), {'t': 0, 'c': 'ro', 'r': var(0)}]
    program = {'exec-version': 9, 'gvs': 5, 'f': [function(0, body, internal=1, locals=1),
        function(BASE + 3, factory, returns=8, hidden=1, internal=1, locals=1),
        function(BASE + 4, placement, params=[7], hidden=1, internal=1),
        function(BASE + 5, [setvar(-3, {'t': 0, 'c': 'add', 'v1': var(-3), 'v2': 1}),
                            {'t': 0, 'c': 'context_default', 'context': {'ref': 0}}], params=[7], hidden=1, internal=1),
        function(BASE + 10, [ret(memory(var(-2)))], returns=0),
        function(BASE + 11, [ret(var(-3))], returns=0),
        function(BASE + 12, [ret(op(1, var(-1)))], returns=0),
        function(BASE + 13, [ret(op(0, var(-1)))], returns=0),
        function(BASE + 14, [ret(var(-2))], returns=7),
        function(BASE + 15, [ret(op(3, op(3, var(-4), 0), 0))], returns=0),
        function(BASE + 16, [ret(op(1, var(-5)))], returns=0),
        function(BASE + 20, [put(var(-2), var(0))], params=[0]),
        function(BASE + 21, [op(6, var(-1), 0)]),
        function(BASE + 22, [op(6, var(-1), 3)]),
        function(BASE + 23, [ret(memory(var(0)))], returns=0, params=[7])
    ]}
    with tempfile.TemporaryDirectory(prefix='buffer-snapshot-', dir=ROOT / 'build') as directory:
        work = Path(directory)
        binary = work / 'buffer.abd'
        binary.write_bytes(encode_compact_module(program))
        source = work / 'BufferSnapshot.java'
        source.write_text('''import azertia.AbdInvoker;
import azertia.Address;
import azertia.binary.AbdValue;
import azertia.binary.complexBinary.*;
import java.io.File;
import java.nio.file.Files;
public class BufferSnapshot {
 static final int BASE=0x22220000;
 static int invoke(int n){return (Integer)AbdInvoker.invoke(BASE+n);}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static AcsObject obj(AcsArray values,int index){return (AcsObject)values.acsa.get(index);}
 static AcsObject scalar(){AcsObject c=new AcsObject();c.put("abi",0);c.put("kind",0);c.put("width",1);
  c.put("has factory",false);c.put("contexts",new AcsArray());c.put("has placement",false);
  c.put("placement contexts",new AcsArray());c.put("has element",false);return c;}
 public static void main(String[] args)throws Exception {
  File saved=new File(args[1]), bad=new File(args[1]+".bad");
  try {
   AbdInvoker.loadScript(new File(args[0]));AbdInvoker.flush();
   check(invoke(10)==0&&invoke(11)==0&&invoke(12)==5&&invoke(13)==2&&invoke(15)==41&&invoke(16)==0,"initial buffer state");
   Address before=(Address)AbdInvoker.invoke(BASE+14);
   check(AbdInvoker.saveStatus(saved),"save buffer state");
   byte[] bytes=Files.readAllBytes(saved.toPath());AcsObject root=new AcsObject(AbdValue.fromAbd(bytes));
   check(root.getAsInt("snapshot version")==9,"snapshot version");
   AcsObject buffer=obj(root.getAsAcsArray("variable global"),0);
   check(buffer.getAsInt("capacity")==5&&buffer.getAsInt("length")==2&&buffer.getAsAcsArray("slots").acsa.size()==2,"only live slab elements stored");
   check(obj(buffer.getAsAcsArray("slots"),0).getAsAcsArray("slots").acsa.size()==2,"class slab width");
   check(obj(root.getAsAcsArray("variable global"),4).getAsAcsArray("slots").acsa.isEmpty(),"empty buffer saves no slots");
   AbdInvoker.invoke(BASE+20,99);
   for(int mode=0;mode<22;++mode) {
    root=new AcsObject(AbdValue.fromAbd(bytes));buffer=obj(root.getAsAcsArray("variable global"),0);
    AcsObject element=buffer.getAsAcsObject("element");AcsArray slots=buffer.getAsAcsArray("slots");
    switch(mode) {
     case 0 -> root.put("snapshot version",8);
     case 1 -> buffer.put("capacity",1);
     case 2 -> buffer.put("length",3);
     case 3 -> buffer.put("capacity",Integer.MAX_VALUE);
     case 4 -> element.put("width",0);
     case 5 -> element.put("width",3);
     case 6 -> element.put("placement",BASE+99);
     case 7 -> element.put("placement contexts",new AcsArray());
     case 8 -> element.put("placement",BASE+3);
     case 9 -> element.put("has placement",false);
     case 10 -> obj(slots,1).put("object",obj(slots,0).mmp.get("object"));
     case 11 -> obj(slots,0).getAsAcsArray("slots").acsa.remove(1);
     case 12 -> slots.acsa.set(0,new AcsIntegerElement(42));
     case 13 -> buffer.mmp.remove("element");
     case 14 -> {buffer.put("has destructor",true);buffer.put("destructor",BASE+5);}
     case 15 -> {AcsObject c=scalar();c.put("abi",5);buffer.put("element",c);}
     case 16 -> {AcsObject c=scalar();c.put("width",2);buffer.put("element",c);}
     case 17 -> obj(slots,0).put("destructor contexts",new AcsArray());
     case 18 -> element.put("factory",BASE+4);
     case 19 -> {AcsObject nested=obj(root.getAsAcsArray("variable global"),3);nested.getAsAcsObject("element").put("has element",false);}
     case 20 -> buffer.put("buffer",false);
     case 21 -> buffer.put("capacity",1048576/2);
    }
    Files.write(bad.toPath(),root.toValue().toAbdFormat());
    try {AbdInvoker.loadStatus(bad);throw new AssertionError("accepted malformed buffer "+mode);}
    catch(IllegalArgumentException expected) {}
    check(invoke(10)==99&&invoke(11)==0&&invoke(12)==5&&invoke(13)==2&&invoke(15)==41,"failed restore changed state "+mode);
   }
   AbdInvoker.loadStatus(saved);
   check(invoke(10)==0&&invoke(11)==0&&invoke(12)==5&&invoke(13)==2&&invoke(15)==41&&invoke(16)==0,"restored state");
   Address restored=(Address)AbdInvoker.invoke(BASE+14);check(!before.equals(restored),"view address gets a fresh id");
   try {AbdInvoker.invoke(BASE+23,before);throw new AssertionError("old view address stayed live");}
   catch(IndexOutOfBoundsException expected) {}
   AbdInvoker.invoke(BASE+22);check(invoke(13)==3&&invoke(11)==0,"restored placement context can grow buffer");
   check(AbdInvoker.saveStatus(saved),"save restored buffer");AbdInvoker.loadStatus(saved);
   AbdInvoker.invoke(BASE+21);check(invoke(11)==3&&invoke(13)==0,"restored view destructors run once");
   System.out.println("Buffer JNI snapshot passed: 22 corrupt states rejected atomically; slab views, nested and empty buffers restored");
  } finally {AbdInvoker.close();}
 }
}''', encoding='utf-8')
        classpath = os.pathsep.join((str(args.bridge.resolve()), args.classpath))
        subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(work), str(source)], check=True, cwd=ROOT)
        result = subprocess.run(['java', '-Xcheck:jni', '-Dazertia.native.library=' + str(args.library.resolve()),
                                 '-cp', os.pathsep.join((str(work), classpath)), 'BufferSnapshot', str(binary), str(work / 'buffer.snapshot.abd')],
                                capture_output=True, text=True, timeout=60, cwd=ROOT)
        assert result.returncode == 0, (result.returncode, result.stdout, result.stderr)
        assert 'WARNING in native method' not in result.stdout + result.stderr
        print(result.stdout, end='')


if __name__ == '__main__':
    main()
