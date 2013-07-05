/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent


import java.util.concurrent.{ ExecutorService, Executor }
import scala.annotation.implicitNotFound
import scala.util.Try

/**
 * An `ExecutionContext` is an abstraction over an entity that can execute program logic.
 */
@implicitNotFound("Cannot find an implicit ExecutionContext, either require one yourself or import ExecutionContext.Implicits.global")
trait ExecutionContext {
  
  /** Runs a block of code on this execution context. Some `ExecutionContext`s may need
   *  to be prepared before this method can be called.
   */
  def execute(runnable: Runnable): Unit
  
  /** Reports that an asynchronous computation failed.
   */
  def reportFailure(@deprecatedName('t) cause: Throwable): Unit
  
  /** Prepares for the execution of a task. Returns the prepared execution context. If
   *  the `ExecutionContext` doesn't require any preparation then this method may
   *  return the current object (`this`) unchanged.
   *
   *  Some `ExecutionContext`s will require preparation and others will not. When dealing
   *  with an arbitrary `ExecutionContext` this method should be called to ensure that any
   *  required preparation occurs.
   *
   *  During preparation an `ExecutionContext` may capture information
   *  about the current environment, e.g. thread local information. The captured information
   *  can be restored later when the `ExecutionContext` is executing code. Therefore it may be necessary to
   *  prepare the `ExecutionContext` in the correct environment.
   *
   *  When a method receives an `ExecutionContext` it is recommended to prepare the
   *  `ExecutionContext` on the calling thread so that `prepare` can capture information from
   *  the local environment.
   *
   *  Once an `ExecutionConext` is prepared, it is no longer necessary to call the `prepare` method
   *  again. It is, however, safe to do so, and is usually just a no-op.
   */
  def prepare(): ExecutionContext = this

}

/**
 * An `ExecutionContext` that is already prepared. Code that uses a `PreparedExecutionContext`
 * does not need to call the `prepare` method to prepare the context, since it is already
 * prepared. Calling `prepare` will simply return the object unchanged.
 */
@implicitNotFound("Cannot find an implicit PreparedExecutionContext, either require one yourself or import ExecutionContext.Implicits.global")
trait PreparedExecutionContext extends ExecutionContext {

  /** Returns the current `ExecutionContext` unchanged, since it is already prepared.
   */
  override final def prepare(): PreparedExecutionContext = this

}

/**
 * Union interface since Java does not support union types
 */
trait ExecutionContextExecutor extends ExecutionContext with Executor

/**
 * Union interface since Java does not support union types
 */
trait PreparedExecutionContextExecutor extends ExecutionContextExecutor with PreparedExecutionContext

/**
 * Union interface since Java does not support union types
 */
trait ExecutionContextExecutorService extends ExecutionContextExecutor with ExecutorService

/**
 * Union interface since Java does not support union types
 */
trait PreparedExecutionContextExecutorService extends ExecutionContextExecutorService with PreparedExecutionContextExecutor


/** Contains factory methods for creating execution contexts.
 */
object ExecutionContext {
  /**
   * This is the explicit global ExecutionContext,
   * call this when you want to provide the global ExecutionContext explicitly
   */
  def global: PreparedExecutionContextExecutor = Implicits.global

  /**
   * Prepares an `ExecutionContext` and wraps it with a `PreparedExecutionContext` to indicate
   * that it has been prepared. If the given `ExecutionContext` is already an instance of
   * `PreparedExecutionContext` then it will be cast and returned without any other work.
   */
  def prepare(ec: ExecutionContext): PreparedExecutionContext = Implicits.prepare(ec)

  object Implicits {
    /**
     * This is the implicit global ExecutionContext,
     * import this when you want to provide the global ExecutionContext implicitly
     */
    implicit lazy val global: PreparedExecutionContextExecutor = impl.ExecutionContextImpl.fromExecutor(null: Executor)

    /**
     * Prepares an `ExecutionContext` and wraps it with a `PreparedExecutionContext` to indicate
     * that it has been prepared. If the given `ExecutionContext` is already an instance of
     * `PreparedExecutionContext` then it will be cast and returned without any other work.
     */
    implicit def prepare(ec: ExecutionContext): PreparedExecutionContext = ec match {
      case pec: PreparedExecutionContext => pec
      case _ => {
        val prepared = ec.prepare()
        new PreparedExecutionContext {
          override def execute(runnable: Runnable) = prepared.execute(runnable)
          override def reportFailure(cause: Throwable) = prepared.reportFailure(cause)
        }
      }
    }
  }
    
  /** Creates an `ExecutionContext` from the given `ExecutorService`.
   */
  def fromExecutorService(e: ExecutorService, reporter: Throwable => Unit): PreparedExecutionContextExecutorService =
    impl.ExecutionContextImpl.fromExecutorService(e, reporter)

  /** Creates an `ExecutionContext` from the given `ExecutorService` with the default Reporter.
   */
  def fromExecutorService(e: ExecutorService): PreparedExecutionContextExecutorService =
    fromExecutorService(e, defaultReporter)
  
  /** Creates an `ExecutionContext` from the given `Executor`.
   */
  def fromExecutor(e: Executor, reporter: Throwable => Unit): PreparedExecutionContextExecutor =
    impl.ExecutionContextImpl.fromExecutor(e, reporter)

  /** Creates an `ExecutionContext` from the given `Executor` with the default Reporter.
   */
  def fromExecutor(e: Executor): PreparedExecutionContextExecutor = fromExecutor(e, defaultReporter)
  
  /** The default reporter simply prints the stack trace of the `Throwable` to System.err.
   */
  def defaultReporter: Throwable => Unit = _.printStackTrace()
}


