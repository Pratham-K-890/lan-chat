# LAN Chat — Complete Run Guide
## Everything you need to run the app + full feature list

---

## PART 1 — WHAT YOU NEED (Install Once)

### On your PC / Laptop (the server machine)
| Tool | Version | Where to get it |
|---|---|---|
| **JDK 21** | 21+ | https://adoptium.net → Temurin 21 |
| **Maven** | 3.9+ | https://maven.apache.org/download.cgi |

> **Quick check:** Open terminal and run `java -version` and `mvn -version`.
> Both should print version numbers. If not, reinstall and add to PATH.

### On your PC (for building the Android app)
| Tool | Version | Where to get it |
|---|---|---|
| **Android Studio** | Hedgehog or newer | https://developer.android.com/studio |
| **Android SDK API 34** | API level 34 | Installed via Android Studio → SDK Manager |

### On your phone
- Android 8.0 or newer (API 26+)
- Connected to the **same WiFi** as the server PC

---

## PART 2 — STEP-BY-STEP: FIRST TIME SETUP

### Step 1 — Build the shared library

Open a terminal, go into the project folder:

```bash
cd lan-chat
mvn clean install -pl common
```

You should see `BUILD SUCCESS`. This creates:
```
common/target/common-1.0-SNAPSHOT.jar
```

### Step 2 — Build the server

```bash
mvn clean package -pl common,server
```

You get: `server/target/lanchat-server.jar`

### Step 3 — Copy the shared library into the Android project

```bash
# Create the libs folder (first time only)
mkdir -p android-client/app/libs

# Copy the JAR
cp common/target/common-1.0-SNAPSHOT.jar android-client/app/libs/common.jar
```

> **Every time** you change anything in `common/`, repeat steps 1 and 3.

### Step 4 — Open the Android project in Android Studio

1. Open Android Studio
2. Click **File → Open**
3. Navigate to `lan-chat/android-client/` and click **Open**
4. Wait for Gradle sync (bottom bar shows progress)
5. If it asks to update Gradle — click **Don't remind me again**

### Step 5 — Check SDK is installed

`Tools → SDK Manager → SDK Platforms tab`
Make sure **Android 14 (API 34)** is checked. If not, check it and click Apply.

### Step 6 — Build the APK

`Build → Build Bundle(s) / APK(s) → Build APK(s)`

Wait ~1–2 minutes. When done, a notification appears: **"Build successful"**

APK location:
```
android-client/app/build/outputs/apk/debug/app-debug.apk
```

### Step 7 — Install on your phone

**Option A — USB cable (easiest):**
1. On phone: Settings → Developer Options → enable **USB Debugging**
2. Connect phone to PC via USB
3. In Android Studio press the green **▶ Run** button
4. Select your phone from the list → OK

**Option B — Copy APK file:**
1. Copy `app-debug.apk` to your phone (WhatsApp, USB, Google Drive — any way)
2. Open the file on the phone
3. If blocked: Settings → Apps → Special access → Install unknown apps → allow your file manager
4. Tap Install

---

## PART 3 — RUNNING THE APP (Every Time)

### Step 1 — Find your server PC's LAN IP

**Windows:**
```
ipconfig
```
Look for "IPv4 Address" under your WiFi adapter. Example: `192.168.1.100`

**Mac/Linux:**
```
ip addr   (or)   ifconfig
```
Look for `inet` under your WiFi interface. Example: `192.168.1.100`

### Step 2 — Start the server

```bash
cd lan-chat
java -jar server/target/lanchat-server.jar
```

You should see:
```
╔═══════════════════════════════════════╗
║       LAN Chat Server  v1.0           ║
╚═══════════════════════════════════════╝
[SERVER] Starting on TCP port 9090 ...
[SERVER] Ready. Waiting for clients...
```

**Keep this terminal open.** The server runs until you close it (Ctrl+C).

### Step 3 — Open the app on your phone

1. Open **LAN Chat** on your phone
2. You see the terminal login screen
3. Type your server's IP in the **SERVER_NODE** field (e.g. `192.168.1.100`)
4. Tap **[ EXECUTE_CONNECT ]**
5. Wait for "Connected" (green checkmark / auth fields appear)

### Step 4 — Register or Login

**First time:**
- Type a username (min 3 characters)
- Type a password (min 6 characters)
- Tap **[ REGISTER_NEW_NODE ]**

**Next time:**
- Type same username + password
- Tap **[ EXECUTE_LOGIN ]**

### Step 5 — Chat!

You're in. For multiple phones — repeat steps 3–4 on each phone with the same server IP.

---

## PART 4 — FIREWALL (if connection fails)

The server needs two ports open:

**Windows (run as Administrator):**
```powershell
netsh advfirewall firewall add rule name="LanChat TCP" protocol=TCP dir=in localport=9090 action=allow
netsh advfirewall firewall add rule name="LanChat UDP" protocol=UDP dir=in localport=9091 action=allow
```

**Linux:**
```bash
sudo ufw allow 9090/tcp
sudo ufw allow 9091/udp
```

**Mac:** System Settings → Network → Firewall → Options → add the Java app as allowed.

---

## PART 5 — FULL FEATURE LIST

### 🔐 Authentication
| Feature | How to use |
|---|---|
| Register account | Enter username + password → tap REGISTER |
| Login | Enter username + password → tap LOGIN |
| Accounts persist | Users saved in `users.db` — survives server restart |
| Duplicate login blocked | Same username can't log in twice at once |
| Password security | bcrypt hashed — nobody can read your password, even from the file |

---

### 💬 Messaging — [ LOG ] Screen (Broadcast)
| Feature | How to use |
|---|---|
| Send public message | Type in the input bar → tap SEND |
| See all messages | Scrollable feed with [timestamp] > sender: text |
| See online users | Top panel lists everyone currently connected |
| System notifications | Join/leave events appear automatically |
| Messages encrypted | All text is AES-256 encrypted on the wire |

---

### 📨 Direct Messages — [ DM ] Tab
| Feature | How to use |
|---|---|
| Send a private message | Tap [ DM ] in bottom nav → tap any online user |
| DM only visible to you and recipient | Server relays but cannot read (AES end-to-end) |
| Sent message echoed back | You see your own sent messages in the feed |

---

### 👥 Groups — [ GRP ] Screen
| Feature | How to use |
|---|---|
| Create a group | Tap [ GRP ] → tap [ CREATE_NEW_NODE ] → type group name |
| Join a group | Type group name in the command bar at bottom → press Enter |
| Send a group message | Tap a group in the list → SEND_MESSAGE → type message |
| Leave a group | Tap a group in the list → LEAVE_GROUP |
| Groups shown as directory | Terminal-style `drwxr-xr-x` listing |
| Multiple groups | You can be in as many groups as you want |

---

### 📁 File Transfer
| Feature | How to use |
|---|---|
| Send a file | In [ LOG ] screen → tap the options menu (⋮) → Send File |
| Pick any file | System file picker opens — choose any file |
| Select recipient | Choose from online users list |
| Receive a file | A dialog pops up: "Accept / Decline" |
| Progress shown | Progress bar appears during transfer |
| Files saved to | `/storage/emulated/0/Android/data/com.lanchat.android/files/Downloads/` |
| Max file size | No hard limit — large files split into 64 KB chunks automatically |
| Transfer ID | Each transfer gets a UUID — multiple concurrent transfers supported |

---

### 🎙 Voice Calls — [ VOX ] Screen
| Feature | How to use |
|---|---|
| Start a call | Tap [ VOX ] in bottom nav → select user → call starts |
| Accept a call | Dialog pops up: "[ ACCEPT ] / [ REJECT ]" |
| Reject a call | Tap [ REJECT ] in the dialog |
| Mute mic | In voice screen → tap [ MUTE ] |
| Speaker mode | In voice screen → tap [ SPEAKER ] |
| End call | Tap [ TERMINATE_CALL ] |
| Call timer | Shows elapsed time mm:ss during the call |
| Audio visualizer | L_CH / R_CH bars animate during the call |
| Voice quality | 16 kHz mono PCM — clear voice, ~32 KB/s bandwidth |
| Peer-to-peer | Audio goes directly phone → phone, server not involved |
| Jitter buffer | 60ms buffer absorbs network jitter — smooth audio on LAN |

---

### 🔒 Security
| Feature | Details |
|---|---|
| Message encryption | AES-256-CBC with random IV per message |
| Password hashing | bcrypt with work factor 12 (~250ms per hash) |
| Session tokens | UUID token issued on login, stored in memory |
| Pre-shared key | All clients use the same AES passphrase (change in `AesUtil.java`) |
| No plain text storage | Passwords never stored or logged in plain text |

---

### 🔄 Connection Management
| Feature | Details |
|---|---|
| Auto-reconnect | If server drops, app reconnects automatically with back-off |
| Background keep-alive | Foreground service keeps socket alive when app is minimized |
| Notification | Persistent "LAN Chat — Connected" notification while connected |
| Logout | Logout button in menu → disconnects cleanly |
| Re-login after reconnect | Need to login again after a reconnect (session tokens reset) |

---

## PART 6 — KNOWN LIMITS & TIPS

| Limit | Value |
|---|---|
| Max simultaneous clients | 50 (change `MAX_CLIENTS` in `ChatServer.java`) |
| Voice call: 1 at a time per user | Can't be in two calls at once |
| Groups: in-memory | Groups disappear when server restarts |
| Users: persisted | User accounts survive server restarts (`users.db`) |
| Same WiFi required | All devices must be on the same local network |
| Voice UDP port | 9091 — must be the same on all phones |

### Tips
- **Server must start first** before any phone connects
- **All phones same WiFi** — hotspot works too (connect PC and phones to same hotspot)
- **Username is case-sensitive** — "Alice" and "alice" are different accounts
- **If build fails** — check that `common.jar` is in `android-client/app/libs/`
- **If voice has echo** — use earphones on at least one side

---

## PART 7 — TROUBLESHOOTING

| Problem | Fix |
|---|---|
| "Connection refused" | Server not running, or wrong IP, or firewall blocking port 9090 |
| App crashes on open | Check `adb logcat` in Android Studio — usually missing `common.jar` in libs/ |
| "Cannot open UDP port 9091" | Previous call didn't clean up — restart the app |
| No audio in voice call | Grant microphone permission: Settings → Apps → LAN Chat → Permissions |
| Gradle sync fails | File → Invalidate Caches → Restart |
| "BUILD FAILURE" in Maven | Check JDK is 21: `java -version` |
| Messages show garbled text | AES key mismatch — rebuild common.jar and copy to libs/ |
| Voice call accepted but silent | Check both phones on same WiFi; check UDP port 9091 open |
| Server prints stack trace | Check `users.db` is not corrupted — delete it to reset all accounts |

---

## PART 8 — PROJECT STRUCTURE REMINDER

```
lan-chat/
├── common/          ← Shared Java library (protocol, crypto, models)
│                      → Compile once, used by server + Android app
├── server/          ← Java server — runs on your PC
│                      → java -jar server/target/lanchat-server.jar
├── client/          ← Console test client (optional, for debugging)
│                      → java -jar client/target/lanchat-client.jar <server-ip>
└── android-client/  ← Android Studio project → APK for your phone
    └── app/libs/common.jar  ← MUST copy here before building APK
```
