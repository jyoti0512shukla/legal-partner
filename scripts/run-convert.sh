#!/bin/bash
# Run contract conversion using project venv (ensures correct deps + Mac lib path)

set -e
REPO="$(cd "$(dirname "$0")/.." && pwd)"
VENV="$REPO/.venv-convert"

# Create venv if missing
if [ ! -d "$VENV" ]; then
  echo "Creating venv..."
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -r "$REPO/scripts/requirements-convert.txt"
fi

# Mac: help WeasyPrint find Homebrew libs (pango, glib)
export DYLD_LIBRARY_PATH="/opt/homebrew/lib:$DYLD_LIBRARY_PATH"

exec "$VENV/bin/python" "$REPO/scripts/convert-contracts.py" "$@"
