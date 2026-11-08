#!/bin/bash
# Publish a new version of the app. Phones see the update a few minutes later.
#
#   ./release.sh 1.2 "Faster voice, fixed crash on long videos"
#
# It sets the version, commits, tags v1.2 and pushes to GitHub.
# GitHub Actions then builds the signed APK and creates the release.
set -e
cd "$(dirname "$0")"
V="$1"
NOTES="${2:-Bug fixes and improvements}"
if ! [[ "$V" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]]; then
    echo "Usage: ./release.sh <version like 1.2> \"what's new\""
    echo "Current: $(grep '^versionName=' android-app/version.properties)"
    exit 1
fi
if git rev-parse "v$V" >/dev/null 2>&1; then echo "v$V already exists"; exit 1; fi
perl -pi -e "s/^versionName=.*/versionName=$V/" android-app/version.properties
git add -A
git commit -q -m "Release v$V" -m "$NOTES" || true
git tag -a "v$V" -m "$NOTES"
git push -q origin HEAD
git push -q origin "v$V"
REPO=$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null || true)
echo "Pushed v$V. GitHub is building it now (about 5 minutes)."
[ -n "$REPO" ] && echo "Watch: https://github.com/$REPO/actions"
