"""Paired interleaved A/B verdict engine and sensitivity calibration checker.

Pure Python stdlib statistical evaluator:
- Enforces strict cohort, scenario, ``ab_session_id``, ``run_kind``,
  ``calibration_injected_ms``, ``ab_arm``, and unique ``(ab_arm, iteration)``
  invariants (``invalid_cohort`` on any violation).
- Flags/filters thermally throttled runs (either ``thermal_start`` or
  ``thermal_end`` in severe/critical/emergency/shutdown/serious, or thermal
  tier jump >= 2) and explicitly reports unverified (missing/unknown) thermal
  telemetry.
- Computes median, IQR, MAD, paired deltas, paired median delta, relative
  delta %, and deterministic exact paired sign-flip p-values (default
  ``min_pairs=6``).
- Classifies verdicts with an explicit noise-floor rule:
  ``improved``, ``regressed``, ``neutral``, ``noisy``, ``insufficient_samples``,
  or ``invalid_cohort``.
"""

from __future__ import annotations

import itertools
import math
import random
from collections.abc import Mapping, Sequence
from typing import Any

from .scenarios import SCENARIOS, VALID_CALIBRATION_MODES, get_calibration_config, get_primary_kpi

THERMAL_THROTTLED_STATES: frozenset[str] = frozenset(
    {"severe", "critical", "emergency", "shutdown", "serious"}
)

# Unified monotonic thermal tiers per OS family.
_ANDROID_THERMAL_TIERS: dict[str, int] = {
    "none": 0,
    "light": 1,
    "moderate": 2,
    "severe": 3,
    "critical": 4,
    "emergency": 5,
    "shutdown": 6,
}

_IOS_THERMAL_TIERS: dict[str, int] = {
    "nominal": 0,
    "fair": 1,
    "serious": 2,
    "critical": 3,
}

_THERMAL_STATUS_PREFIX = "thermal_status_"


def _normalize_thermal_state(state: str | None) -> str | None:
    """Normalize thermal string (case-insensitive, stripping optional THERMAL_STATUS_ prefix)."""
    if not isinstance(state, str):
        return None
    norm = state.strip().lower()
    if not norm:
        return None
    if norm.startswith(_THERMAL_STATUS_PREFIX):
        norm = norm[len(_THERMAL_STATUS_PREFIX) :]
    return norm or None


def _thermal_tier(state: str | None) -> int | None:
    norm = _normalize_thermal_state(state)
    if norm is None:
        return None
    if norm in _ANDROID_THERMAL_TIERS:
        return _ANDROID_THERMAL_TIERS[norm]
    if norm in _IOS_THERMAL_TIERS:
        return _IOS_THERMAL_TIERS[norm]
    return None


def is_thermal_throttled(
    thermal_start: str | None,
    thermal_end: str | None,
) -> tuple[bool, str | None]:
    """Return ``(throttled, reason)`` for a run's start/end thermal states."""
    start_norm = _normalize_thermal_state(thermal_start)
    end_norm = _normalize_thermal_state(thermal_end)

    start_hot = start_norm in THERMAL_THROTTLED_STATES
    end_hot = end_norm in THERMAL_THROTTLED_STATES
    if start_hot and end_hot:
        return True, f"thermal_start={start_norm},thermal_end={end_norm}"
    if end_hot:
        return True, f"thermal_end={end_norm}"
    if start_hot:
        return True, f"thermal_start={start_norm}"

    t_start = _thermal_tier(start_norm)
    t_end = _thermal_tier(end_norm)
    if t_start is not None and t_end is not None and (t_end - t_start) >= 2:
        return True, f"thermal_tier_jump={start_norm}->{end_norm} (+{t_end - t_start})"

    return False, None


def classify_thermal_status(
    thermal_start: str | None,
    thermal_end: str | None,
) -> tuple[str, str | None]:
    """Classify thermal telemetry into ``("throttled" | "unverified" | "ok", reason)``.

    - ``("throttled", reason)`` when throttled at start, at end, or tier jump >= 2.
    - ``("unverified", reason)`` when either ``thermal_start`` or ``thermal_end``
      is missing/null/empty or unrecognized after normalization.
    - ``("ok", None)`` only when both ``thermal_start`` and ``thermal_end`` are
      recognized non-throttled states with tier jump < 2.
    """
    throttled, throttle_reason = is_thermal_throttled(thermal_start, thermal_end)
    if throttled:
        return "throttled", throttle_reason

    issues: list[str] = []
    start_norm = _normalize_thermal_state(thermal_start)
    end_norm = _normalize_thermal_state(thermal_end)

    if start_norm is None:
        issues.append("thermal_start=missing")
    elif _thermal_tier(start_norm) is None:
        issues.append(f"thermal_start=unknown({thermal_start!r})")

    if end_norm is None:
        issues.append("thermal_end=missing")
    elif _thermal_tier(end_norm) is None:
        issues.append(f"thermal_end=unknown({thermal_end!r})")

    if issues:
        return "unverified", ",".join(issues)

    return "ok", None


# ---------------------------------------------------------------------------
# Robust summary statistics & deterministic permutation tests
# ---------------------------------------------------------------------------


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_finite_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(float(value))


def _quantile(sorted_vals: Sequence[float], q: float) -> float:
    if not sorted_vals:
        raise ValueError("Cannot compute quantile of empty sequence")
    if len(sorted_vals) == 1:
        return float(sorted_vals[0])
    pos = (len(sorted_vals) - 1) * q
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return float(sorted_vals[lo])
    return float(sorted_vals[lo] + (sorted_vals[hi] - sorted_vals[lo]) * (pos - lo))


def _median(vals: Sequence[float]) -> float:
    return _quantile(sorted(vals), 0.5)


def _iqr(vals: Sequence[float]) -> float:
    s = sorted(vals)
    return _quantile(s, 0.75) - _quantile(s, 0.25)


def _mad(vals: Sequence[float]) -> float:
    med = _median(vals)
    return _median([abs(v - med) for v in vals])


def _exact_paired_sign_flip_p_value(deltas: Sequence[float]) -> tuple[float, str]:
    """Two-sided deterministic paired sign-flip permutation p-value.

    Exact over all ``2**n`` sign assignments for ``n <= 16``, and deterministic
    fixed-seed Monte Carlo (10,000 permutations) for ``n > 16``.
    """
    n = len(deltas)
    if n == 0:
        return 1.0, "empty"
    obs = abs(sum(deltas))
    if obs <= 1e-12:
        return 1.0, "exact_paired_sign_flip"

    if n <= 16:
        total = 1 << n
        extreme = 0
        for signs in itertools.product((-1, 1), repeat=n):
            stat = abs(sum(s * d for s, d in zip(signs, deltas)))
            if stat >= obs - 1e-12:
                extreme += 1
        return extreme / total, "exact_paired_sign_flip"

    rng = random.Random(0x5EED1234)
    draws = 10_000
    extreme = 0
    for _ in range(draws):
        stat = abs(sum((d if rng.getrandbits(1) else -d) for d in deltas))
        if stat >= obs - 1e-12:
            extreme += 1
    return (extreme + 1) / (draws + 1), "mc_paired_sign_flip_seeded"


def _exact_two_sample_permutation_p_value(
    base_vals: Sequence[float],
    head_vals: Sequence[float],
) -> tuple[float, str]:
    """Two-sided deterministic two-sample permutation p-value (exact for small n)."""
    n_b = len(base_vals)
    n_h = len(head_vals)
    if n_b == 0 or n_h == 0:
        return 1.0, "empty"
    pooled = list(base_vals) + list(head_vals)
    total_sum = sum(pooled)
    obs = abs((sum(head_vals) / n_h) - (sum(base_vals) / n_b))
    if obs <= 1e-12:
        return 1.0, "exact_two_sample_permutation"

    n_total = n_b + n_h
    comb_count = math.comb(n_total, n_b)
    if comb_count <= 50_000:
        extreme = 0
        for idxs in itertools.combinations(range(n_total), n_b):
            sum_b = sum(pooled[i] for i in idxs)
            sum_h = total_sum - sum_b
            stat = abs((sum_h / n_h) - (sum_b / n_b))
            if stat >= obs - 1e-12:
                extreme += 1
        return extreme / comb_count, "exact_two_sample_permutation"

    rng = random.Random(0x5EED5678)
    draws = 10_000
    extreme = 0
    indices = list(range(n_total))
    for _ in range(draws):
        rng.shuffle(indices)
        sum_b = sum(pooled[i] for i in indices[:n_b])
        sum_h = total_sum - sum_b
        stat = abs((sum_h / n_h) - (sum_b / n_b))
        if stat >= obs - 1e-12:
            extreme += 1
    return (extreme + 1) / (draws + 1), "mc_two_sample_permutation_seeded"


def _default_abs_floor(metric: str) -> float:
    if metric.endswith("_ratio"):
        return 0.01
    if metric.endswith("_mb"):
        return 5.0
    if metric.endswith("_count") or metric.endswith("_dp"):
        return 1.0
    return 10.0


def _resolve_metric(rows: Sequence[Mapping[str, Any]], primary_metric: str | None) -> str:
    if primary_metric:
        return primary_metric
    scenarios = {r.get("scenario") for r in rows if r.get("scenario")}
    projects = {r.get("project") for r in rows if r.get("project")}
    if len(scenarios) == 1:
        sc = next(iter(scenarios))
        proj = next(iter(projects)) if len(projects) == 1 else None
        if sc in SCENARIOS:
            return get_primary_kpi(sc, project=proj)
    return "duration_ms"


def _resolve_calibration_mode_and_metric(
    rows: Sequence[Mapping[str, Any]],
    *,
    mode: str | None,
    primary_metric: str | None,
) -> tuple[str, str]:
    if mode is not None and mode not in VALID_CALIBRATION_MODES:
        raise ValueError(
            f"Unknown calibration mode {mode!r}; expected one of {sorted(VALID_CALIBRATION_MODES)}"
        )
    scenarios_seen = {r.get("scenario") for r in rows if r.get("scenario")}
    projects_seen = {r.get("project") for r in rows if r.get("project")}
    proj = next(iter(projects_seen)) if len(projects_seen) == 1 else None
    sc = next(iter(scenarios_seen)) if len(scenarios_seen) == 1 else None

    if sc in SCENARIOS:
        default_mode, default_metric = get_calibration_config(sc, project=proj)
    else:
        default_mode, default_metric = "latency_ms", "duration_ms"

    if mode is not None:
        resolved_mode = mode
    elif primary_metric in ("slow_frame_ratio", "hitch_ms_per_s"):
        resolved_mode = "slow_frames"
    elif primary_metric in ("duration_ms", "ttfd_ms"):
        resolved_mode = "latency_ms"
    else:
        resolved_mode = default_mode

    if primary_metric is not None:
        resolved_metric = primary_metric
    elif resolved_mode == default_mode:
        resolved_metric = default_metric
    elif resolved_mode == "slow_frames":
        resolved_metric = "hitch_ms_per_s" if proj == "raft-ios" else "slow_frame_ratio"
    else:
        resolved_metric = "ttfd_ms" if sc == "cold_start" else "duration_ms"

    return resolved_mode, resolved_metric


# ---------------------------------------------------------------------------
# Public API: paired A/B verdict evaluator
# ---------------------------------------------------------------------------


def evaluate_ab(
    rows: Sequence[Mapping[str, Any]],
    primary_metric: str | None = None,
    *,
    min_pairs: int = 6,
    alpha: float = 0.05,
    rel_floor: float = 0.05,
    noise_multiplier: float = 2.0,
    abs_floor_ms: float | None = None,
    max_noise_ratio: float = 0.15,
    lower_is_better: bool = True,
    filter_thermal: bool = True,
) -> dict[str, Any]:
    """Evaluate paired interleaved A/B runs and emit a structured verdict dict."""
    if min_pairs < 1:
        raise ValueError(f"min_pairs must be >= 1, got {min_pairs!r}")
    if not rows:
        return {
            "verdict": "insufficient_samples",
            "primary_metric": primary_metric,
            "cohort_key": None,
            "scenario": None,
            "ab_session_id": None,
            "paired_count": 0,
            "min_pairs": min_pairs,
            "unrecognized_arm_count": 0,
            "unrecognized_arms": [],
            "thermal_flags": [],
            "thermal_unverified_count": 0,
            "thermal_unverified_runs": [],
            "reasons": ["no rows supplied"],
        }

    # 1. Strict cohort, scenario, session, run_kind, calibration, arm, and uniqueness gates.
    cohort_keys = sorted({str(r.get("cohort_key") or "") for r in rows})
    scenarios_seen = sorted({str(r.get("scenario") or "") for r in rows})
    metric = _resolve_metric(rows, primary_metric)
    reasons: list[str] = []

    if len(cohort_keys) != 1 or not cohort_keys[0]:
        reasons.append(f"cohort_key mismatch across rows: {cohort_keys}")
    if len(scenarios_seen) != 1 or not scenarios_seen[0]:
        reasons.append(f"scenario mismatch across rows: {scenarios_seen}")

    # Single non-empty ab_session_id across all rows.
    session_ids_raw = [r.get("ab_session_id") for r in rows]
    missing_session_count = sum(
        1 for s in session_ids_raw if not isinstance(s, str) or not s.strip()
    )
    nonempty_sessions = sorted(
        {s.strip() for s in session_ids_raw if isinstance(s, str) and s.strip()}
    )
    if missing_session_count > 0:
        reasons.append(f"missing or empty ab_session_id on {missing_session_count} row(s)")
    if len(nonempty_sessions) > 1:
        reasons.append(f"ab_session_id mismatch across rows: {nonempty_sessions}")

    # Strict run_kind == "ab" and calibration_injected_ms not > 0.
    bad_run_kinds = [r.get("run_kind") for r in rows if r.get("run_kind") != "ab"]
    if bad_run_kinds:
        unique_bad_rk = sorted({repr(rk) if rk is None else str(rk) for rk in bad_run_kinds})
        reasons.append(
            f"invalid run_kind for A/B evaluation on {len(bad_run_kinds)} row(s) "
            f"(expected 'ab'): {unique_bad_rk}"
        )

    injected_cal_vals = [
        float(r["calibration_injected_ms"])
        for r in rows
        if _is_finite_number(r.get("calibration_injected_ms"))
        and float(r["calibration_injected_ms"]) > 0
    ]
    if injected_cal_vals:
        reasons.append(
            f"calibration_injected_ms > 0 is forbidden in A/B evaluation "
            f"({len(injected_cal_vals)} row(s): {sorted(set(injected_cal_vals))})"
        )

    # Unrecognized ab_arm values (never silently skip).
    unrecognized_arms_raw = [r.get("ab_arm") for r in rows if r.get("ab_arm") not in ("base", "head")]
    unrecognized_arm_count = len(unrecognized_arms_raw)
    unrecognized_arms: list[Any] = []
    for arm_val in unrecognized_arms_raw:
        if arm_val not in unrecognized_arms:
            unrecognized_arms.append(arm_val)
    if unrecognized_arm_count > 0:
        reasons.append(
            f"unrecognized ab_arm on {unrecognized_arm_count} row(s) "
            f"(expected 'base' or 'head'): {unrecognized_arms}"
        )

    # Duplicate (ab_arm, iteration) entries across ALL rows (valid or invalid).
    arm_iter_counts: dict[tuple[str, int], int] = {}
    for row in rows:
        arm = row.get("ab_arm")
        it = row.get("iteration")
        if arm in ("base", "head") and _is_int(it):
            key = (arm, it)
            arm_iter_counts[key] = arm_iter_counts.get(key, 0) + 1
    duplicate_arm_iters = sorted(k for k, cnt in arm_iter_counts.items() if cnt > 1)
    if duplicate_arm_iters:
        dup_desc = ", ".join(
            f"{arm}#{it} (x{arm_iter_counts[(arm, it)]})" for arm, it in duplicate_arm_iters
        )
        reasons.append(f"duplicate (ab_arm, iteration) entries detected: {dup_desc}")

    if reasons:
        return {
            "verdict": "invalid_cohort",
            "primary_metric": metric,
            "cohort_keys": cohort_keys,
            "scenarios": scenarios_seen,
            "cohort_key": cohort_keys[0] if len(cohort_keys) == 1 and cohort_keys[0] else None,
            "scenario": scenarios_seen[0] if len(scenarios_seen) == 1 and scenarios_seen[0] else None,
            "ab_session_id": (
                nonempty_sessions[0]
                if len(nonempty_sessions) == 1 and missing_session_count == 0
                else None
            ),
            "ab_session_ids": nonempty_sessions,
            "unrecognized_arm_count": unrecognized_arm_count,
            "unrecognized_arms": unrecognized_arms,
            "duplicate_arm_iterations": [
                {"ab_arm": arm, "iteration": it, "count": arm_iter_counts[(arm, it)]}
                for arm, it in duplicate_arm_iters
            ],
            "paired_count": 0,
            "min_pairs": min_pairs,
            "thermal_flags": [],
            "thermal_unverified_count": 0,
            "thermal_unverified_runs": [],
            "reasons": reasons,
        }

    cohort_key = cohort_keys[0]
    scenario = scenarios_seen[0]
    ab_session_id = nonempty_sessions[0]
    eff_abs_floor = _default_abs_floor(metric) if abs_floor_ms is None else float(abs_floor_ms)

    # 2. Filter invalid rows, missing metrics, and thermal throttling; record unverified thermal.
    base_by_iter: dict[int, float] = {}
    head_by_iter: dict[int, float] = {}
    invalid_excluded = 0
    thermal_excluded = 0
    thermal_flags: list[dict[str, Any]] = []
    thermal_unverified_runs: list[dict[str, Any]] = []

    for row in rows:
        arm = row["ab_arm"]
        iteration = row.get("iteration")
        valid = row.get("valid")
        samples = row.get("samples")
        val = row.get(metric)

        thermal_status, thermal_reason = classify_thermal_status(
            row.get("thermal_start"), row.get("thermal_end")
        )
        has_valid_sample_and_metric = (
            _is_int(samples)
            and samples > 0
            and _is_int(iteration)
            and _is_finite_number(val)
        )

        if thermal_status == "throttled" and has_valid_sample_and_metric:
            thermal_flags.append(
                {
                    "run_id": row.get("run_id"),
                    "ab_arm": arm,
                    "iteration": iteration,
                    "thermal_start": row.get("thermal_start"),
                    "thermal_end": row.get("thermal_end"),
                    "reason": thermal_reason,
                }
            )
            if filter_thermal or valid is not True:
                thermal_excluded += 1
                continue

        if valid is not True or not has_valid_sample_and_metric:
            invalid_excluded += 1
            continue

        if thermal_status == "unverified":
            thermal_unverified_runs.append(
                {
                    "run_id": row.get("run_id"),
                    "ab_arm": arm,
                    "iteration": iteration,
                    "thermal_start": row.get("thermal_start"),
                    "thermal_end": row.get("thermal_end"),
                    "reason": thermal_reason,
                }
            )

        target = base_by_iter if arm == "base" else head_by_iter
        target[iteration] = float(val)

    if invalid_excluded:
        reasons.append(f"excluded {invalid_excluded} invalid or missing-metric row(s)")
    if thermal_flags:
        action = "excluded" if filter_thermal else "flagged"
        reasons.append(
            f"{action} {len(thermal_flags)} thermally throttled run(s): "
            + ", ".join(f"{f['ab_arm']}#{f['iteration']}({f['reason']})" for f in thermal_flags)
        )
    if thermal_unverified_runs:
        reasons.append(
            f"WARNING: {len(thermal_unverified_runs)} run(s) have unverified thermal telemetry "
            f"(missing or unrecognized thermal_start/thermal_end): "
            + ", ".join(
                f"{u['ab_arm']}#{u['iteration']}({u['reason']})" for u in thermal_unverified_runs
            )
        )

    # 3. Pair base & head by exact iteration index.
    paired_iters = sorted(set(base_by_iter.keys()) & set(head_by_iter.keys()))
    base_vals = [base_by_iter[i] for i in paired_iters]
    head_vals = [head_by_iter[i] for i in paired_iters]
    deltas = [h - b for b, h in zip(base_vals, head_vals)]

    if len(paired_iters) < min_pairs:
        reasons.append(
            f"only {len(paired_iters)} valid paired iteration(s) available (requires >= {min_pairs})"
        )
        return {
            "verdict": "insufficient_samples",
            "primary_metric": metric,
            "cohort_key": cohort_key,
            "scenario": scenario,
            "ab_session_id": ab_session_id,
            "paired_count": len(paired_iters),
            "min_pairs": min_pairs,
            "unrecognized_arm_count": 0,
            "unrecognized_arms": [],
            "invalid_excluded_count": invalid_excluded,
            "thermal_excluded_count": thermal_excluded,
            "thermal_flags": thermal_flags,
            "thermal_unverified_count": len(thermal_unverified_runs),
            "thermal_unverified_runs": thermal_unverified_runs,
            "reasons": reasons,
        }

    # 4. Robust statistics & deterministic paired sign-flip p-value.
    base_med = _median(base_vals)
    head_med = _median(head_vals)
    base_iqr = _iqr(base_vals)
    head_iqr = _iqr(head_vals)
    base_mad = _mad(base_vals)
    head_mad = _mad(head_vals)
    pooled_mad = math.sqrt((base_mad**2 + head_mad**2) / 2.0)

    median_delta = head_med - base_med
    paired_median_delta = _median(deltas)
    delta_mad = _mad(deltas)
    rel_delta_pct = (paired_median_delta / base_med * 100.0) if abs(base_med) > 1e-12 else None

    p_value, p_method = _exact_paired_sign_flip_p_value(deltas)

    rel_threshold = rel_floor * abs(base_med)
    noise_threshold = noise_multiplier * pooled_mad
    practical_floor = max(rel_threshold, eff_abs_floor)
    noise_floor = max(practical_floor, noise_threshold)

    abs_delta = abs(paired_median_delta)
    significant = p_value <= alpha + 1e-12
    exceeds_noise_floor = abs_delta > noise_floor
    relative_noise = (pooled_mad / abs(base_med)) if abs(base_med) > 1e-12 else 0.0

    if significant and exceeds_noise_floor:
        if lower_is_better:
            verdict = "improved" if paired_median_delta < 0 else "regressed"
        else:
            verdict = "improved" if paired_median_delta > 0 else "regressed"
        reasons.append(
            f"{metric} paired median delta {paired_median_delta:+.3f} ({rel_delta_pct:+.2f}%) "
            f"exceeds noise floor {noise_floor:.3f} with p={p_value:.5f}"
            if rel_delta_pct is not None
            else f"{metric} paired median delta {paired_median_delta:+.3f} exceeds noise floor "
            f"{noise_floor:.3f} with p={p_value:.5f}"
        )
    else:
        # Distinguish noisy from neutral: high dispersion or an unverified large shift => noisy.
        if relative_noise > max_noise_ratio or abs_delta > practical_floor:
            verdict = "noisy"
            if relative_noise > max_noise_ratio:
                reasons.append(
                    f"excessive dispersion: pooled MAD/median={relative_noise:.3f} > {max_noise_ratio:.3f}"
                )
            if abs_delta > practical_floor:
                reasons.append(
                    f"observed paired median delta {paired_median_delta:+.3f} exceeds practical floor "
                    f"{practical_floor:.3f} but failed gate (p={p_value:.5f}, noise_floor={noise_floor:.3f})"
                )
        else:
            verdict = "neutral"
            reasons.append(
                f"paired median delta {paired_median_delta:+.3f} within noise floor "
                f"{noise_floor:.3f} with controlled variance "
                f"(p={p_value:.5f}, pooled_mad={pooled_mad:.3f})"
            )

    return {
        "verdict": verdict,
        "primary_metric": metric,
        "cohort_key": cohort_key,
        "scenario": scenario,
        "ab_session_id": ab_session_id,
        "paired_count": len(paired_iters),
        "paired_iterations": paired_iters,
        "min_pairs": min_pairs,
        "unrecognized_arm_count": 0,
        "unrecognized_arms": [],
        "invalid_excluded_count": invalid_excluded,
        "thermal_excluded_count": thermal_excluded,
        "thermal_flags": thermal_flags,
        "thermal_unverified_count": len(thermal_unverified_runs),
        "thermal_unverified_runs": thermal_unverified_runs,
        "base_median": round(base_med, 4),
        "head_median": round(head_med, 4),
        "base_iqr": round(base_iqr, 4),
        "head_iqr": round(head_iqr, 4),
        "base_mad": round(base_mad, 4),
        "head_mad": round(head_mad, 4),
        "pooled_mad": round(pooled_mad, 4),
        "median_delta": round(median_delta, 4),
        "paired_median_delta": round(paired_median_delta, 4),
        "delta_mad": round(delta_mad, 4),
        "paired_deltas": [round(d, 4) for d in deltas],
        "relative_delta_pct": round(rel_delta_pct, 3) if rel_delta_pct is not None else None,
        "p_value": round(p_value, 6),
        "p_value_method": p_method,
        "alpha": alpha,
        "noise_floor": round(noise_floor, 4),
        "noise_floor_components": {
            "rel_floor_value": round(rel_threshold, 4),
            "mad_floor_value": round(noise_threshold, 4),
            "abs_floor_value": round(eff_abs_floor, 4),
        },
        "reasons": reasons,
    }


# ---------------------------------------------------------------------------
# Public API: per-scenario sensitivity calibration checker
# ---------------------------------------------------------------------------


def _infer_calibration_window_ms(
    rows: Sequence[Mapping[str, Any]],
    explicit_window_ms: float | None,
) -> float | None:
    if explicit_window_ms is not None:
        if not _is_finite_number(explicit_window_ms) or float(explicit_window_ms) <= 0:
            raise ValueError(f"window_ms must be > 0, got {explicit_window_ms!r}")
        return float(explicit_window_ms)
    found: list[float] = []
    for r in rows:
        candidates = [r.get("window_ms")]
        extra = r.get("extra")
        if isinstance(extra, Mapping):
            candidates.append(extra.get("window_ms"))
        segs = r.get("segments")
        if isinstance(segs, Mapping):
            candidates.append(segs.get("window_ms"))
        for cand in candidates:
            if _is_finite_number(cand) and float(cand) > 0:
                found.append(float(cand))
                break
    return _median(found) if found else None


def evaluate_calibration(
    base_rows: Sequence[Mapping[str, Any]],
    injected_rows: Sequence[Mapping[str, Any]],
    *,
    primary_metric: str | None = None,
    mode: str | None = None,
    injected_ms: float = 200.0,
    expected_delta: float | None = None,
    injected_slow_frames: int | None = None,
    window_ms: float | None = None,
    tolerance_ratio: float = 0.25,
    min_pairs: int = 6,
    alpha: float = 0.05,
    filter_thermal: bool = True,
) -> dict[str, Any]:
    """Verify harness sensitivity against synthetic latency or slow-frame injection.

    Supports two calibration modes:
    - ``latency_ms`` (default for the 6 latency scenarios): checks that the
      observed paired median delta falls inside
      ``[injected_ms * (1 - tolerance_ratio), injected_ms * (1 + tolerance_ratio)]``
      with ``p <= alpha``.
    - ``slow_frames`` (default for ``thread_scroll_ime``): checks either
      ``slow_frame_ratio`` (via ``expected_delta`` or
      ``injected_slow_frames / median(samples)``) or ``hitch_ms_per_s`` (via
      ``expected_delta`` or ``injected_slow_frames * injected_ms / window_s``)
      within ``±tolerance_ratio`` with ``p <= alpha``.
    """
    if min_pairs < 1:
        raise ValueError(f"min_pairs must be >= 1, got {min_pairs!r}")
    if not _is_finite_number(injected_ms) or float(injected_ms) <= 0:
        raise ValueError(f"injected_ms must be > 0, got {injected_ms!r}")
    if not _is_finite_number(tolerance_ratio) or not (0.0 < float(tolerance_ratio) < 1.0):
        raise ValueError(f"tolerance_ratio must be in (0, 1), got {tolerance_ratio!r}")
    if expected_delta is not None and (not _is_finite_number(expected_delta) or float(expected_delta) <= 0):
        raise ValueError(f"expected_delta must be > 0, got {expected_delta!r}")
    if injected_slow_frames is not None and (not _is_int(injected_slow_frames) or injected_slow_frames <= 0):
        raise ValueError(f"injected_slow_frames must be a positive integer, got {injected_slow_frames!r}")

    all_rows = list(base_rows) + list(injected_rows)
    if not base_rows or not injected_rows:
        return {
            "verdict": "insufficient_samples",
            "passed": False,
            "calibration_mode": mode or "latency_ms",
            "primary_metric": primary_metric,
            "injected_ms": float(injected_ms),
            "injected_slow_frames": injected_slow_frames,
            "expected_min_ms": round(injected_ms * (1.0 - tolerance_ratio), 3)
            if (mode or "latency_ms") == "latency_ms"
            else None,
            "expected_max_ms": round(injected_ms * (1.0 + tolerance_ratio), 3)
            if (mode or "latency_ms") == "latency_ms"
            else None,
            "reasons": ["both base_rows and injected_rows are required for calibration"],
            "thermal_flags": [],
            "thermal_unverified_count": 0,
            "thermal_unverified_runs": [],
        }

    cohort_keys = sorted({str(r.get("cohort_key") or "") for r in all_rows})
    scenarios_seen = sorted({str(r.get("scenario") or "") for r in all_rows})
    resolved_mode, metric = _resolve_calibration_mode_and_metric(
        all_rows, mode=mode, primary_metric=primary_metric
    )

    cohort_errors: list[str] = []
    if len(cohort_keys) != 1 or not cohort_keys[0]:
        cohort_errors.append(f"cohort_key mismatch across calibration rows: {cohort_keys}")
    if len(scenarios_seen) != 1 or not scenarios_seen[0]:
        cohort_errors.append(f"scenario mismatch across calibration rows: {scenarios_seen}")

    # Strict calibration metadata gate BEFORE stats.
    bad_base_rk = [r.get("run_kind") for r in base_rows if r.get("run_kind") != "calibration"]
    if bad_base_rk:
        cohort_errors.append(
            f"base_rows must all have run_kind='calibration' "
            f"(found {len(bad_base_rk)} non-calibration row(s): "
            f"{sorted({repr(x) if x is None else str(x) for x in bad_base_rk})})"
        )

    bad_base_cal = [
        r.get("calibration_injected_ms")
        for r in base_rows
        if not (
            r.get("calibration_injected_ms") is None
            or (
                _is_finite_number(r.get("calibration_injected_ms"))
                and float(r["calibration_injected_ms"]) == 0.0
            )
        )
    ]
    if bad_base_cal:
        cohort_errors.append(
            f"base_rows must have calibration_injected_ms in (null, 0) "
            f"(found {len(bad_base_cal)} invalid row(s): {bad_base_cal})"
        )

    bad_inj_rk = [r.get("run_kind") for r in injected_rows if r.get("run_kind") != "calibration"]
    if bad_inj_rk:
        cohort_errors.append(
            f"injected_rows must all have run_kind='calibration' "
            f"(found {len(bad_inj_rk)} non-calibration row(s): "
            f"{sorted({repr(x) if x is None else str(x) for x in bad_inj_rk})})"
        )

    bad_inj_cal = [
        r.get("calibration_injected_ms")
        for r in injected_rows
        if not (
            _is_finite_number(r.get("calibration_injected_ms"))
            and float(r["calibration_injected_ms"]) > 0.0
        )
    ]
    if bad_inj_cal:
        cohort_errors.append(
            f"injected_rows must all have numeric calibration_injected_ms > 0 "
            f"(found {len(bad_inj_cal)} invalid row(s): {bad_inj_cal})"
        )
    elif resolved_mode == "latency_ms":
        mismatched_inj = [
            float(r["calibration_injected_ms"])
            for r in injected_rows
            if abs(float(r["calibration_injected_ms"]) - float(injected_ms)) > 1e-6
        ]
        if mismatched_inj:
            cohort_errors.append(
                f"injected_rows calibration_injected_ms must match injected_ms={injected_ms} "
                f"(found {len(mismatched_inj)} mismatched row(s): {sorted(set(mismatched_inj))})"
            )

    # Duplicate iteration check within base_rows and within injected_rows.
    for label, group in (("base", base_rows), ("injected", injected_rows)):
        iter_counts: dict[int, int] = {}
        for row in group:
            it = row.get("iteration")
            if _is_int(it):
                iter_counts[it] = iter_counts.get(it, 0) + 1
        dups = sorted(it for it, cnt in iter_counts.items() if cnt > 1)
        if dups:
            dup_str = ", ".join(f"{it} (x{iter_counts[it]})" for it in dups)
            cohort_errors.append(f"duplicate iteration in {label}_rows: {dup_str}")

    if cohort_errors:
        return {
            "verdict": "invalid_cohort",
            "passed": False,
            "calibration_mode": resolved_mode,
            "primary_metric": metric,
            "cohort_keys": cohort_keys,
            "scenarios": scenarios_seen,
            "cohort_key": cohort_keys[0] if len(cohort_keys) == 1 and cohort_keys[0] else None,
            "scenario": scenarios_seen[0] if len(scenarios_seen) == 1 and scenarios_seen[0] else None,
            "injected_ms": float(injected_ms),
            "injected_slow_frames": injected_slow_frames,
            "expected_min_ms": round(injected_ms * (1.0 - tolerance_ratio), 3)
            if resolved_mode == "latency_ms"
            else None,
            "expected_max_ms": round(injected_ms * (1.0 + tolerance_ratio), 3)
            if resolved_mode == "latency_ms"
            else None,
            "reasons": cohort_errors,
            "thermal_flags": [],
            "thermal_unverified_count": 0,
            "thermal_unverified_runs": [],
        }

    # Validate slow_frames mode inputs before running statistics.
    resolved_window_ms: float | None = None
    if resolved_mode == "slow_frames" and expected_delta is None:
        if metric == "slow_frame_ratio":
            if injected_slow_frames is None:
                raise ValueError(
                    "slow_frames calibration on slow_frame_ratio requires expected_delta or injected_slow_frames"
                )
        elif metric == "hitch_ms_per_s":
            if injected_slow_frames is None:
                raise ValueError(
                    "slow_frames calibration on hitch_ms_per_s requires expected_delta or injected_slow_frames"
                )
            resolved_window_ms = _infer_calibration_window_ms(all_rows, window_ms)
            if resolved_window_ms is None:
                raise ValueError(
                    "slow_frames calibration on hitch_ms_per_s requires expected_delta or window_ms"
                )
        else:
            raise ValueError(
                f"slow_frames calibration on metric {metric!r} requires expected_delta"
            )

    thermal_flags: list[dict[str, Any]] = []
    thermal_unverified_runs: list[dict[str, Any]] = []
    reasons: list[str] = []

    def _collect(
        group: Sequence[Mapping[str, Any]], label: str
    ) -> tuple[list[float], dict[int, float], list[int], dict[int, int]]:
        vals: list[float] = []
        by_iter: dict[int, float] = {}
        sample_counts: list[int] = []
        samples_by_iter: dict[int, int] = {}
        for row in group:
            val = row.get(metric)
            samples = row.get("samples")
            has_valid_sample_and_metric = (
                _is_int(samples) and samples > 0 and _is_finite_number(val)
            )
            thermal_status, t_reason = classify_thermal_status(
                row.get("thermal_start"), row.get("thermal_end")
            )
            if thermal_status == "throttled" and has_valid_sample_and_metric:
                thermal_flags.append(
                    {
                        "run_id": row.get("run_id"),
                        "group": label,
                        "iteration": row.get("iteration"),
                        "thermal_start": row.get("thermal_start"),
                        "thermal_end": row.get("thermal_end"),
                        "reason": t_reason,
                    }
                )
                if filter_thermal or row.get("valid") is not True:
                    continue
            if row.get("valid") is not True or not has_valid_sample_and_metric:
                continue
            if thermal_status == "unverified":
                thermal_unverified_runs.append(
                    {
                        "run_id": row.get("run_id"),
                        "group": label,
                        "iteration": row.get("iteration"),
                        "thermal_start": row.get("thermal_start"),
                        "thermal_end": row.get("thermal_end"),
                        "reason": t_reason,
                    }
                )
            fval = float(val)
            vals.append(fval)
            sample_counts.append(int(samples))
            it = row.get("iteration")
            if _is_int(it):
                by_iter[it] = fval
                samples_by_iter[it] = int(samples)
        return vals, by_iter, sample_counts, samples_by_iter

    base_vals, base_by_iter, base_samples, base_samples_by_iter = _collect(base_rows, "base")
    inj_vals, inj_by_iter, inj_samples, inj_samples_by_iter = _collect(injected_rows, "injected")

    if thermal_flags:
        action = "filtered" if filter_thermal else "flagged"
        reasons.append(f"{action} {len(thermal_flags)} thermally throttled calibration run(s)")
    if thermal_unverified_runs:
        reasons.append(
            f"WARNING: {len(thermal_unverified_runs)} calibration run(s) have unverified thermal telemetry "
            f"(missing or unrecognized thermal_start/thermal_end): "
            + ", ".join(
                f"{u['group']}#{u['iteration']}({u['reason']})" for u in thermal_unverified_runs
            )
        )

    paired_iters = sorted(set(base_by_iter.keys()) & set(inj_by_iter.keys()))
    use_paired = len(paired_iters) >= min_pairs
    if use_paired:
        b_series = [base_by_iter[i] for i in paired_iters]
        i_series = [inj_by_iter[i] for i in paired_iters]
        used_samples = [
            float(base_samples_by_iter[i]) for i in paired_iters
        ] + [
            float(inj_samples_by_iter[i]) for i in paired_iters
        ]
        deltas = [h - b for b, h in zip(b_series, i_series)]
        p_value, p_method = _exact_paired_sign_flip_p_value(deltas)
    else:
        b_series = base_vals
        i_series = inj_vals
        used_samples = [float(s) for s in base_samples + inj_samples]
        deltas = []
        if len(b_series) < min_pairs or len(i_series) < min_pairs:
            reasons.append(
                f"insufficient valid calibration rows (base={len(b_series)}, injected={len(i_series)}, min={min_pairs})"
            )
            return {
                "verdict": "insufficient_samples",
                "passed": False,
                "calibration_mode": resolved_mode,
                "primary_metric": metric,
                "cohort_key": cohort_keys[0],
                "scenario": scenarios_seen[0],
                "base_count": len(b_series),
                "injected_count": len(i_series),
                "min_pairs": min_pairs,
                "injected_ms": float(injected_ms),
                "injected_slow_frames": injected_slow_frames,
                "expected_min_ms": round(injected_ms * (1.0 - tolerance_ratio), 3)
                if resolved_mode == "latency_ms"
                else None,
                "expected_max_ms": round(injected_ms * (1.0 + tolerance_ratio), 3)
                if resolved_mode == "latency_ms"
                else None,
                "thermal_flags": thermal_flags,
                "thermal_unverified_count": len(thermal_unverified_runs),
                "thermal_unverified_runs": thermal_unverified_runs,
                "reasons": reasons,
            }
        p_value, p_method = _exact_two_sample_permutation_p_value(b_series, i_series)

    # Resolve target expected delta for the active calibration mode.
    median_samples: float | None = _median(used_samples) if used_samples else None
    if resolved_mode == "latency_ms":
        target_delta = float(expected_delta) if expected_delta is not None else float(injected_ms)
    elif expected_delta is not None:
        target_delta = float(expected_delta)
    elif metric == "slow_frame_ratio":
        assert injected_slow_frames is not None and median_samples is not None and median_samples > 0
        target_delta = float(injected_slow_frames) / median_samples
    else:  # hitch_ms_per_s
        assert injected_slow_frames is not None and resolved_window_ms is not None and resolved_window_ms > 0
        target_delta = (float(injected_slow_frames) * float(injected_ms)) / (resolved_window_ms / 1000.0)

    expected_min = target_delta * (1.0 - tolerance_ratio)
    expected_max = target_delta * (1.0 + tolerance_ratio)

    base_med = _median(b_series)
    inj_med = _median(i_series)
    median_delta = inj_med - base_med
    paired_median_delta: float | None = _median(deltas) if use_paired else None
    observed_delta = paired_median_delta if paired_median_delta is not None else median_delta
    error_val = observed_delta - target_delta
    base_mad = _mad(b_series)
    inj_mad = _mad(i_series)
    pooled_mad = math.sqrt((base_mad**2 + inj_mad**2) / 2.0)

    within_tolerance = expected_min <= observed_delta <= expected_max
    significant = p_value <= alpha + 1e-12
    passed = within_tolerance and significant

    unit_suffix = "ms" if resolved_mode == "latency_ms" else ""
    if not within_tolerance:
        reasons.append(
            f"observed delta {observed_delta:.4f}{unit_suffix} outside tolerance "
            f"[{expected_min:.4f}, {expected_max:.4f}]{unit_suffix}"
        )
    if not significant:
        reasons.append(f"not statistically significant (p={p_value:.5f} > alpha={alpha})")
    if passed:
        reasons.append(
            f"detected +{observed_delta:.4f}{unit_suffix} {metric} shift "
            f"(target +{target_delta:.4f}{unit_suffix}, error {error_val:+.4f}{unit_suffix}, p={p_value:.5f})"
        )

    is_latency = resolved_mode == "latency_ms"
    return {
        "verdict": "pass" if passed else "fail",
        "passed": passed,
        "calibration_mode": resolved_mode,
        "primary_metric": metric,
        "cohort_key": cohort_keys[0],
        "scenario": scenarios_seen[0],
        "paired": use_paired,
        "base_count": len(b_series),
        "injected_count": len(i_series),
        "min_pairs": min_pairs,
        "injected_ms": float(injected_ms),
        "injected_slow_frames": injected_slow_frames,
        "window_ms": resolved_window_ms,
        "median_samples": round(median_samples, 2) if median_samples is not None else None,
        "tolerance_ratio": float(tolerance_ratio),
        "expected_delta": round(target_delta, 6),
        "expected_min": round(expected_min, 6),
        "expected_max": round(expected_max, 6),
        "expected_min_ms": round(expected_min, 3) if is_latency else None,
        "expected_max_ms": round(expected_max, 3) if is_latency else None,
        "base_median": round(base_med, 6),
        "injected_median": round(inj_med, 6),
        "median_delta": round(median_delta, 6),
        "paired_median_delta": round(paired_median_delta, 6) if paired_median_delta is not None else None,
        "observed_delta": round(observed_delta, 6),
        "error": round(error_val, 6),
        "observed_delta_ms": round(observed_delta, 4) if is_latency else None,
        "error_ms": round(error_val, 4) if is_latency else None,
        "pooled_mad": round(pooled_mad, 6),
        "p_value": round(p_value, 6),
        "p_value_method": p_method,
        "alpha": alpha,
        "thermal_flags": thermal_flags,
        "thermal_unverified_count": len(thermal_unverified_runs),
        "thermal_unverified_runs": thermal_unverified_runs,
        "reasons": reasons,
    }
