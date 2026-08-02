package com.machingclee.domain.util.common.factory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Resolves a JPA {@link EntityManager} for documentation scanners.
 * <p>
 * Spring Boot registers {@code entityManagerFactory}, not a bean named
 * {@code "entityManager"}. {@code @PersistenceContext} injection works via
 * {@code PersistenceAnnotationBeanPostProcessor}, but
 * {@code ApplicationContext#getBean("entityManager")} does not. This helper
 * tries, in order:
 * <ol>
 *   <li>bean named {@code "entityManager"} (apps that register one explicitly)</li>
 *   <li>unique {@link EntityManager} typed bean</li>
 *   <li>shared EM created from {@code entityManagerFactory} /
 *       {@link EntityManagerFactory}</li>
 * </ol>
 * Returns {@code null} when JPA is not on the classpath or not configured.
 */
final class EntityManagerAccess {

    private static final String ENTITY_MANAGER_BEAN_NAME = "entityManager";
    private static final String ENTITY_MANAGER_FACTORY_BEAN_NAME = "entityManagerFactory";

    private EntityManagerAccess() {
    }

    static EntityManager resolve(ApplicationContext context, Logger logger) {
        // 1) Explicit shared / named EM bean (optional consumer override)
        try {
            return context.getBean(ENTITY_MANAGER_BEAN_NAME, EntityManager.class);
        } catch (Exception ignored) {
            // not registered under the historical name — continue
        }

        // 2) Unique typed EntityManager bean (if the app registered one)
        try {
            return context.getBean(EntityManager.class);
        } catch (Exception ignored) {
            // zero or multiple — fall through to EMF
        }

        // 3) Build a transaction-aware shared proxy from EntityManagerFactory
        //    (what Spring Boot actually auto-configures)
        try {
            EntityManagerFactory emf = resolveEntityManagerFactory(context);
            return SharedEntityManagerCreator.createSharedEntityManager(emf);
        } catch (Exception e) {
            logger.debug("EntityManager not available: {}", e.getMessage());
            return null;
        }
    }

    private static EntityManagerFactory resolveEntityManagerFactory(ApplicationContext context) {
        try {
            return context.getBean(ENTITY_MANAGER_FACTORY_BEAN_NAME, EntityManagerFactory.class);
        } catch (Exception ignored) {
            return context.getBean(EntityManagerFactory.class);
        }
    }
}
