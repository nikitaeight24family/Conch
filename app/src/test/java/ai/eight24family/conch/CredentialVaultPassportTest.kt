package ai.eight24family.conch

import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.CredentialVault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The account passport rides in the slot meta as extra `key=value` lines
 * appended by the server-side enrich pass (verified against real slots on the
 * server, 2026-08-18). This pins the Kotlin half: listSlots must surface those
 * lines as Slot fields and tolerate their absence (pre-enrichment metas).
 */
class CredentialVaultPassportTest {

    private fun vaultReturning(listing: String) =
        CredentialVault(Agent.CLAUDE) { _ -> listing }

    @Test
    fun `enriched codex-style meta parses into passport fields`() = runBlocking {
        val line = "SLOT f9b196ca\tmethod=oauth\tlabel=Account 1\tcreated=1783232679\t" +
            "kind=file\temail=user@example.com\tplan=team\t" +
            "planUntil=2026-08-04T17:33:39+00:00\t" +
            "lastRefresh=2026-07-05T06:24:36.037134414Z\tenriched=1\t"
        val slot = vaultReturning(line).listSlots()!!.single()
        assertEquals("f9b196ca", slot.id)
        assertEquals("file", slot.kind)
        assertEquals("user@example.com", slot.email)
        assertEquals("team", slot.plan)
        assertEquals("2026-08-04T17:33:39+00:00", slot.planUntil)
        assertEquals("2026-07-05T06:24:36.037134414Z", slot.lastRefresh)
        assertNull(slot.expiresMs)
    }

    @Test
    fun `claude token slot carries kind only`() = runBlocking {
        val line = "SLOT 857bdb63\tmethod=oauth\tlabel=Account 1\tcreated=1784159664\t" +
            "kind=token\tenriched=1\t"
        val slot = vaultReturning(line).listSlots()!!.single()
        assertEquals("token", slot.kind)
        assertNull(slot.email)
        assertNull(slot.plan)
    }

    @Test
    fun `pre-enrichment meta still parses with null passport`() = runBlocking {
        val line = "SLOT old\tmethod=oauth\tlabel=Old\tcreated=1780000000\t"
        val slot = vaultReturning(line).listSlots()!!.single()
        assertEquals("Old", slot.label)
        assertNull(slot.kind)
        assertNull(slot.email)
        assertNull(slot.plan)
        assertNull(slot.planUntil)
        assertNull(slot.lastRefresh)
        assertNull(slot.expiresMs)
    }

    @Test
    fun `claude full-oauth meta parses plan and access expiry`() = runBlocking {
        val line = "SLOT abc\tmethod=oauth\tlabel=A\tcreated=1784000000\t" +
            "kind=file\tplan=max\texpiresMs=1790000000000\tenriched=1\t"
        val slot = vaultReturning(line).listSlots()!!.single()
        assertEquals("file", slot.kind)
        assertEquals("max", slot.plan)
        assertEquals(1790000000000L, slot.expiresMs)
    }
}
