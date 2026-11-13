output "bucket_arn" {
  value = aws_s3_bucket.archives.arn
}

output "vpc_id" {
  value = module.network.vpc_id
}

output "account" {
  value = data.aws_caller_identity.current.account_id
}
