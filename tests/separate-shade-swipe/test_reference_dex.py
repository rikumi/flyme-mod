"""Verify exact native declarations used for separate shade switching."""
from pathlib import Path
import struct
import zipfile
methods = {'Lcom/android/systemui/shade/NotificationPanelViewController$TouchHandler;': {
 ('onInterceptTouchEvent', '(Landroid/view/MotionEvent;)Z'),
 ('onTouch', '(Landroid/view/View;Landroid/view/MotionEvent;)Z')},
 'Landroidx/dynamicanimation/animation/DynamicAnimation;': {('cancel', '()V')},
 'Landroidx/recyclerview/widget/RecyclerView;': {('getEdgeEffectFactory',
                                                  '()Landroidx/recyclerview/widget/RecyclerView$EdgeEffectFactory;')},
 'Landroidx/viewpager2/widget/ViewPager2;': {('getScrollState', '()I')},
 'Lcom/android/systemui/Dependency;': {('get', '(Ljava/lang/Class;)Ljava/lang/Object;')},
 'Lcom/android/systemui/scrim/ScrimView;': {('setViewAlpha', '(F)V')},
 'Lcom/android/systemui/shade/NotificationPanelViewController;': {('abortAnimations', '()V'),
                                                                  ('canExpandCenterControlPanel', '(Landroid/view/MotionEvent;)Z'),
                                                                  ('dispatchEventToControlCenter', '(Landroid/view/MotionEvent;)Z'),
                                                                  ('expand', '(Z)V'),
                                                                  ('instantCollapsePanel', '()V'),
                                                                  ('isKeyguardShowing', '()Z'),
                                                                  ('onFinishInflate', '()V'),
                                                                  ('setAnimationAlpha', '(F)V'),
                                                                  ('setBlurRadius', '(F)V'),
                                                                  ('setBlurScaleFactor', '(F)V'),
                                                                  ('setExpandedFraction', '(F)V'),
                                                                  ('showBackgroundBlurDimLayer', '(F)V'),
                                                                  ('startExpandAnimator', '()V'),
                                                                  ('updateHeightAnimation', '(F)V')},
 'Lcom/android/systemui/shade/NotificationShadeWindowView;': {('dispatchTouchEvent',
                                                               '(Landroid/view/MotionEvent;)Z'),
                                                              ('onDetachedFromWindow', '()V')},
 'Lcom/android/systemui/shade/domain/interactor/BaseShadeInteractor;': {('isClassicsMode',
                                                                         '()Lkotlinx/coroutines/flow/StateFlow;')},
 'Lcom/flyme/systemui/controlcenter/domain/interactor/ControlCenterInteractor;': {('setBlurRadiusFactor',
                                                                                   '(F)V'),
                                                                                  ('setBlurScaleFactor',
                                                                                   '(F)V')},
 'Lcom/flyme/systemui/controlcenter/phone/CenterController;': {('abortAnimations', '()V'),
                                                               ('expandPanel', '(Z)V'),
                                                               ('instantCollapse', '()V'),
                                                               ('setAnimationAlpha', '(F)V'),
                                                               ('setAnimationScale', '(F)V'),
                                                               ('setBlurRadius', '(F)V'),
                                                               ('setBlurScaleFactor', '(F)V'),
                                                               ('setExpandedFraction', '(F)V'),
                                                               ('startExpandAnimator', '()V'),
                                                               ('updateHeightAnimation', '(F)V')},
 'Lcom/flyme/systemui/controlcenter/qs/PagedUnifiedTileLayout$2$1$1;': set(),
 'Lcom/flyme/systemui/controlcenter/qs/PagedUnifiedTileLayout;': set(),
 'Lcom/flyme/systemui/controlcenter/state/QSPanelStateInteractor;': {('isEditing',
                                                                      '()Lkotlinx/coroutines/flow/StateFlow;')},
 'Lcom/flyme/systemui/controlcenter/ui/binder/MzRootContainerBlurBinder$bind$1$1;': {('emit',
                                                                                      '(Lcom/flyme/systemui/controlcenter/domain/interactor/ControlCenterBlurInteractor$BlurState;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;')},
 'Lcom/flyme/systemui/controlcenter/ui/binder/MzRootContainerBlurBinder;': {('applyBlurState',
                                                                             '(Lcom/flyme/systemui/controlcenter/domain/interactor/ControlCenterBlurInteractor$BlurState;)V'),
                                                                            ('getBlurState',
                                                                             '()Lkotlinx/coroutines/flow/StateFlow;')},
 'Lcom/flyme/systemui/controls/domain/interactor/DeviceCenterInteractor;': {('isShowingDeviceCenter', '()Z')},
 'Lcom/flyme/systemui/keyguard/ui/view/ClockSurfaceContainer;': {('getContentContainer',
                                                                  '()Landroid/widget/FrameLayout;')},
 'Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager$setNotificationShadeWindowView$4$1;': {('emit',
                                                                                                   '(FLkotlin/coroutines/Continuation;)Ljava/lang/Object;')},
 'Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager;': {('setKeyguardBackgroundFrameVisibility',
                                                                '(Z)V'),
                                                               ('setWallpaperAlpha', '(F)V'),
                                                               ('updatePanelWallPaperVisibility', '(ZZ)V')},
 'Lkotlinx/coroutines/flow/StateFlow;': {('getValue', '()Ljava/lang/Object;')}}
fields = {'Lcom/android/systemui/shade/NotificationPanelViewController;': {('mBackgroundSurface',
                                                                   'Landroid/view/SurfaceControl;'),
                                                                  ('mBarState', 'I'),
                                                                  ('mBlurRadiusSpring',
                                                                   'Landroidx/dynamicanimation/animation/SpringAnimation;'),
                                                                  ('mBlurScaleSpring',
                                                                   'Landroidx/dynamicanimation/animation/SpringAnimation;'),
                                                                  ('mCenterController',
                                                                   'Lcom/flyme/systemui/controlcenter/phone/CenterController;'),
                                                                  ('mCollapseAnimatorSet',
                                                                   'Landroid/animation/AnimatorSet;'),
                                                                  ('mCurrentBlurRadius', 'F'),
                                                                  ('mCurrentBlurScale', 'F'),
                                                                  ('mDimColor', 'I'),
                                                                  ('mExpandAnimatorSet',
                                                                   'Landroid/animation/AnimatorSet;'),
                                                                  ('mExpandedFraction', 'F'),
                                                                  ('mInstantExpanding', 'Z'),
                                                                  ('mShadeInteractor',
                                                                   'Lcom/android/systemui/shade/domain/interactor/ShadeInteractor;'),
                                                                  ('mSharedNotificationContainer',
                                                                   'Lcom/android/systemui/statusbar/notification/stack/ui/view/SharedNotificationContainer;'),
                                                                  ('mView',
                                                                   'Lcom/android/systemui/shade/NotificationPanelView;'),
                                                                  ('sBlur_RADIUS_MAX', 'I')},
 'Lcom/flyme/systemui/controlcenter/phone/CenterController;': {('mBehindView',
                                                                'Lcom/android/systemui/scrim/ScrimView;'),
                                                               ('mBlurRadiusSpring',
                                                                'Landroidx/dynamicanimation/animation/SpringAnimation;'),
                                                               ('mBlurScaleSpring',
                                                                'Landroidx/dynamicanimation/animation/SpringAnimation;'),
                                                               ('mCenterContent',
                                                                'Landroid/widget/FrameLayout;'),
                                                               ('mCollapseAnimatorSet',
                                                                'Landroid/animation/AnimatorSet;'),
                                                               ('mControlCenterInteractor',
                                                                'Lcom/flyme/systemui/controlcenter/domain/interactor/ControlCenterInteractor;'),
                                                               ('mControlCenterPanelView',
                                                                'Lcom/flyme/systemui/controlcenter/phone/ControlCenterPanelView;'),
                                                               ('mCurrentBlurRadius', 'F'),
                                                               ('mCurrentBlurScale', 'F'),
                                                               ('mDeviceCenterInteractor',
                                                                'Lcom/flyme/systemui/controls/domain/interactor/DeviceCenterInteractor;'),
                                                               ('mExpandAnimatorSet',
                                                                'Landroid/animation/AnimatorSet;'),
                                                               ('mExpandedFraction', 'F'),
                                                               ('mInstantExpanding', 'Z'),
                                                               ('mMzRootContainerBlurBinder',
                                                                'Lcom/flyme/systemui/controlcenter/ui/binder/MzRootContainerBlurBinder;'),
                                                               ('mQSPanelStateInteractor',
                                                                'Lcom/flyme/systemui/controlcenter/state/QSPanelStateInteractor;'),
                                                               ('mQsDisabled', 'Z'),
                                                               ('sBLUR_RADIUS_MAX', 'I')},
 'Lcom/flyme/systemui/controlcenter/qs/PagedUnifiedTileLayout$2$1$1;': {('$spring',
                                                                         'Landroidx/dynamicanimation/animation/SpringAnimation;')},
 'Lcom/flyme/systemui/controlcenter/qs/PagedUnifiedTileLayout;': {('mCurrentPage', 'I'),
                                                                  ('mIsDragging', 'Z'),
                                                                  ('mIsSwitching', 'Z'),
                                                                  ('mRecyclerView',
                                                                   'Landroidx/recyclerview/widget/RecyclerView;'),
                                                                  ('mViewPager',
                                                                   'Landroidx/viewpager2/widget/ViewPager2;')},
 'Lcom/flyme/systemui/keyguard/ui/view/ClockSurfaceContainer;': {('mContentContainer',
                                                                  'Landroid/widget/FrameLayout;'),
                                                                 ('mSurfaceView',
                                                                  'Landroid/view/SurfaceView;')},
 'Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager;': {('foregroundShadeView', 'Landroid/view/View;'),
                                                               ('keyguardBackgroundFrame',
                                                                'Landroid/widget/FrameLayout;'),
                                                               ('surfaceControl',
                                                                'Landroid/view/SurfaceControl;'),
                                                               ('variedpage', 'Landroid/widget/ImageView;'),
                                                               ('wallpaperBlurBg', 'Landroid/view/View;'),
                                                               ('wallpaperSurfaceAlpha',
                                                                'Lkotlinx/coroutines/flow/StateFlow;')}}
methods['Lcom/flyme/systemui/controlcenter/phone/CenterController;'].add(('shouldHideBlurForControlCenter', '()Z'))
methods['Lcom/android/systemui/statusbar/NotificationShadeDepthController;'] = {('onBlurApplied', '(IF)V')}
methods['Lcom/android/systemui/statusbar/BlurUtils;'] = {('getMaxBlurRadius', '()F'), ('applyBlurMZ', '(Landroid/view/ViewRootImpl;IZF)V')}
methods['Lcom/flyme/systemui/controlcenter/qs/UnifiedQSTileView;'] = {('getMDeleteBadge', '()Landroid/widget/ImageView;')}
methods['Lcom/android/systemui/statusbar/notification/row/NotificationBackgroundView;'] = {('onFlymeAlphaChanged', '(Landroid/view/View;)V'), ('updateLiveBlurAlpha', '(I)V'), ('setBlurBgForLive', '(II)V')}
methods['Lcom/flyme/systemui/controlcenter/phone/MzQSPanel;'] = {('setFooterEditModeTopMargin', '(I)V')}
fields['Lcom/android/systemui/shade/NotificationPanelViewController;'].add(('mDepthController', 'Lcom/android/systemui/statusbar/NotificationShadeDepthController;'))
fields['Lcom/android/systemui/statusbar/NotificationShadeDepthController;'] = {('blurUtils', 'Lcom/android/systemui/statusbar/BlurUtils;')}
methods['Lcom/android/systemui/statusbar/phone/PhoneStatusBarView;'] = {('onFinishInflate', '()V')}
methods['Lcom/android/systemui/statusbar/notification/row/ExpandableOutlineView;'] = {('childNeedsClipping', '(Landroid/view/View;)Z'), ('getCustomClipPath', '(Landroid/view/View;)Landroid/graphics/Path;'), ('getClipPath', '(Z)Landroid/graphics/Path;')}
fields['Lcom/flyme/systemui/controlcenter/phone/CenterController;'].add(('mQSStatusBar', 'Lcom/flyme/systemui/controlcenter/qs/QSStatusBar;'))
methods['Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager;'].update({('setWallpapeMirrorViewHeight', '(I)V'), ('setWallpaperBlurBgY', '(FF)V')})
methods['Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager$setNotificationShadeWindowView$5$2;'] = {('emit', '(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;')}
methods['Lcom/flyme/systemui/wallpaper/LockscreenWallpaperManager$setNotificationShadeWindowView$8$2;'] = {('emit', '(Lkotlin/Pair;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;')}
fields['Lcom/android/systemui/shade/NotificationPanelViewController;'].add(('mKeyguardStatusBar', 'Lcom/android/systemui/statusbar/phone/KeyguardStatusBarView;'))
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
