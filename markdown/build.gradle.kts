plugins { id("lite.module") }

// Code blocks are colored by the other Lite languages, found by language id at run time (no compile dependency).
dependencies { implementation(project(":core")) }
