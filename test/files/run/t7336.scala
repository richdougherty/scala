import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration

object Test {  
  def main(args: Array[String]) {
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

    val arraySize = 1000000
    val tooManyArrays = (Runtime.getRuntime().totalMemory() / arraySize).toInt + 1
    Await.ready(loop(tooManyArrays, arraySize), Duration.Inf)
  }
}