package stormpot.qpool;

import java.util.concurrent.CountDownLatch;

import stormpot.Completion;
import stormpot.Timeout;

class LatchCompletion implements Completion {

  private final CountDownLatch latch;

  public LatchCompletion(CountDownLatch latch) {
    this.latch = latch;
  }

  @Override
  public boolean await(Timeout timeout) throws InterruptedException {
    if (timeout == null) {
      throw new IllegalArgumentException("Timeout cannot be null.");
    }
    return latch.await(timeout.getTimeout(), timeout.getUnit());
  }
}
