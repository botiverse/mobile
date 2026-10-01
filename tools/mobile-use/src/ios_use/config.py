from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from pathlib import Path


DEFAULT_BUNDLE = "build.raft.app"
DEFAULT_WORKSPACE = Path("compose/iosApp/iosApp.xcworkspace")
DEFAULT_SCHEME = "iosApp"
DEFAULT_CONFIGURATION = "Debug"
DEFAULT_SDK = "iphonesimulator"
DEFAULT_DERIVED_DATA = Path("/tmp/ios-use-derived-data")


@dataclass(frozen=True)
class IosConfig:
    xcrun: str = "xcrun"
    xcodebuild: str = "xcodebuild"
    device: str | None = None
    bundle: str = DEFAULT_BUNDLE
    workspace: Path | None = None
    scheme: str = DEFAULT_SCHEME
    configuration: str = DEFAULT_CONFIGURATION
    sdk: str = DEFAULT_SDK
    derived_data: Path = DEFAULT_DERIVED_DATA

    @classmethod
    def from_defaults(
        cls,
        *,
        xcrun: str | None = None,
        xcodebuild: str | None = None,
        device: str | None = None,
        bundle: str | None = None,
        workspace: str | Path | None = None,
        scheme: str | None = None,
        configuration: str | None = None,
        sdk: str | None = None,
        derived_data: str | Path | None = None,
    ) -> "IosConfig":
        raw_workspace = workspace or os.environ.get("IOS_USE_WORKSPACE")
        raw_derived_data = derived_data or os.environ.get("IOS_USE_DERIVED_DATA") or DEFAULT_DERIVED_DATA
        return cls(
            xcrun=xcrun or os.environ.get("XCRUN") or "xcrun",
            xcodebuild=xcodebuild or os.environ.get("XCODEBUILD") or "xcodebuild",
            device=device or os.environ.get("IOS_SIMULATOR_UDID"),
            bundle=bundle or os.environ.get("IOS_USE_BUNDLE") or DEFAULT_BUNDLE,
            workspace=Path(raw_workspace).expanduser() if raw_workspace else None,
            scheme=scheme or os.environ.get("IOS_USE_SCHEME") or DEFAULT_SCHEME,
            configuration=configuration or os.environ.get("IOS_USE_CONFIGURATION") or DEFAULT_CONFIGURATION,
            sdk=sdk or os.environ.get("IOS_USE_SDK") or DEFAULT_SDK,
            derived_data=Path(raw_derived_data).expanduser(),
        )

    def tool_status(self) -> dict[str, object]:
        xcrun_path = shutil.which(self.xcrun) if "/" not in self.xcrun else self.xcrun
        xcodebuild_path = shutil.which(self.xcodebuild) if "/" not in self.xcodebuild else self.xcodebuild
        from .agent_device import tool_status as agent_device_status

        return {
            "xcrun": {"path": xcrun_path or self.xcrun, "exists": bool(xcrun_path)},
            "xcodebuild": {"path": xcodebuild_path or self.xcodebuild, "exists": bool(xcodebuild_path)},
            "agent_device": agent_device_status(),
            "device": self.device,
            "bundle": self.bundle,
            "workspace": str(self.workspace) if self.workspace else None,
            "scheme": self.scheme,
            "configuration": self.configuration,
            "sdk": self.sdk,
            "derived_data": str(self.derived_data),
        }
