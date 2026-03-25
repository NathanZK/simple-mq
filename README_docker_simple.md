# Docker Hub Deployment Guide

## Quick Steps

### 1. Create Docker Hub Repository
1. Go to https://hub.docker.com
2. Click "Create Repository"
3. Name: `simple-mq`
4. Your repository: `docker.io/nathanzk/simple-mq`

### 2. Build Image
```bash
docker build -t nathanzk/simple-mq:latest .
```

### 3. Login to Docker Hub
```bash
docker login
```
Enter your Docker Hub username and password when prompted.

### 4. Push Image
```bash
docker push nathanzk/simple-mq:latest
```

### 5. Verify
```bash
# Pull to test
docker pull nathanzk/simple-mq:latest

# Run to test
docker run -p 8080:8080 nathanzk/simple-mq:latest
```
