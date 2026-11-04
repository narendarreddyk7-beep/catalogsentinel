variable "bucket_name" {
  type = string
}

variable "force_destroy" {
  type    = bool
  default = false
}

variable "rejected_retention_days" {
  type    = number
  default = 90
}
