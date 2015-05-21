package ammonite.interpreter

import java.io.{ ByteArrayOutputStream, InputStream }

import acyclic.file

import scala.util.Try
import ammonite.api.ImportData

object Res{
  def apply[T](o: Option[T], errMsg: => String) = o match{
    case Some(s) => Success(s)
    case None => Failure(errMsg)
  }
  def apply[T](o: Try[T], errMsg: Throwable => String) = o match{
    case util.Success(s) => Success(s)
    case util.Failure(t) => Failure(errMsg(t))
  }


  /**
   * Successes map and flatmap just like a simple Box[T]
   */
  case class Success[+T](s: T) extends Res[T] {
    def flatMap[V](f: T => Res[V]): Res[V] = f(s)
    def map[V](f: T => V): Res[V] = Success(f(s))
  }

  /**
   * Failing results never call their callbacks, and just remain unchanged
   */
  sealed abstract class Failing extends Res[Nothing]{
    def flatMap[V](f: Nothing => Res[V]): Res[V] = this
    def map[V](f: Nothing => V): Res[V] = this
  }
  case class Failure(s: String) extends Failing
  object Failure{
    def apply(exceptions: Seq[Throwable], stopMethod: String = null, stopClass: String = null): Failure = {
      val traces = exceptions.map(exception =>
        exception.toString + "\n" +
        exception
          .getStackTrace
          .takeWhile(x => x.getMethodName != stopMethod && x.getClassName != stopClass)
          .map("\t" + _)
          .mkString("\n")
      )
      Res.Failure(traces.mkString("\n"))
    }
  }
  case object Skip extends Failing
  case object Exit extends Failing
  case class Buffer(s: String) extends Failing
}

/**
 * The result of a single pass through the ammonite REPL.
 */
sealed abstract class Res[+T]{
  def flatMap[V](f: T => Res[V]): Res[V]
  def map[V](f: T => V): Res[V]
  def filter(f: T => Boolean): Res[T] = this
}

/**
 * Fake for-comprehension generator to catch errors and turn
 * them into [[Res.Failure]]s
 */
case class Catching(handler: PartialFunction[Throwable, Res.Failing]) {

  def foreach[T](t: Unit => T): T = t(())
  def flatMap[T](t: Unit => Res[T]): Res[T] =
    try{t(())} catch handler
  def map[T](t: Unit => T): Res[T] =
    try Res.Success(t(())) catch handler
}

case class Evaluated[T](wrapper: String,
                        imports: Seq[ImportData],
                        value: T)

/**
 * Encapsulates a read-write cell that can be passed around
 */
trait Ref[T]{
  def apply(): T
  def update(t: T): Unit
}
object Ref{
  def apply[T](value0: T) = {
    var value = value0
    new Ref[T]{
      def apply() = value
      def update(t: T) = value = t
    }
  }
  def apply[T](value: T, update0: T => Unit) = new Ref[T]{
    def apply() = value
    def update(t: T) = update0(t)
  }
}

/**
 * Nice pattern matching for chained exceptions
 */
object Ex{
  def unapplySeq(t: Throwable): Option[Seq[Throwable]] = {
    def rec(t: Throwable): List[Throwable] = {
      t match {
        case null => Nil
        case t => t :: rec(t.getCause)
      }
    }
    Some(rec(t))
  }
}

object Util {
  def transpose[A](xs: List[List[A]]): List[List[A]] = xs.filter(_.nonEmpty) match {
    case Nil    =>  Nil
    case ys: List[List[A]] => ys.map{ _.head }::transpose(ys.map{ _.tail })
  }

  def readFully(is: InputStream) = {
    val buffer = new ByteArrayOutputStream()
    val data = Array.ofDim[Byte](16384)

    var nRead = is.read(data, 0, data.length)
    while (nRead != -1) {
      buffer.write(data, 0, nRead)
      nRead = is.read(data, 0, data.length)
    }

    buffer.flush()
    buffer.toByteArray
  }
}

object Timer{
  var current = 0L
  def reset() = current = System.nanoTime()
  /**
   * Prints the time, in millis, that has passed
   * since the last time `reset` or `apply` was called
   */
  def apply(s: String) = {
    val now = System.nanoTime()
    println(s + ": " + (now - current) / 1000000.0)
    current = now
  }
}
object BacktickWrap{
  // from ammonite-pprint
  /**
   * Escapes a string to turn it back into a string literal
   */
  def escape(text: String): String = {
    val s = new StringBuilder
    val len = text.length
    var pos = 0
    var prev = 0

    @inline
    def handle(snip: String) = {
      s.append(text.substring(prev, pos))
      s.append(snip)
    }
    while (pos < len) {
      text.charAt(pos) match {
        case '"' => handle("\\\""); prev = pos + 1
        case '\n' => handle("\\n"); prev = pos + 1
        case '\r' => handle("\\r"); prev = pos + 1
        case '\t' => handle("\\t"); prev = pos + 1
        case '\\' => handle("\\\\"); prev = pos + 1
        case _ =>
      }
      pos += 1
    }
    handle("")
    s.toString()
  }

  def apply(s: String) = {
    import fastparse._
    import scalaparse.Scala._

    val Id2 = P( Id ~ End )
    Id2.parse(s) match{
      case _: Result.Success[_] => s
      case _ => "`" + escape(s) + "`"
    }
  }
}

object NamesFor {
  import scala.reflect.runtime.universe._

  def apply(t: scala.reflect.runtime.universe.Type): Map[String, Boolean] = {
    val yours = t.members.map(s => s.name.toString -> s.isImplicit).toMap
      .filterKeys(!_.endsWith(nme.LOCAL_SUFFIX_STRING)) // See http://stackoverflow.com/a/17248174/3714539
    val default = typeOf[Object].members.map(_.name.toString)
    yours -- default
  }

  def apply[T: TypeTag]: Map[String, Boolean] = apply(typeOf[T])
}
