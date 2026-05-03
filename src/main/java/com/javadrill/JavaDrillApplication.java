package com.javadrill;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class JavaDrillApplication {
    public static void main(String[] args) {
        SpringApplication.run(JavaDrillApplication.class, args);
    }
}
