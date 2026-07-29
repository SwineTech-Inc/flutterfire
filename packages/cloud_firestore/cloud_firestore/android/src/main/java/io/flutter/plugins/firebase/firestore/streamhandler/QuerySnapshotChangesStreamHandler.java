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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  // outrun the main thread and pile up pigeon payloads — precisely the unbounded accumulation this
  // fork exists to prevent. A permit is taken before a batch is converted and released after its
  // events.success() has run, so peak extra memory is bounded to this many batches regardless of
  // how far ahead conversion gets.
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
  private ExecutorService conversionExecutor;
  private Semaphore inFlightBatches;

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
    inFlightBatches = new Semaphore(MAX_IN_FLIGHT_BATCHES);
    conversionExecutor =
        Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "SwineQSChanges-" + handlerId));

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
      mainHandler.post(
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
                          + changes.size()
                          + " waitMs="
                          + (emitStartMs - postedAtMs)
                          + " emitMs="
                          + (SystemClock.elapsedRealtime() - emitStartMs));
                }
              }
            } finally {
              inFlightBatches.release();
            }
          });
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
    mainHandler.removeCallbacksAndMessages(null);
    mainHandler.post(
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
    // Drop any batch already converted but still queued — the sink is going away.
    mainHandler.removeCallbacksAndMessages(null);
    releaseResources();
  }

  /**
   * Detaches the Firestore listener and stops the conversion thread. {@code shutdownNow()} is
   * deliberate: it interrupts a conversion thread parked in {@link Semaphore#acquire()} whose
   * permits will never be returned now that queued deliveries have been dropped.
   */
  private void releaseResources() {
    if (listenerRegistration != null) {
      listenerRegistration.remove();
      listenerRegistration = null;
    }

    ExecutorService executor = conversionExecutor;
    conversionExecutor = null;
    if (executor != null) {
      executor.shutdownNow();
    }
  }
}
