@Entry
@Component
struct ActivityUnreadCard {
  @LocalStorageProp('title') title: string = 'Activity';
  @LocalStorageProp('countText') countText: string = '--';
  @LocalStorageProp('primaryLabel') primaryLabel: string = 'Unread';
  @LocalStorageProp('secondaryLabel') secondaryLabel: string = 'Open Raft to sync';
  @LocalStorageProp('accentLabel') accentLabel: string = 'RAFT';
  @LocalStorageProp('stateLabel') stateLabel: string = 'Needs app session';
  @LocalStorageProp('footerLabel') footerLabel: string = 'Tap to open';
  @LocalStorageProp('updatedAtLabel') updatedAtLabel: string = '--:--';

  build() {
    FormLink({ action: 'router', abilityName: 'EntryAbility' }) {
    Stack() {
      Column()
        .width('100%')
        .height('100%')
        .backgroundColor('#0A0A0A')
        .translate({ x: 5, y: 5 })
      Column() {
        SlockWidgetHeader({ title: this.title, accentLabel: this.accentLabel })
        Row() {
          Text(this.countText)
            .fontSize(52)
            .fontWeight(FontWeight.Bold)
            .fontColor('#0A0A0A')
            .fontFamily('space_grotesk_bold')
            .maxLines(1)
            .layoutWeight(1)
          Text(this.primaryLabel.toUpperCase())
            .fontSize(14)
            .fontWeight(FontWeight.Bold)
            .fontColor('#0A0A0A')
            .fontFamily('space_grotesk_bold')
            .padding({ left: 8, right: 8, top: 5, bottom: 5 })
            .border({ width: 2, color: '#0A0A0A' })
            .backgroundColor('#FFFFFF')
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .margin({ top: 10 })
        Text(this.stateLabel)
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor('#0A0A0A')
          .fontFamily('space_grotesk_bold')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 8 })
        Text(this.secondaryLabel)
          .fontSize(14)
          .fontColor('#333333')
          .fontFamily('space_grotesk_regular')
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ top: 4 })
        Blank()
        SlockWidgetFooter({ footerLabel: this.footerLabel, updatedAtLabel: this.updatedAtLabel })
      }
      .width('100%')
      .height('100%')
      .padding(14)
      .backgroundColor(this.accentLabel === 'CLEAR' ? '#89F0D1' : '#FFD440')
      .border({ width: 2, color: '#0A0A0A' })
    }
    .width('100%')
    .height('100%')
    .padding({ right: 5, bottom: 5 })
  }
}

@Component
struct SlockWidgetHeader {
  title: string = 'Raft';
  accentLabel: string = 'RAFT';

  build() {
    Row() {
      Text(this.title.toUpperCase())
        .fontSize(15)
        .fontWeight(FontWeight.Bold)
        .fontColor('#0A0A0A')
        .fontFamily('space_grotesk_bold')
        .layoutWeight(1)
      Text(this.accentLabel)
        .fontSize(12)
        .fontWeight(FontWeight.Bold)
        .fontColor('#0A0A0A')
        .fontFamily('space_grotesk_bold')
        .padding({ left: 7, right: 7, top: 4, bottom: 4 })
        .border({ width: 2, color: '#0A0A0A' })
        .backgroundColor('#FFFFFF')
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }
}

@Component
struct SlockWidgetFooter {
  footerLabel: string = 'Updated';
  updatedAtLabel: string = '--:--';

  build() {
    Row() {
      Text(this.footerLabel.toUpperCase())
        .fontSize(12)
        .fontWeight(FontWeight.Bold)
        .fontColor('#0A0A0A')
        .fontFamily('space_grotesk_bold')
      Blank()
      Text(this.updatedAtLabel)
        .fontSize(12)
        .fontWeight(FontWeight.Bold)
        .fontColor('#0A0A0A')
        .fontFamily('space_grotesk_bold')
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
  }
}
