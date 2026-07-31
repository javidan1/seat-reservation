terraform {
  required_version = ">= 1.11"

  # Encrypted, versioned remote state with S3-native locking. Belt to the braces
  # of manage_master_user_password: state carries no DB password either way.
  backend "s3" {
    bucket       = "seatres-tfstate-485378271805"
    key          = "seat-reservation/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "seat-reservation"
      ManagedBy = "terraform"
    }
  }
}
