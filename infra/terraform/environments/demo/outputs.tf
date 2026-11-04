output "url" {
  value = "https://${module.service.alb_dns_name}"
}

output "ecr_repositories" {
  value = module.service.ecr_repositories
}

output "api_keys_secret_arn" {
  value       = module.service.api_keys_secret_arn
  description = "Put the API key entries here out of band (never in Terraform)."
}

output "batch_policy_arn" {
  value = aws_iam_policy.batch.arn
}

output "alarm_topic_arn" {
  value = module.observability.alarm_topic_arn
}
