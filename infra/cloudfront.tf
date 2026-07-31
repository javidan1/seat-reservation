# Browser-trusted TLS without owning a domain: CloudFront's default certificate
# terminates HTTPS at the edge and forwards to the ALB. Caching is disabled (this
# is a TLS/edge layer, not a cache) and the ALB only admits CloudFront's
# origin-facing IP range, so the plain-HTTP path cannot be reached directly.
# Honest limit, stated: edge→origin is HTTP; end-to-end TLS needs a real domain
# with an ACM cert on the ALB.

resource "aws_cloudfront_distribution" "app" {
  enabled      = true
  comment      = "${var.app_name} TLS edge"
  price_class  = "PriceClass_100"
  http_version = "http2"

  origin {
    domain_name = aws_lb.app.dns_name
    origin_id   = "alb"

    custom_origin_config {
      http_port                = 80
      https_port               = 443
      origin_protocol_policy   = "http-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = 30 # SSE heartbeats every 15s keep streams alive
      origin_keepalive_timeout = 5
    }
  }

  default_cache_behavior {
    target_origin_id       = "alb"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods         = ["GET", "HEAD"]
    # AWS managed policies: CachingDisabled + AllViewerExceptHostHeader.
    cache_policy_id          = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
    origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
