using CatalogService.Data;
using Microsoft.EntityFrameworkCore;

namespace CatalogService.Mapping;

/// <summary>
/// The GraphQL queries (<c>/graphql</c>). The customer fields of <see cref="ReviewResponse"/> are not filled here:
/// FxMap.HotChocolate resolves the ones a query selects from sales-service, in one NATS request per query.
/// </summary>
public sealed class Query
{
    public Task<List<ProductResponse>> GetProducts([Service] CatalogDb db) => Catalog.Products(db.Products).ToListAsync();

    public Task<ProductResponse?> GetProduct(int id, [Service] CatalogDb db) =>
        Catalog.Products(db.Products.Where(p => p.Id == id)).FirstOrDefaultAsync();

    public Task<List<ReviewResponse>> GetReviews([Service] CatalogDb db) => Catalog.Reviews(db.Reviews).ToListAsync();

    public Task<ReviewResponse?> GetReview(int id, [Service] CatalogDb db) =>
        Catalog.Reviews(db.Reviews.Where(r => r.Id == id)).FirstOrDefaultAsync();
}
