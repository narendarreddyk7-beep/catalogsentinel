variable "name" {
  type = string
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}

variable "queue_names" {
  type = map(string)
}

variable "dlq_names" {
  type = map(string)
}

variable "alb_arn_suffix" {
  type = string
}

variable "api_target_group_arn_suffix" {
  type = string
}

variable "db_instance_id" {
  type = string
}

variable "max_message_age_seconds" {
  type    = number
  default = 300
}

variable "api_p95_seconds" {
  type    = number
  default = 1
}
