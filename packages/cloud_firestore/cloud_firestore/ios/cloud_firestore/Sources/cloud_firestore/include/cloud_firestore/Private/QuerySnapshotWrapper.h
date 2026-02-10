
#import <FirebaseFirestore/FirebaseFirestore.h>

@interface QuerySnapshotWrapper : NSObject

@property(nonatomic, strong, readonly) FIRQuerySnapshot *snapshot;
@property(nonatomic, strong, readonly) NSDate *fetchedAt;
@property(nonatomic, assign, readonly) NSInteger count;

// Convenience accessors
@property(nonatomic, strong, readonly) NSArray<FIRDocumentSnapshot *> *documents;
@property(nonatomic, strong, readonly) NSArray<FIRDocumentChange *> *documentChanges;
@property(nonatomic, strong, readonly) FIRSnapshotMetadata *metadata;
@property(nonatomic, assign, readonly) BOOL isEmpty;

// Initializer
- (instancetype)initWithSnapshot:(FIRQuerySnapshot *)snapshot;
+ (instancetype)wrapSnapshot:(FIRQuerySnapshot *)snapshot;

// Your custom methods
//- (NSArray *)documentIDs;
//- (NSArray *)documentsOfType:(Class)modelClass;
//- (NSDictionary *)groupedByField:(NSString *)fieldName;
//- (FIRDocumentSnapshot *)documentWithID:(NSString *)documentID;

@end
