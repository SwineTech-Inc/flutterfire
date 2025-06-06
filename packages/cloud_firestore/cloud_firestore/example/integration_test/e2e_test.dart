// Copyright 2020, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'document_reference_e2e.dart';
import 'firebase_options.dart';
import 'instance_e2e.dart';

bool kUseFirestoreEmulator = true;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('cloud_firestore', () {
    setUpAll(() async {
      await Firebase.initializeApp(
        options: DefaultFirebaseOptions.currentPlatform,
      );

      if (kUseFirestoreEmulator) {
        FirebaseFirestore.instance.useFirestoreEmulator('10.1.20.225', 8080);
      }
    });

    runInstanceTests();

    // runCollectionReferenceTests();
    // runDocumentChangeTests();
    runDocumentReferenceTests();
    // runFieldValueTests();
    // runGeoPointTests();
    // runQueryTests();
    // runSnapshotMetadataTests();
    // runTimestampTests();
    // runTransactionTests();
    // runWriteBatchTests();
    // runLoadBundleTests();
    // if (defaultTargetPlatform != TargetPlatform.windows) {
    //   runSecondDatabaseTests();
    // }
  });
}
