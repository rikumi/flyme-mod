"""Run the actual Kotlin drag/drop/cancel callback bodies with host-side state.

This verifies transaction behavior; pointer dispatch and animation still need a device.
Uses the existing Gradle Kotlin compiler cache, without downloading dependencies.
"""
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
source = (root / 'app/src/main/java/dev/rikumi/flymemod/HiddenAppsScreen.kt').read_text()

def body(marker):
    start = source.index('{', source.index(marker))
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

functions = '\n'.join(header + body(header) for header in (
    'fun cancelDrag()', 'fun insertAtPointer()', 'fun hit(position: Offset): HiddenAppEntry?'))
callbacks = 'fun finish() ' + body('onDragEnd =') + '\nfun cancel() ' + body('onDragCancel =')
# Verify these transaction callbacks are also wired to back, lifecycle and external-list changes.
assert 'BackHandler(enabled = held != null) { cancelDrag() }' in source
assert 'Lifecycle.Event.ON_STOP) { cancelDrag(); menuPackage = null }' in source
assert 'LaunchedEffect(entries) { cancelDrag(); menuPackage = null }' in source

harness = '''package dev.rikumi.flymemod

data class HiddenAppEntry(val pkg: String)
data class Offset(val x: Float, val y: Float)
data class IntSize(val width: Int, val height: Int)
class Pager(var currentPage: Int = 0)
var entries = (0..24).map { HiddenAppEntry("p$it") }
var ordered = entries
var held: HiddenAppEntry? = null
var moved = false
var pointer = Offset(0f, 0f)
var gridSize = IntSize(312, 432)
var gestureVersion = 0
var menuPackage: String? = null
val pages = Pager()
val writes = mutableListOf<List<String>>()
val persist: (List<String>) -> Unit = { writes.add(it) }
''' + functions + '\n' + callbacks + '''
fun main() {
    val baseline = entries
    held = entries[11]; moved = true; pages.currentPage = 1; pointer = Offset(52f, 54f)
    insertAtPointer()
    check(ordered[12].pkg == "p11" && writes.isEmpty())
    cancel() // gesture loss
    check(ordered == baseline && held == null && !moved && writes.isEmpty())
    check(gestureVersion == 1)
    held = entries[0]; moved = true; ordered = HiddenAppOrder.move(entries, 0, 24)
    cancelDrag() // same callback used by BackHandler and ON_STOP
    check(ordered == baseline && writes.isEmpty() && gestureVersion == 2)
    held = entries[4]; moved = false
    finish() // long press, release without movement -> menu only
    check(menuPackage == "p4" && held == null && writes.isEmpty())
    held = entries[11]; moved = true; pages.currentPage = 1; pointer = Offset(52f, 54f)
    finish() // drop across the page boundary
    check(writes.size == 1 && writes.last()[12] == "p11" && !moved && held == null)
    held = entries[12]; moved = true; pages.currentPage = 2; pointer = Offset(300f, 430f)
    finish() // final-page empty cell -> tail
    check(writes.size == 2 && writes.last().last() == "p12")
    entries = entries.filter { it.pkg != "p12" } + HiddenAppEntry("new")
    held = baseline[1]; moved = true
    cancelDrag() // incoming membership replaces an unsaved preview
    check(ordered == entries && ordered.last().pkg == "new" && writes.size == 2)
    cancel() // cancellation while idle neither persists nor restarts a gesture
    check(gestureVersion == 3)
    pages.currentPage = 2
    check(hit(Offset(300f, 430f)) == null)
    println("PASS: actual drag callbacks: preview, gesture cancel, back/stop reset, menu, cross-page drop, tail drop, external update")
}
'''
cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
def jar(group, name, version):
    return next((cache / group / name / version).glob('*/*.jar'))
stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', '2.2.21')
compiler_cp = ':'.join(str(p) for p in (
    jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.2.21'), stdlib,
    jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.2.21'),
    jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
    jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.1'),
    jar('org.jetbrains', 'annotations', '13.0')))
java = '/opt/homebrew/opt/openjdk@21/bin/java'
javac = '/opt/homebrew/opt/openjdk@21/bin/javac'
with tempfile.TemporaryDirectory(prefix='flyme-drag-', dir='/private/tmp') as directory:
    out = Path(directory)
    kt = out / 'DragCallbacks.kt'
    kt.write_text(harness)
    subprocess.run([javac, '-d', directory, str(root / 'app/src/main/java/dev/rikumi/flymemod/HiddenAppOrder.java')], check=True)
    subprocess.run([java, '-cp', compiler_cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-classpath', f'{stdlib}:{directory}',
                    '-d', directory, str(kt)], check=True)
    subprocess.run([java, '-cp', f'{stdlib}:{directory}', 'dev.rikumi.flymemod.DragCallbacksKt'], check=True)
