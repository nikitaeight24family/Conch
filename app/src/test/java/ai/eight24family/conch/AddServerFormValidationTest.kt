package ai.eight24family.conch

import ai.eight24family.conch.domain.AuthMethod
import ai.eight24family.conch.ui.viewmodel.AddServerForm
import ai.eight24family.conch.ui.viewmodel.hasMandatoryFields
import ai.eight24family.conch.ui.viewmodel.hasUsableCredentials
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the [AddServerForm] validators that gate the
 * "test connection" / "save" buttons. The VM that owns the form pulls
 * collaborators from `ServiceLocator` in its constructor, so tests
 * lean on the file-level helpers extracted from those private extension
 * properties — same logic, no DI footprint.
 *
 * Why these matter: a regression here means the user can either
 *   - tap "test" with an empty host and stare at a "Wrong password"
 *     toast, or
 *   - tap "save" with no auth credentials and get a stuck spinner
 *     that nobody knows is hung.
 * Both are debug-with-logcat-or-bust UX failures, exactly the kind
 * that's painful to discover after a refactor.
 */
class AddServerFormValidationTest {

    @Test
    fun `mandatory fields are host and user`() {
        // Default form is empty everywhere — nothing required is filled.
        assertFalse(hasMandatoryFields(AddServerForm()))

        assertFalse("host alone isn't enough",
            hasMandatoryFields(AddServerForm(host = "h.example.com")))
        assertFalse("user alone isn't enough",
            hasMandatoryFields(AddServerForm(user = "alice")))

        assertTrue("host + user is sufficient",
            hasMandatoryFields(AddServerForm(host = "h.example.com", user = "alice")))
    }

    @Test
    fun `whitespace-only fields don't satisfy mandatory check`() {
        assertFalse(hasMandatoryFields(AddServerForm(host = "   ", user = "alice")))
        assertFalse(hasMandatoryFields(AddServerForm(host = "h.example.com", user = "\t")))
        assertFalse(hasMandatoryFields(AddServerForm(host = "  ", user = "  ")))
    }

    @Test
    fun `name field is optional — host alone is allowed as display label`() {
        assertTrue(hasMandatoryFields(AddServerForm(name = "", host = "h", user = "u")))
    }

    @Test
    fun `port is not part of mandatory validation`() {
        // Port has a default of 22 in the data class. Leaving it at default,
        // or setting any int including 0, must NOT block mandatory check.
        assertTrue(hasMandatoryFields(AddServerForm(host = "h", user = "u", port = 22)))
        assertTrue(hasMandatoryFields(AddServerForm(host = "h", user = "u", port = 0)))
        assertTrue(hasMandatoryFields(AddServerForm(host = "h", user = "u", port = 65535)))
    }

    @Test
    fun `password auth requires non-blank password`() {
        val base = AddServerForm(authMethod = AuthMethod.PASSWORD, host = "h", user = "u")
        assertFalse("missing password", hasUsableCredentials(base))
        assertFalse("blank password", hasUsableCredentials(base.copy(password = "")))
        assertFalse("whitespace password", hasUsableCredentials(base.copy(password = "  ")))
        assertTrue("real password", hasUsableCredentials(base.copy(password = "hunter2")))
    }

    @Test
    fun `password auth ignores sshKeyIds — even if set`() {
        val f = AddServerForm(
            authMethod = AuthMethod.PASSWORD,
            host = "h", user = "u",
            password = "ok",
            sshKeyIds = listOf("k1"),
        )
        assertTrue(hasUsableCredentials(f))
    }

    @Test
    fun `key auth requires at least one sshKeyId`() {
        val base = AddServerForm(authMethod = AuthMethod.KEY, host = "h", user = "u")
        assertFalse("missing keys", hasUsableCredentials(base))
        assertFalse("empty key list", hasUsableCredentials(base.copy(sshKeyIds = emptyList())))
        assertTrue("real key", hasUsableCredentials(base.copy(sshKeyIds = listOf("k1"))))
    }

    @Test
    fun `key auth ignores password — set or not`() {
        val withKey = AddServerForm(
            authMethod = AuthMethod.KEY,
            host = "h", user = "u",
            sshKeyIds = listOf("k1"),
            password = "wouldbeignored",
        )
        assertTrue(hasUsableCredentials(withKey))

        val noPwd = withKey.copy(password = "")
        assertTrue("password absence shouldn't block KEY auth", hasUsableCredentials(noPwd))
    }

    @Test
    fun `default form fails both gates`() {
        val empty = AddServerForm()
        assertFalse(hasMandatoryFields(empty))
        assertFalse(hasUsableCredentials(empty))
    }

    @Test
    fun `switching auth method invalidates old credentials`() {
        // User filled out password under PASSWORD mode, then flipped to KEY.
        // Credentials check must now fail because no key is picked.
        val pwdForm = AddServerForm(authMethod = AuthMethod.PASSWORD, password = "hunter2")
        assertTrue(hasUsableCredentials(pwdForm))

        val flippedToKey = pwdForm.copy(authMethod = AuthMethod.KEY)
        assertFalse(
            "flipping auth method without picking a key should re-block save",
            hasUsableCredentials(flippedToKey)
        )
    }
}
