CREATE TABLE IF NOT EXISTS roles (
    id SERIAL PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(100) UNIQUE,
    email VARCHAR(100),
    password VARCHAR(255) NOT NULL,
    role_id INTEGER REFERENCES roles(id),
    status BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMP,
    mobile VARCHAR(20) UNIQUE
);

CREATE TABLE IF NOT EXISTS operation_logs (
    id SERIAL PRIMARY KEY,
    user_id INTEGER,
    user_name VARCHAR(100),
    operation_type VARCHAR(50),
    operation_obj VARCHAR(100),
    ip_address VARCHAR(100),
    operation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    remarks TEXT,
    status VARCHAR(30)
);

INSERT INTO roles (id, role_name, description)
VALUES
    (1, 'super_admin', 'Super administrator'),
    (2, 'admin', 'Administrator'),
    (3, 'user', 'Normal user')
ON CONFLICT (id) DO UPDATE
SET role_name = EXCLUDED.role_name,
    description = EXCLUDED.description;

SELECT setval(pg_get_serial_sequence('roles', 'id'), (SELECT COALESCE(MAX(id), 1) FROM roles), true);
