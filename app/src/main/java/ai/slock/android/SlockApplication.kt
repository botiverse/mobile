package ai.slock.android

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

class SlockApplication : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader =
            ImageLoader.Builder(context)
                    .memoryCache {
                        MemoryCache.Builder()
                                .maxSizePercent(context, SlockImageMemoryCachePercent)
                                .build()
                    }
                    .diskCache {
                        DiskCache.Builder()
                                .directory(context.cacheDir.resolve(SlockImageDiskCacheDir))
                                .maxSizeBytes(SlockImageDiskCacheBytes)
                                .build()
                    }
                    .components {
                        add(OkHttpNetworkFetcherFactory())
                    }
                    .build()
}

private const val SlockImageMemoryCachePercent = 0.20
private const val SlockImageDiskCacheDir = "image_cache"
private const val SlockImageDiskCacheBytes = 128L * 1024L * 1024L
