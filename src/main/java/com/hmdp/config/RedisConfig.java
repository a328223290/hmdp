package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 配置Redisson客户端
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
        // 配置类
        Config config = new Config();
        // 添加redis地址，这里添加了单点的地址，也可以使用config.useClusterServers()添加集群地址。（暂时不考虑集群，没有虚拟机来部署。）
        config.useSingleServer().setAddress("redis://127.0.0.1:6379").setPassword("3582478gxk");
        // 创建客户端
        return Redisson.create(config);
    }
}
