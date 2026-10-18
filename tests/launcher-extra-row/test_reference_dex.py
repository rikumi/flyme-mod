"""Exact native entry points for the isolated launcher layout (13000000)."""
from pathlib import Path
import struct
import sys
import zipfile
methods = {
'Lcom/android/launcher3/InvariantDeviceProfile;': {
('initGrid', '(Landroid/content/Context;Lcom/android/launcher3/util/DisplayController$Info;Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;)V'),
('applyPartnerDeviceProfileOverrides', '(Landroid/content/Context;Landroid/util/DisplayMetrics;)V')},
'Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;': set(),
'Lcom/android/launcher3/InvariantDeviceProfile$GridOption;': set(),
'Lcom/android/launcher3/DeviceProfile;': set(),
'Lcom/meizu/flyme/launcher/deviceprofile/FlymeDeviceProfile;': {('getWorkspaceGridCellHeight','()I')},
'Lcom/meizu/flyme/launcher/backup/MzLauncherBackupHelper;': {('getLocalBackupPath','()Ljava/lang/String;'),('getLocalRestorePath','()Ljava/lang/String;')},
}
fields = {
'Lcom/android/launcher3/InvariantDeviceProfile;': {('numRows','I')},
'Lcom/android/launcher3/InvariantDeviceProfile$DisplayOption;': {('grid','Lcom/android/launcher3/InvariantDeviceProfile$GridOption;')},
'Lcom/android/launcher3/InvariantDeviceProfile$GridOption;': {('numRows','I')},
'Lcom/android/launcher3/DeviceProfile;': {('inv','Lcom/android/launcher3/InvariantDeviceProfile;')},
'Lcom/meizu/flyme/launcher/deviceprofile/FlymeDeviceProfile;': {('mParentDeviceProfile','Lcom/android/launcher3/DeviceProfile;')},
}
methods.update({
'Lcom/meizu/flyme/launcher/dragndrop/GlobalItemDragListener;': {('processLauncherViewBeforeDragStart','()V')},
'Lcom/meizu/flyme/launcher/dragndrop/LauncherGlobalDragItemInfo;': {('isLauncherSource','()Z'), ('getLauncherDragItemInfo','()Lcom/android/launcher3/model/data/ItemInfo;')},
'Lcom/android/launcher3/widget/PendingAppWidgetHostView;': set(),
'Lcom/android/launcher3/popup/PopupContainerWithArrow;': {('showForWidget','(Lcom/android/launcher3/widget/LauncherAppWidgetHostView;)Lcom/android/launcher3/popup/PopupContainerWithArrow;')},
})
fields.update({
'Lcom/meizu/flyme/launcher/dragndrop/GlobalItemDragListener;': {('mLauncher','Lcom/android/launcher3/Launcher;'),('mSourceDragView','Landroid/view/View;'),('mItem','Lcom/meizu/flyme/launcher/dragndrop/LauncherGlobalDragItemInfo;')},
'Lcom/meizu/flyme/launcher/dragndrop/LauncherGlobalDragItemInfo;': {('mBoundWidget','Landroid/appwidget/AppWidgetHostView;')},
})
apk_path = Path('reference-apks/com.meizu.flyme.launcher.apk')
if len(sys.argv) > 1:
    apk_path = Path(sys.argv[1])
    fields = {}
    methods = {'Lo2/z;': {('v','()Lo2/z;'),('w','()Landroid/content/SharedPreferences;')}}
    for size in ['2x2','4x2']:
        methods[f'Lcom/meizu/assistant/feature/quotes/Quotes{size}WidgetProvider;'] = {
            ('onUpdate','(Landroid/content/Context;Landroid/appwidget/AppWidgetManager;[I)V'),
            ('onAppWidgetOptionsChanged','(Landroid/content/Context;Landroid/appwidget/AppWidgetManager;ILandroid/os/Bundle;)V')}
found = set()
count = 0
with zipfile.ZipFile(apk_path) as apk:
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
