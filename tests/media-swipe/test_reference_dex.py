"""Verify exact declarations used by the hooks in the checked-in Flyme reference APK."""
from pathlib import Path
import struct
import zipfile

base = 'Lcom/android/systemui/media/controls/ui/view/'
handler = base + 'MediaCarouselScrollHandler;'
scroll = base + 'MediaScrollView;'
event = 'Landroid/view/MotionEvent;'
methods = {
    handler: {
        ('<init>', '(' + scroll + 'Lcom/android/systemui/qs/PageIndicator;'
         'Lcom/android/systemui/util/concurrency/DelayableExecutor;'
         'Lkotlin/jvm/functions/Function0;Lkotlin/jvm/functions/Function0;'
         'Lkotlin/jvm/functions/Function1;Lkotlin/jvm/functions/Function1;'
         'Lcom/android/systemui/plugins/FalsingManager;'
         'Lcom/android/systemui/media/controls/util/MediaUiEventLogger;'
         'Lcom/android/systemui/media/controls/ui/controller/MediaCarouselController;)V'),
        ('setCarouselBounds', '(II)V'), ('setPlayerWidthPlusPadding', '(I)V'),
        ('setScrollingDisabled', '(Z)V'), ('setContentTranslation', '(F)V'),
        ('resetTranslation', '(Z)V'), ('onInterceptTouch', '(' + event + ')Z'),
        ('onTouch', '(' + event + ')Z'), ('onScroll', '(' + event * 2 + 'F)Z'),
        ('onFling', '(FF)Z'),
    },
    scroll: {('setAnimationTargetX', '(F)V'), ('onInterceptTouchEvent', '(' + event + ')Z'),
             ('onTouchEvent', '(' + event + ')Z')},
    base + 'MediaCarouselScrollHandler$gestureListener$1;': {
        ('onScroll', '(' + event * 2 + 'FF)Z'), ('onFling', '(' + event * 2 + 'FF)Z')},
    'Lcom/android/wm/shell/shared/animation/PhysicsAnimator;': {
        ('getInstance', '(Ljava/lang/Object;)Lcom/android/wm/shell/shared/animation/PhysicsAnimator;'),
        ('cancel', '()V')},
}
fields = {handler: {('scrollView', scroll), ('mediaContent', 'Landroid/view/ViewGroup;'),
                    ('scrollingDisabled', 'Z')}}
view = 'Landroid/view/View;'
swipe = 'Lcom/android/systemui/SwipeHelper;'
notification = 'Lcom/android/systemui/statusbar/notification/stack/NotificationSwipeHelper;'
methods[swipe] = {
    ('onTouchEvent', '(' + event + ')Z'), ('onInterceptTouchEvent', '(' + event + ')Z'),
    ('isSwipeDismissible', '()Z'), ('isDismissGesture', '(' + event + ')Z'),
    ('resetSwipeStates', '(Z)V'), ('updateSwipeProgressFromOffset', '(' + view + 'Z)V'),
}
methods[notification] = {
    ('onDownUpdate', '(' + view + event + ')V'), ('onMoveUpdate', '(' + view + event + 'FF)V'),
    ('setTranslation', '(' + view + 'F)V'), ('getTranslation', '(' + view + ')F'),
    ('snapChild', '(' + view + 'FF)V'), ('handleUpEvent', '(' + event + view + 'FF)Z'),
    ('getViewTranslationAnimator', '(' + view + 'FLandroid/animation/ValueAnimator$AnimatorUpdateListener;)Landroid/animation/Animator;'),
}
methods['Lcom/android/systemui/statusbar/notification/stack/NotificationStackScrollLayout;'] = {
    ('canChildBeDismissed', '(' + view + ')Z')}
fields[swipe] = {('mTouchedView', view), ('mIsSwiping', 'Z'), ('mLongPressSent', 'Z'),
                 ('mMenuRowIntercepting', 'Z'), ('mTranslation', 'F'), ('mInitialTouchPos', 'F')}
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
