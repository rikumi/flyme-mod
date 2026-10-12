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
    assert "return false;" in hook
    assert "firstAxis==FIRST_MOVE_VERTICAL" in system_hook
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
