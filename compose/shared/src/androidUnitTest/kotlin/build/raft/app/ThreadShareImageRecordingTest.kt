package build.raft.app

import build.raft.app.thread.threadShareImageGpuFallbackFits
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The share image is recorded on the main thread (an `android.graphics.Picture`) and rasterised
 * off it ([ThreadShareImageRecording.rasterise] on Dispatchers.Default), band by band so a new
 * width stops a stale one; the preview gets a downscaled copy.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThreadShareImageRecordingTest {
    @Test
    fun theGpuFallbackStaysWithinTheTextureLimit() {
        kotlin.test.assertTrue(threadShareImageGpuFallbackFits(1170, 4096))
        kotlin.test.assertTrue(threadShareImageGpuFallbackFits(4096, 4096))
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(1170, 4097), "a tall image would come back blank from the GPU")
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(4097, 100))
        kotlin.test.assertFalse(threadShareImageGpuFallbackFits(0, 100))
    }
}
