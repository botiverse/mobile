@Entry
@Component
struct AgentRuntimeStatusCard {
  @LocalStorageProp('title') title: string = 'Live Agents';
  @LocalStorageProp('countText') countText: string = '--';
  @LocalStorageProp('primaryLabel') primaryLabel: string = 'Running';
  @LocalStorageProp('secondaryLabel') secondaryLabel: string = 'Open Raft to sync';
  @LocalStorageProp('accentLabel') accentLabel: string = 'LIVE';
  @LocalStorageProp('stateLabel') stateLabel: string = 'Needs app session';
  @LocalStorageProp('footerLabel') footerLabel: string = 'Tap to open';
  @LocalStorageProp('updatedAtLabel') updatedAtLabel: string = '--:--';

  build() {
    FormLink({ action: 'router', abilityName: 'EntryAbility' }) {
        Column() {
          Row() {
            Text(this.title.toUpperCase())
              .fontSize(12)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
              .fontFamily('space_grotesk_bold')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
              .margin({ right: 6 })
            Text(this.accentLabel)
              .fontSize(10)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
              .fontFamily('space_grotesk_bold')
              .padding({ left: 6, right: 6, top: 3, bottom: 3 })
              .borderRadius(8)
              .backgroundColor('#FFFFFF')
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)

          Row() {
            Circle()
              .width(10)
              .height(10)
              .borderRadius(5)
              .backgroundColor(this.accentLabel === 'LIVE' ? '#00D084' : '#9CA3AF')
              .margin({ right: 6 })
            Text(this.stateLabel)
              .fontSize(14)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
              .fontFamily('space_grotesk_bold')
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 10 })

          Row() {
            Text(this.countText)
              .fontSize(38)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
              .fontFamily('space_grotesk_bold')
              .maxLines(1)
            Text(this.primaryLabel.toUpperCase())
              .fontSize(12)
              .fontWeight(FontWeight.Bold)
              .fontColor('#111827')
              .fontFamily('space_grotesk_bold')
              .margin({ left: 8 })
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
          .margin({ top: 2 })

          Text(this.secondaryLabel)
            .fontSize(11)
            .fontColor('#4B5563')
            .fontFamily('space_grotesk_regular')
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
          Blank()
          Row() {
            Text(this.footerLabel.toUpperCase())
              .fontSize(10)
              .fontWeight(FontWeight.Bold)
              .fontColor('#6B7280')
              .fontFamily('space_grotesk_bold')
            Blank()
            Text(this.updatedAtLabel)
              .fontSize(10)
              .fontWeight(FontWeight.Bold)
              .fontColor('#6B7280')
              .fontFamily('space_grotesk_bold')
          }
          .width('100%')
          .alignItems(VerticalAlign.Center)
        }
        .width('100%')
        .height('100%')
        .padding(12)
        .backgroundColor(this.accentLabel === 'LIVE' ? '#ECFDF5' : '#F9FAFB')
        .borderRadius(18)
        .border({ width: 1, color: this.accentLabel === 'LIVE' ? '#A7F3D0' : '#E5E7EB' })
    }
  }
}
