using FxMap.Fluent;

namespace CatalogService.Mapping;

/// <summary>Which customer fields a review shows, read from sales-service by the string key "CustomerOf".</summary>
public sealed class ReviewResponseProfile : ProfileOf<ReviewResponse>
{
    protected override void Configure() =>
        UseDistributedKey("CustomerOf")
            .Of(x => x.CustomerId)
            .For(x => x.CustomerName)                         // the default property
            .For(x => x.CustomerEmail, "CustomerEmail")        // an exposed name
            .For(x => x.CustomerCity, "City")                  // a plain property
            .For(x => x.CustomerOrderCount, "Orders:count");   // a collection, counted by jfxmap's JPA provider
}
