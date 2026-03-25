#!/bin/bash

# Update system packages
apt-get update && apt-get upgrade -y

# Install Docker
apt-get install -y apt-transport-https ca-certificates curl gnupg lsb-release
curl -fsSL https://download.docker.com/linux/debian/gpg | gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg
echo "deb [arch=amd64 signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable" | tee /etc/apt/sources.list.d/docker.list > /dev/null
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io

# Install Docker Compose
curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
chmod +x /usr/local/bin/docker-compose

# Start and enable Docker service
systemctl start docker
systemctl enable docker

# Add default user to docker group
usermod -aG docker appuser

# Clone the repository
cd /opt
git clone https://github.com/${GITHUB_USERNAME:-NathanZK}/simple-mq.git
cd simple-mq

# Create .env file if it doesn't exist
if [ ! -f .env ]; then
  cat > .env << EOF
# Simple MQ Configuration
SERVER_PORT=8080
QUEUE_MAX_SIZE=1000
MESSAGE_TTL_SECONDS=3600
EOF
fi

# Build and start the application
docker-compose up -d

# Wait for the application to start
sleep 30

# Check if the application is running
if docker-compose ps | grep -q "Up"; then
  echo "Simple MQ application started successfully!"
  echo "Application logs:"
  docker-compose logs --tail=20
else
  echo "Failed to start Simple MQ application"
  docker-compose logs
fi

# Create a simple health check script
cat > /opt/health-check.sh << 'EOF'
#!/bin/bash
if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
  echo "Application is healthy"
  exit 0
else
  echo "Application is not healthy"
  exit 1
fi
EOF

chmod +x /opt/health-check.sh

# Add a cron job to check application health every 5 minutes
echo "*/5 * * * * /opt/health-check.sh >> /var/log/simple-mq-health.log 2>&1" | crontab -
