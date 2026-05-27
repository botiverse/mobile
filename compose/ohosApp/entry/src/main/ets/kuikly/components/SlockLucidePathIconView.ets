import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { SlockLucidePathEntry, slockLucidePathEntries } from '../generated/SlockLucidePathIcons';

const kName = 'name';
const kColor = 'color';

@Observed
export class SlockLucidePathIconView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockLucidePathIconView';
  iconName: string = '';
  iconColor: ResourceColor = '#000000';

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockLucidePathIconView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kName:
        this.iconName = normalizeIconName(propValue as string);
        return true;
      case kColor:
        this.iconColor = normalizeColor(propValue as string);
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  entries(): SlockLucidePathEntry[] {
    return slockLucidePathEntries(this.iconName);
  }
}

@Component
export struct SlockLucidePathIcon {
  @ObjectLink renderView: SlockLucidePathIconView;

  build() {
    Stack() {
      ForEach(this.renderView.entries(), (entry: SlockLucidePathEntry) => {
        Path({
          width: `${entry.viewportWidth}px`,
          height: `${entry.viewportHeight}px`,
          commands: entry.commands,
        })
          .width(`${entry.viewportWidth}px`)
          .height(`${entry.viewportHeight}px`)
          .fill(pathPaint(this.renderView.iconColor, entry.fillOpacity))
          .stroke(pathPaint(this.renderView.iconColor, entry.strokeOpacity))
          .strokeWidth(entry.strokeOpacity > 0 ? `${entry.strokeWidth}px` : 0)
          .strokeLineCap(entry.strokeLineCap)
          .strokeLineJoin(entry.strokeLineJoin)
          .scale({ x: vp2px(1), y: vp2px(1) })
          .antiAlias(true)
      }, (entry: SlockLucidePathEntry) => `${entry.commands}:${entry.strokeWidth}`)
    }
    .width('100%')
    .height('100%')
  }
}

function normalizeIconName(value: string): string {
  if (value === null || value === undefined) {
    return '';
  }
  return value
    .trim()
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .replace(/_/g, '-')
    .toLowerCase();
}

function normalizeColor(value: string): ResourceColor {
  if (value === null || value === undefined || value.length === 0) {
    return '#000000';
  }
  if (value.length === 9 && value.startsWith('#FF')) {
    return '#' + value.substring(3);
  }
  return value;
}

function pathPaint(color: ResourceColor, opacity: number): ResourceColor {
  return opacity > 0 ? color : '#00000000';
}

@Builder
function createSlockLucidePathIconView(view: KuiklyRenderBaseView) {
  SlockLucidePathIcon({ renderView: view as SlockLucidePathIconView });
}
