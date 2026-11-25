package me.kevinlot.lite.terminal

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.SystemInfo
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalKeyEvent
import com.intellij.terminal.frontend.view.TerminalKeyEventsListener
import com.intellij.terminal.frontend.view.TerminalView
import com.jediterm.terminal.ui.JediTermWidget
import com.jediterm.terminal.ui.TerminalPanel
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * On macOS the IDE's terminal sends Cmd+Left / Cmd+Right as Ctrl+A / Ctrl+E (line start / end), hard-coded in its key
 * encoders. Here they move by word instead: ESC b / ESC f, what Option+Left / Right already send and what readline, zsh
 * and full-screen programs such as Claude Code read as "previous / next word".
 */
object TerminalWordKeys {
    private val SEQUENCES = mapOf(KeyEvent.VK_LEFT to "\u001bb", KeyEvent.VK_RIGHT to "\u001bf")

    /** What Cmd alone with an arrow sends, or null for any other key. */
    fun sequence(keyCode: Int, modifiersEx: Int, mac: Boolean = SystemInfo.isMac): String? {
        if (!mac || modifiersEx != InputEvent.META_DOWN_MASK) return null
        return SEQUENCES[keyCode]
    }
}

private val LOG = Logger.getInstance(TerminalWordKeys::class.java)

/** The views already listened to, and the parent of their listeners. */
@Service(Service.Level.PROJECT)
class TerminalWordKeysInstaller : Disposable {
    private val installed = Collections.newSetFromMap(WeakHashMap<TerminalView, Boolean>())

    fun install(view: TerminalView) {
        if (!installed.add(view)) return
        LOG.info("Lite terminal keys: listening to a reworked terminal view")
        view.addKeyEventsListener(this, object : TerminalKeyEventsListener {
            // true keeps the key away from the terminal's encoder: the release of the key must not reach it either
            override fun beforeKeyEvent(event: TerminalKeyEvent): Boolean {
                val key = event.awtEvent
                val sequence = TerminalWordKeys.sequence(key.keyCode, key.modifiersEx) ?: return false
                if (key.id == KeyEvent.KEY_PRESSED) {
                    LOG.info("Lite terminal keys: reworked terminal, key ${key.keyCode} sent as a word move")
                    view.sendText(sequence)
                }
                return true
            }
        })
    }

    override fun dispose() = installed.clear()
}

/**
 * The classic engine (JediTerm): its panel has no listener that runs before its own encoder, so the key is taken from the
 * IDE's event queue while such a panel has the focus.
 */
@Service(Service.Level.APP)
class ClassicTerminalWordKeys : Disposable {
    init {
        IdeEventQueue.getInstance().addDispatcher({ event -> event is KeyEvent && handle(event) }, this)
        LOG.info("Lite terminal keys: watching the event queue for classic terminals")
    }

    private fun handle(event: KeyEvent): Boolean {
        val sequence = TerminalWordKeys.sequence(event.keyCode, event.modifiersEx) ?: return false
        val pressed = event.id == KeyEvent.KEY_PRESSED
        // in the queue a key event targets the window: the terminal is the focus owner
        val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        val widget = (focused as? TerminalPanel)?.let { SwingUtilities.getAncestorOfClass(JediTermWidget::class.java, it) as? JediTermWidget }
        if (widget == null) {
            if (pressed) LOG.info("Lite terminal keys: Cmd+arrow outside a classic terminal, focus in ${focused?.javaClass?.name}")
            return false
        }
        if (pressed) {
            LOG.info("Lite terminal keys: classic terminal, key ${event.keyCode} sent as a word move (session started: ${widget.terminalStarter != null})")
            widget.terminalStarter?.sendString(sequence, true)
        }
        return true
    }

    override fun dispose() = Unit
}

class TerminalWordKeysListener(private val project: Project) : TerminalTabsManagerListener {
    override fun terminalViewCreated(view: TerminalView) = project.service<TerminalWordKeysInstaller>().install(view)
}

/** Terminals restored before this plugin's listener was there. */
class TerminalWordKeysStartup : ProjectActivity {
    override suspend fun execute(project: Project) = withContext(Dispatchers.EDT) {
        LOG.info("Lite terminal keys: starting for ${project.name}")
        service<ClassicTerminalWordKeys>()
        TerminalToolWindowTabsManager.getInstance(project).tabs.forEach { project.service<TerminalWordKeysInstaller>().install(it.view) }
    }
}
