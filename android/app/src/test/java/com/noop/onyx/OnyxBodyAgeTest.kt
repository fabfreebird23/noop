package com.noop.onyx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pace of Aging is a slope through noisy weekly estimates, so most of what matters is when NOT to show it. */
class OnyxBodyAgeTest {

    private val today = LocalDate.of(2026, 11, 11)
    private val dob = LocalDate.of(1990, 1, 22)

    /** One weekly row per Saturday going back [weeks], body age = age that day + [delta](week index). */
    private fun weekly(weeks: Int, birth: LocalDate = dob, delta: (Int) -> Double): List<Pair<LocalDate, Double>> =
        (0 until weeks).map { i ->
            val d = today.minusWeeks((weeks - 1 - i).toLong())
            // Stored the way the engine stores it: whole-number age THAT week, plus the delta.
            d to (OnyxBodyAge.ageOn(birth, d) + delta(i))
        }

    @Test
    fun theRealFourteenWeeksReadAsBuildingNotAsANumber() {
        // The phone's actual history: 11 rows over ~14 weeks, wobbling ±0.5 yr. Fitted, that wobble reads as
        // "1.8x faster than time", so it must not be fitted yet.
        val wobble = listOf(-2.79, -2.17, -1.78, -2.52, -2.49, -1.86, -1.89, -2.42, -2.57, -1.65, -2.32)
        val rows = wobble.mapIndexed { i, dlt ->
            val d = today.minusDays(98L - i * 9L)
            d to (OnyxBodyAge.ageOn(dob, d) + dlt)
        }
        val p = OnyxBodyAge.pace(rows, dob, today)
        assertTrue("thin history must not produce a number: $p", p is OnyxBodyAge.Pace.Building)
        assertTrue((p as OnyxBodyAge.Pace.Building).weeksToGo in 5..8)
    }

    @Test
    fun aSteadyBodyAgeAgesInStepWithTime() {
        val p = OnyxBodyAge.pace(weekly(24) { -2.3 }, dob, today)
        p as OnyxBodyAge.Pace.Ready
        assertEquals(1.0, p.ratio, 0.01)
    }

    @Test
    fun improvingHabitsReadAsSlowerThanTime() {
        // Δ falls 0.5 yr across ~23 weeks (0.44 yr), so body age moves ~0.56 yr per calendar year... minus 1.
        val p = OnyxBodyAge.pace(weekly(24) { i -> -2.0 - i * (0.5 / 23) }, dob, today)
        p as OnyxBodyAge.Pace.Ready
        assertTrue("expected slower than time, got ${p.ratio}", p.ratio < 1.0)
    }

    @Test
    fun aBirthdayInsideTheWindowIsNotAYearOfAging() {
        // Stored body age carries whole-number age, so it steps +1 on the birthday. Taking Δ against the age
        // the wearer was THAT week cancels the step; a naive fit on body age would read it as ~2x+.
        val birthdayToday = today.withYear(1990)
        val p = OnyxBodyAge.pace(weekly(24, birthdayToday) { -2.3 }, birthdayToday, today)
        p as OnyxBodyAge.Pace.Ready
        assertEquals(1.0, p.ratio, 0.01)
    }

    @Test
    fun aRunawayFitIsClampedToTheDisplayRange() {
        val p = OnyxBodyAge.pace(weekly(24) { i -> i * 1.0 }, dob, today)
        p as OnyxBodyAge.Pace.Ready
        assertEquals(OnyxBodyAge.PACE_MAX, p.ratio, 0.0)
    }
}
