import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { bootstrapRealtime, SlockOhosRealtimeHostContext } from './SlockOhosRealtimeHostContext';

export class SlockOhosRealtimeBridge {
  private readonly hostContext: SlockOhosRealtimeHostContext = new SlockOhosRealtimeHostContext();

  setController(controller?: KRNativeRenderController): void {
    this.hostContext.bindController(controller);
    bootstrapRealtime(this.hostContext);
  }

  handleCommand(params: KRRecord): void {
    this.hostContext.handleCommand(params);
  }

  onPageShow(): void {
    this.hostContext.onPageShow();
  }

  onPageHide(keepAlive: boolean = false): void {
    this.hostContext.onPageHide(keepAlive);
  }

  disconnectForSessionEnd(reason: string): void {
    this.hostContext.disconnectForSessionEnd(reason);
  }
}

const slockOhosRealtimeBridge = new SlockOhosRealtimeBridge();

export default slockOhosRealtimeBridge;
