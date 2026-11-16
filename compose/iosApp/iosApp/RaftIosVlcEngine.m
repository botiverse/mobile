#import "RaftIosVlcEngine.h"

#import <MobileVLCKit/MobileVLCKit.h>

/// How long the open probe may take before playback is left to decide (a slow link is not a
/// failure: only libVLC answering "failed" is).
static const int RaftIosVlcEngineProbeTimeoutMs = 15000;

static void RaftIosVlcEngineOnMain(dispatch_block_t block) {
    if (NSThread.isMainThread) {
        block();
    } else {
        dispatch_async(dispatch_get_main_queue(), block);
    }
}

@interface RaftIosVlcEngine () <VLCMediaPlayerDelegate, VLCMediaDelegate>
@end

@implementation RaftIosVlcEngine {
    VLCMediaPlayer *_player;
    VLCMedia *_media;
    BOOL _prepared;
    BOOL _shutDown;
    BOOL _started;
    BOOL _ended;
    BOOL _hasPendingSeek;
    int64_t _pendingSeekMs;
    BOOL _muted;
    float _rate;
    RaftIosVlcEngineState _lastState;
}

- (instancetype)initWithURL:(NSURL *)url drawable:(UIView *)drawable {
    self = [super init];
    if (self) {
        _rate = 1.0f;
        _lastState = RaftIosVlcEngineStateIdle;
        _media = [VLCMedia mediaWithURL:url];
        _media.delegate = self;
        _player = [[VLCMediaPlayer alloc] init];
        _player.delegate = self;
        _player.drawable = drawable;
        _player.media = _media;
    }
    return self;
}

- (void)dealloc {
    [self shutdown];
}

#pragma mark - Control

- (void)prepare {
    if (_prepared || _shutDown) return;
    _prepared = YES;
    int started = [_media parseWithOptions:VLCMediaParseNetwork timeout:RaftIosVlcEngineProbeTimeoutMs];
    if (started != 0) {
        // The probe could not start (not a verdict on the file): leave it to playback.
        void (^prepared)(BOOL, int64_t) = self.onPrepared;
        if (prepared) prepared(YES, 0);
    }
}

- (void)play {
    if (_shutDown) return;
    if (_ended || _player.state == VLCMediaPlayerStateEnded) {
        // libVLC does not restart an ended input on -play; stop it first.
        _ended = NO;
        [_player stop];
    }
    [_player play];
}

- (void)pause {
    if (_shutDown) return;
    if (_player.isPlaying) [_player pause];
}

- (void)seekToMs:(int64_t)timeMs {
    if (_shutDown) return;
    int64_t target = MAX((int64_t)0, timeMs);
    if (!_started || _ended || _player.state == VLCMediaPlayerStateStopped || _player.state == VLCMediaPlayerStateEnded) {
        _hasPendingSeek = YES;
        _pendingSeekMs = target;
        return;
    }
    _player.time = [VLCTime timeWithInt:(int)MIN(target, (int64_t)INT_MAX)];
}

- (void)shutdown {
    if (_shutDown) return;
    _shutDown = YES;
    self.onStateChanged = nil;
    self.onTimeChanged = nil;
    self.onPrepared = nil;
    _media.delegate = nil;
    _player.delegate = nil;
    if (_prepared) [_media parseStop];
    [_player stop];
    _player.drawable = nil;
}

- (BOOL)playing {
    return !_shutDown && _player.isPlaying;
}

- (int64_t)durationMs {
    return MAX((int64_t)0, (int64_t)_media.length.intValue);
}

- (BOOL)muted {
    return _muted;
}

- (void)setMuted:(BOOL)muted {
    _muted = muted;
    if (!_shutDown) _player.audio.muted = muted;
}

- (float)rate {
    return _rate;
}

- (void)setRate:(float)rate {
    _rate = rate > 0 ? rate : 1.0f;
    if (!_shutDown && _started) _player.rate = _rate;
}

#pragma mark - VLCMediaDelegate

- (void)mediaDidFinishParsing:(VLCMedia *)aMedia {
    RaftIosVlcEngineOnMain(^{
        if (self->_shutDown) return;
        void (^prepared)(BOOL, int64_t) = self.onPrepared;
        if (!prepared) return;
        // Timeout / skipped say nothing about the file; only "failed" means libVLC could not open it.
        BOOL opened = aMedia.parsedStatus != VLCMediaParsedStatusFailed;
        prepared(opened, self.durationMs);
    });
}

#pragma mark - VLCMediaPlayerDelegate

- (void)mediaPlayerStateChanged:(NSNotification *)aNotification {
    RaftIosVlcEngineOnMain(^{
        [self handleStateChange];
    });
}

- (void)mediaPlayerTimeChanged:(NSNotification *)aNotification {
    RaftIosVlcEngineOnMain(^{
        if (self->_shutDown) return;
        void (^timeChanged)(int64_t, int64_t) = self.onTimeChanged;
        if (timeChanged) timeChanged(MAX((int64_t)0, (int64_t)self->_player.time.intValue), self.durationMs);
    });
}

- (void)handleStateChange {
    if (_shutDown) return;
    RaftIosVlcEngineState next;
    switch (_player.state) {
        case VLCMediaPlayerStateOpening:
            next = RaftIosVlcEngineStateOpening;
            break;
        case VLCMediaPlayerStateBuffering:
            // libVLC reports buffering throughout playback; only waiting to start counts.
            if (_player.isPlaying) return;
            next = RaftIosVlcEngineStateBuffering;
            break;
        case VLCMediaPlayerStatePlaying:
            next = RaftIosVlcEngineStatePlaying;
            if (!_started) {
                _started = YES;
                if (_rate != 1.0f) _player.rate = _rate;
            }
            _player.audio.muted = _muted;
            if (_hasPendingSeek) {
                _hasPendingSeek = NO;
                _player.time = [VLCTime timeWithInt:(int)MIN(_pendingSeekMs, (int64_t)INT_MAX)];
            }
            break;
        case VLCMediaPlayerStatePaused:
            next = RaftIosVlcEngineStatePaused;
            break;
        case VLCMediaPlayerStateEnded:
            _ended = YES;
            next = RaftIosVlcEngineStateEnded;
            break;
        case VLCMediaPlayerStateStopped:
            // -stop after the end (and our own restart) also lands here: keep "ended".
            if (_ended || _lastState == RaftIosVlcEngineStateIdle) return;
            next = RaftIosVlcEngineStatePaused;
            break;
        case VLCMediaPlayerStateError:
            next = RaftIosVlcEngineStateFailed;
            break;
        default:
            return;
    }
    if (next == _lastState) return;
    _lastState = next;
    void (^stateChanged)(RaftIosVlcEngineState) = self.onStateChanged;
    if (stateChanged) stateChanged(next);
}

@end
