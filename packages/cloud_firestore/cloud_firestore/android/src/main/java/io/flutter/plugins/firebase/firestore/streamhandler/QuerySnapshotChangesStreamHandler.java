/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static io.flutter.plugins.firebase.firestore.FlutterFirebaseFirestorePlugin.DEFAULT_ERROR_CODE;

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

  // SwineTech: any snapshot whose change set exceeds this size is split into batches of this many
  // document changes, one method-channel message per batch. This is NOT only the initial load — a
  // large delta (bulk write, or reconnect after extended offline edits) is split too. Delivering a
  // huge change set as ONE message forces a single contiguous multi-MB allocation (an ArrayList of
  // pigeon objects plus the serialized ByteBuffer) — the usual proximate OOM / fragmentation
  // trigger. Splitting guarantees no single allocation spans the whole result set.
  //
  // It does NOT by itself bound peak memory: getDocumentChanges() below holds the full SDK-side
  // change list live for the whole callback, and with synchronous emit the per-batch buffers can
  // queue before Dart drains them. The primary memory lever is setChangesOnly(true) (below) plus
  // the fork's changes-only projected View / per-field cache / byte-backed ObjectValue — see
  // charlotte/SWINETECH_FIREBASE_FORK.md.
  //
  // Batches are emitted synchronously (no inter-batch pacing): pacing was measured to add load
  // latency for no memory win, so it was dropped.
  private static final int DELIVERY_BATCH_SIZE = 500;

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
                // SwineTech: a large change set is delivered as several sequential events (see
                // DELIVERY_BATCH_SIZE). This plugin does NOT merge them — the application consumer
                // must merge the batches (by document id) and treat isPartial == false as the
                // signal that the whole change set has arrived (see pigflow #244).
                //
                // The batch loop is guarded: unlike the old single-message path, a failure part
                // way through (conversion, codec serialization, ...) after one or more
                // isPartial=true batches would otherwise strand the consumer waiting for the
                // terminal isPartial=false batch that never comes. On failure, terminate the
                // stream deterministically, mirroring the listener-error branch above.
                // (OutOfMemoryError is an Error, not an Exception, and is intentionally not
                // caught — recovery after OOM is unreliable.)
                try {
                  List<DocumentChange> documentChanges = querySnapshotChanges.getDocumentChanges();
                  SnapshotMetadata metadata = querySnapshotChanges.getMetadata();
                  int total = documentChanges.size();
                  if (total <= DELIVERY_BATCH_SIZE) {
                    events.success(
                        PigeonParser.toPigeonQuerySnapshotChanges(
                            metadata, documentChanges, serverTimestampBehavior, false));
                  } else {
                    for (int start = 0; start < total; start += DELIVERY_BATCH_SIZE) {
                      int end = Math.min(start + DELIVERY_BATCH_SIZE, total);
                      // isPartial is true for every batch except the last, so the consumer knows
                      // when the whole change set has been delivered.
                      boolean isPartial = end < total;
                      events.success(
                          PigeonParser.toPigeonQuerySnapshotChanges(
                              metadata,
                              documentChanges.subList(start, end),
                              serverTimestampBehavior,
                              isPartial));
                    }
                  }
                } catch (Exception e) {
                  Map<String, String> exceptionDetails = ExceptionConverter.createDetails(e);
                  events.error(DEFAULT_ERROR_CODE, e.getMessage(), exceptionDetails);
                  events.endOfStream();
                  onCancel(null);
                }
              }
            });
  }

  @Override
  public void onCancel(Object arguments) {
    if (listenerRegistration != null) {
      listenerRegistration.remove();
      listenerRegistration = null;
    }
  }
}
