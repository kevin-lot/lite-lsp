package me.kevinlot.lite.formats

class PubspecTest : FmtTestCase() {
    private val pubspec = """
        name: app
        environment:
          sdk: ^3.5.0
        workspace:
          - packages/shapes
          - "packages/colors"
          - packages/*
        dependencies:
          flutter:
            sdk: flutter
          shapes:
            path: packages/shapes
          colors: { path: packages/colors }
          http: ^1.2.0
        dev_dependencies:
          tools:
            path: './tools' # local
        flutter:
          assets:
            - assets/logo.png
            - assets/icons/
            - path: assets/dev.json
              flavors: [dev]
          fonts:
            - family: Mono
              fonts:
                - asset: fonts/mono.ttf
                  weight: 400
    """

    private fun target(file: String, marked: String) = check(file to "x", "pubspec.yaml" to pubspec.replaceFirst(marked.replace("<ref>", ""), marked))

    fun `test path dependency`() = target("packages/shapes/pubspec.yaml", "path: <ref>packages/shapes")
    fun `test quoted path with a comment`() = target("tools/pubspec.yaml", "path: '<ref>./tools'")
    fun `test workspace member`() = target("packages/shapes/pubspec.yaml", "- <ref>packages/shapes")
    fun `test quoted workspace member`() = target("packages/colors/pubspec.yaml", "- \"<ref>packages/colors\"")
    fun `test asset file`() = target("assets/logo.png", "- <ref>assets/logo.png")
    fun `test asset folder`() = target("assets/icons/add.svg", "- <ref>assets/icons/")
    fun `test asset with flavors`() = target("assets/dev.json", "- path: <ref>assets/dev.json")
    fun `test font asset`() = target("fonts/mono.ttf", "- asset: <ref>fonts/mono.ttf")

    fun `test what is no path`() {
        val paths = Fmt.links(myFixture.configureByText("pubspec.yaml", pubspec.trimIndent())).paths.map { it.path }
        assertEquals(listOf("packages/shapes", "packages/colors", "packages/shapes", "./tools", "assets/logo.png", "assets/icons", "assets/dev.json", "fonts/mono.ttf"), paths)
        assertEmpty(Fmt.links(myFixture.configureByText("other.yaml", pubspec.trimIndent())).paths)
    }
}
