-- Tables of the playground
CREATE TABLE users (
    id    BIGSERIAL PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    email TEXT UNIQUE
);

SELECT u.name, count(*) AS shapes
FROM users u
LEFT JOIN shapes s ON s.owner_id = u.id
WHERE u.email LIKE '%@example.com'
GROUP BY u.name;
