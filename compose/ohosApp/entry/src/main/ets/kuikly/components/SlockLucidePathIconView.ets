import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { SlockLucidePathEntry, slockLucidePathEntries } from '../generated/SlockLucidePathIcons';

const kName = 'name';
const kColor = 'color';
const kFill = 'fill';
const kStrokeWidth = 'strokeWidth';

@Observed
export class SlockLucidePathIconView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockLucidePathIconView';
  iconName: string = '';
  iconColor: ResourceColor = '#000000';
  iconFill: ResourceColor = '#00000000';
  iconFillEnabled: boolean = false;
  iconStrokeWidth: number = 0;

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
      case kFill:
        this.iconFillEnabled = hasColorValue(propValue as string);
        this.iconFill = this.iconFillEnabled ? normalizeColor(propValue as string) : '#00000000';
        return true;
      case kStrokeWidth:
        this.iconStrokeWidth = normalizeStrokeWidth(propValue);
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

  strokeWidth(entry: SlockLucidePathEntry): number {
    return this.iconStrokeWidth > 0 ? this.iconStrokeWidth : entry.strokeWidth;
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
          .fill(pathFillPaint(this.renderView.iconColor, this.renderView.iconFill, this.renderView.iconFillEnabled, entry.fillOpacity))
          .stroke(pathPaint(this.renderView.iconColor, entry.strokeOpacity))
          .strokeWidth(entry.strokeOpacity > 0 ? `${this.renderView.strokeWidth(entry)}px` : 0)
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

function hasColorValue(value: string): boolean {
  return value !== null && value !== undefined && value.length > 0;
}

function normalizeStrokeWidth(value: KRAny | KuiklyRenderCallback): number {
  if (typeof value === 'number') {
    return value > 0 ? value : 0;
  }
  if (typeof value === 'string') {
    const parsed = Number(value);
    return parsed > 0 ? parsed : 0;
  }
  return 0;
}

function pathPaint(color: ResourceColor, opacity: number): ResourceColor {
  return opacity > 0 ? color : '#00000000';
}

function pathFillPaint(strokeColor: ResourceColor, fillColor: ResourceColor, fillEnabled: boolean, opacity: number): ResourceColor {
  if (fillEnabled) {
    return fillColor;
  }
  return pathPaint(strokeColor, opacity);
}

@Builder
function createSlockLucidePathIconView(view: KuiklyRenderBaseView) {
  SlockLucidePathIcon({ renderView: view as SlockLucidePathIconView });
}
