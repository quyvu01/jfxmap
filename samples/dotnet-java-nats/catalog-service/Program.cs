using CatalogService.Data;
using CatalogService.Mapping;
using FxMap.Abstractions;
using FxMap.EntityFrameworkCore.Extensions;
using FxMap.Extensions;
using FxMap.Nats.Extensions;
using Microsoft.EntityFrameworkCore;

var builder = WebApplication.CreateBuilder(args);

builder.Services.AddDbContext<CatalogDb>(options =>
    options.UseNpgsql(builder.Configuration.GetConnectionString("Catalog")));

// FxMap answers "ProductOf" from EF Core over NATS, and fills reviews from "CustomerOf", which sales-service owns
builder.Services.AddFxMap(cfg =>
    {
        cfg.AddEntitiesFromAssemblyContaining<ProductConfig>();
        cfg.AddProfilesFromAssemblyContaining<ProductConfig>();
        cfg.AddNats(nats => nats.NatsOpts(o => o.Url = builder.Configuration["FxMap:Nats:Url"] ?? "nats://localhost:4222"));
        cfg.ThrowIfException();
    })
    .AddEntityFrameworkCore(cfg => cfg.AddDbContexts(typeof(CatalogDb)));

var app = builder.Build();

// Creates the tables and seeds the sample data (see Migrations/ and CatalogSeed)
using (var scope = app.Services.CreateScope())
{
    await scope.ServiceProvider.GetRequiredService<CatalogDb>().Database.MigrateAsync();
}

app.MapGet("/products", async (CatalogDb db) =>
    await db.Products.OrderBy(p => p.Id).Select(p => new ProductResponse(p.Id, p.Name, p.Price, p.Category!.Name))
        .ToListAsync());

app.MapGet("/products/{id:int}", async (int id, CatalogDb db) =>
    await db.Products.Where(p => p.Id == id).Select(p => new ProductResponse(p.Id, p.Name, p.Price, p.Category!.Name))
        .FirstOrDefaultAsync() is { } product ? Results.Ok(product) : Results.NotFound());

// Every review, with the customer fields of all of them fetched from sales-service in one NATS request
app.MapGet("/reviews", async (CatalogDb db, IDistributedMapper mapper) =>
{
    var reviews = await Reviews(db.Reviews).ToListAsync();
    await mapper.MapDataAsync(reviews);
    return reviews;
});

app.MapGet("/reviews/{id:int}", async (int id, CatalogDb db, IDistributedMapper mapper) =>
{
    var review = await Reviews(db.Reviews.Where(r => r.Id == id)).FirstOrDefaultAsync();
    if (review is null) return Results.NotFound();
    await mapper.MapDataAsync(review);
    return Results.Ok(review);
});

app.Run();

static IQueryable<ReviewResponse> Reviews(IQueryable<CatalogService.Models.Review> reviews) =>
    reviews.OrderBy(r => r.Id).Select(r => new ReviewResponse
    {
        Id = r.Id,
        ProductId = r.ProductId,
        ProductName = r.Product!.Name,
        Rating = r.Rating,
        Comment = r.Comment,
        CustomerId = r.CustomerId
    });

public sealed record ProductResponse(int Id, string Name, decimal Price, string? Category);

/// <summary>Lets the tests start the service with <c>WebApplicationFactory</c>.</summary>
public partial class Program;
