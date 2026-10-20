using FxMap.Abstractions;
using FxMap.EntityFrameworkCore.Extensions;
using FxMap.Extensions;
using FxMap.Fluent;
using FxMap.Nats.Extensions;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

namespace CatalogService.Tests;

/// <summary>
/// A stand-in for sales-service: a .NET FxMap service on the same NATS that owns "CustomerOf" (Alice and Bob only)
/// and maps orders from "ProductOf", as the Java service does.
/// </summary>
public sealed class SalesStandIn : IAsyncDisposable
{
    public static readonly Guid Alice = Guid.Parse("0b6e1b9a-55b1-4c5e-9f57-0a3c3a0d7e11");
    public static readonly Guid Bob = Guid.Parse("6f9619ff-8b86-d011-b42d-00c04fc964ff");

    private readonly IHost _host;

    private SalesStandIn(IHost host) => _host = host;

    public IDistributedMapper Mapper => _host.Services.GetRequiredService<IDistributedMapper>();

    public static async Task<SalesStandIn> StartAsync(string natsUrl)
    {
        var builder = Host.CreateApplicationBuilder();
        builder.Services.AddDbContext<CustomersDb>(o => o.UseInMemoryDatabase("customers"));
        builder.Services.AddFxMap(cfg =>
            {
                cfg.AddEntityConfigs(typeof(CustomerConfig));
                cfg.AddProfileConfigs(typeof(OrderViewProfile));
                cfg.AddNats(n => n.NatsOpts(o => o.Url = natsUrl));
                cfg.ThrowIfException();
            })
            .AddEntityFrameworkCore(c => c.AddDbContexts(typeof(CustomersDb)));
        var host = builder.Build();
        using (var scope = host.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<CustomersDb>();
            db.Customers.AddRange(
                new Customer
                {
                    Id = Alice, Name = "Alice", Email = "alice@test", City = "Hanoi",
                    Orders = [new CustomerOrder { Id = 1 }, new CustomerOrder { Id = 2 }]
                },
                new Customer
                {
                    Id = Bob, Name = "Bob", Email = "bob@test", City = "Lyon", Orders = [new CustomerOrder { Id = 3 }]
                });
            await db.SaveChangesAsync();
        }
        await host.StartAsync();
        return new SalesStandIn(host);
    }

    public async ValueTask DisposeAsync()
    {
        await _host.StopAsync();
        _host.Dispose();
    }
}

public sealed class Customer
{
    public Guid Id { get; set; }
    public required string Name { get; set; }
    public required string Email { get; set; }
    public string? City { get; set; }
    public List<CustomerOrder> Orders { get; set; } = [];
}

public sealed class CustomerOrder
{
    public int Id { get; set; }
}

public sealed class CustomersDb(DbContextOptions<CustomersDb> options) : DbContext(options)
{
    public DbSet<Customer> Customers => Set<Customer>();
}

public sealed class CustomerConfig : EntityConfigureOf<Customer>
{
    protected override void Configure()
    {
        Id(x => x.Id);
        DefaultProperty(x => x.Name);
        UseDistributedKey("CustomerOf");
        ExposedName(x => x.Email, "CustomerEmail");
    }
}

public sealed class OrderView
{
    public int ProductId { get; set; }
    public string? ProductName { get; set; }
    public decimal? UnitPrice { get; set; }
    public string? CategoryName { get; set; }
}

public sealed class OrderViewProfile : ProfileOf<OrderView>
{
    protected override void Configure() =>
        UseDistributedKey("ProductOf")
            .Of(x => x.ProductId)
            .For(x => x.ProductName)
            .For(x => x.UnitPrice, "UnitPrice")
            .For(x => x.CategoryName, "Category.Name");
}
