variable "gcp_project" {
  description = "GCP project ID"
  type        = string
  default     = "simple-mq"
}

variable "gcp_region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "gcp_credentials_path" {
  description = "Path to GCP service account JSON key"
  type        = string
  default     = "~/.gcp/simple-mq-terraform.json"
}

variable "github_username" {
  description = "GitHub username for cloning the repository"
  type        = string
  default     = "NathanZK"
}

variable "machine_type" {
  description = "GCP machine type"
  type        = string
  default     = "e2-micro"
}

variable "boot_disk_size" {
  description = "Boot disk size in GB"
  type        = number
  default     = 20
}

variable "boot_disk_image" {
  description = "Boot disk image"
  type        = string
  default     = "debian-cloud/debian-12"
}
