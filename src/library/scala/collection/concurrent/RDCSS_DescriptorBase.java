/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.collection.concurrent;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

abstract class RDCSS_DescriptorBase {

    static final int UNDECIDED = 0;
    static final int COMMIT = 1;
    static final int ABORT = 2;

    static final AtomicIntegerFieldUpdater<RDCSS_DescriptorBase> updater =
            AtomicIntegerFieldUpdater.newUpdater(RDCSS_DescriptorBase.class, "decision");

    // Whether the root swap commits. Decided once, by the first thread to complete the descriptor.
    volatile int decision = UNDECIDED;

    void decide(int verdict) {
        updater.compareAndSet(this, UNDECIDED, verdict);
    }

}
