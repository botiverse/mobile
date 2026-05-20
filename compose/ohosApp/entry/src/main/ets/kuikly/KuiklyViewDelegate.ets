import {
  IKuiklyViewDelegate,
  KRRenderModuleExportCreator,
  KRRenderViewExportCreator
} from '@kuikly-open/render';
import { SlockLucideIconView } from './components/SlockLucideIconView';
import { SlockOhosHdsTabBarView } from './components/SlockOhosHdsTabBarView';
import { KRBridgeModule } from './modules/KRBridgeModule';

export class KuiklyViewDelegate extends IKuiklyViewDelegate {
  getCustomRenderViewCreatorRegisterMap(): Map<string, KRRenderViewExportCreator> {
    const map: Map<string, KRRenderViewExportCreator> = new Map();
    map.set(SlockLucideIconView.VIEW_NAME, () => new SlockLucideIconView());
    map.set(SlockOhosHdsTabBarView.VIEW_NAME, () => new SlockOhosHdsTabBarView());
    return map;
  }

  getCustomRenderModuleCreatorRegisterMap(): Map<string, KRRenderModuleExportCreator> {
    const map: Map<string, KRRenderModuleExportCreator> = new Map();
    map.set(KRBridgeModule.MODULE_NAME, () => new KRBridgeModule());
    return map;
  }
}
