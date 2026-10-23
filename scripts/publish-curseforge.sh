#!/usr/bin/env bash
# Uploads build/libs/particlefilter-<version>.jar to the Particle Filter CurseForge project.
#
#   scripts/publish-curseforge.sh [--dry-run] [--type release|beta|alpha] [version]
#
# The version defaults to the one in build.gradle. The changelog is that version's section of CHANGELOG.md.
# The API token is read from ~/.config/curseforge/token (or $CURSEFORGE_TOKEN_FILE) and is never printed;
# it's passed to curl through a temporary header file so it doesn't appear in the process list.
# --dry-run checks the token, resolves the game versions and prints what would be uploaded, without uploading.
set -euo pipefail

PROJECT_ID=1760372
API=https://minecraft.curseforge.com/api
GAME_VERSIONS=("minecraft-1-21:1.21.1" "modloader:NeoForge" "environment:Client")
TOKEN_FILE=${CURSEFORGE_TOKEN_FILE:-$HOME/.config/curseforge/token}

cd "$(dirname "$0")/.."

dry_run=false
release_type=release
version=""
while (($#)); do
  case $1 in
    --dry-run) dry_run=true ;;
    --type) release_type=$2; shift ;;
    -h | --help) sed -n '2,9p' "$0"; exit 0 ;;
    *) version=$1 ;;
  esac
  shift
done
[[ $release_type =~ ^(release|beta|alpha)$ ]] || { echo "--type must be release, beta or alpha" >&2; exit 1; }
[[ -n $version ]] || version=$(sed -n "s/^version = '\(.*\)'/\1/p" build.gradle)

jar=build/libs/particlefilter-$version.jar
[[ -f $jar ]] || { echo "Missing $jar; build it first (mise exec -- ./gradlew build)" >&2; exit 1; }
[[ -s $TOKEN_FILE ]] || { echo "No token in $TOKEN_FILE" >&2; exit 1; }

# The "## <version>" section of CHANGELOG.md, without its heading.
changelog=$(awk -v v="$version" '$0 == "## " v {on = 1; next} /^## / {on = 0} on' CHANGELOG.md | sed '/./,$!d')
[[ -n $changelog ]] || { echo "CHANGELOG.md has no '## $version' section" >&2; exit 1; }
changelog+=$'\n\n'"*This mod's code was written by Claude, an AI model by Anthropic; Minihercules317 did the idea, design, testing and icon. Details are linked on the project page.*"

header=$(mktemp)
trap 'rm -f "$header"' EXIT
chmod 600 "$header"
printf 'X-Api-Token: %s\n' "$(<"$TOKEN_FILE")" >"$header"

# Saves the response of GET $API/$1 to file $2; the version list is too big to pass around as an argument.
api_get() {
  local status
  status=$(curl -sS -o "$2" -w '%{http_code}' -H @"$header" "$API/$1")
  if [[ $status != 200 ]]; then
    echo "GET $1 failed (HTTP $status); is the token valid?" >&2
    exit 1
  fi
}

types_file=$(mktemp)
versions_file=$(mktemp)
trap 'rm -f "$header" "$types_file" "$versions_file"' EXIT
api_get game/version-types "$types_file"
api_get game/versions "$versions_file"

# Resolve "type-slug:name" pairs to CurseForge's numeric game version ids.
version_ids=$(python3 -I -c '
import json, sys
types = {t["slug"]: t["id"] for t in json.load(open(sys.argv[1]))}
versions = json.load(open(sys.argv[2]))
ids = []
for wanted in sys.argv[3:]:
    slug, name = wanted.split(":", 1)
    match = [v["id"] for v in versions if v["name"] == name and v["gameVersionTypeID"] == types.get(slug)]
    if len(match) != 1:
        sys.exit(f"Game version {wanted!r}: expected 1 match, found {len(match)}")
    ids.append(match[0])
print(json.dumps(ids))
' "$types_file" "$versions_file" "${GAME_VERSIONS[@]}")

metadata=$(python3 -I -c '
import json, sys
print(json.dumps({"changelog": sys.argv[1], "changelogType": "markdown", "displayName": sys.argv[2],
                  "gameVersions": json.loads(sys.argv[3]), "releaseType": sys.argv[4]}))
' "$changelog" "particlefilter-$version.jar" "$version_ids" "$release_type")

echo "Project:  $PROJECT_ID"
echo "File:     $jar ($(stat -c %s "$jar") bytes, sha256 $(sha256sum "$jar" | cut -c1-16)…)"
echo "Type:     $release_type"
echo "Versions: ${GAME_VERSIONS[*]} -> $version_ids"
echo "Changelog:"
sed 's/^/  | /' <<<"$changelog"

if $dry_run; then
  echo "Dry run: nothing uploaded."
  exit 0
fi

# Read the metadata from a file: a literal value containing ";" could be misread as curl field options.
meta_file=$(mktemp)
response=$(mktemp)
printf '%s' "$metadata" >"$meta_file"
status=$(curl -sS -o "$response" -w '%{http_code}' -H @"$header" \
  -F "metadata=<$meta_file" -F "file=@$jar" "$API/projects/$PROJECT_ID/upload-file")
rm -f "$meta_file"
if [[ $status != 200 ]]; then
  echo "Upload failed (HTTP $status):" >&2
  cat "$response" >&2
  rm -f "$response"
  exit 1
fi
file_id=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["id"])' "$response")
rm -f "$response"
echo "Uploaded: https://www.curseforge.com/minecraft/mc-mods/particle-filter/files/$file_id"
echo "CurseForge may review the file before it becomes public."
