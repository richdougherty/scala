package scala.concurrent

import scala.util.Try

case class FutureTry[+A](future: Future[A]) {
  def map[B](f: Try[A] => Try[B])(implicit ec: ExecutionContext): FutureTry[B] = {
    FutureTry(future.transform(f))
  }
  def flatMap[B](f: Try[A] => FutureTry[B])(implicit ec: ExecutionContext): FutureTry[B] = {
    FutureTry(future.transformWith(t => f(t).future))
  }
}