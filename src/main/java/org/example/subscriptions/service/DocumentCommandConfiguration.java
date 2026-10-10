package org.example.subscriptions.service;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.example.subscriptions.model.Tariff;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import su.onno.metadata.CatalogDescriptor;
import su.onno.ui.CatalogCommandService;
import su.onno.ui.DocumentCommandService;

@Configuration
public class DocumentCommandConfiguration {
    @Bean
    public Clock businessClock() {
        return Clock.system(ZoneId.of("Europe/Moscow"));
    }

    @Bean
    public static BeanPostProcessor guardedDocumentCommands(ObjectProvider<DocumentOperations> operations) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof CatalogCommandService) {
                    ProxyFactory catalogProxy = new ProxyFactory(bean);
                    catalogProxy.setProxyTargetClass(true);
                    catalogProxy.addAdvice((MethodInterceptor) call -> {
                        var args = call.getArguments();
                        if (args.length > 1 && args[0] instanceof CatalogDescriptor descriptor && descriptor.javaClass() == Tariff.class
                                && java.util.Set.of("create", "update", "validate").contains(call.getMethod().getName())) {
                            var body = (Map<?, ?>) args[args.length - 2];
                            Object days = body.containsKey("periodDays") ? body.get("periodDays") : body.get("period_days");
                            operations.getObject().checkTariffDays(descriptor, days, (su.onno.access.AccessSubject) args[args.length - 1],
                                    call.getMethod().getName());
                        }
                        return call.proceed();
                    });
                    return catalogProxy.getProxy();
                }
                if (!(bean instanceof DocumentCommandService target)) return bean;
                ProxyFactory proxy = new ProxyFactory(target);
                proxy.setProxyTargetClass(true);
                proxy.addAdvice((MethodInterceptor) call -> operations.getObject().intercept(target, call));
                return proxy.getProxy();
            }
        };
    }
}
