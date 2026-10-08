# ColorOS contour shaders on Flyme

Native shader math: ColorOS BlurDrawableShaderStrokeStringKt and BlurDrawableShaderCornerStringKt. GradientStrokeLineAdapter supplies the QS stroke templates.

Flyme 12.6.0.0A / SystemUI 16260625: contours use view-local RuntimeShader overlays. The native perimeter ratio is adjusted per aspect ratio to center the peaks at top and bottom. The hooks do not replace backgrounds, alter background alpha, draw compositor effects, or change native icons/labels.

The experimental backdrop implementation was withdrawn after device feedback reported that only the blurred background remained. FlymeEffectDrawable's compositor shader binding and scope were not verified. Its creation, drawing, background wrappers and settings entry have all been removed. Persisted coloros_control_center_material preferences are ignored; other preferences remain intact. material.agsl is retained solely as a reference for the ColorOS luminosity/overlay/color-dodge algorithms and is not loaded by any hook.
