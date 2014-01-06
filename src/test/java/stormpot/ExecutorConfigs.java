package stormpot;

import java.util.concurrent.*;
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
   * The stack executor is a good demonstration that the commands can run in
   * any order.
   */
  public static ExecutorConfig stackExecutor() {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        BlockingQueue<Runnable> stackQueue = new LinkedBlockingDeque<Runnable>() {
          @Override
          public Runnable poll(long timeout, TimeUnit unit) throws InterruptedException {
            return pollLast(timeout, unit);
          }

          @Override
          public Runnable take() throws InterruptedException {
            return takeLast();
          }

          @Override
          public Runnable poll() {
            return pollLast();
          }

          @Override
          public Runnable remove() {
            return removeLast();
          }
        };
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, stackQueue);
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

  // --- These Executor configurations are too crazy to be supported:
  @Deprecated
  public static ExecutorConfig synchronousSingleThreadedExecutor() {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        BlockingQueue<Runnable> synchronousQueue = new SynchronousQueue<Runnable>();
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, synchronousQueue);
      }
    };
  }

  @Deprecated
  public static ExecutorConfig callerRunsExecutor() {
    return new ExecutorConfig() {
      @Override
      public Executor configure(Executor executor) {
        return new Executor() {
          @Override
          public void execute(Runnable command) {
            command.run();
          }
        };
      }
    };
  }
}
