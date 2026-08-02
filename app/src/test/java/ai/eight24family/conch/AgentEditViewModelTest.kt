package ai.eight24family.conch

import androidx.lifecycle.SavedStateHandle
import ai.eight24family.conch.agent.Agent
import ai.eight24family.conch.agent.AgentScope
import ai.eight24family.conch.agent.SubagentCatalog
import ai.eight24family.conch.ui.viewmodel.AgentEditViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AgentEditViewModel] form-state mutations. Doesn't exercise
 * the SSH save/delete paths (those need a live SSH stand-in); pins the
 * pure state-machine behaviour:
 *  • applyTemplate fills tools+body but leaves user-typed name/desc alone
 *  • update* methods are isolated (changing name doesn't touch tools)
 *  • toggleTool actually toggles, doesn't just add
 *  • path-decoded SavedStateHandle handling
 *  • isNew flag flips correctly for new vs edit context
 *
 * Uses Dispatchers.setMain(UnconfinedTestDispatcher) so anything that
 * `viewModelScope.launch`-es runs synchronously on the same thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentEditViewModelTest {

    @Before
    fun setUpDispatchers() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownDispatchers() {
        Dispatchers.resetMain()
    }

    private fun newVm(
        path: String? = null,
        agent: Agent = Agent.CLAUDE,
    ): AgentEditViewModel {
        val handle = SavedStateHandle().apply {
            set("serverId", "server-x")
            set("agent", agent.name)
            // chatId left null
            if (path != null) set("path", path)
        }
        return AgentEditViewModel(handle)
    }

    // ───────────────────── identity ─────────────────────

    @Test
    fun `isNew is true when no path supplied`() {
        val vm = newVm()
        assertTrue(vm.isNew)
        assertNull(vm.path)
    }

    @Test
    fun `path is URL-decoded from SavedStateHandle`() {
        val encoded = java.net.URLEncoder.encode("/home/me/project/.claude/agents/x.md", "UTF-8")
        val vm = newVm(path = encoded)
        assertFalse(vm.isNew)
        assertEquals("/home/me/project/.claude/agents/x.md", vm.path)
    }

    @Test
    fun `blank path treated as new`() {
        val handle = SavedStateHandle().apply {
            set("serverId", "x")
            set("agent", "CLAUDE")
            set("path", "")
        }
        val vm = AgentEditViewModel(handle)
        assertTrue(vm.isNew)
        assertNull(vm.path)
    }

    // ───────────────────── form mutation isolation ─────────────────────

    @Test
    fun `initial form is blank with global scope`() {
        val vm = newVm()
        val f = vm.form.value
        assertEquals(AgentScope.GLOBAL, f.scope)
        assertEquals("", f.name)
        assertEquals("", f.description)
        assertEquals(emptySet<String>(), f.tools)
        assertEquals("", f.body)
    }

    @Test
    fun `updateName isolates that field`() {
        val vm = newVm()
        vm.updateName("reviewer")
        val f = vm.form.value
        assertEquals("reviewer", f.name)
        assertEquals("", f.description)
        assertEquals("", f.body)
        assertEquals(emptySet<String>(), f.tools)
    }

    @Test
    fun `updateName strips slashes (filename safety)`() {
        // The name turns into the filename; slashes would break the path.
        val vm = newVm()
        vm.updateName("evil/../sneaky")
        assertFalse("/" in vm.form.value.name)
        assertFalse("\\" in vm.form.value.name)
    }

    @Test
    fun `updateScope flips between GLOBAL and PROJECT`() {
        val vm = newVm()
        vm.updateScope(AgentScope.PROJECT)
        assertEquals(AgentScope.PROJECT, vm.form.value.scope)
        vm.updateScope(AgentScope.GLOBAL)
        assertEquals(AgentScope.GLOBAL, vm.form.value.scope)
    }

    @Test
    fun `toggleTool adds then removes`() {
        val vm = newVm()
        assertFalse("Bash" in vm.form.value.tools)
        vm.toggleTool("Bash")
        assertTrue("Bash" in vm.form.value.tools)
        vm.toggleTool("Bash")
        assertFalse("Bash" in vm.form.value.tools)
    }

    @Test
    fun `toggleTool is independent across tools`() {
        val vm = newVm()
        vm.toggleTool("Read")
        vm.toggleTool("Grep")
        vm.toggleTool("Bash")
        vm.toggleTool("Grep")  // off
        assertEquals(setOf("Read", "Bash"), vm.form.value.tools)
    }

    @Test
    fun `updateDescription and updateBody don't touch each other`() {
        val vm = newVm()
        vm.updateDescription("D")
        vm.updateBody("B")
        val f = vm.form.value
        assertEquals("D", f.description)
        assertEquals("B", f.body)
    }

    // ───────────────────── applyTemplate ─────────────────────

    @Test
    fun `applyTemplate fills tools and body for blank form`() {
        val vm = newVm()
        val tpl = SubagentCatalog.templateById("code-reviewer")!!
        vm.applyTemplate(tpl)
        val f = vm.form.value
        assertEquals(tpl.tools.toSet(), f.tools)
        assertEquals(tpl.body, f.body)
        // name/description filled because both were blank
        assertEquals("code-reviewer", f.name)
        assertEquals(tpl.description, f.description)
    }

    @Test
    fun `applyTemplate does not overwrite already-typed name`() {
        val vm = newVm()
        vm.updateName("my-custom")
        val tpl = SubagentCatalog.templateById("code-reviewer")!!
        vm.applyTemplate(tpl)
        // Name stays as the user typed it.
        assertEquals("my-custom", vm.form.value.name)
        // But tools+body still got applied.
        assertEquals(tpl.tools.toSet(), vm.form.value.tools)
    }

    @Test
    fun `applyTemplate does not overwrite already-typed description`() {
        val vm = newVm()
        vm.updateDescription("my own desc")
        vm.applyTemplate(SubagentCatalog.templateById("code-reviewer")!!)
        assertEquals("my own desc", vm.form.value.description)
    }

    @Test
    fun `applyTemplate blank does not fill name with the literal string blank`() {
        // Sanity: there's a "blank" template; applying it shouldn't set
        // name to the literal "blank" — it's a starter, not a name.
        val vm = newVm()
        val blank = SubagentCatalog.templateById("blank")!!
        vm.applyTemplate(blank)
        assertNotEquals("blank", vm.form.value.name)
    }

    @Test
    fun `applyTemplate twice replaces tools and body each time`() {
        val vm = newVm()
        vm.applyTemplate(SubagentCatalog.templateById("code-reviewer")!!)
        val codeRevTools = vm.form.value.tools

        vm.applyTemplate(SubagentCatalog.templateById("test-writer")!!)
        val testWriterTools = vm.form.value.tools

        assertNotEquals(codeRevTools, testWriterTools)
        // Body changed too.
        assertTrue(vm.form.value.body.contains("test", ignoreCase = true))
    }

    // ───────────────────── save validation ─────────────────────

    @Test
    fun `save with blank name surfaces toast and does not flip saved`() {
        val vm = newVm()
        // No fields set; name is blank.
        vm.save()
        assertEquals("name is required", vm.toast.value)
        assertFalse(vm.saved.value)
    }

    @Test
    fun `consumeToast clears it`() {
        val vm = newVm()
        vm.save()  // sets toast
        vm.consumeToast()
        assertNull(vm.toast.value)
    }
}
