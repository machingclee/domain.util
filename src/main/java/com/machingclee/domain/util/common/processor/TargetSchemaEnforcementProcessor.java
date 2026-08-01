package com.machingclee.domain.util.common.processor;


import com.machingclee.domain.util.schema.TargetSchema;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Annotation processor that enforces every concrete CommandHandler
 * implementation
 * to be annotated with @TargetSchema.
 * <p>
 * Runs during javac — violations are reported as compile errors, not runtime
 * exceptions.
 * <p>
 * Registered via META-INF/services/javax.annotation.processing.Processor.
 */
@SupportedAnnotationTypes("*")
public class TargetSchemaEnforcementProcessor extends AbstractProcessor {

    /**
     * Track the running compiler's latest source version. Done as a method override
     * (not @SupportedSourceVersion) because {@link SourceVersion#latestSupported()}
     * is not a compile-time constant, and so that consumers compiling at a newer
     * source level than this module's own --source do not get a "supported source
     * version 'RELEASE_N' less than '<higher>'" warning.
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // 取得 CommandHandler 的 TypeElement
        TypeElement commandHandlerType = processingEnv.getElementUtils()
                .getTypeElement("com.machingclee.domain.util.common.interfaces.CommandHandler");

        if (commandHandlerType == null) {
            // CommandHandler not yet on classpath in this round, skip
            return false;
        }

        Types typeUtils = processingEnv.getTypeUtils();

        // 掃描所有被處理的類，找出實作 CommandHandler 的非抽象類
        for (Element element : roundEnv.getRootElements()) {
            if (element.getKind() != ElementKind.CLASS)
                continue;

            TypeElement classElement = (TypeElement) element;

            // 跳過抽象類和介面
            if (classElement.getModifiers().contains(Modifier.ABSTRACT))
                continue;
            if (classElement.getKind() == ElementKind.INTERFACE)
                continue;

            // 檢查是否實作了 CommandHandler
            boolean implementsCommandHandler = classElement.getInterfaces().stream()
                    .anyMatch(iface -> {
                        TypeMirror erasure = typeUtils.erasure(iface);
                        TypeMirror commandHandlerErasure = typeUtils.erasure(commandHandlerType.asType());
                        return typeUtils.isSameType(erasure, commandHandlerErasure);
                    });

            if (!implementsCommandHandler)
                continue;

            // 若實作了 CommandHandler 但沒有 @TargetSchema，報告 compile error
            TargetSchema targetSchema = classElement.getAnnotation(TargetSchema.class);
            if (targetSchema == null) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        classElement.getSimpleName()
                                + " implements CommandHandler but is missing @TargetSchema."
                                + " Add @TargetSchema(<YourSchema>.class), e.g."
                                + " @TargetSchema(SalesSchema.class).",
                        classElement);
            }
        }

        return false;
    }
}
