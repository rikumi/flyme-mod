package dev.rikumi.flymemod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.view.Surface
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import kotlin.math.min

@Composable
internal fun BackGesturePreview(visible: Boolean, amount: Int) {
    val root = LocalView.current.rootView
    val configuration = LocalConfiguration.current
    val preview = remember(root, configuration) { BackGestureRegionDrawable(root) }
    SideEffect {
        preview.amount = amount
        preview.invalidateSelf()
    }
    DisposableEffect(root, preview, visible) {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            preview.setBounds(0, 0, root.width, root.height)
            preview.invalidateSelf()
        }
        if (visible) {
            preview.setBounds(0, 0, root.width, root.height)
            root.overlay.add(preview)
            root.addOnLayoutChangeListener(listener)
        }
        onDispose {
            root.overlay.remove(preview)
            root.removeOnLayoutChangeListener(listener)
        }
    }
}

private class BackGestureRegionDrawable(private val root: View) : Drawable() {
    var amount = ModuleSettings.BACK_GESTURE_WIDTH_DEFAULT
    private val paint = Paint().apply { color = Color.argb(72, 32, 108, 255) }
    private val resources = root.resources
    private val density = resources.displayMetrics.density
    private fun dimension(name: String, fallback: Float): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) resources.getDimensionPixelSize(id) else (fallback * density).toInt()
    }
    private val navbar = dimension("navigation_bar_gesture_height", 48f)
    // DeviceConfig is unavailable to ordinary apps on some builds; use the framework default then.
    private fun deviceConfig(name: String, fallback: Float): Float = try {
        Class.forName("android.provider.DeviceConfig")
            .getMethod("getFloat", String::class.java, String::class.java, Float::class.javaPrimitiveType)
            .invoke(null, "systemui", name, fallback) as Float
    } catch (_: ReflectiveOperationException) { fallback }
      catch (_: SecurityException) { fallback }
    private val systemUiBottom = deviceConfig("back_gesture_bottom_height", navbar / density) * density

    @Suppress("DEPRECATION")
    override fun draw(canvas: Canvas) {
        val display = root.display ?: return
        val size = Point()
        display.getRealSize(size)
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val portrait = display.rotation == Surface.ROTATION_0 || display.rotation == Surface.ROTATION_180
        // RecentGestureListener uses one quarter of the portrait height as its upper limit.
        val top = if (portrait) size.y / 4 else 0
        val bottom = min((if (portrait) size.y - (navbar / 2) * 3 else size.y - size.y / 4).toFloat(),
            size.y - systemUiBottom)
        val left = BackGestureWidthHooks.widthPixels(root.context, amount)
        val right = left
        canvas.save()
        canvas.translate(-location[0].toFloat(), -location[1].toFloat())
        canvas.drawRect(0f, top.toFloat(), left.toFloat(), bottom.toFloat(), paint)
        canvas.drawRect((size.x - right).toFloat(), top.toFloat(), size.x.toFloat(), bottom.toFloat(), paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
