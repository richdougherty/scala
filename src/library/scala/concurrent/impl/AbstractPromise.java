/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent.impl;


import scala.concurrent.util.Updaters;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

abstract class AbstractPromise {
    private volatile Object _ref;

    private final static AtomicReferenceFieldUpdater<AbstractPromise, Object> updater =
      Updaters.newReferenceUpdater(AbstractPromise.class, Object.class, "_ref");

    protected final boolean updateState(Object oldState, Object newState) {
	    return updater.compareAndSet(this, oldState, newState);
    }

    protected final Object getState() {
	    return updater.get(this);
    }
}