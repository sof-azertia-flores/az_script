#!/usr/bin/env python3
"""Check source -> JSON AST -> ABD -> native execution, including bad programs."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--classpath',required=True)
    p.add_argument('--runner',type=Path,required=True)
    p.add_argument('--bridge',type=Path,required=True)
    p.add_argument('--library',type=Path)
    a=p.parse_args()
    checks=0
    with tempfile.TemporaryDirectory(prefix='e2e-',dir=ROOT/'build') as temp:
        work=Path(temp)
        def compiler(*args,success=True):
            result=subprocess.run(['java','-cp',a.classpath,'azertia.Main',*map(str,args)],capture_output=True,text=True,timeout=20,cwd=ROOT)
            if (result.returncode==0)!=success:raise AssertionError(result.stdout+result.stderr)
            return result
        def execute(source,expected=None,error=None):
            nonlocal checks
            src=work/'case.azs';src.write_text(source,encoding='utf-8')
            binary=work/'case.abd'
            compiler('compile',src,'-o',binary)
            result=subprocess.run([str(a.runner),str(binary)],capture_output=True,text=True,timeout=10)
            if error:
                assert result.returncode!=0 and error in result.stderr,(source,result.stdout,result.stderr)
            else:
                assert result.returncode==0 and result.stdout==expected,(source,result.stdout,result.stderr)
            checks+=1
        for expression,value in [('20-3-2',15),('100/5/2',10),('2+3*(4+1)',17),('((2+3)*4)-7',13),
                                 ('-20/-2/2',5),('7%4*2+1',7),('-(-5)',5),('1+-2*3',-5),('-2147483648',-2147483648)]:
            execute(f'int main(){{return {expression};}}',f'{value}\n')
        execute('int main(){return sum(sum(1,2),fact(4));}int sum(a,b){return a+b;}int fact(n){if(n<=1){return 1;}return n*fact(n-1);}','27\n')
        execute('int main(){def(i,0);while(i<10,{if(i==3,{return i;});i=i+1;});return 99;}','3\n')
        execute('int main(){var a=1;var b=2;a=b=7;{var a=90;}return a+b;}','14\n')
        execute('int main(){var i=0;var total=0;while(i<3){var j=0;while(j<2){total+=1;j+=1;}i+=1;}return total;}','6\n')
        execute('boolean main(){return true || (1/0==0);}','true\n')
        execute('boolean main(){return false && (1/0==0);}','false\n')
        execute('boolean main(){return !(2>=3) && (4<=4) && (1!=2);}','true\n')
        execute('double main(){return 5-0.25*2;}','4.5\n')
        execute('double main(){return 5e0/2;}','2.5\n')
        execute('float main(){return 1.5f;}','1.5\n')
        execute('double main(){return 5./2;}','2.5\n')
        execute('double main(){return -0.0;}','-0\n')
        execute('int main(){var i=0;while((i=i+1)<3) var x=i;return i;}','3\n')
        execute('#namespace 123\nint helper(){return 4;}int main(){return helper();}','4\n')
        execute('int main(){var outer=0;var total=0;while(outer<3){var inner=0;while(inner<5){inner+=1;if(inner==2){break;}total+=1;}outer+=1;}return total;}','3\n')
        execute('#define N 7 // value\nint main(){return N;}','7\n')
        execute('#ifdef OFF\n/*\n#endif\n*/\n#endif\nint main(){return 1;}','1\n')
        execute('string main(){ /* ( , ; } */ return "你好,(世界); \\"quoted\\"";}'.replace('\\"quoted\\"','\\"quoted\\"'),'你好,(世界); "quoted"\n')
        # Numbers keep their shortest round-trip spelling when printed or concatenated.
        execute('double main(){return 0.1+0.2;}','0.30000000000000004\n')
        execute('double main(){return 123456789.0;}','123456789\n')
        execute('string main(){return "v="+3.14159265358979;}','v=3.14159265358979\n')
        execute('float main(){return 0.1f;}','0.1\n')
        execute('string main(){return "n="+16777217.0f;}','n=16777216\n')
        # Flat operator chains are limited by the ABD depth, not the old syntax budget.
        execute('int main(){return '+'+'.join(['1']*100)+';}','100\n')
        execute('int main(){return f();}int f(){while(true){return 5;}}','5\n')
        execute('﻿#define X 5\nint main(){return X;}','5\n')
        for zero_division in [
                'int main(){return 1/0;}',
                'int main(){return 1/-0;}',
                'float main(){return 1.0f/0.0f;}',
                'float main(){return 1.0f/-0.0f;}',
                'double main(){return 1.0/0.0;}',
                'double main(){return 1.0/-0.0;}']:
            execute(zero_division,error='Division by zero')
        execute('int main(){return 2147483647+1;}',error='Integer overflow')
        execute('void main(){while(true){}}',error='step limit')
        execute('int main(){return main();}',error='depth limit')
        execute('extern int host():0xfff0009;\nint main(){return host();}',error='Imported script function is missing')
        for source in ['void main(){var x=(1+2;}', 'int main(){return missing;}', 'void main(){@;}',
                       'void main(){var x="unterminated;}', 'int main(){return f(1);}int f(a,b){return a+b;}',
                       'void main(){2=3;}', 'void main(){return 7;}', 'void main(){break;}',
                       '#extern host_add 0xccf0001\nvoid main(){}',
                       'extern int host_add(int, int):0xccf0001;\nint main(){return host_add(1.0,2);}',
                       'extern int host_add(int, int):0xccf0001;\nint main(){return host_add(1);}',
                       'int f(){} int main(){return f();}',
                       'int while(){return 1;} int main(){return 2;}',
                       'int main(){def(if,1);return 1;}',
                       'int main(){return '+'+'.join(['1']*130)+';}']:
            path=work/'bad.azs';path.write_text(source,encoding='utf-8')
            result=compiler('compile',path,'-o',work/'bad.abd',success=False)
            assert 'Error:' in result.stderr and 'Exception in thread' not in result.stderr,result.stderr
            checks+=1
        for bad_json in ['[]','null','42','{}']:
            path=work/'invalid.json';path.write_text(bad_json,encoding='utf-8')
            result=compiler('compile-json',path,'-o',work/'invalid.abd',success=False)
            assert 'Error:' in result.stderr and 'Exception in thread' not in result.stderr,result.stderr
            checks+=1
        original=ROOT/'compiler/examples/parser-regressions.azs'
        binary=work/'regressions.abd';ast=work/'ast.json';instructions=work/'instructions.json'
        compiler('compile',original,'-o',binary,'--ast',ast,'--exec-json',instructions)
        compiler('compile-json',ast,'-o',work/'from-json.abd')
        assert binary.read_bytes()==(work/'from-json.abd').read_bytes(),'AST recompile differs'
        result=subprocess.run([str(a.runner),str(binary)],capture_output=True,text=True,timeout=10)
        expected=['quoted: "a,(b);{c}" and a backslash: \\','15','10','17','120','26','false','true','146','done']
        assert result.returncode==0 and result.stdout.splitlines()==expected,(result.stdout,result.stderr)
        checks+=2
        # The pointer arithmetic sample must execute after recompilation.
        original=work/'heap.azs'
        original.write_text((ROOT/'abdJavaInvoker/tse.azs').read_text(encoding='utf-8'),encoding='utf-8')
        compiler('compile',original,'-o',work/'heap.abd')
        result=subprocess.run([str(a.runner),str(work/'heap.abd')],capture_output=True,text=True,timeout=10)
        assert result.returncode==0 and '24:33554432' in result.stdout and result.stdout.rstrip().endswith('predestroy2'),(result.stdout,result.stderr)
        checks+=1
        if a.library:
            source=work/'CompilerBridgeSmoke.java'
            source.write_text('''import azertia.AbdInvoker;
import java.io.File;
public class CompilerBridgeSmoke {
 public static void main(String[] args) {
  String mode=args.length>1?args[1]:"script";
  boolean external=!mode.equals("script");
  final int[] callbackCalls={0};
  if (external) AbdInvoker.registerJfunction(0xf2340001, values -> {
      callbackCalls[0]++;
      if (mode.equals("wrong-return")) return Double.valueOf(42.0);
      return (Integer)values[0]+(Integer)values[1];
  });
  if (mode.equals("all-types")) AbdInvoker.registerJfunction(0xf2340002, values -> {
      if (values.length!=5 || !Integer.valueOf(7).equals(values[0]) || !"x".equals(values[1])
          || !Float.valueOf(1.5f).equals(values[2]) || !Double.valueOf(2.5).equals(values[3])
          || !Boolean.TRUE.equals(values[4])) throw new AssertionError("typed callback arguments");
      callbackCalls[0]++;
      return "all-types-ok";
  });
  AbdInvoker.loadScript(new File(args[0]));
   AbdInvoker.flush();
  try {
   if (mode.equals("division-zero")) {
    expectIllegalState(() -> AbdInvoker.invoke(0x0fff0000),"division by zero");
    System.out.println("JAVA_BOUNDARY=division-zero");
   } else if (mode.equals("all-types")) {
    Object result=AbdInvoker.invoke(0x0fff0000);
    if (!"all-types-ok".equals(result) || callbackCalls[0]!=1) throw new AssertionError(result);
    System.out.println("JAVA_BOUNDARY=all-types");
   } else if (mode.equals("wrong-return")) {
    expectIllegalState(() -> AbdInvoker.invoke(0xf2340001,20,22),"return");
    if (callbackCalls[0]!=1) throw new AssertionError("wrong-return callback count="+callbackCalls[0]);
    System.out.println("JAVA_BOUNDARY=wrong-return");
   } else {
    Object result=AbdInvoker.invoke(0x0fff0000);
    Object expected=external?Integer.valueOf(42):Integer.valueOf(146);
    if (!expected.equals(result)) throw new AssertionError(result);
    if (external) {
     int before=callbackCalls[0];
     expectIllegalState(() -> AbdInvoker.invoke(0xf2340001,20.0,22),"argument");
     expectIllegalState(() -> AbdInvoker.invoke(0xf2340001,20),"argument");
     if (callbackCalls[0]!=before) throw new AssertionError("invalid arguments reached callback");
     System.out.println("JAVA_BOUNDARY=arguments");
    }
    System.out.println("JAVA_RESULT="+result);
   }
  } finally { AbdInvoker.close(); }
 }
 private static void expectIllegalState(Runnable action,String text) {
  try { action.run(); }
  catch (IllegalStateException expected) {
   if (!expected.getMessage().toLowerCase().contains(text)) throw new AssertionError(expected);
   return;
  }
  throw new AssertionError("Expected IllegalStateException containing "+text);
 }
}
''',encoding='utf-8')
            subprocess.run(['javac','--release','17','-cp',str(a.bridge),'-d',str(work),str(source)],check=True,timeout=20)
            result=subprocess.run(['java','-Xcheck:jni','-Dazertia.native.library='+str(a.library),'-cp',os.pathsep.join([str(work),str(a.bridge)]),'CompilerBridgeSmoke',str(binary)],capture_output=True,text=True,timeout=20)
            assert result.returncode==0 and 'JAVA_RESULT=146' in result.stdout and not result.stderr,(result.stdout,result.stderr)
            checks+=1
            external=work/'external.azs';external.write_text('extern int host_add(int, int):0xf2340001;\nint main(){return host_add(20,22);}',encoding='utf-8')
            external_binary=work/'external.abd';compiler('compile',external,'-o',external_binary)
            result=subprocess.run(['java','-Xcheck:jni','-Dazertia.native.library='+str(a.library),'-cp',os.pathsep.join([str(work),str(a.bridge)]),'CompilerBridgeSmoke',str(external_binary),'external'],capture_output=True,text=True,timeout=20)
            assert result.returncode==0 and 'JAVA_RESULT=42' in result.stdout and 'JAVA_BOUNDARY=arguments' in result.stdout and not result.stderr,(result.stdout,result.stderr)
            checks+=1
            result=subprocess.run(['java','-Xcheck:jni','-Dazertia.native.library='+str(a.library),'-cp',os.pathsep.join([str(work),str(a.bridge)]),'CompilerBridgeSmoke',str(external_binary),'wrong-return'],capture_output=True,text=True,timeout=20)
            assert result.returncode==0 and 'JAVA_BOUNDARY=wrong-return' in result.stdout and not result.stderr,(result.stdout,result.stderr)
            checks+=1
            all_types=work/'all-types.azs';all_types.write_text('extern string host_all(int, string, float, double, boolean):0xf2340002;\nstring main(){return host_all(7,"x",1.5f,2.5,true);}',encoding='utf-8')
            all_types_binary=work/'all-types.abd';compiler('compile',all_types,'-o',all_types_binary)
            result=subprocess.run(['java','-Xcheck:jni','-Dazertia.native.library='+str(a.library),'-cp',os.pathsep.join([str(work),str(a.bridge)]),'CompilerBridgeSmoke',str(all_types_binary),'all-types'],capture_output=True,text=True,timeout=20)
            assert result.returncode==0 and 'JAVA_BOUNDARY=all-types' in result.stdout and not result.stderr,(result.stdout,result.stderr)
            checks+=1
            division_zero=work/'division-zero.azs';division_zero.write_text('double main(){return 1.0/-0.0;}',encoding='utf-8')
            division_zero_binary=work/'division-zero.abd';compiler('compile',division_zero,'-o',division_zero_binary)
            result=subprocess.run(['java','-Xcheck:jni','-Dazertia.native.library='+str(a.library),'-cp',os.pathsep.join([str(work),str(a.bridge)]),'CompilerBridgeSmoke',str(division_zero_binary),'division-zero'],capture_output=True,text=True,timeout=20)
            assert result.returncode==0 and 'JAVA_BOUNDARY=division-zero' in result.stdout and not result.stderr,(result.stdout,result.stderr)
            checks+=1
    print(f'Compiler/native integration cases passed: {checks}')

if __name__=='__main__':main()
