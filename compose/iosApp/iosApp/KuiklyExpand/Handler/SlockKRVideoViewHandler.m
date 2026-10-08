#import "SlockKRVideoViewHandler.h"

#import <AVFoundation/AVFoundation.h>
#import <AVKit/AVKit.h>
#import <math.h>

// task #11 (iOS pendant of Android task #556 phase 2): the engine stays
// AVPlayer — iOS's first-party Media3 equivalent; ijkplayer was evaluated
// and rejected (unmaintained FFmpeg base, +8-15MB, buys only codecs our
// mp4/mp3 attachments never use). What changes is the chrome: the bare
// AVPlayerLayer becomes an embedded AVPlayerViewController, which ships the
// mature system controller (tap-to-show play/pause, scrubbing, ±15s,
// fullscreen) for free. The shared minimal controls are hidden on iOS via
// slockPlatformUsesNativeVideoController(), same switch Android uses.
@interface SlockKRVideoViewHandler ()

@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerViewController *playerViewController;
@property (nonatomic, strong) id timeObserver;
@property (nonatomic, assign) BOOL observingStatus;
@property (nonatomic, assign) BOOL firstFrameReported;
@property (nonatomic, assign) BOOL embeddedInParentController;
@property (nonatomic, assign) CGFloat preferredRate;

@end

@implementation SlockKRVideoViewHandler

@synthesize krv_delegate;

+ (void)load {
    [KRVideoView registerVideoViewCreator:^id<KRVideoViewProtocol> _Nonnull(NSString * _Nonnull src, CGRect frame) {
        return [[SlockKRVideoViewHandler alloc] initWithFrame:frame source:src];
    }];
}

- (instancetype)initWithFrame:(CGRect)frame source:(NSString *)src {
    if (self = [super initWithFrame:frame]) {
        _preferredRate = 1.0;
        self.backgroundColor = UIColor.blackColor;

        NSURL *url = [NSURL URLWithString:src ?: @""];
        AVPlayerItem *item = url ? [AVPlayerItem playerItemWithURL:url] : nil;
        // Attachment audio is intentional media playback, including audio-only
        // files. The default SoloAmbient category silences it in Silent mode.
        // Configure before exposing AVKit controls: their Play button bypasses
        // krv_play. AVPlayer activates the session when playback actually starts;
        // merely opening a preview must not interrupt another app's audio.
        NSError *audioSessionError = nil;
        if (![[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayback
                                                    mode:AVAudioSessionModeDefault
                                                 options:0
                                                   error:&audioSessionError]) {
            NSLog(@"[SlockMedia] audio session configuration failed: %@ (%ld)",
                  audioSessionError.domain, (long)audioSessionError.code);
        }
        _player = [AVPlayer playerWithPlayerItem:item];
        _playerViewController = [[AVPlayerViewController alloc] init];
        _playerViewController.player = _player;
        _playerViewController.showsPlaybackControls = YES;
        _playerViewController.videoGravity = AVLayerVideoGravityResizeAspect;
        _playerViewController.view.frame = self.bounds;
        _playerViewController.view.backgroundColor = UIColor.blackColor;
        [self addSubview:_playerViewController.view];

        if (item) {
            [item addObserver:self forKeyPath:@"status" options:NSKeyValueObservingOptionNew context:nil];
            _observingStatus = YES;
            [[NSNotificationCenter defaultCenter] addObserver:self
                                                     selector:@selector(playerItemDidPlayToEnd:)
                                                         name:AVPlayerItemDidPlayToEndTimeNotification
                                                       object:item];
            [[NSNotificationCenter defaultCenter] addObserver:self
                                                     selector:@selector(playerItemFailedToPlay:)
                                                         name:AVPlayerItemFailedToPlayToEndTimeNotification
                                                       object:item];
            [[NSNotificationCenter defaultCenter] addObserver:self
                                                     selector:@selector(playerItemPlaybackStalled:)
                                                         name:AVPlayerItemPlaybackStalledNotification
                                                       object:item];
        }
        [self installTimeObserver];
    }
    return self;
}

- (void)layoutSubviews {
    [super layoutSubviews];
    self.playerViewController.view.frame = self.bounds;
}

// Proper view-controller containment: fullscreen transitions and rotation
// need the AVPlayerViewController in the controller hierarchy, not just its
// view in ours. Attach to the nearest ancestor controller once we are in a
// window; detach on removal.
- (void)didMoveToWindow {
    [super didMoveToWindow];
    if (self.window && !self.embeddedInParentController) {
        UIViewController *parent = [self slock_nearestViewController];
        if (parent) {
            [parent addChildViewController:self.playerViewController];
            [self.playerViewController didMoveToParentViewController:parent];
            self.embeddedInParentController = YES;
        }
    } else if (!self.window && self.embeddedInParentController) {
        [self slock_detachPlayerViewController];
    }
}

- (UIViewController *)slock_nearestViewController {
    UIResponder *responder = self;
    while (responder) {
        responder = responder.nextResponder;
        if ([responder isKindOfClass:UIViewController.class]) {
            return (UIViewController *)responder;
        }
    }
    return nil;
}

- (void)slock_detachPlayerViewController {
    if (!self.embeddedInParentController) {
        return;
    }
    [self.playerViewController willMoveToParentViewController:nil];
    [self.playerViewController removeFromParentViewController];
    self.embeddedInParentController = NO;
}

- (void)dealloc {
    [self cleanupPlayer];
}

#pragma mark - KRVideoViewProtocol

- (void)krv_preplay {
    [self krv_seekToTime:40];
}

- (void)krv_play {
    if (self.preferredRate > 0) {
        [self.player playImmediatelyAtRate:self.preferredRate];
    } else {
        [self.player play];
    }
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStatePlaying extInfo:@{}];
}

- (void)krv_pause {
    [self.player pause];
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStatePaused extInfo:@{}];
}

- (void)krv_stop {
    [self cleanupPlayer];
}

- (void)krv_setVideoContentMode:(KRVideoViewContentMode)videoViewContentMode {
    if (videoViewContentMode == KRVideoViewContentModeScaleToFill) {
        self.playerViewController.videoGravity = AVLayerVideoGravityResize;
    } else if (videoViewContentMode == KRVideoViewContentModeScaleAspectFill) {
        self.playerViewController.videoGravity = AVLayerVideoGravityResizeAspectFill;
    } else {
        self.playerViewController.videoGravity = AVLayerVideoGravityResizeAspect;
    }
}

- (void)krv_setMuted:(BOOL)muted {
    self.player.muted = muted;
}

- (void)krv_setRate:(CGFloat)rate {
    self.preferredRate = MAX(rate, 0.25);
    if (self.player.rate > 0) {
        self.player.rate = self.preferredRate;
    }
}

- (void)krv_seekToTime:(NSUInteger)seekTotime {
    CMTime time = CMTimeMakeWithSeconds(((Float64)seekTotime) / 1000.0, NSEC_PER_SEC);
    [self.player seekToTime:time toleranceBefore:kCMTimeZero toleranceAfter:kCMTimeZero];
}

- (void)krv_setPropWithKey:(NSString *)propKey propValue:(id)propValue {
    if ([propKey isEqualToString:@"seekToTimeMs"]) {
        NSNumber *seekTime = [self slock_seekTimeFromPropValue:propValue];
        if (seekTime) {
            [self krv_seekToTime:[seekTime unsignedIntegerValue]];
        }
    }
}

- (void)krv_callWithMethod:(NSString *)method params:(NSString *)params {
}

- (NSNumber *)slock_seekTimeFromPropValue:(id)propValue {
    if ([propValue isKindOfClass:NSNumber.class]) {
        return (NSNumber *)propValue;
    }
    if ([propValue isKindOfClass:NSString.class]) {
        NSString *value = (NSString *)propValue;
        NSString *rawTime = value;
        NSRange delimiter = [value rangeOfString:@":" options:NSBackwardsSearch];
        if (delimiter.location != NSNotFound && delimiter.location + 1 < value.length) {
            rawTime = [value substringFromIndex:delimiter.location + 1];
        }
        return @([rawTime doubleValue]);
    }
    return nil;
}

#pragma mark - Player events

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey,id> *)change
                       context:(void *)context {
    if ([keyPath isEqualToString:@"status"]) {
        AVPlayerItem *item = (AVPlayerItem *)object;
        if (item.status == AVPlayerItemStatusReadyToPlay) {
            [self reportFirstFrameIfNeeded];
            KRVideoPlayState state = self.player.rate > 0 ? KRVideoPlayStatePlaying : KRVideoPlayStatePaused;
            [self.krv_delegate videoPlayStateDidChangedWithState:state extInfo:@{}];
        } else if (item.status == AVPlayerItemStatusFailed) {
            [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStateFaild extInfo:@{}];
        }
        return;
    }
    [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
}

- (void)playerItemDidPlayToEnd:(NSNotification *)notification {
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStatePlayEnd extInfo:@{}];
}

- (void)playerItemFailedToPlay:(NSNotification *)notification {
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStateFaild extInfo:@{}];
}

- (void)playerItemPlaybackStalled:(NSNotification *)notification {
    [self.krv_delegate videoPlayStateDidChangedWithState:KRVideoPlayStateCaching extInfo:@{}];
}

- (void)installTimeObserver {
    __weak typeof(self) weakSelf = self;
    self.timeObserver =
        [self.player addPeriodicTimeObserverForInterval:CMTimeMakeWithSeconds(0.5, NSEC_PER_SEC)
                                                  queue:dispatch_get_main_queue()
                                             usingBlock:^(CMTime time) {
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) {
            return;
        }
        NSUInteger currentTime = [self millisecondsFromTime:time];
        NSUInteger totalTime = [self millisecondsFromTime:self.player.currentItem.duration];
        [self.krv_delegate playTimeDidChangedWithCurrentTime:currentTime totalTime:totalTime];
    }];
}

- (void)reportFirstFrameIfNeeded {
    if (self.firstFrameReported || !self.krv_delegate) {
        return;
    }
    self.firstFrameReported = YES;
    [self.krv_delegate videoFirstFrameDidDisplay];
}

- (NSUInteger)millisecondsFromTime:(CMTime)time {
    if (!CMTIME_IS_NUMERIC(time)) {
        return 0;
    }
    Float64 seconds = CMTimeGetSeconds(time);
    if (!isfinite(seconds) || seconds <= 0) {
        return 0;
    }
    return (NSUInteger)llround(seconds * 1000.0);
}

- (void)cleanupPlayer {
    AVPlayerItem *item = self.player.currentItem;
    if (self.observingStatus && item) {
        [item removeObserver:self forKeyPath:@"status"];
        self.observingStatus = NO;
    }
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    if (self.timeObserver) {
        [self.player removeTimeObserver:self.timeObserver];
        self.timeObserver = nil;
    }
    [self.player pause];
    [self.player replaceCurrentItemWithPlayerItem:nil];
    [self slock_detachPlayerViewController];
    self.playerViewController.player = nil;
}

@end
