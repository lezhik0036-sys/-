package app.mama.backend.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Production must not advertise real SMS escape while any link is missing. */
class ProductionReadinessTest {
    @Test fun `SMS provider grant and DB alone cannot declare ready`() {
        assertFalse(Readiness(grant = true, db = true, sms = true).ready)
        assertFalse(Readiness(
            grant = true, db = true, sms = true,
            destination = true,
        ).ready)
        assertFalse(Readiness(
            grant = true, db = true, sms = true,
            authoritativeReceiptDelivery = true,
        ).ready)
    }

    @Test fun `production ready only when every independent service is verified`() {
        val full = Readiness(
            grant = true, db = true, sms = true,
            destination = true, authoritativeReceiptDelivery = true,
        )
        assertTrue(full.ready)
        assertFalse(full.copy(grant = false).ready)
        assertFalse(full.copy(db = false).ready)
        assertFalse(full.copy(sms = false).ready)
        assertFalse(full.copy(destination = false).ready)
        assertFalse(full.copy(authoritativeReceiptDelivery = false).ready)
    }
}
