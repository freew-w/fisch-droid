var CONFIG_PATH = "./config.json"

function fail(message) {
    toasterror(message)
    exit()
}

function hexToRgb(hex) {
    var match = /^#?([0-9a-fA-F]{6})$/.exec(hex)
    if (!match)
        fail("Invalid color in config.json: " + hex)
    var value = parseInt(match[1], 16)
    return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff]
}

var appConfig
try {
    appConfig = JSON.parse(files.read(CONFIG_PATH).replace(/^\uFEFF/, ""))
} catch (e) {
    fail("Could not read config.json next to main.js: " + e)
}

var deviceConfig = appConfig && typeof appConfig === "object" ? appConfig : null
if (!deviceConfig || !deviceConfig.moneyLevel || !deviceConfig.shake || !deviceConfig.minigame || !deviceConfig.control)
    fail("config.json is missing required sections")

var REQUIRED_CONTROL_KEYS = [
    "holdX", "holdY", "holdMinMs", "holdMaxMs", "releaseMinMs", "releaseMaxMs",
    "pressLatencyMs", "holdAccelPxPerSec2", "releaseAccelPxPerSec2", "velocityCostMs",
    "deadbandPx", "accelAdaptPercent", "holdPenaltyPerMs", "minigameLockMs"
]
for (var requiredKeyIndex = 0; requiredKeyIndex < REQUIRED_CONTROL_KEYS.length; requiredKeyIndex++) {
    var requiredKey = REQUIRED_CONTROL_KEYS[requiredKeyIndex]
    if (typeof deviceConfig.control[requiredKey] !== "number" || !isFinite(deviceConfig.control[requiredKey]))
        fail("config.json is missing control." + requiredKey + "; re-export it with config.html")
}

// ============================================================
// Detection config
// ============================================================
var MONEY_LEVEL_BASE_COLOR = deviceConfig.moneyLevel.baseColor
var MONEY_LEVEL_PATHS = deviceConfig.moneyLevel.paths
var MONEY_LEVEL_OPTIONS = {
    region: deviceConfig.moneyLevel.region,
    threshold: deviceConfig.moneyLevel.threshold
}

var SHAKE_BASE_COLOR = deviceConfig.shake.baseColor
var SHAKE_PATHS = deviceConfig.shake.paths
var SHAKE_OPTIONS = { threshold: deviceConfig.shake.threshold }

var BAR_X = deviceConfig.minigame.barX
var BAR_W = deviceConfig.minigame.barWidth

var MAIN_SCAN_Y = deviceConfig.minigame.mainScanY ? deviceConfig.minigame.mainScanY : deviceConfig.minigame.barY + Math.floor(deviceConfig.minigame.barHeight / 2) + 1
var SECONDARY_SCAN_Y = deviceConfig.minigame.secondaryScanY ? deviceConfig.minigame.secondaryScanY : MAIN_SCAN_Y - 7

var arrowRgb = hexToRgb(deviceConfig.minigame.arrow.color)
var ARROW_RELEASED_SECONDARY_SCAN_MAX_HIT_OFFSET = deviceConfig.minigame.arrow.releasedMaxHitOffset
var ARROW_W = deviceConfig.minigame.arrow.width
var ARROW_R = arrowRgb[0]
var ARROW_G = arrowRgb[1]
var ARROW_B = arrowRgb[2]
var ARROW_THRESHOLD = deviceConfig.minigame.arrow.threshold
var TARGET_ZONE_W = deviceConfig.minigame.targetZone.width
var TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_RELEASED = deviceConfig.minigame.targetZone.leftOffsetWhenReleased
var TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_HOLDING = deviceConfig.minigame.targetZone.leftOffsetWhenHolding

var indicatorRgb = hexToRgb(deviceConfig.minigame.indicator.color)
var INDICATOR_R = indicatorRgb[0]
var INDICATOR_G = indicatorRgb[1]
var INDICATOR_B = indicatorRgb[2]
var INDICATOR_THRESHOLD = deviceConfig.minigame.indicator.threshold

// ============================================================
// Control config
// ============================================================
var HOLD_X = deviceConfig.control.holdX
var HOLD_Y = deviceConfig.control.holdY

var HOLD_MIN_MS = deviceConfig.control.holdMinMs
var HOLD_MAX_MS = deviceConfig.control.holdMaxMs
var RELEASE_MIN_MS = deviceConfig.control.releaseMinMs
var RELEASE_MAX_MS = deviceConfig.control.releaseMaxMs
var PRESS_LATENCY_MS = deviceConfig.control.pressLatencyMs
var HOLD_ACCEL_PX_PER_SEC2 = deviceConfig.control.holdAccelPxPerSec2
var RELEASE_ACCEL_PX_PER_SEC2 = deviceConfig.control.releaseAccelPxPerSec2
var VELOCITY_COST_S = deviceConfig.control.velocityCostMs / 1000
var DEADBAND_PX = deviceConfig.control.deadbandPx
var ACCEL_ADAPT = deviceConfig.control.accelAdaptPercent / 100
var HOLD_PENALTY_PER_MS = deviceConfig.control.holdPenaltyPerMs
var MINIGAME_LOCK_MS = deviceConfig.control.minigameLockMs

var PULSE_STEP_MS = 5
var ACCEL_MIN = 50
var ACCEL_MAX = 5000
var EST_ALPHA = 0.7
var EST_BETA = 0.35
var DEFAULT_OVERHEAD_MS = 30

if (HOLD_MAX_MS < HOLD_MIN_MS)
    HOLD_MAX_MS = HOLD_MIN_MS
if (RELEASE_MAX_MS < RELEASE_MIN_MS)
    RELEASE_MAX_MS = RELEASE_MIN_MS
if (HOLD_MIN_MS < 1)
    HOLD_MIN_MS = 1
if (RELEASE_MIN_MS < 0)
    RELEASE_MIN_MS = 0

// ============================================================
// Helper functions
// ============================================================
function colorInRange(colorr, colorg, colorb, ranger, rangeg, rangeb, threshold) {
    var rInRange = ranger - threshold <= colorr && colorr <= ranger + threshold
    var gInRange = rangeg - threshold <= colorg && colorg <= rangeg + threshold
    var bInRange = rangeb - threshold <= colorb && colorb <= rangeb + threshold
    return rInRange && gInRange && bInRange
}

function mark(win, x) {
    if (x < 0)
        return
    win.setPosition(x + BAR_X, MAIN_SCAN_Y - 200)
}

function clamp(value, min, max) {
    return value < min ? min : (value > max ? max : value)
}

// ============================================================
// Motion estimator and pulse controller
// ============================================================
var estimatorValid = false
var estimatedBarX = 0
var estimatedBarV = 0
var estimatedFishX = 0
var estimatedFishV = 0
var lastSampleMs = 0
var lastAction = null
var lastActionEndMs = 0
var overheadMs = DEFAULT_OVERHEAD_MS
var accelHold = HOLD_ACCEL_PX_PER_SEC2
var accelRelease = RELEASE_ACCEL_PX_PER_SEC2

function resetEstimator() {
    estimatorValid = false
    estimatedBarX = 0
    estimatedBarV = 0
    estimatedFishX = 0
    estimatedFishV = 0
    lastSampleMs = 0
    lastAction = null
    lastActionEndMs = Date.now()
    overheadMs = DEFAULT_OVERHEAD_MS
    accelHold = HOLD_ACCEL_PX_PER_SEC2
    accelRelease = RELEASE_ACCEL_PX_PER_SEC2
}

function adaptAccel(current, observed) {
    if (!isFinite(observed))
        return current
    observed = clamp(observed, ACCEL_MIN, ACCEL_MAX)
    return current + (observed - current) * ACCEL_ADAPT
}

function predictPulse(error, velocity, durationMs, isHold) {
    var latency = PRESS_LATENCY_MS / 1000
    var overhead = overheadMs / 1000
    var duration = durationMs / 1000
    var e = error
    var v = velocity

    if (isHold) {
        e += v * latency - 0.5 * accelRelease * latency * latency
        v -= accelRelease * latency
        e += v * duration + 0.5 * accelHold * duration * duration
        v += accelHold * duration
        e += v * overhead - 0.5 * accelRelease * overhead * overhead
        v -= accelRelease * overhead
    } else {
        var total = duration + overhead
        e += v * total - 0.5 * accelRelease * total * total
        v -= accelRelease * total
    }
    return [e, v]
}

function considerPulse(best, error, velocity, durationMs, isHold) {
    var predicted = predictPulse(error, velocity, durationMs, isHold)
    var outside = Math.max(0, Math.abs(predicted[0]) - DEADBAND_PX)
    var velocityCost = predicted[1] * VELOCITY_COST_S
    var cost = outside * outside + velocityCost * velocityCost
    if (isHold)
        cost += HOLD_PENALTY_PER_MS * durationMs
    if (!best || cost < best.cost)
        return { hold: isHold, durationMs: durationMs, cost: cost }
    return best
}

function choosePulse(error, velocity) {
    var best = null
    var t
    for (t = RELEASE_MIN_MS; t <= RELEASE_MAX_MS; t += PULSE_STEP_MS)
        best = considerPulse(best, error, velocity, t, false)
    for (t = HOLD_MIN_MS; t <= HOLD_MAX_MS; t += PULSE_STEP_MS)
        best = considerPulse(best, error, velocity, t, true)
    if (!best)
        best = considerPulse(null, error, velocity, Math.max(1, HOLD_MIN_MS), true)
    return best
}

function updateEstimator(now, barPosition, fishPosition, adapt) {
    if (!estimatorValid) {
        estimatedBarX = barPosition
        estimatedBarV = 0
        estimatedFishX = fishPosition
        estimatedFishV = 0
        lastSampleMs = now
        lastAction = null
        estimatorValid = true
        return false
    }

    var dtms = now - lastSampleMs
    if (dtms <= 0)
        return false
    if (dtms > 500) {
        estimatorValid = false
        updateEstimator(now, barPosition, fishPosition, adapt)
        return false
    }

    var dt = dtms / 1000
    var holdSeconds = 0
    if (lastAction && lastAction.hold)
        holdSeconds = Math.min(lastAction.durationMs, dtms) / 1000
    var releaseSeconds = dt - holdSeconds

    var averageAccel = accelHold * (holdSeconds / dt) - accelRelease * (releaseSeconds / dt)

    var previousBarV = estimatedBarV
    var predictedBar = estimatedBarX + estimatedBarV * dt + 0.5 * averageAccel * dt * dt
    var predictedBarV = estimatedBarV + averageAccel * dt
    var barResidual = barPosition - predictedBar
    estimatedBarX = predictedBar + EST_ALPHA * barResidual
    estimatedBarV = predictedBarV + (EST_BETA / dt) * barResidual

    var predictedFish = estimatedFishX + estimatedFishV * dt
    var fishResidual = fishPosition - predictedFish
    estimatedFishX = predictedFish + EST_ALPHA * fishResidual
    estimatedFishV = estimatedFishV + (EST_BETA / dt) * fishResidual

    if (adapt) {
        var dv = estimatedBarV - previousBarV
        if (holdSeconds >= 0.03) {
            accelHold = adaptAccel(accelHold, (dv + accelRelease * releaseSeconds) / holdSeconds)
        } else if (holdSeconds === 0) {
            accelRelease = adaptAccel(accelRelease, -dv / dt)
        }
    }

    lastSampleMs = now
    return true
}

function noteOverhead(now) {
    if (!lastAction)
        return
    var measured = now - lastActionEndMs
    if (measured >= 0 && measured < 500)
        overheadMs = overheadMs * 0.8 + measured * 0.2
}

function runControl(now, barPosition, fishPosition) {
    if (!updateEstimator(now, barPosition, fishPosition, true))
        return

    noteOverhead(now)

    var error = estimatedBarX - estimatedFishX
    var velocity = estimatedBarV - estimatedFishV
    var choice = choosePulse(error, velocity)
    if (!choice)
        return

    if (choice.hold)
        press(HOLD_X, HOLD_Y, choice.durationMs)
    else if (choice.durationMs > 0)
        sleep(choice.durationMs)

    lastAction = { hold: choice.hold, durationMs: choice.durationMs }
    lastActionEndMs = Date.now()

    log((choice.hold ? "hold " : "release ") + choice.durationMs + "ms" +
        " err=" + Math.round(error) +
        " vel=" + Math.round(velocity) +
        " aH=" + Math.round(accelHold) +
        " aR=" + Math.round(accelRelease) +
        " overhead=" + Math.round(overheadMs))
}

// ============================================================
// Script starts
// ============================================================
if (!floaty.hasPermission()) {
    toasterror("Grant floating window permission, then rerun")
    floaty.requestPermission()
    exit()
}

var winIndicator = floaty.rawWindow(
    <frame>
        <text text="✛" textColor="#00ff04" gravity="center" />
    </frame>
)
winIndicator.setTouchable(false)
winIndicator.setPosition(-500, -500)

var winTargetZoneLeft = floaty.rawWindow(
    <frame>
        <text text="✛" textColor="#ff0000" gravity="center" />
    </frame>
)
winTargetZoneLeft.setTouchable(false)
winTargetZoneLeft.setPosition(-500, -500)

var winTargetZoneMiddle = floaty.rawWindow(
    <frame>
        <text text="✛" textColor="#0095ff" gravity="center" />
    </frame>
)
winTargetZoneMiddle.setTouchable(false)
winTargetZoneMiddle.setPosition(-500, -500)

if (!requestScreenCapture(true)) {
    toasterror("Screen capture permission denied.")
    exit()
}

console.show()

// ============================================================
// Main loop
// ============================================================
var mainScanRow = util.java.array("int", BAR_W)
var secondaryScanRow = util.java.array("int", BAR_W)
var minigameStartedAt = 0
var isNewMinigame = true

while (true) {
    // ============================================================
    // Detection
    // ============================================================
    var img = captureScreen()
    var bmp = img.bitmap
    bmp.getPixels(mainScanRow, 0, BAR_W, BAR_X, MAIN_SCAN_Y, BAR_W, 1)
    bmp.getPixels(secondaryScanRow, 0, BAR_W, BAR_X, SECONDARY_SCAN_Y, BAR_W, 1)

    try {
        var isInFishingMinigame = images.findPointByColors(img, MONEY_LEVEL_BASE_COLOR, MONEY_LEVEL_PATHS, MONEY_LEVEL_OPTIONS) === null
        if (!isInFishingMinigame) {
            isNewMinigame = true
            // Shake
            var shake = images.findPointByColors(img, SHAKE_BASE_COLOR, SHAKE_PATHS, SHAKE_OPTIONS)
            if (shake)
                click(shake.x, shake.y)
            sleep(30)
        } else {
            if (isNewMinigame) {
                isNewMinigame = false
                minigameStartedAt = Date.now()
                resetEstimator()
            }

            var indicatorX = -1
            var targetZoneLeftX = -1
            for (var x = 0; x < BAR_W; x++) {
                var p = mainScanRow[x]
                var r = (p >> 16) & 0xff
                var g = (p >> 8) & 0xff
                var b = p & 0xff

                if (indicatorX === -1 && colorInRange(r, g, b, INDICATOR_R, INDICATOR_G, INDICATOR_B, INDICATOR_THRESHOLD))
                    indicatorX = x

                if (targetZoneLeftX === -1 && colorInRange(r, g, b, ARROW_R, ARROW_G, ARROW_B, ARROW_THRESHOLD))
                    for (var sx = x; sx < x + ARROW_W; sx++) {
                        var sp = secondaryScanRow[sx]
                        var sr = (sp >> 16) & 0xff
                        var sg = (sp >> 8) & 0xff
                        var sb = sp & 0xff
                        if (colorInRange(sr, sg, sb, ARROW_R, ARROW_G, ARROW_B, ARROW_THRESHOLD)) {
                            if (sx - x <= ARROW_RELEASED_SECONDARY_SCAN_MAX_HIT_OFFSET)
                                // arrow on left
                                targetZoneLeftX = x - TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_RELEASED
                            else
                                targetZoneLeftX = x - TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_HOLDING
                            break
                        }
                    }

                if (indicatorX !== -1 && targetZoneLeftX !== -1)
                    break
            }

            var now = Date.now()
            if (indicatorX !== -1 && targetZoneLeftX !== -1) {
                var targetZoneCenterX = targetZoneLeftX + TARGET_ZONE_W / 2

                // ============================================================
                // Marking
                // ============================================================
                mark(winIndicator, indicatorX)
                mark(winTargetZoneLeft, targetZoneLeftX)
                mark(winTargetZoneMiddle, targetZoneCenterX)

                // ============================================================
                // Control
                // ============================================================
                if (now - minigameStartedAt < MINIGAME_LOCK_MS) {
                    updateEstimator(now, targetZoneCenterX, indicatorX, false)
                } else {
                    runControl(now, targetZoneCenterX, indicatorX)
                }
            }
        }
    }
    finally {
        img.recycle()
    }
}