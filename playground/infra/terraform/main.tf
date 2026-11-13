provider "aws" {
  region = var.region
}

locals {
  prefix = "playground-${var.env}"
  tags   = { project = "playground", env = var.env }
}

module "network" {
  source = "./modules/network"
  cidr   = "10.0.0.0/16"
}

resource "aws_s3_bucket" "archives" {
  bucket = "${local.prefix}-archives"
  tags   = local.tags
}

data "aws_caller_identity" "current" {}
