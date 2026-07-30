// ignore_for_file: require_trailing_commas
// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import 'package:cloud_firestore_platform_interface/cloud_firestore_platform_interface.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

/// A interface that contains zero or more [DocumentSnapshotChangesPlatform] objects
/// representing the results of a query.
///
/// The document changes can be accessed as a list by calling [docChanges()]
/// and the number of documents with changes can be determined by calling [size()].
class QuerySnapshotChangesPlatform extends PlatformInterface {
  /// Create a [QuerySnapshotChangesPlatform]
  QuerySnapshotChangesPlatform(
    this.docChanges,
    this.metadata, [
    this.isPartial = false,
  ]) : super(token: _token);

  static final Object _token = Object();

  /// Throws an [AssertionError] if [instance] does not extend
  /// [QuerySnapshotChangesPlatform].
  ///
  /// This is used by the app-facing [QuerySnapshotChanges] to ensure that
  /// the object in which it's going to delegate calls has been
  /// constructed properly.
  static void verify(QuerySnapshotChangesPlatform instance) {
    PlatformInterface.verify(instance, _token);
  }

  /// An array of the documents that changed since the last snapshot. If this
  /// is the first snapshot, all documents will be in the list as Added changes.
  final List<DocumentChangePlatform> docChanges;

  /// Metadata for the document
  final SnapshotMetadataPlatform metadata;

  /// Whether this is a non-final batch of a snapshot whose change set was too
  /// large to deliver in a single platform message and was split into batches.
  ///
  /// True for every batch except the last; the whole change set has arrived once
  /// a snapshot with this set to `false` is received. The application consumer
  /// must merge the batches (by document id) and treat `isPartial == false` as
  /// the completion signal — this layer does not merge. Any snapshot exceeding
  /// the delivery batch size is split, including large deltas — not just the
  /// initial load.
  final bool isPartial;

  /// The number of documents with changes in this [QuerySnapshotChangesPlatform].
  ///
  /// When a large snapshot is split (see [isPartial]), this is the size of *this
  /// batch*, not the total for the logical snapshot.
  int get size => docChanges.length;

  /// Tells the platform that this batch has been fully hydrated into application
  /// state, so it may release the delivery slot the batch was occupying.
  ///
  /// SwineTech (SP30-9652): the Android handler bounds how many batches are in flight
  /// with a semaphore, but a permit was returned when the payload was handed to Dart
  /// rather than when Dart had hydrated it — so the consumer's hydration queue grew with
  /// nothing capping it. Calling this is what turns that semaphore into an end-to-end
  /// bound.
  ///
  /// **Consumers must call this once per delivered batch, from a `finally`.** Skipping it
  /// — including on a hydration failure — starves the native side of slots and stalls the
  /// stream until its acquire timeout expires on every subsequent batch.
  ///
  /// Deliberately a no-op default rather than abstract: only the Android handler
  /// throttles delivery, and making this abstract would break every other
  /// implementation of this interface, `cloud_firestore_web` included.
  Future<void> acknowledgeHydrated() async {}
}
