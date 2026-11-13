# Playground tasks. Ctrl+Click: $(VARS) → their assignment, prerequisites → their rule, include → the file.
include build/common.mk

PYTHON ?= python3
NPM := npm --prefix web

.PHONY: all check check-shell check-web check-rust test docker

all: check

check: check-shell check-web check-rust

check-shell:
	for f in scripts/*.sh scripts/lib/*.sh scripts/tests/*.sh; do bash -n "$$f"; done

check-web:
	$(NPM) run check

check-rust:
	cargo check --manifest-path backend/rust/Cargo.toml

test: check
	./scripts/tests/run.sh

docker:
	docker build -f docker/Dockerfile -t $(IMAGE):$(TAG) .
