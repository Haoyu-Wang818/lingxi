"""提示词模板：以文件保存在 app/prompts/<名称>-<版本>.md，版本号写在文件名里，修改即升版（FR-GW-06）。"""

from functools import cache
from pathlib import Path

PROMPT_DIR = Path(__file__).parent / "prompts"

EXTRACT = "extract-v2"
NOTE = "note-v1"
CARDS = "cards-v1"


@cache
def load(version: str) -> str:
    return (PROMPT_DIR / f"{version}.md").read_text(encoding="utf-8").strip()
