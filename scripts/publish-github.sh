#!/usr/bin/env sh
set -eu
repository_name='galaxycoils/englishdub-android'
gh repo view "$repository_name" --json nameWithOwner >/dev/null
expected_url="https://github.com/$repository_name.git"
if git remote get-url origin >/dev/null 2>&1; then
  current_url="$(git remote get-url origin)"
  if [ "$current_url" != "$expected_url" ]; then
    echo 'Origin differs from the approved repository; inspect it before publishing.' >&2
    exit 1
  fi
else
  git remote add origin "$expected_url"
fi
git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push -u origin main
