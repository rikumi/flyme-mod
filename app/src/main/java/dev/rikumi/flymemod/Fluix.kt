package dev.rikumi.flymemod

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalOverscrollFactory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import top.yukonga.miuix.kmp.theme.miuixShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

private val FLUIX_SWITCH_W = 36.dp
private val FLUIX_SWITCH_H = 22.dp
private val FLUIX_SWITCH_RADIUS = 12.dp
private val FLUIX_SWITCH_THUMB_R = 8.dp

// 自定义滑条
private val FLUIX_SLIDER_TRACK_H = 20.dp
private val FLUIX_SLIDER_GAP = 3.dp
private val FLUIX_SLIDER_THUMB_R = 10.dp

// 首页顶部机型横幅: 作为第一张 group 卡片的内容, 底色与渐变中心色随明暗主题取不同值。
private val FLUIX_HEADER_HEIGHT = 200.dp
private val FLUIX_HEADER_BASE_DARK = Color(0xFF34383B)
private val FLUIX_HEADER_GLOW_DARK = Color(0xFFB17666)
private val FLUIX_HEADER_BASE_LIGHT = Color(0xFFDDE2E8)
private val FLUIX_HEADER_GLOW_LIGHT = Color(0xFFDD7963)
private const val FLUIX_HEADER_GLOW_RADIUS_RATIO = 0.7f
// 渐变圆心在横幅底边之下(>1 表示沉到底边外), 使可见范围内只剩光晕上部, 越往上越淡。
private const val FLUIX_HEADER_GLOW_CENTER_Y_RATIO = 1f

private const val FLUIX_HEADER_GLOW_RY_RATIO = 0.85f

private const val FLUIX_HEADER_CORE_RADIUS_RATIO = 8f
private const val FLUIX_HEADER_CORE_RY_RATIO = 0.34f
private const val FLUIX_HEADER_CORE_ALPHA = 0.5f

private const val FLUIX_HEADER_GLOW_LOG_K = 12f
private const val FLUIX_HEADER_GLOW_STOP_COUNT = 32
// 横幅内系统名: 相对文字色的不透明度, 以及贴底时离底边的距离。
private const val FLUIX_HEADER_SYSTEM_ALPHA = 0.9f
private val FLUIX_HEADER_SYSTEM_BOTTOM = 24.dp
// 设备名自横幅正中再上移的距离。
private val FLUIX_HEADER_MODEL_LIFT = 16.dp

// 横幅长按时触发连续振动的阈值(ms): 按住超过阈值后开始连续细微震动, 松手即停。
private const val BANNER_LONG_PRESS_MS = 350L
// 长按期间震动间隔(ms): 越小越频繁。
private const val BANNER_TICK_INTERVAL_MS = 35L
// 单次震动时长(ms)与振幅(0~255): 极短 + 低振幅 = 更细微的连续震感。
private const val BANNER_VIBRATE_MS = 8L
private const val BANNER_VIBRATE_AMPLITUDE = 100

internal val FLUIX_CARD_CORNER = 12.dp

// 分组左右外边距（相对屏幕边缘）。
private val FLUIX_GROUP_HMARGIN = 16.dp

// 卡片下方留白 = 相邻 section 之间的间距（首页卡片→卡片、子页面卡片→下一节小标题）。
private val FLUIX_CARD_BOTTOM_GAP = 16.dp

// 分割线两端距离 group 边界的内缩。
private val FLUIX_DIVIDER_INSET = 16.dp

// 子页面列表项内边距；紧凑行统一控制无图标偏好项、下拉项和并排操作项的高度。
internal val FLUIX_ROW_HPADDING = 20.dp
internal val FLUIX_ROW_VPADDING = 12.dp
internal val FLUIX_ROW_MIN_HEIGHT = 56.dp

// 下拉菜单宽度。
private val FLUIX_DROPDOWN_WIDTH = 200.dp
private val FLUIX_DROPDOWN_TRANSFORM_ORIGIN = TransformOrigin(0.85f, 0f)

private val FLUIX_DROPDOWN_ELEVATION = 8.dp
private val FLUIX_DROPDOWN_CORNER = 16.dp
internal val FLUIX_PAGE_LIGHT = Color(0xFFF5F5F5)
internal val FLUIX_PAGE_DARK = Color(0xFF0B0B0C)
internal val FLUIX_GROUP_LIGHT = Color.White
internal val FLUIX_GROUP_DARK = Color(0xFF1C1C1E)
internal val FLUIX_POPUP_LIGHT = Color.White
internal val FLUIX_POPUP_DARK = Color(0xFF161616)
internal val FLUIX_TEXT_LIGHT = Color.Black
internal val FLUIX_TEXT_DARK = Color(0xFFEEEEEE)
internal val FLUIX_SUMMARY_LIGHT = Color(0x993C3C43)
internal val FLUIX_SUMMARY_DARK = Color(0x73EBEBF5)

// 子页面返回按钮图标尺寸: miuix 图标固有尺寸为 24dp, 此处略微放大。
internal val FLUIX_BACK_ICON = 26.dp

private val FLUIX_TOP_BAR_VPADDING = 8.dp
private val FLUIX_TOP_BAR_END = 16.dp

// 子页面: 返回按钮左侧内缩, 以及返回按钮与标题之间的间距。
private val FLUIX_TOP_BAR_START = 8.dp
private val FLUIX_TOP_BAR_TITLE_GAP = 8.dp

// 首页: 没有返回按钮, 标题自身按此值内缩(只影响水平位置, 不影响高度)。
private val FLUIX_LARGE_TITLE_START = 24.dp

// 分类入口行: 图标尺寸及图标与标题的间距（无底色容器，图标直接绘制）。
private val FLUIX_CATEGORY_ICON = 40.dp
private val FLUIX_CATEGORY_ICON_GAP = 10.dp
private val FLUIX_HOME_CATEGORY_ROW_HPADDING = 12.dp
// 首页分类与启动入口共用行尾边距，使相同尺寸的箭头对齐。
private val FLUIX_HOME_CHEVRON_END_PADDING = FLUIX_HOME_CATEGORY_ROW_HPADDING
private val FLUIX_HOME_CATEGORY_ROW_VPADDING = 10.dp
private val FLUIX_HOME_CATEGORY_ROW_MIN_HEIGHT = 60.dp

// 分类入口行右侧"进入子菜单"箭头尺寸: 明显小于左侧分类图标, 只作指示不抢视觉。
private val FLUIX_CATEGORY_CHEVRON = 16.dp

// 该箭头在 onSurfaceVariantSummary 之上再压低不透明度, 进一步弱化。
private const val FLUIX_CATEGORY_CHEVRON_ALPHA = 0.6f

// 分类入口行右侧副标题与标题/箭头之间的间隔。
private val FLUIX_CATEGORY_SUBTITLE_GAP = 2.dp

// 分类入口行标题左边缘相对卡片左缘的距离: 带图标 item 的分割线从此处起，避开图标。
internal val FLUIX_CATEGORY_TEXT_START = FLUIX_HOME_CATEGORY_ROW_HPADDING + FLUIX_CATEGORY_ICON + FLUIX_CATEGORY_ICON_GAP

fun fluixTextStyles(): TextStyles {
    val base = defaultTextStyles()
    return base.copy(
        body1 = base.body1.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
        body2 = base.body2.copy(fontSize = 14.sp, lineHeight = FLUIX_SUBTITLE_LINE_HEIGHT),
    )
}

// 副标题(body2)行高: 略高于默认行距, 长副标题折行后行与行之间不至于挤在一起。
private val FLUIX_SUBTITLE_LINE_HEIGHT = 18.sp

@Composable
fun FluixSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    BasicText(
        text = text,
        style = androidx.compose.ui.text.TextStyle(
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        ),
        modifier = modifier.padding(start = 34.dp, end = 32.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun fluixTopBarTitleStyle(): TextStyle =
    MiuixTheme.textStyles.title3.copy(
        color = MiuixTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium,
    )

@Composable
private fun FluixTopBarFrame(
    startPadding: Dp,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    start = startPadding,
                    end = FLUIX_TOP_BAR_END,
                    top = FLUIX_TOP_BAR_VPADDING,
                    bottom = FLUIX_TOP_BAR_VPADDING,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
fun FluixLargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    FluixTopBarFrame(
        startPadding = FLUIX_LARGE_TITLE_START,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = fluixTopBarTitleStyle())
            if (!subtitle.isNullOrBlank()) {
                BasicText(
                    text = subtitle,
                    style = TextStyle(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 12.sp,
                    ),
                )
            }
        }
        actions()
    }
}

@Composable
fun FluixStatusBar() {
    val view = LocalView.current
    val surface = MiuixTheme.colorScheme.surface
    // 亮底配深色图标, 暗底配浅色图标: 以感知亮度判定, 不依赖主题模式枚举。
    val lightIcons = surface.luminance() > 0.5f
    DisposableEffect(view, surface, lightIcons) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            window.statusBarColor = surface.toArgb()
            WindowInsetsControllerCompat(window, view).isAppearanceLightStatusBars = lightIcons
        }
        onDispose { }
    }
}

// 回弹位移的上限: 手指拖动拉到该距离后停住(线性阻尼下必须有一个终点, 否则可无限拖动)。
private val FLUIX_OVERSCROLL_MAX = 400.dp

private val FLUIX_OVERSCROLL_FLING_MAX = 50.dp

private const val FLUIX_OVERSCROLL_DRAG_FLING = 0.7f

private const val FLUIX_OVERSCROLL_DRAG = 0.7f

// 松手回弹: 不过冲(DampingRatioNoBouncy), 刚度明显低于系统 MediumLow(400), 回弹过程更慢更柔和。
private const val FLUIX_RELEASE_STIFFNESS = 120f

@Composable
fun FluixOverscrollHost(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalOverscrollFactory provides null) { content() }
}

@Composable
fun Modifier.fluixOverscroll(
    listState: ScrollableState,
    overscrollOffset: MutableState<Float>? = null,
): Modifier {
    val density = LocalDensity.current
    val maxPx = with(density) { FLUIX_OVERSCROLL_MAX.toPx() }
    val flingPx = with(density) { FLUIX_OVERSCROLL_FLING_MAX.toPx() }
    val scope = rememberCoroutineScope()
    val offsetState = overscrollOffset ?: remember { mutableFloatStateOf(0f) }
    var offsetPx by offsetState
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    val connection = remember(listState, offsetState, maxPx, flingPx, scope) {
        object : NestedScrollConnection {

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                if (offsetPx == 0f || delta == 0f) return Offset.Zero
                if (delta * offsetPx > 0f) return Offset.Zero
                releaseJob?.cancel()
                releaseJob = null
                val next = if (offsetPx > 0f) {
                    (offsetPx + delta).coerceAtLeast(0f)
                } else {
                    (offsetPx + delta).coerceAtMost(0f)
                }
                val consumed = next - offsetPx
                offsetPx = next
                return Offset(0f, consumed)
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                val delta = available.y
                if (delta == 0f) return Offset.Zero
                val atEdge = if (delta > 0f) {
                    !listState.canScrollBackward
                } else {
                    !listState.canScrollForward
                }
                if (!atEdge) return Offset.Zero
                releaseJob?.cancel()
                releaseJob = null
                if (source != NestedScrollSource.UserInput) {

                    val step = fluixOverscrollFlingStep(delta, flingPx, offsetPx)
                    offsetPx += step

                    // 半像素没吃完就立刻取消惯性动画, 随后 onPostFling 起弹簧回弹。
                    if (abs(step) < 0.5f) return Offset.Zero
                    return Offset(0f, delta)
                }
                offsetPx = fluixOverscrollStep(offsetPx, delta, maxPx)
                return Offset(0f, delta)
            }

            // 松手: 吃掉全部速度, 位移弹回 0。
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offsetPx == 0f) return Velocity.Zero
                releaseJob?.cancel()
                releaseJob = null
                startRelease()
                return available
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                startRelease()
                return Velocity.Zero
            }

            private fun startRelease() {
                if (offsetPx == 0f || releaseJob != null) return
                releaseJob = scope.launch {
                    animate(
                        offsetPx,
                        0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = FLUIX_RELEASE_STIFFNESS,
                        ),
                    ) { value, _ -> offsetPx = value }
                }
            }
        }
    }

    return this
        // 位移后裁掉溢出部分, 避免上滑时内容盖住标题栏。
        .clipToBounds()
        .graphicsLayer { translationY = offsetPx }
        .nestedScroll(connection)
}

private fun fluixOverscrollStep(current: Float, delta: Float, maxPx: Float): Float {
    if (maxPx <= 0f) return 0f
    return (current + delta * FLUIX_OVERSCROLL_DRAG).coerceIn(-maxPx, maxPx)
}

private fun fluixOverscrollFlingStep(delta: Float, capPx: Float, current: Float = 0f): Float {
    if (capPx <= 0f) return 0f
    val remaining = (capPx - abs(current)).coerceIn(0f, capPx)
    return delta * FLUIX_OVERSCROLL_DRAG_FLING * (remaining / capPx)
}

// 子页面标题栏: 左侧返回按钮 + 小标题, 右侧 actions(如重启菜单),
@Composable
fun FluixTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    FluixTopBarFrame(
        startPadding = FLUIX_TOP_BAR_START,
        modifier = modifier,
    ) {
        navigationIcon()
        BasicText(
            text = title,
            style = fluixTopBarTitleStyle(),
            modifier = Modifier
                .weight(1f)
                .padding(start = FLUIX_TOP_BAR_TITLE_GAP),
        )
        actions()
    }
}

// 紧凑卡片容器, 供首页分类列表等自定义内容复用。
@Composable
internal fun FluixCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val baseCard = CardDefaults.defaultColors()
    val groupColor = if (isSystemInDarkTheme()) FLUIX_GROUP_DARK else FLUIX_GROUP_LIGHT
    val cardColors: CardColors = baseCard.copy(color = backgroundColor ?: groupColor)
    Card(
        modifier = modifier.padding(
            start = FLUIX_GROUP_HMARGIN,
            top = 2.dp,
            end = FLUIX_GROUP_HMARGIN,
            bottom = FLUIX_CARD_BOTTOM_GAP,
        ),
        cornerRadius = FLUIX_CARD_CORNER,
        colors = cardColors,
        content = content,
    )
}

@Composable
internal fun FluixScopeWarningCard(message: String = "有作用域未勾选，请前往勾选", onClick: () -> Unit) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    FluixCard(backgroundColor = Color(0xFFFFBE0A).copy(alpha = 0.18f)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                .clickable(onClick = onClick)
                .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = message,
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = summary.copy(alpha = summary.alpha * FLUIX_CATEGORY_CHEVRON_ALPHA),
                modifier = Modifier.padding(start = 12.dp).size(FLUIX_CATEGORY_CHEVRON),
            )
        }
    }
}

@Composable
internal fun FluixCardRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val cardColor = if (isSystemInDarkTheme()) FLUIX_GROUP_DARK else FLUIX_GROUP_LIGHT
    val shape = when {
        first && last -> RoundedCornerShape(FLUIX_CARD_CORNER)
        first -> RoundedCornerShape(
            topStart = FLUIX_CARD_CORNER,
            topEnd = FLUIX_CARD_CORNER,
            bottomStart = 0.dp,
            bottomEnd = 0.dp,
        )
        last -> RoundedCornerShape(
            topStart = 0.dp,
            topEnd = 0.dp,
            bottomStart = FLUIX_CARD_CORNER,
            bottomEnd = FLUIX_CARD_CORNER,
        )
        else -> RectangleShape
    }
    Column(
        modifier = modifier
            .padding(
                start = FLUIX_GROUP_HMARGIN,
                end = FLUIX_GROUP_HMARGIN,
                top = if (first) 2.dp else 0.dp,
                bottom = if (last) FLUIX_CARD_BOTTOM_GAP else 0.dp,
            )
            .background(cardColor, shape)
            // 只有带圆角的首尾行需要裁剪(水波纹不溢出圆角); 中间行是矩形, 免掉一层。
            .then(if (first || last) Modifier.clip(shape) else Modifier),
        content = content,
    )
}

@Composable
internal fun FluixCategoryRow(
    icon: Int,
    iconForeground: Color,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FLUIX_HOME_CATEGORY_ROW_MIN_HEIGHT)
            .clickable { onClick() }
            .padding(
                start = FLUIX_HOME_CATEGORY_ROW_HPADDING,
                end = FLUIX_HOME_CHEVRON_END_PADDING,
                top = FLUIX_HOME_CATEGORY_ROW_VPADDING,
                bottom = FLUIX_HOME_CATEGORY_ROW_VPADDING,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                colorFilter = ColorFilter.tint(iconForeground),
                modifier = Modifier.size(32.dp),
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = FLUIX_CATEGORY_ICON_GAP)) {
            BasicText(text = title, style = MiuixTheme.textStyles.body1.copy(color = onSurface))
            if (!subtitle.isNullOrBlank()) BasicText(
                text = subtitle,
                style = MiuixTheme.textStyles.body2.copy(color = summary),
                modifier = Modifier.padding(top = FLUIX_CATEGORY_SUBTITLE_GAP),
            )
        }
        Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = summary.copy(alpha = summary.alpha * FLUIX_CATEGORY_CHEVRON_ALPHA),
            modifier = Modifier
                // 仅当右侧有副标题时才需要间隔，其余行的箭头紧贴标题区右缘。
                .padding(start = 12.dp, end = 0.dp)
                .size(FLUIX_CATEGORY_CHEVRON),
        )
    }
}

private fun glowStops(color: Color, alpha: Float): Array<Pair<Float, Color>> {
    val k = FLUIX_HEADER_GLOW_LOG_K.toDouble()
    val denom = ln(1.0 + k)
    return Array(FLUIX_HEADER_GLOW_STOP_COUNT + 1) { i ->
        val t = i.toFloat() / FLUIX_HEADER_GLOW_STOP_COUNT
        val falloff = (1.0 - ln(1.0 + k * t) / denom).toFloat()
        t to color.copy(alpha = alpha * falloff)
    }
}

@Composable
internal fun FluixDeviceHeader(
    model: String,
    system: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FLUIX_CARD_CORNER),
) {
    // miuix 未直接暴露当前明暗, 用系统配置判定(主题为 ColorSchemeMode.System, 两者一致)。
    val light = !isSystemInDarkTheme()
    val baseColor = if (light) FLUIX_HEADER_BASE_LIGHT else FLUIX_HEADER_BASE_DARK
    val glowColor = if (light) FLUIX_HEADER_GLOW_LIGHT else FLUIX_HEADER_GLOW_DARK
    // 亮底上白字不可读, 故文字色跟着主题走: 暗底用纯白, 亮底用 onSurface。
    val textColor = if (light) MiuixTheme.colorScheme.onSurface else Color.White
    // 两层光晕共用同一条 log 衰减曲线, 只有整体 alpha 不同; 按颜色缓存避免每次重组重算。
    val outerStops = remember(glowColor) { glowStops(glowColor, 1f) }
    val coreStops = remember(glowColor) { glowStops(glowColor, FLUIX_HEADER_CORE_ALPHA) }

    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var bannerTickJob by remember { mutableStateOf<Job?>(null) }
    val longPressModifier = Modifier.pointerInput(Unit) {
        detectTapGestures(onPress = {
            val vibrator =
                view.context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val tick =
                VibrationEffect.createOneShot(BANNER_VIBRATE_MS, BANNER_VIBRATE_AMPLITUDE)
            val startJob = scope.launch {
                delay(BANNER_LONG_PRESS_MS)
                bannerTickJob = scope.launch {
                    while (isActive) {
                        vibrator.vibrate(tick)
                        delay(BANNER_TICK_INTERVAL_MS)
                    }
                }
            }
            tryAwaitRelease()
            startJob.cancel()
            bannerTickJob?.cancel()
            bannerTickJob = null
        })
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FLUIX_HEADER_HEIGHT)
            .clip(shape)
            .then(longPressModifier)
            .drawBehind {
                drawRect(color = baseColor)
                val cx = size.width / 2f
                val cy = size.height * FLUIX_HEADER_GLOW_CENTER_Y_RATIO

                fun drawGlowEllipse(
                    radiusX: Float,
                    radiusY: Float,
                    stops: Array<Pair<Float, Color>>,
                ) {
                    val k = radiusY / radiusX
                    scale(scaleX = 1f, scaleY = k, pivot = Offset(cx, cy)) {
                        val top = cy + (0f - cy) / k
                        val bottom = cy + (size.height - cy) / k
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = stops,
                                center = Offset(cx, cy),
                                radius = radiusX,
                            ),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, bottom - top),
                        )
                    }
                }
                drawGlowEllipse(
                    radiusX = size.width * FLUIX_HEADER_GLOW_RADIUS_RATIO,
                    radiusY = size.height * FLUIX_HEADER_GLOW_RY_RATIO,
                    stops = outerStops,
                )
                drawGlowEllipse(
                    radiusX = size.width * FLUIX_HEADER_CORE_RADIUS_RATIO,
                    radiusY = size.height * FLUIX_HEADER_CORE_RY_RATIO,
                    stops = coreStops,
                )
            },
    ) {
        // 设备名在横幅正中, 再上移 FLUIX_HEADER_MODEL_LIFT。
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(y = -FLUIX_HEADER_MODEL_LIFT),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                text = model,
                style = MiuixTheme.textStyles.title1.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
        // 系统名贴横幅底边。
        if (!system.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(bottom = FLUIX_HEADER_SYSTEM_BOTTOM),
                contentAlignment = Alignment.BottomCenter,
            ) {
                BasicText(
                    text = system,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = textColor.copy(alpha = FLUIX_HEADER_SYSTEM_ALPHA),
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
private fun fluixSwitchTick(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

@Composable
fun FluixSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MiuixTheme.colorScheme.primary
    val trackOff = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.3f)
    val thumbTarget: Float by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        label = "fluix_switch_thumb",
    )
    Canvas(modifier = modifier.requiredSize(FLUIX_SWITCH_W, FLUIX_SWITCH_H)) {
        val w = size.width
        val h = size.height
        val r = FLUIX_SWITCH_RADIUS.toPx()
        drawRoundRect(
            color = if (checked) primary else trackOff,
            cornerRadius = CornerRadius(r, r),
        )
        val thumbR = FLUIX_SWITCH_THUMB_R.toPx()
        val padY = (h - 2 * thumbR) / 2f
        val padX = padY
        val onX = w - padX - thumbR
        val offX = padX + thumbR
        val cx = offX + (onX - offX) * thumbTarget
        drawCircle(
            color = Color.White,
            radius = thumbR,
            center = Offset(cx, h / 2f),
        )
    }
}

@Composable
fun FluixSwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onTitleClick: (() -> Unit)? = null,
    leftTrailingContent: @Composable RowScope.() -> Unit = {},
    showDivider: Boolean = false,
) {
    val density = LocalDensity.current
    var rowHeightPx by remember { mutableStateOf(0) }
    val dividerHeight = with(density) {
        (rowHeightPx - 2 * FLUIX_ROW_VPADDING.toPx()).coerceAtLeast(0f).toDp()
    }
    val tick = fluixSwitchTick()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { rowHeightPx = it.height }
            .clickable {
                tick()
                onCheckedChange(!checked)
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clickable { onTitleClick() }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .padding(
                            start = FLUIX_ROW_HPADDING,
                            top = FLUIX_ROW_VPADDING,
                            bottom = FLUIX_ROW_VPADDING,
                            end = 12.dp,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                    ) {
                        BasicText(
                            text = title,
                            style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                        )
                        if (!subtitle.isNullOrBlank()) {
                            BasicText(
                                text = subtitle,
                                style = MiuixTheme.textStyles.body2.copy(
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                ),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    leftTrailingContent()
                }
            }
            if (showDivider) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .width((1f / density.density).dp)
                        .height(dividerHeight)
                        .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
                )
            }
            Row(
                modifier = Modifier.padding(
                    start = if (showDivider) 12.dp else 0.dp,
                    top = FLUIX_ROW_VPADDING,
                    bottom = FLUIX_ROW_VPADDING,
                    end = FLUIX_ROW_HPADDING,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluixSwitch(
                    checked = checked,
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun FluixItemDivider(modifier: Modifier = Modifier, startInset: Dp = FLUIX_DIVIDER_INSET) {
    // Empty by design; retaining call sites keeps each item group's composition readable.
}

@Composable
private fun FluixDropdownItem(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val onSurface = MiuixTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = MiuixTheme.textStyles.body1.copy(
                color = if (selected) primary else onSurface,
            ),
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            FluixCheckMark(color = primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun FluixDropdownPreference(
    title: String,
    options: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = FLUIX_ROW_MIN_HEIGHT).clickable { expanded = true }
            .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
                modifier = Modifier.weight(1f),
            )
            BasicText(
                text = options[selected] + " ▾",
                style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (expanded) Popup(
            alignment = Alignment.TopEnd,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            val shape = miuixShape(FLUIX_DROPDOWN_CORNER)
            Column(
                modifier = Modifier.width(FLUIX_DROPDOWN_WIDTH)
                    .shadow(FLUIX_DROPDOWN_ELEVATION, shape)
                    .background(if (isSystemInDarkTheme()) FLUIX_POPUP_DARK else FLUIX_POPUP_LIGHT, shape).clip(shape),
            ) {
                options.forEachIndexed { index, option ->
                    if (index > 0) FluixDropdownDivider()
                    FluixDropdownItem(text = option, selected = index == selected) {
                        expanded = false
                        onSelected(index)
                    }
                }
            }
        }
    }
}

@Composable
private fun FluixCheckMark(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val sw = minOf(w, h) * 0.11f
        val p1 = Offset(w * 0.20f, h * 0.52f)
        val p2 = Offset(w * 0.42f, h * 0.73f)
        val p3 = Offset(w * 0.80f, h * 0.27f)
        drawLine(color, p1, p2, strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(color, p2, p3, strokeWidth = sw, cap = StrokeCap.Round)
    }
}

@Composable
private fun FluixDropdownDivider() {
    // Native Flyme popup rows are separated by spacing and background, not rules.
}

data class ActionMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val confirmTitle: String? = null,
    val confirmText: String? = null,
)

@Composable
fun FluixActionMenu(
    icon: @Composable () -> Unit,
    items: List<ActionMenuItem>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var popupVisible by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<ActionMenuItem?>(null) }
    LaunchedEffect(expanded) {
        if (!expanded) {
            delay(220)
            popupVisible = false
        }
    }
    // Popup 挂载后(expanded 初始为 false)再置 true, 让 AnimatedVisibility 播放进场动画
    LaunchedEffect(popupVisible) {
        if (popupVisible) {
            delay(16)
            expanded = true
        }
    }

    val dropdownShape = miuixShape(FLUIX_DROPDOWN_CORNER)

    Box(
        modifier = modifier,
    ) {
        IconButton(
            onClick = {
                if (expanded) {
                    expanded = false
                } else {
                    popupVisible = true
                }
            },
        ) { icon() }

        if (popupVisible) Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(0, 0),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true),
        ) {
            // 全屏根容器: 透明遮罩捕获菜单外点击以收起菜单, 菜单本身靠右上角对齐
            Box(modifier = Modifier.fillMaxSize()) {
                // 透明全屏遮罩, 点击外部收起菜单
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { expanded = false },
                )
                // 菜单容器: 右上角对齐并留出间距, 点击菜单项不触发遮罩收起
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 16.dp, top = 16.dp),
                ) {
                    Box(modifier = Modifier.width(FLUIX_DROPDOWN_WIDTH)) {
                        AnimatedVisibility(
                            visible = expanded,
                            enter = scaleIn(
                            initialScale = 0.8f,
                            transformOrigin = FLUIX_DROPDOWN_TRANSFORM_ORIGIN,
                            animationSpec = tween(durationMillis = 120),
                        ) + fadeIn(animationSpec = tween(durationMillis = 120)),
                        exit = scaleOut(
                            targetScale = 0.8f,
                            transformOrigin = FLUIX_DROPDOWN_TRANSFORM_ORIGIN,
                            animationSpec = tween(durationMillis = 120),
                        ) + fadeOut(animationSpec = tween(durationMillis = 120)),
                    ) {
                        Column(
                            modifier = Modifier
                                .width(FLUIX_DROPDOWN_WIDTH)
                                // 阴影画在自身边界之外, 故 clip = false; 内容裁剪仍由下面的 .clip() 负责。
                                .shadow(
                                    elevation = FLUIX_DROPDOWN_ELEVATION,
                                    shape = dropdownShape,
                                    clip = false,
                                    ambientColor = Color.Black,
                                    spotColor = Color.Black,
                                )
                                .background(if (isSystemInDarkTheme()) FLUIX_POPUP_DARK else FLUIX_POPUP_LIGHT, dropdownShape)
                                .clip(dropdownShape),
                        ) {
                            items.forEachIndexed { index, item ->
                                if (index > 0) FluixDropdownDivider()
                                FluixDropdownItem(
                                    text = item.label,
                                    selected = false,
                                ) {
                                    expanded = false
                                    if (item.confirmTitle != null) pendingConfirm = item else item.onClick()
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    if (pendingConfirm != null) {
        FluixConfirmDialog(
            title = pendingConfirm!!.confirmTitle ?: "",
            text = pendingConfirm!!.confirmText ?: "",
            onConfirm = {
                val action = pendingConfirm!!.onClick
                pendingConfirm = null
                action()
            },
            onDismiss = { pendingConfirm = null },
        )
    }
}

@Composable
fun FluixConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            cornerRadius = FLUIX_CARD_CORNER,
            colors = CardDefaults.defaultColors().copy(
                color = if (isSystemInDarkTheme()) FLUIX_GROUP_DARK else FLUIX_GROUP_LIGHT,
            ),
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .padding(horizontal = 8.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                BasicText(
                    text = title,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
                BasicText(
                    text = text,
                    style = MiuixTheme.textStyles.body2.copy(
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    ),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(
                            text = dismissLabel,
                            style = MiuixTheme.textStyles.body2.copy(
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            ),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clickable { onConfirm() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        BasicText(
                            text = confirmLabel,
                            style = MiuixTheme.textStyles.body2.copy(
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FluixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    onInteractionChange: (Boolean) -> Unit = {},
) {
    val updateValue by rememberUpdatedState(onValueChange)
    val finishChange by rememberUpdatedState(onValueChangeFinished)
    val interactionChange by rememberUpdatedState(onInteractionChange)
    val primary = MiuixTheme.colorScheme.primary
    val trackOff = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)
    val v = value.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FLUIX_SLIDER_TRACK_H)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    interactionChange(true)
                    val range = size.width.coerceAtLeast(1)
                    updateValue((offset.x / range).coerceIn(0f, 1f))
                    finishChange()
                    interactionChange(false)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { interactionChange(true) },
                    onDragEnd = { finishChange(); interactionChange(false) },
                    onDragCancel = { interactionChange(false) },
                ) { change, _ ->
                    change.consume()
                    val range = size.width.coerceAtLeast(1)
                    updateValue((change.position.x / range).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val gap = FLUIX_SLIDER_GAP.toPx()
            val thumbR = FLUIX_SLIDER_THUMB_R.toPx()
            val cy = size.height / 2f
            // 轨道恒为全高(已去除 idle 收缩态); barR 为半高兼作圆角与延伸量
            val barH = FLUIX_SLIDER_TRACK_H.toPx()
            val barR = barH / 2f
            // thumb 中心按比例走全宽, 不为半径留白
            val cx = size.width * v
            // 轨道(胶囊背景): 向两侧延伸 barR 包裹 thumb(两端时 thumb 半径超出可见区)
            drawRoundRect(
                color = trackOff,
                topLeft = Offset(-barR, cy - barR),
                size = Size(size.width + 2 * barR, barH),
                cornerRadius = CornerRadius(barR, barR),
            )
            // 激活部分: 左端与轨道对齐(-barR), 右端到 thumb 右缘(cx + barR), 包裹 thumb
            drawRoundRect(
                color = primary,
                topLeft = Offset(-barR, cy - barR),
                size = Size(cx + 2 * barR, barH),
                cornerRadius = CornerRadius(barR, barR),
            )
            drawCircle(color = Color.White, radius = thumbR - gap, center = Offset(cx, cy))
        }
    }
}

@Composable
internal fun FluixActionPairRow(
    leftTitle: String,
    onLeftClick: () -> Unit,
    rightTitle: String,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluixActionCell(
            title = leftTitle,
            onClick = onLeftClick,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = FLUIX_ROW_VPADDING)
                .width((1f / density.density).dp)
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
        )
        FluixActionCell(
            title = rightTitle,
            onClick = onRightClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FluixActionCell(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable { onClick() }
            .heightIn(min = FLUIX_ROW_MIN_HEIGHT)
            .padding(
                start = FLUIX_ROW_HPADDING,
                end = FLUIX_HOME_CHEVRON_END_PADDING,
                top = FLUIX_ROW_VPADDING,
                bottom = FLUIX_ROW_VPADDING,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = title,
                style = MiuixTheme.textStyles.body1.copy(
                    color = MiuixTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 与分类入口行同一枚前进箭头(同尺寸、同压低后的不透明度)。
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = summary.copy(alpha = summary.alpha * FLUIX_CATEGORY_CHEVRON_ALPHA),
                modifier = Modifier.size(FLUIX_CATEGORY_CHEVRON),
            )
        }
    }
}

@Composable
fun FluixMasterToggle(
    checked: Boolean,
    title: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    // 主开关上方追加的内容(如首页的机型横幅), 与主开关同卡片、以分割线隔开。
    aboveContent: (@Composable ColumnScope.() -> Unit)? = null,
    // 主开关下方追加的内容(如首页的隐藏桌面图标开关), 与主开关同卡片、以分割线隔开。
    belowContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val tick = fluixSwitchTick()
    FluixCard(modifier = modifier) {
        if (aboveContent != null) {
            aboveContent()
            FluixItemDivider()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = FLUIX_ROW_MIN_HEIGHT)
                .clickable {
                    tick()
                    onCheckedChange(!checked)
                }
                .padding(horizontal = FLUIX_ROW_HPADDING, vertical = FLUIX_ROW_VPADDING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                BasicText(
                    text = title,
                    style = MiuixTheme.textStyles.body1.copy(
                        color = MiuixTheme.colorScheme.onSurface,
                    ),
                )
                if (!subtitle.isNullOrBlank()) {
                    BasicText(
                        text = subtitle,
                        style = MiuixTheme.textStyles.body2.copy(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        ),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            FluixSwitch(
                checked = checked,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        if (belowContent != null) {
            FluixItemDivider()
            belowContent()
        }
    }
}
