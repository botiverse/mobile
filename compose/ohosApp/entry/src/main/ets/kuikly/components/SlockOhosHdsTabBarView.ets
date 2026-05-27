import { KRAny, KuiklyRenderBaseView, KuiklyRenderCallback } from '@kuikly-open/render';
import { ComponentContent } from '@kit.ArkUI';
import { DividerMode, HdsTabs, HdsTabsController } from '@kit.UIDesignKit';
import { hdsMaterial } from '@hms.hds.hdsMaterial';
import { SlockLucidePathEntry, slockLucidePathEntries } from '../generated/SlockLucidePathIcons';

const kKeysText = 'keysText';
const kLabelsText = 'labelsText';
const kIconsText = 'iconsText';
const kEnabledText = 'enabledText';
const kSelectedKey = 'selectedKey';
const kBottomSafeArea = 'bottomSafeArea';
const kSelect = 'select';
const kSeparator = '\u001F';

const kDockHeight = 60;
const kDockSideMargin = 18;
const kDockBottomMargin = 16;
const kDockHostHeight = 96;
const kAnimationDuration = 240;
const kItemHeight = 44;
const kIconSize = 18;
const kGradientMaskColor = '#66F1F3F5';
const kGradientMaskHeight = 92;

interface SlockHdsTabItem {
  key: string;
  label: string;
  icon: string;
  enabled: boolean;
}

@Observed
export class SlockOhosHdsTabBarView extends KuiklyRenderBaseView {
  static readonly VIEW_NAME = 'SlockOhosHdsTabBarView';
  controller: HdsTabsController = new HdsTabsController();
  keysText: string = '';
  labelsText: string = '';
  iconsText: string = '';
  enabledText: string = '';
  selectedKey: string = '';
  bottomSafeArea: number = 0;
  selectCallback: KuiklyRenderCallback | null = null;

  createArkUIView(): ComponentContent<KuiklyRenderBaseView> {
    const uiContext = this.getUIContext() as UIContext;
    return new ComponentContent<KuiklyRenderBaseView>(
      uiContext,
      wrapBuilder<[KuiklyRenderBaseView]>(createSlockOhosHdsTabBarView),
      this
    );
  }

  setProp(propKey: string, propValue: KRAny | KuiklyRenderCallback): boolean {
    switch (propKey) {
      case kKeysText:
        this.keysText = normalizeText(propValue);
        return true;
      case kLabelsText:
        this.labelsText = normalizeText(propValue);
        return true;
      case kIconsText:
        this.iconsText = normalizeText(propValue);
        return true;
      case kEnabledText:
        this.enabledText = normalizeText(propValue);
        return true;
      case kSelectedKey:
        this.selectedKey = normalizeText(propValue);
        this.controller.changeIndex(this.selectedIndex());
        return true;
      case kBottomSafeArea:
        this.bottomSafeArea = normalizeNumber(propValue);
        return true;
      case kSelect:
        this.selectCallback = propValue as KuiklyRenderCallback;
        return true;
      default:
        return super.setProp(propKey, propValue);
    }
  }

  call(method: string, params: KRAny, callback: KuiklyRenderCallback | null): KRAny {
    return null;
  }

  items(): SlockHdsTabItem[] {
    const keys = splitText(this.keysText);
    const labels = splitText(this.labelsText);
    const icons = splitText(this.iconsText);
    const enabled = splitText(this.enabledText);
    return keys.map((key: string, index: number): SlockHdsTabItem => {
      return {
        key: key,
        label: labels[index] ?? key,
        icon: icons[index] ?? '',
        enabled: enabled[index] !== '0',
      };
    }).filter((item: SlockHdsTabItem) => item.key.length > 0);
  }

  selectedIndex(): number {
    const items = this.items();
    const index = items.findIndex((item: SlockHdsTabItem) => item.key === this.selectedKey);
    return index >= 0 ? index : 0;
  }

  selectIndex(index: number) {
    const items = this.items();
    if (index < 0 || index >= items.length) {
      return;
    }
    const item = items[index];
    if (!item.enabled) {
      this.controller.changeIndex(this.selectedIndex());
      return;
    }
    this.selectedKey = item.key;
    this.controller.changeIndex(index);
    this.selectCallback?.({
      key: item.key,
      index: index,
    });
  }

}

@Component
export struct SlockOhosHdsTabBar {
  @ObjectLink renderView: SlockOhosHdsTabBarView;

  private itemColor(item: SlockHdsTabItem, index: number): ResourceColor {
    if (!item.enabled) {
      return '#66000000';
    }
    return this.renderView.selectedIndex() === index ? '#FF000000' : '#FF737373';
  }

  private itemFontWeight(index: number): FontWeight {
    return this.renderView.selectedIndex() === index ? FontWeight.Bold : FontWeight.Medium;
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      HdsTabs({ index: this.renderView.selectedIndex(), controller: this.renderView.controller }) {
        ForEach(this.renderView.items(), (item: SlockHdsTabItem, index: number) => {
          TabContent() {
            Column() {
              Blank()
                .width('100%')
                .height(1)
            }
          }
          .tabBar(this.tabBarItem(item, index))
        }, (item: SlockHdsTabItem) => item.key)
      }
      .vertical(false)
      .barPosition(BarPosition.End)
      .barMode(BarMode.Fixed)
      .scrollable(false)
      .animationDuration(kAnimationDuration)
      .barHeight(kDockHeight)
      .barOverlap(true)
      .barFloatingStyle({
        barSideMargin: kDockSideMargin,
        barBottomMargin: kDockBottomMargin,
        adaptToHandedness: true,
        gradientMask: {
          maskColor: kGradientMaskColor,
          maskHeight: kGradientMaskHeight,
        },
        thermoCtrl: true,
        systemMaterialEffect: {
          materialType: hdsMaterial.MaterialType.ADAPTIVE,
          materialLevel: hdsMaterial.MaterialLevel.ADAPTIVE,
        },
      })
      .onChange((index: number) => {
        this.renderView.selectIndex(index);
      })
      .onTabBarClick((index: number) => {
        this.renderView.selectIndex(index);
      })
      .divider({ mode: DividerMode.NONE })
      .width('100%')
      .height(`${kDockHostHeight}vp`)
      .backgroundColor('#00FFFFFF')
    }
    .width('100%')
    .height(`${kDockHostHeight}vp`)
  }

  @Builder
  tabBarItem(item: SlockHdsTabItem, index: number) {
    Column() {
      Stack({ alignContent: Alignment.Center }) {
        ForEach(slockLucidePathEntries(item.icon), (entry: SlockLucidePathEntry) => {
          Path({
            width: `${entry.viewportWidth}px`,
            height: `${entry.viewportHeight}px`,
            commands: entry.commands,
          })
            .width(`${entry.viewportWidth}px`)
            .height(`${entry.viewportHeight}px`)
            .fill(pathPaint(this.itemColor(item, index), entry.fillOpacity))
            .stroke(pathPaint(this.itemColor(item, index), entry.strokeOpacity))
            .strokeWidth(entry.strokeOpacity > 0 ? `${entry.strokeWidth}px` : 0)
            .strokeLineCap(entry.strokeLineCap)
            .strokeLineJoin(entry.strokeLineJoin)
            .scale({ x: tabIconScale(entry), y: tabIconScale(entry) })
            .antiAlias(true)
        }, (entry: SlockLucidePathEntry) => `${entry.commands}:${entry.strokeWidth}`)
      }
      .width(kIconSize)
      .height(kIconSize)
      Text(item.label.toUpperCase())
        .fontSize(9)
        .fontWeight(this.itemFontWeight(index))
        .fontColor(this.itemColor(item, index))
        .lineHeight(12)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .height(kItemHeight)
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .borderRadius(16)
    .backgroundColor('#00FFFFFF')
  }
}

function splitText(value: string): string[] {
  if (value.length === 0) {
    return [];
  }
  return value.split(kSeparator);
}

function normalizeText(value: KRAny | KuiklyRenderCallback): string {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value);
}

function normalizeNumber(value: KRAny | KuiklyRenderCallback): number {
  const numberValue = Number(value);
  return Number.isFinite(numberValue) && numberValue > 0 ? numberValue : 0;
}

function pathPaint(color: ResourceColor, opacity: number): ResourceColor {
  return opacity > 0 ? color : '#00000000';
}

function tabIconScale(entry: SlockLucidePathEntry): number {
  const maxViewport = Math.max(entry.viewportWidth, entry.viewportHeight);
  return maxViewport > 0 ? vp2px(kIconSize / maxViewport) : vp2px(1);
}

@Builder
function createSlockOhosHdsTabBarView(view: KuiklyRenderBaseView) {
  SlockOhosHdsTabBar({ renderView: view as SlockOhosHdsTabBarView });
}
