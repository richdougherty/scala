class LazyFutureSeq {
  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], executor: ExecutionContext): Future[M[A]] = {


trait FutureTraversableOnce[+A, M[X] <: TraversableOnce[X]] {
  
}

object FutureSeq {
  def sequence[A, M[X] <: TraversableOnce[X]](in: M[Future[A]])(implicit cbf: CanBuildFrom[M[Future[A]], A, M[A]], executor: ExecutionContext): Future[M[A]] = {
}

trait FutureTraverser[A] {
  def map[A]
}