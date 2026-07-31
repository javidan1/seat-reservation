resource "aws_lb" "app" {
  name               = var.app_name
  load_balancer_type = "application"
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  # SSE streams are quiet between seat changes; the app heartbeats every 15s,
  # so the default 60s idle timeout is safe — stated here so nobody "tunes" it away.
  idle_timeout = 60
}

resource "aws_lb_target_group" "app" {
  name        = var.app_name
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = aws_vpc.main.id
  target_type = "ip"

  deregistration_delay = 30

  health_check {
    path                = "/api/events"
    matcher             = "200"
    interval            = 30
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
