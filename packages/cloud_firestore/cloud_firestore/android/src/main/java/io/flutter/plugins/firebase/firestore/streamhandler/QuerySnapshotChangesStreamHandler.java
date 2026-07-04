/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static io.flutter.plugins.firebase.firestore.FlutterFirebaseFirestorePlugin.DEFAULT_ERROR_CODE;

import android.os.Handler;
import android.os.Looper;
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
import io.flutter.plugins.firebase.firestore.utils.ExceptionConverter;
import io.flutter.plugins.firebase.firestore.utils.PigeonParser;
import java.util.List;
import java.util.Map;

// SwineTech: streams a "true" query snapshot that carries only the changed
// documents (document changes + metadata), not the full result set. Mirrors
// QuerySnapshotsStreamHandler but emits InternalQuerySnapshotChanges.
public class QuerySnapshotChangesStreamHandler implements StreamHandler {

  ListenerRegistration listenerRegistration;

  Query query;
  MetadataChanges metadataChanges;
  DocumentSnapshot.ServerTimestampBehavior serverTimestampBehavior;

  ListenSource source;

  // SwineTech: a large initial snapshot arrives as the whole result set expressed as ADDED
  // document changes. Delivering it as a single method-channel message materializes every
  // document at once (pigeon objects + serialized buffer) and drives the cold-start memory peak.
  // Split large change lists into batches of this size, emitting one message per batch, so peak
  // memory stays bounded to a single batch. Ordinary (smaller) deltas are sent as one message.
  private static final int INITIAL_DELIVERY_BATCH_SIZE = 500;

  // SwineTech: batches of a large snapshot are delivered one per main-looper turn (with a short
  // gap) rather than in a synchronous loop, so the concurrent GC can reclaim each batch's transient
  // inflation (getData maps + pigeon objects) before the next is built. Without this the whole
  // herd's inflation piles up as garbage in one burst and drives the cold-start peak.
  private static final long BATCH_DELIVERY_INTERVAL_MS = 8;

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
    SnapshotListenOptions.Builder optionsBuilder = new SnapshotListenOptions.Builder();
    optionsBuilder.setMetadataChanges(metadataChanges);
    optionsBuilder.setSource(source);
    // SwineTech: this handler only ever surfaces document changes (never the full result set), so
    // let the Firestore SDK retain projected documents instead of full data in the query view.
    optionsBuilder.setChangesOnly(true);

    listenerRegistration =
        query.addSnapshotListener(
            optionsBuilder.build(),
            (querySnapshotChanges, exception) -> {
              if (exception != null) {
                Map<String, String> exceptionDetails = ExceptionConverter.createDetails(exception);
                events.error(DEFAULT_ERROR_CODE, exception.getMessage(), exceptionDetails);
                events.endOfStream();

                onCancel(null);
              } else {
                // Emit the Pigeon object directly; the Pigeon-aware codec serializes the
                // nested `InternalDocumentChange` / `InternalSnapshotMetadata` with their
                // proper type codes. Pigeon 26 no longer flattens nested types via `.toList()`.
                //
                // SwineTech: a large snapshot (notably the initial one, which carries the whole
                // result set as ADDED changes) is delivered in batches so peak memory during
                // conversion + serialization stays bounded to one batch rather than the whole
                // result set. The consumer merges document changes incrementally by document id,
                // so one logical snapshot split across several sequential events is equivalent.
                List<DocumentChange> documentChanges = querySnapshotChanges.getDocumentChanges();
                SnapshotMetadata metadata = querySnapshotChanges.getMetadata();
                if (documentChanges.size() <= INITIAL_DELIVERY_BATCH_SIZE) {
                  events.success(
                      PigeonParser.toPigeonQuerySnapshotChanges(
                          metadata, documentChanges, serverTimestampBehavior, false));
                } else {
                  // Deliver the first batch now and pace the remaining batches across main-looper
                  // turns (see deliverBatch) so the GC can reclaim each batch before the next.
                  deliverBatch(events, metadata, documentChanges, 0);
                }
              }
            });
  }

  // SwineTech: delivers one batch of a large snapshot, then schedules the next on the main looper
  // (rather than looping synchronously) so the concurrent GC reclaims this batch's transient
  // inflation before the next is built. isPartial is true for every batch except the last, so the
  // consumer only treats the initial result set as complete once the final (non-partial) batch
  // arrives.
  private void deliverBatch(
      EventSink events, SnapshotMetadata metadata, List<DocumentChange> changes, int start) {
    // Stop if the stream was torn down (onCancel / onError) while batches were still pending.
    if (listenerRegistration == null) {
      return;
    }
    int total = changes.size();
    int end = Math.min(start + INITIAL_DELIVERY_BATCH_SIZE, total);
    boolean isPartial = end < total;
    events.success(
        PigeonParser.toPigeonQuerySnapshotChanges(
            metadata, changes.subList(start, end), serverTimestampBehavior, isPartial));
    if (isPartial) {
      mainHandler.postDelayed(
          () -> deliverBatch(events, metadata, changes, end), BATCH_DELIVERY_INTERVAL_MS);
    }
  }

  @Override
  public void onCancel(Object arguments) {
    if (listenerRegistration != null) {
      listenerRegistration.remove();
      listenerRegistration = null;
    }
  }
}
