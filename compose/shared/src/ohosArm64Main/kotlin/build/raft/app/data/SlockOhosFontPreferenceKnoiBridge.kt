package build.raft.app.data

import com.tencent.tmm.knoi.annotation.KNExport

/** Called synchronously by EntryAbility after MMKV/KNOI setup, before any shared page exists. */
@KNExport
fun slockOhosRestoreStoredFontPreferenceOnColdStart(): String {
    slockRestoreStoredFontPreferenceOnColdStart()
    return slockReadStoredFontPreferenceForDiagnostics()
}
