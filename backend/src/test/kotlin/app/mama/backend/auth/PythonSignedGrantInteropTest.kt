package app.mama.backend.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent Python cryptography Ed25519 vectors (PUBLIC key + signed
 * protocol tokens only; source control contains NO PRIVATE KEY).
 *
 * These exercise the exact Python-signed wire representation using the Ktor
 * production verifier. A signed token is never live device/OTP authorization.
 */
class PythonSignedGrantInteropTest {
    private val publicKeyDerBase64 = "MCowBQYDK2VwAyEAyXmFTuKBpo6F4sqHh1iLPoQ46ad3kZtbxwvaza57jpk="
    private val issuedAt = 1_793_836_800L
    private val smsToken = "eyJhbGciOiJFZERTQSIsImtpZCI6Im1hbWEta3Rvci1zbXMtdjEiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOiJtYW1hLWt0b3ItdHJ1c3RlZC1leGl0IiwiY29udGFjdF9pZCI6IjU1NTU1NTU1LTU1NTUtNDU1NS04NTU1LTU1NTU1NTU1NTU1NSIsImRldmljZV9pZCI6IjQ0NDQ0NDQ0LTQ0NDQtNDQ0NC04NDQ0LTQ0NDQ0NDQ0NDQ0NCIsImV4cCI6MTc5MzgzNjg0NSwiaWF0IjoxNzkzODM2ODAwLCJpc3MiOiJtYW1hLWZhc3RhcGkiLCJqdGkiOiI3Nzc3Nzc3Ny03Nzc3LTQ3NzctODc3Ny03Nzc3Nzc3Nzc3NzciLCJuYmYiOjE3OTM4MzY4MDAsInBlcmlvZF9pZCI6IjMzMzMzMzMzLTMzMzMtNDMzMy04MzMzLTMzMzMzMzMzMzMzMyIsInB1cnBvc2UiOiJ0cnVzdGVkLWV4aXQ6c21zLXJlcXVlc3QiLCJzZXJpZXNfaWQiOiIyMjIyMjIyMi0yMjIyLTQyMjItODIyMi0yMjIyMjIyMjIyMjIiLCJzdWIiOiIxMTExMTExMS0xMTExLTQxMTEtODExMS0xMTExMTExMTExMTEifQ.PpTauofr8UBpTHboJi_xhwt0DDoNQnXKqRlVuYOhsZLZd_wmZwDUGgzp-as-_uRwEoBC-siYDWSuULHG9I5VAg"
    private val verifyToken = "eyJhbGciOiJFZERTQSIsImtpZCI6Im1hbWEta3Rvci12ZXJpZnktdjEiLCJ0eXAiOiJKV1QifQ.eyJhdWQiOiJtYW1hLWt0b3ItdHJ1c3RlZC1leGl0IiwiY2hhbGxlbmdlX2lkIjoidGVjLTY2NjY2NjY2LTY2NjYtNDY2Ni04NjY2LTY2NjY2NjY2NjY2NiIsImNvbnRhY3RfaWQiOiI1NTU1NTU1NS01NTU1LTQ1NTUtODU1NS01NTU1NTU1NTU1NTUiLCJkZXZpY2VfaWQiOiI0NDQ0NDQ0NC00NDQ0LTQ0NDQtODQ0NC00NDQ0NDQ0NDQ0NDQiLCJleHAiOjE3OTM4MzY4NDUsImlhdCI6MTc5MzgzNjgwMCwiaXNzIjoibWFtYS1mYXN0YXBpIiwianRpIjoiNzc3Nzc3NzctNzc3Ny00Nzc3LTg3NzctNzc3Nzc3Nzc3Nzc3IiwibmJmIjoxNzkzODM2ODAwLCJwZXJpb2RfaWQiOiIzMzMzMzMzMy0zMzMzLTQzMzMtODMzMy0zMzMzMzMzMzMzMzMiLCJwdXJwb3NlIjoidHJ1c3RlZC1leGl0OnZlcmlmeSIsInNlcmllc19pZCI6IjIyMjIyMjIyLTIyMjItNDIyMi04MjIyLTIyMjIyMjIyMjIyMiIsInN1YiI6IjExMTExMTExLTExMTEtNDExMS04MTExLTExMTExMTExMTExMSJ9.da_-rTRgw4pcDMo-hUN9o0NFjIy54XQ1tMl5fD2orBGVFNomjKC9I09uKYMGvGud7CedcaM0EQXclDuCt97oAA"

    @Test
    fun `independent Python SMS-scope grant verifies and cannot become a verify grant`() {
        val smsVerifier = GrantVerifier(publicKeyDerBase64, now = { issuedAt })
        val result = smsVerifier.verify("Bearer $smsToken")
        assertTrue(result is GrantVerifier.Result.Valid)
        val grant = (result as GrantVerifier.Result.Valid).grant
        assertEquals("11111111-1111-4111-8111-111111111111", grant.sub)
        assertEquals(null, grant.challengeId)
        assertTrue(
            GrantVerifier.forVerify(publicKeyDerBase64, now = { issuedAt })
                .verify("Bearer $smsToken") is GrantVerifier.Result.Invalid
        )
    }

    @Test
    fun `independent Python challenge-bound verify grant verifies only in verify scope`() {
        val verifier = GrantVerifier.forVerify(publicKeyDerBase64, now = { issuedAt })
        val result = verifier.verify("Bearer $verifyToken")
        assertTrue(result is GrantVerifier.Result.Valid)
        assertEquals(
            "tec-66666666-6666-4666-8666-666666666666",
            (result as GrantVerifier.Result.Valid).grant.challengeId,
        )
        assertTrue(
            GrantVerifier(publicKeyDerBase64, now = { issuedAt })
                .verify("Bearer $verifyToken") is GrantVerifier.Result.Invalid
        )
        assertTrue(
            GrantVerifier.forVerify(publicKeyDerBase64, now = { issuedAt + 45 })
                .verify("Bearer $verifyToken") is GrantVerifier.Result.Invalid
        )
        val signature = verifyToken.substringAfterLast(".")
        val tampered = verifyToken.substringBeforeLast(".") + "." +
            (if (signature[0] == 'A') "B" else "A") + signature.drop(1)
        assertTrue(verifier.verify("Bearer $tampered") is GrantVerifier.Result.Invalid)
    }
}
