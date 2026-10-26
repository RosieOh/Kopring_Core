// 코프링 교재 — 화면 동작. 파일로 바로 열어도(file://) 동작하도록 모듈이 아닌 일반 스크립트로 쓴다.
(function () {
  "use strict";

  var root = document.documentElement;

  function isDark() {
    var chosen = root.dataset.theme;
    if (chosen) return chosen === "dark";
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
  }

  // ── 테마 ────────────────────────────────────────────
  var themeButton = document.querySelector(".theme-button");

  function updateThemeButton() {
    if (themeButton) themeButton.textContent = isDark() ? "밝게" : "어둡게";
  }

  function setTheme(theme) {
    root.dataset.theme = theme;
    try { localStorage.setItem("book-theme", theme); } catch (e) { /* 저장할 수 없으면 이번 화면에만 */ }
    updateThemeButton();
    renderDiagrams();
  }

  if (themeButton) {
    updateThemeButton();
    themeButton.addEventListener("click", function () { setTheme(isDark() ? "light" : "dark"); });
  }

  // ── 휴대폰 화면의 장 목록 ───────────────────────────
  var menuButton = document.querySelector(".menu-button");
  var sidebar = document.getElementById("sidebar");

  function setNavOpen(open) {
    document.body.classList.toggle("nav-open", open);
    if (menuButton) menuButton.setAttribute("aria-expanded", String(open));
  }

  if (menuButton && sidebar) {
    menuButton.addEventListener("click", function (event) {
      event.stopPropagation();
      setNavOpen(!document.body.classList.contains("nav-open"));
    });
    // 장을 고르거나, 목록 바깥을 누르거나, Esc 를 누르면 닫는다
    document.addEventListener("click", function (event) {
      if (!document.body.classList.contains("nav-open")) return;
      if (!sidebar.contains(event.target) || event.target.closest("a")) setNavOpen(false);
    });
    document.addEventListener("keydown", function (event) {
      if (event.key === "Escape") setNavOpen(false);
    });
  }

  // ── 코드 복사 ──────────────────────────────────────
  document.querySelectorAll(".code-block").forEach(function (block) {
    var button = block.querySelector(".copy-button");
    var code = block.querySelector("pre");
    if (!button || !code) return;
    button.addEventListener("click", function () {
      var done = function () {
        button.textContent = "복사됨";
        button.classList.add("is-copied");
        setTimeout(function () { button.textContent = "복사"; button.classList.remove("is-copied"); }, 1500);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(code.innerText).then(done, function () {});
      }
    });
  });

  // ── 이 장의 목차: 지금 읽는 절 표시 ──────────────────
  // 화면 위쪽(상단 막대 바로 아래)을 이미 지난 제목 중 마지막 것이 지금 읽는 절이다
  var tocLinks = Array.prototype.slice.call(document.querySelectorAll(".page-toc a"));
  if (tocLinks.length) {
    var sections = tocLinks
      .map(function (link) {
        return { link: link, heading: document.getElementById(decodeURIComponent(link.getAttribute("href").slice(1))) };
      })
      .filter(function (s) { return s.heading; });
    var active = null;
    var ticking = false;

    var updateActive = function () {
      ticking = false;
      var current = null;
      for (var i = 0; i < sections.length; i++) {
        if (sections[i].heading.getBoundingClientRect().top <= 96) current = sections[i];
        else break;
      }
      if (current === active) return;
      if (active) active.link.classList.remove("is-active");
      if (current) current.link.classList.add("is-active");
      active = current;
    };

    window.addEventListener("scroll", function () {
      if (!ticking) { ticking = true; window.requestAnimationFrame(updateActive); }
    }, { passive: true });
    updateActive();
  }

  // ── Mermaid 그림 ───────────────────────────────────
  // 테마를 바꾸면 원문(data-source)으로 되돌린 뒤 새 테마로 다시 그린다.
  function renderDiagrams() {
    if (!window.mermaid) return;
    var nodes = document.querySelectorAll("pre.mermaid");
    if (!nodes.length) return;
    nodes.forEach(function (node) {
      node.removeAttribute("data-processed");
      node.textContent = node.dataset.source;
    });
    window.mermaid.initialize({
      startOnLoad: false,
      securityLevel: "strict",
      theme: isDark() ? "dark" : "neutral",
      fontFamily: getComputedStyle(document.body).fontFamily,
    });
    window.mermaid.run({ nodes: nodes }).catch(function () { /* 그리지 못하면 원문이 그대로 보인다 */ });
  }

  if (window.mermaid) {
    renderDiagrams();
  } else {
    window.addEventListener("load", renderDiagrams);
  }

  if (window.matchMedia) {
    window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
      if (!root.dataset.theme) { updateThemeButton(); renderDiagrams(); }
    });
  }
})();
