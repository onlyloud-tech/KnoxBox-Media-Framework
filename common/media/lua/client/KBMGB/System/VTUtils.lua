-- File: media/lua/client/KBMGB/System/VTUtils.lua
-- Shared utility functions for KnoxBox Media.
-- Eliminates redundant local definitions across the codebase.

local VTUtils = {}

-- Restricts a number to a [a..b] range.
function VTUtils.clamp(n, a, b)
    n = tonumber(n) or 0
    a = tonumber(a) or 0
    b = tonumber(b) or 0
    if n < a then return a end
    if n > b then return b end
    return n
end

-- Restricts a number to [0..1].
function VTUtils.clamp01(a)
    a = tonumber(a) or 0
    if a < 0 then return 0 end
    if a > 1 then return 1 end
    return a
end

-- Returns current time in milliseconds, trying several PZ APIs in turn.
function VTUtils.nowMs()
    if _G.getTimestampMs then return _G.getTimestampMs() end
    return math.floor(os.clock() * 1000)
end

-- Like require(), but returns nil instead of crashing if the module is missing.
function VTUtils.safeRequire(path)
    local ok, mod = pcall(require, path)
    if ok then return mod end
    local alt
    if tostring(path):find("/") then
        alt = tostring(path):gsub("/", ".")
    else
        alt = tostring(path):gsub("%.", "/")
    end
    local ok2, mod2 = pcall(require, alt)
    if ok2 then return mod2 end
    return nil
end

-- True if the point (px,py) is inside the rectangle (rx,ry,rw,rh).
function VTUtils.pointInRect(px, py, rx, ry, rw, rh)
    return px >= rx and px <= (rx + rw) and py >= ry and py <= (ry + rh)
end

-- Returns the current screen width and height, with sane defaults if the
-- PZ API isn't available.
function VTUtils.safeGetScreen()
    local w, h = 1280, 720
    if getCore and getCore() then
        local c = getCore()
        if c.getScreenWidth then w = c:getScreenWidth() end
        if c.getScreenHeight then h = c:getScreenHeight() end
    end
    return w, h
end

-- Wrapper around isKeyDown() that handles missing globals gracefully.
function VTUtils.keyDown(key)
    local core = _G.getCore and _G.getCore() or nil
    if core and core.isKeyDown then
        return core:isKeyDown(key) == true
    end
    if _G.isKeyDown then
        return _G.isKeyDown(key) == true
    end
    return false
end

-- Media/VHS utility constants and functions (merged from KBM_LOCK_MODS_Utils)
VTUtils.DEFAULT_VOLUME = 0.8
VTUtils.MIN_VOLUME = 0.0
VTUtils.MAX_VOLUME = 1.0

-- Restricts a volume value to [0..1].
function VTUtils.clampVolume(v)
    v = tonumber(v) or VTUtils.DEFAULT_VOLUME
    if v < VTUtils.MIN_VOLUME then v = VTUtils.MIN_VOLUME end
    if v > VTUtils.MAX_VOLUME then v = VTUtils.MAX_VOLUME end
    return v
end

-- True if the given world object is a TV.
-- Placed KnoxBox TVs expose an IsoRadio alongside the visible world item.
-- Its sprite need not have "television" in the name, so identify the item
-- through the RadioItemID link used by the TV's own device-options menu.
local KNOXBOX_3D_TV_TYPES = {
    ["KBM.ScreenTV"] = true,
    ["KBMViewpointProbe.ScreenTV"] = true,
}

function VTUtils.isKnoxBox3DTV(obj)
    if not obj then return false end
    local item = obj.getItem and obj:getItem() or nil
    if item and item.getFullType and KNOXBOX_3D_TV_TYPES[item:getFullType()] then
        return true
    end
    local data = obj.getModData and obj:getModData() or nil
    local itemId = data and data.RadioItemID
    local square = obj.getSquare and obj:getSquare() or nil
    local worlds = square and square.getWorldObjects and square:getWorldObjects() or nil
    if not itemId or not worlds then return false end
    for i = 0, worlds:size() - 1 do
        local world = worlds:get(i)
        local linked = world and world.getItem and world:getItem() or nil
        if linked and linked.getID and linked:getID() == itemId
            and KNOXBOX_3D_TV_TYPES[linked:getFullType()] then return true end
    end
    return false
end

function VTUtils.isTelevisionObject(obj)
    if not obj then return false end
    if VTUtils.isKnoxBox3DTV(obj) then return true end
    if instanceof and instanceof(obj, "IsoTelevision") then return true end
    if obj.getSprite and obj:getSprite() and obj:getSprite():getName() then
        local name = tostring(obj:getSprite():getName() or ""):lower()
        if name:find("television") or name:find("tv") then return true end
    end
    return false
end

-- Freezes (or unfreezes) the player so their character doesn't walk around
-- or swing weapons while an emulator/media window is open.
function VTUtils.setPlayerLocked(playerObj, locked)
    if not playerObj then return end
    local flag = locked and true or false
    if playerObj.setIgnoreInputs then pcall(playerObj.setIgnoreInputs, playerObj, flag) end
    if playerObj.setBlockMovement then pcall(playerObj.setBlockMovement, playerObj, flag) end
    if playerObj.setIgnoreInputsForDirection then pcall(playerObj.setIgnoreInputsForDirection, playerObj, flag) end
end

-- Sets the volume on a sound handle, dealing with several FMOD API quirks.
function VTUtils.applyHandleVolume(handle, value)
    if not handle then return false end
    local v = VTUtils.clampVolume(value)
    if type(handle) == "table" and handle.setVolume then
        local ok = pcall(handle.setVolume, handle, v)
        if ok then return true end
    end
    if type(handle) == "userdata" then
        local ok = pcall(function() handle:setVolume(v) end)
        if ok then return true end
    end
    return false
end

return VTUtils
