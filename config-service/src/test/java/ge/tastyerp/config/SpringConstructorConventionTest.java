package ge.tastyerp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the 2026-08-15 outage: {@code TbcDbiClient} gained a
 * second (test-seam) constructor without {@code @Autowired}, Spring could not
 * pick one, and payment-service crash-looped on start-up — unnoticed because no
 * test boots the context. This scans every Spring-managed class on this
 * service's classpath (including {@code common}) and fails on the hazard
 * itself, cheaply and without Firebase.
 */
class SpringConstructorConventionTest {

    @Test
    void everyBeanHasAnUnambiguousConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class)); // includes @Service/@Repository/@Configuration/@RestController
        List<String> offenders = new ArrayList<>();
        scanner.findCandidateComponents("ge.tastyerp").forEach(bd -> {
            try {
                Class<?> type = Class.forName(bd.getBeanClassName());
                Constructor<?>[] ctors = type.getDeclaredConstructors();
                if (ctors.length <= 1) return;
                boolean annotated = false, noArg = false;
                for (Constructor<?> c : ctors) {
                    if (c.isAnnotationPresent(Autowired.class)) annotated = true;
                    if (c.getParameterCount() == 0) noArg = true;
                }
                if (!annotated && !noArg) {
                    offenders.add(type.getName() + " has " + ctors.length + " constructors, none @Autowired and no no-arg");
                }
            } catch (ClassNotFoundException e) {
                // not on this classpath — ignore
            }
        });
        assertTrue(offenders.isEmpty(), "Spring cannot choose a constructor for: " + offenders);
    }
}
