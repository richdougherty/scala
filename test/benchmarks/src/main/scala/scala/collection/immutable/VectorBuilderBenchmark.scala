package scala.collection.immutable

import java.util.concurrent.TimeUnit

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra._

/** Benchmark for Map construction */
@BenchmarkMode(Array(Mode.AverageTime))
@Fork(2)
@Threads(1)
@Warmup(iterations = 10)
@Measurement(iterations = 10)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
class VectorBuilderBenchmark {

  /** Vary the size of the Map to benchmark. Sizes 0-4 are special cases in the Map implementation */
  @Param(Array("0","1","2","3","16","32","50","100","1000","10000"))
  var size: Int = _

  // var elems: Vector[(String,String)] = _
  // var elemMap: Map[String,String] = _
  // var result: Map[String,String] = _

  // @Setup(Level.Iteration)
  // def iterationInit(): Unit = {
  //   // Slightly randomize the keys in the map
  //   val thread = Thread.currentThread.getName
  //   val time = System.currentTimeMillis()
  //   elems = (0 until size).map(i => (s"key$i-$thread-$time" -> s"$i")).to(Vector)
  //   elemMap = elems.toMap
  // }

  // @TearDown(Level.Iteration)
  // def iterationTearDown(): Unit = {
  //   // Minimal sanity check
  //   assert(result.size == size)
  // }

  @Benchmark def buildWithHint(bh: Blackhole): Unit = {
    val builder = Vector.newBuilder[Int]
    builder.sizeHint(size)
    var i = 0
    while (i < size) {
      builder.addOne(i)
      i += 1
    }
    bh.consume(builder.result())
  }

}