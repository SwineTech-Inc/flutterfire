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
import java.util.concurrent.atomic.AtomicBoolean;

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

  // SwineTech (ANR fix): how many converted-but-not-yet-delivered batches may exist at once.
  //
  // Conversion now runs on a background thread while delivery runs on main, so conversion can
  // outrun the main thread and pile up pigeon payloads. A permit is taken before a batch is
  // converted and released after its events.success() has run, so the number of resident CONVERTED
  // PAYLOADS is bounded to this many batches.
  //
  // SwineTech (SP30-9652): that is the whole of what it bounds — an earlier version of this comment
  // claimed "peak extra memory is bounded to this many batches regardless of how far ahead
  // conversion gets", which is not true. acquire() blocks the very thread the Firestore SDK submits
  // to, and Executors.newSingleThreadExecutor is backed by an UNBOUNDED LinkedBlockingQueue, so
  // while conversion is parked here AsyncEventListener keeps calling execute(...) for every
  // subsequent snapshot and each queued Runnable pins its own QuerySnapshot. The accumulation is
  // relocated from converted payloads to queued raw snapshots, not eliminated. Raw snapshots are
  // much the cheaper of the two (setChangesOnly(true) leaves the query view holding projected
  // documents), which is why this is documented rather than restructured — but do not read the
  // permit count as an end-to-end memory bound. Note also that error delivery for this listener
  // goes through the same single executor, so onEvent(null, error) queues behind a parked
  // conversion.
  //
  // SwineTech (SP30-9652): lowered 3 -> 2 after the moto g fast peak-heap measurement above. 2
  // still lets one batch convert while another awaits delivery (keeping conversion and the main
  // thread overlapped), but cuts native-side resident payloads from 3x500 to 2x250 documents. Drop
  // to 1 if peak is still high — that serializes conversion behind delivery, costing load time.
  //
  // NOTE this bounds only the NATIVE side. Once events.success() runs the permit is released, but
  // the payload then lives in the Dart consumer's hydration queue, which nothing here caps. Truly
  // bounding end-to-end residency needs Dart to acknowledge hydration before the permit is
  // returned — a native round-trip that does not exist yet.
  private static final int MAX_IN_FLIGHT_BATCHES = 2;

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
  private Semaphore inFlightBatches;

  // Set once the stream is torn down (onCancel) or terminated by an error. Read from both the
  // conversion thread and main, so posted deliveries can no-op against a dead sink.
  private final AtomicBoolean terminated = new AtomicBoolean(false);

  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // SwineTech (SP30-9652): distinct Handler tokens, so dropping queued batch deliveries at teardown
  // cannot also drop the terminal error notification. Both used to go through an untokened post()
  // while teardown cleared the queue with removeCallbacksAndMessages(null) — which could discard a
  // terminateWithError that had not run yet, leaving the consumer holding isPartial=true batches
  // with no terminal batch, no onError and no onDone. That is precisely the stranding the
  // "terminate the stream deterministically" guard exists to prevent, just displaced onto the Dart
  // side's inter-batch watchdog 60 s later.
  private static final Object BATCH_TOKEN = new Object();
  private static final Object TERMINAL_TOKEN = new Object();

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
    inFlightBatches = new Semaphore(MAX_IN_FLIGHT_BATCHES);
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
    // Backpressure: block conversion while MAX_IN_FLIGHT_BATCHES payloads are already awaiting
    // delivery, so getting ahead of the main thread cannot grow without bound.
    inFlightBatches.acquire();

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
      mainHandler.postAtTime(
          () -> {
            try {
              if (!terminated.get()) {
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
              terminateWithError(events, e.getMessage(), ExceptionConverter.createDetails(e));
            } finally {
              inFlightBatches.release();
            }
          },
          BATCH_TOKEN,
          SystemClock.uptimeMillis());
      posted = true;
    } finally {
      if (!posted) {
        // Conversion threw before anything was queued — releasing here keeps a failed batch from
        // permanently consuming a permit and deadlocking later deliveries.
        inFlightBatches.release();
      }
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
    mainHandler.removeCallbacksAndMessages(BATCH_TOKEN);
    mainHandler.postAtTime(
        () -> {
          events.error(DEFAULT_ERROR_CODE, message, details);
          events.endOfStream();
        },
        TERMINAL_TOKEN,
        SystemClock.uptimeMillis());

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
    mainHandler.removeCallbacksAndMessages(BATCH_TOKEN);
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
