package ammonite.interpreter.bridge

import java.io.File

import scala.reflect.runtime.universe._

trait ReplAPI {
  /**
   * Exit the Ammonite REPL. You can also use Ctrl-D to exit
   */
  def exit: Nothing

  /**
   * Clears the screen of the REPL
   */
  def clear: Unit

  /**
   * Read/writable prompt for the shell. Use this to change the
   * REPL prompt at any time!
   */
  var shellPrompt: String

  /**
   * Display this help text
   */
  def help: String

  /**
   * History of commands that have been entered into the shell
   */
  def history: Seq[String]

  /**
   * Get the `Type` object of [[T]]. Useful for finding
   * what its methods are and what you can do with it
   */
  def typeOf[T: WeakTypeTag]: Type

  /**
   * Get the `Type` object representing the type of `t`. Useful
   * for finding what its methods are and what you can do with it
   *
   */
  def typeOf[T: WeakTypeTag](t: => T): Type

  /**
   * Tools related to loading external scripts and code into the REPL
   */
  def load: Load

  def power: Power

  /**
   * Throw away the current scala.tools.nsc.Global and get a new one
   */
  def newCompiler(): Unit

  /**
   *
   */
  def imports: String
  /**
   * Controls how things are pretty-printed in the REPL. Feel free
   * to shadow this with your own definition to change how things look
   */
  implicit def pprintConfig: ammonite.pprint.Config
}

trait Load extends (String => Unit){
  /**
   * Load a `.jar` file
   */
  def jar(jar: java.io.File): Unit
  /**
   * Load a library from its maven/ivy coordinates
   */
  def ivy(coordinates: (String, String, String)): Unit

  /**
   * Loads a command into the REPL and
   * evaluates them one after another
   */
  def apply(line: String): Unit
}

trait Power {
  /**
   *
   */
  def jars: Seq[File]

  /**
   *
   */
  def classes: Map[String, Array[Byte]]
}

class ReplAPIHolder {
  var shell0: FullReplAPI = null
  lazy val shell = shell0
}

object ReplAPI{
  def initReplBridge(holder: Class[ReplAPIHolder], api: ReplAPI) = {
    val method = holder
      .getDeclaredMethods
      .find(_.getName == "shell0_$eq")
      .get
    method.invoke(null, api)
  }
}

/**
 * Things that are part of the ReplAPI that aren't really "public"
 */
abstract class FullReplAPI extends ReplAPI{
  def shellPPrint[T: WeakTypeTag](value: => T, ident: String): String
  def shellPrintDef(definitionLabel: String, ident: String): String
  def shellPrintImport(imported: String): String
  def typeOf[T: WeakTypeTag] = scala.reflect.runtime.universe.weakTypeOf[T]
  def typeOf[T: WeakTypeTag](t: => T) = scala.reflect.runtime.universe.weakTypeOf[T]
}