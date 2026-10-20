using CatalogService.Models;
using FxMap.Fluent;

namespace CatalogService.Mapping;

/// <summary>
/// What this service exposes to others: products under the string key "ProductOf", answered from EF Core. A string key
/// is what lets a Java service (jfxmap) ask for it with <c>useDistributedKey("ProductOf")</c>.
/// </summary>
public sealed class ProductConfig : EntityConfigureOf<Product>
{
    protected override void Configure()
    {
        Id(x => x.Id);
        DefaultProperty(x => x.Name);
        UseDistributedKey("ProductOf");
        ExposedName(x => x.Price, "UnitPrice");
    }
}
