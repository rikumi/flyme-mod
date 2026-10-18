"""Exercise the production drag hook and native-scope restoration."""
from pathlib import Path
import subprocess, tempfile
base=Path('app/src/main/java/dev/rikumi/flymemod')
s=(base/'SystemIconPackLauncherHooks.java').read_text()
body=s.split('installer.hook(utilities.getName(),fullDrawable.getName(),chain->{',1)[1].split('        },Context.class,itemInfo,int.class,int.class,boolean.class);',1)[0]
s=(base/'SystemIconPackClient.java').read_text()
scope=s[s.index('    private static final ThreadLocal<Integer>'):s.index('    private static final LruCache')]
code='''class DragScopeTest {
static class SystemIconPackClient {'''+scope+'''}
static class ModuleSettings {static String SYSTEM_ICON_PACK="enabled";}
static boolean enabled;
static class Settings {boolean getBoolean(String key,boolean fallback){return enabled;}}
static Settings settings=new Settings();
interface Chain {Object proceed() throws Exception;}
static Object load(Chain chain) throws Exception {'''+body+'''}
static void check(boolean value){if(!value)throw new AssertionError();}
public static void main(String[] args)throws Exception {
 Object themeLayers=new Object();enabled=true;
 check(load(()->{check(SystemIconPackClient.isNative());return themeLayers;})==themeLayers);
 check(!SystemIconPackClient.isNative());
 try {load(()->{throw new Exception("theme error");});throw new AssertionError();}
 catch(Exception expected){check(!SystemIconPackClient.isNative());}
 try(var outer=SystemIconPackClient.nativeScope()){
  load(()->themeLayers);check(SystemIconPackClient.isNative());
 }
 check(!SystemIconPackClient.isNative());enabled=false;
 load(()->{check(!SystemIconPackClient.isNative());return themeLayers;});
 System.out.println("PASS: native theme layers, disabled option, nested and exceptional scope restoration");
}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 p=Path(tmp)/'DragScopeTest.java';p.write_text(code)
 jdk='/opt/homebrew/opt/openjdk@21/bin/'
 subprocess.run([jdk+'javac',str(p)],check=True)
 subprocess.run([jdk+'java','-cp',tmp,'DragScopeTest'],check=True)
