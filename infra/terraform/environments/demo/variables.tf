variable "region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  type    = string
  default = "demo"
}

variable "image_tag" {
  type        = string
  description = "Image tag pushed by the deploy workflow (git SHA prefix)."
}

variable "certificate_arn" {
  type        = string
  description = "ACM certificate ARN for the HTTPS listener (DNS-validated, for your domain)."
}

variable "allowed_ingress_cidrs" {
  type        = list(string)
  description = "Who may reach the load balancer. Use your office/VPN ranges for a private demo."
}

variable "admin_cidrs" {
  type        = list(string)
  description = "Who may reach /api/v1/admin/*."
}

variable "batch_bucket_name" {
  type = string
}

variable "alarm_emails" {
  type    = list(string)
  default = []
}

variable "deletion_protection" {
  type    = bool
  default = true
}
