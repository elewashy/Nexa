#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf 'Usage: %s validate|extract [output-file]\n' "$0" >&2
}

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  usage
  exit 2
fi

command="$1"
output_file="${2:-}"
version="$(awk -F '"' '/versionName[[:space:]]*=/ { print $2; exit }' app/build.gradle.kts)"

if [ -z "$version" ]; then
  printf 'Could not read versionName from app/build.gradle.kts\n' >&2
  exit 1
fi

section="$(awk -v version="$version" '
  $0 ~ "^## " version "[[:space:]]*-" { capture = 1; next }
  capture && /^## / { exit }
  capture { print }
' CHANGELOG.md | sed '/^[[:space:]]*$/d')"

if [ -z "$section" ]; then
  printf 'CHANGELOG.md is missing a non-empty section for version %s.\n' "$version" >&2
  printf 'Add a heading like: ## %s - YYYY-MM-DD\n' "$version" >&2
  exit 1
fi

case "$command" in
  validate)
    printf 'CHANGELOG.md contains release notes for version %s.\n' "$version"
    ;;
  extract)
    if [ -z "$output_file" ]; then
      usage
      exit 2
    fi
    {
      printf '## %s\n\n' "$version"
      printf '%s\n' "$section"
    } > "$output_file"
    printf 'Wrote release notes for version %s to %s.\n' "$version" "$output_file"
    ;;
  *)
    usage
    exit 2
    ;;
esac
