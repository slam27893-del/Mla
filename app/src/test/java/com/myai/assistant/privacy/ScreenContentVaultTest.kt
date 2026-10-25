package com.myai.assistant.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentVaultTest {

    private val guard = ScreenContentGuard()
    private val vault = ScreenContentVault(guard)

    private val secret = "ZQ9-VAULT-4410-ONLY-HERE"

    @Test
    fun `stores text by step id`() {
        vault.put("step-1", "رصيد $secret")

        assertEquals("رصيد $secret", vault.get("step-1"))
        assertEquals(1, vault.size())
    }

    @Test
    fun `every stored value is registered with the outbound guard`() {
        vault.put("step-1", "رصيد $secret")

        assertTrue(guard.containsProtected(secret))
    }

    @Test
    fun `overwriting the same step does not grow the vault`() {
        vault.put("step-1", "أ")
        vault.put("step-1", "ب")

        assertEquals(1, vault.size())
        assertEquals("ب", vault.get("step-1"))
    }

    @Test
    fun `blank values and ids are ignored`() {
        vault.put("", "نص")
        vault.put("step-1", "   ")

        assertEquals(0, vault.size())
        assertNull(vault.get("step-1"))
    }

    @Test
    fun `oldest entries are evicted beyond the limit`() {
        repeat(ScreenContentVault.MAX_ENTRIES + 5) { index ->
            vault.put("step-$index", "نص الشاشة رقم $index")
        }

        assertEquals(ScreenContentVault.MAX_ENTRIES, vault.size())
        assertNull(vault.get("step-0"))
        assertEquals("نص الشاشة رقم ${ScreenContentVault.MAX_ENTRIES + 4}",
            vault.get("step-${ScreenContentVault.MAX_ENTRIES + 4}"))
    }

    @Test
    fun `clear forgets every readout`() {
        vault.put("step-1", "رصيد $secret")
        vault.clear()

        assertEquals(0, vault.size())
        assertNull(vault.get("step-1"))
    }

    @Test
    fun `the placeholder itself carries no screen text`() {
        vault.put("step-1", "رصيد $secret")

        val placeholder = ScreenContentVault.PLACEHOLDER
        assertTrue(placeholder.isNotBlank())
        assertTrue(!placeholder.contains(secret))
        assertTrue(!placeholder.contains("رصيد"))
    }
}
