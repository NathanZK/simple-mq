# Outputs
output "external_ip" {
  description = "External IP address of the VM"
  value       = google_compute_instance.simple_mq_vm.network_interface[0].access_config[0].nat_ip
}

output "internal_ip" {
  description = "Internal IP address of the VM"
  value       = google_compute_instance.simple_mq_vm.network_interface[0].network_ip
}

output "ssh_command" {
  description = "SSH command to connect to the VM"
  value       = "ssh appuser@${google_compute_instance.simple_mq_vm.network_interface[0].access_config[0].nat_ip}"
}

output "app_url" {
  description = "URL to access the simple-mq application"
  value       = "http://${google_compute_instance.simple_mq_vm.network_interface[0].access_config[0].nat_ip}:8080"
}
