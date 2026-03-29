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

  tags = ["simple-mq", "http-server", "ssh"]

  depends_on = [
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

  source_ranges = ["0.0.0.0/0"]
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