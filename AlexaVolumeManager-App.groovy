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

        section("<b>Logging</b>") {
            input "logLevel",
                  "enum",
                  title      : "Log level",
                  options    : ["0":"Off",
                                "1":"Info",
                                "2":"Debug",
                                "3":"Trace (verbose — logs everything including tokens and cookies)"],
                  defaultValue: "1"
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
                      "4. Copy the <code>Atnr|...</code> token from the terminal output"
        }

        section("<b>Credentials</b>") {
            // Using "text" not "password" so the token is visible for debugging
            input "refreshToken",
                  "text",
                  title      : "Amazon refresh token",
                  description: "Paste the full Atnr|... string",
                  required   : true

            input "amazonDomain",
                  "enum",
                  title       : "Amazon domain",
                  options     : ["amazon.com","amazon.co.uk","amazon.de","amazon.com.au","amazon.ca"],
                  defaultValue: "amazon.com",
                  required    : true

            input "connectBtn", "button", title: "Connect to Amazon"
        }

        section("<b>Status</b>") {
            def st = state.authStatus ?: "Not connected"
            paragraph st == "Connected"
                ? "<span style='color:#2a9d8f;font-weight:500'>● ${st}</span>"
                : "<span style='color:#e76f51'>${st}</span>"
            if (state.echoDevices) {
                paragraph "Found <b>${state.echoDevices.size()}</b> Echo device(s) — go to Echo devices to configure them."
            }
            if (state.lastAuthError) {
                paragraph "<small style='color:#e76f51'>Last error: ${state.lastAuthError}</small>"
            }
        }

        section("<b>Debug info</b>") {
            paragraph "<small>" +
                      "Token present: ${refreshToken ? 'Yes (' + refreshToken.take(8) + '...)' : 'No'}<br>" +
                      "Cookies present: ${state.cookies ? 'Yes (' + (state.cookies.split(';').size()) + ' cookies)' : 'No'}<br>" +
                      "Cookie expiry: ${state.cookieExpiry ? new Date(state.cookieExpiry).toString() : 'N/A'}<br>" +
                      "CSRF present: ${state.csrfToken ? 'Yes' : 'No'}" +
                      "</small>"
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
                  title   : "Echo devices",
                  options : state.echoDevices,
                  multiple: true,
                  required: true

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
                  title  : "Controller type",
                  options: [rotary :"Rotary knob or button device",
                            slider :"Slider / dimmer (0–100 level)",
                            buttons:"Separate up and down buttons"],
                  required: true, submitOnChange: true
        }

        if (type == "rotary") {
            section("<b>Device</b>") {
                paragraph "MOES TS004F defaults: button 2 = clockwise, 3 = counter-clockwise, 1 = center press."
                input "ctrl_${idx}_device", "capability.pushableButton",
                      title: "Rotary / button device", required: true
            }
            section("<b>Button mapping</b>") {
                input "ctrl_${idx}_upBtn",   "number", title: "Volume up button",            defaultValue: 2, range: "1..20", required: true
                input "ctrl_${idx}_downBtn", "number", title: "Volume down button",           defaultValue: 3, range: "1..20", required: true
                input "ctrl_${idx}_muteBtn", "number", title: "Mute toggle button (optional)", range: "1..20", required: false
                input "ctrl_${idx}_step",    "number", title: "Volume step per click",        defaultValue: 5, range: "1..20", required: true
            }
            section("<b>Center button extras (optional)</b>") {
                input "ctrl_${idx}_dblTap", "enum", title: "Double-tap action", options: volumeActionOptions(), defaultValue: "none"
                input "ctrl_${idx}_hold",   "enum", title: "Hold action",       options: volumeActionOptions(), defaultValue: "none"
            }
        }

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

        if (type == "buttons") {
            section("<b>Up button</b>") {
                input "ctrl_${idx}_upDev",    "capability.pushableButton", title: "Up device",     required: true
                input "ctrl_${idx}_upBtnNum", "number",                    title: "Button number", defaultValue: 1, range: "1..20", required: true
            }
            section("<b>Down button</b>") {
                paragraph "Can be the same device as the up button — just use a different button number."
                input "ctrl_${idx}_downDev",    "capability.pushableButton", title: "Down device",   required: true
                input "ctrl_${idx}_downBtnNum", "number",                    title: "Button number", defaultValue: 2, range: "1..20", required: true
            }
            section("<b>Mute button (optional)</b>") {
                input "ctrl_${idx}_btnMuteDev", "capability.pushableButton", title: "Mute device", required: false
                if (settings["ctrl_${idx}_btnMuteDev"]) {
                    input "ctrl_${idx}_btnMuteBtnNum", "number", title: "Button number", defaultValue: 3, range: "1..20"
                }
            }
            section("<b>Volume step</b>") {
                input "ctrl_${idx}_step", "number", title: "Volume change per press",
                      defaultValue: 5, range: "1..20", required: true
            }
        }

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
    logDebug "Button pressed: ${btn}"
    switch (btn) {
        case "connectBtn":
            state.authStatus    = "Connecting..."
            state.lastAuthError = null
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
            logInfo "Added controller ${newIdx}"
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

def installed() { logInfo "Installed"; initialize() }

def updated() { logInfo "Updated"; unsubscribe(); initialize() }

def uninstalled() {
    logInfo "Uninstalled — removing child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    logInfo "Initializing"
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
    if (!type) { logDebug "Controller ${idx}: no type set, skipping"; return }
    logDebug "Subscribing controller ${idx} (${type})"

    switch (type) {
        case "rotary":
            def dev = settings["ctrl_${idx}_device"]
            if (dev) {
                subscribe(dev, "pushed",       pushHandler)
                subscribe(dev, "doubleTapped", doubleTapHandler)
                subscribe(dev, "held",         holdHandler)
                logDebug "Controller ${idx}: subscribed to '${dev.displayName}' — pushed / doubleTapped / held"
            } else {
                logDebug "Controller ${idx}: no device configured"
            }
            break
        case "slider":
            def dev  = settings["ctrl_${idx}_sliderDevice"]
            def mDev = settings["ctrl_${idx}_muteDev"]
            if (dev)  { subscribe(dev,  "level",  levelHandler); logDebug "Controller ${idx}: subscribed slider '${dev.displayName}'" }
            if (mDev) { subscribe(mDev, "pushed", pushHandler);  logDebug "Controller ${idx}: subscribed mute button '${mDev.displayName}'" }
            break
        case "buttons":
            def upDev   = settings["ctrl_${idx}_upDev"]
            def downDev = settings["ctrl_${idx}_downDev"]
            def mDev    = settings["ctrl_${idx}_btnMuteDev"]
            if (upDev)                               { subscribe(upDev,   "pushed", pushHandler); logDebug "Controller ${idx}: subscribed up '${upDev.displayName}'" }
            if (downDev && downDev.id != upDev?.id) { subscribe(downDev, "pushed", pushHandler); logDebug "Controller ${idx}: subscribed down '${downDev.displayName}'" }
            if (mDev && mDev.id != upDev?.id
                     && mDev.id != downDev?.id)     { subscribe(mDev,    "pushed", pushHandler); logDebug "Controller ${idx}: subscribed mute '${mDev.displayName}'" }
            break
    }
}

// -------------------------------------------------------
// EVENT HANDLERS
// -------------------------------------------------------

def pushHandler(evt) {
    def devId = evt.deviceId
    def btn   = evt.value?.toInteger()
    logDebug "pushHandler: deviceId=${devId} button=${btn}"

    (state.controllerIds ?: []).each { idx ->
        def type    = settings["ctrl_${idx}_type"]
        def targets = settings["ctrl_${idx}_targets"]
        def step    = settings["ctrl_${idx}_step"]?.toInteger() ?: 5

        switch (type) {
            case "rotary":
                if (devId != settings["ctrl_${idx}_device"]?.id) return
                def upBtn   = settings["ctrl_${idx}_upBtn"]?.toInteger()   ?: 2
                def downBtn = settings["ctrl_${idx}_downBtn"]?.toInteger() ?: 3
                def muteBtn = settings["ctrl_${idx}_muteBtn"]?.toInteger()
                logTrace "Controller ${idx} rotary: btn=${btn} upBtn=${upBtn} downBtn=${downBtn} muteBtn=${muteBtn} step=${step}"
                if      (btn == upBtn)              { logInfo "Controller ${idx}: volume up (step=${step})";   targets?.each { it.volumeUp(step) } }
                else if (btn == downBtn)            { logInfo "Controller ${idx}: volume down (step=${step})"; targets?.each { it.volumeDown(step) } }
                else if (muteBtn && btn == muteBtn) { logInfo "Controller ${idx}: mute toggle";               targets?.each { it.muteToggle() } }
                else                               { logTrace "Controller ${idx}: button ${btn} not mapped — ignoring" }
                break

            case "slider":
                def mDev    = settings["ctrl_${idx}_muteDev"]
                def mBtnNum = settings["ctrl_${idx}_muteBtnNum"]?.toInteger() ?: 1
                logTrace "Controller ${idx} slider mute check: devId=${devId} muteDev=${mDev?.id} btn=${btn} muteBtnNum=${mBtnNum}"
                if (devId == mDev?.id && btn == mBtnNum) { logInfo "Controller ${idx}: mute toggle"; targets?.each { it.muteToggle() } }
                break

            case "buttons":
                def upDev      = settings["ctrl_${idx}_upDev"]
                def downDev    = settings["ctrl_${idx}_downDev"]
                def mDev       = settings["ctrl_${idx}_btnMuteDev"]
                def upBtnNum   = settings["ctrl_${idx}_upBtnNum"]?.toInteger()      ?: 1
                def downBtnNum = settings["ctrl_${idx}_downBtnNum"]?.toInteger()    ?: 2
                def mBtnNum    = settings["ctrl_${idx}_btnMuteBtnNum"]?.toInteger() ?: 3
                logTrace "Controller ${idx} buttons: devId=${devId} btn=${btn} up=${upDev?.id}:${upBtnNum} down=${downDev?.id}:${downBtnNum} mute=${mDev?.id}:${mBtnNum}"
                if      (devId == upDev?.id   && btn == upBtnNum)     { logInfo "Controller ${idx}: volume up (step=${step})";   targets?.each { it.volumeUp(step) } }
                else if (devId == downDev?.id && btn == downBtnNum)   { logInfo "Controller ${idx}: volume down (step=${step})"; targets?.each { it.volumeDown(step) } }
                else if (mDev && devId == mDev?.id && btn == mBtnNum) { logInfo "Controller ${idx}: mute toggle";               targets?.each { it.muteToggle() } }
                break
        }
    }
}

def levelHandler(evt) {
    def devId = evt.deviceId
    def level = evt.value?.toInteger()
    logDebug "levelHandler: deviceId=${devId} level=${level}"
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "slider") return
        if (devId != settings["ctrl_${idx}_sliderDevice"]?.id) return
        logInfo "Controller ${idx}: setVolume(${level})"
        settings["ctrl_${idx}_targets"]?.each { it.setVolume(level) }
    }
}

def doubleTapHandler(evt) {
    if (evt.value?.toInteger() != 1) return
    def devId = evt.deviceId
    logDebug "doubleTapHandler: deviceId=${devId}"
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "rotary") return
        if (devId != settings["ctrl_${idx}_device"]?.id) return
        def action = settings["ctrl_${idx}_dblTap"] ?: "none"
        logInfo "Controller ${idx}: double-tap → ${action}"
        executeAction(idx, action)
    }
}

def holdHandler(evt) {
    if (evt.value?.toInteger() != 1) return
    def devId = evt.deviceId
    logDebug "holdHandler: deviceId=${devId}"
    (state.controllerIds ?: []).each { idx ->
        if (settings["ctrl_${idx}_type"] != "rotary") return
        if (devId != settings["ctrl_${idx}_device"]?.id) return
        def action = settings["ctrl_${idx}_hold"] ?: "none"
        logInfo "Controller ${idx}: hold → ${action}"
        executeAction(idx, action)
    }
}

// -------------------------------------------------------
// VOLUME COMMANDS — called by child drivers via parent.*
// -------------------------------------------------------

def setVolume(childDevice, level) {
    level = Math.max(0, Math.min(100, level?.toInteger() ?: 50))
    logDebug "setVolume: ${childDevice.label} → ${level}"
    if (sendAlexaCommand(childDevice, [type:"VolumeLevelCommand", volumeLevel:level, contentFocusClientId:null])) {
        childDevice.sendEvent(name: "volume", value: level, unit: "%")
        logInfo "Volume set: ${childDevice.label} = ${level}%"
    }
}

def volumeUp(childDevice, step) {
    def current = childDevice.currentValue("volume")?.toInteger() ?: 50
    logDebug "volumeUp: ${childDevice.label} current=${current} step=${step}"
    setVolume(childDevice, current + (step?.toInteger() ?: 5))
}

def volumeDown(childDevice, step) {
    def current = childDevice.currentValue("volume")?.toInteger() ?: 50
    logDebug "volumeDown: ${childDevice.label} current=${current} step=${step}"
    setVolume(childDevice, current - (step?.toInteger() ?: 5))
}

def mute(childDevice) {
    logDebug "mute: ${childDevice.label}"
    if (sendAlexaCommand(childDevice, [type:"VolumeMuteCommand", muted:true, contentFocusClientId:null])) {
        childDevice.sendEvent(name: "mute", value: "muted")
        logInfo "Muted: ${childDevice.label}"
    }
}

def unmute(childDevice) {
    logDebug "unmute: ${childDevice.label}"
    if (sendAlexaCommand(childDevice, [type:"VolumeMuteCommand", muted:false, contentFocusClientId:null])) {
        childDevice.sendEvent(name: "mute", value: "unmuted")
        logInfo "Unmuted: ${childDevice.label}"
    }
}

def muteToggle(childDevice) {
    logDebug "muteToggle: ${childDevice.label} current=${childDevice.currentValue('mute')}"
    childDevice.currentValue("mute") == "muted" ? unmute(childDevice) : mute(childDevice)
}

// -------------------------------------------------------
// AMAZON API
// -------------------------------------------------------

private boolean sendAlexaCommand(childDevice, Map body) {
    ensureAuthenticated()
    def serial   = childDevice.getDataValue("serialNumber")
    def devType  = childDevice.getDataValue("deviceType")
    def domain   = amazonDomain ?: "amazon.com"
    def url      = "https://alexa.${domain}/api/np/command?deviceSerialNumber=${serial}&deviceType=${devType}"
    def bodyJson = groovy.json.JsonOutput.toJson(body)

    logTrace "sendAlexaCommand: url=${url}"
    logTrace "sendAlexaCommand: body=${bodyJson}"
    logTrace "sendAlexaCommand: cookieLength=${state.cookies?.length()} csrfPresent=${state.csrfToken ? true : false}"

    def success = false
    try {
        httpPost([
            uri    : url,
            headers: alexaHeaders() + ["Content-Type":"application/json"],
            body   : bodyJson
        ]) { resp ->
            logTrace "sendAlexaCommand: response status=${resp.status}"
            logTrace "sendAlexaCommand: response headers=${resp.headers?.collect { "${it.name}: ${it.value}" }?.join(' | ')}"
            if (resp.status in [200, 204]) {
                success = true
            } else {
                log.error "AlexaManager: command failed for ${childDevice.label} — HTTP ${resp.status}"
                logDebug "sendAlexaCommand: error body=${resp.data}"
                if (resp.status in [401, 403]) {
                    logInfo "Session rejected (${resp.status}) — re-authenticating"
                    authenticate()
                }
            }
        }
    } catch (Exception e) {
        log.error "AlexaManager: sendAlexaCommand exception — ${e.message}"
        logTrace "sendAlexaCommand: full exception=${e}"
    }
    return success
}

// -------------------------------------------------------
// AUTHENTICATION
// -------------------------------------------------------

private boolean authenticate() {
    logInfo "Starting authentication"
    if (!refreshToken) {
        log.error "AlexaManager: no refresh token configured"
        state.authStatus = "Error: no token configured"
        return false
    }

    logDebug "authenticate: token starts with '${refreshToken.take(12)}...'"
    logDebug "authenticate: domain=${amazonDomain}"

    def accessToken = getAccessToken()
    if (!accessToken) {
        state.authStatus    = "Error: access token failed"
        state.lastAuthError = "getAccessToken returned null — check token is valid and try reconnecting"
        return false
    }

    def cookies = exchangeForCookies(accessToken)
    if (!cookies) {
        state.authStatus    = "Error: cookie exchange failed"
        state.lastAuthError = "exchangeForCookies returned null — set log level to Trace and reconnect for full detail"
        return false
    }

    state.cookies       = cookies.cookieString
    state.csrfToken     = cookies.csrf
    state.cookieExpiry  = now() + (12 * 3600 * 1000L)
    state.authStatus    = "Connected"
    state.lastAuthError = null
    logInfo "Authenticated — ${cookies.cookieString.split(';').size()} cookies captured, CSRF present: ${cookies.csrf ? 'yes' : 'no'}"
    return true
}

private String getAccessToken() {
    def result  = null
    def bodyStr = "app_name=Amazon%20Alexa&app_version=2.2.223830.0&di.sdk.version=6.12.4" +
                  "&source_token=${URLEncoder.encode(refreshToken.trim(), 'UTF-8')}" +
                  "&package_name=com.amazon.echo&di.hw.version=phone&platform=android" +
                  "&requested_token_type=access_token&source_token_type=refresh_token" +
                  "&di.os.name=android&di.os.version=22&current_version=6.12.4"

    logTrace "getAccessToken: POST https://api.amazon.com/auth/token"
    logTrace "getAccessToken: body=${bodyStr}"

    try {
        httpPost([
            uri    : "https://api.amazon.com/auth/token",
            headers: [
                "Content-Type"               : "application/x-www-form-urlencoded",
                "x-amzn-identity-auth-domain": "api.amazon.com",
                "User-Agent"                 : "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone"
            ],
            body   : bodyStr
        ]) { resp ->
            logDebug "getAccessToken: response status=${resp.status}"
            logTrace "getAccessToken: response headers=${resp.headers?.collect { "${it.name}: ${it.value}" }?.join(' | ')}"
            logTrace "getAccessToken: response body=${resp.data}"
            if (resp.status == 200) {
                result = resp.data?.access_token
                logDebug "getAccessToken: token obtained, length=${result?.length()}"
            } else {
                log.error "AlexaManager: getAccessToken HTTP ${resp.status}"
                logDebug "getAccessToken: error response body=${resp.data}"
            }
        }
    } catch (Exception e) {
        log.error "AlexaManager: getAccessToken exception — ${e.message}"
        logTrace "getAccessToken: full exception=${e}"
    }
    return result
}

private Map exchangeForCookies(String accessToken) {
    def domain   = amazonDomain ?: "amazon.com"
    def list     = [], csrf = null
    def bodyMap  = [
        "requested_token_type": "auth_cookies",
        "domain"              : ".${domain}",
        "source_token_type"   : "access_token",
        "source_token"        : accessToken
    ]
    def bodyJson = groovy.json.JsonOutput.toJson(bodyMap)
    def url      = "https://www.${domain}/ap/exchangetoken/cookies"

    logTrace "exchangeForCookies: POST ${url}"
    logTrace "exchangeForCookies: body=${bodyJson}"

    try {
        httpPost([
            uri            : url,
            headers        : [
                "Content-Type"               : "application/json",
                "x-amzn-identity-auth-domain": "api.amazon.com",
                "User-Agent"                 : "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone",
                "Accept"                     : "application/json"
            ],
            body           : bodyJson,
            followRedirects: true
        ]) { resp ->
            logDebug "exchangeForCookies: response status=${resp.status}"
            logTrace "exchangeForCookies: response headers=${resp.headers?.collect { "${it.name}: ${it.value}" }?.join(' | ')}"
            logTrace "exchangeForCookies: response body=${resp.data}"

            // Path 1 — cookies in set-cookie response headers
            resp.headers.each { h ->
                if (h.name?.toLowerCase() == "set-cookie") {
                    logTrace "exchangeForCookies: set-cookie header=${h.value}"
                    def nv = h.value?.split(";")?.getAt(0)?.trim()
                    if (nv) {
                        list << nv
                        if (nv.startsWith("csrf=")) csrf = nv.replace("csrf=", "")
                    }
                }
            }
            logDebug "exchangeForCookies: ${list.size()} cookies found in response headers"

            // Path 2 — cookies inside JSON response body (some Amazon versions)
            if (list.isEmpty() && resp.data) {
                logDebug "exchangeForCookies: no header cookies — checking JSON body"
                def cookies = resp.data?.response?.tokens?.cookies
                logTrace "exchangeForCookies: body cookies map=${cookies}"
                cookies?.each { domainKey, cookieList ->
                    cookieList?.each { c ->
                        def nv = "${c.Name}=${c.Value}"
                        list << nv
                        logTrace "exchangeForCookies: body cookie name=${c.Name}"
                        if (c.Name == "csrf") csrf = c.Value
                    }
                }
                logDebug "exchangeForCookies: ${list.size()} cookies found in JSON body"
            }
        }
    } catch (Exception e) {
        log.error "AlexaManager: exchangeForCookies exception — ${e.message}"
        logTrace "exchangeForCookies: full exception=${e}"
        return null
    }

    if (list.isEmpty()) {
        log.error "AlexaManager: exchangeForCookies — no cookies in headers or body"
        return null
    }

    logDebug "exchangeForCookies: total=${list.size()} cookies, csrf=${csrf ? 'present' : 'missing'}"
    logTrace "exchangeForCookies: cookie names=${list.collect { it.split('=')[0] }.join(', ')}"
    return [cookieString: list.join("; "), csrf: csrf ?: ""]
}

private ensureAuthenticated() {
    if (!state.cookies || !state.cookieExpiry || now() > state.cookieExpiry) {
        logDebug "ensureAuthenticated: session missing or expired — re-authenticating"
        authenticate()
    } else {
        logTrace "ensureAuthenticated: session valid, expires ${new Date(state.cookieExpiry)}"
    }
}

// -------------------------------------------------------
// ECHO DEVICE DISCOVERY & CHILD DEVICE MANAGEMENT
// -------------------------------------------------------

private fetchEchoDevices() {
    ensureAuthenticated()
    def url = "https://alexa.${amazonDomain ?: 'amazon.com'}/api/devices-v2/device?raw=false"
    logDebug "fetchEchoDevices: GET ${url}"

    try {
        httpGet([uri: url, headers: alexaHeaders()]) { resp ->
            logDebug "fetchEchoDevices: response status=${resp.status}"
            logTrace "fetchEchoDevices: response body=${resp.data}"
            if (resp.status == 200) {
                def echoMap = [:], typeMap = [:]
                resp.data?.devices?.each { d ->
                    logTrace "fetchEchoDevices: checking device='${d.accountName}' family=${d.deviceFamily} caps=${d.capabilities?.collect { it.interfaceName }}"
                    def canPlay = d.capabilities?.any { it.interfaceName in
                        ["VOLUME_SETTING","AUDIO_PLAYER","NPE_ALERTS_VOLUME"] }
                    if (canPlay || d.deviceFamily in ["ECHO","KNIGHT","TABLET"]) {
                        echoMap[d.serialNumber] = d.accountName
                        typeMap[d.serialNumber] = d.deviceType
                        logDebug "fetchEchoDevices: accepted '${d.accountName}' (${d.serialNumber})"
                    } else {
                        logTrace "fetchEchoDevices: skipped '${d.accountName}' — not a volume-capable Echo"
                    }
                }
                state.echoDevices     = echoMap
                state.echoDeviceTypes = typeMap
                logInfo "Found ${echoMap.size()} Echo device(s): ${echoMap.values().join(', ')}"
            } else {
                log.error "AlexaManager: fetchEchoDevices HTTP ${resp.status}"
                logDebug "fetchEchoDevices: error body=${resp.data}"
            }
        }
    } catch (Exception e) {
        log.error "AlexaManager: fetchEchoDevices exception — ${e.message}"
        logTrace "fetchEchoDevices: full exception=${e}"
    }
}

private syncChildDevices() {
    logDebug "syncChildDevices: selected serials=${selectedEchoSerials}"
    selectedEchoSerials?.each { serial ->
        if (!getChildDevice(serial)) {
            def name = state.echoDevices?.get(serial) ?: serial
            try {
                def child = addChildDevice("community", "Alexa Volume Device", serial,
                                          [name:"Alexa — ${name}", label:"Alexa — ${name}"])
                child.updateDataValue("serialNumber", serial)
                child.updateDataValue("deviceType",   state.echoDeviceTypes?.get(serial) ?: "")
                child.sendEvent(name: "echoDevice", value: name)
                logInfo "Created child device: Alexa — ${name} (serial=${serial})"
            } catch (Exception e) {
                log.error "AlexaManager: addChildDevice failed for ${serial} — ${e.message}"
            }
        } else {
            logDebug "syncChildDevices: ${serial} already exists — skipping"
        }
    }
    getChildDevices().each { child ->
        if (!selectedEchoSerials?.contains(child.deviceNetworkId)) {
            logInfo "Removing deselected child device: ${child.label}"
            deleteChildDevice(child.deviceNetworkId)
        }
    }
}

private removeController(int idx) {
    logInfo "Removing controller ${idx}"
    unsubscribe()
    state.controllerIds = (state.controllerIds ?: []) - [idx]
    ["name","type","device","upBtn","downBtn","muteBtn","step","dblTap","hold",
     "sliderDevice","muteDev","muteBtnNum","upDev","upBtnNum","downDev","downBtnNum",
     "btnMuteDev","btnMuteBtnNum","targets"].each { app.removeSetting("ctrl_${idx}_${it}") }
    initialize()
}

// -------------------------------------------------------
// HELPERS
// -------------------------------------------------------

private Map alexaHeaders() {
    def h = [
        "Cookie"    : state.cookies   ?: "",
        "csrf"      : state.csrfToken ?: "",
        "Accept"    : "application/json; charset=UTF-8",
        "User-Agent": "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone",
        "Referer"   : "https://alexa.${amazonDomain ?: 'amazon.com'}/spa/index.html"
    ]
    logTrace "alexaHeaders: csrf=${h.csrf ? 'present' : 'missing'} cookieLength=${h.Cookie?.length()}"
    return h
}

private Map volumeActionOptions() {
    ["none":"Do nothing","mute":"Mute toggle","vol0":"Set to 0",
     "vol25":"Set to 25%","vol50":"Set to 50%","vol75":"Set to 75%","vol100":"Set to max"]
}

private executeAction(idx, String action) {
    def targets = settings["ctrl_${idx}_targets"]
    logDebug "executeAction: idx=${idx} action=${action} targets=${targets?.size()}"
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

private logInfo(String msg)  { if ((logLevel?.toInteger() ?: 1) >= 1) log.info  "AlexaManager: ${msg}" }
private logDebug(String msg) { if ((logLevel?.toInteger() ?: 1) >= 2) log.debug "AlexaManager: ${msg}" }
private logTrace(String msg) { if ((logLevel?.toInteger() ?: 1) >= 3) log.trace "AlexaManager: ${msg}" }
