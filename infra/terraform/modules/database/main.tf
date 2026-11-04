# PostgreSQL 16 with the master password generated and rotated by RDS in Secrets Manager: no password ever
# appears in Terraform state, variables or the repository.
resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-db"
  subnet_ids = var.subnet_ids
}

resource "aws_security_group" "db" {
  name        = "${var.name}-db"
  description = "PostgreSQL reachable only from the application tasks"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "from_app" {
  for_each                     = toset(var.client_security_group_ids)
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = each.value
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from application tasks"
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-pg16"
  family = "postgres16"
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
  # SSD-backed gp3 storage: random reads are cheap. Keeps literal-valued (custom-planned) title-trigram queries on
  # the GIN index (docs/benchmarking.md#planner-cost-setting).
  parameter {
    name  = "random_page_cost"
    value = "1.1"
  }
}

resource "aws_db_instance" "this" {
  identifier                          = "${var.name}-db"
  engine                              = "postgres"
  engine_version                      = var.engine_version
  instance_class                      = var.instance_class
  allocated_storage                   = var.allocated_storage_gb
  max_allocated_storage               = var.allocated_storage_gb * 4
  storage_type                        = "gp3"
  storage_encrypted                   = true
  db_name                             = "catalogsentinel"
  username                            = "catalog_admin"
  manage_master_user_password         = true
  db_subnet_group_name                = aws_db_subnet_group.this.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  parameter_group_name                = aws_db_parameter_group.this.name
  multi_az                            = var.multi_az
  publicly_accessible                 = false
  backup_retention_period             = 7
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = !var.deletion_protection
  final_snapshot_identifier           = var.deletion_protection ? "${var.name}-db-final" : null
  auto_minor_version_upgrade          = true
  iam_database_authentication_enabled = true
  performance_insights_enabled        = true
  monitoring_interval                 = 0
  enabled_cloudwatch_logs_exports     = ["postgresql"]
}
