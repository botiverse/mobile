package ai.slock.android.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlin.io.path.extension
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityResultBoundaryTest {
    @Test
    fun mainActivityRootDoesNotCreateComposableActivityResultLaunchers() {
        val mainActivity = Path.of("src/main/java/ai/slock/android/MainActivity.kt")
        val source = String(Files.readAllBytes(mainActivity), StandardCharsets.UTF_8)

        assertEquals(
            "MainActivity root app composition must use AppActivityActions/registerForActivityResult instead of rememberLauncherForActivityResult.",
            false,
            source.contains("rememberLauncherForActivityResult")
        )
    }

    @Test
    fun localeProviderPreservesActivityResultRegistryOwner() {
        val mainActivity = Path.of("src/main/java/ai/slock/android/MainActivity.kt")
        val source = String(Files.readAllBytes(mainActivity), StandardCharsets.UTF_8)

        assertEquals(
            "SlockLocaleProvider must keep LocalActivityResultRegistryOwner when replacing LocalContext.",
            true,
            source.contains("LocalActivityResultRegistryOwner provides activityResultRegistryOwner")
        )
    }
}
