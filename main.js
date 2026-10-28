var CONFIG_PATH = "./config.json"

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
function createConfig() {
    var SCHEMA = {
        "debug.showMarkers": { type: "boolean" },

        "cast.reCastAfterNoDetectionForMs": { type: "number", min: 0 },
        "cast.holdMs": { type: "number", min: 0 },

        "coins.baseColor": { type: "hexColor" },
        "coins.paths": { type: "array" },
        "coins.region": { type: "numberArray", len: 4 },
        "coins.threshold": { type: "number", min: 0, max: 255 },

        "shake.baseColor": { type: "hexColor" },
        "shake.paths": { type: "array" },
        "shake.threshold": { type: "number", min: 0, max: 255 },

        "minigame.trackX": { type: "number", min: 0 },
        "minigame.trackY": { type: "number", min: 0 },
        "minigame.trackWidth": { type: "number", min: 1 },
        "minigame.trackHeight": { type: "number", min: 1 },
        "minigame.mainScanY": { type: "number", optional: true },
        "minigame.secondaryScanY": { type: "number", optional: true },

        "minigame.arrow.color": { type: "hexColor" },
        "minigame.arrow.threshold": { type: "number", min: 0, max: 255 },
        "minigame.arrow.releasedMaxHitOffset": { type: "number", min: 0 },
        "minigame.arrow.width": { type: "number", min: 1 },

        "minigame.bar.width": { type: "number", min: 1 },
        "minigame.bar.leftOffsetWhenReleased": { type: "number" },
        "minigame.bar.leftOffsetWhenHolding": { type: "number" },

        "minigame.line.color": { type: "hexColor" },
        "minigame.line.threshold": { type: "number", min: 0, max: 255 },

        "control.controller.holdX": { type: "number", optional: true },
        "control.controller.holdY": { type: "number", optional: true },
        "control.controller.holdMinMs": { type: "number", min: 0 },
        "control.controller.holdMaxMs": { type: "number", min: 0 },
        "control.controller.releaseMinMs": { type: "number", min: 0 },
        "control.controller.releaseMaxMs": { type: "number", min: 0 },
        "control.controller.pressLatencyMs": { type: "number", min: 0 },
        "control.controller.velocityCostMs": { type: "number", min: 0 },
        "control.controller.deadbandPx": { type: "number", min: 0 },
        "control.controller.holdPenaltyPerMs": { type: "number", min: 0 },
        "control.controller.minigameLockMs": { type: "number", min: 0 },
        "control.controller.bouncePenaltyPerImpact": { type: "number", min: 0 },
        "control.controller.contactPenalty": { type: "number", min: 0 },

        "control.estimator.holdAccelPxPerSec2": { type: "number", min: 1 },
        "control.estimator.releaseAccelPxPerSec2": { type: "number", min: 1 },
        "control.estimator.accelAdaptPercent": { type: "number", min: 0, max: 100 },
        "control.estimator.defaultOverheadMs": { type: "number", min: 0, max: 1000 },
        "control.estimator.pulseStepMs": { type: "number", min: 0.001 },
        "control.estimator.simStepMs": { type: "number", min: 0.001 },
        "control.estimator.wallBouncePercent": { type: "number", min: 0, max: 100 },
        "control.estimator.minWallBounceSpeed": { type: "number", min: 0 },
        "control.estimator.minBrakeSpeed": { type: "number", min: 0 },
        "control.estimator.minAccel": { type: "number", min: 0 },
        "control.estimator.maxAccel": { type: "number", min: 0 },
        "control.estimator.estAlpha": { type: "number", min: 0, max: 1 },
        "control.estimator.estBeta": { type: "number", min: 0, max: 1 },
        "control.estimator.postActionBrakeMs": { type: "number", min: 1, max: 500 }
    }

    function getValue(obj, path) {
        var parts = path.split(".")
        var cur = obj
        for (var i = 0; i < parts.length; i++) {
            if (cur === null || cur === undefined)
                return undefined
            cur = cur[parts[i]]
        }
        return cur
    }

    function typeProblem(value, spec) {
        if (value === undefined || value === null)
            return spec.optional ? null : "missing"
        if (spec.type === "number") {
            if (typeof value !== "number" || !isFinite(value))
                return "expected a finite number, got " + JSON.stringify(value)
            if (spec.min !== undefined && value < spec.min)
                return value + " is below min " + spec.min
            if (spec.max !== undefined && value > spec.max)
                return value + " is above max " + spec.max
            return null
        }
        if (spec.type === "boolean") {
            if (typeof value !== "boolean")
                return "expected a boolean, got " + JSON.stringify(value)
            return null
        }
        if (spec.type === "hexColor") {
            if (typeof value !== "string" || !/^#[0-9a-fA-F]{6}$/.test(value))
                return "expected #rrggbb, got " + JSON.stringify(value)
            return null
        }
        if (spec.type === "array") {
            if (!(value instanceof Array))
                return "expected an array"
            return null
        }
        if (spec.type === "numberArray") {
            if (!(value instanceof Array) || value.length !== spec.len)
                return "expected an array of " + spec.len + " numbers"
            for (var j = 0; j < value.length; j++)
                if (typeof value[j] !== "number" || !isFinite(value[j]))
                    return "element " + j + " is not a finite number"
            return null
        }
        return "unknown schema type " + spec.type
    }

    function validateConfig(raw) {
        var problems = []
        for (var path in SCHEMA) {
            var problem = typeProblem(getValue(raw, path), SCHEMA[path])
            if (problem)
                problems.push(path + ": " + problem)
        }
        if (problems.length)
            fail("config.json has " + problems.length + " problem(s):\n  " +
                problems.join("\n  "))
    }

    function buildConfig(raw) {
        validateConfig(raw)

        var m = raw.minigame
        var c = raw.control.controller

        var mainScanY = m.mainScanY
            ? m.mainScanY
            : m.trackY + Math.floor(m.trackHeight / 2) + 1
        var secondaryScanY = m.secondaryScanY
            ? m.secondaryScanY
            : mainScanY - 7

        raw.derived = {
            mainScanY: mainScanY,
            secondaryScanY: secondaryScanY,
            holdX: c.holdX ? c.holdX : device.width / 2,
            holdY: c.holdY ? c.holdY : device.height / 2,
            arrowRgb: hexToRgb(m.arrow.color),
            lineRgb: hexToRgb(m.line.color),
            barHalfWidth: m.bar.width / 2,
        }
        return raw
    }

    var rawConfig
    try {
        rawConfig = JSON.parse(files.read(CONFIG_PATH).replace(/^\uFEFF/, ""))
    } catch (e) {
        fail("Could not read config.json next to main.js: " + e)
    }

    return buildConfig(rawConfig)
}

// ============================================================
// Detection
// ============================================================
function createDetector(cfg) {
    var m = cfg.minigame

    var mainScanRow = util.java.array("int", m.trackWidth)
    var secondaryScanRow = util.java.array("int", m.trackWidth)

    function isMinigame(img) {
        return images.findPointByColors(
            img,
            cfg.coins.baseColor,
            cfg.coins.paths,
            { region: cfg.coins.region, threshold: cfg.coins.threshold }
        ) === null
    }

    function findShake(img) {
        return images.findPointByColors(
            img,
            cfg.shake.baseColor,
            cfg.shake.paths,
            { threshold: cfg.shake.threshold }
        )
    }

    function findLineAndBar(img) {
        var barCenterX = -1
        var lineCenterX = -1

        var bmp = img.bitmap
        bmp.getPixels(mainScanRow, 0, m.trackWidth, m.trackX, cfg.derived.mainScanY, m.trackWidth, 1)
        bmp.getPixels(secondaryScanRow, 0, m.trackWidth, m.trackX, cfg.derived.secondaryScanY, m.trackWidth, 1)

        for (var x = 0; x < m.trackWidth; x++) {
            if (lineCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], cfg.derived.lineRgb, m.line.threshold))
                lineCenterX = x

            if (barCenterX === -1 && pixelHexColorInRgbRange(mainScanRow[x], cfg.derived.arrowRgb, m.arrow.threshold))
                for (var sx = x; sx < x + m.arrow.width; sx++) {
                    if (pixelHexColorInRgbRange(secondaryScanRow[sx], cfg.derived.arrowRgb, m.arrow.threshold)) {
                        if (sx - x <= m.arrow.releasedMaxHitOffset)
                            // arrow on left
                            barCenterX = x - m.bar.leftOffsetWhenReleased + cfg.derived.barHalfWidth
                        else
                            barCenterX = x - m.bar.leftOffsetWhenHolding + cfg.derived.barHalfWidth
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
        overheadMs: cfg.control.estimator.defaultOverheadMs,
        accelHold: cfg.control.estimator.holdAccelPxPerSec2,
        accelRelease: cfg.control.estimator.releaseAccelPxPerSec2
    }
}

function createController(cfg) {
    var ctl = cfg.control.controller
    var est = cfg.control.estimator

    var pressLatencyS = ctl.pressLatencyMs / 1000
    var velocityCostS = ctl.velocityCostMs / 1000
    var accelAdapt = est.accelAdaptPercent / 100
    var simStepS = est.simStepMs / 1000
    var wallBounce = est.wallBouncePercent / 100
    var brakeS = est.postActionBrakeMs / 1000

    var state = createControlState(cfg)

    var minBarX = cfg.derived.barHalfWidth
    var maxBarX = cfg.minigame.trackWidth - cfg.derived.barHalfWidth

    function clamp(value, min, max) {
        return value < min ? min : (value > max ? max : value)
    }

    function adaptAccel(current, observed) {
        observed = clamp(observed, est.minAccel, est.maxAccel)
        return current + (observed - current) * accelAdapt
    }

    function advanceBar(bar, accel, seconds) {
        var remaining = seconds
        while (remaining > 0) {
            var dt = remaining < simStepS ? remaining : simStepS

            bar.x += bar.v * dt + 0.5 * accel * dt * dt
            bar.v += accel * dt

            if (bar.x <= minBarX) {
                bar.x = minBarX
                bar.contact = true
                if (bar.v < -est.minWallBounceSpeed) {
                    bar.v = -bar.v * wallBounce
                    bar.impacts++
                } else if (bar.v < 0) {
                    bar.v = 0
                }
            } else if (bar.x >= maxBarX) {
                bar.x = maxBarX
                bar.contact = true
                if (bar.v > est.minWallBounceSpeed) {
                    bar.v = -bar.v * wallBounce
                    bar.impacts++
                } else if (bar.v > 0) {
                    bar.v = 0
                }
            }

            remaining -= dt
        }
    }

    function advancePair(bar, fish, accel, seconds) {
        advanceBar(bar, accel, seconds)
        fish.x += fish.v * seconds
    }

    function brakePhase(bar, fish, seconds) {
        var remaining = seconds
        while (remaining > 0) {
            var relativeV = bar.v - fish.v
            var accel = 0
            if (relativeV < -est.minBrakeSpeed)
                accel = state.accelHold
            else if (relativeV > est.minBrakeSpeed)
                accel = -state.accelRelease

            if (accel === 0) {
                var coastStep = remaining < simStepS ? remaining : simStepS
                advancePair(bar, fish, 0, coastStep)
                remaining -= coastStep
                continue
            }

            var timeToZero = Math.abs(relativeV) / Math.abs(accel)
            var dt = remaining < timeToZero ? remaining : timeToZero
            if (dt < 0.000001)
                dt = remaining < simStepS ? remaining : simStepS
            advancePair(bar, fish, accel, dt)
            remaining -= dt
        }
    }

    function simulateCandidate(barX, barV, fishX, fishV, durationMs, isHold, horizon) {
        var bar = { x: barX, v: barV, impacts: 0, contact: false }
        var fish = { x: fishX, v: fishV }
        var overhead = state.overheadMs / 1000
        var duration = durationMs / 1000
        var elapsed = 0

        if (isHold) {
            advancePair(bar, fish, -state.accelRelease, pressLatencyS)
            elapsed += pressLatencyS
            advancePair(bar, fish, state.accelHold, duration)
            elapsed += duration
        } else {
            advancePair(bar, fish, -state.accelRelease, duration)
            elapsed += duration
        }

        advancePair(bar, fish, -state.accelRelease, overhead)
        elapsed += overhead

        var remaining = horizon - elapsed
        if (remaining > 0)
            brakePhase(bar, fish, remaining)

        return {
            error: bar.x - fish.x,
            velocity: bar.v - fish.v,
            impacts: bar.impacts,
            contact: bar.contact
        }
    }

    function considerCandidate(best, predicted, durationMs, isHold) {
        var outside = Math.max(0, Math.abs(predicted.error) - ctl.deadbandPx)
        var velocityCost = predicted.velocity * velocityCostS
        var cost = outside * outside + velocityCost * velocityCost
        cost += predicted.impacts * ctl.bouncePenaltyPerImpact
        if (predicted.contact)
            cost += ctl.contactPenalty
        if (isHold)
            cost += ctl.holdPenaltyPerMs * durationMs
        if (!best || cost < best.cost)
            return { hold: isHold, durationMs: durationMs, cost: cost, impacts: predicted.impacts }
        return best
    }

    function choosePulse() {
        var overheadS = state.overheadMs / 1000

        var best = null
        var t
        for (t = ctl.releaseMinMs; t <= ctl.releaseMaxMs; t += est.pulseStepMs) {
            var horizonR = overheadS + t / 1000 + brakeS
            var predictedRelease = simulateCandidate(
                state.barX, state.barV, state.fishX, state.fishV, t, false, horizonR
            )
            best = considerCandidate(best, predictedRelease, t, false)
        }
        for (t = ctl.holdMinMs; t <= ctl.holdMaxMs; t += est.pulseStepMs) {
            var horizonH = pressLatencyS + overheadS + t / 1000 + brakeS
            var predictedHold = simulateCandidate(
                state.barX, state.barV, state.fishX, state.fishV, t, true, horizonH
            )
            best = considerCandidate(best, predictedHold, t, true)
        }
        return best
    }

    function predictBarInterval(x, v, dt, action) {
        var bar = { x: x, v: v, impacts: 0, contact: false }
        var holdSeconds = 0
        var elapsed = 0

        if (action && action.hold) {
            var latencySeconds = pressLatencyS < dt ? pressLatencyS : dt
            advanceBar(bar, -state.accelRelease, latencySeconds)
            elapsed += latencySeconds

            if (elapsed < dt) {
                var wantedHold = action.durationMs / 1000
                holdSeconds = wantedHold < dt - elapsed ? wantedHold : dt - elapsed
                advanceBar(bar, state.accelHold, holdSeconds)
                elapsed += holdSeconds
            }

            if (elapsed < dt)
                advanceBar(bar, -state.accelRelease, dt - elapsed)
        } else {
            advanceBar(bar, -state.accelRelease, dt)
        }

        return {
            x: bar.x,
            v: bar.v,
            impacts: bar.impacts,
            contact: bar.contact,
            holdSeconds: holdSeconds
        }
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
        var previousBarV = state.barV
        var barPrediction = predictBarInterval(state.barX, state.barV, dt, state.lastAction)

        var barResidual = barPosition - barPrediction.x
        state.barX = barPrediction.x + est.estAlpha * barResidual
        state.barX = clamp(state.barX, minBarX, maxBarX)
        state.barV = barPrediction.v + (est.estBeta / dt) * barResidual

        var predictedFish = state.fishX + state.fishV * dt
        var fishResidual = fishPosition - predictedFish
        state.fishX = predictedFish + est.estAlpha * fishResidual
        state.fishV = state.fishV + (est.estBeta / dt) * fishResidual

        if (adapt && !barPrediction.contact &&
            state.barX > minBarX + 0.5 && state.barX < maxBarX - 0.5) {
            var dv = state.barV - previousBarV
            var releaseSeconds = dt - barPrediction.holdSeconds
            if (barPrediction.holdSeconds >= 0.03)
                state.accelHold = adaptAccel(
                    state.accelHold,
                    (dv + state.accelRelease * releaseSeconds) / barPrediction.holdSeconds
                )
            else if (barPrediction.holdSeconds === 0)
                state.accelRelease = adaptAccel(state.accelRelease, -dv / dt)
        }

        state.lastSampleMs = now
        return true
    }

    function noteOverhead() {
        if (!state.lastAction)
            return
        var measured = Date.now() - state.lastActionEndMs
        if (measured >= 0 && measured < 500)
            state.overheadMs = state.overheadMs * 0.8 + measured * 0.2
    }

    function step(now, barPosition, fishPosition) {
        if (!update(now, barPosition, fishPosition, true))
            return

        noteOverhead()

        var error = state.barX - state.fishX
        var velocity = state.barV - state.fishV
        var choice = choosePulse()
        if (!choice)
            return

        if (choice.hold)
            press(cfg.derived.holdX, cfg.derived.holdY, choice.durationMs)
        else if (choice.durationMs > 0)
            sleep(choice.durationMs)

        state.lastAction = { hold: choice.hold, durationMs: choice.durationMs }
        state.lastActionEndMs = Date.now()

        log((choice.hold ? "hold " : "release ") + choice.durationMs + "ms" +
            " err=" + Math.round(error) +
            " vel=" + Math.round(velocity) +
            " aH=" + Math.round(state.accelHold) +
            " aR=" + Math.round(state.accelRelease) +
            " overhead=" + Math.round(state.overheadMs) +
            " wall=" + choice.impacts)
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
        win.setPosition(x + cfg.minigame.trackX, cfg.derived.mainScanY - 200)
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
        estBar: make("estBar", "#38fc02"),
        estFish: make("estFish", "#38fc02"),
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
function cast(cfg) {
    press(cfg.derived.holdX, cfg.derived.holdY, cfg.cast.holdMs)
}

function run(cfg, detector, controller, markers, toggle) {
    var minigameStartedAt = 0
    var isNewMinigame = true
    var isNewMacroLoop = true
    var lastDetectionAt = 0

    while (true) {
        if (!toggle.isRunning()) {
            isNewMacroLoop = true
            sleep(100)
            continue
        }

        if (isNewMacroLoop) {
            isNewMacroLoop = false
            sleep(500)
            cast(cfg)
        }

        var now = Date.now()
        if (now - lastDetectionAt > cfg.cast.reCastAfterNoDetectionForMs) {
            cast(cfg)
            lastDetectionAt = now
        }

        var img = captureScreen()

        try {
            if (!detector.isMinigame(img)) {
                isNewMinigame = true

                var shake = detector.findShake(img)
                if (shake) {
                    lastDetectionAt = now
                    click(shake.x, shake.y)
                }
                sleep(30)
            } else {
                if (isNewMinigame) {
                    isNewMinigame = false
                    minigameStartedAt = Date.now()
                    controller.reset()
                }

                var positions = detector.findLineAndBar(img)
                if (positions.lineCenterX !== -1 && positions.barCenterX !== -1) {
                    lastDetectionAt = now

                    if (cfg.debug.showMarkers) {
                        markers.move(markers.line, positions.lineCenterX)
                        markers.move(markers.bar, positions.barCenterX)
                        markers.move(markers.estBar, controller.snapshot().barX)
                        markers.move(markers.estFish, controller.snapshot().fishX)
                    }

                    if (now - minigameStartedAt < cfg.control.controller.minigameLockMs)
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

var cfg = createConfig()
var markers = createMarkers(cfg)
var controller = createController(cfg)
var detector = createDetector(cfg)
var toggle = createToggle()

run(cfg, detector, controller, markers, toggle)
