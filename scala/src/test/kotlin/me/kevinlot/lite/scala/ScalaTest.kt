package me.kevinlot.lite.scala

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class ScalaTest : BasePlatformTestCase() {

    private fun referenceAt(file: PsiFile, offset: Int): PsiPolyVariantReference =
        generateSequence(file.findElementAt(offset)) { it.parent }.takeWhile { it !is PsiFile }
            .flatMap { it.references.asSequence() }.firstOrNull { it.rangeInElement.shiftRight(it.element.textRange.startOffset).containsOffset(offset) }
            as? PsiPolyVariantReference ?: error("no reference at <ref>")

    /** <ref> resolves to exactly <def>. */
    private fun check(vararg files: Pair<String, String>) {
        val sources = files.map { (path, raw) -> path to raw.trimIndent() }
        sources.forEach { (path, text) -> myFixture.addFileToProject(path, text.replace("<def>", "").replace("<ref>", "")) }
        fun marked(marker: String, other: String): Pair<String, Int>? =
            sources.firstNotNullOfOrNull { (path, text) -> text.replace(other, "").indexOf(marker).takeIf { it >= 0 }?.let { path to it } }
        val (refFile, refOffset) = marked("<ref>", "<def>") ?: error("no <ref>")
        val def = marked("<def>", "<ref>") ?: error("no <def>")
        val file = myFixture.configureFromTempProjectFile(refFile)
        assertNull(firstError(file))
        val targets = referenceAt(file, refOffset).multiResolve(false).mapNotNull { it.element }
        val describe = targets.map { "${it.containingFile?.name}:${it.textOffset} ${it.text.take(30)}" }
        val target = targets.singleOrNull() ?: return fail("resolved to $describe")
        val offset = (target as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: target.textOffset
        assertTrue("resolved to $describe", target.containingFile.virtualFile.path.endsWith(def.first) && offset == def.second)
    }

    private fun one(code: String) = check("Main.scala" to code)

    private fun unresolved(code: String) {
        val text = code.trimIndent()
        val file = myFixture.configureByText("Main.scala", text.replace("<ref>", ""))
        assertNull(firstError(file))
        assertEmpty(referenceAt(file, text.indexOf("<ref>")).multiResolve(false).toList())
    }

    private fun firstError(file: PsiFile): String? = PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java)
        ?.let { "${it.errorDescription} at ${it.textOffset}: '${file.text.substring(it.textOffset).take(30)}'" }

    private fun parses(name: String, code: String): PsiFile {
        val file = myFixture.configureByText(name, code.trimIndent())
        assertNull(firstError(file))
        return file
    }

    private fun parses(code: String): PsiFile = parses("Main.scala", code)

    /** Names declared by the file, in source order. */
    private fun declared(file: PsiFile): List<String?> = PsiTreeUtil.findChildrenOfType(file, ScalaNamed::class.java).map { it.name }

    private fun tokens(text: String, from: Int = 0): List<Triple<String, String, Int>> {
        val lexer = ScalaLexer()
        lexer.start(text, from, text.length, 0)
        return generateSequence { lexer.tokenType?.let { type -> Triple(type.toString(), lexer.tokenText, lexer.state).also { lexer.advance() } } }.toList()
    }

    private fun lex(text: String): String = tokens(text).filter { it.first != "WHITE_SPACE" }.joinToString(" ") { (type, token, _) ->
        when (type) {
            "IDENT", "OP", "NUMBER", "CHAR", "SYMBOL", "STRING", "INTERPOLATOR", "ISTRING_BEGIN", "ISTRING_TEXT", "ISTRING_END", "LINE_COMMENT",
            "BLOCK_COMMENT", "DOC_COMMENT", "SHEBANG", "BAD_CHARACTER" -> "$type<$token>"
            else -> token
        }
    }

    // ---------------------------------------------------------------- file types

    fun `test file types`() {
        val types = FileTypeManager.getInstance()
        listOf("A.scala", "notes.sc", "build.sbt").forEach { assertEquals(it, ScalaFileType, types.getFileTypeByFileName(it)) }
        assertNotSame(ScalaFileType, types.getFileTypeByFileName("build.gradle.kts"))
    }

    // ---------------------------------------------------------------- lexer

    fun `test comments nest`() = assertEquals(
        "LINE_COMMENT<// line> BLOCK_COMMENT</* a /* b */ c */> DOC_COMMENT</** doc */> BLOCK_COMMENT</**/> IDENT<x>",
        lex("// line\n/* a /* b */ c */ /** doc */ /**/ x"),
    )

    fun `test script header`() = assertEquals("SHEBANG<#!/usr/bin/env scala\n!#> IDENT<println> ( STRING<\"x\"> )", lex("#!/usr/bin/env scala\n!#\nprintln(\"x\")"))

    fun `test numbers`() = assertEquals(
        "NUMBER<1> NUMBER<1_000> NUMBER<0x1F> NUMBER<0b101> NUMBER<1.5> NUMBER<1e10> NUMBER<2.5e-3f> NUMBER<7L> NUMBER<.5> NUMBER<1> . IDENT<second> NUMBER<3> . IDENT<toString>",
        lex("1 1_000 0x1F 0b101 1.5 1e10 2.5e-3f 7L .5 1.second 3.toString"),
    )

    fun `test operators are names`() = assertEquals(
        "IDENT<a> OP<+> IDENT<b> OP<::> IDENT<c> OP<->> IDENT<d> OP<==> IDENT<e> OP<&&> OP<!> IDENT<f> OP<<=> IDENT<g> OP<%%> IDENT<h> OP<:=> IDENT<i>",
        lex("a + b :: c -> d == e && !f <= g %% h := i"),
    )

    fun `test reserved operators`() = assertEquals(
        "IDENT<x> : IDENT<T> = IDENT<y> => IDENT<z> <- IDENT<w> <: IDENT<A> >: IDENT<B> # IDENT<C> @ IDENT<D> _ ⇒ ←",
        lex("x: T = y => z <- w <: A >: B # C @ D _ ⇒ ←"),
    )

    fun `test operator stops before a comment`() = assertEquals("IDENT<a> OP<+> LINE_COMMENT<// c> IDENT<b> OP<*> BLOCK_COMMENT</* d */>", lex("a +// c\nb */* d */"))

    fun `test identifiers`() = assertEquals(
        "IDENT<`type`> IDENT<unary_!> IDENT<value_=> IDENT<x_1> IDENT<\$name> _ IDENT<_x> _ OP<+> _ IDENT<`a b`>",
        lex("`type` unary_! value_= x_1 \$name _ _x _ + _ `a b`"),
    )

    fun `test keywords`() = assertEquals(
        "case class IDENT<A> extends IDENT<B> with IDENT<C> IDENT<given> IDENT<enum> IDENT<then> IDENT<end> IDENT<using>",
        lex("case class A extends B with C given enum then end using"),
    )

    fun `test characters and symbols`() = assertEquals(
        "CHAR<'a'> CHAR<'\\n'> CHAR<'\\u0041'> CHAR<'\\''> CHAR<'\"'> SYMBOL<'sym> SYMBOL<'+> IDENT<f> ( CHAR<'('> )",
        lex("'a' '\\n' '\\u0041' '\\'' '\"' 'sym '+ f('(')"),
    )

    fun `test plain strings are single tokens`() = assertEquals(
        "STRING<\"a\\\"b\"> STRING<\"\"\"multi\n\"line\"\"\"\"> STRING<\"\"> STRING<\"#{el} \$x\"> IDENT<x>",
        lex("\"a\\\"b\" \"\"\"multi\n\"line\"\"\"\" \"\" \"#{el} \$x\" x"),
    )

    fun `test string interpolation`() = assertEquals(
        "INTERPOLATOR<s> ISTRING_BEGIN<\"> ISTRING_TEXT<Hi > \$ IDENT<name> ISTRING_TEXT<, > \$ { IDENT<x> OP<+> NUMBER<1> } ISTRING_TEXT< \$\$5 \\\"q\\\"> ISTRING_END<\">",
        lex("s\"Hi \$name, \${x + 1} \$\$5 \\\"q\\\"\""),
    )

    fun `test interpolation nests`() = assertEquals(
        "INTERPOLATOR<f> ISTRING_BEGIN<\"> \$ { IDENT<m> . IDENT<map> { IDENT<x> => INTERPOLATOR<s> ISTRING_BEGIN<\"> ISTRING_TEXT<<> \$ IDENT<x> ISTRING_TEXT<>> ISTRING_END<\"> } } ISTRING_TEXT<%s> ISTRING_END<\">",
        lex("f\"\${m.map { x => s\"<\$x>\" }}%s\""),
    )

    fun `test triple quoted interpolation`() = assertEquals(
        "INTERPOLATOR<raw> ISTRING_BEGIN<\"\"\"> ISTRING_TEXT<a \"q\" > \$ this ISTRING_TEXT<.b\n> \$ IDENT<in> ISTRING_TEXT<\"> ISTRING_END<\"\"\"> IDENT<x>",
        lex("raw\"\"\"a \"q\" \$this.b\n\$in\"\"\"\" x"),
    )

    /** The editor re-lexes from the nearest token whose state is 0: the tokens must come out the same. */
    fun `test lexing restarts at any state 0 token`() {
        val text = "val a = s\"x \${ List(1, 2).map { i => s\"\$i/\${i / 2}\" } } y\" + b / c\n/* a /* b */ c */ val d = 'x'\n" +
            "val e = raw\"\"\"m\n\$a \"q\"\n\"\"\"\nval f = \"plain\" :: 'sym :: Nil\n"
        val all = tokens(text)
        assertTrue(all.any { it.first == "ISTRING_TEXT" })
        assertTrue(all.count { it.third == 0 } > 20)
        assertTrue(all.any { it.third == 1 })
        val offsets = all.runningFold(0) { offset, token -> offset + token.second.length }
        all.indices.filter { all[it].third == 0 }.forEach { assertEquals("restart at ${offsets[it]}", all.drop(it), tokens(text, offsets[it])) }
    }

    fun `test highlighting of an edited string`() {
        myFixture.configureByText("a.scala", "val a = s\"x <caret>\"\nval b = a / 2\n")
        myFixture.type("\${a")
        myFixture.type("}")
        myFixture.doHighlighting()
        val highlighter = myFixture.editor.highlighter
        val edited = generateSequence(highlighter.createIterator(0)) { it.advance(); it.takeIf { !it.atEnd() } }.map { it.tokenType.toString() }.toList()
        assertEquals(tokens(myFixture.editor.document.text).map { it.first }, edited)
    }

    // ---------------------------------------------------------------- parser

    fun `test empty file`() {
        assertEmpty(parses("").children)
        parses("\n  // nothing\n")
    }

    fun `test parses packages and imports`() {
        val file = parses("""
            package a.b
            package c

            import x.y.Z
            import x.y._
            import x.y.{A, B => C, D => _, _}
            import x.y.*
            import x.y.{A as B, given}
            import x.y.Z as W, q.R
            import scala.concurrent.duration.DurationInt

            package d {
              class Inner
            }
            package object tools { val limit = 1 }
        """)
        val inner = PsiTreeUtil.findChildrenOfType(file, ScalaClass::class.java).first { it.name == "Inner" }
        assertEquals("a.b.c.d.Inner", inner.qualifiedName)
        assertEquals(listOf("a.b.c.d", "a.b.c", "a.b", ""), ScalaFiles.visiblePackages(inner))
        assertEquals(listOf("a.b.c.d" to "Inner", "a.b.c" to "tools", "a.b.c.tools" to "limit"), ScalaFiles.topLevel(file).map { it.packageName to it.declaration.name })
    }

    fun `test parses templates`() {
        val file = parses("""
            @SerialVersionUID(1L)
            sealed abstract class Shape[+A <: AnyRef : Ordering](val name: String, private var sides: Int = 0)(implicit ev: A =:= String)
              extends Base[A](name) with Serializable with Comparable[Shape[A]] { self: Named =>
              import self._
              protected[this] lazy val area: Double = 0.0
              var (w, h) = (1, 2)
              val Some(first) = Option(1)
              val a, b: Int = 0
              type Id = String
              type F[X] <: Seq[X]
              def this(name: String) = this(name, 0)
              def +(other: Shape[A]): Shape[A] = this
              def unary_- : Shape[A] = this
              def area_=(v: Double): Unit = ()
              override final def toString: String = s"${'$'}name"
              def curried[B](x: => B)(f: B => Unit, rest: Int*): Unit
              def proc() { println("old") }
            }
            object Shape extends App
            case object Empty extends Shape[String]("empty")
            final case class Circle(radius: Double) extends Shape[String]("circle")
            trait Named { def label: String }
            class Outer { class Inner; object Nested { val deep = 1 } }
        """)
        assertEquals(
            listOf("Shape", "A", "name", "sides", "ev", "self", "area", "w", "h", "first", "a", "b", "Id", "F", "X", null, "name", "+", "other",
                "unary_-", "area_=", "v", "toString", "curried", "B", "x", "f", "rest", "proc", "Shape", "Empty", "Circle", "radius", "Named",
                "label", "Outer", "Inner", "Nested", "deep"),
            declared(file),
        )
    }

    fun `test parses expressions`() {
        parses("""
            object Main {
              val f = (x: Int) => x + 1
              val g: (Int, Int) => Int = (a, b) => a * b
              val h = xs.map(_ + 1).filter { x => x > 0 }.foldLeft(0)(_ + _)
              val i = if (a) b else if (c) d else e
              val j = xs map (_.size) filter (_ > 1) mkString ","
              val k = 1 to 10 by 2
              val l = new Foo(1) with Bar { def x = 1 }
              val m = try risky() catch { case e: Exception => 0 } finally cleanup()
              val n = obj.method[Int](1)(2) { 3 }
              val o = a -> b :: Nil
              val p: Seq[Int] = xs: _*
              val q = (a, b, c)
              val r = xs.foo _
              val s = -x + !y
              val t = a match { case 1 => "one" case _ => "other" }
              var u = 0
              u += 1
              u = u * 2
              obj.field = 3
              arr(0) = 1
              while (u < 10) u += 1
              do { u -= 1 } while (u > 0)
              for (i <- 1 to 3; j = i * 2 if j > 2) println(j)
              throw new IllegalStateException("x")
              def early(): Int = { if (u > 0) return 1; 2 }
              val v = { implicit x: Int => x }
              val w = duration(5 seconds)
              val y = 5.seconds + (2 minutes)
              val z = classOf[Map[String, List[_ <: AnyRef]]]
            }
        """)
    }

    fun `test line breaks`() {
        val file = parses("""
            object Main {
              val a = foo
                .bar
                .baz(1)
              val b = x +
                y
              val c =
                compute(1)
              val d = xs
              (1, 2)
              val e = if (cond)
                1
              else
                2
              def f(x: Int)
                : Int = x
              val g = h
              { println("block") }
            }
        """)
        val main = PsiTreeUtil.findChildOfType(file, ScalaClass::class.java)
        assertEquals(listOf("a", "b", "c", "d", "e", "f", "g"), main?.members?.map { it.name })
    }

    fun `test interpolated strings`() {
        val file = parses("""
            val name = "x"
            val a = s"Hello ${'$'}name and ${'$'}{name.toUpperCase} or ${'$'}{ if (name.isEmpty) "none" else s"<${'$'}name>" }"
            val b = f"${'$'}{1.5}%2.2f ${'$'}${'$'}"
            val c = s""${'"'}{"id": "${'$'}name", "n": ${'$'}{name.length}}""${'"'}
            val d = custom"x${'$'}name"
        """)
        assertEquals(7, PsiTreeUtil.findChildrenOfType(file, ScalaRef::class.java).count { it.name == "name" })
    }

    fun `test nested comments`() {
        parses("""
            /* outer /* inner */ still a comment */
            object A {
              /** doc with /* nested */ text */
              val x = 1 // trailing
              val y = /* inline */ 2
            }
        """)
    }

    fun `test infix chains`() {
        parses("""
            val scn = scenario("Users") exec http("home").get("/") pause (1 second) exec { session => session }
            val check = status is 200
            val all = scn inject (atOnceUsers(10), rampUsers(100) during (10 seconds)) protocols httpConf
            val cond = a && b || !c &&
              d
            val list = 1 :: 2 :: Nil ++ other
            libraryDependencies ++= Seq("org" %% "name" % "1.0" % Test)
            name := "demo"
        """)
    }

    fun `test for comprehensions`() {
        parses("""
            val a = for (x <- xs) yield x
            val b = for {
              x <- xs
              (k, v) <- x.pairs
              y = k + v
              if y > 0
              Some(z) <- lookup(y)
            } yield z
            for (i <- 0 until n; j <- i until n if i != j) {
              swap(i, j)
            }
            val c = for (x: Int <- xs if x > 0; y <- ys) yield (x, y)
        """)
    }

    fun `test pattern matching`() {
        val file = parses("""
            def describe(x: Any): String = x match {
              case 1 | 2 => "small"
              case -1 => "negative"
              case n: Int if n > 100 => "big"
              case s: String => s
              case (a, b) => "pair"
              case head :: tail => "list"
              case Some(Circle(r)) => "circle"
              case p @ Point(_, y) if y > 0 => "point"
              case List(first, _*) => "many"
              case xs @ Seq(_, rest @ _*) => "seq"
              case `expected` => "same"
              case Color.Red | Color.Blue => "color"
              case _: Runnable | _: Comparable[_] => "typed"
              case s"prefix${'$'}rest" => "text"
              case null => "null"
              case _ => "other"
            }
            val pf: PartialFunction[Int, String] = { case 1 => "one"; case other => other.toString }
            val (q, w) = pair
            val first :: second :: _ = list
            try run() catch {
              case e: IllegalArgumentException => throw e
              case e @ (_: A | _: B) => ()
              case scala.util.control.NonFatal(e) => log(e)
            }
            xs.foreach { case (k, v) => println(k) }
        """)
        val variables = PsiTreeUtil.findChildrenOfType(file, ScalaVariable::class.java).map { it.name }
        assertEquals(
            listOf("x", "n", "s", "a", "b", "head", "tail", "r", "p", "y", "first", "xs", "rest", "pf", "other", "q", "w", "first", "second", "e", "e", "e", "k", "v"),
            variables,
        )
    }

    fun `test gatling simulation`() {
        parses("Sim.scala", """
            package simulations

            import io.gatling.core.Predef._
            import io.gatling.http.Predef._
            import scala.concurrent.duration._

            class BasicSimulation extends Simulation {

              val httpProtocol = http
                .baseUrl("https://example.org")
                .acceptHeader("application/json")
                .userAgentHeader("Gatling")

              val feeder = csv("users.csv").circular

              @ScenarioName("users")
              val scn = scenario("Users")
                .feed(feeder)
                .exec(
                  http("list")
                    .get("/users")
                    .check(status.is(200), jsonPath("$[0].id").saveAs("id"))
                )
                .pause(1.second, 3 seconds)
                .doIf(session => session("id").asOption[String].nonEmpty
                  // a comment inside the condition
                  && session("role").as[String] == "admin"
                ) {
                  exec(session => {
                    val id = session("id").as[String]
                    session.set("url", s"/users/${'$'}id")
                  }).exec(http("one").get("#{url}").check(status is 200))
                }
                .repeat(3, "i") {
                  exec(http("page").get("/page/#{i}"))
                }

              setUp(
                scn.inject(
                  nothingFor(4 seconds),
                  atOnceUsers(10),
                  rampUsers(100) during (30 seconds),
                  constantUsersPerSec(20) during (15 seconds) randomized
                ).protocols(httpProtocol)
              ).maxDuration(5 minutes)
                .assertions(global.responseTime.max.lt(500), forAll.failedRequests.percent.lte(5))
            }
        """)
    }

    fun `test scala 3 braceless file`() {
        val file = parses("Shapes.scala", """
            package shapes

            import scala.math.{Pi as pi, *}

            enum Color(val rgb: Int):
              case Red extends Color(0xFF0000)
              case Green extends Color(0x00FF00)
              def isRed: Boolean = this == Red

            enum Tree[+A]:
              case Leaf
              case Node(left: Tree[A], value: A, right: Tree[A])

            trait Shape:
              def area: Double
              def describe: String =
                val a = area
                if a > 100 then "big"
                else if a > 10 then
                  "medium"
                else
                  "small"

            final class Circle(radius: Double) extends Shape:
              def area: Double = pi * radius * radius
            end Circle

            object Shapes:
              given ordering: Ordering[Shape] with
                def compare(a: Shape, b: Shape): Int = a.area.compare(b.area)

              given Conversion[Double, Circle] = Circle(_)

              extension (s: Shape)
                def scaled(k: Double): Double = s.area * k
                def isBig: Boolean = s.area > 100

              inline def largest(shapes: List[Shape])(using ord: Ordering[Shape]): Shape =
                shapes.max

              opaque type Id = Int

              def classify(s: Shape): String = s match
                case c: Circle => "circle"
                case _ =>
                  val name = s.describe
                  name

              def total(shapes: List[Shape]): Double =
                var sum = 0.0
                for s <- shapes do
                  sum += s.area
                while sum > 1000 do sum -= 1000
                val doubled =
                  for
                    s <- shapes
                    if s.isBig
                  yield s.area * 2
                try
                  doubled.sum
                catch
                  case e: ArithmeticException => 0.0
                finally
                  println("done")

              def each(shapes: List[Shape]): Unit =
                shapes.foreach: s =>
                  println(s.describe)
                shapes.map: s =>
                  s.area
            end Shapes

            @main def run(): Unit =
              println(Shapes.total(Nil))
        """)
        val names = PsiTreeUtil.findChildrenOfType(file, ScalaClass::class.java).map { it.name }
        assertEquals(listOf("Color", "Tree", "Shape", "Circle", "Shapes"), names)
        val shapes = PsiTreeUtil.findChildrenOfType(file, ScalaClass::class.java).first { it.name == "Shapes" }
        assertEquals(listOf("ordering", null, "scaled", "isBig", "largest", "Id", "classify", "total", "each"), shapes.members.map { it.name })
        val softKeywords = PsiTreeUtil.collectElements(file) { it.node.elementType == ScalaTokens.SOFT_KEYWORD }.map { it.text }.toSet()
        assertEquals(setOf("as", "end", "enum", "extension", "given", "inline", "opaque", "then", "using"), softKeywords)
    }

    fun `test scala 3 names stay names in scala 2`() {
        val file = parses("""
            object A {
              val end = 10
              val given = end - 1
              def then(x: Int) = x
              val open = then(given)
              val using = List(end, open).map(inline => inline + 1)
              given(using)
              extension(open)
            }
        """)
        assertEmpty(PsiTreeUtil.collectElements(file) { it.node.elementType == ScalaTokens.SOFT_KEYWORD })
    }

    fun `test scala 2 syntax of the standard library`() {
        val file = parses("""
            object Lib {
              def fold[B](z: B)
                         (f: (B, Int) => B)(implicit @deprecated ord: Ordering[B]): B = z
              class Iter[A](start: Option[A], end: Option[A])
                           (implicit ord: Ordering[A]) extends Iterator[A]
              def refined[C0](c: C0): IsMap[C0] { type K = Int; type V = String } =
                new IsMap[C0] { type K = Int; type V = String }
              lazy val loaders = new {
                val global: Lib.this.type = Lib.this
              } with Loaders
              trait View[K, +V] extends MapOps[K, V, ({ type l[X, Y] = View[(X, Y)] })#l, View[(K, V)]]
              type ::[+A] = scala.collection.immutable.::[A]
              val :: = scala.collection.immutable.::
              val -> = Tuple2
              object #:: { def unapply[A](s: Stream[A]): Option[(A, Stream[A])] = None }
              sealed abstract class <:<[-From, +To] extends (From => To)
              def cast[A](x: Any) = x.asInstanceOf[::[A]]
              def nothing: Nothing = ???
              val x = if (ready) ??? else 1
            }
        """)
        val lib = PsiTreeUtil.findChildOfType(file, ScalaClass::class.java)
        assertEquals(listOf("fold", "Iter", "refined", "loaders", "View", "::", "::", "->", "#::", "<:<", "cast", "nothing", "x"), lib?.members?.map { it.name })
    }

    fun `test scala 3 syntax of the compiler`() {
        val file = parses("Dotc.scala", """
            object Dotc:
              def conditions(a: Boolean, b: Boolean): Int =
                if (a) && b.self then 1
                else if a ||
                  // a comment inside the condition
                  b
                then
                  val x = 2
                  if x > 1 then log(x)
                  x
                else if (b)
                  val y = 3
                  y
                else 0

              def leading(a: Boolean, b: Boolean) =
                     a && b
                  || a.self
                  || b

              def clauses(xs: List[(Int, Int)]) =
                for (a, b) <- xs do
                  println(a); println(b)
                while (a) && b do step()
                val f = run { ctx ?=> (x: Int) => x + 1 }
                xs.headOption.match
                  case Some(pair) => pair
                  case None => (0, 0)
                xs.collect:
                  case (a, b) if a > b => a
                .map: a =>
                  a + 1
                .size
                call(
                  xs.map:
                    _._1,
                  xs.size)

              def recover(): Int =
                try compute()
                catch case ex: Exception =>
                  log(ex)
                  -1
                finally cleanup()

              enum Extension(val name: String):
                case Tasty extends Extension("tasty")
                private case External(override val name: String) extends Extension(name)

              type Elem[X <: Tuple] = X match
                case h *: t => h
                case EmptyTuple => Nothing

              given listOrd: [T: Ordering] => Ordering[List[T]] = ???
              def captured(f: Int => Int): View[Int]^{this, f} = ???
              def quoted(e: Expr[Int]) = e match
                case '{ ${'$'}x: Int } => 1
                case _ => 0
            end Dotc
        """)
        val dotc = PsiTreeUtil.findChildOfType(file, ScalaClass::class.java)
        assertEquals(listOf("conditions", "leading", "clauses", "recover", "Extension", "Elem", "listOrd", "captured", "quoted"), dotc?.members?.map { it.name })
    }

    fun `test broken code does not stop the parser`() {
        val file = myFixture.configureByText("Main.scala", "class A {\n  def f(x: Int = {\n  val y = )\n}\nclass B { def ok = 1 }\nobject C { val z = 2 }\n")
        assertNotNull(firstError(file))
        assertTrue(declared(file).containsAll(listOf("A", "f", "ok", "C", "z")))
    }

    // ---------------------------------------------------------------- resolve: locals

    fun `test local val`() = one("""
        def f(): Int = {
          val <def>count = 1
          <ref>count + 1
        }
    """)

    fun `test local declared later is not visible`() = unresolved("""
        def f(): Int = {
          val a = <ref>later
          val later = 1
          a
        }
    """)

    fun `test local function is visible before its declaration`() = one("""
        def f(): Int = {
          val a = <ref>helper(1)
          def <def>helper(x: Int) = x
          a
        }
    """)

    fun `test shadowing takes the nearest`() = one("""
        val x = 1
        def f(): Int = {
          val x = 2
          locally {
            val <def>x = 3
            <ref>x
          }
        }
    """)

    fun `test parameter`() = one("def f(a: Int, <def>b: Int): Int = a + <ref>b")

    fun `test parameter of a second clause in a default value`() = one("def f(<def>a: Int)(b: Int = <ref>a): Int = b")

    fun `test lambda parameter`() = one("val f = xs.map(<def>x => <ref>x + 1)")

    fun `test lambda parameters in parentheses`() = one("val f = xs.fold(0)((acc, <def>x) => acc + <ref>x)")

    fun `test block lambda owns the rest of the block`() = one("""
        xs.foreach { <def>item =>
          val twice = item * 2
          println(<ref>item + twice)
        }
    """)

    fun `test local in a block lambda`() = one("""
        xs.foreach { item =>
          val <def>twice = item * 2
          println(<ref>twice)
        }
    """)

    fun `test case pattern variable`() = one("""
        x match {
          case Some(<def>value) if value > 0 => <ref>value
          case _ => 0
        }
    """)

    fun `test pattern variable in a guard`() = one("""
        x match {
          case (<def>a, b) if <ref>a > b => a
        }
    """)

    fun `test typed pattern variable`() = one("""
        x match {
          case <def>e: Exception => throw <ref>e
        }
    """)

    fun `test pattern variable of another case is not visible`() = unresolved("""
        x match {
          case Some(value) => 1
          case _ => <ref>value
        }
    """)

    fun `test partial function argument`() = one("pairs.map { case (<def>k, v) => <ref>k + v }")

    fun `test catch variable`() = one("try run() catch { case <def>e: Exception => log(<ref>e) }")

    fun `test pattern definition`() = one("""
        def f() = {
          val (a, <def>b) = pair
          a + <ref>b
        }
    """)

    fun `test extractor pattern definition`() = one("""
        def f() = {
          val Some(<def>x) = maybe
          <ref>x
        }
    """)

    fun `test generator variable in the body`() = one("for (<def>x <- xs) yield <ref>x * 2")

    fun `test generator variable in a later generator`() = one("for { <def>x <- xs; y <- <ref>x.items } yield y")

    fun `test generator definition and guard`() = one("""
        for {
          x <- xs
          <def>y = x * 2
          if <ref>y > 2
        } yield x
    """)

    fun `test generator variable is not visible in its own source`() = unresolved("for (x <- <ref>x) yield 1")

    fun `test name in an interpolated string`() = one("""
        def greet(<def>who: String) = s"Hello ${'$'}<ref>who"
    """)

    fun `test expression in an interpolated string`() = one("""
        def greet(<def>who: String) = s"Hello ${'$'}{<ref>who.trim}!"
    """)

    fun `test type parameter`() = one("def id[<def>T](x: <ref>T): T = x")

    fun `test class type parameter`() = one("class Box[<def>A](value: A) { def get: <ref>A = value }")

    // ---------------------------------------------------------------- resolve: members

    fun `test member of the enclosing class`() = one("""
        class A {
          def run() = <ref>helper()
          private def <def>helper() = 1
        }
    """)

    fun `test constructor parameter`() = one("""
        class A(<def>base: String) {
          val url = <ref>base + "/x"
        }
    """)

    fun `test constructor parameter in the parent arguments`() = one("class A(<def>base: String) extends B(<ref>base)")

    fun `test member of an outer class`() = one("""
        class Outer {
          val <def>limit = 1
          class Inner { def f = <ref>limit }
        }
    """)

    fun `test inherited member`() = one("""
        trait Base { def <def>greet(): String = "hi" }
        trait Other
        class A extends Other with Base {
          def run() = <ref>greet()
        }
    """)

    fun `test inherited member across files`() = check(
        "src/base/Base.scala" to "package base\nabstract class Base(val name: String) { protected def <def>log(m: String): Unit = () }\n",
        "src/app/Main.scala" to "package app\nimport base.Base\nclass Main extends Base(\"m\") { def run() = <ref>log(name) }\n",
    )

    fun `test overloads are told apart by their arity`() = one("""
        object A {
          def f(x: Int) = 1
          def <def>f(x: Int, y: Int) = 2
          val r = <ref>f(1, 2)
        }
    """)

    fun `test overloads are never all dropped`() {
        val file = parses("""
            object A {
              def f(x: Int) = 1
              def f(x: Int, y: Int) = 2
              val r = f(1, 2, 3)
            }
        """)
        assertEquals(2, referenceAt(file, file.text.lastIndexOf("f(1")).multiResolve(false).size)
    }

    fun `test this member`() = one("""
        class A(x: Int) {
          val <def>size = x
          def f = this.<ref>size
        }
    """)

    fun `test this`() = one("""
        class <def>A {
          def f = <ref>this
        }
    """)

    fun `test super member`() = one("""
        class Base { def <def>name = "b" }
        class A extends Base { override def name = super.<ref>name + "a" }
    """)

    fun `test self type name`() = one("""
        trait A { <def>self =>
          def f = <ref>self
        }
    """)

    fun `test member through a declared type`() = one("""
        class Config { val <def>url = "x" }
        def f(c: Config) = c.<ref>url
    """)

    fun `test member through new`() = one("""
        class Config { val <def>url = "x" }
        object Main {
          val c = new Config
          val u = c.<ref>url
        }
    """)

    fun `test member of a case class instance`() = one("""
        case class User(<def>name: String, age: Int)
        object Main {
          val u = User("a", 1)
          val n = u.<ref>name
        }
    """)

    fun `test member through a companion apply`() = one("""
        class Urls(env: String) { val <def>internal = env }
        object Urls { def apply(p: Params): Urls = new Urls(p.env) }
        class Mission(p: Params) {
          private val urls = Urls(p)
          val x = urls.<ref>internal
        }
    """)

    fun `test member of an object`() = one("""
        object Headers { val <def>Json = "application/json" }
        val h = Headers.<ref>Json
    """)

    fun `test member of a nested object`() = one("""
        object A { object B { def <def>c = 1 } }
        val x = A.B.<ref>c
    """)

    fun `test member through a declared result type`() = one("""
        class Config { def <def>url = "x" }
        object Main {
          def load(): Config = ???
          val u = load().<ref>url
        }
    """)

    fun `test member through a function whose value is written`() = one("""
        class Urls(env: String) { val <def>internal = env }
        class Spec {
          private def urls(env: String) = new Urls(env)
          val x = urls("dev").<ref>internal
        }
    """)

    fun `test member through a method chain`() = one("""
        class Builder { def step(): Builder = this; def <def>build(): String = "" }
        val s = new Builder().step().step().<ref>build()
    """)

    fun `test member through an ascription`() = one("""
        class Config { val <def>url = "x" }
        val u = (any: Config).<ref>url
    """)

    fun `test member of an enum`() = one("""
        enum Color:
          case Red, <def>Green
        val c = Color.<ref>Green
    """)

    fun `test member without a written type is not guessed`() = unresolved("""
        class Config { val url = "x" }
        def f(c: Any) = { val d = identity(c); d.<ref>url }
    """)

    fun `test infix operator is a method of the left operand`() = one("""
        class Money(n: Int) { def <def>plus(o: Money): Money = this }
        def f(a: Money, b: Money) = a <ref>plus b
    """)

    fun `test symbolic operator`() = one("""
        class Money(n: Int) { def <def>+(o: Money): Money = this }
        def f(a: Money, b: Money) = a <ref>+ b
    """)

    fun `test postfix operator`() = one("""
        class Money(n: Int) { def <def>cents: Int = n }
        def f(a: Money) = a <ref>cents
    """)

    fun `test named argument`() = one("""
        def connect(host: String, <def>port: Int = 80) = host
        val c = connect("h", <ref>port = 8080)
    """)

    fun `test named argument of a constructor`() = one("""
        case class User(name: String, <def>age: Int)
        val u = User(name = "a", <ref>age = 3)
    """)

    fun `test named argument of new`() = one("""
        class User(name: String, <def>age: Int)
        val u = new User(name = "a", <ref>age = 3)
    """)

    fun `test named argument of copy`() = one("""
        case class User(name: String, <def>age: Int) {
          def older = copy(<ref>age = age + 1)
        }
    """)

    fun `test named argument of copy through a value`() = one("""
        case class User(name: String, <def>age: Int)
        def f(u: User) = u.copy(<ref>age = 1)
    """)

    fun `test typed lambda parameter at the start of a block`() = one("xs.foreach { <def>item: Item => println(<ref>item) }")

    // ---------------------------------------------------------------- resolve: cycles

    fun `test a value defined by itself resolves to nothing`() {
        unresolved("object A { def f = f.<ref>x }")
        unresolved("object A { val a = b.c; val b = a.<ref>d }")
    }

    fun `test cyclic inheritance`() = unresolved("""
        class A extends B
        class B extends A { val x = <ref>missing }
    """)

    fun `test import of the enclosing object`() = one("""
        object A {
          import A._
          val <def>limit = 1
          def f = <ref>limit
        }
    """)

    fun `test parent found through an import of the enclosing object`() = one("""
        object Trees {
          import Trees._
          trait <def>Tree
          class Leaf extends <ref>Tree
        }
    """)

    // ---------------------------------------------------------------- resolve: Scala 3 without braces

    fun `test member in an indented template`() = one("""
        class A(x: Int):
          val <def>size = x
          def twice =
            val two = 2
            <ref>size * two
    """)

    fun `test local in an indented body`() = one("""
        def f(x: Int) =
          val <def>two = 2
          if x > two then
            x * <ref>two
          else 0
    """)

    fun `test pattern variable in an indented case`() = one("""
        def f(x: Option[Int]) = x match
          case Some(<def>v) =>
            val w = v
            <ref>v + w
          case None => 0
    """)

    fun `test parameter of an indented lambda`() = one("""
        def f(xs: List[Int]) =
          xs.map: <def>x =>
            val y = 1
            <ref>x + y
    """)

    fun `test extension parameter`() = one("""
        extension (<def>s: String)
          def shout: String = <ref>s.toUpperCase
    """)

    fun `test extension method`() = one("""
        object Syntax:
          extension (s: String)
            def <def>shout: String = s.toUpperCase
          def f = <ref>shout("x")
    """)

    fun `test given by name`() = one("""
        trait Ord[T]
        object Ords:
          given <def>intOrd: Ord[Int] with
            def compare(a: Int, b: Int) = 0
          val o = <ref>intOrd
    """)

    fun `test using parameter`() = one("""
        trait Ctx
        def f(x: Int)(using <def>ctx: Ctx) = g(x)(using <ref>ctx)
    """)

    fun `test enum case in a pattern`() = one("""
        enum Color:
          case <def>Red, Green
        def f(c: Color) = c match
          case Color.<ref>Red => 1
          case _ => 0
    """)

    // ---------------------------------------------------------------- resolve: classes and objects

    fun `test class in a type`() = one("""
        class <def>Config
        def f(c: <ref>Config) = c
    """)

    fun `test class in a type argument`() = one("""
        class <def>Config
        val all: List[Map[String, <ref>Config]] = Nil
    """)

    fun `test class after new`() = one("""
        class <def>Config(x: Int)
        object Config
        val c = new <ref>Config(1)
    """)

    fun `test parent class`() = one("""
        abstract class <def>Base(x: Int)
        class A extends <ref>Base(1)
    """)

    fun `test mixed in trait`() = one("""
        trait <def>Logging
        class A extends Object with <ref>Logging
    """)

    fun `test a type is the class and a value is the object`() = one("""
        class Config
        object <def>Config { val default = 1 }
        val d = <ref>Config.default
    """)

    fun `test case class call is the class`() = one("""
        case class <def>User(name: String)
        val u = <ref>User("a")
    """)

    fun `test call of an object with apply is its apply`() = one("""
        class User(name: String)
        object User { def <def>apply(name: String): User = new User(name) }
        val u = <ref>User("a")
    """)

    fun `test extractor is the class`() = one("""
        case class <def>User(name: String)
        def f(x: Any) = x match { case <ref>User(n) => n }
    """)

    fun `test object as a value`() = one("""
        object <def>Registry
        val r = register(<ref>Registry)
    """)

    fun `test type alias`() = one("""
        class Config { val url = "" }
        object Types { type <def>Conf = Config }
        import Types._
        def f(c: <ref>Conf) = c
    """)

    fun `test member through a type alias`() = one("""
        class Config { val <def>url = "" }
        object Types { type Conf = Config }
        def f(c: Types.Conf) = c.<ref>url
    """)

    fun `test annotation class`() = one("""
        class <def>ScenarioName(name: String) extends scala.annotation.StaticAnnotation
        class A { @<ref>ScenarioName("x") val scn = 1 }
    """)

    fun `test class of the same package in another file`() = check(
        "src/main/scala/shop/Reward.scala" to "package scenarios.shop\nclass <def>Reward(p: Params)\n",
        "src/main/scala/shop/RewardScenarios.scala" to "package scenarios.shop\nclass RewardScenarios(p: Params) { val reward = new <ref>Reward(p) }\n",
    )

    fun `test class of an enclosing package`() = check(
        "a/Base.scala" to "package a\nclass <def>Base\n",
        "a/b/Main.scala" to "package a\npackage b\nclass Main extends <ref>Base\n",
    )

    fun `test class of another package needs an import`() {
        myFixture.addFileToProject("a/Base.scala", "package a\nclass Base\n")
        unresolved("package b\nclass Main extends <ref>Base\n")
    }

    fun `test fully qualified class`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nclass Main extends a.b.<ref>Base\n",
    )

    fun `test fully qualified object in an expression`() = check(
        "a/Tools.scala" to "package a.b\nobject Tools { def <def>run() = 1 }\n",
        "Main.scala" to "package c\nobject Main { val x = a.b.Tools.<ref>run() }\n",
    )

    // ---------------------------------------------------------------- resolve: imports

    fun `test import of a class`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.Base\nclass Main extends <ref>Base\n",
    )

    fun `test the name in an import`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.<ref>Base\nclass Main\n",
    )

    fun `test wildcard import`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b._\nclass Main extends <ref>Base\n",
    )

    fun `test scala 3 wildcard import`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.*\nclass Main extends <ref>Base\n",
    )

    fun `test import selectors`() = check(
        "a/Base.scala" to "package a.b\nclass Other\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.{Other, Base}\nclass Main extends <ref>Base\n",
    )

    fun `test the name in an import selector`() = check(
        "a/Base.scala" to "package a.b\nclass Other\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.{Other, <ref>Base}\nclass Main\n",
    )

    fun `test renaming import`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.{Base => Parent}\nclass Main extends <ref>Parent\n",
    )

    fun `test scala 3 renaming import`() = check(
        "a/Base.scala" to "package a.b\nclass <def>Base\n",
        "Main.scala" to "package c\nimport a.b.Base as Parent\nclass Main extends <ref>Parent\n",
    )

    fun `test hidden import`() {
        myFixture.addFileToProject("a/Base.scala", "package a.b\nclass Base\nclass Other\n")
        unresolved("package c\nimport a.b.{Base => _, _}\nclass Main extends <ref>Base\n")
    }

    fun `test import of an object member`() = check(
        "shared/UrlHelper.scala" to "package shared\nobject UrlHelper { def <def>awsInternalUrl(d: String, e: String) = d + e }\n",
        "Main.scala" to "package scenarios\nimport shared.UrlHelper.awsInternalUrl\nclass Urls(env: String) { val t = <ref>awsInternalUrl(\"t\", env) }\n",
    )

    fun `test wildcard import of object members`() = check(
        "shared/Config.scala" to "package shared\nobject Config { val <def>baseUrl = \"x\" }\n",
        "Main.scala" to "package scenarios\nimport shared.Config._\nclass Sim { val u = <ref>baseUrl }\n",
    )

    fun `test the object in an import path`() = check(
        "shared/Config.scala" to "package shared\nobject <def>Config { val baseUrl = \"x\" }\n",
        "Main.scala" to "package scenarios\nimport shared.<ref>Config._\nclass Sim\n",
    )

    fun `test import of the members of a value`() = one("""
        class Params { val <def>env = "dev" }
        class Sim(params: Params) {
          import params._
          val e = <ref>env
        }
    """)

    fun `test relative import`() = check(
        "a/b/Tools.scala" to "package a.b\nobject <def>Tools\n",
        "a/Main.scala" to "package a\nimport b.Tools\nobject Main { val t = <ref>Tools }\n",
    )

    fun `test import inside a block`() = check(
        "a/Tools.scala" to "package a\nobject Tools { def <def>run() = 1 }\n",
        "Main.scala" to "package c\nobject Main { def f() = { import a.Tools._; <ref>run() } }\n",
    )

    fun `test import written after the use is not visible`() {
        myFixture.addFileToProject("a/Tools.scala", "package a\nobject Tools { def run() = 1 }\n")
        unresolved("package c\nobject Main { def f() = { val x = <ref>run(); import a.Tools._; x } }\n")
    }

    fun `test member of a package object`() = check(
        "shared/package.scala" to "package object shared { def <def>env(name: String) = name }\n",
        "Main.scala" to "package scenarios\nimport shared._\nclass Sim { val e = <ref>env(\"x\") }\n",
    )

    fun `test class beside a package object`() = check(
        "shared/package.scala" to "package object shared { val limit = 1 }\n",
        "shared/Feeder.scala" to "package shared\nclass <def>Feeder\n",
        "Main.scala" to "package scenarios\nimport shared._\nclass Sim(f: <ref>Feeder)\n",
    )

    fun `test top level function of scala 3`() = check(
        "a/tools.scala" to "package a\ndef <def>helper(x: Int) = x\n",
        "Main.scala" to "package c\nimport a.helper\nval y = <ref>helper(1)\n",
    )

    // ---------------------------------------------------------------- project boundaries

    fun `test lookups stay inside the project`() {
        myFixture.addFileToProject("one/build.sbt", "name := \"one\"\n")
        myFixture.addFileToProject("one/src/a/Base.scala", "package a\nclass Base\n")
        myFixture.addFileToProject("two/pom.xml", "<project/>")
        val file = myFixture.addFileToProject("two/src/a/Main.scala", "package a\nclass Main extends Base\n")
        assertEmpty(referenceAt(file, file.text.indexOf("Base")).multiResolve(false).toList())
    }

    fun `test build output is ignored`() {
        myFixture.addFileToProject("pom.xml", "<project/>")
        myFixture.addFileToProject("target/generated/a/Base.scala", "package a\nclass Base\n")
        val source = myFixture.addFileToProject("src/a/Base.scala", "package a\nclass Base\n")
        val file = myFixture.addFileToProject("src/a/Main.scala", "package a\nclass Main extends Base\n")
        assertEquals(listOf(source), referenceAt(file, file.text.indexOf("Base")).multiResolve(false).map { it.element?.containingFile })
    }

    fun `test playground sample`() {
        val root = File("../playground/loadtest")
        root.walkTopDown().filter { it.isFile }.forEach { myFixture.addFileToProject(it.relativeTo(root).path, it.readText()) }
        fun target(path: String, snippet: String, shift: Int = 0): String? {
            val file = myFixture.configureFromTempProjectFile(path)
            assertNull(firstError(file))
            val found = referenceAt(file, file.text.indexOf(snippet) + shift).multiResolve(false).singleOrNull()?.element ?: return null
            return "${found.containingFile.name}: ${lineAt(found.containingFile, found.textOffset)}"
        }
        val simulation = "src/test/scala/playground/ShapesSimulation.scala"
        val scenarios = "src/test/scala/playground/scenarios/ShapeScenarios.scala"
        assertEquals("ShapeScenarios.scala: object ShapeScenarios {", target(simulation, "ShapeScenarios.browse"))
        assertEquals("ShapeScenarios.scala: val browse = scenario(\"Browse shapes\")", target(simulation, "browse.inject"))
        assertEquals("Settings.scala: val users: Int = sys.props.getOrElse(\"users\", \"10\").toInt", target(simulation, "users)"))
        assertEquals("Settings.scala: def url(path: String): String = s\"\$baseUrl\$path\"", target(scenarios, "url(\"/shapes\")"))
        assertEquals("Settings.scala: object Settings {", target(scenarios, "Settings.rampUp"))
        assertEquals("ShapeScenarios.scala: private def shape(id: String) =", target(scenarios, "shape(\"#{id}\")"))
        assertEquals("ShapeScenarios.scala: private def shape(id: String) =", target(scenarios, "\$id", 1))
        assertEquals("Settings.scala: val baseUrl: String = sys.props.getOrElse(\"baseUrl\", \"http://localhost:8080\")", target("src/test/scala/playground/config/Settings.scala", "baseUrl\$path"))
    }

    private fun lineAt(file: PsiFile, offset: Int): String = file.text.substring(file.text.lastIndexOf('\n', offset) + 1).substringBefore('\n').trim()

    // ---------------------------------------------------------------- editing

    private fun named(file: PsiFile, name: String): PsiElement =
        PsiTreeUtil.findChildrenOfType(file, PsiNameIdentifierOwner::class.java).first { it.name == name && it.nameIdentifier != null }

    fun `test name offset is the name leaf`() {
        val file = parses("""
            @deprecated sealed abstract class Shape[A](private val limit: Int) { @inline final def label(size: Int): String = "x" }
            case object Empty
            object Tools { lazy val cache = 1; type Id = Int; val (left, right) = (1, 2) }
            enum E { case One }
        """)
        val declarations = PsiTreeUtil.findChildrenOfType(file, ScalaNamed::class.java)
        assertEquals(listOf("Shape", "A", "limit", "label", "size", "Empty", "Tools", "cache", "Id", "left", "right", "E", "One"), declarations.map { it.name })
        declarations.forEach { assertEquals(it.name, file.text.indexOf(it.name.orEmpty(), it.textRange.startOffset), it.textOffset) }
    }

    fun `test rename local`() {
        myFixture.configureByText("a.scala", "def f() = {\n  val co<caret>unt = 0\n  println(s\"\$count \${count * 2}\")\n  count + 1\n}\n")
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("def f() = {\n  val total = 0\n  println(s\"\$total \${total * 2}\")\n  total + 1\n}\n")
    }

    fun `test rename parameter from a use`() {
        myFixture.configureByText("a.scala", "object A {\n  def greet(who: String) = println(wh<caret>o)\n  val who = 1\n  greet(who = \"x\")\n}\n")
        myFixture.renameElementAtCaret("name")
        myFixture.checkResult("object A {\n  def greet(name: String) = println(name)\n  val who = 1\n  greet(name = \"x\")\n}\n")
    }

    fun `test rename pattern variable`() {
        myFixture.configureByText("a.scala", "val r = x match {\n  case Some(va<caret>lue) if value > 0 => value\n  case value => 0\n}\n")
        myFixture.renameElementAtCaret("v")
        myFixture.checkResult("val r = x match {\n  case Some(v) if v > 0 => v\n  case value => 0\n}\n")
    }

    fun `test rename method across files`() {
        val main = myFixture.addFileToProject("Main.scala", "import com.acme.Deployer\nobject Main {\n  val d = new Deployer\n  d.deploy(\"prod\")\n  d deploy \"dev\"\n}\n")
        myFixture.configureByText("Deployer.scala", "package com.acme\nclass Deployer {\n  def dep<caret>loy(env: String) = env\n  def all() = { deploy(\"dev\"); this.deploy(\"qa\") }\n}\n")
        myFixture.renameElementAtCaret("release")
        myFixture.checkResult("package com.acme\nclass Deployer {\n  def release(env: String) = env\n  def all() = { release(\"dev\"); this.release(\"qa\") }\n}\n")
        assertEquals("import com.acme.Deployer\nobject Main {\n  val d = new Deployer\n  d.release(\"prod\")\n  d release \"dev\"\n}\n", main.text)
    }

    fun `test rename class updates imports and types`() {
        val main = myFixture.addFileToProject("Main.scala", "import com.acme.Deployer\nimport com.acme.{Deployer => D}\nobject Main {\n  val d: Deployer = new Deployer\n  val all: List[Deployer] = Nil\n}\n")
        myFixture.configureByText("Deployer.scala", "package com.acme\nclass Deplo<caret>yer { def self: Deployer = this }\n")
        myFixture.renameElementAtCaret("Releaser")
        myFixture.checkResult("package com.acme\nclass Releaser { def self: Releaser = this }\n")
        assertEquals("import com.acme.Releaser\nimport com.acme.{Releaser => D}\nobject Main {\n  val d: Releaser = new Releaser\n  val all: List[Releaser] = Nil\n}\n", main.text)
    }

    fun `test rename class renames its companion`() {
        val main = myFixture.addFileToProject("Main.scala", "import com.acme.Urls\nobject Main {\n  val a = Urls(\"dev\")\n  val b: Urls = new Urls(\"x\")\n  val c = Urls.default\n}\n")
        myFixture.configureByText("Urls.scala", "package com.acme\nclass Ur<caret>ls(env: String)\nobject Urls {\n  val default = \"dev\"\n  def apply(env: String): Urls = new Urls(env)\n}\n")
        myFixture.renameElementAtCaret("Hosts")
        myFixture.checkResult("package com.acme\nclass Hosts(env: String)\nobject Hosts {\n  val default = \"dev\"\n  def apply(env: String): Hosts = new Hosts(env)\n}\n")
        assertEquals("import com.acme.Hosts\nobject Main {\n  val a = Hosts(\"dev\")\n  val b: Hosts = new Hosts(\"x\")\n  val c = Hosts.default\n}\n", main.text)
    }

    fun `test rename apply keeps the calls through the object`() {
        myFixture.configureByText("Urls.scala", "class Urls(env: String)\nobject Urls {\n  def app<caret>ly(env: String): Urls = new Urls(env)\n  val a = Urls(\"dev\")\n  val b = Urls.apply(\"dev\")\n}\n")
        myFixture.renameElementAtCaret("of")
        myFixture.checkResult("class Urls(env: String)\nobject Urls {\n  def of(env: String): Urls = new Urls(env)\n  val a = Urls(\"dev\")\n  val b = Urls.of(\"dev\")\n}\n")
    }

    private fun usages(element: PsiElement): Int = ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).findAll().size

    fun `test find usages of a method`() {
        myFixture.addFileToProject("Main.scala", "import com.acme.Deployer\nobject Main {\n  val d = new Deployer\n  d.deploy(\"a\")\n  d.deploy(\"b\")\n  deploy(\"not this one\")\n}\n")
        val file = myFixture.addFileToProject("src/com/acme/Deployer.scala", "package com.acme\nclass Deployer {\n  def deploy(env: String) = env\n  def all() = deploy(\"c\")\n}\n")
        assertEquals(3, usages(named(file, "deploy")))
    }

    fun `test find usages of a class`() {
        myFixture.addFileToProject("Main.scala", "import com.acme.Deployer\nobject Main { val d: Deployer = new Deployer }\n")
        val file = myFixture.addFileToProject("src/com/acme/Deployer.scala", "package com.acme\nclass Deployer\n")
        assertEquals(3, usages(named(file, "Deployer")))
    }

    fun `test find usages of a val through an object`() {
        myFixture.addFileToProject("Main.scala", "import config.Settings\nimport config.Settings.users\nobject Main { val a = Settings.users + users }\n")
        val file = myFixture.addFileToProject("config/Settings.scala", "package config\nobject Settings { val users = 10 }\n")
        assertEquals(3, usages(named(file, "users")))
    }

    fun `test find usages of a local stay in its scope`() {
        val file = myFixture.configureByText("a.scala", "object A {\n  def f() = { val x = 1; x + x }\n  def g() = { val x = 2; x }\n}\n")
        val x = named(file, "x")
        assertEquals(2, ReferencesSearch.search(x).findAll().size)
        assertTrue(x.useScope is LocalSearchScope)
    }

    fun `test use scopes`() {
        val file = parses("""
            class A(plain: Int, val shown: Int) {
              private val secret = 1
              val open = 2
              private def hidden(arg: Int) = arg
              def visible(named: Int) = xs.map(item => item + named)
              def loop() = for (each <- xs) yield each match { case found => found }
            }
        """)
        val local = listOf("plain", "secret", "hidden", "arg", "item", "each", "found")
        val global = listOf("A", "shown", "open", "visible", "named", "loop")
        local.forEach { assertTrue(it, named(file, it).useScope is LocalSearchScope) }
        global.forEach { assertFalse(it, named(file, it).useScope is LocalSearchScope) }
    }

    fun `test highlighting has no errors on valid code`() {
        myFixture.configureByText("a.scala", "class A(n: Int) {\n  def m(p: Int) = List(1, 2).foreach { i => println(s\"\$i \${p + n}\") }\n}\nobject A { new A(1).m(1) }\n")
        assertEmpty(myFixture.doHighlighting().filter { it.severity.name == "ERROR" })
        myFixture.configureByText("b.scala", "enum Color:\n  case Red, Green\n\ngiven ord: Ordering[Color] with\n  def compare(a: Color, b: Color) =\n    if a == b then 0 else 1\n")
        assertEmpty(myFixture.doHighlighting().filter { it.severity.name == "ERROR" })
        assertTrue(myFixture.doHighlighting().any { it.forcedTextAttributesKey == ScalaColors.KEYWORD && it.text == "given" })
    }

    // ---------------------------------------------------------------- real files (read-only, optional)

    private fun isScala(f: File) = f.extension in setOf("scala", "sc", "sbt")
    private fun isSource(f: File) = f.isFile && f.length() < 1_000_000 && (isScala(f) || f.name in MANIFESTS)

    private fun isName(reference: PsiPolyVariantReference): Boolean = (reference.element as? ScalaOpRef)?.isSymbolic != true
    private fun inImport(reference: PsiPolyVariantReference): Boolean = PsiTreeUtil.getParentOfType(reference.element, ScalaImportExpr::class.java) != null
    private fun nameOf(reference: PsiPolyVariantReference): String = reference.rangeInElement.substring(reference.element.text)

    /** LITE_SCALA_CORPUS=<dir:dir>: every Scala file lexes and parses; prints the share of names resolved, per directory. */
    fun `test corpus`() {
        val dirs = System.getenv("LITE_SCALA_CORPUS")?.split(':')?.map(::File) ?: return
        val overRate = dirs.mapIndexed { i, dir -> corpus(dir, "corpus$i") }.count { it }
        assertEquals("directories over the tolerated error rate", 0, overRate)
    }

    /** Copies [dir] into the test project (the index only knows project files), then tells whether it is over the tolerated error rate. */
    private fun corpus(dir: File, folder: String): Boolean {
        val show = System.getenv("LITE_SHOW") != null
        val sources = dir.walkTopDown().onEnter { it.name !in IGNORED_DIRS }.filter(::isSource).toList()
        val copies = sources.map { myFixture.addFileToProject("$folder/${it.relativeTo(dir).path}", it.readText()) }.filterIsInstance<ScalaFile>()
        val started = System.nanoTime()
        val reports = copies.map { psi ->
            val unknown = tokens(psi.text).firstOrNull { it.first == "BAD_CHARACTER" }?.let { "unknown character '${it.second}'" }
            val references = PsiTreeUtil.findChildrenOfType(psi, ScalaElement::class.java).flatMap { it.references.toList() }
                .filterIsInstance<PsiPolyVariantReference>().filter(::isName)
            val missed = references.filter { it.multiResolve(false).isEmpty() }
            Report((firstError(psi) ?: unknown)?.let { "${psi.virtualFile.path}: $it" }, references.size, missed, references.filter(::inImport))
        }
        val broken = reports.mapNotNull { it.first }
        val total = reports.sumOf { it.second }
        val missed = reports.flatMap { it.third }
        val paths = reports.flatMap { it.fourth }
        val frequent = missed.groupingBy(::nameOf).eachCount().entries.sortedByDescending { it.value }.take(40).joinToString(" ") { "${it.key}×${it.value}" }
        val sample = missed.shuffled(java.util.Random(1)).take(80).map { "${it.element.containingFile.name}: ${nameOf(it)} in ${it.element.text.replace(Regex("\\s+"), " ").take(60)}" }
        val shown = (broken.take(60) + sample).takeIf { show }.orEmpty()
        println(
            "scala corpus $dir in ${(System.nanoTime() - started) / 1_000_000} ms: ${reports.size - broken.size}/${reports.size} files without errors, " +
                "names ${total - missed.size}/$total resolved (project sources only: no JDK, no Scala library, no jars), " +
                "outside imports ${total - paths.size - missed.count { it !in paths }}/${total - paths.size}\n" +
                "most missed: $frequent\n" + shown.joinToString("\n"),
        )
        return broken.size > reports.size * TOLERATED_ERROR_RATE
    }

    private data class Report(val first: String?, val second: Int, val third: List<PsiPolyVariantReference>, val fourth: List<PsiPolyVariantReference>)

    private companion object {
        const val TOLERATED_ERROR_RATE = 0.02
        val IGNORED_DIRS = setOf(".bloop", ".git", ".metals", "node_modules", "target")
        val MANIFESTS = setOf("build.gradle", "build.gradle.kts", "pom.xml", "settings.gradle", "settings.gradle.kts")
    }
}
