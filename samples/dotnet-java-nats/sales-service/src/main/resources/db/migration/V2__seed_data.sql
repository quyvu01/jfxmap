-- The customers that the reviews of catalog-service (.NET) refer to, with the same ids
INSERT INTO customer (id, name, email, city) VALUES
    ('0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11', 'Alice Nguyen', 'alice@example.com', 'Hanoi'),
    ('6f9619ff-8b86-d011-b42d-00c04fc964ff', 'Bob Martin', 'bob@example.com', 'Lyon'),
    ('c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c', 'Carol Tran', 'carol@example.com', NULL);

-- Orders of the products that catalog-service seeds (ids 1 to 5)
INSERT INTO sales_order (id, customer_id, product_id, quantity) VALUES
    (1, '0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11', 1, 1),
    (2, '0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11', 4, 2),
    (3, '6f9619ff-8b86-d011-b42d-00c04fc964ff', 3, 1),
    (4, 'c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c', 5, 3),
    (5, '0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11', 2, 1);
