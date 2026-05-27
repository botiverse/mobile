import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { fileUri } from '@kit.CoreFileKit';
import { ComponentContent } from '@kit.ArkUI';

const kName = 'name';
const kColor = 'color';

@Observed
export class SlockLucideIconView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockLucideIconView';
  iconName: string = '';
  iconColor: ResourceColor = '#000000';

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
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  getIconResource(): ResourceStr | null {
    if (this.iconName.length === 0) {
      return null;
    }
    const path = getContext().resourceDir + '/lucide/' + this.iconName + '.svg';
    return fileUri.getUriFromPath(path);
  }
}

@Component
export struct SlockLucideIcon {
  @ObjectLink renderView: SlockLucideIconView;

  build() {
    Image(this.renderView.getIconResource())
      .width('100%')
      .height('100%')
      .objectFit(ImageFit.Contain)
      .interpolation(ImageInterpolation.High)
      .supportSvg2(true)
      .fillColor(this.renderView.iconColor);
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

@Builder
function createSlockLucideIconView(view: KuiklyRenderBaseView) {
  SlockLucideIcon({ renderView: view as SlockLucideIconView });
}
