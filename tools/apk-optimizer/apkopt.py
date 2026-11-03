#!/usr/bin/env python3
"""APK 优化与体检工具的启动入口。

用法：
    python tools/apk-optimizer/apkopt.py analyze
    python tools/apk-optimizer/apkopt.py optimize --dry-run
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from apkopt.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
