package com.laser.exchange.resultpublisher.checkpoint;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan(basePackageClasses = ResultPublisherCheckpointMapper.class, markerInterface = BaseMapper.class)
public class ResultPublisherCheckpointPersistenceConfiguration {
}
