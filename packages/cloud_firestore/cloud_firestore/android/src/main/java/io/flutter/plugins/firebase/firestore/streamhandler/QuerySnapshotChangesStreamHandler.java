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

  // SwineTech: a large initial snapshot arrives as the whole result set expressed as ADDED
  // document changes. Delivering it as ONE method-channel message materializes the whole herd at
  // once (a single multi-MB serialized buffer with every pigeon object live together). Split large
  // change lists into batches of this size, one message per batch, so no single allocation spans
  // the whole result set. Ordinary (smaller) deltas are sent as one message.
  //
  // Batches are emitted synchronously (no inter-batch pacing): memory during the initial load is
  // already bounded by the fork's per-field cache + changes-only projected View + byte-backed
  // ObjectValue, so pacing only added load latency and queued batch buffers for no memory win.
  private static final int INITIAL_DELIVERY_BATCH_SIZE = 500;

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
                int total = documentChanges.size();
                if (total <= INITIAL_DELIVERY_BATCH_SIZE) {
                  events.success(
                      PigeonParser.toPigeonQuerySnapshotChanges(
                          metadata, documentChanges, serverTimestampBehavior, false));
                } else {
                  for (int start = 0; start < total; start += INITIAL_DELIVERY_BATCH_SIZE) {
                    int end = Math.min(start + INITIAL_DELIVERY_BATCH_SIZE, total);
                    // isPartial is true for every batch except the last, so the consumer knows when
                    // the whole initial result set has been delivered.
                    boolean isPartial = end < total;
                    events.success(
                        PigeonParser.toPigeonQuerySnapshotChanges(
                            metadata,
                            documentChanges.subList(start, end),
                            serverTimestampBehavior,
                            isPartial));
                  }
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
