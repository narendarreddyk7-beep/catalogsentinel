variable "prefix" {
  type        = string
  description = "Prefix for queue names, e.g. \"demo-\"."
}

variable "queue_names" {
  type    = list(string)
  default = ["catalog-listing-events", "catalog-change-events"]
}

variable "visibility_timeout_seconds" {
  type    = number
  default = 60
}

variable "max_receive_count" {
  type    = number
  default = 6
}
