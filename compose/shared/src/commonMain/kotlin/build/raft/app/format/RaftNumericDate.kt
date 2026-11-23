package build.raft.app.format

import build.raft.app.model.twoDigits

/**
 * A day written in digits the way this UI language writes it: what Intl prints for
 * `{ month: "2-digit", day: "2-digit" }` and, [withYear], for
 * `{ year: "numeric", month: "2-digit", day: "2-digit" }` (CLDR's `MMdd` and `yMMdd`). The web's
 * `formatMessageTime` puts this before the clock of a message from an earlier day
 * (`packages/web/src/utils/timeFormatting.ts`, botiverse/slock d7f7f7a2; read off Chrome 157):
 *
 * - English: `06/22`, `06/22/2025`;
 * - Chinese and Japanese: `06/22`, `2025/06/22`;
 * - Korean: `06. 22.`, `2025. 06. 22.`;
 * - Spanish: `22/6` (CLDR's `MMdd` there keeps no leading zeros), `22/06/2025`;
 * - Brazilian Portuguese: `22/06`, `22/06/2025`;
 * - Russian: `22.06`, `22.06.2025`.
 */
internal fun RaftFormatLocale.numericDate(year: Int, month: Int, day: Int, withYear: Boolean): String {
    val mm = month.twoDigits()
    val dd = day.twoDigits()
    return when (this) {
        RaftFormatLocale.English -> if (withYear) "$mm/$dd/$year" else "$mm/$dd"
        RaftFormatLocale.SimplifiedChinese,
        RaftFormatLocale.TraditionalChinese,
        RaftFormatLocale.Japanese -> if (withYear) "$year/$mm/$dd" else "$mm/$dd"
        RaftFormatLocale.Korean -> if (withYear) "$year. $mm. $dd." else "$mm. $dd."
        RaftFormatLocale.Spanish -> if (withYear) "$dd/$mm/$year" else "$day/$month"
        RaftFormatLocale.BrazilianPortuguese -> if (withYear) "$dd/$mm/$year" else "$dd/$mm"
        RaftFormatLocale.Russian -> if (withYear) "$dd.$mm.$year" else "$dd.$mm"
    }
}
