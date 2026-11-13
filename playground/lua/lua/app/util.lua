local M = {}

function M.trim(s)
  return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

function M.split(s, sep)
  local out = {}
  for part in s:gmatch("[^" .. sep .. "]+") do
    out[#out + 1] = M.trim(part)
  end
  return out
end

return M
