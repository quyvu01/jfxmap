CREATE TABLE customer (
    id    UUID PRIMARY KEY,
    name  VARCHAR(100) NOT NULL,
    email VARCHAR(200) NOT NULL,
    city  VARCHAR(100)
);

-- product_id is a product of catalog-service (.NET): no foreign key across services
CREATE TABLE sales_order (
    id          BIGINT PRIMARY KEY,
    customer_id UUID    NOT NULL REFERENCES customer (id),
    product_id  INTEGER NOT NULL,
    quantity    INTEGER NOT NULL
);
