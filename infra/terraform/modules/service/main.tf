# ECS Fargate services behind an HTTPS load balancer: the API, the ingestion worker and the dashboard.
# Every task gets its own least-privilege task role; secrets come from Secrets Manager at task start.

data "aws_caller_identity" "current" {}

locals {
  account_id  = data.aws_caller_identity.current.account_id
  log_prefix  = "/catalogsentinel/${var.name}"
  image       = { for app in ["catalog-api", "ingestion-worker", "dashboard"] : app => "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com/catalogsentinel/${app}:${var.image_tag}" }
  listing_q   = var.queue_names["catalog-listing-events"]
  change_q    = var.queue_names["catalog-change-events"]
  listing_dlq = var.dlq_names["catalog-listing-events"]
  change_dlq  = var.dlq_names["catalog-change-events"]
  db_url      = "jdbc:postgresql://${var.db_address}:${var.db_port}/catalogsentinel?sslmode=require"
  queue_env   = [
    { name = "AWS_REGION", value = var.region },
    { name = "LISTING_EVENTS_QUEUE", value = local.listing_q },
    { name = "LISTING_EVENTS_DLQ", value = local.listing_dlq },
    { name = "CHANGE_EVENTS_QUEUE", value = local.change_q },
    { name = "CHANGE_EVENTS_DLQ", value = local.change_dlq },
  ]
  db_env     = [{ name = "DB_URL", value = local.db_url }]
  db_secrets = [
    { name = "DB_USERNAME", valueFrom = "${var.db_secret_arn}:username::" },
    { name = "DB_PASSWORD", valueFrom = "${var.db_secret_arn}:password::" },
  ]
  adot_config = <<-YAML
    receivers:
      prometheus:
        config:
          scrape_configs:
            - job_name: app
              scrape_interval: 30s
              metrics_path: /actuator/prometheus
              static_configs: [{ targets: ["localhost:APP_PORT"] }]
      otlp:
        protocols:
          http: { endpoint: 0.0.0.0:4318 }
    exporters:
      awsemf:
        namespace: CatalogSentinel
        log_group_name: ${local.log_prefix}/metrics
        dimension_rollup_option: NoDimensionRollup
      awsxray: {}
    service:
      pipelines:
        metrics: { receivers: [prometheus], exporters: [awsemf] }
        traces: { receivers: [otlp], exporters: [awsxray] }
  YAML
}

# ------------------------------------------------------------------ images

resource "aws_ecr_repository" "app" {
  for_each             = toset(["catalog-api", "ingestion-worker", "dashboard"])
  name                 = "catalogsentinel/${each.key}"
  image_tag_mutability = "IMMUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "AES256"
  }
}

# ------------------------------------------------------------------ cluster, logs

resource "aws_ecs_cluster" "this" {
  name = var.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_cloudwatch_log_group" "app" {
  for_each          = toset(["catalog-api", "ingestion-worker", "dashboard", "adot", "metrics"])
  name              = "${local.log_prefix}/${each.key}"
  retention_in_days = var.log_retention_days
}

# ------------------------------------------------------------------ security groups

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "HTTPS from the internet to the load balancer"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  for_each          = toset(var.allowed_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS"
}

resource "aws_vpc_security_group_ingress_rule" "alb_http_redirect" {
  for_each          = toset(var.allowed_ingress_cidrs)
  security_group_id = aws_security_group.alb.id
  cidr_ipv4         = each.value
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  description       = "HTTP, redirected to HTTPS"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_tasks" {
  security_group_id            = aws_security_group.alb.id
  referenced_security_group_id = aws_security_group.tasks.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "To API and dashboard tasks"
}

resource "aws_security_group" "tasks" {
  name        = "${var.name}-tasks"
  description = "Application tasks: inbound only from the load balancer"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  security_group_id            = aws_security_group.tasks.id
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "From the load balancer"
}

resource "aws_vpc_security_group_egress_rule" "tasks_https" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs (SQS, Secrets Manager, ECR, CloudWatch, X-Ray) through NAT or endpoints"
}

resource "aws_vpc_security_group_egress_rule" "tasks_db" {
  security_group_id = aws_security_group.tasks.id
  cidr_ipv4         = var.vpc_cidr
  ip_protocol       = "tcp"
  from_port         = 5432
  to_port           = 5432
  description       = "PostgreSQL inside the VPC"
}

# ------------------------------------------------------------------ load balancer

resource "aws_lb" "this" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  dynamic "access_logs" {
    for_each = var.alb_log_bucket == "" ? [] : [var.alb_log_bucket]
    content {
      bucket  = access_logs.value
      prefix  = "alb"
      enabled = true
    }
  }
}

resource "aws_lb_target_group" "api" {
  name        = "${var.name}-api"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }
  deregistration_delay = 30
}

resource "aws_lb_target_group" "dashboard" {
  name        = "${var.name}-dashboard"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  health_check {
    path = "/healthz"
  }
  deregistration_delay = 15
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.dashboard.arn
  }
}

# Operator endpoints: allowed only from the administrators' networks (on top of the API's ADMIN role).
resource "aws_lb_listener_rule" "admin_allowed" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  condition {
    path_pattern {
      values = ["/api/v1/admin/*"]
    }
  }
  condition {
    source_ip {
      values = var.admin_cidrs
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener_rule" "admin_denied" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 11
  condition {
    path_pattern {
      values = ["/api/v1/admin/*", "/actuator/*"]
    }
  }
  action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/problem+json"
      status_code  = "403"
      message_body = "{\"status\":403,\"code\":\"FORBIDDEN\",\"detail\":\"Not reachable from this network\"}"
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 20
  condition {
    path_pattern {
      values = ["/api/*", "/v3/api-docs*", "/swagger-ui*"]
    }
  }
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

# ------------------------------------------------------------------ IAM

data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-task-secrets"
  role   = aws_iam_role.execution.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = [var.db_secret_arn, aws_secretsmanager_secret.api_keys.arn]
    }]
  })
}

# API keys (hashed, role-tagged entries; see docs/security.md). The value is set out of band so that it never
# enters Terraform state: aws secretsmanager put-secret-value --secret-id <name> --secret-string '...'
resource "aws_secretsmanager_secret" "api_keys" {
  name                    = "${var.name}/api-keys"
  description             = "CatalogSentinel API keys as <sha256-hex>:<ROLE>|<ROLE>, comma separated"
  recovery_window_in_days = 7
}

data "aws_iam_policy_document" "telemetry" {
  statement {
    sid       = "EmfMetrics"
    actions   = ["logs:PutLogEvents", "logs:CreateLogStream", "logs:DescribeLogStreams"]
    resources = ["${aws_cloudwatch_log_group.app["metrics"].arn}:*"]
  }
  statement {
    sid       = "Traces"
    actions   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords"]
    resources = ["*"] # X-Ray does not support resource-level permissions for these actions
  }
}

resource "aws_iam_role" "api" {
  name               = "${var.name}-api"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "api" {
  name   = "api"
  role   = aws_iam_role.api.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [
      {
        Sid      = "EnqueueListingEvents"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:GetQueueUrl"]
        Resource = [var.queue_arns["catalog-listing-events"]]
      },
      {
        Sid      = "ReadQueueDepths"
        Effect   = "Allow"
        Action   = ["sqs:GetQueueAttributes", "sqs:GetQueueUrl"]
        Resource = concat(values(var.queue_arns), values(var.dlq_arns))
      },
      {
        Sid      = "OperateDeadLetterQueues"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility"]
        Resource = values(var.dlq_arns)
      },
      {
        Sid      = "ReplayToSourceQueues"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = values(var.queue_arns)
      },
    ]
  })
}

resource "aws_iam_role_policy" "api_telemetry" {
  name   = "telemetry"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.telemetry.json
}

resource "aws_iam_role" "worker" {
  name               = "${var.name}-worker"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "worker" {
  name   = "worker"
  role   = aws_iam_role.worker.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [
      {
        Sid      = "ConsumeWorkQueues"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
        Resource = values(var.queue_arns)
      },
      {
        Sid      = "PublishChangeEvents"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = [var.queue_arns["catalog-change-events"]]
      },
      {
        Sid      = "DeadLetterExplicitly"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
        Resource = values(var.dlq_arns)
      },
    ]
  })
}

resource "aws_iam_role_policy" "worker_telemetry" {
  name   = "telemetry"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.telemetry.json
}

resource "aws_iam_role" "dashboard" {
  name               = "${var.name}-dashboard"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json # no permissions: static files + proxy only
}

# ------------------------------------------------------------------ task definitions

locals {
  log_config = { for app in ["catalog-api", "ingestion-worker", "dashboard", "adot"] : app => {
    logDriver = "awslogs"
    options   = {
      awslogs-group         = aws_cloudwatch_log_group.app[app].name
      awslogs-region        = var.region
      awslogs-stream-prefix = app
    }
  } }
  adot = { for app, port in { "catalog-api" = 8080, "ingestion-worker" = 8081 } : app => {
    name             = "adot"
    image            = var.adot_image
    essential        = false
    environment      = [{ name = "AOT_CONFIG_CONTENT", value = replace(local.adot_config, "APP_PORT", tostring(port)) }]
    logConfiguration = local.log_config["adot"]
  } }
}

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.api_cpu
  memory                   = var.api_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.api.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode(concat([{
    name                   = "catalog-api"
    image                  = local.image["catalog-api"]
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    portMappings           = [{ containerPort = 8080, protocol = "tcp" }]
    environment            = concat(local.queue_env, local.db_env, [
      { name = "SPRING_PROFILES_ACTIVE", value = "aws,json-logs" },
      { name = "TRACING_SAMPLING_PROBABILITY", value = tostring(var.trace_sampling) },
      { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "http://localhost:4318/v1/traces" },
    ])
    secrets     = concat(local.db_secrets, [{ name = "CATALOGSENTINEL_API_KEYS", valueFrom = aws_secretsmanager_secret.api_keys.arn }])
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost:8080/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 3
      retries     = 3
      startPeriod = 60
    }
    stopTimeout      = 30
    logConfiguration = local.log_config["catalog-api"]
  }], var.enable_adot ? [local.adot["catalog-api"]] : []))
}

resource "aws_ecs_task_definition" "worker" {
  family                   = "${var.name}-worker"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.worker_cpu
  memory                   = var.worker_memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.worker.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  volume {
    name = "tmp"
  }
  container_definitions = jsonencode(concat([{
    name                   = "ingestion-worker"
    image                  = local.image["ingestion-worker"]
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    portMappings           = [{ containerPort = 8081, protocol = "tcp" }]
    environment            = concat(local.queue_env, local.db_env, [
      { name = "SPRING_PROFILES_ACTIVE", value = "json-logs" },
      { name = "TRACING_SAMPLING_PROBABILITY", value = tostring(var.trace_sampling) },
      { name = "OTEL_EXPORTER_OTLP_ENDPOINT", value = "http://localhost:4318/v1/traces" },
    ])
    secrets     = local.db_secrets
    healthCheck = {
      command     = ["CMD-SHELL", "curl -fsS http://localhost:8081/actuator/health/liveness || exit 1"]
      interval    = 15
      timeout     = 3
      retries     = 3
      startPeriod = 60
    }
    # Long poll (20 s) + in-flight drain (25 s): give the graceful shutdown room before SIGKILL.
    stopTimeout      = 60
    logConfiguration = local.log_config["ingestion-worker"]
  }], var.enable_adot ? [local.adot["ingestion-worker"]] : []))
}

resource "aws_ecs_task_definition" "dashboard" {
  family                   = "${var.name}-dashboard"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.dashboard.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name         = "dashboard"
    image        = local.image["dashboard"]
    essential    = true
    portMappings = [{ containerPort = 8080, protocol = "tcp" }]
    # In AWS the load balancer routes /api/* straight to the API, so nginx never proxies; the value is unused.
    environment            = [{ name = "API_UPSTREAM", value = "http://127.0.0.1:1" }]
    readonlyRootFilesystem = false
    logConfiguration       = local.log_config["dashboard"]
  }])
}

# ------------------------------------------------------------------ services

resource "aws_ecs_service" "api" {
  name                               = "catalog-api"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.api.arn
  desired_count                      = var.api_desired_count
  launch_type                        = "FARGATE"
  health_check_grace_period_seconds  = 90
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "catalog-api"
    container_port   = 8080
  }
  depends_on = [aws_lb_listener_rule.api]
}

resource "aws_ecs_service" "worker" {
  name                               = "ingestion-worker"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.worker.arn
  desired_count                      = var.worker_min_count
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }
  lifecycle {
    ignore_changes = [desired_count] # owned by autoscaling
  }
}

resource "aws_ecs_service" "dashboard" {
  name                               = "dashboard"
  cluster                            = aws_ecs_cluster.this.id
  task_definition                    = aws_ecs_task_definition.dashboard.arn
  desired_count                      = 1
  launch_type                        = "FARGATE"
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_execute_command             = false
  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.tasks.id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.dashboard.arn
    container_name   = "dashboard"
    container_port   = 8080
  }
}

# ------------------------------------------------------------------ worker autoscaling on backlog

resource "aws_appautoscaling_target" "worker" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.worker.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.worker_min_count
  max_capacity       = var.worker_max_count
}

resource "aws_appautoscaling_policy" "worker_out" {
  name               = "${var.name}-worker-scale-out"
  service_namespace  = aws_appautoscaling_target.worker.service_namespace
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  policy_type        = "StepScaling"
  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 120
    metric_aggregation_type = "Maximum"
    step_adjustment {
      metric_interval_lower_bound = 0
      metric_interval_upper_bound = 5000
      scaling_adjustment          = 1
    }
    step_adjustment {
      metric_interval_lower_bound = 5000
      scaling_adjustment          = 3
    }
  }
}

resource "aws_appautoscaling_policy" "worker_in" {
  name               = "${var.name}-worker-scale-in"
  service_namespace  = aws_appautoscaling_target.worker.service_namespace
  resource_id        = aws_appautoscaling_target.worker.resource_id
  scalable_dimension = aws_appautoscaling_target.worker.scalable_dimension
  policy_type        = "StepScaling"
  step_scaling_policy_configuration {
    adjustment_type         = "ChangeInCapacity"
    cooldown                = 300
    metric_aggregation_type = "Maximum"
    step_adjustment {
      metric_interval_upper_bound = 0
      scaling_adjustment          = -1
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "backlog_high" {
  alarm_name          = "${var.name}-listing-backlog-high"
  alarm_description   = "Scale workers out while listing events queue up"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = local.listing_q }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = var.scale_out_backlog
  comparison_operator = "GreaterThanThreshold"
  alarm_actions       = [aws_appautoscaling_policy.worker_out.arn]
}

resource "aws_cloudwatch_metric_alarm" "backlog_low" {
  alarm_name          = "${var.name}-listing-backlog-low"
  alarm_description   = "Scale workers in when the queue is nearly empty"
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = local.listing_q }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 3
  threshold           = var.scale_in_backlog
  comparison_operator = "LessThanThreshold"
  alarm_actions       = [aws_appautoscaling_policy.worker_in.arn]
}
