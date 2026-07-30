/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.flutter.plugins.firebase.firestore.streamhandler.QuerySnapshotChangesStreamHandler.GuardedConversionExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * SwineTech (SP30-9652): the first Android test in this fork.
 *
 * <p>{@link GuardedConversionExecutor} is the whole of the fix for the crash class SP30-9652
 * introduced, so it is the piece that most needs pinning. {@code releaseResources()} used to call
 * {@code shutdownNow()} on the very ExecutorService still registered with the Firestore SDK via
 * {@code setExecutor()}. {@code ListenerRegistration.remove()} only <em>enqueues</em> {@code
 * stopListening} on Firestore's AsyncQueue, so a snapshot already queued ahead of it is still
 * raised afterwards — and {@code AsyncEventListener.onEvent} calls {@code executor.execute(...)}
 * unconditionally, its {@code muted} check living inside the submitted Runnable rather than around
 * the submission. Executing on a shut-down ThreadPoolExecutor throws {@link
 * java.util.concurrent.RejectedExecutionException}, which {@code AsyncQueue} converts into {@code
 * AsyncQueue.panic()} — rethrown on the main looper as {@code RuntimeException("Internal error in
 * Cloud Firestore")}, i.e. process death, on every barn change and logout.
 *
 * <p>This class is deliberately testable without Firestore, Android or Robolectric: it takes an
 * ExecutorService and implements Executor, nothing more. The handler that owns it cannot be
 * instantiated in a unit test without mocking Query, QuerySnapshot and PigeonParser, and {@code
 * SnapshotListenOptions} exposes no getter for the executor handed to {@code setExecutor}, so
 * testing the mechanism directly is both cheaper and stronger than testing it through the handler.
 */
public class GuardedConversionExecutorTest {

  @Test
  public void runsWorkNormallyBeforeStop() throws Exception {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    GuardedConversionExecutor executor = new GuardedConversionExecutor(delegate);

    CountDownLatch ran = new CountDownLatch(1);
    executor.execute(ran::countDown);

    assertTrue("the guard must not stop work reaching the delegate", ran.await(5, TimeUnit.SECONDS));

    executor.stop();
  }

  /**
   * THE regression assertion. Post-teardown submissions must be dropped silently rather than
   * reaching a shut-down pool, because the caller is Firestore's AsyncQueue and a throw there
   * panics the process.
   */
  @Test
  public void dropsWorkAfterStopWithoutThrowing() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    GuardedConversionExecutor executor = new GuardedConversionExecutor(delegate);

    executor.stop();

    AtomicInteger ran = new AtomicInteger();
    // No try/catch here on purpose: a throw escaping this call is the bug, so letting it fail the
    // test is the point.
    executor.execute(ran::incrementAndGet);

    assertEquals("work submitted after stop() must not run", 0, ran.get());
  }

  /**
   * The race the catch inside {@code execute} exists for: {@code stopped} is checked and then the
   * delegate is used, so another thread calling {@code stop()} in between produces a
   * RejectedExecutionException from a delegate the guard still believes is live. Simulated by
   * shutting the delegate down behind the guard's back, which leaves {@code stopped} false.
   */
  @Test
  public void swallowsRejectionFromAnAlreadyShutDownDelegate() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    GuardedConversionExecutor executor = new GuardedConversionExecutor(delegate);

    delegate.shutdownNow();

    AtomicInteger ran = new AtomicInteger();
    executor.execute(ran::incrementAndGet);

    assertEquals(0, ran.get());
  }

  @Test
  public void stopShutsTheDelegateDown() throws Exception {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    GuardedConversionExecutor executor = new GuardedConversionExecutor(delegate);

    executor.stop();

    assertTrue("stop() must shut the real pool down, not just gate it", delegate.isShutdown());
    assertTrue(
        "the worker thread must actually terminate", delegate.awaitTermination(5, TimeUnit.SECONDS));
  }

  /**
   * stop() is reached from both onCancel and terminateWithError, and terminateWithError can be
   * called from the conversion thread while onCancel arrives on main, so it has to tolerate being
   * called more than once.
   */
  @Test
  public void stopIsIdempotent() {
    ExecutorService delegate = Executors.newSingleThreadExecutor();
    GuardedConversionExecutor executor = new GuardedConversionExecutor(delegate);

    executor.stop();
    executor.stop();

    assertTrue(delegate.isShutdown());
  }
}
