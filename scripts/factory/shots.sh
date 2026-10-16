#!/usr/bin/env bash
# Takes the screenshots a PR lists, of one tree: each shot runs a fresh
# server of that tree, puts it in the state of its scenario, and
# captures a URL. The tree is built once.
#
# Usage: scripts/factory/shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>
#
# The list (the build job keeps it in .factory/pr/shots.txt of its
# worktree, never committed) has one shot per line, "#" starting a
# comment:
#   <name> <scenario> <path> <size>
# for example:
#   frozen frozen-consumer.json / 1440x900
# The scenario is the name of a file next to the list (one made for the
# PR), or of the factory's scenarios (scripts/factory/scenarios/), or
# "none": the shots of main and of the PR use the same file. Each shot
# is saved as <out-dir>/<name>-<suffix>.png. The UI has a dark theme
# only.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

usage="usage: shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>"
target=${1:?$usage}
list=${2:?$usage}
out=${3:?$usage}
suffix=${4:?$usage}
[[ -f $list ]] || die "no list of shots $list"
list=$(cd "$(dirname "$list")" && pwd)/$(basename "$list")
shared=$(cd "$(dirname "$0")" && pwd)/scenarios
mkdir -p "$out"
out=$(cd "$out" && pwd)
here=$(cd "$(dirname "$0")" && pwd)

# A run name of its own, so that it never stops a run of David's.
case $target in
.) as=shots-$(basename "$(git rev-parse --show-toplevel)") ;;
*) as=shots-${target//\//-} ;;
esac
trap '"$here/try-pr.sh" --stop "try-$as" 2>/dev/null' EXIT

build=()
while read -r name scenario path size || [[ -n ${name:-} ]]; do
    [[ -z ${name:-} || $name == \#* ]] && continue
    if [[ $scenario != none ]]; then
        [[ $scenario == *.json ]] || scenario=$scenario.json
        if [[ -f $(dirname "$list")/$scenario ]]; then
            scenario=$(dirname "$list")/$scenario
        else
            scenario=$shared/$scenario
        fi
    fi
    url=$("$here/try-pr.sh" "$target" --as "$as" --detach --scenario "$scenario" ${build[@]+"${build[@]}"} </dev/null | tail -n 1)
    build=(--no-build)
    "$here/capture.sh" "$url${path#/}" "$out/$name-$suffix.png" --size "${size:-1440x900}" >/dev/null ||
        die "no shot $name"
    info "shot $out/$name-$suffix.png"
    "$here/try-pr.sh" --stop "try-$as"
done <"$list"
