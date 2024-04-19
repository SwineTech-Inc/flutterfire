// Copyright 2023, the Chromium project authors.  Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Autogenerated from Pigeon (v11.0.1), do not edit directly.
// See also: https://pub.dev/packages/pigeon

#import <Foundation/Foundation.h>

@protocol FlutterBinaryMessenger;
@protocol FlutterMessageCodec;
@class FlutterError;
@class FlutterStandardTypedData;

NS_ASSUME_NONNULL_BEGIN

/// An enumeration of document change types.
typedef NS_ENUM(NSUInteger, DocumentChangeType) {
  /// Indicates a new document was added to the set of documents matching the
  /// query.
  DocumentChangeTypeAdded = 0,
  /// Indicates a document within the query was modified.
  DocumentChangeTypeModified = 1,
  /// Indicates a document within the query was removed (either deleted or no
  /// longer matches the query.
  DocumentChangeTypeRemoved = 2,
};

/// Wrapper for DocumentChangeType to allow for nullability.
@interface DocumentChangeTypeBox : NSObject
@property(nonatomic, assign) DocumentChangeType value;
- (instancetype)initWithValue:(DocumentChangeType)value;
@end

/// An enumeration of firestore source types.
typedef NS_ENUM(NSUInteger, Source) {
  /// Causes Firestore to try to retrieve an up-to-date (server-retrieved) snapshot, but fall back
  /// to
  /// returning cached data if the server can't be reached.
  SourceServerAndCache = 0,
  /// Causes Firestore to avoid the cache, generating an error if the server cannot be reached. Note
  /// that the cache will still be updated if the server request succeeds. Also note that
  /// latency-compensation still takes effect, so any pending write operations will be visible in
  /// the
  /// returned data (merged into the server-provided data).
  SourceServer = 1,
  /// Causes Firestore to immediately return a value from the cache, ignoring the server completely
  /// (implying that the returned value may be stale with respect to the value on the server). If
  /// there is no data in the cache to satisfy the `get` call,
  /// [DocumentReference.get] will throw a [FirebaseException] and
  /// [Query.get] will return an empty [QuerySnapshotPlatform] with no documents.
  SourceCache = 2,
};

/// Wrapper for Source to allow for nullability.
@interface SourceBox : NSObject
@property(nonatomic, assign) Source value;
- (instancetype)initWithValue:(Source)value;
@end

/// The listener retrieves data and listens to updates from the local Firestore cache only.
/// If the cache is empty, an empty snapshot will be returned.
/// Snapshot events will be triggered on cache updates, like local mutations or load bundles.
///
/// Note that the data might be stale if the cache hasn't synchronized with recent server-side
/// changes.
typedef NS_ENUM(NSUInteger, ListenSource) {
  /// The default behavior. The listener attempts to return initial snapshot from cache and retrieve
  /// up-to-date snapshots from the Firestore server.
  /// Snapshot events will be triggered on local mutations and server side updates.
  ListenSourceDefaultSource = 0,
  /// The listener retrieves data and listens to updates from the local Firestore cache only.
  /// If the cache is empty, an empty snapshot will be returned.
  /// Snapshot events will be triggered on cache updates, like local mutations or load bundles.
  ListenSourceCache = 1,
};

/// Wrapper for ListenSource to allow for nullability.
@interface ListenSourceBox : NSObject
@property(nonatomic, assign) ListenSource value;
- (instancetype)initWithValue:(ListenSource)value;
@end

typedef NS_ENUM(NSUInteger, ServerTimestampBehavior) {
  /// Return null for [FieldValue.serverTimestamp()] values that have not yet
  ServerTimestampBehaviorNone = 0,
  /// Return local estimates for [FieldValue.serverTimestamp()] values that have not yet been set to
  /// their final value.
  ServerTimestampBehaviorEstimate = 1,
  /// Return the previous value for [FieldValue.serverTimestamp()] values that have not yet been set
  /// to their final value.
  ServerTimestampBehaviorPrevious = 2,
};

/// Wrapper for ServerTimestampBehavior to allow for nullability.
@interface ServerTimestampBehaviorBox : NSObject
@property(nonatomic, assign) ServerTimestampBehavior value;
- (instancetype)initWithValue:(ServerTimestampBehavior)value;
@end

/// [AggregateSource] represents the source of data for an [AggregateQuery].
typedef NS_ENUM(NSUInteger, AggregateSource) {
  /// Indicates that the data should be retrieved from the server.
  AggregateSourceServer = 0,
};

/// Wrapper for AggregateSource to allow for nullability.
@interface AggregateSourceBox : NSObject
@property(nonatomic, assign) AggregateSource value;
- (instancetype)initWithValue:(AggregateSource)value;
@end

typedef NS_ENUM(NSUInteger, PigeonTransactionResult) {
  PigeonTransactionResultSuccess = 0,
  PigeonTransactionResultFailure = 1,
};

/// Wrapper for PigeonTransactionResult to allow for nullability.
@interface PigeonTransactionResultBox : NSObject
@property(nonatomic, assign) PigeonTransactionResult value;
- (instancetype)initWithValue:(PigeonTransactionResult)value;
@end

typedef NS_ENUM(NSUInteger, PigeonTransactionType) {
  PigeonTransactionTypeGet = 0,
  PigeonTransactionTypeUpdate = 1,
  PigeonTransactionTypeSet = 2,
  PigeonTransactionTypeDeleteType = 3,
};

/// Wrapper for PigeonTransactionType to allow for nullability.
@interface PigeonTransactionTypeBox : NSObject
@property(nonatomic, assign) PigeonTransactionType value;
- (instancetype)initWithValue:(PigeonTransactionType)value;
@end

typedef NS_ENUM(NSUInteger, AggregateType) {
  AggregateTypeCount = 0,
  AggregateTypeSum = 1,
  AggregateTypeAverage = 2,
};

/// Wrapper for AggregateType to allow for nullability.
@interface AggregateTypeBox : NSObject
@property(nonatomic, assign) AggregateType value;
- (instancetype)initWithValue:(AggregateType)value;
@end

@class PigeonFirebaseSettings;
@class FirestorePigeonFirebaseApp;
@class PigeonSnapshotMetadata;
@class PigeonDocumentSnapshot;
@class PigeonDocumentChange;
@class PigeonQuerySnapshot;
@class PigeonQuerySnapshotChanges;
@class PigeonGetOptions;
@class PigeonDocumentOption;
@class PigeonTransactionCommand;
@class DocumentReferenceRequest;
@class PigeonQueryParameters;
@class AggregateQuery;
@class AggregateQueryResponse;

@interface PigeonFirebaseSettings : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithPersistenceEnabled:(nullable NSNumber *)persistenceEnabled
                                      host:(nullable NSString *)host
                                sslEnabled:(nullable NSNumber *)sslEnabled
                            cacheSizeBytes:(nullable NSNumber *)cacheSizeBytes
                 ignoreUndefinedProperties:(NSNumber *)ignoreUndefinedProperties;
@property(nonatomic, strong, nullable) NSNumber *persistenceEnabled;
@property(nonatomic, copy, nullable) NSString *host;
@property(nonatomic, strong, nullable) NSNumber *sslEnabled;
@property(nonatomic, strong, nullable) NSNumber *cacheSizeBytes;
@property(nonatomic, strong) NSNumber *ignoreUndefinedProperties;
@end

@interface FirestorePigeonFirebaseApp : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithAppName:(NSString *)appName
                       settings:(PigeonFirebaseSettings *)settings
                    databaseURL:(NSString *)databaseURL;
@property(nonatomic, copy) NSString *appName;
@property(nonatomic, strong) PigeonFirebaseSettings *settings;
@property(nonatomic, copy) NSString *databaseURL;
@end

@interface PigeonSnapshotMetadata : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithHasPendingWrites:(NSNumber *)hasPendingWrites
                             isFromCache:(NSNumber *)isFromCache;
@property(nonatomic, strong) NSNumber *hasPendingWrites;
@property(nonatomic, strong) NSNumber *isFromCache;
@end

@interface PigeonDocumentSnapshot : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithPath:(NSString *)path
                        data:(nullable NSDictionary<NSString *, id> *)data
                    metadata:(PigeonSnapshotMetadata *)metadata;
@property(nonatomic, copy) NSString *path;
@property(nonatomic, strong, nullable) NSDictionary<NSString *, id> *data;
@property(nonatomic, strong) PigeonSnapshotMetadata *metadata;
@end

@interface PigeonDocumentChange : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithType:(DocumentChangeType)type
                    document:(PigeonDocumentSnapshot *)document
                    oldIndex:(NSNumber *)oldIndex
                    newIndex:(NSNumber *)newIndex;
@property(nonatomic, assign) DocumentChangeType type;
@property(nonatomic, strong) PigeonDocumentSnapshot *document;
@property(nonatomic, strong) NSNumber *oldIndex;
@property(nonatomic, strong) NSNumber *newIndex;
@end

@interface PigeonQuerySnapshot : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithDocuments:(NSArray<PigeonDocumentSnapshot *> *)documents
                  documentChanges:(NSArray<PigeonDocumentChange *> *)documentChanges
                         metadata:(PigeonSnapshotMetadata *)metadata;
@property(nonatomic, strong) NSArray<PigeonDocumentSnapshot *> *documents;
@property(nonatomic, strong) NSArray<PigeonDocumentChange *> *documentChanges;
@property(nonatomic, strong) PigeonSnapshotMetadata *metadata;
@end

@interface PigeonQuerySnapshotChanges : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithDocumentChanges:(NSArray<PigeonDocumentChange *> *)documentChanges
                               metadata:(PigeonSnapshotMetadata *)metadata;
@property(nonatomic, strong) NSArray<PigeonDocumentChange *> *documentChanges;
@property(nonatomic, strong) PigeonSnapshotMetadata *metadata;
@end

@interface PigeonGetOptions : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithSource:(Source)source
       serverTimestampBehavior:(ServerTimestampBehavior)serverTimestampBehavior;
@property(nonatomic, assign) Source source;
@property(nonatomic, assign) ServerTimestampBehavior serverTimestampBehavior;
@end

@interface PigeonDocumentOption : NSObject
+ (instancetype)makeWithMerge:(nullable NSNumber *)merge
                  mergeFields:(nullable NSArray<NSArray<NSString *> *> *)mergeFields;
@property(nonatomic, strong, nullable) NSNumber *merge;
@property(nonatomic, strong, nullable) NSArray<NSArray<NSString *> *> *mergeFields;
@end

@interface PigeonTransactionCommand : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithType:(PigeonTransactionType)type
                        path:(NSString *)path
                        data:(nullable NSDictionary<NSString *, id> *)data
                      option:(nullable PigeonDocumentOption *)option;
@property(nonatomic, assign) PigeonTransactionType type;
@property(nonatomic, copy) NSString *path;
@property(nonatomic, strong, nullable) NSDictionary<NSString *, id> *data;
@property(nonatomic, strong, nullable) PigeonDocumentOption *option;
@end

@interface DocumentReferenceRequest : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithPath:(NSString *)path
                        data:(nullable NSDictionary<id, id> *)data
                      option:(nullable PigeonDocumentOption *)option
                      source:(nullable SourceBox *)source
     serverTimestampBehavior:(nullable ServerTimestampBehaviorBox *)serverTimestampBehavior;
@property(nonatomic, copy) NSString *path;
@property(nonatomic, strong, nullable) NSDictionary<id, id> *data;
@property(nonatomic, strong, nullable) PigeonDocumentOption *option;
@property(nonatomic, strong, nullable) SourceBox *source;
@property(nonatomic, strong, nullable) ServerTimestampBehaviorBox *serverTimestampBehavior;
@end

@interface PigeonQueryParameters : NSObject
+ (instancetype)makeWithWhere:(nullable NSArray<NSArray<id> *> *)where
                      orderBy:(nullable NSArray<NSArray<id> *> *)orderBy
                        limit:(nullable NSNumber *)limit
                  limitToLast:(nullable NSNumber *)limitToLast
                      startAt:(nullable NSArray<id> *)startAt
                   startAfter:(nullable NSArray<id> *)startAfter
                        endAt:(nullable NSArray<id> *)endAt
                    endBefore:(nullable NSArray<id> *)endBefore
                      filters:(nullable NSDictionary<NSString *, id> *)filters;
@property(nonatomic, strong, nullable) NSArray<NSArray<id> *> *where;
@property(nonatomic, strong, nullable) NSArray<NSArray<id> *> *orderBy;
@property(nonatomic, strong, nullable) NSNumber *limit;
@property(nonatomic, strong, nullable) NSNumber *limitToLast;
@property(nonatomic, strong, nullable) NSArray<id> *startAt;
@property(nonatomic, strong, nullable) NSArray<id> *startAfter;
@property(nonatomic, strong, nullable) NSArray<id> *endAt;
@property(nonatomic, strong, nullable) NSArray<id> *endBefore;
@property(nonatomic, strong, nullable) NSDictionary<NSString *, id> *filters;
@end

@interface AggregateQuery : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithType:(AggregateType)type field:(nullable NSString *)field;
@property(nonatomic, assign) AggregateType type;
@property(nonatomic, copy, nullable) NSString *field;
@end

@interface AggregateQueryResponse : NSObject
/// `init` unavailable to enforce nonnull fields, see the `make` class method.
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)makeWithType:(AggregateType)type
                       field:(nullable NSString *)field
                       value:(nullable NSNumber *)value;
@property(nonatomic, assign) AggregateType type;
@property(nonatomic, copy, nullable) NSString *field;
@property(nonatomic, strong, nullable) NSNumber *value;
@end

/// The codec used by FirebaseFirestoreHostApi.
NSObject<FlutterMessageCodec> *FirebaseFirestoreHostApiGetCodec(void);

@protocol FirebaseFirestoreHostApi
- (void)loadBundleApp:(FirestorePigeonFirebaseApp *)app
               bundle:(FlutterStandardTypedData *)bundle
           completion:(void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
- (void)namedQueryGetApp:(FirestorePigeonFirebaseApp *)app
                    name:(NSString *)name
                 options:(PigeonGetOptions *)options
              completion:
                  (void (^)(PigeonQuerySnapshot *_Nullable, FlutterError *_Nullable))completion;
- (void)namedQueryGetChangesApp:(FirestorePigeonFirebaseApp *)app
                           name:(NSString *)name
                        options:(PigeonGetOptions *)options
                     completion:(void (^)(PigeonQuerySnapshotChanges *_Nullable,
                                          FlutterError *_Nullable))completion;
- (void)clearPersistenceApp:(FirestorePigeonFirebaseApp *)app
                 completion:(void (^)(FlutterError *_Nullable))completion;
- (void)disableNetworkApp:(FirestorePigeonFirebaseApp *)app
               completion:(void (^)(FlutterError *_Nullable))completion;
- (void)enableNetworkApp:(FirestorePigeonFirebaseApp *)app
              completion:(void (^)(FlutterError *_Nullable))completion;
- (void)terminateApp:(FirestorePigeonFirebaseApp *)app
          completion:(void (^)(FlutterError *_Nullable))completion;
- (void)waitForPendingWritesApp:(FirestorePigeonFirebaseApp *)app
                     completion:(void (^)(FlutterError *_Nullable))completion;
- (void)setIndexConfigurationApp:(FirestorePigeonFirebaseApp *)app
              indexConfiguration:(NSString *)indexConfiguration
                      completion:(void (^)(FlutterError *_Nullable))completion;
- (void)setLoggingEnabledLoggingEnabled:(NSNumber *)loggingEnabled
                             completion:(void (^)(FlutterError *_Nullable))completion;
- (void)snapshotsInSyncSetupApp:(FirestorePigeonFirebaseApp *)app
                     completion:(void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
- (void)transactionCreateApp:(FirestorePigeonFirebaseApp *)app
                     timeout:(NSNumber *)timeout
                 maxAttempts:(NSNumber *)maxAttempts
                  completion:(void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
- (void)transactionStoreResultTransactionId:(NSString *)transactionId
                                 resultType:(PigeonTransactionResult)resultType
                                   commands:(nullable NSArray<PigeonTransactionCommand *> *)commands
                                 completion:(void (^)(FlutterError *_Nullable))completion;
- (void)transactionGetApp:(FirestorePigeonFirebaseApp *)app
            transactionId:(NSString *)transactionId
                     path:(NSString *)path
               completion:
                   (void (^)(PigeonDocumentSnapshot *_Nullable, FlutterError *_Nullable))completion;
- (void)documentReferenceSetApp:(FirestorePigeonFirebaseApp *)app
                        request:(DocumentReferenceRequest *)request
                     completion:(void (^)(FlutterError *_Nullable))completion;
- (void)documentReferenceUpdateApp:(FirestorePigeonFirebaseApp *)app
                           request:(DocumentReferenceRequest *)request
                        completion:(void (^)(FlutterError *_Nullable))completion;
- (void)documentReferenceGetApp:(FirestorePigeonFirebaseApp *)app
                        request:(DocumentReferenceRequest *)request
                     completion:(void (^)(PigeonDocumentSnapshot *_Nullable,
                                          FlutterError *_Nullable))completion;
- (void)documentReferenceDeleteApp:(FirestorePigeonFirebaseApp *)app
                           request:(DocumentReferenceRequest *)request
                        completion:(void (^)(FlutterError *_Nullable))completion;
- (void)queryGetApp:(FirestorePigeonFirebaseApp *)app
                 path:(NSString *)path
    isCollectionGroup:(NSNumber *)isCollectionGroup
           parameters:(PigeonQueryParameters *)parameters
              options:(PigeonGetOptions *)options
           completion:(void (^)(PigeonQuerySnapshot *_Nullable, FlutterError *_Nullable))completion;
- (void)queryGetChangesApp:(FirestorePigeonFirebaseApp *)app
                      path:(NSString *)path
         isCollectionGroup:(NSNumber *)isCollectionGroup
                parameters:(PigeonQueryParameters *)parameters
                   options:(PigeonGetOptions *)options
                completion:(void (^)(PigeonQuerySnapshotChanges *_Nullable,
                                     FlutterError *_Nullable))completion;
- (void)aggregateQueryApp:(FirestorePigeonFirebaseApp *)app
                     path:(NSString *)path
               parameters:(PigeonQueryParameters *)parameters
                   source:(AggregateSource)source
                  queries:(NSArray<AggregateQuery *> *)queries
        isCollectionGroup:(NSNumber *)isCollectionGroup
               completion:(void (^)(NSArray<AggregateQueryResponse *> *_Nullable,
                                    FlutterError *_Nullable))completion;
- (void)writeBatchCommitApp:(FirestorePigeonFirebaseApp *)app
                     writes:(NSArray<PigeonTransactionCommand *> *)writes
                 completion:(void (^)(FlutterError *_Nullable))completion;
- (void)querySnapshotApp:(FirestorePigeonFirebaseApp *)app
                      path:(NSString *)path
         isCollectionGroup:(NSNumber *)isCollectionGroup
                parameters:(PigeonQueryParameters *)parameters
                   options:(PigeonGetOptions *)options
    includeMetadataChanges:(NSNumber *)includeMetadataChanges
                    source:(ListenSource)source
                completion:(void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
- (void)querySnapshotChangesApp:(FirestorePigeonFirebaseApp *)app
                           path:(NSString *)path
              isCollectionGroup:(NSNumber *)isCollectionGroup
                     parameters:(PigeonQueryParameters *)parameters
                        options:(PigeonGetOptions *)options
         includeMetadataChanges:(NSNumber *)includeMetadataChanges
                         source:(ListenSource)source
                     completion:(void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
- (void)documentReferenceSnapshotApp:(FirestorePigeonFirebaseApp *)app
                          parameters:(DocumentReferenceRequest *)parameters
              includeMetadataChanges:(NSNumber *)includeMetadataChanges
                              source:(ListenSource)source
                          completion:
                              (void (^)(NSString *_Nullable, FlutterError *_Nullable))completion;
@end

extern void FirebaseFirestoreHostApiSetup(id<FlutterBinaryMessenger> binaryMessenger,
                                          NSObject<FirebaseFirestoreHostApi> *_Nullable api);

NS_ASSUME_NONNULL_END
