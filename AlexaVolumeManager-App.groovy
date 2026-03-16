/**
 * Alexa Volume Manager — Hubitat App
 * ------------------------------------
 * Single app that handles everything:
 *   - Amazon authentication via refresh token
 *   - Echo device discovery and child device creation
 *   - Multiple physical controllers (rotary, slider, up/down buttons)
 *   - Each controller can target one or more Echo child devices
 *
 * Install order:
 *   1. Drivers Code → paste AlexaVolumeDevice-Driver.groovy → Save
 *   2. Apps Code    → paste this file → Save
 *   3. Apps → Add User App → Alexa Volume Manager
 */

definition(
    name          : "Alexa Volume Manager",
    namespace     : "community",
    author        : "community",
    description   : "Control Amazon Echo volume from any Hubitat button, knob, or slider",
    category      : "Convenience",
    singleInstance: true,
    iconUrl       : "",
    iconX2Url     : ""
)

// -------------------------------------------------------
// PAGES
// -------------------------------------------------------

preferences {
    page(name: "mainPage")
    page(name: "credentialsPage")
    page(name: "devicesPage")
    page(name: "controllerPage")
}

// ── Main ────────────────────────────────────────────────

def mainPage() {
    dynamicPage(name: "mainPage", title: "Alexa Volume Manager",
                install: true, uninstall: true) {

        def connected  = state.authStatus == "Connected"
        def childCount = getChildDevices()?.size() ?: 0
        def ctrlIds    = state.controllerIds ?: []

        section() {
            paragraph "<b>Amazon:</b> ${connected ? '● Connected' : '○ ' + (state.authStatus ?: 'Not connected')}<br>" +
                      "<b>Echo devices:</b> ${childCount} configured<br>" +
                      "<b>Controllers:</b> ${ctrlIds.size()}"
        }

        section("<b>Setup</b>") {
            href "credentialsPage",
                 title      : "Amazon credentials",
                 description: connected ? "Connected — tap to update token" : "Tap to connect"

            href "devicesPage",
                 title      : "Echo devices",
                 description: childCount ? "${childCount} device(s) configured" : "Tap to select Echo devices"
        }

        section("<b>Controllers</b>") {
            ctrlIds.each { idx ->
                def name    = settings["ctrl_${idx}_name"] ?: "Controller ${idx + 1}"
                def type    = settings["ctrl_${idx}_type"] ?: ""
                def targets = settings["ctrl_${idx}_targets"]
                href "controllerPage",
                     title      : name,
                     description: "${typeLabel(type)} · ${targets?.size() ?: 0} Echo(s)",
                     params     : [idx: idx]
            }
            input "addController", "button", title: "+ Add controller"
        }

        section() {
            input "logEnable", "bool", title: "Debug logging", defaultValue: false
        }
    }
}

// ── Credentials ─────────────────────────────────────────

def credentialsPage() {
    dynamicPage(name: "credentialsPage", title: "Amazon credentials",
                install: false, uninstall: false) {

        section("<b>How to get your refresh token</b>") {
            paragraph "1. Download <b>alexa-cookie-cli</b> from " +
                      "<a href='https://github.com/adn77/alexa-cookie-cli/releases'>github.com/adn77/alexa-cookie-cli/releases</a><br>" +
                      "2. Run from a terminal — do not double-click<br>" +
                      "3. Open <b>http://127.0.0.1:8080</b> and log in to Amazon<br>" +
                      "4. Copy the <code>Atnr|...</code> token from the terminal"
        }

        section("<b>Credentials</b>") {
            input "refreshToken",  "password",
                  title: "Amazon refresh token", description: "Paste the full Atnr|... string",
                  required: true

            input "amazonDomain",  "enum",
                  title: "Amazon domain",
                  options: ["amazon.com","amazon.co.uk","amazon.de","amazon.com.au","amazon.ca"],
                  defaultValue: "amazon.com", required: true

            input "connectBtn", "button", title: "Connect to Amazon"
        }

        section() {
            def st = state.authStatus ?: "Not connected"
            paragraph st == "Connected"
                ? "<span style='color:#2a9d8f;font-weight:500'>● ${st}</span>"
                : "<span style='color:#e76f51'>${st}</span>"
            if (state.echoDevices) {
                paragraph "Found <b>${state.echoDevices.size()}</b> Echo device(s) — go to Echo devices to configure them."
            }
        }
    }
}

// ── Echo devices ─────────────────────────────────────────

def devicesPage() {
    if (state.authStatus == "Connected" && !state.echoDevices) fetchEchoDevices()

    dynamicPage(name: "devicesPage", title: "Echo devices",
                install: false, uninstall: false) {

        if (!state.echoDevices) {
            section() { paragraph "⚠️ Connect to Amazon first, then return here." }
            return
        }

        section("<b>Select which Echos to control</b>") {
            paragraph "A Hubitat device is created for each Echo you select. " +
                      "These appear on dashboards and in Rule Machine."
            input "selectedEchoSerials", "enum",
                  title: "Echo devices", options: state.echoDevices,
                  multiple: true, required: true

            input "syncDevices", "button", title: "Create / sync devices"
        }

        def children = getChildDevices()
        if (children) {
            section("<b>Configured devices</b>") {
                children.each { paragraph "• ${it.label}" }
            }
        }
    }
}

// ── Controller page (parameterised by idx) ───────────────

def controllerPage(params) {
    if (params?.idx != null) state.editingIdx = params.idx
    def idx  = state.editingIdx ?: 0
    def type = settings["ctrl_${idx}_type"]

    dynamicPage(name: "controllerPage",
                title: settings["ctrl_${idx}_name"] ?: "Controller ${idx + 1}",
                install: false, uninstall: false) {

        section("<b>Name</b>") {
            input "ctrl_${idx}_name", "text",
                  title: "Controller name", defaultValue: "Controller ${idx + 1}", required: true
        }

        section("<b>Type</b>") {
            input "ctrl_${idx}_type", "enum",
                  title: "Controller type",
                  options: [rotary:"Rotary knob or button device",
                            slider:"Slider / dimmer (0–100 level)",
                            buttons:"Separate up and down buttons"],
                  required: true, submitOnChange: true
        }

        // ── Rotary ───────────────────────────────────

        if (type == "rotary") {
            section("<b>Device</b>") {
                paragraph "MOES TS004F defaults: button 2 = clockwise, 3 = counter-clockwise, 1 = center press."
                input "ctrl_${idx}_device", "capability.pushableButton",
                      title: "Rotary / button device", required: true
            }
            section("<b>Button mapping</b>") {
                input "ctrl_${idx}_upBtn",   "number", title: "Volume up button",   defaultValue: 2, range: "1..20", required: true
                input "ctrl_${idx}_downBtn", "number", title: "Volume down button", defaultValue: 3, range: "1..20", required: true
                input "ctrl_${idx}_muteBtn", "number", title: "Mute toggle button (optional)", range: "1..20", required: false
                input "ctrl_${idx}_step",    "number", title: "Volume step per click", defaultValue: 5, range: "1..20", required: true
            }
            section("<b>Center button extras (optional)</b>") {
                input "ctrl_${idx}_dblTap", "enum", title: "Double-tap action", options: volumeActionOptions(), defaultValue: "none"
                input "ctrl_${idx}_hold",   "enum", title: "Hold action",       options: volumeActionOptions(), defaultValue: "none"
            }
        }

        // ── Slider ───────────────────────────────────

        if (type == "slider") {
            section("<b>Device</b>") {
                paragraph "Slider level (0–100) maps directly to Echo volume."
                input "ctrl_${idx}_sliderDevice", "capability.switchLevel",
                      title: "Slider / dimmer device", required: true
            }
            section("<b>Mute button (optional)</b>") {
                input "ctrl_${idx}_muteDev", "capability.pushableButton",
                      title: "Mute button device", required: false
                if (settings["ctrl_${idx}_muteDev"]) {
                    input "ctrl_${idx}_muteBtnNum", "number", title: "Button number",
                          defaultValue: 1, range: "1..20"
                }
            }
        }

        // ── Up / down buttons ─────────────────────────

        if (type == "buttons") {
            section("<b>Up button</b>") {
                input "ctrl_${idx}_upDev",    "capability.pushableButton", title: "Up device", required: true
                input "ctrl_${idx}_upBtnNum", "number", title: "Button number", defaultValue: 1, range: "1..20", required: true
            }
            section("<b>Down button</b>") {
                paragraph "Can be the same device as the up button — just use a different button number."
                input "ctrl_${idx}_downDev",    "capability.pushableButton", title: "Down device", required: true
                input "ctrl_${idx}_downBtnNum", "number", title: "Button number", defaultValue: 2, range: "1..20", required: true
            }
            section("<b>Mute button (optional)</b>") {
                input "ctrl_${idx}_btnMuteDev",    "capability.pushableButton", title: "Mute device", required: false
                if (settings["ctrl_${idx}_btnMuteDev"]) {
                    input "ctrl_${idx}_btnMuteBtnNum", "number", title: "Button number", defaultValue: 3, range: "1..20"
                }
            }
            section("<b>Volume step</b>") {
                input "ctrl_${idx}_step", "number", title: "Volume change per press",
                      defaultValue: 5, range: "1..20", required: true
            }
        }

        // ── Targets ──────────────────────────────────

        section("<b>Target Echo devices</b>") {
            paragraph "All selected Echos respond simultaneously."
            input "ctrl_${idx}_targets", "capability.audioVolume",
                  title: "Echo devices to control", multiple: true, required: true
        }

        section() {
            input "ctrl_${idx}_remove", "button", title: "Remove this controller"
        }
    }
}

// -------------------------------------------------------
// APP BUTTON HANDLER
// -------------------------------------------------------

def appButtonHandler(btn) {
    switch (btn) {
        case "connectBtn":
            state.authStatus = "Connecting..."
            if (authenticate()) fetchEchoDevices()
            break

        case "syncDevices":
            syncChildDevices()
            break

        case "addController":
            def ids    = state.controllerIds ?: []
            def newIdx = ids ? (ids.max() + 1) : 0
            ids << newIdx
            state.controllerIds = ids
            state.editingIdx    = newIdx
            break

        default:
            if (btn ==~ /ctrl_(\d+)_remove/) {
                removeController((btn =~ /ctrl_(\d+)_remove/)[0][1].toInteger())
            }
    }
}

// -------------------------------------------------------
// LIFECYCLE
// -------------------------------------------------------

def installed() {
    log.info "Alexa Volume Manager installed"
    initialize()
}

def updated() {
    log.info "Alexa Volume Manager updated"
    unsubscribe()
    initialize()
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (refreshToken && state.authStatus != "Connected") authenticate()
    if (selectedEchoSerials) syncChildDevices()
    (state.controllerIds ?: []).each { subscribeController(it) }
    runEvery12Hours("authenticate")
}

// -------------------------------------------------------
// SUBSCRIPTIONS
// -------------------------------------------------------

private subscribeController(idx) {
    def type = settings["ctrl_${idx}_type"]
    if (!type) return

    switch (type) {
        case "rotary":
            def dev = settings["ctrl_${idx}_device"]
            if (dev) {
                subscribe(dev, "pushed",       pushHandler)
                subscribe(dev, "doubleTapped", doubleTapHandler)
                subscribe(dev, "held",         holdHandler)
            }
            break

        case "slider":
            def dev = settings["ctrl_${idx}_sliderDevice"]
            if (dev) subscribe(dev, "level", levelHandler)
            def mDev = settings["ctrl_${idx}_muteDev"]
            if (mDev) subscribe(mDev, "pushed", pushHandler)
            break

        case "buttons":
            def upDev   = settings["ctrl_${idx}_upDev"]
            def downDev = settings["ctrl_${idx}_downDev"]
            def mDev    = settings["ctrl_${idx}_btnMuteDev"]
            if (upDev)                                  subscribe(upDev,   "pushed", pushHandler)
            if (downDev && downDev.id != upDev?.id)    subscribe(downDev, "pushed", pushHandler)
            if (mDev && mDev.id != upDev?.id
                     && mDev.id != downDev?.id)        subscribe(mDev,    "pushed", pushHandler)
            break
    }
}

// -------------------------------------------------------
// EVENT HANDLERS
// -------------------------------------------------------

def pushHandler(evt) {
    def devId = evt.deviceId
    def btn   = evt.value?.toInteger()

    (state.controllerIds ?: []).each { idx ->
        def type    = settings["ctrl_${idx}_type"]
        def targets = settings["ctrl_${idx}_targets"]
        def step    = settings["ctrl_${idx}_step"]?.toInteger() ?: 5

        switch (type) {
            case "rotary":
                if (devId != settings["ctrl_${idx}_device"]?.id) return
                if      (btn == (settings["ctrl_${idx}_upBtn"]?.toInteger()   ?: 2)) targets?.each { it.volumeUp(step) }
                else if (btn == (settings["ctrl_${idx}_downBtn"]?.toInteger() ?: 3)) targets?.each { it.volumeDown(step) }
                else if (settings["ctrl_${idx}_muteBtn"] &&
                         btn == settings["ctrl_${idx}_muteBtn"].toInteger())         targets?.each { it.muteToggle() }
                break

            case "slider":
                def mDev = settings["ctrl_${idx}_muteDev"]
                if (devId == mDev?.id &&
                    btn == (settings["ctrl_${idx}_muteBtnNum"]?.toInteger() ?: 1))   targets?.each { it.muteToggle() }
                break

            case "buttons":
                def upDev      = settings["ctrl_${idx}_upDev"]
                def downDev    = settings["ctrl_${idx}_downDev"]
                def mDev       = settings["ctrl_${idx}_btnMuteDev"]
                def upBtnNum   = settings["ctrl_${idx}_upBtnNum"]?.toInteger()      ?: 1
                def downBtnNum = settings["ctrl_${idx}_downBtnNum"]?.toInteger()    ?: 2
                def mBtnNum    = settings["ctrl_${idx}_btnMuteBtnNum"]?.toInteger() ?: 3
                if      (devId == upDev?.id   && btn == upBtnNum)   targets?.each { it.volumeUp(step) }
                else if (devId == downDev?.id && btn == downBtnNum) targets?.each { it.volumeDown(step) }
                else if (mDev && devId == mDev?.id && btn == mBtnNum) targets?.each { it.muteToggle() }
                break
        }
    }
}

def levelHandler(evt) {
    def devId = evt.deviceId
    def level = evt.value?.toInteger()
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "slider") return
        if (devId != settings["ctrl_${idx}_sliderDevice"]?.id) return
        settings["ctrl_${idx}_targets"]?.each { it.setVolume(level) }
    }
}

def doubleTapHandler(evt) {
    if (evt.value?.toInteger() != 1) return
    def devId = evt.deviceId
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "rotary") return
        if (devId != settings["ctrl_${idx}_device"]?.id) return
        executeAction(idx, settings["ctrl_${idx}_dblTap"] ?: "none")
    }
}

def holdHandler(evt) {
    if (evt.value?.toInteger() != 1) return
    def devId = evt.deviceId
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "rotary") return
        if (devId != settings["ctrl_${idx}_device"]?.id) return
        executeAction(idx, settings["ctrl_${idx}_hold"] ?: "none")
    }
}

// -------------------------------------------------------
// VOLUME COMMANDS — called by child drivers via parent.*
// -------------------------------------------------------

def setVolume(childDevice, level) {
    level = Math.max(0, Math.min(100, level?.toInteger() ?: 50))
    if (sendAlexaCommand(childDevice, [type:"VolumeLevelCommand", volumeLevel:level, contentFocusClientId:null])) {
        childDevice.sendEvent(name: "volume", value: level, unit: "%")
    }
}

def volumeUp(childDevice, step) {
    setVolume(childDevice, (childDevice.currentValue("volume")?.toInteger() ?: 50) + (step?.toInteger() ?: 5))
}

def volumeDown(childDevice, step) {
    setVolume(childDevice, (childDevice.currentValue("volume")?.toInteger() ?: 50) - (step?.toInteger() ?: 5))
}

def mute(childDevice) {
    if (sendAlexaCommand(childDevice, [type:"VolumeMuteCommand", muted:true, contentFocusClientId:null]))
        childDevice.sendEvent(name: "mute", value: "muted")
}

def unmute(childDevice) {
    if (sendAlexaCommand(childDevice, [type:"VolumeMuteCommand", muted:false, contentFocusClientId:null]))
        childDevice.sendEvent(name: "mute", value: "unmuted")
}

def muteToggle(childDevice) {
    childDevice.currentValue("mute") == "muted" ? unmute(childDevice) : mute(childDevice)
}

// -------------------------------------------------------
// AMAZON API
// -------------------------------------------------------

private boolean sendAlexaCommand(childDevice, Map body) {
    ensureAuthenticated()
    def success = false
    try {
        httpPost([
            uri    : "https://alexa.${amazonDomain ?: 'amazon.com'}/api/np/command" +
                     "?deviceSerialNumber=${childDevice.getDataValue('serialNumber')}" +
                     "&deviceType=${childDevice.getDataValue('deviceType')}",
            headers: alexaHeaders() + ["Content-Type":"application/json"],
            body   : groovy.json.JsonOutput.toJson(body)
        ]) { resp ->
            if (resp.status in [200, 204]) { success = true }
            else {
                log.error "${childDevice.label}: command HTTP ${resp.status}"
                if (resp.status in [401, 403]) authenticate()
            }
        }
    } catch (Exception e) { log.error "sendAlexaCommand: ${e.message}" }
    return success
}

// -------------------------------------------------------
// AUTHENTICATION
// -------------------------------------------------------

private boolean authenticate() {
    if (!refreshToken) { state.authStatus = "Error: no token"; return false }
    logDebug "Authenticating..."
    def accessToken = getAccessToken()
    if (!accessToken) { state.authStatus = "Error: access token failed"; return false }
    def cookies = exchangeForCookies(accessToken)
    if (!cookies)     { state.authStatus = "Error: cookie exchange failed"; return false }
    state.cookies      = cookies.cookieString
    state.csrfToken    = cookies.csrf
    state.cookieExpiry = now() + (12 * 3600 * 1000L)
    state.authStatus   = "Connected"
    log.info "Alexa Volume Manager: authenticated"
    return true
}

private String getAccessToken() {
    def result = null
    try {
        httpPost([
            uri    : "https://api.amazon.com/auth/token",
            headers: ["Content-Type":"application/x-www-form-urlencoded",
                      "x-amzn-identity-auth-domain":"api.amazon.com",
                      "User-Agent":"AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone"],
            body   : "app_name=Amazon%20Alexa&app_version=2.2.223830.0&di.sdk.version=6.12.4" +
                     "&source_token=${URLEncoder.encode(refreshToken.trim(),'UTF-8')}" +
                     "&package_name=com.amazon.echo&di.hw.version=phone&platform=android" +
                     "&requested_token_type=access_token&source_token_type=refresh_token" +
                     "&di.os.name=android&di.os.version=22&current_version=6.12.4"
        ]) { resp -> if (resp.status == 200) result = resp.data?.access_token }
    } catch (Exception e) { log.error "getAccessToken: ${e.message}" }
    return result
}

private Map exchangeForCookies(String accessToken) {
    def domain = amazonDomain ?: "amazon.com"
    def list   = [], csrf = null
    try {
        httpPost([
            uri    : "https://www.${domain}/ap/exchangetoken/cookies",
            headers: ["Content-Type":"application/x-www-form-urlencoded",
                      "x-amzn-identity-auth-domain":"api.amazon.com",
                      "User-Agent":"AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone"],
            body   : "di.os.name=android&app_name=Amazon%20Alexa&di.hw.version=phone" +
                     "&di.sdk.version=6.12.4&di.os.version=22" +
                     "&source_token=${URLEncoder.encode(accessToken,'UTF-8')}" +
                     "&requested_token_type=auth_cookies&domain=.${domain}&source_token_type=access_token"
        ]) { resp ->
            resp.headers.each { h ->
                if (h.name?.toLowerCase() == "set-cookie") {
                    def nv = h.value?.split(";")?.getAt(0)?.trim()
                    if (nv) { list << nv; if (nv.startsWith("csrf=")) csrf = nv.replace("csrf=","") }
                }
            }
        }
    } catch (Exception e) { log.error "exchangeForCookies: ${e.message}"; return null }
    if (list.isEmpty()) { log.error "No cookies returned"; return null }
    return [cookieString: list.join("; "), csrf: csrf ?: ""]
}

private ensureAuthenticated() {
    if (!state.cookies || !state.cookieExpiry || now() > state.cookieExpiry) authenticate()
}

// -------------------------------------------------------
// ECHO DEVICE DISCOVERY & CHILD DEVICE MANAGEMENT
// -------------------------------------------------------

private fetchEchoDevices() {
    ensureAuthenticated()
    try {
        httpGet([uri:"https://alexa.${amazonDomain ?: 'amazon.com'}/api/devices-v2/device?raw=false",
                 headers: alexaHeaders()]) { resp ->
            if (resp.status == 200) {
                def echoMap = [:], typeMap = [:]
                resp.data?.devices?.each { d ->
                    def canPlay = d.capabilities?.any { it.interfaceName in
                        ["VOLUME_SETTING","AUDIO_PLAYER","NPE_ALERTS_VOLUME"] }
                    if (canPlay || d.deviceFamily in ["ECHO","KNIGHT","TABLET"]) {
                        echoMap[d.serialNumber] = d.accountName
                        typeMap[d.serialNumber] = d.deviceType
                    }
                }
                state.echoDevices     = echoMap
                state.echoDeviceTypes = typeMap
                log.info "Found ${echoMap.size()} Echo device(s)"
            }
        }
    } catch (Exception e) { log.error "fetchEchoDevices: ${e.message}" }
}

private syncChildDevices() {
    selectedEchoSerials?.each { serial ->
        if (!getChildDevice(serial)) {
            def name = state.echoDevices?.get(serial) ?: serial
            try {
                def child = addChildDevice("community", "Alexa Volume Device", serial,
                                          [name:"Alexa — ${name}", label:"Alexa — ${name}"])
                child.updateDataValue("serialNumber", serial)
                child.updateDataValue("deviceType",   state.echoDeviceTypes?.get(serial) ?: "")
                child.sendEvent(name: "echoDevice", value: name)
                log.info "Created child device: Alexa — ${name}"
            } catch (Exception e) { log.error "addChildDevice ${serial}: ${e.message}" }
        }
    }
    getChildDevices().each { child ->
        if (!selectedEchoSerials?.contains(child.deviceNetworkId))
            deleteChildDevice(child.deviceNetworkId)
    }
}

private removeController(int idx) {
    unsubscribe()
    state.controllerIds = (state.controllerIds ?: []) - [idx]
    ["name","type","device","upBtn","downBtn","muteBtn","step","dblTap","hold",
     "sliderDevice","muteDev","muteBtnNum","upDev","upBtnNum","downDev","downBtnNum",
     "btnMuteDev","btnMuteBtnNum","targets"].each { app.removeSetting("ctrl_${idx}_${it}") }
    log.info "Removed controller ${idx}"
    initialize()
}

// -------------------------------------------------------
// HELPERS
// -------------------------------------------------------

private Map alexaHeaders() {
    ["Cookie"    : state.cookies   ?: "",
     "csrf"      : state.csrfToken ?: "",
     "Accept"    : "application/json; charset=UTF-8",
     "User-Agent": "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone",
     "Referer"   : "https://alexa.${amazonDomain ?: 'amazon.com'}/spa/index.html"]
}

private Map volumeActionOptions() {
    ["none":"Do nothing","mute":"Mute toggle","vol0":"Set to 0",
     "vol25":"Set to 25%","vol50":"Set to 50%","vol75":"Set to 75%","vol100":"Set to max"]
}

private executeAction(idx, String action) {
    def targets = settings["ctrl_${idx}_targets"]
    switch (action) {
        case "mute"  : targets?.each { it.muteToggle() };  break
        case "vol0"  : targets?.each { it.setVolume(0) };  break
        case "vol25" : targets?.each { it.setVolume(25) }; break
        case "vol50" : targets?.each { it.setVolume(50) }; break
        case "vol75" : targets?.each { it.setVolume(75) }; break
        case "vol100": targets?.each { it.setVolume(100) };break
    }
}

private String typeLabel(type) {
    ["rotary":"Rotary/button","slider":"Slider","buttons":"Up/down"][type] ?: "Not configured"
}

private logDebug(String msg) { if (logEnable) log.debug "AlexaManager: ${msg}" }
