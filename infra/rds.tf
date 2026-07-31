resource "aws_db_subnet_group" "postgres" {
  name       = "${var.app_name}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "random_password" "db" {
  length  = 32
  special = false
}

# The password lives in SSM, never in a task definition or state-adjacent file
# an operator would casually read.
resource "aws_ssm_parameter" "db_password" {
  name  = "/${var.app_name}/db-password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_db_instance" "postgres" {
  identifier            = "${var.app_name}-db"
  engine                = "postgres"
  engine_version        = "16"
  instance_class        = "db.t4g.micro"
  allocated_storage     = 20
  storage_type          = "gp3"
  db_name               = "seatres"
  username              = "seatres"
  password              = random_password.db.result
  db_subnet_group_name  = aws_db_subnet_group.postgres.name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible   = false
  multi_az              = false

  # Demo stack: destroyable without ceremony. A production database would set
  # deletion_protection, final snapshots, and backups.
  skip_final_snapshot     = true
  deletion_protection     = false
  backup_retention_period = 0

  tags = { Name = "${var.app_name}-db" }
}
