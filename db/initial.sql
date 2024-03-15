CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE TYPE status AS ENUM ('PENDING', 'REJECTED', 'BORROWED', 'RETURNED');

CREATE TABLE AppUser (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  username VARCHAR(255) UNIQUE NOT NULL,
  name VARCHAR(255) NOT NULL,
  isAdmin BOOLEAN NOT NULL,
  password VARCHAR(255) NOT NULL
);

CREATE TABLE Book (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  title VARCHAR(255) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(625) NOT NULL,
  author VARCHAR(255) NOT NULL
);

CREATE TABLE BookBorrowRequest (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  bookId UUID NOT NULL REFERENCES Book (id),
  lenderId UUID NOT NULL REFERENCES AppUser (id),
  borrowerId UUID NOT NULL REFERENCES AppUser (id),
  status status NOT NULL
);

CREATE TABLE Message (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  senderId UUID NOT NULL REFERENCES AppUser(id),
  receiverId UUID NOT NULL REFERENCES AppUser(id),
  body TEXT
);
