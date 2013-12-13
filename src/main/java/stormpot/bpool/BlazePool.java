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
package stormpot.bpool;

import stormpot.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BlazePool is a highly optimised {@link LifecycledResizablePool}
 * implementation that consists of a queues of Poolable instances, the access
 * to which is made faster with clever use of ThreadLocals.
 * <p>
 * Object allocation always happens in a dedicated thread, off-loading the 
 * cost of allocating the pooled objects. This should lead to reduced deviation
 * in the times it takes claim method to complete, provided the pool is not
 * depleted.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} managed by this pool.
 */
public final class BlazePool<T extends Poolable>
implements LifecycledResizablePool<T> {
  /**
   * Special slot used to signal that the pool has been shut down.
   */
  private final BSlot<T> poisonPill;
  private final BlockingQueue<BSlot<T>> live;
  private final Expiration<? super T> deallocRule;
  // TODO consider making it a ThreadLocal of Ref<QSlot>, hoping that maybe
  // writes to it will be faster in not requiring a ThreadLocal look-up.
  private final ThreadLocal<BSlot<T>> tlr;
  private final Executor executor;
  private final Allocator<T> allocator;
  private final AtomicInteger currentSize;
  private final CountDownLatch shutdownLatch;

  private volatile boolean shutdown = false;
  private volatile int targetSize;

  /**
   * Construct a new BlazePool instance based on the given {@link Config}.
   * @param config The pool configuration to use.
   */
  public BlazePool(Config<T> config) {
    poisonPill = new BSlot<T>(null);
    // The poisonPill must be in a live state. Otherwise claim() will get stuck in
    // an infinite loop when it tries to transition it to the claimed state.
    poisonPill.dead2live();
    live = new LinkedBlockingQueue<BSlot<T>>();
    tlr = new ThreadLocal<BSlot<T>>();
    synchronized (config) {
      config.validate();
      deallocRule = config.getExpiration();
      executor = config.getExecutor();
      allocator = config.getAllocator();
      targetSize = config.getSize();
    }

    currentSize = new AtomicInteger(0);
    shutdownLatch = new CountDownLatch(1);
    for (int i = 0; i < targetSize; i++) {
      try {
        executor.execute(new AllocateNew());
      } catch (Exception e) {
        currentSize.incrementAndGet();
        BSlot<T> slot = new BSlot<T>(live);
        slot.poison = e;
        slot.dead2live();
        live.offer(slot);
      }
    }
  }

  private class AllocateNew implements Runnable {
    @Override
    public void run() {
      BSlot<T> slot = new BSlot<T>(live);
      allocateSlot(slot);
    }
  }

  private class DeallocateAny implements Runnable {
    @Override
    public void run() {
      try {
        BSlot<T> slot;
        int observedSize;
        do {
          slot = live.poll(20, TimeUnit.MILLISECONDS);
          observedSize = currentSize.get();
          if (slot == poisonPill) {
            slot = live.poll(5, TimeUnit.MILLISECONDS);
            live.offer(poisonPill);
            // TODO this is super ugly!!!
            Thread.sleep(1); // Leave the live queue locks alone for a little while, to avoid starving other threads
          }
          // TODO maybe do more to prevent infinite looping
          // we don't want to starve other tasks in the pool
        } while (slot == null && observedSize > 0);

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
    private BSlot<T> slot;

    public Reallocate(BSlot<T> slot) {
      this.slot = slot;
    }

    @Override
    public void run() {
      deallocateSlot(slot);
      allocateSlot(slot);
    }
  }

  private class Deallocate implements Runnable {
    private BSlot<T> slot;

    public Deallocate(BSlot<T> slot) {
      this.slot = slot;
    }

    @Override
    public void run() {
      deallocateSlot(slot);
    }
  }

  private void allocateSlot(BSlot<T> slot) {
    if (currentSize.incrementAndGet() > targetSize) {
      currentSize.decrementAndGet();
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
    slot.dead2live();
    live.offer(slot);
  }

  private void deallocateSlot(BSlot<T> slot) {
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

  public T claim(Timeout timeout) throws PoolException,
      InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("timeout cannot be null");
    }
    BSlot<T> slot = tlr.get();
    // Note that the TLR slot at this point might have been tried by another
    // thread, found to be expired, put on the dead-queue and deallocated.
    // We handle this because slots always transition to the dead state before
    // they are put on the dead-queue, and if they are dead, then the
    // slot.live2claimTlr() call will fail.
    // Then we will eventually find another slot from the live-queue that we
    // can claim and make our new TLR slot.
    if (slot != null && slot.live2claimTlr()) {
      // Attempt the claim before checking the validity, because we might
      // already have claimed it.
      // If we checked validity before claiming, then we might find that it
      // had expired, and throw it in the dead queue, causing a claimed
      // Poolable to be deallocated before it is released.
      if (!isInvalid(slot)) {
        slot.incrementClaims();
        return slot.obj;
      }
      // We managed to tlr-claim the slot, but it turned out to be no good.
      // That means we now have to transition it from tlr-claimed to dead.
      // However, since we didn't pull it off of the live-queue, it might still
      // be in the live-queue. And since it might be in the live-queue, it
      // can't be put on the dead-queue. And since it can't be put on the
      // dead-queue, it also cannot transition to the dead state.
      // This effectively means that we have to transition it back to the live
      // state, and then let some pull it off of the live-queue, check it
      // again, and only then put it on the dead-queue.
      // It's cumbersome, but we have to do it this way, in order to prevent
      // duplicate entries in the queues. Otherwise we'd have a nasty memory
      // leak on our hands.
    }
    long deadline = timeout.getDeadline();
    long timeoutLeft = timeout.getTimeoutInBaseUnit();
    TimeUnit baseUnit = timeout.getBaseUnit();
    boolean notClaimed;
    for (;;) {
      slot = live.poll(timeoutLeft, baseUnit);
      if (slot == null) {
        // we timed out while taking from the queue - just return null
        return null;
      }
      if (slot == poisonPill) {
        // The poison pill means the pool has been shut down.
        // We must make sure to keep it circulating.
        live.offer(poisonPill);
        throw new IllegalStateException("pool is shut down");
      }
      // Again, attempt to claim before checking validity. We mustn't kill
      // objects that are already claimed by someone else.
      do {
        // We pulled a slot off the queue. If we can transition it to the
        // claimed state, then it means it wasn't tlr-claimed and we got it.
        // Note that the slot at this point can be in any queue.
        notClaimed = !slot.live2claim();
        // If we fail to claim it, then it means that it is tlr-claimed by
        // someone else. We know this because slots in the live-queue can only
        // be either live or tlr-claimed. There is no transition to claimed
        // or dead without first pulling the slot off the live-queue.
        // Note that the slot at this point is not in any queue, and we can't
        // put it back into the live-queue, because that could lead to
        // busy-looping on tlr-claimed slots which would waste CPU cycles when
        // the pool is depleted. We must instead make sure that tlr-claimer
        // transition to a proper claimer, such that he will make sure to
        // release the slot back into the live-queue once he is done with it.
        // However, as we are contemplating this, he might have already
        // released it again, which means that it is live and we can't make our
        // transition because it is now too late for him to put it back into
        // the live-queue. On the other hand, if the slot is now live, it means
        // that we can claim it for our selves. So we loop on this.
      } while (notClaimed && !slot.claimTlr2claim());
      // If we could not live->claimed but tlr-claimed->claimed, then
      // we mustn't check isInvalid, because that might send it to be
      // reallocated *while somebody else thinks they've TLR-claimed it!*
      // We handle this in the outer loop: if we couldn't claim, then we retry
      // the loop.
      if (notClaimed || isInvalid(slot)) {
        timeoutLeft = timeout.getTimeLeft(deadline);
        continue;
      }
      break;
    }
    //noinspection ConstantConditions
    slot.incrementClaims();
    tlr.set(slot);
    return slot.obj;
  }

  private boolean isInvalid(BSlot<T> slot) {
    checkForPoison(slot);
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
      kill(slot, new Reallocate(slot), exception);
      if (exception != null) {
        throw exception;
      }
    }
    return invalid;
  }

  private void checkForPoison(BSlot<T> slot) {
    if (slot.poison != null) {
      Exception poison = slot.poison;
      PoolException cause = new PoolException("allocation failed", poison);
      kill(slot, new Reallocate(slot), cause);
      throw cause;
    }
    if (shutdown) {
      // TODO Mutation testing not killed when removing this call.
      IllegalStateException cause = new IllegalStateException("pool is shut down");
      kill(slot, new Deallocate(slot), cause);
      throw cause;
    }
  }

  private void kill(BSlot<T> slot, Runnable action, RuntimeException cause) {
    // The use of claim2dead() here ensures that we don't put slots into the
    // dead-queue more than once. Many threads might have this as their
    // TLR-slot and try to tlr-claim it, but only when a slot has been normally
    // claimed, that is, pulled off the live-queue, can it be put into the
    // dead-queue. This helps ensure that a slot will only ever be in at most
    // one queue.
    for (;;) {
      int state = slot.getState();
      if (state == BSlot.CLAIMED && slot.claim2dead()) {
        try {
          executor.execute(action);
        } catch (Exception e) {
          // We unfortunately have to silently ignore this exception for now.
          // When we move to Java7 as a target, we can add it as a suppressed exception.
//        cause.addSuppressed(e);
          // Meanwhile, we still cannot be allowed to throw away slot objects!
          // They must remain circulating:
          slot.poison = e;
          // Never put dead slots in the live queue, or we'll get an infinite loop!
          slot.dead2live();
          live.offer(slot);
        }
        return;
      }
      if (state == BSlot.TLR_CLAIMED && slot.claimTlr2live()) {
        return;
      }
    }
  }

  public synchronized Completion shutdown() {
    if (!shutdown) {
      shutdown = true;
      live.offer(poisonPill);
      for (int i = 0; i < targetSize; i++) {
        executor.execute(new DeallocateAny());
      }
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
