"""Paired interleaved A/B verdict engine and 200ms sensitivity calibration checker.

Pure Python stdlib statistical evaluator:
- Enforces strict cohort & scenario equality (``invalid_cohort`` on mismatch).
- Flags/filters thermally throttled runs (severe/critical/emergency/shutdown/serious
  or thermal tier jump >= 2).
- Computes median, IQR, MAD, paired deltas, relative delta %, and deterministic
  exact sign-flip / permutation p-values.
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

from .scenarios import SCENARIOS, get_primary_kpi

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


def _thermal_tier(state: str | None) -> int | None:
    if not isinstance(state, str) or not state.strip():
        return None
    norm = state.strip().lower()
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
    end_norm = thermal_end.strip().lower() if isinstance(thermal_end, str) else None
    start_norm = thermal_start.strip().lower() if isinstance(thermal_start, str) else None

    if end_norm in THERMAL_THROTTLED_STATES:
        return True, f"thermal_end={end_norm}"

    t_start = _thermal_tier(start_norm)
    t_end = _thermal_tier(end_norm)
    if t_start is not None and t_end is not None and (t_end - t_start) >= 2:
        return True, f"thermal_tier_jump={start_norm}->{end_norm} (+{t_end - t_start})"

    return False, None


# ---------------------------------------------------------------------------
# Robust summary statistics & deterministic permutation tests
# ---------------------------------------------------------------------------


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


def _compute_ab_p_value(
    base_vals: Sequence[float],
    head_vals: Sequence[float],
    deltas: Sequence[float],
    alpha: float,
) -> tuple[float, str]:
    """Choose exact paired sign-flip when attainable p_min <= alpha, else exact two-sample permutation.

    Note: for ``n = 5``, the minimum attainable two-sided paired sign-flip p-value
    is ``2 / 2^5 = 0.0625 > 0.05``. Falling back to the exact two-sample
    permutation test (``C(10, 5) = 252``, ``p_min = 0.00794``) preserves full
    exactness and determinism at ``min_pairs = 5`` while using pure paired
    sign-flip for all ``n >= 6``.
    """
    n = len(deltas)
    if n >= 6 or (n > 0 and (2.0 / (1 << min(n, 30))) <= alpha + 1e-12):
        return _exact_paired_sign_flip_p_value(deltas)
    return _exact_two_sample_permutation_p_value(base_vals, head_vals)


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


# ---------------------------------------------------------------------------
# Public API: paired A/B verdict evaluator
# ---------------------------------------------------------------------------


def evaluate_ab(
    rows: Sequence[Mapping[str, Any]],
    primary_metric: str | None = None,
    *,
    min_pairs: int = 5,
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
            "paired_count": 0,
            "min_pairs": min_pairs,
            "reasons": ["no rows supplied"],
            "thermal_flags": [],
        }

    # 1. Strict cohort & scenario equality gate across all supplied rows.
    cohort_keys = sorted({str(r.get("cohort_key") or "") for r in rows})
    scenarios_seen = sorted({str(r.get("scenario") or "") for r in rows})
    metric = _resolve_metric(rows, primary_metric)
    reasons: list[str] = []

    if len(cohort_keys) != 1 or not cohort_keys[0]:
        reasons.append(f"cohort_key mismatch across rows: {cohort_keys}")
    if len(scenarios_seen) != 1 or not scenarios_seen[0]:
        reasons.append(f"scenario mismatch across rows: {scenarios_seen}")
    if reasons:
        return {
            "verdict": "invalid_cohort",
            "primary_metric": metric,
            "cohort_keys": cohort_keys,
            "scenarios": scenarios_seen,
            "cohort_key": cohort_keys[0] if len(cohort_keys) == 1 else None,
            "scenario": scenarios_seen[0] if len(scenarios_seen) == 1 else None,
            "paired_count": 0,
            "min_pairs": min_pairs,
            "reasons": reasons,
            "thermal_flags": [],
        }

    cohort_key = cohort_keys[0]
    scenario = scenarios_seen[0]
    eff_abs_floor = _default_abs_floor(metric) if abs_floor_ms is None else float(abs_floor_ms)

    # 2. Filter invalid rows, missing metrics, and thermal throttling.
    base_by_iter: dict[int, float] = {}
    head_by_iter: dict[int, float] = {}
    invalid_excluded = 0
    thermal_excluded = 0
    thermal_flags: list[dict[str, Any]] = []

    for row in rows:
        arm = row.get("ab_arm")
        if arm not in ("base", "head"):
            continue
        iteration = row.get("iteration")
        valid = row.get("valid")
        samples = row.get("samples")
        val = row.get(metric)

        throttled, thermal_reason = is_thermal_throttled(
            row.get("thermal_start"), row.get("thermal_end")
        )
        has_valid_sample_and_metric = (
            isinstance(samples, int)
            and not isinstance(samples, bool)
            and samples > 0
            and isinstance(iteration, int)
            and not isinstance(iteration, bool)
            and isinstance(val, (int, float))
            and not isinstance(val, bool)
            and math.isfinite(float(val))
        )

        if throttled and has_valid_sample_and_metric:
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
            "paired_count": len(paired_iters),
            "min_pairs": min_pairs,
            "invalid_excluded_count": invalid_excluded,
            "thermal_excluded_count": thermal_excluded,
            "thermal_flags": thermal_flags,
            "reasons": reasons,
        }

    # 4. Robust statistics & deterministic p-value.
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
    rel_delta_pct = (median_delta / base_med * 100.0) if abs(base_med) > 1e-12 else None

    p_value, p_method = _compute_ab_p_value(base_vals, head_vals, deltas, alpha)

    rel_threshold = rel_floor * abs(base_med)
    noise_threshold = noise_multiplier * pooled_mad
    practical_floor = max(rel_threshold, eff_abs_floor)
    noise_floor = max(practical_floor, noise_threshold)

    abs_delta = abs(median_delta)
    significant = p_value <= alpha + 1e-12
    exceeds_noise_floor = abs_delta > noise_floor
    relative_noise = (pooled_mad / abs(base_med)) if abs(base_med) > 1e-12 else 0.0

    if significant and exceeds_noise_floor:
        if lower_is_better:
            verdict = "improved" if median_delta < 0 else "regressed"
        else:
            verdict = "improved" if median_delta > 0 else "regressed"
        reasons.append(
            f"{metric} delta {median_delta:+.3f} ({rel_delta_pct:+.2f}%) exceeds noise floor "
            f"{noise_floor:.3f} with p={p_value:.5f}"
            if rel_delta_pct is not None
            else f"{metric} delta {median_delta:+.3f} exceeds noise floor {noise_floor:.3f} with p={p_value:.5f}"
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
                    f"observed delta {median_delta:+.3f} exceeds practical floor {practical_floor:.3f} "
                    f"but failed gate (p={p_value:.5f}, noise_floor={noise_floor:.3f})"
                )
        else:
            verdict = "neutral"
            reasons.append(
                f"delta {median_delta:+.3f} within noise floor {noise_floor:.3f} with controlled variance "
                f"(p={p_value:.5f}, pooled_mad={pooled_mad:.3f})"
            )

    return {
        "verdict": verdict,
        "primary_metric": metric,
        "cohort_key": cohort_key,
        "scenario": scenario,
        "paired_count": len(paired_iters),
        "paired_iterations": paired_iters,
        "min_pairs": min_pairs,
        "invalid_excluded_count": invalid_excluded,
        "thermal_excluded_count": thermal_excluded,
        "thermal_flags": thermal_flags,
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
# Public API: 200ms sensitivity calibration checker
# ---------------------------------------------------------------------------


def evaluate_calibration(
    base_rows: Sequence[Mapping[str, Any]],
    injected_rows: Sequence[Mapping[str, Any]],
    *,
    primary_metric: str | None = None,
    injected_ms: float = 200.0,
    tolerance_ratio: float = 0.25,
    min_pairs: int = 5,
    alpha: float = 0.05,
    filter_thermal: bool = True,
) -> dict[str, Any]:
    """Verify the harness reliably detects an injected synthetic stall (default 200ms).

    For ``injected_ms=200.0`` and ``tolerance_ratio=0.25``, requires the observed
    latency delta (``injected_median - base_median``) to fall inside
    ``[150.0, 250.0]`` ms and be statistically significant (``p <= alpha``) on
    matching cohorts.
    """
    if injected_ms <= 0:
        raise ValueError(f"injected_ms must be > 0, got {injected_ms!r}")
    if not (0.0 < tolerance_ratio < 1.0):
        raise ValueError(f"tolerance_ratio must be in (0, 1), got {tolerance_ratio!r}")

    all_rows = list(base_rows) + list(injected_rows)
    if not base_rows or not injected_rows:
        return {
            "verdict": "insufficient_samples",
            "passed": False,
            "primary_metric": primary_metric,
            "injected_ms": float(injected_ms),
            "expected_min_ms": round(injected_ms * (1.0 - tolerance_ratio), 3),
            "expected_max_ms": round(injected_ms * (1.0 + tolerance_ratio), 3),
            "reasons": ["both base_rows and injected_rows are required for calibration"],
            "thermal_flags": [],
        }

    cohort_keys = sorted({str(r.get("cohort_key") or "") for r in all_rows})
    scenarios_seen = sorted({str(r.get("scenario") or "") for r in all_rows})
    metric = _resolve_metric(all_rows, primary_metric)
    expected_min = injected_ms * (1.0 - tolerance_ratio)
    expected_max = injected_ms * (1.0 + tolerance_ratio)

    cohort_errors: list[str] = []
    if len(cohort_keys) != 1 or not cohort_keys[0]:
        cohort_errors.append(f"cohort_key mismatch across calibration rows: {cohort_keys}")
    if len(scenarios_seen) != 1 or not scenarios_seen[0]:
        cohort_errors.append(f"scenario mismatch across calibration rows: {scenarios_seen}")
    if cohort_errors:
        return {
            "verdict": "invalid_cohort",
            "passed": False,
            "primary_metric": metric,
            "cohort_keys": cohort_keys,
            "scenarios": scenarios_seen,
            "injected_ms": float(injected_ms),
            "expected_min_ms": round(expected_min, 3),
            "expected_max_ms": round(expected_max, 3),
            "reasons": cohort_errors,
            "thermal_flags": [],
        }

    thermal_flags: list[dict[str, Any]] = []
    reasons: list[str] = []

    def _collect(group: Sequence[Mapping[str, Any]], label: str) -> tuple[list[float], dict[int, float]]:
        vals: list[float] = []
        by_iter: dict[int, float] = {}
        for row in group:
            val = row.get(metric)
            samples = row.get("samples")
            if (
                row.get("valid") is not True
                or not isinstance(samples, int)
                or isinstance(samples, bool)
                or samples <= 0
                or not isinstance(val, (int, float))
                or isinstance(val, bool)
                or not math.isfinite(float(val))
            ):
                continue
            throttled, t_reason = is_thermal_throttled(
                row.get("thermal_start"), row.get("thermal_end")
            )
            if throttled:
                thermal_flags.append(
                    {
                        "run_id": row.get("run_id"),
                        "group": label,
                        "iteration": row.get("iteration"),
                        "reason": t_reason,
                    }
                )
                if filter_thermal:
                    continue
            fval = float(val)
            vals.append(fval)
            it = row.get("iteration")
            if isinstance(it, int) and not isinstance(it, bool):
                by_iter[it] = fval
        return vals, by_iter

    base_vals, base_by_iter = _collect(base_rows, "base")
    inj_vals, inj_by_iter = _collect(injected_rows, "injected")

    paired_iters = sorted(set(base_by_iter.keys()) & set(inj_by_iter.keys()))
    use_paired = len(paired_iters) >= min_pairs
    if use_paired:
        b_series = [base_by_iter[i] for i in paired_iters]
        i_series = [inj_by_iter[i] for i in paired_iters]
        deltas = [h - b for b, h in zip(b_series, i_series)]
        p_value, p_method = _compute_ab_p_value(b_series, i_series, deltas, alpha)
    else:
        b_series = base_vals
        i_series = inj_vals
        deltas = []
        if len(b_series) < min_pairs or len(i_series) < min_pairs:
            reasons.append(
                f"insufficient valid calibration rows (base={len(b_series)}, injected={len(i_series)}, min={min_pairs})"
            )
            return {
                "verdict": "insufficient_samples",
                "passed": False,
                "primary_metric": metric,
                "cohort_key": cohort_keys[0],
                "scenario": scenarios_seen[0],
                "base_count": len(b_series),
                "injected_count": len(i_series),
                "min_pairs": min_pairs,
                "injected_ms": float(injected_ms),
                "expected_min_ms": round(expected_min, 3),
                "expected_max_ms": round(expected_max, 3),
                "thermal_flags": thermal_flags,
                "reasons": reasons,
            }
        p_value, p_method = _exact_two_sample_permutation_p_value(b_series, i_series)

    base_med = _median(b_series)
    inj_med = _median(i_series)
    observed_delta = inj_med - base_med
    error_ms = observed_delta - injected_ms
    base_mad = _mad(b_series)
    inj_mad = _mad(i_series)
    pooled_mad = math.sqrt((base_mad**2 + inj_mad**2) / 2.0)

    within_tolerance = expected_min <= observed_delta <= expected_max
    significant = p_value <= alpha + 1e-12
    passed = within_tolerance and significant

    if thermal_flags:
        reasons.append(f"filtered {len(thermal_flags)} thermally throttled calibration run(s)")
    if not within_tolerance:
        reasons.append(
            f"observed delta {observed_delta:.2f}ms outside tolerance [{expected_min:.2f}, {expected_max:.2f}]ms"
        )
    if not significant:
        reasons.append(f"not statistically significant (p={p_value:.5f} > alpha={alpha})")
    if passed:
        reasons.append(
            f"detected +{observed_delta:.2f}ms stall (target +{injected_ms:.1f}ms, "
            f"error {error_ms:+.2f}ms, p={p_value:.5f})"
        )

    return {
        "verdict": "pass" if passed else "fail",
        "passed": passed,
        "primary_metric": metric,
        "cohort_key": cohort_keys[0],
        "scenario": scenarios_seen[0],
        "paired": use_paired,
        "base_count": len(b_series),
        "injected_count": len(i_series),
        "injected_ms": float(injected_ms),
        "tolerance_ratio": float(tolerance_ratio),
        "expected_min_ms": round(expected_min, 3),
        "expected_max_ms": round(expected_max, 3),
        "base_median": round(base_med, 4),
        "injected_median": round(inj_med, 4),
        "observed_delta_ms": round(observed_delta, 4),
        "error_ms": round(error_ms, 4),
        "pooled_mad": round(pooled_mad, 4),
        "p_value": round(p_value, 6),
        "p_value_method": p_method,
        "alpha": alpha,
        "thermal_flags": thermal_flags,
        "reasons": reasons,
    }
