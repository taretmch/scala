package scala.tools.nsc.interpreter

import java.io.{PrintWriter, StringWriter}

import org.junit.Assert.assertEquals
import org.junit.Test

import scala.reflect.internal.util.{BatchSourceFile, SourceFile}
import scala.tools.nsc.Settings
import scala.tools.nsc.interpreter.shell._

class CompletionTest {
  val EmptyString = "" // def string results include the empty string so that JLine won't insert "def ..." at the cursor

  def newIMain(classBased: Boolean = false): IMain = {
    val settings = new Settings()
    settings.Xjline.value = "off"
    settings.usejavacp.value = true
    settings.Yreplclassbased.value = classBased

    new IMain(settings, new ReplReporterImpl(settings, new PrintWriter(new StringWriter)))
  }

  private def setup(sources: SourceFile*): Completion = {
    val intp = newIMain()
    intp.compileSources(sources: _*)
    val completer = new ReplCompletion(intp)
    completer
  }

  private def interpretLines(lines: String*): (Completion, Repl, Accumulator) = {
    val intp = newIMain()
    lines.foreach(intp.interpret)
    val acc = new Accumulator
    val completer = new ReplCompletion(intp, acc)
    (completer, intp, acc)
  }

  implicit class BeforeAfterCompletion(completion: Completion) {
    def complete(before: String, after: String = ""): CompletionResult =
      completion.complete(before + after, before.length)
  }


  @Test
  def t4438_arrayCompletion(): Unit = {
    val completer = setup()
    checkExact(completer, "Array(1, 2, 3) rev")("reverse", "reverseIterator", "reverseMap")
  }

  @Test
  def classBased(): Unit = {
    val intp = newIMain()
    val completer = new ReplCompletion(intp)
    checkExact(completer, "object O { def x_y_z = 1 }; import O._; x_y")("x_y_z")
  }

  @Test
  def completions(): Unit = {
    testCompletions(classBased = false)
  }

  @Test
  def completionsReplClassBased(): Unit = {
    testCompletions(classBased = true)
  }

  private def testCompletions(classBased: Boolean): Unit = {
    val intp = newIMain(classBased)
    val completer = new ReplCompletion(intp)

    checkExact(completer, "object O { def x_y_z = 1 }; import O._; x_y")("x_y_z")
    checkExact(completer, "object O { private def x_y_z = 1 }; import O._; x_y")()
    checkExact(completer, "object O { private def x_y_z = 1; x_y", "}")("x_y_z")
    checkExact(completer, "object x_y_z; import x_y")("x_y_z")

    checkExact(completer, "object x_y_z { def a_b_c }; import x_y_z.a_b")("a_b_c")

    checkExact(completer, "object X { private[this] def definition = 0; def")("definition")

    // stable terms are offered in type completion as they might be used as a prefix
    checkExact(completer, """object O { def x_y_z = 0; val x_z_y = ""; type T = x_""")("x_z_y")
    checkExact(completer, """def method { def x_y_z = 0; val x_z_y = ""; type T = x_""")("x_z_y")

    checkExact(completer, "asInstanceO", includeUniversal = false)()
    checkExact(completer, "asInstanceO", "", includeUniversal = true)("asInstanceOf")

    // Output is sorted
    assertEquals(List("prefix_aaa", "prefix_nnn", "prefix_zzz"), completer.complete( """class C { def prefix_nnn = 0; def prefix_zzz = 0; def prefix_aaa = 0; prefix_""").candidates.filter(!_.isUniversal).map(_.defString))

    // Enable implicits to check completion enrichment
    checkExact(completer, """'c'.toU""")("toUpper")
    checkExact(completer, """val c = 'c'; c.toU""")("toUpper")

    intp.interpret("object O { def x_y_x = 1; def x_y_z = 2; def getFooBarZot = 3}; ")
    checkExact(new ReplCompletion(intp), """object O2 { val x = O.""")("x_y_x", "x_y_z", "getFooBarZot")
  }

  @Test
  def annotations(): Unit = {
    val completer = setup()
    checkExact(completer, "def foo[@specialize", " A]")("specialized")
    checkExact(completer, "def foo[@specialize")("specialized")
//    TODO: re-enable once scala/bug#11060 is fixed
//    checkExact(completer, """@deprecatedN""", """ class Foo""")("deprecatedName")
//    checkExact(completer, """@deprecateN""")("deprecatedName")
//    checkExact(completer, """{@deprecateN""")("deprecatedName")
  }

  @Test
  def incompleteStringInterpolation(): Unit = {
    val completer = setup()
    checkExact(completer, """val x_y_z = 1; s"${x_""", "}\"")("x_y_z")
    checkExact(completer, """val x_y_z = 1; s"${x_""", "\"")("x_y_z")
  }

  @Test
  def symbolically(): Unit = {
    val completer = setup()
    checkExact(completer, """class C { def +++(a: Any) = 0; def ---(a: Any) = 0; this.++""")("+++")
  }

  @Test
  def previousLineCompletions(): Unit = {
    val (completer, intp, _) = interpretLines(
      "class C { val x_y_z = 42 }",
      "object O { type T = Int }")

    checkExact(completer, "new C().x_y")("x_y_z")
    checkExact(completer, "(1 : O.T).toCha")("toChar")

    intp.interpret("case class X_y_z()")
    val completer1 = new ReplCompletion(intp)
    checkExact(completer1, "new X_y_")("X_y_z")
    checkExact(completer1, "X_y_")("X_y_z")
    checkExact(completer1, "X_y_z.app")("apply")
  }

  @Test
  def previousResultInvocation(): Unit = {
    val (completer, _, _) = interpretLines("1 + 1")

    checkExact(completer, ".toCha")("toChar")
  }

  @Test
  def multiLineInvocation(): Unit = {
    val (completer, _, accumulator) = interpretLines()
    accumulator += "class C {"
    checkExact(completer, "1 + 1.toCha")("toChar")
  }

  @Test
  def defStringConstructor(): Unit = {
    val intp = newIMain()
    val completer = new ReplCompletion(intp)
    checkExact(completer, "class Shazam(i: Int); new Shaza")("Shazam")
    checkExact(completer, "class Shazam(i: Int); new Shazam")(EmptyString, "def <init>(i: Int): Shazam")

    checkExact(completer, "class Shazam(i: Int) { def this(x: String) = this(0) }; new Shaza")("Shazam")
    checkExact(completer, "class Shazam(i: Int) { def this(x: String) = this(0) }; new Shazam")(EmptyString, "def <init>(i: Int): Shazam", "def <init>(x: String): Shazam")
  }

  @Test
  def treePrint(): Unit = {
    val completer = setup()
    checkExact(completer, " 1.toHexString //print")(EmptyString, "scala.Predef.intWrapper(1).toHexString // : String")
  }

  @Test
  def replGeneratedCodeDeepPackages(): Unit = {
    val completer = setup(new BatchSourceFile("<paste>", "package p1.p2.p3; object Ping { object Pong }"))
    checkExact(completer, "p1.p2.p")("p3")
    checkExact(completer, "p1.p2.p3.P")("Ping")
    checkExact(completer, "p1.p2.p3.Ping.Po")("Pong")
  }

  @Test
  def constructor(): Unit = {
    val intp = newIMain()
    val completer = new ReplCompletion(intp)
    checkExact(completer, "class Shazam{}; new Shaz")("Shazam")

    intp.interpret("class Shazam {}")
    checkExact(completer, "new Shaz")("Shazam")
  }

  @Test
  def completionWithComment(): Unit = {
    val completer = setup()

    val withMultilineCommit =
      """|Array(1, 2, 3)
         |  .map(_ + 1) /* then we do reverse */
         |  .rev""".stripMargin
    assert(
      completer.complete(withMultilineCommit).candidates.map(_.defString).contains("reverseMap")
    )

    val withInlineCommit =
      """|Array(1, 2, 3)
         |  .map(_ + 1) // then we do reverse
         |  .rev""".stripMargin
    assert(
      completer.complete(withInlineCommit).candidates.map(_.defString).contains("reverseMap")
    )
  }

  @Test
  def dependentTypeImplicits_t10353(): Unit = {
    val code =
      """
package test

// tests for autocomplete on repl

object Test {
  trait Conv[In] {
    type Out
    def apply(in: In): Out
  }
  object Conv {
    type Aux[In, Out0] = Conv[In] { type Out = Out0 }
    implicit val int2String = new Conv[Int] {
      type Out = String
      override def apply(i: Int) = i.toString
    }
  }

  // autocomplete works on repl: `test.Test.withParens().<TAB>` shows completions for String
  def withParens[Out]()(implicit conv: Conv.Aux[Int, Out]): Out = "5".asInstanceOf[Out]

  // autocomplete doesn't work on repl: `test.Test.withoutParens.` doesn't suggest anything
  // when saving intermediate result it works though: `val a = test.Test.withoutParens; a.<TAB>`
  def withoutParens[Out](implicit conv: Conv.Aux[Int, Out]): Out = "5".asInstanceOf[Out]
}

// this works fine
object Test2 {
  trait A
  implicit val a: A = ???
  def withParens()(implicit a: A): String = "something"
  def withoutParens(implicit a: A): String = "something"
}
"""
    val completer = setup(new BatchSourceFile("<paste>", code))
    checkExact(completer, "val x = test.Test.withoutParens; x.charA")("charAt")
    checkExact(completer, "test.Test.withoutParens.charA")("charAt")
  }

  def checkExact(completer: Completion, before: String, after: String = "", includeUniversal: Boolean = false)(expected: String*): Unit = {
    val actual =
      completer.complete(before, after).candidates
        .filter(c => includeUniversal || !c.isUniversal)
        .map(_.defString)
    assertEquals(expected.sorted.mkString(" "), actual.toSeq.distinct.sorted.mkString(" "))
  }
}
