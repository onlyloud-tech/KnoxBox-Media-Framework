-- AppWindowBase.lua
-- Common pop-up panel that hosts an AppHost-managed app inside a viewport
-- rectangle. EmulatorWindow / GameBoyWindow / NESConsoleWindow all derive
-- from this and only override:
--   :new()               - sizing, background colour, overlay texture, etc.
--   :getAppViewport()    - returns (x, y, w, h) where the app is drawn
--   mouse / key handlers - per-window behaviour
-- and otherwise reuse the shared init / focus / prerender / render / close
-- implementations defined below.

require "ISUI/ISPanel"

local AppHost = require("KBMGB/System/AppHost")
local VTUtils = require("KBMGB/System/VTUtils")

local AppWindowBase = ISPanel:derive("AppWindowBase")

-- Subclasses are expected to set these on the instance during :new()
-- (any of them may be left nil):
--   self.AppModule       module passed to AppHost.instantiate
--   self.args            args table for AppHost.init / instantiate
--   self.modalLockId     string id passed to monitorUI:_unlockInputForModal
--   self.playerObj       IsoPlayer to lock while the window is open
--   self.overlayTex      texture drawn on top of the app (e.g. GB shell)
--   self.captureMouse    if true, _ensureTopAndFocus also calls setCapture(true)
--   self.appBackgroundColor / appBorderColor (optional, set by ISPanel.new)

-- Standard ISPanel hook called once after construction.
function AppWindowBase:initialise()
    ISPanel.initialise(self)
end

-- Creates the embedded app and grabs keyboard focus.
function AppWindowBase:createChildren()
    ISPanel.createChildren(self)
    self:_initApp()
    self:_ensureTopAndFocus()
end

-- Forces the window above other UI and tells PZ we want key events.
-- If self.captureMouse is true, also grabs mouse capture.
function AppWindowBase:_ensureTopAndFocus()
    if self.setAlwaysOnTop then pcall(self.setAlwaysOnTop, self, true) end
    if self.setWantKeyEvents then pcall(self.setWantKeyEvents, self, true) end
    if self.captureMouse and self.setCapture then
        pcall(self.setCapture, self, true)
    end
end

-- Builds the embedded app instance and tells it the viewport rectangle.
-- Subclass must implement :getAppViewport() returning (x, y, w, h).
function AppWindowBase:_initApp()
    local app, err = AppHost.instantiate(self.AppModule, self.args,
        type(self.AppModule) == "table" and self.AppModule.meta or nil)
    if not app then
        self._initErr = tostring(err or "app constructor failed")
        return
    end

    self.app = app
    local ax, ay, aw, ah = self:getAppViewport()
    local ok, initErr = AppHost.init(self.app, self, ax, ay, aw, ah, self.args)
    if not ok then
        self._initErr = tostring(initErr or "app init failed")
        self.app = nil
    end
end

-- Tells PZ no global hotkey should fire while the window is open.
function AppWindowBase:isKeyConsumed(_key)
    return true
end

-- Per-frame work before drawing: keep focus, tick the app, close if asked.
function AppWindowBase:prerender()
    ISPanel.prerender(self)
    self:_ensureTopAndFocus()

    if self.app then
        AppHost.update(self.app, self)
        if AppHost.wantsClose(self.app) then
            self:close()
            return
        end
    end
end

-- Default render: draws the app inside the viewport (with stencil clipping
-- if available) and an optional overlay texture on top. Subclasses may
-- override to add debug dots, calibration UI, etc.
function AppWindowBase:render()
    ISPanel.render(self)

    local ax, ay, aw, ah = self:getAppViewport()
    if self.setStencilRect and self.clearStencilRect then
        self:setStencilRect(ax, ay, aw, ah)
    end
    if self._initErr then
        self:drawText("APP INIT FAILED", ax, ay, 1, 0.3, 0.3, 1, UIFont.Small)
        self:drawText(tostring(self._initErr), ax, ay + 16, 0.8, 0.8, 0.8, 1, UIFont.Small)
    elseif self.app then
        AppHost.render(self.app, self, ax, ay, aw, ah)
    end
    if self.setStencilRect and self.clearStencilRect then
        self:clearStencilRect()
    end

    if self.overlayTex then
        self:drawTextureScaled(self.overlayTex, 0, 0, self.width, self.height, 1)
    end
end

-- Cleanly shuts down the app, releases capture, unlocks modal/player,
-- and removes the window from the UI.
function AppWindowBase:close()
    if self.app then
        AppHost.shutdown(self.app, self)
    end
    if self.monitorUI and self.monitorUI._unlockInputForModal then
        pcall(self.monitorUI._unlockInputForModal, self.monitorUI, self.modalLockId)
    end
    if self.setCapture then pcall(self.setCapture, self, false) end
    if self.setWantKeyEvents then pcall(self.setWantKeyEvents, self, false) end
    if self.playerObj then
        VTUtils.setPlayerLocked(self.playerObj, false)
    end
    self:removeFromUIManager()
end

return AppWindowBase
