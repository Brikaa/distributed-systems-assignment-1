CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE AppUser (
  id UUID NOT NULL DEFAULT uuid_generate_v4(),
  username VARCHAR(255) UNIQUE NOT NULL,
  name VARCHAR(255) NOT NULL,
  isAdmin BOOLEAN NOT NULL,
  password VARCHAR(255) NOT NULL
);
