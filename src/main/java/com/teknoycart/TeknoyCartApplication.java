package com.teknoycart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Autowired;
import com.teknoycart.services.EmailService;

import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
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

        // Initialize Supabase Storage Buckets
        try {
            jdbcTemplate.execute("INSERT INTO storage.buckets (id, name, public) VALUES ('product-images', 'product-images', true) ON CONFLICT (id) DO NOTHING");
            jdbcTemplate.execute("INSERT INTO storage.buckets (id, name, public) VALUES ('chat-images', 'chat-images', true) ON CONFLICT (id) DO NOTHING");
            System.out.println("====== Supabase storage buckets initialized successfully! ======");
        } catch (Exception e) {
            System.err.println("Could not initialize storage buckets: " + e.getMessage());
        }

        // Configure Storage Policies
        try {
            jdbcTemplate.execute(
                "DO $$\n" +
                "BEGIN\n" +
                "    IF NOT EXISTS (\n" +
                "        SELECT 1 FROM pg_policies \n" +
                "        WHERE tablename = 'objects' AND schemaname = 'storage' AND policyname = 'Public Read Access'\n" +
                "    ) THEN\n" +
                "        CREATE POLICY \"Public Read Access\" ON storage.objects FOR SELECT USING (true);\n" +
                "    END IF;\n" +
                "    \n" +
                "    IF NOT EXISTS (\n" +
                "        SELECT 1 FROM pg_policies \n" +
                "        WHERE tablename = 'objects' AND schemaname = 'storage' AND policyname = 'Authenticated Upload Access'\n" +
                "    ) THEN\n" +
                "        CREATE POLICY \"Authenticated Upload Access\" ON storage.objects FOR INSERT WITH CHECK (\n" +
                "            bucket_id IN ('product-images', 'chat-images')\n" +
                "        );\n" +
                "    END IF;\n" +
                "END\n" +
                "$$;"
            );
            System.out.println("====== Supabase storage policies configured successfully! ======");
        } catch (Exception e) {
            System.err.println("Could not configure storage policies: " + e.getMessage());
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
