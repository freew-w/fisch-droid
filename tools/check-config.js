// Verifies config.json/default.json, the blank defaults in config.html, and
// the expected shape used by main.js. Run with: npm run check
"use strict";

const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const htmlPath = path.join(root, "config.html");
let currentFile = "";

function fail(message) {
  console.error("config check failed: " + (currentFile ? currentFile + ": " : "") + message);
  process.exit(1);
}

const html = fs.readFileSync(htmlPath, "utf8");
const inlineScript = html.match(/<script>([\s\S]*)<\/script>/);
if (!inlineScript) fail("cannot find the inline script in config.html");
try {
  new Function(inlineScript[1]);
} catch (error) {
  fail("config.html inline script has a syntax error: " + error.message);
}
const blankMatch = html.match(/const BLANK_CONFIG = (\{[\s\S]*?\n\});\n\nconst STORAGE_KEY/);
if (!blankMatch) fail("cannot find BLANK_CONFIG in config.html");
let blankConfig;
try {
  blankConfig = new Function("return (" + blankMatch[1] + ");")();
} catch (error) {
  fail("cannot parse BLANK_CONFIG in config.html: " + error.message);
}

const REQUIRED = {
  root: ["version", "moneyLevel", "shake", "minigame", "control"],
  moneyLevel: ["baseColor", "paths", "region", "threshold"],
  shake: ["baseColor", "paths", "threshold"],
  minigame: ["barX", "barY", "barWidth", "barHeight", "mainScanY", "secondaryScanY", "arrow", "targetZone", "indicator"],
  arrow: ["width", "color", "threshold", "releasedMaxHitOffset"],
  targetZone: ["width", "leftOffsetWhenReleased", "leftOffsetWhenHolding"],
  indicator: ["color", "threshold"],
  control: [
    "holdX", "holdY", "holdMinMs", "holdMaxMs", "releaseMinMs", "releaseMaxMs",
    "pressLatencyMs", "holdAccelPxPerSec2", "releaseAccelPxPerSec2", "velocityCostMs",
    "deadbandPx", "accelAdaptPercent", "holdPenaltyPerMs", "minigameLockMs"
  ]
};

function requireKeys(object, keys, label) {
  if (!object || typeof object !== "object") fail(label + " is not an object");
  for (const key of keys) {
    if (!(key in object)) fail(label + "." + key + " is missing");
  }
}

function requireNumber(value, label) {
  if (typeof value !== "number" || !Number.isFinite(value)) fail(label + " must be a finite number");
}

function requireColor(value, label) {
  if (typeof value !== "string" || !/^#[0-9a-f]{6}$/i.test(value)) fail(label + " must be a #rrggbb color");
}

function requirePaths(paths, label) {
  if (!Array.isArray(paths) || paths.length < 1) fail(label + " must be a non-empty array");
  paths.forEach((point, index) => {
    if (!Array.isArray(point) || point.length !== 3) fail(label + "[" + index + "] must be [dx, dy, color]");
    requireNumber(point[0], label + "[" + index + "].dx");
    requireNumber(point[1], label + "[" + index + "].dy");
    requireColor(point[2], label + "[" + index + "].color");
  });
}

function validateConfig(config) {
  requireKeys(config, REQUIRED.root, "config");
  requireNumber(config.version, "config.version");
  if ("profiles" in config || "activeProfile" in config) {
    fail("profiles are no longer supported; config must be one device configuration");
  }

  requireKeys(config.moneyLevel, REQUIRED.moneyLevel, "moneyLevel");
  requireColor(config.moneyLevel.baseColor, "moneyLevel.baseColor");
  requirePaths(config.moneyLevel.paths, "moneyLevel.paths");
  requireNumber(config.moneyLevel.threshold, "moneyLevel.threshold");
  const region = config.moneyLevel.region;
  if (!Array.isArray(region) || region.length !== 4) fail("moneyLevel.region must be [x, y, w, h]");
  region.forEach((value, index) => requireNumber(value, "moneyLevel.region[" + index + "]"));
  if (region[2] < 1 || region[3] < 1) fail("moneyLevel.region width and height must be positive");

  requireKeys(config.shake, REQUIRED.shake, "shake");
  requireColor(config.shake.baseColor, "shake.baseColor");
  requirePaths(config.shake.paths, "shake.paths");
  requireNumber(config.shake.threshold, "shake.threshold");

  requireKeys(config.minigame, REQUIRED.minigame, "minigame");
  for (const key of ["barX", "barY", "barWidth", "barHeight", "mainScanY", "secondaryScanY"]) {
    requireNumber(config.minigame[key], "minigame." + key);
  }
  if (config.minigame.barWidth < 1) fail("minigame.barWidth must be positive");
  if (config.minigame.barHeight < 1) fail("minigame.barHeight must be positive");

  requireKeys(config.minigame.arrow, REQUIRED.arrow, "minigame.arrow");
  requireNumber(config.minigame.arrow.width, "minigame.arrow.width");
  requireColor(config.minigame.arrow.color, "minigame.arrow.color");
  requireNumber(config.minigame.arrow.threshold, "minigame.arrow.threshold");
  requireNumber(config.minigame.arrow.releasedMaxHitOffset, "minigame.arrow.releasedMaxHitOffset");

  requireKeys(config.minigame.targetZone, REQUIRED.targetZone, "minigame.targetZone");
  requireNumber(config.minigame.targetZone.width, "minigame.targetZone.width");
  requireNumber(config.minigame.targetZone.leftOffsetWhenReleased, "minigame.targetZone.leftOffsetWhenReleased");
  requireNumber(config.minigame.targetZone.leftOffsetWhenHolding, "minigame.targetZone.leftOffsetWhenHolding");

  requireKeys(config.minigame.indicator, REQUIRED.indicator, "minigame.indicator");
  requireColor(config.minigame.indicator.color, "minigame.indicator.color");
  requireNumber(config.minigame.indicator.threshold, "minigame.indicator.threshold");

  requireKeys(config.control, REQUIRED.control, "control");
  for (const key of REQUIRED.control) requireNumber(config.control[key], "control." + key);
}

currentFile = "config.html BLANK_CONFIG";
validateConfig(blankConfig);
currentFile = "";

const configFiles = ["config.json", "default.json"].filter(name => fs.existsSync(path.join(root, name)));
if (!configFiles.length) fail("no config.json or default.json found");

for (const name of configFiles) {
  currentFile = name;
  let config;
  try {
    config = JSON.parse(fs.readFileSync(path.join(root, name), "utf8"));
  } catch (error) {
    fail("cannot parse " + name + ": " + error.message);
  }
  validateConfig(config);
}

console.log("config check passed: " + configFiles.join(", ") + ", config.html BLANK_CONFIG, and the inline script are valid");
