# A discriminator must actually vary

**Area:** verification / criteria
**Owner:** CC-Cata
**Verified at:** 2026-10-19
**Status:** current

> **Scope and lineage.** Distilled from one day of adjacent failures on this repo, several of them
> mine and several pointed out by reviewers. It generalises the criterion in
> `numeric-criteria-that-pass-while-the-feature-is-broken.md` from "does this number prove the
> feature" to "does this signal distinguish anything at all".
>
> **Forms 1–3 and 5 came from the investigation; form 4 was added in review after the article first
> landed** — the reviewer's case (same-looking store handles, two copies in one process) generalised
> to worktree-vs-branch and to per-channel task numbering. It is recorded as part of the article
> rather than a separate one because it is the same failure: a legitimate answer about the wrong
> thing. Nothing here has been superseded.

A **discriminator** is anything you use to tell two states apart: a test, a log line, a metric, a
toggle, a measurement. Six ways they fail, all of which produce a confident wrong answer rather than
an error.

## 1. It never varies

A signal that is identical in both states distinguishes nothing, and reads as "no difference found".

A class-name signature was proposed to detect "the probe attached to a different list". Every list in
this app is the same class, so the signature was a constant — it could not report a change even if
one occurred. The fix was an instance identity already present in the payload.

Same bone as a test that can never fail: **the check's inability to fire is invisible in its output.**
The output looks the same whether the states differ or not.

## 2. It does not reach the surface you read it from

"Code contains a log statement" is not "the log appears where you plan to grep". A diagnostic line
written through a wrapper whose verbosity flag is off silently produces nothing, and the empty result
then gets read as *the condition was false*.

This direction is the dangerous one: **an absence is read as a negative finding, when the real cause
may be that nothing was ever written there.** Any "we saw zero of X" needs a positive control — a
companion line that must appear if the path ran at all.

## 3. It is not measured where you assume the value comes from

`viewportStartOffset` read `-134`, which looks like a scroll past the content start. It is defined as
`-afterContentPadding` for a reverse list — the correct, constant representation of a bottom padding.
An "impossible" value is a question about the contract, not evidence of a defect.

Check the definition before treating a number as an observation.

## 4. It is a copy, not the thing the conclusion is about

You inspected a different instance of the same-looking object, a different checkout, a different
build, or a different process — and got a **legitimate** answer about that thing, not about the one
in question. This is the most convincing of the five because it never errors: an empty result from
the wrong copy looks exactly like an empty result from the right one.

Three instances, all observed on this repo:

- **Worktree vs branch.** A file reported "not in the repository" from a working-tree `ls`, while it
  was on the branch. `ls` answers about the worktree, `git show origin/main:` answers about the
  branch, and both look like "I checked". This one cost two people two rounds.
- **A truncated API response read as an empty one.** `gh api .../contents/<path>` returns an empty
  `content` field for files above roughly 1 MB rather than an error, so a grep over it finds nothing
  and reports "the symbol is not there". The file was fine; the fetch had not delivered it.
- **Per-channel numbering.** A task number referring to three different items depending on which
  channel's numbering was in view: the reference stayed *valid* while the referent changed, which is
  more dangerous than a broken link because nothing looks wrong.

A fourth case — two same-class store handles in one process — is currently a hypothesis in an open
diagnosis, not an observation. It is listed here as *unverified* deliberately: writing a suspected
mechanism into an article as an instance is the same defect this article is about, and it nearly
shipped as one.

**The tell is not in the answer, it is in the question you did not ask**: *which copy is this answer
about?* Make the answer carry its own provenance — a store identity on the log line, a full ref on the
lookup, a commit SHA rather than a branch name. Then "absent" distinguishes *not there* from *not
there in the copy I happened to ask*.

## 5. It is satisfied by an implementation that fails the user

`window.innerWidth === 1280` passed while the page was drawn at 100% and the user could see about
480px of it. The criterion described what the code should *do*, not what the user must *experience*.
Full treatment in `numeric-criteria-that-pass-while-the-feature-is-broken.md`.

## 6. Two measurements of different things, sitting in one window

The most convincing form, because both numbers are real and correctly measured — they are just not
about the same subject.

Observed on this repo: a frame of **478 ms** sat in the same diagnostic line as a tag
`operation=thread_first_frame`. Read together they form a clean causal sentence: *the first-frame path
took 478 ms.* Neither half supports that sentence on its own:

- the **478 ms** comes from JankStats (`SlockPerformanceDiagnostics.kt:51`,
  `frameData.frameDurationUiNanos`), and by API definition that is time spent on the **UI thread** of
  the window — so the duration is genuinely about the main thread;
- **`operation=` is an `AtomicReference`** set by `updateOperation` (`:125`) and merely read at the
  moment a jank frame is logged (`:79`). It reports *the last operation that was set*, not what the
  measured interval was spent doing, and not which thread was doing it.

So the honest reading is: **the main thread had a 478 ms frame, and the operation label happened to be
`thread_first_frame` when it was recorded.** Whether the main thread was doing that work itself or
blocking on another thread is a separate question, unresolved as of 2026-10-19.

(Note this correction: an earlier draft attributed the 478 ms to a `FrameMetrics` listener registered
on its own `Handler` at `:327` and argued the callback thread is not the measured thread. That
listener emits a *different* log line. `jank_frame` is JankStats, and its duration is UI-thread time.
The general rule below survived the correction; the specific reasoning did not, which is exactly why
examples need pinning to `file:line`.)

**The tell is the same as form 4, pointed the other way.** Form 4 is *asking the wrong object*; this is
*two right objects read as one*. It is invisible precisely because nothing is wrong with either
measurement.

## The question that catches all six


Before trusting a signal, ask: **what would this report if the thing I am testing for were true, and
what would it report if it were false?** If the two answers are the same — or if you cannot say what
either would be — the signal is not evidence yet.

For a test, run it against the broken code. For a log line, confirm it appears on the surface you
will read. For a metric, read its definition. For a criterion, name the user-visible outcome first
and find a measurement the wrong implementation cannot satisfy.

## Related

- `numeric-criteria-that-pass-while-the-feature-is-broken.md` — criteria satisfied by broken code
- `declaring-your-verification-ceiling.md` — what a claim at each layer can and cannot support
