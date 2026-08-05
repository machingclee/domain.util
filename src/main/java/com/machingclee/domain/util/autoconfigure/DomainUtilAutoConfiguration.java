package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.event.ExternalEventPublisher;
import com.machingclee.domain.util.common.event.SpringDomainEventDispatcher;
import com.machingclee.domain.util.common.factory.EntityFactoryService;
import com.machingclee.domain.util.common.factory.EntityGraphService;
import com.machingclee.domain.util.common.query.DefaultQueryInvoker;
import com.machingclee.domain.util.common.query.interfaces.QueryHandler;
import com.machingclee.domain.util.common.query.interfaces.QueryInvoker;
import com.machingclee.domain.util.controller.DocController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

import java.util.List;

@AutoConfiguration
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
    @ConditionalOnBean(AbstractCommandInvoker.class)
    public DocController docController(List<AbstractCommandInvoker> commandInvokers,
                                       ObjectProvider<DefaultQueryInvoker> queryInvokerProvider,
                                       EntityFactoryService entityFactoryService,
                                       EntityGraphService entityGraphService) {
        return new DocController(commandInvokers, queryInvokerProvider.getIfAvailable(),
                entityFactoryService, entityGraphService);
    }
}
