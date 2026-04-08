terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }

  backend "gcs" {
    bucket = "simple-mq-tf-state"
    prefix = "terraform/state"
  }
}

provider "google" {
  project     = var.gcp_project
  region      = var.gcp_region
  credentials = var.gcp_credentials_path != "" ? file(var.gcp_credentials_path) : null
}

# Service account for VM - no roles attached, VM has no GCP API dependencies at runtime
resource "google_service_account" "vm_sa" {
  account_id   = "simple-mq-vm"
  display_name = "simple-mq VM Service Account"
  description  = "Minimal SA for simple-mq VM. No roles attached - VM has no GCP API dependencies at runtime."
}

# Compute instance
resource "google_compute_instance" "simple_mq_vm" {
  name         = "simple-mq-vm"
  machine_type = "e2-micro"
  zone         = "${var.gcp_region}-a"

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = 20
      type  = "pd-standard"
    }
  }

  network_interface {
    network = "default"
    access_config {
      # Ephemeral IP
    }
  }

  metadata = {
    ssh-keys       = "appuser:${var.ssh_public_key}"
    startup-script = file("${path.module}/startup.sh")
  }

  service_account {
    email  = google_service_account.vm_sa.email
    scopes = ["cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  tags = ["simple-mq", "http-server", "ssh"]

  depends_on = [
    google_service_account.vm_sa,
    google_compute_firewall.allow_ssh,
    google_compute_firewall.allow_http
  ]
}

# Firewall rules
resource "google_compute_firewall" "allow_ssh" {
  name    = "allow-ssh"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["22"]
  }

  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["ssh"]
}

resource "google_compute_firewall" "allow_http" {
  name    = "allow-http-8080"
  network = "default"

  allow {
    protocol = "tcp"
    ports    = ["8080"]
  }

  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["http-server"]
}