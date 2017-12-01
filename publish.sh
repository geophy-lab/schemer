#!/usr/bin/env bash

set -ex

sbt "project core" +publishSigned
sbt sonatypeReleaseAll

docker login -u "$DOCKER_USERNAME" -p "$DOCKER_PASSWORD"
sbt docker:publish
