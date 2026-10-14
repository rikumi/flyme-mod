"""Check Flyme Bluetooth subtitle repair targets against the reference DEX."""
from pathlib import Path
import struct
import zipfile
methods = {
'Lcom/android/systemui/statusbar/policy/BluetoothControllerImpl;': {('getDevices', '()Ljava/util/Collection;'), ('getConnectedDevices', '()Ljava/util/List;'), ('onConnectionStatusFetched', '(Lcom/android/systemui/statusbar/policy/bluetooth/ConnectionStatusModel;)V')},
'Lcom/android/settingslib/bluetooth/CachedBluetoothDevice;': {('isConnected', '()Z'), ('getName', '()Ljava/lang/String;')},
'Lcom/flyme/systemui/qs/tiles/ConnectivityTile;': {('updateBluetoothState', '(Lcom/android/systemui/plugins/qs/QSTile$BooleanState;Ljava/lang/Object;)V')},
'Lcom/android/systemui/qs/tiles/BluetoothTile;': {('mzHandleUpdateState', '(Lcom/android/systemui/plugins/qs/QSTile$BooleanState;Ljava/lang/Object;)V')},
}
fields = {'Lcom/android/systemui/statusbar/policy/BluetoothControllerImpl;': {('mConnectionState', 'I'), ('mHandler', 'Lcom/android/systemui/statusbar/policy/BluetoothControllerImpl$H;')}}
found = set()
count = 0
with zipfile.ZipFile(Path('reference-apks/com.android.systemui.apk')) as apk:
    for name in apk.namelist():
        if not name.endswith('.dex'):
            continue
        data = apk.read(name)

        def u32(offset):
            return struct.unpack_from('<I', data, offset)[0]

        def leb(offset):
            value = shift = 0
            while True:
                byte = data[offset]
                offset += 1
                value |= (byte & 127) << shift
                shift += 7
                if byte < 128:
                    return value, offset

        strings = []
        for i in range(u32(56)):
            offset = u32(u32(60) + i * 4)
            _, offset = leb(offset)
            strings.append(data[offset:data.index(0, offset)].decode('utf-8', 'replace'))
        types = [strings[u32(u32(68) + i * 4)] for i in range(u32(64))]

        def prototype(index):
            offset = u32(76) + index * 12
            args = u32(offset + 8)
            params = [] if not args else [
                types[struct.unpack_from('<H', data, args + 4 + j * 2)[0]] for j in range(u32(args))]
            return '(' + ''.join(params) + ')' + types[u32(offset + 4)]

        dex_methods = []
        for i in range(u32(88)):
            owner, proto, name_index = struct.unpack_from('<HHI', data, u32(92) + i * 8)
            dex_methods.append((strings[name_index], prototype(proto)))
        dex_fields = []
        for i in range(u32(80)):
            owner, type_index, name_index = struct.unpack_from('<HHI', data, u32(84) + i * 8)
            dex_fields.append((strings[name_index], types[type_index]))
        for i in range(u32(96)):
            offset = u32(100) + i * 32
            owner = types[u32(offset)]
            if owner not in methods:
                continue
            found.add(owner)
            position = u32(offset + 24)
            counts = []
            for _ in range(4):
                size, position = leb(position)
                counts.append(size)
            declared_fields, declared_methods = set(), set()
            for group, size in enumerate(counts):
                index = 0
                for _ in range(size):
                    delta, position = leb(position)
                    index += delta
                    _, position = leb(position)
                    if group < 2:
                        declared_fields.add(dex_fields[index])
                    else:
                        _, position = leb(position)
                        declared_methods.add(dex_methods[index])
            assert methods[owner] <= declared_methods, (owner, methods[owner] - declared_methods)
            assert fields.get(owner, set()) <= declared_fields, (owner, fields[owner] - declared_fields)
            count += len(methods[owner]) + len(fields.get(owner, set()))
assert found == set(methods), set(methods) - found
print(f'PASS: {count} exact reference DEX method/constructor/field declarations')
