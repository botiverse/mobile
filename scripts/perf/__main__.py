"""Allow `python3 -m perf.atrace_summary ...` invocation.

Side-steps a CLI-vs-module shadowing bug in `render_markdown` (see README
"Workaround for known bug"). Equivalent to running the script directly.
"""

from __future__ import annotations

import sys


def main() -> int:
    # Defer import until after the package's __init__ has run so
    # `parse` / `render_markdown` resolve against the canonical module.
    from . import atrace_summary
    return atrace_summary.main()


if __name__ == "__main__":
    sys.exit(main())