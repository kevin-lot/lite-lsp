plugins { id("lite.module") }

// The key listener of the IDE's own terminal: optional at run time, see lite-terminal.xml.
dependencies { intellijPlatform { bundledPlugin("org.jetbrains.plugins.terminal") } }
