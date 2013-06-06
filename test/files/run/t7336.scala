import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

def loop(i: Int, arraySize: Int): Future[Unit] = {
  val array = new Array[Byte](arraySize)
  Future.successful(i).flatMap { i =>
    if (i == 0) {
      Future.successful(())
    } else {
      array.size // Force closure to refer to array
      loop(i - 1, arraySize)
    }

  }
}

def test(i: Int, arraySize: Int) {
  val start = System.currentTimeMillis()
  Await.ready(loop(i, arraySize), Duration.Inf)
  val end = System.currentTimeMillis()
  //println(s"Created $i arrays of size $arraySize bytes in ${end - start}ms")
}

test(10000, 1000000) // Will require 10GiB of heap if Future.flatMap optimization doesn't work
