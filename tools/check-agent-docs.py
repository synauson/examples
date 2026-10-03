#!/usr/bin/env python3
"""Check the agent docs (CLAUDE.md, AGENTS.md, .claude/) against the repository.

    python3 tools/check-agent-docs.py [--base <git ref>] [--github]

Run from the repository root. Python 3.9+, standard library only. The same file
is used in synauson, jsyn and examples; per-repo settings are in
.claude/docs-check.json.

Errors (exit 1):
  - a backticked repo path, `just` recipe, `--test` binary, Gradle task, npm
    script, env var or Rust `Type::item` path that doesn't exist
  - rule frontmatter: `paths:` missing or a glob matching no tracked file
  - skill frontmatter and structure: name, description, size, links, contents
  - size budgets for CLAUDE.md and for everything loaded in every session
  - a `${VAR:?...}` required by a justfile, or a Cargo workspace member, that
    CLAUDE.md doesn't name
  - a home path or password manager in the agent docs or the docs they cite
    (extra patterns, one regex per line, in $AGENT_DOCS_DENY)

Warnings (never fail):
  - with --base: a rule whose `paths:` match changed files the change didn't touch
  - an env var the code reads that no agent doc or doc names
  - a test binary the test index doesn't name

What it can't check: whether a sentence is still true. That is the author's job.
"""
import argparse
import fnmatch
import json
import os
import re
import subprocess
import sys

DEFAULTS = {
    "env_prefixes": [],
    "external_names": [],
    "claude_md_max_lines": 200,
    "always_loaded_max_lines": 300,
    "skill_max_lines": 500,
    "reference_contents_over_lines": 100,
    "description_max_chars": 1024,
    "members_named_in_claude_md": False,
    "test_index": None,
    "test_dirs_glob": "*/tests/*.rs",
    "deny_scan_extra": [],
    "env_scan": [],
    "env_doc_extra": [],
}
# Written so the patterns don't match themselves (the public repos' commit
# tooling refuses some of these words outright).
PLACEHOLDER = r"(?!(?:you|user|username|yourname|me|<)\b)"
DENY = [r"/h[o]me/" + PLACEHOLDER + r"[a-z][\w.-]*/", r"/U[s]ers/" + PLACEHOLDER + r"[A-Za-z][\w.-]*/",
        r"C:\\U[s]ers\\" + PLACEHOLDER + r"[A-Za-z]",
        r"\bgo[p]ass\b", r"\b1passwor[d]\b", r"\bbitwarde[n]\b", r"\bpas[s] show\b"]
IGNORE_PATH_PREFIXES = ("/", "http", "~", "$", "..")

errors, warnings = [], []


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def load_config():
    cfg = dict(DEFAULTS)
    if os.path.exists(".claude/docs-check.json"):
        cfg.update(json.loads(read(".claude/docs-check.json")))
    return cfg


def glob_regex(pattern):
    """A gitignore-style glob as a regex over repo-relative paths."""
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        elif pattern[i] == "?":
            out += "[^/]"
            i += 1
        elif pattern[i] == "{":
            j = pattern.index("}", i)
            out += "(?:" + "|".join(re.escape(a) for a in pattern[i + 1:j].split(",")) + ")"
            i = j + 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile("^" + out + "$")


def split_frontmatter(text):
    if not text.startswith("---\n"):
        return None, text
    end = text.find("\n---", 4)
    if end < 0:
        return None, text
    return text[4:end], text[end + 4:]


def frontmatter_value(fm, key):
    m = re.search(r"^" + key + r":[ \t]*(.*)$", fm or "", re.M)
    return m.group(1).strip() if m else None


def parse_paths(fm):
    """A rule's `paths:` list; None when absent. Handles the YAML forms rules use."""
    lines = (fm or "").splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^paths:\s*(.*)$", line)
        if not m:
            continue
        inline = m.group(1).strip()
        if inline:
            return [s.strip().strip("'\"") for s in inline.strip("[]").split(",") if s.strip()]
        items = []
        for nxt in lines[i + 1:]:
            lm = re.match(r"^\s*-\s+(.+?)\s*$", nxt)
            if not lm:
                break
            items.append(lm.group(1).strip("'\""))
        return items
    return None


class Repo:
    def __init__(self, cfg):
        self.cfg = cfg
        self.tracked = git("ls-files", "-z").split("\0")[:-1]
        self.tracked_set = set(self.tracked)
        self.dirs = set()
        for p in self.tracked:
            d = os.path.dirname(p)
            while d and d not in self.dirs:
                self.dirs.add(d)
                d = os.path.dirname(d)
        self.roots = {p.split("/")[0] for p in self.tracked}
        self.crate_roots = sorted({d for d in self.dirs | {""}
                                   if os.path.join(d, "Cargo.toml").lstrip("/") in self.tracked_set})
        code_ext = (".rs", ".toml", ".yml", ".yaml", ".sh", ".py", ".java", ".kts", ".kt",
                    ".ts", ".tsx", ".js", ".json", ".properties", ".example", ".proto", ".ps1")
        names = ("justfile", "Dockerfile", "Makefile")
        parts = []
        for p in self.tracked:
            if (p.endswith(code_ext) or os.path.basename(p) in names) and os.path.isfile(p):
                parts.append(read(p))
        self.corpus = "\n".join(parts)
        self.words = set(re.findall(r"\w+", self.corpus))
        self.recipes, self.required_env = set(), set()
        for jf in (p for p in self.tracked if os.path.basename(p) == "justfile"):
            for line in read(jf).splitlines():
                m = re.match(r"^@?([A-Za-z_][\w-]*)(?:\s[^:]*)?:(?!=)", line)
                if m:
                    self.recipes.add(m.group(1))
                self.required_env.update(re.findall(r"\$\{([A-Z][A-Z0-9_]+):\?", line))
        self.test_bins = {os.path.splitext(os.path.basename(p))[0]
                          for p in self.tracked if glob_regex(cfg["test_dirs_glob"]).match(p)}
        self.gradle_tasks = set()
        for p in self.tracked:
            if p.endswith(".gradle.kts") or p.endswith(".gradle"):
                t = read(p)
                self.gradle_tasks.update(re.findall(r'register(?:<[^>]*>)?\(\s*"([\w-]+)"', t))
                self.gradle_tasks.update(re.findall(r"val\s+(\w+)\s+by\s+tasks\.register", t))
                self.gradle_tasks.update(re.findall(r'tasks\.named(?:<[^>]*>)?\(\s*"([\w-]+)"', t))
        self.npm_scripts = set()
        for p in self.tracked:
            if os.path.basename(p) == "package.json":
                try:
                    self.npm_scripts.update(json.loads(read(p)).get("scripts", {}))
                except ValueError:
                    pass

    def ignored(self, path):
        return subprocess.run(["git", "check-ignore", "-q", "--no-index", path],
                              capture_output=True).returncode == 0

    def path_exists(self, tok, doc):
        tok = tok.rstrip("/").split(":")[0]
        bases = ["", os.path.dirname(doc)] + self.crate_roots
        bases += [os.path.join(c, "src") for c in self.crate_roots]
        for b in bases:
            cand = os.path.normpath(os.path.join(b, tok))
            if cand in self.tracked_set or cand in self.dirs:
                return True
            if any(ch in cand for ch in "*?[") and any(fnmatch.fnmatch(p, cand) for p in self.tracked):
                return True
            if self.ignored(cand) or self.ignored(cand + "/x"):
                return True  # generated or fetched (target/, build/, models/)
        return False


GRADLE_BUILTIN = {"build", "test", "check", "clean", "assemble", "compileJava", "compileTestJava",
                  "javadoc", "jar", "run", "publish", "publishToMavenLocal", "bootRun",
                  "bootJar", "processResources", "dependencies", "tasks", "wrapper"}
TICK = re.compile(r"`([^`\n]+)`")
PATHY = re.compile(r"^\.?[\w.-]+/[\w.*/%{},-]*$|^[\w-]+\.(?:md|rs|toml|ya?ml|json|sh|py|proto|java|kts|ts|tsv)$")
ENVY = re.compile(r"^\$?([A-Z][A-Z0-9]*_[A-Z0-9_]+)$")
RUSTPATH = re.compile(r"^\w+(?:::\w+)+(?:\(\))?$")
SNAKE = re.compile(r"^[a-z][a-z0-9]*(?:_[a-z0-9]+)+(?:\(\))?$")


def err(doc, line, msg):
    errors.append((doc, line, msg))


def warn(doc, line, msg):
    warnings.append((doc, line, msg))


def check_tokens(repo, doc, n, tok, in_fence):
    cfg = repo.cfg
    words = tok.split()
    for i, w in enumerate(words):
        starts = i == 0 or words[i - 1] in ("&&", ";", "|", "#", "$", "||")
        nxt = words[i + 1] if i + 1 < len(words) else ""
        if w == "just" and starts and re.match(r"^[a-z][\w-]*$", nxt) and repo.recipes:
            if nxt not in repo.recipes:
                err(doc, n, f"no just recipe `{nxt}`")
        if w == "--test" and nxt:
            t = nxt.strip("'\"")
            if not any(c in t for c in "*?") and t not in repo.test_bins:
                err(doc, n, f"no test binary `{t}`")
        if re.match(r"^\.[/\\]gradlew(\.bat)?$", w) and starts:
            for task in words[i + 1:]:
                if task.startswith("-") or not re.match(r"^[:\w-]+$", task):
                    if task.startswith("-"):
                        continue
                    break
                name = task.split(":")[-1]
                if name and name not in GRADLE_BUILTIN and name not in repo.gradle_tasks:
                    err(doc, n, f"no Gradle task `{name}`")
        if w == "npm" and starts and nxt in ("run", "test"):
            script = "test" if nxt == "test" else (words[i + 2] if i + 2 < len(words) else "")
            if repo.npm_scripts and script and script not in repo.npm_scripts:
                err(doc, n, f"no npm script `{script}`")
    if in_fence:
        return
    if len(words) == 1 and "/" in tok and PATHY.match(tok) and not tok.startswith(IGNORE_PATH_PREFIXES):
        first = tok.split("/")[0]
        local = first in repo.roots or first.startswith(".") or repo.path_exists(first, doc)
        if local and not any(c in tok for c in "<>") and not repo.path_exists(tok, doc):
            err(doc, n, f"path `{tok}` does not exist")
    m = ENVY.match(tok)
    if m:
        name = m.group(1)
        if name.startswith(tuple(cfg["env_prefixes"])) and name not in cfg["external_names"] \
                and name not in repo.corpus:
            err(doc, n, f"env var `{name}` is not read anywhere in the code, CI or recipes")
    if RUSTPATH.match(tok) or SNAKE.match(tok):
        for seg in re.findall(r"\w+", tok):
            if seg not in repo.words and seg not in repo.test_bins:
                err(doc, n, f"`{seg}` (in `{tok}`) is not in the code")


def check_doc(repo, doc):
    text = read(doc)
    lines = text.splitlines()
    in_fence = False
    for n, line in enumerate(lines, 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        toks = [line.strip()] if in_fence else [m.group(1).strip() for m in TICK.finditer(line)]
        for tok in toks:
            if tok:
                check_tokens(repo, doc, n, tok, in_fence)
    return text, lines


def check_deny(paths):
    pats = DENY + [d for d in os.environ.get("AGENT_DOCS_DENY", "").splitlines() if d.strip()]
    rx = [re.compile(p, re.I) for p in pats]
    for p in paths:
        for n, line in enumerate(read(p).splitlines(), 1):
            if any(r.search(line) for r in rx):
                err(p, n, "personal path, host or secret store")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", help="git ref to compare with for the untouched-rule warnings")
    ap.add_argument("--github", action="store_true", help="print GitHub Actions annotations")
    args = ap.parse_args()

    cfg = load_config()
    repo = Repo(cfg)

    seen, docs = set(), []
    for p in repo.tracked:
        if not p.endswith(".md"):
            continue
        agent = os.path.basename(p) in ("CLAUDE.md", "AGENTS.md") or p.startswith(
            (".claude/rules/", ".claude/skills/", ".claude/agents/", ".claude/commands/"))
        if not agent:
            continue
        if not os.path.exists(p):
            err(p, 1, "dangling symlink")
            continue
        real = os.path.realpath(p)
        if real in seen:
            continue  # AGENTS.md -> CLAUDE.md: check the target once
        seen.add(real)
        docs.append(p)

    always_loaded = 0
    for doc in docs:
        text, lines = check_doc(repo, doc)
        fm, _ = split_frontmatter(text)
        base = os.path.basename(doc)
        if base in ("CLAUDE.md", "AGENTS.md"):
            if len(lines) > cfg["claude_md_max_lines"]:
                err(doc, 1, f"{len(lines)} lines; keep it at most {cfg['claude_md_max_lines']}")
            if os.path.dirname(doc) in ("", ".claude"):
                always_loaded += len(lines)
        if doc.startswith(".claude/rules/"):
            paths = parse_paths(fm)
            if paths is None:
                always_loaded += len(lines)
                if fm is not None:
                    err(doc, 1, "frontmatter without `paths:` (the only key rules read); the rule loads always")
            for g in paths or []:
                rx = glob_regex(g)
                if not any(rx.match(p) for p in repo.tracked):
                    err(doc, 1, f"paths glob `{g}` matches no tracked file")
        if doc.startswith(".claude/skills/") and base == "SKILL.md":
            check_skill(cfg, doc, fm, lines)

    if always_loaded > cfg["always_loaded_max_lines"]:
        err("CLAUDE.md", 1, f"always-loaded agent docs total {always_loaded} lines; "
                            f"budget {cfg['always_loaded_max_lines']}")

    claude = read("CLAUDE.md") if os.path.exists("CLAUDE.md") else ""
    expanded = claude + " ".join(
        pre + alt for pre, alts in re.findall(r"([A-Z][A-Z0-9_]*_)\{([A-Z0-9_,]+)\}", claude)
        for alt in alts.split(","))
    for v in sorted(repo.required_env):
        if v not in expanded:
            err("CLAUDE.md", 1, f"a justfile requires `{v}` but CLAUDE.md doesn't name it")
    if cfg["members_named_in_claude_md"] and os.path.exists("Cargo.toml"):
        m = re.search(r"members\s*=\s*\[(.*?)\]", read("Cargo.toml"), re.S)
        for member in re.findall(r'"([^"]+)"', m.group(1) if m else ""):
            if member not in claude:
                err("CLAUDE.md", 1, f"workspace member `{member}` isn't named in CLAUDE.md")

    def matching(globs):
        rxs = [glob_regex(g) for g in globs]
        return [p for p in repo.tracked if any(r.match(p) for r in rxs) and os.path.isfile(p)]

    cited = matching(cfg["deny_scan_extra"])
    check_deny(sorted(set(docs) | set(cited)))

    all_docs = "\n".join(read(p) for p in set(docs) | set(cited) | set(matching(cfg["env_doc_extra"])) | (
        {cfg["test_index"]} if cfg["test_index"] and os.path.exists(cfg["test_index"]) else set()))
    env_read = set()
    for p in matching(cfg["env_scan"]):
        env_read.update(re.findall(
                r'(?:env::var(?:_os)?\(|env!\(|getenv\(|environ\.get\(|environ\[|\$\{?)"?([A-Z][A-Z0-9]*_[A-Z0-9_]+)',
                read(p)))
    for name in sorted(env_read):
        if name.startswith(tuple(cfg["env_prefixes"])) and name not in all_docs:
            warn("CLAUDE.md", 1, f"the code reads `{name}` but no agent doc or doc names it")

    if cfg["test_index"] and os.path.exists(cfg["test_index"]):
        index = read(cfg["test_index"])
        named = set(re.findall(r"`([\w*]+)`", index))
        for t in sorted(repo.test_bins):
            if not any(fnmatch.fnmatch(t, pat) for pat in named):
                warn(cfg["test_index"], 1, f"test binary `{t}` isn't named here")

    if args.base:
        try:
            changed = set(git("diff", "--name-only", f"{args.base}...HEAD").split())
        except subprocess.CalledProcessError:
            changed = set()
            warn("CLAUDE.md", 1, f"could not diff against `{args.base}`; skipped the untouched-rule check")
        for doc in docs:
            if not doc.startswith(".claude/rules/") or doc in changed:
                continue
            pats = parse_paths(split_frontmatter(read(doc))[0]) or []
            rxs = [glob_regex(g) for g in pats]
            hits = sorted(c for c in changed if any(r.match(c) for r in rxs))
            if hits:
                warn(doc, 1, f"covers {len(hits)} changed file(s), e.g. {hits[0]}, but wasn't "
                             "changed; check it is still true")

    for kind, items in (("warning", warnings), ("error", errors)):
        for doc, line, msg in items:
            print(f"::{kind} file={doc},line={line}::{msg}" if args.github else f"{doc}:{line}: {kind}: {msg}")
    print(f"agent docs: {len(errors)} error(s), {len(warnings)} warning(s)", file=sys.stderr)
    return 1 if errors else 0


def check_skill(cfg, doc, fm, lines):
    folder = os.path.basename(os.path.dirname(doc))
    name = frontmatter_value(fm, "name")
    desc = frontmatter_value(fm, "description")
    if name != folder:
        err(doc, 2, f"skill `name` must equal its folder `{folder}`")
    if not re.match(r"^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$", folder):
        err(doc, 2, "skill name must be lowercase letters, digits and hyphens, at most 64")
    if not desc:
        err(doc, 3, "skill has no `description`")
    else:
        if len(desc) > cfg["description_max_chars"]:
            err(doc, 3, f"description is {len(desc)} chars; at most {cfg['description_max_chars']}")
        if re.search(r"<[A-Za-z/]", desc):
            err(doc, 3, "description contains an XML tag")
        if not desc.startswith(("'", '"')) and ": " in desc:
            err(doc, 3, "description contains `: `, which breaks the unquoted YAML value")
    if len(lines) > cfg["skill_max_lines"]:
        err(doc, 1, f"{len(lines)} lines; keep SKILL.md at most {cfg['skill_max_lines']}")
    for n, line in enumerate(lines, 1):
        for link in re.findall(r"\]\(([^)#\s]+)", line):
            if re.match(r"^[a-z]+:", link):
                continue
            target = os.path.normpath(os.path.join(os.path.dirname(doc), link))
            if not os.path.exists(target):
                err(doc, n, f"link `{link}` does not exist")
            elif target.endswith(".md"):
                t = read(target)
                if t.count("\n") > cfg["reference_contents_over_lines"] and "## Contents" not in t:
                    err(target, 1, f"over {cfg['reference_contents_over_lines']} lines without a `## Contents` section")


if __name__ == "__main__":
    sys.exit(main())
