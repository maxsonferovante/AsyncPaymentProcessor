#!/bin/bash
# This script builds a Docker image for Async Payment Processor API.
docker build -t async-payment-processor:latest .

# Check if the build was successful
if [ $? -eq 0 ]; then
    echo "Docker image 'async-payment-processor:latest' built successfully."
else
    echo "Failed to build the Docker image."
    exit 1
fi

# If you want to push the image to a Docker registry, uncomment the following lines:
docker login

# Tag the image with your Docker Hub username or registry name
docker tag async-payment-processor:latest maxsonferovante/async-payment-processor:latest

# Push the image to the Docker registry
docker push maxsonferovante/async-payment-processor:latest