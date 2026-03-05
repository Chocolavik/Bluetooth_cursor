#!/usr/bin/env python3
"""
Bluetooth Cursor Bridge — Laptop Side
======================================
Monitors the mouse and, when it crosses the RIGHT edge of the screen,
locks the cursor there and streams relative deltas + click events to
the Android tablet over Bluetooth RFCOMM.

Pressing the Meta (Super/Windows) key returns control to the laptop.

Usage:
    1. Edit TABLET_MAC below with your tablet's Bluetooth address.
    2. Pair the devices via system Bluetooth settings.
    3. On the tablet: start the app and enable the Accessibility Service.
    4. Run:  python3 bridge.py

Requirements:
    pip install pynput PyBluez
"""

import struct
import threading
import sys
import time
import bluetooth  # PyBluez
from pynput import mouse, keyboard
from pynput.mouse import Controller as MouseController

# ─────────────────────────────────────────────────
#  CONFIGURATION  — edit these before running
# ─────────────────────────────────────────────────
TABLET_MAC   = "90:09:17:86:0F:ED"   # Lenovo tablet
RFCOMM_PORT  = 1
SCREEN_WIDTH  = 1366
SCREEN_HEIGHT = 768
EDGE_X        = SCREEN_WIDTH - 1     # 1365 — pixel that triggers tablet mode
# ─────────────────────────────────────────────────

HEADER       = 0xAB
CLICK_NONE   = 0x00
CLICK_DOWN   = 0x01
CLICK_UP     = 0x02

# ── State ──────────────────────────────────────────
class BridgeState:
    LAPTOP = "LAPTOP"
    TABLET = "TABLET"

state      = BridgeState.LAPTOP
state_lock = threading.Lock()

last_x: int = 0
last_y: int = 0
sock = None          # Bluetooth RFCOMM socket
mouse_ctrl = MouseController()


# ── Packet helpers ─────────────────────────────────
def encode_packet(dx: int, dy: int, click: int) -> bytes:
    """Encode a 7-byte packet: [0xAB, dx_hi, dx_lo, dy_hi, dy_lo, click, xor_checksum]"""
    dx_clamped = max(-32767, min(32767, dx))
    dy_clamped = max(-32767, min(32767, dy))
    dx_b = struct.pack(">h", dx_clamped)
    dy_b = struct.pack(">h", dy_clamped)
    payload = bytes([HEADER, dx_b[0], dx_b[1], dy_b[0], dy_b[1], click])
    checksum = 0
    for b in payload:
        checksum ^= b
    return payload + bytes([checksum])


def send_packet(dx: int, dy: int, click: int):
    """Send a packet over the open RFCOMM socket. Exits on error."""
    global sock
    if sock is None:
        return
    try:
        pkt = encode_packet(dx, dy, click)
        sock.send(pkt)
    except (bluetooth.BluetoothError, OSError) as e:
        print(f"\n[ERROR] Bluetooth send failed: {e}", file=sys.stderr)
        print("[INFO]  Returning to LAPTOP mode.", file=sys.stderr)
        set_mode(BridgeState.LAPTOP)
        try:
            sock.close()
        except Exception:
            pass
        sock = None


# ── Mode switching ──────────────────────────────────
def set_mode(new_mode: str):
    global state, last_x, last_y
    with state_lock:
        if state == new_mode:
            return
        state = new_mode
        if new_mode == BridgeState.TABLET:
            print("[BRIDGE] ── TABLET mode  (Meta key to return) ──")
            # anchor last known position at the edge
            pos = mouse_ctrl.position
            last_x = EDGE_X
            last_y = pos[1]
        else:
            print("[BRIDGE] ── LAPTOP mode ──")


# ── Mouse listener ──────────────────────────────────
def on_move(x, y):
    global last_x, last_y

    with state_lock:
        current_state = state

    if current_state == BridgeState.LAPTOP:
        if x >= EDGE_X:
            set_mode(BridgeState.TABLET)
            # Lock cursor at edge immediately
            mouse_ctrl.position = (EDGE_X, y)
            last_x = EDGE_X
            last_y = y
            # Send a zero-delta packet to signal connection
            send_packet(0, 0, CLICK_NONE)
        return  # normal laptop movement — do nothing

    # ── TABLET mode ──
    # Always pin cursor at right edge
    mouse_ctrl.position = (EDGE_X, last_y)

    # Calculate deltas from last known virtual position
    dx = x - last_x
    dy = y - last_y

    # Update the "virtual" y (x stays pinned)
    last_x = x   # will be used just for delta, but we keep track
    last_y = y

    if dx != 0 or dy != 0:
        send_packet(dx, dy, CLICK_NONE)


def on_click(x, y, button, pressed):
    with state_lock:
        current_state = state

    if current_state == BridgeState.TABLET and button == mouse.Button.left:
        click_code = CLICK_DOWN if pressed else CLICK_UP
        send_packet(0, 0, click_code)


# ── Keyboard listener ───────────────────────────────
def on_key_press(key):
    is_meta = key in (
        keyboard.Key.cmd,
        keyboard.Key.cmd_l,
        keyboard.Key.cmd_r,
        keyboard.Key.meta,
        keyboard.Key.meta_l,
        keyboard.Key.meta_r,
    )
    if is_meta:
        with state_lock:
            current_state = state
        if current_state == BridgeState.TABLET:
            set_mode(BridgeState.LAPTOP)


# ── Bluetooth connect ───────────────────────────────
def connect_bluetooth() -> bool:
    global sock
    if TABLET_MAC == "XX:XX:XX:XX:XX:XX":
        print("[ERROR] Please set TABLET_MAC in bridge.py before running!", file=sys.stderr)
        return False

    print(f"[BT]  Connecting to {TABLET_MAC} on port {RFCOMM_PORT}…")
    try:
        s = bluetooth.BluetoothSocket(bluetooth.RFCOMM)
        s.connect((TABLET_MAC, RFCOMM_PORT))
        sock = s
        print(f"[BT]  Connected ✓")
        return True
    except bluetooth.BluetoothError as e:
        print(f"[ERROR] Bluetooth connection failed: {e}", file=sys.stderr)
        return False


# ── Entry point ─────────────────────────────────────
def main():
    print("=" * 50)
    print("  Bluetooth Cursor Bridge — Laptop Side")
    print("=" * 50)
    print(f"  Screen  : {SCREEN_WIDTH} × {SCREEN_HEIGHT}")
    print(f"  Trigger : X ≥ {EDGE_X}")
    print(f"  Return  : Meta key")
    print(f"  Tablet  : {TABLET_MAC}")
    print("=" * 50)

    if not connect_bluetooth():
        sys.exit(1)

    print("\n[BRIDGE] Listening… move mouse to the RIGHT EDGE to transfer control.\n")
    print("[BRIDGE] ── LAPTOP mode ──")

    mouse_listener    = mouse.Listener(on_move=on_move, on_click=on_click)
    keyboard_listener = keyboard.Listener(on_press=on_key_press)

    mouse_listener.start()
    keyboard_listener.start()

    try:
        mouse_listener.join()
        keyboard_listener.join()
    except KeyboardInterrupt:
        print("\n[BRIDGE] Interrupted. Shutting down.")
    finally:
        mouse_listener.stop()
        keyboard_listener.stop()
        if sock:
            sock.close()
        print("[BRIDGE] Done.")


if __name__ == "__main__":
    main()
