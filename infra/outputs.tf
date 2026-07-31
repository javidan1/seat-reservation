output "app_url" {
  description = "Public HTTPS URL of the deployed application (CloudFront TLS edge)"
  value       = "https://${aws_cloudfront_distribution.app.domain_name}"
}

output "alb_dns" {
  description = "Origin ALB (only reachable from CloudFront's IP range)"
  value       = aws_lb.app.dns_name
}

output "ecr_repository_url" {
  description = "Push the application image here"
  value       = aws_ecr_repository.app.repository_url
}

output "region" {
  value = var.region
}
