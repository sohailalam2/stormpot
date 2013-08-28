package stormpot;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

}
