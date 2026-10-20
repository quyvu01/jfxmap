namespace CatalogService.Models;

public sealed class Category
{
    public int Id { get; set; }
    public required string Name { get; set; }
    public List<Product> Products { get; set; } = [];
}
