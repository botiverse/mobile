import {
  IKuiklyViewDelegate,
  KRMonitorType,
  KRRecord,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { SlockLucideIconView } from './components/SlockLucideIconView';
import { SlockLucidePathIconView } from './components/SlockLucidePathIconView';
import { SlockOhosHdsTabBarView } from './components/SlockOhosHdsTabBarView';
import { KRBridgeModule } from './modules/KRBridgeModule';
import { SlockOhosDiagnostics } from './SlockOhosDiagnostics';
import { SlockNetworkModule } from './modules/SlockNetworkModule';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  private static readonly PERFORMANCE_LOG_TAG: string = 'SlockKuiklyPerf';
  private static readonly PERFORMANCE_LOG_LIMIT: number = 512;

  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    map.set(SlockLucideIconView.VIEW_NAME, () => new SlockLucideIconView());
    map.set(SlockLucidePathIconView.VIEW_NAME, () => new SlockLucidePathIconView());
    map.set(SlockOhosHdsTabBarView.VIEW_NAME, () => new SlockOhosHdsTabBarView());
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRBridgeModule.MODULE_NAME, () => new KRBridgeModule());
    map.set(SlockNetworkModule.MODULE_NAME, () => new SlockNetworkModule());
    return map;
  }

  performanceMonitorTypes(): Array<KRMonitorType> {
    return [KRMonitorType.LAUNCH, KRMonitorType.FRAME, KRMonitorType.MEMORY];
  }

  onGetLaunchData(data: KRRecord): void {
    this.logPerformanceRecord('launch', data);
  }

  onGetPerformanceData(data: KRRecord): void {
    this.logPerformanceRecord('perf', data);
  }

  private logPerformanceRecord(kind: string, data: KRRecord): void {
    const raw = JSON.stringify(data);
    const clipped =
      raw.length > KuiklyViewDelegate.PERFORMANCE_LOG_LIMIT
        ? `${raw.substring(0, KuiklyViewDelegate.PERFORMANCE_LOG_LIMIT)}...`
        : raw;
    SlockOhosDiagnostics.record(KuiklyViewDelegate.PERFORMANCE_LOG_TAG, `${kind} ${clipped}`);
    console.info(`[${KuiklyViewDelegate.PERFORMANCE_LOG_TAG}] ${kind} ${clipped}`);
  }
}
