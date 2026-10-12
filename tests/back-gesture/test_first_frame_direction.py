from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main/java/dev/rikumi/flymemod/BackGestureDirectionPolicy.java"
HOOK = ROOT / "app/src/main/java/dev/rikumi/flymemod/BackGestureSmartHooks.java"
SYSTEM_HOOK = ROOT / "app/src/main/java/dev/rikumi/flymemod/BackGestureServiceHooks.java"
TEST = ROOT / "tests/back-gesture/BackGestureDirectionPolicyTest.java"


def test_first_move_axis_policy():
    hook = HOOK.read_text()
    system_hook = SYSTEM_HOOK.read_text()
    assert hook.count("BackGestureDirectionPolicy.isHorizontalFirstMove(") == 2
    assert system_hook.count("BackGestureDirectionPolicy.isHorizontalFirstMove(") == 1
    assert "Boolean.TRUE.equals(handlerFirstMoveHorizontal.get(owner))" in hook
    assert "Boolean.FALSE.equals(viewFirstMoveHorizontal.get(owner))" in hook
    assert 'triggerBack.getReturnType()!=void.class' in hook
    assert 'handler.getMethod("cancelGesture",MotionEvent.class)' in hook
    assert 'installer.hook(view.getName(),"onMotionEvent"' in hook
    assert 'triggerBackFlag.setBoolean(owner,false)' in hook
    assert 'if(dx!=0f || dy!=0f)' in hook
    assert "firstAxis==FIRST_MOVE_VERTICAL" in system_hook
    assert "if(firstAxis==null)return null" in system_hook
    assert "dir.endsWith(\"LEFT\")?dx>0f:dx<0f" in system_hook
    width_hook = (ROOT / "app/src/main/java/dev/rikumi/flymemod/BackGestureWidthHooks.java").read_text()
    assert "removeSmart.getAsBoolean() ? Math.max(left.getInt(owner),right.getInt(owner))" in width_hook
    bridge = (ROOT / "app/src/main/java/dev/rikumi/flymemod/CameraSettingsBridge.java").read_text()
    preset = (ROOT / "app/src/main/java/dev/rikumi/flymemod/OneClickPreset.kt").read_text()
    assert "local.getBoolean(ModuleSettings.BACK_GESTURE_REMOVE_SMART, true)" in bridge
    assert "ModuleSettings.BACK_GESTURE_REMOVE_SMART to true" in preset
    assert 'SystemUI side gesture hooks registered:' in hook
    assert 'System side gesture hooks registered:' in system_hook
    with tempfile.TemporaryDirectory() as output:
        subprocess.run(
            ["javac", "-d", output, str(MAIN), str(TEST)],
            check=True,
        )
        result = subprocess.run(
            ["java", "-cp", output, "dev.rikumi.flymemod.BackGestureDirectionPolicyTest"],
            check=True,
            text=True,
            capture_output=True,
        )
        assert "PASS: first-frame direction policy" in result.stdout


if __name__ == "__main__":
    test_first_move_axis_policy()
