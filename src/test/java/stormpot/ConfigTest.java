/*
 * Copyright 2011 Chris Vest
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
package stormpot;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

public class ConfigTest {
  private Config<Poolable> config;
  
  @Before public void
  setUp() {
    config = new Config<Poolable>();
  }
  
  @Test public void
  sizeMustBeSettable() {
    config.setSize(123);
    assertTrue(config.getSize() == 123);
  }
  
  @Test public void
  allocatorMustBeSettable() {
    Allocator<?> allocator = new CountingAllocator();
    config.setAllocator(allocator);
    assertTrue(config.getAllocator() == allocator);
  }
  
  @Test public void
  mustHaveTimeBasedDeallocationRuleAsDefaul() {
    assertThat(config.getExpiration(),
        instanceOf(TimeSpreadExpiration.class));
  }
  
  @Test public void
  deallocationRuleMustBeSettable() {
    Expiration<Poolable> expectedRule = new Expiration<Poolable>() {
      public boolean hasExpired(SlotInfo<? extends Poolable> info) {
        return false;
      }
    };
    config.setExpiration(expectedRule);
    @SuppressWarnings("unchecked")
    Expiration<Poolable> actualRule =
        (Expiration<Poolable>) config.getExpiration();
    assertThat(actualRule, is(expectedRule));
  }
  
  @Test(timeout = 300) public void
  mustHaveFunctioningDefaultExecutor() throws InterruptedException {
    Executor executor = config.getExecutor();
    final AtomicBoolean flag = new AtomicBoolean(false);
    final CountDownLatch latch = new CountDownLatch(1);
    Runnable runnable = new Runnable() {
      public void run() {
        flag.set(true);
        latch.countDown();
      }
    };
    executor.execute(runnable);
    latch.await();
    assertTrue(flag.get());
  }
  
  @Test public void
  executorMustBeSettable() {
    Executor executor = new Executor() {
      public void execute(Runnable command) {}
    };
    config.setExecutor(executor);
    assertThat(config.getExecutor(), sameInstance(executor));
  }
}
