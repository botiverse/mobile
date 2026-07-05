from __future__ import annotations

from pathlib import Path

from .config import DEFAULT_DEVICE_TYPE, DEFAULT_MODULE, DEFAULT_PRODUCT, HarmonyConfig
from .runner import CommandResult, HarmonyUseError, run_command


def run_ohpm_install(config: HarmonyConfig, *, project: Path | None = None) -> CommandResult:
    root = resolve_project(config, project)
    return run_command([config.ohpm, "install", "--all"], cwd=root, env=config.env())


def run_hvigor_sync(config: HarmonyConfig, *, project: Path | None = None) -> CommandResult:
    root = resolve_project(config, project)
    return run_command(
        [config.node, config.hvigorw_js, "--sync", "-p", "product=default", "--analyze=normal", "--parallel"],
        cwd=root,
        env=config.env(),
    )


def assemble_hap(
    config: HarmonyConfig,
    *,
    project: Path | None = None,
    module: str = DEFAULT_MODULE,
    product: str = DEFAULT_PRODUCT,
    required_device_type: str = DEFAULT_DEVICE_TYPE,
) -> CommandResult:
    root = resolve_project(config, project)
    return run_command(
        [
            config.node,
            config.hvigorw_js,
            "--mode",
            "module",
            "-p",
            f"module={module}@{product}",
            "-p",
            f"product={product}",
            "-p",
            f"requiredDeviceType={required_device_type}",
            "assembleHap",
            "--analyze=normal",
            "--parallel",
        ],
        cwd=root,
        env=config.env(),
    )


def default_hap_path(
    config: HarmonyConfig,
    *,
    project: Path | None = None,
    module: str = DEFAULT_MODULE,
    product: str = DEFAULT_PRODUCT,
    signed: bool = True,
) -> Path:
    root = resolve_project(config, project)
    suffix = "signed" if signed else "unsigned"
    return root / module / "build" / product / "outputs" / product / f"{module}-{product}-{suffix}.hap"


def resolve_project(config: HarmonyConfig, project: Path | None = None) -> Path:
    root = project or config.project
    if root is None:
        raise HarmonyUseError("OHOS project path is required; pass --project or set HARMONY_USE_PROJECT")
    root = root.expanduser().resolve()
    if not root.exists():
        raise HarmonyUseError(f"OHOS project does not exist: {root}")
    return root
