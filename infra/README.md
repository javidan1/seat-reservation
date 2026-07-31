# Infrastructure (AWS, Terraform)

One command from nothing to running: VPC → RDS Postgres 16 → ECS Fargate (ARM64) → ALB →
CloudFront TLS edge. Security posture, deliberately:

- **HTTPS without owning a domain**: CloudFront's default certificate terminates TLS at the
  edge; the ALB's security group admits only CloudFront's origin-facing IP range, so the plain
  HTTP origin is unreachable directly. Honest limit: edge→origin is HTTP — end-to-end TLS needs
  a real domain with an ACM cert on the ALB (~20 lines more Terraform).
- **The DB password exists nowhere in Terraform**: `manage_master_user_password` has RDS keep it
  in Secrets Manager; the task definition references the secret's `password` key and the
  execution role can read exactly that one secret. No state file, local or remote, contains it.
- **Remote state**: encrypted, versioned S3 with native locking (bucket bootstrap below).
- Tiered security groups (CloudFront → ALB:80 → app:8080 → db:5432); RDS in private subnets;
  container runs as non-root; ECR scans on push.

## Deploy

```bash
# once: the state bucket (outside Terraform — it must exist before init)
aws s3api create-bucket --bucket seatres-tfstate-<ACCOUNT_ID> --region us-east-1
aws s3api put-bucket-versioning --bucket seatres-tfstate-<ACCOUNT_ID> \
  --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket seatres-tfstate-<ACCOUNT_ID> \
  --public-access-block-configuration BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true

cd infra
terraform init
terraform apply                       # ~10 min (RDS + CloudFront)

aws ecr get-login-password --region us-east-1 \
  | docker login --username AWS --password-stdin "$(terraform output -raw ecr_repository_url | cut -d/ -f1)"
docker build -t "$(terraform output -raw ecr_repository_url):latest" ..
docker push "$(terraform output -raw ecr_repository_url):latest"
aws ecs update-service --cluster seat-reservation --service seat-reservation \
  --force-new-deployment --region us-east-1 > /dev/null

terraform output app_url              # https://…cloudfront.net
```

Costs roughly **$1–2/day** while up (Fargate 0.5 vCPU + db.t4g.micro + ALB; CloudFront and the
Secrets Manager secret are pennies). Tear down: `terraform destroy` (the state bucket survives —
delete it separately if abandoning the project).

Remaining demo-stack shortcuts, called out rather than hidden: single-AZ RDS with no backups,
tasks in public subnets instead of a ~$32/mo NAT gateway, `app_count = 1` (set 2 to run the
multi-instance no-double-sell guarantee in the cloud — correctness is instance-agnostic; only SSE
liveness is per-instance, a documented cut).
