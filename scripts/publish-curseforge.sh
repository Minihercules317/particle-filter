#!/usr/bin/env bash
# Uploads the jars in build/libs/<version>/ (one per Minecraft version and loader) to the Particle Filter CurseForge
# project, each as its own file with its own game versions.
#
#   scripts/publish-curseforge.sh [--dry-run] [--type release|beta|alpha] [version]
#
# The version defaults to mod.version in stonecutter.properties.toml. The changelog is that version's section of
# CHANGELOG.md. The API token is read from ~/.config/curseforge/token (or $CURSEFORGE_TOKEN_FILE) and is never printed;
# it's passed to curl through a temporary header file so it doesn't appear in the process list.
# --dry-run checks the token, resolves the game versions and prints what would be uploaded, without uploading.
set -euo pipefail

PROJECT_ID=1760372
API=https://minecraft.curseforge.com/api
# CurseForge game versions ("type-slug:name") for each build, keyed like the Stonecutter projects: <mc>-<loader>.
declare -A GAME_VERSIONS=(
  [1.21.1-neoforge]="minecraft-1-21:1.21.1 modloader:NeoForge environment:Client"
  [1.20.1-forge]="minecraft-1-20:1.20.1 modloader:Forge environment:Client"
)
TOKEN_FILE=${CURSEFORGE_TOKEN_FILE:-$HOME/.config/curseforge/token}

cd "$(dirname "$0")/.."

dry_run=false
release_type=release
version=""
while (($#)); do
  case $1 in
    --dry-run) dry_run=true ;;
    --type) release_type=$2; shift ;;
    -h | --help) sed -n '2,10p' "$0"; exit 0 ;;
    *) version=$1 ;;
  esac
  shift
done
[[ $release_type =~ ^(release|beta|alpha)$ ]] || { echo "--type must be release, beta or alpha" >&2; exit 1; }
[[ -n $version ]] || version=$(sed -n 's/^mod\.version = "\(.*\)"/\1/p' stonecutter.properties.toml)

# particlefilter-<loader>-<version>+<mc>.jar, as written by ./gradlew buildAndCollect; every build needs its jar.
declare -A jars=()
for target in "${!GAME_VERSIONS[@]}"; do
  mc=${target%%-*} loader=${target#*-}
  jar=build/libs/$version/particlefilter-$loader-$version+$mc.jar
  [[ -f $jar ]] || { echo "Missing $jar; build it first (mise exec -- ./gradlew buildAndCollect)" >&2; exit 1; }
  jars[$target]=$jar
done
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

# Resolves "type-slug:name" pairs to CurseForge's numeric game version ids, as a JSON list.
resolve_versions() {
  python3 -I -c '
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
' "$types_file" "$versions_file" "$@"
}

# Resolve every build's versions before uploading anything, so a bad entry can't leave a release half uploaded.
declare -A version_ids=()
for target in "${!jars[@]}"; do
  # shellcheck disable=SC2086 # one word per game version
  version_ids[$target]=$(resolve_versions ${GAME_VERSIONS[$target]})
done

echo "Project:  $PROJECT_ID"
echo "Type:     $release_type"
for target in "${!jars[@]}"; do
  jar=${jars[$target]}
  echo "File:     $jar ($(stat -c %s "$jar") bytes, sha256 $(sha256sum "$jar" | cut -c1-16)…)"
  echo "          ${GAME_VERSIONS[$target]} -> ${version_ids[$target]}"
done
echo "Changelog:"
sed 's/^/  | /' <<<"$changelog"

if $dry_run; then
  echo "Dry run: nothing uploaded."
  exit 0
fi

for target in "${!jars[@]}"; do
  jar=${jars[$target]}
  metadata=$(python3 -I -c '
import json, sys
print(json.dumps({"changelog": sys.argv[1], "changelogType": "markdown", "displayName": sys.argv[2],
                  "gameVersions": json.loads(sys.argv[3]), "releaseType": sys.argv[4]}))
' "$changelog" "$(basename "$jar")" "${version_ids[$target]}" "$release_type")

  # Read the metadata from a file: a literal value containing ";" could be misread as curl field options.
  meta_file=$(mktemp)
  response=$(mktemp)
  printf '%s' "$metadata" >"$meta_file"
  status=$(curl -sS -o "$response" -w '%{http_code}' -H @"$header" \
    -F "metadata=<$meta_file" -F "file=@$jar" "$API/projects/$PROJECT_ID/upload-file")
  rm -f "$meta_file"
  if [[ $status != 200 ]]; then
    echo "Upload of $jar failed (HTTP $status):" >&2
    cat "$response" >&2
    rm -f "$response"
    exit 1
  fi
  file_id=$(python3 -I -c 'import json, sys; print(json.load(open(sys.argv[1]))["id"])' "$response")
  rm -f "$response"
  echo "Uploaded $(basename "$jar"): https://www.curseforge.com/minecraft/mc-mods/particle-filter/files/$file_id"
done
echo "CurseForge may review the files before they become public."
