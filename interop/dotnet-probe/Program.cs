using System.Diagnostics;
using System.Text.Json;
using Amazon;
using FxMap.Aws.Sqs.Extensions;
using FxMap.Azure.ServiceBus.Extensions;
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

// DotnetProbe owner|consumer nats|rabbitmq|kafka|sqs|servicebus <address> [trace]
//   the address of sqs is the endpoint of LocalStack, that of servicebus the connection string of the emulator
//   owner:    owns the string key UserOf (users 1 Alice, 2 Bob) and answers over the transport until it is stopped
//   consumer: maps the orders of users 1, 2 and 3 over the transport and prints them as JSON
//   trace:    records the activities of FxMap, as an OpenTelemetry SDK would, and prints each one as it stops:
//             SPAN <name> <trace id> <span id> <parent span id>
var (role, transport, address) = (args[0], args[1], args[2]);
using var listener = args.Length > 3 && args[3] == "trace" ? Trace() : null;

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
        "sqs" => c => c.AddSqs(s => s.Region(RegionEndpoint.USEast1, credential =>
        {
            credential.AccessKeyId("test");
            credential.SecretAccessKey("test");
            credential.ServiceUrl(address);
        })),
        // The emulator answers a wait for the next session only when it times out: a short one picks up new sessions
        "servicebus" => c => c.AddAzureServiceBus(s =>
            s.Host(address, o => o.RetryOptions.TryTimeout = TimeSpan.FromSeconds(3))),
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

static ActivityListener Trace()
{
    var listener = new ActivityListener
    {
        ShouldListenTo = source => source.Name == "FxMap",
        Sample = (ref ActivityCreationOptions<ActivityContext> _) => ActivitySamplingResult.AllDataAndRecorded,
        ActivityStopped = activity => Console.WriteLine(
            $"SPAN {activity.OperationName} {activity.TraceId} {activity.SpanId} {activity.ParentSpanId}")
    };
    ActivitySource.AddActivityListener(listener);
    return listener;
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
