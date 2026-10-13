"""Exercise production projection math and check both live-window submission paths."""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = root / 'app/src/main/java/dev/rikumi/flymemod'
hooks = (source / 'StackedRecentsHooks.java').read_text()
# Keep the simulator callback and its inlined/deferred setter fallback protected.
for call in ('synchronizeTransitionPose(parent, task, card);', 'synchronizeTransitionPose(view, task, card);'):
    assert call in hooks
assert 'boolean controlled = controlsTranslation(state, task);' in hooks
assert 'boolean controlled = controlsTranslation(state, view);' in hooks
assert 'if (entering) writeEntryPose(view, child, card, state, pose);' in hooks
assert 'if (surface.builderCorrecting)' in hooks
assert 'deferred ? new Matrix(surface.submitMatrix)' in hooks
jdk = Path('/opt/homebrew/opt/openjdk@21/bin')
with tempfile.TemporaryDirectory(prefix='flyme-stack-entry-', dir='/private/tmp') as folder:
    subprocess.run([str(jdk / 'javac'), '-d', folder, str(source / 'StackedRecentsGeometry.java'),
                    str(Path(__file__).with_name('EntryPoseTest.java'))], check=True)
    subprocess.run([str(jdk / 'java'), '-cp', folder, 'dev.rikumi.flymemod.EntryPoseTest'], check=True)
print('PASS: entry rendering, both translation writer paths and both surface submission paths are connected')
