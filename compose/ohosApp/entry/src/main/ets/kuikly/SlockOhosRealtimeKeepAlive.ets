import common from '@ohos.app.ability.common';
import Want from '@ohos.app.ability.Want';
import notificationManager from '@ohos.notificationManager';
import backgroundTaskManager from '@ohos.resourceschedule.backgroundTaskManager';
import wantAgent, { WantAgent } from '@ohos.wantAgent';

const LOG_TAG = 'SlockOhosRealtimeKeepAlive';
const NOTIFICATION_ID = 73001;

class SlockOhosRealtimeKeepAlive {
  private running: boolean = false;
  private fallbackNotificationPosted: boolean = false;

  start(context: common.UIAbilityContext, source: string = 'unknown'): void {
    this.startInternal(context, source).catch((error: Object) => {
      console.warn(`[${LOG_TAG}] start_failed source=${source} message=${this.safeErrorMessage(error)}`);
      this.publishFallbackNotification().catch((fallbackError: Object) => {
        console.warn(`[${LOG_TAG}] fallback_notification_failed message=${this.safeErrorMessage(fallbackError)}`);
      });
    });
  }

  stop(context: common.UIAbilityContext, reason: string = 'unknown'): void {
    this.stopInternal(context, reason).catch((error: Object) => {
      console.warn(`[${LOG_TAG}] stop_failed reason=${reason} message=${this.safeErrorMessage(error)}`);
    });
  }

  private async startInternal(context: common.UIAbilityContext, source: string): Promise<void> {
    if (this.running) {
      console.info(`[${LOG_TAG}] start_skip reason=already_running source=${source}`);
      return;
    }
    const agent = await this.buildLaunchWantAgent(context);
    await backgroundTaskManager.startBackgroundRunning(
      context,
      backgroundTaskManager.BackgroundMode.DATA_TRANSFER,
      agent
    );
    this.running = true;
    this.fallbackNotificationPosted = false;
    console.info(`[${LOG_TAG}] continuous_task_started source=${source}`);
  }

  private async stopInternal(context: common.UIAbilityContext, reason: string): Promise<void> {
    if (this.running) {
      await backgroundTaskManager.stopBackgroundRunning(context);
      this.running = false;
      console.info(`[${LOG_TAG}] continuous_task_stopped reason=${reason}`);
    }
    if (this.fallbackNotificationPosted) {
      await notificationManager.cancel(NOTIFICATION_ID);
      this.fallbackNotificationPosted = false;
      console.info(`[${LOG_TAG}] fallback_notification_cancelled reason=${reason}`);
    }
  }

  private async buildLaunchWantAgent(context: common.UIAbilityContext): Promise<WantAgent> {
    const launchWant: Want = {
      bundleName: context.abilityInfo.bundleName,
      abilityName: context.abilityInfo.name,
    };
    return wantAgent.getWantAgent({
      wants: [launchWant],
      operationType: wantAgent.OperationType.START_ABILITY,
      requestCode: NOTIFICATION_ID,
      wantAgentFlags: [wantAgent.WantAgentFlags.UPDATE_PRESENT_FLAG],
    });
  }

  private async publishFallbackNotification(): Promise<void> {
    if (!(await notificationManager.isNotificationEnabled())) {
      console.warn(`[${LOG_TAG}] fallback_notification_skip reason=permission_denied`);
      return;
    }
    await notificationManager.addSlot(notificationManager.SlotType.SOCIAL_COMMUNICATION);
    await notificationManager.publish({
      id: NOTIFICATION_ID,
      notificationSlotType: notificationManager.SlotType.SOCIAL_COMMUNICATION,
      tapDismissed: false,
      content: {
        notificationContentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normal: {
          title: 'Raft is keeping realtime connected',
          text: 'Background message delivery is active.',
        },
      },
    });
    this.fallbackNotificationPosted = true;
  }

  private safeErrorMessage(error: Object): string {
    try {
      const encoded = JSON.stringify(error);
      if (encoded !== undefined && encoded.length > 0 && encoded !== '{}') {
        return encoded;
      }
    } catch (_err) {
      // Fall back to String(error).
    }
    return String(error);
  }
}

const slockOhosRealtimeKeepAlive = new SlockOhosRealtimeKeepAlive();

export default slockOhosRealtimeKeepAlive;
