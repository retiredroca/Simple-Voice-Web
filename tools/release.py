#!/usr/bin/env python3
"""Local release driver.

Builds the release jars, commits the version bump, creates the GitHub tag + release, and then asks
CI to publish that release to CurseForge/Modrinth.

Typical use:

    python tools/release.py --mod all                 # build here, CI publishes it
    python tools/release.py --mod all --dry-run
    python tools/release.py --mod all --curseforge --modrinth

By default the GitHub release happens and then the publish-only workflow
(.github/workflows/publish-release.yml) is dispatched to upload the jars to CurseForge/Modrinth;
--no-ci-publish skips that. Passing --curseforge / --modrinth uploads from this machine instead,
reading CURSEFORGE_API_KEY / MODRINTH_TOKEN from the environment, and marks the release so a CI run
would skip it. The tag is a single series `v<api 3 parts>.<stamp>` (e.g. `v1.0.2.26091512`).

Component ids come from the project's own modules (versions/<mc>/*/module.properties), so the
`--mod` choices and the bundle names are never hardcoded here.
"""

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

import versioning

ROOT = Path(__file__).resolve().parent.parent
VERSIONS = ROOT / "versions.properties"
STATE = ROOT / "release-state.properties"
DIST_ROOT = ROOT / "dist"
UA = "mod-template-release"


def log(msg):
    print(f"release: {msg}")


def die(msg):
    sys.exit(f"release: error: {msg}")


# A release jar is [<abbrev>-]<id>-<version>-<kind>.jar, matching jarFileName in
# gradle/versions.gradle. Matching the shape rather than a list of ids means a new module needs no
# edit here, and it rejects every pre-rename shape (a leading loader, or a dot before the version),
# which a count check cannot. The id group allows dashes so an abbrev or a `bundle-` prefix matches.
# A sources jar is the same shape with `-sources` after its loader. That suffix exists only in the
# release: both builds name their sources jar <id>-<version>-sources.jar, because that is what a
# maven consumer looks for, and the release renames them so the two do not collide side by side.
ALLOWED_JAR = re.compile(r"^[A-Za-z0-9_-]+-[0-9][0-9.]*-(fabric|neoforge|universal)(-sources)?\.jar$")


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


# --- versions.properties ------------------------------------------------------------


def read_props(path):
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, _, value = line.partition("=")
            props[key.strip()] = value.strip()
    return props


def default_mc():
    return (read_props(ROOT / "gradle.properties").get("mc") or "1.21.1").strip()


def dist_dir(mc):
    return DIST_ROOT / mc


def set_prop(text, key, value):
    # [^\r\n]* rather than .* so a CRLF file gets a clean LF-terminated replacement.
    pattern = re.compile(rf"(?m)^{re.escape(key)}=[^\r\n]*")
    if not pattern.search(text):
        die(f"versions.properties has no '{key}=' line")
    return pattern.sub(f"{key}={value}", text)


# The project abbreviation (abbrev in versions.properties), prepended to every release jar name so
# jars from different projects cannot collide side by side. Matches jarFileName in gradle/versions.gradle.
ABBREV = (read_props(VERSIONS).get("abbrev") or "").strip()


def release_jar(name, version, kind):
    """Release jar name: [<abbrev>-]<id>-<version>-<kind>.jar, matching jarFileName in gradle/versions.gradle."""
    prefix = f"{ABBREV}-" if ABBREV else ""
    return f"{prefix}{name}-{version}-{kind}.jar"


def module_ids(mc):
    """Every module id under versions/<mc>/, in directory order."""
    return [props.get("id") for _, props in versioning.modules(mc) if props.get("id")]


def library_ids(mc):
    return [lib_id for lib_id, _, _ in versioning.libraries(mc)]


def bump(mc, mod, stamp, dry=False):
    """Re-version the named component(s). Only the ones named get a new version."""
    text = VERSIONS.read_text(encoding="utf-8")
    current = read_props(VERSIONS)
    ids = module_ids(mc)
    if mod == "all":
        targets = ids
    elif mod in ids:
        targets = [mod]
    else:
        die(f"unknown mod '{mod}' (known: {', '.join(ids)}, all)")
    changed = {name: False for name in ids}
    new_versions = dict(current)
    for comp in targets:
        new_versions[comp] = versioning.bump(current[comp], stamp)
        text = set_prop(text, comp, new_versions[comp])
        changed[comp] = True
    # newline="\n": without it Windows text mode writes CRLF, and CI then reads the version
    # values with a trailing \r (which breaks the publish file paths).
    # --dry-run must not touch the file: a later real run would then bump an already-bumped value
    # and produce a double stamp.
    if not dry:
        VERSIONS.write_text(text, encoding="utf-8", newline="\n")
    log(f"bumped {', '.join(targets)} to stamp {stamp}")
    return changed, new_versions


def bump_semantic(mc, mod, part, dry=False):
    """Move the semantic version line, dropping the stamp so the build re-stamps it.

    This is the only supported way to change <major>.<minor>.<patch>: the tooling stays the sole
    writer of versions.properties, so a hand edit cannot drift from what the build and the tag
    expect. A feature branch takes the next patch so its jars are distinguishable from the released
    line by filename; a release that needs more asks for minor or major explicitly.
    """
    text = VERSIONS.read_text(encoding="utf-8")
    current = read_props(VERSIONS)
    ids = module_ids(mc)
    if mod == "all":
        targets = ids
    elif mod in ids:
        targets = [mod]
    else:
        die(f"unknown mod '{mod}' (known: {', '.join(ids)}, all)")
    changed = {name: False for name in ids}
    new_versions = dict(current)
    for comp in targets:
        new_versions[comp] = versioning.bump_semantic(current[comp], part)
        text = set_prop(text, comp, new_versions[comp])
        changed[comp] = True
    if not dry:
        VERSIONS.write_text(text, encoding="utf-8", newline="\n")
    log(f"bumped {', '.join(targets)} to {part}: "
        + ", ".join(f"{c}={new_versions[c]}" for c in targets))
    return changed, new_versions


# --- build --------------------------------------------------------------------------


def build(mc, dry, no_daemon=False, stamp=None):
    g = gradlew()
    # Reuse a warm Gradle daemon across these invocations by default: each one would otherwise pay a
    # cold JVM start and reconfigure every included build. --no-daemon reproduces a CI-like cold
    # build; CI always passes it, where each run is a fresh container.
    daemon = ["--no-daemon"] if no_daemon else []
    # One stamp, computed once, by versioning.stamp() and written into versions.properties for the
    # component being released. Gradle would otherwise compute its own stamp at build time, and the
    # two can disagree in two ways: an hour boundary between this call and the build, and any
    # component still holding a bare "1.0.0" (a project generated from the template), which
    # stampVersion() appends a fresh stamp to at build time. Either way the jar names below are built
    # from the python value while the jars on disk carry Gradle's, so every upload path misses and
    # skips silently. Passing the stamp in makes the two agree by construction.
    pin = [f"-PversionStamp={stamp}"] if stamp else []
    # Bootstrap: library artifacts must be in repo/ before dependents compile, because dependents
    # require a version floor ([<floor>,<upper>)).
    for lib_id in library_ids(mc):
        for loader in ("fabric", "neoforge"):
            run(g + daemon + pin + ["-p", f"versions/{mc}/{lib_id}/{loader}",
                     "publishMavenJavaPublicationToRepoRepository"], dry=dry)
    # Staged build (loader jars -> loader bundles -> universal -> universal bundles) as separate
    # invocations, so a failure names the layer that broke. The last one unions the staged dirs
    # into build/release/<mc>/ and republishes the libraries to ./repo.
    # --refresh-dependencies: the floor range was just republished, so don't use a cached resolution.
    steps = [
        ["releaseLoaderJars"],
        ["releaseLoaderBundles"],
        ["releaseUniversal"],
        ["releaseUniversalBundles", "releaseJars", "publishLibrary"],
    ]
    for step in steps:
        run(g + daemon + pin + ["--console=plain", f"-Pmc={mc}", "--refresh-dependencies"] + step, dry=dry)


def bundle_definitions():
    """bundles.properties as an ordered {name: [module ids]} map."""
    bundles_file = ROOT / "bundles.properties"
    if not bundles_file.exists():
        return {}
    props = read_props(bundles_file)
    out = {}
    for key in sorted(k for k in props if k.startswith("bundle.")):
        ids = [i.strip() for i in props[key].split(",") if i.strip()]
        if ids:
            out[key[len("bundle."):]] = ids
    return out


def expected_jars(mc):
    """How many jars the release set must contain: per-loader + sources + universal + bundles.

    Each module contributes one jar per loader, one sources jar per loader, and one universal jar;
    each bundle contributes a universal container plus one per-loader container, so three files per
    bundle. A bundle has no sources of its own: its sources are its members', which are already here.
    """
    ids = module_ids(mc)
    loaders = ["fabric", "neoforge"]
    loader_jars = len(ids) * len(loaders)
    sources = len(ids) * len(loaders)
    universal = len(ids)
    bundles = 0
    bundles_file = ROOT / "bundles.properties"
    if bundles_file.exists():
        bundles = len([k for k in read_props(bundles_file) if k.startswith("bundle.")])
    return loader_jars + sources + universal + bundles * (1 + len(loaders))


def collect(mc, dry):
    if not dry:
        # Clear this version's staged artifacts; the tracked dist/.gitkeep stays at the root.
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


def verify(mc, dry):
    if dry:
        return
    jars = sorted(p.name for p in dist_dir(mc).glob("*.jar"))
    expected = expected_jars(mc)
    if len(jars) != expected:
        die(f"expected {expected} release jars, found {len(jars)}: {jars}")
    # The count alone cannot tell a correct jar from a stale one: a set of pre-rename jars has the
    # same length as the current set, so `build/release/<mc>` leftovers from an earlier naming
    # scheme would be copied into dist/<mc>/ and published. Check the shape too.
    bad = [j for j in jars if not ALLOWED_JAR.match(j)]
    if bad:
        die(f"unexpected files in dist/{mc}/ (not [<abbrev>-]<id>-<version>-<kind>.jar): {bad}")
    log(f"release jar set OK ({len(jars)} files)")


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


# A bundle's version is not computed here. Gradle decides it (1.0.0.<newest stamp among the
# contents' *built* versions) and writes it into the filename; the only correct way to learn it is to
# read it back off the built jar. The two helpers that used to predict it from versions.properties
# plus a fresh versioning.stamp() were never called, and were wrong twice over: a second clock read
# could disagree with the one the build used, and a component still holding a bare "1.0.0" is stamped
# by Gradle at build time, which the prediction could not see. Read the filename instead, as
# mc-storage-area-network's tools/release.py bundle_versions() does.


# --- git ----------------------------------------------------------------------------


def repo_slug():
    try:
        url = capture(["git", "remote", "get-url", "origin"]).strip()
    except subprocess.CalledProcessError:
        url = ""
    match = re.search(r"github\.com[:/](.+?)(?:\.git)?$", url)
    return match.group(1) if match else ""


def current_branch():
    return capture(["git", "rev-parse", "--abbrev-ref", "HEAD"]).strip()


def ensure_clean(allow_dirty):
    if allow_dirty:
        return
    if capture(["git", "status", "--porcelain"]).strip():
        die("working tree is dirty; commit/stash first or pass --allow-dirty")


def commit(paths, message, dry, sign=True):
    # Only stage paths that exist: a project without a library module has no repo/ directory, and
    # `git add -- <missing>` aborts the whole release.
    existing = [str(p) for p in paths if Path(p).exists()]
    if not existing:
        log("nothing to stage; skipping commit")
        return False
    run(["git", "add", "--"] + existing, dry=dry)
    if not dry and capture(["git", "diff", "--cached", "--name-only"]).strip() == "":
        log("nothing staged; skipping commit")
        return False
    cmd = ["git"]
    if not sign:
        cmd += ["-c", "commit.gpgsign=false"]
    cmd += ["commit", "-m", message]
    run(cmd, dry=dry)
    return True


def tag_release(tag, message, sign, dry):
    """Create the release tag locally (signed unless --unsigned) so it carries a GPG signature."""
    if not dry and capture(["git", "tag", "--list", tag]).strip():
        die(f"tag {tag} already exists; delete it or release with --tag")
    cmd = ["git"]
    if sign:
        cmd += ["tag", "-s", tag, "-m", message]
    else:
        cmd += ["-c", "tag.gpgSign=false", "tag", tag, "-m", message]
    run(cmd, dry=dry)


# --- GitHub API ---------------------------------------------------------------------


def github_token():
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token
    out = subprocess.run(
        ["git", "credential", "fill"],
        input=b"protocol=https\nhost=github.com\n\n",
        capture_output=True,
    ).stdout.decode()
    for line in out.splitlines():
        if line.startswith("password="):
            return line[len("password="):]
    die("no GITHUB_TOKEN and no stored github.com credential")


def gh_request(method, url, token, data=None, content_type="application/vnd.github+json"):
    body = json.dumps(data).encode() if data is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"token {token}")
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", UA)
    req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = resp.read().decode()
            return resp.status, (json.loads(payload) if payload else {})
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def github_release(token, tag, target, body, dry):
    """Create the release as a draft; publish_release() flips it once the jars are attached.

    A published release fires `release: published` immediately, which starts the publish workflow
    while the asset upload is still running - the workflow then downloads a partial jar set and
    fails its count check. Drafting defers that event until the release is complete.
    """
    if dry:
        print(f"  [dry-run] create GitHub release {tag} at {target} (draft)")
        return {"id": 0, "upload_url": ""}
    status, resp = gh_request("POST", f"https://api.github.com/repos/{repo_slug()}/releases", token, {
        "tag_name": tag,
        "target_commitish": target,
        "name": tag,
        "body": body,
        "draft": True,
        "prerelease": False,
    })
    if status not in (200, 201):
        die(f"GitHub release failed ({status}): {resp}")
    log(f"GitHub release {tag} created (draft)")
    return resp


def upload_assets(token, release_id, mc, dry):
    jars = sorted(dist_dir(mc).glob("*.jar"))
    for jar in jars:
        if dry:
            print(f"  [dry-run] upload {jar.name}")
            continue
        url = f"https://uploads.github.com/repos/{repo_slug()}/releases/{release_id}/assets?name={jar.name}"
        req = urllib.request.Request(url, data=jar.read_bytes(), method="POST")
        req.add_header("Authorization", f"token {token}")
        req.add_header("Content-Type", "application/java-archive")
        req.add_header("User-Agent", UA)
        with urllib.request.urlopen(req, timeout=600) as resp:
            log(f"uploaded {jar.name} ({resp.status})")


def publish_release(token, release_id, dry):
    """Flip the draft release to published, now that every jar is attached.

    This is the single trigger for the platform publish: un-drafting fires `release: published`,
    which starts publish-release.yml. Nothing else dispatches it, so a release cannot be published
    twice.
    """
    url = f"https://api.github.com/repos/{repo_slug()}/releases/{release_id}"
    if dry:
        print(f"  [dry-run] PATCH {url} draft=false")
        return
    status, resp = gh_request("PATCH", url, token, {"draft": False})
    if status != 200:
        die(f"GitHub release publish failed ({status}): {resp}")
    log("published the GitHub release; the release event starts the platform publish")


# --- release-state.properties -------------------------------------------------------


def set_state(key, value):
    lines = []
    if STATE.exists():
        lines = STATE.read_text(encoding="utf-8").splitlines()
    pattern = re.compile(rf"^{re.escape(key)}=")
    replaced = False
    for i, line in enumerate(lines):
        if pattern.match(line):
            lines[i] = f"{key}={value}"
            replaced = True
            break
    if not replaced:
        lines.append(f"{key}={value}")
    STATE.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


# --- orchestration ------------------------------------------------------------------


def main():
    ap = argparse.ArgumentParser(description="Build and publish a release locally.")
    ap.add_argument("--mc", default=None,
                    help="Minecraft version folder under versions/ (default: mc in gradle.properties)")
    ap.add_argument("--mod", required=True,
                    help="which component is changing: a module id or 'all' (the tag is always "
                         "v<<library 3 parts>>.<stamp>-<mc>)")
    ap.add_argument("--tag", default=None)
    ap.add_argument("--curseforge", action="store_true", help="also upload to CurseForge")
    ap.add_argument("--modrinth", action="store_true", help="also upload to Modrinth")
    ap.add_argument("--no-ci-publish", action="store_true",
                    help="do not ask CI to publish the release to CurseForge/Modrinth")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--unsigned", action="store_true", help="do not GPG-sign the commit and tag")
    ap.add_argument("--allow-dirty", action="store_true")
    ap.add_argument("--no-push", action="store_true")
    ap.add_argument("--skip-build", action="store_true",
                    help="reuse the existing dist/<mc>/")
    ap.add_argument("--no-daemon", action="store_true",
                    help="do not reuse a Gradle daemon (slower; reproduces a CI-like cold build)")
    ap.add_argument("--local-only", action="store_true",
                    help="build and stage the jars locally only: no commit, tag, push, GitHub release "
                         "or platform/CI publishing. versions.properties is still bumped so the local "
                         "jars carry the next version; undo with `git checkout -- versions.properties`.")
    ap.add_argument("--bump", choices=("patch", "minor", "major"), default=None,
                    help="move the semantic version line and stop: no build, no tag, no push. A feature "
                         "branch takes the next patch so its jars are distinguishable from the released "
                         "line by filename. Commits the change; --dry-run shows it without writing.")
    ap.add_argument("--commit", action="store_true",
                    help="with --bump, also commit the change (otherwise it is left staged for review)")
    args = ap.parse_args()

    dry = args.dry_run
    mc = args.mc or default_mc()
    ensure_clean(args.allow_dirty)

    # --bump is a standalone, version-only operation: it writes versions.properties and stops. It
    # never builds or publishes, so it is safe to run in a worktree or a plain checkout.
    if args.bump:
        changed, versions = bump_semantic(mc, args.mod, args.bump, dry)
        if not dry and args.commit:
            commit([VERSIONS], f"Bump {args.mod} to the {args.bump} line", dry, not args.unsigned)
        elif not dry:
            log("left uncommitted; review with `git diff versions.properties`, then "
                "`git commit -am` or re-run with --commit")
        return

    slug = repo_slug()

    libs = library_ids(mc)
    ids = module_ids(mc)
    if not ids:
        die(f"no modules found under versions/{mc}/")
    # The tag series is keyed on the library's version line; a project without a library (the
    # single-mod template) uses its first module instead.
    primary = libs[0] if libs else ids[0]

    stamp = versioning.stamp()
    changed, versions = bump(mc, args.mod, stamp, dry)

    # Dependent modules resolve a library through a floor range computed from versions.properties
    # at configuration time (see gradle/versions.gradle), so a bump needs no extra rewrite here.

    if args.tag:
        tag = args.tag
        if not tag.endswith(f"-{mc}"):
            die(f"tag {tag!r} must end with -{mc} (the Minecraft version)")
    else:
        tag = versioning.tag(versions[primary], stamp, mc)
    log(f"tag: {tag}")

    if not args.skip_build:
        build(mc, dry, args.no_daemon, stamp)
        collect(mc, dry)
    else:
        verify(mc, dry)
    changelog(tag, mc, dry)

    if args.local_only:
        log(f"local-only: built the {tag} jars into build/release/{mc}/ and dist/{mc}/")
        log("no commit, tag, push, GitHub release or platform publishing was performed")
        log("versions.properties was bumped; undo with: git checkout -- versions.properties")
        return

    sign = not args.unsigned

    # Commit the bump (so the tag points at the bumped versions), then push.
    bump_paths = [VERSIONS, ROOT / "repo"]
    if commit(bump_paths, f"Release {tag}: bump {args.mod} version", dry, sign):
        if not args.no_push and slug:
            run(["git", "push", "origin", current_branch()], dry=dry)
    tag_release(tag, f"Release {tag}", sign, dry)
    if not args.no_push and slug:
        run(["git", "push", "origin", tag], dry=dry)

    if not slug:
        log("no 'origin' GitHub remote; release is local only (committed + tagged, not pushed)")
        if args.curseforge or args.modrinth:
            die("platform publishing needs a GitHub remote and CI; push the project first")
        log("done")
        return

    # GitHub release against the tag we just pushed.
    token = github_token()
    target = capture(["git", "rev-parse", "HEAD"]).strip()
    # The marker tells the publish workflow to skip this release. It is set when the platforms are
    # published from here (--curseforge/--modrinth) or when they must not be published at all
    # (--no-ci-publish); otherwise the release event publishes them.
    marker = "<!-- mod-template:published -->" if (args.curseforge or args.modrinth or args.no_ci_publish) else ""
    release = github_release(token, tag, target, marker, dry)
    if release:
        upload_assets(token, release["id"], mc, dry)
        # Publish only now that every jar is attached: this is what fires `release: published`,
        # which is the single trigger for the CI platform publish.
        publish_release(token, release["id"], dry)

    if args.curseforge:
        cf = os.environ.get("CURSEFORGE_API_KEY")
        if not cf:
            die("--curseforge needs CURSEFORGE_API_KEY in the environment")
        cf_publish(cf, os.environ.get("CURSEFORGE_PROJECT_ID", ""), mc, changed, versions, dry)

    if args.modrinth:
        mr = os.environ.get("MODRINTH_TOKEN")
        if not mr:
            die("--modrinth needs MODRINTH_TOKEN in the environment")
        modrinth_sync(mr, os.environ.get("MODRINTH_ID", ""), mc, changed, versions, dry)

    if (args.curseforge or args.modrinth):
        for name in changed:
            if changed[name]:
                set_state(f"{name}.version.{mc}", versions[name])
        if commit([STATE], f"Release {tag}: update release state", dry, sign) and not args.no_push:
            run(["git", "push", "origin", current_branch()], dry=dry)

    log("done")


# --- CurseForge ---------------------------------------------------------------------


def cf_headers(token):
    return {"X-Api-Token": token, "Accept": "application/json", "User-Agent": UA}


def cf_upload(token, project_id, file_path, metadata, dry, attempt_retries=5):
    import time
    boundary = "----modtemplateboundary"
    meta_json = json.dumps(metadata).encode()
    file_bytes = file_path.read_bytes()
    body = b""
    body += f"--{boundary}\r\n".encode()
    body += b'Content-Disposition: form-data; name="metadata"\r\n'
    body += b"Content-Type: application/json\r\n\r\n"
    body += meta_json + b"\r\n"
    body += f"--{boundary}\r\n".encode()
    body += f'Content-Disposition: form-data; name="file"; filename="{file_path.name}"\r\n'.encode()
    body += b"Content-Type: application/java-archive\r\n\r\n"
    body += file_bytes + b"\r\n"
    body += f"--{boundary}--\r\n".encode()

    url = f"https://minecraft.curseforge.com/api/projects/{project_id}/upload-file"
    for attempt in range(1, attempt_retries + 1):
        if dry:
            print(f"  [dry-run] CurseForge upload {file_path.name}")
            return
        req = urllib.request.Request(url, data=body, method="POST")
        for key, value in cf_headers(token).items():
            req.add_header(key, value)
        req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
        try:
            with urllib.request.urlopen(req, timeout=900) as resp:
                log(f"CurseForge upload {file_path.name} ({resp.status})")
                return
        except urllib.error.HTTPError as e:
            log(f"CurseForge upload attempt {attempt} failed for {file_path.name}: {e.read().decode()}")
        except urllib.error.URLError as e:
            log(f"CurseForge upload attempt {attempt} failed for {file_path.name}: {e}")
        if attempt < attempt_retries:
            time.sleep(15)
    die(f"CurseForge upload gave up on {file_path.name}")


def cf_publish(token, project_id, mc, changed, versions, dry):
    """Upload the universal jars of the components that changed.

    CurseForge files are added, never replaced, so the caller prunes older files on the site.
    """
    if not project_id:
        die("--curseforge needs CURSEFORGE_PROJECT_ID in the environment")
    mc_dir = ROOT / "versions" / mc
    default_loader = "Server"
    java_versions = versioning.load_props(mc_dir / "version.properties").get("java_version", "21")
    uploaded = 0
    for name, is_changed in changed.items():
        if not is_changed:
            continue
        jar = dist_dir(mc) / release_jar(name, versions[name], "universal")
        if not jar.exists():
            log(f"skip CurseForge: {jar.name} not in dist/{mc}/")
            continue
        changelog_path = dist_dir(mc) / "changelog.md"
        metadata = {
            "changelog": changelog_path.read_text(encoding="utf-8") if changelog_path.exists() else "",
            "changelogType": "markdown",
            "displayName": f"{name} {versions[name]}",
            "gameVersions": [mc, java_versions, default_loader],
            "releaseType": "release",
        }
        cf_upload(token, project_id, jar, metadata, dry)
        uploaded += 1
    if uploaded == 0:
        log("CurseForge: nothing to upload")


# --- Modrinth -----------------------------------------------------------------------


def modrinth_sync(token, project, mc, changed, versions, dry):
    if not project:
        die("--modrinth needs MODRINTH_ID in the environment")
    headers = {"Authorization": token, "User-Agent": UA}

    def request(method, url, data=None, raw=None):
        body = raw if raw is not None else (json.dumps(data).encode() if data is not None else None)
        req = urllib.request.Request(url, data=body, method=method)
        for key, value in headers.items():
            req.add_header(key, value)
        if raw is None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=600) as resp:
                payload = resp.read().decode()
                return resp.status, (json.loads(payload) if payload else {})
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode()

    for name, is_changed in changed.items():
        if not is_changed:
            continue
        jar = dist_dir(mc) / release_jar(name, versions[name], "universal")
        if not jar.exists():
            log(f"skip Modrinth: {jar.name} not in dist/{mc}/")
            continue
        version_number = versions[name]
        if dry:
            print(f"  [dry-run] Modrinth create version {name} {version_number}")
            continue
        data = {
            "name": f"{name} {version_number}",
            "version_number": version_number,
            "project_id": project,
            "game_versions": [mc],
            "loaders": ["fabric", "neoforge"],
            "version_type": "release",
            "featured": False,
            "dependencies": [],
            "file_parts": [jar.name],
        }
        boundary = "----modtemplateboundary"
        body = b""
        body += f"--{boundary}\r\n".encode()
        body += b'Content-Disposition: form-data; name="data"\r\n'
        body += b"Content-Type: application/json\r\n\r\n"
        body += json.dumps(data).encode() + b"\r\n"
        body += f"--{boundary}\r\n".encode()
        body += f'Content-Disposition: form-data; name="{jar.name}"; filename="{jar.name}"\r\n'.encode()
        body += b"Content-Type: application/java-archive\r\n\r\n"
        body += jar.read_bytes() + b"\r\n"
        body += f"--{boundary}--\r\n".encode()
        status, resp = request("POST", "https://api.modrinth.com/v2/version", raw=body)
        if status in (200, 201):
            log(f"Modrinth version {version_number} created ({resp.get('id')})")
        else:
            log(f"Modrinth upload failed ({status}): {resp}")


if __name__ == "__main__":
    try:
        main()
    finally:
        # An idle Gradle daemon keeps Loom's cached mapping jars open, and the next build needs
        # exclusive access to re-merge them (FileSystemException: "being used by another process").
        # Stopping it here releases those handles; the next run pays one cold start instead.
        try:
            subprocess.run(gradlew() + ["--stop"], cwd=ROOT,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
        except Exception:
            pass
