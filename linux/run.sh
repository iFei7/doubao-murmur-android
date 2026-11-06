#!/bin/bash
# Development launch script for Doubao Murmur on Linux.
# Usage: ./run.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# Add src to PYTHONPATH
export PYTHONPATH="$SCRIPT_DIR/src:$PYTHONPATH"

echo "🎤 Starting Doubao Murmur (Linux)..."
python3 -m doubao_murmur "$@"
