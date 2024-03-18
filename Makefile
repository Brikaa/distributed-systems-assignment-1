start:
	docker compose up --build -d --scale ds-client=3
	make logs

stop:
	docker compose down

logs:
	docker compose logs -f

sql:
	docker compose exec -it ds-db psql -U user -d app

migrate:
	docker compose down -v
