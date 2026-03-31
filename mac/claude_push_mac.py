#!/usr/bin/env python3
"""
Claude Push Mac — macOS 菜单栏文件接收器
接收 Android 手机通过局域网推送的文件，存到 ~/Downloads/ClaudePush/
支持剪贴板文字双向同步
"""

import atexit
import concurrent.futures
import http.server
import json
import logging
import logging.handlers
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import urllib.request
from datetime import datetime
from pathlib import Path
from urllib.parse import urlparse, parse_qs, urlencode, quote

import time as _time

# ── Logging setup (replaces all print → nohup.out) ──────────────
_LOG_DIR = Path.home() / ".claude" / "logs"
_LOG_DIR.mkdir(parents=True, exist_ok=True)
_log_handler = logging.handlers.RotatingFileHandler(
    _LOG_DIR / "claude_push_mac.log",
    maxBytes=2 * 1024 * 1024,  # 2MB per file
    backupCount=2,             # keep 2 old files, max 6MB total
    encoding="utf-8",
)
_log_handler.setFormatter(logging.Formatter("%(asctime)s %(message)s", datefmt="%m-%d %H:%M:%S"))
_logger = logging.getLogger("claude_push")
_logger.addHandler(_log_handler)
_logger.setLevel(logging.INFO)
# Silence stdout/stderr completely when running via nohup
if not sys.stderr.isatty():
    sys.stdout = open(os.devnull, "w")
    sys.stderr = open(os.devnull, "w")

import rumps

try:
    import objc
    from AppKit import (
        NSView, NSWindow, NSColor, NSBezierPath, NSFont, NSOpenPanel,
        NSPasteboardTypeFileURL, NSDragOperationCopy,
        NSFloatingWindowLevel, NSBackingStoreBuffered,
        NSWindowStyleMaskTitled, NSWindowStyleMaskClosable,
        NSFontAttributeName, NSForegroundColorAttributeName,
    )
    from Foundation import NSURL, NSMakeRect, NSMakePoint
    _HAS_APPKIT = True
except ImportError:
    _HAS_APPKIT = False

_drop_callback = None  # set by ClaudePushApp for DropView to call

RECEIVE_DIR = Path.home() / "Downloads" / "ClaudePush"
DEV_IDEAS_DIR = Path.home() / "Documents" / "dev-ideas"
NAS_URL = "https://xhs.royaldutchhome.com"
PORT = 18081
PHONE_PORT = 18080
PHONE_CONFIG = Path.home() / ".claude" / "push_target"
PHONE_DEVICE_CONFIG = Path.home() / ".claude" / "push_device"  # stores device_id for fingerprint
ICON_DIR = Path(tempfile.gettempdir()) / "claudepush_icons"
ADB_FORWARD_PORT = 18080  # local port for ADB forwarding


def _create_ouroboros_icon(flash=False):
    """Create ouroboros menu bar icon as PNG using PIL. Witcher silver-grey."""
    ICON_DIR.mkdir(exist_ok=True)
    suffix = "_flash" if flash else ""
    path = ICON_DIR / f"ouroboros{suffix}.png"

    try:
        from PIL import Image, ImageDraw
        import math

        size = 88  # @2x for retina
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)

        cx, cy = size / 2, size / 2
        r = size * 0.34
        bw = size * 0.09  # body half-width

        c_body = (220, 222, 225, 255) if flash else (140, 143, 147, 255)
        c_tail = (220, 222, 225, 255) if flash else (110, 113, 116, 255)
        c_eye = (60, 62, 65, 255) if flash else (210, 213, 216, 255)
        c_scale = (220, 222, 225, 255) if flash else (100, 103, 106, 255)

        gap = 55  # degrees gap at top

        # ── Body ring as filled polygon (outer arc + inner arc) ──
        head_deg = 90 - gap // 2   # right side of gap
        tail_deg = 90 + gap // 2   # left side of gap

        # Build outer arc points (tail → clockwise around → head)
        outer_pts = []
        for d in range(tail_deg, tail_deg + (360 - gap) + 1):
            a = math.radians(d % 360)
            outer_pts.append((cx + (r + bw) * math.cos(a),
                              cy - (r + bw) * math.sin(a)))

        # Build inner arc points (head → counterclockwise → tail)
        inner_pts = []
        for d in range(head_deg + (360 - gap), tail_deg - 1, -1):
            a = math.radians(d % 360)
            inner_pts.append((cx + (r - bw) * math.cos(a),
                              cy - (r - bw) * math.sin(a)))

        draw.polygon(outer_pts + inner_pts, fill=c_body)

        # ── Head: upper jaw ──
        ha = math.radians(head_deg)
        # Outer edge of body at head
        h_out_x = cx + (r + bw) * math.cos(ha)
        h_out_y = cy - (r + bw) * math.sin(ha)
        # Extended jaw point (further out)
        jaw_ext_a = math.radians(head_deg + 12)
        je_x = cx + (r + bw * 2.8) * math.cos(jaw_ext_a)
        je_y = cy - (r + bw * 2.8) * math.sin(jaw_ext_a)
        # Jaw tip (toward mouth center, at top)
        mouth_a = math.radians(90)
        jt_x = cx + (r + bw * 1.5) * math.cos(mouth_a)
        jt_y = cy - (r + bw * 1.5) * math.sin(mouth_a)

        draw.polygon([(h_out_x, h_out_y), (je_x, je_y), (jt_x, jt_y)],
                     fill=c_body)

        # ── Head: lower jaw (smaller) ──
        h_in_x = cx + (r - bw) * math.cos(ha)
        h_in_y = cy - (r - bw) * math.sin(ha)
        # Lower jaw tip
        ljt_x = cx + (r - bw * 0.5) * math.cos(mouth_a)
        ljt_y = cy - (r - bw * 0.5) * math.sin(mouth_a)
        # Mid point for shape
        lm_a = math.radians(head_deg + 8)
        lm_x = cx + (r - bw * 0.2) * math.cos(lm_a)
        lm_y = cy - (r - bw * 0.2) * math.sin(lm_a)

        draw.polygon([(h_in_x, h_in_y), (lm_x, lm_y), (ljt_x, ljt_y)],
                     fill=c_body)

        # ── Tail: tapers from body width to thin point entering mouth ──
        ta = math.radians(tail_deg)
        t_out_x = cx + (r + bw) * math.cos(ta)
        t_out_y = cy - (r + bw) * math.sin(ta)
        t_in_x = cx + (r - bw) * math.cos(ta)
        t_in_y = cy - (r - bw) * math.sin(ta)

        # Tail tip (thin, entering between the jaws)
        tip_x = cx + (r + bw * 0.5) * math.cos(mouth_a)
        tip_y = cy - (r + bw * 0.5) * math.sin(mouth_a)

        # Curved tail using intermediate points
        mid_a = math.radians(tail_deg - 10)
        tm_out_x = cx + (r + bw * 0.6) * math.cos(mid_a)
        tm_out_y = cy - (r + bw * 0.6) * math.sin(mid_a)
        tm_in_x = cx + (r - bw * 0.3) * math.cos(mid_a)
        tm_in_y = cy - (r - bw * 0.3) * math.sin(mid_a)

        draw.polygon([
            (t_out_x, t_out_y), (tm_out_x, tm_out_y),
            (tip_x, tip_y),
            (tm_in_x, tm_in_y), (t_in_x, t_in_y)
        ], fill=c_tail)

        # ── Eye ──
        eye_a = math.radians(head_deg + 10)
        eye_r_pos = r + bw * 1.5
        ex = cx + eye_r_pos * math.cos(eye_a)
        ey = cy - eye_r_pos * math.sin(eye_a)
        er = size * 0.03
        draw.ellipse([(ex - er, ey - er), (ex + er, ey + er)], fill=c_eye)

        # ── Scale marks along the body ──
        for d in range(tail_deg + 30, tail_deg + 330, 40):
            a = math.radians(d % 360)
            sx1 = cx + (r - bw * 0.6) * math.cos(a)
            sy1 = cy - (r - bw * 0.6) * math.sin(a)
            sx2 = cx + (r + bw * 0.6) * math.cos(a)
            sy2 = cy - (r + bw * 0.6) * math.sin(a)
            draw.line([(sx1, sy1), (sx2, sy2)], fill=c_scale, width=1)

        img.save(str(path))
        return str(path)
    except Exception as e:
        _logger.warning(f"Could not create icon: {e}")
        return None


def get_local_ip():
    """Get local LAN IP. Prefers real WiFi/Ethernet interface over proxy/VPN."""
    # First try: get IP from actual network interfaces (avoids proxy/VPN IPs)
    try:
        import netifaces
        for iface in ["en0", "en1"]:  # macOS WiFi / Ethernet
            addrs = netifaces.ifaddresses(iface).get(netifaces.AF_INET, [])
            for a in addrs:
                ip = a.get("addr")
                if ip and not ip.startswith("127.") and not ip.startswith("198.18."):
                    return ip
    except ImportError:
        pass

    # Fallback: parse ifconfig for en0/en1
    try:
        import re as _re
        for iface in ["en0", "en1"]:
            r = subprocess.run(["ifconfig", iface], capture_output=True, text=True, timeout=3)
            m = _re.search(r"inet (\d+\.\d+\.\d+\.\d+)", r.stdout)
            if m and not m.group(1).startswith("127.") and not m.group(1).startswith("198.18."):
                return m.group(1)
    except Exception:
        pass

    # Last resort: UDP socket (may return proxy/VPN IP)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def get_phone_target():
    """Read saved phone IP:PORT from config."""
    try:
        if PHONE_CONFIG.exists():
            return PHONE_CONFIG.read_text().strip()
    except Exception:
        pass
    return None


def format_size(n):
    if n < 1024:
        return f"{n} B"
    elif n < 1024 * 1024:
        return f"{n // 1024} KB"
    else:
        return f"{n / 1024 / 1024:.1f} MB"


def save_file(filename, data):
    """Save data to RECEIVE_DIR with collision handling."""
    RECEIVE_DIR.mkdir(parents=True, exist_ok=True)
    filename = re.sub(r'[/\\]', '_', filename)
    path = RECEIVE_DIR / filename
    if path.exists():
        stem, ext = path.stem, path.suffix
        i = 1
        while path.exists():
            path = RECEIVE_DIR / f"{stem}_{i}{ext}"
            i += 1
    path.write_bytes(data)
    return path


def get_clipboard_text():
    """Get text from Mac clipboard."""
    try:
        r = subprocess.run(["pbpaste"], capture_output=True, timeout=3)
        return r.stdout.decode("utf-8", errors="replace")
    except Exception:
        return ""


def set_clipboard_text(text):
    """Set text to Mac clipboard."""
    try:
        subprocess.run(["pbcopy"], input=text.encode("utf-8"), timeout=3)
    except Exception:
        pass


# ── Proxy-free HTTP for LAN ──────────────────────────────────
# urllib.request goes through system proxy (Clash etc.), which breaks LAN connections.
# Use http.client directly for all phone/LAN HTTP requests.

def _lan_request(host, port, method, path, body=None, headers=None, timeout=5):
    """Make HTTP request bypassing macOS Network Extension.
    Uses curl subprocess since Cocoa app sockets get intercepted by proxy extensions."""
    cmd = ["curl", "-s", "--noproxy", "*", "--connect-timeout", str(min(timeout, 10)),
           "-m", str(timeout), "-X", method]
    if headers:
        for k, v in headers.items():
            cmd += ["-H", f"{k}: {v}"]
    if body:
        cmd += ["--data-binary", "@-"]
    cmd += ["-w", "\n%{http_code}"]
    cmd.append(f"http://{host}:{port}{path}")
    r = subprocess.run(cmd, input=body, capture_output=True, timeout=timeout + 5)
    if r.returncode != 0:
        raise OSError(f"curl failed: exit {r.returncode}, stderr={r.stderr[:200]}")
    # Extract HTTP status code from curl -w output
    output = r.stdout
    last_nl = output.rfind(b"\n")
    if last_nl >= 0:
        status_code = int(output[last_nl + 1:].strip() or b"200")
        body_data = output[:last_nl]
    else:
        status_code = 200
        body_data = output
    return status_code, body_data


# ── Phone Discovery ──────────────────────────────────────────

def check_phone(host, port=PHONE_PORT, timeout=3):
    """Check if a Claude Push phone is reachable at host:port.
    Uses curl to bypass macOS Network Extension interception."""
    try:
        r = subprocess.run(
            ["curl", "-s", "--noproxy", "*", "--connect-timeout", str(min(timeout, 5)),
             "-m", str(timeout), f"http://{host}:{port}/status"],
            capture_output=True, text=True, timeout=timeout + 3
        )
        if r.returncode != 0:
            return None
        data = json.loads(r.stdout)
        if data.get("platform") == "Android" or data.get("device"):
            return data
    except Exception:
        pass
    return None


def scan_subnet_for_phone(my_ip, timeout=3):
    """Scan local subnet (and ±1 neighbor) for phone on PHONE_PORT.
    Phase 1: fast TCP probe to find open ports (0.5s timeout).
    Phase 2: HTTP check only on IPs with open port.
    Returns (ip, status_data) or None."""
    _slog = lambda msg: _logger.info(f"[scan] {msg}")
    parts = my_ip.split(".")
    if len(parts) != 4:
        return None

    prefix = f"{parts[0]}.{parts[1]}"
    third = int(parts[2])
    subnets = [f"{prefix}.{third}"]
    if third > 0:
        subnets.append(f"{prefix}.{third - 1}")
    if third < 255:
        subnets.append(f"{prefix}.{third + 1}")

    # Scan each subnet separately with moderate concurrency to avoid SYN flood
    # macOS Network Extension (sing-box) intercepts ALL Python sockets,
    # even in subprocesses. Only curl bypasses it. Use parallel curl for scanning.
    for subnet in subnets:
        _slog(f"scanning {subnet}.* via curl...")
        pool = concurrent.futures.ThreadPoolExecutor(max_workers=30)
        try:
            futures = {}
            for i in range(1, 255):
                ip = f"{subnet}.{i}"
                if ip == my_ip:
                    continue
                futures[pool.submit(check_phone, ip, PHONE_PORT, 1)] = ip
            # 30 curl processes, 1s connect timeout → ~9 batches, ~10s per subnet
            for future in concurrent.futures.as_completed(futures, timeout=20):
                try:
                    result = future.result()
                    if result:
                        found_ip = futures[future]
                        _slog(f"FOUND {found_ip}")
                        return (found_ip, result)
                except Exception:
                    pass
            _slog(f"{subnet}.* done (not found)")
        except concurrent.futures.TimeoutError:
            _slog(f"{subnet}.* TIMEOUT")
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

    _slog("all subnets done, not found")
    return None


_adb_path = None  # cached ADB binary path


def _find_adb():
    """Find ADB binary, cached after first lookup."""
    global _adb_path
    if _adb_path is not None:
        return _adb_path if _adb_path else None
    adb = shutil.which("adb")
    if not adb:
        for p in ["/usr/local/bin/adb", Path.home() / "Library/Android/sdk/platform-tools/adb"]:
            if Path(p).exists():
                adb = str(p)
                break
    _adb_path = adb or ""
    return adb


def try_adb_forward():
    """Try ADB USB connection. Returns status dict if phone connected via USB, else None."""
    adb = _find_adb()
    if not adb:
        return None

    try:
        r = subprocess.run([adb, "devices"], capture_output=True, text=True, timeout=5)
        lines = [l.strip() for l in r.stdout.strip().split("\n")[1:] if l.strip()]
        if not any("\tdevice" in l for l in lines):
            return None

        r = subprocess.run(
            [adb, "forward", f"tcp:{ADB_FORWARD_PORT}", f"tcp:{PHONE_PORT}"],
            capture_output=True, text=True, timeout=5
        )
        if r.returncode != 0:
            return None

        status = check_phone("127.0.0.1", ADB_FORWARD_PORT, timeout=2)
        if status:
            _logger.info(f"[discovery] ADB USB connected: {status.get('device', 'unknown')}")
            return status
        # Remove forward if phone app not running
        subprocess.run([adb, "forward", "--remove", f"tcp:{ADB_FORWARD_PORT}"],
                       capture_output=True, timeout=3)
        return None
    except Exception as e:
        _logger.warning(f"[discovery] ADB error: {e}")
        return None


def remove_adb_forward():
    """Remove ADB port forwarding."""
    adb = _find_adb()
    if adb:
        try:
            subprocess.run([adb, "forward", "--remove", f"tcp:{ADB_FORWARD_PORT}"],
                           capture_output=True, timeout=3)
        except Exception:
            pass


# ── HTTP Server ──────────────────────────────────────────────

class ReceiveHandler(http.server.BaseHTTPRequestHandler):
    app_ref = None

    def log_message(self, *a):
        pass

    def _json(self, code, data):
        body = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        p = urlparse(self.path).path
        if p == "/status":
            my_ip = get_local_ip()
            self._json(200, {
                "status": "ok",
                "hostname": socket.gethostname(),
                "platform": "macOS",
                "port": PORT,
                "host": my_ip,
            })
        elif p == "/files":
            self._files_list()
        elif p == "/clipboard":
            text = get_clipboard_text()
            self._json(200, {"text": text, "length": len(text)})
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        p = urlparse(self.path).path

        # Handle /announce before saving phone IP (it carries explicit host info)
        if p == "/announce":
            self._handle_announce()
            return

        # Save phone IP from incoming request
        client_ip = self.client_address[0] if self.client_address else None
        if client_ip and self.app_ref:
            self.app_ref.save_phone_ip(client_ip)

        if p == "/push/text":
            self._recv_text()
        elif p == "/push":
            self._recv_file()
        elif p == "/clipboard":
            self._set_clipboard()
        else:
            self._json(404, {"error": "not found"})

    def _handle_announce(self):
        """Phone announces itself to Mac."""
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
            host = data.get("host", "")
            port = data.get("port", PHONE_PORT)
            if not host:
                self._json(400, {"error": "missing host"})
                return
            _logger.info(f"[announce] Phone announced itself: {host}:{port}")
            if self.app_ref:
                self.app_ref.save_phone_ip(host, check_phone(host, port, timeout=2), announce=False)
            self._json(200, {"ok": True, "saved": f"{host}:{port}"})
        except Exception as e:
            self._json(400, {"error": str(e)})

    def _files_list(self):
        RECEIVE_DIR.mkdir(parents=True, exist_ok=True)
        entries = []
        for f in RECEIVE_DIR.iterdir():
            if f.is_file() and not f.name.startswith("."):
                st = f.stat()
                entries.append((f.name, st.st_size, st.st_mtime))
        entries.sort(key=lambda e: e[2], reverse=True)
        self._json(200, {"files": [
            {"name": name, "size": size, "timestamp": int(mtime * 1000)}
            for name, size, mtime in entries[:20]
        ]})

    def _set_clipboard(self):
        """Phone pushes text to Mac clipboard."""
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
            text = data.get("text", "")
        except Exception:
            text = body.decode("utf-8", errors="replace")

        if not text:
            self._json(400, {"error": "empty"})
            return

        set_clipboard_text(text)
        self._json(200, {"ok": True, "chars": len(text)})
        if self.app_ref:
            preview = text[:50] + ("..." if len(text) > 50 else "")
            self.app_ref.on_clipboard_received(preview)

    def _recv_text(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        try:
            data = json.loads(body)
            text, title = data.get("text", ""), data.get("title", "")
        except Exception:
            text, title = body.decode("utf-8", errors="replace"), ""

        if not text.strip():
            self._json(400, {"error": "empty"})
            return

        # Auto-copy received text to clipboard
        set_clipboard_text(text)

        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = save_file(f"{ts}_{title or 'text'}.txt", text.encode("utf-8"))
        self._json(200, {"ok": True, "file": path.name, "chars": len(text)})
        if self.app_ref:
            self.app_ref.on_file_received(path, is_text=True)

    def _recv_file(self):
        ct = self.headers.get("Content-Type", "")
        length = int(self.headers.get("Content-Length", 0))
        max_size = 500 * 1024 * 1024  # 500MB
        if length > max_size:
            self._json(413, {"error": f"File too large ({length} bytes, max {max_size})"})
            return
        params = parse_qs(urlparse(self.path).query)
        filename = params.get("filename", [None])[0]

        # Stream body to a temp file in chunks to avoid OOM on large files
        CHUNK = 256 * 1024  # 256KB
        RECEIVE_DIR.mkdir(parents=True, exist_ok=True)
        tmp_path = RECEIVE_DIR / f".recv_{datetime.now().strftime('%Y%m%d_%H%M%S')}_{id(self)}.tmp"
        try:
            remaining = length
            with open(tmp_path, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(CHUNK, remaining))
                    if not chunk:
                        break
                    f.write(chunk)
                    remaining -= len(chunk)

            if "multipart/form-data" in ct:
                self._parse_multipart_from_file(tmp_path, ct, filename)
            else:
                if not filename:
                    filename = f"file_{datetime.now().strftime('%Y%m%d_%H%M%S')}"
                final_path = RECEIVE_DIR / filename
                if final_path.exists():
                    stem, ext = final_path.stem, final_path.suffix
                    final_path = RECEIVE_DIR / f"{stem}_{datetime.now().strftime('%H%M%S')}{ext}"
                tmp_path.rename(final_path)
                size = final_path.stat().st_size
                self._json(200, {"ok": True, "file": final_path.name, "size": size})
                if self.app_ref:
                    self.app_ref.on_file_received(final_path)
        except Exception as e:
            _logger.exception("Error receiving file")
            self._json(500, {"error": str(e)})
        finally:
            if tmp_path.exists():
                tmp_path.unlink(missing_ok=True)

    def _parse_multipart_from_file(self, tmp_path, ct, filename):
        """Parse multipart from a temp file, extracting the file part without loading all into memory."""
        boundary = None
        for seg in ct.split(";"):
            seg = seg.strip()
            if seg.startswith("boundary="):
                boundary = seg[9:].strip('"')
        if not boundary:
            self._json(400, {"error": "no boundary"})
            return

        sep = f"--{boundary}".encode()
        end_sep = f"--{boundary}--".encode()

        with open(tmp_path, "rb") as f:
            raw = f.read()

        parts = raw.split(sep)
        for part in parts[1:]:
            if part.startswith(b"--") or part.strip() == b"--":
                break
            if part.startswith(b"\r\n"):
                part = part[2:]
            if b"\r\n\r\n" not in part:
                continue
            hdr, data = part.split(b"\r\n\r\n", 1)
            if data.endswith(b"\r\n"):
                data = data[:-2]

            if not filename:
                for line in hdr.decode("utf-8", errors="replace").splitlines():
                    if "filename=" in line:
                        for s in line.split(";"):
                            s = s.strip()
                            if s.startswith("filename="):
                                filename = s.split("=", 1)[1].strip('"')
            if not filename:
                filename = f"file_{datetime.now().strftime('%Y%m%d_%H%M%S')}"

            path = save_file(filename, data)
            self._json(200, {"ok": True, "file": path.name, "size": len(data)})
            if self.app_ref:
                self.app_ref.on_file_received(path)
            return

        self._json(400, {"error": "no file in multipart"})


# ── Drop Zone Window ────────────────────────────────────────

if _HAS_APPKIT:
    # Use ObjC ivar for _dragging to survive PyObjC bridge edge cases
    _drop_dragging = {}  # instance id -> bool, avoids PyObjC attribute issues

    class DropView(NSView):
        """NSView that accepts file drag-and-drop, sends files to phone."""

        def initWithFrame_(self, frame):
            self = objc.super(DropView, self).initWithFrame_(frame)
            if self is not None:
                self.registerForDraggedTypes_([NSPasteboardTypeFileURL])
                _drop_dragging[id(self)] = False
            return self

        def dealloc(self):
            _drop_dragging.pop(id(self), None)
            objc.super(DropView, self).dealloc()

        def _is_dragging(self):
            return _drop_dragging.get(id(self), False)

        def drawRect_(self, rect):
            try:
                w, h = rect.size.width, rect.size.height
                dragging = self._is_dragging()
                # Background
                if dragging:
                    NSColor.colorWithRed_green_blue_alpha_(0.15, 0.25, 0.45, 0.95).set()
                else:
                    NSColor.colorWithRed_green_blue_alpha_(0.12, 0.12, 0.14, 0.95).set()
                NSBezierPath.fillRect_(rect)
                # Dashed border
                inset = NSMakeRect(12, 12, w - 24, h - 24)
                c = (0.4, 0.6, 1.0, 0.8) if dragging else (0.4, 0.4, 0.45, 0.6)
                NSColor.colorWithRed_green_blue_alpha_(*c).set()
                bp = NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(inset, 10, 10)
                bp.setLineWidth_(2.0)
                bp.setLineDash_count_phase_([6.0, 4.0], 2, 0)
                bp.stroke()
                # Label
                from Foundation import NSString
                text = NSString.stringWithString_(
                    "✅ Release to send" if dragging else "📲 Drop files here"
                )
                attrs = {
                    NSFontAttributeName: NSFont.systemFontOfSize_(15),
                    NSForegroundColorAttributeName: NSColor.whiteColor(),
                }
                sz = text.sizeWithAttributes_(attrs)
                text.drawAtPoint_withAttributes_(
                    NSMakePoint((w - sz.width) / 2, (h - sz.height) / 2), attrs)
            except Exception as e:
                _logger.warning(f"[DropView] drawRect_ error: {e}")

        def draggingEntered_(self, sender):
            _drop_dragging[id(self)] = True
            self.setNeedsDisplay_(True)
            return NSDragOperationCopy

        def draggingUpdated_(self, sender):
            return NSDragOperationCopy

        def draggingExited_(self, sender):
            _drop_dragging[id(self)] = False
            self.setNeedsDisplay_(True)

        def prepareForDragOperation_(self, sender):
            return True

        def performDragOperation_(self, sender):
            _drop_dragging[id(self)] = False
            self.setNeedsDisplay_(True)
            pb = sender.draggingPasteboard()
            urls = pb.readObjectsForClasses_options_([NSURL], None)
            if urls and _drop_callback:
                paths = [str(u.path()) for u in urls if u.path()]
                if paths:
                    _drop_callback(paths)
            return True


# ── Menu Bar App ─────────────────────────────────────────────

class ClaudePushApp(rumps.App):
    def __init__(self):
        icon_dir = Path(__file__).parent
        icon_path = str(icon_dir / "icon_menubar.png")
        flash_path = str(icon_dir / "icon_menubar_flash.png")
        # Fallback to generated icons if files missing
        if not Path(icon_path).exists():
            icon_path = _create_ouroboros_icon()
            flash_path = _create_ouroboros_icon(flash=True)
        super().__init__("", icon=icon_path, quit_button=None)
        self._icon_path = icon_path
        self._flash_icon_path = flash_path
        RECEIVE_DIR.mkdir(parents=True, exist_ok=True)
        self.ip = get_local_ip()
        self.server = None
        self.mdns_proc = None
        self.phone_ip = None
        self.phone_device_id = None  # device fingerprint
        self.phone_via_adb = False   # connected via USB?
        self._discover_lock = threading.Lock()
        self._dev_polling = False
        self._drop_window = None

        # Load saved device ID
        try:
            if PHONE_DEVICE_CONFIG.exists():
                self.phone_device_id = PHONE_DEVICE_CONFIG.read_text().strip()
        except Exception:
            pass

        phone_status = self._get_phone_status_label()
        self.menu = [
            rumps.MenuItem(f"📡 {self.ip}:{PORT}", callback=None),
            rumps.MenuItem(phone_status, callback=None),
            None,
            rumps.MenuItem("📤 Send File → Phone", callback=self.send_file_to_phone),
            rumps.MenuItem("📋 Send Clipboard → Phone", callback=self.send_clipboard_to_phone),
            rumps.MenuItem("🎯 Drop Zone", callback=self.toggle_drop_zone),
            rumps.MenuItem("🔍 Discover Phone", callback=self._manual_discover),
            None,
            rumps.MenuItem("Recent Files"),
            None,
            rumps.MenuItem("📂 Open Folder", callback=self.open_folder),
            rumps.MenuItem("Quit", callback=self.quit_app),
        ]

        self._start_server()
        self._start_mdns()
        # Auto-discover phone on startup
        threading.Thread(target=self._discover_phone, daemon=True).start()

    def _start_server(self):
        ReceiveHandler.app_ref = self
        try:
            self.server = http.server.HTTPServer(("0.0.0.0", PORT), ReceiveHandler)
            self.server.socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            t = threading.Thread(target=self.server.serve_forever, daemon=True)
            t.start()
        except OSError:
            _logger.error(f"Port {PORT} already in use")

    def _start_mdns(self):
        try:
            hostname = socket.gethostname().replace(" ", "-")
            self.mdns_proc = subprocess.Popen(
                ["dns-sd", "-R", f"ClaudePushMac-{hostname}",
                 "_claudepush._tcp", "local", str(PORT)],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            atexit.register(self._stop_mdns)
        except Exception:
            pass

    def _stop_mdns(self):
        if self.mdns_proc:
            self.mdns_proc.terminate()
            self.mdns_proc = None

    def save_phone_ip(self, ip, device_data=None, announce=True):
        """Save phone IP when it connects to us or is discovered.
        Set announce=False when phone told us about itself (avoid echo loop)."""
        if not ip:
            return
        is_adb = (ip == "127.0.0.1")
        if is_adb and not self.phone_via_adb:
            return  # Don't save 127.0.0.1 from random localhost connections

        is_new = ip != self.phone_ip
        if is_new or (device_data and not self.phone_device_id):
            self.phone_ip = ip
            self.phone_via_adb = is_adb
            try:
                PHONE_CONFIG.parent.mkdir(parents=True, exist_ok=True)
                PHONE_CONFIG.write_text(f"{ip}:{PHONE_PORT}")
            except Exception:
                pass

            # Save device fingerprint
            if device_data and device_data.get("device_id"):
                self.phone_device_id = device_data["device_id"]
                try:
                    PHONE_DEVICE_CONFIG.write_text(self.phone_device_id)
                except Exception:
                    pass

            self._update_phone_menu()

            # Announce ourselves to the phone so it knows our IP immediately
            if is_new and not is_adb and announce:
                threading.Thread(target=self._announce_to_phone, args=(ip,), daemon=True).start()

    def _announce_to_phone(self, phone_ip):
        """Tell the phone our IP and port so it doesn't need to scan for us."""
        my_ip = get_local_ip()
        if my_ip == "127.0.0.1":
            return
        try:
            data = json.dumps({"host": my_ip, "port": PORT}).encode("utf-8")
            status, resp = _lan_request(
                phone_ip, PHONE_PORT, "POST", "/announce",
                body=data,
                headers={"Content-Type": "application/json"},
                timeout=5,
            )
            _logger.info(f"[announce] Told phone {phone_ip} we're at {my_ip}:{PORT} → HTTP {status}")
        except Exception as e:
            _logger.warning(f"[announce] Failed to announce to {phone_ip}: {e}")

    def _get_phone_target(self):
        """Get phone IP:PORT."""
        if self.phone_ip:
            return f"{self.phone_ip}:{PHONE_PORT}"
        return get_phone_target()

    def _get_phone_status_label(self):
        """Generate phone connection status label for menu."""
        if self.phone_ip:
            mode = "USB" if self.phone_via_adb else "WiFi"
            return f"📱 Phone: {self.phone_ip} ({mode})"
        return "📱 Phone: not connected"

    def _update_phone_menu(self):
        """Update the phone status menu item."""
        try:
            new_label = self._get_phone_status_label()
            for key in list(self.menu.keys()):
                item = self.menu[key]
                if hasattr(item, 'title') and "Phone" in item.title:
                    # rumps uses the original title as key, so we need to
                    # remove and re-add if the key changed
                    if key != new_label:
                        del self.menu[key]
                        self.menu.insert_after(list(self.menu.keys())[0], rumps.MenuItem(new_label, callback=None))
                    return
        except Exception as e:
            _logger.error(f"[menu] update failed: {e}")

    def _manual_discover(self, _):
        """Manual discover triggered from menu."""
        threading.Thread(target=self._discover_phone, args=(True,), daemon=True).start()

    def _discover_phone(self, notify=False):
        """Active phone discovery: saved IP → ADB USB → subnet scan."""
        if not self._discover_lock.acquire(blocking=False):
            return
        try:
            found = False

            # 1. Try saved IP first (fast path)
            saved = get_phone_target()
            _log = lambda msg: _logger.info(f"[discovery] {msg}")
            _log(f"start (saved={saved}, my_ip={get_local_ip()})")


            if saved:
                host, _, port_s = saved.partition(":")
                port = int(port_s) if port_s else PHONE_PORT
                status = check_phone(host, port, timeout=2)
                if status:
                    self.save_phone_ip(host, status)
                    _logger.info(f"[discovery] Phone verified at saved IP: {host}")
                    found = True

            # 2. Try ADB USB (works without any network)
            if not found:
                _log("trying ADB...")
                status = try_adb_forward()
                _log(f"ADB result: {status is not None}")
                if status:
                    self.phone_via_adb = True
                    self.save_phone_ip("127.0.0.1", status)
                    _logger.info(f"[discovery] Phone connected via USB: {status.get('device', '?')}")
                    found = True
                    if notify:
                        self._notify("Phone found", f"USB: {status.get('device', 'unknown')}")

            # 3. Scan subnet (slowest but most general)
            if not found:
                my_ip = get_local_ip()
                _log(f"scanning subnet (my_ip={my_ip})...")
                if my_ip != "127.0.0.1":
                    result = scan_subnet_for_phone(my_ip)
                    if result:
                        ip, status = result
                        self.save_phone_ip(ip, status)
                        _logger.info(f"[discovery] Phone found via scan: {ip} ({status.get('device', '?')})")
                        found = True
                        if notify:
                            self._notify("Phone found", f"WiFi: {ip} ({status.get('device', 'unknown')})")

            if not found:
                _logger.info("[discovery] Phone not found")
                if notify:
                    self._notify("Phone not found", "Make sure Claude Push is open on your phone")
        except Exception as e:
            _logger.error(f"[discovery] Error: {e}")
        finally:
            self._discover_lock.release()

    @rumps.timer(5)
    def _timer(self, _):
        self._refresh_files()
        new_ip = get_local_ip()
        if new_ip != self.ip:
            # Ignore sing-box/proxy virtual IPs (198.18.x.x) — not a real network change
            if new_ip.startswith("198.18.") or self.ip.startswith("198.18."):
                return
            old_ip = self.ip
            self.ip = new_ip
            status_key = list(self.menu.keys())[0]
            self.menu[status_key].title = f"📡 {self.ip}:{PORT}"
            _logger.info(f"[discovery] IP changed {old_ip} → {new_ip}")
            self.phone_ip = None
            self.phone_via_adb = False
            self._update_phone_menu()
            if new_ip != "127.0.0.1":
                threading.Thread(target=self._discover_phone, daemon=True).start()

    @rumps.timer(30)
    def _phone_checker(self, _):
        """Periodic phone reachability check + auto-discover if lost."""
        def _check():
            if self.phone_ip:
                # Verify phone still reachable
                port = ADB_FORWARD_PORT if self.phone_via_adb else PHONE_PORT
                status = check_phone(self.phone_ip, port, timeout=3)
                if status:
                    self._fail_count = 0
                    self._update_phone_menu()
                    return  # Still connected
                # Tolerate transient failures — only disconnect after 3 consecutive misses
                self._fail_count = getattr(self, '_fail_count', 0) + 1
                _logger.info(f"[discovery] Phone {self.phone_ip} check failed ({self._fail_count}/3)")
                if self._fail_count < 3:
                    return  # Give it another chance
                _logger.info(f"[discovery] Phone {self.phone_ip} unreachable after 3 checks, rediscovering...")
                self._fail_count = 0
                self.phone_ip = None
                self.phone_via_adb = False
                self._update_phone_menu()
            # No phone known — try to find one
            self._discover_phone()
        threading.Thread(target=_check, daemon=True).start()

    @rumps.timer(60)
    def _dev_poller(self, _):
        """每60秒从 NAS 拉取开发心得"""
        if self._dev_polling:
            return
        threading.Thread(target=self._poll_dev_ideas, daemon=True).start()

    def _poll_dev_ideas(self):
        self._dev_polling = True
        ua = {"User-Agent": "ClaudePush/1.0"}
        try:
            req = urllib.request.Request(f"{NAS_URL}/dev-ideas", headers=ua)
            with urllib.request.urlopen(req, timeout=15) as resp:
                items = json.loads(resp.read()).get("files", [])
        except Exception as e:
            _logger.warning(f"[dev_poller] 拉取失败: {e}")
            return
        finally:
            self._dev_polling = False

        if not items:
            return

        DEV_IDEAS_DIR.mkdir(parents=True, exist_ok=True)
        last_text = None

        for item in items:
            filename = item.get("file", "")
            text = item.get("text", "")
            if not filename or not text:
                continue

            # 保存到 dev-ideas 目录
            (DEV_IDEAS_DIR / filename).write_text(text, encoding="utf-8")

            # 存到 ClaudePush 目录并通知
            ts = datetime.now().strftime("%Y%m%d_%H%M%S")
            path = save_file(f"dev_{ts}_{filename}", text.encode("utf-8"))
            self.on_file_received(path, is_text=True)
            last_text = text

            # 标记已取
            try:
                data = json.dumps({"file": filename}).encode("utf-8")
                mark = urllib.request.Request(
                    f"{NAS_URL}/dev-ideas-done", data=data,
                    headers={**ua, "Content-Type": "application/json"}, method="POST"
                )
                urllib.request.urlopen(mark, timeout=10)
            except Exception as e:
                _logger.warning(f"[dev_poller] 标记失败 {filename}: {e}")

        # 只复制最后一条到剪贴板
        if last_text:
            set_clipboard_text(last_text)
            _logger.info(f"[dev_poller] 拉取 {len(items)} 条开发想法")

    def _refresh_files(self):
        try:
            files = sorted(
                [f for f in RECEIVE_DIR.iterdir() if f.is_file() and not f.name.startswith(".")],
                key=lambda f: f.stat().st_mtime, reverse=True
            )[:10]
        except Exception:
            files = []

        sub = self.menu.get("Recent Files")
        if sub is None:
            return
        try:
            for key in list(sub.keys()):
                del sub[key]
        except Exception:
            pass
        if files:
            for i, f in enumerate(files):
                try:
                    st = f.stat()
                    ts = datetime.fromtimestamp(st.st_mtime).strftime("%H:%M")
                    label = f"{f.name}  ({format_size(st.st_size)}, {ts})"
                    item = rumps.MenuItem(label, callback=lambda _, p=f: self._open_file(p))
                    sub[f"f{i}"] = item
                except Exception:
                    pass
        else:
            sub["empty"] = rumps.MenuItem("No files yet", callback=None)

    def _send_file_to_phone(self, filepath):
        """Push a file to the phone via HTTP multipart POST."""
        target = self._get_phone_target()
        if not target:
            self._notify("No phone found", "Click 🔍 Discover Phone or connect via USB")
            threading.Thread(target=self._discover_phone, daemon=True).start()
            return

        path = Path(filepath)
        if not path.exists() or not path.is_file():
            self._notify("File not found", path.name)
            return

        def do_push():
            try:
                filename = path.name
                file_size = path.stat().st_size
                host, _, port_s = target.partition(":")
                port = int(port_s) if port_s else PHONE_PORT
                # Use curl with file reference to avoid loading into memory
                cmd = [
                    "curl", "-s", "--noproxy", "*", "--connect-timeout", "10", "-m", "300",
                    "-X", "POST",
                    "-F", f'file=@{path};filename={filename}',
                    f"http://{host}:{port}/push?filename={quote(filename)}"
                ]
                r = subprocess.run(cmd, capture_output=True, timeout=310)
                if r.returncode != 0:
                    raise OSError(f"curl failed: exit {r.returncode}")
                self._notify("Sent to phone", f"{filename} ({format_size(file_size)})")
                self._flash_icon()
            except Exception as e:
                self._notify("Send failed", str(e)[:80])

        threading.Thread(target=do_push, daemon=True).start()

    def send_file_to_phone(self, _):
        """Open native file picker and send selected files to phone."""
        if _HAS_APPKIT:
            panel = NSOpenPanel.openPanel()
            panel.setCanChooseFiles_(True)
            panel.setCanChooseDirectories_(False)
            panel.setAllowsMultipleSelection_(True)
            panel.setTitle_("Select files to send to phone")
            if panel.runModal() == 1:  # NSOKButton
                for url in panel.URLs():
                    p = url.path()
                    if p:
                        self._send_file_to_phone(str(p))
        else:
            # Fallback to osascript
            result = subprocess.run([
                "osascript", "-e",
                'set f to choose file with prompt "Select file to send" '
                'with multiple selections allowed',
                "-e", "set paths to {}",
                "-e", "repeat with i in f",
                "-e", "  set end of paths to POSIX path of i",
                "-e", "end repeat",
                "-e", 'set AppleScript\'s text item delimiters to linefeed',
                "-e", "return paths as text",
            ], capture_output=True, text=True)
            if result.returncode == 0 and result.stdout.strip():
                for line in result.stdout.strip().split("\n"):
                    if line.strip():
                        self._send_file_to_phone(line.strip())

    def toggle_drop_zone(self, _):
        """Show or hide a floating drop zone window."""
        if not _HAS_APPKIT:
            self._notify("Not available", "PyObjC required")
            return

        global _drop_callback
        _drop_callback = self._on_files_dropped

        if self._drop_window and self._drop_window.isVisible():
            self._drop_window.close()
            return

        if not self._drop_window:
            w, h = 220, 160
            style = NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
            win = NSWindow.alloc().initWithContentRect_styleMask_backing_defer_(
                NSMakeRect(0, 0, w, h), style, NSBackingStoreBuffered, False)
            win.setTitle_("Claude Push")
            win.setLevel_(NSFloatingWindowLevel)
            win.setOpaque_(False)
            drop_view = DropView.alloc().initWithFrame_(NSMakeRect(0, 0, w, h))
            win.setContentView_(drop_view)
            win.center()
            self._drop_window = win

        self._drop_window.makeKeyAndOrderFront_(None)

    def _on_files_dropped(self, paths):
        """Callback from DropView when files are dropped."""
        for p in paths:
            self._send_file_to_phone(p)

    def send_clipboard_to_phone(self, _):
        """Send Mac clipboard text to phone."""
        target = self._get_phone_target()
        if not target:
            self._notify("No phone found", "Click 🔍 Discover Phone or connect via USB")
            threading.Thread(target=self._discover_phone, daemon=True).start()
            return

        text = get_clipboard_text()
        if not text.strip():
            self._notify("Clipboard empty", "Nothing to send")
            return

        def do_send():
            try:
                data = json.dumps({"text": text}).encode("utf-8")
                host, _, port_s = target.partition(":")
                port = int(port_s) if port_s else PHONE_PORT
                status, resp_body = _lan_request(
                    host, port, "POST", "/clipboard",
                    body=data,
                    headers={"Content-Type": "application/json"},
                    timeout=5,
                )
                result = json.loads(resp_body)
                preview = text[:30] + ("..." if len(text) > 30 else "")
                self._notify("Sent to phone", preview)
            except Exception as e:
                self._notify("Send failed", str(e)[:80])

        threading.Thread(target=do_send, daemon=True).start()

    def _run_on_main(self, func):
        """Schedule a function to run on the main thread via rumps.Timer.

        This avoids AppKit thread-safety violations that deadlock the
        menu bar and keyboard when UI is mutated from a background thread
        (e.g. HTTP handler) while the dropdown menu is open.
        """
        def _fire(timer):
            timer.stop()
            func()
        t = rumps.Timer(_fire, 0.1)
        t.start()

    def on_file_received(self, path, is_text=False):
        if is_text:
            msg = f"Text copied to clipboard ({format_size(path.stat().st_size)})"
        else:
            msg = f"{path.name} ({format_size(path.stat().st_size)})"
        # All UI ops must run on main thread to avoid AppKit deadlock
        self._run_on_main(lambda: self._notify("File received", msg))
        self._run_on_main(self._refresh_files)
        self._flash_icon()

    def on_clipboard_received(self, preview):
        self._run_on_main(lambda: self._notify("Clipboard updated", preview))
        self._flash_icon()

    def _flash_icon(self):
        """Flash menu bar icon to indicate activity (main-thread safe).

        Uses rumps.Timer to schedule UI updates on the main thread,
        avoiding AppKit thread-safety violations that can deadlock
        the entire macOS event dispatch (freezing keyboard/mouse).
        """
        # Stop any previous flash timer to prevent accumulation
        if hasattr(self, '_flash_timer') and self._flash_timer:
            try:
                self._flash_timer.stop()
            except Exception:
                pass
        self._flash_step = 0
        self._flash_frames = ["✨", "🔥", "✨", ""]
        def _flash_tick(timer):
            idx = self._flash_step
            if idx < len(self._flash_frames):
                self.title = self._flash_frames[idx]
                self._flash_step += 1
            else:
                self.title = ""
                if self._icon_path:
                    self.icon = self._icon_path
                timer.stop()
                self._flash_timer = None
        t = rumps.Timer(_flash_tick, 0.3)
        self._flash_timer = t
        t.start()

    def _notify(self, title, msg):
        try:
            rumps.notification("Claude Push", title, msg, sound=True)
        except Exception:
            subprocess.run([
                "osascript", "-e",
                f'display notification "{msg}" with title "Claude Push" subtitle "{title}" sound name "default"'
            ], capture_output=True)

    def _open_file(self, path):
        subprocess.run(["open", str(path)])

    def open_folder(self, _):
        subprocess.run(["open", str(RECEIVE_DIR)])

    def quit_app(self, _):
        self._stop_mdns()
        if self.phone_via_adb:
            remove_adb_forward()
        if self.server:
            threading.Thread(target=self.server.shutdown, daemon=True).start()
        rumps.quit_application()


if __name__ == "__main__":
    ClaudePushApp().run()
