variable "region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "app_name" {
  description = "Name prefix for all resources"
  type        = string
  default     = "seat-reservation"
}

variable "image_tag" {
  description = "Tag of the container image in ECR to deploy"
  type        = string
  default     = "latest"
}

# 1 keeps the SSE live-view consistent for a demo. Scale to 2+ to run the
# multi-instance no-double-sell guarantee in the cloud (correctness is
# instance-agnostic by design; only SSE fan-out is per-instance — documented cut).
variable "app_count" {
  description = "Number of Fargate tasks behind the load balancer"
  type        = number
  default     = 1
}
