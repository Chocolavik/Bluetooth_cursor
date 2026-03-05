# Bluetooth Cursor Bridge

Control your Lenovo Android tablet with your Kubuntu laptop trackpad via Bluetooth. When the cursor leaves the right edge of the laptop screen, it seamlessly transfers to the tablet — with a visible cursor and click support. Press **Meta (Super/Windows)** to return control to the laptop.

---

## Project Structure

```
Bluetooth_Cursor/
├── laptop/
│   ├── bridge.py          ← Python script — run on the Kubuntu laptop
│   └── requirements.txt
└── tablet/
    └── BluetoothCursorBridge/
        └── app/           ← Android Studio project — build & install on tablet
```

---

## Prerequisites

| Side   | Requirements |
|--------|-------------|
| Laptop | Python 3.8+, `pynput`, `PyBluez`, Bluetooth adapter |
| Tablet | Android 10 (API 29+), Bluetooth enabled |

---

## Step 1 — Pair the Devices via Bluetooth

> Do this **once**. You only need to pair manually for now; auto-pairing is not implemented.

**On the Tablet:**
1. Go to **Settings → Connections → Bluetooth**
2. Enable Bluetooth
3. Tap **Scan** to make the tablet discoverable

**On the Laptop:**
```bash
# Open Bluetooth manager (GUI) — or use bluetoothctl:
bluetoothctl
> power on
> scan on
# Wait for your tablet to appear, note the MAC address e.g. AA:BB:CC:DD:EE:FF
> pair AA:BB:CC:DD:EE:FF
> trust AA:BB:CC:DD:EE:FF
> connect AA:BB:CC:DD:EE:FF
> quit
```

**Find your tablet's Bluetooth MAC address:**  
On the tablet: *Settings → About tablet → Status → Bluetooth address*

---

## Step 2 — Configure the Laptop Script

Edit `laptop/bridge.py` and set your tablet's MAC address:

```python
TABLET_MAC = "AA:BB:CC:DD:EE:FF"   # ← replace with your tablet's address
```

Other constants (pre-configured for your hardware):
```python
SCREEN_WIDTH  = 1366
SCREEN_HEIGHT = 768
RFCOMM_PORT   = 1
```

---

## Step 3 — Install Laptop Dependencies

```bash
cd /home/svik/Desktop/Code/Bluetooth_Cursor/laptop

# Install system Bluetooth dev library (needed by PyBluez)
sudo apt install libbluetooth-dev

pip install -r requirements.txt
```

---

## Step 4 — Build the APK via GitHub Actions (no Android Studio needed)

> GitHub Actions builds the APK in the cloud — you just download it.

### 4a. Push the code to GitHub
```bash
cd /home/svik/Desktop/Code/Bluetooth_Cursor
git init
git add .
git commit -m "Initial commit — Bluetooth Cursor Bridge"
# Create a new repo on github.com, then:
git remote add origin https://github.com/Chocolavik/bluetooth-cursor-bridge.git
git push -u origin main
```

### 4b. Watch the build
- Go to your repo on GitHub → click **Actions** tab
- The workflow **"Build Android APK"** runs automatically on every push
- Wait ~3–5 minutes for it to finish ✅

### 4c. Download the APK
- Click the completed workflow run
- Scroll to **Artifacts** at the bottom
- Download **BluetoothCursorBridge-debug** → it contains `app-debug.apk`

> You can also trigger a build manually anytime:  
> **Actions → Build Android APK → Run workflow**

### 4d. Install the APK on your tablet
```bash
# Transfer via ADB (USB cable):
adb install app-debug.apk

# OR: copy the APK to the tablet, then tap it to install
# (You may need to enable "Install unknown apps" in Settings → Security)
```

---

## Step 5 — Setup the Tablet App

After installing, open **Bluetooth Cursor Bridge** on the tablet and complete these two setup steps shown on screen:

### 5a. Grant Overlay Permission
- Tap **"1. Grant Overlay Permission"**
- In the system dialog: find "Bluetooth Cursor Bridge" → enable **"Allow display over other apps"**
- Return to the app

### 5b. Enable the Accessibility Service
- Tap **"2. Open Accessibility Settings"**
- Find **"Bluetooth Cursor Bridge"** under Installed Services
- Tap it → toggle **ON** → confirm
- Return to the app

The status card should show both ✅ checkmarks. Then tap **▶ START SERVER**.

---

## Step 6 — Run the Laptop Script

```bash
cd /home/svik/Desktop/Code/Bluetooth_Cursor/laptop
python3 bridge.py
```

Expected output:
```
==================================================
  Bluetooth Cursor Bridge — Laptop Side
==================================================
  Screen  : 1366 × 768
  Trigger : X ≥ 1365
  Return  : Meta key
  Tablet  : AA:BB:CC:DD:EE:FF
==================================================
[BT]  Connecting to AA:BB:CC:DD:EE:FF on port 1…
[BT]  Connected ✓

[BRIDGE] Listening… move mouse to the RIGHT EDGE to transfer control.
[BRIDGE] ── LAPTOP mode ──
```

---

## Usage

| Action | Result |
|--------|--------|
| Move cursor to **right edge** of laptop screen | Control transfers to tablet; cursor appears on tablet |
| Move trackpad | Cursor moves on tablet |
| Tap trackpad / left-click | Tap is injected at cursor position on tablet |
| Press **Meta (Super/Windows)** key | Control returns to laptop |

---

## Packet Protocol Reference

```
Byte 0    : 0xAB (header)
Byte 1–2  : dx (signed 16-bit, big-endian)
Byte 3–4  : dy (signed 16-bit, big-endian)
Byte 5    : click_state (0x00=none, 0x01=down, 0x02=up)
Byte 6    : XOR checksum of bytes 0–5
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `Bluetooth connection failed` | Make sure devices are paired and MAC is correct |
| Cursor visible but taps not registering | Confirm Accessibility Service is enabled |
| Cursor overlay not showing | Grant SYSTEM_ALERT_WINDOW (overlay) permission |
| `PyBluez` install fails | `sudo apt install libbluetooth-dev` then retry pip |
| Script hangs at "Connecting…" | Tablet app must be running with server started first |
| Meta key not detected | Try `Escape` as fallback; some keyboards map Meta differently |
