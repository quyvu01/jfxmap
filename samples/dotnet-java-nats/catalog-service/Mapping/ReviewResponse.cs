namespace CatalogService.Mapping;

/// <summary>A review with its customer's fields, which FxMap fills from sales-service (Java) over NATS.</summary>
public sealed class ReviewResponse
{
    public int Id { get; set; }
    public int ProductId { get; set; }
    public string? ProductName { get; set; }
    public int Rating { get; set; }
    public string? Comment { get; set; }
    public Guid CustomerId { get; set; }
    public string? CustomerName { get; set; }
    public string? CustomerEmail { get; set; }
    public string? CustomerCity { get; set; }
    public int? CustomerOrderCount { get; set; }
}
