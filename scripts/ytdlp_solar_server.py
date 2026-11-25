#!/usr/bin/env python3
"""
Tiny yt-dlp HTTP sidecar for Solar Y1/Y2.
Usage:
  pip install -U yt-dlp
  python3 scripts/ytdlp_solar_server.py --host 0.0.0.0 --port 8787

Solar config (on device):
  adb shell 'mkdir -p /sdcard/Solar && echo http://<PC-IP>:8787 > /sdcard/Solar/ytdlp_server.txt'

Endpoints:
  /resolve?videoId=ID&quality=360
  /resolve?videoId=ID&audioOnly=1
  /search?q=...
  /comments?videoId=ID
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

import yt_dlp


def _ydl(opts=None):
    base = {
        "quiet": True,
        "no_warnings": True,
        "noplaylist": True,
        "skip_download": True,
    }
    if opts:
        base.update(opts)
    return yt_dlp.YoutubeDL(base)


def _video_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={video_id}"


def resolve(video_id: str, quality: str, audio_only: bool):
    if not video_id:
        raise ValueError("missing videoId")
    if audio_only:
        fmt = "bestaudio[ext=m4a]/bestaudio/best"
    else:
        # Prefer progressive mp4 for old Android/MediaPlayer; avoid ffmpeg merge on device.
        q = "".join(ch for ch in (quality or "360") if ch.isdigit()) or "360"
        fmt = (
            f"best[height<={q}][ext=mp4][acodec!=none][vcodec!=none]/"
            f"best[height<={q}][acodec!=none][vcodec!=none]/"
            "best[ext=mp4][acodec!=none][vcodec!=none]/best"
        )
    with _ydl({"format": fmt}) as ydl:
        info = ydl.extract_info(_video_url(video_id), download=False)
        info = ydl.sanitize_info(info)
    return {
        "url": info.get("url", ""),
        "ext": info.get("ext", "m4a" if audio_only else "mp4"),
        "title": info.get("title", ""),
        "id": info.get("id", video_id),
        "audioOnly": bool(audio_only),
    }


def search(query: str):
    q = (query or "").strip()
    if not q:
        return []
    with _ydl({"extract_flat": True}) as ydl:
        info = ydl.extract_info(f"ytsearch20:{q}", download=False)
        info = ydl.sanitize_info(info)
    entries = info.get("entries") or []
    out = []
    for e in entries:
        if not e:
            continue
        vid = e.get("id") or ""
        if not vid:
            continue
        out.append({
            "video_id": vid,
            "title": e.get("title") or "",
            "author": e.get("uploader") or e.get("channel") or "",
            "duration": str(e.get("duration") or ""),
        })
    return out


class Handler(BaseHTTPRequestHandler):
    server_version = "SolarYtDlp/1.0"

    def _send(self, code: int, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            parsed = urlparse(self.path)
            qs = parse_qs(parsed.query)
            path = parsed.path
            if path in ("/resolve", "/api/resolve", "/stream", "/audio"):
                video_id = (qs.get("videoId") or qs.get("id") or [""])[0]
                quality = (qs.get("quality") or ["360"])[0]
                audio_only = path == "/audio" or (qs.get("audioOnly") or ["0"])[0] in ("1", "true", "yes")
                self._send(200, resolve(video_id, quality, audio_only))
                return
            if path in ("/search", "/api/search"):
                query = (qs.get("q") or qs.get("query") or [""])[0]
                self._send(200, search(query))
                return
            if path in ("/comments", "/api/comments"):
                # Comments are not critical; return empty to let other backends handle if needed.
                self._send(200, {"comments": []})
                return
            if path in ("/popular", "/api/popular"):
                self._send(200, [])
                return
            self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(500, {"error": str(e)})

    def log_message(self, fmt, *args):
        print("%s - %s" % (self.address_string(), fmt % args))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8787)
    args = ap.parse_args()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Solar yt-dlp server listening on http://{args.host}:{args.port}")
    server.serve_forever()


if __name__ == "__main__":
    main()
