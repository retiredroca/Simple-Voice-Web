#!/usr/bin/env bash
#
# Upload release jars to CurseForge through the API, in the two shapes the release
# workflow needs:
#
#   attach <parentFileId> <file>...   add each file as an additional file under an
#                                    EXISTING entry, so an updated component jar
#                                    lands on the current library entry instead of
#                                    creating (and re-uploading) a whole new one
#   new    <displayName> <file>...   create a new top-level file for a component
#                                    whose entry does not exist yet
#
# CurseForge has no public API to archive the superseded files; archive those on
# the website (Files -> ... -> Archive) after the run.
#
# Environment:
#   CURSEFORGE_TOKEN       required; CurseForge API token
#   CURSEFORGE_PROJECT_ID  required; numeric CurseForge project id
#
# Usage: curseforge-upload.sh attach|new <parentFileId|displayName> <file> [<file>...]
#
set -euo pipefail

: "${CURSEFORGE_TOKEN:?CURSEFORGE_TOKEN is required}"
: "${CURSEFORGE_PROJECT_ID:?CURSEFORGE_PROJECT_ID is required}"

mode="${1:?mode (attach|new) required}"
case "$mode" in
  attach|new) shift ;;
  *)
    echo "curseforge-upload: unknown mode '${mode}' (expected attach or new)." >&2
    exit 2
    ;;
esac

target="${1:?${mode} argument required}"
shift

if [ "$#" -eq 0 ]; then
  echo "curseforge-upload: nothing to upload"
  exit 0
fi

project_id="${CURSEFORGE_PROJECT_ID}"
upload_url="https://minecraft.curseforge.com/api/projects/${project_id}/upload-file"
# The changelog: the last few commits plus a link to the full release notes.
changelog="$(cat dist/changelog.md 2>/dev/null || echo '')"

# Only an attach may name a parent file, and it must be one: a non-numeric value here used to be
# passed silently and rejected later by the API with an opaque error.
if [ "$mode" = attach ]; then
  case "$target" in
    ''|*[!0-9]*)
      echo "curseforge-upload: parent file id '${target}' is not numeric." >&2
      echo "Set cf.<library>.file.<mc> in release-state.properties (the library release records it)." >&2
      exit 1
      ;;
  esac
fi

for file in "$@"; do
  name="$(basename "$file")"
  display_name="${name}"
  metadata="$(jq -nc \
    --arg changelog "$changelog" \
    --arg displayName "$display_name" \
    --arg mode "$mode" \
    --arg target "$target" \
    'if $mode == "attach" then
       {changelog: $changelog, changelogType: "markdown", displayName: $displayName,
        parentFileID: ($target | tonumber), releaseType: "release"}
     else
       {changelog: $changelog, changelogType: "markdown", displayName: $target,
        releaseType: "release"}
     end')"

  if [ "$mode" = attach ]; then
    echo "curseforge-upload: attaching ${name} under file ${target}"
  else
    echo "curseforge-upload: creating '${target}' from ${name}"
  fi

  ok=false
  for attempt in 1 2 3 4 5; do
    if curl -fsS -X POST "$upload_url" \
        -H "X-Api-Token: ${CURSEFORGE_TOKEN}" \
        -F "metadata=${metadata};type=application/json" \
        -F "file=@${file}"; then
      echo "curseforge-upload: ${name} uploaded"
      ok=true
      break
    fi
    echo "curseforge-upload: attempt ${attempt} failed for ${name}" >&2
    sleep 15
  done

  if [ "$ok" != true ]; then
    echo "curseforge-upload: giving up on ${name}" >&2
    exit 1
  fi
done