# Tools

Release helpers for this project. Both scripts are plain Python 3 (no third-party packages).

## `versioning.py`

```
python tools/versioning.py stamp                                   # the current yymmddhh stamp
python tools/versioning.py bump <version> <stamp>                  # replace the stamp component
python tools/versioning.py tag --api <version> --stamp <s>         # v<major.minor.patch>.<stamp>
python tools/versioning.py floor --api <version> [--mc <mc>]       # report a dependency floor
```

The stamp timezone is the `TIMEZONE` constant at the top of the file, set when the project was
created. It accepts an IANA zone name (`America/New_York`) or a fixed offset (`UTC`, `-10:00`,
`+05:30`). Fixed offsets always work; an IANA name needs a timezone database, which is read from
Python's `zoneinfo` or, failing that, from the JDK's `java.time` (already required to run Gradle).

## `release.py`

See `PROJECT-GUIDE.md` for building and adding Minecraft versions, and `RELEASE-GUIDE.md` for the
release flow. Short version:

```bash
python tools/release.py --mod <id> --dry-run
python tools/release.py --mod <id>
```

Platform tokens come from the environment only (`CURSEFORGE_API_KEY`, `MODRINTH_TOKEN`,
`GITHUB_TOKEN` or the stored `github.com` git credential).
