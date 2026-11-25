# Ctrl+Click: `common.in_department`, `data.common.has_role`, `is_editor`, `level`, `limits.maximum`.
package shapes.access

import data.common
import data.shapes.limits
import rego.v1

default is_editor := false

is_editor if {
	common.in_department("DESIGN")
}

is_editor if {
	data.common.has_role("ADMIN")
}

allow if {
	is_editor
	level := input.shape.level
	level <= limits.maximum
}

editable contains shape if {
	some shape in input.shapes
	shape.owner == input.user.id
}
