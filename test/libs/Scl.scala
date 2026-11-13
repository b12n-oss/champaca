import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import scala.collection.mutable

sealed trait Shape { def area: Double }
case class Circle(r: Double) extends Shape { def area = math.Pi * r * r }
case class Rect(w: Double, h: Double) extends Shape { def area = w * h }
case class User(name: String, age: Int = 0)
trait Greeter { def name: String; def greet = s"hi $name" }
object Bob extends Greeter { val name = "bob" }
class Box[+A](val a: A) { def map[B](f: A => B) = new Box(f(a)); override def toString = s"Box($a)" }
object Color extends Enumeration { val Red, Green, Blue = Value }
trait Show[A] { def show(a: A): String }
object Implicits {
  implicit class Shout(s: String) { def shout = s.toUpperCase + "!" }
  implicit val intShow: Show[Int] = (a: Int) => s"int:$a"
  def show[A](a: A)(implicit s: Show[A]) = s.show(a)
}
import Implicits._

object Scl {
  def row(k: String, v: Any): Unit = println(s"$k: $v")
  def fact(n: BigInt): BigInt = if (n <= 1) 1 else n * fact(n - 1)
  @annotation.tailrec def sum(n: Long, acc: Long = 0): Long = if (n == 0) acc else sum(n - 1, acc + n)
  def describe(x: Any): String = x match {
    case i: Int if i > 10 => "big int"
    case i: Int => "int"
    case s: String => s"str $s"
    case User(n, a) => s"user $n $a"
    case (a, b) => s"pair $a $b"
    case xs: List[_] => s"list ${xs.size}"
    case _ => "other"
  }
  def main(args: Array[String]): Unit = {
    row("case classes", List(User("a", 1), User("a", 1) == User("a", 1), User("b").copy(age = 5), User("c", 2).hashCode))
    row("sealed", List(Circle(1), Rect(2, 3)).map(s => f"${s.area}%.3f"))
    row("match", List(42, 3, "x", User("u", 9), (1, 2), List(1, 2), 1.5).map(describe))
    row("traits", Bob.greet)
    row("variance", new Box(3).map(_ * 2))
    row("enum", (Color.values.toList, Color.withName("Green").id))
    row("implicits", ("hey".shout, show(5)))
    row("collections", ((1 to 10).filter(_ % 2 == 0).map(x => x * x), List(3, 1, 2).sorted, List("bb", "a", "ccc").sortBy(_.length), (1 to 6).groupBy(_ % 3).toList.sortBy(_._1), List(1, 2, 3).foldLeft(0)(_ + _)))
    row("maps", Map("a" -> 1, "b" -> 2).map { case (k, v) => k.toUpperCase -> v * 10 }.toList.sorted)
    row("lazy", LazyList.iterate(1)(_ * 3).takeWhile(_ < 1000).toList)
    row("for", for { x <- List(1, 2, 3); y <- List("a", "b") if x != 2 } yield s"$x$y")
    row("option", (Some(3).map(_ + 1), None.getOrElse("dflt"), Option(null: String).isEmpty))
    row("either", (Right(2).map(_ * 3), Left("e").left.map(_.length), List(Right(1), Left("x")).partitionMap(identity)))
    row("try", (Try(1 / 0).isFailure, Try("5".toInt).map(_ + 1), Try(throw new IllegalStateException("boom")) match { case Failure(e) => e.getMessage; case Success(v) => v }))
    row("bigint", (fact(25), BigInt(2).pow(100), BigDecimal("0.1") + BigDecimal("0.2")))
    row("tailrec", sum(100000))
    row("strings", ("abc".reverse, "x" * 3, "a,b,,c".split(",").toList, f"${3.14159}%05.2f|${255}%x", "hello".capitalize, raw"a\nb"))
    row("regex", ("""(\d+)-(\d+)""".r.findFirstMatchIn("tel 12-34").map(m => (m.group(1), m.group(2))), """\w+""".r.findAllIn("a bb ccc").toList))
    row("mutable", { val b = mutable.ArrayBuffer(1, 2); b += 3; val m = mutable.Map[String, Int](); m("k") = 1; (b, m) })
    row("arrays", (Array(3, 1, 2).sorted.toList, Array.tabulate(3)(i => i * i).toList, Array("x", "y").mkString("[", ",", "]")))
    row("futures", Await.result(Future.sequence((1 to 5).map(i => Future(i * i))), 10.seconds))
    row("parallel threads", { val c = new java.util.concurrent.atomic.AtomicInteger(); val ts = (1 to 4).map(_ => new Thread(() => (1 to 1000).foreach(_ => c.incrementAndGet()))); ts.foreach(_.start()); ts.foreach(_.join()); c.get })
    row("java", (new java.util.ArrayList[Int](java.util.List.of(1, 2)), java.time.LocalDate.of(2024, 2, 29).plusYears(1), String.format("%,d", 1234567)))
    row("exceptions", try { throw new IllegalArgumentException("bad") } catch { case e: IllegalArgumentException => e.getMessage } finally { () })
    row("hash", ("abc".hashCode, List(1, 2).hashCode, Map(1 -> 2).hashCode))
  }
}
