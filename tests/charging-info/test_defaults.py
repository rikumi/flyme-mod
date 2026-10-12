"""Execute production preference expressions, including missing-column fallbacks."""
from pathlib import Path
import re,subprocess,tempfile
base=Path('app/src/main/java/dev/rikumi/flymemod')
settings=(base/'ModuleSettings.java').read_text()
constants=[]
for key in ['STATUS_BAR_CHARGING_INFO','STATUS_BAR_LYRIC_AVOID','STATUS_BAR_CHARGING_INFO_DEFAULT','STATUS_BAR_LYRIC_AVOID_DEFAULT']:
 m=re.search(r'public static final (?:String|int|boolean) '+key+r' = [^;]+;',settings);assert m,key;constants.append(m.group())
methods=[];names=[]
for name in ['SettingsProvider.java','CameraSettingsBridge.java','StatusBarOverlayServiceHooks.java']:
 source=(base/name).read_text()
 for index,m in enumerate(re.finditer(r'get(Int|Boolean)\(ModuleSettings\.(STATUS_BAR_CHARGING_INFO|STATUS_BAR_LYRIC_AVOID),\s*ModuleSettings\.\2_DEFAULT\)',source)):
  fn=f'get_{name.split(".")[0]}_{index}';names.append((fn,m.group(1)))
  methods.append(f'static {"int" if m.group(1)=="Int" else "boolean"} {fn}(Prefs prefs){{return prefs.{m.group()};}}')
assert len(names)==6,names
xposed=(base/'XposedInit.java').read_text()
for field,default in [('statusBarChargingInfoMode','STATUS_BAR_CHARGING_INFO_DEFAULT'),('statusBarLyricAvoidEnabled','STATUS_BAR_LYRIC_AVOID_DEFAULT')]:
 assert re.search(r'private (int|boolean) '+field+r' = ModuleSettings\.'+default+r';',xposed)
mode=re.search(r'statusBarChargingInfoMode = (chargingInfoColumn < 0 [^;]+);',xposed).group(1)
lyric=re.search(r'statusBarLyricAvoidEnabled = (lyricAvoidColumn < 0 [^;]+);',xposed).group(1)
catalog=(base/'SettingsCatalog.kt').read_text();items=(base/'SettingsItems.kt').read_text();preset=(base/'OneClickPreset.kt').read_text()
assert 'defaultValue = ModuleSettings.STATUS_BAR_CHARGING_INFO_DEFAULT' in catalog
assert 'defaultEnabled = ModuleSettings.STATUS_BAR_LYRIC_AVOID_DEFAULT' in catalog
assert 'preferences.getInt(item.key, item.defaultValue)' in items
assert 'val defaultValue: Int = values.first()' in items
assert 'ModuleSettings.STATUS_BAR_CHARGING_INFO to ModuleSettings.STATUS_BAR_CHARGING_INFO_DEFAULT' in preset
assert 'ModuleSettings.STATUS_BAR_LYRIC_AVOID to ModuleSettings.STATUS_BAR_LYRIC_AVOID_DEFAULT' in preset
cases=[]
for fn,kind in names:
 expected='1' if kind=='Int' else 'true';closed='0' if kind=='Int' else 'false';chosen='2' if kind=='Int' else 'true'
 cases.extend([f'check({fn}(prefs)=={expected});',f'prefs.mode=0;prefs.lyric=false;check({fn}(prefs)=={closed});',f'prefs.mode=2;prefs.lyric=true;check({fn}(prefs)=={chosen});prefs.mode=null;prefs.lyric=null;'])
java='''public class DefaultTest{
static class ModuleSettings{'''+''.join(constants)+'''}
static class Prefs{Integer mode;Boolean lyric;int getInt(String key,int value){return mode==null?value:mode;}boolean getBoolean(String key,boolean value){return lyric==null?value:lyric;}}
static class Cursor{int value;int getInt(int column){if(column<0)throw new AssertionError("missing read");return value;}}
'''+''.join(methods)+f'''static int mode(Cursor cursor,int chargingInfoColumn){{return {mode};}}static boolean lyric(Cursor cursor,int lyricAvoidColumn){{return {lyric};}}'''+'''
static int n;static void check(boolean b){n++;if(!b)throw new AssertionError("check "+n);}public static void main(String[] args){Prefs prefs=new Prefs();'''+''.join(cases)+'''
Cursor cursor=new Cursor();check(mode(cursor,-1)==1);check(lyric(cursor,-1));cursor.value=0;check(mode(cursor,0)==0);check(!lyric(cursor,0));cursor.value=2;check(mode(cursor,0)==2);cursor.value=1;check(lyric(cursor,0));System.out.println("PASS: "+n+" production default/saved-choice/provider/remote/missing-column checks; UI and preset wiring checked");}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 p=Path(tmp)/'DefaultTest.java';p.write_text(java);jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',tmp,str(p)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',tmp,'DefaultTest'],check=True)
