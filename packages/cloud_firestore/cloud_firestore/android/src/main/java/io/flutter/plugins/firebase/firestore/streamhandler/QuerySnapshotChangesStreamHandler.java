/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static io.flutter.plugins.firebase.firestore.FlutterFirebaseFirestorePlugin.DEFAULT_ERROR_CODE;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenSource;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.SnapshotListenOptions;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugins.firebase.firestore.utils.ExceptionConverter;
import io.flutter.plugins.firebase.firestore.utils.PigeonParser;
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
                events.success(
                    PigeonParser.toPigeonQuerySnapshotChanges(
                        querySnapshotChanges, serverTimestampBehavior));
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
