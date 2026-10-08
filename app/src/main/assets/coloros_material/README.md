# ColorOS AGSL bridge

The shader algorithms are extracted from the reference ColorOS SystemUI sources in coloros-mod/sysui_src:

- com/oplus/posteffect/agsl/BlurDrawableShaderStrokeStringKt.java: gradientStrokeColorWithRatio and native fade calculations.
- com/oplus/posteffect/agsl/BlurDrawableShaderCornerStringKt.java: RBox and G2 sdSquircle.
- com/oplus/posteffect/agsl/BlurDrawableShaderBlendStringKt.java: luminosity, overlay and colorDodgeBlend.
- com/oplusos/systemui/common/util/GradientStrokeLineAdapter.java: light/night QS presets, 2dp stroke.
- com/oplusos/systemui/common/util/QSBlurConfigProvider.java and NotifiAndQsPlatformBlurExKt.java: foreground/background mix colors.

Entry points and uniform binding are adapted for Flyme 12.6.0.0A SystemUI 16260625. Background material samples Flyme's existing blurred wallpaper BitmapShader with the original center-crop and view transform; it does not filter foreground icons or labels. These are native ColorOS blend operations, not a fabricated saturation/contrast filter. Native progress rendering on SeekBars remains intact; they receive the contour overlay only. Ripple rendering and fallback when no wallpaper bitmap is ready are preserved. Options default to disabled. Shader creation and draw failures log through the module and restore native rendering.
