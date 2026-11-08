package app.birdo.vpn.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** MR-2260: a free plan never shows a renewal date, and no past date is shown. */
class RenewalDateTest {
    private val today = LocalDate.of(2026, 11, 8)

    @Test
    fun freePlanShowsNoDateEvenWhenTheServerSendsOne() {
        assertNull(renewalDateToShow("RECON", "2026-08-01T00:00:00.000Z", today))
        assertNull(renewalDateToShow("recon", "2027-01-01T12:00:00Z", today))
    }

    @Test
    fun pastDateIsNeverShown() {
        assertNull(renewalDateToShow("OPERATIVE", "2026-08-01T00:00:00.000Z", today))
        assertNull(renewalDateToShow("OPERATIVE", "2026-11-07", today))
    }

    @Test
    fun futureDateOfAPaidPlanIsShown() {
        assertEquals("Dec 3, 2026", renewalDateToShow("OPERATIVE", "2026-12-03", today))
        assertEquals("Nov 8, 2026", renewalDateToShow("SOVEREIGN", "2026-11-08", today))
    }

    @Test
    fun missingOrUnparseableDateIsNull() {
        assertNull(renewalDateToShow("OPERATIVE", null, today))
        assertNull(renewalDateToShow("OPERATIVE", "not a date", today))
    }
}
