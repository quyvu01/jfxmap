package io.github.quyvu01.jfxmap.samples.sales;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    /** Every order with its customer's fields, which are local. */
    @Query("""
            select new io.github.quyvu01.jfxmap.samples.sales.OrderResponse(
                o.id, c.id, c.name, o.productId, o.quantity)
            from SalesOrder o join o.customer c order by o.id""")
    List<OrderResponse> findResponses();

    @Query("""
            select new io.github.quyvu01.jfxmap.samples.sales.OrderResponse(
                o.id, c.id, c.name, o.productId, o.quantity)
            from SalesOrder o join o.customer c where o.id = :id""")
    Optional<OrderResponse> findResponse(long id);
}
