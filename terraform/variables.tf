variable "gcp_project" {
  description = "GCP project ID"
  type        = string
  default     = "simple-mq"
}

variable "gcp_region" {
  description = "GCP region"
  type        = string
  default     = "us-east1"
}

variable "gcp_credentials_path" {
  description = "Path to GCP service account JSON key"
  type        = string
  default     = ""
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

variable "ssh_public_key" {
  description = "SSH public key for appuser"
  type        = string
}