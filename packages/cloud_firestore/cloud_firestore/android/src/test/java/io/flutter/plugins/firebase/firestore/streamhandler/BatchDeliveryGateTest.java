/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.os.Handler;
import android.os.Looper;
import io.flutter.plugins.firebase.firestore.streamhandler.QuerySnapshotChangesStreamHandler.BatchDeliveryGate;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * SwineTech (SP30-9652): the slot accounting that makes MAX_IN_FLIGHT_BATCHES an end-to-end bound.
 *
 * <p>A slot is taken on the conversion thread, returned on the main thread when the consumer
 * acknowledges hydration, and may have to be reclaimed by a teardown racing both — so it is worth
 * pinning on its own rather than only through a device run. Robolectric supplies a real Looper for
 * the Handler; nothing here touches Firestore, EventChannel or Pigeon.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BatchDeliveryGateTest {

  private Handler handler;

  @Before
  public void setUp() {
    handler = new Handler(Looper.getMainLooper());
  }

  private void drainMainLooper() {
    shadowOf(Looper.getMainLooper()).idle();
  }

  @Test
  public void holdsTheSlotUntilAcknowledged() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 1_000L);

    assertTrue(gate.acquireSlot());
    assertEquals(1, gate.outstandingSlots());
    assertEquals("the slot must stay taken after delivery", 1, gate.availableSlots());

    gate.acknowledge();

    assertEquals(0, gate.outstandingSlots());
    assertEquals(2, gate.availableSlots());
  }

  /**
   * THE bound. With capacity exhausted and nothing acknowledged, the next acquire must not succeed —
   * that is what stops conversion running arbitrarily far ahead of hydration.
   */
  @Test
  public void refusesAnExtraSlotUntilOneIsReturned() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 50L);

    assertTrue(gate.acquireSlot());
    assertTrue(gate.acquireSlot());

    assertFalse("capacity is 2, so a third batch must be refused", gate.acquireSlot());

    gate.acknowledge();

    assertTrue("acknowledging one batch must free exactly one slot", gate.acquireSlot());
  }

  /**
   * THE end-to-end guarantee, and the one the other bound test cannot express: delivering a batch
   * must NOT free its slot. Only hydration does.
   *
   * <p>Added because a mutation that returned the slot inside postBatch — exactly the
   * pre-acknowledgement "release on hand-off" behaviour this work exists to replace — passed every
   * other test in this class. refusesAnExtraSlotUntilOneIsReturned drives acquireSlot() directly and
   * never posts, so it cannot see the difference.
   */
  @Test
  public void deliveringABatchDoesNotFreeItsSlot() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 1, 50L);

    assertTrue(gate.acquireSlot());

    gate.postBatch(() -> {});
    drainMainLooper();

    assertFalse(
        "handing the payload to the consumer must not free the slot — only hydration does",
        gate.acquireSlot());

    gate.acknowledge();

    assertTrue("acknowledging hydration is what frees it", gate.acquireSlot());
  }

  /**
   * The refusal degrades rather than blocking. The thread that waits here is the executor handed to
   * setExecutor, which also carries onEvent(null, error), so a permanent park would stall error
   * delivery as well as data.
   */
  @Test
  public void timesOutInsteadOfBlockingForever() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 1, 100L);

    assertTrue(gate.acquireSlot());

    long start = System.nanoTime();
    assertFalse(gate.acquireSlot());
    long waitedMs = (System.nanoTime() - start) / 1_000_000L;

    assertTrue("must actually wait for the timeout, not fail fast", waitedMs >= 90L);
    assertTrue("must not wait appreciably beyond the timeout, got " + waitedMs, waitedMs < 5_000L);
  }

  /** A refused batch owns no slot, so it must not return one belonging to another batch. */
  @Test
  public void aRefusedBatchCannotReturnSomeoneElsesSlot() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 1, 50L);

    assertTrue(gate.acquireSlot());
    assertFalse(gate.acquireSlot());

    // The handler only calls abandonSlot when holdsSlot is true, but the accounting must be safe
    // regardless: a stray return must never push availability above capacity.
    gate.abandonSlot();
    gate.abandonSlot();
    gate.abandonSlot();

    assertEquals("availability must never exceed capacity", 1, gate.availableSlots());
    assertEquals(0, gate.outstandingSlots());
  }

  @Test
  public void shutdownReclaimsEverythingOutstanding() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 3, 1_000L);

    assertTrue(gate.acquireSlot());
    assertTrue(gate.acquireSlot());
    assertEquals(2, gate.outstandingSlots());

    gate.shutdown();

    assertEquals(0, gate.outstandingSlots());
    assertEquals(3, gate.availableSlots());
  }

  @Test
  public void acquireIsInertAfterShutdown() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 1_000L);

    gate.shutdown();

    assertFalse("a torn-down gate must not hand out slots", gate.acquireSlot());
  }

  /** A late acknowledgement from a consumer that was mid-hydration when the stream was torn down. */
  @Test
  public void acknowledgeAfterShutdownIsInert() throws Exception {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 1_000L);

    assertTrue(gate.acquireSlot());
    gate.shutdown();
    gate.acknowledge();

    assertEquals(2, gate.availableSlots());
  }

  /**
   * THE #11 regression assertion. Dropping queued batch deliveries at teardown must not also drop a
   * terminal error/endOfStream notification, or the consumer is left with isPartial=true batches and
   * no terminal batch, no onError and no onDone.
   */
  @Test
  public void droppingBatchesLeavesTheTerminalNotificationQueued() {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 1_000L);
    List<String> ran = new ArrayList<>();

    gate.postBatch(() -> ran.add("batch-1"));
    gate.postBatch(() -> ran.add("batch-2"));
    gate.postTerminal(() -> ran.add("terminal"));

    gate.dropPendingBatches();
    drainMainLooper();

    assertEquals("only the terminal notification may survive", List.of("terminal"), ran);
  }

  @Test
  public void batchesRunInOrderWhenNotDropped() {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 5, 1_000L);
    List<String> ran = new ArrayList<>();

    gate.postBatch(() -> ran.add("one"));
    gate.postBatch(() -> ran.add("two"));
    gate.postTerminal(() -> ran.add("terminal"));

    drainMainLooper();

    assertEquals(List.of("one", "two", "terminal"), ran);
  }

  /** shutdown() also clears queued batches, since they will never be acknowledged. */
  @Test
  public void shutdownDropsQueuedBatchesButNotTheTerminal() {
    BatchDeliveryGate gate = new BatchDeliveryGate(handler, 2, 1_000L);
    List<String> ran = new ArrayList<>();

    gate.postBatch(() -> ran.add("batch"));
    gate.postTerminal(() -> ran.add("terminal"));

    gate.shutdown();
    drainMainLooper();

    assertEquals(List.of("terminal"), ran);
  }
}
