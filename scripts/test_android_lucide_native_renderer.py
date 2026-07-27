import os
import re
import unittest


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ANDROID_PRODUCTION_ROOT = os.path.join(
    REPO_ROOT,
    "compose",
    "androidApp",
    "src",
    "main",
    "java",
)
RENDERER_PATH = os.path.join(
    ANDROID_PRODUCTION_ROOT,
    "build",
    "raft",
    "app",
    "shared",
    "SlockLucideIconRenderView.kt",
)
GENERATOR_PATH = os.path.join(REPO_ROOT, "scripts", "generate_lucide_icons.py")


class AndroidLucideNativeRendererTest(unittest.TestCase):
    def test_android_production_has_no_compose_vector_icon_runtime(self):
        forbidden = {
            "ComposeView": re.compile(r"\bComposeView\b"),
            "ImageVector": re.compile(r"\bImageVector\b"),
            "LucideIcons": re.compile(r"\bLucideIcons\b"),
            "Compose vector package": re.compile(r"androidx\.compose\.ui\.graphics\.vector"),
        }
        violations = []
        for root, _, filenames in os.walk(ANDROID_PRODUCTION_ROOT):
            for filename in filenames:
                if not filename.endswith(".kt"):
                    continue
                path = os.path.join(root, filename)
                with open(path, encoding="utf-8") as file:
                    source = file.read()
                for label, pattern in forbidden.items():
                    if pattern.search(source):
                        violations.append(f"{label}: {os.path.relpath(path, REPO_ROOT)}")
        self.assertEqual([], violations)

    def test_renderer_is_one_cached_native_canvas_path(self):
        with open(RENDERER_PATH, encoding="utf-8") as file:
            source = file.read()
        self.assertIn("class SlockLucideCanvasView(context: Context) : View(context)", source)
        self.assertIn("override fun onDraw(canvas: Canvas)", source)
        self.assertIn("private object SlockLucidePathCache", source)
        self.assertIn("PathParser.createPathFromPathData", source)
        on_draw = source.split("override fun onDraw(canvas: Canvas)", 1)[1].split(
            "override fun onAttachedToWindow", 1
        )[0]
        self.assertNotIn("PathParser", on_draw)
        self.assertNotIn("Path()", on_draw)
        self.assertNotIn("Color.parseColor", on_draw)
        self.assertNotIn("compileLucidePaint", on_draw)
        self.assertNotIn("parseLucideDirectPaintColor", on_draw)
        self.assertNotIn("buildString", on_draw)

    def test_renderer_preserves_props_and_animation_lifecycle(self):
        with open(RENDERER_PATH, encoding="utf-8") as file:
            source = file.read()
        for prop in (
            "name",
            "color",
            "fill",
            "strokeWidth",
            "verticalOffsetDp",
            "spinning",
            "rotationDegrees",
        ):
            self.assertIn(f'"{prop}"', source)
        for callback in (
            "onAttachedToWindow",
            "onDetachedFromWindow",
            "onVisibilityChanged",
            "onWindowVisibilityChanged",
        ):
            self.assertIn(callback, source)
        self.assertIn("private const val SPIN_DURATION_MS = 900L", source)

    def test_default_generation_cannot_recreate_image_vectors(self):
        with open(GENERATOR_PATH, encoding="utf-8") as file:
            source = file.read()
        default_targets = re.search(r'^DEFAULT_TARGETS = "([^"]+)"$', source, re.MULTILINE)
        self.assertIsNotNone(default_targets)
        targets = set(default_targets.group(1).split(","))
        self.assertIn("android-path", targets)
        self.assertNotIn("android", targets)


if __name__ == "__main__":
    unittest.main()
