package app.mama.core

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SeriesDraftTest {
    @Test
    fun `a new draft starts now, to the minute, in the chosen zone - not at the previous series' 23_00`() {
        val now = t("2026-10-12T12:57:42Z") // 15:57:42 Moscow
        val (start, end) = SeriesDraft.freshTimes(now, MSK)
        assertEquals(LocalTime.of(15, 57), start)
        assertEquals(LocalTime.of(23, 57), end)
        assertEquals(LocalTime.of(14, 57), SeriesDraft.freshTimes(now, BERLIN).first)
    }

    @Test
    fun `late evening wraps past midnight`() {
        val (start, end) = SeriesDraft.freshTimes(t("2026-10-12T19:30:10Z"), MSK) // 22:30 Moscow
        assertEquals(LocalTime.of(22, 30), start)
        assertEquals(LocalTime.of(6, 30), end)
    }
}
