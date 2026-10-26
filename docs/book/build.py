#!/usr/bin/env python3
"""Markdown 교재(docs/*.md)를 HTML 책(docs/book/*.html)으로 만든다.

Markdown 이 원본이다. 교재를 고칠 때는 Markdown 을 고치고 이 스크립트를 다시 돌린다.
CI 는 이 스크립트를 돌린 결과가 커밋된 HTML 과 같은지 확인한다. (Markdown 만 고치고 HTML 을 안 만들면 실패)

    python3 -m venv .venv
    .venv/bin/pip install -r docs/book/requirements.txt
    .venv/bin/python docs/book/build.py          # → docs/book/index.html 을 브라우저로 연다

- 코드 색칠은 Pygments 로 미리 해 둔다. 인터넷이 없어도 색이 보인다.
- Mermaid 그림과 글꼴(Pretendard)은 CDN 에서 받는다. 인터넷이 없으면 그림 대신 원문, 글꼴은 시스템 글꼴이 보인다.
- 결과는 실행할 때마다 똑같아야 한다. (시각·임의 값을 넣지 않는다 — CI 비교가 깨진다)
"""

from __future__ import annotations

import html
import re
import sys
import textwrap
from dataclasses import dataclass, field
from pathlib import Path

from markdown_it import MarkdownIt
from mdit_py_plugins.anchors import anchors_plugin
from pygments import highlight
from pygments.formatters import HtmlFormatter
from pygments.lexers import get_lexer_by_name
from pygments.util import ClassNotFound

DOCS = Path(__file__).resolve().parent.parent
OUT = DOCS / "book"
REPO = "https://github.com/RosieOh/Kopring_Core"
BOOK_TITLE = "코프링 교재"
MERMAID_JS = "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js"
FONT_CSS = "https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css"

LANGUAGE_LABELS = {
    "kotlin": "Kotlin",
    "properties": "properties",
    "bash": "Shell",
    "sql": "SQL",
    "json": "JSON",
    "javascript": "JavaScript",
    "yaml": "YAML",
    "python": "Python",
}


@dataclass
class Page:
    source: Path
    output: str
    title: str = ""
    nav_title: str = ""
    body: str = ""
    toc: list[tuple[int, str, str]] = field(default_factory=list)  # (레벨, id, 제목)
    has_mermaid: bool = False


def github_slug(text: str) -> str:
    """GitHub 이 Markdown 제목에 붙이는 id 와 같은 규칙. (소문자, 글자·숫자·공백·하이픈만 남기고 공백은 하이픈으로)"""
    slug = re.sub(r"[^\w\- ]", "", text.strip().lower())
    return slug.replace(" ", "-")


def pages() -> list[Page]:
    chapters = sorted(DOCS.glob("[0-9][0-9]-*.md"))
    return [Page(DOCS / "README.md", "index.html")] + [Page(p, p.with_suffix(".html").name) for p in chapters]


def rewrite_href(href: str, page_names: set[str]) -> tuple[str, bool]:
    """(새 주소, 외부 링크인지)"""
    if href.startswith(("http://", "https://")):
        return href, True
    if href.startswith("#") or href.startswith("mailto:"):
        return href, False

    path, _, anchor = href.partition("#")
    suffix = f"#{anchor}" if anchor else ""
    if path == "README.md":
        return f"index.html{suffix}", False
    if path.startswith("book/"):
        # Markdown 에서 HTML 책을 가리키는 링크. HTML 문서는 같은 폴더, 그 밖의 파일(build.py 등)은 GitHub 로
        rest = path.removeprefix("book/")
        if rest.endswith(".html"):
            return rest + suffix, False
        return f"{REPO}/blob/main/docs/{path}{suffix}", True
    if path.endswith(".md") and "/" not in path and path.removesuffix(".md") + ".html" in page_names:
        return path.removesuffix(".md") + ".html" + suffix, False
    if path.startswith("../"):
        # 저장소 코드·문서 → GitHub (#L10-L20 같은 줄 번호가 그대로 동작한다)
        target = path.removeprefix("../")
        kind = "tree" if target.endswith("/") or "." not in target.rsplit("/", 1)[-1] else "blob"
        return f"{REPO}/{kind}/main/{target}{suffix}", True
    return href, False


def render_code(code: str, lang: str) -> str:
    label = LANGUAGE_LABELS.get(lang, lang or "text")
    try:
        lexer = get_lexer_by_name(lang) if lang else get_lexer_by_name("text")
    except ClassNotFound:
        lexer = get_lexer_by_name("text")
    # 클래스 안 메서드를 인용하면 들여쓰기가 그대로 딸려 온다. 보여 줄 때만 공통 들여쓰기를 걷어 낸다 (Markdown 원문은 원본 코드 그대로)
    highlighted = highlight(textwrap.dedent(code), lexer, HtmlFormatter(cssclass="highlight", wrapcode=True))
    return (
        '<figure class="code-block">'
        f'<figcaption><span class="code-lang">{html.escape(label)}</span>'
        '<button type="button" class="copy-button" aria-label="코드 복사">복사</button></figcaption>'
        f"{highlighted}</figure>\n"
    )


def build_markdown(page_names: set[str]) -> MarkdownIt:
    md = MarkdownIt("commonmark", {"html": True, "typographer": False}).enable("table")
    anchors_plugin(md, min_level=1, max_level=3, slug_func=github_slug, permalink=True, permalinkSymbol="#", permalinkBefore=False)

    def fence(renderer, tokens, idx, options, env):
        token = tokens[idx]
        lang = token.info.strip().split(" ")[0]
        if lang == "mermaid":
            env["has_mermaid"] = True
            source = html.escape(token.content)
            return f'<figure class="diagram"><pre class="mermaid" data-source="{source}">{source}</pre></figure>\n'
        return render_code(token.content, lang)

    def table_open(renderer, tokens, idx, options, env):
        return '<div class="table-wrap"><table>\n'

    def table_close(renderer, tokens, idx, options, env):
        return "</table></div>\n"

    def link_open(renderer, tokens, idx, options, env):
        token = tokens[idx]
        href, external = rewrite_href(token.attrGet("href") or "", page_names)
        token.attrSet("href", href)
        if external:
            token.attrSet("target", "_blank")
            token.attrSet("rel", "noopener")
        return renderer.renderToken(tokens, idx, options, env)

    md.add_render_rule("fence", fence)
    md.add_render_rule("table_open", table_open)
    md.add_render_rule("table_close", table_close)
    md.add_render_rule("link_open", link_open)
    return md


def heading_text(inline) -> str:
    # 제목 뒤에 붙인 # 링크 앞의 공백까지 text 로 들어오므로 다듬는다
    return "".join(child.content for child in inline.children if child.type in ("text", "code_inline")).strip()


def nav_title_of(page: Page) -> str:
    if page.output == "index.html":
        return "처음에 — 읽는 법과 목차"
    # "7장. 동시성 제어 — 이 프로젝트의 심장" → "7장. 동시성 제어"
    return page.title.split(" — ")[0]


def render_page(page: Page, md: MarkdownIt) -> None:
    env: dict = {}
    text = page.source.read_text(encoding="utf-8")
    tokens = md.parse(text, env)

    for i, token in enumerate(tokens):
        if token.type != "heading_open":
            continue
        level = int(token.tag[1])
        title = heading_text(tokens[i + 1])
        if level == 1 and not page.title:
            page.title = title
        elif level in (2, 3):
            page.toc.append((level, token.attrGet("id"), title))

    page.body = md.renderer.render(tokens, md.options, env)
    page.has_mermaid = env.get("has_mermaid", False)
    page.nav_title = nav_title_of(page)


def sidebar(all_pages: list[Page], current: Page) -> str:
    items = []
    for p in all_pages:
        current_attr = ' aria-current="page"' if p is current else ""
        items.append(f'<li><a href="{p.output}"{current_attr}>{html.escape(p.nav_title)}</a></li>')
    return "\n".join(items)


def page_toc(page: Page) -> str:
    if not page.toc:
        return ""
    items = "\n".join(
        f'<li class="toc-level-{level}"><a href="#{anchor}">{html.escape(title)}</a></li>' for level, anchor, title in page.toc
    )
    return f'<nav class="page-toc" aria-label="이 장의 목차"><p class="page-toc-title">이 장에서</p><ol>{items}</ol></nav>'


def pager(all_pages: list[Page], index: int) -> str:
    previous = all_pages[index - 1] if index > 0 else None
    following = all_pages[index + 1] if index + 1 < len(all_pages) else None
    parts = []
    if previous:
        parts.append(f'<a class="pager-prev" href="{previous.output}"><span>이전</span>{html.escape(previous.nav_title)}</a>')
    if following:
        parts.append(f'<a class="pager-next" href="{following.output}"><span>다음</span>{html.escape(following.nav_title)}</a>')
    return f'<nav class="pager" aria-label="장 이동">{"".join(parts)}</nav>'


def render_html(all_pages: list[Page], index: int) -> str:
    page = all_pages[index]
    title = BOOK_TITLE if page.output == "index.html" else f"{page.nav_title} — {BOOK_TITLE}"
    source_url = f"{REPO}/blob/main/docs/{page.source.name}"
    mermaid = f'<script src="{MERMAID_JS}" defer></script>\n' if page.has_mermaid else ""
    return f"""<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(title)}</title>
<meta name="description" content="선착순 쿠폰 코드로 배우는 Kotlin + Spring Boot">
<script>try{{var t=localStorage.getItem("book-theme");if(t)document.documentElement.dataset.theme=t;}}catch(e){{}}</script>
<link rel="stylesheet" href="{FONT_CSS}">
<link rel="stylesheet" href="assets/book.css">
<link rel="stylesheet" href="assets/highlight.css">
{mermaid}<script src="assets/book.js" defer></script>
</head>
<body>
<a class="skip-link" href="#content">본문으로 건너뛰기</a>
<header class="topbar">
  <button type="button" class="menu-button" aria-label="장 목록 열기" aria-expanded="false" aria-controls="sidebar">목차</button>
  <a class="topbar-title" href="index.html">{BOOK_TITLE}</a>
  <button type="button" class="theme-button" aria-label="밝은 화면과 어두운 화면 바꾸기">화면</button>
</header>
<div class="layout">
<aside class="sidebar" id="sidebar">
  <a class="sidebar-title" href="index.html">{BOOK_TITLE}<small>선착순 쿠폰으로 배우는 Kotlin + Spring Boot</small></a>
  <nav aria-label="장 목록"><ol class="chapter-list">
{sidebar(all_pages, page)}
  </ol></nav>
</aside>
<main id="content" class="content">
<article class="markdown">
{page.body}
</article>
<p class="source-link"><a href="{source_url}" target="_blank" rel="noopener">이 장의 원본 Markdown 보기</a></p>
{pager(all_pages, index)}
</main>
{page_toc(page)}
</div>
</body>
</html>
"""


def highlight_css() -> str:
    """밝은 화면은 github 계열(default 를 다듬은 xcode), 어두운 화면은 github-dark. 테마 전환은 data-theme 와 시스템 설정 둘 다 따른다."""
    light = HtmlFormatter(style="xcode").get_style_defs(".highlight")
    dark = HtmlFormatter(style="github-dark").get_style_defs(":root[data-theme=dark] .highlight")
    dark_auto = HtmlFormatter(style="github-dark").get_style_defs(":root:not([data-theme=light]) .highlight")
    return (
        "/* build.py 가 만든다. 직접 고치지 말 것 */\n"
        f"{light}\n"
        f"{dark}\n"
        f"@media (prefers-color-scheme: dark) {{\n{dark_auto}\n}}\n"
    )


def main() -> int:
    all_pages = pages()
    page_names = {p.output for p in all_pages}
    md = build_markdown(page_names)
    for page in all_pages:
        render_page(page, md)

    (OUT / "assets").mkdir(parents=True, exist_ok=True)
    (OUT / "assets" / "highlight.css").write_text(highlight_css(), encoding="utf-8")
    for index, page in enumerate(all_pages):
        (OUT / page.output).write_text(render_html(all_pages, index), encoding="utf-8")
    print(f"{len(all_pages)}개 문서 → {OUT.relative_to(DOCS.parent)}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
