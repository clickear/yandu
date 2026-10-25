package com.yandu;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.util.function.LongSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class YanduApplication {

    @Bean
    LongSupplier snowflakeIds() {
        return IdWorker::getId;
    }

    public static void main(String[] args) {
        SpringApplication.run(YanduApplication.class, args);
    }
}
