resource "aws_ecr_repository" "app" {
  name         = var.app_name
  force_delete = true # demo stack: let terraform destroy remove images too

  image_scanning_configuration {
    scan_on_push = true
  }
}
