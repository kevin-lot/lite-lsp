local Dog = {}
Dog.__index = Dog

function Dog.new(name)
  local self = setmetatable({}, Dog)
  self.name = name
  return self
end

function Dog:bark()
  print(self.name .. ": woof")
end

return Dog
