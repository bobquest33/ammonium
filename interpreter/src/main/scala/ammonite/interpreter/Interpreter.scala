package ammonite.interpreter

import java.lang.reflect.InvocationTargetException

import acyclic.file

import scala.collection.mutable
import scala.reflect.io.VirtualDirectory
import scala.util.Try
import scala.util.control.ControlThrowable

import ammonite.interpreter.api.{ DisplayItem, Decl, BridgeConfig, ImportData }


object Wrap {
  val default = apply(_.map {
    case DisplayItem.Definition(label, name) => s"""println("defined $label $name")"""
    case DisplayItem.Import(imported) => s"""println("import $imported")"""
    case DisplayItem.Identity(ident) => s"""println("$ident = " + $$user.$ident)"""
    case DisplayItem.LazyIdentity(ident) => s"""println("$ident = <lazy>")"""
  } .mkString(" ; "))

  def apply(displayCode: Seq[DisplayItem] => String, classWrap: Boolean = false) = {
    (decls: Seq[Decl], previousImportBlock: String, wrapperName: String) =>
      val code = decls.map(_.code) mkString " ; "
      val mainCode = displayCode(decls.flatMap(_.display))

      if (classWrap)
        s"""
          object $wrapperName$$Main {
            $previousImportBlock // FIXME Only import implicits here

            def $$main() = {val $$user = $wrapperName.INSTANCE.$$user; $mainCode}
          }


          object $wrapperName {
            val INSTANCE = new $wrapperName
          }

          class $wrapperName extends Serializable {
            $previousImportBlock // FIXME Only import necessary imports here (implicits ones + the ones referenced in code)

            class $$user extends Serializable {
              $code
            }

            val $$user = new $$user
          }
       """
      else
        s"""$previousImportBlock

            object $wrapperName$$Main {
              def $$main() = {val $$user = $wrapperName; $mainCode}
            }

            object $wrapperName {
              $code
            }
         """
  }
}


/**
 * Thrown to exit the interpreter cleanly
 */
case object Exit extends ControlThrowable

/**
 * A convenient bundle of all the functionality necessary
 * to interpret Scala code.
 */

trait InterpreterInternals {

  def apply[T](
                line: String,
                saveHistory: (String => Unit, String) => Unit = _(_),
                printer: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]
                ): Res[Evaluated[T]]

  // def evalClass(code: String, wrapperName: String) // return type???
  def process[T](input: Seq[Decl], process: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]): Res[Evaluated[T]]
  def handleOutput(res: Res[Evaluated[_]]): Boolean

}

class Interpreter(
  val bridgeConfig: BridgeConfig = BridgeConfig.empty,
  val wrapper: (Seq[Decl], String, String) => String = Wrap.default,
  val imports: api.Imports = new Imports(),
  val classes: api.Classes = new Classes(),
  startingLine: Int = 0,
  initialHistory: Seq[String] = Nil
) extends api.Interpreter with InterpreterInternals {

  imports.update(bridgeConfig.imports)

  val dynamicClasspath = new VirtualDirectory("(memory)", None)

  val history = initialHistory.to[collection.mutable.Buffer]
  var buffered = ""


  /**
   * The current line number of the REPL, used to make sure every snippet
   * evaluated can have a distinct name that doesn't collide.
   */
  var currentLine = startingLine

  def getCurrentLine = currentLine.toString.replace("-", "_")


  def complete(snippetIndex: Int, snippet: String, previousImports: String = null) = {
    pressy.complete(snippetIndex, Option(previousImports) getOrElse imports.previousImportBlock(), snippet)
  }

  def decls(code: String) = {
    Preprocessor(compiler.parse, code, getCurrentLine) match {
      case Res.Success(l) =>
        Right(l)
      case Res.Buffer(s) =>
        throw new IllegalArgumentException(s"Incomplete statement: $s")
      case Res.Exit =>
        throw new Exception("Can't happen")
      case Res.Skip =>
        Right(Nil)
      case Res.Failure(err) =>
        Left(err)
    }
  }

  def compile(src: Array[Byte], runLogger: String => Unit) = {
    compiler.compile(src, runLogger)
  }

  def run(code: String) = {
    apply(code, (_, _) => (), bridgeConfig.defaultPrinter) match {
      case Res.Success(ev) =>
        imports.update(ev.imports)
        Right(())
      case Res.Buffer(s) =>
        throw new IllegalArgumentException(s"Incomplete statement: $s")
      case Res.Exit =>
        throw Exit
      case Res.Skip =>
        Right(())
      case Res.Failure(err) =>
        Left(err)
    }
  }

  def apply[T](
    line: String,
    saveHistory: (String => Unit, String) => Unit = _(_),
    printer: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]
  ) =
    for{
      _ <- Catching { case Ex(x@_*) =>
        val Res.Failure(trace) = Res.Failure(x)
        Res.Failure(trace + "\nSomething unexpected went wrong =(")
      }
      p <- Preprocessor(compiler.parse, line, getCurrentLine)
      _ = saveHistory(history.append(_), line)
      out <- process(p, printer)
    } yield out

  def evalClass(code: String, wrapperName: String) = for {
    (output, compiled) <- Res.Success {
      val output = mutable.Buffer.empty[String]
      val c = compiler.compile(code.getBytes, output.append(_))
      (output, c)
    }

    (classFiles, importData) <- Res[(Traversable[(String, Array[Byte])], Seq[ImportData])](
      compiled, "Compilation Failed\n" + output.mkString("\n")
    )

    cls <- Res[Class[_]](Try {
      for ((name, bytes) <- classFiles) classes.addClass(name, bytes)
      Class.forName(wrapperName, true, classes.currentClassLoader)
    }, e => "Failed to load compiled class " + e)
  } yield (cls, importData)

  def interrupted() = {
    Thread.interrupted()
    Res.Failure("\nInterrupted!")
  }

  type InvEx = InvocationTargetException
  type InitEx = ExceptionInInitializerError

  /**
   * Takes the preprocessed `code` and `printCode` and compiles/evals/runs/etc.
   * it to provide a result. Takes `printer` as a callback, instead of returning
   * the `Iterator` as part of the output, because printing can cause side effects
   * (e.g. for Streams which are lazily printed) and can fail with an exception!
   * passing in the callback ensures the printing is still done lazily, but within
   * the exception-handling block of the `Evaluator`
   */
  def process[T](input: Seq[Decl], process: AnyRef => T = (x: AnyRef) => x.asInstanceOf[T]): Res[Evaluated[T]] = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try { Thread.currentThread().setContextClassLoader(classes.currentClassLoader)

      for {
        wrapperName <- Res.Success("cmd" + getCurrentLine)
        _ <- Catching{ case e: ThreadDeath => interrupted() }
        wrappedLine = wrapper(input, imports.previousImportBlock(input.flatMap(_.referencedNames).toSet), wrapperName)
        (cls, newImports) <- evalClass(wrappedLine, wrapperName + "$Main")
        _ = currentLine += 1
        _ <- Catching{
          case Ex(_: InitEx, Exit)                => Res.Exit
          case Ex(_: InvEx, _: InitEx, Exit)      => Res.Exit
          case Ex(_: ThreadDeath)                 => interrupted()
          case Ex(_: InvEx, _: ThreadDeath)       => interrupted()
          case Ex(_: InvEx, _: InitEx, userEx@_*) => Res.Failure(userEx, stopMethod = "$main", stopClass = s"$wrapperName$$$$user")
          case Ex(userEx@_*)                      => Res.Failure(userEx, stopMethod = "evaluatorRunPrinter")
        }
      } yield {
        // Exhaust the printer iterator now, before exiting the `Catching`
        // block, so any exceptions thrown get properly caught and handled
        val value = evaluatorRunPrinter(process(cls.getDeclaredMethod("$main").invoke(null)))
        Evaluated(
          wrapperName,
          newImports.map(id => id.copy(
            wrapperName = wrapperName,
            prefix = if (id.prefix == "") wrapperName else id.prefix
          )),
          value
        )
      }

    } finally Thread.currentThread().setContextClassLoader(oldClassloader)
  }

  /**
   * Dummy function used to mark this method call in the stack trace,
   * so we can easily cut out the irrelevant part of the trace when
   * showing it to the user.
   */
  def evaluatorRunPrinter[T](f: => T): T = f


  def handleOutput(res: Res[Evaluated[_]]) = {
    res match{
      case Res.Skip =>
        buffered = ""
        true
      case Res.Buffer(line) =>
        /**
         * Hack to work around the fact that if nothing got entered into
         * the prompt, the `ConsoleReader`'s history wouldn't increase
         */
        buffered = line + "\n"
        true
      case Res.Exit =>
        pressy.shutdownPressy()
        false
      case Res.Success(ev) =>
        buffered = ""
        imports.update(ev.imports)
        true
      case Res.Failure(msg) =>
        buffered = ""
        true
    }
  }

  var compiler: Compiler = _
  var pressy: Pressy = _
  def init() = {
    compiler = Compiler(
      Classes.bootStartJars ++ classes.jars,
      Classes.bootStartDirs ++ classes.dirs,
      dynamicClasspath,
      classes.currentClassLoader,
      () => pressy.shutdownPressy()
    )
    pressy = Pressy(
      Classes.bootStartJars ++ classes.jars,
      Classes.bootStartDirs ++ classes.dirs,
      dynamicClasspath,
      classes.currentClassLoader
    )

    // initializing the compiler so that it does not complain having no phase
    compiler.compile("object $dummy".getBytes, _ => ())
  }

  def stop() = {
    onStopHooks.foreach(_())
  }

  var onStopHooks = Seq.empty[() => Unit]
  def onStop(action: => Unit) = onStopHooks = onStopHooks :+ { () => action }

  init()

  bridgeConfig.initClass(this,
    evalClass(bridgeConfig.init, bridgeConfig.name).map(_._1) match {
      case Res.Success(s) => s
      case other => throw new Exception(s"Error while initializing REPL API: $other")
    }
  )
}

