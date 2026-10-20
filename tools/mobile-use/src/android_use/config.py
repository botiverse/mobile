from __future__ import annotations

import os
import shutil
from dataclasses import dataclass


DEFAULT_PACKAGE = "build.raft.app"
DEFAULT_MAIN_COMPONENT = "build.raft.app/.MainActivity"
DEFAULT_KUIKLY_COMPONENT = "build.raft.app/.shared.RaftLegacyHostActivity"
DEFAULT_DIAGNOSTICS_ACTION = "build.raft.app.diagnostics.OPEN"
DEFAULT_DIAGNOSTICS_COMPONENT = "build.raft.app/.core.diagnostics.DiagnosticsDeepLinkActivity"


@dataclass(frozen=True)
class AndroidConfig:
    adb: str = "adb"
    device: str | None = None
    package: str = DEFAULT_PACKAGE
    main_component: str = DEFAULT_MAIN_COMPONENT
    kuikly_component: str = DEFAULT_KUIKLY_COMPONENT

    @classmethod
    def from_defaults(
        cls,
        *,
        adb: str | None = None,
        device: str | None = None,
        package: str | None = None,
        main_component: str | None = None,
        kuikly_component: str | None = None,
    ) -> "AndroidConfig":
        resolved_package = package or os.environ.get("ANDROID_USE_PACKAGE") or DEFAULT_PACKAGE
        return cls(
            adb=adb or os.environ.get("ADB") or "adb",
            device=device or os.environ.get("ANDROID_SERIAL"),
            package=resolved_package,
            main_component=main_component or os.environ.get("ANDROID_USE_MAIN_COMPONENT") or f"{resolved_package}/.MainActivity",
            kuikly_component=kuikly_component or os.environ.get("ANDROID_USE_KUIKLY_COMPONENT") or f"{resolved_package}/.shared.RaftLegacyHostActivity",
        )

    def tool_status(self) -> dict[str, object]:
        resolved = shutil.which(self.adb) if "/" not in self.adb else self.adb
        return {
            "adb": {
                "path": resolved or self.adb,
                "exists": bool(resolved),
            },
            "device": self.device,
            "package": self.package,
            "main_component": self.main_component,
            "kuikly_component": self.kuikly_component,
        }
