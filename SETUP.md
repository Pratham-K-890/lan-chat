# LAN Chat — Build & Setup Guide

## Project layout

```
lan-chat/
├── common/          ← shared Java library (Maven)
├── server/          ← Java server (Maven, runs on PC)
├── client/          ← console test client (Maven, optional)
└── android-client/  ← Android Studio project (Kotlin + Gradle) → APK
```

---

## Step 1 — Prerequisites

| Tool | Version | Download |
|---|---|---|
| JDK | 21 | https://adoptium.net |
| Maven | 3.9+ | https://maven.apache.org |
| Android Studio | Hedgehog / Iguana | https://developer.android.com/studio |
| Android SDK | API 34 | installed via Android Studio SDK Manager |

---

## Step 2 — Build the common JAR

The `common` module is shared between the Java server and the Android client.

```bash
cd lan-chat
mvn clean install -pl common
```

This produces:
```
common/target/common-1.0-SNAPSHOT.jar
```

---

## Step 3 — Build and run the server

```bash
mvn clean package -pl common,server
java -jar server/target/lanchat-server.jar
```

You should see:
```
[SERVER] Listening on port 9090
[SERVER] Ready. Waiting for clients...
```

The server persists user accounts to `users.db` in the working directory.
Find your machine's LAN IP with `ipconfig` (Windows) or `ip addr` (Linux/Mac).

---

## Step 4 — Import the Android project into Android Studio

1. **Copy the common JAR into the Android project:**
   ```bash
   mkdir -p lan-chat/android-client/app/libs
   cp lan-chat/common/target/common-1.0-SNAPSHOT.jar \
      lan-chat/android-client/app/libs/common.jar
   ```
   > Re-run this copy every time you change `common/` and rebuild the server.

2. **Open Android Studio** → `File → Open` → select `lan-chat/android-client/`

3. **Sync Gradle** — Android Studio will prompt automatically. Click **Sync Now**.

4. **Check SDK is installed:**
   `Tools → SDK Manager` → ensure **API 34** (Android 14) is installed.

5. **Build the APK:**
   `Build → Build Bundle(s) / APK(s) → Build APK(s)`

   Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Step 5 — Install APK on your phone

**Option A — USB:**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Option B — Direct transfer:**
Copy `app-debug.apk` to the phone via USB/WiFi, open it in Files app.
Allow "Install unknown apps" for your file manager.

**Option C — Run directly from Android Studio:**
Connect phone with USB, enable Developer Options + USB Debugging,
then press the green ▶ Run button.

---

## Step 6 — Connect

1. Make sure **phone and server PC are on the same WiFi network.**
2. Open LAN Chat on the phone.
3. Enter the **server's LAN IP** (e.g. `192.168.1.100`).
4. Tap **Connect**, then **Register** or **Login**.

---

## Network ports used

| Port | Protocol | Purpose |
|---|---|---|
| 9090 | TCP | Chat messages, auth, signaling |
| 9091 | UDP | Voice audio (peer-to-peer) |

Open both ports in your PC firewall if connections fail.

**Windows:**
```powershell
netsh advfirewall firewall add rule name="LanChat TCP" protocol=TCP dir=in localport=9090 action=allow
netsh advfirewall firewall add rule name="LanChat UDP" protocol=UDP dir=in localport=9091 action=allow
```

**Linux (ufw):**
```bash
sudo ufw allow 9090/tcp
sudo ufw allow 9091/udp
```

---

## Changing the pre-shared AES key

Edit this constant in **both** places before building:

```
common/src/main/java/com/lanchat/common/crypto/AesUtil.java
  → private static final String PASSPHRASE = "YourNewSecret";
```

Then rebuild `common`, copy the new JAR into `app/libs/`, and rebuild both server and APK.

---

## Project architecture recap

```
Android phone                         PC / Laptop
┌──────────────────────┐              ┌─────────────────────┐
│  LAN Chat APK        │  TCP 9090    │  lanchat-server.jar │
│  ├── LoginActivity   │◄────────────►│  ChatServer         │
│  ├── ChatActivity    │              │  ClientHandler      │
│  ├── VoiceCallActivity│             │  UserService        │
│  │                   │              │  MessageRouter      │
│  ├── ChatRepository  │              │  GroupService       │
│  │   ├── ConnectionManager          │  FileTransferHandler│
│  │   ├── MessageSender              │  VoiceCallSignaling │
│  │   └── MessageListener            └─────────────────────┘
│  └── ChatService (fg)│
│       (keeps socket  │  UDP 9091
│        alive)        │◄────────────► Other Android phone
└──────────────────────┘  (voice, p2p — server not involved)
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| `Connection refused` | Check server is running; check IP; check firewall |
| `Cannot open UDP port 9091` | Another app is using it, or the previous call didn't clean up — restart app |
| `Jackson deserialization failed` | common.jar version mismatch — rebuild common and copy new JAR |
| App crashes on launch | Check `adb logcat` for stack trace; most likely a missing resource |
| Voice call connects but no audio | Microphone permission denied — grant in phone Settings → Apps → LAN Chat |
| `usesCleartextTraffic` error | Already set in manifest; if persisting, add a `network_security_config.xml` |

---

## Adding more Android phones (multi-client)

No changes needed. The server supports up to 50 simultaneous clients (configured in
`ChatServer.MAX_CLIENTS`). Install the APK on each phone, connect to the same server IP.
