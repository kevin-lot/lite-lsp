package me.kevinlot.lite.terminal

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.view.TerminalKeyEventImpl
import com.intellij.terminal.frontend.view.TerminalKeyEventsListener
import com.intellij.terminal.frontend.view.TerminalView
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.lang.reflect.Proxy
import javax.swing.JPanel
import org.jetbrains.plugins.terminal.view.TerminalOffset
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TerminalWordKeysTest {
    private val cmd = InputEvent.META_DOWN_MASK

    @Test
    fun `cmd with an arrow moves by word`() {
        assertEquals("\u001bb", TerminalWordKeys.sequence(KeyEvent.VK_LEFT, cmd, mac = true))
        assertEquals("\u001bf", TerminalWordKeys.sequence(KeyEvent.VK_RIGHT, cmd, mac = true))
    }

    @Test
    fun `other keys, other modifiers and other systems are left to the terminal`() {
        assertNull(TerminalWordKeys.sequence(KeyEvent.VK_UP, cmd, mac = true))
        assertNull(TerminalWordKeys.sequence(KeyEvent.VK_LEFT, 0, mac = true))
        assertNull(TerminalWordKeys.sequence(KeyEvent.VK_LEFT, cmd or InputEvent.SHIFT_DOWN_MASK, mac = true))
        assertNull(TerminalWordKeys.sequence(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK, mac = true))
        assertNull(TerminalWordKeys.sequence(KeyEvent.VK_LEFT, cmd, mac = false))
    }

    @Test
    fun `a disposed installer listens to the view again`() {
        val installer = TerminalWordKeysInstaller()
        val terminal = FakeTerminal()
        installer.install(terminal.view)
        installer.dispose()
        installer.install(terminal.view)
        assertEquals(2, terminal.listeners.size)
    }

    @Test
    fun `a view is listened to once, with the installer as parent`() {
        val installer = TerminalWordKeysInstaller()
        val terminal = FakeTerminal()
        repeat(3) { installer.install(terminal.view) }
        assertEquals(1, terminal.listeners.size)
        assertSame(installer, terminal.parents.single())
    }

    @Test
    fun `ctrl a and ctrl e keep their own meaning`() {
        val ctrl = InputEvent.CTRL_DOWN_MASK
        listOf(KeyEvent.VK_A, KeyEvent.VK_E, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT).forEach { assertNull(TerminalWordKeys.sequence(it, ctrl, mac = true)) }
        listOf(KeyEvent.VK_A, KeyEvent.VK_E).forEach { assertNull(TerminalWordKeys.sequence(it, cmd, mac = true)) }
    }

    @Test
    fun `each view gets its own word moves`() {
        assumeTrue(SystemInfo.isMac)
        val installer = TerminalWordKeysInstaller()
        val left = FakeTerminal().also { installer.install(it.view) }
        val right = FakeTerminal().also { installer.install(it.view) }
        assertTrue(left.press(KeyEvent.VK_LEFT, cmd))
        assertTrue(right.press(KeyEvent.VK_RIGHT, cmd))
        assertEquals(listOf("\u001bb"), left.sent)
        assertEquals(listOf("\u001bf"), right.sent)
    }

    @Test
    fun `the reworked terminal keeps every other key`() {
        val terminal = FakeTerminal().also { TerminalWordKeysInstaller().install(it.view) }
        assertFalse(terminal.press(KeyEvent.VK_LEFT, 0))
        assertFalse(terminal.press(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK))
        assertFalse(terminal.press(KeyEvent.VK_LEFT, cmd or InputEvent.SHIFT_DOWN_MASK))
        assertFalse(terminal.press(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK))
        assertFalse(terminal.press(KeyEvent.VK_UP, cmd))
        assertTrue(terminal.sent.isEmpty())
    }

    @Test
    fun `the reworked terminal sends the word move on the press and swallows the release`() {
        assumeTrue(SystemInfo.isMac)
        val terminal = FakeTerminal().also { TerminalWordKeysInstaller().install(it.view) }
        assertTrue(terminal.press(KeyEvent.VK_LEFT, cmd))
        assertTrue(terminal.key(KeyEvent.KEY_RELEASED, KeyEvent.VK_LEFT, cmd))
        assertTrue(terminal.press(KeyEvent.VK_RIGHT, cmd))
        assertTrue(terminal.key(KeyEvent.KEY_RELEASED, KeyEvent.VK_RIGHT, cmd))
        assertEquals(listOf("\u001bb", "\u001bf"), terminal.sent)
    }
}

/** A [TerminalView] that records what the plugin does to it: the real one needs a running terminal session. */
private class FakeTerminal {
    val listeners = mutableListOf<TerminalKeyEventsListener>()
    val parents = mutableListOf<Disposable>()
    val sent = mutableListOf<String>()

    val view: TerminalView = fake { proxy, name, args ->
        when (name) {
            "addKeyEventsListener" -> null.also { parents += args[0] as Disposable; listeners += args[1] as TerminalKeyEventsListener }
            "equals" -> proxy === args[0]
            "hashCode" -> System.identityHashCode(proxy)
            "sendText" -> null.also { sent += args[0] as String }
            else -> error("unexpected call: $name")
        }
    }

    fun key(id: Int, keyCode: Int, modifiers: Int): Boolean {
        val awt = KeyEvent(JPanel(), id, 0, modifiers, keyCode, KeyEvent.CHAR_UNDEFINED)
        val output: TerminalOutputModel = fake { _, name, _ -> error("unexpected call: $name") }
        return listeners.single().beforeKeyEvent(TerminalKeyEventImpl(awt, TerminalOffset.ZERO, output))
    }

    fun press(keyCode: Int, modifiers: Int): Boolean = key(KeyEvent.KEY_PRESSED, keyCode, modifiers)
}

private inline fun <reified T : Any> fake(crossinline call: (proxy: Any, name: String, args: Array<out Any?>) -> Any?): T {
    val type = T::class.java
    return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args -> call(proxy, method.name, args.orEmpty()) })
}
