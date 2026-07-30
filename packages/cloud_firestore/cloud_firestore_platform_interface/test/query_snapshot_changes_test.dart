// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

// SwineTech: pins the hand-added `isPartial` field on the (hand-maintained) pigeon
// type and its MethodChannel wrapper — the length-tolerant decode, the encode/equals
// contract, and the null -> false coercion. See PR #14.

import 'package:cloud_firestore_platform_interface/cloud_firestore_platform_interface.dart';
import 'package:cloud_firestore_platform_interface/src/method_channel/method_channel_query_snapshot_changes.dart';
import 'package:flutter/services.dart';
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

InternalQuerySnapshotChanges _changes(bool? isPartial,
        {InternalSnapshotMetadata? metadata}) =>
    InternalQuerySnapshotChanges(
      documentChanges: const <InternalDocumentChange?>[],
      metadata: metadata ?? _metadata(),
      isPartial: isPartial,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('InternalQuerySnapshotChanges.decode isPartial', () {
    test('2-element message decodes as null instead of throwing RangeError',
        () {
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

  // SwineTech (SP30-9652): acknowledgeHydrated tells the Android handler that a delivered
  // batch has been hydrated, so it can release the delivery slot the batch occupied. That
  // is what makes MAX_IN_FLIGHT_BATCHES an end-to-end bound rather than a native-only one.
  //
  // The behaviour that matters here is what happens on the platforms that do NOT implement
  // it. iOS and web neither batch nor throttle delivery, so they register no handler — and
  // this is called once per batch from a `finally`, so a MissingPluginException escaping it
  // would throw into the zone on every single batch of every stream.
  group('MethodChannelQuerySnapshotChanges acknowledgeHydrated', () {
    final firestore = _MockFirestore();

    MethodChannelQuerySnapshotChanges wrap([String? observerId]) =>
        MethodChannelQuerySnapshotChanges(
          firestore,
          _changes(false),
          observerId,
        );

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel(
            'plugins.flutter.io/firebase_firestore/queryChanges/obs-1/ack'),
        null,
      );
    });

    test('is a no-op when there is no observer id', () async {
      // Constructed without an id by the fork's own older call sites, and by any platform
      // that does not route through method_channel_query.
      await expectLater(wrap().acknowledgeHydrated(), completes);
    });

    test('invokes ack on the observer channel', () async {
      final calls = <String>[];

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel(
            'plugins.flutter.io/firebase_firestore/queryChanges/obs-1/ack'),
        (call) async {
          calls.add(call.method);
          return null;
        },
      );

      await wrap('obs-1').acknowledgeHydrated();

      expect(calls, equals(<String>['ack']));
    });

    test('swallows MissingPluginException so iOS and web are unaffected',
        () async {
      // No handler registered at all — exactly the iOS/web situation.
      await expectLater(wrap('obs-1').acknowledgeHydrated(), completes);
    });

    test('stops calling the channel after the first MissingPluginException',
        () async {
      // Latching matters: this runs once per batch, so a 66-batch herd load would
      // otherwise raise and catch 66 platform exceptions for nothing.
      final unsupported = wrap('obs-2');
      await unsupported.acknowledgeHydrated();

      var calls = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(
        const MethodChannel(
            'plugins.flutter.io/firebase_firestore/queryChanges/obs-2/ack'),
        (call) async {
          calls++;
          return null;
        },
      );

      await unsupported.acknowledgeHydrated();

      expect(calls, isZero,
          reason: 'obs-2 was latched as unsupported by the first attempt');
    });
  });
}
