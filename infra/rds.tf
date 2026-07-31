resource "aws_db_subnet_group" "postgres" {
  name       = "${var.app_name}-db"
  subnet_ids = aws_subnet.private[*].id
}

# RDS owns the master password (Secrets Manager, auto-created): Terraform never
# generates or stores it, so no state file — local or remote — ever contains it.
resource "aws_db_instance" "postgres" {
  identifier                  = "${var.app_name}-db"
  engine                      = "postgres"
  engine_version              = "16"
  instance_class              = "db.t4g.micro"
  allocated_storage           = 20
  storage_type                = "gp3"
  db_name                     = "seatres"
  username                    = "seatres"
  manage_master_user_password = true
  db_subnet_group_name        = aws_db_subnet_group.postgres.name
  vpc_security_group_ids      = [aws_security_group.db.id]
  publicly_accessible         = false
  multi_az                    = false

  # Demo stack: destroyable without ceremony. A production database would set
  # deletion_protection, final snapshots, and backups.
  skip_final_snapshot     = true
  deletion_protection     = false
  backup_retention_period = 0

  tags = { Name = "${var.app_name}-db" }
}
