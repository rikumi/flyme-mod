from pathlib import Path
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "app/src/main/java/dev/rikumi/flymemod/BackGestureDirectionPolicy.java"
TEST = ROOT / "tests/back-gesture/BackGestureDirectionPolicyTest.java"


def test_first_move_axis_policy():
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
