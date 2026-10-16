#!/usr/bin/env bash
# Takes the screenshots a PR lists, of one tree: each shot runs a fresh
# server of that tree, puts it in the state of its scenario, and
# captures a URL. The tree is built once.
#
# Usage: scripts/factory/shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>
#
# The list (scripts/factory/captures/<issue>.txt) has one shot per line,
# "#" starting a comment:
#   <name> <scenario> <path> <size> <light|dark>
# for example:
#   frozen frozen-consumer.json / 1440x900 dark
# The scenario is the name of a file of the scenarios/ directory next to
# the list's captures/ directory, so that the shots of main use the same
# scenarios as the PR's, new ones included; or "none".
# Each shot is saved as <out-dir>/<name>-<suffix>.png.
set -euo pipefail
. "$(dirname "$0")/lib.sh"

target=${1:?usage: shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>}
list=${2:?usage: shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>}
out=${3:?usage: shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>}
suffix=${4:?usage: shots.sh <PR|branch|main|.> <shots.txt> <out-dir> <suffix>}
[[ -f $list ]] || die "no list of shots $list"
list=$(cd "$(dirname "$list")" && pwd)/$(basename "$list")
mkdir -p "$out"
out=$(cd "$out" && pwd)
here=$(cd "$(dirname "$0")" && pwd)

# The name try-pr.sh runs the target as.
case $target in
.) running=try-$(basename "$(git rev-parse --show-toplevel)") ;;
*) running=try-${target//\//-} ;;
esac

build=()
while read -r name scenario path size theme; do
    [[ -z $name || $name == \#* ]] && continue
    [[ $scenario == none ]] || scenario=$(dirname "$(dirname "$list")")/scenarios/$scenario
    url=$("$here/try-pr.sh" "$target" --detach --scenario "$scenario" ${build[@]+"${build[@]}"} </dev/null | tail -n 1)
    build=(--no-build)
    flags=(--size "${size:-1440x900}")
    [[ ${theme:-light} == dark ]] && flags+=(--dark)
    if "$here/capture.sh" "$url${path#/}" "$out/$name-$suffix.png" "${flags[@]}" >/dev/null; then
        info "shot $out/$name-$suffix.png"
    else
        "$here/try-pr.sh" --stop "$running"
        die "no shot $name"
    fi
    "$here/try-pr.sh" --stop "$running"
done <"$list"
