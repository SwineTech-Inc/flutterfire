// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

// SwineTech: pins the hand-added `isPartial` field on the (hand-maintained) pigeon
// type and its MethodChannel wrapper — the length-tolerant decode, the encode/equals
// contract, and the null -> false coercion. See PR #14.

import 'package:cloud_firestore_platform_interface/cloud_firestore_platform_interface.dart';
import 'package:cloud_firestore_platform_interface/src/method_channel/method_channel_query_snapshot_changes.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mockito/mockito.dart';

// ignore: avoid_implementing_value_types
class _MockFirestore extends Mock implements FirebaseFirestorePlatform {}

InternalSnapshotMetadata _metadata({
  bool hasPendingWrites = false,
  bool isFromCache = false,
}) =>
    InternalSnapshotMetadata(
      hasPendingWrites: hasPendingWrites,
      isFromCache: isFromCache,
    );

InternalQuerySnapshotChanges _changes(bool? isPartial, {InternalSnapshotMetadata? metadata}) =>
    InternalQuerySnapshotChanges(
      documentChanges: const <InternalDocumentChange?>[],
      metadata: metadata ?? _metadata(),
      isPartial: isPartial,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('InternalQuerySnapshotChanges.decode isPartial', () {
    test('2-element message decodes as null instead of throwing RangeError', () {
      // Platforms that don't set isPartial (iOS/Windows/test_api) encode only
      // documentChanges + metadata; the length-tolerant decode must tolerate it.
      final decoded = InternalQuerySnapshotChanges.decode(
        <Object?>[<Object?>[], _metadata()],
      );
      expect(decoded.isPartial, isNull);
    });

    test('3-element message preserves true / false / null', () {
      for (final value in <bool?>[true, false, null]) {
        final decoded = InternalQuerySnapshotChanges.decode(
          <Object?>[<Object?>[], _metadata(), value],
        );
        expect(decoded.isPartial, value);
      }
    });

    test('encode/decode round-trips isPartial', () {
      for (final value in <bool?>[true, false, null]) {
        final decoded =
            InternalQuerySnapshotChanges.decode(_changes(value).encode());
        expect(decoded.isPartial, value);
      }
    });
  });

  group('InternalQuerySnapshotChanges equality', () {
    // Share one metadata instance so only isPartial varies.
    final md = _metadata();
    InternalQuerySnapshotChanges make(bool? isPartial) =>
        _changes(isPartial, metadata: md);

    test('isPartial participates in == and hashCode', () {
      expect(make(true), equals(make(true)));
      expect(make(true).hashCode, equals(make(true).hashCode));
      expect(make(true), isNot(equals(make(false))));
    });

    test('null is distinct from false', () {
      expect(make(null), isNot(equals(make(false))));
    });
  });

  group('MethodChannelQuerySnapshotChanges isPartial coercion', () {
    final firestore = _MockFirestore();

    // Empty documentChanges => firestore is never dereferenced, so the mock needs
    // no stubs; this isolates the isPartial/metadata mapping.
    MethodChannelQuerySnapshotChanges wrap(bool? isPartial) =>
        MethodChannelQuerySnapshotChanges(
          firestore,
          InternalQuerySnapshotChanges(
            documentChanges: const <InternalDocumentChange?>[],
            metadata: _metadata(hasPendingWrites: true),
            isPartial: isPartial,
          ),
        );

    test('null isPartial coerces to false', () {
      expect(wrap(null).isPartial, isFalse);
    });

    test('true isPartial is preserved', () {
      expect(wrap(true).isPartial, isTrue);
    });

    test('the added isPartial arg did not shift metadata / docChanges', () {
      final result = wrap(true);
      expect(result.docChanges, isEmpty);
      expect(result.size, 0);
      expect(result.metadata.hasPendingWrites, isTrue);
      expect(result.metadata.isFromCache, isFalse);
    });
  });
}
