package com.noop.onyx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noop.analytics.VitalityEngine
import com.noop.data.DailyMetric
import com.noop.ui.AppViewModel
import com.noop.ui.NoopCard
import com.noop.ui.NoopType
import com.noop.ui.Palette
import com.noop.ui.ProfileStore
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln

/**
 * A WHOOP-style Body Age card for Today. FORK-ONLY.
 *
 * Builds nothing new to compute. NOOP's VitalityEngine is an independent implementation of the published
 * method WHOOP's Healthspan also uses: each habit or vital maps to its all-cause-mortality hazard ratio,
 * summed with an overlap correction and converted to years through the Gompertz doubling time. NOOP
 * already stores the weekly result as `body_age`. This card is a front door to that number:
 *
 *  - the headline is the SAME stored value the Health screen's Vitality section reads, through the same
 *    repository call, so the two screens cannot quote different ages;
 *  - "what's moving it" uses the same 7-day aggregation that section (and the engine pass) uses;
 *  - Pace of Aging, which NOOP does not have, is derived from that stored weekly history — see [pace].
 */
object OnyxBodyAge {

    /** Weekly history Pace is fitted over. WHOOP's equivalent also looks back about six months. */
    const val PACE_WINDOW_DAYS = 182L

    /** Below these, Pace is reported as BUILDING rather than as a number. On 14 weeks of real data the
     *  week-to-week wobble alone fitted to "1.8x faster than time": noise presented as a finding. */
    const val PACE_MIN_POINTS = 16
    const val PACE_MIN_SPAN_DAYS = 150L

    /** Display clamp, WHOOP's range: a pace below -1x or above 3x is not a pace, it is a fitting artefact. */
    const val PACE_MIN = -1.0
    const val PACE_MAX = 3.0

    /** Years an engine contribution moves Body Age. Same constants as VitalityEngine.compute (0.75 overlap
     *  shrink, ln2 / 8 per year), so the per-factor years sum the way the headline does. */
    fun years(c: VitalityEngine.Contribution): Double = c.lnHazard * 0.75 / (ln(2.0) / 8.0)

    sealed interface Pace {
        /** Body Age years gained per calendar year: 1.0 = in step with time, below 1 = slower. */
        data class Ready(val ratio: Double, val weeks: Int) : Pace
        /** Not enough history to say; [weeksToGo] more weeks of wear until it can. */
        data class Building(val weeksToGo: Int) : Pace
    }

    /**
     * Pace of Aging from the weekly `body_age` history.
     *
     * Body Age = age + Δ, so its rate against the calendar is 1 + dΔ/dt. Δ is taken against the age the
     * wearer actually WAS that week ([ageOn]) — the stored value used whole-number age at compute time, so
     * a birthday inside the window would otherwise read as a year of aging in one week. dΔ/dt is the
     * least-squares slope through every point in the window, not two endpoints, so one odd week cannot
     * swing it alone.
     */
    fun pace(rows: List<Pair<LocalDate, Double>>, dob: LocalDate, today: LocalDate): Pace {
        val inWindow = rows.filter { !it.first.isBefore(today.minusDays(PACE_WINDOW_DAYS)) && !it.first.isAfter(today) }
            .sortedBy { it.first }
        val first = inWindow.firstOrNull()?.first
        val span = if (first == null) 0L else ChronoUnit.DAYS.between(first, today)
        if (inWindow.size < PACE_MIN_POINTS || span < PACE_MIN_SPAN_DAYS) {
            val bySpan = ceil((PACE_MIN_SPAN_DAYS - span).coerceAtLeast(0L) / 7.0).toInt()
            val byCount = (PACE_MIN_POINTS - inWindow.size).coerceAtLeast(0)
            return Pace.Building(maxOf(bySpan, byCount, 1))
        }
        val t0 = inWindow.first().first
        val xs = inWindow.map { ChronoUnit.DAYS.between(t0, it.first) / 365.25 }
        val ys = inWindow.map { (d, bodyAge) -> bodyAge - ageOn(dob, d) }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return Pace.Building(1)
        val slope = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
        return Pace.Ready((1.0 + slope).coerceIn(PACE_MIN, PACE_MAX), inWindow.size)
    }

    /** Whole years of age on [day] — the same floor the stored Body Age used. */
    fun ageOn(dob: LocalDate, day: LocalDate): Int = Period.between(dob, day).years

    /** The engine's per-factor contributions over the last 7 days, aggregated exactly as the Health screen's
     *  Vitality section and the weekly engine pass do: MEDIAN resting HR and HRV, MEAN sleep and steps. */
    fun contributions(days: List<DailyMetric>, age: Int): List<VitalityEngine.Contribution> {
        val last7 = days.takeLast(7)
        val nights = last7.mapNotNull { it.totalSleepMin }.map { it / 60.0 }.filter { it > 0 }
        val hrvs = last7.mapNotNull { it.avgHrv }
        val rhrs = last7.mapNotNull { it.restingHr }.map { it.toDouble() }
        val steps = last7.mapNotNull { it.steps }.map { it.toDouble() }
        fun mean(a: List<Double>) = if (a.isEmpty()) null else a.average()
        fun median(a: List<Double>): Double? {
            if (a.isEmpty()) return null
            val s = a.sorted(); val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
        }
        return VitalityEngine.contributions(VitalityEngine.Inputs(
            chronoAge = age.toDouble(), restingHR = median(rhrs), sleepHours = mean(nights),
            sleepConsistency = VitalityEngine.sleepConsistency(nights),
            rmssd = median(hrvs), rmssdNorm = VitalityEngine.rmssdNorm(age.toDouble()), steps = mean(steps)))
    }

    @Composable
    fun Card(viewModel: AppViewModel, days: List<DailyMetric>, profile: ProfileStore) {
        var bodyAge by remember { mutableStateOf<Double?>(null) }
        var pace by remember { mutableStateOf<Pace?>(null) }
        val age = profile.age
        val dob = remember(profile.dateOfBirthMillis) {
            Instant.ofEpochMilli(profile.dateOfBirthMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        LaunchedEffect(days) {
            val today = LocalDate.now()
            // The Health screen's exact read, so both screens show one number.
            bodyAge = runCatching {
                viewModel.repo.latestMetricComputedUnion(viewModel.activeStrapId, "body_age")?.value
            }.getOrNull()
            pace = runCatching {
                val rows = viewModel.repo.metricSeriesComputedUnion(
                    viewModel.activeStrapId, "body_age",
                    today.minusDays(PACE_WINDOW_DAYS).toString(), today.toString(),
                ).map { LocalDate.parse(it.day) to it.value }
                pace(rows, dob, today)
            }.getOrNull()
        }
        val drivers = remember(days, age) {
            contributions(days, age).sortedByDescending { abs(years(it)) }.take(3)
        }
        val ba = bodyAge ?: return // nothing until the engine has stored a week (it needs 3+ inputs)

        val diff = age - ba
        val younger = diff >= 0
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BODY AGE", style = NoopType.overline, color = Palette.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Text("WEEKLY ESTIMATE", style = NoopType.overline, color = Palette.textTertiary)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(Locale.getDefault(), "%.1f", ba), style = NoopType.display(56f), color = Palette.textPrimary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Text(
                            String.format(Locale.getDefault(), "%.1f years %s", abs(diff), if (younger) "younger" else "older"),
                            style = NoopType.headline,
                            color = if (younger) Palette.statusPositive else Palette.statusWarning,
                        )
                        Text("than your $age", style = NoopType.subhead, color = Palette.textTertiary)
                    }
                }
                HorizontalDivider(color = Palette.hairline)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Pace of aging", style = NoopType.subhead, color = Palette.textSecondary)
                    Spacer(Modifier.weight(1f))
                    when (val p = pace) {
                        is Pace.Ready -> Text(
                            String.format(Locale.getDefault(), "%.1f× · %s", p.ratio, if (p.ratio <= 1.0) "slower than time" else "faster than time"),
                            style = NoopType.subhead,
                            color = if (p.ratio <= 1.0) Palette.statusPositive else Palette.statusWarning,
                        )
                        is Pace.Building -> Text(
                            "building · ${p.weeksToGo} more ${if (p.weeksToGo == 1) "week" else "weeks"} of wear",
                            style = NoopType.subhead,
                            color = Palette.textTertiary,
                        )
                        null -> Text("—", style = NoopType.subhead, color = Palette.textTertiary)
                    }
                }
                drivers.forEach { c ->
                    val y = years(c)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(c.label, style = NoopType.footnote, color = Palette.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            String.format(Locale.getDefault(), "%+.1f yr", y),
                            style = NoopType.footnote,
                            // Positive years age you; negative ones are working for you.
                            color = if (y <= 0) Palette.statusPositive else Palette.statusWarning,
                        )
                    }
                }
                Text(
                    "A wellness estimate from your habits, not a clinical age.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}
