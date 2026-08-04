export type SlockOhosMobileOAuthConsumer = (uri: string | undefined) => boolean;

/**
 * Fences callback delivery until Kuikly/shared is ready. Clearing the queued
 * URI before invoking the consumer makes cold and warm delivery exactly once,
 * including when the consumer throws.
 */
export class SlockOhosMobileOAuthIngress {
  private pendingUri?: string;
  private pageContentLoaded: boolean = false;
  private readonly consumeCallback: SlockOhosMobileOAuthConsumer;

  constructor(consumeCallback: SlockOhosMobileOAuthConsumer) {
    this.consumeCallback = consumeCallback;
  }

  captureColdStart(uri: string | undefined): void {
    this.pendingUri = uri;
  }

  captureWarmStart(uri: string | undefined): void {
    this.pendingUri = uri;
    this.consumePendingCallback();
  }

  markPageContentLoaded(): void {
    this.pageContentLoaded = true;
    this.consumePendingCallback();
  }

  private consumePendingCallback(): void {
    if (!this.pageContentLoaded) return;
    const uri = this.pendingUri;
    if (uri === undefined || uri.length === 0) return;
    this.pendingUri = undefined;
    this.consumeCallback(uri);
  }
}
