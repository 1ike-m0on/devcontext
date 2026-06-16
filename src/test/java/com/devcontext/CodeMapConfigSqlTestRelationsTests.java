package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.adapters.filesystem.LocalProjectScanner;
import com.devcontext.application.codemap.CodeMapGenerator;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeMapRoutingHint;
import com.devcontext.domain.codemap.CodeMapTestRelation;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeMapConfigSqlTestRelationsTests {

    private final LocalProjectScanner scanner = new LocalProjectScanner();
    private final CodeMapGenerator generator = new CodeMapGenerator();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path root;

    @Test
    void scannerPopulatesConfigSqlMapperEntityAndTestRelationsIntoCodeMapV2() throws Exception {
        write("pom.xml", """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        write("src/main/resources/application.yml", """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost/orders
                    username: orders
                management:
                  endpoints:
                    web:
                      exposure:
                        include: health,info
                """);
        write("src/main/resources/application.properties", """
                feature.orders.enabled=true
                orders.page-size=50
                """);
        write(".env.example", """
                SPRING_DATASOURCE_URL=
                REDIS_URL=
                """);
        write("src/main/resources/db/migration/V1__orders.sql", """
                CREATE TABLE orders (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(32) NOT NULL
                );
                CREATE INDEX idx_orders_status ON orders (status);
                """);
        write("src/main/resources/mappers/OrderMapper.xml", """
                <?xml version="1.0" encoding="UTF-8" ?>
                <mapper namespace="com.acme.order.OrderMapper">
                    <select id="findByStatus" resultType="com.acme.order.OrderEntity">
                        SELECT * FROM orders WHERE status = #{status}
                    </select>
                </mapper>
                """);
        write("src/main/java/com/acme/order/OrderService.java", """
                package com.acme.order;

                import java.util.List;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    private final OrderMapper orderMapper;

                    public OrderService(OrderMapper orderMapper) {
                        this.orderMapper = orderMapper;
                    }

                    public List<OrderEntity> listOrders() {
                        return orderMapper.findByStatus("OPEN");
                    }
                }
                """);
        write("src/main/java/com/acme/order/OrderMapper.java", """
                package com.acme.order;

                import java.util.List;

                public interface OrderMapper {
                    List<OrderEntity> findByStatus(String status);
                }
                """);
        write("src/main/java/com/acme/order/OrderEntity.java", """
                package com.acme.order;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "orders")
                public class OrderEntity {
                    private Long id;
                }
                """);
        write("src/test/java/com/acme/order/OrderServiceTest.java", """
                package com.acme.order;

                class OrderServiceTest {
                    void listOrders() {
                    }
                }
                """);

        ProjectScan scan = scanner.scan(root.toString());
        CodeMap codeMap = generator.generate(project(), scan);
        CodeMap parsed = objectMapper.readValue(objectMapper.writeValueAsString(codeMap), CodeMap.class);

        assertThat(parsed.isV2()).isTrue();
        assertThat(parsed.configKeys()).extracting("key")
                .contains(
                        "spring.datasource.url",
                        "management.endpoints.web.exposure.include",
                        "feature.orders.enabled",
                        "orders.page-size",
                        "SPRING_DATASOURCE_URL",
                        "REDIS_URL"
                );

        assertThat(parsed.sqlHints())
                .anySatisfy(hint -> assertHint(
                        hint,
                        "sql_table",
                        "orders",
                        "src/main/resources/db/migration/V1__orders.sql"
                ))
                .anySatisfy(hint -> assertHint(
                        hint,
                        "sql_index",
                        "idx_orders_status",
                        "src/main/resources/db/migration/V1__orders.sql"
                ))
                .anySatisfy(hint -> assertHint(
                        hint,
                        "sql_table",
                        "orders",
                        "src/main/resources/mappers/OrderMapper.xml"
                ));
        assertThat(parsed.mapperHints())
                .anySatisfy(hint -> assertHint(
                        hint,
                        "mapper",
                        "OrderMapper",
                        "src/main/java/com/acme/order/OrderMapper.java"
                ))
                .anySatisfy(hint -> assertHint(
                        hint,
                        "mapper_xml",
                        "OrderMapper",
                        "src/main/resources/mappers/OrderMapper.xml"
                ))
                .anySatisfy(hint -> assertHint(
                        hint,
                        "mapper_statement",
                        "findByStatus",
                        "src/main/resources/mappers/OrderMapper.xml"
                ));
        assertThat(parsed.entityHints())
                .anySatisfy(hint -> assertHint(
                        hint,
                        "entity",
                        "OrderEntity",
                        "src/main/java/com/acme/order/OrderEntity.java"
                ))
                .anySatisfy(hint -> assertHint(
                        hint,
                        "entity_table",
                        "orders",
                        "src/main/java/com/acme/order/OrderEntity.java"
                ));

        assertThat(parsed.testRelations())
                .anySatisfy(this::assertOrderServiceTestRelation);
        assertThat(fileEntry(parsed, "src/main/resources/db/migration/V1__orders.sql").kind())
                .isEqualTo("database_schema");
        assertThat(fileEntry(parsed, "src/main/resources/mappers/OrderMapper.xml").kind())
                .isEqualTo("mapper");
        assertThat(fileEntry(parsed, "src/test/java/com/acme/order/OrderServiceTest.java").kind())
                .isEqualTo("test");

        assertThat(parsed.configs())
                .contains("src/main/resources/application.yml", "src/main/resources/application.properties", ".env.example");
        assertThat(parsed.testRoots()).contains("src/test/java");
        assertThat(parsed.symbols()).extracting("name").contains("OrderService", "OrderMapper", "OrderEntity");
    }

    private Project project() {
        Instant now = Instant.parse("2026-06-16T00:00:00Z");
        return new Project(
                1L,
                "order-service",
                root.toString(),
                "Java",
                "Spring Boot",
                "main",
                now,
                now
        );
    }

    private void assertHint(CodeMapRoutingHint hint, String kind, String name, String file) {
        assertThat(hint.kind()).isEqualTo(kind);
        assertThat(hint.name()).isEqualTo(name);
        assertThat(hint.file()).isEqualTo(file);
    }

    private void assertOrderServiceTestRelation(CodeMapTestRelation relation) {
        assertThat(relation.testFile()).isEqualTo("src/test/java/com/acme/order/OrderServiceTest.java");
        assertThat(relation.targetFile()).isEqualTo("src/main/java/com/acme/order/OrderService.java");
        assertThat(relation.relationType()).isEqualTo("name_convention");
    }

    private CodeMapFileEntry fileEntry(CodeMap codeMap, String path) {
        return codeMap.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
