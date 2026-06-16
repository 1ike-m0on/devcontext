package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.adapters.filesystem.LocalProjectScanner;
import com.devcontext.application.codemap.CodeMapGenerator;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeMapFileEntry;
import com.devcontext.domain.codemap.CodeSymbol;
import com.devcontext.domain.project.Project;
import com.devcontext.domain.project.ProjectScan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeMapJavaSignalsTests {

    private final LocalProjectScanner scanner = new LocalProjectScanner();
    private final CodeMapGenerator generator = new CodeMapGenerator();

    @TempDir
    Path root;

    @Test
    void scannerPopulatesControllerServiceRepositoryEntitySignalsIntoCodeMapV2() throws Exception {
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
        write("src/main/java/com/acme/order/OrderController.java", """
                package com.acme.order;

                import java.util.List;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/orders")
                public class OrderController {
                    private final OrderService orderService;

                    public OrderController(OrderService orderService) {
                        this.orderService = orderService;
                    }

                    @GetMapping
                    public List<OrderEntity> list() {
                        return orderService.listOrders();
                    }

                    @PostMapping("/{id}/refresh")
                    public void refresh(@PathVariable String id) {
                        orderService.refreshOrder(id);
                    }
                }
                """);
        write("src/main/java/com/acme/order/OrderService.java", """
                package com.acme.order;

                import java.util.List;
                import org.springframework.stereotype.Service;

                @Service
                public class OrderService {
                    private final OrderRepository orderRepository;

                    public OrderService(OrderRepository orderRepository) {
                        this.orderRepository = orderRepository;
                    }

                    public List<OrderEntity> listOrders() {
                        return orderRepository.findByStatus("OPEN");
                    }

                    public void refreshOrder(String id) {
                    }
                }
                """);
        write("src/main/java/com/acme/order/OrderRepository.java", """
                package com.acme.order;

                import java.util.List;
                import org.springframework.stereotype.Repository;

                @Repository
                public interface OrderRepository {
                    List<OrderEntity> findByStatus(String status);

                    void deleteById(Long id);
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

        ProjectScan scan = scanner.scan(root.toString());
        CodeMap codeMap = generator.generate(project(), scan);

        assertThat(codeMap.isV2()).isTrue();
        assertThat(symbol(codeMap, "OrderController").role()).isEqualTo("controller");
        assertThat(symbol(codeMap, "OrderController").methods()).contains("list", "refresh");
        assertThat(codeMap.endpoints())
                .anySatisfy(endpoint -> {
                    assertThat(endpoint.httpMethod()).isEqualTo("GET");
                    assertThat(endpoint.path()).isEqualTo("/api/orders");
                    assertThat(endpoint.handlerMethod()).isEqualTo("list");
                    assertThat(endpoint.className()).isEqualTo("OrderController");
                })
                .anySatisfy(endpoint -> {
                    assertThat(endpoint.httpMethod()).isEqualTo("POST");
                    assertThat(endpoint.path()).isEqualTo("/api/orders/{id}/refresh");
                    assertThat(endpoint.handlerMethod()).isEqualTo("refresh");
                });

        assertThat(symbol(codeMap, "OrderService").role()).isEqualTo("service");
        assertThat(symbol(codeMap, "OrderService").methods()).contains("listOrders", "refreshOrder");
        assertThat(symbol(codeMap, "OrderRepository").role()).isEqualTo("repository");
        assertThat(symbol(codeMap, "OrderRepository").methods()).contains("findByStatus", "deleteById");
        assertThat(symbol(codeMap, "OrderEntity").role()).isEqualTo("entity");

        assertThat(fileRoles(codeMap, "src/main/java/com/acme/order/OrderController.java"))
                .contains("controller", "endpoint", "spring-component");
        assertThat(fileRoles(codeMap, "src/main/java/com/acme/order/OrderService.java"))
                .contains("service", "spring-component");
        assertThat(fileRoles(codeMap, "src/main/java/com/acme/order/OrderRepository.java"))
                .contains("repository", "data-access", "spring-component");
        assertThat(fileRoles(codeMap, "src/main/java/com/acme/order/OrderEntity.java"))
                .contains("entity", "domain-entity");

        assertThat(codeMap.symbols()).extracting("name")
                .contains("OrderController", "OrderService", "OrderRepository", "OrderEntity");
        assertThat(codeMap.files()).extracting("path")
                .contains("src/main/java/com/acme/order/OrderController.java");
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

    private CodeSymbol symbol(CodeMap codeMap, String name) {
        return codeMap.symbols().stream()
                .filter(symbol -> symbol.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private List<String> fileRoles(CodeMap codeMap, String path) {
        return codeMap.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .map(CodeMapFileEntry::roles)
                .orElseThrow();
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
