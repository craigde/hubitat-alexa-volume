# Alexa Knob Volume Controller for Hubitat

Control the volume of any Amazon Echo device using a **MOES ZigBee Rotary Knob** — entirely within Hubitat, no Raspberry Pi, no persistent server, no Node.js required.

A one-time token capture on your PC is all the setup needed. After that, the Hubitat driver manages authentication automatically in the background.

---

## How It Works

Amazon's Alexa API uses a long-lived **refresh token** for authentication. You capture this token once using a small free CLI tool on your PC, paste it into the Hubitat driver, and from that point on the driver:

- Exchanges the refresh token for an access token
- Exchanges the access token for session cookies
- Automatically re-authenticates every 12 hours
- Sends volume commands directly to your Echo device via Amazon's internal API

No cloud service, no subscription, no third-party server — just your Hubitat hub talking directly to Amazon.

---

## What You Need

- A **Hubitat Elevation** hub (any model)
- A **MOES ZigBee Smart Rotary Knob** ([ZT-SY-RD-MS](https://moeshouse.com/products/zigbee-round-dimmer-scene-switch)) paired to Hubitat using the built-in **Tuya Scene Switch TS004F** driver
- A PC (Windows, Mac, or Linux) to run the one-time token capture tool
- An Amazon account with at least one Echo device

> **Note:** This uses Amazon's internal (unofficial) API. Amazon may change it without notice. If it stops working, a driver update will be needed — the refresh token itself typically stays valid long-term.

---

## Files

| File | Type | Purpose |
|---|---|---|
| `AlexaVolumeControl-Driver.groovy` | Hubitat Driver | Handles Amazon auth and sends volume commands to Echo |
| `AlexaKnobVolumeController-App.groovy` | Hubitat App | Connects the knob's rotation events to the driver |

---

## Setup Guide

### Step 1 — Pair Your Knob

1. In Hubitat, go to **Settings → Zigbee Details → Add Device**
2. Put your MOES knob into pairing mode (hold the center button until the LED flashes)
3. Once paired, set the driver to **Tuya Scene Switch TS004F**
4. Click **Save**

### Step 2 — Get Your Amazon Refresh Token (one-time, on your PC)

1. Download the latest `alexa-cookie-cli` binary for your OS from the [releases page](https://github.com/adn77/alexa-cookie-cli/releases)
   - Windows: `alexa-cookie-cli-win-x64.exe`
   - Mac: `alexa-cookie-cli-macos`
   - Linux: `alexa-cookie-cli-linux`
2. Open a terminal / command prompt and run the binary:
   ```
   ./alexa-cookie-cli-macos        # Mac/Linux
   alexa-cookie-cli-win-x64.exe    # Windows
   ```
   > ⚠️ Run from the terminal — do not double-click. The token is printed to the terminal window.
3. Open your browser and go to **http://127.0.0.1:8080/**
4. Log into your Amazon account (including any 2FA)
5. On success, the tool exits and prints a token starting with `Atnr|...` in the terminal
6. Copy the full token — you'll need it in the next step

> **Tips:**
> - If port 8080 is in use, add `-P 8081` to use a different port
> - If you use SMS-based 2FA and hit a 404 error, try switching your Amazon account to use an authenticator app for 2FA instead

### Step 3 — Install the Driver

1. In Hubitat, go to **Drivers Code → New Driver**
2. Paste the contents of `AlexaVolumeControl-Driver.groovy` and click **Save**
3. Go to **Devices → Add Device → Virtual**
4. Name it something like `Alexa Volume` and set the driver to **Alexa Volume Control**
5. Click **Save Device**
6. In the device preferences:
   - **Amazon Refresh Token** — paste your `Atnr|...` token
   - **Amazon Domain** — choose your region (`amazon.com`, `amazon.co.uk`, etc.)
   - Leave **Echo Device Name** blank for now
7. Click **Save Preferences** — the driver will authenticate automatically

### Step 4 — Find Your Echo Device Name

1. On the device page, click the **listDevices** command
2. Open **Logs** in Hubitat — your Echo devices will be listed with their exact names:
   ```
   ============================================
     ALEXA ECHO DEVICES — copy name exactly
   ============================================
     Name : 'Living Room Echo'
     Type : ECHO / A3C9PE6TNYLTCH
     Serial: G090LF123456789
     ----
   ```
3. Copy the exact name (including capitalisation and spaces)
4. Go back to the device preferences, paste the name into **Echo Device Name**, and click **Save Preferences**
5. Check the **authStatus** attribute shows `Authenticated` and **echoDevice** shows your device name

### Step 5 — Install the App

1. In Hubitat, go to **Apps Code → New App**
2. Paste the contents of `AlexaKnobVolumeController-App.groovy` and click **Save**
3. Go to **Apps → Add User App → Alexa Knob Volume Controller**
4. Configure:
   - **Knob device** — select your MOES knob
   - **Alexa Volume Control device** — select the driver device from Step 3
   - **Volume change per click** — how much each rotation step changes the volume (default: 5)
   - **Center press = mute toggle** — enable if you want the center button to mute/unmute
5. Click **Done**

---

## Knob Button Map

| Action | Hubitat Event | Result |
|---|---|---|
| Rotate clockwise | Button 2 pushed | Volume up |
| Rotate counter-clockwise | Button 3 pushed | Volume down |
| Center press | Button 1 pushed | Mute toggle *(if enabled)* |
| Center double-tap | Button 1 double-tapped | Configurable *(see Advanced settings)* |
| Center hold | Button 1 held | Configurable *(see Advanced settings)* |

### Advanced Actions (configurable in app settings)

Double-tap and hold on the center button can each be set to:
- Do nothing
- Mute toggle
- Set volume to 0, 25, 50, 75, or 100%

---

## Driver Reference

The **Alexa Volume Control** driver exposes the following commands, which can also be called from Rule Machine or other Hubitat apps:

| Command | Description |
|---|---|
| `setVolume(level)` | Set volume to an absolute level (0–100) |
| `volumeUp(step)` | Increase volume by step amount |
| `volumeDown(step)` | Decrease volume by step amount |
| `mute()` | Mute the Echo device |
| `unmute()` | Unmute the Echo device |
| `muteToggle()` | Toggle mute state |
| `listDevices()` | Log all Echo device names to Hubitat logs |
| `refreshCookies()` | Force immediate re-authentication |
| `refresh()` | Alias for refreshCookies() |

### Driver Attributes

| Attribute | Values | Description |
|---|---|---|
| `volume` | 0–100 | Current tracked volume level |
| `mute` | `muted` / `unmuted` | Current mute state |
| `authStatus` | string | Human-readable authentication status |
| `echoDevice` | string | Confirmed Echo device name |

---

## Troubleshooting

**`authStatus` shows "Error: Access token request failed"**
- Check your refresh token is pasted correctly and starts with `Atnr|`
- Make sure there are no extra spaces before or after the token
- Try re-running `alexa-cookie-cli` to get a fresh token

**`authStatus` shows "Warning: Echo device not found"**
- Run `listDevices()` and check the logs for the exact device name
- The name is case-sensitive and must match exactly — including spaces

**Volume command fires but nothing happens on the Echo**
- Enable debug logging in driver preferences and check the Hubitat logs
- If you see HTTP 401 or 403, click `refreshCookies()` to force re-authentication
- If errors persist, Amazon may have changed their API — check this repo for updates

**Knob rotation events aren't firing**
- Confirm the knob is using the **Tuya Scene Switch TS004F** driver
- Check the device events tab in Hubitat — you should see Button 2 and 3 pushed events when rotating
- Try rotating slowly — there is approximately a 1-second delay between events from the knob

**alexa-cookie-cli gives a 404 after login**
- This is a known issue with SMS-based two-factor authentication
- Switch your Amazon account to use an authenticator app for 2FA and try again

---

## Credits

- Authentication approach based on the work of [Alexander Noack](https://blog.loetzimmer.de/2021/09/alexa-remote-control-shell-script.html) and the [alexa-cookie2](https://github.com/Apollon77/alexa-cookie) project by Ingo Fischer
- Knob pairing approach based on community findings in the [Hubitat community forums](https://community.hubitat.com/t/moes-tuya-smart-knob-detection-and-driver/109632)

---

## Disclaimer

This project uses Amazon's internal, undocumented API. It is not affiliated with, endorsed by, or supported by Amazon. Use at your own risk. Amazon may change or revoke API access at any time.
