from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
HOOK = ROOT / "app/src/main/java/dev/rikumi/flymemod/ZygoteRestartBootHooks.java"


def test_boot_notification():
    sources = {
        "android/os/SystemProperties.java": """package android.os;
public class SystemProperties {
    public static boolean booted;
    public static boolean getBoolean(String key, boolean fallback) {
        if (!key.equals("sys.boot_completed")) throw new AssertionError(key);
        return booted;
    }
}""",
        "com/android/server/pm/StagingManager.java": """package com.android.server.pm;
import java.util.ArrayList;
public class StagingManager {
    private final ArrayList<Integer> mSuccessfulStagedSessionIds = new ArrayList<>();
    public void markBootCompleted() {}
    public void markStagedSessionsAsSuccessful() {}
    public void addSession() { mSuccessfulStagedSessionIds.add(1); }
    public static class Lifecycle { public void onBootPhase(int phase) {} }
}""",
        "dev/rikumi/flymemod/SignalHooks.java": """package dev.rikumi.flymemod;
class SignalHooks {
    interface Chain { Object getThisObject(); Object proceed(); }
    interface Hooker { Object run(Chain chain) throws Throwable; }
    interface Installer { void hook(String type, String method, Hooker hook, Class<?>... args); }
}""",
        "dev/rikumi/flymemod/BootNotificationTest.java": """package dev.rikumi.flymemod;
import android.os.SystemProperties;
import com.android.server.pm.StagingManager;
public class BootNotificationTest {
    static SignalHooks.Hooker installed;
    static int calls;
    static boolean enabled;
    public static void main(String[] args) throws Throwable {
        ClassLoader loader = BootNotificationTest.class.getClassLoader();
        SignalHooks.Installer installer = (type, method, hook, parameters) -> installed = hook;
        SystemProperties.booted = false;
        ZygoteRestartBootHooks.install(loader, installer, () -> enabled, m -> {}, (s, e) -> {});
        if (installed != null) throw new AssertionError("real boot must remain native");
        SystemProperties.booted = true;
        ZygoteRestartBootHooks.install(loader, installer, () -> enabled, m -> {}, (s, e) -> {});
        if (installed == null) throw new AssertionError("warm restart hook missing");
        StagingManager owner = new StagingManager();
        SignalHooks.Chain chain = new SignalHooks.Chain() {
            public Object getThisObject() { return owner; }
            public Object proceed() { calls++; return null; }
        };
        installed.run(chain);
        if (calls != 1) throw new AssertionError("disabled switch must preserve native behavior");
        calls = 0;
        enabled = true;
        installed.run(chain);
        if (calls != 0) throw new AssertionError("duplicate notification must be skipped");
        owner.addSession();
        installed.run(chain);
        if (calls != 1) throw new AssertionError("pending staged sessions must remain native");
        System.out.println("PASS: real boot, warm restart and staged-session preservation");
    }
}""",
    }
    with tempfile.TemporaryDirectory() as temp:
        directory = Path(temp)
        files = [str(HOOK)]
        for relative, source in sources.items():
            target = directory / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(source)
            files.append(str(target))
        subprocess.run(["javac", "-d", temp, *files], check=True)
        subprocess.run(["java", "-cp", temp, "dev.rikumi.flymemod.BootNotificationTest"], check=True)


if __name__ == "__main__":
    test_boot_notification()
