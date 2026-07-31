output "app_url" {
  description = "Public URL of the deployed application"
  value       = "http://${aws_lb.app.dns_name}"
}

output "ecr_repository_url" {
  description = "Push the application image here"
  value       = aws_ecr_repository.app.repository_url
}

output "region" {
  value = var.region
}
