# Claude Push Codebase Cleanup Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strip Claude Push down to its core mission (Mac-phone file/clipboard transfer), remove dead code, unused deps, unrelated features, and unify duplicated patterns.

**Architecture:** No structural refactor of the Mac Python monolith in this pass — that's a separate effort. This plan focuses purely on deletion and deduplication: remove what shouldn't be there, unify what's duplicated, clean up what's left.

**Tech Stack:** Kotlin (Android), Python (Mac), Gradle

---

### Task 1: Delete dead Kotlin code

**Files:**
- Modify: `app/src/main/java/com/flow/claudepush/FileRepository.kt:132-147`
- Modify: `app/src/main/java/com/flow/claudepush/NsdHelper.kt:132-150`
- Modify: `app/src/main/java/com/flow/claudepush/PushService.kt:270`
- Modify: `app/src/main/java/com/flow/claudepush/PushServer.kt:223-226`

- [ ] **Step 1: Delete `FileRepository.saveToOutbox()`**

Remove lines 132-147 in `FileRepository.kt` — the entire `saveToOutbox` method. It is never called anywhere.

- [ ] **Step 2: Delete `NsdHelper.checkMac()`**

Remove the `checkMac` private method in `NsdHelper.kt` (lines 132-150). It is never called — replaced by the announce model.

- [ ] **Step 3: Delete `PushService.MSG_REDISCOVER`**

Remove the `MSG_REDISCOVER` constant from `PushService.kt` companion object. It is never referenced.

- [ ] **Step 4: Delete `PushServer.serveGetClipboard()` stub**

In `PushServer.kt`, remove the `serveGetClipboard()` method and its route entry in `serve()`. It returns a hardcoded empty string and is useless.

```kotlin
// Remove this from the when block in serve():
method == Method.GET && uri == "/clipboard" -> serveGetClipboard()
```

- [ ] **Step 5: Fix video type mapping in FileRepository**

In `FileRepository.kt`, `toReceivedFile()` maps extensions to types but never returns `"video"`. The `copyToMediaStore` code handles video but the type is never set. Add video extensions:

```kotlin
private fun File.toReceivedFile() = ReceivedFile(
    name = name,
    size = length(),
    timestamp = lastModified(),
    type = when (extension.lowercase()) {
        "apk" -> "apk"
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> "image"
        "mp4", "mov", "avi", "mkv", "webm" -> "video"
        "txt", "md", "json", "log", "csv", "xml", "html" -> "text"
        else -> "file"
    }
)
```

- [ ] **Step 6: Build and verify**

Run: `ANDROID_HOME=/Users/flow/ClaudeCode/.android-sdk ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: remove dead Kotlin code (saveToOutbox, checkMac, MSG_REDISCOVER, clipboard stub)"
```

---

### Task 2: Remove unused Gradle dependencies and permissions

**Files:**
- Modify: `app/build.gradle.kts:49-52`
- Modify: `app/src/main/AndroidManifest.xml:7,11,35-60`

- [ ] **Step 1: Remove unused dependencies from build.gradle.kts**

Remove these three lines from the dependencies block:

```kotlin
// DELETE these:
implementation("androidx.navigation:navigation-compose:2.8.5")    // no NavHost used
implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7") // no lifecycle-compose APIs used
implementation("androidx.appcompat:appcompat:1.7.0")               // only theme parent, replace below
```

- [ ] **Step 2: Update theme parent**

Check `app/src/main/res/values/themes.xml` — if it uses `Theme.AppCompat.*`, change to `Theme.Material3.*` so appcompat can be removed. If no themes.xml exists, the Compose theme handles everything and appcompat is truly unused.

- [ ] **Step 3: Remove unused permissions from AndroidManifest.xml**

Remove these two lines:

```xml
<!-- DELETE: never requested at runtime, never used in code -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<!-- DELETE: no network state modification APIs used -->
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
```

- [ ] **Step 4: Remove redundant intent filters from AndroidManifest.xml**

The `*/*` SEND filter (lines 35-40) already matches everything. Remove the duplicate specific filters for `image/*`, `video/*`, `application/*`, `text/*` (lines 41-60).

After cleanup, the activity should have just:
```xml
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="*/*" />
</intent-filter>
```

- [ ] **Step 5: Build and verify**

Run: `ANDROID_HOME=/Users/flow/ClaudeCode/.android-sdk ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: remove unused deps (navigation, lifecycle-compose, appcompat) and permissions"
```

---

### Task 3: Delete NAS dev-ideas poller from Mac

**Files:**
- Modify: `mac/claude_push_mac.py`

This feature polls `xhs.royaldutchhome.com/dev-ideas` every 60 seconds. It has nothing to do with file transfer and doesn't belong.

- [ ] **Step 1: Remove constants**

Delete these lines near the top:

```python
# DELETE:
DEV_IDEAS_DIR = Path.home() / "Documents" / "dev-ideas"
NAS_URL = "https://xhs.royaldutchhome.com"
```

- [ ] **Step 2: Remove the `_dev_polling` instance variable**

In `ClaudePushApp.__init__`, delete:
```python
self._dev_polling = False
```

- [ ] **Step 3: Remove `_dev_poller` timer method**

Delete the entire `_dev_poller` method (the `@rumps.timer(60)` decorated method).

- [ ] **Step 4: Remove `_poll_dev_ideas` method**

Delete the entire `_poll_dev_ideas` method (~50 lines).

- [ ] **Step 5: Remove `urllib.request` import if no longer used**

Check if `urllib.request` is used anywhere else in the file. If not, remove the import.

- [ ] **Step 6: Test Mac app starts**

```bash
pkill -f claude_push_mac.py; sleep 1
/usr/bin/python3 /Users/flow/ClaudeCode/claude-push/mac/claude_push_mac.py &
sleep 3; ps aux | grep claude_push_mac | grep -v grep
```

Expected: process running, no errors in `~/.claude/logs/claude_push_mac.log`

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "chore: remove NAS dev-ideas poller (unrelated to file transfer)"
```

---

### Task 4: Delete icon generation code from Mac

**Files:**
- Modify: `mac/claude_push_mac.py`

The `_create_ouroboros_icon()` function is 122 lines of PIL pixel art. The bundled `icon_menubar.png` and `icon_menubar_flash.png` files already exist and are used. The PIL function is a fallback that's never needed.

- [ ] **Step 1: Delete `_create_ouroboros_icon()` function**

Remove the entire function (lines 76-197).

- [ ] **Step 2: Remove `ICON_DIR` constant**

Delete:
```python
ICON_DIR = Path(tempfile.gettempdir()) / "claudepush_icons"
```

- [ ] **Step 3: Simplify icon loading in `ClaudePushApp.__init__`**

Find where icons are loaded and ensure they use the bundled files directly without fallback to PIL generation. The icon paths should be:
```python
icon_path = str(Path(__file__).parent / "icon_menubar.png")
flash_path = str(Path(__file__).parent / "icon_menubar_flash.png")
```

If there is a fallback chain like `bundled → _create_ouroboros_icon()`, remove the fallback.

- [ ] **Step 4: Remove unused `tempfile` import if no longer needed**

Check if `tempfile` is used elsewhere. If not, remove the import.

- [ ] **Step 5: Test Mac app starts with icons**

```bash
pkill -f claude_push_mac.py; sleep 1
/usr/bin/python3 /Users/flow/ClaudeCode/claude-push/mac/claude_push_mac.py &
sleep 3; ps aux | grep claude_push_mac | grep -v grep
```

Verify menu bar icon appears correctly.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "chore: remove runtime icon generation, use bundled PNGs only"
```

---

### Task 5: Deduplicate file collision logic

**Files:**
- Modify: `app/src/main/java/com/flow/claudepush/FileRepository.kt`
- Modify: `mac/claude_push_mac.py`

The same `stem_1.ext`, `stem_2.ext` pattern exists 4 times. After Task 1 removed `saveToOutbox`, 3 remain.

- [ ] **Step 1: Extract `deduplicateFile` in Kotlin**

In `FileRepository.kt`, extract the collision logic from `save()` into a reusable private method:

```kotlin
private fun deduplicateFile(dir: File, filename: String): File {
    val safe = filename.replace(Regex("[/\\\\]"), "_")
    var target = File(dir, safe)
    if (target.exists()) {
        val base = safe.substringBeforeLast(".")
        val ext = safe.substringAfterLast(".", "")
        var i = 1
        while (target.exists()) {
            target = File(dir, if (ext.isNotEmpty()) "${base}_$i.$ext" else "${base}_$i")
            i++
        }
    }
    return target
}
```

Then simplify `save()`:

```kotlin
fun save(input: InputStream, filename: String): ReceivedFile {
    val target = deduplicateFile(dir, filename)
    input.use { src -> target.outputStream().use { src.copyTo(it) } }
    val received = target.toReceivedFile()
    if (received.type == "image" || received.type == "video") {
        copyToMediaStore(target, received)
    }
    return received
}
```

- [ ] **Step 2: Verify Python `save_file` and `_parse_multipart_from_file` use same pattern**

In `claude_push_mac.py`, `save_file()` and `_parse_multipart_from_file()` both have inline dedup. The `_parse_multipart_from_file` should call `save_file` instead of reimplementing the pattern. Check the multipart parser — if it writes to a temp file then copies, have it call `save_file` for the final save.

- [ ] **Step 3: Build and verify**

Run: `ANDROID_HOME=/Users/flow/ClaudeCode/.android-sdk ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: deduplicate file collision logic"
```

---

### Task 6: Unify Mac curl patterns

**Files:**
- Modify: `mac/claude_push_mac.py`

Three different curl invocation patterns exist: `_lan_request()`, `check_phone()`, and inline curl in `_send_file_to_phone()`. The first two can share a base.

- [ ] **Step 1: Refactor `check_phone` to use `_lan_request`**

`check_phone()` currently builds its own curl command. Refactor it to use `_lan_request()` with latency measurement:

```python
def check_phone(host, port=PHONE_PORT, timeout=3):
    """Check if a Claude Push phone is reachable at host:port.
    Returns status dict (with _latency_ms, _checked_ip) or None."""
    try:
        start = _time.monotonic()
        status_code, body = _lan_request(host, port, "GET", "/status", timeout=timeout)
        latency = (_time.monotonic() - start) * 1000
        if status_code != 200:
            return None
        data = json.loads(body)
        if data.get("platform") == "Android" or data.get("device"):
            data["_latency_ms"] = round(latency, 1)
            data["_checked_ip"] = host
            return data
    except Exception:
        pass
    return None
```

Note: `_send_file_to_phone` uses curl with `--form` for multipart upload — this is a fundamentally different pattern and should stay separate. Only `check_phone` and `_lan_request` should be unified.

- [ ] **Step 2: Test phone discovery still works**

```bash
pkill -f claude_push_mac.py; sleep 1
/usr/bin/python3 /Users/flow/ClaudeCode/claude-push/mac/claude_push_mac.py &
```

Check logs: `tail -20 ~/.claude/logs/claude_push_mac.log`
Verify `[discovery]` entries appear.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: unify check_phone to use _lan_request base"
```

---

### Task 7: Final verification

**Files:** None (testing only)

- [ ] **Step 1: Full Android build**

```bash
cd /Users/flow/ClaudeCode/claude-push
ANDROID_HOME=/Users/flow/ClaudeCode/.android-sdk ./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Mac app smoke test**

```bash
pkill -f claude_push_mac.py; sleep 1
/usr/bin/python3 /Users/flow/ClaudeCode/claude-push/mac/claude_push_mac.py &
sleep 3
# Check running
ps aux | grep claude_push_mac | grep -v grep
# Check log for errors
tail -20 ~/.claude/logs/claude_push_mac.log
```

Expected: No errors, menu bar icon visible.

- [ ] **Step 3: Verify line count reduction**

```bash
wc -l mac/claude_push_mac.py
find app/src -name "*.kt" -exec wc -l {} + | tail -1
```

Target: Mac file drops from ~1,467 to ~1,250 lines. Kotlin drops from ~2,234 to ~2,100 lines. Total ~350 lines removed.

- [ ] **Step 4: Commit tag**

```bash
git tag cleanup-v1
```
