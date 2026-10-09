(() => {
  const CLOSERS = /^(done|fi|esac|\}|\))(\s|;|$)/;
  const HEREDOC = /<<-?\s*['"]?([A-Za-z_]\w*)['"]?/;

  function scanQuotes(text, quote) {
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (quote) {
        if (c === quote && (quote === "'" || text[i - 1] !== "\\")) quote = null;
      } else if (c === "\\") {
        i++;
      } else if (c === "'" || c === '"') {
        quote = c;
      } else if (c === "#" && (i === 0 || /\s/.test(text[i - 1]))) {
        break;
      }
    }
    return quote;
  }

  function commandStarts(lines) {
    const starts = [];
    let continued = false;
    let quote = null;
    let heredoc = null;
    for (const text of lines) {
      if (heredoc) {
        starts.push(false);
        if (text.trim() === heredoc) heredoc = null;
        continue;
      }
      const trimmed = text.trim();
      starts.push(!continued && !quote && trimmed !== "" && !/^\s/.test(text) && !CLOSERS.test(trimmed));
      quote = scanQuotes(text, quote);
      if (!quote) heredoc = text.match(HEREDOC)?.[1] ?? null;
      continued = !quote && !heredoc && /\\$/.test(text.trimEnd());
    }
    return starts;
  }

  function markPrompts() {
    const blocks = document.querySelectorAll(
      '.ox-container--terminal .ox-code:first-of-type pre[data-language="sh"] code',
    );
    for (const code of blocks) {
      const lines = [...code.querySelectorAll(".line")];
      commandStarts(lines.map((line) => line.textContent)).forEach((start, i) => {
        if (start) lines[i].classList.add("ajmx-prompt");
      });
      code.setAttribute("data-prompts", "");
    }
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", markPrompts);
  } else {
    markPrompts();
  }
})();
