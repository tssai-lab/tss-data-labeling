#!/usr/bin/env bash

# Atomically replace keys managed by a bootstrap while preserving every other
# existing runtime setting. Values are never sourced or printed.
tss_merge_runtime_env_file() {
  local target_file="$1"
  local managed_file="$2"
  local source_file="/dev/null"
  local preserved_file=""
  local merged_file=""

  if [[ ! -f $managed_file || -L $managed_file ]]; then
    echo "Managed runtime environment input must be a regular file." >&2
    return 1
  fi
  if [[ -e $target_file && ( ! -f $target_file || -L $target_file ) ]]; then
    echo "Runtime environment target must be a regular file." >&2
    return 1
  fi
  if [[ -f $target_file ]]; then
    source_file="$target_file"
  fi

  umask 077
  preserved_file="$(mktemp "${target_file}.preserved.XXXXXX")"
  merged_file="$(mktemp "${target_file}.merged.XXXXXX")"

  if ! awk '
    FNR == NR {
      if ($0 ~ /^[[:space:]]*($|#)/) next
      separator = index($0, "=")
      if (!separator) exit 41
      key = substr($0, 1, separator - 1)
      if (key !~ /^[A-Za-z_][A-Za-z0-9_]*$/ || key in managed) exit 42
      managed[key] = 1
      next
    }
    $0 ~ /^[[:space:]]*($|#)/ { print; next }
    {
      separator = index($0, "=")
      if (!separator) exit 43
      key = substr($0, 1, separator - 1)
      if (key !~ /^[A-Za-z_][A-Za-z0-9_]*$/) exit 44
      if (!(key in managed)) print
    }
  ' "$managed_file" "$source_file" >"$preserved_file"; then
    rm -f -- "$preserved_file" "$merged_file"
    echo "Runtime environment contains an invalid or duplicate key." >&2
    return 1
  fi

  if ! cat "$managed_file" "$preserved_file" >"$merged_file"; then
    rm -f -- "$preserved_file" "$merged_file"
    return 1
  fi
  chmod 600 "$merged_file"
  if ! mv -f -- "$merged_file" "$target_file"; then
    rm -f -- "$preserved_file" "$merged_file"
    return 1
  fi
  rm -f -- "$preserved_file"
}
