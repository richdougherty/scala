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
class MapBuilderBenchmark {

  /** Vary the size of the Map to benchmark. Sizes 0-4 are special cases in the Map implementation */
  @Param(Array("0","1","2","3","4","5","50"))
  var size: Int = _

  var elems: Array[(String,String)] = _
  var elemMap: Map[String,String] = _
  var result: Map[String,String] = _

  @Setup(Level.Iteration)
  def iterationInit(): Unit = {
    // Slightly randomize the keys in the map
    val thread = Thread.currentThread.getName
    val time = System.currentTimeMillis()
    elems = (0 until size).map(i => (s"key$i-$thread-$time" -> s"$i")).toArray
    elemMap = elems.toMap
  }

  @TearDown(Level.Iteration)
  def iterationTearDown(): Unit = {
    // Minimal sanity check
    assert(result.size == size)
  }

  /** Construct a Map using Map.newBuilder() with a sizeHint */
  @Benchmark def mapBuilderKnownSize(bh: Blackhole): Unit = {
    val builder = Map.newBuilder[String,String]
    builder.sizeHint(size)
    var i = 0
    while (i < size) {
      builder +=(elems(i))
      i += 1
    }
    result = builder.result()
  }

  /** Construct a Map using Map.newBuilder() without a sizeHint */
  @Benchmark def mapBuilderUnknownSize(bh: Blackhole): Unit = {
    val builder = Map.newBuilder[String,String]
    var i = 0
    while (i < size) {
      builder +=(elems(i))
      i += 1
    }
    result = builder.result()
  }

  /** Construct a Map using Map(...) */
  @Benchmark def mapApply(bh: Blackhole): Unit = {
    result = Map(elems: _*)
  }

  /** Construct a Map by calling `map` on an existing Map */
  @Benchmark def mapMap(bh: Blackhole): Unit = {
    result = elemMap.map(identity(_))
  }
}