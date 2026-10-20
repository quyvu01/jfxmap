using CatalogService.Models;
using Microsoft.EntityFrameworkCore;

namespace CatalogService.Data;

public sealed class CatalogDb(DbContextOptions<CatalogDb> options) : DbContext(options)
{
    public DbSet<Category> Categories => Set<Category>();
    public DbSet<Product> Products => Set<Product>();
    public DbSet<Review> Reviews => Set<Review>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Category>(category =>
        {
            category.ToTable("categories");
            category.Property(x => x.Name).HasMaxLength(100);
        });
        modelBuilder.Entity<Product>(product =>
        {
            product.ToTable("products");
            product.Property(x => x.Name).HasMaxLength(200);
            product.Property(x => x.Price).HasPrecision(10, 2);
            product.HasOne(x => x.Category).WithMany(x => x.Products).HasForeignKey(x => x.CategoryId);
        });
        modelBuilder.Entity<Review>(review =>
        {
            review.ToTable("reviews");
            review.Property(x => x.Comment).HasMaxLength(500);
            review.HasOne(x => x.Product).WithMany(x => x.Reviews).HasForeignKey(x => x.ProductId);
        });
        CatalogSeed.Apply(modelBuilder);
    }
}
