package com.machingclee.domain.util.common.bytecodescanner;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRoleExtractorTest {

    enum SampleRole {
        ADMINISTRATOR, BASIC_MEMBER
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface RequiresRole {
        SampleRole[] role() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface StringRoles {
        String[] value() default {};
    }

    static class ClassLevelController {
        @RequiresRole(role = {SampleRole.ADMINISTRATOR})
        public void methodOverride() {
        }

        public void inheritFromClass() {
        }
    }

    @RequiresRole(role = {SampleRole.BASIC_MEMBER})
    static class ClassAnnotatedController {
        public void inheritFromClass() {
        }

        @RequiresRole(role = {SampleRole.ADMINISTRATOR})
        public void methodOverride() {
        }
    }

    static class StringRoleController {
        @StringRoles({"ADMIN", "STAFF"})
        public void listed() {
        }
    }

    @Test
    void emptyAnnotationClassDisablesScanning() throws Exception {
        Method method = ClassLevelController.class.getMethod("methodOverride");
        List<String> roles = AuthRoleExtractor.extract(
                method, ClassLevelController.class, AuthRoleAnnotationConfig.disabled());
        assertTrue(roles.isEmpty());
    }

    @Test
    void readsEnumArrayFromMethodAnnotation() throws Exception {
        Method method = ClassLevelController.class.getMethod("methodOverride");
        AuthRoleAnnotationConfig config = new AuthRoleAnnotationConfig(
                RequiresRole.class.getName(), "role");
        assertEquals(List.of("ADMINISTRATOR"),
                AuthRoleExtractor.extract(method, ClassLevelController.class, config));
    }

    @Test
    void methodAnnotationWinsOverClass() throws Exception {
        Method method = ClassAnnotatedController.class.getMethod("methodOverride");
        AuthRoleAnnotationConfig config = new AuthRoleAnnotationConfig(
                RequiresRole.class.getName(), "role");
        assertEquals(List.of("ADMINISTRATOR"),
                AuthRoleExtractor.extract(method, ClassAnnotatedController.class, config));
    }

    @Test
    void fallsBackToClassAnnotation() throws Exception {
        Method method = ClassAnnotatedController.class.getMethod("inheritFromClass");
        AuthRoleAnnotationConfig config = new AuthRoleAnnotationConfig(
                RequiresRole.class.getName(), "role");
        assertEquals(List.of("BASIC_MEMBER"),
                AuthRoleExtractor.extract(method, ClassAnnotatedController.class, config));
    }

    @Test
    void readsStringArrayFromCustomAttribute() throws Exception {
        Method method = StringRoleController.class.getMethod("listed");
        AuthRoleAnnotationConfig config = new AuthRoleAnnotationConfig(
                StringRoles.class.getName(), "value");
        assertEquals(List.of("ADMIN", "STAFF"),
                AuthRoleExtractor.extract(method, StringRoleController.class, config));
    }

    @Test
    void missingAnnotationTypeYieldsEmpty() throws Exception {
        Method method = ClassLevelController.class.getMethod("methodOverride");
        AuthRoleAnnotationConfig config = new AuthRoleAnnotationConfig(
                "com.example.DoesNotExist", "role");
        assertTrue(AuthRoleExtractor.extract(method, ClassLevelController.class, config).isEmpty());
    }

    @Test
    void toRoleNamesAcceptsSingleEnumAndCollection() {
        assertEquals(List.of("ADMINISTRATOR"),
                AuthRoleExtractor.toRoleNames(SampleRole.ADMINISTRATOR));
        assertEquals(List.of("ADMINISTRATOR", "BASIC_MEMBER"),
                AuthRoleExtractor.toRoleNames(List.of(SampleRole.ADMINISTRATOR, SampleRole.BASIC_MEMBER)));
        assertEquals(List.of(), AuthRoleExtractor.toRoleNames(new SampleRole[0]));
    }
}
