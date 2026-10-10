package org.example.subscriptions;

import java.math.BigDecimal;
import javax.sql.DataSource;
import org.example.subscriptions.model.Client;
import org.example.subscriptions.model.ClientStatus;
import org.example.subscriptions.model.Tariff;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import su.onno.metadata.MetadataRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SubscriptionsApplicationTest {
    @Autowired
    private MetadataRegistry metadata;
    @Autowired
    private DataSource dataSource;

    @Test
    void realOnnoContextRegistersCatalogsAndUsesIsolatedStorage() throws Exception {
        assertThat(metadata.allCatalogs()).extracting(c -> c.logicalName()).containsExactlyInAnyOrder("Clients",
                "Tariffs");

        assertThat(metadata.getCatalogDescriptor(Client.class).attributes()).extracting(attribute -> attribute.fieldName())
                .contains("status", "email", "phone", "registrationDate");
        var price = metadata.getCatalogDescriptor(Tariff.class).attributes().stream().filter(attribute -> attribute.fieldName()
                .equals("price")).findFirst().orElseThrow();
        assertThat(price.javaType()).isEqualTo(BigDecimal.class);
        assertThat(price.scale()).isEqualTo(2);
        assertThat(metadata.getEnumerationDescriptor(ClientStatus.class)).isNotNull();
        assertThat(metadata.allDocuments()).extracting(d -> d.logicalName()).containsExactlyInAnyOrder("Payments",
                "Subscriptions");
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:subscriptions-test");
        }
    }
}
