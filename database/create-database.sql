-- Local MySQL bootstrap for the Uno Web Game.
-- Run this once as a MySQL administrator. Hibernate creates/updates the
-- application tables when the backend starts (ddl-auto=update).

CREATE DATABASE IF NOT EXISTS uno_db
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
