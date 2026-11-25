package me.kevinlot.lite.groovy

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class GroovyTest : BasePlatformTestCase() {

    private fun referenceAt(file: PsiFile, offset: Int): PsiPolyVariantReference =
        generateSequence(file.findElementAt(offset)) { it.parent }.takeWhile { it !is PsiFile }
            .flatMap { it.references.asSequence() }.firstOrNull { it.rangeInElement.shiftRight(it.element.textRange.startOffset).containsOffset(offset) }
            as? PsiPolyVariantReference ?: error("no reference at <ref>")

    /** <ref> resolves to exactly <def> (or to the first file when it has no <def>). */
    private fun check(vararg files: Pair<String, String>) {
        val sources = files.map { (path, raw) -> path to raw.trimIndent() }
        sources.forEach { (path, text) -> myFixture.addFileToProject(path, text.replace("<def>", "").replace("<ref>", "")) }
        fun marked(marker: String, other: String): Pair<String, Int>? =
            sources.firstNotNullOfOrNull { (path, text) -> text.replace(other, "").indexOf(marker).takeIf { it >= 0 }?.let { path to it } }
        val (refFile, refOffset) = marked("<ref>", "<def>") ?: error("no <ref>")
        val def = marked("<def>", "<ref>")
        val file = myFixture.configureFromTempProjectFile(refFile)
        assertNull(firstError(file))
        val targets = referenceAt(file, refOffset).multiResolve(false).mapNotNull { it.element }
        val describe = targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }
        if (def == null) return assertTrue("resolved to $describe", (targets.singleOrNull() as? PsiFile)?.virtualFile?.path?.endsWith(files.first().first) == true)
        val target = targets.singleOrNull() ?: return fail("resolved to $describe")
        val offset = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: target.textOffset
        assertTrue("resolved to $describe", target.containingFile.virtualFile.path.endsWith(def.first) && offset == def.second)
    }

    private fun one(code: String) = check("main.groovy" to code)

    private fun unresolved(code: String) {
        val text = code.trimIndent()
        val file = myFixture.configureByText("main.groovy", text.replace("<ref>", ""))
        assertEmpty(referenceAt(file, text.indexOf("<ref>")).multiResolve(false).toList())
    }

    private fun firstError(file: PsiFile): String? = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        ?.let { "${it.errorDescription} at ${it.textOffset}: '${file.text.substring(it.textOffset).take(30)}'" }

    private fun parses(name: String, code: String): PsiFile {
        val file = myFixture.configureByText(name, code.trimIndent())
        assertNull(firstError(file))
        return file
    }

    private fun tokens(text: String, from: Int = 0): List<Triple<String, String, Int>> {
        val lexer = GroovyLexer()
        lexer.start(text, from, text.length, 0)
        return generateSequence { lexer.tokenType?.let { type -> Triple(type.toString(), lexer.tokenText, lexer.state).also { lexer.advance() } } }.toList()
    }

    private fun lex(text: String): String = tokens(text).filter { it.first != "WHITE_SPACE" }.joinToString(" ") { (type, token, _) ->
        when (type) {
            "IDENT", "NUMBER", "STRING", "GSTRING_BEGIN", "GSTRING_TEXT", "GSTRING_END", "LINE_COMMENT", "BLOCK_COMMENT", "DOC_COMMENT", "SHEBANG" -> "$type<$token>"
            else -> token
        }
    }

    // ---------------------------------------------------------------- file types

    fun `test file types`() {
        val types = FileTypeManager.getInstance()
        listOf("A.groovy", "a.gvy", "build.gradle", "Jenkinsfile").forEach { assertEquals(it, GroovyFileType, types.getFileTypeByFileName(it)) }
        listOf("build.gradle.kts", "Jenkinsfile.yaml").forEach { assertNotSame(it, GroovyFileType, types.getFileTypeByFileName(it)) }
    }

    // ---------------------------------------------------------------- lexer

    fun `test comments and shebang`() = assertEquals(
        "SHEBANG<#!/usr/bin/env groovy> LINE_COMMENT<// line> BLOCK_COMMENT</* block */> DOC_COMMENT</** doc */> BLOCK_COMMENT</**/> IDENT<x>",
        lex("#!/usr/bin/env groovy\n// line\n/* block */ /** doc */ /**/ x"),
    )

    fun `test numbers`() = assertEquals(
        "NUMBER<1> NUMBER<1_000> NUMBER<0x1F> NUMBER<0b101> NUMBER<1.5> NUMBER<1e10> NUMBER<2.5e-3f> NUMBER<10G> NUMBER<7L> NUMBER<1> .. NUMBER<5> NUMBER<3> . IDENT<times>",
        lex("1 1_000 0x1F 0b101 1.5 1e10 2.5e-3f 10G 7L 1..5 3.times"),
    )

    fun `test operators`() = assertEquals(
        "IDENT<a> ?. IDENT<b> *. IDENT<c> ?: IDENT<d> <=> IDENT<e> =~ IDENT<f> ==~ IDENT<g> -> IDENT<h> .. IDENT<i> ..< IDENT<j> .& IDENT<k> :: IDENT<l> ** IDENT<m> === IDENT<n>",
        lex("a?.b*.c ?: d <=> e =~ f ==~ g -> h..i..<j.&k::l ** m === n"),
    )

    fun `test plain strings are single tokens`() = assertEquals(
        "STRING<'a\\'b'> STRING<'''multi\nline'''> STRING<\"plain\"> STRING<\"\"\"plain\ntoo\"\"\"> STRING<\"price: \\\$5\">",
        lex("'a\\'b' '''multi\nline''' \"plain\" \"\"\"plain\ntoo\"\"\" \"price: \\\$5\""),
    )

    fun `test gstring interpolation`() = assertEquals(
        "GSTRING_BEGIN<\"> GSTRING_TEXT<Hi > \$ IDENT<name> GSTRING_TEXT<, > \$ IDENT<a> . IDENT<b> GSTRING_TEXT<. > \$ { IDENT<x> + NUMBER<1> } GSTRING_END<\">",
        lex("\"Hi \$name, \$a.b. \${x + 1}\""),
    )

    fun `test gstring nesting`() = assertEquals(
        "GSTRING_BEGIN<\"> \$ { IDENT<m> . IDENT<collect> { GSTRING_BEGIN<\"> GSTRING_TEXT<<> \$ { IDENT<it> } GSTRING_TEXT<>> GSTRING_END<\"> } . IDENT<join> ( STRING<','> ) } GSTRING_END<\">",
        lex("\"\${m.collect { \"<\${it}>\" }.join(',')}\""),
    )

    fun `test keywords inside a string path are names`() = assertEquals(
        "GSTRING_BEGIN<\"\"\"> \$ this . IDENT<class> GSTRING_TEXT< > \$ IDENT<in> GSTRING_END<\"\"\">",
        lex("\"\"\"\$this.class \$in\"\"\""),
    )

    fun `test slashy string after an operator`() = assertEquals(
        "IDENT<x> = STRING</a\\/b+/> IDENT<y> =~ STRING</\\d+/> IDENT<f> ( STRING</re/> , STRING</x/> ) return STRING</z/>",
        lex("x = /a\\/b+/\ny =~ /\\d+/\nf(/re/, /x/)\nreturn /z/"),
    )

    fun `test division after a value`() = assertEquals(
        "IDENT<a> / IDENT<b> / IDENT<c> IDENT<f> ( IDENT<x> ) / NUMBER<2> IDENT<l> [ NUMBER<0> ] / NUMBER<2> NUMBER<4> / NUMBER<2> / NUMBER<1> IDENT<x> /= NUMBER<2>",
        lex("a / b / c\nf(x) / 2\nl[0] / 2\n4 / 2 / 1\nx /= 2"),
    )

    fun `test slashy and dollar slashy interpolation`() = assertEquals(
        "IDENT<p> = GSTRING_BEGIN</> GSTRING_TEXT<^> \$ IDENT<prefix> GSTRING_TEXT<-\\d+\$> GSTRING_END</> IDENT<q> = GSTRING_BEGIN<\$/> GSTRING_TEXT<C:> \$ { IDENT<dir> } GSTRING_TEXT</x\$\$> GSTRING_END</\$>",
        lex("p = /^\$prefix-\\d+\$/\nq = \$/C:\${dir}/x\$\$/\$"),
    )

    fun `test keyword after a dot is a name`() = assertEquals("IDENT<a> . IDENT<class> . IDENT<default> ?. IDENT<in> class", lex("a.class.default?.in class"))

    /** The editor re-lexes from the nearest token whose state is 0: the tokens must come out the same. */
    fun `test lexing restarts at any state 0 token`() {
        val text = "def re = /a+/\nprintln \"x \${ [1, 2].collect { \"\$it/\${it / 2}\" } } y\" + a / b\nfoo.class\n\"\"\"m\n\$a.b\n\"\"\"\nq = \$/x \$y/\$\n"
        val all = tokens(text)
        assertTrue(all.any { it.first == "GSTRING_TEXT" })
        assertTrue(all.count { it.third == 0 } > 10)
        val offsets = all.runningFold(0) { offset, token -> offset + token.second.length }
        all.indices.filter { all[it].third == 0 }.forEach { assertEquals("restart at ${offsets[it]}", all.drop(it), tokens(text, offsets[it])) }
    }

    fun `test highlighting of an edited string`() {
        myFixture.configureByText("a.groovy", "def a = \"x <caret>\"\ndef b = a / 2\n")
        myFixture.type("\${a")
        myFixture.type("}")
        myFixture.doHighlighting()
        val highlighter = myFixture.editor.highlighter
        val edited = generateSequence(highlighter.createIterator(0)) { it.advance(); it.takeIf { !it.atEnd() } }.map { it.tokenType.toString() }.toList()
        assertEquals(tokens(myFixture.editor.document.text).map { it.first }, edited)
    }

    // ---------------------------------------------------------------- parser

    fun `test empty file`() {
        assertEmpty(parses("empty.groovy", "").children)
        parses("blank.groovy", "\n  // nothing\n")
    }

    fun `test parses classes`() {
        parses("Shapes.groovy", """
            package shapes.model

            import groovy.transform.CompileStatic
            import static java.lang.Math.*
            import java.util.concurrent.TimeUnit as Unit

            @CompileStatic
            abstract class Shape implements Comparable<Shape>, Serializable {
                static final double EPSILON = 1e-9
                protected String name
                private Map<String, List<Integer>> tags = [:]
                def meta

                Shape(String name) { this.name = name }

                abstract double area()

                @Override
                int compareTo(Shape other) { area() <=> other.area() }

                static <T extends Shape> T largest(List<T> shapes) { shapes.max { it.area() } }

                String toString() { "${'$'}name(${'$'}{area().round(2)})" }

                static class Builder { Shape build() { null } }
            }

            interface Scalable { Shape scaled(double factor) }

            trait Named { String label() { "named" } }

            enum Color {
                RED('r'), GREEN('g') { String code() { 'G' } }, BLUE('b');
                final String code
                Color(String code) { this.code = code }
            }

            @interface Marker { String value() default "" }

            class Circle extends Shape implements Scalable, Named {
                double radius
                Circle(double radius) { super('circle'); this.radius = radius }
                double area() { PI * radius ** 2 }
                Shape scaled(double factor) { new Circle(radius * factor) }
                def "spock style name"() { expect: area() > 0 }
            }
        """)
    }

    fun `test parses statements and expressions`() {
        parses("script.groovy", """
            def (a, b) = [1, 2]
            def (int c, String d) = [3, 'x']
            int i = 0, j = 1
            final List<String> names = ['x', 'y'] as List<String>
            def map = [a: 1, 'b': 2, (c): 3, *: other, "${'$'}{d}": 4]
            def empty = [:]
            x = cond ? a : b ?: c
            y = (String) obj
            z = obj as int[]
            if (a in names && !(b instanceof Integer) || c !in [1, 2]) println a else println b
            if (a) { b } else if (c) { d } else { a }
            for (int k = 0; k < 10; k++) { continue }
            for (name in names) println name
            for (String s : names) { break }
            while (i < 3) i++
            do { i-- } while (i > 0)
            outer:
            for (q in 1..3) { if (q == 2) break outer }
            switch (a) {
                case 1: println 'one'; break
                case 2..5:
                case [7, 8]:
                    println 'few'
                    break
                case String: return
                default: println 'many'
            }
            def kind = switch (a) { case 1 -> 'one'; default -> 'other' }
            try { risky() } catch (IOException | IllegalStateException e) { throw e } catch (e) { log e } finally { done() }
            try (def r = open()) { r.read() }
            synchronized (lock) { counter++ }
            assert a == 1 : "a is ${'$'}a"
            def square = { n -> n * n }
            def add = { int p, int q = 2 -> p + q }
            def nothing = { -> 42 }
            def lambda = (p, q) -> p + q
            def inc = v -> v + 1
            names.each { println it }.collect { it.size() }
            names.eachWithIndex { n, idx ->
                println "${'$'}idx: ${'$'}n"
            }
            def r1 = a >> 2
            def r2 = a >>> 2 << 1
            r1 >>= 1
            def p = names*.size()
            def m = this.&square
            def ref = String::valueOf
            def len = names?.first()?.length() ?: 0
            def range = (0..<names.size()).step(2)
            def bits = ~a & b | c ^ d
            println(names[0], names[-1], map['a'], map.'b', map."${'$'}d")
            def arr = new int[3]
            def obj2 = new ArrayList<String>(10) { int size() { 0 } }
            def pattern = ~/\d+/
            def half = total / 2 / count
            def text = '''raw ${'$'}x'''
            def multi = ${'"'}""
                Hello ${'$'}{names.join(', ')}
            ""${'"'}
            return
        """)
    }

    fun `test parses command chains`() {
        val file = parses("chains.groovy", """
            println x
            println "a", b
            sh script: 'ls', returnStdout: true
            status = sh returnStatus: true, script: 'true'
            def out = sh(script: 'ls').trim()
            id 'java' version '1.0' apply false
            id('org.x') version '2.0'
            implementation 'a:b:c'
            compile project(':core'), files('lib.jar')
            checkout scm
            run [1, 2]
            list[0].foo bar
            take 2 pills of medicine
        """)
        val calls = PsiTreeUtil.findChildrenOfType(file, GroovyCall::class.java).map { it.text }
        listOf("println x", "println \"a\", b", "id 'java' version '1.0' apply false", "run [1, 2]", "take 2 pills of", "sh returnStatus: true, script: 'true'")
            .forEach { assertTrue(it, it in calls) }
    }

    fun `test parses a declarative Jenkinsfile`() {
        parses("Jenkinsfile", """
            #!/usr/bin/env groovy
            @Library('shared-lib@main') _
            import com.acme.Deployer

            def version = '1.0'

            pipeline {
                agent { docker { image 'gradle:8'; args '-v /cache:/cache' } }
                options { timeout(time: 30, unit: 'MINUTES'); disableConcurrentBuilds() }
                parameters {
                    string(name: 'ENV', defaultValue: 'dev', description: 'Target')
                    booleanParam name: 'DRY_RUN', defaultValue: false
                }
                environment {
                    APP = "shapes-${'$'}{env.BRANCH_NAME}"
                    TOKEN = credentials('api-token')
                }
                stages {
                    stage('Build') {
                        steps {
                            sh './gradlew build'
                            script {
                                version = sh(script: 'git describe', returnStdout: true).trim()
                                def parts = version.split(/\./)
                                currentBuild.displayName = "#${'$'}{env.BUILD_NUMBER} ${'$'}version"
                            }
                        }
                    }
                    stage('Test') {
                        when { anyOf { branch 'main'; expression { params.ENV == 'prod' } } }
                        parallel {
                            stage('Unit') { steps { sh "./gradlew test -Pversion=${'$'}{version}" } }
                            stage('Lint') { steps { sh 'make lint' } }
                        }
                    }
                    stage('Deploy') {
                        steps {
                            deployApp env: params.ENV, version: version
                            notifySlack "Deployed ${'$'}version"
                        }
                    }
                }
                post {
                    always { junit '**/build/test-results/**/*.xml' }
                    failure { mail to: 'team@acme.io', subject: "Failed: ${'$'}{currentBuild.fullDisplayName}", body: env.BUILD_URL }
                }
            }
        """)
    }

    fun `test parses a build gradle`() {
        parses("build.gradle", """
            buildscript {
                ext.kotlinVersion = '2.0.0'
                repositories { mavenCentral() }
                dependencies { classpath "org.jetbrains.kotlin:kotlin-gradle-plugin:${'$'}kotlinVersion" }
            }

            plugins {
                id 'java'
                id 'org.springframework.boot' version '3.2.0' apply false
            }

            apply plugin: 'groovy'
            apply from: 'gradle/lint.gradle'

            group = 'com.acme'
            version = '1.0-SNAPSHOT'
            sourceCompatibility = JavaVersion.VERSION_17

            def libs = [junit: 'org.junit.jupiter:junit-jupiter:5.10.0']

            repositories {
                mavenCentral()
                maven { url 'https://repo.acme.io/releases'; credentials { username = findProperty('user') ?: '' } }
            }

            dependencies {
                implementation project(':core')
                implementation group: 'org.apache.commons', name: 'commons-lang3', version: '3.14.0'
                implementation('com.acme:lib:1.0') { exclude group: 'log4j', module: 'log4j' }
                testImplementation libs.junit
                testRuntimeOnly "org.junit.platform:junit-platform-launcher"
            }

            sourceSets { main { groovy { srcDirs = ['src/main/groovy'] } } }

            test {
                useJUnitPlatform()
                testLogging { events 'passed', 'failed' }
                maxParallelForks = Runtime.runtime.availableProcessors() / 2 ?: 1
            }

            task copyDocs(type: Copy, dependsOn: 'build') {
                from 'docs'
                into "${'$'}buildDir/docs"
                doLast { println "Copied to ${'$'}{destinationDir}" }
            }

            tasks.register('hello') { doLast { logger.lifecycle "Hello ${'$'}project.name" } }
            tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8' }
            subprojects { p -> p.afterEvaluate { if (p.plugins.hasPlugin('java')) p.tasks.test.enabled = false } }
        """)
    }

    fun `test broken code does not stop the parser`() {
        val file = myFixture.configureByText("broken.groovy", "def x = (1 +\nclass A { def m( { ] }\n\"unclosed \${a\nfoo(")
        assertNotNull(firstError(file))
        assertEquals(file.text.length, file.node.textLength)
        assertNotNull(PsiTreeUtil.findChildOfType(file, GroovyClass::class.java))
    }

    // ---------------------------------------------------------------- locals

    fun `test local`() = one("def <def>x = 1\nprintln <ref>x")
    fun `test typed local`() = one("String <def>name = 'a'\nprintln(<ref>name)")
    fun `test local declared after the use is not visible`() = unresolved("def f = { <ref>later }\nf()\nif (true) { def later = 1 }")
    fun `test shadowing in a nested block`() = one("def x = 1\nif (true) {\n  def <def>x = 2\n  println <ref>x\n}")
    fun `test local is not visible in its own initializer`() = one("def <def>x = 1\nif (x) { def x = <ref>x + 1 }")
    fun `test second variable of a declaration`() = one("int a = 1, <def>b = 2\nprintln <ref>b")
    fun `test multiple assignment`() = one("def (a, <def>b) = [1, 2]\nprintln a + <ref>b")
    fun `test parameter`() = one("def greet(String <def>who, int n) {\n  return 'hi ' + <ref>who\n}")
    fun `test parameter default sees an earlier parameter`() = one("def f(<def>a, b = <ref>a) { b }")
    fun `test closure parameter`() = one("[1].each { <def>n -> println <ref>n }")
    fun `test typed closure parameter`() = one("def add = { int p, int <def>q -> p + <ref>q }")
    fun `test implicit it`() = one("[1].each <def>{ println <ref>it }")
    fun `test implicit it of the outer closure`() = one("[1].each <def>{ [2].each { x -> println <ref>it } }")
    fun `test lambda parameter`() = one("def f = (<def>a, b) -> <ref>a + b")
    fun `test for in variable`() = one("for (<def>item in [1, 2]) { println <ref>item }")
    fun `test typed for variable`() = one("for (String <def>s : names) println <ref>s")
    fun `test classic for variable`() = one("for (int <def>i = 0; i < 3; i++) { println <ref>i }")
    fun `test catch parameter`() = one("try { } catch (Exception <def>e) { println <ref>e }")
    fun `test resource variable`() = one("try (def <def>r = open()) { <ref>r.read() }")
    fun `test local after a label`() = one("def test() {\n  given:\n  def <def>x = 1\n  expect:\n  <ref>x == 1\n}")
    fun `test name inside a string`() = one("def <def>who = 'x'\nprintln \"hi \$<ref>who\"")
    fun `test name inside a string expression`() = one("def <def>who = 'x'\nprintln \"hi \${<ref>who.size()}\"")
    fun `test path inside a string`() = one("class P { String <def>name }\nP p = new P()\nprintln \"hi \$p.<ref>name!\"")
    fun `test closure inside a string`() = one("def xs = []\nprintln \"\${xs.collect { <def>v -> <ref>v * 2 }}\"")
    fun `test named argument label is no reference`() {
        val file = parses("a.groovy", "def name = 1\nfoo(name: 2)")
        assertEquals(listOf("foo"), PsiTreeUtil.findChildrenOfType(file, GroovyNameRef::class.java).map { it.text })
    }

    // ---------------------------------------------------------------- scripts

    fun `test script method`() = one("def <def>build(target) { }\n<ref>build('x')")
    fun `test script method declared later`() = one("<ref>build 'x'\nvoid <def>build(String target) { }")
    fun `test overload by argument count`() = one("def f(a) { }\ndef <def>f(a, b) { }\n<ref>f(1, 2)")
    fun `test argument count never drops every candidate`() = one("def <def>f(a) { }\n<ref>f(1, 2, 3)")
    fun `test named arguments count as one map`() = one("def f() { }\ndef <def>f(Map args) { }\n<ref>f name: 'x', size: 2")
    fun `test trailing closure counts as an argument`() = one("def f(a) { }\ndef <def>f(a, Closure body) { }\n<ref>f(1) { }")
    fun `test script variable used in a method declared before`() = one("def use() { <ref>config }\n@groovy.transform.Field def <def>config = [:]")
    fun `test binding`() = one("<def>version = '1.0'\nprintln <ref>version")
    fun `test binding assigned in a closure`() = one("node {\n  <def>TAG = sh(script: 'git describe')\n}\necho \"\$<ref>TAG\"")
    fun `test later assignment refers to the first`() = one("<def>count = 0\n<ref>count = count + 1")
    fun `test declared variable is no binding`() = one("def <def>x = 1\n<ref>x = 2")
    fun `test this in a script`() = one("def <def>helper() { }\nthis.<ref>helper()")
    fun `test unknown name`() = unresolved("println <ref>env")

    // ---------------------------------------------------------------- classes

    fun `test field through implicit this`() = one("class A {\n  int <def>count\n  def inc() { <ref>count++ }\n}")
    fun `test field through this`() = one("class A {\n  int <def>count\n  A(int count) { this.<ref>count = count }\n}")
    fun `test field declared after the method`() = one("class A {\n  def get() { <ref>cache }\n  private Map <def>cache = [:]\n}")
    fun `test parameter shadows a field`() = one("class A {\n  int n\n  def set(int <def>n) { this.n = <ref>n }\n}")
    fun `test method of the class`() = one("class A {\n  def <def>helper() { }\n  def run() { <ref>helper() }\n}")
    fun `test method of the outer class`() = one("class A {\n  def <def>helper() { }\n  class B { def run() { <ref>helper() } }\n}")
    fun `test call prefers a method over a field`() = one("class A {\n  def run\n  def <def>run() { }\n  def go() { <ref>run() }\n}")
    fun `test inherited method`() = one("class Base { def <def>log(m) { } }\nclass A extends Base { def run() { <ref>log('x') } }")
    fun `test method of a trait`() = one("trait Named { String <def>label() { 'n' } }\nclass A implements Named { def run() { <ref>label() } }")
    fun `test super`() = one("class Base { def <def>run() { } }\nclass A extends Base { def run() { super.<ref>run() } }")
    fun `test class in the same file`() = one("class <def>Point { }\ndef p = new <ref>Point()")
    fun `test class as a type`() = one("class <def>Point { }\n<ref>Point origin = null")
    fun `test class in generics`() = one("class <def>Point { }\nList<<ref>Point> points = []")
    fun `test nested class`() = one("class A {\n  static class <def>Builder { }\n  def make() { new <ref>Builder() }\n}")
    fun `test qualified nested class`() = one("class A { static class <def>Builder { } }\ndef b = new A.<ref>Builder()")
    fun `test enum constant`() = one("enum Color { RED, <def>GREEN }\ndef c = Color.<ref>GREEN")
    fun `test member of a declared type`() = one("class P { def <def>move() { } }\nP p = make()\np.<ref>move()")
    fun `test member of a new expression`() = one("class P { int <def>x }\ndef p = new P()\nprintln p.<ref>x")
    fun `test member of a typed parameter`() = one("class P { int <def>x }\ndef show(P p) { p?.<ref>x }")
    fun `test member of a typed field`() = one("class P { int <def>x }\nclass A {\n  P origin\n  def show() { origin.<ref>x + this.origin.x }\n}")
    fun `test static member`() = one("class U { static def <def>trim(s) { s } }\nU.<ref>trim(' x ')")
    fun `test member through a declared return type`() = one("class P { int <def>x }\nP make() { new P() }\nmake().<ref>x")
    fun `test property read through its getter`() = one("class P { String <def>getName() { 'p' } }\nP p = new P()\np.<ref>name")
    fun `test method pointer`() = one("class P { def <def>move() { } }\nP p = new P()\ndef m = p.&<ref>move")
    fun `test member of an untyped value is unresolved`() = unresolved("class P { int x }\ndef p = make()\np.<ref>x")

    fun `test imported class`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "main.groovy" to "import com.acme.Deployer\nnew <ref>Deployer()",
    )
    fun `test import statement`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "main.groovy" to "import com.acme.<ref>Deployer",
    )
    fun `test import alias`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "main.groovy" to "import com.acme.Deployer as D\nnew <ref>D()",
    )
    fun `test star import`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "main.groovy" to "import com.acme.*\n<ref>Deployer d = null",
    )
    fun `test same package`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "src/com/acme/Main.groovy" to "package com.acme\nclass Main { <ref>Deployer d }",
    )
    fun `test another package is not visible without an import`() {
        myFixture.addFileToProject("src/com/acme/Deployer.groovy", "package com.acme\nclass Deployer { }")
        unresolved("new <ref>Deployer()")
    }
    fun `test fully qualified class`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass <def>Deployer { }",
        "main.groovy" to "def d = new com.acme.<ref>Deployer()",
    )
    fun `test member of an imported class`() = check(
        "src/com/acme/Deployer.groovy" to "package com.acme\nclass Deployer { def <def>deploy(env) { } }",
        "main.groovy" to "import com.acme.Deployer\ndef d = new Deployer()\nd.<ref>deploy('prod')",
    )
    fun `test static import`() = check(
        "src/com/acme/Util.groovy" to "package com.acme\nclass Util { static def <def>slug(s) { s } }",
        "main.groovy" to "import static com.acme.Util.slug\n<ref>slug('A b')",
    )
    fun `test static star import`() = check(
        "src/com/acme/Util.groovy" to "package com.acme\nclass Util { static final int <def>LIMIT = 3 }",
        "main.groovy" to "import static com.acme.Util.*\nprintln <ref>LIMIT",
    )
    fun `test inherited member across files`() = check(
        "src/com/acme/Base.groovy" to "package com.acme\nclass Base { def <def>log(m) { } }",
        "src/com/acme/Job.groovy" to "package com.acme\nclass Job extends Base { def run() { <ref>log 'x' } }",
    )

    // ---------------------------------------------------------------- Jenkins and Gradle

    fun `test step with a call method`() = check(
        "vars/deployApp.groovy" to "def <def>call(Map args) { echo args.env }",
        "Jenkinsfile" to "node { <ref>deployApp env: 'prod' }",
    )
    fun `test step chooses the call method by argument count`() = check(
        "vars/notify.groovy" to "def call() { }\ndef <def>call(String message) { }",
        "Jenkinsfile" to "<ref>notify('done')",
    )
    fun `test step without a call method`() = check("vars/settings.groovy" to "class X { }", "Jenkinsfile" to "<ref>settings()")
    fun `test local method wins over a step`() = check(
        "vars/build.groovy" to "def call() { }",
        "Jenkinsfile" to "def <def>build() { }\n<ref>build()",
    )
    fun `test a name that is not called is no step`() {
        myFixture.addFileToProject("vars/deployApp.groovy", "def call() { }")
        unresolved("println <ref>deployApp")
    }
    fun `test load`() = check("ci/deploy.groovy" to "def run() { }", "Jenkinsfile" to "def d = load '<ref>ci/deploy.groovy'")
    fun `test load relative to the file`() = check("ci/deploy.groovy" to "def run() { }", "ci/main.groovy" to "load('<ref>deploy.groovy')")
    fun `test apply from`() = check("gradle/lint.gradle" to "task lint { }", "build.gradle" to "apply from: '<ref>gradle/lint.gradle'")
    fun `test apply plugin is no path`() {
        val file = parses("build.gradle", "apply plugin: 'java'\nprintln 'x.gradle'")
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, GroovyLiteral::class.java).flatMap { it.references.toList() })
    }

    /** What `playground/README.md` promises for `playground/pipeline`. */
    fun `test playground sample`() {
        val root = File("../playground/pipeline")
        root.walkTopDown().filter { it.isFile }.forEach { myFixture.addFileToProject(it.relativeTo(root).path, it.readText()) }
        fun target(path: String, snippet: String): String? {
            val file = myFixture.configureFromTempProjectFile(path)
            assertNull(firstError(file))
            val found = referenceAt(file, file.text.indexOf(snippet)).multiResolve(false).singleOrNull()?.element ?: return null
            return "${found.containingFile.name}: ${found.text.lineSequence().first()}"
        }
        assertEquals("deployApp.groovy: def call(Map args) {", target("Jenkinsfile", "deployApp env"))
        assertEquals("Release.groovy: class Release implements Serializable {", target("Jenkinsfile", "Release(version)"))
        assertEquals("Release.groovy: String tag() {", target("Jenkinsfile", "tag()}"))
        assertEquals("Jenkinsfile: version", target("Jenkinsfile", "version.split"))
        assertEquals("Jenkinsfile: target", target("Jenkinsfile", "target, version"))
        assertEquals("Jenkinsfile: { echo \"deployed to \$it\" }", target("Jenkinsfile", "it\" }"))
        assertTrue(target("Jenkinsfile", "ci/notify.groovy").orEmpty().startsWith("notify.groovy"))
        assertTrue(target("build.gradle", "gradle/versions.gradle").orEmpty().startsWith("versions.gradle"))
        assertEquals("build.gradle: sharedLibrary = ['src', 'vars']", target("build.gradle", "sharedLibrary }"))
        assertEquals("Release.groovy: boolean isStable() {", target("vars/deployApp.groovy", "isStable"))
        assertEquals("Release.groovy: ATTEMPTS = 3", target("vars/deployApp.groovy", "ATTEMPTS"))
        assertEquals("Release.groovy: private boolean snapshot() {", target("src/playground/ci/Release.groovy", "snapshot()"))
    }

    // ---------------------------------------------------------------- editing

    private fun named(file: PsiFile, name: String): PsiElement =
        PsiTreeUtil.findChildrenOfType(file, PsiNameIdentifierOwner::class.java).first { it.name == name && it.nameIdentifier != null }

    fun `test name offset is the name leaf`() {
        val file = parses("a.groovy", "@Deprecated class Shape { private static final int limit = 1\n  @Override String label(int size) { 'x' } }\nenum E { ONE }\ntrait T { }")
        val declarations = PsiTreeUtil.findChildrenOfType(file, GroovyNamed::class.java)
        assertEquals(listOf("Shape", "limit", "label", "size", "E", "ONE", "T"), declarations.map { it.name })
        declarations.forEach { assertEquals(it.name, file.text.indexOf(it.name.orEmpty(), it.textRange.startOffset), it.textOffset) }
    }

    fun `test rename local`() {
        myFixture.configureByText("a.groovy", "def co<caret>unt = 0\ncount = count + 1\nprintln \"\$count \${count * 2}\"\n")
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("def total = 0\ntotal = total + 1\nprintln \"\$total \${total * 2}\"\n")
    }

    fun `test rename parameter from a use`() {
        myFixture.configureByText("a.groovy", "def greet(who) { println wh<caret>o }\ndef who = 1\n")
        myFixture.renameElementAtCaret("name")
        myFixture.checkResult("def greet(name) { println name }\ndef who = 1\n")
    }

    fun `test rename binding`() {
        myFixture.configureByText("Jenkinsfile", "TAG = 'a'\nnode { echo T<caret>AG }\nTAG = 'b'\n")
        myFixture.renameElementAtCaret("VERSION")
        myFixture.checkResult("VERSION = 'a'\nnode { echo VERSION }\nVERSION = 'b'\n")
    }

    fun `test rename method across files`() {
        val main = myFixture.addFileToProject("main.groovy", "import com.acme.Deployer\nDeployer d = new Deployer()\nd.deploy('prod')\n")
        myFixture.configureByText("Deployer.groovy", "package com.acme\nclass Deployer {\n  def dep<caret>loy(env) { }\n  def all() { deploy('dev'); this.deploy('qa') }\n}\n")
        myFixture.renameElementAtCaret("release")
        myFixture.checkResult("package com.acme\nclass Deployer {\n  def release(env) { }\n  def all() { release('dev'); this.release('qa') }\n}\n")
        assertEquals("import com.acme.Deployer\nDeployer d = new Deployer()\nd.release('prod')\n", main.text)
    }

    fun `test rename class updates imports and types`() {
        val main = myFixture.addFileToProject("main.groovy", "import com.acme.Deployer\nDeployer d = new Deployer()\nList<Deployer> all = []\n")
        myFixture.configureByText("Deployer.groovy", "package com.acme\nclass Deplo<caret>yer { Deployer self() { this } }\n")
        myFixture.renameElementAtCaret("Releaser")
        myFixture.checkResult("package com.acme\nclass Releaser { Releaser self() { this } }\n")
        assertEquals("import com.acme.Releaser\nReleaser d = new Releaser()\nList<Releaser> all = []\n", main.text)
    }

    fun `test rename step file`() {
        val pipeline = myFixture.addFileToProject("Jenkinsfile", "node { deployApp env: 'prod' }\n")
        val step = myFixture.addFileToProject("vars/deployApp.groovy", "def call(Map args) { }\n")
        myFixture.renameElement(step, "releaseApp.groovy")
        assertEquals("node { releaseApp env: 'prod' }\n", pipeline.text)
    }

    fun `test rename loaded file`() {
        val pipeline = myFixture.addFileToProject("Jenkinsfile", "def d = load 'ci/deploy.groovy'\n")
        val script = myFixture.addFileToProject("ci/deploy.groovy", "def run() { }\n")
        myFixture.renameElement(script, "release.groovy")
        assertEquals("def d = load 'ci/release.groovy'\n", pipeline.text)
    }

    private fun usages(element: PsiElement): Int = ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll().size

    fun `test find usages of a method`() {
        myFixture.addFileToProject("main.groovy", "import com.acme.Deployer\nDeployer d = new Deployer()\nd.deploy('a')\nd.deploy('b')\ndeploy('not this one')\n")
        val file = myFixture.addFileToProject("src/com/acme/Deployer.groovy", "package com.acme\nclass Deployer {\n  def deploy(env) { }\n  def all() { deploy('c') }\n}\n")
        assertEquals(3, usages(named(file, "deploy")))
    }

    fun `test find usages of a class`() {
        myFixture.addFileToProject("main.groovy", "import com.acme.Deployer\nDeployer d = new Deployer()\n")
        val file = myFixture.addFileToProject("src/com/acme/Deployer.groovy", "package com.acme\nclass Deployer { }\n")
        assertEquals(3, usages(named(file, "Deployer")))
    }

    fun `test find usages of a local stay in its scope`() {
        val file = myFixture.configureByText("a.groovy", "def f() { def x = 1; x + x }\ndef g() { def x = 2; x }\n")
        val x = named(file, "x")
        assertEquals(2, ReferencesSearch.search(x).findAll().size)
        assertTrue(x.useScope is com.intellij.psi.search.LocalSearchScope)
    }

    fun `test find usages of a binding`() {
        val file = myFixture.configureByText("Jenkinsfile", "TAG = 'a'\nnode { echo TAG; echo \"\$TAG\" }\nTAG = 'b'\n")
        assertEquals(3, ReferencesSearch.search(named(file, "TAG")).findAll().size)
    }

    fun `test find usages of a step`() {
        myFixture.addFileToProject("Jenkinsfile", "node { deployApp env: 'prod' }\ndeployApp(env: 'dev')\n")
        val step = myFixture.addFileToProject("vars/deployApp.groovy", "def call(Map args) { }\n")
        assertEquals(2, usages(named(step, "call")))
        assertEquals(2, usages(step))
    }

    fun `test highlighting has no errors on valid code`() {
        myFixture.configureByText("a.groovy", "class A { int n\n  def m(int p) { [1, 2].each { println \"\$it \${p + n}\" } } }\nnew A().m(1)\n")
        assertEmpty(myFixture.doHighlighting().filter { it.severity.name == "ERROR" })
    }

    // ---------------------------------------------------------------- real files (read-only, optional)

    private fun isGroovy(f: File) = f.extension in setOf("groovy", "gvy", "gradle") || f.name == "Jenkinsfile"

    /** LITE_GROOVY_CORPUS=<dir:dir>: every Groovy file lexes and parses; prints the share of references resolved. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_GROOVY_CORPUS")?.split(':')?.map(::File) ?: return
        dirs.forEach { VfsRootAccess.allowRootAccess(testRootDisposable, it.path) }
        val show = System.getenv("LITE_SHOW") != null
        val files = dirs.flatMap { d -> d.walkTopDown().onEnter { it.name !in setOf(".git", "node_modules") }.filter { it.isFile && isGroovy(it) && it.length() < 1_000_000 } }
        val started = System.nanoTime()
        val reports = files.mapNotNull { f ->
            val psi = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)?.let(psiManager::findFile) as? GroovyFile ?: return@mapNotNull null
            val unknown = tokens(psi.text).firstOrNull { it.first == "BAD_CHARACTER" }?.let { "unknown character '${it.second}'" }
            val references = PsiTreeUtil.findChildrenOfAnyType(psi, GroovyNameRef::class.java, GroovyMember::class.java, GroovyType::class.java)
                .filter { !(it is GroovyNameRef && it.isBindingDefinition) }.flatMap { it.references.toList() }.filterIsInstance<PsiPolyVariantReference>()
            val missed = references.filter { it.multiResolve(false).isEmpty() }.map { "${f.name}: ${it.element.text.take(40)}" }
            Triple((firstError(psi) ?: unknown)?.let { "${f.path}: $it" }, references.size, missed)
        }
        val broken = reports.mapNotNull { it.first }
        val total = reports.sumOf { it.second }
        val missed = reports.flatMap { it.third }
        val shown = (broken.take(60) + missed.shuffled(java.util.Random(1)).take(80)).takeIf { show }.orEmpty()
        println(
            "groovy corpus in ${(System.nanoTime() - started) / 1_000_000} ms: ${reports.size - broken.size}/${reports.size} files without errors, " +
                "references ${total - missed.size}/$total resolved (no JDK, no Gradle or Jenkins DSL)\n" + shown.joinToString("\n"),
        )
        assertTrue("${broken.size} of ${reports.size} files with errors", broken.size <= reports.size * TOLERATED_ERROR_RATE)
    }

    private companion object {
        const val TOLERATED_ERROR_RATE = 0.02
    }
}
