/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2013, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.concurrent.util;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * getAndSet, addAndGet, incrementAndGet, getAndIncrement, decrementAndGet, getAndDecrement
 * are yet to be intrinsified as they only exist on s.m.Unsafe as of JDK 8
 **/
public final class Updaters {
    private final static boolean useUnsafe;

    static {
        boolean unsafe = false;
        try {
          unsafe = Unsafe.instance != null;
        } catch(ExceptionInInitializerError eiie) {
          unsafe = false;
        }
        useUnsafe = unsafe;
    }

  public final static <T,V> AtomicReferenceFieldUpdater<T, V> newReferenceUpdater(
    final Class<? extends T> ownerType,
    final Class<? extends V> fieldType,
    final String fieldName) {
    return useUnsafe ?
      new UnsafeAtomicReferenceFieldUpdater(ownerType, fieldType, fieldName) :
      AtomicReferenceFieldUpdater.newUpdater(ownerType, fieldType, fieldName);
  }

  public final static <T,V> AtomicLongFieldUpdater<T> newLongUpdater(
    final Class<? extends T> ownerType,
    final String fieldName) {
    return useUnsafe ?
      new UnsafeAtomicLongFieldUpdater(ownerType, fieldName) :
      AtomicLongFieldUpdater.newUpdater(ownerType, fieldName);
  }

  public final static <T,V> AtomicIntegerFieldUpdater<T> newIntegerUpdater(
    final Class<? extends T> ownerType,
    final String fieldName) {
    return useUnsafe ?
      new UnsafeAtomicIntegerFieldUpdater(ownerType, fieldName) :
      AtomicIntegerFieldUpdater.newUpdater(ownerType, fieldName);
  }

  private final static <T, V> long getFieldOffset(final Class<? extends T> ownerType,
                                                  final Class<? extends V> fieldType,
                                                  final String fieldName) {
      if (Updaters.useUnsafe == false) throw new IllegalStateException("sun.misc.Unsafe not properly loaded");
      if (ownerType == null) throw new IllegalArgumentException("ownerType is not allowed to be null");
      if (fieldType == null) throw new IllegalArgumentException("fieldType is not allowed to be null");
      if (fieldName == null || fieldName.length() < 1) throw new IllegalArgumentException("fieldName must be at least 1 character");
      try {
         final Field field = ownerType.getDeclaredField(fieldName);
         if (!field.getType().equals(fieldType))
          throw new IllegalArgumentException("" + ownerType + "." + fieldName + " has type " + field.getType() + " but " + fieldType + " was expected");
         
         return Unsafe.instance.objectFieldOffset(field);
      } catch(final NoSuchFieldException nsfe) {
        throw new IllegalArgumentException("Field '" + fieldName + "' not found on type '" + ownerType +"'");
      }
  }



  private static final class UnsafeAtomicReferenceFieldUpdater<T, V> extends AtomicReferenceFieldUpdater<T, V> {
    private final Class<? extends T> ownerType;
    private final Class<? extends V> fieldType;
    private final String fieldName;
    private final long fieldOffset;

    public UnsafeAtomicReferenceFieldUpdater(final Class<? extends T> ownerType,
                                             final Class<? extends V> fieldType,
                                             final String fieldName) {
      this.fieldOffset = Updaters.getFieldOffset(ownerType, fieldType, fieldName);
      this.ownerType = ownerType;
      this.fieldType = fieldType;
      this.fieldName = fieldName;
    }

    @Override
    public final boolean compareAndSet(final T obj, final V expect, final V update) {
      return Unsafe.instance.compareAndSwapObject(obj, fieldOffset, expect, update);
    }

    @Override
    public final V getAndSet(final T obj, final V newValue) {
      for(;;) {
        final V current = get(obj);
        if (compareAndSet(obj, current, newValue))
          return current;
      }
    }

    @Override
    public final V get(final T obj) {
      return (V)Unsafe.instance.getObjectVolatile(obj, fieldOffset);
    }

    @Override
    public final void set(final T obj, final V newValue) {
      Unsafe.instance.putObjectVolatile(obj, fieldOffset, newValue);
    }

    @Override
    public final void lazySet(final T obj, final V newValue) {
      Unsafe.instance.putOrderedObject(obj, fieldOffset, newValue);
    }

    @Override
    public final boolean weakCompareAndSet(final T obj, final V expect, final V update) {
      return Unsafe.instance.compareAndSwapObject(obj, fieldOffset, expect, update);
    }

    @Override
    public final String toString() {
      return this.getClass().getSimpleName() + "(" + fieldType + " " + ownerType + "." + fieldName + ")";
    }
  }





  private static final class UnsafeAtomicLongFieldUpdater<T> extends AtomicLongFieldUpdater<T> {
    private final Class<? extends T> ownerType;
    private final String fieldName;
    private final long fieldOffset;

    public UnsafeAtomicLongFieldUpdater(final Class<? extends T> ownerType, final String fieldName) {
      this.fieldOffset = Updaters.getFieldOffset(ownerType, long.class, fieldName);
      this.ownerType = ownerType;
      this.fieldName = fieldName;
    }

    @Override public final long addAndGet(final T obj, final long delta) {
      for(;;) {
        final long current = get(obj);
        final long newValue = current + delta;
        if (compareAndSet(obj, current, newValue)) return newValue;
      }
    }

    @Override public final boolean compareAndSet(final T obj, final long expect, final long update) {
      return Unsafe.instance.compareAndSwapLong(obj, fieldOffset, expect, update);
    }
    
    @Override public final long decrementAndGet(final T obj) {
      return addAndGet(obj, -1);
    }
    
    @Override public final long get(final T obj) {
      return Unsafe.instance.getLongVolatile(obj, fieldOffset);
    }
    
    @Override public final long getAndAdd(final T obj, final long delta) {
      for(;;) {
        final long current = get(obj);
        if (compareAndSet(obj, current, current + delta)) return current;
      }
    }
    
    @Override public final long getAndDecrement(final T obj) {
      return getAndAdd(obj, -1);
    }
    
    @Override public final long getAndIncrement(final T obj) {
      return getAndAdd(obj, 1);
    }

    @Override public final long getAndSet(final T obj, final long newValue) {
      for(;;) {
        final long current = get(obj);
        if (compareAndSet(obj, current, newValue))
          return current;
      }
    }
    
    @Override public final long incrementAndGet(final T obj) {
      return addAndGet(obj, 1);
    }

    @Override public final void lazySet(final T obj, final long newValue) {
      Unsafe.instance.putOrderedLong(obj, fieldOffset, newValue);
    }
    
    @Override public final void set(final T obj, final long newValue) {
      Unsafe.instance.putLongVolatile(obj, fieldOffset, newValue);
    }
    
    @Override public final boolean weakCompareAndSet(final T obj, final long expect, final long update) {
      return compareAndSet(obj, expect, update);
    }

    @Override
    public final String toString() {
      return this.getClass().getSimpleName() + "(" + "java.lang.long" + " " + ownerType + "." + fieldName + ")";
    }
  }




  private static final class UnsafeAtomicIntegerFieldUpdater<T> extends AtomicIntegerFieldUpdater<T> {
    private final Class<? extends T> ownerType;
    private final String fieldName;
    private final long fieldOffset;

    public UnsafeAtomicIntegerFieldUpdater(final Class<? extends T> ownerType, final String fieldName) {
      this.fieldOffset = Updaters.getFieldOffset(ownerType, int.class, fieldName);
      this.ownerType = ownerType;
      this.fieldName = fieldName;
    }

    @Override public final int addAndGet(final T obj, final int delta) {
      for(;;) {
        final int current = get(obj);
        final int newValue = current + delta;
        if (compareAndSet(obj, current, newValue)) return newValue;
      }
    }

    @Override public final boolean compareAndSet(final T obj, final int expect, final int update) {
      return Unsafe.instance.compareAndSwapInt(obj, fieldOffset, expect, update);
    }
    
    @Override public final int decrementAndGet(final T obj) {
      return addAndGet(obj, -1);
    }
    
    @Override public final int get(final T obj) {
      return Unsafe.instance.getIntVolatile(obj, fieldOffset);
    }
    
    @Override public final int getAndAdd(final T obj, final int delta) {
      for(;;) {
        final int current = get(obj);
        if (compareAndSet(obj, current, current + delta)) return current;
      }
    }
    
    @Override public final int getAndDecrement(final T obj) {
      return getAndAdd(obj, -1);
    }
    
    @Override public final int getAndIncrement(final T obj) {
      return getAndAdd(obj, 1);
    }

    @Override public final int getAndSet(final T obj, final int newValue) {
      for(;;) {
        final int current = get(obj);
        if (compareAndSet(obj, current, newValue))
          return current;
      }
    }
    
    @Override public final int incrementAndGet(final T obj) {
      return addAndGet(obj, 1);
    }

    @Override public final void lazySet(final T obj, final int newValue) {
      Unsafe.instance.putOrderedInt(obj, fieldOffset, newValue);
    }
    
    @Override public final void set(final T obj, final int newValue) {
      Unsafe.instance.putIntVolatile(obj, fieldOffset, newValue);
    }
    
    @Override public final boolean weakCompareAndSet(final T obj, final int expect, final int update) {
      return compareAndSet(obj, expect, update);
    }

    @Override
    public final String toString() {
      return this.getClass().getSimpleName() + "(" + "java.lang.int" + " " + ownerType + "." + fieldName + ")";
    }
  }
}
