.PHONY: db-up db-down app-build app-up app-down app-restart app-lint app-test

# Database commands
db-up:
	docker-compose up postgres -d

db-down:
	docker-compose down postgres

# Application commands
app-build:
	docker build -t simple-mq .

app-up: 
	docker-compose up app -d

app-down:
	docker-compose stop app
	docker-compose rm -f app

app-restart: app-down app-up

# Development commands
app-lint:
	./gradlew ktlintCheck

app-format:
	./gradlew ktlintFormat

app-test:
	./gradlew test

# Log commands
app-logs:
	docker logs simple-mq-app -f

db-logs:
	docker logs simple-mq-postgres -f

app-logs-static:
	docker logs simple-mq-app --tail 50

db-logs-static:
	docker logs simple-mq-postgres --tail 50

# Full environment
up: db-up app-up

down: app-down db-down
