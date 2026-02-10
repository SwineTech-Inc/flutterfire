
#import "QuerySnapshotWrapper.h"

@interface QuerySnapshotWrapper ()
@property(nonatomic, strong, readwrite) FIRQuerySnapshot *snapshot;
@property(nonatomic, strong, readwrite) NSDate *fetchedAt;
@end

@implementation QuerySnapshotWrapper

- (instancetype)initWithSnapshot:(FIRQuerySnapshot *)snapshot {
  self = [super init];
  if (self) {
    _snapshot = snapshot;
    _fetchedAt = [NSDate date];
  }
  return self;
}

+ (instancetype)wrapSnapshot:(FIRQuerySnapshot *)snapshot {
  return [[self alloc] initWithSnapshot:snapshot];
}

// Convenience accessors
- (NSArray<FIRDocumentSnapshot *> *)documents {
  return self.snapshot.documents;
}

- (NSArray<FIRDocumentChange *> *)documentChanges {
  return self.snapshot.documentChanges;
}

- (FIRSnapshotMetadata *)metadata {
  return self.snapshot.metadata;
}

- (BOOL)isEmpty {
  return self.snapshot.isEmpty;
}

- (NSInteger)count {
  return self.snapshot.count;
}

// Custom methods
//- (NSArray *)documentIDs {
//    NSMutableArray *ids = [NSMutableArray array];
//    for (FIRDocumentSnapshot *doc in self.documents) {
//        [ids addObject:doc.documentID];
//    }
//    return [ids copy];
//}
//
//- (NSArray *)documentsOfType:(Class)modelClass {
//    NSMutableArray *models = [NSMutableArray array];
//    for (FIRDocumentSnapshot *doc in self.documents) {
//        if ([modelClass respondsToSelector:@selector(modelFromSnapshot:)]) {
//            id model = [modelClass performSelector:@selector(modelFromSnapshot:)
//                                        withObject:doc];
//            if (model) {
//                [models addObject:model];
//            }
//        }
//    }
//    return [models copy];
//}
//
//- (NSDictionary *)groupedByField:(NSString *)fieldName {
//    NSMutableDictionary *grouped = [NSMutableDictionary dictionary];
//    for (FIRDocumentSnapshot *doc in self.documents) {
//        id value = doc.data[fieldName];
//        NSString *key = [value description] ?: @"nil";
//
//        NSMutableArray *group = grouped[key];
//        if (!group) {
//            group = [NSMutableArray array];
//            grouped[key] = group;
//        }
//        [group addObject:doc];
//    }
//    return [grouped copy];
//}
//
//- (FIRDocumentSnapshot *)documentWithID:(NSString *)documentID {
//    for (FIRDocumentSnapshot *doc in self.documents) {
//        if ([doc.documentID isEqualToString:documentID]) {
//            return doc;
//        }
//    }
//    return nil;
//}

@end
