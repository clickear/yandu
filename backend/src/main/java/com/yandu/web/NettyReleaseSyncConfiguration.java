package com.yandu.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yandu.release.ReleaseApplicationService;
import com.yandu.web.netty.NettyReleaseSyncServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "yandu.sync.netty", name = "enabled", havingValue = "true")
public class NettyReleaseSyncConfiguration {

    @Bean
    NettyReleaseSyncServer nettyReleaseSyncServer(
            ObjectMapper json, ReleaseChangeHub hub, ReleaseApplicationService releases,
            @Value("${yandu.sync.netty.port:9091}") int port) {
        return new NettyReleaseSyncServer(json, hub, releases, port);
    }
}
