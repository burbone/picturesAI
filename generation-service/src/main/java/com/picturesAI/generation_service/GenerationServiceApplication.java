package com.picturesAI.generation_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GenerationServiceApplication {

	public static void main(String[] args) {
		System.setProperty("java.awt.headless", "true");
		SpringApplication.run(GenerationServiceApplication.class, args);
	}
}