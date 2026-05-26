import { BusinessError } from '@kit.BasicServicesKit';
import { motion } from '@kit.MultimodalAwarenessKit';
import { KRNativeRenderController, KRRecord } from '@kuikly-open/render';
import deviceInfo from '@ohos.deviceInfo';

const MOTION_EVENT_NAME = 'slockMotionEvent';
const MOTION_SYSCAP = 'SystemCapability.MultimodalAwareness.Motion';
const OPERATING_HAND_MIN_API_VERSION = 15;
const HOLDING_HAND_MIN_API_VERSION = 20;

type OperatingHandCallback = Callback<motion.OperatingHandStatus>;
type HoldingHandCallback = Callback<motion.HoldingHandStatus>;

export class SlockOhosMotionBridge {
  private controller?: KRNativeRenderController;
  private operatingHandCallback?: OperatingHandCallback;
  private holdingHandCallback?: HoldingHandCallback;

  setController(controller?: KRNativeRenderController): void {
    this.controller = controller;
  }

  handleCommand(params: KRRecord): KRRecord {
    const action = params['action'] as string;
    switch (action) {
      case 'startOperatingHand':
        return this.startOperatingHand();
      case 'stopOperatingHand':
        return this.stopOperatingHand();
      case 'getRecentOperatingHand':
        return this.getRecentOperatingHand();
      case 'startHoldingHand':
        return this.startHoldingHand();
      case 'stopHoldingHand':
        return this.stopHoldingHand();
      case 'stopAll':
        return this.stopAll();
      default:
        return this.errorResult('unsupported_action', `Unsupported motion action: ${action}`);
    }
  }

  private startOperatingHand(): KRRecord {
    const supported = this.supportStatus(OPERATING_HAND_MIN_API_VERSION);
    if (supported !== null) {
      return supported;
    }
    if (this.operatingHandCallback !== undefined) {
      return this.okResult('already_started');
    }

    const callback: OperatingHandCallback = (status: motion.OperatingHandStatus) => {
      this.sendMotionEvent('operatingHand', status, this.operatingHandStatusName(status), 'changed');
    };
    try {
      motion.on('operatingHandChanged', callback);
      this.operatingHandCallback = callback;
      return this.okResult('started');
    } catch (error) {
      return this.businessErrorResult('start_operating_hand_failed', error);
    }
  }

  private stopOperatingHand(): KRRecord {
    try {
      if (this.operatingHandCallback !== undefined) {
        motion.off('operatingHandChanged', this.operatingHandCallback);
        this.operatingHandCallback = undefined;
      } else {
        motion.off('operatingHandChanged');
      }
      return this.okResult('stopped');
    } catch (error) {
      return this.businessErrorResult('stop_operating_hand_failed', error);
    }
  }

  private getRecentOperatingHand(): KRRecord {
    const supported = this.supportStatus(OPERATING_HAND_MIN_API_VERSION);
    if (supported !== null) {
      return supported;
    }
    try {
      const status = motion.getRecentOperatingHandStatus();
      return {
        status: 'ok',
        code: 'recent_operating_hand',
        kind: 'operatingHand',
        handStatus: status,
        handStatusName: this.operatingHandStatusName(status),
        apiVersion: this.currentApiVersion(),
        timestampMillis: Date.now(),
      };
    } catch (error) {
      return this.businessErrorResult('get_recent_operating_hand_failed', error);
    }
  }

  private startHoldingHand(): KRRecord {
    const supported = this.supportStatus(HOLDING_HAND_MIN_API_VERSION);
    if (supported !== null) {
      return supported;
    }
    if (this.holdingHandCallback !== undefined) {
      return this.okResult('already_started');
    }

    const callback: HoldingHandCallback = (status: motion.HoldingHandStatus) => {
      this.sendMotionEvent('holdingHand', status, this.holdingHandStatusName(status), 'changed');
    };
    try {
      motion.on('holdingHandChanged', callback);
      this.holdingHandCallback = callback;
      return this.okResult('started');
    } catch (error) {
      return this.businessErrorResult('start_holding_hand_failed', error);
    }
  }

  private stopHoldingHand(): KRRecord {
    try {
      if (this.holdingHandCallback !== undefined) {
        motion.off('holdingHandChanged', this.holdingHandCallback);
        this.holdingHandCallback = undefined;
      } else {
        motion.off('holdingHandChanged');
      }
      return this.okResult('stopped');
    } catch (error) {
      return this.businessErrorResult('stop_holding_hand_failed', error);
    }
  }

  private stopAll(): KRRecord {
    const operatingResult = this.stopOperatingHand();
    const holdingResult = this.stopHoldingHand();
    return {
      status: operatingResult['status'] === 'ok' && holdingResult['status'] === 'ok' ? 'ok' : 'error',
      code: 'stopped_all',
      operatingHandStatus: operatingResult['status'],
      holdingHandStatus: holdingResult['status'],
    };
  }

  private sendMotionEvent(kind: string, handStatus: number, handStatusName: string, source: string): void {
    this.controller?.sendEvent(MOTION_EVENT_NAME, {
      kind: kind,
      handStatus: handStatus,
      handStatusName: handStatusName,
      source: source,
      apiVersion: this.currentApiVersion(),
      timestampMillis: Date.now(),
    });
  }

  private supportStatus(minApiVersion: number): KRRecord | null {
    if (!canIUse(MOTION_SYSCAP)) {
      return this.errorResult('motion_capability_unavailable', 'System motion capability is unavailable');
    }
    const apiVersion = this.currentApiVersion();
    if (apiVersion < minApiVersion) {
      return this.errorResult('motion_api_unavailable', `Motion API requires API ${minApiVersion}`);
    }
    return null;
  }

  private currentApiVersion(): number {
    const distributionApiVersion = deviceInfo.distributionOSApiVersion;
    if (distributionApiVersion > 0) {
      return distributionApiVersion;
    }
    return deviceInfo.sdkApiVersion;
  }

  private operatingHandStatusName(status: motion.OperatingHandStatus): string {
    switch (status) {
      case motion.OperatingHandStatus.LEFT_HAND_OPERATED:
        return 'left_hand_operated';
      case motion.OperatingHandStatus.RIGHT_HAND_OPERATED:
        return 'right_hand_operated';
      case motion.OperatingHandStatus.UNKNOWN_STATUS:
      default:
        return 'unknown';
    }
  }

  private holdingHandStatusName(status: motion.HoldingHandStatus): string {
    switch (status) {
      case motion.HoldingHandStatus.NOT_HELD:
        return 'not_held';
      case motion.HoldingHandStatus.LEFT_HAND_HELD:
        return 'left_hand_held';
      case motion.HoldingHandStatus.RIGHT_HAND_HELD:
        return 'right_hand_held';
      case motion.HoldingHandStatus.BOTH_HANDS_HELD:
        return 'both_hands_held';
      case motion.HoldingHandStatus.UNKNOWN_STATUS:
      default:
        return 'unknown';
    }
  }

  private okResult(code: string): KRRecord {
    return {
      status: 'ok',
      code: code,
      apiVersion: this.currentApiVersion(),
    };
  }

  private errorResult(code: string, message: string): KRRecord {
    return {
      status: 'error',
      code: code,
      message: message,
      apiVersion: this.currentApiVersion(),
    };
  }

  private businessErrorResult(code: string, error: Object): KRRecord {
    const businessError = error as BusinessError;
    return {
      status: 'error',
      code: code,
      platformCode: businessError.code ?? -1,
      message: businessError.message ?? `${error}`,
      apiVersion: this.currentApiVersion(),
    };
  }
}

const slockOhosMotionBridge = new SlockOhosMotionBridge();

export default slockOhosMotionBridge;
