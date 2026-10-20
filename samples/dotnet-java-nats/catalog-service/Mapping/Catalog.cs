using CatalogService.Models;

namespace CatalogService.Mapping;

public sealed record ProductResponse(int Id, string Name, decimal Price, string? Category);

/// <summary>The queries of the REST endpoints and of GraphQL, before FxMap fills the customer fields.</summary>
public static class Catalog
{
    public static IQueryable<ProductResponse> Products(IQueryable<Product> products) =>
        products.OrderBy(p => p.Id).Select(p => new ProductResponse(p.Id, p.Name, p.Price, p.Category!.Name));

    public static IQueryable<ReviewResponse> Reviews(IQueryable<Review> reviews) =>
        reviews.OrderBy(r => r.Id).Select(r => new ReviewResponse
        {
            Id = r.Id,
            ProductId = r.ProductId,
            ProductName = r.Product!.Name,
            Rating = r.Rating,
            Comment = r.Comment,
            CustomerId = r.CustomerId
        });
}
