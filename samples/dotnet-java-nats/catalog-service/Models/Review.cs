namespace CatalogService.Models;

/// <summary>A review of a product. Only the customer's id is here: the customers are owned by sales-service (Java).</summary>
public sealed class Review
{
    public int Id { get; set; }
    public int ProductId { get; set; }
    public Product? Product { get; set; }
    public Guid CustomerId { get; set; }
    public int Rating { get; set; }
    public required string Comment { get; set; }
}
