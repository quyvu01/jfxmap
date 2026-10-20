package io.github.quyvu01.jfxmap.benchmarks;

import io.github.quyvu01.jfxmap.DistributedKey;
import io.github.quyvu01.jfxmap.DistributedMapper;
import io.github.quyvu01.jfxmap.FxMap;
import io.github.quyvu01.jfxmap.fluent.EntityConfigureOf;
import io.github.quyvu01.jfxmap.fluent.ProfileOf;
import io.github.quyvu01.jfxmap.provider.InMemoryQueryHandler;
import io.github.quyvu01.jfxmap.provider.LocalRequestClient;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

/**
 * The whole engine in one process: a {@link DistributedMapper} over a {@link LocalRequestClient} and
 * {@link InMemoryQueryHandler}s, filling DTOs with plain properties, navigations, an aggregation, a chained rule (the
 * province id comes from the user, the province name from the province) and nested DTOs with their own profile.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MappingBenchmark {
    static final int USERS = 1_000;
    static final int PROVINCES = 64;
    static final int PRODUCTS = 50;

    public static final class UserOf implements DistributedKey {
    }

    public static final class ProductOf implements DistributedKey {
    }

    public record Country(String name) {
    }

    public record Order(String status, double total) {
    }

    public record User(int id, String name, String email, Country country, int provinceId, List<Order> orders) {
    }

    public record Province(int id, String name) {
    }

    public record Product(int id, String name) {
    }

    public static class UserConfig extends EntityConfigureOf<User> {
        @Override
        protected void configure() {
            id(User::id);
            defaultProperty(User::name);
            useDistributedKey(UserOf.class);
            exposedName(User::email, "UserEmail");
        }
    }

    public static class ProvinceConfig extends EntityConfigureOf<Province> {
        @Override
        protected void configure() {
            id(Province::id);
            defaultProperty(Province::name);
            useDistributedKey("ProvinceOf");
        }
    }

    public static class ProductConfig extends EntityConfigureOf<Product> {
        @Override
        protected void configure() {
            id(Product::id);
            defaultProperty(Product::name);
            useDistributedKey(ProductOf.class);
        }
    }

    public static class LineDto {
        private final int productId;
        private String productName;

        public LineDto(int productId) {
            this.productId = productId;
        }

        public int getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public void setProductName(String productName) {
            this.productName = productName;
        }
    }

    public static class OrderDto {
        private final int userId;
        private String userName;
        private String userEmail;
        private String countryName;
        private double doneTotal;
        private Integer provinceId;
        private String provinceName;
        private final List<LineDto> lines = new ArrayList<>();

        public OrderDto(int userId) {
            this.userId = userId;
        }

        public int getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public void setUserName(String userName) {
            this.userName = userName;
        }

        public String getUserEmail() {
            return userEmail;
        }

        public void setUserEmail(String userEmail) {
            this.userEmail = userEmail;
        }

        public String getCountryName() {
            return countryName;
        }

        public void setCountryName(String countryName) {
            this.countryName = countryName;
        }

        public double getDoneTotal() {
            return doneTotal;
        }

        public void setDoneTotal(double doneTotal) {
            this.doneTotal = doneTotal;
        }

        public Integer getProvinceId() {
            return provinceId;
        }

        public void setProvinceId(Integer provinceId) {
            this.provinceId = provinceId;
        }

        public String getProvinceName() {
            return provinceName;
        }

        public void setProvinceName(String provinceName) {
            this.provinceName = provinceName;
        }

        public List<LineDto> getLines() {
            return lines;
        }
    }

    public static class OrderProfile extends ProfileOf<OrderDto> {
        @Override
        protected void configure() {
            useDistributedKey(UserOf.class)
                    .of(OrderDto::getUserId)
                    .map(OrderDto::getUserName)
                    .map(OrderDto::getUserEmail, "UserEmail")
                    .map(OrderDto::getCountryName, "Country?.Name")
                    .map(OrderDto::getDoneTotal, "Orders:where(Status = 'Done'):sum(Total)")
                    .map(OrderDto::getProvinceId, "ProvinceId");
            useDistributedKey("ProvinceOf")
                    .of(OrderDto::getProvinceId)
                    .map(OrderDto::getProvinceName);
        }
    }

    public static class LineProfile extends ProfileOf<LineDto> {
        @Override
        protected void configure() {
            useDistributedKey(ProductOf.class)
                    .of(LineDto::getProductId)
                    .map(LineDto::getProductName);
        }
    }

    /** The number of DTOs mapped in one call. */
    @Param({"1", "100", "10000"})
    public int dtos;

    DistributedMapper mapper;

    @Setup
    public void setUp() {
        var users = IntStream.range(0, USERS).mapToObj(i -> new User(i, "User " + i, "user" + i + "@example.com",
                i % 10 == 0 ? null : new Country("Country " + i % 7), i % PROVINCES,
                List.of(new Order("Done", i), new Order("Open", 1), new Order("Done", 2.5)))).toList();
        var provinces = IntStream.range(0, PROVINCES).mapToObj(i -> new Province(i, "Province " + i)).toList();
        var products = IntStream.range(0, PRODUCTS).mapToObj(i -> new Product(i, "Product " + i)).toList();
        var client = new LocalRequestClient(
                new InMemoryQueryHandler<>(new UserConfig(), users),
                new InMemoryQueryHandler<>(new ProvinceConfig(), provinces),
                new InMemoryQueryHandler<>(new ProductConfig(), products));
        mapper = FxMap.builder()
                .addProfiles(new OrderProfile(), new LineProfile())
                .requestClient(client)
                .throwIfException()
                .build();
    }

    /** New DTOs each time, as an application maps the ones it has just read. */
    static List<OrderDto> newDtos(int count) {
        var result = new ArrayList<OrderDto>(count);
        for (var i = 0; i < count; i++) {
            var dto = new OrderDto(i % USERS);
            dto.getLines().add(new LineDto(i % PRODUCTS));
            dto.getLines().add(new LineDto((i + 1) % PRODUCTS));
            result.add(dto);
        }
        return result;
    }

    @Benchmark
    public List<OrderDto> map() {
        var result = newDtos(dtos);
        mapper.map(result);
        return result;
    }
}
