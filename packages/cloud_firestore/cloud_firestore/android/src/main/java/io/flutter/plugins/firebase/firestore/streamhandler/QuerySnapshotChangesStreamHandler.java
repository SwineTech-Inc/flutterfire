/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import static io.flutter.plugins.firebase.firestore.FlutterFirebaseFirestorePlugin.DEFAULT_ERROR_CODE;

import android.util.Log;
import com.google.firebase.firestore.DocumentChange;
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
import java.util.ArrayList;
import java.util.Map;

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

            Log.e(
              "QuerySnapshotChangesStreamHandler",
              "OnListen exception: " + exception.getMessage());

            onCancel(null);
          } else {
            ArrayList<Object> toListResult = new ArrayList<Object>(2);

            ArrayList<Object> documentChanges =
              new ArrayList<Object>(querySnapshotChanges.getDocumentChanges().size());

            for (DocumentChange documentChange : querySnapshotChanges.getDocumentChanges()) {
              documentChanges.add(
                PigeonParser.toPigeonDocumentChange(documentChange, serverTimestampBehavior)
                  .toList());
            }

            toListResult.add(documentChanges);
            toListResult.add(
              PigeonParser.toPigeonSnapshotMetadata(querySnapshotChanges.getMetadata())
                .toList());

            events.success(toListResult);
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
