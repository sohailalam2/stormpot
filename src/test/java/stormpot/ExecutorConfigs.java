package stormpot;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

public class ExecutorConfigs {

  public static ExecutorConfig cleanDefault() {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        return Config.buildDefaultExecutor();
      }
    };
  }

  public static ExecutorConfig constantly(final Executor constant) {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        return constant;
      }
    };
  }

  public static ExecutorConfig singleThreaded() {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        return Executors.newSingleThreadExecutor();
      }
    };
  }

  /**
   * Wrap the ExecutorConfig such that it keeps count of on-going tasks.
   */
  public static ExecutorConfig countingWrapper(
      final ExecutorConfig ec,
      final AtomicLong counter) {
    // Don't worry about the level of wrapping going on here... it's fine. (ugh...)
    return new ExecutorConfig() {
      public Executor configure(Executor executor) {
        final Executor delegate = ec.configure(executor);
        return new Executor() {
          public void execute(final Runnable command) {
            counter.incrementAndGet();
            delegate.execute(new Runnable() {
              public void run() {
                try {
                  command.run();
                } finally {
                  counter.decrementAndGet();
                }
              }
            });
          }
        };
      }
    };
  }

  /**
   * Reject the given number of executions before accepting anything.
   * This wrapper is not thread-safe.
   */
  public static ExecutorConfig rejectingWrapper(
      final ExecutorConfig ec,
      final int rejections) {
    // More wrapping... yay.
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        final Executor delegate = ec.configure(executor);
        return new Executor() {
          int rejectionsLeft = rejections;
          @Override
          public void execute(Runnable command) {
            if (rejectionsLeft == 0) {
              delegate.execute(command);
              return;
            }
            rejectionsLeft--;
            String message = "No soup for you! (Rejections left: " + rejectionsLeft + ")";
            throw new RejectedExecutionException(message);
          }
        };
      }
    };
  }
}
