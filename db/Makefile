.DEFAULT_GOAL := help

DB_URL := postgres://cluetooth_test:cluetooth_test@127.0.0.1:55432/cluetooth_test?sslmode=disable

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | awk -F ':.*## ' 'BEGIN {printf "\n\033[1mTARGETS:\033[0m\n\n"} {printf "    \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: testdb-up
testdb-up: ## Start the local test PostGIS database
	docker compose -f test/compose.yaml up -d

.PHONY: testdb-down
testdb-down: ## Stop the local test PostGIS database
	docker compose -f test/compose.yaml down

.PHONY: testdb-migrate
testdb-migrate: ## Apply migrations to the local test database
	npx dbmate --url "$(DB_URL)" up

.PHONY: testdb-wait
testdb-wait: ## Wait until the local test database is ready
	docker compose -f test/compose.yaml exec postgres pg_isready -U cluetooth_test -d cluetooth_test

.PHONY: clean
clean: ## Remove local test database state
	docker compose -f test/compose.yaml down -v
