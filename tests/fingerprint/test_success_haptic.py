"""Compile the real hook against reduced authentication fixtures."""
from pathlib import Path
import importlib.util, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('back_hooks',ROOT/'tests/back-gesture/test_hooks.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
sources={k:m.SOURCES[k] for k in ['dev/rikumi/flymemod/SignalHooks.java','dev/rikumi/flymemod/HookRuntime.java']}
sources.update({
'android/content/SharedPreferences.java':'package android.content; public interface SharedPreferences { boolean getBoolean(String k,boolean d); }',
'android/content/Context.java':'package android.content; public class Context { public android.os.Vibrator vibrator=new android.os.Vibrator(); public <T> T getSystemService(Class<T> t){return t.cast(vibrator);} }',
'android/os/VibrationAttributes.java':'package android.os; public class VibrationAttributes { public static int USAGE_HARDWARE_FEEDBACK=50; public int usage; public static class Builder { int usage; public Builder setUsage(int u){usage=u;return this;} public VibrationAttributes build(){VibrationAttributes a=new VibrationAttributes();a.usage=usage;return a;} } }',
'android/os/VibrationEffect.java':'package android.os; public class VibrationEffect { public long duration; public int amplitude,id; public static VibrationEffect get(int id){VibrationEffect e=new VibrationEffect();e.id=id;return e;} public static VibrationEffect createOneShot(long d,int a){VibrationEffect e=new VibrationEffect();e.duration=d;e.amplitude=a;return e;} }',
'android/os/Vibrator.java':'package android.os; public class Vibrator { public int count; public boolean fail,available=true; public VibrationEffect effect; public VibrationAttributes attributes; public boolean hasVibrator(){return available;} public void vibrate(VibrationEffect e,VibrationAttributes a){if(fail)throw new IllegalStateException();count++;effect=e;attributes=a;} }',
'android/hardware/biometrics/BiometricAuthenticator.java':'package android.hardware.biometrics; public class BiometricAuthenticator { public static class Identifier {} }',
'dev/rikumi/flymemod/ModuleSettings.java':'package dev.rikumi.flymemod; class ModuleSettings { static String FINGERPRINT_SUCCESS_HAPTIC="feedback"; }',
'com/android/server/biometrics/sensors/fingerprint/aidl/FingerprintAuthenticationClient.java':'''package com.android.server.biometrics.sensors.fingerprint.aidl;
import java.util.ArrayList;import android.hardware.biometrics.BiometricAuthenticator.Identifier;import dev.rikumi.flymemod.HookRuntime;
public class FingerprintAuthenticationClient { public android.content.Context context=new android.content.Context(); public boolean accepted=true,success;public int delivered;
public android.content.Context getContext(){return context;}public boolean wasAuthSuccessful(){return success;}
public void onAuthenticated(Identifier id,boolean ok,ArrayList<Byte> token){HookRuntime.call(this,getClass(),"onAuthenticated",new Object[]{id,ok,token},()->{success=ok&&accepted;delivered++;return null;});} }
''',
'dev/rikumi/flymemod/Test.java':'''package dev.rikumi.flymemod;
import com.android.server.biometrics.sensors.fingerprint.aidl.FingerprintAuthenticationClient;import java.util.ArrayList;
public class Test {static boolean enabled;static int checks;static void check(boolean v){checks++;if(!v)throw new AssertionError();}
public static void main(String[] a)throws Exception {
FingerprintSuccessHapticHooks.install(Test.class.getClassLoader(),(k,d)->enabled,HookRuntime::install,m->{},(s,t)->{});
FingerprintAuthenticationClient c=new FingerprintAuthenticationClient();c.onAuthenticated(null,true,new ArrayList<>());check(c.context.vibrator.count==0&&c.delivered==1);
enabled=true;c.onAuthenticated(null,false,new ArrayList<>());check(c.context.vibrator.count==0);
c.accepted=false;c.onAuthenticated(null,true,new ArrayList<>());check(c.context.vibrator.count==0);
c.accepted=true;c.onAuthenticated(null,true,new ArrayList<>());check(c.context.vibrator.count==1&&c.success);
check(c.context.vibrator.effect.id==31025&&c.context.vibrator.effect.duration==0);check(c.context.vibrator.attributes.usage==50);
c.context.vibrator.fail=true;c.onAuthenticated(null,true,new ArrayList<>());check(c.success&&c.delivered==5);
c.context.vibrator.fail=false;c.context.vibrator.available=false;c.onAuthenticated(null,true,new ArrayList<>());check(c.context.vibrator.count==1);
enabled=false;c.context.vibrator.available=true;c.onAuthenticated(null,true,new ArrayList<>());check(c.context.vibrator.count==1);
System.out.println("PASS: "+checks+" fingerprint success feedback checks");} }
''',
})
with tempfile.TemporaryDirectory() as folder:
 base=Path(folder);paths=[]
 for name,source in sources.items():
  f=base/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(source);paths.append(str(f))
 paths.append(str(ROOT/'app/src/main/java/dev/rikumi/flymemod/FingerprintSuccessHapticHooks.java'))
 subprocess.run([str(m.JAVA/'javac'),'-d',str(base/'classes'),*paths],check=True)
 subprocess.run([str(m.JAVA/'java'),'-cp',str(base/'classes'),'dev.rikumi.flymemod.Test'],check=True)
