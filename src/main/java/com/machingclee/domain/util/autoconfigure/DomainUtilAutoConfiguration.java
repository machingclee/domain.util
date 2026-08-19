package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.event.ExternalEventPublisher;
import com.machingclee.domain.util.common.event.SpringDomainEventDispatcher;
import com.machingclee.domain.util.common.factory.EntityFactoryService;
import com.machingclee.domain.util.common.factory.EntityGraphService;
import com.machingclee.domain.util.common.factory.ServiceGraphService;
import com.machingclee.domain.util.common.query.DefaultQueryInvoker;
import com.machingclee.domain.util.common.query.interfaces.QueryHandler;
import com.machingclee.domain.util.common.query.interfaces.QueryInvoker;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
@EnableConfigurationProperties(DomainUtilDocsProperties.class)
public class DomainUtilAutoConfiguration {

    @Bean
    public SpringDomainEventDispatcher springDomainEventDispatcher(ApplicationEventPublisher publisher) {
        return new SpringDomainEventDispatcher(publisher);
    }

    @Bean
    @ConditionalOnMissingBean(QueryInvoker.class)
    public DefaultQueryInvoker defaultQueryInvoker(List<QueryHandler<?, ?>> queryHandlers,
                                                   ApplicationContext context) {
        return new DefaultQueryInvoker(queryHandlers, context);
    }

    @Bean
    @ConditionalOnMissingBean
    public ExternalEventPublisher externalEventPublisher(ApplicationEventPublisher publisher) {
        return new ExternalEventPublisher(publisher);
    }

    @Bean
    public EntityFactoryService entityFactoryService(ApplicationContext context) {
        return new EntityFactoryService(context);
    }

    @Bean
    public EntityGraphService entityGraphService(ApplicationContext context) {
        return new EntityGraphService(context);
    }

    @Bean
    public ServiceGraphService serviceGraphService(ApplicationContext context) {
        return new ServiceGraphService(context);
    }
}
