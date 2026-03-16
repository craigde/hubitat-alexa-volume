/**
 * Alexa Volume Device — Hubitat Child Driver
 * -------------------------------------------
 * Thin child driver representing one Amazon Echo device.
 * Created and managed by the Alexa Volume Manager app.
 * All commands delegate to the parent app.
 */

metadata {
    definition(
        name       : "Alexa Volume Device",
        namespace  : "community",
        author     : "community",
        description: "Child device for an Amazon Echo — managed by Alexa Volume Manager"
    ) {
        capability "AudioVolume"
        capability "Actuator"

        command "volumeUp",   [[name:"Step", type:"NUMBER", description:"Amount to increase (default 5)"]]
        command "volumeDown", [[name:"Step", type:"NUMBER", description:"Amount to decrease (default 5)"]]
        command "muteToggle"

        attribute "volume",     "number"
        attribute "mute",       "string"
        attribute "echoDevice", "string"
    }

    preferences {
        input "defaultStep", "number", title: "Default volume step", defaultValue: 5, range: "1..20"
        input "logEnable",   "bool",   title: "Debug logging",       defaultValue: false
    }
}

def installed() {
    sendEvent(name: "volume", value: 50)
    sendEvent(name: "mute",   value: "unmuted")
}

def setVolume(level)          { logDebug "setVolume(${level})";   parent.setVolume(device, level) }
def volumeUp(step = null)     { logDebug "volumeUp(${step})";     parent.volumeUp(device, step ?: defaultStep ?: 5) }
def volumeDown(step = null)   { logDebug "volumeDown(${step})";   parent.volumeDown(device, step ?: defaultStep ?: 5) }
def mute()                    { logDebug "mute()";                parent.mute(device) }
def unmute()                  { logDebug "unmute()";              parent.unmute(device) }
def muteToggle()              { logDebug "muteToggle()";          parent.muteToggle(device) }

private logDebug(String msg) { if (logEnable) log.debug "${device.label}: ${msg}" }
