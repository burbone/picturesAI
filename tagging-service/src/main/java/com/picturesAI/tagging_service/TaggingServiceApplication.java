package com.picturesAI.tagging_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TaggingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(TaggingServiceApplication.class, args);
	}
}