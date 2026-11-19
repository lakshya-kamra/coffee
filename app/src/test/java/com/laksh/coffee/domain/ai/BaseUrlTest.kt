package com.laksh.coffee.domain.ai

import com.laksh.coffee.domain.ai.AiError.InvalidBaseUrl.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlTest {

    private fun valid(raw: String): BaseUrlCheck.Valid {
        val check = BaseUrl.check(raw)
        assertTrue("expected $raw to be valid, got $check", check is BaseUrlCheck.Valid)
        return check as BaseUrlCheck.Valid
    }

    private fun reason(raw: String): Reason {
        val check = BaseUrl.check(raw)
        assertTrue("expected $raw to be invalid, got $check", check is BaseUrlCheck.Invalid)
        return (check as BaseUrlCheck.Invalid).error.reason
    }

    @Test
    fun bareHostGetsHttpsAndLosesTheTrailingSlash() {
        val v = valid("api.commandcode.ai/provider/v1/")
        assertEquals("https://api.commandcode.ai/provider/v1", v.normalized)
        assertEquals("api.commandcode.ai", v.host)
    }

    @Test
    fun theDefaultIsAlreadyNormal() {
        val commandCode = "https://api.commandcode.ai/provider/v1"
        assertEquals(commandCode, valid(commandCode).normalized)
    }

    @Test
    fun plainHttpIsRefused() {
        assertEquals(Reason.NotHttps, reason("http://x.com"))
    }

    @Test
    fun credentialsInTheAddressAreRefused() {
        assertEquals(Reason.HasCredentials, reason("https://user:pw@x.com"))
    }

    @Test
    fun queryAndFragmentAreRefused() {
        assertEquals(Reason.HasQuery, reason("https://x.com/v1?k=1"))
        assertEquals(Reason.HasQuery, reason("https://x.com/v1#top"))
    }

    @Test
    fun pastedEndpointSuffixesAreStripped() {
        assertEquals(
            "https://api.commandcode.ai/provider/v1",
            valid("https://api.commandcode.ai/provider/v1/chat/completions").normalized,
        )
        assertEquals("https://openrouter.ai/api/v1", valid("https://openrouter.ai/api/v1/models/").normalized)
    }

    @Test
    fun schemeAndHostAreLowercasedAndThePortKept() {
        assertEquals(
            "https://api.commandcode.ai/provider/v1",
            valid("HTTPS://API.CommandCode.AI/provider/v1").normalized,
        )
        assertEquals("https://x.com:8443/v1", valid("https://x.com:8443/v1/").normalized)
    }

    @Test
    fun blankAndGarbageAreRefused() {
        assertEquals(Reason.Blank, reason("   "))
        assertEquals(Reason.Malformed, reason("https://exa mple.com"))
        assertEquals(Reason.Malformed, reason("https://intranet/v1"))
    }

    @Test
    fun aPastedKeyIsNeverAcceptedAsAHost() {
        assertEquals(Reason.LooksLikeKey, reason("sk-or-v1-3f9a8b7c6d5e4f3a2b1c"))
        assertEquals(Reason.LooksLikeKey, reason("cc_live_9Xk2Lm4Np6Qr8St0Uv"))
        assertEquals(Reason.LooksLikeKey, reason("Bearer sk-abc123def456"))
        assertEquals(Reason.LooksLikeKey, reason("https://sk-proj-8Hs7Df6Gh5Jk4.example.com/v1"))
        assertEquals(Reason.LooksLikeKey, reason("https://Ab3dE9gH2jK7mN4pQ8sT.example.com/v1"))
    }

    @Test
    fun anUnprefixedKeyOnTheEndOfTheAddressIsRefused() {
        assertEquals(Reason.LooksLikeKey, reason("https://api.example.com/v1/3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c"))
        assertEquals(Reason.LooksLikeKey, reason("https://api.example.com/v1/3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c/chat/completions"))
        assertEquals(Reason.LooksLikeKey, reason("https://api.example.com/v1/XK7mQ2pL9vR4tZ8nB3wY6cD1"))
    }

    @Test
    fun aGatewayIdInTheMiddleOfThePathStillPasses() {
        val v = valid("https://gateway.ai.cloudflare.com/v1/0123456789abcdef0123456789abcdef/my-gateway/openai")
        assertEquals("gateway.ai.cloudflare.com", v.host)
        valid("https://my-resource.openai.azure.com/openai/deployments/gpt-4o-mini-2024-07-18")
    }

    @Test
    fun ordinaryLongHostsStillPass() {
        valid("https://my-company-openai-gateway.example.com/v1")
        valid("https://disk-cache-proxy-server.example.com/v1")
        valid("https://generativelanguage.googleapis.com/v1beta/openai")
    }

    @Test
    fun requestUrlsAreBuiltFromTheNormalizedRoot() {
        val root = valid("api.commandcode.ai/provider/v1").normalized
        assertEquals("https://api.commandcode.ai/provider/v1/chat/completions", BaseUrl.chatCompletions(root))
        assertEquals("https://api.commandcode.ai/provider/v1/models", BaseUrl.models(root))
        assertEquals("https://api.commandcode.ai/provider/v1/messages", BaseUrl.messages(root))
        // The catalog's listModels.path is used when it is a plain path, and never anything else.
        assertEquals("https://api.commandcode.ai/provider/v1/v2/models", BaseUrl.models(root, "/v2/models"))
        assertEquals("https://api.commandcode.ai/provider/v1/models", BaseUrl.models(root, "models?key=x"))
        assertEquals(ModelCatalog.COMMANDCODE_HOST, BaseUrl.hostOf(root))
        assertEquals("", BaseUrl.hostOf("not a url"))
    }

    @Test
    fun invalidResultsCarryUserFacingCopy() {
        val error = (BaseUrl.check("http://x.com") as BaseUrlCheck.Invalid).error
        assertFalse(error.retryable)
        assertTrue(error.title.isNotBlank())
        assertTrue(error.message.contains("https://"))
    }
}
