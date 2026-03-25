# Terraform Configuration for Simple MQ GCP Deployment

This directory contains Terraform configuration to provision a GCP e2-micro VM for deploying the simple-mq application.

## Prerequisites

1. **GCP Project**: Ensure you have a GCP project named `simple-mq`
2. **Service Account**: Create a service account with the following roles:
   - Compute Engine Admin
   - Service Account User
   - Storage Admin (for Terraform state bucket)
3. **JSON Key**: Download the service account JSON key and place it at `~/.gcp/simple-mq-terraform.json`
4. **Terraform**: Install Terraform CLI

## Setup Steps

### 1. Create GCS Bucket for Terraform State

```bash
gcloud storage buckets create gs://simple-mq-tf-state \
    --default-storage-class=STANDARD \
    --location=US_CENTRAL1 \
    --uniform-bucket-level-access \
    --public-access-prevention
```

### 2. Generate SSH Key

```bash
ssh-keygen -t rsa -b 4096 -f .ssh/id_rsa
```
- Press Enter for no passphrase when prompted
- This creates `id_rsa` (private) and `id_rsa.pub` (public) keys

### 3. Initialize Terraform

```bash
terraform init
```

### 4. Review and Customize Variables

Edit `variables.tf` if needed to customize:
- GCP project ID
- Region
- Machine type
- GitHub username

### 5. Plan and Apply

```bash
terraform plan
terraform apply
```

## Configuration Details

### VM Specifications
- **Machine Type**: e2-micro
- **OS**: Debian 12
- **Boot Disk**: 20 GB pd-balanced
- **Region**: us-central1
- **Zone**: us-central1-a

### Network Configuration
- **SSH**: Port 22 open from anywhere
- **Application**: Port 8080 open from anywhere
- **Service Account**: Dedicated service account with minimal permissions

### Startup Script Features
- Installs Docker and Docker Compose
- Clones the simple-mq repository
- Builds and starts the application with docker-compose
- Creates environment configuration
- Sets up health monitoring
- Adds application to docker group for the appuser

## Outputs

After successful deployment, Terraform will output:
- **external_ip**: Public IP address of the VM
- **internal_ip**: Private IP address of the VM
- **ssh_command**: SSH command to connect to the VM
- **app_url**: URL to access the simple-mq application

## Accessing the Application

Once deployed, you can:
1. SSH into the VM: `ssh appuser@<EXTERNAL_IP>`
2. Access the app: `http://<EXTERNAL_IP>:8080`
3. Check application logs: `docker-compose logs -f` (from `/opt/simple-mq`)

## Troubleshooting

### Common Issues

1. **Authentication Errors**: Ensure the service account JSON key exists at the correct path
2. **Permission Denied**: Verify the service account has required IAM roles
3. **Startup Script Fails**: Check the VM serial console in GCP Console
4. **Application Not Running**: SSH into VM and check `docker-compose ps`

### Useful Commands

```bash
# Check VM status
gcloud compute instances describe simple-mq-vm --zone europe-west1-b --project simple-mq

# View startup script output
gcloud compute instances get-serial-port-output simple-mq-vm --zone europe-west1-b --project simple-mq

# SSH into VM
ssh appuser@<EXTERNAL_IP>

# Check application logs
cd /opt/simple-mq
docker-compose logs -f
```

## Cleanup

To destroy all resources:

```bash
terraform destroy
```
