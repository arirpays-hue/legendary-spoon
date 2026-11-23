package com.example.screentranslator

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.HapticFeedbackConstants
import android.view.animation.PathInterpolator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class AppTab(val label: String) {
    HOME("ראשי"),
    MODELS("מודלים"),
    TEST("בדיקה"),
    SETTINGS("הגדרות")
}

internal object AppPalette {
    val BgObsidian = Color(0xFF0B0E17)
    val BgObsidianTop = Color(0xFF101526)
    val GlassCardBg = Color(0xD9151A28)
    val GlassCardInner = Color(0xFF0F1320)
    val GlassBorder = Color(0x14FFFFFF)
    val ElectricBlue = Color(0xFF3B82F6)
    val ElectricBlueLight = Color(0xFF60A5FA)
    val ElectricCyan = Color(0xFF38BDF8)
    val GlowPurple = Color(0xFF7C3AED)
    val EmeraldGreen = Color(0xFF10B981)
    val AccentAmber = Color(0xFFF59E0B)
    val AccentRed = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
}

internal object AppMotion {
    val SmoothOutEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
    val BouncySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    fun tabTransition(
        fromTab: AppTab,
        toTab: AppTab,
        animationsEnabled: Boolean
    ): ContentTransform {
        if (!animationsEnabled) {
            return fadeIn(tween(90)) togetherWith fadeOut(tween(90))
        }
        val forward = toTab.ordinal >= fromTab.ordinal
        val enter = fadeIn(tween(240, easing = SmoothOutEasing)) +
            scaleIn(initialScale = 0.985f, animationSpec = tween(240, easing = SmoothOutEasing)) +
            slideInHorizontally(tween(260, easing = SmoothOutEasing)) { fullWidth ->
                if (forward) -fullWidth / 12 else fullWidth / 12
            }
        val exit = fadeOut(tween(150)) +
            slideOutHorizontally(tween(180)) { fullWidth ->
                if (forward) fullWidth / 16 else -fullWidth / 16
            }
        return enter togetherWith exit
    }
}

internal fun Modifier.staggeredEntrance(
    index: Int,
    enabled: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index * 55L).coerceAtMost(280L))
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 360, easing = AppMotion.SmoothOutEasing)
        )
    }
    this.graphicsLayer {
        val v = progress.value.coerceIn(0f, 1f)
        alpha = v
        translationY = (1f - v) * 26f
        val s = 0.975f + (0.025f * v)
        scaleX = s
        scaleY = s
    }
}

internal fun Modifier.tactilePress(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
    animationsEnabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled && animationsEnabled) pressedScale else 1f,
        animationSpec = AppMotion.BouncySpring,
        label = "TactilePress"
    )
    this
        .scale(scale)
        .clickable(
            enabled = enabled,
            interactionSource = interactionSource,
            indication = null
        ) {
            CrashGuard.safeRun("TactileHaptic") {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
            onClick()
        }
}

@Composable
internal fun ObsidianAmbientBackdrop(
    isServiceActive: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryOrbColor by animateColorAsState(
        targetValue = if (isServiceActive) AppPalette.EmeraldGreen else AppPalette.ElectricBlue,
        animationSpec = tween(600),
        label = "AuroraPrimary"
    )
    val secondaryOrbColor by animateColorAsState(
        targetValue = if (isServiceActive) AppPalette.ElectricCyan else AppPalette.GlowPurple,
        animationSpec = tween(600),
        label = "AuroraSecondary"
    )

    val phase = if (animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "AmbientAurora")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 14_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "AuroraPhase"
        ).value
    } else {
        0.25f
    }

    val particles = remember {
        List(16) { i ->
            val seed = (i + 1) * 37
            Triple(
                ((seed * 29) % 100) / 100f,
                ((seed * 53) % 100) / 100f,
                1.4f + (i % 3) * 0.9f
            )
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 2f || h <= 2f) return@Canvas

        val angle = phase * 2.0 * PI
        val orb1X = w * (0.50f + 0.14f * cos(angle).toFloat())
        val orb1Y = h * (0.18f + 0.05f * sin(angle).toFloat())
        val orb1R = (w * 0.82f).coerceAtLeast(10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    primaryOrbColor.copy(alpha = 0.14f),
                    secondaryOrbColor.copy(alpha = 0.06f),
                    Color.Transparent
                ),
                center = Offset(orb1X, orb1Y),
                radius = orb1R
            ),
            center = Offset(orb1X, orb1Y),
            radius = orb1R
        )

        val orb2X = w * (0.20f + 0.18f * sin(angle * 0.8).toFloat())
        val orb2Y = h * (0.72f + 0.06f * cos(angle * 0.8).toFloat())
        val orb2R = (w * 0.65f).coerceAtLeast(10f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    secondaryOrbColor.copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(orb2X, orb2Y),
                radius = orb2R
            ),
            center = Offset(orb2X, orb2Y),
            radius = orb2R
        )

        if (animationsEnabled) {
            particles.forEachIndexed { idx, (bx, by, rDp) ->
                val yNorm = (by - phase * (0.6f + (idx % 3) * 0.25f) + 2f) % 1f
                val driftX = sin(angle + idx * 0.9).toFloat() * 0.03f
                val px = (bx + driftX).coerceIn(0.03f, 0.97f) * w
                val py = yNorm * h
                val edgeFade = sin(yNorm * PI).toFloat().coerceIn(0f, 1f)
                val pColor = if (idx % 2 == 0) AppPalette.ElectricCyan else primaryOrbColor
                drawCircle(
                    color = pColor.copy(alpha = 0.22f * edgeFade),
                    radius = rDp.dp.toPx(),
                    center = Offset(px, py)
                )
            }
        }
    }
}

@Composable
internal fun AnimatedGlassCard(
    modifier: Modifier = Modifier,
    staggerIndex: Int = 0,
    animationsEnabled: Boolean = true,
    flashTrigger: Int = 0,
    content: @Composable ColumnScope.() -> Unit
) {
    val flashAlpha = remember { Animatable(0f) }
    LaunchedEffect(flashTrigger) {
        if (flashTrigger > 0 && animationsEnabled) {
            flashAlpha.snapTo(0.90f)
            flashAlpha.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 360, easing = AppMotion.SmoothOutEasing)
            )
        }
    }

    val safeFlash = flashAlpha.value.coerceIn(0f, 1f)
    val borderColor = Color(
        red = lerpFloat(1f, AppPalette.ElectricBlueLight.red, safeFlash).coerceIn(0f, 1f),
        green = lerpFloat(1f, AppPalette.ElectricBlueLight.green, safeFlash).coerceIn(0f, 1f),
        blue = lerpFloat(1f, AppPalette.ElectricBlueLight.blue, safeFlash).coerceIn(0f, 1f),
        alpha = lerpFloat(0.08f, 0.78f, safeFlash).coerceIn(0f, 1f)
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .staggeredEntrance(index = staggerIndex, enabled = animationsEnabled)
            .clip(RoundedCornerShape(22.dp))
            .background(AppPalette.GlassCardBg)
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
internal fun AnimatedSwapButton(
    animationsEnabled: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current
    var targetRotation by remember { mutableFloatStateOf(0f) }
    val rotation by animateFloatAsState(
        targetValue = if (animationsEnabled) targetRotation else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "SwapRotation"
    )
    val glowPulse = remember { Animatable(0f) }
    var pulseTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(pulseTick) {
        if (pulseTick > 0 && animationsEnabled) {
            glowPulse.snapTo(1f)
            glowPulse.animateTo(0f, tween(340, easing = AppMotion.SmoothOutEasing))
        }
    }

    val safePulse = glowPulse.value.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(AppPalette.ElectricBlue.copy(alpha = 0.16f + safePulse * 0.28f))
            .border(
                width = 1.dp,
                color = AppPalette.ElectricCyan.copy(alpha = 0.35f + safePulse * 0.55f),
                shape = CircleShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                CrashGuard.safeRun("SwapHaptic") {
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                targetRotation += 180f
                pulseTick++
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⇄",
            color = AppPalette.ElectricBlueLight,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.graphicsLayer { rotationZ = rotation }
        )
    }
}

@Composable
internal fun PulsingStatusPill(
    label: String,
    color: Color,
    pulsing: Boolean = false,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val pulseProgress = if (pulsing && animationsEnabled) {
        val inf = rememberInfiniteTransition(label = "PillPulse")
        inf.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "DotHalo"
        ).value.coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(50))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier.size(11.dp),
            contentAlignment = Alignment.Center
        ) {
            if (pulsing && animationsEnabled) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    if (size.minDimension <= 1f) return@Canvas
                    val r = (3.5f + pulseProgress * 4.5f).dp.toPx()
                    drawCircle(
                        color = color.copy(alpha = (1f - pulseProgress) * 0.55f),
                        radius = r
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
internal fun ImageScanLaserOverlay(
    statusText: String,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scanY = if (animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "ImageLaser")
        infinite.animateFloat(
            initialValue = 0.08f,
            targetValue = 0.92f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "LaserY"
        ).value.coerceIn(0.05f, 0.95f)
    } else {
        0.5f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppPalette.BgObsidian.copy(alpha = 0.48f)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            if (w <= 4f || h <= 4f) return@Canvas

            val beamY = h * scanY
            val trailH = (h * 0.22f).coerceAtLeast(8f)
            val topY = (beamY - trailH).coerceIn(0f, h - 2f)
            val botY = (beamY + trailH).coerceIn(topY + 2f, h)

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AppPalette.ElectricBlue.copy(alpha = 0.25f),
                        AppPalette.ElectricCyan.copy(alpha = 0.65f),
                        AppPalette.ElectricBlue.copy(alpha = 0.22f),
                        Color.Transparent
                    ),
                    startY = topY,
                    endY = botY
                ),
                topLeft = Offset(0f, topY),
                size = Size(w, (botY - topY).coerceAtLeast(1f))
            )

            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AppPalette.ElectricCyan,
                        Color.White,
                        AppPalette.ElectricCyan,
                        Color.Transparent
                    )
                ),
                start = Offset(0f, beamY),
                end = Offset(w, beamY),
                strokeWidth = 2.6.dp.toPx(),
                cap = StrokeCap.Round
            )

            val pad = 16.dp.toPx().coerceAtMost(minOf(w, h) * 0.25f)
            val arm = 22.dp.toPx().coerceAtMost(minOf(w, h) * 0.22f)
            val bracketStroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            val bracketColor = AppPalette.ElectricCyan.copy(alpha = 0.85f)
            val corners = Path().apply {
                moveTo(pad, pad + arm); lineTo(pad, pad); lineTo(pad + arm, pad)
                moveTo(w - pad - arm, pad); lineTo(w - pad, pad); lineTo(w - pad, pad + arm)
                moveTo(pad, h - pad - arm); lineTo(pad, h - pad); lineTo(pad + arm, h - pad)
                moveTo(w - pad - arm, h - pad); lineTo(w - pad, h - pad); lineTo(w - pad, h - pad - arm)
            }
            drawPath(corners, color = bracketColor, style = bracketStroke)
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(AppPalette.BgObsidian.copy(alpha = 0.82f))
                .border(1.dp, AppPalette.ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = statusText.ifEmpty { "סורק ומתרגם תמונה..." },
                color = AppPalette.TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
internal fun HeroPowerButton(
    isServiceActive: Boolean,
    animationsEnabled: Boolean,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && animationsEnabled) 0.94f else 1f,
        animationSpec = AppMotion.BouncySpring,
        label = "HeroPressScale"
    )

    val rippleProgress = remember { Animatable(1f) }
    var clickPulseCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(clickPulseCount) {
        if (clickPulseCount > 0 && animationsEnabled) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
            )
        }
    }

    val ring1Progress: Float
    val ring2Progress: Float
    if (animationsEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "HeroBreathing")
        ring1Progress = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "Ring1"
        ).value.coerceIn(0f, 1f)
        ring2Progress = infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 2200,
                    delayMillis = 700,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "Ring2"
        ).value.coerceIn(0f, 1f)
    } else {
        ring1Progress = 0.25f
        ring2Progress = 0.65f
    }

    val coreStartColor by animateColorAsState(
        targetValue = if (isServiceActive) Color(0xFF10B981) else Color(0xFF60A5FA),
        animationSpec = tween(320),
        label = "CoreStart"
    )
    val coreEndColor by animateColorAsState(
        targetValue = if (isServiceActive) Color(0xFF047857) else Color(0xFF2563EB),
        animationSpec = tween(320),
        label = "CoreEnd"
    )
    val glowColor by animateColorAsState(
        targetValue = if (isServiceActive) AppPalette.EmeraldGreen else AppPalette.ElectricBlue,
        animationSpec = tween(320),
        label = "GlowColor"
    )

    Box(
        modifier = Modifier.size(226.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.minDimension <= 4f) return@Canvas
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension * 0.34f).coerceAtLeast(4f)
            val haloRadius = (size.minDimension * 0.50f).coerceAtLeast(8f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = if (isServiceActive) 0.42f else 0.34f),
                        AppPalette.GlowPurple.copy(alpha = if (isServiceActive) 0.08f else 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = haloRadius
                ),
                center = center,
                radius = haloRadius
            )

            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = baseRadius * 1.18f,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.04f),
                radius = baseRadius * 1.36f,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )

            val r1Scale = 1.0f + (ring1Progress * 0.36f)
            val r1Alpha = (1f - ring1Progress) * 0.40f
            drawCircle(
                color = glowColor.copy(alpha = r1Alpha),
                radius = baseRadius * r1Scale,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            val r2Scale = 1.0f + (ring2Progress * 0.36f)
            val r2Alpha = (1f - ring2Progress) * 0.28f
            drawCircle(
                color = AppPalette.ElectricCyan.copy(alpha = r2Alpha),
                radius = baseRadius * r2Scale,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )

            val rp = rippleProgress.value.coerceIn(0f, 1f)
            if (rp < 0.99f) {
                drawCircle(
                    color = Color.White.copy(alpha = (1f - rp) * 0.55f),
                    radius = baseRadius * (1.0f + rp * 0.48f),
                    center = center,
                    style = Stroke(width = (3.5f * (1f - rp) + 1f).dp.toPx())
                )
            }
        }

        Box(
            modifier = Modifier
                .size(152.dp)
                .scale(pressScale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(coreStartColor, coreEndColor),
                        center = Offset(140f, 110f),
                        radius = 290f
                    )
                )
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.50f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    ),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    CrashGuard.safeRun("HeroHaptic") {
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                    clickPulseCount++
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(12.dp)
            ) {
                PowerTranslateGlyph(
                    isActive = isServiceActive,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isServiceActive) "עצור מתרגם מסך" else "הפעל מתרגם מסך",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PowerTranslateGlyph(isActive: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tint = Color(0xFF0A1628).copy(alpha = 0.85f)

        val powerCenter = Offset(w * 0.5f, h * 0.22f)
        val powerRadius = w * 0.11f
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(powerCenter.x - powerRadius, powerCenter.y - powerRadius),
            size = Size(powerRadius * 2f, powerRadius * 2f),
            style = stroke
        )
        drawLine(
            color = tint,
            start = Offset(powerCenter.x, powerCenter.y - powerRadius * 1.25f),
            end = Offset(powerCenter.x, powerCenter.y + powerRadius * 0.1f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )

        if (isActive) {
            val sqSize = w * 0.30f
            drawRoundRect(
                color = tint,
                topLeft = Offset((w - sqSize) / 2f, h * 0.52f),
                size = Size(sqSize, sqSize),
                cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            )
        } else {
            val tileW = w * 0.34f
            val tileH = h * 0.34f
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.17f, h * 0.44f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(w * 0.49f, h * 0.56f),
                size = Size(tileW, tileH),
                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = stroke
            )
            val aPath = Path().apply {
                moveTo(w * 0.58f, h * 0.82f)
                lineTo(w * 0.66f, h * 0.64f)
                lineTo(w * 0.74f, h * 0.82f)
                moveTo(w * 0.61f, h * 0.76f)
                lineTo(w * 0.71f, h * 0.76f)
            }
            drawPath(aPath, color = tint, style = stroke)

            val alefPath = Path().apply {
                moveTo(w * 0.25f, h * 0.52f)
                lineTo(w * 0.42f, h * 0.70f)
                moveTo(w * 0.41f, h * 0.52f)
                lineTo(w * 0.36f, h * 0.61f)
                moveTo(w * 0.26f, h * 0.70f)
                lineTo(w * 0.31f, h * 0.61f)
            }
            drawPath(alefPath, color = tint, style = stroke)
        }
    }
}

@Composable
internal fun ObsidianDownloadProgressBar(
    progress: Float,
    isDownloading: Boolean,
    isSegmented: Boolean,
    segmentProgress: List<Float>,
    completedParts: Int,
    totalParts: Int,
    speedBytesPerSec: Long,
    labelHe: String,
    animationsEnabled: Boolean = true,
    isError: Boolean = false
) {
    val clamped by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "ObsidianProgress"
    )

    val shimmerOffset = if (isDownloading && animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "ProgressShimmer")
        infinite.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "BarShimmer"
        ).value
    } else {
        -1f
    }

    val pct = (clamped * 100f).roundToInt().coerceIn(0, 100)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppPalette.GlassCardInner)
            .border(
                width = 1.dp,
                color = when {
                    isError -> AppPalette.AccentRed.copy(alpha = 0.45f)
                    clamped >= 0.999f && !isDownloading -> AppPalette.EmeraldGreen.copy(alpha = 0.45f)
                    isDownloading -> AppPalette.ElectricCyan.copy(alpha = 0.45f)
                    else -> AppPalette.GlassBorder
                },
                shape = RoundedCornerShape(14.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = labelHe,
                color = if (isError) Color(0xFFFCA5A5) else AppPalette.TextPrimary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            )
            Text(
                text = if (isError) "שגיאה" else "$pct%",
                color = when {
                    isError -> AppPalette.AccentRed
                    clamped >= 0.999f && !isDownloading -> AppPalette.EmeraldGreen
                    else -> AppPalette.ElectricCyan
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
        ) {
            if (size.width <= 2f || size.height <= 1f) return@Canvas
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
            )

            val fillWidth = (size.width * clamped).coerceIn(0f, size.width)
            if (fillWidth > 2f) {
                val brush = when {
                    isError -> Brush.horizontalGradient(listOf(AppPalette.AccentRed, Color(0xFFF87171)))
                    clamped >= 0.999f && !isDownloading -> Brush.horizontalGradient(
                        listOf(AppPalette.EmeraldGreen, Color(0xFF34D399))
                    )
                    else -> Brush.horizontalGradient(
                        listOf(AppPalette.GlowPurple, AppPalette.ElectricBlue, AppPalette.ElectricCyan)
                    )
                }
                drawRoundRect(
                    brush = brush,
                    size = Size(fillWidth, size.height),
                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                )

                if (isDownloading && shimmerOffset >= -0.3f && fillWidth > 12f) {
                    val glossX = shimmerOffset * fillWidth
                    val sx = (glossX - 60f).coerceIn(0f, fillWidth - 4f)
                    val ex = (glossX + 60f).coerceIn(sx + 2f, fillWidth)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.White.copy(alpha = 0.45f),
                                Color.Transparent
                            ),
                            startX = sx,
                            endX = ex
                        ),
                        size = Size(fillWidth, size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
            }
        }

        if (isSegmented && totalParts > 1) {
            val segments = if (segmentProgress.size == totalParts) {
                segmentProgress
            } else {
                List(totalParts) { idx -> if (idx < completedParts) 1f else 0f }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
            ) {
                if (size.width <= 2f || size.height <= 1f) return@Canvas
                val count = segments.size.coerceAtLeast(1)
                val gap = 1.5.dp.toPx()
                val segW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
                for (i in 0 until count) {
                    val p = segments[i].coerceIn(0f, 1f)
                    val x = i * (segW + gap)
                    val segColor = when {
                        p >= 0.99f -> AppPalette.EmeraldGreen
                        p > 0.05f -> AppPalette.ElectricCyan
                        else -> Color.White.copy(alpha = 0.10f)
                    }
                    drawRoundRect(
                        color = segColor,
                        topLeft = Offset(x, 0f),
                        size = Size(segW, size.height),
                        cornerRadius = CornerRadius(2f, 2f)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "חלקים: $completedParts / $totalParts",
                    color = AppPalette.TextSecondary,
                    fontSize = 11.sp
                )
                Text(
                    text = if (speedBytesPerSec > 0L) formatDownloadSpeed(speedBytesPerSec) else "",
                    color = AppPalette.ElectricCyan,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun CircularModelGauge(
    isReady: Boolean,
    isDownloading: Boolean,
    progress: Float,
    animationsEnabled: Boolean = true
) {
    val targetSweep = when {
        isDownloading -> (progress.coerceIn(0.05f, 0.98f) * 360f)
        isReady -> 360f
        else -> 18f
    }
    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = tween(durationMillis = if (animationsEnabled) 450 else 80, easing = FastOutSlowInEasing),
        label = "GaugeSweep"
    )
    val spinAngle = if (isDownloading && animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "GaugeSpin")
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "SpinAngle"
        ).value
    } else {
        0f
    }

    val ringColor = when {
        isDownloading -> AppPalette.ElectricCyan
        isReady -> AppPalette.EmeraldGreen
        else -> AppPalette.AccentAmber
    }

    Box(
        modifier = Modifier.size(102.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (size.minDimension <= 10f) return@Canvas
            val strokePx = 7.dp.toPx()
            val inset = strokePx + 4.dp.toPx()
            val arcW = (size.width - inset * 2f).coerceAtLeast(4f)
            val arcH = (size.height - inset * 2f).coerceAtLeast(4f)
            val arcSize = Size(arcW, arcH)
            val topLeft = Offset(inset, inset)

            if (isReady || isDownloading) {
                val glowR = (size.minDimension * 0.52f).coerceAtLeast(4f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(ringColor.copy(alpha = 0.24f), Color.Transparent),
                        center = center,
                        radius = glowR
                    ),
                    radius = glowR,
                    center = center
                )
            }

            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx)
            )

            drawArc(
                color = ringColor,
                startAngle = if (isDownloading) spinAngle - 90f else -90f,
                sweepAngle = animatedSweep.coerceIn(1f, 360f),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    isDownloading -> "${(progress.coerceIn(0f, 1f) * 100f).roundToInt()}%"
                    isReady -> "100%"
                    else -> "0%"
                },
                color = if (isReady) AppPalette.EmeraldGreen else AppPalette.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when {
                    isDownloading -> "מוריד"
                    isReady -> "מוכן"
                    else -> "לא הורד"
                },
                color = AppPalette.TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
internal fun GlowingDownArrow(
    active: Boolean,
    animationsEnabled: Boolean = true
) {
    val glowAlpha by animateFloatAsState(
        targetValue = if (active) 0.50f else 0.20f,
        animationSpec = tween(250),
        label = "ArrowGlow"
    )
    val bobOffset = if (active && animationsEnabled) {
        val inf = rememberInfiniteTransition(label = "ArrowBob")
        inf.animateFloat(
            initialValue = -2.5f,
            targetValue = 2.5f,
            animationSpec = infiniteRepeatable(
                animation = tween(900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "BobY"
        ).value
    } else {
        0f
    }

    Canvas(
        modifier = Modifier
            .size(34.dp)
            .offset(y = bobOffset.dp)
    ) {
        if (size.minDimension <= 4f) return@Canvas
        val cx = size.width / 2f
        val glowR = (size.minDimension * 0.55f).coerceAtLeast(4f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AppPalette.ElectricCyan.copy(alpha = glowAlpha), Color.Transparent),
                center = Offset(cx, size.height / 2f),
                radius = glowR
            ),
            radius = glowR,
            center = Offset(cx, size.height / 2f)
        )
        val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val arrowPath = Path().apply {
            moveTo(cx, size.height * 0.18f)
            lineTo(cx, size.height * 0.78f)
            moveTo(cx - size.width * 0.22f, size.height * 0.56f)
            lineTo(cx, size.height * 0.80f)
            lineTo(cx + size.width * 0.22f, size.height * 0.56f)
        }
        drawPath(arrowPath, color = AppPalette.ElectricBlueLight, style = stroke)
    }
}

@Composable
internal fun ShimmerTranslationResultBox(
    isLoading: Boolean,
    translatedText: String,
    placeholderText: String,
    revealKey: Int,
    animationsEnabled: Boolean = true
) {
    val shimmerPhase = if (isLoading && animationsEnabled) {
        val infinite = rememberInfiniteTransition(label = "ResultShimmer")
        infinite.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "ShimmerPhase"
        ).value
    } else {
        0.5f
    }

    val borderBrush = if (isLoading || translatedText.isNotEmpty()) {
        Brush.horizontalGradient(
            colors = listOf(
                AppPalette.ElectricCyan.copy(alpha = 0.55f),
                AppPalette.ElectricBlue.copy(alpha = 0.25f),
                AppPalette.GlowPurple.copy(alpha = 0.45f)
            )
        )
    } else {
        Brush.horizontalGradient(colors = listOf(AppPalette.GlassBorder, AppPalette.GlassBorder))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppPalette.GlassCardInner)
            .border(1.dp, borderBrush, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.04f),
                                AppPalette.ElectricCyan.copy(alpha = 0.32f),
                                Color.White.copy(alpha = 0.04f)
                            ),
                            start = Offset(shimmerPhase * 420f, 0f),
                            end = Offset((shimmerPhase + 0.8f) * 420f, 80f)
                        )
                    )
            )
        } else if (translatedText.isEmpty()) {
            Text(
                text = placeholderText,
                color = AppPalette.TextSecondary.copy(alpha = 0.7f),
                fontSize = 14.sp
            )
        } else {
            AnimatedContent(
                targetState = revealKey to translatedText,
                transitionSpec = {
                    if (animationsEnabled) {
                        (fadeIn(tween(250, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(tween(250, easing = FastOutSlowInEasing)) { it / 6 }) togetherWith
                            fadeOut(tween(120))
                    } else {
                        fadeIn(tween(80)) togetherWith fadeOut(tween(80))
                    }
                },
                label = "TextReveal"
            ) { (_, text) ->
                Text(
                    text = text,
                    color = AppPalette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

@Composable
internal fun ObsidianToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) AppPalette.ElectricBlue else Color(0xFF23293A),
        animationSpec = tween(durationMillis = 200),
        label = "SwitchTrack"
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color(0xFF3B1F9E) else Color(0xFF94A3B8),
        animationSpec = tween(durationMillis = 200),
        label = "SwitchThumb"
    )
    val thumbOffset: Dp by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "SwitchOffset"
    )

    Box(
        modifier = Modifier
            .width(50.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .border(
                width = 1.dp,
                color = if (checked) AppPalette.ElectricBlueLight.copy(alpha = 0.55f) else AppPalette.GlassBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(thumbColor)
                .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
        )
    }
}

@Composable
internal fun FloatingGlassBottomBar(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val tabs = AppTab.entries
    val selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
    val view = LocalView.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(Color(0xE8131826))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(32.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        if (maxWidth <= 0.dp) return@BoxWithConstraints
        val tabWidth = maxWidth / tabs.size
        val spotlightOffset by animateDpAsState(
            targetValue = tabWidth * selectedIndex,
            animationSpec = if (animationsEnabled) {
                tween(durationMillis = 250, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 1)
            },
            label = "SpotlightOffset"
        )

        Box(
            modifier = Modifier
                .offset(x = spotlightOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AppPalette.ElectricBlue.copy(alpha = 0.36f),
                            AppPalette.ElectricBlue.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        radius = 150f
                    )
                )
                .border(
                    width = 1.dp,
                    color = AppPalette.ElectricBlueLight.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(24.dp)
                )
        )

        Row(modifier = Modifier.fillMaxSize()) {
            tabs.forEach { tab ->
                val isSelected = tab == selectedTab
                val iconJumpY by animateDpAsState(
                    targetValue = if (isSelected && animationsEnabled) (-2).dp else 0.dp,
                    animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
                    label = "IconJump"
                )
                val itemColor by animateColorAsState(
                    targetValue = if (isSelected) AppPalette.ElectricBlueLight else AppPalette.TextSecondary,
                    animationSpec = tween(220),
                    label = "TabTint"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            if (!isSelected) {
                                CrashGuard.safeRun("TabHaptic") {
                                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                }
                                onTabSelected(tab)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    NavTabIcon(
                        tab = tab,
                        filled = isSelected,
                        tint = itemColor,
                        modifier = Modifier
                            .offset(y = iconJumpY)
                            .size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = tab.label,
                        color = if (isSelected) AppPalette.TextPrimary else AppPalette.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun NavTabIcon(
    tab: AppTab,
    filled: Boolean,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 4f || h <= 4f) return@Canvas
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when (tab) {
            AppTab.HOME -> {
                val house = Path().apply {
                    moveTo(w * 0.5f, h * 0.14f)
                    lineTo(w * 0.88f, h * 0.46f)
                    lineTo(w * 0.80f, h * 0.46f)
                    lineTo(w * 0.80f, h * 0.86f)
                    lineTo(w * 0.20f, h * 0.86f)
                    lineTo(w * 0.20f, h * 0.46f)
                    lineTo(w * 0.12f, h * 0.46f)
                    close()
                }
                if (filled) {
                    drawPath(house, color = tint)
                    drawRoundRect(
                        color = AppPalette.BgObsidian,
                        topLeft = Offset(w * 0.42f, h * 0.58f),
                        size = Size(w * 0.16f, h * 0.28f),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                } else {
                    drawPath(house, color = tint, style = stroke)
                }
            }

            AppTab.MODELS -> {
                if (filled) {
                    drawRoundRect(
                        color = tint.copy(alpha = 0.55f),
                        topLeft = Offset(w * 0.16f, h * 0.14f),
                        size = Size(w * 0.48f, h * 0.56f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.36f, h * 0.30f),
                        size = Size(w * 0.48f, h * 0.56f),
                        cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                    )
                } else {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.16f, h * 0.14f),
                        size = Size(w * 0.46f, h * 0.54f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = stroke
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.38f, h * 0.32f),
                        size = Size(w * 0.46f, h * 0.54f),
                        cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                        style = stroke
                    )
                }
            }

            AppTab.TEST -> {
                val radius = w * 0.38f
                val center = Offset(w / 2f, h / 2f)
                if (filled) {
                    drawCircle(color = tint, radius = radius, center = center)
                } else {
                    drawCircle(color = tint, radius = radius, center = center, style = stroke)
                }
                val check = Path().apply {
                    moveTo(w * 0.34f, h * 0.51f)
                    lineTo(w * 0.46f, h * 0.63f)
                    lineTo(w * 0.68f, h * 0.38f)
                }
                drawPath(
                    check,
                    color = if (filled) AppPalette.BgObsidian else tint,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            AppTab.SETTINGS -> {
                val center = Offset(w / 2f, h / 2f)
                val outerR = w * 0.39f
                val innerR = w * 0.27f
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val sx = center.x + (innerR * cos(angle)).toFloat()
                    val sy = center.y + (innerR * sin(angle)).toFloat()
                    val ex = center.x + (outerR * cos(angle)).toFloat()
                    val ey = center.y + (outerR * sin(angle)).toFloat()
                    drawLine(
                        color = tint,
                        start = Offset(sx, sy),
                        end = Offset(ex, ey),
                        strokeWidth = 2.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
                if (filled) {
                    drawCircle(color = tint, radius = w * 0.27f, center = center)
                    drawCircle(color = AppPalette.BgObsidian, radius = w * 0.11f, center = center)
                } else {
                    drawCircle(color = tint, radius = w * 0.25f, center = center, style = stroke)
                    drawCircle(color = tint, radius = w * 0.09f, center = center, style = stroke)
                }
            }
        }
    }
}

/**
 * מנוע אפקטים מוקשח לשכבת ה-Overlay — ללא setShadowLayer בעייתי על GPU,
 * ועם הגנות מלאות נגד ערכי גבול ב-LinearGradient.
 */
class OverlayEffectController(
    private val onInvalidate: () -> Unit
) {
    private enum class Mode { NONE, FULL_WAVE, BLOCK_FADE }

    private var mode: Mode = Mode.NONE
    private var animator: ValueAnimator? = null
    private var fraction: Float = 1f

    private val waveBandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val waveHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val waveCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.parseColor("#E0F2FE")
        style = Paint.Style.FILL
    }
    private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
    }

    private val sparkSeeds = List(14) { i ->
        Triple(
            ((i * 43 + 17) % 94 + 3) / 100f,
            ((i * 29 + 11) % 100) / 100f,
            2.2f + (i % 3) * 1.4f
        )
    }

    fun startFullScanWave() {
        CrashGuard.safeRun("OverlayEffect.startWave") {
            animator?.cancel()
            mode = Mode.FULL_WAVE
            fraction = 0.01f

            animator = ValueAnimator.ofFloat(0.01f, 1.08f).apply {
                duration = 560L
                interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                addUpdateListener { anim ->
                    fraction = (anim.animatedValue as? Float) ?: 1f
                    onInvalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mode = Mode.NONE
                        fraction = 1f
                        onInvalidate()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        mode = Mode.NONE
                        fraction = 1f
                    }
                })
                start()
            }
        }
    }

    fun startBlockReveal() {
        CrashGuard.safeRun("OverlayEffect.startReveal") {
            animator?.cancel()
            mode = Mode.BLOCK_FADE
            fraction = 0f

            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 240L
                interpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)
                addUpdateListener { anim ->
                    fraction = (anim.animatedValue as? Float) ?: 1f
                    onInvalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        mode = Mode.NONE
                        fraction = 1f
                        onInvalidate()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        mode = Mode.NONE
                        fraction = 1f
                    }
                })
                start()
            }
        }
    }

    fun stop() {
        CrashGuard.safeRun("OverlayEffect.stop") {
            animator?.cancel()
        }
        animator = null
        mode = Mode.NONE
        fraction = 1f
    }

    fun blockRevealProgress(rectTop: Float, totalHeight: Float): Float {
        return when (mode) {
            Mode.NONE -> 1f
            Mode.BLOCK_FADE -> fraction.coerceIn(0f, 1f)
            Mode.FULL_WAVE -> {
                val waveY = fraction * totalHeight.coerceAtLeast(1f)
                ((waveY - rectTop) / 150f).coerceIn(0f, 1f)
            }
        }
    }

    fun blockScale(reveal: Float): Float {
        return if (mode == Mode.NONE) 1f else 0.96f + 0.04f * reveal.coerceIn(0f, 1f)
    }

    fun drawBlockHighlightIfAnimating(canvas: AndroidCanvas, rect: RectF, reveal: Float) {
        if (mode == Mode.NONE || reveal <= 0.02f || reveal >= 0.98f) return
        val peak = sin(reveal * PI).toFloat().coerceIn(0f, 1f)
        val alpha = (peak * 160f).toInt().coerceIn(0, 255)
        if (alpha <= 4) return
        highlightStrokePaint.color = AndroidColor.argb(alpha, 56, 189, 248)
        canvas.drawRoundRect(rect, 10f, 10f, highlightStrokePaint)
    }

    fun drawScanWave(canvas: AndroidCanvas, width: Float, height: Float) {
        if (mode != Mode.FULL_WAVE || width <= 2f || height <= 2f) return
        val waveY = fraction * height
        if (waveY <= 4f || waveY >= height + 90f) return

        val trailHeight = 155f
        val bandTop = (waveY - trailHeight).coerceAtLeast(0f)
        if (waveY - bandTop <= 2f) return

        waveBandPaint.shader = LinearGradient(
            0f,
            bandTop,
            0f,
            waveY,
            intArrayOf(
                AndroidColor.TRANSPARENT,
                AndroidColor.parseColor("#2E7C3AED"),
                AndroidColor.parseColor("#553B82F6"),
                AndroidColor.parseColor("#AA38BDF8")
            ),
            floatArrayOf(0f, 0.35f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, bandTop, width, waveY, waveBandPaint)

        // Soft GPU-safe halo around the core beam instead of setShadowLayer
        val haloTop = (waveY - 14f).coerceAtLeast(0f)
        val haloBottom = (waveY + 14f).coerceAtMost(height)
        if (haloBottom - haloTop > 2f) {
            waveHaloPaint.shader = LinearGradient(
                0f,
                haloTop,
                0f,
                haloBottom,
                intArrayOf(
                    AndroidColor.TRANSPARENT,
                    AndroidColor.parseColor("#CC38BDF8"),
                    AndroidColor.TRANSPARENT
                ),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, haloTop, width, haloBottom, waveHaloPaint)
        }
        canvas.drawRect(0f, waveY - 2.5f, width, waveY + 1.5f, waveCorePaint)

        for ((xRatio, distRatio, radius) in sparkSeeds) {
            val sx = width * xRatio
            val sy = waveY - (distRatio * trailHeight * 0.85f)
            if (sy < 0f || sy > height) continue
            val sparkAlpha = ((1f - distRatio) * 210f).toInt().coerceIn(0, 255)
            sparkPaint.color = AndroidColor.argb(sparkAlpha, 186, 230, 253)
            canvas.drawCircle(sx, sy, radius, sparkPaint)
        }
    }
}

internal fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    return start + (stop - start) * t
}

internal fun formatDownloadSpeed(bytesPerSec: Long): String {
    val kb = bytesPerSec.coerceAtLeast(0L) / 1024.0
    return if (kb >= 1024.0) {
        String.format(Locale.US, "%.1f MB/s", kb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}
