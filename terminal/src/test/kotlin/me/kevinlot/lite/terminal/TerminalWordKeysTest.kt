package me.kevinlot.lite.terminal

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
