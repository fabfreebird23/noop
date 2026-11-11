package com.noop.onyx

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ui.BarTab
import com.noop.ui.Destination
import com.noop.ui.DrawerGroup
import com.noop.ui.KeyMetric
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.TodaySection
import java.util.Locale

/**
 * Onyx Focus: NOOP with only the numbers that get looked at. FORK-ONLY.
 *
 * NOOP surfaces everything it computes: eleven Today sections, a fourteen-card metric catalogue, 38
 * screens behind the bar. The wearer's routine is four things: morning readiness, last night's sleep,
 * the day's strain and workouts, and trends over time. So:
 *
 *  - Today is the three score rings, ONE sentence of guidance, and five numbers: HRV, resting HR,
 *    respiratory rate, blood oxygen, skin temperature. The rings already carry Recovery / Strain / Sleep,
 *    so the metric grid stops repeating them.
 *  - The bar is one tab per routine: Today · Sleep · Strain · Trends · More. Strain opens Workouts.
 *  - Everything else stays one tap away under More. Coach, no longer a tab, gets a row there; Workouts,
 *    now a tab, leaves it, so no destination appears twice.
 *  - The once-a-morning "Open Journal" sheet is off. The journal is still in More.
 *
 * Nothing is deleted and no preference is rewritten: flip [ENABLED] and the build is upstream's layout
 * again, with the wearer's own saved order and hidden sections intact.
 *
 * Layout, not colour, so it is independent of [OnyxSkin] and applies in Light mode too.
 */
object OnyxFocus {

    const val ENABLED = true

    val active: Boolean get() = ENABLED

    // MARK: - Today

    private val focusSections = listOf(TodaySection.HERO, TodaySection.SYNTHESIS, TodaySection.KEY_METRICS)

    /** The Today sections to draw. [visible] is upstream's own result (saved order minus hidden). */
    fun sections(visible: List<TodaySection>): List<TodaySection> = if (active) focusSections else visible

    /** The five numbers under the rings, or null for the wearer's own Key Metrics selection. Five, not
     *  six: under the cap, so the grid never folds anything behind "Show all metrics". */
    val keyMetrics: List<KeyMetric>?
        get() = if (!active) null else listOf(
            KeyMetric.HRV, KeyMetric.RESTING_HR,
            KeyMetric.RESPIRATORY, KeyMetric.BLOOD_OXYGEN, KeyMetric.SKIN_TEMP,
        )

    /** The "why is Strain 0?" footnote under the hero. [DailyLine] says what to do instead. */
    val hidesCardioFootnote: Boolean get() = active

    /**
     * The one sentence under the rings, in place of the Synthesis card and its chips.
     *
     * The strain target is the SAME [target] fraction the Strain ring draws its target arc from
     * (`optimalFractionRange(recovery)`), passed in rather than recomputed, so the sentence and the ring
     * cannot quote two different bands.
     */
    @Composable
    fun DailyLine(recovery: Double?, target: ClosedFloatingPointRange<Float>?, strainMax: Double) {
        val text = buildAnnotatedString {
            if (recovery == null) {
                append("Recovery is still building. It scores once last night's sleep syncs.")
                return@buildAnnotatedString
            }
            val (word, advice) = when {
                recovery >= 67 -> "Recovered" to "a hard day fits"
                recovery >= 34 -> "Moderate recovery" to "keep it steady"
                else -> "Low recovery" to "keep strain light"
            }
            withStyle(SpanStyle(color = Palette.recoveryColor(recovery), fontWeight = FontWeight.SemiBold)) {
                append(word)
            }
            append(" — $advice")
            if (target != null) {
                val lo = target.start * strainMax
                val hi = target.endInclusive * strainMax
                append(String.format(Locale.getDefault(), ". Aim for %.0f–%.0f strain.", lo, hi))
            } else {
                append(".")
            }
        }
        Text(
            text = text,
            style = NoopType.subhead,
            color = Palette.textSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }

    /** The pinned "Synced from: …" footer. Provenance, not a metric; More → Data Sources has it. */
    val hidesSourcesFooter: Boolean get() = active

    // MARK: - Navigation

    /** The bar's tabs, in order, or null for upstream's. More is appended by the bar itself. */
    internal val barTabs: List<BarTab>?
        get() = if (!active) null else listOf(
            BarTab(Destination.Today, Icons.Outlined.GridView, R.string.nav_today),
            BarTab(Destination.Sleep, Icons.Filled.Bedtime, R.string.nav_sleep),
            // "Strain" through the score-name overlay; the screen is Workouts (strain per session,
            // today's sessions, the 13-week heatmap).
            BarTab(Destination.Workouts, Icons.Filled.Bolt, R.string.today_metric_effort),
            BarTab(Destination.Trends, Icons.AutoMirrored.Filled.TrendingUp, R.string.nav_trends),
        )

    /** More's groups: Coach gains a row (it left the bar), Workouts loses one (it joined it). */
    internal fun moreGroups(groups: List<DrawerGroup>): List<DrawerGroup> = if (!active) groups else groups.map { g ->
        when {
            Destination.InsightsHub in g.items -> g.copy(items = listOf(Destination.Coach) + g.items)
            Destination.Workouts in g.items -> g.copy(items = g.items - Destination.Workouts)
            else -> g
        }
    }

    // MARK: - Names

    private val nameSwaps = listOf(
        Regex("\\bCharge\\b") to "Recovery", Regex("\\bEffort\\b") to "Strain", Regex("\\bRest\\b") to "Sleep",
        Regex("\\beffort\\b") to "strain",
    )

    /**
     * WHOOP's score names over NOOP's, for text built in code rather than read from resources (the
     * resource overlay in src/release covers those). Called ONLY at sites already checked to be about the
     * scores: applied blindly it would rename the battery's "charge" and the interval timer's "Rest".
     * Follows [OnyxSkin.ENABLED], the same switch as the overlay's names, not the layout switch.
     */
    fun scoreNames(text: String): String =
        if (!OnyxSkin.ENABLED) text else nameSwaps.fold(text) { acc, (re, to) -> re.replace(acc, to) }

    // MARK: - Prompts

    /** The once-a-morning "Open Journal" sheet on the Sleep screen. */
    val showsMorningJournalPrompt: Boolean get() = !active
}
