// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assert_;
import static com.google.devtools.build.skyframe.GraphTester.CONCATENATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.events.DelegatingErrorEventListener;
import com.google.devtools.build.lib.events.ErrorEventListener;
import com.google.devtools.build.lib.events.EventCollector;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter;
import com.google.devtools.build.lib.events.Reporter;
import com.google.devtools.build.lib.testutil.JunitTestUtils;
import com.google.devtools.build.lib.testutil.MoreAsserts;
import com.google.devtools.build.lib.testutil.TestThread;
import com.google.devtools.build.skyframe.GraphTester.SomeErrorException;
import com.google.devtools.build.skyframe.GraphTester.StringValue;
import com.google.devtools.build.skyframe.NotifyingInMemoryGraph.EventType;
import com.google.devtools.build.skyframe.NotifyingInMemoryGraph.Listener;
import com.google.devtools.build.skyframe.NotifyingInMemoryGraph.Order;
import com.google.devtools.build.skyframe.ParallelEvaluator.SkyFunctionEnvironment;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

/**
 * Tests for {@link ParallelEvaluator}.
 */
@RunWith(JUnit4.class)
public class ParallelEvaluatorTest {
  protected ProcessableGraph graph;
  protected GraphTester tester = new GraphTester();

  private EventCollector eventCollector;
  private ErrorEventListener reporter;

  private EvaluationProgressReceiver revalidationReceiver;

  @Before
  public void initializeReporter() {
    eventCollector = new EventCollector(EventKind.ALL_EVENTS);
    reporter = new Reporter(eventCollector);
  }

  private ParallelEvaluator makeEvaluator(ProcessableGraph graph,
      ImmutableMap<SkyFunctionName, ? extends SkyFunction> builders, boolean keepGoing) {
    return new ParallelEvaluator(graph, /*graphVersion=*/0L,
        builders, reporter,  new MemoizingEvaluator.EmittedEventState(), keepGoing,
        150, revalidationReceiver);
  }

  /** Convenience method for eval-ing a single value. */
  protected SkyValue eval(boolean keepGoing, SkyKey key) throws InterruptedException {
    return eval(keepGoing, ImmutableList.of(key)).get(key);
  }

  protected ErrorInfo evalValueInError(SkyKey key) throws InterruptedException {
    return eval(true, ImmutableList.of(key)).getError(key);
  }

  protected <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, SkyKey... keys)
      throws InterruptedException {
    return eval(keepGoing, ImmutableList.copyOf(keys));
  }

  protected <T extends SkyValue> EvaluationResult<T> eval(boolean keepGoing, Iterable<SkyKey> keys)
      throws InterruptedException {
    ParallelEvaluator evaluator = makeEvaluator(graph,
        ImmutableMap.of(GraphTester.NODE_TYPE, tester.createDelegatingFunction()),
        keepGoing);
    return evaluator.eval(keys);
  }

  protected GraphTester.TestFunction set(String name, String value) {
    return tester.set(name, new StringValue(value));
  }

  @Test
  public void smoke() throws Exception {
    graph = new InMemoryGraph();
    set("a", "a");
    set("b", "b");
    tester.getOrCreate("ab").addDependency("a").addDependency("b").setComputedValue(CONCATENATE);
    StringValue value = (StringValue) eval(false, GraphTester.toSkyKey("ab"));
    assertEquals("ab", value.getValue());
    JunitTestUtils.assertNoEvents(eventCollector);
  }

  /**
   * Test interruption handling when a long-running SkyFunction gets interrupted.
   */
  @Test
  public void interruptedFunction() throws Exception {
    runInterruptionTest(new SkyFunctionFactory() {
      @Override
      public SkyFunction create(final Semaphore threadStarted, final String[] errorMessage) {
        return new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey key, Environment env) throws InterruptedException {
            // Signal the waiting test thread that the evaluator thread has really started.
            threadStarted.release();

            // Simulate a SkyFunction that runs for 10 seconds (this number was chosen arbitrarily).
            // The main thread should interrupt it shortly after it got started.
            Thread.sleep(10 * 1000);

            // Set an error message to indicate that the expected interruption didn't happen.
            // We can't use Assert.fail(String) on an async thread.
            errorMessage[0] = "SkyFunction should have been interrupted";
            return null;
          }

          @Nullable
          @Override
          public String extractTag(SkyKey skyKey) {
            return null;
          }
        };
      }
    });
  }

  /**
   * Test interruption handling when the Evaluator is in-between running SkyFunctions.
   *
   * <p>This is the point in time after a SkyFunction requested a dependency which is not yet built
   * so the builder returned null to the Evaluator, and the latter is about to schedule evaluation
   * of the missing dependency but gets interrupted before the dependency's SkyFunction could start.
   */
  @Test
  public void interruptedEvaluatorThread() throws Exception {
    runInterruptionTest(new SkyFunctionFactory() {
      @Override
      public SkyFunction create(final Semaphore threadStarted, final String[] errorMessage) {
        return new SkyFunction() {
          // No need to synchronize access to this field; we always request just one more
          // dependency, so it's only one SkyFunction running at any time.
          private int valueIdCounter = 0;

          @Override
          public SkyValue compute(SkyKey key, Environment env) {
            // Signal the waiting test thread that the Evaluator thread has really started.
            threadStarted.release();

            // Keep the evaluator busy until the test's thread gets scheduled and can
            // interrupt the Evaluator's thread.
            env.getValue(GraphTester.toSkyKey("a" + valueIdCounter++));

            // This method never throws InterruptedException, therefore it's the responsibility
            // of the Evaluator to detect the interrupt and avoid calling subsequent SkyFunctions.
            return null;
          }

          @Nullable
          @Override
          public String extractTag(SkyKey skyKey) {
            return null;
          }
        };
      }
    });
  }

  private void runPartialResultOnInterruption(boolean buildFastFirst) throws Exception {
    graph = new InMemoryGraph();
    // Two runs for fastKey's builder and one for the start of waitKey's builder.
    final CountDownLatch allValuesReady = new CountDownLatch(3);
    final SkyKey waitKey = GraphTester.toSkyKey("wait");
    final SkyKey fastKey = GraphTester.toSkyKey("fast");
    SkyKey leafKey = GraphTester.toSkyKey("leaf");
    tester.getOrCreate(waitKey).setBuilder(new SkyFunction() {
          @Override
          public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
            allValuesReady.countDown();
            Thread.sleep(10000);
            throw new AssertionError("Should have been interrupted");
          }

          @Override
          public String extractTag(SkyKey skyKey) {
            return null;
          }
        });
    tester.getOrCreate(fastKey).setBuilder(new ChainedFunction(null, null, allValuesReady, false,
        new StringValue("fast"), ImmutableList.of(leafKey)));
    tester.set(leafKey, new StringValue("leaf"));
    if (buildFastFirst) {
      eval(/*keepGoing=*/false, fastKey);
    }
    final Set<SkyKey> receivedValues = Sets.newConcurrentHashSet();
    revalidationReceiver = new EvaluationProgressReceiver() {
      @Override
      public void invalidated(SkyValue value, InvalidationState state) {}

      @Override
      public void enqueueing(SkyKey key) {}

      @Override
      public void evaluated(SkyKey skyKey, SkyValue value, EvaluationState state) {
        receivedValues.add(skyKey);
      }
    };
    TestThread evalThread = new TestThread() {
      @Override
      public void runTest() throws Exception {
        try {
          eval(/*keepGoing=*/true, waitKey, fastKey);
          fail();
        } catch (InterruptedException e) {
          // Expected.
        }
      }
    };
    evalThread.start();
    assertTrue(allValuesReady.await(2, TimeUnit.SECONDS));
    evalThread.interrupt();
    evalThread.join(5000);
    assertFalse(evalThread.isAlive());
    if (buildFastFirst) {
      // If leafKey was already built, it is not reported to the receiver.
      MoreAsserts.assertContentsAnyOrder(receivedValues, fastKey);
    } else {
      // On first time being built, leafKey is registered too.
      MoreAsserts.assertContentsAnyOrder(receivedValues, fastKey, leafKey);
    }
  }

  @Test
  public void partialResultOnInterruption() throws Exception {
    runPartialResultOnInterruption(/*buildFastFirst=*/false);
  }

  @Test
  public void partialCachedResultOnInterruption() throws Exception {
    runPartialResultOnInterruption(/*buildFastFirst=*/true);
  }

  /**
   * Factory for SkyFunctions for interruption testing (see {@link #runInterruptionTest}).
   */
  private interface SkyFunctionFactory {
    /**
     * Creates a SkyFunction suitable for a specific test scenario.
     *
     * @param threadStarted a latch which the returned SkyFunction must
     *     {@link Semaphore#release() release} once it started (otherwise the test won't work)
     * @param errorMessage a single-element array; the SkyFunction can put a error message in it
     *     to indicate that an assertion failed (calling {@code fail} from async thread doesn't
     *     work)
     */
    SkyFunction create(final Semaphore threadStarted, final String[] errorMessage);
  }

  /**
   * Test that we can handle the Evaluator getting interrupted at various points.
   *
   * <p>This method creates an Evaluator with the specified SkyFunction for GraphTested.NODE_TYPE,
   * then starts a thread, requests evaluation and asserts that evaluation started. It then
   * interrupts the Evaluator thread and asserts that it acknowledged the interruption.
   *
   * @param valueBuilderFactory creates a SkyFunction which may or may not handle interruptions
   *     (depending on the test)
   */
  private void runInterruptionTest(SkyFunctionFactory valueBuilderFactory) throws Exception {
    final Semaphore threadStarted = new Semaphore(0);
    final Semaphore threadInterrupted = new Semaphore(0);
    final String[] wasError = new String[] { null };
    final ParallelEvaluator evaluator = makeEvaluator(new InMemoryGraph(),
        ImmutableMap.of(GraphTester.NODE_TYPE, valueBuilderFactory.create(threadStarted, wasError)),
        false);

    Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            evaluator.eval(ImmutableList.of(GraphTester.toSkyKey("a")));

            // There's no real need to set an error here. If the thread is not interrupted then
            // threadInterrupted is not released and the test thread will fail to acquire it.
            wasError[0] = "evaluation should have been interrupted";
          } catch (InterruptedException e) {
            // This is the interrupt we are waiting for. It should come straight from the
            // evaluator (more precisely, the AbstractQueueVisitor).
            // Signal the waiting test thread that the interrupt was acknowledged.
            threadInterrupted.release();
          }
        }
    });

    // Start the thread and wait for a semaphore. This ensures that the thread was really started.
    // The timeout guarantees the test won't hang in case the thread can't start for some reason.
    // (The timeout was chosen arbitrarily; 500 ms looks like it should be enough.)
    t.start();
    assertTrue(threadStarted.tryAcquire(500, TimeUnit.MILLISECONDS));

    // Interrupt the thread and wait for a semaphore. This ensures that the thread was really
    // interrupted and this fact was acknowledged.
    // The timeout guarantees the test won't hang in case the thread can't acknowledge the
    // interruption for some reason.
    // (The timeout was chosen arbitrarily; 500 ms looks like it should be enough.)
    t.interrupt();
    assertTrue(threadInterrupted.tryAcquire(500, TimeUnit.MILLISECONDS));

    // The SkyFunction may have reported an error.
    if (wasError[0] != null) {
      fail(wasError[0]);
    }

    // Wait for the thread to finish.
    // (The timeout was chosen arbitrarily; 500 ms looks like it should be enough.)
    t.join(500);
  }

  @Test
  public void unrecoverableError() throws Exception {
    class CustomRuntimeException extends RuntimeException {}
    final CustomRuntimeException expected = new CustomRuntimeException();

    final SkyFunction builder = new SkyFunction() {
      @Override
      @Nullable
      public SkyValue compute(SkyKey skyKey, Environment env)
          throws SkyFunctionException, InterruptedException {
        throw expected;
      }

      @Override
      @Nullable
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    };

    final ParallelEvaluator evaluator = makeEvaluator(new InMemoryGraph(),
        ImmutableMap.of(GraphTester.NODE_TYPE, builder),
        false);

    SkyKey valueToEval = GraphTester.toSkyKey("a");
    try {
      evaluator.eval(ImmutableList.of(valueToEval));
    } catch (RuntimeException re) {
      assertTrue(re.getMessage()
          .contains("Unrecoverable error while evaluating node '" + valueToEval.toString() + "'"));
      assertTrue(re.getCause() instanceof CustomRuntimeException);
    }
  }

  @Test
  public void simpleWarning() throws Exception {
    graph = new InMemoryGraph();
    set("a", "a").setWarning("warning on 'a'");
    StringValue value = (StringValue) eval(false, GraphTester.toSkyKey("a"));
    assertEquals("a", value.getValue());
    JunitTestUtils.assertContainsEvent(eventCollector, "warning on 'a'");
    JunitTestUtils.assertEventCount(1, eventCollector);
  }

  @Test
  public void warningMatchesRegex() throws Exception {
    graph = new InMemoryGraph();
    ((Reporter) reporter).setOutputFilter(RegexOutputFilter.forRegex("a"));
    set("example", "a value").setWarning("warning message");
    SkyKey a = GraphTester.toSkyKey("example");
    tester.getOrCreate(a).setTag("a");
    StringValue value = (StringValue) eval(false, a);
    assertEquals("a value", value.getValue());
    JunitTestUtils.assertContainsEvent(eventCollector, "warning message");
    JunitTestUtils.assertEventCount(1, eventCollector);
  }

  @Test
  public void warningMatchesRegexOnlyTag() throws Exception {
    graph = new InMemoryGraph();
    ((Reporter) reporter).setOutputFilter(RegexOutputFilter.forRegex("a"));
    set("a", "a value").setWarning("warning on 'a'");
    SkyKey a = GraphTester.toSkyKey("a");
    tester.getOrCreate(a).setTag("b");
    StringValue value = (StringValue) eval(false, a);
    assertEquals("a value", value.getValue());
    JunitTestUtils.assertEventCount(0, eventCollector);  }

  @Test
  public void warningDoesNotMatchRegex() throws Exception {
    graph = new InMemoryGraph();
    ((Reporter) reporter).setOutputFilter(RegexOutputFilter.forRegex("b"));
    set("a", "a").setWarning("warning on 'a'");
    SkyKey a = GraphTester.toSkyKey("a");
    tester.getOrCreate(a).setTag("a");
    StringValue value = (StringValue) eval(false, a);
    assertEquals("a", value.getValue());
    JunitTestUtils.assertEventCount(0, eventCollector);
  }

  /** Regression test: events from already-done value not replayed. */
  @Test
  public void eventFromDoneChildRecorded() throws Exception {
    graph = new InMemoryGraph();
    set("a", "a").setWarning("warning on 'a'");
    SkyKey a = GraphTester.toSkyKey("a");
    SkyKey top = GraphTester.toSkyKey("top");
    tester.getOrCreate(top).addDependency(a).setComputedValue(CONCATENATE);
    // Build a so that it is already in the graph.
    eval(false, a);
    JunitTestUtils.assertEventCount(1, eventCollector);
    eventCollector.clear();
    // Build top. The warning from a should be reprinted.
    eval(false, top);
    JunitTestUtils.assertEventCount(1, eventCollector);
    eventCollector.clear();
    // Build top again. The warning should have been stored in the value.
    eval(false, top);
    JunitTestUtils.assertEventCount(1, eventCollector);
  }

  @Test
  public void shouldCreateErrorValueWithRootCause() throws Exception {
    graph = new InMemoryGraph();
    set("a", "a");
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(parentErrorKey).addDependency("a").addDependency(errorKey)
    .setComputedValue(CONCATENATE);
    tester.getOrCreate(errorKey).setHasError(true);
    ErrorInfo error = evalValueInError(parentErrorKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
  }

  @Test
  public void shouldBuildOneTarget() throws Exception {
    graph = new InMemoryGraph();
    set("a", "a");
    set("b", "b");
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    SkyKey errorFreeKey = GraphTester.toSkyKey("ab");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(parentErrorKey).addDependency(errorKey).addDependency("a")
    .setComputedValue(CONCATENATE);
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate(errorFreeKey).addDependency("a").addDependency("b")
    .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(true, parentErrorKey, errorFreeKey);
    ErrorInfo error = result.getError(parentErrorKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
    StringValue abValue = result.get(errorFreeKey);
    assertEquals("ab", abValue.getValue());
  }

  @Test
  public void catastropheHaltsKeepGoingBuild() throws Exception {
    catastrophicBuild(true);
  }

  @Test
  public void catastropheInFailFastBuild() throws Exception {
    catastrophicBuild(false);
  }

  private void catastrophicBuild(boolean keepGoing) throws Exception {
    graph = new InMemoryGraph();

    SkyKey catastropheKey = GraphTester.toSkyKey("catastrophe");
    SkyKey otherKey = GraphTester.toSkyKey("someKey");

    tester.getOrCreate(catastropheKey).setBuilder(new SkyFunction() {
      @Nullable
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
        throw new SkyFunctionException(skyKey, new Exception()) {
          @Override
          public boolean isCatastrophic() {
            return true;
          }
        };
      }

      @Nullable
      @Override
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    });

    tester.getOrCreate(otherKey).setBuilder(new SkyFunction() {
      @Nullable
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
        new CountDownLatch(1).await();
        throw new RuntimeException("can't get here");
      }

      @Nullable
      @Override
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    });
    EvaluationResult<StringValue> result = eval(keepGoing, catastropheKey, otherKey);
    ErrorInfo error = result.getError(catastropheKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), catastropheKey);
  }

  @Test
  public void parentFailureDoesntAffectChild() throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).setHasError(true);
    SkyKey childKey = GraphTester.toSkyKey("child");
    set("child", "onions");
    tester.getOrCreate(parentKey).addDependency(childKey).setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, parentKey, childKey);
    // Child is guaranteed to complete successfully before parent can run (and fail),
    // since parent depends on it.
    StringValue childValue = result.get(childKey);
    Assert.assertNotNull(childValue);
    assertEquals("onions", childValue.getValue());
    ErrorInfo error = result.getError(parentKey);
    Assert.assertNotNull(error);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), parentKey);
  }

  @Test
  public void newParentOfErrorShouldHaveError() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    ErrorInfo error = evalValueInError(errorKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency("error").setComputedValue(CONCATENATE);
    error = evalValueInError(parentKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
  }

  @Test
  public void errorTwoLevelsDeep() throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(CONCATENATE);
    ErrorInfo error = evalValueInError(parentKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
  }

  /**
   * A recreation of BuildViewTest#testHasErrorRaceCondition.  Also similar to errorTwoLevelsDeep,
   * except here we request multiple toplevel values.
   */
  @Test
  public void errorPropagationToTopLevelValues() throws Exception {
    graph = new InMemoryGraph();
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey badKey = GraphTester.toSkyKey("bad");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);
    EvaluationResult<SkyValue> result = eval(/*keepGoing=*/false, topKey, midKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(midKey).getRootCauses(), badKey);
    // Do it again with keepGoing.  We should also see an error for the top key this time.
    result = eval(/*keepGoing=*/true, topKey, midKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(midKey).getRootCauses(), badKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(topKey).getRootCauses(), badKey);
  }

  @Test
  public void valueNotUsedInFailFastErrorRecovery() throws Exception {
    graph = new InMemoryGraph();
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey recoveryKey = GraphTester.toSkyKey("midRecovery");
    SkyKey badKey = GraphTester.toSkyKey("bad");

    tester.getOrCreate(topKey).addDependency(recoveryKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(recoveryKey).addErrorDependency(badKey, new StringValue("i recovered"))
        .setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);

    EvaluationResult<SkyValue> result = eval(/*keepGoing=*/true, ImmutableList.of(recoveryKey));
    assertThat(result.errorMap()).isEmpty();
    assertTrue(result.hasError());
    assertEquals(new StringValue("i recovered"), result.get(recoveryKey));

    result = eval(/*keepGoing=*/false, ImmutableList.of(topKey));
    assertTrue(result.hasError());
    assertThat(result.keyNames()).isEmpty();
    assertEquals(1, result.errorMap().size());
    assertNotNull(result.getError(topKey).getException());
  }

  /**
   * Regression test: "clearing incomplete values on --keep_going build is racy".
   * Tests that if a value is requested on the first (non-keep-going) build and its child throws
   * an error, when the second (keep-going) build runs, there is not a race that keeps it as a
   * reverse dep of its children.
   */
  @Test
  public void raceClearingIncompleteValues() throws Exception {
    SkyKey topKey = GraphTester.toSkyKey("top");
    final SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey badKey = GraphTester.toSkyKey("bad");
    final AtomicBoolean waitForSecondCall = new AtomicBoolean(false);
    final TrackingAwaiter trackingAwaiter = new TrackingAwaiter();
    final CountDownLatch otherThreadWinning = new CountDownLatch(1);
    final AtomicReference<Thread> firstThread = new AtomicReference<>();
    graph = new NotifyingInMemoryGraph(new Listener() {
      @Override
      public void accept(SkyKey key, EventType type, Order order, Object context) {
        if (!waitForSecondCall.get()) {
          return;
        }
        if (key.equals(midKey)) {
          if (type == EventType.CREATE_IF_ABSENT) {
            // The first thread to create midKey will not be the first thread to add a reverse dep
            // to it.
            firstThread.compareAndSet(null, Thread.currentThread());
            return;
          }
          if (type == EventType.ADD_REVERSE_DEP) {
            if (order == Order.BEFORE && Thread.currentThread().equals(firstThread.get())) {
              // If this thread created midKey, block until the other thread adds a dep on it.
              trackingAwaiter.awaitLatchAndTrackExceptions(otherThreadWinning,
                  "other thread didn't pass this one");
            } else if (order == Order.AFTER && !Thread.currentThread().equals(firstThread.get())) {
              // This thread has added a dep. Allow the other thread to proceed.
              otherThreadWinning.countDown();
            }
          }
        }
      }
    });
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(badKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(badKey).setHasError(true);
    EvaluationResult<SkyValue> result = eval(/*keepGoing=*/false, topKey, midKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(midKey).getRootCauses(), badKey);
    waitForSecondCall.set(true);
    result = eval(/*keepGoing=*/true, topKey, midKey);
    trackingAwaiter.assertNoErrors();
    assertNotNull(firstThread.get());
    assertEquals(0, otherThreadWinning.getCount());
    MoreAsserts.assertContentsAnyOrder(result.getError(midKey).getRootCauses(), badKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(topKey).getRootCauses(), badKey);
  }

  /**
   * Regression test: IllegalStateException on hasInflightParent check.
   * A parent value may be signaled by its child, realize it is done, restart itself, and build, all
   * before the child actually throws an exception and stops the threadpool. To test this, we have
   * the following sequence, with the value top depending on error and on the many values [deps]:
   * <pre>
   * 0.  [deps] are all built (before the main evaluation).
   * 1.  top requests its children.
   * 2.  top's builder exits, and ParallelEvaluator registers all of top's new deps.
   * 2'. (Concurrent with 2.) Since it was requested by top, error builds and throws an exception,
   *     as well as outputting a warning.
   * 3'. error is written to the graph, signaling top that it is done. If top is still engaged in
   *     registering its deps, this signal will make top ready when it finishes registering them,
   *     causing top to restart its builder.
   * 4'. reporter is called to output error's warning. It blocks on top's builder's second run. If
   *     error signaled top after top had finished, this second run will not happen, and so the
   *     reporter will give up. In that case, this run did not exercise the desired codepath, and so
   *     it will be run again. In testing, this happened approximately 1% of the time.
   * 5.  top's builder restarts, and blocks on the threadpool catching an exception.
   * 6.  reporter finishes. ParallelEvaluator throws a SchedulerException.
   * 7.  top finishes. Its value is written to the graph, but the SchedulerException thrown by
   *     ParallelEvaluator on its behalf is ignored, since the threadpool is shutting down.
   * 8.  The exception thrown by error is bubbled up the graph.
   * </pre>
   *
   * A time diagram (time flows down, and 2', 3', 4' take place concurrently with 2):
   * <pre>
   *                         0
   *                         |
   *                         1
   *                         |
   *                         2- -2'
   *                         |   |
   *                         |   3'
   *                         |   |
   *                         5   4'
   *                         \   /
   *                           6
   *                           |
   *                           7
   *                           |
   *                           8
   * </pre>
   */
  @Test
  public void slowChildCleanup() throws Exception {
    // Value to be built. It will be signaled to restart its builder before it has finished
    // registering its deps.
    final SkyKey top = GraphTester.toSkyKey("top");
    // Dep that blocks before it acknowledges being added as a dep by top, so the errorKey value has
    // time to signal top.
    final SkyKey slowAddingDep = GraphTester.toSkyKey("slowAddingDep");
    final StringValue depValue = new StringValue("dep");
    tester.set(slowAddingDep, depValue);
    final CountDownLatch topRestartedBuild = new CountDownLatch(1);
    final CountDownLatch topSignaled = new CountDownLatch(1);
    final TrackingAwaiter trackingAwaiter = new TrackingAwaiter();
    final AtomicBoolean topBuilderEntered = new AtomicBoolean(false);
    graph = new NotifyingInMemoryGraph(new Listener() {
      @Override
      public void accept(SkyKey key, EventType type, Order order, Object context) {
        if (key.equals(top) && type == EventType.SIGNAL && order == Order.AFTER) {
          // top is signaled by errorKey (since slowAddingDep is blocking), so slowAddingDep is now
          // free to acknowledge top as a parent.
          topSignaled.countDown();
          return;
        }
        if (key.equals(slowAddingDep) && type == EventType.ADD_REVERSE_DEP
            && top.equals(context) && order == Order.BEFORE) {
          // If top is trying to declare a dep on slowAddingDep, wait until errorKey has signaled
          // top. Then this add dep will return DONE and top will be signaled, making it ready, so
          // it will be enqueued.
          trackingAwaiter.awaitLatchAndTrackExceptions(topSignaled,
              "error key didn't signal top in time");
        }
      }
    });
    // Value that will throw an error when it is built, but will wait to actually throw the error
    // until top's builder restarts. We enforce the wait by having the listener to which it reports
    // its warning block until top's builder restarts.
    final SkyKey errorKey = GraphTester.skyKey("error");
    String warningText = "warning text";
    tester.getOrCreate(errorKey).setHasError(true).setWarning(warningText);
    // On its first run, top's builder just requests both its deps. On its second run, the builder
    // waits for an exception to have been thrown (by errorKey) and then returns.
    SkyFunction topBuilder = new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey key, SkyFunction.Environment env) {
        // The reporter will be given errorKey's warning to emit when it is requested as a dep
        // below, if errorKey is already built, so we release the reporter's latch beforehand.
        boolean firstTime = topBuilderEntered.compareAndSet(false, true);
        if (!firstTime) {
          topRestartedBuild.countDown();
        }
        assertNull(env.getValue(errorKey));
        assertEquals(depValue, env.getValue(slowAddingDep));
        if (firstTime) {
          return null;
        }
        SkyFunctionEnvironment skyEnv = (SkyFunctionEnvironment) env;
        trackingAwaiter.awaitLatchAndTrackExceptions(skyEnv.getExceptionLatchForTesting(),
            "top did not get exception in time");
        return null;
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        return skyKey.toString();
      }
    };
    reporter = new DelegatingErrorEventListener(reporter) {
      @Override
      public void warn(Location location, String message) {
        super.warn(location, message);
        trackingAwaiter.awaitLatchAndTrackExceptions(topRestartedBuild,
            "top's builder did not restart in time");
      }
    };
    tester.getOrCreate(top).setBuilder(topBuilder);
    // Make sure slowAddingDep is already in the graph, so it will be DONE.
    eval(/*keepGoing=*/false, slowAddingDep);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/false, ImmutableList.of(top));
    assertThat(result.keyNames()).isEmpty(); // No successfully evaluated values.
    ErrorInfo errorInfo = result.getError(top);
    MoreAsserts.assertContentsAnyOrder(errorInfo.getRootCauses(), errorKey);
    JunitTestUtils.assertContainsEvent(eventCollector, warningText);
    assertTrue(topBuilderEntered.get());
    trackingAwaiter.assertNoErrors();
    assertEquals(0, topRestartedBuild.getCount());
    assertEquals(0, topSignaled.getCount());
  }

  @Test
  public void multipleRootCauses() throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey errorKey2 = GraphTester.toSkyKey("error2");
    SkyKey errorKey3 = GraphTester.toSkyKey("error3");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate(errorKey2).setHasError(true);
    tester.getOrCreate(errorKey3).setHasError(true);
    tester.getOrCreate("mid").addDependency(errorKey).addDependency(errorKey2)
      .setComputedValue(CONCATENATE);
    tester.getOrCreate(parentKey)
      .addDependency("mid").addDependency(errorKey2).addDependency(errorKey3)
      .setComputedValue(CONCATENATE);
    ErrorInfo error = evalValueInError(parentKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(),
        errorKey, errorKey2, errorKey3);
  }

  @Test
  public void rootCauseWithNoKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.getOrCreate("mid").addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(parentKey).addDependency("mid").setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(parentKey));
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), errorKey);
  }

  @Test
  public void errorBubblesToParentsOfTopLevelValue() throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final SkyKey errorKey = GraphTester.toSkyKey("error");
    final CountDownLatch latch = new CountDownLatch(1);
    tester.getOrCreate(errorKey).setBuilder(new ChainedFunction(null, /*waitToFinish=*/latch, null,
        false, /*value=*/null, ImmutableList.<SkyKey>of()));
    tester.getOrCreate(parentKey).setBuilder(new ChainedFunction(/*notifyStart=*/latch, null, null,
        false, new StringValue("unused"), ImmutableList.of(errorKey)));
    EvaluationResult<StringValue> result = eval( /*keepGoing=*/false,
        ImmutableList.of(parentKey, errorKey));
    assertEquals(result.toString(), 2, result.errorMap().size());
  }

  @Test
  public void noKeepGoingAfterKeepGoingFails() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency(errorKey);
    ErrorInfo error = evalValueInError(parentKey);
    MoreAsserts.assertContentsAnyOrder(error.getRootCauses(), errorKey);
    SkyKey[] list = { parentKey };
    EvaluationResult<StringValue> result = eval(false, list);
    ErrorInfo errorInfo = result.getError();
    assertEquals(errorKey, Iterables.getOnlyElement(errorInfo.getRootCauses()));
    assertEquals(errorKey.toString(), errorInfo.getException().getMessage());
  }

  @Test
  public void twoErrors() throws Exception {
    graph = new InMemoryGraph();
    SkyKey firstError = GraphTester.toSkyKey("error1");
    SkyKey secondError = GraphTester.toSkyKey("error2");
    CountDownLatch firstStart = new CountDownLatch(1);
    CountDownLatch secondStart = new CountDownLatch(1);
    tester.getOrCreate(firstError).setBuilder(new ChainedFunction(firstStart, secondStart,
        /*notifyFinish=*/null, /*waitForException=*/false, /*value=*/null,
        ImmutableList.<SkyKey>of()));
    tester.getOrCreate(secondError).setBuilder(new ChainedFunction(secondStart, firstStart,
        /*notifyFinish=*/null, /*waitForException=*/false, /*value=*/null,
        ImmutableList.<SkyKey>of()));
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/false, firstError, secondError);
    assertTrue(result.toString(), result.hasError());
    assertNotNull(result.toString(), result.getError(firstError));
    assertNotNull(result.toString(), result.getError(secondError));
    assertNotNull(result.toString(), result.getError());
  }

  @Test
  public void simpleCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(aKey)).getError();
    assertEquals(null, errorInfo.getException());
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), aKey, bKey);
    assertTrue(cycleInfo.getPathToCycle().isEmpty());
  }

  @Test
  public void cycleWithHead() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(topKey)).getError();
    assertEquals(null, errorInfo.getException());
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), aKey, bKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), topKey, midKey);
  }

  @Test
  public void selfEdgeWithHead() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(aKey);
    ErrorInfo errorInfo = eval(false, ImmutableList.of(topKey)).getError();
    assertEquals(null, errorInfo.getException());
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), aKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), topKey, midKey);
  }

  @Test
  public void cycleWithKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey goodKey = GraphTester.toSkyKey("good");
    StringValue goodValue = new StringValue("good");
    tester.set(goodKey, goodValue);
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(midKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    EvaluationResult<StringValue> result = eval(true, topKey, goodKey);
    assertEquals(goodValue, result.get(goodKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), aKey, bKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), topKey, midKey);
  }

  @Test
  public void twoCycles() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    tester.getOrCreate(cKey).addDependency(dKey);
    tester.getOrCreate(dKey).addDependency(cKey);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    Iterable<CycleInfo> cycles = CycleInfo.prepareCycles(topKey,
        ImmutableList.of(new CycleInfo(ImmutableList.of(aKey, bKey)),
        new CycleInfo(ImmutableList.of(cKey, dKey))));
    MoreAsserts.assertContains(cycles, Iterables.getOnlyElement(errorInfo.getCycleInfo()));
  }


  @Test
  public void twoCyclesKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey);
    tester.getOrCreate(cKey).addDependency(dKey);
    tester.getOrCreate(dKey).addDependency(cKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo aCycle = new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(aKey, bKey));
    CycleInfo cCycle = new CycleInfo(ImmutableList.of(topKey), ImmutableList.of(cKey, dKey));
    MoreAsserts.assertContentsAnyOrder(errorInfo.getCycleInfo(), aCycle, cCycle);
  }

  @Test
  public void triangleBelowHeadCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey).addDependency(cKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(topKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo topCycle = new CycleInfo(ImmutableList.of(topKey, aKey, cKey));
    MoreAsserts.assertContentsAnyOrder(errorInfo.getCycleInfo(), topCycle);
  }

  @Test
  public void longCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(topKey);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo topCycle = new CycleInfo(ImmutableList.of(topKey, aKey, bKey, cKey));
    MoreAsserts.assertContentsAnyOrder(errorInfo.getCycleInfo(), topCycle);
  }

  @Test
  public void cycleWithTail() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(aKey).addDependency(cKey);
    tester.getOrCreate(cKey);
    tester.set(cKey, new StringValue("cValue"));
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    ErrorInfo errorInfo = result.getError(topKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), aKey, bKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), topKey);
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void selfEdgeWithExtraChildrenUnderCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey).addDependency(bKey);
    tester.getOrCreate(cKey).addDependency(aKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(aKey));
    assertEquals(null, result.get(aKey));
    ErrorInfo errorInfo = result.getError(aKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), bKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), aKey);
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void cycleWithExtraChildrenUnderCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    SkyKey dKey = GraphTester.toSkyKey("d");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey).addDependency(dKey);
    tester.getOrCreate(cKey).addDependency(aKey);
    tester.getOrCreate(dKey).addDependency(bKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(aKey));
    assertEquals(null, result.get(aKey));
    ErrorInfo errorInfo = result.getError(aKey);
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), bKey, dKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), aKey);
  }

  /** Regression test: "value cannot be ready in a cycle". */
  @Test
  public void cycleAboveIndependentCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey bKey = GraphTester.toSkyKey("b");
    SkyKey cKey = GraphTester.toSkyKey("c");
    tester.getOrCreate(aKey).addDependency(bKey);
    tester.getOrCreate(bKey).addDependency(cKey);
    tester.getOrCreate(cKey).addDependency(aKey).addDependency(bKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(aKey));
    assertEquals(null, result.get(aKey));
    MoreAsserts.assertContentsAnyOrder(result.getError(aKey).getCycleInfo(),
        new CycleInfo(ImmutableList.of(aKey, bKey, cKey)),
        new CycleInfo(ImmutableList.of(aKey), ImmutableList.of(bKey, cKey)));
 }

  public void valueAboveCycleAndExceptionReportsException() throws Exception {
    graph = new InMemoryGraph();
    SkyKey aKey = GraphTester.toSkyKey("a");
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey bKey = GraphTester.toSkyKey("b");
    tester.getOrCreate(aKey).addDependency(bKey).addDependency(errorKey);
    tester.getOrCreate(bKey).addDependency(bKey);
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(aKey));
    assertEquals(null, result.get(aKey));
    assertNotNull(result.getError(aKey).getException());
    CycleInfo cycleInfo = Iterables.getOnlyElement(result.getError(aKey).getCycleInfo());
    MoreAsserts.assertContentsInOrder(cycleInfo.getCycle(), bKey);
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle(), aKey);
  }

  @Test
  public void errorValueStored() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(false, ImmutableList.of(errorKey));
    assertThat(result.keyNames()).isEmpty();
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), errorKey);
    ErrorInfo errorInfo = result.getError();
    MoreAsserts.assertContentsAnyOrder(errorInfo.getRootCauses(), errorKey);
    // Update value. But builder won't rebuild it.
    tester.getOrCreate(errorKey).setHasError(false);
    tester.set(errorKey, new StringValue("no error?"));
    result = eval(false, ImmutableList.of(errorKey));
    assertThat(result.keyNames()).isEmpty();
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), errorKey);
    errorInfo = result.getError();
    MoreAsserts.assertContentsAnyOrder(errorInfo.getRootCauses(), errorKey);
  }

  /**
   * Regression test: "OOM in Skyframe cycle detection".
   * We only store the first 20 cycles found below any given root value.
   */
  @Test
  public void manyCycles() throws Exception {
    graph = new InMemoryGraph();
    SkyKey topKey = GraphTester.toSkyKey("top");
    for (int i = 0; i < 100; i++) {
      SkyKey dep = GraphTester.toSkyKey(Integer.toString(i));
      tester.getOrCreate(topKey).addDependency(dep);
      tester.getOrCreate(dep).addDependency(dep);
    }
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    assertManyCycles(result.getError(topKey), topKey, /*selfEdge=*/false);
  }

  /**
   * Regression test: "OOM in Skyframe cycle detection".
   * We filter out multiple paths to a cycle that go through the same child value.
   */
  @Test
  public void manyPathsToCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    tester.getOrCreate(topKey).addDependency(midKey);
    tester.getOrCreate(cycleKey).addDependency(cycleKey);
    for (int i = 0; i < 100; i++) {
      SkyKey dep = GraphTester.toSkyKey(Integer.toString(i));
      tester.getOrCreate(midKey).addDependency(dep);
      tester.getOrCreate(dep).addDependency(cycleKey);
    }
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(topKey));
    assertEquals(null, result.get(topKey));
    CycleInfo cycleInfo = Iterables.getOnlyElement(result.getError(topKey).getCycleInfo());
    assertEquals(1, cycleInfo.getCycle().size());
    assertEquals(3, cycleInfo.getPathToCycle().size());
    MoreAsserts.assertContentsInOrder(cycleInfo.getPathToCycle().subList(0, 2),
        topKey, midKey);
  }

  /**
   * Checks that errorInfo has many self-edge cycles, and that one of them is a self-edge of
   * topKey, if {@code selfEdge} is true.
   */
  private static void assertManyCycles(ErrorInfo errorInfo, SkyKey topKey, boolean selfEdge) {
    MoreAsserts.assertGreaterThan(1, Iterables.size(errorInfo.getCycleInfo()));
    MoreAsserts.assertLessThan(50, Iterables.size(errorInfo.getCycleInfo()));
    boolean foundSelfEdge = false;
    for (CycleInfo cycle : errorInfo.getCycleInfo()) {
      assertEquals(1, cycle.getCycle().size()); // Self-edge.
      if (!Iterables.isEmpty(cycle.getPathToCycle())) {
        MoreAsserts.assertContentsInOrder(cycle.getPathToCycle(), topKey);
      } else {
        MoreAsserts.assertContentsInOrder(cycle.getCycle(), topKey);
        foundSelfEdge = true;
      }
    }
    assertEquals(errorInfo + ", " + topKey, selfEdge, foundSelfEdge);
  }

  @Test
  public void manyUnprocessedValuesInCycle() throws Exception {
    graph = new InMemoryGraph();
    SkyKey lastSelfKey = GraphTester.toSkyKey("lastSelf");
    SkyKey firstSelfKey = GraphTester.toSkyKey("firstSelf");
    SkyKey midSelfKey = GraphTester.toSkyKey("midSelf");
    // We add firstSelf first so that it is processed last in cycle detection (LIFO), meaning that
    // none of the dep values have to be cleared from firstSelf.
    tester.getOrCreate(firstSelfKey).addDependency(firstSelfKey);
    for (int i = 0; i < 100; i++) {
      SkyKey firstDep = GraphTester.toSkyKey("first" + i);
      SkyKey midDep = GraphTester.toSkyKey("mid" + i);
      SkyKey lastDep = GraphTester.toSkyKey("last" + i);
      tester.getOrCreate(firstSelfKey).addDependency(firstDep);
      tester.getOrCreate(midSelfKey).addDependency(midDep);
      tester.getOrCreate(lastSelfKey).addDependency(lastDep);
      if (i == 90) {
        // Most of the deps will be cleared from midSelf.
        tester.getOrCreate(midSelfKey).addDependency(midSelfKey);
      }
      tester.getOrCreate(firstDep).addDependency(firstDep);
      tester.getOrCreate(midDep).addDependency(midDep);
      tester.getOrCreate(lastDep).addDependency(lastDep);
    }
    // All the deps will be cleared from lastSelf.
    tester.getOrCreate(lastSelfKey).addDependency(lastSelfKey);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true,
        ImmutableList.of(lastSelfKey, firstSelfKey, midSelfKey));
    assert_().withFailureMessage(result.toString()).that(result.keyNames()).isEmpty();
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(),
        lastSelfKey, firstSelfKey, midSelfKey);

    // Check lastSelfKey.
    ErrorInfo errorInfo = result.getError(lastSelfKey);
    assertEquals(errorInfo.toString(), 1, Iterables.size(errorInfo.getCycleInfo()));
    CycleInfo cycleInfo = Iterables.getOnlyElement(errorInfo.getCycleInfo());
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getCycle(), lastSelfKey);
    assertThat(cycleInfo.getPathToCycle()).isEmpty();

    // Check firstSelfKey. It should not have discovered its own self-edge, because there were too
    // many other values before it in the queue.
    assertManyCycles(result.getError(firstSelfKey), firstSelfKey, /*selfEdge=*/false);

    // Check midSelfKey. It should have discovered its own self-edge.
    assertManyCycles(result.getError(midSelfKey), midSelfKey, /*selfEdge=*/true);
  }

  @Test
  public void errorValueStoredWithKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    EvaluationResult<StringValue> result = eval(true, ImmutableList.of(errorKey));
    assertThat(result.keyNames()).isEmpty();
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), errorKey);
    ErrorInfo errorInfo = result.getError();
    MoreAsserts.assertContentsAnyOrder(errorInfo.getRootCauses(), errorKey);
    // Update value. But builder won't rebuild it.
    tester.getOrCreate(errorKey).setHasError(false);
    tester.set(errorKey, new StringValue("no error?"));
    result = eval(true, ImmutableList.of(errorKey));
    assertThat(result.keyNames()).isEmpty();
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), errorKey);
    errorInfo = result.getError();
    MoreAsserts.assertContentsAnyOrder(errorInfo.getRootCauses(), errorKey);
  }

  @Test
  public void continueWithErrorDep() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE).addDependency("after");
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(parentKey));
    assertThat(result.errorMap()).isEmpty();
    assertEquals("recoveredafter", result.get(parentKey).getValue());
    result = eval(/*keepGoing=*/false, ImmutableList.of(parentKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), errorKey);
  }

  @Test
  public void breakWithErrorDep() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE).addDependency("after");
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/false, ImmutableList.of(parentKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), errorKey);
    result = eval(/*keepGoing=*/true, ImmutableList.of(parentKey));
    assertThat(result.errorMap()).isEmpty();
    assertEquals("recoveredafter", result.get(parentKey).getValue());
  }

  @Test
  public void breakWithInterruptibleErrorDep() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    // When the error value throws, the propagation will cause an interrupted exception in parent.
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/false, ImmutableList.of(parentKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), errorKey);
    assertFalse(Thread.interrupted());
    result = eval(/*keepGoing=*/true, ImmutableList.of(parentKey));
    assertThat(result.errorMap()).isEmpty();
    assertEquals("recovered", result.get(parentKey).getValue());
  }

  @Test
  public void transformErrorDep() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setHasError(true);
    EvaluationResult<StringValue> result = eval(
        /*keepGoing=*/false, ImmutableList.of(parentErrorKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentErrorKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), parentErrorKey);
  }

  @Test
  public void transformErrorDepKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setHasError(true);
    EvaluationResult<StringValue> result = eval(
        /*keepGoing=*/true, ImmutableList.of(parentErrorKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(parentErrorKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), parentErrorKey);
  }

  @Test
  public void transformErrorDepOneLevelDownKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"));
    tester.set(parentErrorKey, new StringValue("parent value"));
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(parentErrorKey).addDependency("after")
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableList.of(topKey));
    MoreAsserts.assertContentsAnyOrder(
        ImmutableList.<String>copyOf(result.<String>keyNames()), "top");
    assertEquals("parent valueafter", result.get(topKey).getValue());
    assertThat(result.errorMap()).isEmpty();
  }

  @Test
  public void transformErrorDepOneLevelDownNoKeepGoing() throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    tester.getOrCreate(errorKey).setHasError(true);
    tester.set("after", new StringValue("after"));
    SkyKey parentErrorKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentErrorKey).addErrorDependency(errorKey, new StringValue("recovered"));
    tester.set(parentErrorKey, new StringValue("parent value"));
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(parentErrorKey).addDependency("after")
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/false, ImmutableList.of(topKey));
    assertThat(result.keyNames()).isEmpty();
    Map.Entry<SkyKey, ErrorInfo> error = Iterables.getOnlyElement(result.errorMap().entrySet());
    assertEquals(topKey, error.getKey());
    MoreAsserts.assertContentsAnyOrder(error.getValue().getRootCauses(), errorKey);
  }

  /**
   * Make sure that multiple unfinished children can be cleared from a cycle value.
   */
  @Test
  public void cycleWithMultipleUnfinishedChildren() throws Exception {
    graph = new InMemoryGraph();
    tester = new GraphTester();
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    SkyKey selfEdge1 = GraphTester.toSkyKey("selfEdge1");
    SkyKey selfEdge2 = GraphTester.toSkyKey("selfEdge2");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    // selfEdge* come before cycleKey, so cycleKey's path will be checked first (LIFO), and the
    // cycle with mid will be detected before the selfEdge* cycles are.
    tester.getOrCreate(midKey).addDependency(selfEdge1).addDependency(selfEdge2)
        .addDependency(cycleKey)
    .setComputedValue(CONCATENATE);
    tester.getOrCreate(cycleKey).addDependency(midKey);
    tester.getOrCreate(selfEdge1).addDependency(selfEdge1);
    tester.getOrCreate(selfEdge2).addDependency(selfEdge2);
    EvaluationResult<StringValue> result = eval(/*keepGoing=*/true, ImmutableSet.of(topKey));
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), topKey);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getPathToCycle(), topKey);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getCycle(), midKey, cycleKey);
  }

  /**
   * Regression test: "value in cycle depends on error".
   * The mid value will have two parents -- top and cycle. Error bubbles up from mid to cycle, and
   * we should detect cycle.
   */
  private void cycleAndErrorInBubbleUp(boolean keepGoing) throws Exception {
    graph = new DeterministicInMemoryGraph();
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(errorKey).addDependency(cycleKey)
        .setComputedValue(CONCATENATE);

    // We need to ensure that cycle value has finished his work, and we have recorded dependencies
    CountDownLatch cycleFinish = new CountDownLatch(1);
    tester.getOrCreate(cycleKey).setBuilder(new ChainedFunction(null,
        null, cycleFinish, false, new StringValue(""), ImmutableSet.<SkyKey>of(midKey)));
    tester.getOrCreate(errorKey).setBuilder(new ChainedFunction(null, cycleFinish,
        null, /*waitForException=*/false, null, ImmutableSet.<SkyKey>of()));

    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableSet.of(topKey));
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), topKey);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    if (keepGoing) {
      // The error thrown will only be recorded in keep_going mode.
      MoreAsserts.assertContentsAnyOrder(result.getError().getRootCauses(), errorKey);
    }
    assertThat(cycleInfos).isNotEmpty();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getPathToCycle(), topKey);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getCycle(), midKey, cycleKey);
  }

  @Test
  public void cycleAndErrorInBubbleUpNoKeepGoing() throws Exception {
    cycleAndErrorInBubbleUp(false);
  }

  @Test
  public void cycleAndErrorInBubbleUpKeepGoing() throws Exception {
    cycleAndErrorInBubbleUp(true);
  }

  /**
   * Regression test: "value in cycle depends on error".
   * We add another value that won't finish building before the threadpool shuts down, to check that
   * the cycle detection can handle unfinished values.
   */
  @Test
  public void cycleAndErrorAndOtherInBubbleUp() throws Exception {
    graph = new DeterministicInMemoryGraph();
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    // We should add cycleKey first and errorKey afterwards. Otherwise there is a chance that
    // during error propagation cycleKey will not be processed, and we will not detect the cycle.
    tester.getOrCreate(midKey).addDependency(errorKey).addDependency(cycleKey)
        .setComputedValue(CONCATENATE);
    SkyKey otherTop = GraphTester.toSkyKey("otherTop");
    CountDownLatch topStartAndCycleFinish = new CountDownLatch(2);
    // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
    // then request its own dep. This guarantees that there is a value that is not finished when
    // cycle detection happens.
    tester.getOrCreate(otherTop).setBuilder(new ChainedFunction(topStartAndCycleFinish,
        new CountDownLatch(0), null, /*waitForException=*/true, new StringValue("never returned"),
        ImmutableSet.<SkyKey>of(GraphTester.toSkyKey("dep that never builds"))));

    tester.getOrCreate(cycleKey).setBuilder(new ChainedFunction(null, null,
        topStartAndCycleFinish, /*waitForException=*/false, new StringValue(""),
        ImmutableSet.<SkyKey>of(midKey)));
    // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
    // its dep before the threadpool shuts down.
    tester.getOrCreate(errorKey).setBuilder(new ChainedFunction(null, topStartAndCycleFinish,
        null, /*waitForException=*/false, null,
        ImmutableSet.<SkyKey>of()));
    EvaluationResult<StringValue> result =
        eval(/*keepGoing=*/false, ImmutableSet.of(topKey, otherTop));
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), topKey);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    assertThat(cycleInfos).isNotEmpty();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getPathToCycle(), topKey);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getCycle(), midKey, cycleKey);
  }

  /**
   * Regression test: "value in cycle depends on error".
   * Here, we add an additional top-level key in error, just to mix it up.
   */
  private void cycleAndErrorAndError(boolean keepGoing) throws Exception {
    graph = new DeterministicInMemoryGraph();
    tester = new GraphTester();
    SkyKey errorKey = GraphTester.toSkyKey("error");
    SkyKey cycleKey = GraphTester.toSkyKey("cycle");
    SkyKey midKey = GraphTester.toSkyKey("mid");
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addDependency(midKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(midKey).addDependency(errorKey).addDependency(cycleKey)
        .setComputedValue(CONCATENATE);
    SkyKey otherTop = GraphTester.toSkyKey("otherTop");
    CountDownLatch topStartAndCycleFinish = new CountDownLatch(2);
    // In nokeep_going mode, otherTop will wait until the threadpool has received an exception,
    // then throw its own exception. This guarantees that its exception will not be the one
    // bubbling up, but that there is a top-level value with an exception by the time the bubbling
    // up starts.
    tester.getOrCreate(otherTop).setBuilder(new ChainedFunction(topStartAndCycleFinish,
        new CountDownLatch(0), null, /*waitForException=*/!keepGoing, null,
        ImmutableSet.<SkyKey>of()));
    // error waits until otherTop starts and cycle finishes, to make sure otherTop will request
    // its dep before the threadpool shuts down.
    tester.getOrCreate(errorKey).setBuilder(new ChainedFunction(null, topStartAndCycleFinish,
        null, /*waitForException=*/false, null,
        ImmutableSet.<SkyKey>of()));
    tester.getOrCreate(cycleKey).setBuilder(new ChainedFunction(null, null,
        topStartAndCycleFinish, /*waitForException=*/false, new StringValue(""),
        ImmutableSet.<SkyKey>of(midKey)));
    EvaluationResult<StringValue> result =
        eval(keepGoing, ImmutableSet.of(topKey, otherTop));
    MoreAsserts.assertContentsAnyOrder(result.errorMap().keySet(), otherTop, topKey);
    MoreAsserts.assertContentsAnyOrder(result.getError(otherTop).getRootCauses(), otherTop);
    Iterable<CycleInfo> cycleInfos = result.getError(topKey).getCycleInfo();
    if (keepGoing) {
      // The error thrown will only be recorded in keep_going mode.
      MoreAsserts.assertContentsAnyOrder(result.getError(topKey).getRootCauses(), errorKey);
    }
    assertThat(cycleInfos).isNotEmpty();
    CycleInfo cycleInfo = Iterables.getOnlyElement(cycleInfos);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getPathToCycle(), topKey);
    MoreAsserts.assertContentsAnyOrder(cycleInfo.getCycle(), midKey, cycleKey);
  }

  @Test
  public void cycleAndErrorAndErrorNoKeepGoing() throws Exception {
    cycleAndErrorAndError(false);
  }

  @Test
  public void cycleAndErrorAndErrorKeepGoing() throws Exception {
    cycleAndErrorAndError(true);
  }

  @Test
  public void testFunctionCrashTrace() throws Exception {
    final SkyFunctionName childType = new SkyFunctionName("child", false);
    final SkyFunctionName parentType = new SkyFunctionName("parent", false);

    class ChildFunction implements SkyFunction {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) {
        throw new IllegalStateException("I WANT A PONY!!!");
      }

      @Override public String extractTag(SkyKey skyKey) { return null; }
    }

    class ParentFunction implements SkyFunction {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) {
        SkyValue dep = env.getValue(new SkyKey(childType, "billy the kid"));
        if (dep == null) {
          return null;
        }
        throw new IllegalStateException();  // Should never get here.
      }

      @Override public String extractTag(SkyKey skyKey) { return null; }
    }

    ImmutableMap<SkyFunctionName, SkyFunction> skyFunctions = ImmutableMap.of(
        childType, new ChildFunction(),
        parentType, new ParentFunction());
    ParallelEvaluator evaluator = makeEvaluator(new InMemoryGraph(),
        skyFunctions, false);

    try {
      evaluator.eval(ImmutableList.of(new SkyKey(parentType, "octodad")));
      fail();
    } catch (RuntimeException e) {
      assertEquals("I WANT A PONY!!!", e.getCause().getMessage());
      assertEquals("Unrecoverable error while evaluating node 'child:billy the kid' "
          + "(requested by nodes 'parent:octodad')", e.getMessage());
    }
  }

  private void unexpectedErrorDep(boolean keepGoing) throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    final Exception exception = new Exception("error exception");
    tester.getOrCreate(errorKey).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws GenericFunctionException {
        throw new GenericFunctionException(skyKey, exception);
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        throw new UnsupportedOperationException();
      }
    });
    SkyKey topKey = GraphTester.toSkyKey("top");
    tester.getOrCreate(topKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(topKey));
    assertThat(result.keyNames()).isEmpty();
    assertSame(exception, result.getError(topKey).getException());
    MoreAsserts.assertContentsAnyOrder(result.getError(topKey).getRootCauses(), errorKey);
  }

  /**
   * This and the following three tests are in response a bug: "Skyframe error propagation model is
   * problematic". They ensure that exceptions a child throws that a value does not specify it can
   * handle in getValueOrThrow do not cause a crash.
   */
  @Test
  public void unexpectedErrorDepKeepGoing() throws Exception {
    unexpectedErrorDep(true);
  }

  @Test
  public void unexpectedErrorDepNoKeepGoing() throws Exception {
    unexpectedErrorDep(false);
  }

  private void unexpectedErrorDepOneLevelDown(final boolean keepGoing) throws Exception {
    graph = new InMemoryGraph();
    SkyKey errorKey = GraphTester.toSkyKey("my_error_value");
    final Exception exception = new Exception("error exception");
    final Exception topException = new Exception("top exception");
    final StringValue topValue = new StringValue("top");
    tester.getOrCreate(errorKey).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws GenericFunctionException {
        throw new GenericFunctionException(skyKey, exception);
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        throw new UnsupportedOperationException();
      }
    });
    SkyKey topKey = GraphTester.toSkyKey("top");
    final SkyKey parentKey = GraphTester.toSkyKey("parent");
    tester.getOrCreate(parentKey).addDependency(errorKey).setComputedValue(CONCATENATE);
    tester.getOrCreate(topKey).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws GenericFunctionException {
        try {
          if (env.getValueOrThrow(parentKey, Exception.class) == null) {
            return null;
          }
        } catch (Exception e) {
          assertEquals(e.toString(), exception, e);
        }
        if (keepGoing) {
          return topValue;
        } else {
          throw new GenericFunctionException(skyKey, topException);
        }
      }
      @Override
      public String extractTag(SkyKey skyKey) {
        throw new UnsupportedOperationException();
      }
    });
    tester.getOrCreate(topKey).addErrorDependency(errorKey, new StringValue("recovered"))
        .setComputedValue(CONCATENATE);
    EvaluationResult<StringValue> result = eval(keepGoing, ImmutableList.of(topKey));
    if (!keepGoing) {
      assertThat(result.keyNames()).isEmpty();
      assertEquals(topException, result.getError(topKey).getException());
      MoreAsserts.assertContentsAnyOrder(result.getError(topKey).getRootCauses(), topKey);
      assertTrue(result.hasError());
    } else {
      // result.hasError() is set to true even if the top-level value returned has recovered from
      // an error.
      assertTrue(result.hasError());
      assertSame(topValue, result.get(topKey));
    }
  }

  @Test
  public void unexpectedErrorDepOneLevelDownKeepGoing() throws Exception {
    unexpectedErrorDepOneLevelDown(true);
  }

  @Test
  public void unexpectedErrorDepOneLevelDownNoKeepGoing() throws Exception {
    unexpectedErrorDepOneLevelDown(false);
  }

  /**
   * Exercises various situations involving groups of deps that overlap -- request one group, then
   * request another group that has a dep in common with the first group.
   *
   * @param sameFirst whether the dep in common in the two groups should be the first dep.
   * @param twoCalls whether the two groups should be requested in two different builder calls.
   * @param valuesOrThrow whether the deps should be requested using getValuesOrThrow.
   */
  private void sameDepInTwoGroups(final boolean sameFirst, final boolean twoCalls,
      final boolean valuesOrThrow) throws Exception {
    graph = new InMemoryGraph();
    SkyKey topKey = GraphTester.toSkyKey("top");
    final List<SkyKey> leaves = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      SkyKey leaf = GraphTester.toSkyKey("leaf" + i);
      leaves.add(leaf);
      tester.set(leaf, new StringValue("leaf" + i));
    }
    final SkyKey leaf4 = GraphTester.toSkyKey("leaf4");
    tester.set(leaf4, new StringValue("leaf" + 4));
    tester.getOrCreate(topKey).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException,
          InterruptedException {
        if (valuesOrThrow) {
          env.getValuesOrThrow(leaves, Exception.class);
        } else {
          env.getValues(leaves);
        }
        if (twoCalls && env.valuesMissing()) {
          return null;
        }
        SkyKey first = sameFirst ? leaves.get(0) : leaf4;
        SkyKey second = sameFirst ? leaf4 : leaves.get(2);
        List<SkyKey> secondRequest = ImmutableList.of(first, second);
        if (valuesOrThrow) {
          env.getValuesOrThrow(secondRequest, Exception.class);
        } else {
          env.getValues(secondRequest);
        }
        if (env.valuesMissing()) {
          return null;
        }
        return new StringValue("top");
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    });
    eval(/*keepGoing=*/false, topKey);
    assertEquals(new StringValue("top"), eval(/*keepGoing=*/false, topKey));
  }

  @Test
  public void sameDepInTwoGroups_Same_Two_Throw() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/true, /*twoCalls=*/true, /*valuesOrThrow=*/true);
  }

  @Test
  public void sameDepInTwoGroups_Same_Two_Deps() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/true, /*twoCalls=*/true, /*valuesOrThrow=*/false);
  }

  @Test
  public void sameDepInTwoGroups_Same_One_Throw() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/true, /*twoCalls=*/false, /*valuesOrThrow=*/true);
  }

  @Test
  public void sameDepInTwoGroups_Same_One_Deps() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/true, /*twoCalls=*/false, /*valuesOrThrow=*/false);
  }

  @Test
  public void sameDepInTwoGroups_Different_Two_Throw() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/false, /*twoCalls=*/true, /*valuesOrThrow=*/true);
  }

  @Test
  public void sameDepInTwoGroups_Different_Two_Deps() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/false, /*twoCalls=*/true, /*valuesOrThrow=*/false);
  }

  @Test
  public void sameDepInTwoGroups_Different_One_Throw() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/false, /*twoCalls=*/false, /*valuesOrThrow=*/true);
  }

  @Test
  public void sameDepInTwoGroups_Different_One_Deps() throws Exception {
    sameDepInTwoGroups(/*sameFirst=*/false, /*twoCalls=*/false, /*valuesOrThrow=*/false);
  }

  private void getValuesOrThrowWithErrors(boolean keepGoing) throws Exception {
    graph = new InMemoryGraph();
    SkyKey parentKey = GraphTester.toSkyKey("parent");
    final SkyKey errorDep = GraphTester.toSkyKey("errorChild");
    final SomeErrorException childExn = new SomeErrorException("child error");
    tester.getOrCreate(errorDep).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
        throw new GenericFunctionException(skyKey, childExn);
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    });
    final List<SkyKey> deps = new ArrayList<>();
    for (int i = 1; i <= 3; i++) {
      SkyKey dep = GraphTester.toSkyKey("child" + i);
      deps.add(dep);
      tester.set(dep, new StringValue("child" + i));
    }
    final SomeErrorException parentExn = new SomeErrorException("parent error");
    tester.getOrCreate(parentKey).setBuilder(new SkyFunction() {
      @Override
      public SkyValue compute(SkyKey skyKey, Environment env) throws SkyFunctionException {
        try {
          SkyValue value = env.getValueOrThrow(errorDep, SomeErrorException.class);
          if (value == null) {
            return null;
          }
        } catch (SomeErrorException e) {
          // Recover from the child error.
        }
        env.getValues(deps);
        if (env.valuesMissing()) {
          return null;
        }
        throw new GenericFunctionException(skyKey, parentExn);
      }

      @Override
      public String extractTag(SkyKey skyKey) {
        return null;
      }
    });
    EvaluationResult<StringValue> evaluationResult = eval(keepGoing, ImmutableList.of(parentKey));
    assertTrue(evaluationResult.hasError());
    assertEquals(keepGoing ? parentExn : childExn, evaluationResult.getError().getException());
  }

  @Test
  public void getValuesOrThrowWithErrors_NoKeepGoing() throws Exception {
    getValuesOrThrowWithErrors(/*keepGoing=*/false);
  }

  @Test
  public void getValuesOrThrowWithErrors_KeepGoing() throws Exception {
    getValuesOrThrowWithErrors(/*keepGoing=*/true);
  }

  @Test
  public void duplicateCycles() throws Exception {
    graph = new InMemoryGraph();
    SkyKey grandparentKey = GraphTester.toSkyKey("grandparent");
    SkyKey parentKey1 = GraphTester.toSkyKey("parent1");
    SkyKey parentKey2 = GraphTester.toSkyKey("parent2");
    SkyKey loopKey1 = GraphTester.toSkyKey("loop1");
    SkyKey loopKey2 = GraphTester.toSkyKey("loop2");
    tester.getOrCreate(loopKey1).addDependency(loopKey2);
    tester.getOrCreate(loopKey2).addDependency(loopKey1);
    tester.getOrCreate(parentKey1).addDependency(loopKey1);
    tester.getOrCreate(parentKey2).addDependency(loopKey2);
    tester.getOrCreate(grandparentKey).addDependency(parentKey1);
    tester.getOrCreate(grandparentKey).addDependency(parentKey2);

    ErrorInfo errorInfo = evalValueInError(grandparentKey);
    List<ImmutableList<SkyKey>> cycles = Lists.newArrayList();
    for (CycleInfo cycleInfo : errorInfo.getCycleInfo()) {
      cycles.add(cycleInfo.getCycle());
    }
    // Skyframe doesn't automatically dedupe cycles that are the same except for entry point.
    assertEquals(2, cycles.size());
    int numUniqueCycles = 0;
    CycleDeduper<SkyKey> cycleDeduper = new CycleDeduper<SkyKey>();
    for (ImmutableList<SkyKey> cycle : cycles) {
      if (cycleDeduper.seen(cycle)) {
        numUniqueCycles++;
      }
    }
    assertEquals(1, numUniqueCycles);
  }

  @Test
  public void signalValueEnqueuedAndEvaluated() throws Exception {
    final Set<SkyKey> enqueuedValues = new HashSet<>();
    final Set<SkyKey> evaluatedValues = new HashSet<>();
    EvaluationProgressReceiver progressReceiver = new EvaluationProgressReceiver() {
      @Override
      public void invalidated(SkyValue value, InvalidationState state) {
        throw new IllegalStateException();
      }

      @Override
      public void enqueueing(SkyKey skyKey) {
        enqueuedValues.add(skyKey);
      }

      @Override
      public void evaluated(SkyKey skyKey, SkyValue value, EvaluationState state) {
        evaluatedValues.add(skyKey);
      }
    };

    ErrorEventListener reporter = new ErrorEventListener() {
      @Override
      public void warn(Location location, String message) {
        throw new IllegalStateException();
      }

      @Override
      public boolean showOutput(String tag) {
        throw new IllegalStateException();
      }

      @Override
      public void report(EventKind kind, Location location, String message) {
        throw new IllegalStateException();
      }

      @Override
      public void report(EventKind kind, Location location, byte[] message) {
        throw new IllegalStateException();
      }

      @Override
      public void progress(Location location, String message) {
        throw new IllegalStateException();
      }

      @Override
      public void info(Location location, String message) {
        throw new IllegalStateException();
      }

      @Override
      public void error(Location location, String message) {
        throw new IllegalStateException();
      }
    };

    MemoizingEvaluator aug = new InMemoryMemoizingEvaluator(
        ImmutableMap.of(GraphTester.NODE_TYPE, tester.getFunction()), new RecordingDifferencer(),
        progressReceiver);
    SequentialBuildDriver driver = new SequentialBuildDriver(aug);

    tester.getOrCreate("top1").setComputedValue(CONCATENATE)
        .addDependency("d1").addDependency("d2");
    tester.getOrCreate("top2").setComputedValue(CONCATENATE).addDependency("d3");
    tester.getOrCreate("top3");
    assertThat(enqueuedValues).isEmpty();
    assertThat(evaluatedValues).isEmpty();

    tester.set("d1", new StringValue("1"));
    tester.set("d2", new StringValue("2"));
    tester.set("d3", new StringValue("3"));

    driver.evaluate(ImmutableList.of(GraphTester.toSkyKey("top1")), false, 200, reporter);
    MoreAsserts.assertContentsAnyOrder(enqueuedValues, GraphTester.toSkyKeys("top1", "d1", "d2"));
    MoreAsserts.assertContentsAnyOrder(evaluatedValues, GraphTester.toSkyKeys("top1", "d1", "d2"));
    enqueuedValues.clear();
    evaluatedValues.clear();

    driver.evaluate(ImmutableList.of(GraphTester.toSkyKey("top2")), false, 200, reporter);
    MoreAsserts.assertContentsAnyOrder(enqueuedValues, GraphTester.toSkyKeys("top2", "d3"));
    MoreAsserts.assertContentsAnyOrder(evaluatedValues, GraphTester.toSkyKeys("top2", "d3"));
    enqueuedValues.clear();
    evaluatedValues.clear();

    driver.evaluate(ImmutableList.of(GraphTester.toSkyKey("top1")), false, 200, reporter);
    assertThat(enqueuedValues).isEmpty();
    MoreAsserts.assertContentsAnyOrder(evaluatedValues, GraphTester.toSkyKeys("top1"));
  }
}
