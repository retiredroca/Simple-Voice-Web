#!/usr/bin/env bash
#
# Publish a Modrinth version for one project: the primary jar plus any additional
# files (e.g. a bundle), then archive every superseded version so only the current
# one stays listed.
#
# Modrinth versions are effectively immutable, so an update is a new version rather
# than a mutated one; the previous version is archived afterwards. The new version id
# is written back to release-state.properties under <state_key>.
#
# Usage: modrinth-sync.sh <projectIdOrSlug> <stateKey> <versionNumber> <versionName> <primaryFile> [<extraFile>...]
#
# Required Modrinth personal-access-token scopes:
#   VERSION_CREATE  - upload new versions
#   VERSION_WRITE   - archive superseded versions (PATCH requested_status)
#   (VERSION_DELETE is NOT used here; the workflow never deletes versions.)
#
# Optional environment:
#   MODRINTH_LOADERS        comma-separated (default: fabric,neoforge)
#   MODRINTH_GAME_VERSIONS  comma-separated (default: 1.21.1)
#   MODRINTH_ENVIRONMENT    default: client_and_server
#
set -euo pipefail

: "${MODRINTH_TOKEN:?MODRINTH_TOKEN is required}"

project="${1:?project id or slug required}"
state_key="${2:?state key required}"
version_number="${3:?version number required}"
version_name="${4:?version name required}"
primary="${5:?primary file required}"
shift 5
extras=("$@")

api='https://api.modrinth.com/v2'
state_file='release-state.properties'
previous="$(grep -E "^${state_key}=" "$state_file" | head -1 | cut -d= -f2 || true)"

loaders_json="$(jq -nc --arg s "${MODRINTH_LOADERS:-fabric,neoforge}" \
  '$s | split(",") | map(gsub("^\\s+|\\s+$"; ""))')"
game_versions_json="$(jq -nc --arg s "${MODRINTH_GAME_VERSIONS:-1.21.1}" \
  '$s | split(",") | map(gsub("^\\s+|\\s+$"; ""))')"

data="$(jq -nc \
  --arg project_id "$project" \
  --arg name "$version_name" \
  --arg version_number "$version_number" \
  --arg changelog "$(cat dist/changelog.md 2>/dev/null || true)" \
  --arg environment "${MODRINTH_ENVIRONMENT:-client_and_server}" \
  --argjson loaders "$loaders_json" \
  --argjson game_versions "$game_versions_json" \
  '{project_id: $project_id, name: $name, version_number: $version_number,
    changelog: $changelog,
    version_type: "release", status: "listed", featured: false,
    environment: $environment, loaders: $loaders, game_versions: $game_versions, dependencies: []}')"

echo "modrinth: creating version ${version_number} on ${project}"
args=(-fsS -X POST "${api}/version" -H "Authorization: ${MODRINTH_TOKEN}"
      -F "data=${data};type=application/json"
      -F "file=@${primary}")
for extra in "${extras[@]}"; do
  args+=(-F "file=@${extra}")
done
response="$(curl "${args[@]}")"

version_id="$(echo "$response" | jq -r '.id // empty')"
if [ -z "$version_id" ]; then
  echo "modrinth: could not read the new version id from the response" >&2
  echo "$response" >&2
  exit 1
fi
echo "modrinth: created version ${version_id}"

sed -i -E "s|^${state_key}=.*|${state_key}=${version_id}|" "$state_file"
if ! grep -qE "^${state_key}=" "$state_file"; then
  echo "${state_key}=${version_id}" >> "$state_file"
fi

if [ -n "$previous" ] && [ "$previous" != "$version_id" ]; then
  echo "modrinth: archiving superseded version ${previous}"
  curl -fsS -X PATCH "${api}/version/${previous}" \
    -H "Authorization: ${MODRINTH_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d '{"requested_status":"archived"}' || echo "modrinth: could not archive ${previous}" >&2
fi
