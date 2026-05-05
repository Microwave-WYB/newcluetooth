.DEFAULT_GOAL := help

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | awk -F ':.*## ' 'BEGIN {printf "\n\033[1mTARGETS:\033[0m\n\n"} {printf "    \033[36m%-12s\033[0m %s\n", $$1, $$2}'

.PHONY: sync
sync: ## Sync the project environment
	uv sync

.PHONY: lint
lint: ## Run Ruff lint checks
	uv run ruff check .

.PHONY: format-check
format-check: ## Run Ruff format checks
	uv run ruff format --check .

.PHONY: fix
fix: ## Apply auto-fixable Ruff lint fixes and formatting
	uv run ruff check . --fix
	uv run ruff format .

.PHONY: type-check
type-check: ## Run basedpyright in standard mode
	uv run basedpyright

.PHONY: test
test: ## Run tests
	uv run pytest

.PHONY: check
check: lint format-check type-check test ## Run all checks

.PHONY: clean
clean: ## Clean build artifacts
	@echo "TODO"
