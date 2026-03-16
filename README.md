# Alexa Volume Controller for Hubitat

Control Amazon Echo volume from any physical controller — rotary knob, slider, or up/down buttons — entirely within Hubitat. No Raspberry Pi, no persistent server, no Node.js required after initial setup.

> **Note:** This uses Amazon's internal (unofficial) API. Amazon may change it without notice. If it stops working, an app update will be needed — the refresh token itself typically stays valid long-term.

---

## Files

| File | Purpose |
|---|---|
| `AlexaVolumeManager-App.groovy` | Main app — credentials, Echo discovery, controller config |
| `AlexaVolumeDevice-Driver.groovy` | Child driver — one per Echo, exposes volume/mute to Hubitat |

---

## How it works

```
Physical control (knob / slider / buttons)
        │  pushes button or changes level
        ▼
Alexa Volume Manager app
  │  holds auth · manages Echo child devices · maps controller events
  │
  ├─ Alexa — Kitchen Echo   ┐
  ├─ Alexa — Bedroom Echo   ├─ child devices visible in dashboards & Rule Machine
  └─ Alexa — Living Room    ┘
        │  HTTPS
        ▼
Amazon Alexa API → Echo volume changes
```

One app, one wizard. The Manager handles credentials, discovers your Echo devices, creates a Hubitat child device for each one, and lets you add as many physical controllers as you need — each mapping to one or more Echos.

---

## Supported controller types

| Type | Example | How it works |
|---|---|---|
| Rotary / button | MOES TS004F knob | Rotation fires pushed events; button numbers are configurable |
| Slider | Any Hubitat dimmer | Level (0–100) maps directly to Echo volume |
| Up / down buttons | Any two button devices | Separate up and down presses; can be same device, different buttons |

Each controller can target **multiple Echo devices simultaneously**.

---

## Setup

### Step 1 — Get your Amazon refresh token (one-time, on your PC)

1. Download `alexa-cookie-cli` for your OS from [github.com/adn77/alexa-cookie-cli/releases](https://github.com/adn77/alexa-cookie-cli/releases)
2. Run it from a terminal — do not double-click
   ```
   ./alexa-cookie-cli-macos        # Mac / Linux
   alexa-cookie-cli-win-x64.exe    # Windows
   ```
3. Open **http://127.0.0.1:8080** in your browser and log in to Amazon
4. Copy the `Atnr|...` token that appears in the terminal

> If port 8080 is in use, add `-P 8081`. If you get a 404 after login, switch your Amazon account from SMS 2FA to an authenticator app.

### Step 2 — Install the driver

Hubitat → **Drivers Code** → New Driver → paste `AlexaVolumeDevice-Driver.groovy` → Save

### Step 3 — Install and run the app

1. Hubitat → **Apps Code** → New App → paste `AlexaVolumeManager-App.groovy` → Save
2. Hubitat → **Apps** → Add User App → **Alexa Volume Manager**

### Step 4 — Connect to Amazon

1. Tap **Amazon credentials**
2. Paste your `Atnr|...` token and choose your domain
3. Tap **Connect to Amazon** — status updates to "Connected"

### Step 5 — Select Echo devices

1. Tap **Echo devices**
2. A dropdown shows all your Echos by name — select the ones you want to control
3. Tap **Create / sync devices**

Your Echos now appear in Hubitat as `Alexa — [Device Name]` devices.

### Step 6 — Add a controller

1. Back on the main page, tap **+ Add controller**
2. Name it, choose the type, configure the device and button mapping
3. Select which Echo devices it should control
4. Tap Done

Repeat for as many controllers as you need.

---

## Controller configuration

### Rotary / button (e.g. MOES ZigBee Smart Knob)

Pair the knob using the built-in **Tuya Scene Switch TS004F** driver. Default button mapping:

| Action | Button | Result |
|---|---|---|
| Rotate clockwise | Button 2 pushed | Volume up |
| Rotate counter-clockwise | Button 3 pushed | Volume down |
| Center press | Button 1 pushed | Mute toggle (if configured) |
| Center double-tap | Button 1 double-tapped | Configurable |
| Center hold | Button 1 held | Configurable |

All button numbers are configurable — works with any pushable button device.

### Slider

Any device with a `level` attribute (0–100). The level maps directly to Echo volume. An optional separate button can be added for mute toggle.

### Up / down buttons

Any two pushable button devices. Up and down can be on the same device with different button numbers. Optional third button for mute.

---

## Configurable actions (rotary double-tap / hold)

- Do nothing
- Mute toggle
- Set volume to 0, 25, 50, 75, or 100%

---

## Child device reference

Each Echo device exposes these in Hubitat:

| Command | Description |
|---|---|
| `setVolume(level)` | Absolute volume 0–100 |
| `volumeUp(step)` | Increase by step (default 5) |
| `volumeDown(step)` | Decrease by step (default 5) |
| `mute()` | Mute |
| `unmute()` | Unmute |
| `muteToggle()` | Toggle mute state |

| Attribute | Description |
|---|---|
| `volume` | Current tracked volume (0–100) |
| `mute` | `muted` or `unmuted` |
| `echoDevice` | Echo device name |

---

## Troubleshooting

**"Error: access token failed"** — Check the token starts with `Atnr|` and has no extra spaces. Try running alexa-cookie-cli again for a fresh token.

**No Echo devices in the dropdown** — Make sure status shows "Connected" before tapping Echo devices. Confirm the token belongs to the same Amazon account your Echos are on.

**Volume commands fire but nothing happens** — Enable debug logging and check Hubitat logs. HTTP 401/403 means the app will re-authenticate automatically. If it persists, Amazon may have changed their API — check this repo for updates.

**Knob rotation events not firing** — Confirm the knob uses the **Tuya Scene Switch TS004F** driver. Check the device Events tab — you should see Button 2 and 3 pushed events when rotating. A ~1 second delay between events is normal.

**404 from alexa-cookie-cli after login** — Known issue with SMS 2FA. Switch to an authenticator app for 2FA on your Amazon account.

---

## Credits

- Authentication approach: [Alexander Noack](https://blog.loetzimmer.de/2021/09/alexa-remote-control-shell-script.html) and [alexa-cookie2](https://github.com/Apollon77/alexa-cookie) by Ingo Fischer
- Knob pairing: [Hubitat community thread](https://community.hubitat.com/t/moes-tuya-smart-knob-detection-and-driver/109632)

---

## Disclaimer

This project uses Amazon's internal, undocumented API. Not affiliated with or endorsed by Amazon. Use at your own risk.
