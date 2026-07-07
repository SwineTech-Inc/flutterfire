// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

part of '../cloud_firestore.dart';

/// Contains the results of a query.
/// It can contain zero or more [DocumentSnapshot] objects.
abstract class QuerySnapshotChanges<T extends Object?> {
  /// An array of the documents that changed since the last snapshot. If this
  /// is the first snapshot, all documents will be in the list as Added changes.
  List<DocumentChange<T>> get docChanges;

  /// Returns the [SnapshotMetadata] for this snapshot.
  SnapshotMetadata get metadata;

  /// Whether this is a non-final batch of a snapshot whose change set was too
  /// large to deliver in a single platform message and was split into batches.
  ///
  /// True for every batch except the last; the whole change set has arrived once
  /// a snapshot with this set to `false` is received. The application consumer is
  /// responsible for merging the batches (by document id) and treating
  /// `isPartial == false` as the completion signal — this layer does not merge.
  ///
  /// Any snapshot exceeding the delivery batch size is split, including large
  /// deltas (e.g. a bulk write, or reconnecting after extended offline edits) —
  /// not just the initial load.
  bool get isPartial;

  /// Returns the size (number of documents) of this snapshot.
  ///
  /// When a large snapshot is split (see [isPartial]), this is the size of *this
  /// batch* (≤ the delivery batch size), not the total for the logical snapshot.
  int get size;
}

/// Contains the results of a query.
/// It can contain zero or more [DocumentSnapshot] objects.
class _JsonQuerySnapshotChanges
    implements QuerySnapshotChanges<Map<String, dynamic>> {
  _JsonQuerySnapshotChanges(this._firestore, this._delegate) {
    QuerySnapshotChangesPlatform.verify(_delegate);
  }

  final FirebaseFirestore _firestore;
  final QuerySnapshotChangesPlatform _delegate;

  @override
  List<DocumentChange<Map<String, dynamic>>> get docChanges {
    return _delegate.docChanges.map((documentDelegate) {
      return _JsonDocumentChange(_firestore, documentDelegate);
    }).toList();
  }

  @override
  SnapshotMetadata get metadata => SnapshotMetadata._(_delegate.metadata);

  @override
  bool get isPartial => _delegate.isPartial;

  @override
  int get size => _delegate.size;
}

/// Contains the results of a query.
/// It can contain zero or more [DocumentSnapshot] objects.
class _WithConverterQuerySnapshotChanges<T extends Object?>
    implements QuerySnapshotChanges<T> {
  _WithConverterQuerySnapshotChanges(
    this._originalQuerySnapshotChanges,
    this._fromFirestore,
    this._toFirestore,
  );

  final QuerySnapshotChanges<Map<String, dynamic>>
      _originalQuerySnapshotChanges;
  final FromFirestore<T> _fromFirestore;
  final ToFirestore<T> _toFirestore;

  @override
  List<DocumentChange<T>> get docChanges {
    return [
      for (final change in _originalQuerySnapshotChanges.docChanges)
        _WithConverterDocumentChange<T>(
          change,
          _fromFirestore,
          _toFirestore,
        ),
    ];
  }

  @override
  SnapshotMetadata get metadata => _originalQuerySnapshotChanges.metadata;

  @override
  bool get isPartial => _originalQuerySnapshotChanges.isPartial;

  @override
  int get size => _originalQuerySnapshotChanges.size;
}
