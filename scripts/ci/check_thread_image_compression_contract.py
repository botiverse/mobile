#!/usr/bin/env python3
"""Static admission gate for the composer image-compression contract (task #1066).

The picker/editor path is deliberately a two-stage protocol:

* a picker result is handed to the image editor without an eager JPEG pass;
* after editor confirmation, shared code either keeps the original ref or asks
  the native host to produce one new JPEG temporary file.

This checker only verifies the structural faces of that protocol.  It does not
claim that an iOS/OHOS compiler, a camera, or a physical device was exercised.
Run ``--self-test`` to exercise mutation arms against small source fixtures;
without the flag the checker reads the current repository and fails closed when
any required source or contract face is absent.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import Mapping


REPO_ROOT = Path(__file__).resolve().parents[2]

PATHS = {
    "bridge": "compose/shared/src/commonMain/kotlin/build/raft/app/base/BridgeModule.kt",
    "preview": "compose/shared/src/commonMain/kotlin/build/raft/app/SlockFilePreview.kt",
    "thread": "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadPage.kt",
    "uploaded_preview": "compose/shared/src/commonMain/kotlin/build/raft/app/ThreadComposerUploadedImagePreview.kt",
    "android": "compose/androidApp/src/main/java/build/raft/app/shared/SlockKuiklyActivity.kt",
    "ios": "compose/iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m",
    "ohos": "compose/ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets",
}

ERROR_CODE = "attachment_image_compression_failed"
SOURCE_MARKER = "composer_image_compressor"


class Violation(Exception):
    """A source-level contract violation."""


def _source(sources: Mapping[str, str], name: str) -> str:
    value = sources.get(name)
    if not value:
        raise Violation(f"{name} source is missing or empty")
    return value


def _require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise Violation(f"{label}: missing {needle!r}")


def _require_all(source: str, needles: tuple[str, ...], label: str) -> None:
    for needle in needles:
        _require(source, needle, label)


def _quoted_tokens(body: str) -> set[str]:
    """Return simple single- or double-quoted tokens from a source fragment."""

    tokens: set[str] = set()
    for match in re.finditer(r"(['\"])([^'\"]+)\1", body):
        tokens.add(match.group(2).strip().lower())
    return tokens


def _kotlin_string_set(source: str, name: str, label: str) -> set[str]:
    """Extract a Kotlin ``val name = setOf(...)`` declaration."""

    match = re.search(
        rf"(?m)^\s*(?:private|internal|public)?\s*(?:const\s+)?val\s+"
        rf"{re.escape(name)}\s*=\s*setOf\((?P<body>[^)]*)\)",
        source,
    )
    if match is None:
        raise Violation(f"{label}: {name} set declaration is missing")
    tokens = _quoted_tokens(match.group("body"))
    if not tokens:
        raise Violation(f"{label}: {name} set declaration is empty")
    return tokens


def _require_video_set_covers(
    actual: set[str],
    expected: set[str],
    label: str,
) -> None:
    missing = sorted(expected - actual)
    if missing:
        raise Violation(
            f"{label}: missing shared video extensions: {', '.join(missing)}"
        )


def _ohos_video_regex_extensions(
    region: str,
    target: str,
    label: str,
) -> set[str]:
    """Extract alternatives from an OHOS ``/\\.(a|b)$/`` guard."""

    match = re.search(
        rf"/\\\.\((?P<body>[^)]+)\)\$/\.test\(\s*{re.escape(target)}\s*\)",
        region,
    )
    if match is None:
        raise Violation(f"{label}: video-extension regex for {target} is missing")
    extensions = {
        token.strip().lower()
        for token in match.group("body").split("|")
        if token.strip()
    }
    if not extensions:
        raise Violation(f"{label}: video-extension regex for {target} is empty")
    return extensions


def _ohos_video_array_extensions(region: str, label: str) -> set[str]:
    """Extract quoted extensions from an OHOS ``[...].includes(extension)`` guard."""

    match = re.search(
        r"\[(?P<body>[^\]]+)\]\.includes\(\s*extension\s*\)",
        region,
    )
    if match is None:
        raise Violation(f"{label}: video-extension array guard is missing")
    extensions = _quoted_tokens(match.group("body"))
    if not extensions:
        raise Violation(f"{label}: video-extension array guard is empty")
    return extensions


def _objc_video_set_extensions(region: str, label: str) -> set[str]:
    """Extract quoted alternatives from an Objective-C ``videoExtensions`` NSSet."""

    match = re.search(
        r"videoExtensions\s*=\s*\[NSSet\s+setWithArray:@\["
        r"(?P<body>.*?)\]\]",
        region,
        re.DOTALL,
    )
    if match is None:
        raise Violation(f"{label}: videoExtensions NSSet declaration is missing")
    extensions = _quoted_tokens(match.group("body"))
    if not extensions:
        raise Violation(f"{label}: videoExtensions NSSet declaration is empty")
    return extensions


def _region(source: str, start: str, end: str | None, label: str) -> str:
    begin = source.find(start)
    if begin < 0:
        raise Violation(f"{label}: start marker {start!r} not found")
    finish = len(source) if end is None else source.find(end, begin + len(start))
    if finish < 0:
        raise Violation(f"{label}: end marker {end!r} not found")
    return source[begin:finish]


def _replace_in_region(
    source: str,
    start: str,
    end: str | None,
    old: str,
    new: str,
    label: str,
    count: int = 1,
) -> str:
    """Replace a marker only inside one bounded source region.

    Mutation arms should exercise the contract face they claim to exercise.  A
    whole-file replacement can accidentally alter an unrelated producer (or a
    response marker) and make a mutation pass for the wrong reason.  Fail
    loudly when the fixture no longer contains the intended marker so a stale
    self-test cannot silently become a no-op.
    """

    begin = source.find(start)
    if begin < 0:
        raise AssertionError(f"{label}: start marker {start!r} not found")
    finish = len(source) if end is None else source.find(end, begin + len(start))
    if finish < 0:
        raise AssertionError(f"{label}: end marker {end!r} not found")
    region = source[begin:finish]
    mutated, replacements = region.replace(old, new, count), region.count(old)
    if replacements == 0:
        raise AssertionError(f"{label}: mutation marker {old!r} not found")
    return source[:begin] + mutated + source[finish:]


def _ordered(source: str, needles: tuple[str, ...], label: str) -> None:
    positions = []
    for needle in needles:
        position = source.find(needle)
        if position < 0:
            raise Violation(f"{label}: missing ordered marker {needle!r}")
        positions.append(position)
    if positions != sorted(positions):
        raise Violation(f"{label}: markers are not in the required order: {needles!r}")


def check_shared_bridge(sources: Mapping[str, str]) -> None:
    source = _source(sources, "bridge")
    method = _region(source, "suspend fun compressThreadImage(", None, "shared bridge")
    _require_all(
        method,
        (
            "fileRef: String",
            "fileName: String",
            "mimeType: String",
            "requestId: String",
            'put("requestId"',
            'put("fileRef"',
            'put("fileName"',
            'put("mimeType"',
            "suspendCancellableCoroutine",
            "invokeOnCancellation",
            "CANCEL_COMPRESS_THREAD_IMAGE",
            "terminal.compareAndSet",
        ),
        "shared compressThreadImage bridge",
    )
    _require_all(
        source,
        (
            'const val COMPRESS_THREAD_IMAGE = "compressThreadImage"',
            'const val CANCEL_COMPRESS_THREAD_IMAGE = "cancelCompressThreadImage"',
        ),
        "shared bridge constants",
    )
    upload = _region(source, "suspend fun threadAttachmentUpload(", "suspend fun compressThreadImage(", "shared picker bridge")
    _require(upload, "compressImages: Boolean", "shared picker bridge")
    _require(upload, 'put("compressImages"', "shared picker bridge")


def check_shared_preview(sources: Mapping[str, str]) -> None:
    source = _source(sources, "preview")
    _require_all(
        source,
        (
            ERROR_CODE,
            SOURCE_MARKER,
            "SlockFilePreviewComposerImageOperationGate",
            "fun begin(",
            "fun complete(",
            "fun cancel(",
            "fun retire(",
            "parseSlockFilePreviewCompressedImageResult",
            "composerImageCompressAfterEdit",
            "composerImageEditCompleted",
            "composerImageCompressed",
            "hasPendingComposerImageFinalization",
            "withComposerImageFinalResult",
            "markComposerImageFinalized",
        ),
        "shared preview compression state",
    )
    parser = _region(
        source,
        "internal fun parseSlockFilePreviewCompressedImageResult(",
        None,
        "shared compression response parser",
    )
    _require_all(
        parser,
        (
            "filesJson.length() != 1",
            'rawMimeType != "image/jpeg"',
            "rawSource != SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE",
            "SlockFilePreviewCompressedImageResult.Failure",
            "SlockFilePreviewCompressedImageResult.Success",
        ),
        "shared compression response parser",
    )


def check_thread_routing(sources: Mapping[str, str]) -> None:
    thread = _source(sources, "thread")
    preview = _source(sources, "preview")
    # File picks never compress images. The helper must stay a hard false so a
    # JPEG selected via attachFile keeps source bytes for the shared size gate.
    # Image and camera picks still skip picker compression and stay on the
    # two-stage editor path. Sample the policy before the async launch.
    policy = _region(
        thread,
        "internal fun shouldCompressThreadAttachmentPickerImages(",
        "internal fun slockComposerImagePickerFilesAreStrictImages(",
        "ThreadPage mixed-picker compression policy",
    )
    if re.search(r'action\s*==\s*"attachFile"\s*&&\s*!originalImage', policy):
        raise Violation(
            "ThreadPage mixed-picker compression policy: File picks must not compress"
        )
    # Anchor the hard-false to the return type, not a parameter default.
    # `originalImage: Boolean = false` would otherwise satisfy a bare
    # ": Boolean = false" substring while the body compresses again.
    if not re.search(r"\)\s*:\s*Boolean\s*=\s*false\b", policy):
        raise Violation(
            "ThreadPage mixed-picker compression policy: helper must be a hard false"
        )
    picker_calls = list(re.finditer(r"threadAttachmentUpload\s*\(", thread))
    if not picker_calls:
        raise Violation("ThreadPage: no threadAttachmentUpload call")
    # The call may contain comments and named arguments, so inspect a bounded
    # window around its event branch rather than relying on one exact line layout.
    picker_start = max(0, picker_calls[-1].start() - 2200)
    picker_window = thread[picker_start : picker_calls[-1].start() + 1800]
    _require(
        picker_window,
        "val compressPickerImages",
        "ThreadPage picker policy snapshot",
    )
    _require(
        picker_window,
        "shouldCompressThreadAttachmentPickerImages(",
        "ThreadPage picker policy snapshot",
    )
    _require(
        picker_window,
        "compressImages = compressPickerImages",
        "ThreadPage picker policy propagation",
    )
    launch_position = picker_window.find("coroutineScope.launch")
    snapshot_position = picker_window.find("val compressPickerImages")
    upload_position = picker_window.find("threadAttachmentUpload(")
    if launch_position >= 0 and snapshot_position > launch_position:
        raise Violation(
            "ThreadPage picker policy snapshot: policy is sampled after async launch"
        )
    if upload_position >= 0 and snapshot_position > upload_position:
        raise Violation(
            "ThreadPage picker policy snapshot: policy is sampled after upload admission"
        )
    # A literal true would re-enable eager compression for every picker action.
    if "compressImages = true" in picker_window:
        raise Violation("ThreadPage picker routing: eager compression is enabled")
    # Both uploaded-tile entry points must forward the draft's policy into the
    # local builder, which in turn reuses the initial picker policy mapping.
    calls = re.findall(r"threadComposerUploadedImagePreviewPayload\((.*?)\)\s*\?: return", thread, re.DOTALL)
    if len(calls) != 2:
        raise Violation("ThreadPage: expected single and gallery uploaded-preview calls")
    for call in calls:
        _require(call, "originalImage = currentDraftState.originalImage", "uploaded-preview policy argument")
    local_builder = _source(sources, "uploaded_preview")
    _require(local_builder,
             "threadComposerImageAnnotationPayload(threadInstanceKey, actionId, files, originalImage)",
             "uploaded-preview policy forwarding")
    initial_builder = _region(thread, "internal fun threadComposerImageAnnotationPayload(",
                              "private fun KuiklyThreadAttachmentItem.toThreadAttachmentPreviewPayload(",
                              "initial image editor policy")
    _require(initial_builder, "composerImageCompressAfterEdit = !originalImage", "initial image editor policy")
    _require_all(
        thread,
        (
            "slockComposerImagePickerIsStrictImage",
            "resolveThreadPickedAttachmentComposerAction",
            "ThreadPickedAttachmentComposerAction.UploadToComposer",
            "ThreadPickedAttachmentComposerAction.OpenImageAnnotation",
        ),
        "ThreadPage picker classification",
    )
    # The actual asynchronous finalization can live in the preview composable,
    # but it must be reachable from this ThreadPage-produced payload and consume
    # the dedicated bridge/parser pair.
    combined = thread + "\n" + preview
    _require(combined, "compressThreadImage", "editor-confirmation compression route")
    _require(combined, "parseSlockFilePreviewCompressedImageResult", "editor-confirmation response admission")
    _require(combined, "withComposerImageFinalResult", "editor-confirmation payload replacement")


def _owned_temporary_source_set_names(thread: str) -> set[str]:
    names: set[str] = set()
    for match in re.finditer(
        r"^(?:private\s+|internal\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*setOf\((.*?)\)",
        thread,
        re.DOTALL | re.MULTILINE,
    ):
        body = match.group(2)
        if all(
            marker in body
            for marker in ('"platform_picker"', '"platform_camera"', '"composer_image_editor"')
        ) and (
            '"composer_image_compressor"' in body
            or "SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE" in body
        ):
            names.add(match.group(1))
    return names


def _require_owned_temporary_sources(
    region: str,
    thread: str,
    label: str,
) -> None:
    direct_sources_present = all(
        marker in region
        for marker in ('"platform_picker"', '"platform_camera"', '"composer_image_editor"')
    ) and (
        '"composer_image_compressor"' in region
        or "SLOCK_FILE_PREVIEW_IMAGE_COMPRESSION_SOURCE" in region
    )
    shared_source_sets = _owned_temporary_source_set_names(thread)
    if not direct_sources_present and not any(name in region for name in shared_source_sets):
        raise Violation(
            f"{label}: picker/camera/editor/compressor owned source set is not referenced"
        )


def _check_pending_journal_ownership(thread: str) -> None:
    """Keep process-death cleanup ownership aligned with the final file set.

    A compressed editor result is a new, process-local file just like a picker/camera
    temporary.  If the pending-send journal only recognizes the original picker sources,
    a process death after the send admission can strand the JPEG (or lose the source PNG).
    This is deliberately a generic ownership helper.  A video or ordinary document can still be
    backed by a process-local picker/editor ref and therefore must be retired after a terminal
    send.  Image/video classification belongs to the compression admission helpers, not this
    process-death cleanup path.
    """

    helper = _region(
        thread,
        "internal fun threadPendingSendOwnedTemporaryFileRefs(",
        "internal fun threadComposerMessageBodyForSend(",
        "pending-send temporary ownership helper",
    )
    _require_all(
        helper,
        (
            "fileRef",
            "mapNotNull",
            "source",
            "takeIf",
            "isNotBlank",
        ),
        "pending-send temporary ownership sources",
    )
    _require_owned_temporary_sources(
        helper,
        thread,
        "pending-send temporary ownership sources",
    )

    # The source predicate must actually be applied to each row.  Merely declaring a whitelist
    # elsewhere in the file is insufficient: deleting this filter would make remote/external refs
    # eligible for native retirement.
    if re.search(r"\b(?:attachment|it)\.source\s+in\s+[A-Za-z_][A-Za-z0-9_]*", helper) is None:
        raise Violation(
            "pending-send temporary ownership helper: source whitelist filter is missing"
        )
    source_filter_position = re.search(
        r"\b(?:attachment|it)\.source\s+in\s+[A-Za-z_][A-Za-z0-9_]*", helper
    )
    extraction_position = helper.find("mapNotNull")
    if extraction_position >= 0 and source_filter_position is not None and source_filter_position.start() > extraction_position:
        raise Violation(
            "pending-send temporary ownership helper: source whitelist must precede fileRef extraction"
        )
    nonblank_position = helper.find("isNotBlank")
    if extraction_position >= 0 and nonblank_position >= 0 and nonblank_position < extraction_position:
        raise Violation(
            "pending-send temporary ownership helper: nonblank fileRef guard must be inside extraction"
        )


def _check_pending_journal_upload_propagation(thread: str) -> None:
    upload = _region(
        thread,
        "suspend fun uploadComposerAttachmentFiles(",
        "fun performComposerSend(",
        "composer upload success mapping",
    )
    _require_all(
        upload,
        (
            "val sourceAttachment",
            "val uploaded",
            "uploadResult.data.attachments.map",
            "fileRef =",
            "sourceAttachment",
            ".fileRef",
            "source = sourceAttachment?.source",
        ),
        "composer upload temporary ownership propagation",
    )
    _require_owned_temporary_sources(
        upload,
        thread,
        "composer upload temporary ownership propagation",
    )
    source_line = upload.find("source = sourceAttachment?.source")
    if source_line < 0:
        raise Violation(
            "composer upload source propagation: sourceAttachment source is not preserved"
        )
    file_ref_start = upload.rfind("fileRef =", 0, source_line)
    if file_ref_start < 0:
        raise Violation(
            "composer upload fileRef propagation: mapped fileRef assignment is missing"
        )
    file_ref_assignment = upload[file_ref_start:source_line]
    if "sourceAttachment" not in file_ref_assignment or ".fileRef" not in file_ref_assignment:
        raise Violation(
            "composer upload fileRef propagation: accepted source fileRef is not preserved"
        )
    if "takeIf" not in file_ref_assignment and "isOwnedTemporary" not in file_ref_assignment:
        raise Violation(
            "composer upload fileRef propagation: ownership admission is missing"
        )
    # The source was already admitted when the local row was created.  Re-applying a
    # picker/camera-only whitelist here would silently discard editor/compressor refs.
    if "setOf(\"platform_picker\", \"platform_camera\")" in file_ref_assignment:
        raise Violation(
            "composer upload fileRef propagation: legacy picker-only ownership filter remains"
        )


def _check_strict_image_classifier(preview: str) -> None:
    classifier_start = preview.find("internal fun slockComposerImagePickerIsStrictImage(")
    if classifier_start < 0:
        raise Violation(
            "shared image/video classifier: start marker "
            "'internal fun slockComposerImagePickerIsStrictImage(' not found"
        )
    # The extension set is shared with ThreadPage's pending-send ownership
    # helper.  Its visibility is intentionally allowed to be private (legacy
    # source) or internal (when ThreadPage references the single shared set).
    video_set = re.search(
        r"(?m)^\s*(?:private|internal)\s+(?:const\s+)?val\s+"
        r"SlockFilePreviewVideoExtensions\b",
        preview[classifier_start:],
    )
    if video_set is None:
        raise Violation(
            "shared image/video classifier: shared video-extension set declaration is missing"
        )
    classifier = preview[classifier_start : classifier_start + video_set.start()]
    _require_all(
        classifier,
        ('mime.startsWith("image/")', "extension in SlockFilePreviewImageExtensions"),
        "shared image/video classifier",
    )
    # MIME and extension are both required; this prevents a video MIME or a
    # video extension from entering the image editor even when providers lie.
    if "&&" not in classifier:
        raise Violation("shared image/video classifier: MIME and extension are not conjunctive")


def _check_video_priority(sources: Mapping[str, str]) -> None:
    preview = _source(sources, "preview")
    _check_strict_image_classifier(preview)
    shared_video_extensions = _kotlin_string_set(
        preview,
        "SlockFilePreviewVideoExtensions",
        "shared video-extension contract",
    )

    android = _source(sources, "android")
    android_video_extensions = _kotlin_string_set(
        android,
        "THREAD_ATTACHMENT_VIDEO_EXTENSIONS",
        "Android video-extension contract",
    )
    _require_video_set_covers(
        android_video_extensions,
        shared_video_extensions,
        "Android video-extension contract",
    )
    android_fn = _region(
        android,
        "private fun isThreadImageCompressionInput(",
        "private fun compressThreadImage(",
        "Android image/video classifier",
    )
    _require_all(
        android_fn,
        (
            "THREAD_ATTACHMENT_VIDEO_EXTENSIONS",
            "!normalizedMime.startsWith(\"image/\")",
            "return false",
        ),
        "Android image/video classifier",
    )
    if "extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS" not in android_fn:
        raise Violation("Android image/video classifier: video extension must be rejected")
    if android_fn.find("extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS") > android_fn.rfind("return true"):
        raise Violation("Android image/video classifier: video extension check must precede acceptance")
    # The renderer-supplied display name is not authoritative.  A provider can
    # return an image MIME/name for a movie-backed cache path, so the native
    # admission helper must reject the source file's suffix before decoding.
    _require_all(
        android_fn,
        ("sourceFile.extension", "sourceExtension", "sourceExtension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS"),
        "Android source-path image/video classifier",
    )
    source_guard = android_fn.find("sourceExtension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS")
    acceptance_position = android_fn.rfind("return true")
    if acceptance_position < 0 or source_guard > acceptance_position:
        raise Violation(
            "Android source-path image/video classifier: source extension guard must precede decode/acceptance"
        )

    # The generic Android picker can be asked for ``*/*`` and providers may
    # misreport a movie as image/jpeg.  Every path identity (display name and
    # URI path/last segment) plus the video MIME guard must run before the
    # image-MIME acceptance branch.  The shared set parity check above keeps
    # the extension vocabulary (including flv) aligned with ThreadPage.
    android_picker_fn = _region(
        android,
        "private fun isImageAttachment(",
        "private fun compressedPickedImageFileName(",
        "Android picker image/video classifier",
    )
    _require_all(
        android_picker_fn,
        (
            "videoPathCandidates",
            "uri.path.orEmpty()",
            "uri.lastPathSegment.orEmpty()",
            "extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS",
            'normalizedMimeType.startsWith("video/")',
            'normalizedMimeType.startsWith("image/")',
        ),
        "Android picker image/video classifier",
    )
    android_image_acceptance = android_picker_fn.find(
        'normalizedMimeType.startsWith("image/")'
    )
    if android_image_acceptance < 0:
        raise Violation("Android picker image/video classifier: image acceptance is missing")
    for marker in (
        "videoPathCandidates",
        "extension in THREAD_ATTACHMENT_VIDEO_EXTENSIONS",
        'normalizedMimeType.startsWith("video/")',
    ):
        marker_position = android_picker_fn.find(marker)
        if marker_position < 0 or marker_position > android_image_acceptance:
            raise Violation(
                "Android picker image/video classifier: video guard must precede image acceptance"
            )

    ios = _source(sources, "ios")
    ios_fn = _region(
        ios,
        "static BOOL SlockThreadImageCompressionInputIsImage(",
        "static NSString *SlockCompressedAttachmentFileName",
        "iOS image/video classifier",
    )
    _require_all(
        ios_fn,
        ("hasPrefix:@\"image/\"", "videoExtensions"),
        "iOS image/video classifier",
    )
    # Hosts may receive a provider display name whose suffix disagrees with the
    # source URL.  The hardened implementation rejects either suffix; retain
    # compatibility with the compact single-extension fixture used by the
    # mutation self-test.
    has_single_extension_guard = (
        "return ![videoExtensions containsObject:extension]" in ios_fn
    )
    has_dual_extension_guard = all(
        marker in ios_fn
        for marker in (
            "nameExtension",
            "sourceExtension",
            "containsObject:nameExtension",
            "containsObject:sourceExtension",
        )
    ) and ("&&" in ios_fn)
    if not (has_single_extension_guard or has_dual_extension_guard):
        raise Violation(
            "iOS image/video classifier: video extension guard must cover "
            "the display name (and source URL when available)"
        )

    ios_compression_video_extensions = _objc_video_set_extensions(
        ios_fn,
        "iOS compression video-extension classifier",
    )
    _require_video_set_covers(
        ios_compression_video_extensions,
        shared_video_extensions,
        "iOS compression video-extension contract",
    )

    # Generic document picks use this classifier before the compression branch.
    # Keep MIME, display-name, and source-URL movie evidence ahead of the
    # image/* acceptance so a mismatched provider tuple cannot enter ImageIO.
    ios_picker_fn = _region(
        ios,
        "static BOOL SlockAttachmentIsImage(",
        "static NSError *SlockThreadAttachmentImageCompressionError(",
        "iOS picker image/video classifier",
    )
    _require_all(
        ios_picker_fn,
        (
            "normalizedMimeType",
            "componentsSeparatedByString:@\";\"",
            "hasPrefix:@\"video/\"",
            "nameExtension",
            "sourceExtension",
            "containsObject:nameExtension",
            "containsObject:sourceExtension",
            "hasPrefix:@\"image/\"",
        ),
        "iOS picker image/video classifier",
    )
    ios_video_extensions = _objc_video_set_extensions(
        ios_picker_fn,
        "iOS picker image/video classifier",
    )
    _require_video_set_covers(
        ios_video_extensions,
        shared_video_extensions,
        "iOS picker video-extension contract",
    )
    ios_image_acceptance = ios_picker_fn.find('hasPrefix:@"image/"')
    if ios_image_acceptance < 0:
        raise Violation("iOS picker image/video classifier: image acceptance is missing")
    for marker in (
        "containsObject:nameExtension",
        "containsObject:sourceExtension",
        'hasPrefix:@"video/"',
    ):
        marker_position = ios_picker_fn.find(marker)
        if marker_position < 0 or marker_position > ios_image_acceptance:
            raise Violation(
                "iOS picker image/video classifier: video guard must precede image acceptance"
            )

    ohos = _source(sources, "ohos")
    ohos_fn = _region(
        ohos,
        "private isOhosImageAttachment(",
        "private async cacheCompressedPickedThreadImage(",
        "OHOS image/video classifier",
    )
    _require_all(
        ohos_fn,
        ("startsWith('video/')", "startsWith('image/')", "\\.(avif|bmp|gif|heic|heif|jpe?g|png|webp|tiff?)"),
        "OHOS image/video classifier",
    )
    _ordered(
        ohos_fn,
        ("startsWith('video/')", "startsWith('image/')"),
        "OHOS video MIME priority",
    )
    ohos_picker_video_extensions = _ohos_video_regex_extensions(
        ohos_fn,
        "lowerUri",
        "OHOS picker image/video classifier",
    )
    _require_video_set_covers(
        ohos_picker_video_extensions,
        shared_video_extensions,
        "OHOS picker video-extension contract",
    )

    # The lower-level compression admission receives the resolved source path,
    # which may disagree with the picker display name.  It must apply the same
    # video-suffix guard before opening the source file/ImageKit decoder.
    ohos_input = _region(
        ohos,
        "private async isThreadImageCompressionInput(",
        "private threadImageCompressionOutputName(",
        "OHOS source-path image/video classifier",
    )
    _require_all(
        ohos_input,
        (
            "sourcePath",
            "normalizedSourcePath",
            "test(normalizedSourcePath)",
            "fs.stat(sourcePath)",
        ),
        "OHOS source-path image/video classifier",
    )
    source_guard = ohos_input.find("test(normalizedSourcePath)")
    stat_position = ohos_input.find("fs.stat(sourcePath)")
    if source_guard < 0 or stat_position < 0 or source_guard > stat_position:
        raise Violation(
            "OHOS source-path image/video classifier: source extension guard must precede source stat/decode"
        )
    ohos_source_video_extensions = _ohos_video_regex_extensions(
        ohos_input,
        "normalizedSourcePath",
        "OHOS source-path image/video classifier",
    )
    _require_video_set_covers(
        ohos_source_video_extensions,
        shared_video_extensions,
        "OHOS source-path video-extension contract",
    )


def _check_android_native(sources: Mapping[str, str]) -> None:
    source = _source(sources, "android")
    _require_all(
        source,
        (
            '"compressThreadImage" ->',
            '"cancelCompressThreadImage" ->',
            "private fun compressThreadImage(",
            "private fun cancelCompressThreadImage(",
            SOURCE_MARKER,
            ERROR_CODE,
            "UUID.randomUUID()",
            'Bitmap.CompressFormat.JPEG',
            "outputFile.delete()",
            "AtomicBoolean",
            "threadImageCompressionOperations",
        ),
        "Android native compression bridge",
    )
    method = _region(source, "private fun compressThreadImage(", "private fun cancelCompressThreadImage(", "Android native compression")
    _require_all(
        method,
        (
            'optString("requestId")',
            'optString("fileName")',
            'optString("mimeType")',
            'optString("fileRef")',
            ".jpg",
            "compressThreadImageFile(sourceFile, outputFile",
            "finishThreadImageCompression",
        ),
        "Android native compression",
    )
    if "outputFile" in method and "sourceFile" in method:
        # Distinct UUID-named output plus a source-root admission check prevents
        # accidental in-place overwrite of the picker/editor source.
        _require(method, "UUID.randomUUID()", "Android output isolation")
    _require(source, "if (!successful || operation.isCancelled())", "Android failed-output cleanup")


def _check_ios_native(sources: Mapping[str, str]) -> None:
    source = _source(sources, "ios")
    _require_all(
        source,
        (
            "- (void)compressThreadImage:(NSDictionary *)args",
            "- (void)cancelCompressThreadImage:(NSDictionary *)args",
            SOURCE_MARKER,
            ERROR_CODE,
            "NSUUID.UUID.UUIDString",
            "image/jpeg",
            "removeItemAtURL",
            "outputLock",
            "[self.outputLock lock]",
            "[self.outputLock unlock]",
        ),
        "iOS native compression bridge",
    )
    # The bridge declares these methods in its private interface before the
    # implementation.  Anchor on the opening brace so the bounded scan cannot
    # accidentally stop at the prototype and report a false negative.
    method = _region(source, "- (void)compressThreadImage:(NSDictionary *)args {", "- (void)cancelCompressThreadImage:", "iOS native compression")
    _require_all(
        method,
        (
            'params[@"requestId"]',
            'params[@"fileRef"]',
            'params[@"fileName"]',
            'params[@"mimeType"]',
            "[operation.outputLock lock]",
            "[operation.outputLock unlock]",
            "SlockWriteCompressedPickedImage",
        ),
        "iOS native compression",
    )
    write_lock_start = method.find("[operation.outputLock lock]")
    write_lock_end = method.find("[operation.outputLock unlock]", write_lock_start + 1)
    if write_lock_start < 0 or write_lock_end < 0:
        raise Violation(
            "iOS native compression: write/rename must be enclosed by outputLock"
        )
    write_region = method[write_lock_start:write_lock_end]
    _require(
        write_region,
        "SlockWriteCompressedPickedImage",
        "iOS native compression output-lock fence",
    )
    # Cleanup may be performed directly in the method or through the operation's
    # ownership helper. In the latter form, verify the helper implementation too;
    # a call-site token alone must not permit a no-op cleanup helper.
    if "removeItemAtURL:outputURL" not in method:
        _require(method, "removeOutputs", "iOS native compression cleanup dispatch")
        _require(source, "removeItemAtURL:self.outputURL", "iOS native compression cleanup implementation")


def _check_ohos_native(sources: Mapping[str, str]) -> None:
    source = _source(sources, "ohos")
    _require_all(
        source,
        (
            "static readonly COMPRESS_THREAD_IMAGE = \"compressThreadImage\"",
            "static readonly CANCEL_COMPRESS_THREAD_IMAGE = \"cancelCompressThreadImage\"",
            "case KRBridgeModule.COMPRESS_THREAD_IMAGE",
            "case KRBridgeModule.CANCEL_COMPRESS_THREAD_IMAGE",
            "private async compressThreadImage(",
            "private cancelCompressThreadImage(",
            SOURCE_MARKER,
            ERROR_CODE,
            "image/jpeg",
            "UUID",
            "await fs.unlink",
        ),
        "OHOS native compression bridge",
    )
    method = _region(source, "private async compressThreadImage(", "private cancelCompressThreadImage(", "OHOS native compression")
    _require_all(
        method,
        (
            '["requestId"]',
            '["fileRef"]',
            '["fileName"]',
            '["mimeType"]',
        ),
        "OHOS native compression",
    )
    # The production bridge keeps ImageKit work in a separate helper so the
    # request dispatcher remains small. Inspect that helper when the encode
    # markers are not in the dispatcher itself (the fixture keeps both forms
    # valid for mutation testing).
    encode_source = method
    if "packToFile" not in encode_source:
        _require(source, "cacheCompressedPickedThreadImage", "OHOS native compression dispatch")
        encoder_end = (
            "private async applyOhosImageOrientation("
            if "private async applyOhosImageOrientation(" in source
            else "private cancelCompressThreadImage("
        )
        encode_source = _region(
            source,
            "private async cacheCompressedPickedThreadImage(",
            encoder_end,
            "OHOS native compression encoder",
        )
    _require_all(
        encode_source,
        ("packToFile", "format: 'image/jpeg'", "await fs.unlink"),
        "OHOS native compression encoder",
    )


def check_contract(sources: Mapping[str, str]) -> None:
    """Run every gate in a deterministic order."""

    check_shared_bridge(sources)
    check_shared_preview(sources)
    check_thread_routing(sources)
    _check_pending_journal_ownership(_source(sources, "thread"))
    _check_pending_journal_upload_propagation(_source(sources, "thread"))
    _check_video_priority(sources)
    _check_android_native(sources)
    _check_ios_native(sources)
    _check_ohos_native(sources)


def load_repo_sources(root: Path) -> dict[str, str]:
    sources: dict[str, str] = {}
    for name, relative in PATHS.items():
        path = root / relative
        try:
            sources[name] = path.read_text(encoding="utf-8")
        except OSError as error:
            raise Violation(f"{relative}: unreadable ({error})") from error
    return sources


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        from thread_image_compression_contract_selftest import self_test
        return self_test()
    if argv:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        check_contract(load_repo_sources(REPO_ROOT))
    except Violation as error:
        print(f"thread image compression contract: FAIL\n  {error}", file=sys.stderr)
        return 1
    print("thread image compression contract: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
