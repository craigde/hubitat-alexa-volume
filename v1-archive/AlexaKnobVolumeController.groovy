/**
 * Alexa Knob Volume Controller — Hubitat App
 * -------------------------------------------
 * Connects a MOES ZigBee Rotary Knob (TS004F) to the
 * Alexa Volume Control driver to give you physical knob
 * control over any Echo device's volume.
 *
 * Requires:
 *   - MOES ZigBee Smart Knob paired using the "Tuya Scene Switch TS004F" driver
 *   - "Alexa Volume Control" driver installed and configured with a refresh token
 *
 * Knob button mapping (via TS004F driver):
 *   Button 1 = center press       → mute toggle (optional)
 *   Button 2 = rotate clockwise   → volume up
 *   Button 3 = rotate counter-CW  → volume down
 */

definition(
    name        : "Alexa Knob Volume Controller",
    namespace   : "community",
    author      : "community",
    description : "Use a MOES ZigBee rotary knob to control Alexa Echo volume",
    category    : "Convenience",
    iconUrl     : "",
    iconX2Url   : ""
)

// -------------------------------------------------------
// PREFERENCES (User-facing configuration UI)
// -------------------------------------------------------

preferences {
    page(name: "mainPage")
    page(name: "advancedPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Alexa Knob Volume Controller", install: true, uninstall: true) {

        section("<b>1. Select your knob</b>") {
            input "knobDevice",
                  "capability.pushableButton",
                  title    : "MOES Rotary Knob device",
                  description: "Select the knob paired with the TS004F driver",
                  required : true,
                  multiple : false
        }

        section("<b>2. Select the Alexa Volume driver</b>") {
            input "alexaDriver",
                  "capability.audioVolume",
                  title    : "Alexa Volume Control device",
                  description: "Select the driver you configured with your refresh token",
                  required : true,
                  multiple : false
        }

        section("<b>3. Volume settings</b>") {
            input "stepSize",
                  "number",
                  title      : "Volume change per knob click",
                  description: "Each rotation step changes volume by this amount",
                  defaultValue: 5,
                  range      : "1..20",
                  required   : true

            input "centerPressMute",
                  "bool",
                  title      : "Center press = mute/unmute toggle",
                  defaultValue: true
        }

        section("<b>Advanced options</b>") {
            href "advancedPage", title: "Advanced settings", description: "Tap to configure"
        }

        section("") {
            paragraph "<small><b>Button map (TS004F driver):</b><br>" +
                      "• Rotate clockwise → Volume Up<br>" +
                      "• Rotate counter-clockwise → Volume Down<br>" +
                      "• Center press → Mute toggle (if enabled above)<br>" +
                      "• Center double-tap → (configurable on advanced page)<br>" +
                      "• Center hold → (configurable on advanced page)</small>"
        }
    }
}

def advancedPage() {
    dynamicPage(name: "advancedPage", title: "Advanced Settings") {

        section("<b>Center button actions</b>") {
            input "doubleTapAction",
                  "enum",
                  title      : "Center double-tap action",
                  options    : [
                      "none"     : "Do nothing",
                      "mute"     : "Mute toggle",
                      "vol25"    : "Set volume to 25%",
                      "vol50"    : "Set volume to 50%",
                      "vol75"    : "Set volume to 75%",
                      "volMax"   : "Set volume to max (100%)"
                  ],
                  defaultValue: "none"

            input "holdAction",
                  "enum",
                  title      : "Center hold action",
                  options    : [
                      "none"     : "Do nothing",
                      "mute"     : "Mute toggle",
                      "vol0"     : "Set volume to 0 (silent)",
                      "vol50"    : "Set volume to 50%",
                      "volMax"   : "Set volume to max (100%)"
                  ],
                  defaultValue: "none"
        }

        section("<b>Logging</b>") {
            input "logEnable",
                  "bool",
                  title      : "Enable debug logging",
                  defaultValue: false
        }
    }
}

// -------------------------------------------------------
// LIFECYCLE
// -------------------------------------------------------

def installed() {
    log.info "Alexa Knob Controller installed"
    initialize()
}

def updated() {
    log.info "Alexa Knob Controller updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    log.info "Alexa Knob Controller uninstalled"
    unsubscribe()
}

def initialize() {
    logDebug "Initializing — subscribing to knob button events"
    subscribe(knobDevice, "pushed",       buttonPushedHandler)
    subscribe(knobDevice, "doubleTapped", buttonDoubleTappedHandler)
    subscribe(knobDevice, "held",         buttonHeldHandler)
    log.info "Alexa Knob Controller ready — controlling '${alexaDriver?.displayName}'"
}

// -------------------------------------------------------
// EVENT HANDLERS
// -------------------------------------------------------

def buttonPushedHandler(evt) {
    def button = evt.value?.toInteger()
    logDebug "Button pushed: ${button}"

    switch (button) {
        case 1:
            // Center click
            if (centerPressMute) {
                logDebug "Center press → mute toggle"
                alexaDriver.muteToggle()
            }
            break

        case 2:
            // Rotate clockwise → volume up
            logDebug "Rotate CW → volume up (step: ${stepSize})"
            alexaDriver.volumeUp(stepSize ?: 5)
            break

        case 3:
            // Rotate counter-clockwise → volume down
            logDebug "Rotate CCW → volume down (step: ${stepSize})"
            alexaDriver.volumeDown(stepSize ?: 5)
            break

        default:
            logDebug "Unhandled button: ${button}"
    }
}

def buttonDoubleTappedHandler(evt) {
    def button = evt.value?.toInteger()
    logDebug "Button double-tapped: ${button}"

    if (button == 1) {
        executeAction(doubleTapAction ?: "none")
    }
}

def buttonHeldHandler(evt) {
    def button = evt.value?.toInteger()
    logDebug "Button held: ${button}"

    if (button == 1) {
        executeAction(holdAction ?: "none")
    }
}

// -------------------------------------------------------
// ACTION EXECUTION
// -------------------------------------------------------

private executeAction(String action) {
    logDebug "Executing action: ${action}"
    switch (action) {
        case "mute":
            alexaDriver.muteToggle()
            break
        case "vol0":
            alexaDriver.setVolume(0)
            break
        case "vol25":
            alexaDriver.setVolume(25)
            break
        case "vol50":
            alexaDriver.setVolume(50)
            break
        case "vol75":
            alexaDriver.setVolume(75)
            break
        case "volMax":
            alexaDriver.setVolume(100)
            break
        case "none":
        default:
            break
    }
}

// -------------------------------------------------------
// HELPERS
// -------------------------------------------------------

private logDebug(String msg) {
    if (logEnable) log.debug "AlexaKnob: ${msg}"
}
