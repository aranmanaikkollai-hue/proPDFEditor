#!/bin/bash

OWNER="aranmanaikkollai-hue"
REPO="proPDFEditor"
BRANCH="main" # Change to "master" if needed

# Create a temporary directory
TEMP_DIR=$(mktemp -d)
cd "$TEMP_DIR" || exit

# Clone only the latest commit (shallow clone for speed)
git clone --depth 1 --branch "$BRANCH" "https://github.com/$OWNER/$REPO.git" .

# If clone fails due to branch name, try master
if [ $? -ne 0 ]; then
echo "Branch 'main' not found, trying 'master'..."
git clone --depth 1 --branch master "https://github.com/$OWNER/$REPO.git" .
BRANCH="master"
fi

# Find all files (excluding .git) and print raw URLs
find . -type f -not -path "./.git/*" | while read -r filepath; do
# Remove leading "./"
cleanpath="${filepath#./}"
echo "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$cleanpath"
done

# Clean up
cd /tmp && rm -rf "$TEMP_DIR"