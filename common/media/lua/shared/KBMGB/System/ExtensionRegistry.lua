local existing = rawget(_G, "KBMExtensionRegistry")
if existing then return existing end

local Registry = {
    apiVersion = 1,
    devices = {},
    apps = {},
    mediaProviders = {},
}

local function register(target, kind, definition)
    if type(definition) ~= "table" then error("KBM " .. kind .. " registration must be a table") end
    local id = definition.id
    if type(id) ~= "string" or id == "" then error("KBM " .. kind .. " registration requires an id") end
    if type(definition.modId) ~= "string" or definition.modId == "" then
        error("KBM " .. kind .. " registration requires a modId")
    end
    if target[id] then error("duplicate KBM " .. kind .. " id: " .. id) end
    target[id] = definition
    return definition
end

local function sortedValues(target)
    local ids = {}
    for id in pairs(target) do ids[#ids + 1] = id end
    table.sort(ids)
    local values = {}
    for _, id in ipairs(ids) do values[#values + 1] = target[id] end
    return values
end

function Registry.registerDevice(definition)
    return register(Registry.devices, "device", definition)
end

function Registry.registerApp(definition)
    return register(Registry.apps, "app", definition)
end

function Registry.registerMediaProvider(definition)
    return register(Registry.mediaProviders, "media provider", definition)
end

function Registry.getDevice(id) return Registry.devices[id] end
function Registry.getApp(id) return Registry.apps[id] end
function Registry.getMediaProvider(id) return Registry.mediaProviders[id] end
function Registry.listDevices() return sortedValues(Registry.devices) end
function Registry.listApps() return sortedValues(Registry.apps) end
function Registry.listMediaProviders() return sortedValues(Registry.mediaProviders) end

_G.KBMExtensionRegistry = Registry
_G.KBM_RegisterDevice = Registry.registerDevice
_G.KBM_RegisterApp = Registry.registerApp
_G.KBM_RegisterMediaProvider = Registry.registerMediaProvider
return Registry
