#!/bin/bash
set -e

DOCKER_USER="ohhaithere"
VERSION="${1:-latest}"

echo "=========================================="
echo "Building and pushing Docker images"
echo "Version: $VERSION"
echo "=========================================="

# Build backend
echo ""
echo ">>> Building backend image..."
docker build -t $DOCKER_USER/loyalty-backend:$VERSION -f Dockerfile .

# Build frontend
echo ""
echo ">>> Building frontend image..."
docker build -t $DOCKER_USER/loyalty-frontend:$VERSION -f admin-panel/Dockerfile ./admin-panel

# Push backend
echo ""
echo ">>> Pushing backend image..."
docker push $DOCKER_USER/loyalty-backend:$VERSION

# Push frontend
echo ""
echo ">>> Pushing frontend image..."
docker push $DOCKER_USER/loyalty-frontend:$VERSION

echo ""
echo "=========================================="
echo "Done! Images pushed:"
echo "  - $DOCKER_USER/loyalty-backend:$VERSION"
echo "  - $DOCKER_USER/loyalty-frontend:$VERSION"
echo "=========================================="
