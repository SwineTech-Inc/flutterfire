// ignore_for_file: require_trailing_commas
// Copyright 2017, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import 'package:cloud_firestore_platform_interface/cloud_firestore_platform_interface.dart';

import 'method_channel_document_change.dart';

/// An implementation of [QuerySnapshotPlatform] that uses [MethodChannel] to
/// communicate with Firebase plugins.
class MethodChannelQuerySnapshotChanges extends QuerySnapshotChangesPlatform {
  /// Creates a [MethodChannelQuerySnapshotChanges] from the given [data]
  MethodChannelQuerySnapshotChanges(
      FirebaseFirestorePlatform firestore, PigeonQuerySnapshotChanges data)
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
            ));
}
