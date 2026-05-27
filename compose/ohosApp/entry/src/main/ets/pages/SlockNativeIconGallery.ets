import { fileUri } from '@kit.CoreFileKit';
import {
  SlockLucidePathEntry,
  SlockLucidePathIconNames,
  slockLucidePathEntries,
} from '../kuikly/generated/SlockLucidePathIcons';

export const SLOCK_NATIVE_ICON_GALLERY_PAGE = 'native_icon_gallery';

@Component
export struct SlockNativeIconGallery {
  @Prop safeAreaTopPx: number = 0;

  build() {
    List({ space: 14 }) {
      ListItem() {
        Column({ space: 4 }) {
          Text('Native Lucide Icons')
            .fontSize(30)
            .fontColor('#000000')
            .fontWeight(FontWeight.Medium)
          Text(`${SlockLucidePathIconNames.length} icons · native SVG Image left · native PATH right`)
            .fontSize(18)
            .fontColor('#737373')
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
      }

      ForEach(SlockLucidePathIconNames, (name: string) => {
        ListItem() {
          NativeIconCompareRow({ name })
        }
      }, (name: string) => name)
    }
    .width('100%')
    .height('100%')
    .padding({
      left: 16,
      right: 16,
      top: px2vp(this.safeAreaTopPx) + 16,
      bottom: 16,
    })
    .backgroundColor('#FFF7EA')
  }
}

@Component
struct NativeIconCompareRow {
  @Prop name: string = '';

  build() {
    Row({ space: 10 }) {
      Text(this.name)
        .width('32%')
        .fontSize(14)
        .fontColor('#737373')
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })

      NativeIconTile({ label: 'SVG' }) {
        Image(iconResource(this.name))
          .width('100%')
          .height('100%')
          .objectFit(ImageFit.Contain)
          .interpolation(ImageInterpolation.High)
          .supportSvg2(true)
      }

      NativeIconTile({ label: 'PATH' }) {
        NativePathIcon({ name: this.name })
      }
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }
}

@Component
struct NativeIconTile {
  @Prop label: string = '';
  @BuilderParam content: () => void;

  build() {
    Column({ space: 6 }) {
      Stack() {
        this.content()
      }
      .width(56)
      .height(56)
      .padding(14)
      .backgroundColor('#FFFFFF')
      .border({ width: 2, color: '#000000' })

      Text(this.label)
        .fontSize(14)
        .fontColor('#737373')
    }
    .layoutWeight(1)
    .alignItems(HorizontalAlign.Center)
  }
}

@Component
struct NativePathIcon {
  @Prop name: string = '';

  build() {
    Stack() {
      ForEach(slockLucidePathEntries(this.name), (entry: SlockLucidePathEntry) => {
        Path({
          width: `${entry.viewportWidth}px`,
          height: `${entry.viewportHeight}px`,
          commands: entry.commands,
        })
          .width(`${entry.viewportWidth}px`)
          .height(`${entry.viewportHeight}px`)
          .fill(pathPaint('#000000', entry.fillOpacity))
          .stroke(pathPaint('#000000', entry.strokeOpacity))
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

function iconResource(name: string): ResourceStr {
  return fileUri.getUriFromPath(getContext().resourceDir + '/lucide/' + name + '.svg');
}

function pathPaint(color: ResourceColor, opacity: number): ResourceColor {
  return opacity > 0 ? color : '#00000000';
}
