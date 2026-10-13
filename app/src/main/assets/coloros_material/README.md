# ColorOS contour shaders on Flyme

Native shader math: ColorOS BlurDrawableShaderStrokeStringKt and BlurDrawableShaderCornerStringKt. GradientStrokeLineAdapter supplies the QS stroke templates.

Flyme 12.6.0.0A / SystemUI 16260625: contours use view-local RuntimeShader overlays. The native perimeter ratio is adjusted per aspect ratio to center the peaks at top and bottom. The hooks do not replace backgrounds, alter background alpha, draw compositor effects, or change native icons/labels.

The experimental backdrop implementation was withdrawn after device feedback reported that only the blurred background remained. FlymeEffectDrawable's compositor shader binding and scope were not verified. Its creation, drawing, background wrappers and settings entry have all been removed. Persisted coloros_control_center_material preferences are ignored; other preferences remain intact. material.agsl is retained solely as a reference for the ColorOS luminosity/overlay/color-dodge algorithms and is not loaded by any hook.

## ColorOS 17 bottom inner light

Reference: user-provided UX Design 17.0.18 (`com.oplus.uxdesign`, versionCode 17000018). Actual call path: `RoundFrameLayout.q` -> `COUIShadowEdgeDrawable` -> `shadowedge.c.v/u` -> `a5.e/a5.b` -> `ShadowEdge.blendShadowColor/blendEdge`.

The view selects ShadowStyle 2: white alpha 0.6, fade distance 86, reference size 600x600, scale 1.1x1.07, vertical offset 10, exponent 4 and nonlinear blend 0.7. Units are density * 0.34. The reference-size padding remains fixed in pixels as the target shape changes size. Night mode scales inner-light alpha to 20% and edge alpha to 40%, and selects PLUS blending. Style arrays themselves have identical day/night values; the effective difference is supplied by the caller.

Flyme adaptation retains native shape/alpha clipping and its fine contour. It reflects the offset vertically and masks the upper half to implement the requested bottom-only light; no independent horizontal center fade is added. Alpha combines additively and clamps to one. The requested overlay peaks are 0.15 in light mode and 0.06 at night. The current user-selected ratio overrides the reference's 20% night multiplier. The bottom restriction and reflected offset remain module adaptations, not source defaults. The existing native masks retain interior coverage with red-channel edge distance encoding. The reference's alpha noise/soft-light dithering is retained. SystemUI-specific templates remain unverified.
