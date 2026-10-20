using CatalogService.Models;
using Microsoft.EntityFrameworkCore;

namespace CatalogService.Data;

/// <summary>
/// The sample data, seeded by the migrations (<c>HasData</c>). The reviews point at customers of sales-service, whose
/// Flyway seed uses the same ids; its orders point at the products below.
/// </summary>
public static class CatalogSeed
{
    public static readonly Guid Alice = Guid.Parse("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    public static readonly Guid Bob = Guid.Parse("6f9619ff-8b86-d011-b42d-00c04fc964ff");
    public static readonly Guid Carol = Guid.Parse("c3a1e5b2-0d4f-4e6a-9b8c-7d2e1f0a3b4c");

    public static void Apply(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Category>().HasData(
            new Category { Id = 1, Name = "Laptops" },
            new Category { Id = 2, Name = "Phones" },
            new Category { Id = 3, Name = "Accessories" });
        modelBuilder.Entity<Product>().HasData(
            new Product { Id = 1, Name = "ThinkPad X1 Carbon", Price = 1899.00m, CategoryId = 1 },
            new Product { Id = 2, Name = "MacBook Air 15", Price = 1499.00m, CategoryId = 1 },
            new Product { Id = 3, Name = "Pixel 10", Price = 799.00m, CategoryId = 2 },
            new Product { Id = 4, Name = "USB-C Dock", Price = 129.90m, CategoryId = 3 },
            new Product { Id = 5, Name = "Mechanical Keyboard", Price = 89.50m, CategoryId = 3 });
        modelBuilder.Entity<Review>().HasData(
            new Review { Id = 1, ProductId = 1, CustomerId = Alice, Rating = 5, Comment = "Light and fast." },
            new Review { Id = 2, ProductId = 4, CustomerId = Alice, Rating = 4, Comment = "One cable for everything." },
            new Review { Id = 3, ProductId = 3, CustomerId = Bob, Rating = 3, Comment = "Great camera, average battery." },
            new Review { Id = 4, ProductId = 5, CustomerId = Carol, Rating = 5, Comment = "Clicky in the best way." });
    }
}
