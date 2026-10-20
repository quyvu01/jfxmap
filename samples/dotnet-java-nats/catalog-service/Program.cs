using CatalogService.Data;
using CatalogService.Mapping;
using FxMap.Abstractions;
using FxMap.EntityFrameworkCore.Extensions;
using FxMap.Extensions;
using FxMap.HotChocolate.Extensions;
using FxMap.Nats.Extensions;
using Microsoft.EntityFrameworkCore;
using Serilog;

var builder = WebApplication.CreateBuilder(args);

// Serilog to the console; the levels are in appsettings.json ("Serilog"), where
// Microsoft.EntityFrameworkCore.Database.Command at Information prints each SQL command EF Core runs
builder.Services.AddSerilog((services, logger) => logger
    .ReadFrom.Configuration(builder.Configuration)
    .ReadFrom.Services(services)
    .WriteTo.Console(outputTemplate: "[{Timestamp:HH:mm:ss} {Level:u3}] {SourceContext}{NewLine}  {Message:lj}{NewLine}{Exception}"));

builder.Services.AddDbContext<CatalogDb>(options => options
    .UseNpgsql(builder.Configuration.GetConnectionString("Catalog"))
    // A sample: the logged SQL shows the values of its parameters too
    .EnableSensitiveDataLogging());

builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen();
var graphQl = builder.Services.AddGraphQLServer().AddQueryType<Query>();

// FxMap answers "ProductOf" from EF Core over NATS, and fills reviews from "CustomerOf", which sales-service owns:
// in the REST endpoints with IDistributedMapper, in GraphQL with FxMap.HotChocolate
builder.Services.AddFxMap(cfg =>
    {
        cfg.AddEntitiesFromAssemblyContaining<ProductConfig>();
        cfg.AddProfilesFromAssemblyContaining<ProductConfig>();
        cfg.AddNats(nats => nats.NatsOpts(o => o.Url = builder.Configuration["FxMap:Nats:Url"] ?? "nats://localhost:4222"));
        cfg.ThrowIfException();
    })
    .AddEntityFrameworkCore(cfg => cfg.AddDbContexts(typeof(CatalogDb)))
    .AddHotChocolate(cfg => cfg.AddRequestExecutorBuilder(graphQl));

var app = builder.Build();

// Creates the tables and seeds the sample data (see Migrations/ and CatalogSeed)
using (var scope = app.Services.CreateScope())
{
    await scope.ServiceProvider.GetRequiredService<CatalogDb>().Database.MigrateAsync();
}

// One line per HTTP call: "HTTP GET /reviews responded 200 in 35.1 ms"
app.UseSerilogRequestLogging();
app.UseSwagger();
app.UseSwaggerUI();
app.MapGraphQL();

app.MapGet("/products", async (CatalogDb db) => await Catalog.Products(db.Products).ToListAsync());

app.MapGet("/products/{id:int}", async (int id, CatalogDb db) =>
    await Catalog.Products(db.Products.Where(p => p.Id == id)).FirstOrDefaultAsync() is { } product
        ? Results.Ok(product)
        : Results.NotFound());

// Every review, with the customer fields of all of them fetched from sales-service in one NATS request
app.MapGet("/reviews", async (CatalogDb db, IDistributedMapper mapper) =>
{
    var reviews = await Catalog.Reviews(db.Reviews).ToListAsync();
    await mapper.MapDataAsync(reviews);
    return reviews;
});

app.MapGet("/reviews/{id:int}", async (int id, CatalogDb db, IDistributedMapper mapper) =>
{
    var review = await Catalog.Reviews(db.Reviews.Where(r => r.Id == id)).FirstOrDefaultAsync();
    if (review is null) return Results.NotFound();
    await mapper.MapDataAsync(review);
    return Results.Ok(review);
});

app.Run();

/// <summary>Lets the tests start the service with <c>WebApplicationFactory</c>.</summary>
public partial class Program;
