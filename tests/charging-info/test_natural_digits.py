"""Execute the real font-feature selector to isolate charging from clock tnum preferences."""
from pathlib import Path
import tempfile,subprocess
s=Path('app/src/main/java/dev/rikumi/flymemod/ClockFontHooks.java').read_text()
a=s.index('    private String fontFeatures(');b=s.index('\n    private ',a+8);method=s[a:b]
java='''import java.util.*;import java.util.function.*;public class NaturalDigitsTest{
static class ViewParent{ViewParent getParent(){return null;}}static class View extends ViewParent{Object tag;ViewParent parent;Object getTag(){return tag;}ViewParent getParent(){return parent;}}
static class TextView extends View{}
String clockKind(TextView v){return knownClockKinds.get(v);}
Map<TextView,String> knownClockKinds=new HashMap<>();BooleanSupplier controlCenterMonospace=()->true,statusBarMonospace=()->true,aodMonospace=()->true,lockMonospace=()->true;Class<?> aodClass=String.class,lockClass=Integer.class;
'''+method+'''
public static void main(String[] args){NaturalDigitsTest t=new NaturalDigitsTest();int n=0;for(String tag:new String[]{"flyme_mod_charging_top","flyme_mod_charging_bottom"}){TextView v=new TextView();v.tag=tag;t.knownClockKinds.put(v,"status_extra");if(t.fontFeatures(v,null)!=null)throw new AssertionError();if(t.fontFeatures(v,"'tnum'")!=null)throw new AssertionError();if(!"'liga'".equals(t.fontFeatures(v,"'liga', 'tnum'")))throw new AssertionError();n+=3;}for(String kind:new String[]{"clock","status_extra","mz_clock"}){TextView v=new TextView();t.knownClockKinds.put(v,kind);if(!"'tnum'".equals(t.fontFeatures(v,null)))throw new AssertionError();n++;}System.out.println("PASS: "+n+" actual charging natural-digit/other-clock preference checks");}}
'''
with tempfile.TemporaryDirectory(dir='/private/tmp') as tmp:
 p=Path(tmp)/'NaturalDigitsTest.java';p.write_text(java);jdk=Path('/opt/homebrew/opt/openjdk@21/bin')
 subprocess.run([str(jdk/'javac'),'-d',tmp,str(p)],check=True)
 subprocess.run([str(jdk/'java'),'-cp',tmp,'NaturalDigitsTest'],check=True)
