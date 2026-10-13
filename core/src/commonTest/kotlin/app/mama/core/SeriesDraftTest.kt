package app.mama.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.LocalTime

class SeriesDraftTest {
    @Test
    fun `a new draft starts now, to the minute, in the chosen zone - not at the previous series' 23_00`() {
        val now = t("2026-10-12T12:57:42Z") // 15:57:42 Moscow
        val (start, end) = SeriesDraft.freshTimes(now, MSK)
        assertEquals(LocalTime(15, 57), start)
        assertEquals(LocalTime(23, 57), end)
        assertEquals(LocalTime(14, 57), SeriesDraft.freshTimes(now, BERLIN).first)
    }

    @Test
    fun `late evening wraps past midnight`() {
        val (start, end) = SeriesDraft.freshTimes(t("2026-10-12T19:30:10Z"), MSK) // 22:30 Moscow
        assertEquals(LocalTime(22, 30), start)
        assertEquals(LocalTime(6, 30), end)
    }
}
