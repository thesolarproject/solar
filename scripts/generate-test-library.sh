#!/bin/bash
# Generate a synthetic MP3 library for testing Solar without real music.
# Usage: ./scripts/generate-test-library.sh [COUNT] [OUTDIR]
set -euo pipefail

COUNT="${1:-100}"
OUTDIR="${2:-$PWD/test-library}"
mkdir -p "$OUTDIR"

GENRES=(Rock Pop Electronic Jazz Classical HipHop Country Blues Folk Metal)
ARTISTS=("Test Artist" "Sample Band" "Demo Group" "Mock Singer")
ALBUM_ARTISTS=("Test Artist" "Sample Band" "Demo Group" "Mock Singer")

for i in $(seq -w 1 "$COUNT"); do
    idx=$((10#$i - 1))
    title="Track $i"
    artist="${ARTISTS[$((idx % ${#ARTISTS[@]}))]}"
    album_artist="${ALBUM_ARTISTS[$((idx % ${#ALBUM_ARTISTS[@]}))]}"
    album="Album $(( idx / 10 + 1 ))"
    genre="${GENRES[$((idx % ${#GENRES[@]}))]}"
    file="$OUTDIR/$(printf '%03d' "$idx" | sed 's/^0*//') - $title.mp3"
    # Short silent-ish sine tone with ID3 tags.
    ffmpeg -f lavfi -i "sine=frequency=$((200 + idx * 5)):duration=5" \
        -metadata title="$title" \
        -metadata artist="$artist" \
        -metadata album="$album" \
        -metadata genre="$genre" \
        -metadata track="$i" \
        -metadata album_artist="$album_artist" \
        -y "$file" >/dev/null 2>&1
    echo "$file"
done
echo "Generated $COUNT MP3s in $OUTDIR"
