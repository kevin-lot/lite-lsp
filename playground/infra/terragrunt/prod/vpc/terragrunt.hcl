terraform {
  source = "../../../terraform/modules/network"
}

inputs = {
  cidr = "10.1.0.0/16"
}
