-- Lua tour: every name below is a Ctrl+Click target.
local util = require("app.util")        -- → lua/app/util.lua
local Dog = require("app.dog")          -- → lua/app/dog.lua
local config = require("app.config")

local rex = Dog.new("Rex")              -- Dog.new → function Dog.new
rex:bark()                              -- → function Dog:bark (through setmetatable / __index)
print(rex.name)                         -- → self.name = name

local words = util.split(config.greeting, " ")
for i, word in ipairs(words) do          -- word → the loop variable
  print(i, util.trim(word))              -- util.trim → M.trim
end

local function count(t)                  -- local function: visible in its own body
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

print(count(words), config.port, VERSION) -- VERSION → global defined in app/config.lua
