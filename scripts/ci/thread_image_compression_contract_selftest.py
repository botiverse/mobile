"""Mutation fixtures for the composer image-compression source contract."""
from __future__ import annotations

import sys
from typing import Callable

from check_thread_image_compression_contract import Violation, ERROR_CODE, check_contract, _replace_in_region


def _good_fixture() -> dict[str, str]:
    """Minimal but realistic fixture covering every checker face."""

    bridge = '''
suspend fun threadAttachmentUpload(channelId: String, compressImages: Boolean = false) {
  put("compressImages", compressImages)
}
suspend fun compressThreadImage(
  fileRef: String, fileName: String, mimeType: String,
  requestId: String = nextBridgeThreadImageCompressionRequestId()
) = suspendCancellableCoroutine { continuation ->
  val methodArgs = JSONObject().apply {
    put("requestId", requestId); put("fileRef", fileRef)
    put("fileName", fileName); put("mimeType", mimeType)
  }
  val terminal = atomic(false)
  terminal.compareAndSet(false, true)
  invokeOnCancellation { callNativeMethod(CANCEL_COMPRESS_THREAD_IMAGE, methodArgs, null) }
}
const val COMPRESS_THREAD_IMAGE = "compressThreadImage"
const val CANCEL_COMPRESS_THREAD_IMAGE = "cancelCompressThreadImage"
    '''
    preview = '''
const val SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_ERROR_CODE = "attachment_image_compression_failed"
const val SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE = "composer_image_compressor"
class SlockFilePreviewComposerImageOperationGate {
  fun begin() = Unit; fun complete() = Unit; fun cancel() = Unit; fun retire() = Unit
}
data class SlockFilePreviewPayload(
  val composerImageCompressAfterEdit: Boolean = false,
  val composerImageEditCompleted: Boolean = false,
  val composerImageCompressed: Boolean = false
)
internal fun parseSlockFilePreviewCompressedImageResult(response: JSONObject?) {
  val filesJson = response?.optJSONArray("files")
  if (filesJson == null || filesJson.length() != 1) return SlockFilePreviewCompressedImageResult.Failure()
  val rawMimeType = filesJson.optJSONObject(0).optString("mimeType")
  val rawSource = filesJson.optJSONObject(0).optString("source")
  if (rawMimeType != "image/jpeg" || rawSource != SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE) return SlockFilePreviewCompressedImageResult.Failure()
  return SlockFilePreviewCompressedImageResult.Success()
}
fun hasPendingComposerImageFinalization() = true
fun withComposerImageFinalResult() = Unit
fun markComposerImageFinalized() = Unit
internal fun slockComposerImagePickerIsStrictImage(fileName: String, mimeType: String): Boolean {
  val mime = mimeType.substringBefore(';').trim().lowercase()
  val extension = fileName.substringAfterLast('.').lowercase()
  return mime.startsWith("image/") && extension in SlockFilePreviewImageExtensions
}
private val SlockFilePreviewImageExtensions = setOf("jpg")
private val SlockFilePreviewVideoExtensions = setOf("mp4", "flv")
    '''
    thread = '''
internal fun shouldCompressThreadAttachmentPickerImages(action: String, originalImage: Boolean): Boolean = false
internal fun slockComposerImagePickerFilesAreStrictImages(files: List<PlatformFile>): Boolean = true
val compressPickerImages = shouldCompressThreadAttachmentPickerImages("attachFile", currentDraftState.originalImage)
val response = bridge.threadAttachmentUpload(channelId = id, imageOnly = true,
  compressImages = compressPickerImages)
threadComposerUploadedImagePreviewPayload(files = galleryFiles, originalImage = currentDraftState.originalImage) ?: return
threadComposerUploadedImagePreviewPayload(files = listOf(file), originalImage = currentDraftState.originalImage) ?: return
internal fun threadComposerImageAnnotationPayload(originalImage: Boolean) {
  val finalPolicy = composerImageCompressAfterEdit = !originalImage
}
private fun KuiklyThreadAttachmentItem.toThreadAttachmentPreviewPayload() = Unit
slockComposerImagePickerIsStrictImage(name, mime)
resolveThreadPickedAttachmentComposerAction(action, count, allFilesAreImages = true)
ThreadPickedAttachmentComposerAction.OpenImageAnnotation
ThreadPickedAttachmentComposerAction.UploadToComposer
bridge.compressThreadImage(fileRef, fileName, mimeType)
suspend fun uploadComposerAttachmentFiles(files: List<PlatformFile>): Boolean {
  val ownedTemporarySources = setOf(
    "platform_picker", "platform_camera", "composer_image_editor",
    "composer_image_compressor"
  )
  val sourceAttachment = composerDraftViewModel.state.value.uploadedAttachments.firstOrNull()
  val uploaded = uploadResult.data.attachments.map { attachment ->
    SharedThreadComposerAttachment(
      id = attachment.id.value,
      fileRef = sourceAttachment?.takeIf { it.source in ownedTemporarySources }?.fileRef,
      source = sourceAttachment?.source
    )
  }
  return uploaded.isNotEmpty()
}
fun performComposerSend() = Unit
internal fun threadPendingSendOwnedTemporaryFileRefs(
  attachments: List<SharedThreadComposerAttachment>, attachmentIds: Collection<String>
): List<String> {
  val acceptedIds = attachmentIds.toSet()
  val ownedTemporarySources = setOf(
    "platform_picker", "platform_camera", "composer_image_editor",
    "composer_image_compressor"
  )
  return attachments.asSequence()
    .filter { it.id in acceptedIds }
    .filter { attachment ->
      val mime = attachment.mimeType.orEmpty().substringBefore(';').trim().lowercase()
      val extension = attachment.fileName.substringAfterLast('.').lowercase()
      !mime.startsWith("video/") && extension !in SlockFilePreviewVideoExtensions
    }
    .filter { it.source in ownedTemporarySources }
    .mapNotNull { it.fileRef?.trim()?.takeIf(String::isNotBlank) }
}
private val SlockFilePreviewVideoExtensions = setOf("mp4")
internal fun threadComposerMessageBodyForSend(draftText: String, attachmentCount: Int): String = draftText
    '''
    android = '''
private val THREAD_ATTACHMENT_VIDEO_EXTENSIONS = setOf("mp4", "flv")
const val THREAD_IMAGE_COMPRESSION_ERROR = "attachment_image_compression_failed"
private fun isImageAttachment(uri: Uri, fileName: String, mimeType: String, imageOnly: Boolean): Boolean {
  val videoPathCandidates = sequenceOf(fileName, uri.path.orEmpty(), uri.lastPathSegment.orEmpty())
  if (videoPathCandidates.any { candidate ->
      val extension = candidate.substringAfterLast('.').lowercase(Locale.US)
      extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS
  }) return false
  val normalizedMimeType = mimeType.substringBefore(';').trim().lowercase(Locale.US)
  if (normalizedMimeType.startsWith("video/")) return false
  if (normalizedMimeType.startsWith("image/")) return true
  val extension = fileName.substringAfterLast('.').lowercase(Locale.US)
  return imageOnly && normalizedMimeType == "application/octet-stream" && extension.isNotBlank()
}
private fun isThreadImageCompressionInput(fileName: String, mimeType: String, sourceFile: File): Boolean {
  val normalizedMime = mimeType
  if (!normalizedMime.startsWith("image/") || !sourceFile.isFile) return false
  val extension = fileName.substringAfterLast('.')
  if (extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false
  val sourceExtension = sourceFile.extension.lowercase()
  if (sourceExtension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false
  return true
}
private fun compressedPickedImageFileName(fileName: String): String = "image.jpg"
"compressThreadImage" -> { compressThreadImage(params, callback) }
"cancelCompressThreadImage" -> { cancelCompressThreadImage(params) }
private fun compressThreadImage(params: String?, callback: KuiklyRenderCallback?) {
  val requestId = request.optString("requestId"); val fileRef = request.optString("fileRef")
  val fileName = request.optString("fileName"); val mimeType = request.optString("mimeType")
  val outputFile = File(cacheDir, "composer-${UUID.randomUUID()}.jpg")
  compressThreadImageFile(sourceFile, outputFile)
  val file = JSONObject().put("source", "composer_image_compressor").put("mimeType", "image/jpeg")
  resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
  finishThreadImageCompression(operation, file)
  outputFile.delete()
}
private fun cancelCompressThreadImage(params: String?) = Unit
private val threadImageCompressionOperations = ConcurrentHashMap<String, ThreadImageCompressionOperation>()
private data class ThreadImageCompressionOperation(val terminal: AtomicBoolean)
if (!successful || operation.isCancelled()) outputFile.delete()
    '''
    ios = '''
static BOOL SlockThreadImageCompressionInputIsImage(NSString *name, NSString *mime, NSURL *url) {
  if (![mime hasPrefix:@"image/"]) return NO;
  NSSet *videoExtensions = [NSSet setWithArray:@[@"mp4", @"flv"]];
  NSString *extension = name.pathExtension.lowercaseString;
  return ![videoExtensions containsObject:extension];
}
static BOOL SlockAttachmentIsImage(NSString *fileName, NSString *mimeType, NSURL *sourceURL) {
  NSString *normalizedMimeType = SlockBridgeString(mimeType).lowercaseString;
  normalizedMimeType = [[normalizedMimeType componentsSeparatedByString:@";"] firstObject] ?: @"";
  NSString *nameExtension = SlockBridgeString(fileName).pathExtension.lowercaseString;
  NSString *sourceExtension = sourceURL.pathExtension.lowercaseString;
  NSSet<NSString *> *videoExtensions = [NSSet setWithArray:@[@"mp4", @"flv"]];
  if ([videoExtensions containsObject:nameExtension] ||
      [videoExtensions containsObject:sourceExtension] ||
      [normalizedMimeType hasPrefix:@"video/"]) return NO;
  if ([normalizedMimeType hasPrefix:@"image/"]) return YES;
  return NO;
}
static NSError *SlockThreadAttachmentImageCompressionError(NSString *message) { return nil; }
static NSString *SlockCompressedAttachmentFileName(NSString *name) { return name; }
@interface FixtureCompressionOperation : NSObject
@property(nonatomic, strong) NSRecursiveLock *outputLock;
@property(nonatomic, strong) NSURL *outputURL;
- (void)removeOutputs;
@end
@implementation FixtureCompressionOperation
- (void)removeOutputs {
  [self.outputLock lock];
  [NSFileManager.defaultManager removeItemAtURL:self.outputURL error:nil];
  [self.outputLock unlock];
}
@end
- (void)compressThreadImage:(NSDictionary *)args {
  NSDictionary *params = args; NSString *requestId = params[@"requestId"];
  NSString *fileRef = params[@"fileRef"]; NSString *fileName = params[@"fileName"];
  NSString *mimeType = params[@"mimeType"];
  FixtureCompressionOperation *operation = [FixtureCompressionOperation new];
  NSURL *outputURL = [NSURL fileURLWithPath:[NSUUID.UUID.UUIDString stringByAppendingPathExtension:@"jpg"]];
  [operation.outputLock lock];
  SlockWriteCompressedPickedImage([NSURL URLWithString:fileRef], outputURL, nil);
  [operation.outputLock unlock];
  NSDictionary *file = @{@"source": @"composer_image_compressor", @"mimeType": @"image/jpeg"};
  [NSFileManager.defaultManager removeItemAtURL:outputURL error:nil];
}
- (void)cancelCompressThreadImage:(NSDictionary *)args { }
if (!encoded) { [NSFileManager.defaultManager removeItemAtURL:outputURL error:nil]; }
attachment_image_compression_failed
    '''
    ohos = '''
const S = "composer_image_compressor";
class KRBridgeModule {
  static readonly COMPRESS_THREAD_IMAGE = "compressThreadImage";
  static readonly CANCEL_COMPRESS_THREAD_IMAGE = "cancelCompressThreadImage";
  call(method: string) { switch (method) {
    case KRBridgeModule.COMPRESS_THREAD_IMAGE: this.compressThreadImage({}, cb); break;
    case KRBridgeModule.CANCEL_COMPRESS_THREAD_IMAGE: this.cancelCompressThreadImage({}); break;
  }}
  private isOhosImageAttachment(uri: string, mimeType: string, imageOnly: boolean) {
    if (mimeType.startsWith('video/')) return false;
    const lowerUri = uri.toLowerCase().split('?')[0];
    if (/\\.(mp4|flv)$/.test(lowerUri)) return false;
    if (mimeType.startsWith('image/')) return true;
    if (/\\.(avif|bmp|gif|heic|heif|jpe?g|png|webp|tiff?)$/.test(uri)) return true;
    return false;
  }
  private async cacheCompressedPickedThreadImage() { }
  private async isThreadImageCompressionInput(fileName: string, mimeType: string, sourcePath: string) {
    const normalizedMimeType = mimeType.split(';')[0].trim().toLowerCase();
    if (!normalizedMimeType.startsWith('image/')) return false;
    const extension = fileName.split('.').pop()?.toLowerCase() ?? '';
    if (['mp4', 'flv'].includes(extension)) return false;
    const normalizedSourcePath = sourcePath.toLowerCase().split('?')[0].split('#')[0];
    if (/\\.(mp4|flv)$/.test(normalizedSourcePath)) return false;
    const sourceStat = await fs.stat(sourcePath);
    return sourceStat.isFile() && sourceStat.size > 0;
  }
  private threadImageCompressionOutputName(fileName: string) { return fileName; }
  private async compressThreadImage(params: KRAny, callback: KuiklyRenderCallback) {
    const requestId = params["requestId"]; const fileRef = params["fileRef"];
    const fileName = params["fileName"]; const mimeType = params["mimeType"];
    const targetPath = `${UUID.randomUUID()}.jpg`;
    await this.cacheCompressedPickedThreadImage();
    await packer.packToFile(pixelMap, fd, { format: 'image/jpeg' });
    callback({ source: "composer_image_compressor", mimeType: "image/jpeg" });
    await fs.unlink(targetPath);
  }
  private cancelCompressThreadImage(params: KRAny) { }
  private async cacheCompressedPickedThreadImage() {
    await packer.packToFile(pixelMap, fd, { format: 'image/jpeg' });
    await fs.unlink(targetPath);
  }
  attachment_image_compression_failed
}
    '''
    return {"bridge": bridge, "preview": preview, "thread": thread, "android": android, "ios": ios, "ohos": ohos,
            "uploaded_preview": "threadComposerImageAnnotationPayload(threadInstanceKey, actionId, files, originalImage)"}


def self_test() -> int:
    good = _good_fixture()
    try:
        check_contract(good)
    except Violation as error:
        print(f"self-test FAIL: good fixture rejected: {error}", file=sys.stderr)
        return 1

    mutations: list[tuple[str, Callable[[dict[str, str]], None]]] = [
        ("uploaded-gallery policy", lambda s: s.__setitem__("thread", s["thread"].replace(
            "files = galleryFiles, originalImage = currentDraftState.originalImage",
            "files = galleryFiles, originalImage = false", 1))),
        ("uploaded-single policy", lambda s: s.__setitem__("thread", s["thread"].replace(
            "files = listOf(file), originalImage = currentDraftState.originalImage",
            "files = listOf(file), originalImage = false", 1))),
        ("uploaded builder policy", lambda s: s.__setitem__("uploaded_preview", s["uploaded_preview"].replace(
            "files, originalImage)", "files, false)", 1))),
        ("initial builder policy", lambda s: s.__setitem__("thread", s["thread"].replace(
            "composerImageCompressAfterEdit = !originalImage", "composerImageCompressAfterEdit = true", 1))),
        (
            "shared bridge request field",
            lambda s: s.__setitem__("bridge", s["bridge"].replace('put("mimeType", mimeType)', "", 1)),
        ),
        (
            "picker eager compression",
            lambda s: s.__setitem__(
                "thread",
                s["thread"].replace(
                    "compressImages = compressPickerImages",
                    "compressImages = true",
                    1,
                ),
            ),
        ),
        (
            "file-picker compression reintroduced",
            lambda s: s.__setitem__(
                "thread",
                s["thread"].replace(
                    "internal fun shouldCompressThreadAttachmentPickerImages(action: String, originalImage: Boolean): Boolean = false",
                    'internal fun shouldCompressThreadAttachmentPickerImages(action: String, originalImage: Boolean): Boolean = action == "attachFile" && !originalImage',
                    1,
                ),
            ),
        ),
        (
            "file-picker false satisfied by parameter default",
            lambda s: s.__setitem__(
                "thread",
                s["thread"].replace(
                    "internal fun shouldCompressThreadAttachmentPickerImages(action: String, originalImage: Boolean): Boolean = false",
                    'internal fun shouldCompressThreadAttachmentPickerImages(action: String, originalImage: Boolean = false): Boolean = action == "attachFile"',
                    1,
                ),
            ),
        ),
        (
            "mixed-picker policy snapshot",
            lambda s: s.__setitem__(
                "thread",
                s["thread"].replace(
                    "val compressPickerImages =",
                    "val missingPolicy =",
                    1,
                ),
            ),
        ),
        (
            "Android dispatch",
            lambda s: s.__setitem__("android", s["android"].replace('"compressThreadImage" ->', '"other" ->', 1)),
        ),
        (
            "iOS dedicated method",
            lambda s: s.__setitem__("ios", s["ios"].replace("- (void)compressThreadImage:", "- (void)otherThreadImage:", 1)),
        ),
        (
            "OHOS cancellation dispatch",
            lambda s: s.__setitem__("ohos", s["ohos"].replace("case KRBridgeModule.CANCEL_COMPRESS_THREAD_IMAGE", "case KRBridgeModule.OTHER", 1)),
        ),
        (
            "video-first guard",
            lambda s: s.__setitem__("android", s["android"].replace('if (extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false', "", 1)),
        ),
        (
            "Android source-path video guard",
            lambda s: s.__setitem__(
                "android",
                _replace_in_region(
                    s["android"],
                    "private fun isThreadImageCompressionInput(",
                    "private fun compressThreadImage(",
                    "if (sourceExtension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS) return false",
                    "",
                    "Android source-path video guard mutation",
                ),
            ),
        ),
        (
            "Android shared flv parity",
            lambda s: s.__setitem__(
                "android",
                s["android"].replace('"mp4", "flv"', '"mp4"', 1),
            ),
        ),
        (
            "Android picker path video guard",
            lambda s: s.__setitem__(
                "android",
                _replace_in_region(
                    s["android"],
                    "private fun isImageAttachment(",
                    "private fun compressedPickedImageFileName(",
                    "  val videoPathCandidates = sequenceOf(fileName, uri.path.orEmpty(), uri.lastPathSegment.orEmpty())\n"
                    "  if (videoPathCandidates.any { candidate ->\n"
                    "      val extension = candidate.substringAfterLast('.').lowercase(Locale.US)\n"
                    "      extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS\n"
                    "  }) return false\n",
                    "",
                    "Android picker path video guard mutation",
                ),
            ),
        ),
        (
            "Android picker MIME video guard",
            lambda s: s.__setitem__(
                "android",
                _replace_in_region(
                    s["android"],
                    "private fun isImageAttachment(",
                    "private fun compressedPickedImageFileName(",
                    '  if (normalizedMimeType.startsWith("video/")) return false\n',
                    "",
                    "Android picker MIME video guard mutation",
                ),
            ),
        ),
        (
            "JPEG output",
            lambda s: s.__setitem__("android", s["android"].replace('.jpg")', '.png")', 1)),
        ),
        (
            "failure cleanup",
            lambda s: s.__setitem__("ios", s["ios"].replace("removeItemAtURL:outputURL", "", 1)),
        ),
        (
            "OHOS source-path video guard",
            lambda s: s.__setitem__(
                "ohos",
                _replace_in_region(
                    s["ohos"],
                    "private async isThreadImageCompressionInput(",
                    "private threadImageCompressionOutputName(",
                    r"if (/\.(mp4|flv)$/.test(normalizedSourcePath)) return false;",
                    "",
                    "OHOS source-path video guard mutation",
                ),
            ),
        ),
        (
            "OHOS source-path shared flv parity",
            lambda s: s.__setitem__(
                "ohos",
                _replace_in_region(
                    s["ohos"],
                    "private async isThreadImageCompressionInput(",
                    "private threadImageCompressionOutputName(",
                    "mp4|flv",
                    "mp4",
                    "OHOS source-path flv parity mutation",
                ),
            ),
        ),
        (
            "OHOS picker shared flv parity",
            lambda s: s.__setitem__(
                "ohos",
                _replace_in_region(
                    s["ohos"],
                    "private isOhosImageAttachment(",
                    "private async cacheCompressedPickedThreadImage(",
                    r"mp4|flv",
                    "mp4",
                    "OHOS picker flv parity mutation",
                ),
            ),
        ),
        (
            "iOS picker video guard",
            lambda s: s.__setitem__(
                "ios",
                _replace_in_region(
                    s["ios"],
                    "static BOOL SlockAttachmentIsImage(",
                    "static NSError *SlockThreadAttachmentImageCompressionError(",
                    "  if ([videoExtensions containsObject:nameExtension] ||\n"
                    "      [videoExtensions containsObject:sourceExtension] ||\n"
                    "      [normalizedMimeType hasPrefix:@\"video/\"]) return NO;\n",
                    "",
                    "iOS picker video guard mutation",
                ),
            ),
        ),
        (
            "iOS picker source-extension guard",
            lambda s: s.__setitem__(
                "ios",
                _replace_in_region(
                    s["ios"],
                    "static BOOL SlockAttachmentIsImage(",
                    "static NSError *SlockThreadAttachmentImageCompressionError(",
                    "[videoExtensions containsObject:sourceExtension] ||\n      ",
                    "",
                    "iOS picker source-extension guard mutation",
                ),
            ),
        ),
        (
            "iOS output write lock",
            lambda s: s.__setitem__(
                "ios",
                _replace_in_region(
                    s["ios"],
                    "- (void)compressThreadImage:(NSDictionary *)args {",
                    "- (void)cancelCompressThreadImage:",
                    "[operation.outputLock lock]",
                    "",
                    "iOS output write lock mutation",
                ),
            ),
        ),
        (
            "shared error code",
            lambda s: s.__setitem__("preview", s["preview"].replace(ERROR_CODE, "different_error", 1)),
        ),
        (
            "pending journal compressed source",
            lambda s: s.__setitem__(
                "thread",
                _replace_in_region(
                    s["thread"],
                    "internal fun threadPendingSendOwnedTemporaryFileRefs(",
                    "internal fun threadComposerMessageBodyForSend(",
                    '"composer_image_compressor"',
                    '"other_source"',
                    "pending journal compressed source mutation",
                ),
            ),
        ),
        (
            "pending journal source ownership filter",
            lambda s: s.__setitem__(
                "thread",
                _replace_in_region(
                    s["thread"],
                    "internal fun threadPendingSendOwnedTemporaryFileRefs(",
                    "internal fun threadComposerMessageBodyForSend(",
                    "    .filter { it.source in ownedTemporarySources }\n",
                    "",
                    "pending journal source ownership filter mutation",
                ),
            ),
        ),
        (
            "pending journal nonblank fileRef guard",
            lambda s: s.__setitem__(
                "thread",
                _replace_in_region(
                    s["thread"],
                    "internal fun threadPendingSendOwnedTemporaryFileRefs(",
                    "internal fun threadComposerMessageBodyForSend(",
                    "?.takeIf(String::isNotBlank)",
                    "",
                    "pending journal nonblank fileRef guard mutation",
                ),
            ),
        ),
        (
            "pending upload fileRef propagation",
            lambda s: s.__setitem__(
                "thread", s["thread"].replace("?.fileRef", "?.missingFileRef", 1)
            ),
        ),
        (
            "pending upload source propagation",
            lambda s: s.__setitem__(
                "thread", s["thread"].replace(
                    "source = sourceAttachment?.source", "source = null", 1
                )
            ),
        ),
        (
            "pending upload fileRef ownership admission",
            lambda s: s.__setitem__(
                "thread", s["thread"].replace(
                    "fileRef = sourceAttachment?.takeIf { it.source in ownedTemporarySources }?.fileRef",
                    "fileRef = sourceAttachment?.fileRef",
                    1,
                )
            ),
        ),
    ]
    for label, mutate in mutations:
        candidate = dict(good)
        mutate(candidate)
        try:
            check_contract(candidate)
        except Violation:
            continue
        print(f"self-test FAIL: mutation was accepted: {label}", file=sys.stderr)
        return 1
    print(f"thread image compression contract self-test: PASS ({len(mutations) + 1} arms)")
    return 0


