output "queue_arns" {
  value = { for k, q in aws_sqs_queue.work : k => q.arn }
}

output "queue_names" {
  value = { for k, q in aws_sqs_queue.work : k => q.name }
}

output "dlq_arns" {
  value = { for k, q in aws_sqs_queue.dlq : k => q.arn }
}

output "dlq_names" {
  value = { for k, q in aws_sqs_queue.dlq : k => q.name }
}
