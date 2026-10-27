package com.pawhome.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication = @ComponentScan + @EnableAutoConfiguration + @Configuration.
//
// The @ComponentScan half is why every feature package lives BELOW this one
// (com.pawhome.backend.*). Anything annotated @RestController / @Service /
// @Repository / @Component / @Configuration under here is found automatically.
//
// Package by FEATURE, not by layer:
//   com.pawhome.backend.pet/     Pet, PetRepository, PetService, PetController, dto/
//   com.pawhome.backend.user/    User, UserRepository, ...
// Never controllers/ services/ repositories/.
@SpringBootApplication
public class PawhomeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PawhomeApplication.class, args);
    }
}
