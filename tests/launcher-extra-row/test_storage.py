"""Exercise the SQL snapshot against a live WAL and independent layout edits."""
from pathlib import Path
import hashlib
import sqlite3
import tempfile

source_code = Path('app/src/main/java/dev/rikumi/flymemod/LauncherExtraRowStorage.java').read_text()
assert 'VACUUM seed INTO ?' in source_code
assert 'Files.copy(source' not in source_code
with tempfile.TemporaryDirectory() as folder:
    root = Path(folder)
    original = root / 'launcher_6_by_4.db'
    expanded = root / 'expanded.db'
    native = sqlite3.connect(original)
    native.execute('PRAGMA journal_mode=WAL')
    native.execute('PRAGMA wal_autocheckpoint=0')
    native.execute('PRAGMA user_version=32')
    native.execute('CREATE TABLE favorites (_id INTEGER PRIMARY KEY AUTOINCREMENT, screen INT, cellX INT, cellY INT, spanY INT, title TEXT)')
    native.execute('CREATE INDEX positions ON favorites(screen,cellY,cellX)')
    native.execute('CREATE TABLE changes (id INTEGER)')
    native.execute('CREATE TRIGGER changed AFTER UPDATE ON favorites BEGIN INSERT INTO changes VALUES(new._id); END')
    rows = [(page, x, y, 1, f'{page}:{x}:{y}') for page in [0, 3, 17] for x in range(4) for y in range(6) if (x+y)%4]
    native.executemany('INSERT INTO favorites(screen,cellX,cellY,spanY,title) VALUES(?,?,?,?,?)', rows)
    native.execute("INSERT INTO favorites(_id,screen,cellX,cellY,spanY,title) VALUES(999,18,0,0,1,'deleted')")
    native.execute('DELETE FROM favorites WHERE _id=999')
    native.commit()
    def hashes():
        return {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [original, Path(str(original)+'-wal')]}
    before = hashes()
    connection = sqlite3.connect(':memory:', isolation_level=None)
    connection.execute('ATTACH DATABASE ? AS seed', (str(original),))
    connection.execute('VACUUM seed INTO ?', (str(expanded),))
    connection.close()
    extra = sqlite3.connect(expanded)
    assert extra.execute('PRAGMA user_version').fetchone() == (32,)
    assert extra.execute('SELECT * FROM favorites').fetchall() == native.execute('SELECT * FROM favorites').fetchall()
    assert extra.execute('SELECT * FROM sqlite_sequence').fetchall() == native.execute('SELECT * FROM sqlite_sequence').fetchall()
    # The newly available row starts empty; gaps, page IDs and coordinates remain unchanged.
    assert extra.execute('SELECT COUNT(*) FROM favorites WHERE cellY=6').fetchone() == (0,)
    extra.execute("INSERT INTO favorites(screen,cellX,cellY,spanY,title) VALUES(3,0,6,1,'extra row')")
    extra.execute('UPDATE favorites SET cellY=5,spanY=2 WHERE _id=1')
    extra.commit()
    assert native.execute('SELECT cellY,spanY FROM favorites WHERE _id=1').fetchone() != (5,2)
    assert extra.execute('SELECT * FROM changes').fetchall() == [(1,)]
    assert before == hashes(), 'source database or WAL changed'
    extra.close()
    native.execute("UPDATE favorites SET title='native only' WHERE _id=2")
    native.commit()
    # Reopening is not another initialization: independent edits survive both directions.
    extra = sqlite3.connect(expanded)
    assert extra.execute('SELECT title FROM favorites WHERE _id=2').fetchone() != ('native only',)
    assert extra.execute("SELECT COUNT(*) FROM favorites WHERE title='extra row'").fetchone() == (1,)
    extra.close()
    native.close()
print('PASS: WAL snapshot, schema/version/sequences, empty extra row, independent writes and reopen')
