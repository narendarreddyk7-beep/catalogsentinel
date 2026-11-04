terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.70, < 7.0"
    }
  }
  # Remote state: create the bucket and a DynamoDB lock table (partition key "LockID", string) once, then pass
  # them at init time (docs/deployment.md):
  #   terraform init -backend-config="bucket=<state-bucket>" -backend-config="key=catalogsentinel/demo.tfstate" \
  #                  -backend-config="region=<region>" -backend-config="dynamodb_table=<lock-table>" \
  #                  -backend-config="encrypt=true"
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project     = "catalogsentinel"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
