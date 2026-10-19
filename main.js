// ============================================================
// Detection config
// ============================================================
// Status
var MONEY_LEVEL_BASE_COLOR = "#fffde4"
var MONEY_LEVEL_PATHS = [[-13, -10, "#fffde4"], [-1, 12, "#fffde4"], [-120, -45, "#ffdcac"]]
var MONEY_LEVEL_OPTIONS = { region: [1805, 907, 76, 88], threshold: 20 }

// Shaking
var SHAKE_BASE_COLOR = "#ffffff"
var SHAKE_PATHS = [[-39, -13, "#ebebeb"], [-14, 5, "#ffffff"], [24, -12, "#fefefe"], [41, 8, "#ffffff"]]
var SHAKE_OPTIONS = { threshold: 20 }

// Fishing minigame
var BAR_X = 565
var BAR_Y = 843
var BAR_W = 762
var MAIN_SCAN_Y = 857
var SECONDARY_SCAN_Y = 850

var ARROW_RELEASED_SECONDARY_SCAN_MAX_HIT_OFFSET = 9
var ARROW_W = 28
var ARROW_R = 130
var ARROW_G = 134
var ARROW_B = 136
var ARROW_THRESHOLD = 20
var TARGET_ZONE_W = 343
var TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_RELEASED = 22
var TARGET_ZONE_LEFT_TO_ARROW_OFFSET_WHEN_HOLDING = 294

var INDICATOR_R = 67
var INDICATOR_G = 75
var INDICATOR_B = 91
var INDICATOR_THRESHOLD = 10

// ============================================================
// Control config
// ============================================================
var HOLD_X = 1446
var HOLD_Y = 600

var SETTLE_MS = 100
var HOLD_MIN_MS = 120
var HOLD_MAX_MS = 300
var HOLD_GAIN_MS_PER_PX = 1.5 // KP
var RELEASE_MIN_MS = 5
var RELEASE_MAX_MS = 20
var RELEASE_GAIN_MS_PER_PX = 0.03
var DEADBAND_PX = 0
var COUNTER_RELEASE_PERCENTAGE = 100

// ============================================================
// Derived config
// ============================================================
var COUNTER_RELEASE = COUNTER_RELEASE_PERCENTAGE / 100

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
    win.setPosition(x + BAR_X, BAR_Y - 150)
}

function holdMs(error) {
    var t = HOLD_MIN_MS + HOLD_GAIN_MS_PER_PX * Math.abs(error)
    return Math.min(HOLD_MAX_MS, t)
}

function releaseMs(error) {
    var t = RELEASE_MIN_MS + RELEASE_GAIN_MS_PER_PX * Math.abs(error)
    return Math.min(RELEASE_MAX_MS, t)
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
while (true) {
    // ============================================================
    // Detection
    // ============================================================
    var img = captureScreen()
    var bmp = img.bitmap
    bmp.getPixels(mainScanRow, 0, BAR_W, BAR_X, MAIN_SCAN_Y, BAR_W, 1)
    bmp.getPixels(secondaryScanRow, 0, BAR_W, BAR_X, SECONDARY_SCAN_Y, BAR_W, 1)

    try {
        // Status
        var isInFishingMinigame = images.findPointByColors(img, MONEY_LEVEL_BASE_COLOR, MONEY_LEVEL_PATHS, MONEY_LEVEL_OPTIONS) === null
        if (!isInFishingMinigame) {
            // Shake
            var shake = images.findPointByColors(img, SHAKE_BASE_COLOR, SHAKE_PATHS, SHAKE_OPTIONS)
            if (shake)
                click(shake.x, shake.y)
            sleep(30)
        } else {
            // Minigame
            var indicatorX = -1
            var targetZoneLeftX = -1
            for (var x = 0; x < BAR_W; x++) {
                var p = mainScanRow[x]
                var r = (p >> 16) & 0xff
                var g = (p >> 8) & 0xff
                var b = p & 0xff

                // Indicator
                if (indicatorX === -1 && colorInRange(r, g, b, INDICATOR_R, INDICATOR_G, INDICATOR_B, INDICATOR_THRESHOLD))
                    indicatorX = x

                // Target zone
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

            // ============================================================
            // Control
            // ============================================================
            if (indicatorX !== -1 && targetZoneLeftX !== -1) {
                var targetZoneCenterX = targetZoneLeftX + TARGET_ZONE_W / 2
                if (indicatorX > targetZoneCenterX + DEADBAND_PX) {
                    var holdDuration = holdMs(indicatorX - targetZoneCenterX)
                    press(HOLD_X, HOLD_Y, holdDuration)
                    log("hold")
                } else if (indicatorX < targetZoneCenterX - DEADBAND_PX) {
                    var releaseDuration = releaseMs(indicatorX - targetZoneCenterX)
                    sleep(releaseDuration)
                    press(HOLD_X, HOLD_Y, releaseDuration * COUNTER_RELEASE)
                    log("release")
                } else {
                    press(HOLD_X, HOLD_Y, SETTLE_MS)
                    log("settle")
                }

                // ============================================================
                // Marking
                // ============================================================
                mark(winIndicator, indicatorX)
                mark(winTargetZoneLeft, targetZoneLeftX)
                mark(winTargetZoneMiddle, targetZoneCenterX)
            }
        }
    }
    finally {
        img.recycle()
    }
}