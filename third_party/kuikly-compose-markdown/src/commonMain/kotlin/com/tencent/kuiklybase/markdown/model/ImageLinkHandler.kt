package com.tencent.kuiklybase.markdown.model

interface ImageLinkHandler {

    val stored: MutableList<ImageData>

    fun store(image: ImageData)

    fun preview(image: ImageData)
}

open class ImageLinkHandlerImpl : ImageLinkHandler {

    override val stored: MutableList<ImageData> = mutableListOf()

    override fun store(image: ImageData) {
        stored.add(image)
    }

    override fun preview(image: ImageData) {
    }
}