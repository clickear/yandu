package com.yandu.sync.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.sync.ReleaseSyncService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(prefix = "yandu.sync.netty", name = "enabled", havingValue = "true")
public class NettyReleaseSyncAutoConfiguration {

    @Bean
    NettyReleaseSyncServer nettyReleaseSyncServer(ObjectMapper json, ReleaseSyncService releases,
                                                   @Value("${yandu.sync.netty.port:9091}") int port) {
        return new NettyReleaseSyncServer(json, releases, port);
    }
}
