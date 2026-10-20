using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using CatalogService.Mapping;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Testcontainers.Nats;
using Testcontainers.PostgreSql;
using Xunit;

namespace CatalogService.Tests;

/// <summary>Starts PostgreSQL, NATS, the stand-in sales-service and catalog-service once for all the tests.</summary>
public sealed class CatalogFixture : IAsyncLifetime
{
    private readonly PostgreSqlContainer _postgres = new PostgreSqlBuilder("postgres:17-alpine")
        .WithDatabase("catalog").Build();
    private readonly NatsContainer _nats = new NatsBuilder("nats:2.11").Build();
    private WebApplicationFactory<Program>? _factory;

    public IServiceProvider Services => _factory!.Services;

    public SalesStandIn Sales { get; private set; } = null!;
    public HttpClient Client { get; private set; } = null!;

    public async Task InitializeAsync()
    {
        await Task.WhenAll(_postgres.StartAsync(), _nats.StartAsync());
        Sales = await SalesStandIn.StartAsync(_nats.GetConnectionString());
        _factory = new WebApplicationFactory<Program>().WithWebHostBuilder(web => web
            .UseSetting("ConnectionStrings:Catalog", _postgres.GetConnectionString())
            .UseSetting("FxMap:Nats:Url", _nats.GetConnectionString()));
        Client = _factory.CreateClient();
    }

    public async Task DisposeAsync()
    {
        if (_factory is not null) await _factory.DisposeAsync();
        await Sales.DisposeAsync();
        await _nats.DisposeAsync();
        await _postgres.DisposeAsync();
    }
}

public sealed class CatalogServiceTests(CatalogFixture fixture) : IClassFixture<CatalogFixture>
{
    [Fact]
    public async Task TheMigrationsCreateAndSeedTheCatalog()
    {
        var products = await fixture.Client.GetFromJsonAsync<List<ProductResponse>>("/products");

        Assert.NotNull(products);
        Assert.Equal([1, 2, 3, 4, 5], products.Select(p => p.Id));
        Assert.Equal(new ProductResponse(1, "ThinkPad X1 Carbon", 1899.00m, "Laptops"), products[0]);
        Assert.Equal("Accessories", products[4].Category);
    }

    [Fact]
    public async Task OneProduct()
    {
        var product = await fixture.Client.GetFromJsonAsync<ProductResponse>("/products/3");

        Assert.Equal(new ProductResponse(3, "Pixel 10", 799.00m, "Phones"), product);
        Assert.Equal(HttpStatusCode.NotFound, (await fixture.Client.GetAsync("/products/99")).StatusCode);
    }

    [Fact]
    public async Task FillsTheCustomerFieldsOfEveryReviewOverNats()
    {
        var reviews = await fixture.Client.GetFromJsonAsync<List<ReviewResponse>>("/reviews");

        Assert.NotNull(reviews);
        Assert.Equal([1, 2, 3, 4], reviews.Select(r => r.Id));
        var first = reviews[0];
        Assert.Equal("ThinkPad X1 Carbon", first.ProductName);
        Assert.Equal(SalesStandIn.Alice, first.CustomerId);
        Assert.Equal("Alice", first.CustomerName);
        Assert.Equal("alice@test", first.CustomerEmail);
        Assert.Equal("Hanoi", first.CustomerCity);
        Assert.Equal(2, first.CustomerOrderCount);
        Assert.Equal("Bob", reviews[2].CustomerName);
        // Carol is unknown to the stand-in: her fields stay empty
        Assert.Null(reviews[3].CustomerName);
        Assert.Null(reviews[3].CustomerOrderCount);
    }

    [Fact]
    public async Task OneReview()
    {
        var review = await fixture.Client.GetFromJsonAsync<ReviewResponse>("/reviews/3");

        Assert.NotNull(review);
        Assert.Equal("Pixel 10", review.ProductName);
        Assert.Equal("bob@test", review.CustomerEmail);
        Assert.Equal(HttpStatusCode.NotFound, (await fixture.Client.GetAsync("/reviews/99")).StatusCode);
    }

    [Fact]
    public async Task AnswersProductOfOverNatsFromEfCore()
    {
        var orders = new[] { new OrderView { ProductId = 4 }, new OrderView { ProductId = 2 } };

        await fixture.Sales.Mapper.MapDataAsync(orders);

        Assert.Equal("USB-C Dock", orders[0].ProductName);
        Assert.Equal(129.90m, orders[0].UnitPrice);
        Assert.Equal("Accessories", orders[0].CategoryName);
        Assert.Equal("MacBook Air 15", orders[1].ProductName);
        Assert.Equal("Laptops", orders[1].CategoryName);
    }

    private async Task<JsonElement> GraphQl(string query)
    {
        var response = await fixture.Client.PostAsJsonAsync("/graphql", new { query });
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        Assert.True(response.IsSuccessStatusCode, body.ToString());
        Assert.False(body.TryGetProperty("errors", out var errors), errors.ToString());
        return body.GetProperty("data");
    }

    [Fact]
    public async Task GraphQlFillsTheSelectedCustomerFieldsOverNats()
    {
        var data = await GraphQl("{ reviews { id productName customerName customerCity customerOrderCount } }");

        var reviews = data.GetProperty("reviews").EnumerateArray().ToList();
        Assert.Equal(4, reviews.Count);
        Assert.Equal("ThinkPad X1 Carbon", reviews[0].GetProperty("productName").GetString());
        Assert.Equal("Alice", reviews[0].GetProperty("customerName").GetString());
        Assert.Equal("Hanoi", reviews[0].GetProperty("customerCity").GetString());
        Assert.Equal(2, reviews[0].GetProperty("customerOrderCount").GetInt32());
        Assert.Equal("Bob", reviews[2].GetProperty("customerName").GetString());
        Assert.Equal(JsonValueKind.Null, reviews[3].GetProperty("customerName").ValueKind);
    }

    [Fact]
    public async Task GraphQlOneReviewAndTheProducts()
    {
        var data = await GraphQl(
            "{ review(id: 3) { customerEmail } missing: review(id: 99) { id } products { name price category } }");

        Assert.Equal("bob@test", data.GetProperty("review").GetProperty("customerEmail").GetString());
        Assert.Equal(JsonValueKind.Null, data.GetProperty("missing").ValueKind);
        var products = data.GetProperty("products").EnumerateArray().ToList();
        Assert.Equal(5, products.Count);
        Assert.Equal("Laptops", products[0].GetProperty("category").GetString());
        Assert.Equal(1899.00m, products[0].GetProperty("price").GetDecimal());
    }

    [Fact]
    public async Task SwaggerDescribesTheRestEndpoints()
    {
        var document = await fixture.Client.GetFromJsonAsync<JsonElement>("/swagger/v1/swagger.json");
        var paths = document.GetProperty("paths").EnumerateObject().Select(p => p.Name).ToList();

        Assert.Equal(["/products", "/products/{id}", "/reviews", "/reviews/{id}"], paths.Order());
        Assert.Equal(HttpStatusCode.OK, (await fixture.Client.GetAsync("/swagger/index.html")).StatusCode);
    }

    [Fact]
    public void TheSqlCommandsOfEfCoreAreLogged()
    {
        var loggers = fixture.Services.GetRequiredService<ILoggerFactory>();

        Assert.True(loggers.CreateLogger("Microsoft.EntityFrameworkCore.Database.Command").IsEnabled(LogLevel.Information));
        Assert.False(loggers.CreateLogger("Microsoft.EntityFrameworkCore.Query").IsEnabled(LogLevel.Information));
    }
}
