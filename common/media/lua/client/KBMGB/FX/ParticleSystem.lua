-- File: media/lua/client/KBMGB/FX/ParticleSystem.lua
-- Shared particle backend for ValuTech OS apps / exported games.

local VTUtils = require("KBMGB/System/VTUtils")
local clamp = VTUtils.clamp
local clamp01 = VTUtils.clamp01

local ParticleSystem = {}

local function randf(a, b)
    if _G.ZombRand then
        return a + (_G.ZombRand(10000) / 10000) * (b - a)
    end
    return a + math.random() * (b - a)
end

local function colorLerp(a, b, t)
    return {
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t,
        a[3] + (b[3] - a[3]) * t,
        a[4] + (b[4] - a[4]) * t,
    }
end

local function pushHistoryPoint(p, maxLen)
    if not p then return end
    if not p.history then p.history = {} end
    table.insert(p.history, 1, { x = p.x, y = p.y })
    while #p.history > maxLen do
        table.remove(p.history)
    end
end

local function particleSpeed(p)
    local vx = p and p.vx or 0
    local vy = p and p.vy or 0
    return math.sqrt(vx * vx + vy * vy)
end

local function safeFloor(n)
    return math.floor(tonumber(n) or 0)
end

local function drawRectSafe(ui, x, y, w, h, a, r, g, b)
    if not ui or type(ui.drawRect) ~= "function" then return end
    if (tonumber(a) or 0) <= 0 then return end
    if (tonumber(w) or 0) <= 0 then return end
    if (tonumber(h) or 0) <= 0 then return end
    ui:drawRect(safeFloor(x), safeFloor(y), safeFloor(w), safeFloor(h), a, r, g, b)
end

local function drawSoftRect(ui, x, y, size, a, r, g, b, layers)
    size = math.max(1, tonumber(size) or 1)
    layers = math.max(1, math.floor(tonumber(layers) or 2))

    local cx = tonumber(x) or 0
    local cy = tonumber(y) or 0

    for i = layers, 1, -1 do
        local grow = i - 1
        local alpha = a * (0.12 + (0.20 / i))
        drawRectSafe(
            ui,
            cx - grow,
            cy - grow,
            size + grow * 2,
            size + grow * 2,
            alpha,
            r, g, b
        )
    end

    drawRectSafe(ui, cx, cy, size, size, a, r, g, b)
end

local function drawGlowRect(ui, x, y, size, a, r, g, b, glowSize)
    size = math.max(1, tonumber(size) or 1)
    glowSize = math.max(1, tonumber(glowSize) or (size + 2))

    local cx = tonumber(x) or 0
    local cy = tonumber(y) or 0
    local coreInset = math.max(0, math.floor((glowSize - size) * 0.5))

    drawRectSafe(ui, cx - coreInset, cy - coreInset, glowSize, glowSize, a * 0.10, r, g, b)
    drawRectSafe(ui, cx - 1, cy - 1, size + 2, size + 2, a * 0.22, r, g, b)
    drawRectSafe(ui, cx, cy, size, size, a, r, g, b)
end

local function drawStreak(ui, x, y, size, a, r, g, b, vx, vy, stretchByVelocity, streakWidth, minStretch, maxStretch)
    size = math.max(1, tonumber(size) or 1)

    local speed = math.sqrt((vx or 0) * (vx or 0) + (vy or 0) * (vy or 0))
    local stretch = tonumber(stretchByVelocity) or 0
    local len = size + speed * stretch
    len = clamp(len, tonumber(minStretch) or size, tonumber(maxStretch) or math.max(size, 8))

    local dirLen = math.sqrt((vx or 0) * (vx or 0) + (vy or 0) * (vy or 0))
    local dx, dy = 0, 0
    if dirLen > 0.0001 then
        dx = (vx or 0) / dirLen
        dy = (vy or 0) / dirLen
    else
        dx = 0
        dy = -1
    end

    local sx = tonumber(x) or 0
    local sy = tonumber(y) or 0
    local steps = math.max(1, math.floor(len))
    local width = math.max(1, math.floor(tonumber(streakWidth) or size))

    for i = steps, 0, -1 do
        local t = i / math.max(1, steps)
        local px = sx - dx * i
        local py = sy - dy * i
        local alpha = a * (0.18 + (0.55 * t))
        drawRectSafe(ui, px, py, width, width, alpha, r, g, b)
    end

    drawRectSafe(ui, sx, sy, size, size, a, r, g, b)
end

ParticleSystem.palettes = {
    fire = {
        { 1.00, 1.00, 0.70, 1.00 },
        { 1.00, 0.75, 0.20, 1.00 },
        { 1.00, 0.30, 0.05, 0.95 },
        { 0.35, 0.05, 0.02, 0.00 },
    },

    smoke = {
        { 0.85, 0.85, 0.85, 0.80 },
        { 0.60, 0.60, 0.60, 0.55 },
        { 0.35, 0.35, 0.35, 0.25 },
        { 0.10, 0.10, 0.10, 0.00 },
    },

    bubble = {
        { 0.95, 0.95, 1.00, 0.85 },
        { 0.45, 0.90, 1.00, 0.75 },
        { 0.15, 0.60, 1.00, 0.50 },
        { 0.10, 0.25, 0.75, 0.00 },
    },

    magic = {
        { 1.00, 0.90, 1.00, 1.00 },
        { 0.85, 0.45, 1.00, 0.95 },
        { 0.30, 0.25, 0.85, 0.90 },
        { 0.05, 0.02, 0.20, 0.00 },
    },

    green = {
        { 0.90, 1.00, 0.90, 1.00 },
        { 0.30, 1.00, 0.55, 0.95 },
        { 0.10, 0.55, 0.20, 0.90 },
        { 0.05, 0.18, 0.08, 0.00 },
    },

    rain = {
        { 0.85, 0.95, 1.00, 0.95 },
        { 0.40, 0.75, 1.00, 0.95 },
        { 0.15, 0.45, 0.85, 0.85 },
        { 0.10, 0.20, 0.35, 0.00 },
    },

    trail = {
        { 1.00, 0.95, 0.80, 1.00 },
        { 1.00, 0.55, 0.25, 0.98 },
        { 0.90, 0.20, 0.65, 0.92 },
        { 0.08, 0.02, 0.12, 0.00 },
    },

    boids = {
        { 0.95, 1.00, 0.92, 1.00 },
        { 0.55, 0.95, 1.00, 0.98 },
        { 0.20, 0.70, 0.95, 0.90 },
        { 0.03, 0.10, 0.18, 0.00 },
    },

    galaxy = {
        { 1.00, 0.98, 0.92, 1.00 },
        { 0.72, 0.82, 1.00, 0.95 },
        { 0.42, 0.55, 1.00, 0.82 },
        { 0.02, 0.03, 0.08, 0.00 },
    },
}

ParticleSystem.behaviors = {}

ParticleSystem.behaviors.fountain = function(emitter, p)
    p.x = emitter.x + randf(-5, 5)
    p.y = emitter.y + randf(-2, 2)
    p.vx = randf(-0.8, 0.8)
    p.vy = randf(-3.6, -2.2)
end

ParticleSystem.behaviors.sparks = function(emitter, p)
    local a = randf(-3.14159, 3.14159)
    local s = randf(1.8, 4.6)
    p.x = emitter.x
    p.y = emitter.y
    p.vx = math.cos(a) * s
    p.vy = math.sin(a) * s
end

ParticleSystem.behaviors.rain = function(emitter, p)
    local bounds = emitter.bounds
    if bounds then
        p.x = randf(bounds.x + 1, bounds.x + bounds.w - 2)
        p.y = bounds.y - randf(0, 10)
    else
        p.x = emitter.x + randf(-20, 20)
        p.y = emitter.y - randf(0, 10)
    end
    p.vx = randf(-0.15, 0.15)
    p.vy = randf(2.8, 4.3)
end

ParticleSystem.behaviors.burst = function(emitter, p)
    local a = randf(-3.14159, 3.14159)
    local s = randf(0.6, 3.0)
    p.x = emitter.x
    p.y = emitter.y
    p.vx = math.cos(a) * s
    p.vy = math.sin(a) * s
end

ParticleSystem.behaviors.orbit = function(emitter, p)
    local a = randf(-3.14159, 3.14159)
    local r = randf(10, 34)
    p.x = emitter.x + math.cos(a) * r
    p.y = emitter.y + math.sin(a) * r
    p.vx = -math.sin(a) * randf(1.0, 2.2)
    p.vy =  math.cos(a) * randf(1.0, 2.2)
    p.orbit = true
end

ParticleSystem.behaviors.swarm = function(emitter, p)
    p.x = emitter.x + randf(-36, 36)
    p.y = emitter.y + randf(-24, 24)
    p.vx = randf(-0.8, 0.8)
    p.vy = randf(-0.8, 0.8)
    p.swarm = true
    p.seed = randf(0, 6.28318)
    p.wander = randf(0.015, 0.055)
    p.seek = randf(0.035, 0.085)
    p.maxSpeed = randf(1.4, 2.8)
end

ParticleSystem.behaviors.flow = function(emitter, p)
    p.x = emitter.x + randf(-18, 18)
    p.y = emitter.y + randf(-18, 18)
    p.vx = randf(-0.4, 0.4)
    p.vy = randf(-0.4, 0.4)
    p.flow = true
    p.seed = randf(0, 6.28318)
    p.maxSpeed = randf(1.8, 3.4)
    p.flowForce = randf(0.040, 0.090)
end

ParticleSystem.behaviors.trail = function(emitter, p)
    local a = randf(-3.14159, 3.14159)
    local s = randf(1.2, 3.8)
    p.x = emitter.x
    p.y = emitter.y
    p.vx = math.cos(a) * s
    p.vy = math.sin(a) * s
    p.trail = true
    p.seed = randf(0, 6.28318)
    p.maxSpeed = randf(2.2, 4.2)
    p.history = {}
end

ParticleSystem.behaviors.boids = function(emitter, p)
    p.x = emitter.x + randf(-42, 42)
    p.y = emitter.y + randf(-30, 30)
    p.vx = randf(-1.4, 1.4)
    p.vy = randf(-1.4, 1.4)
    p.boid = true
    p.history = {}
    p.maxSpeed = randf(1.8, 2.8)
    p.sepRadius = randf(10, 16)
    p.neighborRadius = randf(22, 34)
    p.sepWeight = randf(0.055, 0.090)
    p.alignWeight = randf(0.022, 0.040)
    p.cohWeight = randf(0.016, 0.030)
    p.homeWeight = randf(0.004, 0.009)
end

ParticleSystem.behaviors.galaxy = function(emitter, p)
    local armCount = tonumber(emitter.armCount) or 2
    local innerRadius = tonumber(emitter.innerRadius) or 10
    local outerRadius = tonumber(emitter.outerRadius) or 64
    local armJitter = tonumber(emitter.armJitter) or 0.16
    local spiralTightness = tonumber(emitter.spiralTightness) or 0.11
    local flatten = tonumber(emitter.flatten) or 0.62
    local coreBias = tonumber(emitter.coreBias) or 1.75
    local orbitSpeedMin = tonumber(emitter.orbitSpeedMin) or 0.16
    local orbitSpeedMax = tonumber(emitter.orbitSpeedMax) or 0.42
    local coreRadius = tonumber(emitter.coreRadius) or 16
    local coreChance = tonumber(emitter.coreChance) or 0.32
    local bigCoreStarChance = tonumber(emitter.bigCoreStarChance) or 0.35

    if randf(0, 1) < coreChance then
        local a = randf(-3.14159, 3.14159)
        local r = randf(0, coreRadius)
        r = r * randf(0.15, 1.0)

        p.x = emitter.x + math.cos(a) * r
        p.y = emitter.y + math.sin(a) * r * flatten

        p.vx = randf(-0.08, 0.08)
        p.vy = randf(-0.06, 0.06)

        p.galaxy = true
        p.isCore = true
        p.seed = randf(0, 6.28318)
        p.galaxyRadius = r
        p.spin = randf(0.002, 0.006)
        p.corePull = randf(0.0005, 0.0015)
        p.maxSpeed = randf(0.12, 0.28)
        p.size = (randf(0, 1) < bigCoreStarChance) and 2 or 1
        return
    end

    local armIndex = math.floor(randf(0, armCount))
    if armIndex >= armCount then armIndex = armCount - 1 end

    local armBaseAngle = (math.pi * 2) * (armIndex / armCount)

    local t = randf(0.0, 1.0)
    local radius = innerRadius + (outerRadius - innerRadius) * (t ^ coreBias)

    local spiralAngle = armBaseAngle + radius * spiralTightness
    local angle = spiralAngle + randf(-armJitter, armJitter)

    local x = math.cos(angle) * radius
    local y = math.sin(angle) * radius * flatten

    p.x = emitter.x + x
    p.y = emitter.y + y

    local tangentialX = -math.sin(angle)
    local tangentialY =  math.cos(angle) * flatten

    local tangentLen = math.sqrt(tangentialX * tangentialX + tangentialY * tangentialY)
    if tangentLen > 0.0001 then
        tangentialX = tangentialX / tangentLen
        tangentialY = tangentialY / tangentLen
    end

    local orbitSpeed = randf(orbitSpeedMin, orbitSpeedMax)

    p.vx = tangentialX * orbitSpeed
    p.vy = tangentialY * orbitSpeed

    p.galaxy = true
    p.isCore = false
    p.seed = randf(0, 6.28318)
    p.galaxyRadius = radius
    p.spin = randf(0.008, 0.016)
    p.corePull = randf(0.0020, 0.0045)
    p.maxSpeed = randf(0.55, 1.00)
end

local function resolvePalette(config)
    if type(config.palette) == "table" then
        return config.palette
    end
    if type(config.paletteName) == "string" then
        return ParticleSystem.palettes[config.paletteName] or ParticleSystem.palettes.fire
    end
    return ParticleSystem.palettes.fire
end

local function resolveBehavior(config)
    if type(config.spawn) == "function" then
        return config.spawn
    end
    if type(config.behaviorFn) == "function" then
        return config.behaviorFn
    end
    if type(config.behavior) == "string" then
        return ParticleSystem.behaviors[config.behavior] or ParticleSystem.behaviors.sparks
    end
    return ParticleSystem.behaviors.sparks
end

local function resolveRenderStyle(config)
    local style = tostring(config.renderStyle or "pixel")
    if style == "soft" or style == "glow" or style == "streak" or style == "mixed" then
        return style
    end
    return "pixel"
end

function ParticleSystem.newEmitter(x, y, config)
    config = config or {}

    local emitter = {
        x = tonumber(x) or 0,
        y = tonumber(y) or 0,

        active = (config.active ~= false),
        spawnRate = tonumber(config.spawnRate) or 0,
        gravity = tonumber(config.gravity) or 0,
        drag = tonumber(config.drag) or 0.985,

        lifeMin = tonumber(config.lifeMin) or 20,
        lifeMax = tonumber(config.lifeMax) or 40,
        sizeMin = tonumber(config.sizeMin) or 1,
        sizeMax = tonumber(config.sizeMax) or 2,
        maxParticles = tonumber(config.maxParticles) or 120,
        trailLen = tonumber(config.trailLen) or 0,

        bounds = config.bounds,
        palette = resolvePalette(config),
        spawn = resolveBehavior(config),

        renderStyle = resolveRenderStyle(config),
        alphaScale = tonumber(config.alphaScale) or 1.0,
        glowSize = tonumber(config.glowSize) or 0,
        softLayers = tonumber(config.softLayers) or 2,
        stretchByVelocity = tonumber(config.stretchByVelocity) or 0,
        streakWidth = tonumber(config.streakWidth) or 1,
        minStretch = tonumber(config.minStretch) or 1,
        maxStretch = tonumber(config.maxStretch) or 8,
        historyAlpha = tonumber(config.historyAlpha) or 0.45,
        historyFade = tonumber(config.historyFade) or 1.0,
        mixedSpeedThreshold = tonumber(config.mixedSpeedThreshold) or 2.2,
        renderFn = config.renderFn,

        shapeRadius = tonumber(config.shapeRadius) or 54,
        shapeInnerRadius = tonumber(config.shapeInnerRadius) or 8,

        armCount = tonumber(config.armCount) or 2,
        innerRadius = tonumber(config.innerRadius) or 10,
        outerRadius = tonumber(config.outerRadius) or 64,
        armJitter = tonumber(config.armJitter) or 0.16,
        spiralTightness = tonumber(config.spiralTightness) or 0.11,
        flatten = tonumber(config.flatten) or 0.62,
        coreBias = tonumber(config.coreBias) or 1.75,
        orbitSpeedMin = tonumber(config.orbitSpeedMin) or 0.16,
        orbitSpeedMax = tonumber(config.orbitSpeedMax) or 0.42,
        coreRadius = tonumber(config.coreRadius) or 16,
        coreChance = tonumber(config.coreChance) or 0.32,
        bigCoreStarChance = tonumber(config.bigCoreStarChance) or 0.35,

        particles = {},
        spawnCarry = 0,
        time = 0,
        config = config,
    }

    return emitter
end

function ParticleSystem.setPosition(emitter, x, y)
    if not emitter then return end
    emitter.x = tonumber(x) or emitter.x
    emitter.y = tonumber(y) or emitter.y
end

function ParticleSystem.setBounds(emitter, bounds)
    if not emitter then return end
    emitter.bounds = bounds
end

function ParticleSystem.clear(emitter)
    if not emitter then return end
    emitter.particles = {}
    emitter.spawnCarry = 0
end

function ParticleSystem.getParticleColor(emitter, p)
    if p and p.galaxy then
        local t = clamp((p.age or 0) / math.max(1, p.life or 1), 0, 1)

        if p.isCore then
            local corePal = {
                { 1.00, 0.98, 0.88, 1.00 }, -- hot white/yellow
                { 1.00, 0.86, 0.42, 0.95 }, -- gold
                { 1.00, 0.62, 0.18, 0.70 }, -- orange
                { 0.18, 0.08, 0.02, 0.00 }, -- warm fade
            }

            if t <= 0.33 then
                return colorLerp(corePal[1], corePal[2], t / 0.33)
            elseif t <= 0.66 then
                return colorLerp(corePal[2], corePal[3], (t - 0.33) / 0.33)
            end
            return colorLerp(corePal[3], corePal[4], (t - 0.66) / 0.34)
        else
            local armPal = {
                { 0.78, 0.88, 1.00, 0.82 }, -- pale blue
                { 0.42, 0.62, 1.00, 0.68 }, -- strong blue
                { 0.26, 0.22, 0.72, 0.42 }, -- indigo/violet
                { 0.01, 0.02, 0.08, 0.00 }, -- dark fade
            }

            if t <= 0.33 then
                return colorLerp(armPal[1], armPal[2], t / 0.33)
            elseif t <= 0.66 then
                return colorLerp(armPal[2], armPal[3], (t - 0.33) / 0.33)
            end
            return colorLerp(armPal[3], armPal[4], (t - 0.66) / 0.34)
        end
    end

    local pal = emitter.palette or ParticleSystem.palettes.fire
    local t = clamp((p.age or 0) / math.max(1, p.life or 1), 0, 1)

    if t <= 0.33 then
        return colorLerp(pal[1], pal[2], t / 0.33)
    elseif t <= 0.66 then
        return colorLerp(pal[2], pal[3], (t - 0.33) / 0.33)
    end
    return colorLerp(pal[3], pal[4], (t - 0.66) / 0.34)
end

function ParticleSystem.spawnOne(emitter, opts)
    if not emitter then return nil end
    opts = opts or {}

    if #emitter.particles >= (opts.maxParticles or emitter.maxParticles or 120) then
        table.remove(emitter.particles, 1)
    end

    local p = {
        x = emitter.x,
        y = emitter.y,
        vx = 0,
        vy = 0,
        age = 0,
        life = randf(opts.lifeMin or emitter.lifeMin, opts.lifeMax or emitter.lifeMax),
        size = math.floor(randf(opts.sizeMin or emitter.sizeMin, (opts.sizeMax or emitter.sizeMax) + 0.999)),
        orbit = false,
        swarm = false,
        flow = false,
        trail = false,
        boid = false,
        galaxy = false,
        isCore = false,
        history = nil,
    }

    local trailLen = opts.trailLen or emitter.trailLen or 0
    if trailLen > 0 then
        p.history = {}
    end

    local spawnFn = opts.spawn or emitter.spawn
    if type(spawnFn) == "function" then
        spawnFn(emitter, p, opts)
    end

    table.insert(emitter.particles, p)
    return p
end

function ParticleSystem.spawn(emitter, count, opts)
    if not emitter then return end
    count = tonumber(count) or 1
    for _ = 1, count do
        ParticleSystem.spawnOne(emitter, opts)
    end
end

function ParticleSystem.burstAt(emitter, x, y, count, opts)
    if not emitter then return end
    local oldX, oldY = emitter.x, emitter.y
    emitter.x = tonumber(x) or oldX
    emitter.y = tonumber(y) or oldY
    ParticleSystem.spawn(emitter, count or 8, opts)
    emitter.x, emitter.y = oldX, oldY
end

function ParticleSystem.updateEmitter(emitter, dt)
    if not emitter then return end

    dt = tonumber(dt) or 0
    if dt < 0 then dt = 0 end
    if dt > 0.08 then dt = 0.08 end

    local step = clamp(dt * 60.0, 0.25, 3.0)
    emitter.time = (emitter.time or 0) + dt

    if emitter.active and (emitter.spawnRate or 0) > 0 then
        emitter.spawnCarry = (emitter.spawnCarry or 0) + ((emitter.spawnRate or 0) * step)
        while emitter.spawnCarry >= 1 do
            ParticleSystem.spawnOne(emitter)
            emitter.spawnCarry = emitter.spawnCarry - 1
        end
    end

    local drag = math.pow(emitter.drag or 0.985, step)
    local bounds = emitter.bounds
    local left   = bounds and bounds.x or nil
    local right  = bounds and (bounds.x + bounds.w) or nil
    local top    = bounds and bounds.y or nil
    local bottom = bounds and (bounds.y + bounds.h) or nil
    local ex = emitter.x or 0
    local ey = emitter.y or 0
    local tSec = emitter.time or 0

    for i = #emitter.particles, 1, -1 do
        local p = emitter.particles[i]

        p.age = (p.age or 0) + step

        if p.galaxy then
            local dx = ex - (p.x or 0)
            local dy = ey - (p.y or 0)
            dy = dy * 0.42

            local dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0.001 then
                local nx = dx / dist
                local ny = dy / dist

                local tx = -ny
                local ty = nx

                local spin = p.spin or 0.010
                local pull = p.corePull or 0.0035

                p.vx = (p.vx or 0) + (tx * spin + nx * pull) * step
                p.vy = (p.vy or 0) + ((ty * spin + ny * pull) / 0.42) * step
            end

            p.vx = (p.vx or 0) + math.sin((p.seed or 0) + tSec * 0.9) * 0.0025 * step
            p.vy = (p.vy or 0) + math.cos((p.seed or 0) + tSec * 1.1) * 0.0025 * step

            local spd = math.sqrt((p.vx or 0) * (p.vx or 0) + (p.vy or 0) * (p.vy or 0))
            local maxSpeed = p.maxSpeed or 1.2
            if spd > maxSpeed and spd > 0.0001 then
                local s = maxSpeed / spd
                p.vx = (p.vx or 0) * s
                p.vy = (p.vy or 0) * s
            end

        elseif p.flow then
            local lx, ly = 0, 0
            if bounds then
                lx = ((p.x or 0) - left) / math.max(1, bounds.w or 1)
                ly = ((p.y or 0) - top) / math.max(1, bounds.h or 1)
            end

            local a =
                math.sin((lx * 8.0) + tSec * 1.7 + (p.seed or 0)) * 1.2 +
                math.cos((ly * 10.0) - tSec * 1.3 + (p.seed or 0) * 0.7) * 0.9 +
                math.sin(((lx + ly) * 7.0) + tSec * 0.8) * 0.7

            local fx = math.cos(a)
            local fy = math.sin(a)

            local centerDx = ex - (p.x or 0)
            local centerDy = ey - (p.y or 0)
            local centerDist = math.sqrt(centerDx * centerDx + centerDy * centerDy)
            if centerDist > 0.001 then
                centerDx = centerDx / centerDist
                centerDy = centerDy / centerDist
            else
                centerDx = 0
                centerDy = 0
            end

            local flowForce = p.flowForce or 0.06
            p.vx = (p.vx or 0) + (fx * flowForce + centerDx * 0.010) * step
            p.vy = (p.vy or 0) + (fy * flowForce + centerDy * 0.010) * step

            local spd = math.sqrt((p.vx or 0) * (p.vx or 0) + (p.vy or 0) * (p.vy or 0))
            local maxSpeed = p.maxSpeed or 2.6
            if spd > maxSpeed and spd > 0.0001 then
                local s = maxSpeed / spd
                p.vx = (p.vx or 0) * s
                p.vy = (p.vy or 0) * s
            end

        elseif p.swarm then
            local phase = (p.seed or 0) + tSec * 1.8
            local tx = ex + math.cos(phase) * 18 + math.sin(phase * 0.7) * 10
            local ty = ey + math.sin(phase * 1.2) * 14 + math.cos(phase * 0.5) * 8

            local dx = tx - (p.x or 0)
            local dy = ty - (p.y or 0)
            local dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0.001 then
                local nx = dx / dist
                local ny = dy / dist
                p.vx = (p.vx or 0) + nx * (p.seek or 0.05) * step
                p.vy = (p.vy or 0) + ny * (p.seek or 0.05) * step
            end

            local wx = math.sin((p.seed or 0) * 1.7 + tSec * 4.2) * (p.wander or 0.03)
            local wy = math.cos((p.seed or 0) * 1.1 + tSec * 3.6) * (p.wander or 0.03)
            p.vx = (p.vx or 0) + wx * step
            p.vy = (p.vy or 0) + wy * step

            local spd = math.sqrt((p.vx or 0) * (p.vx or 0) + (p.vy or 0) * (p.vy or 0))
            local maxSpeed = p.maxSpeed or 2.0
            if spd > maxSpeed and spd > 0.0001 then
                local s = maxSpeed / spd
                p.vx = (p.vx or 0) * s
                p.vy = (p.vy or 0) * s
            end

        elseif p.trail then
            local phase = (p.seed or 0) + tSec * 2.2
            local tx = ex + math.cos(phase) * 24 + math.sin(phase * 0.6) * 14
            local ty = ey + math.sin(phase * 1.4) * 18

            local dx = tx - (p.x or 0)
            local dy = ty - (p.y or 0)
            local dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0.001 then
                local nx = dx / dist
                local ny = dy / dist
                p.vx = (p.vx or 0) + nx * 0.060 * step
                p.vy = (p.vy or 0) + ny * 0.060 * step
            end

            p.vx = (p.vx or 0) + math.sin((p.seed or 0) * 1.9 + tSec * 5.0) * 0.020 * step
            p.vy = (p.vy or 0) + math.cos((p.seed or 0) * 1.3 + tSec * 4.4) * 0.020 * step

            local spd = math.sqrt((p.vx or 0) * (p.vx or 0) + (p.vy or 0) * (p.vy or 0))
            local maxSpeed = p.maxSpeed or 3.2
            if spd > maxSpeed and spd > 0.0001 then
                local s = maxSpeed / spd
                p.vx = (p.vx or 0) * s
                p.vy = (p.vy or 0) * s
            end

        elseif p.boid then
            local neighborRadius = p.neighborRadius or 22
            local sepRadius = p.sepRadius or 11
            local neighborRadiusSq = neighborRadius * neighborRadius
            local sepRadiusSq = sepRadius * sepRadius

            local sepX, sepY = 0, 0
            local alignX, alignY = 0, 0
            local cohX, cohY = 0, 0
            local nCount = 0
            local sCount = 0

            local checks = 0
            for j = 1, #emitter.particles do
                if j ~= i then
                    local o = emitter.particles[j]
                    if o and o.boid then
                        local dx = (o.x or 0) - (p.x or 0)
                        local dy = (o.y or 0) - (p.y or 0)
                        local d2 = dx * dx + dy * dy

                        if d2 > 0.0001 and d2 <= neighborRadiusSq then
                            nCount = nCount + 1
                            alignX = alignX + (o.vx or 0)
                            alignY = alignY + (o.vy or 0)
                            cohX = cohX + (o.x or 0)
                            cohY = cohY + (o.y or 0)

                            if d2 <= sepRadiusSq then
                                local d = math.sqrt(d2)
                                if d > 0.0001 then
                                    sepX = sepX - (dx / d)
                                    sepY = sepY - (dy / d)
                                    sCount = sCount + 1
                                end
                            end

                            checks = checks + 1
                            if checks >= 18 then break end
                        end
                    end
                end
            end

            if sCount > 0 then
                sepX = sepX / sCount
                sepY = sepY / sCount
                p.vx = (p.vx or 0) + sepX * (p.sepWeight or 0.065) * step
                p.vy = (p.vy or 0) + sepY * (p.sepWeight or 0.065) * step
            end

            if nCount > 0 then
                alignX = alignX / nCount
                alignY = alignY / nCount
                cohX = cohX / nCount
                cohY = cohY / nCount

                p.vx = (p.vx or 0) + (alignX - (p.vx or 0)) * (p.alignWeight or 0.025) * step
                p.vy = (p.vy or 0) + (alignY - (p.vy or 0)) * (p.alignWeight or 0.025) * step

                local cdx = cohX - (p.x or 0)
                local cdy = cohY - (p.y or 0)
                local cd = math.sqrt(cdx * cdx + cdy * cdy)
                if cd > 0.0001 then
                    cdx = cdx / cd
                    cdy = cdy / cd
                    p.vx = (p.vx or 0) + cdx * (p.cohWeight or 0.020) * step
                    p.vy = (p.vy or 0) + cdy * (p.cohWeight or 0.020) * step
                end
            end

            local homeDx = ex - (p.x or 0)
            local homeDy = ey - (p.y or 0)
            local homeD = math.sqrt(homeDx * homeDx + homeDy * homeDy)
            if homeD > 0.0001 then
                homeDx = homeDx / homeD
                homeDy = homeDy / homeD
                p.vx = (p.vx or 0) + homeDx * (p.homeWeight or 0.009) * step
                p.vy = (p.vy or 0) + homeDy * (p.homeWeight or 0.009) * step
            end

            local spd = math.sqrt((p.vx or 0) * (p.vx or 0) + (p.vy or 0) * (p.vy or 0))
            local maxSpeed = p.maxSpeed or 2.7
            if spd > maxSpeed and spd > 0.0001 then
                local s = maxSpeed / spd
                p.vx = (p.vx or 0) * s
                p.vy = (p.vy or 0) * s
            end

        elseif p.orbit then
            local dx = ex - (p.x or 0)
            local dy = ey - (p.y or 0)
            local dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0.001 then
                local nx = dx / dist
                local ny = dy / dist
                local tx = -ny
                local ty = nx
                p.vx = (p.vx or 0) + (nx * 0.035 + tx * 0.012) * step
                p.vy = (p.vy or 0) + (ny * 0.035 + ty * 0.012) * step
            end

        else
            p.vy = (p.vy or 0) + (emitter.gravity or 0) * step
        end

        if emitter.trailLen and emitter.trailLen > 0 then
            pushHistoryPoint(p, emitter.trailLen)
        end

        p.vx = (p.vx or 0) * drag
        p.vy = (p.vy or 0) * drag
        p.x = (p.x or 0) + (p.vx or 0) * step
        p.y = (p.y or 0) + (p.vy or 0) * step

        local dead = false

        if p.swarm or p.flow or p.trail or p.boid or p.galaxy then
            if bounds then
                if p.x < left then
                    p.x = left
                    p.vx = math.abs(p.vx or 0) * 0.8
                elseif p.x > right then
                    p.x = right
                    p.vx = -math.abs(p.vx or 0) * 0.8
                end

                if p.y < top then
                    p.y = top
                    p.vy = math.abs(p.vy or 0) * 0.8
                elseif p.y > bottom then
                    p.y = bottom
                    p.vy = -math.abs(p.vy or 0) * 0.8
                end
            end

            if p.age >= (p.life or 1) then
                dead = true
            end
        else
            if bounds then
                if p.x < left or p.x > right or p.y < top or p.y > bottom then
                    dead = true
                end
            end

            if p.age >= (p.life or 1) then
                dead = true
            end
        end

        if dead then
            table.remove(emitter.particles, i)
        end
    end
end

function ParticleSystem.renderParticle(ui, emitter, p, ox, oy, c)
    if not ui or not emitter or not p then return end

    local renderFn = emitter.renderFn
    if type(renderFn) == "function" then
        renderFn(ui, emitter, p, ox, oy, c)
        return
    end

    local size = math.max(1, tonumber(p.size) or 1)
    local px = (tonumber(ox) or 0) + safeFloor(p.x)
    local py = (tonumber(oy) or 0) + safeFloor(p.y)
    local alpha = clamp01((c and c[4] or 1) * (tonumber(emitter.alphaScale) or 1.0))
    local r = c and c[1] or 1
    local g = c and c[2] or 1
    local b = c and c[3] or 1

    if p and p.galaxy and p.isCore then
        alpha = math.min(1.0, alpha * 1.18)
    end

    local style = emitter.renderStyle or "pixel"
    if style == "mixed" then
        local speed = particleSpeed(p)
        if speed >= (emitter.mixedSpeedThreshold or 2.2) then
            style = "streak"
        elseif size >= 2 then
            style = "glow"
        else
            style = "soft"
        end
    end

    if style == "soft" then
        drawSoftRect(ui, px, py, size, alpha, r, g, b, emitter.softLayers or 2)
        return
    end

    if style == "glow" then
        local glowSize = emitter.glowSize
        if not glowSize or glowSize <= 0 then
            glowSize = size + 2
        end
        drawGlowRect(ui, px, py, size, alpha, r, g, b, glowSize)
        return
    end

    if style == "streak" then
        drawStreak(
            ui,
            px,
            py,
            size,
            alpha,
            r, g, b,
            p.vx or 0,
            p.vy or 0,
            emitter.stretchByVelocity or 1.4,
            emitter.streakWidth or size,
            emitter.minStretch or size,
            emitter.maxStretch or math.max(size, 8)
        )
        return
    end

    drawRectSafe(ui, px, py, size, size, alpha, r, g, b)
end

function ParticleSystem.renderEmitter(ui, emitter, ox, oy)
    if not ui or not emitter or not emitter.particles then return end
    if type(ui.drawRect) ~= "function" then return end

    ox = tonumber(ox) or 0
    oy = tonumber(oy) or 0

    for _, p in ipairs(emitter.particles) do
        local c = ParticleSystem.getParticleColor(emitter, p)

        if p.history and #p.history > 0 then
            local histCount = #p.history
            local historyAlpha = tonumber(emitter.historyAlpha) or 0.45
            local historyFade = tonumber(emitter.historyFade) or 1.0

            for j = histCount, 1, -1 do
                local hpt = p.history[j]
                local tailT = j / histCount
                local alpha = (c[4] or 1) * historyAlpha * math.pow(1.0 - tailT, historyFade)
                local size = math.max(1, (p.size or 1) - (j >= histCount and 1 or 0))

                if alpha > 0.01 then
                    ParticleSystem.renderParticle(ui, emitter, {
                        x = hpt.x,
                        y = hpt.y,
                        vx = 0,
                        vy = 0,
                        size = size,
                        galaxy = p.galaxy,
                        isCore = false,
                    }, ox, oy, { c[1], c[2], c[3], alpha })
                end
            end
        end

        ParticleSystem.renderParticle(ui, emitter, p, ox, oy, c)
    end
end

return ParticleSystem