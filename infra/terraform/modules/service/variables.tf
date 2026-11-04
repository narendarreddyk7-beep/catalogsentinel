variable "name" {
  type = string
}

variable "region" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "vpc_cidr" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "allowed_ingress_cidrs" {
  type        = list(string)
  description = "Networks allowed to reach the load balancer. Narrow this for a private demo."
}

variable "admin_cidrs" {
  type        = list(string)
  description = "Networks allowed to reach /api/v1/admin/* (in addition to the ADMIN API role)."
}

variable "certificate_arn" {
  type        = string
  description = "ACM certificate for the HTTPS listener."
}

variable "image_tag" {
  type = string
}

variable "queue_names" {
  type = map(string)
}

variable "dlq_names" {
  type = map(string)
}

variable "queue_arns" {
  type = map(string)
}

variable "dlq_arns" {
  type = map(string)
}

variable "db_address" {
  type = string
}

variable "db_port" {
  type = number
}

variable "db_secret_arn" {
  type = string
}

variable "log_retention_days" {
  type    = number
  default = 30
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "alb_log_bucket" {
  type        = string
  default     = ""
  description = "S3 bucket for ALB access logs (needs the ELB log-delivery bucket policy); empty disables them."
}

variable "enable_adot" {
  type        = bool
  default     = true
  description = "Run an AWS Distro for OpenTelemetry sidecar: Prometheus scrape to CloudWatch EMF, OTLP traces to X-Ray."
}

variable "adot_image" {
  type    = string
  default = "public.ecr.aws/aws-observability/aws-otel-collector:v0.40.0"
}

variable "trace_sampling" {
  type    = number
  default = 0.1
}

variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memory" {
  type    = number
  default = 1024
}

variable "worker_cpu" {
  type    = number
  default = 512
}

variable "worker_memory" {
  type    = number
  default = 1024
}

variable "api_desired_count" {
  type    = number
  default = 2
}

variable "worker_min_count" {
  type    = number
  default = 1
}

variable "worker_max_count" {
  type    = number
  default = 6
}

variable "scale_out_backlog" {
  type    = number
  default = 1000
}

variable "scale_in_backlog" {
  type    = number
  default = 50
}
