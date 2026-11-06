#!/bin/bash
# Kill running Doubao Murmur instance
if pkill -x "Doubao Murmur" 2>/dev/null; then
  echo "🛑 Doubao Murmur stopped"
else
  echo "ℹ️  Doubao Murmur is not running"
fi
