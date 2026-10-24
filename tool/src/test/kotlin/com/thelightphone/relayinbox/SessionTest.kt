package com.thelightphone.relayinbox

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SessionTest {
    // A throwaway certificate, and its pin as light-relay's up.sh prints it:
    // openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIBizCCATKgAwIBAgIUaAoqpuY3DhQOsjzgx5VoOlQMiGswCgYIKoZIzj0EAwIw
        EzERMA8GA1UEAwwIcGluIHRlc3QwHhcNMjYxMDEzMTU0NTI0WhcNMzYxMDEwMTU0
        NTI0WjATMREwDwYDVQQDDAhwaW4gdGVzdDBZMBMGByqGSM49AgEGCCqGSM49AwEH
        A0IABNYrHII+0kzsnqAe0oW8bnwz3l51o+EE82+c0GVyJk66d6BHywZOSo4tUnqC
        05FZYsKKGKXNKGtcMOfGGUWze3GjZDBiMB0GA1UdDgQWBBTg/ZwBp7+XwCc6PJVb
        IQvIBH/W4zAfBgNVHSMEGDAWgBTg/ZwBp7+XwCc6PJVbIQvIBH/W4zAPBgNVHRMB
        Af8EBTADAQH/MA8GA1UdEQQIMAaHBMAAAgowCgYIKoZIzj0EAwIDRwAwRAIgQvzc
        18/ISpqliTqV+RckNjox3NfJm7ynuCeyGGfoISICICJllc3bcjnnq015ox5bIbyW
        kV8GqCWjE934AdH4H1tT
        -----END CERTIFICATE-----
    """.trimIndent()
    private val pin = "iJTTd83V4i9uLFiI8uROCkrCG1hX1GWhZwPgE+8kSw8="

    private fun cert() = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()) as X509Certificate

    @Test
    fun `the pin matches what up_sh prints`() {
        assertEquals(pin, spkiPin(cert()))
    }

    @Test
    fun `only the pinned certificate is trusted`() {
        PinnedTrust(pin).checkServerTrusted(arrayOf(cert()), "ECDHE_ECDSA")
        assertFailsWith<CertificateException> {
            PinnedTrust("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").checkServerTrusted(arrayOf(cert()), "ECDHE_ECDSA")
        }
        assertFailsWith<CertificateException> { PinnedTrust(pin).checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
    }

    @Test
    fun `the renewal proof is what the relay checks`() {
        // lightrelay.session.proof(b"t" * 43, 1791900000, "ab" * 16)
        assertEquals(
            "1460c7fb66712023c714e7d73c050cae359f78a21076392781f80188e8ea402b",
            renewalProof("t".repeat(43), 1_791_900_000, "ab".repeat(16)),
        )
    }

    @Test
    fun `a pending reply waits for home only when sessions are on and none is usable`() {
        val now = 1_000_000_000L
        val fresh = InboxSession(token = "s", expiresAt = now + 3_600_000, renewedAt = now)
        val expiring = fresh.copy(expiresAt = now + 30_000)
        assertEquals("waiting for home network", pendingStatus(waitingForHome(enabled = true, session = null, now = now)))
        assertEquals("waiting for home network", pendingStatus(waitingForHome(enabled = true, session = expiring, now = now)))
        assertEquals("sending", pendingStatus(waitingForHome(enabled = true, session = fresh, now = now)))
        assertEquals("sending", pendingStatus(waitingForHome(enabled = false, session = null, now = now)))
    }
}
