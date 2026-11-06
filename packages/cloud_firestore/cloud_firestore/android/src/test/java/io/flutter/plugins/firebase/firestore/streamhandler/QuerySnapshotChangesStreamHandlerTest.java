/*
 * Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
 * for details. All rights reserved. Use of this source code is governed by a
 * BSD-style license that can be found in the LICENSE file.
 */

package io.flutter.plugins.firebase.firestore.streamhandler;

import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenSource;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * SwineTech (ENG-1396): a snapshotChanges() listener cancelled while it registers leaves its
 * handler stored but never listened to, and plugin teardown still cancels every stored handler.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QuerySnapshotChangesStreamHandlerTest {

  @Test
  public void cancelBeforeListenIsSafeAndRepeatable() {
    QuerySnapshotChangesStreamHandler handler =
        new QuerySnapshotChangesStreamHandler(
            null, false, DocumentSnapshot.ServerTimestampBehavior.NONE, ListenSource.DEFAULT);

    handler.onCancel(null);
    handler.onCancel(null);
  }
}
