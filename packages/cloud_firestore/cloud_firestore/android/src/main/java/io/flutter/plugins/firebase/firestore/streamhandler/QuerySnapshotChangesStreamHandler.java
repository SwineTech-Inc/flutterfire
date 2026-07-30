/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static io.flutter.plugins.firebase.firestore.FlutterFirebaseFirestorePlugin.DEFAULT_ERROR_CODE;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenSource;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SnapshotListenOptions;
import com.google.firebase.firestore.SnapshotMetadata;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugins.firebase.firestore.GeneratedAndroidFirebaseFirestore;
import io.flutter.plugins.firebase.firestore.utils.ExceptionConverter;
import io.flutter.plugins.firebase.firestore.utils.PigeonParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// SwineTech: streams a "true" query snapshot that carries only the changed
// documents (document changes + metadata), not the full result set. Mirrors
// QuerySnapshotsStreamHandler but emits InternalQuerySnapshotChanges.
public class QuerySnapshotChangesStreamHandler implements StreamHandler {

  ListenerRegistration listenerRegistration;

  Query query;
  MetadataChanges metadataChanges;
  DocumentSnapshot.ServerTimestampBehavior serverTimestampBehavior;

  ListenSource source;

  // SwineTech: any snapshot whose change set exceeds this size is split into batches of this many
  // document changes, one method-channel message per batch. This is NOT only the initial load — a
  // large delta (bulk write, or reconnect after extended offline edits) is split too. Delivering a
  // huge change set as ONE message forces a single contiguous multi-MB allocation (an ArrayList of
  // pigeon objects plus the serialized ByteBuffer) — the usual proximate OOM / fragmentation
  // trigger. Splitting guarantees no single allocation spans the whole result set.
  //
  // SwineTech (SP30-9652): lowered 500 -> 250. Delivery is now asynchronous (see emitBatch), so a
  // batch handed to Dart is no longer hydrated before the next one is emitted — the consumer's
  // hydration queue trails the emit loop, and every queued batch pins a decoded payload until it
  // is drained. A moto g fast / BPI Hill Farm run (15,832 sows) peaked at 142 MB with 0% free
  // against the ~100 MB documented in SWINETECH_FIREBASE_FORK.md §7, with hydration trailing
  // emission by roughly 8 batches. Halving the batch halves the bytes each trailing batch pins,
  // and doubles the number of main-looper turns the emit loop yields on.
  private static final int DELIVERY_BATCH_SIZE = 250;

  // SwineTech (ANR fix): how many batches may be in flight between conversion and the consumer.
  //
  // A permit is taken before a batch is converted and returned only when the Dart side reports it
  // HYDRATED — not when events.success() hands the payload over. That distinction is the whole
  // point: releasing on hand-off bounded only the native resident payloads, while the payload then
  // sat in the consumer's hydration queue with nothing capping it. An earlier version of this
  // comment claimed a bound "regardless of how far ahead conversion gets", which was false in both
  // directions — conversion could also relocate the accumulation into the executor's UNBOUNDED
  // LinkedBlockingQueue by parking in acquire(). With the acknowledgement in place this is a real
  // end-to-end bound: at most this many batches exist anywhere between the SDK and hydrated Dart
  // state.
  //
  // SwineTech (SP30-9652): raised 2 -> 5 when the acknowledgement landed. Permits are now held for
  // an entire Dart hydration rather than for one main-looper turn, so the old value of 2 would have
  // serialised native conversion behind Dart hydration almost completely and cost load time on a
  // ticket whose subject is a ~74 s barn load. Five keeps the two overlapped while still bounding
  // residency — which, before the acknowledgement, was not bounded at all.
  private static final int MAX_IN_FLIGHT_BATCHES = 5;

  // SwineTech (SP30-9652): how long conversion waits for a slot before giving up and proceeding
  // anyway.
  //
  // There must be a ceiling. The thread that blocks here is the executor handed to setExecutor,
  // which is also the thread Firestore uses to deliver onEvent(null, error) — so parking it
  // indefinitely stalls error delivery as well as data, and a Dart side that has stopped
  // acknowledging (crashed hydration, a killed isolate, aggressive background throttling) would
  // wedge the stream permanently. Timing out and continuing degrades to the pre-acknowledgement
  // behaviour, which is merely unbounded rather than stuck. Generous on purpose: hitting this
  // should mean "Dart has stopped acknowledging", never "Dart is busy".
  private static final long SLOT_WAIT_TIMEOUT_MS = 30_000L;

  // SwineTech DEBUG: lifecycle + delivery timing for the changes-only streams, to diagnose
  // resume-after-long-background load latency and to verify the off-main-thread conversion is
  // actually active (the `thread=` field). Filter logcat by tag "SwineQSChanges".
  //
  // OFF BY DEFAULT — enable on a connected device with no rebuild:
  //     adb shell setprop log.tag.SwineQSChanges DEBUG
  //
  // Gated on isLoggable(DEBUG) rather than emitted unconditionally: Android's default threshold is
  // INFO, so DEBUG is silent until the property is set. Unlike the Dart side there is no automatic
  // strip here — `debugPrint` is nulled out in product builds and a `const false` block is
  // tree-shaken away, but `Log.i` ships and runs in release. A 16K-document herd load emits one
  // line per batch, so leaving it unconditional would put ~66 lines plus their string
  // concatenation into every production cold start. SWINETECH_FIREBASE_FORK.md §7 attributes ~7 s
  // of load time to Firestore's own log spam, which is the same failure mode at larger scale.
  private static final String DEBUG_TAG = "SwineQSChanges";
  private final int handlerId = System.identityHashCode(this);
  private long listenStartMs;

  private static boolean debugLogging() {
    return Log.isLoggable(DEBUG_TAG, Log.DEBUG);
  }

  // SwineTech (ANR fix): the Firestore SDK's default callback executor is
  // Executors.DEFAULT_CALLBACK_EXECUTOR == TaskExecutors.MAIN_THREAD, so without an explicit
  // executor every snapshot — including the ~15K-document initial herd load — inflated its
  // documents and serialized them on the Android main thread. Crashlytics ANR stacks for
  // charlotte 2.2.18 showed exactly that: ActivityThread.main -> Looper.loop -> AsyncEventListener
  // -> this handler -> PigeonParser -> DocumentSnapshot.getData -> ObjectValue.inflate ->
  // MapValue.parseFrom. Conversion now happens here instead.
  //
  // Serial (single-thread) is required, not incidental: Firestore delivers snapshots in order and
  // the consumer merges batches by document id, so conversion must not reorder them.
  private GuardedConversionExecutor conversionExecutor;
  private BatchDeliveryGate batchGate;

  // Set once the stream is torn down (onCancel) or terminated by an error. Read from both the
  // conversion thread and main, so posted deliveries can no-op against a dead sink.
  private final AtomicBoolean terminated = new AtomicBoolean(false);

  private final Handler mainHandler = new Handler(Looper.getMainLooper());


  public QuerySnapshotChangesStreamHandler(
      Query query,
      Boolean includeMetadataChanges,
      DocumentSnapshot.ServerTimestampBehavior serverTimestampBehavior,
      ListenSource source) {
    this.query = query;
    this.metadataChanges =
        includeMetadataChanges ? MetadataChanges.INCLUDE : MetadataChanges.EXCLUDE;
    this.serverTimestampBehavior = serverTimestampBehavior;
    this.source = source;
  }

  @Override
  public void onListen(Object arguments, EventSink events) {
    // Timestamps are taken ONLY when logging is on: they exist to feed the debug lines and nothing
    // reads them otherwise. `listenStartMs` stays 0 when off, which the snapshot line below
    // reports as sinceListenMs=-1 (the property can be flipped mid-session, so a snapshot can be
    // logged for a listen that was not).
    final boolean debug = debugLogging();
    listenStartMs = debug ? SystemClock.elapsedRealtime() : 0L;
    if (debug) {
      Log.d(DEBUG_TAG, "onListen h=" + handlerId + " query=" + query);
    }

    terminated.set(false);
    batchGate = new BatchDeliveryGate(mainHandler, MAX_IN_FLIGHT_BATCHES, SLOT_WAIT_TIMEOUT_MS);
    conversionExecutor =
        new GuardedConversionExecutor(
            Executors.newSingleThreadExecutor(
                runnable -> new Thread(runnable, "SwineQSChanges-" + handlerId)));

    SnapshotListenOptions.Builder optionsBuilder = new SnapshotListenOptions.Builder();
    optionsBuilder.setMetadataChanges(metadataChanges);
    optionsBuilder.setSource(source);
    // SwineTech: this handler only ever surfaces document changes (never the full result set), so
    // let the Firestore SDK retain projected documents instead of full data in the query view.
    optionsBuilder.setChangesOnly(true);
    // SwineTech (ANR fix): keep document inflation off the Android main thread. See the
    // conversionExecutor field comment.
    optionsBuilder.setExecutor(conversionExecutor);

    listenerRegistration =
        query.addSnapshotListener(
            optionsBuilder.build(),
            (querySnapshotChanges, exception) -> {
              // Runs on conversionExecutor, NOT on main.
              if (terminated.get()) {
                return;
              }

              if (exception != null) {
                terminateWithError(
                    events, exception.getMessage(), ExceptionConverter.createDetails(exception));
                return;
              }

              // Emit the Pigeon object directly; the Pigeon-aware codec serializes the
              // nested `InternalDocumentChange` / `InternalSnapshotMetadata` with their
              // proper type codes. Pigeon 26 no longer flattens nested types via `.toList()`.
              //
              // SwineTech: a large change set is delivered as several sequential events (see
              // DELIVERY_BATCH_SIZE). This plugin does NOT merge them — the application consumer
              // must merge the batches (by document id) and treat isPartial == false as the
              // signal that the whole change set has arrived (see pigflow #244).
              //
              // The batch loop is guarded: a failure part way through (conversion, codec
              // serialization, ...) after one or more isPartial=true batches would otherwise
              // strand the consumer waiting for the terminal isPartial=false batch that never
              // comes. On failure, terminate the stream deterministically, mirroring the
              // listener-error branch above. (OutOfMemoryError is an Error, not an Exception, and
              // is intentionally not caught — recovery after OOM is unreliable.)
              try {
                // Resolved ONCE per snapshot and threaded down into emitBatch, rather than called
                // per batch: Log.isLoggable is a JNI system-property read, and the per-batch call
                // site runs on the main thread — it would have cost more than the timestamps it
                // guards. Re-read here rather than reusing onListen's value so that flipping the
                // property mid-session takes effect without a restart.
                final boolean debugSnapshot = debugLogging();
                long snapshotMs = debugSnapshot ? SystemClock.elapsedRealtime() : 0L;
                List<DocumentChange> documentChanges = querySnapshotChanges.getDocumentChanges();
                SnapshotMetadata metadata = querySnapshotChanges.getMetadata();
                int total = documentChanges.size();
                int batches =
                    total <= DELIVERY_BATCH_SIZE
                        ? 1
                        : (total + DELIVERY_BATCH_SIZE - 1) / DELIVERY_BATCH_SIZE;

                if (total <= DELIVERY_BATCH_SIZE) {
                  // A snapshot at or under the batch size — including an EMPTY result set (a new
                  // barn, or a barn with zero on-farm litters) — is delivered as exactly one
                  // terminal isPartial == false event, so the consumer's initial-load gate always
                  // completes.
                  emitBatch(events, metadata, documentChanges, false, 1, batches, debugSnapshot);
                } else {
                  int batchIndex = 0;
                  for (int start = 0; start < total; start += DELIVERY_BATCH_SIZE) {
                    if (terminated.get()) {
                      break;
                    }
                    int end = Math.min(start + DELIVERY_BATCH_SIZE, total);
                    // isPartial is true for every batch except the last, so the consumer knows
                    // when the whole change set has been delivered.
                    boolean isPartial = end < total;
                    emitBatch(
                        events,
                        metadata,
                        documentChanges.subList(start, end),
                        isPartial,
                        ++batchIndex,
                        batches,
                        debugSnapshot);
                  }
                }

                // SwineTech DEBUG: one line per snapshot — change count, batches, cache-vs-server
                // (fromCache), SDK latency (subscribe->snapshot arrival), and the total conversion
                // cost. `thread` proves the conversion executor is actually in use: if this ever
                // prints "main", the setExecutor() wiring above has regressed and the ANR fix is
                // silently inert.
                if (debugSnapshot) {
                  Log.d(
                      DEBUG_TAG,
                      "converted h="
                          + handlerId
                          + " changes="
                          + total
                          + " batches="
                          + batches
                          + " fromCache="
                          + metadata.isFromCache()
                          // -1 when logging was switched on after this stream started listening,
                          // so listenStartMs was never taken.
                          + " sinceListenMs="
                          + (listenStartMs == 0L ? -1L : snapshotMs - listenStartMs)
                          + " convertMs="
                          + (SystemClock.elapsedRealtime() - snapshotMs)
                          + " thread="
                          + Thread.currentThread().getName());
                }
              } catch (InterruptedException e) {
                // shutdownNow() from onCancel/terminateWithError interrupted a blocked acquire
                // mid-delivery. The sink is already gone; restore the flag and stop quietly.
                Thread.currentThread().interrupt();
              } catch (Exception e) {
                terminateWithError(events, e.getMessage(), ExceptionConverter.createDetails(e));
              }
            });
  }

  /**
   * Converts one batch on the calling (conversion) thread, then hands it to main for delivery.
   *
   * <p>SwineTech (ANR fix): the expensive half — {@code DocumentSnapshot.getData()}, which inflates
   * each byte-backed {@code ObjectValue} from protobuf — happens here, off main. Only {@code
   * events.success()} is posted to main, because it must be: {@code EventChannel.EventSink} is
   * {@code @UiThread}, {@code BinaryMessenger} is documented as single-threaded, and {@code
   * DartMessenger.send()} mutates an unsynchronized {@code nextReplyId}. Posting per batch is also
   * what restores an inter-batch yield: each batch becomes its own main-looper turn, so input
   * dispatch is serviced between batches instead of the whole herd landing in one runnable.
   */
  private void emitBatch(
      EventSink events,
      SnapshotMetadata metadata,
      List<DocumentChange> changes,
      boolean isPartial,
      int batchIndex,
      int batchCount,
      boolean debug)
      throws InterruptedException {
    // Backpressure. The slot is held until the consumer acknowledges hydration, so this bounds
    // the whole pipeline rather than just the native side. `holdsSlot` is false when the wait timed
    // out, in which case this batch owns no permit and must not release one — releasing on its
    // behalf would hand back a permit belonging to a different batch.
    final boolean holdsSlot = batchGate.acquireSlot();

    boolean posted = false;
    try {
      GeneratedAndroidFirebaseFirestore.InternalQuerySnapshotChanges payload =
          PigeonParser.toPigeonQuerySnapshotChanges(
              metadata, changes, serverTimestampBehavior, isPartial);

      // Zero clock reads and zero property lookups on the default path: `debug` was resolved once
      // for the whole snapshot, and these timestamps exist solely to feed the line below.
      long postedAtMs = debug ? SystemClock.elapsedRealtime() : 0L;
      // SwineTech (SP30-9652): resolved to an int BEFORE the post, and the lambda below reads this
      // instead of `changes`. `changes` is a subList VIEW over the snapshot's entire
      // documentChanges list — AbstractList$SubList keeps a strong reference to its parent — and
      // Java captures every free variable a lambda body mentions whether or not the branch
      // mentioning it runs. So reading changes.size() inside the debug-gated block was enough to
      // keep the whole ~16K-document change list reachable from each queued runnable, past the
      // Firestore callback's return and on top of the decoded payload the runnable already holds.
      // Four bytes instead.
      final int docCount = changes.size();
      batchGate.postBatch(
          () -> {
            // Already torn down: this batch will never reach Dart, so nothing will ever
            // acknowledge it. Hand its slot back here or it stays held until shutdown.
            if (terminated.get()) {
              if (holdsSlot) {
                batchGate.abandonSlot();
              }

              return;
            }

            try {
              {
                long emitStartMs = debug ? SystemClock.elapsedRealtime() : 0L;
                events.success(payload);
                // SwineTech DEBUG: `emitMs` is the codec encode + channel send, the part that
                // cannot leave main. It should now be a small per-batch cost rather than one
                // unbroken block. `waitMs` is how long the batch sat queued behind other main-
                // thread work — a large value means main is the bottleneck, not conversion.
                //
                // This is the hot one: it fires once per batch (66 times for a 16K-document herd
                // at DELIVERY_BATCH_SIZE=250), on the main thread.
                if (debug) {
                  Log.d(
                      DEBUG_TAG,
                      "emit h="
                          + handlerId
                          + " batch="
                          + batchIndex
                          + "/"
                          + batchCount
                          + " docs="
                          + docCount
                          + " waitMs="
                          + (emitStartMs - postedAtMs)
                          + " emitMs="
                          + (SystemClock.elapsedRealtime() - emitStartMs));
                }
              }
            } catch (Exception e) {
              // SwineTech (SP30-9652): events.success() is the Pigeon codec encode plus the channel
              // send, and it moved in here when delivery became asynchronous. Before that it ran
              // inside the snapshot callback's try/catch, so an encode failure
              // (StandardMessageCodec.writeValue on an unexpected Firestore value) or a messenger
              // failure produced events.error + events.endOfStream deterministically. A Runnable has
              // nowhere to throw: an exception escaping run() reaches Looper.loop() uncaught, which
              // is process death — and if the process somehow survives, `terminated` stays false,
              // the conversion thread keeps posting, and the consumer is left with isPartial=true
              // batches and no terminal batch, no onError and no onDone. Restore the old contract.
              if (holdsSlot) {
                batchGate.abandonSlot();
              }

              terminateWithError(events, e.getMessage(), ExceptionConverter.createDetails(e));
            }

            // NOTE deliberately no release on the success path. The slot stays held until the
            // consumer calls back through acknowledgeHydration(), which is what makes
            // MAX_IN_FLIGHT_BATCHES an end-to-end bound instead of a native-only one.
          });
      posted = true;
    } finally {
      if (!posted && holdsSlot) {
        // Conversion threw before anything was queued, so no acknowledgement will ever arrive for
        // this batch — hand the slot back or it is lost until shutdown.
        batchGate.abandonSlot();
      }
    }
  }

  /**
   * Called by the consumer, on the main thread, once it has finished hydrating one delivered batch.
   *
   * <p>Returns a slot to {@link BatchDeliveryGate}. Without this the semaphore bounds only how many
   * converted payloads exist natively; the payload then lives in the Dart hydration queue, which
   * nothing caps. Counting rather than identifying batches is sufficient because delivery is
   * strictly ordered and both consumers hydrate strictly in order — which also means no batch
   * identity has to be threaded through the Pigeon payload.
   */
  public void acknowledgeHydration() {
    BatchDeliveryGate gate = batchGate;

    if (gate != null) {
      gate.acknowledge();
    }
  }

  /**
   * Terminates the stream deterministically after a listener or conversion failure, so a consumer
   * that has already seen isPartial=true batches is not left waiting forever for a terminal batch.
   * Safe to call from the conversion thread; the sink calls are marshalled to main.
   */
  private void terminateWithError(EventSink events, String message, Map<String, String> details) {
    if (!terminated.compareAndSet(false, true)) {
      return;
    }

    // Drop batches converted but not yet delivered: the consumer is about to be told the stream
    // failed, and a partial batch arriving after endOfStream would violate the delivery contract.
    // Scoped to BATCH_TOKEN, never null — see the token fields for why clearing the whole queue
    // could drop this very notification.
    batchGate.dropPendingBatches();
    batchGate.postTerminal(
        () -> {
          events.error(DEFAULT_ERROR_CODE, message, details);
          events.endOfStream();
        });

    releaseResources();
  }

  @Override
  public void onCancel(Object arguments) {
    if (debugLogging()) {
      Log.d(DEBUG_TAG, "onCancel h=" + handlerId);
    }
    terminated.set(true);
    // Drop any batch already converted but still queued — the sink is going away. Scoped to
    // BATCH_TOKEN so a terminateWithError notification that has not run yet survives: the consumer
    // still needs to be told the stream failed rather than left waiting on its watchdog.
    batchGate.dropPendingBatches();
    releaseResources();
  }

  /**
   * Detaches the Firestore listener and stops the conversion thread.
   *
   * <p>The stop goes through {@link GuardedConversionExecutor}, which is what makes it safe to do
   * while the Firestore SDK still holds a reference to the executor — see that class for why doing
   * it directly was a process crash.
   */
  private void releaseResources() {
    if (listenerRegistration != null) {
      listenerRegistration.remove();
      listenerRegistration = null;
    }

    GuardedConversionExecutor executor = conversionExecutor;
    conversionExecutor = null;
    if (executor != null) {
      executor.stop();
    }

    // Reclaim every slot still held by a batch the consumer will now never acknowledge, and make
    // late acknowledgements inert. Without this a conversion thread parked in acquireSlot() would
    // wait out the full timeout for nothing on every teardown.
    BatchDeliveryGate gate = batchGate;
    if (gate != null) {
      gate.shutdown();
    }
  }

  /**
   * The {@link Executor} actually handed to {@code setExecutor}, wrapping the real single-thread
   * conversion pool.
   *
   * <p>SwineTech (SP30-9652): this indirection exists because shutting down an executor the
   * Firestore SDK still holds is a hard crash. {@code ListenerRegistration.remove()} only
   * <em>enqueues</em> {@code stopListening} on Firestore's AsyncQueue, so a snapshot already queued
   * ahead of it is still raised afterwards — and {@code AsyncEventListener.onEvent} calls {@code
   * executor.execute(...)} <em>unconditionally</em> (its {@code muted} check lives inside the
   * submitted Runnable, not around the submission). Executing on a shut-down ThreadPoolExecutor
   * throws {@link RejectedExecutionException}, which {@code AsyncQueue}'s shutdown-aware executor
   * catches and converts into {@code AsyncQueue.panic()} — rethrown on the main looper as {@code
   * RuntimeException("Internal error in Cloud Firestore")}, i.e. process death. The window opened on
   * every barn change, recovery reload and logout. Verified against firebase-firestore 26.4.0.
   *
   * <p>It is specific to this fork: before {@code setExecutor} was called at all the default was
   * {@code TaskExecutors.MAIN_THREAD}, which is never shut down, so the crash class arrived with the
   * off-main-thread conversion and had to leave with a containment boundary of its own.
   *
   * <p>Dropping post-teardown submissions is correct rather than merely defensive — the sink is
   * already gone, so the converted batch would be discarded on arrival anyway.
   */
  /**
   * Bounds how many batches are in flight between conversion and hydrated Dart state, and owns the
   * main-thread posting that goes with it.
   *
   * <p>Extracted from the handler for two reasons. It is the only part of the delivery path with no
   * Firestore, EventChannel or Pigeon dependency, so it can be unit-tested directly — the handler
   * cannot be constructed in a test without mocking Query, QuerySnapshot and PigeonParser. And the
   * slot accounting is fiddly enough to deserve being reasoned about on its own: a slot is taken on
   * the conversion thread, returned on the main thread, and may have to be reclaimed by a teardown
   * racing both.
   *
   * <p>Distinct Handler tokens matter. Batch deliveries and the terminal error/endOfStream
   * notification used to share an untokened {@code post()} while teardown cleared the queue with
   * {@code removeCallbacksAndMessages(null)}, so a cancel arriving while main was congested could
   * discard the very notification that guard exists to send, leaving the consumer with
   * isPartial=true batches and no terminal batch, no onError and no onDone.
   */
  static final class BatchDeliveryGate {
    private static final Object BATCH_TOKEN = new Object();
    private static final Object TERMINAL_TOKEN = new Object();

    private final Handler handler;
    private final Semaphore slots;
    private final long waitTimeoutMs;

    /**
     * Slots taken but not yet returned. Tracked separately from the semaphore's own count so
     * teardown can reclaim exactly what is outstanding, and so a stray acknowledgement can never
     * release more than were taken — which would let the bound drift upward for the rest of the
     * stream's life.
     */
    private final AtomicInteger outstanding = new AtomicInteger();

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    BatchDeliveryGate(Handler handler, int capacity, long waitTimeoutMs) {
      this.handler = handler;
      this.slots = new Semaphore(capacity);
      this.waitTimeoutMs = waitTimeoutMs;
    }

    /**
     * Conversion thread. Returns true when this batch owns a slot and is therefore responsible for
     * returning it — via an acknowledgement from the consumer, {@link #abandonSlot()}, or {@link
     * #shutdown()}.
     *
     * <p>Times out rather than blocking forever: see SLOT_WAIT_TIMEOUT_MS on the handler.
     */
    boolean acquireSlot() throws InterruptedException {
      if (stopped.get()) {
        return false;
      }

      if (slots.tryAcquire(waitTimeoutMs, TimeUnit.MILLISECONDS)) {
        outstanding.incrementAndGet();

        return true;
      }

      Log.w(
          DEBUG_TAG,
          "no delivery slot within "
              + waitTimeoutMs
              + "ms — the consumer has stopped acknowledging hydration. Proceeding unbounded for"
              + " this batch rather than stalling the SDK callback thread, which also carries error"
              + " delivery.");

      return false;
    }

    /** Main thread, from the consumer. Returns one held slot. */
    void acknowledge() {
      returnSlot();
    }

    /** Returns a slot for a batch that will never be hydrated, so is never acknowledged. */
    void abandonSlot() {
      returnSlot();
    }

    private void returnSlot() {
      if (stopped.get()) {
        return;
      }

      // Only release against a slot actually outstanding. getAndUpdate keeps the check and the
      // decrement atomic across the conversion thread and main.
      if (outstanding.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
        slots.release();
      }
    }

    void postBatch(Runnable delivery) {
      handler.postAtTime(delivery, BATCH_TOKEN, SystemClock.uptimeMillis());
    }

    void postTerminal(Runnable notification) {
      handler.postAtTime(notification, TERMINAL_TOKEN, SystemClock.uptimeMillis());
    }

    /** Drops queued batch deliveries WITHOUT touching a pending terminal notification. */
    void dropPendingBatches() {
      handler.removeCallbacksAndMessages(BATCH_TOKEN);
    }

    void shutdown() {
      if (!stopped.compareAndSet(false, true)) {
        return;
      }

      handler.removeCallbacksAndMessages(BATCH_TOKEN);

      int held = outstanding.getAndSet(0);
      if (held > 0) {
        slots.release(held);
      }
    }

    // Visible for testing.
    int outstandingSlots() {
      return outstanding.get();
    }

    // Visible for testing.
    int availableSlots() {
      return slots.availablePermits();
    }
  }

  // Package-private, not private, so QuerySnapshotChangesStreamHandlerTest can reach it.
  // This is the mechanism the SP30-9652 crash fix rests on, so it is the one piece that
  // most needs a regression test — and it has no Firestore or Android dependencies, so a
  // plain JVM test can cover it exactly.
  @SuppressWarnings("WeakerAccess")
  static final class GuardedConversionExecutor implements Executor {
    private final ExecutorService delegate;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    GuardedConversionExecutor(ExecutorService delegate) {
      this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
      if (stopped.get()) {
        return;
      }

      try {
        delegate.execute(command);
      } catch (RejectedExecutionException ignored) {
        // Raced our own stop(): the same situation as the flag above, and on no account something
        // to let propagate back into Firestore's AsyncQueue.
      }
    }

    /**
     * Stops accepting work, then interrupts the conversion thread. {@code shutdownNow()} is
     * deliberate: it releases a thread parked in {@link Semaphore#acquire()} whose permits will
     * never be returned now that the queued deliveries holding them have been dropped.
     */
    void stop() {
      stopped.set(true);
      delegate.shutdownNow();
    }
  }
}
