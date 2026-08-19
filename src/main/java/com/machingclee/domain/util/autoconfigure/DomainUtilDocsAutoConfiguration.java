package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.factory.EntityFactoryService;
import com.machingclee.domain.util.common.factory.EntityGraphService;
import com.machingclee.domain.util.common.factory.ServiceGraphService;
import com.machingclee.domain.util.common.query.DefaultQueryInvoker;
import com.machingclee.domain.util.controller.DocController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Registers {@link DocController} after write-path glue has been created.
 * <p>
 * {@link DomainUtilAutoConfiguration} runs first (graph services).
 * {@link DomainUtilAuditAutoConfiguration} then creates
 * {@code CustomCommandInvoker}. {@code @ConditionalOnBean(AbstractCommandInvoker)}
 * on a bean in the first class cannot see that later invoker, so docs must live
 * in their own auto-config that is ordered after both.
 * <p>
 * A user-declared {@link AbstractCommandInvoker} is also enough — user beans
 * exist before auto-configuration runs.
 */
@AutoConfiguration(after = {
        DomainUtilAutoConfiguration.class,
        DomainUtilAuditAutoConfiguration.class
})
@ConditionalOnBean(AbstractCommandInvoker.class)
public class DomainUtilDocsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DocController docController(List<AbstractCommandInvoker> commandInvokers,
            ObjectProvider<DefaultQueryInvoker> queryInvokerProvider,
            EntityFactoryService entityFactoryService,
            EntityGraphService entityGraphService,
            ServiceGraphService serviceGraphService) {
        return new DocController(commandInvokers, queryInvokerProvider.getIfAvailable(),
                entityFactoryService, entityGraphService, serviceGraphService);
    }
}
