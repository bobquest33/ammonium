package ammonite.shell
package tests

import ammonite.interpreter.Res
import utest._

class AdvancedTests(check0: => Checker,
                    isAmmonite: Boolean = true,
                    hasMacros: Boolean = !scala.util.Properties.versionNumberString.startsWith("2.10."),
                    wrapperInstance: (Int, Int) => String = (ref, cur) => s"cmd$ref.$$user") extends TestSuite{

  val tests = TestSuite{
    println("AdvancedTests")
    val check = check0
    'load{
      'ivy{
        'standalone{
          val tq = "\"\"\""
          check.session(s"""
            @ import scalatags.Text.all._
            error: not found: value scalatags

            @ load.ivy("com.lihaoyi" %% "scalatags" % "0.4.5")

            @ import scalatags.Text.all._
            import scalatags.Text.all._

            @ a("omg", href:="www.google.com").render
            res2: String = $tq
            <a href="www.google.com">omg</a>
            $tq
          """)
        }
        'dependent{
          // Make sure it automatically picks up jawn-parser since upickle depends on it,
          check.session("""
            @ load.ivy("com.lihaoyi" %% "upickle" % "0.2.6")

            @ import upickle._
            import upickle._

            @ upickle.write(Seq(1, 2, 3))
            res2: String = "[1,2,3]"
          """)
        }

        // Doing things a bit differently than @lihaoyi here.
        // His way of doing would crash at the second res2 below, mine would crash at res4_1.
        // The main advantage of mine is that previous variables don't need to be recalculated
        // when dependencies are added.

        // 'reloading{
        //   // Make sure earlier-loaded things indeed continue working
        //   check.session("""
        //     @ load.ivy("com.lihaoyi" %%"scalarx" % "0.2.7")
        //
        //     @ load.ivy("com.scalatags" %% "scalatags" % "0.2.5")
        //
        //     @ scalatags.all.div("omg").toString
        //     res2: String = "<div>omg</div>"
        //
        //     @ load.ivy("com.lihaoyi" %% "scalatags" % "0.4.5")
        //
        //     @ import scalatags.Text.all._; scalatags.Text.all.div("omg").toString
        //     import scalatags.Text.all._
        //     res4_1: String = "<div>omg</div>"
        //
        //     @ res2 // BOOM
        //
        //     @ import rx._; val x = Var(1); val y = Rx(x() + 1)
        //
        //     @ x(); y()
        //     res6_0: Int = 1
        //     res6_1: Int = 2
        //
        //     @ x() = 2
        //
        //     @ x(); y()
        //     res8_0: Int = 2
        //     res8_1: Int = 3
        //   """)
        // }
        'complex{
          check.session("""
            @ load.ivy("com.typesafe.akka" %% "akka-http-experimental" % "1.0-M3")

            @ implicit val system = akka.actor.ActorSystem()

            @ val serverBinding = akka.http.Http(system).bind(interface = "localhost", port = 31337)

            @ implicit val materializer = akka.stream.ActorFlowMaterializer()

            @ var set = false

            @ serverBinding.connections.runForeach { connection =>
            @   set = true
            @ }

            @ set
            res6: Boolean = false

            @ akka.stream.scaladsl.Source(
            @   List(akka.http.model.HttpRequest(uri="/"))
            @ ).via(
            @   akka.http.Http().outgoingConnection("localhost", port=31337).flow
            @ ).runForeach(println)

            @ Thread.sleep(200)

            @ set
            res9: Boolean = true

            @ system.shutdown()
          """)
        }
      }
      'code{
        check.session("""
          @ load("val x = 1")

          @ x
          res2: Int = 1
        """)
      }
    }
    'pprint{
      check.session(s"""
        @ Seq.fill(10)(Seq.fill(3)("Foo"))
        res0: Seq[Seq[String]] = List(
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo"),
          List("Foo", "Foo", "Foo")
        )

        @ case class Foo(i: Int, s0: String, s1: Seq[String])
        defined class Foo

        @ Foo(1, "", Nil)
        res2: Foo = Foo(1, "", List())

        @ Foo(1234567, "I am a cow, hear me moo", Seq("I weigh twice as much as you", "and I look good on the barbecue"))
        res3: Foo = Foo(
          1234567,
          "I am a cow, hear me moo",
          List("I weigh twice as much as you", "and I look good on the barbecue")
        )
      """)
    }
    'exit{
      if (isAmmonite)
        check.result("exit", Res.Exit)
    }
    'skip{
      check("1", "res0: Int = 1")
      check.result("", Res.Skip)
      check("2", "res1: Int = 2")
    }
    'history{
      check.session("""
        @ val x = 1

        @ x

        @ history
        res2: Seq[String] = Vector("val x = 1", "x")
      """)
    }
    'customPPrint{
      check.session(s"""
        @ class C
        defined class C

        @ implicit def pprint = ammonite.pprint.PPrinter[C]((t, c) => Iterator("INSTANCE OF CLASS C"))
        defined function pprint

        @ new C
        res2: C = INSTANCE OF CLASS C
      """)
    }

    'shapeless{
      check.session("""
        @ load.ivy("com.chuusai" %% "shapeless" % "2.2.0-RC6"); if (scala.util.Properties.versionNumberString.startsWith("2.10.")) load.compiler.ivy("org.scalamacros" % "paradise_2.10.5" % "2.0.1")

        @ import shapeless._

        @ (1 :: "lol" :: List(1, 2, 3) :: HNil)
        res2: Int :: String :: List[Int] :: HNil = ::(1, ::("lol", ::(List(1, 2, 3), HNil)))

        @ res2(1)
        res3: String = "lol"

        @ case class Foo(i: Int, blah: String, b: Boolean)
        defined class Foo

        @ Generic[Foo].to(Foo(2, "a", true))
        res5: Int :: String :: Boolean :: HNil = ::(2, ::("a", ::(true, HNil)))
      """)
    }

    'scalaz{
      check.session("""
        @ load.ivy("org.scalaz" %% "scalaz-core" % "7.1.1")

        @ import scalaz._
        import scalaz._

        @ import Scalaz._
        import Scalaz._

        @ (Option(1) |@| Option(2))(_ + _)
        res3: Option[Int] = Some(3)
      """)
    }
    'scalazstream{
      check.session("""
        @ load.resolver("Scalaz Bintray Repo" at "https://dl.bintray.com/scalaz/releases")

        @ load.ivy("org.scalaz.stream" %% "scalaz-stream" % "0.7a")

        @ import scalaz.stream._
        import scalaz.stream._

        @ import scalaz.concurrent.Task
        import scalaz.concurrent.Task

        @ val p1 = Process.constant(1).toSource
        p1: scalaz.stream.Process[scalaz.concurrent.Task,Int] = Append(Emit(Vector(1)),Vector(<function1>))

        @ val pch = Process.constant((i:Int) => Task.now(())).take(3)
        pch: scalaz.stream.Process[Nothing,Int => scalaz.concurrent.Task[Unit]] = Append(Halt(End),Vector(<function1>))

        @ p1.to(pch).runLog.run.size == 3
        res6: Boolean = true
      """)
    }
    'scalaparse{
      // Prevent regressions when wildcard-importing things called `macro` or `_`
      check.session("""
        @ load.ivy("com.github.alexarchambault.tmp" %% "scalaparse" % "0.1.6-SNAPSHOT")

        @ import scalaparse.Scala._

        @ 1
        res2: Int = 1

        @ ExprCtx.Parened.parse("1 + 1")
        res3: fastparse.core.Result[Unit] = Failure(Parened:0 / "(":0 / "(":0 ..."1 + 1", false)

        @ ExprCtx.Parened.parse("(1 + 1)")
        res4: fastparse.core.Result[Unit] = Success((), 7)
      """)
    }
    'predef{
      if (isAmmonite) {
        val check2 = new AmmoniteChecker{
          override def predef = """
            import math.abs
            val x = 1
            val y = "2"
          """
        }
        check2.session("""
          @ -x
          res0: Int = -1

          @ y
          res1: String = "2"

          @ x + y
          res2: String = "12"

          @ abs(-x)
          res3: Int = 1
        """)
      }

    }
    'macros{
      if (hasMacros)
        check.session("""
          @ import language.experimental.macros

          @ import reflect.macros.Context

          @ def impl(c: Context): c.Expr[String] = {
          @  import c.universe._
          @  c.Expr[String](Literal(Constant("Hello!")))
          @ }
          defined function impl

          @ def m: String = macro impl
          defined function m

          @ m
          res4: String = "Hello!"
        """)
    }
    'typeScope{
      check.session("""
        @ collection.mutable.Buffer(1)
        res0: collection.mutable.Buffer[Int] = ArrayBuffer(1)

        @ import collection.mutable

        @ collection.mutable.Buffer(1)
        res2: mutable.Buffer[Int] = ArrayBuffer(1)

        @ mutable.Buffer(1)
        res3: mutable.Buffer[Int] = ArrayBuffer(1)

        @ import collection.mutable.Buffer

        @ mutable.Buffer(1)
        res5: Buffer[Int] = ArrayBuffer(1)
      """)
    }
    'customTypePrinter{
      check.session("""
        @ Array(1)
        res0: Array[Int] = Array(1)

        @ import ammonite.pprint.TPrint

        @ implicit def ArrayTPrint[T: TPrint]: TPrint[Array[T]] = TPrint.lambda(
        @   c => implicitly[TPrint[T]].render(c) + c.color.literal(" Array")
        @ )

        @ Array(1)
        res3: Int Array = Array(1)
      """)
    }
    'truncation{
      check.session("""
      @ Seq.fill(20)(100)
      res0: Seq[Int] = List(
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
      ...

      @ show(Seq.fill(20)(100))
      res1: ammonite.pprint.Show[Seq[Int]] = List(
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100,
        100
      )

      @ show(Seq.fill(20)(100), lines = 3)
      res2: ammonite.pprint.Show[Seq[Int]] = List(
        100,
        100,
      ...

      @ pprintConfig = pprintConfig.copy(lines = 5)

      @ Seq.fill(20)(100)
      res4: Seq[Int] = List(
        100,
        100,
        100,
        100,
      ...
      """)
    }
  }
}
