rootProject.name = "lite"

// core: language-agnostic infrastructure; one module per language, each depends on core only.
include("core", "rust", "kotlin", "python", "typescript", "qml", "shell", "markdown", "formats", "protobuf")
