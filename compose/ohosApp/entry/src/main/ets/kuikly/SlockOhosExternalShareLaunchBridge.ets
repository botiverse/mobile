import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import { SlockOhosExternalShareLaunch } from '../entryability/SlockOhosExternalShareContract';

const EXTERNAL_SHARE_LAUNCH_EVENT = 'slockExternalShareLaunch';

/** Delivers a token-only warm launch to an already-mounted shared pager exactly once per token. */
export class SlockOhosExternalShareLaunchBridge {
  private controller?: KRNativeRenderController;
  private latest?: SlockOhosExternalShareLaunch;
  private deliveryState: SlockOhosExternalShareBridgeDeliveryState =
    new SlockOhosExternalShareBridgeDeliveryState();

  setController(controller?: KRNativeRenderController): void {
    if (this.controller === controller) return;
    this.controller = controller;
    this.deliveryState.onControllerChanged();
    this.flush();
  }

  publish(launch: SlockOhosExternalShareLaunch): void {
    if (launch.externalShareToken.length === 0) return;
    this.latest = launch;
    this.flush();
  }

  private flush(): void {
    if (this.controller === undefined || this.latest === undefined) return;
    const launch = this.latest;
    if (!this.deliveryState.shouldDeliver(launch.externalShareToken)) return;
    this.controller.sendEvent(EXTERNAL_SHARE_LAUNCH_EVENT, {
      intent: launch.intent,
      externalShareToken: launch.externalShareToken
    } as KRRecord);
    this.deliveryState.markDelivered(launch.externalShareToken);
  }
}

export class SlockOhosExternalShareBridgeDeliveryState {
  private controllerEpoch: number = 0;
  private deliveredEpoch: number = -1;
  private deliveredToken: string = '';

  onControllerChanged(): void {
    this.controllerEpoch += 1;
  }

  shouldDeliver(token: string): boolean {
    return token.length > 0 && (this.deliveredEpoch !== this.controllerEpoch || this.deliveredToken !== token);
  }

  markDelivered(token: string): void {
    this.deliveredEpoch = this.controllerEpoch;
    this.deliveredToken = token;
  }
}

const slockOhosExternalShareLaunchBridge = new SlockOhosExternalShareLaunchBridge();

export default slockOhosExternalShareLaunchBridge;
