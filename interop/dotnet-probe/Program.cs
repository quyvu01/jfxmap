using System.Text.Json;
using FxMap.Abstractions;
using FxMap.EntityFrameworkCore.Extensions;
using FxMap.Extensions;
using FxMap.Fluent;
using FxMap.Kafka.Extensions;
using FxMap.Nats.Extensions;
using FxMap.RabbitMq.Extensions;
using FxMap.Registries;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;

// DotnetProbe owner|consumer nats|rabbitmq|kafka <address>
//   owner:    owns the string key UserOf (users 1 Alice, 2 Bob) and answers over the transport until it is stopped
//   consumer: maps the orders of users 1, 2 and 3 over the transport and prints them as JSON
var (role, transport, address) = (args[0], args[1], args[2]);

var builder = Host.CreateApplicationBuilder();
builder.Services.AddDbContext<UsersDb>(o => o.UseInMemoryDatabase("users"));
var fxMap = builder.Services.AddFxMap(cfg =>
{
    if (role == "owner") cfg.AddEntityConfigs(typeof(UserConfig));
    else cfg.AddProfileConfigs(typeof(OrderViewProfile));
    cfg.ThrowIfException();
    Action<IMapConfigurator> addTransport = transport switch
    {
        "nats" => c => c.AddNats(n => n.NatsOpts(o => o.Url = address)),
        "rabbitmq" => c => c.AddRabbitMq(r =>
        {
            var parts = address.Split(':');
            r.Host(parts[0], "/", int.Parse(parts[1]));
        }),
        "kafka" => c => c.AddKafka(k => k.Host(address)),
        _ => throw new ArgumentException(transport)
    };
    addTransport(cfg);
});
if (role == "owner") fxMap.AddEntityFrameworkCore(c => c.AddDbContexts(typeof(UsersDb)));

var host = builder.Build();
if (role == "owner")
{
    using (var scope = host.Services.CreateScope())
    {
        var db = scope.ServiceProvider.GetRequiredService<UsersDb>();
        db.Users.AddRange(new User { Id = 1, Name = "Alice", Email = "alice@dotnet" },
            new User { Id = 2, Name = "Bob", Email = "bob@dotnet" });
        db.SaveChanges();
    }

    await host.StartAsync();
    Console.WriteLine("READY");
    await host.WaitForShutdownAsync();
    return 0;
}

await host.StartAsync();
var mapper = host.Services.GetRequiredService<IDistributedMapper>();
var orders = new[] { new OrderView { UserId = 1 }, new OrderView { UserId = 2 }, new OrderView { UserId = 3 } };
try
{
    await mapper.MapDataAsync(orders, new HeadersContext());
    Console.WriteLine("RESULT " + JsonSerializer.Serialize(orders));
    return 0;
}
catch (Exception e)
{
    Console.WriteLine("FAILED " + e);
    return 1;
}
finally
{
    await host.StopAsync();
}

public sealed class HeadersContext : IContext
{
    public Dictionary<string, string> Headers { get; } = new() { ["x-probe"] = "dotnet" };
    public CancellationToken CancellationToken => CancellationToken.None;
}

public sealed class User
{
    public int Id { get; set; }
    public string Name { get; set; }
    public string Email { get; set; }
}

public sealed class UsersDb(DbContextOptions<UsersDb> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
}

public sealed class UserConfig : EntityConfigureOf<User>
{
    protected override void Configure()
    {
        Id(x => x.Id);
        DefaultProperty(x => x.Name);
        UseDistributedKey("UserOf");
        ExposedName(x => x.Email, "UserEmail");
    }
}

public sealed class OrderView
{
    public int UserId { get; set; }
    public string UserName { get; set; }
    public string UserEmail { get; set; }
}

public sealed class OrderViewProfile : ProfileOf<OrderView>
{
    protected override void Configure() =>
        UseDistributedKey("UserOf").Of(x => x.UserId).For(x => x.UserName).For(x => x.UserEmail, "UserEmail");
}
