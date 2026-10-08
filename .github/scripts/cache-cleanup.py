#!/usr/bin/env python3
"""Delete GitHub Actions cache entries that no later run will restore.

The repository's caches share one 10 GB budget, and past it GitHub evicts the
least recently used entries, including the ones CI needs next. This removes
what is already dead, so eviction never has to:

  --ref REF   every entry on REF: a closed pull request (refs/pull/N/merge)
              or a deleted branch (refs/heads/NAME). A ref's caches can only
              be restored by that ref, so once it is gone they are garbage.
  --sweep     every entry on a ref that is gone (closed PR, deleted branch,
              tag older than TAG_DAYS), every entry superseded by a newer one
              with the same key prefix on the same ref (a Gradle or model
              cache whose hashed key changed), and every BuildKit layer the
              newest build on its ref no longer touched.

--dry-run prints what would go and deletes nothing. Needs `gh` with a token
that has `actions: write` (GH_TOKEN) and GH_REPO or GITHUB_REPOSITORY.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone

# A BuildKit blob counts as unused when the newest index on its ref was
# written this long after the blob was last touched. A build reads or
# re-uploads every layer it uses, so anything older belongs to an earlier
# image; the margin covers builds that overlap.
BUILDKIT_MARGIN = timedelta(hours=2)
# A superseded entry must be this much older than the newest one with the
# same prefix, so two runs that alternate between keys don't delete each
# other's caches.
SUPERSEDED_MARGIN = timedelta(hours=1)
TAG_DAYS = 2

HASH_SUFFIX = re.compile(r"-[0-9a-f]{8,}$")


def gh(*args, check=True):
    return subprocess.run(["gh", *args], capture_output=True, text=True, check=check)


def when(stamp):
    return datetime.fromisoformat(stamp.replace("Z", "+00:00"))


def list_caches(repo):
    out = gh(
        "api", "--paginate", f"repos/{repo}/actions/caches?per_page=100",
        "--jq", ".actions_caches[] | @json",
    ).stdout
    return [json.loads(line) for line in out.splitlines() if line.strip()]


def branches(repo):
    out = gh("api", "--paginate", f"repos/{repo}/branches?per_page=100", "--jq", ".[].name").stdout
    return set(out.split())


def pr_open(repo, number, memo={}):
    if number not in memo:
        state = gh("api", f"repos/{repo}/pulls/{number}", "--jq", ".state", check=False)
        # A PR we can't read is treated as open: never delete on doubt.
        memo[number] = state.returncode != 0 or state.stdout.strip() == "open"
    return memo[number]


def prefix(key):
    """The key with its trailing hash segments stripped."""
    while True:
        stripped = HASH_SUFFIX.sub("", key)
        if stripped == key:
            return key
        key = stripped


def is_buildkit(key):
    return key.startswith(("buildkit-", "index-buildkit-"))


def dead_ref(repo, ref, live_branches, now, newest):
    if ref.startswith("refs/heads/"):
        return ref[len("refs/heads/"):] not in live_branches
    m = re.fullmatch(r"refs/pull/(\d+)/(merge|head)", ref)
    if m:
        return not pr_open(repo, m.group(1))
    if ref.startswith("refs/tags/"):
        return now - newest > timedelta(days=TAG_DAYS)
    return False


def sweep(repo, caches):
    now = datetime.now(timezone.utc)
    live = branches(repo)
    by_ref = {}
    for c in caches:
        by_ref.setdefault(c["ref"], []).append(c)

    doomed = []
    for ref, entries in sorted(by_ref.items()):
        newest = max(when(c["last_accessed_at"]) for c in entries)
        if dead_ref(repo, ref, live, now, newest):
            doomed += [(c, f"{ref} is gone") for c in entries]
            continue

        groups = {}
        for c in entries:
            if not is_buildkit(c["key"]):
                groups.setdefault(prefix(c["key"]), []).append(c)
        for group in groups.values():
            latest = max(when(c["last_accessed_at"]) for c in group)
            for c in group:
                if latest - when(c["last_accessed_at"]) > SUPERSEDED_MARGIN:
                    doomed.append((c, "superseded by a newer key"))

        indexes = [when(c["last_accessed_at"]) for c in entries if c["key"].startswith("index-buildkit-")]
        if indexes:
            cutoff = max(indexes) - BUILDKIT_MARGIN
            for c in entries:
                if is_buildkit(c["key"]) and when(c["last_accessed_at"]) < cutoff:
                    doomed.append((c, "layer the newest build didn't use"))
    return doomed


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--ref")
    mode.add_argument("--sweep", action="store_true")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    repo = os.environ.get("GH_REPO") or os.environ["GITHUB_REPOSITORY"]
    caches = list_caches(repo)
    total = sum(c["size_in_bytes"] for c in caches)
    print(f"{repo}: {len(caches)} entries, {total / 1e9:.2f} GB")

    if args.ref:
        doomed = [(c, f"{args.ref} is gone") for c in caches if c["ref"] == args.ref]
    else:
        doomed = sweep(repo, caches)

    freed = 0
    failed = 0
    for c, why in doomed:
        size = c["size_in_bytes"]
        print(f"{'would delete' if args.dry_run else 'delete'} {size / 1e6:9.1f} MB  {c['ref']}  {c['key']}  ({why})")
        if args.dry_run:
            freed += size
            continue
        r = gh("api", "-X", "DELETE", f"repos/{repo}/actions/caches/{c['id']}", check=False)
        if r.returncode == 0:
            freed += size
        else:
            # Another run may have deleted or evicted it first.
            failed += 1
            print(f"  not deleted: {r.stderr.strip()}")
    verb = "would free" if args.dry_run else "freed"
    print(f"{len(doomed) - failed} entries, {verb} {freed / 1e9:.2f} GB of {total / 1e9:.2f} GB")


if __name__ == "__main__":
    sys.exit(main())
