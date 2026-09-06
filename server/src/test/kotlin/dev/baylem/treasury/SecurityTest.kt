package dev.baylem.treasury

import dev.baylem.treasury.server.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

class SecurityTest {
    @Test fun forwardedHeadersOnlyWorkThroughExplicitlyTrustedPeers() {
        assertEquals("198.51.100.7", clientAddress("198.51.100.7", "1.2.3.4", emptySet()))
        assertEquals("198.51.100.7", clientAddress("10.0.0.2", "1.2.3.4, 198.51.100.7", setOf("10.0.0.2")))
        assertEquals("198.51.100.7", clientAddress("10.0.0.2", "1.2.3.4, 198.51.100.7, 10.0.0.1", setOf("10.0.0.1", "10.0.0.2")))
        assertNull(parseIpAddress("untrusted.example.com"))
        assertNull(parseIpAddress("deadbeef"))
        assertNull(parseIpAddress("127.0.0.1.evil.example"))
        assertFailsWith<ApiException> { cursorTime("+1000000000-12-31T23:59:59.999999999Z") }
    }

    @Test fun deviceApprovalStopsAfterFiveWrongCodesAndSecretsCannotBeReplayed() = runBlocking {
        val store = testStore()
        val now = System.currentTimeMillis()
        val user = UserRecord(UUID.randomUUID().toString(), null, null, now)
        store.createUser(user)
        val device = OAuthDevice(UUID.randomUUID().toString(), Secrets.hash(Secrets.token()), Secrets.hash("12345678"), "google", now + 600_000)
        store.createDeviceAttempt(device, now)
        val approval = Secrets.hash(Secrets.token())
        assertTrue(store.stageDeviceAttempt(device.id, user.id, approval, now))
        assertFalse(store.stageDeviceAttempt(device.id, user.id, Secrets.hash(Secrets.token()), now))
        repeat(5) { assertFalse(store.approveDeviceAttempt(device.id, approval, Secrets.hash("00000000"), now)) }
        assertFalse(store.approveDeviceAttempt(device.id, approval, device.codeHash, now))
        assertEquals(DevicePoll.Expired, store.consumeDeviceAttempt(device.id, device.secretHash, now))
    }

    @Test fun passwordChangeBetweenVerificationAndSessionCreationRejectsOldCredential() = runBlocking {
        val store = testStore()
        val now = System.currentTimeMillis()
        val user = UserRecord(UUID.randomUUID().toString(), "person@example.com", "new-hash", now)
        store.createUser(user)
        assertFailsWith<ApiException> { store.addSession(SessionRecord(Secrets.hash(Secrets.token()), user.id, now, now + 60_000), "old-hash") }
        Unit
    }
}
