# Work queues with dead-letter queues. maxReceiveCount (6) stays above the consumers' explicit retry budget (5):
# consumers dead-letter with a reason; the redrive policy only catches messages whose consumer crashed.
locals {
  queues = toset(var.queue_names)
}

resource "aws_sqs_queue" "dlq" {
  for_each                  = local.queues
  name                      = "${var.prefix}${each.key}-dlq"
  message_retention_seconds = 1209600 # 14 days, the SQS maximum, to leave time for investigation and replay
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "work" {
  for_each                   = local.queues
  name                       = "${var.prefix}${each.key}"
  visibility_timeout_seconds = var.visibility_timeout_seconds
  receive_wait_time_seconds  = 20
  message_retention_seconds  = 345600 # 4 days
  sqs_managed_sse_enabled    = true
  redrive_policy             = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = var.max_receive_count
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each             = local.queues
  queue_url            = aws_sqs_queue.dlq[each.key].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.work[each.key].arn]
  })
}

# Deny any access that is not over TLS.
resource "aws_sqs_queue_policy" "tls_only" {
  for_each  = merge({ for k, q in aws_sqs_queue.work : k => q }, { for k, q in aws_sqs_queue.dlq : "${k}-dlq" => q })
  queue_url = each.value.id
  policy    = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = each.value.arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}
