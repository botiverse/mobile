#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

/// What a RaftIosVlcEngine is doing, reduced from VLCMediaPlayerState.
typedef NS_ENUM(NSInteger, RaftIosVlcEngineState) {
    RaftIosVlcEngineStateIdle = 0,
    RaftIosVlcEngineStateOpening,
    RaftIosVlcEngineStateBuffering,
    RaftIosVlcEngineStatePlaying,
    RaftIosVlcEngineStatePaused,
    RaftIosVlcEngineStateEnded,
    RaftIosVlcEngineStateFailed,
};

/// One MobileVLCKit (libVLC) player for one network source, drawing into `drawable`.
///
/// A thin Objective-C layer over VLCMediaPlayer / VLCMedia so the Swift player
/// (RaftIosVlcVideoPlayer.swift) talks to a small API with fixed Swift names. Callbacks
/// arrive on the main thread. Main thread only.
NS_SWIFT_NAME(RaftIosVlcEngine)
@interface RaftIosVlcEngine : NSObject

- (instancetype)initWithURL:(NSURL *)url drawable:(UIView *)drawable NS_SWIFT_NAME(init(url:drawable:)) NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

/// The player's state changed.
@property (nonatomic, copy, nullable) void (^onStateChanged)(RaftIosVlcEngineState state);
/// Position and length in milliseconds (length 0 while unknown).
@property (nonatomic, copy, nullable) void (^onTimeChanged)(int64_t currentMs, int64_t durationMs);
/// The probe started by -prepare finished: `opened` is NO when libVLC could not open the
/// source or its container; the length is 0 while unknown.
@property (nonatomic, copy, nullable) void (^onPrepared)(BOOL opened, int64_t durationMs);

/// Probes the source (network parse, no playback). Once.
- (void)prepare NS_SWIFT_NAME(prepare());
- (void)play NS_SWIFT_NAME(play());
- (void)pause NS_SWIFT_NAME(pause());
/// Seeks; before the first play (or after the end) the seek applies when playback starts.
- (void)seekToMs:(int64_t)timeMs NS_SWIFT_NAME(seek(toMs:));
/// Stops playback and drops the callbacks. The engine is not used again.
- (void)shutdown NS_SWIFT_NAME(shutdown());

@property (nonatomic, readonly) BOOL playing;
@property (nonatomic, readonly) int64_t durationMs;
@property (nonatomic) BOOL muted;
@property (nonatomic) float rate;

@end

NS_ASSUME_NONNULL_END
