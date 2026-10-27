#!/usr/bin/env python3
"""Local release preparation.

This script no longer commits, pushes, creates GitHub releases or publishes to CurseForge/Modrinth.
It has two local phases:

    python tools/release.py --mod all              # stamp versions and build/verify every version
    # commit and push the prepared files yourself
    python tools/release.py --create-tags          # create the local v<version>-<mc> tags

Pushing those tags triggers the GitHub workflow that builds the release, creates the GitHub release
with every jar and sources jar, and publishes only the universal jars to CurseForge/Modrinth.

Without --mc, both phases operate on every version under versions/. --mc <mc> restricts them to one.
--local-only stops after the build phase and never creates tags. --bump moves the semantic version
line and stops; it never commits.
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import versioning

ROOT = Path(__file__).resolve().parent.parent
VERSIONS = ROOT / "versions.properties"
DIST_ROOT = ROOT / "dist"

# A release jar is [<abbrev>-]<id>-<version>-<kind>-<mc>.jar, matching jarFileName in
# gradle/versions.gradle. The id group allows dashes so a `bundle-` prefix matches. A sources jar
# carries -sources before the Minecraft suffix.
ALLOWED_JAR = re.compile(r"^[A-Za-z0-9_-]+-[0-9][0-9.]*-(fabric|neoforge|universal)(-sources)?-[0-9][0-9.]*\.jar$")


def log(msg):
    print(f"release: {msg}")


def die(msg):
    sys.exit(f"release: error: {msg}")


# --- process helpers ----------------------------------------------------------------


def run(cmd, dry=False, **kw):
    printable = " ".join(str(c) for c in cmd)
    if dry:
        print(f"  [dry-run] {printable}")
        return subprocess.CompletedProcess(cmd, 0, b"", b"")
    log(printable)
    return subprocess.run(cmd, cwd=ROOT, check=True, **kw)


def capture(cmd):
    return subprocess.run(cmd, cwd=ROOT, check=True, capture_output=True, text=True).stdout


def gradlew():
    # On Windows run the wrapper through cmd.exe: a bare "bash <win-path>" resolves to WSL's bash
    # (or a bash that strips the backslashes) and fails with "No such file or directory".
    if os.name == "nt":
        bat = ROOT / "gradlew.bat"
        if bat.exists():
            return ["cmd", "/c", str(bat)]
    return [str(ROOT / "gradlew")]


# --- versions and modules -----------------------------------------------------------


def read_props(path):
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def dist_dir(mc):
    return DIST_ROOT / mc


def set_prop(text, key, value):
    # [^\r\n]* rather than .* so a CRLF file gets a clean LF-terminated replacement.
    pattern = re.compile(rf"(?m)^{re.escape(key)}=[^\r\n]*")
    if not pattern.search(text):
        die(f"versions.properties has no '{key}=' line")
    return pattern.sub(f"{key}={value}", text)


def all_versions():
    """Every versions/<mc> directory that contains at least one module, sorted."""
    base = ROOT / "versions"
    if not base.is_dir():
        return []
    out = []
    for entry in sorted(base.iterdir()):
        if not entry.is_dir():
            continue
        if not (entry / "version.properties").is_file():
            continue
        if versioning.modules(entry.name):
            out.append(entry.name)
    return out


def selected_versions(args):
    if args.mc:
        if args.mc not in all_versions():
            die(f"unknown Minecraft version '{args.mc}' under versions/")
        return [args.mc]
    versions = all_versions()
    if not versions:
        die("no versions found under versions/")
    return versions


def module_ids(mc):
    return [props.get("id") for _, props in versioning.modules(mc) if props.get("id")]


def library_ids(mc):
    return [lib_id for lib_id, _, _ in versioning.libraries(mc)]


def primary_id(mc):
    libs = library_ids(mc)
    ids = module_ids(mc)
    if not ids:
        die(f"no modules found under versions/{mc}")
    return libs[0] if libs else ids[0]


def union_ids(versions):
    ordered = []
    for mc in versions:
        for mod_id in module_ids(mc):
            if mod_id not in ordered:
                ordered.append(mod_id)
    return ordered


def bump_all(versions, mod, stamp, dry=False):
    """Stamp the named component(s) once, across all selected versions."""
    ids = union_ids(versions)
    if mod == "all":
        targets = ids
    elif mod in ids:
        targets = [mod]
    else:
        die(f"unknown mod '{mod}' (known: {', '.join(ids)}, all)")
    text = VERSIONS.read_text(encoding="utf-8")
    current = read_props(VERSIONS)
    new_versions = dict(current)
    for comp in targets:
        if comp not in current:
            die(f"versions.properties has no '{comp}=' entry")
        new_versions[comp] = versioning.bump(current[comp], stamp)
        text = set_prop(text, comp, new_versions[comp])
    if not dry:
        VERSIONS.write_text(text, encoding="utf-8", newline="\n")
    log(f"stamped {', '.join(targets)} with {stamp}")
    return new_versions


def bump_semantic_all(versions, mod, part, dry=False):
    """Move the semantic version line for the named component(s), without a stamp or a commit."""
    ids = union_ids(versions)
    if mod == "all":
        targets = ids
    elif mod in ids:
        targets = [mod]
    else:
        die(f"unknown mod '{mod}' (known: {', '.join(ids)}, all)")
    text = VERSIONS.read_text(encoding="utf-8")
    current = read_props(VERSIONS)
    new_versions = dict(current)
    for comp in targets:
        if comp not in current:
            die(f"versions.properties has no '{comp}=' entry")
        new_versions[comp] = versioning.bump_semantic(current[comp], part)
        text = set_prop(text, comp, new_versions[comp])
    if not dry:
        VERSIONS.write_text(text, encoding="utf-8", newline="\n")
    log(f"bumped {', '.join(targets)} to the {part} line")
    return new_versions


# --- jars ---------------------------------------------------------------------------


def bundle_definitions(mc=None):
    """bundles.properties as an ordered {name: [module ids]} map.

    Only bare bundle.<name> keys are membership; bundle.<name>.title and .description are display
    strings and must not be counted as bundles. The mc argument is accepted for the multi-module
    workflows and ignored: bundles.properties is project-wide.
    """
    bundles_file = ROOT / "bundles.properties"
    if not bundles_file.exists():
        return {}
    props = read_props(bundles_file)
    out = {}
    for key in sorted(k for k in props if re.fullmatch(r"bundle\.[^.]+", k)):
        ids = [i.strip() for i in props[key].split(",") if i.strip()]
        if ids:
            out[key[len("bundle."):]] = ids
    return out


def bundle_name(key, version, mc):
    abbrev = (read_props(VERSIONS).get("abbrev") or "").strip()
    prefix = f"{abbrev}-" if abbrev else ""
    return f"{prefix}bundle-{key}-{version}-universal-{mc}.jar"


def _version_key(value):
    return [int(part) if part.isdigit() else 0 for part in value.split(".")]


def changed_bundles(mc, changed):
    """Bundles whose member modules changed."""
    return {name for name, ids in bundle_definitions(mc).items()
            if any(changed.get(module) for module in ids)}


def gameplay_ids(mc):
    """Every module id except the library, in directory order."""
    libs = set(library_ids(mc))
    return [module for module in module_ids(mc) if module not in libs]


def bundle_versions(mc, versions):
    """The highest version among each bundle's modules, checked against the built jar."""
    out = {}
    for key, ids in bundle_definitions(mc).items():
        version = max((versions[module] for module in ids), key=_version_key)
        expected = dist_dir(mc) / bundle_name(key, version, mc)
        if not expected.exists():
            built = sorted(dist_dir(mc).glob(bundle_name(key, "*", mc)))
            die(f"bundle '{key}' should be {expected.name} but dist/ has "
                + (", ".join(path.name for path in built) if built else "no such bundle jar"))
        out[key] = version
    return out


def expected_jars(mc):
    """Per-loader + sources + universal jars for every module, plus three jars per bundle."""
    ids = module_ids(mc)
    loaders = ["fabric", "neoforge"]
    loader_jars = len(ids) * len(loaders)
    sources = len(ids) * len(loaders)
    universal = len(ids)
    bundles = len(bundle_definitions())
    return loader_jars + sources + universal + bundles * (1 + len(loaders))


def verify(mc, dry):
    if dry:
        return
    jars = sorted(p.name for p in dist_dir(mc).glob("*.jar"))
    expected = expected_jars(mc)
    if len(jars) != expected:
        die(f"expected {expected} release jars for {mc}, found {len(jars)}: {jars}")
    bad = [j for j in jars if not ALLOWED_JAR.match(j)]
    if bad:
        die(f"unexpected files in dist/{mc}/: {bad}")
    log(f"{mc}: release jar set OK ({len(jars)} files)")


def collect(mc, dry):
    if not dry:
        out = dist_dir(mc)
        out.mkdir(parents=True, exist_ok=True)
        for path in out.iterdir():
            if path.is_dir():
                shutil.rmtree(path)
            else:
                path.unlink()
        release = ROOT / "build" / "release" / mc
        jars = sorted(release.glob("*.jar"))
        if not jars:
            die(f"no jars in {release}; did the build run?")
        for jar in jars:
            shutil.copy2(jar, out)
    verify(mc, dry)


# --- build --------------------------------------------------------------------------


def build(mc, dry, no_daemon=False, stamp=None):
    g = gradlew()
    daemon = ["--no-daemon"] if no_daemon else []
    pin = [f"-PversionStamp={stamp}"] if stamp else []
    # Library artifacts must be in repo/ before dependents compile.
    for lib_id in library_ids(mc):
        for loader in ("fabric", "neoforge"):
            run(g + daemon + pin + ["-p", f"versions/{mc}/{lib_id}/{loader}",
                     "publishMavenJavaPublicationToRepoRepository"], dry=dry)
    steps = [
        ["releaseLoaderJars"],
        ["releaseLoaderBundles"],
        ["releaseUniversal"],
        ["releaseUniversalBundles", "releaseJars"],
    ]
    for step in steps:
        run(g + daemon + pin + ["--console=plain", f"-Pmc={mc}", "--refresh-dependencies"] + step, dry=dry)


def changelog(tag, mc, dry):
    if dry:
        return
    dest = dist_dir(mc)
    dest.mkdir(parents=True, exist_ok=True)
    try:
        prev = capture(["git", "describe", "--tags", "--abbrev=0",
                        "--match", f"v*-{mc}", "HEAD^"]).strip()
    except subprocess.CalledProcessError:
        prev = ""
    if prev:
        log_range = [f"{prev}..HEAD", f"..HEAD"]
        header = f"Changes since {prev}"
    else:
        log_range = ["HEAD"]
        header = f"Initial release for Minecraft {mc}"
    lines = [header, ""]
    for rng in log_range:
        out = capture(["git", "log", rng, "--no-merges", "--pretty=format:- %s (`%h`)"])
        if out.strip():
            lines.append(out.strip())
            break
    (dest / "changelog.md").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    log(f"wrote dist/{mc}/changelog.md")


# --- tags ---------------------------------------------------------------------------


def tag_for(target, versions, explicit):
    ids = module_ids(target)
    if not ids:
        die(f"no modules found under versions/{target}")
    primary = primary_id(target)
    version = versions.get(primary)
    if not version:
        die(f"versions.properties has no version for '{primary}'")
    if explicit:
        if not explicit.endswith(f"-{target}"):
            die(f"tag {explicit!r} must end with -{target}")
        return explicit
    if len(version.split(".")) < 4:
        die(f"version {version} for {primary} is not stamped; run the prepare phase first")
    return f"v{version}-{target}"


def ensure_clean(allow_dirty=False):
    if allow_dirty:
        return
    if capture(["git", "status", "--porcelain"]).strip():
        die("working tree is dirty; commit or stash first")


def ensure_pushed():
    try:
        upstream = capture(["git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"]).strip()
    except subprocess.CalledProcessError:
        die("no upstream branch; push the prepared commit first")
    if not upstream:
        die("no upstream branch; push the prepared commit first")
    ahead = capture(["git", "rev-list", "--count", "@{u}..HEAD"]).strip()
    if ahead != "0":
        die(f"{ahead} commit(s) are not pushed; push the prepared commit first")


def create_tags(versions, args):
    ensure_clean()
    ensure_pushed()
    current = read_props(VERSIONS)
    tags = []
    for target in versions:
        tag = tag_for(target, current, args.tag)
        if tag in tags:
            die(f"duplicate tag {tag}")
        if not args.dry_run and capture(["git", "tag", "--list", tag]).strip():
            die(f"tag {tag} already exists")
        tags.append(tag)
    if args.dry_run:
        for tag in tags:
            print(f"  [dry-run] git tag {tag}")
        return
    for tag in tags:
        cmd = ["git"]
        if args.unsigned:
            cmd += ["-c", "tag.gpgSign=false", "tag", tag, "-m", f"Release {tag}"]
        else:
            cmd += ["tag", "-s", tag, "-m", f"Release {tag}"]
        run(cmd)
    log("created local tags: " + ", ".join(tags))
    for tag in tags:
        log(f"push it to trigger the release workflow: git push origin {tag}")


# --- main ---------------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description="Prepare versions and create release tags locally.")
    ap.add_argument("--mc", default=None,
                    help="Minecraft version under versions/ (default: every version)")
    ap.add_argument("--mod", default=None,
                    help="which component is changing: a module id or 'all'")
    ap.add_argument("--tag", default=None,
                    help="explicit tag name for --create-tags (requires --mc)")
    ap.add_argument("--create-tags", action="store_true",
                    help="create the local per-version tags from the committed versions.properties")
    ap.add_argument("--local-only", action="store_true",
                    help="stamp and build every selected version; never create tags")
    ap.add_argument("--bump", choices=("patch", "minor", "major"), default=None,
                    help="move the semantic version line and stop; never commits")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--unsigned", action="store_true",
                    help="create unsigned tags")
    ap.add_argument("--allow-dirty", action="store_true")
    ap.add_argument("--skip-build", action="store_true",
                    help="do not rebuild; verify the existing dist/<mc>/")
    ap.add_argument("--no-daemon", action="store_true",
                    help="do not reuse a Gradle daemon")
    args = ap.parse_args()

    versions = selected_versions(args)

    if args.bump:
        if args.create_tags or args.local_only:
            die("--bump cannot be combined with --create-tags or --local-only")
        if not args.mod:
            die("--bump requires --mod")
        ensure_clean(args.allow_dirty)
        bump_semantic_all(versions, args.mod, args.bump, args.dry_run)
        log("no commit or build was performed; review and commit versions.properties")
        return

    if args.create_tags:
        if args.local_only:
            die("--create-tags cannot be combined with --local-only")
        if args.tag and len(versions) != 1:
            die("--tag requires exactly one version; pass --mc")
        create_tags(versions, args)
        return

    if args.tag:
        die("--tag is only valid with --create-tags")
    if not args.mod:
        die("release preparation requires --mod, or use --create-tags / --bump")

    ensure_clean(args.allow_dirty)
    stamp = versioning.stamp()
    new_versions = bump_all(versions, args.mod, stamp, args.dry_run)

    if args.tag and len(versions) != 1:
        die("--tag requires exactly one version; pass --mc")

    for target in versions:
        tag = tag_for(target, new_versions, args.tag)
        if not args.skip_build:
            build(target, args.dry_run, args.no_daemon, stamp)
            collect(target, args.dry_run)
        else:
            verify(target, args.dry_run)
        changelog(tag, target, args.dry_run)

    log(f"prepared {', '.join(versions)} with stamp {stamp}")
    log(f"files to commit: {VERSIONS}" + (f", {ROOT / 'repo'}" if (ROOT / "repo").exists() else ""))
    if args.local_only:
        log("local-only: no tags, commit, push, release or platform upload was performed")
    else:
        log("next: commit and push the prepared files, then run:")
        log("  python tools/release.py --create-tags" + (f" --mc {args.mc}" if args.mc else ""))


if __name__ == "__main__":
    main()
