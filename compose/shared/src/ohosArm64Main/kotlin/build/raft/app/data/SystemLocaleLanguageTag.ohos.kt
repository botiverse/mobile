package build.raft.app.data

import com.tencent.tmm.knoi.annotation.ServiceConsumer
import com.tencent.tmm.knoi.service.KNOI

private const val SlockOhosSystemLocaleServiceName = "slockOhosSystemLocale"

@ServiceConsumer(name = SlockOhosSystemLocaleServiceName)
interface SlockOhosSystemLocaleService {
    fun currentLanguageTag(): String
}

internal actual fun currentSystemLanguageTag(): String? =
    runCatching {
        KNOI.get<SlockOhosSystemLocaleService>(SlockOhosSystemLocaleServiceName)
            .currentLanguageTag()
            .takeIf { it.isNotBlank() }
    }.getOrNull()
