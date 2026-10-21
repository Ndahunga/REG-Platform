package rw.terimbere.csams.shared.file;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.file-storage", name = "type", havingValue = "local", matchIfMissing = true)
    public FileStorageService localFileStorageService(FileStorageProperties properties) {
        return new LocalFileStorageService(properties);
    }
}
