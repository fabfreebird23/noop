package com.noop.onyx

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.DarkTokens
import com.noop.ui.LightTokens
import com.noop.ui.Palette
import com.noop.ui.PaletteTokens

/**
 * Onyx — this fork's "glow in glass" skin. FORK-ONLY, like com.noop.dashboard: never proposed upstream.
 *
 * True-black canvas, recovery read in discrete green / yellow / red bands, a soft light in today's band
 * colour behind the screen, translucent glass cards over blue and violet ambient colour, glowing score
 * rings, and condensed display numerals. WHOOP's design LANGUAGE, not its marks: no logo, wordmark,
 * assets or proprietary typeface (Barlow Condensed is SIL OFL, licence beside this file).
 *
 * ## Why it is shaped like this
 *
 * Upstream ships 1,000+ commits a quarter, and the fork's value is that it merges cheaply. So every
 * decision lives HERE, and upstream files only gain one-line calls into this object:
 *
 *  - Theme.kt `NoopTheme`        → [tokensFor]             (the whole palette, ~1,700 call sites)
 *  - Theme.kt `recoveryStops`    → [recoveryStopsOrNull]   (banded rings)
 *  - Theme.kt `NoopType.*`       → [numeralFamily]         (condensed numerals)
 *  - Components.kt card surface  → [drawGlassCard]         (every card becomes glass)
 *  - Components.kt `GlowRing`    → [drawArcGlow]           (soft ring glow)
 *  - LiquidScreenSky.kt backdrop → [Backdrop]              (the ambient light behind each screen)
 *  - TodayScreen.kt hero         → [PublishHeroTint], [forcesRings]
 *
 * Score names (Recovery / Strain / Sleep in place of Charge / Effort / Rest) are a resource overlay in
 * `src/release/res`, so upstream's strings.xml is never edited either.
 *
 * Applies in DARK mode only; Light mode stays upstream's warm-paper theme, untouched.
 */
object OnyxSkin {

    /** Fork build switch. A build that wants upstream's look back flips this, nothing else. */
    const val ENABLED = true

    /** Live: true while the dark Onyx tokens are the active scheme. Snapshot-backed via Palette.active. */
    val active: Boolean get() = ENABLED && Palette.active === OnyxTokens

    // MARK: - Palette

    // WHOOP-style recovery bands. Fraction thresholds: red 0–33, yellow 34–66, green 67–100.
    val bandRed = Color(0xFFF2414F)
    val bandYellow = Color(0xFFF5D33B)
    val bandGreen = Color(0xFF1FD87A)

    private val glassWhite = Color(0xFFFFFFFF)

    /** The token set NoopTheme should activate. Onyx replaces upstream's DARK scheme only. */
    fun tokensFor(dark: Boolean): PaletteTokens = when {
        ENABLED && dark -> OnyxTokens
        dark -> DarkTokens
        else -> LightTokens
    }

    /** Banded recovery stops, or null to fall through to upstream's continuous ramp. Near-duplicate stops
     *  make the linear sampler step: a 60 reads plainly yellow instead of blending toward green. */
    fun recoveryStopsOrNull(): List<Pair<Float, Color>>? = if (!active) null else listOf(
        0.000f to bandRed, 0.335f to bandRed,
        0.337f to bandYellow, 0.665f to bandYellow,
        0.667f to bandGreen, 1.000f to bandGreen,
    )

    /** Sleep in its own violet, not on the recovery scale. NOOP tints the Sleep score with
     *  recoveryColor; banded, a 94 Sleep turned the same green as Recovery and the two read as one. */
    val sleepTint: Color? get() = if (active) Palette.restColor else null

    // MARK: - Type

    private val barlowCondensed by lazy {
        FontFamily(
            Font(R.font.onyx_barlow_condensed_medium, FontWeight.Medium),
            Font(R.font.onyx_barlow_condensed_semibold, FontWeight.SemiBold),
            Font(R.font.onyx_barlow_condensed_bold, FontWeight.Bold),
        )
    }

    /** Family for big numbers (display + gauge numerals), or null for upstream's sans. */
    val numeralFamily: FontFamily? get() = if (active) barlowCondensed else null

    // MARK: - Hero tint → backdrop glow

    /** Today's recovery band colour, published by the Today hero so the backdrop can glow in it. */
    var heroTint by mutableStateOf<Color?>(null)
        private set

    /** Call from the hero; a SideEffect, so composition never writes state it also reads. */
    @Composable
    fun PublishHeroTint(tint: Color?) {
        SideEffect { if (heroTint != tint) heroTint = tint }
    }

    /** Onyx always draws the Today scores as rings: the glow is a ring effect, and the liquid vessel is
     *  upstream's own identity rather than this skin's. */
    val forcesRings: Boolean get() = active

    // MARK: - Backdrop

    /**
     * The ambient light behind every liquid screen: a glow in today's recovery band near the top, Strain
     * blue drifting in from the right, Sleep violet from the lower left. Radial gradients are already soft,
     * so no blur pass is needed — this costs one Canvas and no layers.
     */
    @Composable
    fun Backdrop() {
        val tint = heroTint ?: Palette.chargeColor
        val strain = Palette.effortColor
        val sleep = Palette.restColor
        Canvas(
            Modifier
                .fillMaxSize()
                .background(Palette.surfaceBase)
                .clearAndSetSemantics {}, // decorative
        ) {
            val w = size.width
            val h = size.height
            glow(tint.copy(alpha = 0.30f), Offset(w * 0.5f, h * 0.15f), w * 0.95f)
            glow(strain.copy(alpha = 0.22f), Offset(w * 1.02f, h * 0.50f), w * 0.70f)
            glow(sleep.copy(alpha = 0.22f), Offset(-w * 0.05f, h * 0.86f), w * 0.75f)
        }
    }

    private fun DrawScope.glow(color: Color, center: Offset, radius: Float) {
        drawCircle(
            brush = Brush.radialGradient(listOf(color, Color.Transparent), center = center, radius = radius),
            radius = radius,
            center = center,
        )
    }

    // MARK: - Glass card

    /**
     * Frosted glass in place of upstream's flat raised fill: a faint top-lit white fill, a whisper of the
     * domain tint from the top-left, and a border that is brighter along the top edge, as if lit from above.
     * Scaled by the user's existing "Card transparency" setting ([opacity]) like upstream's surface.
     */
    fun drawGlassCard(scope: DrawScope, corner: CornerRadius, opacity: Float, tint: Color?) = with(scope) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(glassWhite.copy(alpha = 0.085f * opacity), glassWhite.copy(alpha = 0.045f * opacity)),
            ),
            cornerRadius = corner,
        )
        if (tint != null) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to tint.copy(alpha = 0.10f * opacity),
                        0.6f to Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                cornerRadius = corner,
            )
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(glassWhite.copy(alpha = 0.24f * opacity), glassWhite.copy(alpha = 0.07f * opacity)),
            ),
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
        )
    }

    // MARK: - Ring glow

    /**
     * A soft halo under a score ring's arc, in the arc's own colour. Same geometry as the caller's crisp
     * arc, wider and blurred.
     *
     * Upstream removed its ring glow (#2407) because the only stand-in available at minSdk 26 was a wider
     * arc with HARD edges, which read as a misaligned double edge. This uses a real BlurMaskFilter, which
     * hardware-accelerated canvases honour from API 28; below that it draws nothing rather than bring the
     * hard-edged artefact back.
     */
    fun drawArcGlow(scope: DrawScope, color: Color, topLeft: Offset, arcSize: Size, strokePx: Float, sweep: Float) {
        if (!active || sweep <= 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx * 1.5f
            strokeCap = android.graphics.Paint.Cap.ROUND
            this.color = color.copy(alpha = 0.60f).toArgb()
            maskFilter = BlurMaskFilter(strokePx * 1.2f, BlurMaskFilter.Blur.NORMAL)
        }
        scope.drawIntoCanvas {
            it.nativeCanvas.drawArc(
                topLeft.x, topLeft.y, topLeft.x + arcSize.width, topLeft.y + arcSize.height,
                -90f, sweep, false, paint,
            )
        }
    }
}

/**
 * Onyx's token set: upstream's dark scheme with a true-black canvas, translucent glass surfaces where the
 * surface is only ever a card, and Strain / Sleep worlds moved to blue / violet. Surfaces that back sheets
 * and menus (surfaceOverlay) stay OPAQUE on purpose — a translucent sheet over live content is unreadable.
 */
val OnyxTokens: PaletteTokens = DarkTokens.copy(
    surfaceBase = Color(0xFF030304),
    surfaceRaised = Color(0xFF111114),
    surfaceOverlay = Color(0xFF141417),
    surfaceInset = Color(0xFF0B0B0D),
    hairline = Color(0x22FFFFFF),
    hairlineStrong = Color(0x38FFFFFF),
    textPrimary = Color(0xFFF4F4F4),
    textSecondary = Color(0xFFD0D0D0),
    textTertiary = Color(0xFFA0A0A0),
    // Recovery ramp, for the places that sample it continuously rather than through recoveryStops.
    recovery000 = OnyxSkin.bandRed, recovery030 = OnyxSkin.bandRed, recovery055 = OnyxSkin.bandYellow,
    recovery078 = OnyxSkin.bandGreen, recovery100 = OnyxSkin.bandGreen,
    // Strain: blue.
    strain000 = Color(0xFF1E3F7A), strain033 = Color(0xFF2A5FB8), strain066 = Color(0xFF3D8BF5), strain100 = Color(0xFF7DB8FF),
    effortColor = Color(0xFF3D8BF5), effortDeep = Color(0xFF1E4FA8), effortBright = Color(0xFF7DB8FF), effortGlow = Color(0xFF3D8BF5),
    // Sleep: violet.
    restColor = Color(0xFF8E9CF8), restDeep = Color(0xFF4B5BE0), restBright = Color(0xFFC0C9FF), restGlow = Color(0xFF8E9CF8),
    sleepAwake = Color(0xFFE6E6E6), sleepLight = Color(0xFFB7C1FF), sleepDeep = Color(0xFF5566EC), sleepREM = Color(0xFFC99AF5),
    // Recovery (Charge) world: the band green as its neutral.
    chargeColor = OnyxSkin.bandGreen, chargeDeep = Color(0xFF12A85C), chargeBright = Color(0xFF4BEA97), chargeGlow = OnyxSkin.bandGreen,
    scenicCenter = Color(0xFF0A0A0C), scenicEdge = Color(0xFF000000), scenicStar = Color(0x55FFFFFF),
    cardFillTop = Color(0x16FFFFFF), cardFillBottom = Color(0x0AFFFFFF),
    heroFill = Color(0x14FFFFFF), heroBorder = Color(0x26FFFFFF),
)
