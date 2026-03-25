#!/usr/bin/env bash
docker rm -f $(docker ps -a -q) 2>/dev/null; docker volume prune -a -f && docker compose up -d && echo "Aguardando Oracle nodes inicializarem (2 instancias)..." && sleep 30 && ./gradlew clean && ./gradlew benchmarkHikari && ./gradlew benchmarkUcp
