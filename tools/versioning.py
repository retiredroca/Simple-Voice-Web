#!/usr/bin/env python3
"""Version helpers shared by the release tooling and CI.

Scheme: ``<major>.<minor>.<patch>.<yymmddhh>`` — e.g. ``1.0.2.26091512``.

* the trailing ``yymmddhh`` stamp is bumped automatically, in the timezone chosen when the project
  was created (see ``TIMEZONE`` below; replace it by editing this line);
* the semantic line (first three components) moves via `bump_semantic` -- run it through
  `tools/release.py --bump patch|minor|major`, or let the branch/merge workflow do it; it is
  never hand-edited, so the tooling stays the only thing that writes versions.properties;
* the tag is ``v<major>.<minor>.<patch>.<stamp>`` (single series, e.g. ``v1.0.2.26091512``).

Library modules (``module.properties`` with ``library=true``) are depended on by the gameplay
modules through a **floor range** derived from the library's version line, so an older library can
never silently satisfy a dependent. The range is computed at configuration time from
``versions.properties`` (``gradle/versions.gradle`` -> ``moduleFloor``), which is the single source
of truth: a version bump needs no extra rewriting.

CLI:
    python tools/versioning.py stamp
    python tools/versioning.py bump <value> <stamp>
    python tools/versioning.py tag  --api <apiVersion> --stamp <stamp>
    python tools/versioning.py floor --api <apiVersion> [--mc <mc>]   # report the floor + dependents
"""

import argparse
import datetime
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# The timezone the version stamp is taken in, chosen when the project was created
# (`create-project` substitutes this line). Accepts an IANA zone name ("America/New_York") or a
# fixed UTC offset ("UTC", "-10:00", "+05:30"). Fixed offsets always work; an IANA name needs a
# timezone database, which this resolver gets from Python's zoneinfo or, failing that, from the
# JDK's java.time (already required to run Gradle).
TIMEZONE = "Pacific/Honolulu"

# Range upper bound written into the floor. Keep in step with the library's major version line.
FLOOR_UPPER = "1.1"


# --- timezone -----------------------------------------------------------------------


def _fixed_offset(name: str):
    """A datetime.timezone for "UTC", "Z" or a "+hh:mm"/"-hh:mm" style offset, else None."""
    text = name.strip().upper()
    if text in ("UTC", "Z", "GMT", "UTC+0", "UTC-0", "UTC+00:00", "UTC-00:00"):
        return datetime.timezone.utc
    match = re.fullmatch(r"(?:UTC|GMT)?([+-])(\d{1,2})(?::?(\d{2}))?", name.strip())
    if not match:
        return None
    sign = 1 if match.group(1) == "+" else -1
    hours = int(match.group(2))
    minutes = int(match.group(3) or 0)
    if hours > 23 or minutes > 59:
        return None
    return datetime.timezone(sign * datetime.timedelta(hours=hours, minutes=minutes),
                             name.strip())


_JAVA_HELPER = (
    'import java.time.Instant; import java.time.ZoneId;'
    'public class TZ { public static void main(String[] a) {'
    'ZoneId z = ZoneId.of(a[0]);'
    'System.out.println(z.getRules().getOffset(Instant.now()).getTotalSeconds());'
    '} }'
)


def _jdk_tool(java_home: str, tool: str) -> str:
    """A JDK binary by name: JAVA_HOME/bin/<tool> when set, else the bare name from PATH."""
    exe = tool + (".exe" if os.name == "nt" else "")
    if java_home:
        candidate = Path(java_home) / "bin" / exe
        if candidate.exists():
            return str(candidate)
    return exe


def _java_zone(name: str):
    """Resolve an IANA zone through java.time, so names work without tzdata.

    Only the current offset is read (fixed for the run), which is exactly how the stamp uses it;
    the result is a fixed-offset zone, not a DST-aware ZoneId.
    """
    import tempfile
    java_home = os.environ.get("JAVA_HOME", "")
    try:
        with tempfile.TemporaryDirectory(prefix="versioning-tz-") as tmp:
            source = Path(tmp) / "TZ.java"
            source.write_text(_JAVA_HELPER, encoding="utf-8")
            compile = subprocess.run([_jdk_tool(java_home, "javac"), str(source)],
                                     capture_output=True, text=True, timeout=60, check=False)
            if compile.returncode != 0:
                return None
            run = subprocess.run([_jdk_tool(java_home, "java"), "-cp", tmp, "TZ", name],
                                 capture_output=True, text=True, timeout=60, check=False)
            if run.returncode != 0 or not run.stdout.strip():
                return None
            return datetime.timezone(datetime.timedelta(seconds=int(run.stdout.strip())), name)
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def _resolve_zone(name: str):
    fixed = _fixed_offset(name)
    if fixed is not None:
        return fixed
    try:
        from zoneinfo import ZoneInfo
        return ZoneInfo(name)
    except Exception:
        pass
    return _java_zone(name)


def now() -> datetime.datetime:
    """Current time in the project's stamp timezone."""
    zone = _resolve_zone(TIMEZONE)
    if zone is None:
        sys.exit(f"versioning: cannot resolve timezone '{TIMEZONE}'. Use an IANA name with tzdata "
                 f"installed (pip install tzdata) or a fixed offset such as '-10:00'/'UTC'.")
    return datetime.datetime.now(zone)


def stamp() -> str:
    return now().strftime("%y%m%d%H")


def bump(value: str, stamp: str) -> str:
    """Replace the trailing stamp, keeping a three-component semantic prefix.

    Works whether ``value`` is a bare ``1.0.0`` or already stamped ``1.0.0.26091512``.
    """
    return f"{floor(value)}.{stamp}"


def bump_semantic(value: str, part: str = "patch") -> str:
    """Increment the semantic version line and drop the stamp, so the build re-stamps it.

    The stamp is the tool's job (``bump``); the ``<major>.<minor>.<patch>`` line is a deliberate
    decision, made by running this rather than by editing versions.properties by hand. A branch
    takes the next patch so its jars are distinguishable from the released line's by filename;
    a merged release takes the next patch unless a minor or major is called for.

    Returns a three-component value -- ``1.0.1`` from ``1.0.0.26092517`` -- because
    ``stampVersion`` appends the build-time stamp to anything shorter than four components.
    """
    if part not in ("patch", "minor", "major"):
        raise ValueError(f"part must be patch, minor or major, not {part!r}")
    parts = [int(p) if p.isdigit() else 0 for p in value.split(".")[:3]]
    while len(parts) < 3:
        parts.append(0)
    if part == "major":
        return f"{parts[0] + 1}.0.0"
    if part == "minor":
        return f"{parts[0]}.{parts[1] + 1}.0"
    return f"{parts[0]}.{parts[1]}.{parts[2] + 1}"


def floor(version: str) -> str:
    """The first three components, e.g. 1.0.2 from 1.0.2.26091512 (and 1.0.0 from 1.0).

    Rejects anything that is not a dotted run of digits. The floor is what the hosts' dependency
    ranges are built from, so a bad value does not fail the release that produced it -- it
    publishes a range matching nothing, and the failure surfaces much later as mods that quietly do
    not load. An empty string is the case worth naming: it is what a missing, renamed or
    unpopulated versions.properties key yields, and silently produced ",1.1)" until this rejected
    it.
    """
    text = (version or "").strip()
    if not text:
        raise ValueError("no version to take a floor from (the value was empty)")
    parts = text.split(".")
    if not all(p.isascii() and p.isdigit() for p in parts):
        raise ValueError(f"expected a dotted run of digits, got {version!r}")
    while len(parts) < 3:
        parts.append("0")
    return ".".join(parts[:3])


def tag(version: str, stamp: str) -> str:
    return f"v{floor(version)}.{stamp}"


def tag(version: str, stamp: str) -> str:
    return f"v{floor(version)}.{stamp}"


# --- modules ------------------------------------------------------------------------


def load_props(path: Path) -> dict:
    props = {}
    if not path.exists():
        return props
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def modules(mc: str) -> list:
    """Every module under versions/<mc>/ as (dir, properties)."""
    base = ROOT / "versions" / mc
    if not base.is_dir():
        return []
    result = []
    for module_dir in sorted(p for p in base.iterdir() if p.is_dir()):
        props_file = module_dir / "module.properties"
        if props_file.exists():
            result.append((module_dir, load_props(props_file)))
    return result


def libraries(mc: str) -> list:
    """Modules marked library=true: (module id, directory, group)."""
    out = []
    for module_dir, props in modules(mc):
        if (props.get("library") or "false").strip().lower() == "true":
            out.append((props.get("id"), module_dir, props.get("group")))
    return out


# --- dependents ---------------------------------------------------------------------
# Dependent modules ask for a library through a floor range computed at configuration time from
# versions.properties (see gradle/versions.gradle's moduleFloor). There is nothing to rewrite on a
# bump: versions.properties is the single source of truth.


def floor_paths(mc: str, lib_id: str) -> list:
    """The metadata files that declare the library dependency, for reporting."""
    paths = []
    for module_dir, props in modules(mc):
        if (props.get("id") or "").strip() == lib_id:
            continue
        depends = [d.strip() for d in (props.get("depends") or "").split(",") if d.strip()]
        if lib_id not in depends:
            continue
        for loader in ("fabric", "neoforge"):
            loader_dir = module_dir / loader
            if not loader_dir.is_dir():
                continue
            meta = (loader_dir / "src/main/resources/fabric.mod.json" if loader == "fabric"
                    else loader_dir / "src/main/resources/META-INF/neoforge.mods.toml")
            if meta.exists():
                paths.append(str(meta.relative_to(ROOT)))
    return paths


def main() -> int:
    ap = argparse.ArgumentParser(description="Version helpers (scheme <major>.<minor>.<patch>.<yymmddhh>).")
    sub = ap.add_subparsers(dest="cmd", required=True)

    sub.add_parser("stamp").set_defaults(func=lambda a: print(stamp()))

    p_bump = sub.add_parser("bump")
    p_bump.add_argument("value")
    p_bump.add_argument("stamp")
    p_bump.set_defaults(func=lambda a: print(bump(a.value, a.stamp)))

    p_tag = sub.add_parser("tag")
    p_tag.add_argument("--api", required=True, help="the library version the tag is keyed on")
    p_tag.add_argument("--stamp", required=True)
    p_tag.set_defaults(func=lambda a: print(tag(a.api, a.stamp)))

    p_floor = sub.add_parser("floor")
    p_floor.add_argument("--api", required=True, help="the library version to derive the floor from")
    p_floor.add_argument("--lib", default=None, help="library module id (default: the only library)")
    p_floor.add_argument("--mc", default=None)

    def do_floor(a):
        bound = floor(a.api)
        if a.mc is None:
            print(bound)
            return
        libs = libraries(a.mc)
        lib_id = a.lib
        if lib_id is None:
            if len(libs) != 1:
                sys.exit(f"versioning: --lib is required when the project has "
                         f"{len(libs)} libraries ({[l[0] for l in libs]})")
            lib_id = libs[0][0]
        # Nothing is rewritten: dependents resolve this floor at configuration time from
        # versions.properties. Listing the dependents makes the dependency visible in CI logs.
        dependents = floor_paths(a.mc, lib_id)
        print(f"floor [{bound},{FLOOR_UPPER}) for '{lib_id}'")
        for path in dependents:
            print(f"  dependent: {path}")

    p_floor.set_defaults(func=do_floor)

    args = ap.parse_args()
    try:
        args.func(args)
    except ValueError as exc:
        sys.exit(f"versioning: {exc}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
