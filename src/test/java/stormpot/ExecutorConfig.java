package stormpot;

import java.util.concurrent.Executor;

public interface ExecutorConfig {
  Executor configure(Executor executor);
}
