#import <XCTest/XCTest.h>
#import <AVFoundation/AVFoundation.h>
#import <AVKit/AVKit.h>
#import "../iosApp/KuiklyExpand/Handler/SlockKRVideoViewHandler.h"

@interface SlockKRVideoViewHandler (AudioSessionTests)
- (instancetype)initWithFrame:(CGRect)frame source:(NSString *)src;
@end

@interface SlockMediaAudioSessionTests : XCTestCase
@end

@implementation SlockMediaAudioSessionTests

- (void)testPreviewConfiguresPlaybackBeforeNativeControlsCanStartAudio {
    AVAudioSession *session = AVAudioSession.sharedInstance;
    NSString *savedCategory = session.category;
    NSString *savedMode = session.mode;
    AVAudioSessionCategoryOptions savedOptions = session.categoryOptions;
    NSError *error = nil;
    XCTAssertTrue([session setCategory:AVAudioSessionCategorySoloAmbient
                                 mode:AVAudioSessionModeDefault options:0 error:&error], @"%@", error);
    SlockKRVideoViewHandler *view = nil;
    @try {
        view = [[SlockKRVideoViewHandler alloc] initWithFrame:CGRectMake(0, 0, 320, 180) source:@""];
        AVPlayerViewController *controller = [view valueForKey:@"playerViewController"];
        AVPlayer *player = [view valueForKey:@"player"];
        XCTAssertEqualObjects(session.category, AVAudioSessionCategoryPlayback);
        XCTAssertEqualObjects(session.mode, AVAudioSessionModeDefault);
        XCTAssertEqual(session.categoryOptions, 0u);
        XCTAssertEqual(controller.player, player);
        XCTAssertTrue(controller.showsPlaybackControls);
        // Opening a preview configures the policy but must not start playback.
        XCTAssertEqual(player.rate, 0.0f);
        XCTAssertFalse(player.isMuted);
    } @finally {
        [view krv_stop];
        [session setCategory:savedCategory mode:savedMode options:savedOptions error:nil];
    }
}

- (void)testStoppingOnePreviewDetachesItsPlayerWithoutResettingOtherPreviewPolicy {
    AVAudioSession *session = AVAudioSession.sharedInstance;
    NSString *savedCategory = session.category;
    NSString *savedMode = session.mode;
    AVAudioSessionCategoryOptions savedOptions = session.categoryOptions;
    // No network access or audible playback: AVPlayerItem creation is enough
    // to verify the real handler's teardown detaches its media and controller.
    NSString *source = [NSURL fileURLWithPath:[NSTemporaryDirectory()
        stringByAppendingPathComponent:@"slock-audio-session-missing.wav"]].absoluteString;
    SlockKRVideoViewHandler *first = nil;
    SlockKRVideoViewHandler *second = nil;
    @try {
        first = [[SlockKRVideoViewHandler alloc] initWithFrame:CGRectZero source:source];
        second = [[SlockKRVideoViewHandler alloc] initWithFrame:CGRectZero source:source];
        AVPlayer *firstPlayer = [first valueForKey:@"player"];
        AVPlayer *secondPlayer = [second valueForKey:@"player"];
        AVPlayerViewController *firstController = [first valueForKey:@"playerViewController"];
        XCTAssertNotNil(firstPlayer.currentItem);
        XCTAssertNotNil(secondPlayer.currentItem);
        [first krv_stop];
        XCTAssertNil(firstPlayer.currentItem);
        XCTAssertNil(firstController.player);
        XCTAssertEqual(firstPlayer.rate, 0.0f);
        XCTAssertNotNil(secondPlayer.currentItem);
        XCTAssertEqualObjects(session.category, AVAudioSessionCategoryPlayback);
        // Explicit mute remains available; session category does not override it.
        [second krv_setMuted:YES];
        XCTAssertTrue(secondPlayer.isMuted);
        [second krv_setMuted:NO];
        XCTAssertFalse(secondPlayer.isMuted);
    } @finally {
        [first krv_stop];
        [second krv_stop];
        [session setCategory:savedCategory mode:savedMode options:savedOptions error:nil];
    }
}
@end
