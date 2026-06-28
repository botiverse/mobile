#import "SlockKRVideoViewHandler.h"

#import <AVFoundation/AVFoundation.h>
#import <math.h>

@interface SlockKRVideoViewHandler ()

@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerLayer *playerLayer;
@property (nonatomic, strong) id timeObserver;
@property (nonatomic, assign) BOOL observingStatus;
@property (nonatomic, assign) BOOL firstFrameReported;
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
        _player = [AVPlayer playerWithPlayerItem:item];
        _playerLayer = [AVPlayerLayer playerLayerWithPlayer:_player];
        _playerLayer.frame = self.bounds;
        _playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
        [self.layer addSublayer:_playerLayer];

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
    self.playerLayer.frame = self.bounds;
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
        self.playerLayer.videoGravity = AVLayerVideoGravityResize;
    } else if (videoViewContentMode == KRVideoViewContentModeScaleAspectFill) {
        self.playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill;
    } else {
        self.playerLayer.videoGravity = AVLayerVideoGravityResizeAspect;
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
}

- (void)krv_callWithMethod:(NSString *)method params:(NSString *)params {
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
}

@end
