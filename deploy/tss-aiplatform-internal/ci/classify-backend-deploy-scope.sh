#!/usr/bin/env bash
set -Eeuo pipefail

deploy_main=false
while IFS= read -r changed_path; do
  case "$changed_path" in
    backend/*|deploy/main/*|deploy/nodes/*|deploy/scripts/*|k8s/*)
      deploy_main=true
      break
      ;;
  esac
done

printf 'deploy_main=%s\n' "$deploy_main"
