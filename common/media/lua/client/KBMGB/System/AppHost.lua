-- File: media/lua/client/KBMGB/System/AppHost.lua
-- The "operating system kernel" for desktop apps. Every CRT/console/movie
-- app is plugged into AppHost: it builds a uniform `ctx` table for the app,
-- then routes init/update/render/key/mouse/shutdown calls between the
-- emulator window and the app implementation. Apps written in either the
-- old style (`app.update(ui)`) or new ctx style (`app:update(ctx)`) work.

local VTUtils = require("KBMGB/System/VTUtils")
local clamp = VTUtils.clamp

local AppHost = {}

local ParticleSystem = nil
do
    local ok, mod = pcall(require, "KBMGB/FX/ParticleSystem")
    if ok then
        ParticleSystem = mod
    else
        local ok2, mod2 = pcall(require, "KBMGB.FX.ParticleSystem")
        if ok2 then
            ParticleSystem = mod2
        end
    end
end

local PixelFont = nil
do
    local ok, mod = pcall(require, "KBMGB/FX/PixelFont")
    if ok then PixelFont = mod end
end

-- Calls obj:methodName(...) only if it exists; swallows missing-method errors.
local function safeCallMethod(obj, methodName, ...)
    if not obj then return false, nil end
    local fn = obj[methodName]
    if type(fn) ~= "function" then
        return false, nil
    end
    return pcall(fn, obj, ...)
end

-- True if `app` is one of the new context-aware apps that wants a ctx object.
local function isCtxApp(app)
    local meta = app and (app.meta or app.__vt_meta)
    return type(meta) == "table" and meta.api == "ctx_v1"
end

-- Recomputes screen-space metrics on the ctx after a window resize.
local function rebuildCtxMetrics(ctx)
    local vw = tonumber(ctx.virtualWidth) or 320
    local vh = tonumber(ctx.virtualHeight) or 200
    local hw = tonumber(ctx.hostWidth) or vw
    local hh = tonumber(ctx.hostHeight) or vh

    if vw <= 0 then vw = 320 end
    if vh <= 0 then vh = 200 end
    if hw <= 0 then hw = vw end
    if hh <= 0 then hh = vh end

    ctx.scaleX = hw / vw
    ctx.scaleY = hh / vh
end

-- Builds the ctx ("context") table passed to apps: holds bounds, ui handle,
-- player object, args, etc. so apps don't have to dig those out themselves.
local function makeCtx(app, ui, x, y, w, h, args)
    local meta = app and (app.meta or app.__vt_meta) or {}
    local ctx = {}

    ctx.ui = ui
    ctx.args = args

    ctx.x = tonumber(x) or 0
    ctx.y = tonumber(y) or 0

    ctx.virtualWidth = tonumber(meta.width) or 320
    ctx.virtualHeight = tonumber(meta.height) or 200

    ctx.hostWidth = tonumber(w) or ctx.virtualWidth
    ctx.hostHeight = tonumber(h) or ctx.virtualHeight

    ctx.width = ctx.virtualWidth
    ctx.height = ctx.virtualHeight

    rebuildCtxMetrics(ctx)

    ctx.drawRect = function(px, py, pw, ph, a, r, g, b)
        if not ui or not ui.drawRect then return end

        px = tonumber(px) or 0
        py = tonumber(py) or 0
        pw = tonumber(pw) or 0
        ph = tonumber(ph) or 0

        local sx = ctx.scaleX or 1
        local sy = ctx.scaleY or 1

        local dx = ctx.x + (px * sx)
        local dy = ctx.y + (py * sy)
        local dw = pw * sx
        local dh = ph * sy

        if dw < 1 and pw > 0 then dw = 1 end
        if dh < 1 and ph > 0 then dh = 1 end

        ui:drawRect(dx, dy, dw, dh, a, r, g, b)
    end

    ctx.fillScreen = function(a, r, g, b)
        ctx.drawRect(0, 0, ctx.virtualWidth, ctx.virtualHeight, a, r, g, b)
    end

    ctx.close = function()
        if app then
            app.__vt_requestedClose = true
        end
    end

    ctx.drawText = function(text, px, py, r, g, b, a, scale)
        if PixelFont then
            PixelFont.draw(ctx, text, px, py, r, g, b, a, scale)
        end
    end

    -- Optional passthroughs for apps that can use texture primitives.
    -- These are no-ops when the host UI does not expose matching methods.
    ctx.drawTextureScaled = function(tex, px, py, pw, ph, alpha)
        if not ui or type(ui.drawTextureScaled) ~= "function" then return false end
        local sx = ctx.scaleX or 1
        local sy = ctx.scaleY or 1
        local dx = ctx.x + ((tonumber(px) or 0) * sx)
        local dy = ctx.y + ((tonumber(py) or 0) * sy)
        local dw = (tonumber(pw) or 0) * sx
        local dh = (tonumber(ph) or 0) * sy
        local a = tonumber(alpha)
        if a == nil then a = 1 end
        ui:drawTextureScaled(tex, dx, dy, dw, dh, a)
        return true
    end

    ctx.setStencilRect = function(px, py, pw, ph)
        if not ui or type(ui.setStencilRect) ~= "function" then return false end
        local sx = ctx.scaleX or 1
        local sy = ctx.scaleY or 1
        local dx = ctx.x + ((tonumber(px) or 0) * sx)
        local dy = ctx.y + ((tonumber(py) or 0) * sy)
        local dw = (tonumber(pw) or 0) * sx
        local dh = (tonumber(ph) or 0) * sy
        ui:setStencilRect(dx, dy, dw, dh)
        return true
    end

    ctx.clearStencilRect = function()
        if not ui or type(ui.clearStencilRect) ~= "function" then return false end
        ui:clearStencilRect()
        return true
    end

    ctx.textWidth = function(text, scale)
        if PixelFont then
            return PixelFont.width(text, scale)
        end
        return 0
    end

    ctx.drawCenteredText = function(text, py, r, g, b, a, scale)
        if PixelFont then
            PixelFont.centered(ctx, text, py, r, g, b, a, scale)
        end
    end

    ctx.drawRightText = function(text, rightX, py, r, g, b, a, scale)
        if PixelFont then
            PixelFont.right(ctx, text, rightX, py, r, g, b, a, scale)
        end
    end

    ctx.getMouseX = function()
        local mx = 0
        if _G.getMouseX then
            mx = _G.getMouseX()
        end
        if ui then
            mx = mx - (tonumber(ui.x) or 0)
        end
        local sx = ctx.scaleX or 1
        if sx == 0 then sx = 1 end
        return (mx - ctx.x) / sx
    end

    ctx.getMouseY = function()
        local my = 0
        if _G.getMouseY then
            my = _G.getMouseY()
        end
        if ui then
            my = my - (tonumber(ui.y) or 0)
        end
        local sy = ctx.scaleY or 1
        if sy == 0 then sy = 1 end
        return (my - ctx.y) / sy
    end

    ctx.particles = {
        newEmitter = function(opts)
            if ParticleSystem and ParticleSystem.newEmitter then
                return ParticleSystem.newEmitter(0, 0, opts or {})
            end
            return nil
        end,

        setPosition = function(emitter, px, py)
            if ParticleSystem and ParticleSystem.setPosition then
                ParticleSystem.setPosition(emitter, px, py)
            end
        end,

        setBounds = function(emitter, bounds)
            if ParticleSystem and ParticleSystem.setBounds then
                ParticleSystem.setBounds(emitter, bounds)
            end
        end,

        spawn = function(emitter, count, opts)
            if ParticleSystem and ParticleSystem.spawn then
                ParticleSystem.spawn(emitter, count, opts)
            end
        end,

        burstAt = function(emitter, px, py, count, opts)
            if ParticleSystem and ParticleSystem.burstAt then
                ParticleSystem.burstAt(emitter, px, py, count, opts)
            end
        end,

        update = function(emitter, dt)
            if ParticleSystem and ParticleSystem.updateEmitter then
                ParticleSystem.updateEmitter(emitter, dt)
            end
        end,

        clear = function(emitter)
            if ParticleSystem and ParticleSystem.clear then
                ParticleSystem.clear(emitter)
            end
        end,

        render = function(emitter)
            if ParticleSystem and ParticleSystem.renderEmitter then
                local uiShim = {
                    drawRect = function(_, rx, ry, rw, rh, a, r, g, b)
                        ctx.drawRect(rx, ry, rw, rh, a, r, g, b)
                    end
                }
                ParticleSystem.renderEmitter(uiShim, emitter, 0, 0)
            end
        end,

        palettes = ParticleSystem and ParticleSystem.palettes or {},
        behaviors = ParticleSystem and ParticleSystem.behaviors or {},
    }

    return ctx
end

-- Wraps a legacy non-ctx app in a stub that mimics the new lifecycle methods.
function AppHost.wrap(app, meta)
    app = app or {}
    meta = meta or {}

    app.__vt_hosted = true
    app.__vt_meta = meta
    app.__vt_requestedClose = false

    if type(app.getTitle) ~= "function" then
        function app:getTitle()
            local m = self.meta or meta
            return m.title or m.name or m.id or "Application"
        end
    end

    if type(app.init) ~= "function" then
        function app:init(_) end
    end

    if type(app.update) ~= "function" then
        function app:update(_) end
    end

    if type(app.render) ~= "function" then
        function app:render(_) end
    end

    if type(app.onKeyDown) ~= "function" then
        function app:onKeyDown(_, _key)
            return false
        end
    end

    if type(app.onKey) ~= "function" then
        function app:onKey(_, _key)
            return false
        end
    end

    if type(app.onMouseDown) ~= "function" then
        function app:onMouseDown(_, _x, _y)
            return false
        end
    end

    if type(app.onEnter) ~= "function" then
        function app:onEnter(_, _text)
            return false
        end
    end

    if type(app.shutdown) ~= "function" then
        function app:shutdown(_) end
    end

    if type(app.wantsClose) ~= "function" then
        function app:wantsClose()
            return false
        end
    end

    if type(app.requestClose) ~= "function" then
        function app:requestClose()
            self.__vt_requestedClose = true
        end
    end

    return app
end

-- Creates a fresh app instance from its module, with optional args/meta.
function AppHost.instantiate(AppModule, args, meta)
    if not AppModule then return nil, "nil app module" end

    local app

    if type(AppModule.new) == "function" then
        local ok, result = pcall(AppModule.new, AppModule, args)
        if ok and result then
            app = result
        else
            local ok2, result2 = pcall(AppModule.new, args)
            if ok2 and result2 then
                app = result2
            else
                return nil, tostring(result or result2 or "app constructor failed")
            end
        end
    elseif type(AppModule) == "table" then
        app = AppModule
    else
        return nil, "invalid app module type"
    end

    return AppHost.wrap(app, meta)
end

-- One-time setup: builds the ctx and forwards init() to the app.
function AppHost.init(app, ui, x, y, w, h, args)
    if isCtxApp(app) then
        app.__vt_ctx = makeCtx(app, ui, x, y, w, h, args)
        local ok, err = safeCallMethod(app, "init", app.__vt_ctx)
        if not ok and err ~= nil then
            return false, err
        end
        return true, nil
    end

    local ok, err = safeCallMethod(app, "init", ui, x, y, w, h, args)
    if not ok and err ~= nil then
        return false, err
    end
    return true, nil
end

-- Per-tick update; refreshes ctx metrics and forwards to the app.
function AppHost.update(app, ui)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx
        local dt = 1 / (((app.meta or {}).ticksPerSecond) or 30)
        local ok, err = safeCallMethod(app, "update", ctx, dt)
        if not ok and err ~= nil then
            return false, err
        end
        return true, nil
    end

    local ok, err = safeCallMethod(app, "update", ui)
    if not ok and err ~= nil then
        return false, err
    end
    return true, nil
end

-- Per-frame draw; sets up the ctx region and asks the app to render.
function AppHost.render(app, ui, x, y, w, h)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, x, y, w, h, nil)

        ctx.ui = ui
        ctx.x = tonumber(x) or 0
        ctx.y = tonumber(y) or 0
        ctx.virtualWidth = tonumber((app.meta or {}).width) or ctx.virtualWidth or 320
        ctx.virtualHeight = tonumber((app.meta or {}).height) or ctx.virtualHeight or 200
        ctx.hostWidth = tonumber(w) or ctx.hostWidth or ctx.virtualWidth
        ctx.hostHeight = tonumber(h) or ctx.hostHeight or ctx.virtualHeight
        ctx.width = ctx.virtualWidth
        ctx.height = ctx.virtualHeight

        rebuildCtxMetrics(ctx)

        app.__vt_ctx = ctx

        local ok, err = safeCallMethod(app, "render", ctx)
        if not ok and err ~= nil then
            return false, err
        end
        return true, nil
    end

    local ok, err = safeCallMethod(app, "render", ui, x, y, w, h)
    if not ok and err ~= nil then
        return false, err
    end
    return true, nil
end

-- Forwards a key-press event to the app.
function AppHost.onKeyDown(app, ui, key)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx
        local fn = app and app.onKeyDown
        if type(fn) ~= "function" then return false end
        local ok, handled = pcall(fn, app, ctx, key)
        if not ok then return false end
        return handled == true
    end

    local fn = app and app.onKeyDown
    if type(fn) ~= "function" then return false end
    local ok, handled = pcall(fn, app, ui, key)
    if not ok then return false end
    return handled == true
end

-- Forwards a key-release / key event to the app.
function AppHost.onKey(app, ui, key)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx
        local fn = app and app.onKey
        if type(fn) ~= "function" then return false end
        local ok, handled = pcall(fn, app, ctx, key)
        if not ok then return false end
        return handled == true
    end

    local fn = app and app.onKey
    if type(fn) ~= "function" then return false end
    local ok, handled = pcall(fn, app, ui, key)
    if not ok then return false end
    return handled == true
end

-- Forwards a mouse-down event (in screen coords) to the app.
function AppHost.onMouseDown(app, ui, x, y)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx

        local localX = tonumber(x) or 0
        local localY = tonumber(y) or 0

        local sx = ctx.scaleX or 1
        local sy = ctx.scaleY or 1
        if sx == 0 then sx = 1 end
        if sy == 0 then sy = 1 end

        localX = (localX - (ctx.x or 0)) / sx
        localY = (localY - (ctx.y or 0)) / sy

        local fn = app and app.onMouseDown
        if type(fn) ~= "function" then return false end
        local ok, handled = pcall(fn, app, ctx, localX, localY)
        if not ok then return false end
        return handled == true
    end

    local fn = app and app.onMouseDown
    if type(fn) ~= "function" then return false end
    local ok, handled = pcall(fn, app, ui, x, y)
    if not ok then return false end
    return handled == true
end

-- Forwards a mouse-up event (in screen coords) to the app.
function AppHost.onMouseUp(app, ui, x, y)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx

        local localX = tonumber(x) or 0
        local localY = tonumber(y) or 0

        local sx = ctx.scaleX or 1
        local sy = ctx.scaleY or 1
        if sx == 0 then sx = 1 end
        if sy == 0 then sy = 1 end

        localX = (localX - (ctx.x or 0)) / sx
        localY = (localY - (ctx.y or 0)) / sy

        local fn = app and app.onMouseUp
        if type(fn) ~= "function" then return false end
        local ok, handled = pcall(fn, app, ctx, localX, localY)
        if not ok then return false end
        return handled == true
    end

    local fn = app and app.onMouseUp
    if type(fn) ~= "function" then return false end
    local ok, handled = pcall(fn, app, ui, x, y)
    if not ok then return false end
    return handled == true
end

-- Forwards a typed-text event (Enter pressed in input box) to the app.
function AppHost.onEnter(app, ui, text)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx
        local fn = app and app.onEnter
        if type(fn) ~= "function" then return false end
        local ok, handled = pcall(fn, app, ctx, text)
        if not ok then return false end
        return handled == true
    end

    local fn = app and app.onEnter
    if type(fn) ~= "function" then return false end
    local ok, handled = pcall(fn, app, ui, text)
    if not ok then return false end
    return handled == true
end

-- Tells the app to clean up sounds, timers, etc. before its window closes.
function AppHost.shutdown(app, ui)
    if isCtxApp(app) then
        local ctx = app.__vt_ctx or makeCtx(app, ui, 0, 0, 0, 0, nil)
        app.__vt_ctx = ctx
        local ok, err = safeCallMethod(app, "shutdown", ctx)
        if not ok and err ~= nil then
            return false, err
        end
        return true, nil
    end

    local ok, err = safeCallMethod(app, "shutdown", ui)
    if not ok and err ~= nil then
        return false, err
    end
    return true, nil
end

-- True if the app has asked to close itself ("quit" button was hit).
function AppHost.wantsClose(app)
    if not app then return false end
    if app.__vt_requestedClose == true then return true end
    local fn = app.wantsClose
    if type(fn) ~= "function" then return false end
    local ok, result = pcall(fn, app)
    if not ok then return false end
    return result == true
end

return AppHost