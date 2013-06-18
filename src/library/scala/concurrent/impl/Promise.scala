/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent.impl

import scala.concurrent.{ ExecutionContext, CanAwait, OnCompleteRunnable, TimeoutException, ExecutionException, blocking }
import scala.concurrent.Future.InternalCallbackExecutor
import scala.concurrent.duration.{ Duration, Deadline, FiniteDuration, NANOSECONDS }
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.{ Try, Success, Failure }
import java.io.ObjectInputStream
import java.util.concurrent.locks.AbstractQueuedSynchronizer

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

   /**
    * Latch used to implement waiting on a DefaultPromise's result.
    *
    * Inspired by: http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/main/java/util/concurrent/locks/AbstractQueuedSynchronizer.java
    * Written by Doug Lea with assistance from members of JCP JSR-166
    * Expert Group and released to the public domain, as explained at
    * http://creativecommons.org/publicdomain/zero/1.0/
    */
    private final class CompletionLatch[T] extends AbstractQueuedSynchronizer with (Try[T] => Unit) {
      override protected def tryAcquireShared(ignored: Int): Int = if (getState != 0) 1 else -1
      override protected def tryReleaseShared(ignore: Int): Boolean = {
        setState(1)
        true
      }
      override def apply(ignored: Try[T]): Unit = releaseShared(1)
    }


  /** Default promise implementation.
   *
   *  Logically, a promise has two states:
   *
   *  1. Incomplete, with an associated list of callbacks waiting on completion.
   *  2. Complete, with a result.
   *
   *  However, a DefaultPromise can also be linked to another DefaultPromise, so that
   *  both promises appear to share the same state. The ability to link DefaultPromises
   *  is needed to prevent space leaks when using the flatMap operation. Keeping the
   *  promises separate, with their state instead synchronized via completion handlers,
   *  resulted in memory leaks.
   *
   *  When a DefaultPromise is linked to an underlying DefaultPromise, all calls will be
   *  delegated to the underlying promise, e.g. calling `value` on the linked promise
   *  will result in a call to `value` on the underlying promise. By delegating all calls,
   *  the linked promise will always appear externally to have exactly the same state as
   *  the underlying promise. The promise that contains the actual promise state (i.e. any
   *  promise that isn't linked to another promise) is called the "canonical" promise.
   *
   *  Linked promises can be linked to *other* linked promises, resulting in link chains
   *  of arbitrary length. In practice, the link chain will usually be short because an
   *  optimization within DefaultPromise relinks a promise to its canonical promise
   *  whenever the chain is found to have grown.
   * 
   *  A DefaultPromise stores its state entirely in the AnyRef cell exposed by AbstractPromise.
   *  The type of object stored in the cell fully describes the current state of the promise.
   *
   *  1. List[CallbackRunnable] - The promise is incomplete and has zero or more callbacks
   *     to call when it is eventually completed.
   *  2. Try[T] - The promise is complete and now contains its value.
   *  3. DefaultPromise[T] - The promise is linked to another promise.
   */
  final class DefaultPromise[T] extends AbstractPromise with Promise[T] { self =>
    updateState(null, Nil)

    /** Get the canonical promise for this promise. For many promises the result of calling
     *  `canonical()` will just be `this`. However for linked promises, this method will
     *  traverse each link until it locates the canonical promise at the head of the link chain.
     *
     *  As a side effect of calling this method, any link from this promise back to the
     *  canonical promise will be updated to point directly to the canonical promise. In the
     *  case where multiple links were traversed to find the canonical promise, this will make
     *  it faster when `canonical()` is called a second time.
     */
    @tailrec
    def canonical(): DefaultPromise[T] = {
      getState match {
        case linked: DefaultPromise[_] =>
          val target = linked.asInstanceOf[DefaultPromise[T]].headLinked
          if (linked eq target) target else if (updateState(linked, target)) target else canonical()
        case _ => this
      }
    }

    /** Get the promise at the head of the chain of linked promises. Used by `canonical()`.
     */
    @tailrec
    private def headLinked: DefaultPromise[T] = {
      getState match {
        case linked: DefaultPromise[_] => linked.asInstanceOf[DefaultPromise[T]].headLinked
        case _ => this
      }
    }

    /** Try waiting for this promise to be completed.
     */
    protected def tryAwait(atMost: Duration): Boolean = if (!isCompleted) {
      import Duration.Undefined
      import scala.concurrent.Future.InternalCallbackExecutor
      atMost match {
        case e if e eq Undefined => throw new IllegalArgumentException("cannot wait for Undefined period")
        case Duration.Inf        =>
          val l = new CompletionLatch[T]()
          onComplete(l)(InternalCallbackExecutor)
          l.acquireSharedInterruptibly(1)
        case Duration.MinusInf   => // Drop out
        case f: FiniteDuration   =>
          if (f > Duration.Zero) {
            val l = new CompletionLatch[T]()
            onComplete(l)(InternalCallbackExecutor)
            l.tryAcquireSharedNanos(1, f.toNanos)
          }
      }

      isCompleted
    } else true // Already completed

    @throws(classOf[TimeoutException])
    @throws(classOf[InterruptedException])
    def ready(atMost: Duration)(implicit permit: CanAwait): this.type =
      if (tryAwait(atMost)) this
      else throw new TimeoutException("Futures timed out after [" + atMost + "]")

    @throws(classOf[Exception])
    def result(atMost: Duration)(implicit permit: CanAwait): T =
      ready(atMost).value.get.get // ready throws TimeoutException if timeout so value.get is safe here

    @tailrec
    def value: Option[Try[T]] = getState match {
      case c: Try[_] => Some(c.asInstanceOf[Try[T]])
      case _: DefaultPromise[_] => canonical().value
      case _ => None
    }

    @tailrec
    override def isCompleted: Boolean = getState match {
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
    private def tryCompleteAndGetListeners(v: Try[T]): List[CallbackRunnable[T]] = {
      getState match {
        case raw: List[_] =>
          val cur = raw.asInstanceOf[List[CallbackRunnable[T]]]
          if (updateState(cur, v)) cur else tryCompleteAndGetListeners(v)
        case _: DefaultPromise[_] =>
          canonical().tryCompleteAndGetListeners(v)
        case _ => null
      }
    }

    def onComplete[U](func: Try[T] => U)(implicit executor: ExecutionContext): Unit = {
      val preparedEC = executor.prepare
      val runnable = new CallbackRunnable[T](preparedEC, func)
      dispatchOrAddCallback(runnable)
    }

    /** Tries to add the callback, if already completed, it dispatches the callback to be executed
     */
    @tailrec
    private def dispatchOrAddCallback(runnable: CallbackRunnable[T]): Unit = {
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
    def link(target: DefaultPromise[T]): Unit = {
      if (this eq target) return

      getState match {
        case r: Try[_] =>
          if (!target.tryComplete(r.asInstanceOf[Try[T]])) throw new IllegalStateException("Cannot link completed promises together")
        case _: DefaultPromise[_] =>
          canonical().link(target)
        case listeners: List[_] => if (updateState(listeners, target)) {
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
      val preparedEC = executor.prepare
      (new CallbackRunnable(preparedEC, func)).executeWithValue(completedAs)
    }

    def ready(atMost: Duration)(implicit permit: CanAwait): this.type = this

    def result(atMost: Duration)(implicit permit: CanAwait): T = value.get.get
  }

}
