package com.finch;

import org.springframework.boot.SpringApplication;

public class TestFinchApplication {

	public static void main(String[] args) {
		SpringApplication.from(FinchApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
