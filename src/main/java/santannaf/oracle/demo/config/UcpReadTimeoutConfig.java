package santannaf.oracle.demo.config;

import oracle.ucp.jdbc.PoolDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;
import java.util.Properties;

/**
 * Injeta oracle.jdbc.ReadTimeout no PoolDataSource do UCP.
 * Necessario porque Spring Boot auto-config nao mapeia connection-factory-properties corretamente.
 * Esse timeout desbloqueia conexoes congeladas por docker pause (TCP fica vivo mas processo parado).
 */
@Configuration
@Profile("ucp")
class UcpReadTimeoutConfig {

    @Bean
    static BeanPostProcessor ucpReadTimeoutPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof PoolDataSource pds) {
                    try {
                        var props = new Properties();
                        props.setProperty("oracle.jdbc.ReadTimeout", "3000");
                        props.setProperty("oracle.net.READ_TIMEOUT", "3");
                        pds.setConnectionProperties(props);
                    } catch (SQLException e) {
                        throw new RuntimeException("Falha ao configurar ReadTimeout no UCP", e);
                    }
                }
                return bean;
            }
        };
    }
}
