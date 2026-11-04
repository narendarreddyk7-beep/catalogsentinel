# The whole deployment: network, database, queues, bucket, services and alarms.
# Applying this creates billable AWS resources (NAT gateway, load balancer, RDS, Fargate tasks, Secrets Manager,
# CloudWatch). Nothing in this repository applies it automatically; see docs/deployment.md.
locals {
  name = "catalogsentinel-${var.environment}"
}

module "network" {
  source = "../../modules/network"
  name   = local.name
  region = var.region
}

module "messaging" {
  source = "../../modules/messaging"
  prefix = "${var.environment}-"
}

module "storage" {
  source      = "../../modules/storage"
  bucket_name = var.batch_bucket_name
}

module "service" {
  source                = "../../modules/service"
  name                  = local.name
  region                = var.region
  vpc_id                = module.network.vpc_id
  vpc_cidr              = "10.40.0.0/16"
  public_subnet_ids     = module.network.public_subnet_ids
  private_subnet_ids    = module.network.private_subnet_ids
  allowed_ingress_cidrs = var.allowed_ingress_cidrs
  admin_cidrs           = var.admin_cidrs
  certificate_arn       = var.certificate_arn
  image_tag             = var.image_tag
  queue_names           = module.messaging.queue_names
  dlq_names             = module.messaging.dlq_names
  queue_arns            = module.messaging.queue_arns
  dlq_arns              = module.messaging.dlq_arns
  db_address            = module.database.address
  db_port               = module.database.port
  db_secret_arn         = module.database.master_secret_arn
  deletion_protection   = var.deletion_protection
}

module "database" {
  source                    = "../../modules/database"
  name                      = local.name
  vpc_id                    = module.network.vpc_id
  subnet_ids                = module.network.private_subnet_ids
  client_security_group_ids = [module.service.task_security_group_id]
  deletion_protection       = var.deletion_protection
}

module "observability" {
  source                      = "../../modules/observability"
  name                        = local.name
  alarm_emails                = var.alarm_emails
  queue_names                 = module.messaging.queue_names
  dlq_names                   = module.messaging.dlq_names
  alb_arn_suffix              = module.service.alb_arn_suffix
  api_target_group_arn_suffix = module.service.api_target_group_arn_suffix
  db_instance_id              = module.database.instance_id
}

# Least-privilege role for running the batch job (EMR Serverless, ECS or a CI runner): read incoming exports,
# write curated/rejected/state prefixes, enqueue listing events. Nothing else.
data "aws_iam_policy_document" "batch" {
  statement {
    sid       = "ReadExports"
    actions   = ["s3:GetObject"]
    resources = ["${module.storage.bucket_arn}/incoming/*"]
  }
  statement {
    sid       = "WriteOutputs"
    actions   = ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"]
    resources = ["${module.storage.bucket_arn}/curated/*", "${module.storage.bucket_arn}/rejected/*", "${module.storage.bucket_arn}/_state/*"]
  }
  statement {
    sid       = "ListPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.storage.bucket_arn]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["incoming/*", "curated/*", "rejected/*", "_state/*"]
    }
  }
  statement {
    sid       = "PublishListingEvents"
    actions   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
    resources = [module.messaging.queue_arns["catalog-listing-events"]]
  }
}

resource "aws_iam_policy" "batch" {
  name   = "${local.name}-batch"
  policy = data.aws_iam_policy_document.batch.json
}
