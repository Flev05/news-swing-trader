SHELL := /usr/bin/env bash
.DEFAULT_GOAL := help

ROOT_DIR := $(CURDIR)
CORE_DIR := apps/core-java
WORKER_DIR := apps/ai-worker
COMPOSE_FILE := infra/compose.yaml
ENV_FILE := .env
ENV_EXAMPLE := .env.example
TOOLS_DIR := $(ROOT_DIR)/.tools
UV_VERSION := 0.12.9
PYTHON_VERSION := 3.12.14
UV_DIR := $(TOOLS_DIR)/uv/$(UV_VERSION)
UV_BIN := $(UV_DIR)/uv
UV_CACHE_DIR := $(TOOLS_DIR)/cache
UV_PYTHON_INSTALL_DIR := $(TOOLS_DIR)/python
UV_ARCHIVE_URL := https://github.com/astral-sh/uv/releases/download/$(UV_VERSION)/uv-x86_64-unknown-linux-gnu.tar.gz
UV_CHECKSUM_URL := $(UV_ARCHIVE_URL).sha256

.PHONY: help doctor bootstrap format lint test test-java test-python migration-smoke db-up db-down db-logs compose-config

help:
	@printf '%s\n' 'Targets: doctor bootstrap format lint test db-up db-down db-logs compose-config'

doctor:
	@case "$(ROOT_DIR)" in /home/*) ;; *) printf '%s\n' 'ERROR: run from the Linux filesystem under /home.'; exit 1;; esac
	@command -v git >/dev/null
	@command -v make >/dev/null
	@command -v curl >/dev/null
	@command -v sha256sum >/dev/null
	@command -v java >/dev/null
	@command -v javac >/dev/null
	@command -v mvn >/dev/null
	@java -version 2>&1 | grep -q '21.'
	@mvn -version 2>&1 | grep -q 'Apache Maven 3.9'
	@docker info >/dev/null
	@docker compose version >/dev/null
	@test "$$(df --output=avail -k "$(ROOT_DIR)" | tail -1 | tr -d ' ')" -gt 1048576
	@if test -x "$(UV_BIN)"; then \
		UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" python --version | grep -q 'Python 3.12'; \
	else \
		printf '%s\n' 'Python 3.12 tooling is not bootstrapped; run make bootstrap.'; exit 1; \
	fi
	@printf '%s\n' 'doctor: all Milestone 0 prerequisites are ready.'

bootstrap:
	@mkdir -p "$(UV_DIR)" "$(UV_CACHE_DIR)" "$(UV_PYTHON_INSTALL_DIR)"
	@if ! test -x "$(UV_BIN)"; then \
		tmp_dir="$$(mktemp -d)"; trap 'rm -rf "$$tmp_dir"' EXIT; \
		curl --fail --location --silent --show-error --output "$$tmp_dir/uv-x86_64-unknown-linux-gnu.tar.gz" "$(UV_ARCHIVE_URL)"; \
		curl --fail --location --silent --show-error --output "$$tmp_dir/uv.tar.gz.sha256" "$(UV_CHECKSUM_URL)"; \
		(cd "$$tmp_dir" && sha256sum --check uv.tar.gz.sha256); \
		tar --extract --gzip --file "$$tmp_dir/uv-x86_64-unknown-linux-gnu.tar.gz" --directory "$(UV_DIR)" --strip-components=1; \
	fi
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" python install "$(PYTHON_VERSION)"
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" sync --directory "$(WORKER_DIR)" --all-groups --python "$(PYTHON_VERSION)"
	@test -f "$(ENV_FILE)" || cp "$(ENV_EXAMPLE)" "$(ENV_FILE)"
	@./mvnw -f "$(CORE_DIR)" -q -DskipTests compile
	@printf '%s\n' 'bootstrap: local Python tooling and Java dependencies are ready.'

format:
	@./mvnw -f "$(CORE_DIR)" spotless:apply
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" ruff format .

lint: compose-config
	@./mvnw -f "$(CORE_DIR)" spotless:check -DskipTests compile
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" ruff format --check .
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" ruff check .
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" mypy src tests

test: test-java test-python

test-java:
	@./mvnw -f "$(CORE_DIR)" test

test-python:
	@UV_CACHE_DIR="$(UV_CACHE_DIR)" UV_PYTHON_INSTALL_DIR="$(UV_PYTHON_INSTALL_DIR)" "$(UV_BIN)" run --directory "$(WORKER_DIR)" pytest

migration-smoke:
	@./mvnw -f "$(CORE_DIR)" -Dtest=MigrationSmokeTest test

compose-config:
	@test -f "$(ENV_FILE)" || cp "$(ENV_EXAMPLE)" "$(ENV_FILE)"
	@docker compose --env-file "$(ENV_FILE)" -f "$(COMPOSE_FILE)" config --quiet

db-up: compose-config
	@docker compose --env-file "$(ENV_FILE)" -f "$(COMPOSE_FILE)" up -d postgres

db-down:
	@test -f "$(ENV_FILE)" || cp "$(ENV_EXAMPLE)" "$(ENV_FILE)"
	@docker compose --env-file "$(ENV_FILE)" -f "$(COMPOSE_FILE)" down

db-logs:
	@test -f "$(ENV_FILE)" || cp "$(ENV_EXAMPLE)" "$(ENV_FILE)"
	@docker compose --env-file "$(ENV_FILE)" -f "$(COMPOSE_FILE)" logs --tail=100 postgres
