import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent, NodeContent } from '@kit.ArkUI';
import { SlockLucidePathEntry, slockLucidePathEntries } from '../generated/SlockLucidePathIcons';

const kName = 'name';
const kColor = 'color';
const kFill = 'fill';
const kStrokeWidth = 'strokeWidth';
const kFrame = 'frame';

@Observed
export class SlockLucideIconView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockLucideIconView';
  iconName: string = '';
  iconColor: ResourceColor = '#000000';
  iconFill: ResourceColor = '#00000000';
  iconFillEnabled: boolean = false;
  iconStrokeWidth: number = 0;
  iconRenderWidth: number = 0;
  iconRenderHeight: number = 0;
  renderRevision: number = 0;
  slot: NodeContent | null = new NodeContent();

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockLucideIconView),
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
      case kFrame:
        this.applyFrame(propValue);
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  getContentSlot(): NodeContent | null {
    return this.slot;
  }

  didMoveToParentView(): void {
    this.bumpRenderRevision();
    this.updateArkUI();
  }

  sizeDidChanged(width: number, height: number): void {
    super.sizeDidChanged(width, height);
    this.updateRenderSize(width, height);
  }

  entries(): SlockLucidePathEntry[] {
    return slockLucidePathEntries(this.iconName);
  }

  strokeWidth(entry: SlockLucidePathEntry): number {
    return this.iconStrokeWidth > 0 ? this.iconStrokeWidth : entry.strokeWidth;
  }

  drawScale(entry: SlockLucidePathEntry): number {
    const width = this.iconRenderWidth > 0 ? this.iconRenderWidth : this.getViewWidth();
    const height = this.iconRenderHeight > 0 ? this.iconRenderHeight : this.getViewHeight();
    if (width <= 0 || height <= 0 || entry.viewportWidth <= 0 || entry.viewportHeight <= 0) {
      return 1;
    }
    return vp2px(Math.min(width / entry.viewportWidth, height / entry.viewportHeight));
  }

  private bumpRenderRevision(): void {
    this.renderRevision = this.renderRevision >= 1000000 ? 1 : this.renderRevision + 1;
  }

  private updateRenderSize(width: number, height: number): void {
    if (this.iconRenderWidth !== width || this.iconRenderHeight !== height) {
      this.bumpRenderRevision();
    }
    this.iconRenderWidth = width;
    this.iconRenderHeight = height;
  }

  private applyFrame(value: KRAny | KuiklyRenderCallback): void {
    const frameText = typeof value === 'string' ? value : String(value);
    const fields = frameText.trim().split(/\s+/);
    if (fields.length < 4) {
      return;
    }
    const width = Number(fields[2]);
    const height = Number(fields[3]);
    if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
      this.updateRenderSize(width, height);
    }
  }
}

@Component
export struct SlockLucideIcon {
  @ObjectLink renderView: SlockLucideIconView;

  build() {
    Stack({ alignContent: Alignment.Center }) {
      ForEach(this.renderView.entries(), (entry: SlockLucidePathEntry) => {
        Path({
          width: `${entry.viewportWidth}px`,
          height: `${entry.viewportHeight}px`,
          commands: entry.commands,
        })
          .width(`${entry.viewportWidth}px`)
          .height(`${entry.viewportHeight}px`)
          .fill(pathFillPaint(entry, this.renderView.iconColor, this.renderView.iconFill, this.renderView.iconFillEnabled))
          .stroke(pathStrokePaint(entry, this.renderView.iconColor))
          .strokeWidth(entry.strokeOpacity > 0 ? `${this.renderView.strokeWidth(entry)}px` : 0)
          .strokeLineCap(entry.strokeLineCap)
          .strokeLineJoin(entry.strokeLineJoin)
          .scale({ x: this.renderView.drawScale(entry), y: this.renderView.drawScale(entry) })
          .antiAlias(true)
      }, (entry: SlockLucidePathEntry) => `${this.renderView.renderRevision}:${this.renderView.iconName}:${entry.commands}:${entry.strokeWidth}`)
      ContentSlot(this.renderView.slot)
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

function resolvePathPaint(paint: string, currentColor: ResourceColor): ResourceColor {
  if (paint === 'currentColor') {
    return currentColor;
  }
  if (paint === null || paint === undefined || paint.length === 0 || paint === 'none' || paint === 'transparent') {
    return '#00000000';
  }
  return normalizeColor(paint);
}

function pathStrokePaint(entry: SlockLucidePathEntry, currentColor: ResourceColor): ResourceColor {
  if (entry.strokeOpacity <= 0) {
    return '#00000000';
  }
  return resolvePathPaint(entry.strokePaint, currentColor);
}

function pathFillPaint(entry: SlockLucidePathEntry, currentColor: ResourceColor, fillColor: ResourceColor, fillEnabled: boolean): ResourceColor {
  if (fillEnabled) {
    return fillColor;
  }
  if (entry.fillOpacity <= 0) {
    return '#00000000';
  }
  return resolvePathPaint(entry.fillPaint, currentColor);
}

@Builder
function createSlockLucideIconView(view: KuiklyRenderBaseView) {
  SlockLucideIcon({ renderView: view as SlockLucideIconView });
}
