var CONFIG_PATH = "./config.json"

var PULSE_STEP_MS = 5
var ACCEL_MIN = 50
var ACCEL_MAX = 5000
var EST_ALPHA = 0.7
var EST_BETA = 0.35
var DEFAULT_OVERHEAD_MS = 30
var HIDDEN_WINDOW_POSITION = -500

// ============================================================
// Helpers
// ============================================================
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

function pixelHexColorInRgbRange(pixel, range, threshold) {
    var r = (pixel >> 16) & 0xff
    var g = (pixel >> 8) & 0xff
    var b = pixel & 0xff
    var rInRange = range[0] - threshold <= r && r <= range[0] + threshold
    var gInRange = range[1] - threshold <= g && g <= range[1] + threshold
    var bInRange = range[2] - threshold <= b && b <= range[2] + threshold
    return rInRange && gInRange && bInRange
}

// ============================================================
// Config
// ============================================================
var REQUIRED_CONTROL_KEYS = [
    "holdX", "holdY", "holdMinMs", "holdMaxMs", "releaseMinMs", "releaseMaxMs",
    "pressLatencyMs", "holdAccelPxPerSec2", "releaseAccelPxPerSec2", "velocityCostMs",
    "deadbandPx", "accelAdaptPercent", "holdPenaltyPerMs", "minigameLockMs"
]

function buildConfig(raw) {
    if (!raw || typeof raw !== "object" || !raw.moneyLevel || !raw.shake || !raw.minigame || !raw.control)
        fail("config.json is missing required sections")

    var i
    for (i = 0; i < REQUIRED_CONTROL_KEYS.length; i++) {
        var requiredKey = REQUIRED_CONTROL_KEYS[i]
        if (typeof raw.control[requiredKey] !== "number" || !isFinite(raw.control[requiredKey]))
            fail("config.json is missing control." + requiredKey + "; re-export it with config.html")
    }

    var minigame = raw.minigame
    var control = raw.control

    var mainScanY = minigame.mainScanY ? minigame.mainScanY : minigame.trackY + Math.floor(minigame.trackHeight / 2) + 1
    var secondaryScanY = minigame.secondaryScanY ? minigame.secondaryScanY : mainScanY - 7

    var built = {
        moneyLevel: {
            baseColor: raw.moneyLevel.baseColor,
            paths: raw.moneyLevel.paths,
            options: {
                region: raw.moneyLevel.region,
                threshold: raw.moneyLevel.threshold
            }
        },
        shake: {
            baseColor: raw.shake.baseColor,
            paths: raw.shake.paths,
            options: { threshold: raw.shake.threshold }
        },
        trackX: minigame.trackX,
        trackWidth: minigame.trackWidth,
        mainScanY: mainScanY,
        secondaryScanY: secondaryScanY,
        arrow: {
            rgb: hexToRgb(minigame.arrow.color),
            threshold: minigame.arrow.threshold,
            releasedMaxHitOffset: minigame.arrow.releasedMaxHitOffset,
            width: minigame.arrow.width
        },
        bar: {
            halfWidth: minigame.bar.width / 2,
            leftOffsetWhenReleased: minigame.bar.leftOffsetWhenReleased,
            leftOffsetWhenHolding: minigame.bar.leftOffsetWhenHolding
        },
        line: {
            rgb: hexToRgb(minigame.line.color),
            threshold: minigame.line.threshold
        },
        control: {
            holdX: control.holdX,
            holdY: control.holdY,
            holdMinMs: control.holdMinMs,
            holdMaxMs: control.holdMaxMs,
            releaseMinMs: control.releaseMinMs,
            releaseMaxMs: control.releaseMaxMs,
            pressLatencyMs: control.pressLatencyMs,
            holdAccelPxPerSec2: control.holdAccelPxPerSec2,
            releaseAccelPxPerSec2: control.releaseAccelPxPerSec2,
            velocityCostS: control.velocityCostMs / 1000,
            deadbandPx: control.deadbandPx,
            accelAdapt: control.accelAdaptPercent / 100,
            holdPenaltyPerMs: control.holdPenaltyPerMs,
            minigameLockMs: control.minigameLockMs
        }
    }

    if (Object.freeze) {
        var nested = ["moneyLevel", "shake", "arrow", "bar", "line", "control"]
        for (i = 0; i < nested.length; i++)
            Object.freeze(built[nested[i]])
        Object.freeze(built)
    }

    return built
}

var rawConfig
try {
    rawConfig = JSON.parse(files.read(CONFIG_PATH).replace(/^\uFEFF/, ""))
} catch (e) {
    fail("Could not read config.json next to main.js: " + e)
}

var cfg = buildConfig(rawConfig)

// ============================================================
// Detection
// ============================================================
function createDetector(cfg) {
    var mainScanRow = util.java.array("int", cfg.trackWidth)
    var secondaryScanRow = util.java.array("int", cfg.trackWidth)

    function isMinigame(img) {
        return images.findPointByColors(
            img,
            cfg.moneyLevel.baseColor,
            cfg.moneyLevel.paths,
            cfg.moneyLevel.options
        ) === null
    }

    function findShake(img) {
        return images.findPointByColors(
            img,
            cfg.shake.baseColor,
            cfg.shake.paths,
            cfg.shake.options
        )
    }

    function findLineAndBar(img) {
        var barCenterX = -1
        var lineCenterX = -1

        var bmp = img.bitmap
        bmp.getPixels(mainScanRow, 0, cfg.trackWidth, cfg.trackX, cfg.mainScanY, cfg.trackWidth, 1)
        bmp.getPixels(secondaryScanRow, 0, cfg.trackWidth, cfg.trackX, cfg.secondaryScanY, cfg.trackWidth, 1)

        for (var x = 0; x < cfg.trackWidth; x++) {
            if (lineCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], cfg.line.rgb, cfg.line.threshold))
                lineCenterX = x

            if (barCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], cfg.arrow.rgb, cfg.arrow.threshold))
                for (var sx = x; sx < x + cfg.arrow.width; sx++) {
                    if (pixelHexColorInRgbRange(secondaryScanRow[sx], cfg.arrow.rgb, cfg.arrow.threshold)) {
                        if (sx - x <= cfg.arrow.releasedMaxHitOffset)
                            // arrow on left
                            barCenterX = x - cfg.bar.leftOffsetWhenReleased + cfg.bar.halfWidth
                        else
                            barCenterX = x - cfg.bar.leftOffsetWhenHolding + cfg.bar.halfWidth
                        break
                    }
                }

            if (barCenterX !== -1 && lineCenterX !== -1)
                break
        }

        return { lineCenterX: lineCenterX, barCenterX: barCenterX }
    }

    return {
        isMinigame: isMinigame,
        findShake: findShake,
        findLineAndBar: findLineAndBar
    }
}

// ============================================================
// Motion estimator and pulse controller
// ============================================================
function createControlState(cfg) {
    return {
        estimatorValid: false,
        barX: 0,
        barV: 0,
        fishX: 0,
        fishV: 0,
        lastSampleMs: 0,
        lastAction: null,
        lastActionEndMs: Date.now(),
        overheadMs: DEFAULT_OVERHEAD_MS,
        accelHold: cfg.control.holdAccelPxPerSec2,
        accelRelease: cfg.control.releaseAccelPxPerSec2
    }
}

function createController(cfg) {
    var state = createControlState(cfg)

    function clamp(value, min, max) {
        return value < min ? min : (value > max ? max : value)
    }

    function adaptAccel(current, observed) {
        observed = clamp(observed, ACCEL_MIN, ACCEL_MAX)
        return current + (observed - current) * cfg.control.accelAdapt
    }

    function predictPulse(error, velocity, durationMs, isHold) {
        var latency = cfg.control.pressLatencyMs / 1000
        var overhead = state.overheadMs / 1000
        var duration = durationMs / 1000

        if (isHold) {
            error += velocity * latency - 0.5 * state.accelRelease * latency * latency
            velocity -= state.accelRelease * latency
            error += velocity * duration + 0.5 * state.accelHold * duration * duration
            velocity += state.accelHold * duration
            error += velocity * overhead - 0.5 * state.accelRelease * overhead * overhead
            velocity -= state.accelRelease * overhead
        } else {
            var total = duration + overhead
            error += velocity * total - 0.5 * state.accelRelease * total * total
            velocity -= state.accelRelease * total
        }
        return [error, velocity]
    }

    function considerPulse(best, error, velocity, durationMs, isHold) {
        var predicted = predictPulse(error, velocity, durationMs, isHold)
        var outside = Math.max(0, Math.abs(predicted[0]) - cfg.control.deadbandPx)
        var velocityCost = predicted[1] * cfg.control.velocityCostS
        var cost = outside * outside + velocityCost * velocityCost
        if (isHold)
            cost += cfg.control.holdPenaltyPerMs * durationMs
        if (!best || cost < best.cost)
            return { hold: isHold, durationMs: durationMs, cost: cost }
        return best
    }

    function choosePulse(error, velocity) {
        var best = null
        var t
        for (t = cfg.control.releaseMinMs; t <= cfg.control.releaseMaxMs; t += PULSE_STEP_MS)
            best = considerPulse(best, error, velocity, t, false)
        for (t = cfg.control.holdMinMs; t <= cfg.control.holdMaxMs; t += PULSE_STEP_MS)
            best = considerPulse(best, error, velocity, t, true)
        return best
    }

    function update(now, barPosition, fishPosition, adapt) {
        if (!state.estimatorValid) {
            state.barX = barPosition
            state.barV = 0
            state.fishX = fishPosition
            state.fishV = 0
            state.lastSampleMs = now
            state.lastAction = null
            state.estimatorValid = true
            return false
        }

        var dtms = now - state.lastSampleMs
        if (dtms <= 0)
            return false
        if (dtms > 500) {
            state.estimatorValid = false
            update(now, barPosition, fishPosition, adapt)
            return false
        }

        var dt = dtms / 1000
        var holdSeconds = 0
        if (state.lastAction && state.lastAction.hold)
            holdSeconds = Math.min(state.lastAction.durationMs, dtms) / 1000
        var releaseSeconds = dt - holdSeconds

        var averageAccel = state.accelHold * (holdSeconds / dt) - state.accelRelease * (releaseSeconds / dt)

        var previousBarV = state.barV
        var predictedBar = state.barX + state.barV * dt + 0.5 * averageAccel * dt * dt
        var predictedBarV = state.barV + averageAccel * dt
        var barResidual = barPosition - predictedBar
        state.barX = predictedBar + EST_ALPHA * barResidual
        state.barV = predictedBarV + (EST_BETA / dt) * barResidual

        var predictedFish = state.fishX + state.fishV * dt
        var fishResidual = fishPosition - predictedFish
        state.fishX = predictedFish + EST_ALPHA * fishResidual
        state.fishV = state.fishV + (EST_BETA / dt) * fishResidual

        if (adapt) {
            var dv = state.barV - previousBarV
            if (holdSeconds >= 0.03)
                state.accelHold = adaptAccel(state.accelHold, (dv + state.accelRelease * releaseSeconds) / holdSeconds)
            else if (holdSeconds === 0)
                state.accelRelease = adaptAccel(state.accelRelease, -dv / dt)
        }

        state.lastSampleMs = now
        return true
    }

    function noteOverhead(now) {
        if (!state.lastAction)
            return
        var measured = now - state.lastActionEndMs
        if (measured >= 0 && measured < 500)
            state.overheadMs = state.overheadMs * 0.8 + measured * 0.2
    }

    function leftWallImpactIsImminent(barX, barV) {
        var secondsBeforeImpact = barV < 0 ? (cfg.bar.halfWidth - barX) / barV : Infinity
        var horizon = (cfg.control.pressLatencyMs + 50) / 1000 // extra time for slack
        return secondsBeforeImpact < horizon
    }

    function step(now, barPosition, fishPosition) {
        if (!update(now, barPosition, fishPosition, true))
            return

        noteOverhead(now)

        var error = state.barX - state.fishX
        var velocity = state.barV - state.fishV
        var choice = choosePulse(error, velocity)
        if (leftWallImpactIsImminent(state.barX, state.barV))
            choice = { hold: false, durationMs: 0, cost: Infinity }
        if (!choice)
            return

        if (choice.hold)
            press(cfg.control.holdX, cfg.control.holdY, choice.durationMs)
        else if (choice.durationMs > 0)
            sleep(choice.durationMs)

        state.lastAction = { hold: choice.hold, durationMs: choice.durationMs }
        state.lastActionEndMs = Date.now()

        log((choice.hold ? "hold " : "release ") + choice.durationMs + "ms" +
            " err=" + Math.round(error) +
            " vel=" + Math.round(velocity) +
            " aH=" + Math.round(state.accelHold) +
            " aR=" + Math.round(state.accelRelease) +
            " overhead=" + Math.round(state.overheadMs))
    }

    function reset() {
        state = createControlState(cfg)
    }

    return {
        reset: reset,
        update: update,
        step: step,
        snapshot: function () { return state }
    }
}

// ============================================================
// On-screen markers
// ============================================================
function createMarkers(cfg) {
    var wins = []

    function make(glyph, color) {
        var win = floaty.rawWindow(
            <frame>
                <text id="glyph" text="" textColor="#000000" gravity="center" />
            </frame>
        )
        win.setTouchable(false)
        win.setPosition(HIDDEN_WINDOW_POSITION, HIDDEN_WINDOW_POSITION)
        var view = win.findView("glyph")
        view.setText(glyph)
        view.setTextColor(android.graphics.Color.parseColor(color))
        wins.push(win)
        return win
    }

    function move(win, x) {
        win.setPosition(x + cfg.trackX, cfg.mainScanY - 200)
    }

    function clear() {
        for (var i = 0; i < wins.length; i++)
            wins[i].setPosition(HIDDEN_WINDOW_POSITION, HIDDEN_WINDOW_POSITION)
    }

    function dispose() {
        for (var i = 0; i < wins.length; i++)
            wins[i].close()
        wins.length = 0
    }

    return {
        line: make("✛", "#00ff04"),
        bar: make("✛", "#0095ff"),
        estBar: make("e", "#38fc02"),
        estFish: make("l", "#38fc02"),
        move: move,
        clear: clear,
        dispose: dispose
    }
}

// ============================================================
// Pause/resume toggle
// ============================================================
function createToggle() {
    var win = floaty.rawWindow(
        <frame>
            <text id="toggle" text="OFF" w="60" h="38" textSize="12sp" textColor="#ffff8a80" bg="#cc263238" gravity="center" />
        </frame>
    )
    win.setTouchable(true)

    var view = win.findView("toggle")
    var running = false
    var downRawX = 0
    var downRawY = 0
    var winX = 0
    var winY = 0
    var dragged = false

    ui.run(function () {
        view.setOnTouchListener(function (v, event) {
            var action = event.getAction()
            if (action === event.ACTION_DOWN) {
                downRawX = event.getRawX()
                downRawY = event.getRawY()
                winX = win.getX()
                winY = win.getY()
                dragged = false
            } else if (action === event.ACTION_MOVE) {
                var dx = event.getRawX() - downRawX
                var dy = event.getRawY() - downRawY
                if (!dragged && Math.abs(dx) + Math.abs(dy) > 12)
                    dragged = true
                if (dragged)
                    win.setPosition(Math.round(winX + dx), Math.round(winY + dy))
            } else if (action === event.ACTION_UP) {
                if (!dragged) {
                    running = !running
                    view.setText(running ? "ON" : "OFF")
                    view.setTextColor(android.graphics.Color.parseColor(running ? "#ff7cf57c" : "#ffff8a80"))
                }
            }
            return true
        })
    })

    return {
        isRunning: function () { return running },
        dispose: function () { win.close() }
    }
}

// ============================================================
// Main loop
// ============================================================
function run(cfg, detector, controller, markers, toggle) {
    var minigameStartedAt = 0
    var isNewMinigame = true

    while (true) {
        if (!toggle.isRunning()) {
            sleep(100)
            continue
        }

        var img = captureScreen()

        try {
            if (!detector.isMinigame(img)) {
                isNewMinigame = true

                var shake = detector.findShake(img)
                if (shake)
                    click(shake.x, shake.y)
                sleep(30)
            } else {
                if (isNewMinigame) {
                    isNewMinigame = false
                    minigameStartedAt = Date.now()
                    controller.reset()
                }

                var now = Date.now()
                var positions = detector.findLineAndBar(img)
                if (positions.lineCenterX !== -1 && positions.barCenterX !== -1) {
                    markers.move(markers.line, positions.lineCenterX)
                    markers.move(markers.bar, positions.barCenterX)
                    // markers.move(markers.estBar, controller.snapshot().barX)
                    // markers.move(markers.estFish, controller.snapshot().fishX)

                    if (now - minigameStartedAt < cfg.control.minigameLockMs)
                        controller.update(now, positions.barCenterX, positions.lineCenterX, false)
                    else
                        controller.step(now, positions.barCenterX, positions.lineCenterX)
                }
            }
        }
        finally {
            img.recycle()
        }
    }
}

// ============================================================
// Script starts
// ============================================================
if (!floaty.hasPermission()) {
    toasterror("Grant floating window permission, then rerun")
    floaty.requestPermission()
    exit()
}

if (!requestScreenCapture(true)) {
    toasterror("Screen capture permission denied.")
    exit()
}

console.show()

var markers = createMarkers(cfg)
var controller = createController(cfg)
var detector = createDetector(cfg)
var toggle = createToggle()

run(cfg, detector, controller, markers, toggle)
