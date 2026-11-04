output "bucket_name" {
  value = aws_s3_bucket.batch.bucket
}

output "bucket_arn" {
  value = aws_s3_bucket.batch.arn
}
