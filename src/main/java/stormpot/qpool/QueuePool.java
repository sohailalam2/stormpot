/*
 * Copyright 2013 Chris Vest
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.qpool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import stormpot.Allocator;
import stormpot.Completion;
import stormpot.Config;
import stormpot.Expiration;
import stormpot.LifecycledResizablePool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Timeout;

/**
 * QueuePool is a fairly simple {@link LifecycledResizablePool} implementation
 * that basically consists of a queue of Poolable instances, and a Thread to
 * allocate them.
 * <p>
 * This means that the object allocation always happens in a dedicated thread.
 * This means that no thread that calls any of the claim methods, will incur
 * the overhead of allocating Poolables. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class QueuePool<T extends Poolable>
implements LifecycledResizablePool<T> {
  /**
   * Special slot used to signal that the pool has been shut down.
   */
  private final QSlot<T> POISON_PILL = new QSlot<T>(null);
  
  private final BlockingQueue<QSlot<T>> live;
  private final BlockingQueue<QSlot<T>> dead;
  private final Expiration<? super T> deallocRule;
  private volatile boolean shutdown = false;
  
  private final Executor executor;
  private final Allocator<T> allocator;
  private final AtomicInteger currentSize;
  private volatile int targetSize;
  private final CountDownLatch shutdownLatch;
  
  /**
   * Construct a new QueuePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public QueuePool(Config<T> config) {
    live = new LinkedBlockingQueue<QSlot<T>>();
    dead = new LinkedBlockingQueue<QSlot<T>>();
    synchronized (config) {
      config.validate();
      deallocRule = config.getExpiration();
      executor = config.getExecutor();
      allocator = config.getAllocator();
      currentSize = new AtomicInteger(0);
      targetSize = config.getSize();
    }
    
    shutdownLatch = new CountDownLatch(1);
    for (int i = 0; i < targetSize; i++) {
      executor.execute(new AllocateNew());
    }
  }
  
  private class AllocateNew implements Runnable {
    @Override
    public void run() {
      QSlot<T> slot = new QSlot<T>(live);
      allocateSlot(slot);
    }
  }
  
  private class DeallocateAny implements Runnable {
    @Override
    public void run() {
      try {
        QSlot<T> slot = dead.poll();
        int observedSize = currentSize.get();
        while (slot == null && observedSize > 0) {
          slot = live.poll(20, TimeUnit.MILLISECONDS);
          if (slot == POISON_PILL) {
            slot = live.poll(20, TimeUnit.MILLISECONDS);
            live.offer(POISON_PILL);
          }
          if (slot == null) {
            slot = dead.poll();
          }
          observedSize = currentSize.get();
          // TODO maybe do more to prevent infinite looping
          // we don't want to starve other tasks in the pool
        }
        if (slot != null) {
          deallocateSlot(slot);
        } else if (observedSize == 0) {
          shutdownLatch.countDown();
        }
      } catch (InterruptedException e) {
        // TODO so... what do?
      }
    }
  }
  
  private class Reallocate implements Runnable {
    private QSlot<T> slot;
    
    public Reallocate(QSlot<T> slot) {
      this.slot = slot;
    }
    
    @Override
    public void run() {
      deallocateSlot(slot);
      allocateSlot(slot);
    }
  }
  
  private class Deallocate implements Runnable {
    private QSlot<T> slot;
    
    public Deallocate(QSlot<T> slot) {
      this.slot = slot;
    }
    
    @Override
    public void run() {
      deallocateSlot(slot);
    }
  }
  
  private void allocateSlot(QSlot<T> slot) {
    int newSize = currentSize.incrementAndGet();
    int targetSize2 = targetSize;
    if (newSize > targetSize2) {
      newSize = currentSize.decrementAndGet();
      return;
    }
    try {
      slot.obj = allocator.allocate(slot);
      if (slot.obj == null) {
        slot.poison = new NullPointerException("allocation returned null");
      }
    } catch (Exception e) {
      slot.poison = e;
    }
    slot.created = System.currentTimeMillis();
    slot.claims = 0;
    slot.stamp = 0;
    slot.claimed.set(true);
    slot.release(slot.obj);
  }
  
  private void deallocateSlot(QSlot<T> slot) {
    T obj = slot.obj;
    slot.obj = null;
    slot.poison = null;
    try {
      allocator.deallocate(obj);
    } catch (Exception ignored) {
      // Catch whatever the deallocate method might throw, and ignore it.
    }
    int newSize = currentSize.decrementAndGet();
    if (newSize == 0 && shutdown) {
      shutdownLatch.countDown();
    }
  }

  private void checkForPoison(QSlot<T> slot) {
    if (slot == POISON_PILL) {
      live.offer(POISON_PILL);
      throw new IllegalStateException("pool is shut down");
    }
    if (slot.poison != null) {
      Exception poison = slot.poison;
      executor.execute(new Reallocate(slot));
      throw new PoolException("allocation failed", poison);
    }
    if (shutdown) {
      executor.execute(new Deallocate(slot));
      throw new IllegalStateException("pool is shut down");
    }
  }

  private boolean isInvalid(QSlot<T> slot) {
    boolean invalid = true;
    RuntimeException exception = null;
    try {
      invalid = deallocRule.hasExpired(slot);
    } catch (Exception ex) {
      exception = new PoolException(
          "Got exception when checking whether an object had expired", ex);
    }
    if (invalid) {
      // it's invalid - into the dead queue with it and continue looping
      executor.execute(new Reallocate(slot));
      if (exception != null) {
        throw exception;
      }
    } else {
      // it's valid - claim it and stop looping
      slot.claim();
    }
    return invalid;
  }

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    QSlot<T> slot;
    long deadline = timeout.getDeadline();
    do {
      long timeoutLeft = timeout.getTimeLeft(deadline);
      slot = live.poll(timeoutLeft, timeout.getBaseUnit());
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
      checkForPoison(slot);
    } while (isInvalid(slot));
    return slot.obj;
  }

  public synchronized Completion shutdown() {
    if (!shutdown) {
      shutdown = true;
      live.offer(POISON_PILL);
      for (int i = 0; i < targetSize; i++) {
        executor.execute(new DeallocateAny());
      }
      targetSize = 0;
    }
    return new LatchCompletion(shutdownLatch);
  }

  public synchronized void setTargetSize(int size) {
    if (size < 1) {
      throw new IllegalArgumentException("target size must be at least 1");
    }
    if (!shutdown) {
      int delta = targetSize - size;
      if (delta < 0) {
        // We're growing the pool
        for (; delta < 0; delta++) {
          executor.execute(new AllocateNew());
        }
      } else {
        // We're shrinking the pool
        for (; delta > 0; delta--) {
          executor.execute(new DeallocateAny());
        }
      }
      targetSize = size;
    }
  }

  public int getTargetSize() {
    return targetSize;
  }
}
