#import <Foundation/Foundation.h>
#import <sys/socket.h>
#import <sys/stat.h>
#import <sys/un.h>
#import <unistd.h>

#import "../iosApp/Host/RaftDiagnosticsPathAdmission.h"

@interface CharacterDeviceFileManager : NSFileManager
@end

@implementation CharacterDeviceFileManager
- (NSString *)destinationOfSymbolicLinkAtPath:(NSString *)path error:(NSError **)error {
    return nil;
}

- (NSDictionary<NSFileAttributeKey, id> *)attributesOfItemAtPath:(NSString *)path error:(NSError **)error {
    return @{ NSFileType : [path isEqualToString:@"/trusted"] ? NSFileTypeDirectory : NSFileTypeCharacterSpecial };
}
@end

static void Require(BOOL condition, NSString *message) {
    if (!condition) {
        NSLog(@"FAIL: %@", message);
        exit(1);
    }
}

int main(void) {
    @autoreleasepool {
        NSFileManager *fm = NSFileManager.defaultManager;
        NSString *temp = [NSTemporaryDirectory() stringByAppendingPathComponent:NSUUID.UUID.UUIDString];
        NSString *root = [temp stringByAppendingPathComponent:@"root-a"];
        NSString *outside = [temp stringByAppendingPathComponent:@"outside"];
        NSString *confusable = [temp stringByAppendingPathComponent:@"root-ab"];
        [fm createDirectoryAtPath:[root stringByAppendingPathComponent:@"launch-safe"]
      withIntermediateDirectories:YES attributes:nil error:nil];
        [fm createDirectoryAtPath:outside withIntermediateDirectories:YES attributes:nil error:nil];
        [fm createDirectoryAtPath:confusable withIntermediateDirectories:YES attributes:nil error:nil];

        NSString *safeFile = [root stringByAppendingPathComponent:@"launch-safe/raft-diagnostics.jsonl"];
        [@"{}\n" writeToFile:safeFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, safeFile) != nil,
                @"valid in-root regular file must pass");

        NSString *outsideFile = [outside stringByAppendingPathComponent:@"raft-diagnostics.jsonl"];
        [@"outside\n" writeToFile:outsideFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        NSString *leafLink = [root stringByAppendingPathComponent:@"launch-safe/raft-diagnostics.jsonl.1"];
        [fm createSymbolicLinkAtPath:leafLink withDestinationPath:outsideFile error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, leafLink) == nil,
                @"leaf symlink must fail closed");

        NSString *intermediateLink = [root stringByAppendingPathComponent:@"launch-link"];
        [fm createSymbolicLinkAtPath:intermediateLink withDestinationPath:outside error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(
                    fm, root, [intermediateLink stringByAppendingPathComponent:@"raft-diagnostics.jsonl"]) == nil,
                @"intermediate symlink must fail closed");

        NSString *insideAlias = [root stringByAppendingPathComponent:@"launch-alias"];
        [fm createSymbolicLinkAtPath:insideAlias
                 withDestinationPath:[root stringByAppendingPathComponent:@"launch-safe"] error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(
                    fm, root, [insideAlias stringByAppendingPathComponent:@"raft-diagnostics.jsonl"]) == nil,
                @"in-root directory alias must fail closed");

        NSString *confusableFile = [confusable stringByAppendingPathComponent:@"raft-diagnostics.jsonl"];
        [@"confusable\n" writeToFile:confusableFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, confusableFile) == nil,
                @"prefix-confusable root must fail closed");

        NSString *badDir = [root stringByAppendingPathComponent:@"launch-slock-old"];
        [fm createDirectoryAtPath:badDir withIntermediateDirectories:YES attributes:nil error:nil];
        NSString *badComponentFile = [badDir stringByAppendingPathComponent:@"raft-diagnostics.jsonl"];
        [@"bad\n" writeToFile:badComponentFile atomically:YES encoding:NSUTF8StringEncoding error:nil];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, badComponentFile) == nil,
                @"old-brand canonical component must fail closed");

        NSString *fifo = [root stringByAppendingPathComponent:@"launch-safe/raft-diagnostics.jsonl.fifo"];
        Require(mkfifo(fifo.fileSystemRepresentation, 0600) == 0, @"create FIFO fixture");
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, fifo) == nil,
                @"FIFO must fail closed");

        NSString *socketPath = [root stringByAppendingPathComponent:@"launch-safe/raft-diagnostics.jsonl.sock"];
        int socketFd = socket(AF_UNIX, SOCK_STREAM, 0);
        Require(socketFd >= 0, @"create socket fixture");
        struct sockaddr_un address = {0};
        address.sun_family = AF_UNIX;
        strlcpy(address.sun_path, socketPath.fileSystemRepresentation, sizeof(address.sun_path));
        Require(bind(socketFd, (struct sockaddr *)&address, sizeof(address)) == 0, @"bind socket fixture");
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(fm, root, socketPath) == nil,
                @"socket must fail closed");
        close(socketFd);

        CharacterDeviceFileManager *deviceFileManager = [[CharacterDeviceFileManager alloc] init];
        Require(RaftDiagnosticsCanonicalDescendantRegularFilePath(
                    deviceFileManager, @"/trusted", @"/trusted/raft-diagnostics.jsonl") == nil,
                @"character device must fail closed");

        [fm removeItemAtPath:temp error:nil];
        NSLog(@"PASS: diagnostics path admission");
    }
    return 0;
}
