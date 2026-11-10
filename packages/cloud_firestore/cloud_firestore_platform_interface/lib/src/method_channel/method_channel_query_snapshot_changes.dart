// ignore_for_file: require_trailing_commas
// Copyright 2017, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import 'package:cloud_firestore_platform_interface/cloud_firestore_platform_interface.dart';
import 'package:flutter/services.dart';

import 'method_channel_document_change.dart';
import 'method_channel_firestore.dart';

/// An implementation of [QuerySnapshotPlatform] that uses [MethodChannel] to
/// communicate with Firebase plugins.
class MethodChannelQuerySnapshotChanges extends QuerySnapshotChangesPlatform {
  /// Creates a [MethodChannelQuerySnapshotChanges] from the given [data]
  ///
  /// The acknowledger belongs to the native stream this batch came from, so
  /// [acknowledgeHydrated] can reach the right delivery gate.
  MethodChannelQuerySnapshotChanges(
      FirebaseFirestorePlatform firestore, InternalQuerySnapshotChanges data,
      [this._acknowledger])
      : super(
            data.documentChanges
                .map((documentChange) {
                  if (documentChange == null) {
                    return null;
                  }
                  return MethodChannelDocumentChange(
                    firestore,
                    documentChange,
                  );
                })
                .nonNulls
                .toList(),
            SnapshotMetadataPlatform(
              data.metadata.hasPendingWrites,
              data.metadata.isFromCache,
            ),
            // SwineTech: null (from platforms that don't set it) == not partial.
            data.isPartial ?? false);

  final QuerySnapshotChangesAcknowledger? _acknowledger;

  @override
  Future<void> acknowledgeHydrated() async {
    await _acknowledger?.acknowledge();
  }
}

/// Sends one native stream's hydration acknowledgements. Shared by every batch of that
/// stream, so its latch is freed with them rather than kept for the app's lifetime.
class QuerySnapshotChangesAcknowledger {
  QuerySnapshotChangesAcknowledger(String observerId)
      : _channel =
            MethodChannelFirebaseFirestore.querySnapshotChangesAckChannel(
                observerId);

  final MethodChannel _channel;

  /// Whether this platform has no acknowledgement handler — iOS and web, neither of
  /// which batches or throttles delivery, so neither has anything to bound. Latched after
  /// the first MissingPluginException so the rest of the stream does not keep paying for a
  /// channel that will never be there.
  bool _unsupported = false;

  Future<void> acknowledge() async {
    if (_unsupported) {
      return;
    }

    try {
      await _channel.invokeMethod<void>('ack');
    } on MissingPluginException {
      // Expected on any platform that does not throttle delivery. Remember, so the next
      // batch on this stream skips the channel entirely.
      _unsupported = true;
    }
  }
}
