start-server:
	docker compose up --build -d ds-server
	make logs

sql:
	docker compose exec -it ds-db psql -U user -d app

migrate:
	docker compose down -v

logs:
	docker compose logs -f

stop:
	docker compose down

start-client:
	docker compose up --build ds-client
