// CommonJS module (plain JavaScript): `module.exports` and `exports.x` are navigable.
function formatArea(value, unit) {
  return `${value.toFixed(1)} ${unit}²`;
}

exports.separator = " | ";
module.exports.formatArea = formatArea;
