/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * This class is very sensitive to the memory layout, so be careful to measure
 * the effect of even the tiniest changes!
 * False-sharing is a fickle and vengeful mistress.
 */
class BSlot<T extends Poolable> extends BSlotColdFields<T> implements Slot, SlotInfo<T> {
  // LIVING slots are ready to be claimed any time.
  static final int LIVING = 1;
  // CLAIMED slots are in active use and may be returned to the pool any time
  // CLAIMED slots are NOT in neither the live-queue nor the dead-queue.
  static final int CLAIMED = 2;
  // TLR_CLAIMED slots are also in active use, but have been claimed out of a
  // thread-local, and so is therefor also in the live-queue.
  // TLR_CLAIMED slots may at any time transition back to live - in which case
  // they are already in the live-queue. Or they may be pulled off the
  // live-queue - which case they will also transition to a normal CLAIMED.
  static final int TLR_CLAIMED = 3;
  // DEAD slots have expired and can no longer be claimed. They are put on the
  // dead-queue, and later picked up by the allocator thread for reallocation.
  // Claims might be concurrently attempted against dead slots, but these will
  // always fail.
  static final int DEAD = 4;

  public BSlot(BlockingQueue<BSlot<T>> live, BlockingQueue<BSlot<T>> dead) {
    // Volatile write in the constructor: This object must be safely published,
    // so that we are sure that the volatile write happens-before other
    // threads observe the pointer to this object.
    super(DEAD, live, dead);
  }

  @Override
  public void release(Poolable obj) {
    int slotState;
    if (expired) {
      do {
        slotState = get();
        // We loop here because TLR_CLAIMED slots can be concurrently changed
        // into normal CLAIMED slots.
      } while (!tryTransitionToDead(slotState));
    } else {
      do {
        slotState = get();
        // We loop here because TLR_CLAIMED slots can be concurrently changed
        // into normal CLAIMED slots.
      } while (!tryTransitionToLive(slotState));
    }
    if (slotState == CLAIMED) {
      if (expired) {
        dead.offer(this);
      } else {
        live.offer(this);
      }
    }
  }

  private boolean tryTransitionToLive(int slotState) {
    if (slotState == TLR_CLAIMED) {
      return claimTlr2live();
    } else if (slotState == CLAIMED) {
      return claim2live();
    }
    throw new PoolException("Slot release from bad state: " + slotState);
  }

  private boolean tryTransitionToDead(int slotState) {
    if (slotState == TLR_CLAIMED) {
      return claimTlr2dead();
    } else if (slotState == CLAIMED) {
      return claim2dead();
    }
    throw new PoolException("Slot release from bad state: " + slotState);
  }

  @Override
  public void expire(Poolable obj) {
    expired = true;
  }
  
  public boolean claim2live() {
    lazySet(LIVING);
    return true;
  }

  public boolean claimTlr2live() {
    // TODO we cannot lazySet here because we need to know if the slot was
    // concurrently transitioned to an ordinary CLAIMED state.
    // Seriously, though, if the algorithm could somehow be changed such that
    // this became a valid optimisation, then there is a HUGE win to be had
    // here. We could potentially boost the 8-threads case from 270-280 million
    // claim-release cycles per second, up to about 470 million!
//    lazySet(LIVING);
//    return true;
    return compareAndSet(TLR_CLAIMED, LIVING);
  }

  public boolean claimTlr2dead() {
    return compareAndSet(TLR_CLAIMED, DEAD);
  }
  
  public boolean live2claim() {
    return compareAndSet(LIVING, CLAIMED);
  }
  
  public boolean live2claimTlr() {
    return compareAndSet(LIVING, TLR_CLAIMED);
  }
  
  public boolean claimTlr2claim() {
    return compareAndSet(TLR_CLAIMED, CLAIMED);
  }
  
  public boolean claim2dead() {
    // TODO lazySet?
    return compareAndSet(CLAIMED, DEAD);
  }

  // Never fails
  public void dead2live() {
    lazySet(LIVING);
  }
  
  public boolean live2dead() {
    return compareAndSet(LIVING, DEAD);
  }

  @Override
  public long getAgeMillis() {
    return System.currentTimeMillis() - created;
  }

  @Override
  public long getClaimCount() {
    return claims;
  }

  @Override
  public T getPoolable() {
    return obj;
  }

  public boolean isDead() {
    return get() == DEAD;
  }
  
  public int getState() {
    return get();
  }

  public void incrementClaims() {
    claims++;
  }

  @Override
  public long getStamp() {
    return stamp;
  }

  @Override
  public void setStamp(long stamp) {
    this.stamp = stamp;
  }
}



/*
The Java Object Layout rendition:

Running 64-bit HotSpot VM.
Using compressed references with 3-bit shift.
Objects are 8 bytes aligned.
Field sizes by type: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]
Array element sizes: 4, 1, 1, 2, 2, 4, 4, 8, 8 [bytes]

stormpot.BSlot object internals:
 OFFSET  SIZE          TYPE DESCRIPTION                    VALUE
      0     4               (object header)                01 21 88 61 (0000 0001 0010 0001 1000 1000 0110 0001)
      4     4               (object header)                6c 00 00 00 (0110 1100 0000 0000 0000 0000 0000 0000)
      8     4               (object header)                8a b6 61 df (1000 1010 1011 0110 0110 0001 1101 1111)
     12     4           int Padding1.p0                    0
     16     8          long Padding1.p1                    0
     24     8          long Padding1.p2                    0
     32     8          long Padding1.p3                    0
     40     8          long Padding1.p4                    0
     48     8          long Padding1.p5                    0
     56     8          long Padding1.p6                    0
     64     4           int PaddedAtomicInteger.state      4
     68     4               (alignment/padding gap)        N/A
     72     8          long Padding2.p1                    0
     80     8          long Padding2.p2                    0
     88     8          long Padding2.p3                    0
     96     8          long Padding2.p4                    0
    104     8          long Padding2.p5                    0
    112     8          long Padding2.p6                    0
    120     8          long Padding2.p7                    0
    128     8          long BSlotColdFields.stamp          0
    136     8          long BSlotColdFields.created        0
    144     8          long BSlotColdFields.claims         0
    152     4           int BSlotColdFields.x              1818331169
    156     4           int BSlotColdFields.y              938745813
    160     4           int BSlotColdFields.z              452465366
    164     4           int BSlotColdFields.w              1343246171
    168     4 BlockingQueue BSlotColdFields.live           null
    172     4      Poolable BSlotColdFields.obj            null
    176     4     Exception BSlotColdFields.poison         null
    180     4               (loss due to the next object alignment)
Instance size: 184 bytes (estimated, add this JAR via -javaagent: to get accurate result)
Space losses: 4 bytes internal + 4 bytes external = 8 bytes total
 */
abstract class Padding1 {
  private int p0;
  private long p1, p2, p3, p4, p5, p6;
}

abstract class PaddedAtomicInteger extends Padding1 {
  private static final Unsafe unsafe;
  private static final long stateFieldOffset;
  private static final AtomicIntegerFieldUpdater<PaddedAtomicInteger> updater;

  static {
    Unsafe theUnsafe;
    long theStateFieldOffset;
    try {
      Field theUnsafeField = Unsafe.class.getDeclaredField("theUnsafe");
      theUnsafeField.setAccessible(true);
      theUnsafe = (Unsafe) theUnsafeField.get(null);
      Field stateField = PaddedAtomicInteger.class.getDeclaredField("state");
      theStateFieldOffset = theUnsafe.objectFieldOffset(stateField);
    } catch (Throwable ignore) {
      theUnsafe = null;
      theStateFieldOffset = 0;
    }
    stateFieldOffset = theStateFieldOffset;
    unsafe = theUnsafe;
    updater = AtomicIntegerFieldUpdater.newUpdater(PaddedAtomicInteger.class, "state");
  }

  private volatile int state;

  public PaddedAtomicInteger(int state) {
    this.state = state;
  }

  protected final boolean compareAndSet(int expected, int update) {
    if (unsafe != null) {
      return unsafe.compareAndSwapInt(this, stateFieldOffset, expected, update);
    } else {
      return updater.compareAndSet(this, expected, update);
    }
  }

  protected final void lazySet(int update) {
    if (unsafe != null) {
      unsafe.putOrderedInt(this, stateFieldOffset, update);
    } else {
      updater.lazySet(this, update);
    }
  }

  protected int get() {
    return state;
  }
}

abstract class Padding2 extends PaddedAtomicInteger {
  private long p1, p2, p3, p4, p5, p6, p7;

  public Padding2(int state) {
    super(state);
  }
}

abstract class BSlotColdFields<T extends Poolable> extends Padding2 implements SlotInfo<T> {
  final BlockingQueue<BSlot<T>> live;
  final BlockingQueue<BSlot<T>> dead;
  long stamp;
  long created;
  T obj;
  Exception poison;
  long claims;
  boolean expired;

  public BSlotColdFields(
      int state,
      BlockingQueue<BSlot<T>> live,
      BlockingQueue<BSlot<T>> dead) {
    super(state);
    this.live = live;
    this.dead = dead;
  }

  // XorShift PRNG with a 2^128-1 period.
  int x = System.identityHashCode(this);
  int y = -938745813;
  int z = 452465366;
  int w = 1343246171;

  @Override
  public int randomInt() {
    int t = x^(x<<15);
    //noinspection SuspiciousNameCombination
    x = y; y = z; z = w;
    return w = (w^(w>>>21))^(t^(t>>>4));
  }
}
