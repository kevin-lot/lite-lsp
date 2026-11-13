variable "cidr" {
  type = string
}

resource "aws_vpc" "main" {
  cidr_block = var.cidr
}

output "vpc_id" {
  value = aws_vpc.main.id
}
