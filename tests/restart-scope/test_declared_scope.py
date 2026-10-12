"""Compile production scope plan and simulate its shell with recording signals, never real kill."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
files={
'android/content/Context.java':'package android.content;public class Context{public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}}',
'android/content/pm/ComponentInfo.java':'package android.content.pm;public class ComponentInfo{public String processName;public ComponentInfo(String p){processName=p;}}',
'android/content/pm/PackageInfo.java':'package android.content.pm;public class PackageInfo{public ComponentInfo applicationInfo;public ComponentInfo[] activities,services,receivers,providers;}',
'android/content/pm/PackageManager.java':'''package android.content.pm;public class PackageManager{public static final int GET_ACTIVITIES=1,GET_SERVICES=2,GET_RECEIVERS=4,GET_PROVIDERS=8;public static class NameNotFoundException extends Exception{}public PackageInfo getPackageInfo(String p,int flags)throws NameNotFoundException{if(flags!=15)throw new AssertionError();if(!p.equals("com.google.android.gms")&&!p.equals("com.meizu.flyme.sdkstage"))throw new NameNotFoundException();PackageInfo i=new PackageInfo();i.applicationInfo=new ComponentInfo(p);i.services=new ComponentInfo[]{new ComponentInfo(p+":worker"),new ComponentInfo("shared.custom"),new ComponentInfo("invalid;command")};return i;}}''',
'dev/rikumi/flymemod/PlanTest.java':'package dev.rikumi.flymemod;public class PlanTest{public static void main(String[]args)throws Exception{System.out.print(ScopeRestartPlan.command(new android.content.Context()));}}',
}
with tempfile.TemporaryDirectory(prefix='flyme-scope-plan-',dir='/private/tmp') as d:
 base=Path(d);sources=[]
 for n,s in files.items():
  p=base/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(s);sources.append(str(p))
 resource=base/'META-INF/xposed/scope.list';resource.parent.mkdir(parents=True);resource.write_text('com.google.android.gms\ncom.meizu.flyme.sdkstage\ncom.missing\nsystem\ncom.google.android.gms\ninvalid;command\n')
 subprocess.run(['/opt/homebrew/opt/openjdk@21/bin/javac','-d',d,*sources,str(ROOT/'app/src/main/java/dev/rikumi/flymemod/ScopeRestartPlan.java')],check=True)
 command=subprocess.check_output(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',d,'dev.rikumi.flymemod.PlanTest'],text=True)
 subprocess.run(['/bin/sh','-n'],input=command,text=True,check=True)
 assert 'com.missing' not in command and 'invalid;command' not in command
 records={101:'com.google.android.gms',102:'com.google.android.gms.ui',103:'com.google.android.gms:worker',104:'shared.custom',105:'com.meizu.flyme.sdkstage',106:'com.google.android.gms.fake',107:'system_server',108:'com.other',109:'com.google.android.gms:gone'}
 for pid,name in records.items():
  path=base/'proc'/str(pid)/'cmdline';path.parent.mkdir(parents=True);path.write_bytes((('different.process' if pid==109 else name)+'\0ignored').encode())
 snapshot='PID NAME\n'+''.join(f'{pid} {name}\n' for pid,name in records.items())
 (base/'snapshot').write_text(snapshot)
 # Replace only signal and timing operations with recorders. No su/device command is executed.
 simulation=command.replace('/proc/',str(base/'proc')+'/').replace('kill -TERM','record -TERM').replace('kill -0','record -0').replace('sleep 2',':')
 prelude=f"ps() {{ cat '{base}/snapshot'; }}\nrecord() {{ printf '%s %s\\n' \"$1\" \"$2\" >> '{base}/signals'; }}\n"
 subprocess.run(['/bin/sh'],input=prelude+simulation,text=True,check=True)
 signals=(base/'signals').read_text().splitlines()
 assert signals==['-TERM 101','-TERM 103','-TERM 104','-TERM 105'],signals
 # A later click re-reads the declaration; absent packages and no running target are successful no-ops.
 resource.write_text('com.missing\n')
 empty=subprocess.check_output(['/opt/homebrew/opt/openjdk@21/bin/java','-cp',d,'dev.rikumi.flymemod.PlanTest'],text=True)
 assert "-v names=''" in empty and 'com.google.android.gms' not in empty
 subprocess.run(['/bin/sh'],input=prelude+empty.replace('kill -TERM','record -TERM').replace('kill -0','record -0'),text=True,check=True)
 assert (base/'signals').read_text().splitlines()==signals
 menu=(ROOT/'app/src/main/java/dev/rikumi/flymemod/RestartMenu.kt').read_text()
 assert 'scopeRestart = true' in menu and 'ScopeRestartPlan.command(applicationContext)' in menu and 'pidof com.android.systemui' not in menu
 print('PASS: declaration reread, installed filtering, component names, exact main/subprocess matching, no prefix collision, PID reuse guard, system_server excluded, absent/inactive no-op, shell syntax and click-only wiring')
