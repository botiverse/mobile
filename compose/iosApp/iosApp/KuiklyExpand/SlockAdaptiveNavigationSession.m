#import "SlockAdaptiveNavigationSession.h"

@implementation SlockAdaptiveNavigationSession {
    NSString *_requestId;
    NSString *_token;
    NSDictionary *_context;
    NSDictionary *_terminal;
    NSTimeInterval _expiresAt;
    BOOL _committing;
}

- (NSDictionary *)result:(NSString *)status request:(NSString *)requestId reason:(NSString *)reason {
    return @{ @"requestId": requestId ?: @"", @"status": status, @"reason": reason };
}

- (NSDictionary *)prepare:(NSString *)requestId context:(NSDictionary *)context now:(NSTimeInterval)now {
    if (requestId.length == 0 || context.count == 0) {
        return [self result:@"rejected" request:requestId reason:@"invalid_request"];
    }
    if (_committing || [_terminal[@"status"] isEqual:@"committed"]) {
        return [self result:@"rejected" request:requestId reason:@"source_consumed"];
    }
    if ([_requestId isEqual:requestId]) {
        if (_terminal) return _terminal;
        if (now >= _expiresAt || ![_context isEqual:context]) {
            _terminal = [self result:@"rejected" request:requestId reason:@"source_changed"];
            return _terminal;
        }
    } else {
        if (_requestId && !_terminal) {
            return [self result:@"rejected" request:requestId reason:@"request_in_progress"];
        }
        _requestId = [requestId copy];
        _token = NSUUID.UUID.UUIDString;
        _context = [context copy];
        _terminal = nil;
        _expiresAt = now + 30.0;
    }
    return @{ @"requestId": _requestId, @"status": @"prepared", @"token": _token,
              @"context": _context, @"expiresInSeconds": @(MAX(0, _expiresAt - now)) };
}

- (NSDictionary *)commit:(NSString *)requestId token:(NSString *)token
                 context:(NSDictionary *)context now:(NSTimeInterval)now
                 perform:(BOOL (^)(void))perform {
    if (!_requestId || ![_requestId isEqual:requestId] || ![_token isEqual:token]) {
        return [self result:@"rejected" request:requestId reason:@"request_mismatch"];
    }
    if (_terminal) return _terminal; // Idempotent; never execute a second pop.
    if (_committing) return [self result:@"rejected" request:requestId reason:@"commit_in_progress"];
    if (now >= _expiresAt || ![_context isEqual:context]) {
        _terminal = [self result:@"rejected" request:requestId reason:@"source_changed"];
        return _terminal;
    }
    _committing = YES;
    BOOL committed = perform();
    _committing = NO;
    _terminal = [self result:committed ? @"committed" : @"rejected" request:requestId
                     reason:committed ? @"" : @"native_commit_failed"];
    return _terminal;
}

- (NSDictionary *)cancel:(NSString *)requestId token:(NSString *)token {
    if (![_requestId isEqual:requestId] || ![_token isEqual:token]) {
        return [self result:@"rejected" request:requestId reason:@"request_mismatch"];
    }
    if (_terminal) return _terminal;
    if (_committing) return [self result:@"rejected" request:requestId reason:@"commit_in_progress"];
    _terminal = [self result:@"cancelled" request:requestId reason:@"cancelled"];
    return _terminal;
}
@end
