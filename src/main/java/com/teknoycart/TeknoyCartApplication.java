package com.teknoycart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import com.teknoycart.services.EmailService;

@SpringBootApplication
public class TeknoyCartApplication implements CommandLineRunner {

    @Autowired
    private EmailService emailService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public static void main(String[] args) {
        SpringApplication.run(TeknoyCartApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // Automatically set up PostgreSQL implicit casts so Java/Hibernate String bindings 
        // can be cleanly mapped to the custom DB enum 'user_role' without cast errors.
        try {
            jdbcTemplate.execute("CREATE CAST (varchar AS user_role) WITH INOUT AS IMPLICIT");
            System.out.println("====== PostgreSQL varchar -> user_role implicit cast setup successful! ======");
        } catch (Exception e) {
            System.out.println("PostgreSQL varchar -> user_role cast setup notice: " + e.getMessage());
        }

        try {
            jdbcTemplate.execute("CREATE CAST (character varying AS user_role) WITH INOUT AS IMPLICIT");
        } catch (Exception e) {
            // Ignore if already exists
        }

        // System.out.println("====== STARTING SMTP TEST DISPATCH ON STARTUP ======");
        // try {
        //     emailService.sendVerificationEmail("clarencekirk.macapobre@cit.edu", "Clarence", "TEST_STARTUP_TOKEN");
        //     System.out.println("====== SMTP TEST DISPATCH INITIATED ======");
        // } catch (Exception e) {
        //     System.err.println("SMTP Startup Test Exception:");
        //     e.printStackTrace();
        // }
    }
}
