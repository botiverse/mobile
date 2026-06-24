import { formBindingData, FormExtensionAbility, formInfo, formProvider } from '@kit.FormKit';
import { Want } from '@kit.AbilityKit';
import {
  clearWidgetServerConfig,
  fallbackActivityUnreadSnapshot,
  fallbackAgentRuntimeSnapshot,
  FORM_AGENT_RUNTIME,
  loadActivityUnreadSnapshot,
  loadAgentRuntimeSnapshot,
  seedWidgetServerConfig,
  snapshotRecord,
  SlockWidgetSnapshot
} from '../forms/SlockWidgetData';

const formNameById: Map<string, string> = new Map();

export default class SlockFormAbility extends FormExtensionAbility {
  onAddForm(want: Want): formBindingData.FormBindingData {
    const requestedName = formName(want);
    const formId = formIdFromWant(want);
    if (formId.length > 0) {
      formNameById.set(formId, requestedName);
    }
    const snapshot = fallbackForName(requestedName);
    void seedWidgetServerConfig(this.context, formId);
    void this.refreshForm(formId, requestedName);
    return formBindingData.createFormBindingData(snapshotRecord(snapshot));
  }

  onUpdateForm(formId: string, wantParams?: Record<string, Object>): void {
    const requestedName = `${wantParams?.[formInfo.FormParam.NAME_KEY] ?? formNameById.get(formId) ?? ''}`;
    if (requestedName.length > 0) {
      formNameById.set(formId, requestedName);
    }
    void this.refreshForm(formId, requestedName);
  }

  onFormEvent(formId: string, message: string): void {
    void this.refreshForm(formId, message.length > 0 ? message : (formNameById.get(formId) ?? ''));
  }

  onRemoveForm(formId: string): void {
    formNameById.delete(formId);
    clearWidgetServerConfig(this.context, formId);
  }

  onAcquireFormState(_want: Want): formInfo.FormState {
    return formInfo.FormState.READY;
  }

  private async refreshForm(formId: string, requestedName: string): Promise<void> {
    if (formId.length === 0) {
      return;
    }
    const snapshot = await this.loadSnapshot(formId, requestedName);
    await formProvider.updateForm(formId, formBindingData.createFormBindingData(snapshotRecord(snapshot)));
  }

  private async loadSnapshot(formId: string, requestedName: string): Promise<SlockWidgetSnapshot> {
    try {
      if (requestedName === FORM_AGENT_RUNTIME) {
        return await loadAgentRuntimeSnapshot(this.context, formId);
      }
      return await loadActivityUnreadSnapshot(this.context, formId);
    } catch (_error) {
      return requestedName === FORM_AGENT_RUNTIME
        ? fallbackAgentRuntimeSnapshot('Open Raft to sync', 'Unable to refresh')
        : fallbackActivityUnreadSnapshot('Open Raft to sync', 'Unable to refresh');
    }
  }
}

function fallbackForName(requestedName: string): SlockWidgetSnapshot {
  return requestedName === FORM_AGENT_RUNTIME
    ? fallbackAgentRuntimeSnapshot()
    : fallbackActivityUnreadSnapshot();
}

function formName(want: Want): string {
  return `${want.parameters?.[formInfo.FormParam.NAME_KEY] ?? ''}`;
}

function formIdFromWant(want: Want): string {
  return `${want.parameters?.[formInfo.FormParam.IDENTITY_KEY] ?? ''}`;
}
