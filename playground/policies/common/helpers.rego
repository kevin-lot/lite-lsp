package common

import rego.v1

in_department(name) if {
	input.user.department == name
}

has_role(role) if {
	some granted in input.user.roles
	granted == role
}
