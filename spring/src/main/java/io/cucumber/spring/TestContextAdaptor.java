package io.cucumber.spring;

import io.cucumber.core.backend.CucumberBackendException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextManager;

import java.util.Collection;

import static io.cucumber.spring.CucumberTestContext.SCOPE_CUCUMBER_GLUE;

abstract class TestContextAdaptor {

    protected final ConfigurableApplicationContext applicationContext;
    private final Collection<Class<?>> glueClasses;

    protected TestContextAdaptor(ConfigurableApplicationContext applicationContext,
                                 Collection<Class<?>> glueClasses) {
        this.applicationContext = applicationContext;
        this.glueClasses = glueClasses;
    }

    static TestContextAdaptor createTestContextManagerAdaptor(Class<?> stepClassWithSpringContext,
                                                              Collection<Class<?>> glueClasses) {
        TestContextManager delegate = new TestContextManager(stepClassWithSpringContext);
        TestContext testContext = delegate.getTestContext();
        ConfigurableApplicationContext applicationContext =
            (ConfigurableApplicationContext) testContext.getApplicationContext();
        return new TestContextManagerAdaptor(delegate, applicationContext, glueClasses);
    }

    static TestContextAdaptor createFallbackApplicationContextAdaptor(Collection<Class<?>> glue) {
        ConfigurableApplicationContext applicationContext;
        if (FallbackApplicationContextAdaptor.class.getClassLoader().getResource("cucumber.xml") != null) {
            applicationContext = new ClassPathXmlApplicationContext("cucumber.xml");
        } else {
            applicationContext = new GenericApplicationContext();
        }
        return new FallbackApplicationContextAdaptor(applicationContext, glue);
    }

    abstract void start();

    abstract void stop();

    final <T> T getInstance(Class<T> type) {
        return applicationContext.getBean(type);
    }

    final void registerGlueCodeScope(ConfigurableApplicationContext context) {
        while (context != null) {
            context.getBeanFactory().registerScope(SCOPE_CUCUMBER_GLUE, new GlueCodeScope());
            context = (ConfigurableApplicationContext) context.getParent();
        }
    }

    final void registerStepClassBeanDefinitions(ConfigurableListableBeanFactory beanFactory) {
        for (Class<?> glue : glueClasses) {
            registerStepClassBeanDefinition(beanFactory, glue);
        }
    }

    private void registerStepClassBeanDefinition(ConfigurableListableBeanFactory beanFactory, Class<?> glueClass) {
        BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
        String beanName = glueClass.getName();
        // Step definition may have already been
        // registered as a bean by another thread.
        if (registry.containsBeanDefinition(beanName)) {
            return;
        }
        registry.registerBeanDefinition(beanName, BeanDefinitionBuilder
            .genericBeanDefinition(glueClass)
            .setScope(SCOPE_CUCUMBER_GLUE)
            .getBeanDefinition()
        );
    }

    private static final class TestContextManagerAdaptor extends TestContextAdaptor {
        private static final Object monitor = new Object();

        private final TestContextManager delegate;

        private TestContextManagerAdaptor(TestContextManager delegate,
                                          ConfigurableApplicationContext applicationContext,
                                          Collection<Class<?>> glueClasses) {
            super(applicationContext, glueClasses);
            this.delegate = delegate;
        }

        @Override
        public void start() {
            // The TestContextManager delegate makes the application context
            // available to other threads. Register the glue however requires
            // modifies the application context. To avoid concurrent modification
            // issues (#1823, #1153, #1148, #1106) we do this serially.
            synchronized (monitor) {
                registerGlueCodeScope(applicationContext);
                notifyContextManagerAboutTestClassStarted();
                registerStepClassBeanDefinitions(applicationContext.getBeanFactory());
            }
        }

        private void notifyContextManagerAboutTestClassStarted() {
            try {
                delegate.beforeTestClass();
            } catch (Exception e) {
                throw new CucumberBackendException(e.getMessage(), e);
            }
        }

        @Override
        public void stop() {
            try {
                delegate.afterTestClass();
            } catch (Exception e) {
                throw new CucumberBackendException(e.getMessage(), e);
            }
        }
    }

    private static final class FallbackApplicationContextAdaptor extends TestContextAdaptor {

        FallbackApplicationContextAdaptor(ConfigurableApplicationContext applicationContext,
                                          Collection<Class<?>> glueClasses) {
            super(applicationContext, glueClasses);
            applicationContext.registerShutdownHook();
        }

        @Override
        public void start() {
            registerGlueCodeScope(applicationContext);
            registerStepClassBeanDefinitions(applicationContext.getBeanFactory());
        }

        @Override
        public void stop() {

        }
    }
}
