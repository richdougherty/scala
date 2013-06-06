/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent.impl

import scala.concurrent.{ ExecutionContext, CanAwait, OnCompleteRunnable, TimeoutException, ExecutionException }
import scala.concurrent.duration.{ Duration, Deadline, FiniteDuration, NANOSECONDS }
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{ Try, Success, Failure }

private[concurrent] trait Promise[T] extends scala.concurrent.Promise[T] with scala.concurrent.Future[T] {
  def future: this.type = this
}

/* Precondition: `executor` is prepared, i.e., `executor` has been returned from invocation of `prepare` on some other `ExecutionContext`.
 */
private class CallbackRunnable[T](val executor: ExecutionContext, val onComplete: Try[T] => Any) extends Runnable with OnCompleteRunnable {
  // must be filled in before running it
  var value: Try[T] = null

  override def run() = {
    require(value ne null) // must set value to non-null before running!
    try onComplete(value) catch { case NonFatal(e) => executor reportFailure e }
  }

  def executeWithValue(v: Try[T]): Unit = {
    require(value eq null) // can't complete it twice
    value = v
    // Note that we cannot prepare the ExecutionContext at this point, since we might
    // already be running on a different thread!
    try executor.execute(this) catch { case NonFatal(t) => executor reportFailure t }
  }
}

private[concurrent] object Promise {

  private def resolveTry[T](source: Try[T]): Try[T] = source match {
    case Failure(t) => resolver(t)
    case _          => source
  }

  private def resolver[T](throwable: Throwable): Try[T] = throwable match {
    case t: scala.runtime.NonLocalReturnControl[_] => Success(t.value.asInstanceOf[T])
    case t: scala.util.control.ControlThrowable    => Failure(new ExecutionException("Boxed ControlThrowable", t))
    case t: InterruptedException                   => Failure(new ExecutionException("Boxed InterruptedException", t))
    case e: Error                                  => Failure(new ExecutionException("Boxed Error", e))
    case t                                         => Failure(t)
  }

  /** Default promise implementation.
   *
   *  A DefaultPromise has three possible internal states.
   *  1. Canonical/Not-Completed - represented by a List[CallbackRunnable] object
   *     holding its listeners.
   *  2. Canonical/Completed - represented by a Try[T] object holding its result.
   *  3. Linked - represented by a DefaultPromise[T] object holding the promise that
   *     this promise is linked to. By following the chain of linked promises the
   *     underlying canonical promise (that will hold the final result) can be reached.
   *
   *  A DefaultPromise begins in the Canonical/Not-Completed with an empty list of
   *  listeners. It can be completed (see `tryComplete`) or linked to another
   *  DefaultPromise (see `link`).
   *
   *  When waiting on completion (see `awaitDeadline` and `awaitUnbounded`), the
   *  chain of linked promises is first traversed until the canonical promise at the head of
   *  the chain is located. If the canonical promise is not completed yet, the thread will
   *  wait on the promise's monitor until the promise is completed or until it is linked to
   *  another promise. The thread will be notified when the promise's state changes; either
   *  when the promise is completed or when the promise is linked to another promise.
   */
  class DefaultPromise[T] extends AbstractPromise with Promise[T] { self =>
    updateState(null, Nil) // Start in the Canonical/Not-Completed state with no listeners

    /** Get the canonical promise for this promise, compressing links. The canonical
     *  promise will hold the result and list of callbacks for all promises linked to it.
     *  For many promises the result of calling `canonical()` will just be `this`.
     *  However for linked promises, this method will traverse each link until it locates
     *  the underlying linked promise.
     *
     *  As a side effect of calling this method, any links back to the canonical promise
     *  are flattened so that, after this method is called, the canonical promise is at
     *  most one link away from this promise.
     */
    final def canonical(): DefaultPromise[T] = {
      @tailrec def canonical(): DefaultPromise[T] = {
        getState match {
          case linked: DefaultPromise[_] => {
            val p = linked.asInstanceOf[DefaultPromise[T]]
            val target = p.canonical()
            if (linked eq target) target else if (updateState(linked, target)) target else canonical()
          }
          case _ => this
        }
      }
      canonical()
    }

    /** Try waiting for this promise to be completed.
     */
    protected final def tryAwait(atMost: Duration): Boolean = {
      import Duration.Undefined
      atMost match {
        case u if u eq Undefined => throw new IllegalArgumentException("cannot wait for Undefined period")
        case Duration.Inf        => awaitUnbounded()
        case Duration.MinusInf   => isCompleted
        case f: FiniteDuration   => if (f > Duration.Zero) awaitDeadline(f.fromNow, f) else isCompleted
      }
    }

    /** Try waiting until the deadline for this promise to be completed.
     */
    @tailrec
    private final def awaitDeadline(deadline: Deadline, nextWait: FiniteDuration): Boolean = getState match {
      case _: Try[_] => true
      case _: DefaultPromise[_] => canonical().awaitDeadline(deadline, nextWait)
      case _: List[_] if nextWait <= Duration.Zero => false
      case _: List[_] => {
        val ms = nextWait.toMillis
        val ns = (nextWait.toNanos % 1000000l).toInt // as per object.wait spec

        synchronized {
          getState match {
            case _: List[_] => wait(ms, ns)
            case _ => ()
          }
        }

        awaitDeadline(deadline, deadline.timeLeft)
      }
    }

    /** Wait forever for this promise to be completed.
     */
    @tailrec
    private final def awaitUnbounded(): Boolean = getState match {
      case _: Try[_] => true
      case _: DefaultPromise[_] => canonical().awaitUnbounded()
      case _: List[_] => {
        synchronized {
          getState match {
            case _: List[_] => wait()
            case _ => ()
          }
        }
        awaitUnbounded()
      }
    }

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      if (isCompleted || tryAwait(atMost)) this
      else throw new TimeoutException("Futures timed out after [" + atMost + "]")

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): T =
      ready(atMost).value.get match {
        case Failure(e)  => throw e
        case Success(r) => r
      }

    def value: Option[Try[T]] = getState match {
      case c: Try[_] => Some(c.asInstanceOf[Try[T]])
      case _: DefaultPromise[_] => canonical().value
      case _ => None
    }

    override def isCompleted: Boolean = getState match { // Cheaper than boxing result into Option due to "def value"
      case _: Try[_] => true
      case _: DefaultPromise[_] => canonical().isCompleted
      case _ => false
    }

    def tryComplete(value: Try[T]): Boolean = {
      val resolved = resolveTry(value)
      tryCompleteAndGetListeners(resolved) match {
        case null             => false
        case rs if rs.isEmpty => true
        case rs               => rs.foreach(r => r.executeWithValue(resolved)); true
      }
    }

    /** Called by `tryComplete` to store the resolved value and get the list of
     *  listeners, or `null` if it is already completed.
     */
    @tailrec
    private final def tryCompleteAndGetListeners(v: Try[T]): List[CallbackRunnable[T]] = {
      getState match {
        case raw: List[_] =>
          val cur = raw.asInstanceOf[List[CallbackRunnable[T]]]
          if (updateState(cur, v)) {
            synchronized { notifyAll() }
            cur
          } else tryCompleteAndGetListeners(v)
        case _: DefaultPromise[_] =>
          canonical().tryCompleteAndGetListeners(v)
        case _ => null
      }
    }

    def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
      val preparedEC = executor.prepare()
      val runnable = new CallbackRunnable[T](preparedEC, func)
      dispatchOrAddCallback(runnable)
    }

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed
     */
    @tailrec
    private final def dispatchOrAddCallback(runnable: CallbackRunnable[T]): Unit = {
      getState match {
        case r: Try[_]          => runnable.executeWithValue(r.asInstanceOf[Try[T]])
        case _: DefaultPromise[_] => canonical().dispatchOrAddCallback(runnable)
        case listeners: List[_] => if (updateState(listeners, runnable :: listeners)) () else dispatchOrAddCallback(runnable)
      }
    }

    /** Link this promise to another promise so that the other promise becomes the canonical
     *  promise for this promise. Listeners will be added/dispatched to that promise once the
     *  link is in place.
     *
     *  If this promise is already completed, the same effect as linking is achieved by simply
     *  copying this promise's result to the target promise.
     */
    @tailrec
    final def link(target: DefaultPromise[T]): Unit = {
      if (this eq target) return

      getState match {
        case r: Try[_] =>
          if (!target.tryComplete(r.asInstanceOf[Try[T]])) throw new IllegalStateException("Cannot link completed promises together")
        case _: DefaultPromise[_] =>
          canonical().link(target)
        case listeners: List[_] => if (updateState(listeners, target)) {
          synchronized { notifyAll() }
          if (!listeners.isEmpty) listeners.asInstanceOf[List[CallbackRunnable[T]]].foreach(target.dispatchOrAddCallback(_))
        } else link(target)
      }
    }
  }

  /** An already completed Future is given its result at creation.
   *
   *  Useful in Future-composition when a value to contribute is already available.
   */
  final class KeptPromise[T](suppliedValue: Try[T]) extends Promise[T] {

    val value = Some(resolveTry(suppliedValue))

    override def isCompleted: Boolean = true

    def tryComplete(value: Try[T]): Boolean = false

    def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
      val completedAs = value.get
      val preparedEC = executor.prepare()
      (new CallbackRunnable(preparedEC, func)).executeWithValue(completedAs)
    }

    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this

    def result(atMost: Duration)(implicit permit: CanAwait): T = value.get.get
  }

}
