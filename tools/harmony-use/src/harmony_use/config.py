from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


DEFAULT_DEVECO_HOME = Path("/Applications/DevEco-Studio.app/Contents")
DEFAULT_BUNDLE = "build.raft.harmony"
DEFAULT_MODULE = "entry"
DEFAULT_ABILITY = "EntryAbility"
DEFAULT_PRODUCT = "default"
DEFAULT_DEVICE_TYPE = "phone"


@dataclass(frozen=True)
class HarmonyConfig:
    deveco_home: Path = DEFAULT_DEVECO_HOME
    sdk_home: Path = DEFAULT_DEVECO_HOME / "sdk"
    project: Path | None = None
    target: str | None = None

    @classmethod
    def from_defaults(
        cls,
        *,
        deveco_home: str | Path | None = None,
        sdk_home: str | Path | None = None,
        project: str | Path | None = None,
        target: str | None = None,
    ) -> "HarmonyConfig":
        resolved_deveco = Path(
            deveco_home
            or os.environ.get("DEVECO_HOME")
            or os.environ.get("SDK_HOME")
            or DEFAULT_DEVECO_HOME
        ).expanduser()
        resolved_sdk = Path(
            sdk_home
            or os.environ.get("DEVECO_SDK_HOME")
            or resolved_deveco / "sdk"
        ).expanduser()
        raw_project = project or os.environ.get("HARMONY_USE_PROJECT")
        return cls(
            deveco_home=resolved_deveco,
            sdk_home=resolved_sdk,
            project=Path(raw_project).expanduser() if raw_project else None,
            target=target or os.environ.get("OHOS_SERIAL"),
        )

    @property
    def hdc(self) -> Path:
        return Path(
            os.environ.get("HDC_BIN")
            or self.sdk_home / "default" / "openharmony" / "toolchains" / "hdc"
        )

    @property
    def hvigorw(self) -> Path:
        return self.deveco_home / "tools" / "hvigor" / "bin" / "hvigorw"

    @property
    def hvigorw_js(self) -> Path:
        return self.deveco_home / "tools" / "hvigor" / "bin" / "hvigorw.js"

    @property
    def node(self) -> Path:
        return self.deveco_home / "tools" / "node" / "bin" / "node"

    @property
    def ohpm(self) -> Path:
        return self.deveco_home / "tools" / "ohpm" / "bin" / "ohpm"

    @property
    def emulator(self) -> Path:
        return self.deveco_home / "tools" / "emulator" / "Emulator"

    @property
    def java_home(self) -> Path:
        return self.deveco_home / "jbr" / "Contents" / "Home"

    def env(self) -> dict[str, str]:
        env = dict(os.environ)
        path_entries = [
            self.sdk_home,
            self.java_home / "bin",
            self.deveco_home / "tools" / "node" / "bin",
            self.deveco_home / "tools" / "ohpm" / "bin",
            self.deveco_home / "tools" / "hvigor" / "bin",
            self.sdk_home / "default" / "openharmony" / "toolchains",
        ]
        env["DEVECO_SDK_HOME"] = str(self.sdk_home)
        env["PATH"] = os.pathsep.join(str(path) for path in path_entries) + os.pathsep + env.get("PATH", "")
        return env

    def tool_status(self) -> dict[str, dict[str, object]]:
        tools = {
            "deveco_home": self.deveco_home,
            "sdk_home": self.sdk_home,
            "hdc": self.hdc,
            "hvigorw": self.hvigorw,
            "node": self.node,
            "ohpm": self.ohpm,
            "emulator": self.emulator,
            "java_home": self.java_home,
        }
        return {
            name: {
                "path": str(path),
                "exists": path.exists(),
            }
            for name, path in tools.items()
        }

    def export_lines(self) -> list[str]:
        return [
            f"export DEVECO_SDK_HOME={self.sdk_home}",
            "export PATH="
            + ":".join(
                [
                    str(self.sdk_home),
                    str(self.java_home / "bin"),
                    str(self.deveco_home / "tools" / "node" / "bin"),
                    str(self.deveco_home / "tools" / "ohpm" / "bin"),
                    str(self.deveco_home / "tools" / "hvigor" / "bin"),
                    str(self.sdk_home / "default" / "openharmony" / "toolchains"),
                    "$PATH",
                ]
            ),
            f"export HDC_BIN={self.hdc}",
            f"export HARMONY_USE_PROJECT={self.project}" if self.project else "# export HARMONY_USE_PROJECT=/path/to/compose/ohosApp",
        ]
