/**
 * Alexa Volume Control Driver for Hubitat
 * ----------------------------------------
 * Controls Amazon Echo device volume via Amazon's internal API.
 * Requires a refresh token obtained once via alexa-cookie-cli.
 *
 * Setup:
 *   1. Download alexa-cookie-cli from https://github.com/adn77/alexa-cookie-cli/releases
 *   2. Run it on your PC, log into Amazon in the browser it opens (http://127.0.0.1:8080)
 *   3. Copy the Atnr|... token it outputs and paste into driver preferences
 *   4. Run listDevices() and check Hubitat logs to find your Echo device name
 *   5. Paste the exact Echo name into preferences
 *
 * NOTE: This uses Amazon's internal (unofficial) API. Amazon may change it.
 *       If it stops working, a driver update will be required — the refresh
 *       token itself typically remains valid long-term.
 */

metadata {
    definition(
        name: "Alexa Volume Control",
        namespace: "community",
        author: "community",
        description: "Controls Alexa Echo volume via Amazon refresh token auth"
    ) {
        capability "AudioVolume"   // Exposes volume, mute, setVolume(), mute(), unmute()
        capability "Refresh"

        command "setVolume",    [[name: "Volume Level*", type: "NUMBER", description: "0-100"]]
        command "volumeUp",     [[name: "Step",          type: "NUMBER", description: "How much to increase (default 5)"]]
        command "volumeDown",   [[name: "Step",          type: "NUMBER", description: "How much to decrease (default 5)"]]
        command "listDevices"   // Logs all Echo device names to Hubitat logs
        command "refreshCookies" // Force re-authentication

        attribute "volume",      "number"
        attribute "mute",        "string"   // "muted" or "unmuted"
        attribute "authStatus",  "string"   // Human-readable auth state
        attribute "echoDevice",  "string"   // Currently targeted Echo device
    }

    preferences {
        input name: "refreshToken",
              type: "password",
              title: "Amazon Refresh Token",
              description: "Paste the full Atnr|... token from alexa-cookie-cli",
              required: true

        input name: "echoDeviceName",
              type: "string",
              title: "Echo Device Name",
              description: "Exact name as shown in Alexa app. Run listDevices() to find it.",
              required: true

        input name: "amazonDomain",
              type: "enum",
              title: "Amazon Domain",
              options: ["amazon.com", "amazon.co.uk", "amazon.de", "amazon.com.au", "amazon.ca"],
              defaultValue: "amazon.com",
              required: true

        input name: "defaultStep",
              type: "number",
              title: "Default volume step size",
              description: "Used by volumeUp/Down when no step is specified",
              defaultValue: 5,
              range: "1..20"

        input name: "logEnable",
              type: "bool",
              title: "Enable debug logging",
              defaultValue: false
    }
}

// -------------------------------------------------------
// LIFECYCLE
// -------------------------------------------------------

def installed() {
    log.info "${device.name}: Driver installed"
    sendEvent(name: "authStatus", value: "Not configured")
    sendEvent(name: "volume",     value: 50)
    sendEvent(name: "mute",       value: "unmuted")
    state.currentVolume = 50
}

def updated() {
    log.info "${device.name}: Preferences updated — re-authenticating"
    unschedule()
    state.cookies      = null
    state.csrfToken    = null
    state.cookieExpiry = 0
    state.deviceSerial = null
    state.deviceType   = null
    runIn(2, "authenticate")
}

def refresh() {
    refreshCookies()
}

// -------------------------------------------------------
// AUTHENTICATION
// -------------------------------------------------------

def authenticate() {
    if (!refreshToken) {
        log.error "${device.name}: No refresh token set in preferences"
        sendEvent(name: "authStatus", value: "Error: No token configured")
        return
    }

    logDebug "Starting Amazon authentication..."

    try {
        // Step 1: Exchange refresh token → access token
        def accessToken = getAccessToken()
        if (!accessToken) {
            sendEvent(name: "authStatus", value: "Error: Access token request failed")
            return
        }

        // Step 2: Exchange access token → session cookies
        def cookieData = exchangeForCookies(accessToken)
        if (!cookieData) {
            sendEvent(name: "authStatus", value: "Error: Cookie exchange failed")
            return
        }

        state.cookies      = cookieData.cookieString
        state.csrfToken    = cookieData.csrf
        // Cookies typically valid ~24h; we refresh every 12h to be safe
        state.cookieExpiry = now() + (12 * 3600 * 1000)

        sendEvent(name: "authStatus", value: "Authenticated")
        log.info "${device.name}: Successfully authenticated with Amazon"

        // Resolve Echo serial number from device name
        if (echoDeviceName) {
            resolveDeviceSerial()
        }

        // Schedule automatic cookie refresh every 12 hours
        runIn(43200, "authenticate")

    } catch (Exception e) {
        log.error "${device.name}: Authentication error — ${e.message}"
        sendEvent(name: "authStatus", value: "Error: ${e.message}")
    }
}

private Map getAccessToken() {
    def domain = amazonDomain ?: "amazon.com"
    def result = null

    def params = [
        uri    : "https://api.amazon.com/auth/token",
        headers: [
            "Content-Type"               : "application/x-www-form-urlencoded",
            "x-amzn-identity-auth-domain": "api.amazon.com",
            "User-Agent"                 : "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone"
        ],
        body   : "app_name=Amazon%20Alexa" +
                 "&app_version=2.2.223830.0" +
                 "&di.sdk.version=6.12.4" +
                 "&source_token=${URLEncoder.encode(refreshToken.trim(), 'UTF-8')}" +
                 "&package_name=com.amazon.echo" +
                 "&di.hw.version=phone" +
                 "&platform=android" +
                 "&requested_token_type=access_token" +
                 "&source_token_type=refresh_token" +
                 "&di.os.name=android" +
                 "&di.os.version=22" +
                 "&current_version=6.12.4"
    ]

    try {
        httpPost(params) { resp ->
            logDebug "Token endpoint response: ${resp.status}"
            if (resp.status == 200 && resp.data?.access_token) {
                result = resp.data.access_token
                logDebug "Access token obtained"
            } else {
                log.error "${device.name}: Token response status ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: getAccessToken exception — ${e.message}"
    }

    return result
}

private Map exchangeForCookies(String accessToken) {
    def domain     = amazonDomain ?: "amazon.com"
    def cookieList = []
    def csrfValue  = null

    def params = [
        uri    : "https://www.${domain}/ap/exchangetoken/cookies",
        headers: [
            "Content-Type"               : "application/x-www-form-urlencoded",
            "x-amzn-identity-auth-domain": "api.amazon.com",
            "User-Agent"                 : "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone"
        ],
        body   : "di.os.name=android" +
                 "&app_name=Amazon%20Alexa" +
                 "&di.hw.version=phone" +
                 "&di.sdk.version=6.12.4" +
                 "&di.os.version=22" +
                 "&source_token=${URLEncoder.encode(accessToken, 'UTF-8')}" +
                 "&requested_token_type=auth_cookies" +
                 "&domain=.${domain}" +
                 "&source_token_type=access_token",
        followRedirects: true
    ]

    try {
        httpPost(params) { resp ->
            logDebug "Cookie exchange response: ${resp.status}"
            resp.headers.each { header ->
                if (header.name?.toLowerCase() == "set-cookie") {
                    // Extract name=value portion only (before the first semicolon)
                    def nameValue = header.value?.split(";")?.getAt(0)?.trim()
                    if (nameValue) {
                        cookieList.add(nameValue)
                        // Extract the csrf cookie specifically
                        if (nameValue.startsWith("csrf=")) {
                            csrfValue = nameValue.replace("csrf=", "").trim()
                        }
                    }
                }
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: exchangeForCookies exception — ${e.message}"
        return null
    }

    if (cookieList.isEmpty()) {
        log.error "${device.name}: No cookies returned from exchange"
        return null
    }

    logDebug "Captured ${cookieList.size()} cookies. CSRF present: ${csrfValue != null}"
    return [
        cookieString: cookieList.join("; "),
        csrf        : csrfValue ?: ""
    ]
}

def refreshCookies() {
    log.info "${device.name}: Forcing cookie refresh"
    state.cookies      = null
    state.csrfToken    = null
    state.cookieExpiry = 0
    authenticate()
}

private ensureAuthenticated() {
    if (!state.cookies || now() > (state.cookieExpiry ?: 0)) {
        logDebug "Session expired or missing — re-authenticating"
        authenticate()
        pauseExecution(3000)  // Wait for async auth to complete
    }
}

// -------------------------------------------------------
// DEVICE RESOLUTION
// -------------------------------------------------------

def listDevices() {
    ensureAuthenticated()

    def domain = amazonDomain ?: "amazon.com"
    def params = [
        uri    : "https://alexa.${domain}/api/devices-v2/device?raw=false",
        headers: alexaHeaders()
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                def devices = resp.data?.devices
                if (devices) {
                    log.info "============================================"
                    log.info "  ALEXA ECHO DEVICES — copy name exactly"
                    log.info "============================================"
                    devices.each { d ->
                        // Filter to playback-capable devices only
                        def hasVolume = d.capabilities?.any { c ->
                            c.interfaceName in ["VOLUME_SETTING", "AUDIO_PLAYER", "NPE_ALERTS_VOLUME"]
                        }
                        if (hasVolume || d.deviceFamily == "ECHO") {
                            log.info "  Name : '${d.accountName}'"
                            log.info "  Type : ${d.deviceFamily} / ${d.deviceType}"
                            log.info "  Serial: ${d.serialNumber}"
                            log.info "  ----"
                        }
                    }
                    log.info "============================================"
                } else {
                    log.warn "${device.name}: No devices returned"
                }
            } else {
                log.error "${device.name}: listDevices failed — HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: listDevices exception — ${e.message}"
    }
}

private resolveDeviceSerial() {
    def domain = amazonDomain ?: "amazon.com"
    def params = [
        uri    : "https://alexa.${domain}/api/devices-v2/device?raw=false",
        headers: alexaHeaders()
    ]

    try {
        httpGet(params) { resp ->
            if (resp.status == 200) {
                def devices = resp.data?.devices
                def match   = devices?.find { it.accountName?.equalsIgnoreCase(echoDeviceName?.trim()) }

                if (match) {
                    state.deviceSerial = match.serialNumber
                    state.deviceType   = match.deviceType
                    sendEvent(name: "echoDevice", value: match.accountName)
                    log.info "${device.name}: Resolved '${echoDeviceName}' → serial ${state.deviceSerial}"
                } else {
                    log.warn "${device.name}: Device '${echoDeviceName}' not found. Run listDevices() to see all names."
                    sendEvent(name: "authStatus", value: "Warning: Echo device not found")
                }
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: resolveDeviceSerial exception — ${e.message}"
    }
}

// -------------------------------------------------------
// VOLUME COMMANDS
// -------------------------------------------------------

def setVolume(level) {
    level = Math.max(0, Math.min(100, level?.toInteger() ?: 50))
    ensureAuthenticated()

    if (!state.deviceSerial) {
        resolveDeviceSerial()
        if (!state.deviceSerial) {
            log.error "${device.name}: Cannot set volume — Echo device not resolved"
            return
        }
    }

    def domain = amazonDomain ?: "amazon.com"
    def body   = groovy.json.JsonOutput.toJson([
        type                : "VolumeLevelCommand",
        volumeLevel         : level,
        contentFocusClientId: null
    ])

    def params = [
        uri    : "https://alexa.${domain}/api/np/command" +
                 "?deviceSerialNumber=${state.deviceSerial}" +
                 "&deviceType=${state.deviceType}",
        headers: alexaHeaders() + ["Content-Type": "application/json"],
        body   : body
    ]

    try {
        httpPost(params) { resp ->
            if (resp.status in [200, 204]) {
                state.currentVolume = level
                sendEvent(name: "volume", value: level, unit: "%")
                logDebug "Volume set to ${level}"
            } else {
                log.error "${device.name}: setVolume failed — HTTP ${resp.status}"
                // If 401/403, try re-auth then retry once
                if (resp.status in [401, 403]) {
                    refreshCookies()
                }
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: setVolume exception — ${e.message}"
    }
}

def volumeUp(step = null) {
    def s       = step?.toInteger() ?: (defaultStep?.toInteger() ?: 5)
    def current = state.currentVolume?.toInteger() ?: 50
    setVolume(current + s)
}

def volumeDown(step = null) {
    def s       = step?.toInteger() ?: (defaultStep?.toInteger() ?: 5)
    def current = state.currentVolume?.toInteger() ?: 50
    setVolume(current - s)
}

def mute() {
    sendVolumeCommand([type: "VolumeMuteCommand", muted: true, contentFocusClientId: null])
    sendEvent(name: "mute", value: "muted")
}

def unmute() {
    sendVolumeCommand([type: "VolumeMuteCommand", muted: false, contentFocusClientId: null])
    sendEvent(name: "mute", value: "unmuted")
}

def muteToggle() {
    if (device.currentValue("mute") == "muted") {
        unmute()
    } else {
        mute()
    }
}

private sendVolumeCommand(Map commandBody) {
    ensureAuthenticated()
    if (!state.deviceSerial) resolveDeviceSerial()
    if (!state.deviceSerial) return

    def domain = amazonDomain ?: "amazon.com"
    def params = [
        uri    : "https://alexa.${domain}/api/np/command" +
                 "?deviceSerialNumber=${state.deviceSerial}" +
                 "&deviceType=${state.deviceType}",
        headers: alexaHeaders() + ["Content-Type": "application/json"],
        body   : groovy.json.JsonOutput.toJson(commandBody)
    ]

    try {
        httpPost(params) { resp ->
            if (!(resp.status in [200, 204])) {
                log.error "${device.name}: Command failed — HTTP ${resp.status}"
            }
        }
    } catch (Exception e) {
        log.error "${device.name}: sendVolumeCommand exception — ${e.message}"
    }
}

// -------------------------------------------------------
// HELPERS
// -------------------------------------------------------

private Map alexaHeaders() {
    return [
        "Cookie"     : state.cookies ?: "",
        "csrf"       : state.csrfToken ?: "",
        "Accept"     : "application/json; charset=UTF-8",
        "User-Agent" : "AmazonWebView/Amazon Alexa/2.2.223830.0/iOS/16.6/iPhone",
        "Referer"    : "https://alexa.${amazonDomain ?: 'amazon.com'}/spa/index.html"
    ]
}

private logDebug(String msg) {
    if (logEnable) log.debug "${device.name}: ${msg}"
}
