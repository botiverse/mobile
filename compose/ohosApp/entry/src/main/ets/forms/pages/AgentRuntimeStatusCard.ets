@Entry
@Component
struct AgentRuntimeStatusCard {
  @LocalStorageProp('title') title: string = 'Agents';
  @LocalStorageProp('countText') countText: string = '--';
  @LocalStorageProp('primaryLabel') primaryLabel: string = 'Running';
  @LocalStorageProp('secondaryLabel') secondaryLabel: string = 'Open Raft to sync';
  @LocalStorageProp('accentLabel') accentLabel: string = 'LIVE';
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

          Row() {
            Circle()
              .width(14)
              .height(14)
              .borderRadius(7)
              .backgroundColor(this.accentLabel === 'LIVE' ? '#00D084' : '#9CA3AF')
              .border({ width: 2, color: '#0A0A0A' })
              .margin({ right: 8 })
            Text(this.stateLabel)
              .fontSize(18)
              .fontWeight(FontWeight.Bold)
              .fontColor('#0A0A0A')
              .fontFamily('space_grotesk_bold')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 18 })

          Row() {
            Text(this.countText)
              .fontSize(48)
              .fontWeight(FontWeight.Bold)
              .fontColor('#0A0A0A')
              .fontFamily('space_grotesk_bold')
              .maxLines(1)
            Text(this.primaryLabel.toUpperCase())
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor('#0A0A0A')
              .fontFamily('space_grotesk_bold')
              .margin({ left: 10 })
              .maxLines(2)
              .layoutWeight(1)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 6 })

          Text(this.secondaryLabel)
            .fontSize(14)
            .fontColor('#333333')
            .fontFamily('space_grotesk_regular')
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
          Blank()
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
        .width('100%')
        .height('100%')
        .padding(14)
        .backgroundColor(this.accentLabel === 'LIVE' ? '#89F0D1' : '#FFD440')
        .border({ width: 2, color: '#0A0A0A' })
      }
      .width('100%')
      .height('100%')
      .padding({ right: 5, bottom: 5 })
    }
  }
}
