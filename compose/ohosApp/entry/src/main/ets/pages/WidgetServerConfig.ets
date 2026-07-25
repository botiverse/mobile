import { common } from '@kit.AbilityKit';
import { formBindingData, formProvider } from '@kit.FormKit';
import {
  fallbackActivityUnreadSnapshot,
  fallbackAgentRuntimeSnapshot,
  FORM_AGENT_RUNTIME,
  loadActivityUnreadSnapshot,
  loadAgentRuntimeSnapshot,
  readWidgetServerOptions,
  saveWidgetServerConfig,
  snapshotRecord,
  SlockWidgetServerOption
} from '../forms/SlockWidgetData';
import {
  WIDGET_CONFIG_FORM_ID_KEY,
  WIDGET_CONFIG_FORM_NAME_KEY
} from '../entryability/SlockWidgetServerConfigAbility';

@Entry
@Component
struct WidgetServerConfig {
  @StorageLink(WIDGET_CONFIG_FORM_ID_KEY) formId: string = '';
  @StorageLink(WIDGET_CONFIG_FORM_NAME_KEY) formName: string = '';
  @State options: SlockWidgetServerOption[] = [];
  @State selectedServerId: string = '';
  @State loading: boolean = true;
  @State errorText: string = '';

  aboutToAppear(): void {
    void this.loadServers();
  }

  build() {
    Column({ space: 18 }) {
      Text(this.localizedString('widget_config_choose_server'))
        .fontSize(26)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.Black)
        .width('100%')
      Text(this.localizedString('widget_config_description'))
        .fontSize(15)
        .fontColor('#4F4F4F')
        .width('100%')

      if (this.loading) {
        LoadingProgress()
          .width(36)
          .height(36)
          .margin({ top: 28 })
      } else if (this.errorText.length > 0) {
        Text(this.errorText)
          .fontSize(16)
          .fontColor('#8A1F11')
          .width('100%')
      } else {
        ForEach(this.options, (option: SlockWidgetServerOption) => {
          this.serverRow(option)
        }, (option: SlockWidgetServerOption) => option.id)
      }

      Blank()

      Button(this.localizedString('widget_config_save'))
        .width('100%')
        .height(48)
        .enabled(this.selectedServerId.length > 0)
        .backgroundColor(this.selectedServerId.length > 0 ? '#F45D8F' : '#D8D8D8')
        .fontColor(Color.Black)
        .onClick(() => {
          void this.saveAndClose();
        })
    }
    .width('100%')
    .height('100%')
    .padding({ left: 24, right: 24, top: 36, bottom: 24 })
    .backgroundColor('#FFF8EA')
  }

  @Builder
  private serverRow(option: SlockWidgetServerOption) {
    Row() {
      Column({ space: 4 }) {
        Text(option.name)
          .fontSize(18)
          .fontWeight(FontWeight.Medium)
          .fontColor(Color.Black)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        if (option.slug.length > 0) {
          Text(`/${option.slug}`)
            .fontSize(13)
            .fontColor('#696969')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
        }
      }
      .layoutWeight(1)

      Text(this.localizedString(option.id === this.selectedServerId
        ? 'widget_config_selected'
        : 'widget_config_select'))
        .fontSize(13)
        .fontWeight(FontWeight.Bold)
        .fontColor(Color.Black)
        .padding({ left: 10, right: 10, top: 5, bottom: 5 })
        .backgroundColor(option.id === this.selectedServerId ? '#F45D8F' : '#FFFFFF')
        .border({ width: 1, color: Color.Black })
    }
    .width('100%')
    .padding(14)
    .border({ width: 1, color: Color.Black })
    .backgroundColor(option.id === this.selectedServerId ? '#FFE1EB' : '#FFFFFF')
    .onClick(() => {
      this.selectedServerId = option.id;
    })
  }

  private async loadServers(): Promise<void> {
    this.loading = true;
    this.errorText = '';
    try {
      const context = getContext(this) as common.UIAbilityContext;
      const options = await readWidgetServerOptions(context, this.formId);
      this.options = options;
      this.selectedServerId = options.find((option: SlockWidgetServerOption) => option.selected)?.id ?? options[0]?.id ?? '';
      if (options.length === 0) {
        this.errorText = this.localizedString('widget_config_sign_in_required');
      }
    } catch (_error) {
      this.errorText = this.localizedString('widget_config_load_error');
    } finally {
      this.loading = false;
    }
  }

  private async saveAndClose(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    const saved = saveWidgetServerConfig(context, this.formId, this.selectedServerId);
    if (!saved) {
      this.errorText = this.localizedString('widget_config_save_error');
      return;
    }
    await this.refreshConfiguredForm(context);
    await context.terminateSelf();
  }

  private localizedString(name: string): string {
    const context = getContext(this) as common.UIAbilityContext;
    return context.resourceManager.getStringByNameSync(name);
  }

  private async refreshConfiguredForm(context: common.UIAbilityContext): Promise<void> {
    if (this.formId.length === 0) {
      return;
    }
    try {
      const snapshot = this.formName === FORM_AGENT_RUNTIME
        ? await loadAgentRuntimeSnapshot(context, this.formId)
        : await loadActivityUnreadSnapshot(context, this.formId);
      await formProvider.updateForm(this.formId, formBindingData.createFormBindingData(snapshotRecord(snapshot)));
    } catch (_error) {
      const snapshot = this.formName === FORM_AGENT_RUNTIME
        ? fallbackAgentRuntimeSnapshot('Refresh from widget')
        : fallbackActivityUnreadSnapshot('Refresh from widget');
      try {
        await formProvider.updateForm(this.formId, formBindingData.createFormBindingData(snapshotRecord(snapshot)));
      } catch (_fallbackError) {
        // The saved server selection is still valid; the next system update will refresh the form.
      }
    }
  }
}
