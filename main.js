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

if (!appConfig || typeof appConfig !== "object" || !appConfig.moneyLevel || !appConfig.shake || !appConfig.minigame || !appConfig.control)
    fail("config.json is missing required sections")

var REQUIRED_CONTROL_KEYS = [
    "holdX", "holdY", "holdMinMs", "holdMaxMs", "releaseMinMs", "releaseMaxMs",
    "pressLatencyMs", "holdAccelPxPerSec2", "releaseAccelPxPerSec2", "velocityCostMs",
    "deadbandPx", "accelAdaptPercent", "holdPenaltyPerMs", "minigameLockMs"
]
for (var requiredKeyIndex = 0; requiredKeyIndex < REQUIRED_CONTROL_KEYS.length; requiredKeyIndex++) {
    var requiredKey = REQUIRED_CONTROL_KEYS[requiredKeyIndex]
    if (typeof appConfig.control[requiredKey] !== "number" || !isFinite(appConfig.control[requiredKey]))
        fail("config.json is missing control." + requiredKey + "; re-export it with config.html")
}

// ============================================================
// Detection config
// ============================================================
var MONEY_LEVEL_BASE_COLOR = appConfig.moneyLevel.baseColor
var MONEY_LEVEL_PATHS = appConfig.moneyLevel.paths
var MONEY_LEVEL_OPTIONS = {
    region: appConfig.moneyLevel.region,
    threshold: appConfig.moneyLevel.threshold
}

var SHAKE_BASE_COLOR = appConfig.shake.baseColor
var SHAKE_PATHS = appConfig.shake.paths
var SHAKE_OPTIONS = { threshold: appConfig.shake.threshold }

var TRACK_X = appConfig.minigame.trackX
var TRACK_W = appConfig.minigame.trackWidth

var MAIN_SCAN_Y = appConfig.minigame.mainScanY ? appConfig.minigame.mainScanY : appConfig.minigame.trackY + Math.floor(appConfig.minigame.trackHeight / 2) + 1
var SECONDARY_SCAN_Y = appConfig.minigame.secondaryScanY ? appConfig.minigame.secondaryScanY : MAIN_SCAN_Y - 7

var ARROW_RGB = hexToRgb(appConfig.minigame.arrow.color)
var ARROW_THRESHOLD = appConfig.minigame.arrow.threshold
var ARROW_RELEASED_SECONDARY_SCAN_MAX_HIT_OFFSET = appConfig.minigame.arrow.releasedMaxHitOffset
var ARROW_W = appConfig.minigame.arrow.width

var BAR_W_HALF = appConfig.minigame.bar.width / 2
var BAR_LEFT_TO_ARROW_OFFSET_WHEN_RELEASED = appConfig.minigame.bar.leftOffsetWhenReleased
var BAR_LEFT_TO_ARROW_OFFSET_WHEN_HOLDING = appConfig.minigame.bar.leftOffsetWhenHolding

var LINE_RGB = hexToRgb(appConfig.minigame.line.color)
var LINE_THRESHOLD = appConfig.minigame.line.threshold

// ============================================================
// Control config
// ============================================================
var HOLD_X = appConfig.control.holdX
var HOLD_Y = appConfig.control.holdY

var HOLD_MIN_MS = appConfig.control.holdMinMs
var HOLD_MAX_MS = appConfig.control.holdMaxMs
var RELEASE_MIN_MS = appConfig.control.releaseMinMs
var RELEASE_MAX_MS = appConfig.control.releaseMaxMs
var PRESS_LATENCY_MS = appConfig.control.pressLatencyMs
var HOLD_ACCEL_PX_PER_SEC2 = appConfig.control.holdAccelPxPerSec2
var RELEASE_ACCEL_PX_PER_SEC2 = appConfig.control.releaseAccelPxPerSec2
var VELOCITY_COST_S = appConfig.control.velocityCostMs / 1000
var DEADBAND_PX = appConfig.control.deadbandPx
var ACCEL_ADAPT = appConfig.control.accelAdaptPercent / 100
var HOLD_PENALTY_PER_MS = appConfig.control.holdPenaltyPerMs
var MINIGAME_LOCK_MS = appConfig.control.minigameLockMs

var PULSE_STEP_MS = 5
var ACCEL_MIN = 50
var ACCEL_MAX = 5000
var EST_ALPHA = 0.7
var EST_BETA = 0.35
var DEFAULT_OVERHEAD_MS = 30

// ============================================================
// Helper functions
// ============================================================
function pixelHexColorInRgbRange(pixel, range, threshold) {
    var r = (pixel >> 16) & 0xff
    var g = (pixel >> 8) & 0xff
    var b = pixel & 0xff
    var rInRange = range[0] - threshold <= r && r <= range[0] + threshold
    var gInRange = range[1] - threshold <= g && g <= range[1] + threshold
    var bInRange = range[2] - threshold <= b && b <= range[2] + threshold
    return rInRange && gInRange && bInRange
}

function mark(win, x) {
    win.setPosition(x + TRACK_X, MAIN_SCAN_Y - 200)
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
    observed = clamp(observed, ACCEL_MIN, ACCEL_MAX)
    return current + (observed - current) * ACCEL_ADAPT
}

function predictPulse(error, velocity, durationMs, isHold) {
    var latency = PRESS_LATENCY_MS / 1000
    var overhead = overheadMs / 1000
    var duration = durationMs / 1000

    if (isHold) {
        error += velocity * latency - 0.5 * accelRelease * latency * latency
        velocity -= accelRelease * latency
        error += velocity * duration + 0.5 * accelHold * duration * duration
        velocity += accelHold * duration
        error += velocity * overhead - 0.5 * accelRelease * overhead * overhead
        velocity -= accelRelease * overhead
    } else {
        var total = duration + overhead
        error += velocity * total - 0.5 * accelRelease * total * total
        velocity -= accelRelease * total
    }
    return [error, velocity]
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

    // mark(winEstBar, estimatedBarX)
    // mark(winEstFish, estimatedFishX)

    if (adapt) {
        var dv = estimatedBarV - previousBarV
        if (holdSeconds >= 0.03)
            accelHold = adaptAccel(accelHold, (dv + accelRelease * releaseSeconds) / holdSeconds)
        else if (holdSeconds === 0)
            accelRelease = adaptAccel(accelRelease, -dv / dt)
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

function leftWallImpactIsImminent(barX, barV) {
    var secondsBeforeImpact = barV < 0 ? (BAR_W_HALF - barX) / (barV) : Infinity
    var horizon = (PRESS_LATENCY_MS + 50) / 1000 // extra time for slack
    return secondsBeforeImpact < horizon
}

function runControl(now, barPosition, fishPosition) {
    if (!updateEstimator(now, barPosition, fishPosition, true))
        return

    noteOverhead(now)

    var error = estimatedBarX - estimatedFishX
    var velocity = estimatedBarV - estimatedFishV
    var choice = choosePulse(error, velocity)
    if (leftWallImpactIsImminent(estimatedBarX, estimatedBarV))
        choice = { hold: false, durationMs: 0, cost: Infinity }
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
// Detection
// ============================================================
function findLineAndBar(img) {
    var mainScanRow = util.java.array("int", TRACK_W)
    var secondaryScanRow = util.java.array("int", TRACK_W)
    var barCenterX = -1
    var lineCenterX = -1

    var bmp = img.bitmap
    bmp.getPixels(mainScanRow, 0, TRACK_W, TRACK_X, MAIN_SCAN_Y, TRACK_W, 1)
    bmp.getPixels(secondaryScanRow, 0, TRACK_W, TRACK_X, SECONDARY_SCAN_Y, TRACK_W, 1)

    for (var x = 0; x < TRACK_W; x++) {
        if (lineCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], LINE_RGB, LINE_THRESHOLD))
            lineCenterX = x

        if (barCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], ARROW_RGB, ARROW_THRESHOLD))
            for (var sx = x; sx < x + ARROW_W; sx++) {
                if (pixelHexColorInRgbRange(secondaryScanRow[sx], ARROW_RGB, ARROW_THRESHOLD)) {
                    if (sx - x <= ARROW_RELEASED_SECONDARY_SCAN_MAX_HIT_OFFSET)
                        // arrow on left
                        barCenterX = x - BAR_LEFT_TO_ARROW_OFFSET_WHEN_RELEASED + BAR_W_HALF
                    else
                        barCenterX = x - BAR_LEFT_TO_ARROW_OFFSET_WHEN_HOLDING + BAR_W_HALF
                    break
                }
            }

        if (barCenterX !== -1 && lineCenterX !== -1)
            break
    }

    return { lineCenterX: lineCenterX, barCenterX: barCenterX }
}

// ============================================================
// Script starts
// ============================================================
if (!floaty.hasPermission()) {
    toasterror("Grant floating window permission, then rerun")
    floaty.requestPermission()
    exit()
}

var winLine = floaty.rawWindow(
    <frame>
        <text text="✛" textColor="#00ff04" gravity="center" />
    </frame>
)
winLine.setTouchable(false)
winLine.setPosition(-500, -500)

var winBar = floaty.rawWindow(
    <frame>
        <text text="✛" textColor="#0095ff" gravity="center" />
    </frame>
)
winBar.setTouchable(false)
winBar.setPosition(-500, -500)

var winEstBar = floaty.rawWindow(
    <frame>
        <text text="e" textColor="#38fc02" gravity="center" />
    </frame>
)
winEstBar.setTouchable(false)
winEstBar.setPosition(-500, -500)

var winEstFish = floaty.rawWindow(
    <frame>
        <text text="l" textColor="#38fc02" gravity="center" />
    </frame>
)
winEstFish.setTouchable(false)
winEstFish.setPosition(-500, -500)

if (!requestScreenCapture(true)) {
    toasterror("Screen capture permission denied.")
    exit()
}

console.show()

// ============================================================
// Main loop
// ============================================================
var minigameStartedAt = 0
var isNewMinigame = true

while (true) {
    // ============================================================
    // Detection
    // ============================================================
    var img = captureScreen()

    try {
        var isInFishingMinigame = images.findPointByColors(img, MONEY_LEVEL_BASE_COLOR, MONEY_LEVEL_PATHS, MONEY_LEVEL_OPTIONS) === null
        if (!isInFishingMinigame) {
            isNewMinigame = true

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

            var now = Date.now()
            var positions = findLineAndBar(img)
            if (positions.lineCenterX !== -1 && positions.barCenterX !== -1) {
                // ============================================================
                // Marking
                // ============================================================
                mark(winLine, positions.lineCenterX)
                mark(winBar, positions.barCenterX)

                // ============================================================
                // Control
                // ============================================================
                if (now - minigameStartedAt < MINIGAME_LOCK_MS)
                    updateEstimator(now, positions.barCenterX, positions.lineCenterX, false)
                else
                    runControl(now, positions.barCenterX, positions.lineCenterX)
            }
        }
    }
    finally {
        img.recycle()
    }
}