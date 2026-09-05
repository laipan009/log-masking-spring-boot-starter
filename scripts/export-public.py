#!/usr/bin/env python3
"""Export a clean committed tree into a new local repository with one root commit."""

import argparse
from pathlib import Path
import subprocess


def git(directory, *args):
    return subprocess.check_output(["git", "-C", str(directory), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="New directory; must not already exist")
    args = parser.parse_args()
    source = Path(__file__).resolve().parent.parent
    destination = args.destination.expanduser().resolve()
    if destination.exists():
        parser.error("Destination already exists; choose a new directory")
    if source == destination or source in destination.parents:
        parser.error("Destination must be outside the source repository")
    if git(source, "status", "--porcelain").strip():
        parser.error("Commit or resolve source changes before exporting HEAD")

    entries = []
    for entry in git(source, "ls-tree", "-rz", "HEAD").split(b"\0"):
        if not entry:
            continue
        metadata, raw_path = entry.split(b"\t", 1)
        mode, kind, blob = metadata.decode().split()
        path = Path(raw_path.decode("utf-8"))
        if mode not in ("100644", "100755") or kind != "blob":
            parser.error(f"Only regular files are supported: {path}")
        if path.is_absolute() or ".." in path.parts or ".git" in path.parts:
            parser.error(f"Unsafe tree path: {path}")
        entries.append((mode, blob, path))

    destination.mkdir(parents=True)
    for mode, blob, path in entries:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git(source, "cat-file", "blob", blob))
        target.chmod(0o755 if mode == "100755" else 0o644)

    git(destination, "init", "--initial-branch=main")
    # Do not copy private author metadata or configuration from the original repository.
    git(destination, "config", "user.name", "laipan009")
    git(destination, "config", "user.email", "laipan009@users.noreply.github.com")
    git(destination, "add", "--all")
    git(destination, "-c", "commit.gpgsign=false", "commit", "-m", "Initial public source")
    if git(destination, "rev-list", "--count", "HEAD").strip() != b"1":
        raise RuntimeError("Expected exactly one commit")
    if git(destination, "rev-parse", "HEAD^{tree}") != git(source, "rev-parse", "HEAD^{tree}"):
        raise RuntimeError("Exported tree differs from source HEAD")
    print(f"Created {destination} with one root commit, identical files and no remote.")
    print("Review the snapshot, then follow docs/github-setup.md to publish it.")


if __name__ == "__main__":
    main()
